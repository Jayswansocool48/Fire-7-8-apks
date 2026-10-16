package io.github.jayswansocool48.kiln

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kiln's assistant on Android: Ollama (on your PC), Claude (Anthropic API) or ChatGPT (OpenAI API).
 * Same settings file and behaviour as ai.py on the desktop. API keys stay in the app's private
 * storage; the page only ever sees whether a key is saved and its last four characters.
 */
class Assistant(root: File) {
    class AIError(msg: String, val unreachable: Boolean = false) : Exception(msg)

    private val file = File(root, "ai.json")
    private val providers = listOf("ollama", "anthropic", "openai")
    private val names = mapOf("ollama" to "Ollama", "anthropic" to "Claude", "openai" to "ChatGPT")
    private val defaults = mapOf("ollama" to "llama3.2", "anthropic" to "claude-sonnet-5-5", "openai" to "")

    // ---------- settings ----------
    @Synchronized private fun load(): JSONObject {
        val c = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
        if (!c.has("provider")) c.put("provider", "")
        if (!c.has("ollama_url")) c.put("ollama_url", "http://192.168.1.2:11434")
        val models = JSONObject(); defaults.forEach { (k, v) -> models.put(k, v) }
        c.optJSONObject("models")?.let { m -> m.keys().forEach { models.put(it, m.optString(it)) } }
        c.put("models", models)
        val keys = JSONObject(); c.optJSONObject("keys")?.let { k -> listOf("anthropic", "openai").forEach { p -> k.optString(p).takeIf { it.isNotEmpty() }?.let { keys.put(p, it) } } }
        c.put("keys", keys)
        return c
    }

    @Synchronized private fun store(c: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp"); tmp.writeText(c.toString(2)); tmp.renameTo(file)
    }

    fun public(c: JSONObject = load()): JSONObject {
        val keys = JSONObject()
        listOf("anthropic", "openai").forEach { p -> val k = c.getJSONObject("keys").optString(p); keys.put(p, if (k.isNotEmpty()) "…" + k.takeLast(4) else "") }
        return JSONObject().put("provider", c.getString("provider")).put("models", c.getJSONObject("models"))
            .put("ollama_url", c.getString("ollama_url")).put("keys", keys).put("ready", ready(c))
    }

    private fun ready(c: JSONObject): Boolean = when (val p = c.getString("provider")) {
        "ollama" -> true
        "anthropic", "openai" -> c.getJSONObject("keys").optString(p).isNotEmpty() && c.getJSONObject("models").optString(p).isNotEmpty()
        else -> false
    }

    fun update(d: JSONObject): JSONObject {
        val c = load()
        d.optString("provider", null)?.let { if (it in providers || it == "") c.put("provider", it) }
        d.optString("ollama_url").trim().takeIf { it.isNotEmpty() }?.let { c.put("ollama_url", it.trimEnd('/')) }
        d.optJSONObject("models")?.let { m -> m.keys().forEach { p -> if (p in providers) c.getJSONObject("models").put(p, m.optString(p).trim()) } }
        d.optJSONObject("keys")?.let { k ->
            k.keys().forEach { p ->
                if (p !in listOf("anthropic", "openai")) return@forEach
                val v = k.optString(p)
                if (v.isNotBlank()) c.getJSONObject("keys").put(p, v.trim())
                else if (v.isEmpty() && d.optString("clear_key") == p) c.getJSONObject("keys").remove(p)
            }
        }
        store(c)
        return public(c)
    }

    // ---------- HTTP ----------
    private fun open(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw AIError(e.message ?: "bad address", unreachable = true) }
        conn.connectTimeout = 15000; conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) { conn.requestMethod = "POST"; conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = conn.responseCode
            if (code >= 400) throw AIError(httpMessage(code, conn.errorStream?.bufferedReader()?.readText()))
        } catch (e: AIError) { throw e } catch (e: IOException) { throw AIError(e.message ?: "connection failed", unreachable = true) }
        return conn
    }

    private fun httpMessage(code: Int, text: String?): String {
        val msg = try {
            val d = JSONObject(text ?: "{}"); val e = d.opt("error")
            (if (e is JSONObject) e.optString("message") else d.optString("error")).ifEmpty { d.optString("message") }
        } catch (e: Exception) { "" }
        val hint = mapOf(401 to "The API key was rejected. Check it in Settings.", 403 to "This API key isn't allowed to use that model.",
            404 to "That model wasn't found. Pick another one in Settings.", 429 to "Rate limit or quota reached. Try again in a moment, or check your plan's credits.")[code] ?: ""
        return listOf(hint, msg.ifEmpty { "HTTP $code" }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun headers(p: String, key: String) = when (p) {
        "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        "openai" -> mapOf("Authorization" to "Bearer $key")
        else -> emptyMap()
    }

    private fun ollamaHint(url: String, e: AIError) =
        "Can't reach Ollama at $url (${e.message}). On the tablet, Ollama runs on your PC: start it there with " +
            "OLLAMA_HOST=0.0.0.0, then put your PC's address (like http://192.168.1.20:11434) in Settings."

    // ---------- models ----------
    fun models(provider: String?, key: String?, url: String?): List<String> {
        val c = load(); val p = provider?.takeIf { it.isNotEmpty() } ?: c.getString("provider")
        when (p) {
            "ollama" -> {
                val base = (url?.takeIf { it.isNotEmpty() } ?: c.getString("ollama_url")).trimEnd('/')
                try {
                    val d = JSONObject(open("$base/api/tags").inputStream.bufferedReader().readText())
                    val arr = d.optJSONArray("models") ?: JSONArray()
                    return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.sorted()
                } catch (e: AIError) { throw if (e.unreachable) AIError(ollamaHint(base, e)) else e }
            }
            "anthropic", "openai" -> {
                val k = key?.takeIf { it.isNotEmpty() } ?: c.getJSONObject("keys").optString(p)
                if (k.isEmpty()) throw AIError("Add your ${names[p]} API key first.")
                val u = if (p == "anthropic") "https://api.anthropic.com/v1/models?limit=100" else "https://api.openai.com/v1/models"
                val d = JSONObject(open(u, headers = headers(p, k)).inputStream.bufferedReader().readText())
                val arr = d.optJSONArray("data") ?: JSONArray()
                val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                if (p == "anthropic") return ids
                val skip = listOf("audio", "realtime", "tts", "transcribe", "image", "embedding", "search")
                val chat = ids.filter { i -> (i.startsWith("gpt-") || i.startsWith("o") || i.startsWith("chatgpt")) && skip.none { i.contains(it) } }
                return (chat.ifEmpty { ids }).sortedDescending()
            }
        }
        throw AIError("Choose an assistant in Settings first.")
    }

    // ---------- chat ----------
    fun chat(messages: JSONArray, context: JSONObject?, emit: (String) -> Unit) {
        val c = load(); val p = c.getString("provider")
        if (!ready(c)) throw AIError("Set up the assistant first: open Settings and choose Ollama, Claude or ChatGPT.")
        val msgs = JSONArray()
        val start = maxOf(0, messages.length() - 24)
        for (i in start until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role"); val content = m.optString("content")
            if (role in listOf("user", "assistant") && content.isNotEmpty()) msgs.put(JSONObject().put("role", role).put("content", content.take(20000)))
        }
        val system = systemPrompt(context ?: JSONObject())
        val model = c.getJSONObject("models").optString(p).ifEmpty { defaults[p] ?: "" }
        when (p) {
            "ollama" -> {
                val base = c.getString("ollama_url")
                val all = JSONArray().put(JSONObject().put("role", "system").put("content", system)); for (i in 0 until msgs.length()) all.put(msgs.get(i))
                val conn = try { open("$base/api/chat", JSONObject().put("model", model).put("stream", true).put("messages", all)) } catch (e: AIError) {
                    if (e.unreachable) throw AIError(ollamaHint(base, e))
                    if (e.message?.lowercase()?.contains("not found") == true) throw AIError("Ollama doesn't have the model “$model” yet. Run `ollama pull $model` on your PC, or pick another model in Settings.")
                    throw e
                }
                lines(conn) { line ->
                    if (line.isBlank()) return@lines true
                    val d = JSONObject(line)
                    if (d.has("error")) throw AIError(d.optString("error"))
                    d.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }?.let(emit)
                    !d.optBoolean("done")
                }
            }
            "anthropic" -> {
                val body = JSONObject().put("model", model).put("max_tokens", 2048).put("system", system).put("messages", msgs).put("stream", true)
                sse(open("https://api.anthropic.com/v1/messages", body, headers(p, c.getJSONObject("keys").getString(p)))) { d ->
                    when (d.optString("type")) {
                        "content_block_delta" -> { val dl = d.optJSONObject("delta"); if (dl?.optString("type") == "text_delta") emit(dl.optString("text")); true }
                        "error" -> throw AIError(d.optJSONObject("error")?.optString("message") ?: "Claude returned an error.")
                        "message_stop" -> false
                        else -> true
                    }
                }
            }
            "openai" -> {
                val all = JSONArray().put(JSONObject().put("role", "system").put("content", system)); for (i in 0 until msgs.length()) all.put(msgs.get(i))
                sse(open("https://api.openai.com/v1/chat/completions", JSONObject().put("model", model).put("stream", true).put("messages", all), headers(p, c.getJSONObject("keys").getString(p)))) { d ->
                    d.optJSONObject("error")?.let { throw AIError(it.optString("message", "ChatGPT returned an error.")) }
                    val ch = d.optJSONArray("choices") ?: JSONArray()
                    for (i in 0 until ch.length()) ch.getJSONObject(i).optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() && it != "null" }?.let(emit)
                    true
                }
            }
        }
    }

    private fun lines(conn: HttpURLConnection, each: (String) -> Boolean) {
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            while (true) { val line = r.readLine() ?: break; if (!each(line)) break }
        }
    }

    private fun sse(conn: HttpURLConnection, each: (JSONObject) -> Boolean) = lines(conn) { raw ->
        val line = raw.trim()
        if (!line.startsWith("data:")) return@lines true
        val data = line.substring(5).trim()
        if (data == "[DONE]") return@lines false
        val d = try { JSONObject(data) } catch (e: Exception) { return@lines true }
        each(d)
    }

    private fun systemPrompt(ctx: JSONObject): String {
        val prof = ctx.optJSONObject("profile") ?: JSONObject()
        val lines = mutableListOf(
            "You are the Kiln assistant, built into Kiln, an app where game developers plan and track their game assets " +
                "(props, characters, environments, textures, animations) on boards, keep a file library, and preview models in 3D.",
            "Help with: brainstorming assets for a scene or game, writing asset descriptions and art briefs, breaking an asset into " +
                "concrete tasks, polycount and texture budgets, naming, modeling/texturing/rigging tips, and engine import advice.",
            "The user's main 3D tool is ${prof.optString("tool").ifEmpty { "Blender" }}, their engine is ${prof.optString("engine").ifEmpty { "not specified" }}, " +
                "and their experience level is ${prof.optString("level").ifEmpty { "not specified" }}. Tailor advice to that.",
            "Be concise and practical. Use short paragraphs. When you list assets or tasks, put each one on its own line starting " +
                "with '- ' and keep it under 80 characters, so the user can add the list straight to their board.",
            "Don't claim to have changed the user's board yourself; suggest, and the user will add things.",
        )
        ctx.optJSONObject("asset")?.let { a ->
            lines += "\nThe user currently has this asset open:"
            lines += "Name: ${a.optString("name")} | Category: ${a.optString("category")} | Status: ${a.optString("status")}" +
                (a.optString("poly").takeIf { it.isNotEmpty() }?.let { " | Polycount: $it" } ?: "")
            a.optString("notes").takeIf { it.isNotEmpty() }?.let { lines += "Notes: " + it.take(1500) }
            a.optJSONArray("items")?.let { it2 -> if (it2.length() > 0) lines += "Text on its board:\n" + (0 until minOf(30, it2.length())).joinToString("\n") { "- " + it2.optString(it).take(200) } }
        }
        ctx.optJSONArray("board")?.let { b ->
            if (b.length() > 0) {
                lines += "\nTheir board (category: assets with status):"
                for (i in 0 until minOf(20, b.length())) {
                    val cat = b.getJSONObject(i); val arr = cat.optJSONArray("assets") ?: JSONArray()
                    val items = (0 until minOf(25, arr.length())).joinToString(", ") { val x = arr.getJSONObject(it); "${x.optString("name")} (${x.optString("status")})" }
                    lines += "- ${cat.optString("name")}: ${items.ifEmpty { "empty" }}"
                }
            }
        }
        return lines.joinToString("\n")
    }
}
