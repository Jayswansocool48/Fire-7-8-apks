package io.github.jayswansocool48.kiln

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom

/**
 * Kiln's local server, the same API as server.py on the desktop, so the web app runs unchanged:
 *   GET  /api/profiles, /api/<id>/state, /api/<id>/files, /files/<id>/<path>, /api/ai
 *   POST /api/profiles, /api/<id>/state, /api/<id>/upload|delete|move, /api/ai, /api/ai/models, /api/ai/chat, /api/uninstall
 * It listens on 127.0.0.1 only, and every request must carry a secret cookie that only Kiln's
 * own WebView receives, so other apps on the tablet can't read or change your boards.
 */
class KilnServer(
    private val assets: AssetManager,
    private val root: File,
    private val onUninstall: () -> Unit,
) : NanoHTTPD("127.0.0.1", 0) {

    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val prof = File(root, "profiles").apply { mkdirs() }
    private val pj = File(root, "profiles.json")
    private val ai = Assistant(root)

    override fun serve(s: IHTTPSession): Response {
        val path = s.uri ?: "/"
        val q = s.parameters
        // First page load: /?k=<token> sets the cookie, then redirects to a clean URL.
        if (path == "/" && q["k"]?.firstOrNull() == token) {
            return newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, MIME_PLAINTEXT, "").apply {
                addHeader("Location", "/")
                addHeader("Set-Cookie", "kiln=$token; Path=/; HttpOnly; SameSite=Strict")
            }
        }
        if (!trusted(s)) return text(Response.Status.FORBIDDEN, "{}")
        return try {
            if (s.method == Method.POST) post(s, path, q) else get(path)
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "error").toString(), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun trusted(s: IHTTPSession): Boolean {
        val cookie = s.headers["cookie"] ?: return false
        if (cookie.split(';').none { it.trim() == "kiln=$token" }) return false
        val origin = s.headers["origin"] ?: return true
        return origin.startsWith("http://127.0.0.1:") || origin.startsWith("http://localhost:")
    }

    // ---------- GET ----------
    private fun get(path: String): Response {
        val parts = path.trim('/').split('/')
        if (path == "/api/ai") return json(ai.public().toString())
        if (path == "/api/profiles") return json(if (pj.exists()) pj.readText() else "{}")
        if (parts[0] == "api" && parts.size == 3) {
            val d = pdir(parts[1]) ?: return notFound()
            return when (parts[2]) {
                "state" -> json(File(d, "state.json").let { if (it.exists()) it.readText() else "{}" })
                "files" -> json(listFiles(File(d, "files").canonicalFile).toString())
                else -> notFound()
            }
        }
        if (parts[0] == "files" && parts.size >= 3) {
            val d = pdir(parts[1]) ?: return notFound()
            val f = safe(File(d, "files"), parts.drop(2).joinToString("/")) ?: return notFound()
            if (!f.isFile) return notFound()
            return newFixedLengthResponse(Response.Status.OK, mime(f.name), FileInputStream(f), f.length()).apply { addHeader("Cache-Control", "max-age=3600") }
        }
        return asset(if (path == "/") "/index.html" else path)
    }

    private fun asset(path: String): Response {
        if (path.contains("..")) return notFound()
        return try {
            val stream = assets.open("web$path")
            newChunkedResponse(Response.Status.OK, mime(path), stream).apply { addHeader("Cache-Control", "no-cache") }
        } catch (e: IOException) {
            notFound()
        }
    }

    // ---------- POST ----------
    private fun post(s: IHTTPSession, path: String, q: Map<String, List<String>>): Response {
        val body = readBody(s)
        val parts = path.trim('/').split('/')
        when (path) {
            "/api/profiles" -> { write(pj, body); return json("{}") }
            "/api/uninstall" -> {
                if (q["data"]?.firstOrNull() == "1") root.listFiles()?.forEach { it.deleteRecursively() }
                onUninstall()
                return json(JSONObject().put("ok", true).put("message", "").toString())
            }
            "/api/ai/chat" -> return chat(body)
            "/api/ai" -> return json(ai.update(JSONObject(String(body).ifBlank { "{}" })).toString())
            "/api/ai/models" -> {
                val d = JSONObject(String(body).ifBlank { "{}" })
                return try {
                    json(JSONObject().put("models", JSONArray(ai.models(d.optString("provider"), d.optString("key"), d.optString("url")))).toString())
                } catch (e: Assistant.AIError) {
                    json(JSONObject().put("error", e.message).toString())
                }
            }
        }
        if (parts[0] != "api" || parts.size != 3) return notFound()
        val pid = parts[1]
        val d = pdir(pid) ?: return notFound()
        val fd = File(d, "files").canonicalFile  // filesDir is a symlink on Android; compare real paths
        return when (parts[2]) {
            "state" -> { write(File(d, "state.json"), body); json("{}") }
            "upload" -> {
                var p = safe(fd, q["path"]?.firstOrNull() ?: "") ?: return text(Response.Status.BAD_REQUEST, "{}")
                if (q["overwrite"].isNullOrEmpty()) p = unique(p)
                write(p, body)
                json(JSONObject().put("url", fileUrl(pid, p, fd)).toString())
            }
            "delete" -> {
                val p = safe(fd, q["path"]?.firstOrNull() ?: "")
                if (p != null && p.isFile) { p.delete(); json("{}") } else text(Response.Status.BAD_REQUEST, "{}")
            }
            "move" -> {
                val src = safe(fd, q["path"]?.firstOrNull() ?: "")
                val to = (q["to"]?.firstOrNull() ?: "").trim('/')
                val dd = if (to.isNotEmpty()) safe(fd, to) else null
                if (src == null || dd == null || !src.isFile) return text(Response.Status.BAD_REQUEST, "{}")
                dd.mkdirs()
                val p = unique(File(dd, src.name))
                if (!src.renameTo(p)) return text(Response.Status.BAD_REQUEST, "{}")
                json(JSONObject().put("url", fileUrl(pid, p, fd)).toString())
            }
            else -> notFound()
        }
    }

    /** Streams the assistant's reply as newline-delimited JSON, like server.py: {"t":…} … {"done":true} or {"error":…}. */
    private fun chat(body: ByteArray): Response {
        val req = JSONObject(String(body).ifBlank { "{}" })
        val input = PipedInputStream(64 * 1024)
        val out = PipedOutputStream(input)
        Thread {
            fun send(o: JSONObject) { out.write((o.toString() + "\n").toByteArray()); out.flush() }
            try {
                ai.chat(req.optJSONArray("messages") ?: JSONArray(), req.optJSONObject("context")) { send(JSONObject().put("t", it)) }
                send(JSONObject().put("done", true))
            } catch (e: Assistant.AIError) {
                runCatching { send(JSONObject().put("error", e.message)) }
            } catch (e: IOException) {
                // the user pressed Stop and the page closed the stream
            } catch (e: Exception) {
                runCatching { send(JSONObject().put("error", "Something went wrong talking to the assistant: ${e.message}")) }
            } finally {
                runCatching { out.close() }
            }
        }.start()
        return newChunkedResponse(Response.Status.OK, "application/x-ndjson", input).apply { addHeader("Cache-Control", "no-store") }
    }

    // ---------- helpers ----------
    private fun readBody(s: IHTTPSession): ByteArray {
        val n = s.headers["content-length"]?.toLongOrNull() ?: 0L
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n.toInt())
        var off = 0
        val ins: InputStream = s.inputStream
        while (off < buf.size) { val r = ins.read(buf, off, buf.size - off); if (r < 0) break; off += r }
        return if (off == buf.size) buf else buf.copyOf(off)
    }

    /** /files/<id>/<path> with each part URL-encoded, like server.py returns. */
    private fun fileUrl(pid: String, p: File, fd: File) =
        "/files/$pid/" + p.relativeTo(fd).invariantSeparatorsPath.split('/').joinToString("/") { android.net.Uri.encode(it) }

    private fun pdir(pid: String): File? = if (Regex("[a-z0-9]{3,16}").matches(pid)) File(prof, pid) else null

    private fun safe(base: File, rel: String): File? {
        val b = base.canonicalFile
        // NanoHTTPD has already decoded the path and query, so don't decode again (names may contain + or %).
        val f = File(b, rel.trimStart('/')).canonicalFile
        return if (f.path.startsWith(b.path + File.separator)) f else null
    }

    private fun unique(p: File): File {
        var f = p; var i = 1
        val base = p.nameWithoutExtension; val ext = if (p.extension.isEmpty()) "" else "." + p.extension
        while (f.exists()) { f = File(p.parentFile, "${base}_$i$ext"); i++ }
        return f
    }

    private fun write(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun listFiles(fd: File): JSONArray {
        val out = fd.walkTopDown().filter { it.isFile }.map { it.relativeTo(fd).invariantSeparatorsPath }.sortedBy { it.lowercase() }
        return JSONArray().apply { out.forEach { put(JSONObject().put("path", it).put("kind", kind(it))) } }
    }

    private fun kind(rel: String): String {
        val top = rel.substringBefore('/').lowercase(); val ext = "." + rel.substringAfterLast('.', "").lowercase()
        return when {
            top == "games" -> "Games"
            top == "textures" -> "Textures"
            top == "fonts" -> "Fonts"
            ext in setOf(".glb", ".gltf", ".obj", ".fbx") -> "Models"
            ext == ".blend" -> "Blender"
            ext in setOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tga", ".exr", ".hdr", ".gif") -> "Images"
            else -> "Other"
        }
    }

    private fun json(s: String, st: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(st, "application/json", s).apply { addHeader("Cache-Control", "no-store") }
    private fun text(st: Response.Status, s: String) = newFixedLengthResponse(st, "application/json", s)
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")

    companion object {
        fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "glb" -> "model/gltf-binary"
            "gltf" -> "model/gltf+json"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
