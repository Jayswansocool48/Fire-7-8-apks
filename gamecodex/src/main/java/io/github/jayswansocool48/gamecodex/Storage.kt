package io.github.jayswansocool48.gamecodex

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves games the same way the desktop app does, inside the app's private folder:
 *   game-codex/projects/<id>.json   one file per game
 *   game-codex/media/<id>.png|jpg   sketches and images
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

    fun deleteAll() {
        root.deleteRecursively()
        projects.mkdirs()
        media.mkdirs()
    }

    // ---------- export and import (.gamecodex files are zips, same as the desktop app) ----------

    fun exportZip(project: JSONObject, markdown: String): ByteArray {
        val out = ByteArrayOutputStream()
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
                zip.write(f.readBytes())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Returns the imported game with fresh picture ids, or throws IllegalArgumentException. */
    fun importBytes(bytes: ByteArray): JSONObject {
        val notOurs = IllegalArgumentException("That file isn't a Game Codex export.")
        val files = HashMap<String, ByteArray>()
        val data: JSONObject
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) {
            var projectJson: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "project.json") projectJson = String(zip.readBytes())
                    else if (name.startsWith("media/") && MEDIA_ID.matches(name.removePrefix("media/"))) {
                        files[name.removePrefix("media/")] = zip.readBytes()
                    }
                }
            }
            data = try { JSONObject(projectJson ?: throw notOurs) } catch (e: JSONException) { throw notOurs }
        } else {
            data = try { JSONObject(String(bytes)) } catch (e: JSONException) { throw notOurs }
            val inline = data.optJSONObject("media")
            inline?.keys()?.forEach { id ->
                val raw = decodeDataUrl(inline.optString(id))
                if (MEDIA_ID.matches(id) && raw != null) files[id] = raw
            }
        }
        if (data.optString("format") != "game-codex") throw notOurs
        val project = data.optJSONObject("project") ?: throw notOurs
        if (!PROJECT_ID.matches(project.optString("id"))) project.put("id", "p" + UUID.randomUUID().toString().replace("-", "").take(12))

        // New ids so an imported copy never shares picture files with another game.
        val mapping = LinkedHashMap<String, String>()
        for (id in projectMedia(project)) {
            val raw = files[id] ?: continue
            val newId = "m" + UUID.randomUUID().toString().replace("-", "").take(14) + id.substring(id.lastIndexOf('.'))
            writeAtomic(File(media, newId), raw)
            mapping[id] = newId
        }
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

    /** Every picture a game uses: entry art plus its cover. */
    fun projectMedia(project: JSONObject): List<String> {
        val ids = LinkedHashSet<String>()
        project.optString("cover").takeIf { it.isNotEmpty() && it != "null" }?.let { ids.add(it) }
        forEachArt(project) { a -> a.optString("id").takeIf { it.isNotEmpty() }?.let { ids.add(it) } }
        return ids.filter { MEDIA_ID.matches(it) }
    }

    private fun remapMedia(project: JSONObject, mapping: Map<String, String>) {
        mapping[project.optString("cover")]?.let { project.put("cover", it) }
        forEachArt(project) { a -> mapping[a.optString("id")]?.let { a.put("id", it) } }
        project.put("media", JSONArray(mapping.values.toList()))
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
        val MEDIA_ID = Regex("^[A-Za-z0-9_-]{1,64}\\.(png|jpg)$")
        val DATA_URL = Regex("^data:image/(png|jpeg);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}
