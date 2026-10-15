package io.github.jayswansocool48.deskmate

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The one connection to the PC. Messages are JSON, one per line, over TCP port 47800.
 * Everything a screen sees is delivered on the main thread.
 */
object DeskClient {
    enum class State { NO_PC, OFFLINE, CONNECTING, NEED_PAIR, ONLINE }

    data class FoundPC(val name: String, val address: String, val port: Int, val os: String)

    const val DEFAULT_PORT = 47800

    var state = State.NO_PC
        private set
    var caps: JSONObject = JSONObject()
        private set
    var pcName = ""
        private set
    var host: String? = null
        private set
    var port = DEFAULT_PORT
        private set
    var lastError = ""
        private set

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val messageListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val pending = HashMap<Int, (JSONObject) -> Unit>()
    private var nextReq = 1

    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var retryDelay = 2000L
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.getSharedPreferences("deskmate", Context.MODE_PRIVATE)
        host = prefs.getString("host", null)
        port = prefs.getInt("port", DEFAULT_PORT)
        pcName = prefs.getString("pcName", "") ?: ""
        if (host != null) connect() else setState(State.NO_PC)
        main.postDelayed(pinger, 20_000)
    }

    fun onMessage(l: (JSONObject) -> Unit) { if (!messageListeners.contains(l)) messageListeners.add(l) }
    fun removeMessage(l: (JSONObject) -> Unit) { messageListeners.remove(l) }
    fun onState(l: () -> Unit) { if (!stateListeners.contains(l)) stateListeners.add(l) }
    fun removeState(l: () -> Unit) { stateListeners.remove(l) }

    val online get() = state == State.ONLINE

    private fun tokenKey(name: String) = "token:$name"

    /** Connect to a newly chosen PC. */
    fun choose(pc: FoundPC) {
        host = pc.address
        port = pc.port
        pcName = pc.name
        prefs.edit().putString("host", host).putInt("port", port).putString("pcName", pcName).apply()
        connect()
    }

    fun forget() {
        prefs.edit().remove("host").remove(tokenKey(pcName)).apply()
        host = null
        pcName = ""
        disconnect()
        setState(State.NO_PC)
    }

    fun reconnectNow() {
        if (host != null && state != State.ONLINE && state != State.CONNECTING) connect()
    }

    private fun setState(s: State) {
        state = s
        for (l in stateListeners) l()
    }

    private fun disconnect() {
        generation++
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        out = null
    }

    private fun connect() {
        disconnect()
        val gen = generation
        val h = host ?: return
        val p = port
        setState(State.CONNECTING)
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(h, p), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 60_000
                if (gen != generation) { s.close(); return@Thread }
                socket = s
                out = s.getOutputStream()
                val token = prefs.getString(tokenKey(pcName), "") ?: ""
                send(JSONObject().put("op", "hello").put("token", token))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (gen == generation) {
                    val line = try {
                        reader.readLine()
                    } catch (e: SocketTimeoutException) {
                        throw e
                    } ?: break
                    val m = try { JSONObject(line) } catch (e: Exception) { continue }
                    main.post { if (gen == generation) dispatch(m) }
                }
                main.post { if (gen == generation) lost("Disconnected") }
            } catch (e: Exception) {
                main.post { if (gen == generation) lost(e.message ?: "Can't reach the PC") }
            }
        }.start()
    }

    private fun lost(reason: String) {
        lastError = reason
        socket = null
        out = null
        pending.clear()
        setState(State.OFFLINE)
        val gen = generation
        main.postDelayed({ if (gen == generation && host != null) connect() }, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(15_000)
    }

    private fun dispatch(m: JSONObject) {
        when (m.optString("op")) {
            "welcome" -> {
                caps = m.optJSONObject("caps") ?: JSONObject()
                retryDelay = 2000
                setState(State.ONLINE)
            }
            "paired" -> {
                caps = m.optJSONObject("caps") ?: JSONObject()
                prefs.edit().putString(tokenKey(pcName), m.optString("token")).apply()
                retryDelay = 2000
                setState(State.ONLINE)
            }
            "needPair" -> setState(State.NEED_PAIR)
        }
        if (m.has("req")) {
            pending.remove(m.optInt("req"))?.invoke(m)
        }
        for (l in messageListeners) l(m)
    }

    fun send(m: JSONObject) {
        writer.execute {
            try {
                out?.let {
                    it.write((m.toString() + "\n").toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } catch (e: Exception) {
                // The reader thread notices and reconnects.
            }
        }
    }

    fun send(op: String, vararg pairs: Pair<String, Any?>) {
        val m = JSONObject().put("op", op)
        for ((k, v) in pairs) m.put(k, v)
        send(m)
    }

    /** Sends a request and calls back with the matching reply (on the main thread). */
    fun request(op: String, vararg pairs: Pair<String, Any?>, callback: (JSONObject) -> Unit) {
        val id = nextReq++
        pending[id] = callback
        val m = JSONObject().put("op", op).put("req", id)
        for ((k, v) in pairs) m.put(k, v)
        send(m)
    }

    fun pair(code: String) {
        send(JSONObject().put("op", "pair").put("code", code).put("name", deviceName()))
    }

    private fun deviceName(): String {
        val model = Build.MODEL ?: "Tablet"
        return when {
            model.startsWith("KF") -> "Fire tablet ($model)"
            else -> model
        }
    }

    private val pinger = object : Runnable {
        override fun run() {
            if (state == State.ONLINE) send("ping")
            main.postDelayed(this, 20_000)
        }
    }

    // ------------------------------------------------------------ discovery

    /** Broadcasts on the local network for ~1.5 s and reports each PC that answers. */
    fun discover(onFound: (FoundPC) -> Unit, onDone: () -> Unit) {
        Thread {
            val seen = HashSet<String>()
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 300
                    val probe = "DESKMATE?".toByteArray()
                    val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                    val end = System.currentTimeMillis() + 1600
                    var nextSend = 0L
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() >= nextSend) {
                            for (t in targets) {
                                try { sock.send(DatagramPacket(probe, probe.size, t, DEFAULT_PORT)) } catch (e: Exception) {}
                            }
                            nextSend = System.currentTimeMillis() + 500
                        }
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val addr = pkt.address.hostAddress ?: continue
                        if (!seen.add(addr)) continue
                        val j = try { JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) } catch (e: Exception) { continue }
                        if (!j.has("deskmate")) continue
                        val pc = FoundPC(j.optString("name", addr), addr, j.optInt("port", DEFAULT_PORT), j.optString("os"))
                        main.post { onFound(pc) }
                    }
                }
            } catch (e: Exception) {
                // No Wi-Fi, most likely.
            }
            main.post(onDone)
        }.start()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {}
        return out
    }
}
