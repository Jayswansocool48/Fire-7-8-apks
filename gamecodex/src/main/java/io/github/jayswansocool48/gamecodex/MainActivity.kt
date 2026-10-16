package io.github.jayswansocool48.gamecodex

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException

/**
 * Runs Game Codex from inside the APK, fully offline.
 *
 * The page is served from a private https address (appassets.androidplatform.net never
 * reaches the internet). window.AndroidCodex connects it to Storage, which saves games as
 * files in the app's private folder, just like the desktop app does.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Storage
    private val main = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: ByteArray? = null
    private var pendingExportName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Storage(filesDir)

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
        super.onDestroy()
    }

    /** Back closes the open dialog, sketch or menu first; it only leaves the app from the main screen. */
    @Deprecated("Still the simplest way to handle Back across Android 5 to 14")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__gameCodexBack ? window.__gameCodexBack() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    // ---------- talking to the page ----------

    private fun send(payload: JSONObject) {
        val script = "window.__gameCodexNative && window.__gameCodexNative(" + payload + ");"
        main.post { web.evaluateJavascript(script, null) }
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
    } catch (e: Exception) {
        "dev"
    }

    /** Called from the page's JavaScript, on a background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun boot(): String = store.boot(versionName(), "https://$HOST/media/").toString()

        @JavascriptInterface
        fun post(message: String) {
            try {
                handle(JSONObject(message))
            } catch (e: Exception) {
                // never let a bad message close the app
                android.util.Log.w("GameCodex", "Couldn't handle a message", e)
            }
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
            "export" -> {
                val project = msg.optJSONObject("project") ?: return
                val bytes = store.exportZip(project, msg.optString("markdown"))
                main.post { askWhereToSave(bytes, fileBase(msg) + ".gamecodex", "application/zip") }
            }
            "export-markdown" -> {
                val bytes = msg.optString("markdown").toByteArray()
                main.post { askWhereToSave(bytes, fileBase(msg) + ".md", "text/markdown") }
            }
            "import" -> main.post { pickImport() }
            "delete-data" -> {
                store.deleteAll()
                send(JSONObject().put("type", "data-deleted"))
            }
            "check-update" -> send(
                JSONObject().put("type", "update").put("status", "error").put(
                    "text",
                    "To update, download the newest GameCodex.apk from your Fire-7-8-apks repo on GitHub " +
                        "and install it over this one. Your games are kept.",
                ),
            )
            "restart" -> main.post { web.reload() }
            "uninstall" -> main.post { uninstall() }
            "uninstall-finish" -> main.post { finish() }
        }
    }

    private fun fileBase(msg: JSONObject): String =
        msg.optString("name", "game").lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "game" }

    // ---------- files ----------

    private fun askWhereToSave(bytes: ByteArray, name: String, mime: String) {
        pendingExport = bytes
        pendingExportName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        startForResult(intent, REQ_EXPORT, "Couldn't open the file picker to save the export.")
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startForResult(intent, REQ_IMPORT, "Couldn't open the file picker.")
    }

    private fun startForResult(intent: Intent, code: Int, failText: String) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            send(JSONObject().put("type", "exported").put("error", failText))
        }
    }

    private fun uninstall() {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_UNINSTALL)
        } catch (e: ActivityNotFoundException) {
            send(
                JSONObject().put("type", "uninstall").put("status", "error")
                    .put("text", "Couldn't open the uninstaller. Long-press Game Codex in your app list and choose Uninstall."),
            )
        }
    }

    @Deprecated("startActivityForResult works back to Android 5 without extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILES -> {
                val callback = fileCallback ?: return
                fileCallback = null
                val uris = ArrayList<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                    else data.data?.let { uris.add(it) }
                }
                callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            REQ_EXPORT -> {
                val bytes = pendingExport
                val name = pendingExportName
                pendingExport = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null || bytes == null) return
                Thread {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("no stream")
                        send(JSONObject().put("type", "exported").put("file", name))
                    } catch (e: Exception) {
                        send(JSONObject().put("type", "exported").put("error", "Couldn't save the file there. Try another folder."))
                    }
                }.start()
            }
            REQ_IMPORT -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) return
                Thread {
                    val reply = JSONObject().put("type", "imported")
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("no stream")
                        reply.put("project", store.importBytes(bytes))
                    } catch (e: IllegalArgumentException) {
                        reply.put("error", e.message)
                    } catch (e: Exception) {
                        reply.put("error", "Couldn't open that file.")
                    }
                    send(reply)
                }.start()
            }
            REQ_UNINSTALL -> {
                // When the uninstall goes through, Android closes the app before this runs.
                if (resultCode != RESULT_OK) {
                    send(
                        JSONObject().put("type", "uninstall").put("status", "error")
                            .put("text", "Uninstall was cancelled. Nothing was removed."),
                    )
                }
            }
        }
    }

    // ---------- web view ----------

    private inner class Chrome : WebChromeClient() {
        /** Lets "Add images" and the cover picker open the tablet's gallery and files. */
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val pick = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
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

    private inner class AssetClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            var path = url.path ?: "/"
            if (path.isEmpty() || path.endsWith("/")) path += "index.html"
            return try {
                when {
                    path.startsWith("/media/") -> {
                        val file = store.mediaFile(path.removePrefix("/media/"))
                        if (file == null || !file.exists()) notFound()
                        else WebResourceResponse(mimeFor(file.name), null, FileInputStream(file))
                    }
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

        @Deprecated("Needed for Android 7 and older")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.host == HOST) return false
            return when (uri.scheme) {
                "http", "https", "mailto" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed; ignore.
                    }
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
        else -> "text/plain"
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
        private const val REQ_FILES = 1
        private const val REQ_EXPORT = 2
        private const val REQ_IMPORT = 3
        private const val REQ_UNINSTALL = 4
    }
}
