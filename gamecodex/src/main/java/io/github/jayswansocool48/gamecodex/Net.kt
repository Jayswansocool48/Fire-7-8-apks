package io.github.jayswansocool48.gamecodex

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTPS to GitHub only. The sign-in token is added just for api.github.com and is dropped when
 * GitHub redirects a download to another server.
 */
object Net {
    private val PAGE_HOSTS = setOf("api.github.com", "github.com", "uploads.github.com")
    private val UPDATE_HOSTS = setOf("raw.githubusercontent.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    class Result(val status: Int, val body: String)

    fun pageAllowed(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && u.host in PAGE_HOSTS
    } catch (e: Exception) {
        false
    }

    /**
     * headers: request headers from the page (Authorization, Host and Cookie are ignored).
     * token: added as a bearer token only when the request goes to api.github.com.
     * toFile: save the body there instead of returning it.
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        token: String? = null,
        toFile: File? = null,
        userAgent: String = "GameCodex",
        timeoutMs: Int = 60_000,
        anyGithubHost: Boolean = false,
    ): Result {
        var target = URL(url)
        var verb = method
        var payload = body
        repeat(6) {
            val allowed = target.protocol == "https" && (target.host in PAGE_HOSTS || (anyGithubHost && target.host in UPDATE_HOSTS))
            if (!allowed) return Result(0, "Only GitHub can be reached from Game Codex.")
            val c = target.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = verb
                c.connectTimeout = 20_000
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) {
                    if (k.lowercase() !in setOf("authorization", "host", "cookie", "user-agent")) c.setRequestProperty(k, v)
                }
                if (token != null && target.host == "api.github.com") c.setRequestProperty("Authorization", "Bearer $token")
                if (payload != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(payload.size)
                    c.outputStream.use { it.write(payload) }
                }
                val status = c.responseCode
                if (status in 300..399 && c.getHeaderField("Location") != null) {
                    target = URL(target, c.getHeaderField("Location"))
                    if (status != 307 && status != 308) { verb = "GET"; payload = null }
                    return@repeat
                }
                val stream = if (status >= 400) c.errorStream else c.inputStream
                if (toFile != null && status in 200..299) {
                    val tmp = File(toFile.path + ".tmp")
                    stream.use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                    if (!tmp.renameTo(toFile)) { toFile.delete(); tmp.renameTo(toFile) }
                    return Result(status, "")
                }
                val text = stream?.use { String(it.readBytes()) } ?: ""
                return Result(status, text)
            } catch (e: IOException) {
                return Result(0, e.message ?: "network error")
            } finally {
                c.disconnect()
            }
        }
        return Result(0, "Too many redirects.")
    }
}
