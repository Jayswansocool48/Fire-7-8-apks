package io.github.jayswansocool48.hubremote

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, v ?: JSONObject.NULL) }
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** The Hub server on CasaOS: plain HTTP + JSON. */
object Hub {
    val main = Handler(Looper.getMainLooper())
    val pool: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var prefs: SharedPreferences
    lateinit var app: Context

    var server = ""
        private set
    var accent = Theme.ACCENT_DEFAULT
        private set
    var status: JSONObject? = null
        private set
    var tvApp: String? = null
        private set
    var tvOk = false
        private set
    var serverOk = false
        private set

    var onError: ((String) -> Unit)? = null
    private val listeners = ArrayList<() -> Unit>()
    private var polling = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE)
        server = prefs.getString("server", "") ?: ""
        accent = prefs.getInt("accent", Theme.ACCENT_DEFAULT)
    }

    /** Forgets everything Hub Remote saved on this tablet (for Uninstall). */
    fun forget() {
        prefs.edit().clear().commit()
        server = ""
    }

    fun setServer(s: String) {
        server = s.trim().removePrefix("http://").trimEnd('/')
        prefs.edit().putString("server", server).apply()
        status = null
        changed()
        refresh()
    }

    fun base(): String = "http://" + (if (server.contains(":")) server else "$server:8765")
    fun url(path: String): String = if (path.startsWith("http")) path else base() + path

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeChange(l: () -> Unit) { listeners.remove(l) }
    fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    // ---- requests (blocking versions run on the pool) ----

    /** POSTs carry an Idempotency-Key, so the one automatic retry (after a dropped connection)
     *  never runs the same action twice on the server. */
    fun call(method: String, path: String, body: JSONObject? = null, timeout: Int = 10000, idem: String? = null): JSONObject {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = timeout
        c.requestMethod = method
        if (idem != null) c.setRequestProperty("Idempotency-Key", idem)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code >= 400 || j.optBoolean("ok", true) == false) throw RuntimeException(j.optString("error", "Request failed ($code)"))
        return j
    }

    fun get(path: String, cb: (JSONObject?, String?) -> Unit) = async("GET", path, null, cb)
    fun post(path: String, body: JSONObject, cb: (JSONObject?, String?) -> Unit = { _, e -> if (e != null) onError?.invoke(e) }) =
        async("POST", path, body, cb)

    private fun async(method: String, path: String, body: JSONObject?, cb: (JSONObject?, String?) -> Unit) {
        if (server.isEmpty()) { cb(null, "Set your Hub server first"); return }
        pool.execute {
            val idem = if (method == "POST") java.util.UUID.randomUUID().toString() else null
            try {
                val j = try { call(method, path, body, idem = idem) } catch (e: java.io.IOException) {
                    if (idem == null || e is java.net.ConnectException) throw e
                    call(method, path, body, idem = idem) // the request may have arrived; the key makes the retry safe
                }
                main.post { cb(j, null) }
            } catch (e: Exception) {
                main.post { cb(null, friendly(e)) }
            }
        }
    }

    fun friendly(e: Exception): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
            "Can't reach the Hub server at $server"
        else -> e.message ?: "Something went wrong"
    }

    fun key(k: String) = post("/api/tv/key", json("key" to k))

    fun bytes(path: String, timeout: Int = 15000): ByteArray? = try {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = timeout
        val b = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        c.disconnect()
        b
    } catch (e: Exception) { null }

    /** Uploads a file the user picked (or shared) as the raw request body. */
    fun upload(path: String, uri: Uri, name: String, progress: (Int) -> Unit, cb: (JSONObject?, String?) -> Unit) {
        pool.execute {
            var temp: File? = null
            try {
                val cr = app.contentResolver
                var size = -1L
                cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) size = it.getLong(0)
                }
                var input: InputStream = cr.openInputStream(uri) ?: throw RuntimeException("Can't open that file")
                if (size <= 0) { // unknown size: copy it first so the server gets a Content-Length
                    temp = File.createTempFile("send", null, app.cacheDir)
                    temp.outputStream().use { out -> input.use { it.copyTo(out) } }
                    size = temp.length()
                    input = temp.inputStream()
                }
                val c = URL(url(path)).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 120000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setRequestProperty("X-Filename", enc(name))
                c.setRequestProperty("Idempotency-Key", java.util.UUID.randomUUID().toString())
                var sent = 0L
                var lastPct = -1
                c.outputStream.use { out ->
                    input.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            val pct = (sent * 100 / size).toInt()
                            if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                        }
                    }
                }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code >= 400 || !j.optBoolean("ok", true)) throw RuntimeException(j.optString("error", "Upload failed"))
                main.post { cb(j, null) }
            } catch (e: Exception) {
                main.post { cb(null, friendly(e)) }
            } finally {
                temp?.delete()
            }
        }
    }

    /** Uploads bytes made in the app (a drawn note) as the raw request body. */
    fun uploadBytes(path: String, data: ByteArray, name: String, cb: (JSONObject?, String?) -> Unit) {
        pool.execute {
            try {
                val c = URL(url(path)).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 30000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(data.size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setRequestProperty("X-Filename", enc(name))
                c.setRequestProperty("Idempotency-Key", java.util.UUID.randomUUID().toString())
                c.outputStream.use { it.write(data) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code >= 400 || !j.optBoolean("ok", true)) throw RuntimeException(j.optString("error", "Upload failed"))
                main.post { cb(j, null) }
            } catch (e: Exception) {
                main.post { cb(null, friendly(e)) }
            }
        }
    }

    // ---- status polling (what's on the TV, sleep timer, accent color) ----

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            main.postDelayed(this, 5000)
        }
    }

    fun startPolling() { if (!polling) { polling = true; main.post(poll) } }
    fun stopPolling() { polling = false; main.removeCallbacks(poll) }

    fun refresh() {
        if (server.isEmpty()) { changed(); return }
        pool.execute {
            val s = try { call("GET", "/api/status", timeout = 6000) } catch (e: Exception) { null }
            val a = try { call("GET", "/api/tv/active", timeout = 6000).optJSONObject("app") } catch (e: Exception) { null }
            main.post {
                serverOk = s != null
                if (s != null) {
                    status = s
                    val hex = s.optJSONObject("hub")?.optString("accent", "") ?: ""
                    if (hex.length == 7) {
                        accent = (0xFF000000 or hex.substring(1).toLong(16)).toInt()
                        prefs.edit().putInt("accent", accent).apply()
                    }
                }
                tvOk = a != null
                tvApp = when {
                    a == null -> null
                    a.optString("id") == "dev" -> "Hub"
                    a.isNull("id") -> "Roku home"
                    else -> a.optString("name")
                }
                changed()
            }
        }
    }

    fun sleepLeft(): Int = status?.optInt("sleep", 0) ?: 0
    fun hub(): JSONObject = status?.optJSONObject("hub") ?: JSONObject()

    /** Finds Hub servers on this Wi-Fi by trying port 8765 on every address. */
    fun discover(cb: (List<String>) -> Unit) {
        pool.execute {
            val prefix = localPrefix()
            if (prefix == null) { main.post { cb(emptyList()) }; return@execute }
            val found = ArrayList<String>()
            val scan = Executors.newFixedThreadPool(48)
            val left = AtomicInteger(254)
            for (i in 1..254) {
                scan.execute {
                    val host = "$prefix.$i"
                    try {
                        val c = URL("http://$host:8765/api/status").openConnection() as HttpURLConnection
                        c.connectTimeout = 600
                        c.readTimeout = 1500
                        if (c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("\"version\"")) {
                            synchronized(found) { found.add(host) }
                        }
                        c.disconnect()
                    } catch (e: Exception) { }
                    if (left.decrementAndGet() == 0) main.post { cb(found.sorted()) }
                }
            }
            scan.shutdown()
        }
    }

    private fun localPrefix(): String? {
        try {
            @Suppress("DEPRECATION")
            val ip = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}"
        } catch (e: Exception) { }
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return a.hostAddress?.substringBeforeLast('.')
                }
            }
        }
        return null
    }
}

/** Small image loader with a memory cache. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, path: String, maxW: Int = 600) {
        val key = "$path@$maxW"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        Hub.pool.execute {
            val data = Hub.bytes(path) ?: return@execute
            val bmp = decode(data, maxW) ?: return@execute
            cache.put(key, bmp)
            Hub.main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    fun decode(data: ByteArray, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
