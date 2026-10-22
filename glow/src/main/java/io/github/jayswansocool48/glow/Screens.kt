package io.github.jayswansocool48.glow

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Keeps content readable on a wide landscape screen. */
class Column(context: android.content.Context) : LinearLayout(context) {
    private val maxW = context.dpi(640f)
    init { orientation = VERTICAL }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        super.onMeasure(if (w > maxW) MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY) else wSpec, hSpec)
    }
}

abstract class Screen(val act: MainActivity, title: String?) : FrameLayout(act) {
    protected val body = Column(act)
    private val listener: () -> Unit = { refresh() }

    init {
        val scroll = ScrollView(act).apply { isFillViewport = true }
        val outer = FrameLayout(act)
        val p = act.dpi(16f)
        body.setPadding(p, p, p, act.dpi(40f))
        outer.addView(body, LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        scroll.addView(outer)
        addView(scroll, LayoutParams(MATCH, MATCH))
        if (title != null) {
            val bar = act.hbox()
            val back = act.label("‹", 30f, Theme.TEXT).apply {
                gravity = Gravity.CENTER
                background = rounded(Theme.CARD2, act.dp(14f))
                setOnClickListener { act.pop() }
            }
            bar.addView(back, lp(act.dpi(48f), act.dpi(48f)))
            bar.addView(act.label(title, 22f, Theme.TEXT, bold = true), lp(0, WRAP, 1f).apply { leftMargin = act.dpi(14f) })
            body.addView(bar, lp(MATCH, WRAP).apply { bottomMargin = act.dpi(14f) })
        }
    }

    open fun onShow() {
        BleStrip.onChange(listener)
        Pc.onChange(listener)
        refresh()
    }

    open fun onHide() {
        BleStrip.removeChange(listener)
        Pc.removeChange(listener)
    }

    abstract fun refresh()

    protected fun add(v: View, top: Float = 12f) = body.addView(v, lp(MATCH, WRAP).apply { topMargin = act.dpi(top) })
}

/** The rainbow ring logo. */
class Logo(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.36f
        p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFFFF5F6D.toInt(), 0xFFFFC371.toInt(), 0xFF3DD6B5.toInt(), 0xFF7A5CFF.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r * 0.68f, p)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, r * 0.2f, p)
    }
}

class HomeScreen(act: MainActivity) : Screen(act, null) {
    private val aitoStatus = act.label("", 14f, Theme.DIM)
    private val stripStatus = act.label("", 14f, Theme.DIM)
    private val aitoToggle = Toggle(act)
    private val stripToggle = Toggle(act)

    init {
        val head = act.hbox()
        head.addView(Logo(act), lp(act.dpi(44f), act.dpi(44f)))
        head.addView(act.label("Glow", 26f, Theme.TEXT, bold = true), lp(WRAP, WRAP).apply { leftMargin = act.dpi(12f) })
        add(head, 8f)
        add(lightCard("Screen light", "AITOLED on your PC", aitoStatus, aitoToggle) { act.push(AitoScreen(act)) }, 20f)
        add(lightCard("LED strip", "60 ft Bluetooth strip", stripStatus, stripToggle) { act.push(StripScreen(act)) }, 14f)
        aitoToggle.onChange = { on -> Pc.post("/api/aito", JSONObject().put("on", on)) }
        stripToggle.onChange = { on -> StripState.power(on) }
    }

    private fun lightCard(title: String, sub: String, status: TextView, t: Toggle, open: () -> Unit): View {
        val c = act.card()
        val row = act.hbox()
        val texts = act.vbox()
        texts.addView(act.label(title, 19f, Theme.TEXT, bold = true))
        texts.addView(act.label(sub, 13f, Theme.DIM))
        row.addView(texts, lp(0, WRAP, 1f))
        row.addView(t)
        c.addView(row)
        c.addView(status, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        c.addView(act.label("Open  ›", 14f, Theme.ACCENT, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        c.isClickable = true
        c.setOnClickListener { open() }
        return c
    }

    override fun refresh() {
        val a = Pc.aito()
        aitoToggle.visibility = if (a != null) View.VISIBLE else View.GONE
        aitoStatus.text = when {
            !Pc.paired -> "Tap to connect to your PC"
            a == null -> Pc.error ?: "Connecting to ${Pc.name}…"
            a.optBoolean("connected") -> "● ${a.optString("model")} · ${a.optInt("ledCount")} LEDs · ${modeName(a.optJSONObject("config")?.optString("mode"))}"
            else -> a.optString("status")
        }
        if (a != null) aitoToggle.on = a.optJSONObject("config")?.optBoolean("on") == true
        StripState.syncFromPc()
        stripToggle.on = StripState.on
        stripToggle.visibility = if (StripState.connected()) View.VISIBLE else View.GONE
        stripStatus.text = when {
            StripState.viaPc() -> "● Connected through ${Pc.name}"
            BleStrip.state == BleStrip.State.CONNECTED -> "● ${BleStrip.name ?: "Strip"} · ${modeName(StripState.mode)}"
            BleStrip.address == null -> "Tap to set up"
            else -> BleStrip.status
        }
    }
}

fun modeName(m: String?) = when (m) {
    "screen" -> "screen sync"
    "effect" -> "effect"
    "music" -> "music"
    else -> "color"
}

// ---------------------------------------------------------------------------------------------

class StripScreen(act: MainActivity) : Screen(act, "LED strip") {
    private val status = act.label("", 14f, Theme.DIM)
    private val toggle = Toggle(act)
    private val setup = act.vbox()
    private val results = act.vbox()
    private val controls = act.vbox()
    private val panels = HashMap<String, View>()
    private lateinit var tabs: Tabs
    private val wheel: ColorWheel = ColorWheel(act) { c, done -> swatches.selected = c; StripState.pickColor(c, done) }
    private val swatches: Swatches = Swatches(act) { c -> wheel.color = c; StripState.pickColor(c, true) }
    private val fx = Flow(act)
    private var fxProto = ""
    private val speed = Slider(act, "Speed") { StripState.changeSpeed(it) }
    private val bright = Slider(act, "Brightness") { StripState.changeBrightness(it) }
    private val sens = Slider(act, "Sensitivity") { Music.sensitivity = it }
    private val musicNote = act.label("", 14f, Theme.DIM)
    private val via = act.label("", 13f, Theme.DIM)
    private var scanning = false

    init {
        val head = act.hbox()
        head.addView(status, lp(0, WRAP, 1f))
        head.addView(toggle)
        add(head, 0f)
        toggle.onChange = { StripState.power(it) }

        setup.addView(act.label("Plug in the strip and close Lotus Lantern on your phone (only one thing can connect at a time). Then:", 14f, Theme.DIM))
        setup.addView(act.button("Find my strip", primary = true) { scan() }, lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })
        setup.addView(results, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        add(setup)

        tabs = Tabs(act, listOf("color" to "Color", "effect" to "Effect", "music" to "Music", "screen" to "Screen")) { m ->
            if (m == "music") act.withMic { StripState.chooseMode("music"); refresh() }
            else { StripState.chooseMode(m); refresh() }
        }
        controls.addView(tabs)

        val colorP = act.vbox()
        colorP.addView(wheel, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        colorP.addView(swatches, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        val fxP = act.vbox()
        fxP.addView(fx, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        fxP.addView(speed, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        val musicP = act.vbox()
        musicP.addView(musicNote, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        sens.set(Music.sensitivity)
        musicP.addView(sens)
        val screenP = act.vbox()
        screenP.addView(act.label("The strip follows the main color on your PC's screen. Turn on screen sync for the Screen light (on the PC or in this app).", 14f, Theme.DIM),
            lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        panels["color"] = colorP
        panels["effect"] = fxP
        panels["music"] = musicP
        panels["screen"] = screenP
        for (pv in panels.values) controls.addView(pv)
        controls.addView(bright, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        controls.addView(via, lp(MATCH, WRAP).apply { topMargin = act.dpi(18f) })
        val links = act.hbox()
        links.addView(act.button("Connect a different strip") { setup.visibility = View.VISIBLE; scan() })
        links.addView(act.button("Forget") { Music.stop(); BleStrip.forget(); refresh() }, lp(WRAP, WRAP).apply { leftMargin = act.dpi(8f) })
        controls.addView(links, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        add(controls, 14f)
        wheel.color = StripState.color
        swatches.selected = StripState.color
    }

    private fun scan() {
        if (scanning) return
        act.withBluetooth {
            scanning = true
            results.removeAllViews()
            results.addView(act.label("Searching…", 14f, Theme.DIM))
            BleStrip.scan { list, err ->
                scanning = false
                results.removeAllViews()
                if (err != null) return@scan results.addView(act.label(err, 14f, Theme.WARN))
                if (list.isNullOrEmpty()) return@scan results.addView(act.label("Nothing found. Is the strip plugged in, and closed in Lotus Lantern?", 14f, Theme.WARN))
                for (d in list) {
                    val b = act.button((if (d.likely) "💡 " else "") + d.name) {
                        BleStrip.connect(d.address, d.name)
                        setup.visibility = View.GONE
                    }
                    b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    results.addView(b, lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
                }
            }
        }
    }

    override fun onShow() {
        super.onShow()
        if (BleStrip.address != null && BleStrip.state == BleStrip.State.OFF && !StripState.viaPc()) act.withBluetooth { BleStrip.reconnect() }
    }

    override fun onHide() {
        super.onHide()
        Music.stop()
    }

    override fun refresh() {
        StripState.syncFromPc()
        val ready = StripState.connected()
        status.text = when {
            StripState.viaPc() -> "● Connected through ${Pc.name}"
            BleStrip.state == BleStrip.State.CONNECTED -> "● ${BleStrip.name ?: "Strip"} · connected"
            else -> BleStrip.status
        }
        status.setTextColor(if (ready) Theme.OK else Theme.DIM)
        toggle.on = StripState.on
        toggle.visibility = if (ready) View.VISIBLE else View.GONE
        if (BleStrip.address == null && !StripState.viaPc()) setup.visibility = View.VISIBLE
        else if (ready) setup.visibility = View.GONE
        controls.visibility = if (ready || BleStrip.address != null) View.VISIBLE else View.GONE

        val mode = StripState.mode
        tabs.selected = mode
        for ((k, v) in panels) v.visibility = if (k == mode) View.VISIBLE else View.GONE
        bright.visibility = if (mode == "music") View.GONE else View.VISIBLE
        bright.set(StripState.brightness)
        speed.set(StripState.speed)

        val effects = if (StripState.viaPc()) pcEffects() else BleStrip.effects()
        val proto = if (StripState.viaPc()) "pc" else BleStrip.proto
        if (proto != fxProto) {
            fxProto = proto
            fx.removeAllViews()
            for ((id, name) in effects) fx.addView(act.chip(name) { StripState.chooseEffect(id); refresh() }.apply { tag = id })
        }
        for (i in 0 until fx.childCount) (fx.getChildAt(i) as TextView).chipOn(fx.getChildAt(i).tag == StripState.effect)

        if (mode == "music" && ready && StripState.on) {
            if (Music.start { c -> StripState.live(c) }) musicNote.text = "Listening… play some music near the tablet. Each beat changes the color."
            else musicNote.text = "Couldn't open the microphone."
        } else {
            Music.stop()
            musicNote.text = if (!ready) "Connect the strip first." else "Turn the strip on to start."
        }
        via.text = when {
            StripState.viaPc() -> "Your PC is connected to this strip, so the tablet controls it through ${Pc.name}."
            BleStrip.name != null -> "Strip: ${BleStrip.name} (${BleStrip.address})"
            else -> ""
        }
    }

    private fun pcEffects(): List<Pair<Int, String>> {
        val arr = Pc.strip()?.optJSONArray("effects") ?: return BleStrip.effects()
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optInt("id") to o.optString("name") } }
    }
}

// ---------------------------------------------------------------------------------------------

class AitoScreen(act: MainActivity) : Screen(act, "Screen light") {
    private val status = act.label("", 14f, Theme.DIM)
    private val toggle = Toggle(act)
    private val setup = act.vbox()
    private val found = act.vbox()
    private val controls = act.vbox()
    private val panels = HashMap<String, View>()
    private lateinit var tabs: Tabs
    private val capBtn = act.button("Start screen sync", primary = true) { toggleCapture() }
    private val capMsg = act.label("", 14f, Theme.DIM)
    private val wheel: ColorWheel = ColorWheel(act) { c, done -> swatches.selected = c; setColor(c, done) }
    private val swatches: Swatches = Swatches(act) { c -> wheel.color = c; setColor(c, true) }
    private val fx = Flow(act)
    private val fxSwatches = Swatches(act) { c -> setColor(c, true) }
    private var fxBuilt = false
    private val speed = Slider(act, "Speed") { send(JSONObject().put("speed", it)) }
    private val bright = Slider(act, "Brightness") { send(JSONObject().put("brightness", it)) }
    private val smooth = Slider(act, "Smoothing", 95) { send(JSONObject().put("smoothing", it)) }
    private val boost = Slider(act, "Color boost", 200) { send(JSONObject().put("saturation", maxOf(50, it))) }
    private val pcLine = act.label("", 13f, Theme.DIM)
    private var lastSend = 0L
    private var wheelSet = false

    init {
        val head = act.hbox()
        head.addView(status, lp(0, WRAP, 1f))
        head.addView(toggle)
        add(head, 0f)
        toggle.onChange = { send(JSONObject().put("on", it)) }

        setup.addView(act.label("The screen light is plugged into your PC. Install Glow on the PC (see the README), start it, then:", 14f, Theme.DIM))
        setup.addView(act.button("Find my PC", primary = true) { discover() }, lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })
        setup.addView(found, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        setup.addView(act.button("Type the PC's address") { typeAddress() }, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        add(setup)

        tabs = Tabs(act, listOf("screen" to "Screen sync", "color" to "Color", "effect" to "Effect")) { m ->
            send(JSONObject().put("mode", m).put("on", true))
        }
        controls.addView(tabs)
        val screenP = act.vbox()
        screenP.addView(capBtn, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        screenP.addView(capMsg, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        screenP.addView(smooth)
        screenP.addView(boost)
        val colorP = act.vbox()
        colorP.addView(wheel, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        colorP.addView(swatches, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        val fxP = act.vbox()
        fxP.addView(fx, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        fxP.addView(speed, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        fxP.addView(act.label("Color (for Breathe and Comet)", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
        fxP.addView(fxSwatches, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        panels["screen"] = screenP
        panels["color"] = colorP
        panels["effect"] = fxP
        for (pv in panels.values) controls.addView(pv)
        controls.addView(bright, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        val tools = act.hbox()
        tools.addView(act.button("Test LED order") { Pc.post("/api/aito/test"); act.toast("LED #1 is red, the last LED is blue, each side starts green. Fix the layout on the PC's Glow page.") })
        tools.addView(act.button("Forget this PC") { Pc.forget(); refresh() }, lp(WRAP, WRAP).apply { leftMargin = act.dpi(8f) })
        controls.addView(tools, lp(MATCH, WRAP).apply { topMargin = act.dpi(18f) })
        controls.addView(pcLine, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        add(controls, 14f)
    }

    private fun send(j: JSONObject) {
        Pc.post("/api/aito", j)
    }

    private fun setColor(c: Int, done: Boolean) {
        val now = System.currentTimeMillis()
        if (!done && now - lastSend < 90) return
        lastSend = now
        send(JSONObject().put("color", hex(c)))
    }

    private fun toggleCapture() {
        val cap = Pc.state?.optJSONObject("screen")?.optJSONObject("capture")
        val running = cap?.optString("state") == "on" || cap?.optString("state") == "starting"
        Pc.post("/api/capture?on=" + if (running) "0" else "1") { j ->
            if (j?.optBoolean("ok", true) == false) act.toast(j.optString("error"))
        }
        if (!running) act.toast("If a \"share your screen\" window pops up on the PC, pick your monitor there. Glow remembers it.")
    }

    private fun discover() {
        found.removeAllViews()
        found.addView(act.label("Looking on your Wi-Fi…", 14f, Theme.DIM))
        Pc.discover { list ->
            found.removeAllViews()
            if (list.isEmpty()) {
                found.addView(act.label("No PC found. Is Glow running on it, and is the tablet on the same Wi-Fi? You can also type the address shown on the PC.", 14f, Theme.WARN))
                return@discover
            }
            for (pc in list) {
                val b = act.button("🖥  ${pc.name}   ${pc.host}") { askCode(pc) }
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                found.addView(b, lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
            }
        }
    }

    private fun typeAddress() {
        val input = EditText(act).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val box = FrameLayout(act).apply {
            val p = act.dpi(24f)
            setPadding(p, act.dpi(8f), p, 0)
            addView(input)
        }
        AlertDialog.Builder(act)
            .setTitle("PC address")
            .setMessage("It's shown on Glow's page on the PC, under Tablets & phones.")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                Pc.probe(input.text.toString()) { pc ->
                    if (pc == null) act.toast("No Glow at that address") else askCode(pc)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askCode(pc: Pc.Found) {
        val input = EditText(act).apply {
            hint = "6-digit code"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setSingleLine(true)
        }
        val box = FrameLayout(act).apply {
            val p = act.dpi(24f)
            setPadding(p, act.dpi(8f), p, 0)
            addView(input)
        }
        val d = AlertDialog.Builder(act)
            .setTitle("Pair with ${pc.name}")
            .setMessage("Enter the pairing code shown on Glow's page on the PC.")
            .setView(box)
            .setPositiveButton("Pair") { _, _ ->
                Pc.pair(pc, input.text.toString().trim()) { err ->
                    if (err != null) act.toast(err) else refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    override fun refresh() {
        val a = Pc.aito()
        setup.visibility = if (Pc.paired) View.GONE else View.VISIBLE
        controls.visibility = if (Pc.paired && a != null) View.VISIBLE else View.GONE
        toggle.visibility = if (a != null) View.VISIBLE else View.GONE
        status.text = when {
            !Pc.paired -> "Not connected to a PC yet"
            a == null -> Pc.error ?: "Connecting to ${Pc.name}…"
            a.optBoolean("connected") -> "● AITOLED ${a.optString("model")} · ${a.optInt("ledCount")} LEDs"
            else -> a.optString("status")
        }
        status.setTextColor(if (a?.optBoolean("connected") == true) Theme.OK else Theme.DIM)
        if (a == null) return
        val c = a.optJSONObject("config") ?: return
        toggle.on = c.optBoolean("on")
        val mode = c.optString("mode", "screen")
        tabs.selected = mode
        for ((k, v) in panels) v.visibility = if (k == mode) View.VISIBLE else View.GONE
        bright.set(c.optInt("brightness"))
        speed.set(c.optInt("speed"))
        smooth.set(c.optInt("smoothing"))
        boost.set(c.optInt("saturation"))
        val col = parseHex(c.optString("color"))
        if (!wheelSet) { wheel.color = col; wheelSet = true }
        swatches.selected = col
        fxSwatches.selected = col

        val effects = a.optJSONArray("effects")
        if (!fxBuilt && effects != null) {
            fxBuilt = true
            for (i in 0 until effects.length()) {
                val e = effects.getJSONObject(i)
                val id = e.optString("id")
                fx.addView(act.chip(e.optString("name")) { send(JSONObject().put("effect", id).put("mode", "effect").put("on", true)) }.apply { tag = id })
            }
        }
        for (i in 0 until fx.childCount) (fx.getChildAt(i) as TextView).chipOn(fx.getChildAt(i).tag == c.optString("effect"))

        val screen = Pc.state?.optJSONObject("screen")
        val cap = screen?.optJSONObject("capture")
        val st = cap?.optString("state") ?: "off"
        capBtn.text = when (st) {
            "starting" -> "Waiting for the PC…"
            "on" -> "Stop screen sync"
            else -> "Start screen sync"
        }
        capMsg.text = when {
            st == "error" -> cap?.optString("message") ?: ""
            st == "starting" -> "Pick your monitor in the window on the PC."
            st == "on" || screen?.optBoolean("active") == true -> "Syncing with the PC's screen."
            cap?.optBoolean("available") == false -> "The PC needs GStreamer for screen sync: sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire"
            else -> "The light copies the edges of your PC's screen."
        }
        capMsg.setTextColor(if (st == "error") Theme.WARN else Theme.DIM)
        pcLine.text = "PC: ${Pc.name} (${Pc.host})"
    }
}
