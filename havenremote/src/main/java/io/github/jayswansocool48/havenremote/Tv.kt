package io.github.jayswansocool48.havenremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** An app on the TV. */
class TvApp(val pkg: String, val activity: String?, val label: String, val fav: Boolean)

/**
 * Everything the remote does to the TV. Buttons go over ADB (a shell kept open so
 * presses are quick); the app list and icons come from Haven's little web server.
 */
class TvLink(private val ctx: Context) {

    enum class State { OFF, CONNECTING, APPROVE, ON, FAILED }

    var host: String? = null
        private set
    @Volatile var state = State.OFF
        private set
    var message = ""
        private set

    /** Called on the main thread whenever [state] changes. */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val net: ExecutorService = Executors.newFixedThreadPool(3)
    private var adb: AdbConnection? = null
    private var shell: AdbConnection.AdbStream? = null
    private val prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
    val iconCache = HashMap<String, Bitmap>()

    init {
        host = prefs.getString("host", null)
    }

    private fun set(s: State, msg: String) {
        state = s
        message = msg
        main.post { listener?.invoke() }
    }

    fun connect(newHost: String? = host) {
        val h = newHost?.trim().orEmpty()
        if (h.isEmpty()) {
            set(State.OFF, "Pick your TV in Setup")
            return
        }
        if (h != host) {
            host = h
            prefs.edit().putString("host", h).apply()
            iconCache.clear()
            io.execute { drop() }
        }
        io.execute { ensure() }
    }

    fun disconnect() = io.execute {
        drop()
        set(State.OFF, "Disconnected")
    }

    /** Must run on [io]. Connects if needed; returns false if the TV can't be reached. */
    private fun ensure(): Boolean {
        val a = adb
        if (a != null && !a.closed && shell != null) return true
        val h = host ?: return false
        set(State.CONNECTING, "Connecting to $h…")
        return try {
            val conn = AdbConnection.connect(ctx, h) {
                set(State.APPROVE, "Look at the TV: tick \"Always allow\" and press OK")
            }
            conn.onClosed = {
                if (adb === conn) {
                    adb = null
                    shell = null
                    set(State.FAILED, "Lost the TV. Tap to reconnect")
                }
            }
            adb = conn
            shell = conn.open("shell:", keepOutput = false)
            set(State.ON, "Connected")
            true
        } catch (e: java.net.ConnectException) {
            set(State.FAILED, "TV said no. Is ADB debugging on?")
            false
        } catch (e: java.net.SocketTimeoutException) {
            set(State.FAILED, if (state == State.APPROVE) "Didn't get an OK on the TV. Try again" else "Couldn't reach $h")
            false
        } catch (e: Exception) {
            set(State.FAILED, "Couldn't reach $h")
            false
        }
    }

    private fun drop() {
        val a = adb
        adb = null
        shell = null
        a?.close()
    }

    /** Runs a command in the open shell (fast, no output). Retries once on a fresh connection. */
    private fun fire(cmd: String) = io.execute {
        for (attempt in 0..1) {
            if (!ensure()) return@execute
            try {
                shell!!.write((cmd + "\n").toByteArray())
                return@execute
            } catch (e: Exception) {
                drop()
            }
        }
    }

    /** Runs a command and hands back its output on the main thread. */
    fun exec(cmd: String, done: (String?) -> Unit) = io.execute {
        var out: String? = null
        if (ensure()) {
            out = try {
                adb?.shell(cmd)
            } catch (e: Exception) {
                null
            }
        }
        main.post { done(out) }
    }

    fun key(code: Int) = fire("input keyevent $code")
    fun longKey(code: Int) = fire("input keyevent --longpress $code")

    /** Types text into whatever box is focused on the TV. */
    fun type(text: String) {
        if (text.isEmpty()) return
        // `input text` needs spaces as %s, and the whole thing quoted for the shell.
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        fire("input text '$escaped'")
    }

    fun launch(app: TvApp) {
        val p = app.pkg
        if (app.activity != null) {
            fire("am start -n $p/${app.activity} || monkey -p $p -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $p -c android.intent.category.LAUNCHER 1")
        } else {
            fire("monkey -p $p -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $p -c android.intent.category.LAUNCHER 1")
        }
    }

    fun openHaven() = fire("am start -n $HAVEN_PKG/.LauncherActivity")

    fun reboot() = io.execute {
        if (!ensure()) return@execute
        try {
            adb?.open("reboot:")
        } catch (e: Exception) {
        }
        drop()
        set(State.OFF, "TV is restarting…")
    }

    /**
     * Turns on Haven's Home button watcher by adding it to the TV's accessibility services,
     * keeping any others (like an auto-launcher app) that are already on.
     */
    fun makeHavenHome(done: (String) -> Unit) {
        val svc = "$HAVEN_PKG/$HAVEN_PKG.HomeWatcher"
        exec("pm list packages $HAVEN_PKG") { out ->
            if (out == null) {
                done("Couldn't reach the TV")
                return@exec
            }
            if (!out.contains(HAVEN_PKG)) {
                done("Install Haven on the TV first")
                return@exec
            }
            val script = "cur=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$cur\" in *$svc*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$cur:$svc\" ;; esac; " +
                "settings put secure accessibility_enabled 1; " +
                "settings get secure enabled_accessibility_services"
            exec(script) { result ->
                if (result != null && result.contains(svc)) {
                    openHaven()
                    done("Done! Press Home on the TV remote to try it ✿")
                } else {
                    done("The TV didn't accept it. Try again with Haven open on the TV.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Haven's app list

    fun apps(done: (List<TvApp>?, Boolean) -> Unit) = net.execute {
        val h = host
        val fromHaven = try {
            if (h == null) null else parseApps(httpGet("http://$h:$HAVEN_PORT/apps").toString(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        if (fromHaven != null) {
            main.post { done(fromHaven, true) }
            return@execute
        }
        // Haven isn't running: ask the TV for its app packages instead (no names or icons).
        exec("pm list packages -3") { out ->
            val list = out?.lines()?.mapNotNull { l ->
                l.trim().removePrefix("package:").takeIf { it.isNotBlank() && it != l.trim() }
            }?.sorted()?.map { TvApp(it, null, prettyName(it), false) }
            done(list, false)
        }
    }

    fun icon(pkg: String, done: (Bitmap?) -> Unit) {
        iconCache[pkg]?.let { return done(it) }
        val h = host ?: return done(null)
        net.execute {
            val bmp = try {
                val bytes = httpGet("http://$h:$HAVEN_PORT/icon?pkg=" + URLEncoder.encode(pkg, "UTF-8"))
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bmp != null) iconCache[pkg] = bmp
                done(bmp)
            }
        }
    }

    private fun parseApps(json: String): List<TvApp> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TvApp(o.getString("pkg"), o.optString("activity").ifEmpty { null }, o.optString("label", o.getString("pkg")), o.optBoolean("fav"))
        }
    }

    private fun httpGet(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 6000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- finding the TV

    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    /** Looks for TVs running Haven on the Wi-Fi. [found] may be called several times. */
    fun findTvs(found: (name: String, host: String) -> Unit, finished: () -> Unit) {
        stopFinding()
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("haven").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        fun resolveNext() {
            if (resolving) return
            val next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val addr = i.host?.hostAddress
                    main.post {
                        resolving = false
                        if (addr != null && !addr.contains(':')) found(i.serviceName.removePrefix("Haven on "), addr)
                        resolveNext()
                    }
                }
            })
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                main.post(finished)
            }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                main.post {
                    resolveQueue.addLast(i)
                    resolveNext()
                }
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
        }
        discovery = l
        try {
            nsd.discoverServices(HAVEN_SERVICE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            discovery = null
            main.post(finished)
            return
        }
        main.postDelayed({
            if (discovery === l) {
                stopFinding()
                finished()
            }
        }, 8000)
    }

    fun stopFinding() {
        val l = discovery ?: return
        discovery = null
        try {
            (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(l)
        } catch (e: Exception) {
        }
        try {
            multicast?.release()
        } catch (e: Exception) {
        }
        multicast = null
    }

    companion object {
        const val HAVEN_PKG = "io.github.jayswansocool48.haven"
        const val HAVEN_PORT = 8765
        const val HAVEN_SERVICE = "_haven._tcp."

        /** "com.netflix.ninja" -> "Netflix" style guesses for when Haven isn't there to ask. */
        fun prettyName(pkg: String): String {
            val skip = setOf("com", "org", "net", "tv", "android", "amazon", "app", "apps", "firetv", "ninja", "plus", "mobile", "io", "github")
            val part = pkg.split('.').lastOrNull { it.lowercase() !in skip } ?: pkg.substringAfterLast('.')
            return part.replaceFirstChar { it.uppercase() }
        }
    }
}
