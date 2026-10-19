package io.github.jayswansocool48.gamecodex

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The assistant: Ollama (free, on the user's computer, reached over Wi-Fi), Claude (Anthropic API)
 * or ChatGPT (OpenAI API). Same behaviour as the desktop app's assistant.py.
 *
 * Settings, including API keys, are kept in the app's private storage. Keys never reach the page:
 * it only sees whether one is saved and its last four characters.
 */
class Assistant(private val file: File) {
    class AIError(message: String, val unreachable: Boolean = false) : Exception(message)
    class Stopped : Exception()

    private val providers = listOf("ollama", "anthropic", "openai")
    private val names = mapOf("ollama" to "Ollama", "anthropic" to "Claude", "openai" to "ChatGPT")
    private val defaultModels = mapOf("ollama" to "llama3.2", "anthropic" to "claude-sonnet-5-5", "openai" to "")

    // ---------- settings ----------

    fun load(): JSONObject {
        val c = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
        if (!c.has("provider")) c.put("provider", "")
        if (c.optString("ollama_url").isBlank()) c.put("ollama_url", "http://localhost:11434")
        val models = c.optJSONObject("models") ?: JSONObject()
        for ((p, m) in defaultModels) if (!models.has(p)) models.put(p, m)
        c.put("models", models)
        val keys = JSONObject()
        c.optJSONObject("keys")?.let { k -> for (p in listOf("anthropic", "openai")) k.optString(p).takeIf { it.isNotBlank() }?.let { keys.put(p, it) } }
        c.put("keys", keys)
        return c
    }

    private fun store(c: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(c.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun public(c: JSONObject = load()): JSONObject {
        val keys = c.getJSONObject("keys")
        val shown = JSONObject()
        for (p in listOf("anthropic", "openai")) shown.put(p, keys.optString(p).let { if (it.isNotEmpty()) "…" + it.takeLast(4) else "" })
        return JSONObject()
            .put("provider", c.optString("provider"))
            .put("models", c.getJSONObject("models"))
            .put("ollama_url", c.optString("ollama_url"))
            .put("keys", shown)
            .put("ready", ready(c))
    }

    private fun ready(c: JSONObject): Boolean = when (val p = c.optString("provider")) {
        "ollama" -> true
        "anthropic", "openai" -> c.getJSONObject("keys").optString(p).isNotEmpty() && c.getJSONObject("models").optString(p).isNotEmpty()
        else -> false
    }

    fun update(data: JSONObject?): JSONObject {
        val c = load()
        val d = data ?: JSONObject()
        if (d.has("provider") && d.optString("provider") in providers + "") c.put("provider", d.optString("provider"))
        d.optString("ollama_url").trim().takeIf { it.isNotEmpty() }?.let { c.put("ollama_url", it.trimEnd('/')) }
        d.optJSONObject("models")?.let { m -> m.keys().forEach { p -> if (p in providers) c.getJSONObject("models").put(p, m.optString(p).trim()) } }
        d.optJSONObject("keys")?.let { k ->
            k.keys().forEach { p ->
                if (p !in listOf("anthropic", "openai")) return@forEach
                val v = k.optString(p).trim()
                if (v.isNotEmpty()) c.getJSONObject("keys").put(p, v)
                else if (d.optString("clear_key") == p) c.getJSONObject("keys").remove(p)
            }
        }
        store(c)
        return public(c)
    }

    fun forget() { file.delete() }

    // ---------- HTTP ----------

    private fun open(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw AIError(e.message ?: "bad address", unreachable = true) }
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "GameCodex")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                val bytes = body.toString().toByteArray()
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            if (status >= 400) {
                val text = try { c.errorStream?.use { String(it.readBytes()) } ?: "" } catch (e: IOException) { "" }
                c.disconnect()
                throw AIError(httpMessage(status, text))
            }
            return c
        } catch (e: AIError) {
            throw e
        } catch (e: IOException) {
            c.disconnect()
            throw AIError(e.message ?: e.javaClass.simpleName, unreachable = true)
        }
    }

    private fun httpMessage(status: Int, text: String): String {
        val msg = try {
            val d = JSONObject(text.ifEmpty { "{}" })
            d.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: d.optString("error").takeIf { it.isNotEmpty() } ?: d.optString("message")
        } catch (e: Exception) { "" }
        val hint = when (status) {
            401 -> "The API key was rejected. Check it in Settings."
            403 -> "This API key isn't allowed to use that model."
            404 -> "That model wasn't found. Pick another one in Settings."
            429 -> "Rate limit or quota reached. Try again in a moment, or check your plan's credits."
            else -> ""
        }
        return listOf(hint, msg.ifEmpty { "HTTP $status" }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun headersFor(p: String, key: String) = when (p) {
        "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        "openai" -> mapOf("Authorization" to "Bearer $key")
        else -> emptyMap()
    }

    private fun ollamaHint(url: String, e: AIError) = AIError(
        "Can't reach Ollama at $url (${e.message}). Ollama runs on your computer: start it there with " +
            "OLLAMA_HOST=0.0.0.0 ollama serve, and type the computer's address in Settings, like http://192.168.1.20:11434."
    )

    private fun readJson(c: HttpURLConnection): JSONObject = c.inputStream.use { JSONObject(String(it.readBytes())) }.also { c.disconnect() }

    // ---------- models ----------

    fun models(provider: String?, key: String?, url: String?): JSONArray {
        val c = load()
        val p = provider?.takeIf { it.isNotEmpty() } ?: c.optString("provider")
        try {
            if (p == "ollama") {
                val base = (url?.takeIf { it.isNotBlank() } ?: c.optString("ollama_url")).trimEnd('/')
                val list = readJson(open("$base/api/tags")).optJSONArray("models") ?: JSONArray()
                return JSONArray((0 until list.length()).map { list.getJSONObject(it).optString("name") }.sorted())
            }
            if (p !in listOf("anthropic", "openai")) throw AIError("Choose an assistant first.")
            val k = key?.takeIf { it.isNotBlank() } ?: c.getJSONObject("keys").optString(p)
            if (k.isEmpty()) throw AIError("Add your ${names[p]} API key first.")
            if (p == "anthropic") {
                val d = readJson(open("https://api.anthropic.com/v1/models?limit=100", headers = headersFor(p, k))).optJSONArray("data") ?: JSONArray()
                return JSONArray((0 until d.length()).map { d.getJSONObject(it).optString("id") })
            }
            val d = readJson(open("https://api.openai.com/v1/models", headers = headersFor(p, k))).optJSONArray("data") ?: JSONArray()
            val ids = (0 until d.length()).map { d.getJSONObject(it).optString("id") }
            val skip = listOf("audio", "realtime", "tts", "transcribe", "image", "embedding", "search")
            val chat = ids.filter { i -> (i.startsWith("gpt-") || i.startsWith("o") || i.startsWith("chatgpt")) && skip.none { i.contains(it) } }
            return JSONArray(chat.ifEmpty { ids }.sortedDescending())
        } catch (e: AIError) {
            if (p == "ollama" && e.unreachable) throw ollamaHint(url ?: c.optString("ollama_url"), e)
            throw e
        }
    }

    // ---------- chat ----------

    /** Streams the reply: emit(text) for each piece. Throws AIError, or Stopped when stopped() turns true. */
    fun chat(messages: JSONArray?, context: JSONObject?, emit: (String) -> Unit, stopped: () -> Boolean) {
        val c = load()
        val p = c.optString("provider")
        if (!ready(c)) throw AIError("Set up the assistant first: open Settings and choose Ollama, Claude or ChatGPT.")
        val msgs = JSONArray()
        val all = messages ?: JSONArray()
        for (i in maxOf(0, all.length() - 24) until all.length()) {
            val m = all.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.optString("content")
            if ((role == "user" || role == "assistant") && content.isNotEmpty()) msgs.put(JSONObject().put("role", role).put("content", content.take(20000)))
        }
        val system = systemPrompt(context ?: JSONObject())
        val model = c.getJSONObject("models").optString(p).ifEmpty { defaultModels[p] ?: "" }
        fun out(t: String?) {
            if (stopped()) throw Stopped()
            if (!t.isNullOrEmpty()) emit(t)
        }
        fun withSystem(): JSONArray = JSONArray().put(JSONObject().put("role", "system").put("content", system)).also { a -> for (i in 0 until msgs.length()) a.put(msgs.get(i)) }

        when (p) {
            "ollama" -> {
                val url = c.optString("ollama_url")
                val conn = try {
                    open("$url/api/chat", JSONObject().put("model", model).put("stream", true).put("messages", withSystem()))
                } catch (e: AIError) {
                    if (e.unreachable) throw ollamaHint(url, e)
                    if (e.message?.contains("not found", true) == true) throw AIError("Ollama doesn't have the model “$model” yet. Run `ollama pull $model` on your computer, or pick another model in Settings.")
                    throw e
                }
                conn.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        val d = JSONObject(line)
                        if (d.optString("error").isNotEmpty()) throw AIError(d.optString("error"))
                        out(d.optJSONObject("message")?.optString("content"))
                        if (d.optBoolean("done")) break
                    }
                }
                conn.disconnect()
            }
            "anthropic" -> {
                val body = JSONObject().put("model", model).put("max_tokens", 2048).put("system", system).put("messages", msgs).put("stream", true)
                val conn = open("https://api.anthropic.com/v1/messages", body, headersFor(p, c.getJSONObject("keys").optString(p)))
                sse(conn) { d ->
                    when (d.optString("type")) {
                        "content_block_delta" -> d.optJSONObject("delta")?.let { if (it.optString("type") == "text_delta") out(it.optString("text")) }
                        "error" -> throw AIError(d.optJSONObject("error")?.optString("message") ?: "Claude returned an error.")
                        "message_stop" -> return@sse false
                    }
                    true
                }
            }
            "openai" -> {
                val body = JSONObject().put("model", model).put("stream", true).put("messages", withSystem())
                val conn = open("https://api.openai.com/v1/chat/completions", body, headersFor(p, c.getJSONObject("keys").optString(p)))
                sse(conn) { d ->
                    d.optJSONObject("error")?.let { throw AIError(it.optString("message", "ChatGPT returned an error.")) }
                    val choices = d.optJSONArray("choices") ?: JSONArray()
                    for (i in 0 until choices.length()) out(choices.optJSONObject(i)?.optJSONObject("delta")?.optString("content"))
                    true
                }
            }
        }
    }

    /** Reads a server-sent-events stream; onEvent returns false to stop. */
    private fun sse(conn: HttpURLConnection, onEvent: (JSONObject) -> Boolean) {
        try {
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine()?.trim() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val d = try { JSONObject(data) } catch (e: Exception) { continue }
                    if (!onEvent(d)) break
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun clip(v: Any?, n: Int): String {
        val s = when (v) {
            is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
            null -> ""
            else -> v.toString()
        }
        return if (s.length <= n) s else s.take(n - 1) + "…"
    }

    private fun systemPrompt(ctx: JSONObject): String {
        val lines = mutableListOf(
            "You are the assistant built into Game Codex, an app where a game developer designs games (characters, " +
                "props, story and quests, mechanics, levels, trailers) and tracks building their art: every character, prop and " +
                "level has a production stage (Planned, Modeled, Complete), a polycount, a Blender file and production tasks, " +
                "and 3D models open in a Blender-style 3D studio.",
            "Help with: brainstorming characters, props, levels, quests and mechanics; writing descriptions, backstories, " +
                "dialogue and art briefs; breaking an asset into modeling and texturing tasks; polycount and texture budgets; " +
                "naming; Blender modeling, texturing and rigging tips; engine import advice.",
            "Be concise and practical. Use short paragraphs. When you list ideas, assets or tasks, put each on its own line " +
                "starting with '- ' and keep it under 80 characters, so the user can add the list straight to their game.",
            "Don't claim to have changed the user's game yourself; suggest, and the user will add things.",
        )
        ctx.optJSONObject("game")?.let { g ->
            lines.add("\nThe game:")
            for ((k, label) in listOf("name" to "Name", "pitch" to "Pitch", "genre" to "Genre", "platforms" to "Platforms", "audience" to "Who it's for", "artStyle" to "Art style")) {
                val v = g.opt(k)
                if (v != null && v != JSONObject.NULL && clip(v, 600).isNotEmpty()) lines.add("$label: ${clip(v, 600)}")
            }
        }
        ctx.optJSONObject("sections")?.let { s ->
            lines.add("\nWhat's in it so far (name, and details in brackets):")
            s.keys().asSequence().take(8).forEach { name ->
                val items = s.optJSONArray(name) ?: return@forEach
                val joined = (0 until minOf(40, items.length())).joinToString("; ") { items.optString(it) }
                lines.add("- $name: ${clip(joined.ifEmpty { "none yet" }, 1500)}")
            }
        }
        ctx.optJSONObject("entry")?.let { e ->
            lines.add("\nThe user has this ${e.optString("section").ifEmpty { "entry" }} open:")
            e.optJSONObject("fields")?.let { f ->
                f.keys().asSequence().take(30).forEach { k ->
                    val v = f.opt(k)
                    val text = clip(v, 700)
                    if (text.isNotEmpty() && text != "[]" && text != "{}" && text != "null") lines.add("$k: $text")
                }
            }
        }
        return lines.joinToString("\n")
    }
}
