package io.github.jayswansocool48.haven

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web server inside Haven so Haven Remote on a phone can show the TV's apps with their icons.
 * It only lists apps and hands out icons; the remote opens apps and presses buttons over ADB.
 *
 *   GET /ping               -> {"app":"Haven","name":"<TV model>","version":"1.0.0"}
 *   GET /apps               -> [{"pkg","activity","label","tv","fav"}...] favorites first
 *   GET /icon?pkg=<package> -> PNG
 */
object ShareServer {
    const val PORT = 8765
    const val SERVICE_TYPE = "_haven._tcp."

    @Volatile private var started = false
    private var nsdListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(name = "haven-share", isDaemon = true) {
            try {
                ServerSocket(PORT).use { server ->
                    server.reuseAddress = true
                    while (true) {
                        val s = server.accept()
                        thread(isDaemon = true) { handle(app, s) }
                    }
                }
            } catch (e: Exception) {
                started = false
            }
        }
        advertise(app)
    }

    private fun advertise(ctx: Context) {
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Haven on ${Build.MODEL}"
                serviceType = SERVICE_TYPE
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (e: Exception) {
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val target = requestLine.split(' ').getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val out = sock.getOutputStream()
                when (path) {
                    "/ping" -> json(out, JSONObject().apply {
                        put("app", "Haven")
                        put("name", Build.MODEL)
                        put("version", version(ctx))
                    }.toString())
                    "/apps" -> json(out, appsJson(ctx))
                    "/icon" -> icon(ctx, out, query["pkg"].orEmpty())
                    else -> send(out, 404, "text/plain", "not found".toByteArray())
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun appsJson(ctx: Context): String {
        val repo = AppRepo(ctx)
        val apps = repo.load()
        val favs = repo.favorites
        val hidden = repo.hidden
        val ordered = favs.mapNotNull { f -> apps.firstOrNull { it.pkg == f } } + apps.filter { it.pkg !in favs && it.pkg !in hidden }
        val arr = JSONArray()
        for (a in ordered) {
            arr.put(JSONObject().apply {
                put("pkg", a.pkg)
                put("activity", a.activity)
                put("label", a.label)
                put("tv", a.tv)
                put("fav", a.pkg in favs)
            })
        }
        return arr.toString()
    }

    private fun icon(ctx: Context, out: OutputStream, pkg: String) {
        val repo = AppRepo(ctx)
        val app = repo.load().firstOrNull { it.pkg == pkg }
        if (app == null) {
            send(out, 404, "text/plain", "no such app".toByteArray())
            return
        }
        val bmp = repo.icon(app).toBitmap(144)
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        send(out, 200, "image/png", bytes.toByteArray(), cache = true)
    }

    private fun json(out: OutputStream, body: String) = send(out, 200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val status = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            (if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
        val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
        k to v
    }

    private fun version(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
