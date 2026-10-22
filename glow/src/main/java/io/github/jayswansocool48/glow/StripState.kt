package io.github.jayswansocool48.glow

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * What the strip should be doing, and where to send it:
 * straight over Bluetooth, or through the PC if the PC is the one connected to the strip.
 */
object StripState {
    var on = true
    var mode = "color" // color, effect, music, screen
    var color = 0xFF7A5CFF.toInt()
    var effect = 0x89
    var brightness = 100
    var speed = 50

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = app.getSharedPreferences("stripState", Context.MODE_PRIVATE)
        on = p.getBoolean("on", true)
        mode = p.getString("mode", "color") ?: "color"
        if (mode == "music") mode = "color" // the mic only runs while you're in music mode
        color = p.getInt("color", color)
        effect = p.getInt("effect", effect)
        brightness = p.getInt("brightness", 100)
        speed = p.getInt("speed", 50)
    }

    /** The PC holds the Bluetooth connection, so commands go through it. */
    fun viaPc() = BleStrip.state != BleStrip.State.CONNECTED && Pc.paired && Pc.pcHasStrip()

    fun connected() = BleStrip.state == BleStrip.State.CONNECTED || viaPc()

    /** When the PC drives the strip, show its settings instead of ours. */
    fun syncFromPc() {
        if (!viaPc()) return
        val c = Pc.strip()?.optJSONObject("config") ?: return
        on = c.optBoolean("on", on)
        val m = c.optString("mode", mode)
        if (mode != "music") mode = m
        color = parseHex(c.optString("color"), color)
        effect = c.optString("effect").toIntOrNull() ?: effect
        brightness = c.optInt("brightness", brightness)
        speed = c.optInt("speed", speed)
    }

    private fun save() {
        app.getSharedPreferences("stripState", Context.MODE_PRIVATE).edit()
            .putBoolean("on", on).putString("mode", mode).putInt("color", color)
            .putInt("effect", effect).putInt("brightness", brightness).putInt("speed", speed).apply()
    }

    private fun pcMode() = if (mode == "music") "music" else mode

    private fun toPc(fields: JSONObject) {
        Pc.post("/api/strip", fields)
    }

    private var pending: Runnable? = null

    /** Coalesces slider drags into ~12 updates a second. */
    private fun later(r: () -> Unit) {
        pending?.let { main.removeCallbacks(it) }
        val run = Runnable { pending = null; r() }
        pending = run
        main.postDelayed(run, 80)
    }

    fun power(v: Boolean) {
        on = v
        save()
        if (viaPc()) toPc(JSONObject().put("on", v)) else if (v) applyAll() else BleStrip.send(BleStrip.power(false))
    }

    fun chooseMode(m: String) {
        mode = m
        on = true
        save()
        if (viaPc()) toPc(JSONObject().put("mode", pcMode()).put("on", true)) else applyAll()
    }

    fun pickColor(c: Int, done: Boolean) {
        color = c
        mode = "color"
        if (done) save()
        later {
            if (viaPc()) toPc(JSONObject().put("color", hex(c)).put("mode", "color").put("on", true))
            else BleStrip.sendLive(BleStrip.color(scaled(c)))
        }
    }

    fun chooseEffect(id: Int) {
        effect = id
        mode = "effect"
        save()
        if (viaPc()) toPc(JSONObject().put("effect", id.toString()).put("mode", "effect").put("on", true)) else applyAll()
    }

    fun changeBrightness(v: Int) {
        brightness = v
        save()
        later {
            if (viaPc()) toPc(JSONObject().put("brightness", v))
            else {
                val b = BleStrip.brightness(v)
                if (b != null) BleStrip.sendLive(b) else if (mode == "color") BleStrip.sendLive(BleStrip.color(scaled(color)))
            }
        }
    }

    fun changeSpeed(v: Int) {
        speed = v
        save()
        later {
            if (viaPc()) toPc(JSONObject().put("speed", v))
            else {
                val s = BleStrip.speed(v)
                if (s != null) BleStrip.sendLive(s) else BleStrip.sendLive(BleStrip.effect(effect, v))
            }
        }
    }

    /** Music mode: one color at a time, as fast as the strip takes them. */
    private var lastLive = 0L

    fun live(c: Int) {
        val now = System.currentTimeMillis()
        if (viaPc()) {
            if (now - lastLive < 90) return
            lastLive = now
            Pc.post("/api/strip/live", JSONObject().put("color", hex(c)))
        } else {
            BleStrip.sendLive(BleStrip.color(c))
        }
    }

    /** Colors are scaled for controllers that have no brightness command. */
    private fun scaled(c: Int): Int {
        if (BleStrip.brightness(100) != null) return c
        val f = brightness / 100f
        val r = (((c shr 16) and 0xFF) * f).toInt()
        val g = (((c shr 8) and 0xFF) * f).toInt()
        val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Sends everything (after connecting, or switching modes). */
    fun applyAll() {
        if (viaPc() || BleStrip.state != BleStrip.State.CONNECTED) return
        if (!on) {
            BleStrip.send(BleStrip.power(false))
            return
        }
        BleStrip.send(BleStrip.power(true))
        when (mode) {
            "effect" -> {
                BleStrip.send(BleStrip.effect(effect, speed))
                BleStrip.send(BleStrip.speed(speed))
            }
            "music", "screen" -> {}
            else -> BleStrip.send(BleStrip.color(scaled(color)))
        }
        BleStrip.send(BleStrip.brightness(if (mode == "music") 100 else brightness))
    }
}
