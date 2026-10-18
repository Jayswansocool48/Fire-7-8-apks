package io.github.jayswansocool48.gamecodex

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves games the same way the desktop app does, inside the app's private folder:
 *   game-codex/projects/<id>.json   one file per game
 *   game-codex/media/<id>.<ext>     sketches, images, videos, sounds and 3D models
 *   game-codex/settings.json
 * Android removes all of it when the app is uninstalled.
 */
class Storage(appFiles: File) {
    val root = File(appFiles, "game-codex")
    private val projects = File(root, "projects")
    private val media = File(root, "media")
    private val settings = File(root, "settings.json")

    init {
        projects.mkdirs()
        media.mkdirs()
    }

    // ---------- loading ----------

    fun boot(version: String, mediaBase: String): JSONObject {
        val list = JSONArray()
        projects.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            val p = readJson(f)
            if (p != null && PROJECT_ID.matches(p.optString("id"))) list.put(p)
        }
        return JSONObject()
            .put("projects", list)
            .put("settings", readJson(settings) ?: JSONObject())
            .put("mediaBase", mediaBase)
            .put("version", version)
    }

    private fun readJson(f: File): JSONObject? = try {
        JSONObject(f.readText())
    } catch (e: Exception) {
        null
    }

    // ---------- saving ----------

    /** Write to a temporary file first so a crash never leaves a half-written save. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun saveProject(project: JSONObject?) {
        val id = project?.optString("id") ?: return
        if (!PROJECT_ID.matches(id)) return
        writeAtomic(File(projects, "$id.json"), project.toString().toByteArray())
    }

    fun deleteProject(id: String) {
        if (PROJECT_ID.matches(id)) File(projects, "$id.json").delete()
    }

    fun saveSettings(value: JSONObject) {
        writeAtomic(settings, value.toString().toByteArray())
    }

    fun mediaFile(id: String): File? = if (MEDIA_ID.matches(id)) File(media, id) else null

    fun saveMedia(id: String, dataUrl: String) {
        val file = mediaFile(id) ?: return
        val bytes = decodeDataUrl(dataUrl) ?: return
        writeAtomic(file, bytes)
    }

    fun copyMedia(from: String, to: String) {
        val src = mediaFile(from) ?: return
        val dst = mediaFile(to) ?: return
        if (src.exists()) src.copyTo(dst, overwrite = true)
    }

    fun deleteMedia(ids: JSONArray?) {
        if (ids == null) return
        for (i in 0 until ids.length()) mediaFile(ids.optString(i))?.delete()
    }

    fun missingMedia(ids: JSONArray?): JSONArray {
        val out = JSONArray()
        if (ids == null) return out
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            val f = mediaFile(id) ?: continue
            if (!f.exists()) out.put(id)
        }
        return out
    }

    /** Copy a picked file into the media folder. Returns its entry for the page, or null for unknown types. */
    fun addMediaFile(name: String, input: InputStream): JSONObject? {
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "jpeg") ext = "jpg"
        val kind = KINDS[ext] ?: return null
        val id = newId(ext)
        val target = File(media, id)
        val tmp = File(media, "$id.tmp")
        tmp.outputStream().use { input.copyTo(it, 256 * 1024) }
        tmp.renameTo(target)
        return JSONObject().put("id", id).put("name", name).put("size", target.length()).put("kind", kind)
    }

    private fun newId(ext: String) = "m" + UUID.randomUUID().toString().replace("-", "").take(14) + "." + ext

    fun deleteAll() {
        root.deleteRecursively()
        projects.mkdirs()
        media.mkdirs()
    }

    // ---------- export and import (.gamecodex files are zips, same as the desktop app) ----------

    fun exportZip(project: JSONObject, markdown: String, out: java.io.OutputStream) {
        ZipOutputStream(out).use { zip ->
            val wrapper = JSONObject().put("format", "game-codex").put("version", 1).put("project", project)
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(wrapper.toString(1).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("design.md"))
            zip.write(markdown.toByteArray())
            zip.closeEntry()
            for (id in projectMedia(project)) {
                val f = mediaFile(id) ?: continue
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("media/$id"))
                f.inputStream().use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
            }
        }
    }

    /** Store kit: generated pictures and text from the page, plus trailer files from the media folder. */
    fun exportKit(files: JSONArray?, kitMedia: JSONArray?, out: java.io.OutputStream) {
        fun safe(path: String) = path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it == ".." }
        ZipOutputStream(out).use { zip ->
            if (files != null) for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val path = f.optString("path")
                if (!safe(path)) continue
                val data = f.optString("data")
                val bytes = if (data.startsWith("data:")) decodeAnyDataUrl(data) else data.toByteArray()
                if (bytes == null) continue
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            if (kitMedia != null) for (i in 0 until kitMedia.length()) {
                val m = kitMedia.optJSONObject(i) ?: continue
                val src = mediaFile(m.optString("id")) ?: continue
                val path = m.optString("path")
                if (!src.exists() || !safe(path)) continue
                zip.putNextEntry(ZipEntry(path))
                src.inputStream().use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a .gamecodex file (a zip) or a browser export (.json) as a stream, so big videos never
     * have to fit in memory. Returns the game with fresh file ids, or throws IllegalArgumentException.
     */
    fun importStream(input: InputStream): JSONObject {
        val notOurs = IllegalArgumentException("That file isn't a Game Codex export.")
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val isZip = buffered.read() == 'P'.code && buffered.read() == 'K'.code
        buffered.reset()
        val mapping = LinkedHashMap<String, String>()
        val project: JSONObject
        if (isZip) {
            var found: JSONObject? = null
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "project.json") {
                        val data = try { JSONObject(String(zip.readBytes())) } catch (e: JSONException) { throw notOurs }
                        if (data.optString("format") != "game-codex") throw notOurs
                        found = data.optJSONObject("project") ?: throw notOurs
                    } else if (name.startsWith("media/") && MEDIA_ID.matches(name.removePrefix("media/"))) {
                        val old = name.removePrefix("media/")
                        val id = newId(old.substringAfterLast('.'))
                        val tmp = File(media, "$id.tmp")
                        tmp.outputStream().use { zip.copyTo(it, 256 * 1024) }
                        tmp.renameTo(File(media, id))
                        mapping[old] = id
                    }
                }
            }
            project = found ?: throw notOurs
            // drop files the game doesn't use (if any)
            val used = projectMedia(project).toSet()
            mapping.entries.removeAll { (old, new) -> if (old !in used) { File(media, new).delete(); true } else false }
        } else {
            val data = try { JSONObject(String(buffered.readBytes())) } catch (e: JSONException) { throw notOurs }
            if (data.optString("format") != "game-codex") throw notOurs
            project = data.optJSONObject("project") ?: throw notOurs
            val inline = data.optJSONObject("media")
            for (old in projectMedia(project)) {
                val raw = inline?.optString(old)?.let { decodeAnyDataUrl(it) } ?: continue
                val id = newId(old.substringAfterLast('.'))
                writeAtomic(File(media, id), raw)
                mapping[old] = id
            }
        }
        if (!PROJECT_ID.matches(project.optString("id"))) project.put("id", "p" + UUID.randomUUID().toString().replace("-", "").take(12))
        remapMedia(project, mapping)
        return project
    }

    private fun forEachArt(project: JSONObject, action: (JSONObject) -> Unit) {
        val entries = project.optJSONObject("entries") ?: return
        entries.keys().forEach { section ->
            val list = entries.optJSONArray(section) ?: return@forEach
            for (i in 0 until list.length()) {
                val art = list.optJSONObject(i)?.optJSONArray("art") ?: continue
                for (j in 0 until art.length()) art.optJSONObject(j)?.let(action)
            }
        }
    }

    /** Every file a game uses: entry art, model posters and its cover. */
    fun projectMedia(project: JSONObject): List<String> {
        val ids = LinkedHashSet<String>()
        project.optString("cover").takeIf { it.isNotEmpty() && it != "null" }?.let { ids.add(it) }
        forEachArt(project) { a ->
            for (key in arrayOf("id", "poster")) a.optString(key).takeIf { it.isNotEmpty() && it != "null" }?.let { ids.add(it) }
        }
        return ids.filter { MEDIA_ID.matches(it) }
    }

    private fun remapMedia(project: JSONObject, mapping: Map<String, String>) {
        mapping[project.optString("cover")]?.let { project.put("cover", it) }
        forEachArt(project) { a -> for (key in arrayOf("id", "poster")) mapping[a.optString(key)]?.let { a.put(key, it) } }
        project.put("media", JSONArray(mapping.values.toList()))
    }

    private fun decodeAnyDataUrl(data: String): ByteArray? {
        val comma = data.indexOf(',')
        if (!data.startsWith("data:") || comma < 0 || !data.substring(0, comma).endsWith(";base64")) return null
        return try {
            Base64.decode(data.substring(comma + 1), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeDataUrl(data: String): ByteArray? {
        val m = DATA_URL.matchEntire(data) ?: return null
        return try {
            Base64.decode(m.groupValues[2], Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        val PROJECT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        // Must match MEDIA_ID in web/js/store.js and the desktop app.
        val KINDS = mapOf(
            "png" to "image", "jpg" to "image", "webp" to "image", "gif" to "image",
            "mp4" to "video", "webm" to "video", "mov" to "video", "m4v" to "video",
            "mp3" to "audio", "ogg" to "audio", "wav" to "audio", "m4a" to "audio", "flac" to "audio",
            "glb" to "model", "gltf" to "model", "obj" to "model", "stl" to "model", "fbx" to "model",
        )
        val MEDIA_ID = Regex("^[A-Za-z0-9_-]{1,64}\\.(png|jpg|webp|gif|mp4|webm|mov|m4v|mp3|ogg|wav|m4a|flac|glb|gltf|obj|stl|fbx)$")
        val DATA_URL = Regex("^data:image/(png|jpeg);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}
