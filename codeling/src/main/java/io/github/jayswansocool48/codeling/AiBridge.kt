package io.github.jayswansocool48.codeling

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The free AI tutor for the tablet app. The web app talks to it through
 * window.CodelingAndroid.post(json); replies go back through window.__nativeMessage.
 *
 * Two places the AI can run:
 *  - "Your computer": Ollama on the user's PC, reached over Wi-Fi (fast, smart).
 *  - "This tablet": a small model run by llama.cpp inside the app (slow, works offline).
 * The model named "tablet" is the tablet one; any other name is a model on the PC.
 * If the PC can't be reached and the tablet model is downloaded, answers come from the tablet.
 */
class AiBridge(private val activity: Activity, private val web: WebView) {
    private val prefs = activity.getSharedPreferences("codeling-ai", Activity.MODE_PRIVATE)
    private val io = Executors.newCachedThreadPool()
    private val brain = Executors.newSingleThreadExecutor() // the tablet model runs one answer at a time
    private val engine = LlamaEngine()
    private val chats = ConcurrentHashMap<String, AtomicBoolean>()
    private val downloading = AtomicBoolean(false)
    private val pulling = AtomicBoolean(false)
    private val grammar: String by lazy {
        activity.resources.openRawResource(R.raw.json_grammar).bufferedReader().use { it.readText() }
    }
    private val modelFile get() = File(activity.filesDir, "models/$TABLET_FILE")
    private val partFile get() = File(activity.filesDir, "models/$TABLET_FILE.part")
    private var pcUrl: String
        get() = prefs.getString("pc", "") ?: ""
        set(v) { prefs.edit().putString("pc", v).apply() }

    // Idempotency keys: a request sent twice with the same key (a double tap, a retry) runs once.
    private val seenKeys = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    private fun firstTime(key: String): Boolean = synchronized(seenKeys) {
        if (key.isEmpty()) return true
        if (!seenKeys.add(key)) return false
        if (seenKeys.size > 200) seenKeys.remove(seenKeys.first())
        true
    }

    @JavascriptInterface
    fun post(json: String) {
        val msg = try { JSONObject(json) } catch (e: Exception) { return }
        if (!firstTime(msg.optString("key"))) return
        when (msg.optString("type")) {
            "ai-status" -> io.execute { sendStatus() }
            "ai-chat" -> chat(msg)
            "ai-stop" -> chats[msg.optString("id")]?.set(true).also { engine.stop() }
            "ai-pull" -> io.execute { pull(msg.optString("model")) }
            "ai-find" -> io.execute { find() }
            "ai-set-pc" -> io.execute { pcUrl = normalize(msg.optString("url")); sendStatus() }
            "ai-tablet-download" -> io.execute { downloadTabletModel() }
            "ai-tablet-delete" -> brain.execute { engine.unload(); modelFile.delete(); partFile.delete(); sendStatus() }
            // Settings → Uninstall Codeling: Android's own uninstall screen removes the app and all its data
            "app-uninstall" -> activity.runOnUiThread {
                try {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:" + activity.packageName)))
                } catch (e: Exception) { }
            }
        }
    }

    fun close() {
        chats.values.forEach { it.set(true) }
        engine.stop()
        brain.execute { engine.unload() }
        brain.shutdown()
        io.shutdownNow()
    }

    // ---------- messages to the page ----------
    private fun send(o: JSONObject) {
        val script = "window.__nativeMessage && window.__nativeMessage(" + o.put("type", "ai").toString() + ");"
        web.post { web.evaluateJavascript(script, null) }
    }

    private fun event(name: String, vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        put("event", name)
        for ((k, v) in pairs) put(k, v ?: JSONObject.NULL)
    }

    // ---------- status ----------
    private fun pcInfo(): JSONObject {
        val info = JSONObject().put("url", pcUrl).put("ok", false)
        if (pcUrl.isEmpty()) return info
        try {
            val tags = getJson("$pcUrl/api/tags", 2500)
            val version = try { getJson("$pcUrl/api/version", 2500).optString("version") } catch (e: Exception) { "" }
            val models = JSONArray()
            val list = tags.optJSONArray("models") ?: JSONArray()
            for (i in 0 until list.length()) {
                val m = list.getJSONObject(i)
                if (!m.optString("name").contains("embed")) models.put(JSONObject().put("name", m.optString("name")).put("size", m.optLong("size")))
            }
            info.put("ok", true).put("version", version).put("models", models)
        } catch (e: Exception) {
            info.put("error", "Can't reach Ollama at $pcUrl.")
        }
        return info
    }

    private fun tabletInfo() = JSONObject()
        .put("supported", LlamaEngine.available)
        .put("downloaded", modelFile.isFile)
        .put("size", TABLET_SIZE_LABEL)
        .put("label", TABLET_LABEL)
        .put("downloading", downloading.get())
        .put("partial", if (partFile.isFile) partFile.length() else 0)

    private fun sendStatus() {
        val pc = pcInfo()
        val tablet = tabletInfo()
        val models = JSONArray()
        pc.optJSONArray("models")?.let { for (i in 0 until it.length()) models.put(it.get(i)) }
        if (tablet.getBoolean("downloaded")) models.put(JSONObject().put("name", "tablet").put("label", TABLET_LABEL))
        send(event("status",
            "running" to (pc.getBoolean("ok") || tablet.getBoolean("downloaded")),
            "installed" to true,
            "version" to pc.optString("version"),
            "models" to models,
            "android" to JSONObject().put("pc", pc).put("tablet", tablet),
        ))
    }

    // ---------- finding the PC on the Wi-Fi ----------
    private fun find() {
        send(event("find", "status" to "searching"))
        val found = scanForOllama()
        if (found != null) {
            pcUrl = found
            send(event("find", "status" to "found", "url" to found))
        } else {
            send(event("find", "status" to "none"))
        }
        sendStatus()
    }

    /** Checks every address on this tablet's Wi-Fi network for Ollama's port. */
    private fun scanForOllama(): String? {
        val bases = mutableSetOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    bases.add(addr.hostAddress!!.substringBeforeLast('.'))
                }
            }
        }
        if (bases.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = bases.flatMap { base -> (1..254).map { "$base.$it" } }.map { ip ->
                Callable {
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, PORT), 350) }
                        val url = "http://$ip:$PORT"
                        if (getJson("$url/api/version", 1500).has("version")) url else null
                    } catch (e: Exception) { null }
                }
            }
            return pool.invokeAll(tasks, 20, TimeUnit.SECONDS).firstNotNullOfOrNull { f -> try { f.get() } catch (e: Exception) { null } }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- answering ----------
    private fun chat(msg: JSONObject) {
        val id = msg.optString("id")
        val model = msg.optString("model")
        val json = msg.optBoolean("json")
        val messages = msg.optJSONArray("messages") ?: JSONArray()
        val stopped = AtomicBoolean(false)
        chats[id] = stopped
        io.execute {
            try {
                if (model != "tablet" && pcUrl.isNotEmpty()) {
                    try {
                        chatOnPc(id, model, messages, json, stopped)
                        return@execute
                    } catch (e: IOException) {
                        // PC not reachable: fall back to the tablet model when it's there
                        if (!modelFile.isFile || e is OllamaError) throw e
                    }
                }
                if (!modelFile.isFile) {
                    send(event("error", "id" to id, "code" to "offline", "text" to
                        "Your computer's AI isn't reachable, and the tablet model isn't downloaded. Check Settings → AI tutor."))
                    return@execute
                }
                chatOnTablet(id, messages, json, stopped)
            } catch (e: OllamaError) {
                send(event("error", "id" to id, "code" to e.code, "text" to e.message))
            } catch (e: Exception) {
                send(event("error", "id" to id, "code" to "offline", "text" to
                    "Couldn't reach the AI on your computer. Check that it's on and on the same Wi-Fi."))
            } finally {
                chats.remove(id)
            }
        }
    }

    private fun chatOnPc(id: String, model: String, messages: JSONArray, json: Boolean, stopped: AtomicBoolean) {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("options", JSONObject().put("temperature", 0.4).put("num_ctx", 8192))
        if (json) body.put("format", "json")
        val conn = open("$pcUrl/api/chat", 4000, 600000)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) throw ollamaError(conn)
        val text = StringBuilder()
        var last = 0L
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (!stopped.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = JSONObject(line)
                if (obj.has("error")) throw OllamaError("error", "The AI model stopped with an error: " + obj.optString("error"))
                text.append(obj.optJSONObject("message")?.optString("content") ?: "")
                val now = System.currentTimeMillis()
                if (now - last > 80) { last = now; send(event("delta", "id" to id, "text" to text.toString())) }
                if (obj.optBoolean("done")) break
            }
        }
        conn.disconnect()
        send(event("done", "id" to id, "text" to text.toString()))
    }

    private fun chatOnTablet(id: String, messages: JSONArray, json: Boolean, stopped: AtomicBoolean) {
        if (!LlamaEngine.available) {
            send(event("error", "id" to id, "code" to "error", "text" to "The tablet model can't run on this tablet's processor."))
            return
        }
        val done = brain.submit {
            if (stopped.get()) return@submit
            send(event("delta", "id" to id, "text" to ""))
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            if (!engine.load(modelFile.absolutePath, threads, TABLET_CONTEXT)) {
                send(event("error", "id" to id, "code" to "error", "text" to
                    "The tablet model couldn't load. Close other apps to free memory, or remove and download it again in Settings."))
                return@submit
            }
            val list = (0 until messages.length()).map {
                val m = messages.getJSONObject(it)
                m.optString("role", "user") to m.optString("content")
            }
            var last = 0L
            try {
                val text = engine.complete(list, if (json) grammar else null, if (json) 700 else 400, 0.4f) { soFar ->
                    if (stopped.get()) engine.stop()
                    val now = System.currentTimeMillis()
                    if (now - last > 120) { last = now; send(event("delta", "id" to id, "text" to soFar)) }
                }
                send(event("done", "id" to id, "text" to text))
            } catch (e: IllegalStateException) {
                send(event("error", "id" to id, "code" to "error", "text" to e.message))
            }
        }
        done.get()
    }

    // ---------- downloading models ----------
    /** Downloads a model onto the PC through its Ollama. */
    private fun pull(model: String) {
        if (!pulling.compareAndSet(false, true)) return
        try {
            if (!Regex("[\\w.\\-/]+(:[\\w.\\-]+)?").matches(model)) throw OllamaError("error", "That model name isn't valid.")
            val conn = open("$pcUrl/api/pull", 4000, 3600000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("model", model).put("stream", true).toString().toByteArray()) }
            if (conn.responseCode != 200) throw ollamaError(conn)
            var last = 0L
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = JSONObject(line)
                    if (obj.has("error")) throw OllamaError("error", obj.optString("error"))
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        send(event("pull", "model" to model, "status" to obj.optString("status"), "completed" to obj.optLong("completed"), "total" to obj.optLong("total")))
                    }
                }
            }
            send(event("pulled", "model" to model))
        } catch (e: OllamaError) {
            send(event("pull-error", "model" to model, "text" to e.message))
        } catch (e: Exception) {
            send(event("pull-error", "model" to model, "text" to "Couldn't reach the AI on your computer."))
        } finally {
            pulling.set(false)
            sendStatus()
        }
    }

    /** Downloads the tablet model from Hugging Face, resuming where it left off. */
    private fun downloadTabletModel() {
        if (!downloading.compareAndSet(false, true)) return
        try {
            modelFile.parentFile?.mkdirs()
            val have = if (partFile.isFile) partFile.length() else 0L
            val conn = open(TABLET_URL, 15000, 60000)
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val resume = code == 206
            val total = (if (resume) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
            var done = if (resume) have else 0L
            var last = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partFile, resume).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 300) {
                            last = now
                            send(event("tablet-progress", "completed" to done, "total" to total))
                        }
                    }
                }
            }
            if (partFile.length() < 300_000_000L) throw IOException("The download was incomplete.")
            if (!partFile.renameTo(modelFile)) throw IOException("Couldn't save the model.")
            send(event("tablet-progress", "completed" to done, "total" to total, "finished" to true))
        } catch (e: Exception) {
            send(event("tablet-error", "text" to "The download stopped (${e.message}). Tap Download again to continue where it left off."))
        } finally {
            downloading.set(false)
            sendStatus()
        }
    }

    // ---------- HTTP helpers ----------
    private class OllamaError(val code: String, message: String) : IOException(message)

    private fun ollamaError(conn: HttpURLConnection): OllamaError {
        val detail = try {
            JSONObject(conn.errorStream?.bufferedReader()?.readText() ?: "").optString("error")
        } catch (e: Exception) { "" }
        return if (detail.contains("not found") && detail.contains("model"))
            OllamaError("no_model", "That AI model isn't on your computer yet. Download it in Settings.")
        else OllamaError("error", "Ollama couldn't answer: " + detail.ifEmpty { conn.responseCode.toString() })
    }

    private fun open(url: String, connectTimeout: Int, readTimeout: Int) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("User-Agent", "Codeling-Android")
        }

    private fun getJson(url: String, timeout: Int): JSONObject {
        val conn = open(url, timeout, timeout)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun normalize(raw: String): String {
        var v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return ""
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        val hostPart = v.substringAfter("://")
        if (!hostPart.contains(':')) v += ":$PORT"
        return v
    }

    companion object {
        const val PORT = 11434
        const val TABLET_FILE = "qwen2.5-coder-0.5b-instruct-q4_0.gguf"
        const val TABLET_URL = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/$TABLET_FILE"
        const val TABLET_LABEL = "On this tablet (Qwen2.5 Coder 0.5B)"
        const val TABLET_SIZE_LABEL = "429 MB"
        const val TABLET_CONTEXT = 4096
    }
}
