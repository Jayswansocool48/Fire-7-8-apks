package io.github.jayswansocool48.codeling

import java.io.ByteArrayOutputStream

/**
 * The small AI model that runs on the tablet itself (llama.cpp, see src/main/cpp).
 * Only one answer runs at a time; callers use it from a single background thread.
 */
class LlamaEngine {
    fun interface TokenCallback { fun onBytes(bytes: ByteArray) }

    private var handle = 0L
    var loadedPath: String? = null
        private set

    val isLoaded get() = handle != 0L

    /** Loads the model file; returns false if it can't (missing, damaged or not enough memory). */
    fun load(path: String, threads: Int, contextSize: Int): Boolean {
        if (handle != 0L && loadedPath == path) return true
        unload()
        handle = nativeLoad(path, threads, contextSize)
        loadedPath = if (handle != 0L) path else null
        return handle != 0L
    }

    fun unload() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedPath = null
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /**
     * Answers [messages] (role to text). [onText] gets the whole answer so far after each piece.
     * Returns the finished text, or throws with a reason a person can read.
     */
    fun complete(messages: List<Pair<String, String>>, grammar: String?, maxTokens: Int, temperature: Float, onText: (String) -> Unit): String {
        check(handle != 0L) { "The tablet model isn't loaded." }
        val bytes = ByteArrayOutputStream()
        val code = nativeComplete(
            handle,
            messages.map { it.first }.toTypedArray(),
            messages.map { it.second }.toTypedArray(),
            grammar, maxTokens, temperature,
            TokenCallback { piece ->
                bytes.write(piece)
                onText(bytes.toString(Charsets.UTF_8.name()))
            },
        )
        val text = bytes.toString(Charsets.UTF_8.name())
        return when (code) {
            0, 1 -> text
            -3 -> throw IllegalStateException("That request is too long for the tablet model. Try a shorter question, or use the AI on your computer.")
            else -> throw IllegalStateException("The tablet model stopped with an error ($code).")
        }
    }

    private external fun nativeLoad(path: String, threads: Int, contextSize: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeComplete(
        handle: Long, roles: Array<String>, texts: Array<String>, grammar: String?,
        maxTokens: Int, temperature: Float, callback: TokenCallback,
    ): Int

    companion object {
        /** False when the native library couldn't load (for example an unsupported CPU). */
        val available: Boolean = try {
            System.loadLibrary("codelingai")
            true
        } catch (e: Throwable) {
            false
        }
    }
}
