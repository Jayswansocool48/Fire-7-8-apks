package io.github.jayswansocool48.glow

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/** Glow on the PC: the AITOLED screen light is plugged in there. Plain HTTP + JSON over Wi-Fi. */
object Pc {
    data class Found(val name: String, val host: String, val port: Int)

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private lateinit var app: Context

    var host: String? = null
        private set
    var port = 47820
        private set
    var name: String? = null
        private set
    private var token: String? = null

    /** Latest /api/state from the PC, or null if we can't reach it. */
    var state: JSONObject? = null
        private set
    var error: String? = null
        private set
    private val listeners = ArrayList<() -> Unit>()
    private var polling = false

    val paired get() = host != null && token != null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = app.getSharedPreferences("pc", Context.MODE_PRIVATE)
        host = p.getString("host", null)
        port = p.getInt("port", 47820)
        name = p.getString("name", null)
        token = p.getString("token", null)
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    fun forget() {
        host = null
        token = null
        name = null
        state = null
        app.getSharedPreferences("pc", Context.MODE_PRIVATE).edit().clear().apply()
        changed()
    }

    // ---- finding the PC ----

    /** Asks every Glow on the Wi-Fi to answer. Calls back on the main thread after ~2 s. */
    fun discover(done: (List<Found>) -> Unit) {
        pool.execute {
            val found = LinkedHashMap<String, Found>()
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("glow")
            try {
                lock?.acquire()
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 400
                    val msg = "glow?".toByteArray()
                    for (round in 0 until 4) {
                        for (addr in broadcastAddresses()) {
                            try { s.send(DatagramPacket(msg, msg.size, addr, 47821)) } catch (e: Exception) { }
                        }
                        val until = System.currentTimeMillis() + 500
                        while (System.currentTimeMillis() < until) {
                            val buf = ByteArray(1024)
                            val pk = DatagramPacket(buf, buf.size)
                            try {
                                s.receive(pk)
                                val j = JSONObject(String(pk.data, 0, pk.length))
                                if (j.optString("app") == "glow") {
                                    val h = pk.address.hostAddress ?: continue
                                    found[h] = Found(j.optString("host", h), h, j.optInt("port", 47820))
                                }
                            } catch (e: SocketTimeoutException) {
                                break
                            } catch (e: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { lock?.release() } catch (e: Exception) { }
            }
            main.post { done(found.values.toList()) }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (e: Exception) { }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out
    }

    /** Checks a typed-in address ("192.168.1.20" or "192.168.1.20:47820"). */
    fun probe(addr: String, done: (Found?) -> Unit) {
        val parts = addr.trim().removePrefix("http://").trimEnd('/').split(":")
        val h = parts[0]
        val p = parts.getOrNull(1)?.toIntOrNull() ?: 47820
        pool.execute {
            val j = try { http("GET", "http://$h:$p/api/hello", null, null) } catch (e: Exception) { null }
            main.post { done(if (j?.optString("app") == "glow") Found(j.optString("host", h), h, p) else null) }
        }
    }

    fun pair(pc: Found, code: String, done: (String?) -> Unit) {
        pool.execute {
            val body = JSONObject().put("code", code).put("name", Build.MODEL ?: "Tablet")
            val j = try { http("POST", "http://${pc.host}:${pc.port}/api/pair", body, null) } catch (e: Exception) { null }
            main.post {
                if (j == null) return@post done("Can't reach ${pc.name}. Is Glow running there?")
                if (!j.optBoolean("ok")) return@post done(j.optString("error", "Wrong code"))
                host = pc.host
                port = pc.port
                name = pc.name
                token = j.getString("token")
                app.getSharedPreferences("pc", Context.MODE_PRIVATE).edit()
                    .putString("host", host).putInt("port", port).putString("name", name).putString("token", token).apply()
                refresh()
                done(null)
            }
        }
    }

    // ---- talking to it ----

    private fun http(method: String, url: String, body: JSONObject?, tok: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 2500
            c.readTimeout = if (url.contains("/scan") || url.contains("/connect")) 30000 else 5000
            if (tok != null) c.setRequestProperty("Authorization", "Bearer $tok")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode == 401) throw IllegalStateException("unpaired")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** POSTs to the PC; the answer is the new state. */
    fun post(path: String, body: JSONObject = JSONObject(), done: ((JSONObject?) -> Unit)? = null) {
        val h = host ?: return
        val t = token
        pool.execute {
            val j = try {
                http("POST", "http://$h:$port$path", body, t)
            } catch (e: IllegalStateException) {
                main.post { forget() }
                null
            } catch (e: Exception) {
                null
            }
            main.post {
                if (j != null && j.has("aito")) {
                    state = j
                    error = null
                    changed()
                }
                done?.invoke(j)
            }
        }
    }

    fun refresh() {
        val h = host ?: return
        val t = token
        pool.execute {
            try {
                val j = http("GET", "http://$h:$port/api/state", null, t)
                main.post { state = j; error = null; changed() }
            } catch (e: IllegalStateException) {
                main.post { error = "This tablet isn't paired with ${name ?: "the PC"} anymore"; forget() }
            } catch (e: Exception) {
                main.post {
                    state = null
                    error = "Can't reach ${name ?: "your PC"}. Is it on, with Glow running?"
                    changed()
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    fun startPolling() {
        if (polling) return
        polling = true
        main.post(tick)
    }

    fun stopPolling() {
        polling = false
        main.removeCallbacks(tick)
    }

    // Handy readers for the state JSON.
    fun aito(): JSONObject? = state?.optJSONObject("aito")
    fun strip(): JSONObject? = state?.optJSONObject("strip")
    fun pcHasStrip() = strip()?.optBoolean("connected") == true
}
