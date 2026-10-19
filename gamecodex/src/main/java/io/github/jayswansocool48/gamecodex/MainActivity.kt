package io.github.jayswansocool48.gamecodex

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Runs Game Codex from inside the APK.
 *
 * The page is served from a private https address (appassets.androidplatform.net never reaches the
 * internet). window.AndroidCodex connects it to Storage, which saves games as files in the app's
 * private folder, just like the desktop app. The network is used only for GitHub: signing in,
 * syncing games to the user's own private repository, and checking for updates.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Storage
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newFixedThreadPool(4)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: ((java.io.OutputStream) -> Unit)? = null
    private var pendingExportName = ""
    private var pendingPick: JSONObject? = null
    private var pendingImportTarget: String? = null
    private var update: JSONObject? = null
    private val ai by lazy { Assistant(File(filesDir, "game-codex-config/assistant.json")) }
    private val aiStop = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Storage(filesDir)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this)
        web.setBackgroundColor(0x00000000)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
            setSupportZoom(false)
        }
        web.addJavascriptInterface(Bridge(), "AndroidCodex")
        web.webViewClient = AssetClient()
        web.webChromeClient = Chrome()
        setContentView(web)
        web.loadUrl(START_URL)
        handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        work.shutdown()
        super.onDestroy()
    }

    /** Back closes the open dialog, sketch or menu first, then steps back towards the library. */
    @Deprecated("Still the simplest way to handle Back across Android 5 to 14")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__gameCodexBack ? window.__gameCodexBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    // ---------- talking to the page ----------

    private fun send(payload: JSONObject) {
        val script = "window.__gameCodexNative && window.__gameCodexNative(" + payload + ");"
        main.post { web.evaluateJavascript(script, null) }
    }

    private fun reply(msg: JSONObject, fields: JSONObject = JSONObject()) {
        fields.put("type", "reply").put("rid", msg.optString("rid"))
        send(fields)
    }

    private fun replyError(msg: JSONObject, text: String) = reply(msg, JSONObject().put("error", text))

    @Suppress("DEPRECATION")
    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "dev" } catch (e: Exception) { "dev" }

    @Suppress("DEPRECATION")
    private fun versionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) { 0L }

    // The GitHub sign-in token, kept in the app's private storage.
    private val auth by lazy { getSharedPreferences("github", MODE_PRIVATE) }
    private fun token(): String? = auth.getString("token", null)

    /** Called from the page's JavaScript, on a background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun boot(): String = store.boot(versionName(), "https://$HOST/media/")
            .put("platform", "android")
            .put("signedIn", token() != null)
            .put("features", JSONArray().put("assistant"))
            .toString()

        @JavascriptInterface
        fun post(message: String) {
            val msg = try { JSONObject(message) } catch (e: Exception) { return }
            try {
                handle(msg)
            } catch (e: Exception) {
                // never let a bad message close the app
                android.util.Log.w("GameCodex", "Couldn't handle a message", e)
                if (msg.has("rid")) replyError(msg, e.message ?: "Something went wrong.")
            }
        }
    }

    /** Run slow work (network, big files) off the bridge thread and reply when it's done. */
    private fun background(msg: JSONObject, fn: () -> JSONObject) {
        work.execute {
            try { reply(msg, fn()) } catch (e: Exception) { replyError(msg, e.message ?: "Something went wrong.") }
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "save-project" -> store.saveProject(msg.optJSONObject("project"))
            "delete-project" -> store.deleteProject(msg.optString("id"))
            "save-settings" -> msg.optJSONObject("settings")?.let { store.saveSettings(it) }
            "save-media" -> store.saveMedia(msg.optString("id"), msg.optString("data"))
            "copy-media" -> store.copyMedia(msg.optString("from"), msg.optString("to"))
            "delete-media" -> store.deleteMedia(msg.optJSONArray("ids"))
            "media-exists" -> reply(msg, JSONObject().put("missing", store.missingMedia(msg.optJSONArray("ids"))))
            "pick-files" -> main.post { pickFiles(msg) }
            "export" -> {
                val project = msg.optJSONObject("project") ?: return
                val markdown = msg.optString("markdown")
                main.post { askWhereToSave(fileBase(msg) + ".gamecodex", "application/zip") { store.exportZip(project, markdown, it) } }
            }
            "export-markdown" -> {
                val bytes = msg.optString("markdown").toByteArray()
                main.post { askWhereToSave(fileBase(msg) + ".md", "text/markdown") { it.write(bytes) } }
            }
            "export-kit" -> {
                val files = msg.optJSONArray("files")
                val media = msg.optJSONArray("media")
                main.post { askWhereToSave(fileBase(msg) + ".zip", "application/zip") { store.exportKit(files, media, it) } }
            }
            "import" -> {
                pendingImportTarget = msg.optString("target").takeIf { it.isNotEmpty() && it != "null" }
                main.post { pickImport() }
            }
            "delete-data" -> {
                store.deleteAll()
                auth.edit().clear().apply()
                ai.forget()
                send(JSONObject().put("type", "data-deleted"))
            }
            "http" -> background(msg) {
                val url = msg.optString("url")
                if (!Net.pageAllowed(url)) return@background JSONObject().put("status", 0).put("body", "Only GitHub can be reached.")
                val headers = HashMap<String, String>()
                msg.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
                val body = if (msg.isNull("body")) null else msg.optString("body").toByteArray()
                val r = Net.request(url, msg.optString("method", "GET"), headers, body, if (msg.optBoolean("auth")) token() else null, userAgent = "GameCodex/" + versionName())
                JSONObject().put("status", r.status).put("body", r.body)
            }
            "token-set" -> { auth.edit().putString("token", msg.optString("token").trim()).apply(); reply(msg) }
            "token-clear" -> { auth.edit().remove("token").apply(); reply(msg) }
            "gh-media-up" -> background(msg) { uploadMedia(msg) }
            "gh-media-down" -> background(msg) {
                val file = store.mediaFile(msg.optString("mediaId")) ?: return@background JSONObject().put("status", 0)
                val url = msg.optString("url")
                if (!Net.pageAllowed(url)) return@background JSONObject().put("status", 0)
                val r = Net.request(url, headers = mapOf("Accept" to "application/vnd.github.raw+json"), token = token(), toFile = file, timeoutMs = 600_000, userAgent = "GameCodex/" + versionName())
                JSONObject().put("status", r.status)
            }
            "ai-get" -> reply(msg, JSONObject().put("settings", ai.public()))
            "ai-set" -> reply(msg, JSONObject().put("settings", ai.update(msg.optJSONObject("settings"))))
            "ai-models" -> background(msg) {
                JSONObject().put("models", ai.models(msg.optString("provider"), msg.optString("key"), msg.optString("url")))
            }
            "ai-chat" -> work.execute { aiChat(msg) }
            "ai-stop" -> aiStop.add(msg.optString("chat"))
            "kiln-scan", "kiln-pick", "kiln-import" -> replyError(msg, "Importing from another app works in Game Codex on your computer.")
            "check-update" -> work.execute { checkUpdate() }
            "install-update" -> work.execute { downloadUpdate() }
            "restart" -> main.post { web.reload() }
            "uninstall" -> main.post { uninstall() }
            "uninstall-finish" -> main.post { finish() }
        }
    }

    private fun aiChat(msg: JSONObject) {
        val chat = msg.optString("chat")
        try {
            ai.chat(msg.optJSONArray("messages"), msg.optJSONObject("context"),
                emit = { t -> send(JSONObject().put("type", "ai-chunk").put("chat", chat).put("t", t)) },
                stopped = { aiStop.contains(chat) })
            reply(msg, JSONObject().put("done", true))
        } catch (e: Assistant.Stopped) {
            reply(msg, JSONObject().put("stopped", true))
        } catch (e: Assistant.AIError) {
            replyError(msg, e.message ?: "The assistant didn't answer.")
        } catch (e: Exception) {
            replyError(msg, "Something went wrong talking to the assistant: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            aiStop.remove(chat)
        }
    }

    private fun uploadMedia(msg: JSONObject): JSONObject {
        val file = store.mediaFile(msg.optString("mediaId"))
        if (file == null || !file.exists()) return JSONObject().put("status", 0).put("missing", true)
        if (file.length() > 74L * 1024 * 1024) return JSONObject().put("status", 413).put("tooBig", true)
        val url = msg.optString("url")
        if (!Net.pageAllowed(url)) return JSONObject().put("status", 0)
        val content = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().put("message", msg.optString("message", "Add a file")).put("content", content).toString().toByteArray()
        val r = Net.request(url, "PUT", mapOf("Accept" to "application/vnd.github+json", "Content-Type" to "application/json"), body, token(), timeoutMs = 600_000, userAgent = "GameCodex/" + versionName())
        return JSONObject().put("status", r.status).put("body", r.body.take(2000))
    }

    private fun fileBase(msg: JSONObject): String =
        msg.optString("name", "game").lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "game" }

    // ---------- files ----------

    private fun askWhereToSave(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        pendingExport = write
        pendingExportName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        startForResult(intent, REQ_EXPORT) { send(JSONObject().put("type", "exported").put("error", "Couldn't open the file picker to save it.")) }
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startForResult(intent, REQ_IMPORT) { send(JSONObject().put("type", "imported").put("error", "Couldn't open the file picker.")) }
    }

    private fun pickFiles(msg: JSONObject) {
        pendingPick?.let { reply(it, JSONObject().put("files", JSONArray())) }
        pendingPick = msg
        val types = when (msg.optString("accept")) {
            "model" -> arrayOf("model/*", "application/octet-stream", "application/sla", "text/plain", "*/*")
            "audio" -> arrayOf("audio/*")
            "video" -> arrayOf("video/*", "audio/*")
            else -> arrayOf("video/*", "audio/*", "image/*")
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (types.size == 1) types[0] else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, types)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, msg.optBoolean("multiple"))
        startForResult(intent, REQ_PICK) { pendingPick = null; replyError(msg, "Couldn't open the file picker.") }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) { /* fall back below */ }
        val mime = contentResolver.getType(uri) ?: ""
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "file.$ext"
    }

    private fun startForResult(intent: Intent, code: Int, onFail: () -> Unit) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            onFail()
        }
    }

    private fun uninstall() {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        startForResult(intent, REQ_UNINSTALL) {
            send(JSONObject().put("type", "uninstall").put("status", "error")
                .put("text", "Couldn't open the uninstaller. Long-press Game Codex in your app list and choose Uninstall."))
        }
    }

    @Deprecated("startActivityForResult works back to Android 5 without extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            else data.data?.let { uris.add(it) }
        }
        when (requestCode) {
            REQ_FILES -> {
                val callback = fileCallback ?: return
                fileCallback = null
                callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            REQ_PICK -> {
                val msg = pendingPick ?: return
                pendingPick = null
                work.execute {
                    val files = JSONArray()
                    for (uri in uris) {
                        try {
                            val name = displayName(uri)
                            contentResolver.openInputStream(uri)?.use { input -> store.addMediaFile(name, input)?.let { files.put(it) } }
                        } catch (e: Exception) { android.util.Log.w("GameCodex", "Couldn't copy a picked file", e) }
                    }
                    reply(msg, JSONObject().put("files", files))
                }
            }
            REQ_EXPORT -> {
                val write = pendingExport
                val name = pendingExportName
                pendingExport = null
                val uri = uris.firstOrNull()
                if (write == null || uri == null) return
                work.execute {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.use { write(it) } ?: throw IOException("no stream")
                        send(JSONObject().put("type", "exported").put("file", name))
                    } catch (e: Exception) {
                        send(JSONObject().put("type", "exported").put("error", "Couldn't save the file there. Try another folder."))
                    }
                }
            }
            REQ_IMPORT -> {
                val uri = uris.firstOrNull() ?: return
                val target = pendingImportTarget
                work.execute {
                    val out = JSONObject().put("type", "imported").put("target", target ?: JSONObject.NULL)
                    try {
                        val project = contentResolver.openInputStream(uri)?.use { store.importStream(it) } ?: throw IOException("no stream")
                        out.put("project", project)
                    } catch (e: IllegalArgumentException) {
                        out.put("error", e.message)
                    } catch (e: Exception) {
                        out.put("error", "Couldn't open that file.")
                    }
                    send(out)
                }
            }
            REQ_UNINSTALL -> {
                // When the uninstall goes through, Android closes the app before this runs.
                if (resultCode != RESULT_OK) {
                    send(JSONObject().put("type", "uninstall").put("status", "error").put("text", "Uninstall was cancelled. Nothing was removed."))
                }
            }
        }
    }

    // ---------- updates (from the Fire-7-8-apks repository) ----------

    private fun checkUpdate() {
        // raw.githubusercontent.com first; GitHub's API as a fallback (it serves the same file)
        var r = Net.request(UPDATE_INFO, headers = mapOf("Cache-Control" to "no-cache"), anyGithubHost = true, userAgent = "GameCodex/" + versionName())
        if (r.status != 200) r = Net.request(UPDATE_INFO_API, headers = mapOf("Accept" to "application/vnd.github.raw+json"), userAgent = "GameCodex/" + versionName())
        val info = try { JSONObject(r.body) } catch (e: Exception) { null }
        if (r.status != 200 || info == null) {
            val text = when (r.status) {
                0 -> "Couldn't reach GitHub to check for updates. Check your internet connection."
                404 -> "There's no update information on GitHub yet. Try again in a few minutes."
                else -> "GitHub didn't answer the update check (" + r.status + "). Try again later."
            }
            send(JSONObject().put("type", "update").put("status", "error").put("text", text))
            return
        }
        val latest = info.optString("versionName")
        if (info.optLong("versionCode") <= versionCode()) {
            send(JSONObject().put("type", "update").put("status", "current").put("current", versionName()).put("latest", latest))
            return
        }
        update = info
        send(JSONObject().put("type", "update").put("status", "available").put("current", versionName()).put("latest", latest).put("canInstall", true))
    }

    private fun downloadUpdate() {
        val info = update ?: run { checkUpdate(); update } ?: return
        val latest = info.optString("versionName")
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            send(JSONObject().put("type", "update").put("status", "error").put("text", "Allow Game Codex to install updates (Android opens that setting now), then tap Update again."))
            main.post {
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: ActivityNotFoundException) { /* ignore */ }
            }
            return
        }
        send(JSONObject().put("type", "update").put("status", "downloading").put("latest", latest))
        val apk = File(cacheDir, "GameCodex-update.apk")
        var r = Net.request(info.optString("apk", UPDATE_APK), anyGithubHost = true, toFile = apk, timeoutMs = 600_000, userAgent = "GameCodex/" + versionName())
        if (r.status != 200) r = Net.request(UPDATE_APK_API, headers = mapOf("Accept" to "application/vnd.github.raw+json"), toFile = apk, timeoutMs = 600_000, userAgent = "GameCodex/" + versionName())
        if (r.status != 200 || !apk.exists()) {
            send(JSONObject().put("type", "update").put("status", "error").put("text", "The download failed. Check your connection and try again."))
            return
        }
        send(JSONObject().put("type", "update").put("status", "installing").put("latest", latest).put("text", "Tap Update when Android asks. Game Codex closes to finish, then open it again."))
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val status = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getActivity(this, 7, status, flags).intentSender)
            }
        } catch (e: Exception) {
            send(JSONObject().put("type", "update").put("status", "error").put("text", "The update couldn't be installed: " + (e.message ?: "unknown error")))
        }
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) try { startActivity(confirm) } catch (e: Exception) { /* ignore */ }
            }
            PackageInstaller.STATUS_SUCCESS -> send(JSONObject().put("type", "update").put("status", "installed").put("latest", versionName()).put("restart", false))
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val text = if (why.contains("signatures", true) || why.contains("INCOMPATIBLE", true))
                    "This update is signed differently from the Game Codex on this tablet. Uninstall Game Codex once (sign in first so your games are kept), then install the new APK."
                else "The update wasn't installed. $why".trim()
                send(JSONObject().put("type", "update").put("status", "error").put("text", text))
            }
        }
    }

    // ---------- web view ----------

    private inner class Chrome : WebChromeClient() {
        /** Lets "Add images" and the cover picker open the tablet's gallery and files. */
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(pick, "Choose images"), REQ_FILES)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    /** Limits a stream to a byte range, for video seeking. */
    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private inner class AssetClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            var path = url.path ?: "/"
            if (path.isEmpty() || path.endsWith("/")) path += "index.html"
            return try {
                when {
                    path.startsWith("/media/") -> media(path.removePrefix("/media/"), request.requestHeaders)
                    path == "/index.html" -> {
                        // Load the bridge before the app's own scripts.
                        val html = assets.open("web/index.html").use { String(it.readBytes()) }
                        val i = html.indexOf("<script")
                        val withBridge = if (i < 0) html else
                            html.substring(0, i) + "<script src=\"android-bridge.js\"></script>\n  " + html.substring(i)
                        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(withBridge.toByteArray()))
                    }
                    else -> {
                        val mime = mimeFor(path)
                        val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null
                        WebResourceResponse(mime, encoding, assets.open("web$path"))
                    }
                }
            } catch (e: IOException) {
                notFound()
            }
        }

        /** Saved files, with byte ranges so videos can be scrubbed. */
        private fun media(id: String, headers: Map<String, String>?): WebResourceResponse {
            val file = store.mediaFile(id)
            if (file == null || !file.exists()) return notFound()
            val mime = mimeFor(file.name)
            val size = file.length()
            val range = headers?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null && size > 0) {
                val start = m.groupValues[1].toLongOrNull() ?: 0L
                val end = (m.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                if (start <= end) {
                    val input = FileInputStream(file)
                    var skipped = 0L
                    while (skipped < start) { val s = input.skip(start - skipped); if (s <= 0) break; skipped += s }
                    val len = end - start + 1
                    return WebResourceResponse(mime, null, 206, "Partial Content",
                        mapOf("Content-Range" to "bytes $start-$end/$size", "Content-Length" to len.toString(), "Accept-Ranges" to "bytes"),
                        Limited(input, len))
                }
            }
            return WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Length" to size.toString(), "Accept-Ranges" to "bytes"), FileInputStream(file))
        }

        @Deprecated("Needed for Android 7 and older")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.host == HOST) return false
            return when (uri.scheme) {
                "http", "https", "mailto" -> {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: ActivityNotFoundException) { /* no browser */ }
                    true
                }
                else -> true
            }
        }

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        else -> "application/octet-stream"
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
        private const val UPDATE_INFO = "https://raw.githubusercontent.com/Jayswansocool48/Fire-7-8-apks/main/GameCodex.json"
        private const val UPDATE_APK = "https://raw.githubusercontent.com/Jayswansocool48/Fire-7-8-apks/main/GameCodex.apk"
        private const val UPDATE_INFO_API = "https://api.github.com/repos/Jayswansocool48/Fire-7-8-apks/contents/GameCodex.json"
        private const val UPDATE_APK_API = "https://api.github.com/repos/Jayswansocool48/Fire-7-8-apks/contents/GameCodex.apk"
        private const val ACTION_INSTALL_STATUS = "io.github.jayswansocool48.gamecodex.INSTALL_STATUS"
        private const val REQ_FILES = 1
        private const val REQ_EXPORT = 2
        private const val REQ_IMPORT = 3
        private const val REQ_UNINSTALL = 4
        private const val REQ_PICK = 5
    }
}
