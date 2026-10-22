package io.github.jayswansocool48.glow

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Listens to the room and turns the beat into colors: every beat jumps to a new hue,
 * loudness drives brightness. Runs only while music mode is open.
 */
object Music {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    var sensitivity = 60 // 0-100
    var level = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start(onColor: (Int) -> Unit): Boolean {
        if (running) return true
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096))
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        thread = Thread {
            val buf = ShortArray(512) // 32 ms
            var avg = 0f
            var peak = 1000f
            var hue = 0f
            var bright = 0f
            var lastSent = 0L
            var lowPass = 0f
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Bass energy (simple low-pass) beats better than overall loudness.
                    var sum = 0.0
                    var bass = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toFloat()
                        lowPass += (s - lowPass) * 0.06f
                        sum += s * s
                        bass += lowPass * lowPass
                    }
                    val rms = sqrt(sum / n).toFloat()
                    val b = sqrt(bass / n).toFloat()
                    peak = max(peak * 0.995f, rms)
                    avg = avg * 0.92f + b * 0.08f
                    val gate = 1.25f + (100 - sensitivity) / 100f
                    if (b > avg * gate && b > 150f) {
                        hue = (hue + 47f + (b / max(avg, 1f)) * 10f) % 360f
                        bright = 1f
                    }
                    val floor = (rms / max(peak, 1f)).coerceIn(0f, 1f) * 0.6f
                    bright = max(bright * 0.86f, floor)
                    level = min(1f, rms / max(peak, 1f))
                    val now = System.currentTimeMillis()
                    if (now - lastSent >= 60) {
                        lastSent = now
                        val c = Color.HSVToColor(floatArrayOf(hue, 1f, max(0.04f, bright)))
                        main.post { if (running) onColor(c) }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        thread = null
        level = 0f
    }
}
