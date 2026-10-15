package io.github.jayswansocool48.deskmate

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Macro Pad

class MacroScreen(app: MainActivity) : Screen(app) {
    override val title = "Macro Pad"
    override val accent = Theme.PAD
    override val actions = listOf<Pair<Icon, () -> Unit>>(Icon.REFRESH to { load() }, Icon.SETTINGS to { explainEditing() })
    private val banner = offlineBanner()
    private val keysNote = app.label("", 14f, Theme.WARN)
    private val chips = app.hbox()
    private val grid = FlowGrid(app, app.dp(150f), app.dp(110f), app.dp(12f))
    private var pages = JSONArray()
    private var pageIndex = 0
    private val empty = app.label("", 15f, Theme.DIM)

    init {
        val col = app.vbox()
        pad(col, 16f)
        col.addView(banner, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(12f) })
        col.addView(keysNote, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(8f) })
        val hs = HorizontalScrollView(app).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(chips)
        col.addView(hs, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(14f) })
        col.addView(empty)
        col.addView(grid, lp(MATCH, WRAP))
        addView(scroll(col))
    }

    override fun onShow() = load()

    override fun onConnectionChanged() {
        refreshBanner(banner)
        if (DeskClient.online) load()
    }

    private fun load() {
        refreshBanner(banner)
        val c = DeskClient.caps
        keysNote.visibility = if (DeskClient.online && !c.optBoolean("keys", true)) View.VISIBLE else View.GONE
        keysNote.text = c.optString("keysHelp")
        if (!DeskClient.online) return
        DeskClient.request("buttons") { m ->
            pages = m.optJSONArray("pages") ?: JSONArray()
            if (pageIndex >= pages.length()) pageIndex = 0
            render()
        }
    }

    private fun render() {
        chips.removeAllViews()
        for (i in 0 until pages.length()) {
            val name = pages.optJSONObject(i)?.optString("name") ?: ""
            val chip = PillButton(app, name, null, Theme.SURFACE).apply {
                active = i == pageIndex
                activeColor = Theme.PAD
                radius = app.dp(20f)
                textSize = app.dp(14f)
            }
            chip.setOnClickListener { pageIndex = i; render() }
            val w = (measurePaint(chip.textSize).measureText(name) + app.dp(40f)).toInt()
            chips.addView(chip, lp(w, app.dpi(40f)).apply { rightMargin = app.dpi(8f) })
        }
        grid.removeAllViews()
        val buttons = pages.optJSONObject(pageIndex)?.optJSONArray("buttons") ?: JSONArray()
        empty.text = if (buttons.length() == 0) "No buttons on this page yet. Add some in Deskmate's settings page on your PC." else ""
        empty.visibility = if (buttons.length() == 0) View.VISIBLE else View.GONE
        for (i in 0 until buttons.length()) {
            val b = buttons.optJSONObject(i) ?: continue
            val color = parseColor(b.optString("color"), Theme.SURFACE2)
            val v = PillButton(app, b.optString("label"), null, color).apply {
                textColor = 0xFFFFFFFF.toInt()
                textSize = app.dp(17f)
                radius = app.dp(18f)
            }
            val id = b.optString("id")
            v.setOnClickListener {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                DeskClient.send("press", "id" to id)
            }
            grid.addView(v)
        }
    }

    private fun explainEditing() {
        android.app.AlertDialog.Builder(app)
            .setTitle("Editing buttons")
            .setMessage("Buttons are set up on your PC, so the tablet can only do what you allowed there.\n\n" +
                "On the PC, open Deskmate's settings page (it opens when Deskmate starts, or go to http://127.0.0.1:47801). " +
                "Save there, then tap refresh here.")
            .setPositiveButton("OK", null)
            .show()
    }
}

private fun measurePaint(size: Float) = android.graphics.Paint().apply {
    textSize = size
    isFakeBoldText = true
}

// ---------------------------------------------------------------- System Monitor

class MonitorScreen(app: MainActivity) : Screen(app) {
    override val title = "System Monitor"
    override val accent = Theme.MONITOR
    private val banner = offlineBanner()
    private val cpu = GaugeView(app, "CPU", Theme.MONITOR)
    private val ram = GaugeView(app, "Memory", 0xFFB48CFF.toInt())
    private val gpu = GaugeView(app, "GPU", 0xFF5FB4FF.toInt())
    private val disk = GaugeView(app, "Disk", Theme.NOTES)
    private val cpuGraph = GraphView(app, Theme.MONITOR).apply { fixedMax = 100f; label = "CPU over the last minute"; formatter = { "100%" } }
    private val netGraph = GraphView(app, 0xFF5FB4FF.toInt(), Theme.MEDIA).apply { label = "Network  ↓ blue  ↑ pink"; formatter = { humanBytes(it.toDouble()) + "/s" } }
    private val cores = CoreBarsView(app)
    private val info = app.label("", 15f, Theme.TEXT)
    private val listener: (JSONObject) -> Unit = { m -> if (m.optString("op") == "stats") show(m.optJSONObject("stats") ?: JSONObject()) }

    init {
        val col = app.vbox()
        pad(col, 16f)
        col.addView(banner, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(12f) })
        val gauges = app.hbox()
        for (g in listOf(cpu, ram, gpu, disk)) gauges.addView(g, lp(0, app.dpi(170f), 1f))
        col.addView(gauges, lp(MATCH, WRAP))
        col.addView(info.apply {
            background = rounded(Theme.SURFACE, app.dp(12f))
            val p = app.dpi(14f)
            setPadding(p, p, p, p)
            setLineSpacing(0f, 1.25f)
        }, lp(MATCH, WRAP).apply { topMargin = app.dpi(12f) })
        col.addView(cpuGraph, lp(MATCH, app.dpi(150f)).apply { topMargin = app.dpi(12f) })
        col.addView(cores, lp(MATCH, app.dpi(110f)).apply { topMargin = app.dpi(12f) })
        col.addView(netGraph, lp(MATCH, app.dpi(150f)).apply { topMargin = app.dpi(12f) })
        addView(scroll(col))
    }

    override fun onShow() {
        DeskClient.onMessage(listener)
        DeskClient.send("stats", "on" to true)
    }

    override fun onHide() {
        DeskClient.send("stats", "on" to false)
        DeskClient.removeMessage(listener)
    }

    override fun onConnectionChanged() {
        refreshBanner(banner)
        if (DeskClient.online) DeskClient.send("stats", "on" to true)
    }

    private fun show(s: JSONObject) {
        val c = s.optDouble("cpu").toFloat()
        cpu.value = c
        cpu.caption = if (s.optDouble("cpuTemp") > 0) "${s.optDouble("cpuTemp").roundToInt()}°C" else ""
        val memTotal = s.optDouble("memTotal")
        val memUsed = s.optDouble("memUsed")
        ram.value = if (memTotal > 0) (100 * memUsed / memTotal).toFloat() else 0f
        ram.caption = "${humanBytes(memUsed)} / ${humanBytes(memTotal)}"
        val g = s.optJSONObject("gpu")
        gpu.available = g != null
        if (g != null) {
            gpu.value = g.optDouble("util").toFloat()
            gpu.caption = if (g.optDouble("temp") > 0) "${g.optDouble("temp").roundToInt()}°C" else ""
        } else gpu.caption = "not detected"
        val dt = s.optDouble("diskTotal")
        val du = s.optDouble("diskUsed")
        disk.value = if (dt > 0) (100 * du / dt).toFloat() else 0f
        disk.caption = "${humanBytes(dt - du)} free"
        cpuGraph.add(c)
        netGraph.add(s.optDouble("netRx").toFloat(), s.optDouble("netTx").toFloat())
        val arr = s.optJSONArray("cores")
        cores.values = FloatArray(arr?.length() ?: 0) { arr!!.optDouble(it).toFloat() }

        val up = s.optLong("uptime")
        val lines = ArrayList<String>()
        lines += "${s.optString("host")}  ·  ${if (s.optString("os") == "windows") "Windows" else "Linux"}  ·  up ${up / 86400}d ${(up % 86400) / 3600}h ${(up % 3600) / 60}m"
        if (g != null) {
            var gl = "GPU: ${g.optString("name")}"
            if (g.optDouble("vramTotal") > 0) gl += "  ·  VRAM ${humanBytes(g.optDouble("vramUsed") * 1048576)} / ${humanBytes(g.optDouble("vramTotal") * 1048576)}"
            lines += gl
        }
        lines += "Network: ↓ ${humanBytes(s.optDouble("netRx"))}/s   ↑ ${humanBytes(s.optDouble("netTx"))}/s"
        info.text = lines.joinToString("\n")
    }
}

// ---------------------------------------------------------------- Media & Volume

class MediaScreen(app: MainActivity) : Screen(app) {
    override val title = "Media & Volume"
    override val accent = Theme.MEDIA
    private val banner = offlineBanner()
    private val songTitle = app.label("Nothing playing", 24f, Theme.TEXT, bold = true)
    private val artist = app.label("", 16f, Theme.DIM)
    private val playPause = PillButton(app, "", Icon.PLAY, Theme.MEDIA).apply { iconSize = app.dp(40f); radius = app.dp(48f); textColor = 0xFFFFFFFF.toInt() }
    private val mute = PillButton(app, "Sound on", Icon.VOLUME)
    private val mic = PillButton(app, "Mic on", Icon.MIC).apply { iconSize = app.dp(30f) }
    private val volume = SeekBar(app)
    private val volumeLabel = app.label("", 16f, Theme.TEXT)
    private val sliderRow = app.hbox()
    private val stepRow = app.hbox()
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var lastSent = 0L
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    init {
        val col = app.vbox()
        pad(col, 20f)
        col.addView(banner, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(12f) })

        val now = app.vbox().apply {
            background = rounded(Theme.SURFACE, app.dp(18f))
            val p = app.dpi(20f)
            setPadding(p, p, p, p)
        }
        now.addView(app.heading("Now playing"))
        now.addView(songTitle, lp(MATCH, WRAP).apply { topMargin = app.dpi(8f) })
        now.addView(artist)
        col.addView(now, lp(MATCH, WRAP))

        val transport = app.hbox().apply { gravity = android.view.Gravity.CENTER }
        val prev = PillButton(app, "", Icon.PREV).apply { iconSize = app.dp(30f); radius = app.dp(40f) }
        val next = PillButton(app, "", Icon.NEXT).apply { iconSize = app.dp(30f); radius = app.dp(40f) }
        prev.setOnClickListener { DeskClient.send("media", "action" to "prev"); refreshSoon() }
        playPause.setOnClickListener { DeskClient.send("media", "action" to "playpause"); refreshSoon() }
        next.setOnClickListener { DeskClient.send("media", "action" to "next"); refreshSoon() }
        transport.addView(prev, lp(app.dpi(80f), app.dpi(80f)))
        transport.addView(playPause, lp(app.dpi(110f), app.dpi(110f)).apply { leftMargin = app.dpi(28f); rightMargin = app.dpi(28f) })
        transport.addView(next, lp(app.dpi(80f), app.dpi(80f)))
        col.addView(transport, lp(MATCH, WRAP).apply { topMargin = app.dpi(24f) })

        val vol = app.vbox().apply {
            background = rounded(Theme.SURFACE, app.dp(18f))
            val p = app.dpi(18f)
            setPadding(p, p, p, p)
        }
        val head = app.hbox()
        head.addView(app.heading("Volume"), lp(0, WRAP, 1f))
        head.addView(volumeLabel)
        vol.addView(head)
        volume.max = 100
        volume.progressTintList = ColorStateList.valueOf(Theme.MEDIA)
        volume.thumbTintList = ColorStateList.valueOf(Theme.MEDIA)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                volumeLabel.text = "$p%"
                val now = System.currentTimeMillis()
                if (now - lastSent > 60) {
                    lastSent = now
                    DeskClient.send("volume", "value" to p / 100.0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                DeskClient.send("volume", "value" to sb.progress / 100.0)
            }
        })
        sliderRow.addView(volume, lp(0, app.dpi(48f), 1f))
        vol.addView(sliderRow, lp(MATCH, WRAP).apply { topMargin = app.dpi(8f) })
        val down = PillButton(app, "Quieter", Icon.MINUS)
        val up = PillButton(app, "Louder", Icon.PLUS)
        down.setOnClickListener { DeskClient.send("volumeStep", "step" to -0.05); refreshSoon() }
        up.setOnClickListener { DeskClient.send("volumeStep", "step" to 0.05); refreshSoon() }
        stepRow.addView(down, lp(0, app.dpi(56f), 1f))
        stepRow.addView(up, lp(0, app.dpi(56f), 1f).apply { leftMargin = app.dpi(10f) })
        vol.addView(stepRow, lp(MATCH, WRAP).apply { topMargin = app.dpi(10f) })
        col.addView(vol, lp(MATCH, WRAP).apply { topMargin = app.dpi(24f) })

        val toggles = app.hbox()
        mute.setOnClickListener { DeskClient.send("mute", "mic" to false); refreshSoon() }
        mic.setOnClickListener { DeskClient.send("mute", "mic" to true); refreshSoon() }
        toggles.addView(mute, lp(0, app.dpi(72f), 1f))
        toggles.addView(mic, lp(0, app.dpi(72f), 1f).apply { leftMargin = app.dpi(12f) })
        col.addView(toggles, lp(MATCH, WRAP).apply { topMargin = app.dpi(14f) })

        addView(scroll(col))
    }

    override fun onShow() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onHide() { handler.removeCallbacks(poll) }
    override fun onConnectionChanged() { refreshBanner(banner); refresh() }

    private fun refreshSoon() {
        handler.postDelayed({ refresh() }, 350)
    }

    private fun refresh() {
        refreshBanner(banner)
        if (!DeskClient.online) return
        DeskClient.request("mediaInfo") { m ->
            val i = m.optJSONObject("info") ?: return@request
            val caps = DeskClient.caps
            val t = i.optString("title")
            songTitle.text = when {
                t.isNotEmpty() -> t
                caps.optBoolean("nowPlaying") -> "Nothing playing"
                caps.optString("os") == "linux" -> "Install playerctl on your PC to see what's playing"
                else -> "Media controls"
            }
            songTitle.textSize = if (t.isEmpty() && !caps.optBoolean("nowPlaying")) 17f else 24f
            artist.text = i.optString("artist")
            playPause.update(icon = if (i.optBoolean("playing")) Icon.PAUSE else Icon.PLAY)
            val v = i.optDouble("volume", -1.0)
            val hasLevel = v >= 0 && caps.optBoolean("volume")
            sliderRow.visibility = if (hasLevel) View.VISIBLE else View.GONE
            if (hasLevel && !dragging) {
                volume.progress = (v * 100).roundToInt()
                volumeLabel.text = "${(v * 100).roundToInt()}%"
            }
            if (!hasLevel) volumeLabel.text = ""
            val muted = i.optBoolean("muted")
            mute.update(if (muted) "Sound off" else "Sound on", if (muted) Icon.VOLUME_OFF else Icon.VOLUME)
            mute.active = muted
            mute.activeColor = Theme.BAD
            val micMuted = i.optBoolean("micMuted")
            mic.update(if (micMuted) "Mic muted" else "Mic on", if (micMuted) Icon.MIC_OFF else Icon.MIC)
            mic.active = micMuted
            mic.activeColor = Theme.BAD
        }
    }
}
