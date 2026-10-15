package io.github.jayswansocool48.deskmate

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One full-screen page. MainActivity shows the top one and handles Back. */
abstract class Screen(val app: MainActivity) : FrameLayout(app) {
    abstract val title: String
    open val accent: Int = Theme.ACCENT
    /** Extra buttons for the top bar. */
    open val actions: List<Pair<Icon, () -> Unit>> = emptyList()
    open fun onShow() {}
    open fun onHide() {}
    open fun onConnectionChanged() {}
    open fun onOrientationChanged() {}

    protected fun pad(v: View, all: Float = 16f): View {
        val p = app.dpi(all)
        v.setPadding(p, p, p, p)
        return v
    }

    protected fun scroll(content: View): ScrollView = ScrollView(app).apply {
        isFillViewport = true
        addView(content)
    }

    /** "Not connected" strip with a button, shown by tools while offline. */
    protected fun offlineBanner(): LinearLayout {
        val bar = app.hbox().apply {
            background = rounded(0xFF2A2433.toInt(), app.dp(14f))
            val p = app.dpi(12f)
            setPadding(p + p / 2, p, p, p)
        }
        val text = app.label("", 15f, Theme.TEXT)
        bar.addView(text, lp(0, WRAP, 1f))
        val btn = PillButton(app, "Connect", Icon.PC, Theme.ACCENT).apply { textColor = 0xFFFFFFFF.toInt() }
        btn.setOnClickListener { app.push(ConnectScreen(app)) }
        bar.addView(btn, lp(app.dpi(140f), app.dpi(44f)))
        fun refresh() {
            val s = DeskClient.state
            bar.visibility = if (s == DeskClient.State.ONLINE) View.GONE else View.VISIBLE
            text.text = when (s) {
                DeskClient.State.NO_PC -> "No PC set up yet."
                DeskClient.State.CONNECTING -> "Connecting to ${DeskClient.pcName}…"
                DeskClient.State.NEED_PAIR -> "Enter the pairing code shown on ${DeskClient.pcName}."
                else -> "Can't reach ${DeskClient.pcName}. Is Deskmate running on it?"
            }
            btn.visibility = if (s == DeskClient.State.CONNECTING) View.GONE else View.VISIBLE
        }
        bar.tag = Runnable { refresh() }
        refresh()
        return bar
    }

    protected fun refreshBanner(bar: View?) {
        (bar?.tag as? Runnable)?.run()
    }
}

// ---------------------------------------------------------------- Home

class HomeScreen(app: MainActivity) : Screen(app) {
    override val title = "Deskmate"
    private val status = app.label("", 15f, Theme.DIM)
    private val pcButton = PillButton(app, "Connect", Icon.PC)
    private val dot = View(app)

    init {
        val col = app.vbox()
        pad(col, 20f)

        val head = app.hbox()
        val titles = app.vbox()
        titles.addView(app.label("Deskmate", 30f, Theme.TEXT, bold = true))
        val statusRow = app.hbox()
        statusRow.addView(dot, lp(app.dpi(10f), app.dpi(10f)).apply { rightMargin = app.dpi(8f) })
        statusRow.addView(status)
        titles.addView(statusRow, lp(WRAP, WRAP).apply { topMargin = app.dpi(4f) })
        head.addView(titles, lp(0, WRAP, 1f))
        pcButton.setOnClickListener { app.push(ConnectScreen(app)) }
        head.addView(pcButton, lp(app.dpi(170f), app.dpi(48f)))
        col.addView(head)

        val grid = FlowGrid(app, app.dp(230f), app.dp(190f), app.dp(14f))
        fun tile(t: String, s: String, icon: Icon, color: Int, open: () -> Screen) {
            val v = ToolTile(app, t, s, icon, color)
            v.setOnClickListener { app.push(open()) }
            grid.addView(v)
        }
        tile("Macro Pad", "Big buttons for shortcuts, apps and commands on your PC.", Icon.PAD, Theme.PAD) { MacroScreen(app) }
        tile("System Monitor", "CPU, GPU, memory, temperatures and network, live.", Icon.MONITOR, Theme.MONITOR) { MonitorScreen(app) }
        tile("Media & Volume", "Play, pause, skip, volume and mic mute.", Icon.MEDIA, Theme.MEDIA) { MediaScreen(app) }
        tile("Notes & Timer", "Notes, a focus timer and clipboard sharing with your PC.", Icon.NOTES, Theme.NOTES) { NotesScreen(app) }
        tile("Game Dev Panel", "Sliders and switches that tweak your running game.", Icon.GAMEPAD, Theme.DEV) { DevScreen(app) }
        col.addView(grid, lp(MATCH, WRAP).apply { topMargin = app.dpi(22f) })

        val tip = app.label("Tip: the tablet stays awake while Deskmate is open, so it works as a desk dashboard.", 13f, Theme.DIM)
        col.addView(tip, lp(MATCH, WRAP).apply { topMargin = app.dpi(18f) })
        addView(scroll(col))
        onConnectionChanged()
    }

    override fun onConnectionChanged() {
        val s = DeskClient.state
        val (text, color) = when (s) {
            DeskClient.State.NO_PC -> "Not set up. Connect to your PC to start." to Theme.DIM
            DeskClient.State.OFFLINE -> "Can't reach ${DeskClient.pcName}. Retrying…" to Theme.BAD
            DeskClient.State.CONNECTING -> "Connecting to ${DeskClient.pcName}…" to Theme.WARN
            DeskClient.State.NEED_PAIR -> "Waiting for pairing code" to Theme.WARN
            DeskClient.State.ONLINE -> "Connected to ${DeskClient.pcName}" to Theme.OK
        }
        status.text = text
        dot.background = rounded(color, app.dp(5f))
        pcButton.update(if (s == DeskClient.State.NO_PC) "Connect" else "Change PC")
    }
}

// ---------------------------------------------------------------- Connect

class ConnectScreen(app: MainActivity) : Screen(app) {
    override val title = "Connect to your PC"
    override val actions = listOf<Pair<Icon, () -> Unit>>(Icon.REFRESH to { search() })
    private val list = app.vbox()
    private var busy = false
    private val searching = app.label("", 14f, Theme.DIM)
    private val current = app.vbox()

    init {
        val col = app.vbox()
        pad(col, 20f)
        col.addView(app.label("1.  On your PC, start Deskmate (download it from the Releases page of your GitHub repo).", 15f, Theme.TEXT))
        col.addView(app.label("2.  Pick your PC below, then type the 6-digit code it shows.", 15f, Theme.TEXT),
            lp(MATCH, WRAP).apply { topMargin = app.dpi(6f) })
        col.addView(app.label("Both devices need to be on the same Wi-Fi network.", 13f, Theme.DIM),
            lp(MATCH, WRAP).apply { topMargin = app.dpi(6f) })

        col.addView(current, lp(MATCH, WRAP).apply { topMargin = app.dpi(16f) })
        col.addView(app.label("PCs on this network", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = app.dpi(18f) })
        col.addView(searching, lp(MATCH, WRAP).apply { topMargin = app.dpi(6f) })
        col.addView(list, lp(MATCH, WRAP))

        col.addView(app.label("Or type its address", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = app.dpi(22f) })
        val row = app.hbox()
        val input = EditText(app).apply {
            hint = "e.g. 192.168.1.20"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        row.addView(input, lp(0, WRAP, 1f))
        val go = PillButton(app, "Connect", Icon.LINK, Theme.ACCENT).apply { textColor = 0xFFFFFFFF.toInt() }
        go.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val host = text.substringBefore(":")
            val port = text.substringAfter(":", "").toIntOrNull() ?: DeskClient.DEFAULT_PORT
            DeskClient.choose(DeskClient.FoundPC(host, host, port, ""))
            app.toast("Connecting to $host…")
        }
        row.addView(go, lp(app.dpi(140f), app.dpi(46f)).apply { leftMargin = app.dpi(10f) })
        col.addView(row, lp(MATCH, WRAP))

        addView(scroll(col))
        showCurrent()
    }

    override fun onShow() = search()

    override fun onConnectionChanged() {
        showCurrent()
        if (DeskClient.state == DeskClient.State.ONLINE) {
            app.toast("Connected to ${DeskClient.pcName}")
            app.pop()
        }
    }

    private fun showCurrent() {
        current.removeAllViews()
        if (DeskClient.host == null) return
        val row = app.hbox().apply {
            background = rounded(Theme.SURFACE, app.dp(14f))
            val p = app.dpi(14f)
            setPadding(p, p, p, p)
        }
        val info = app.vbox()
        info.addView(app.label(DeskClient.pcName, 17f, Theme.TEXT, bold = true))
        info.addView(app.label("${DeskClient.host} · ${DeskClient.state.name.lowercase().replace('_', ' ')}", 13f, Theme.DIM))
        row.addView(info, lp(0, WRAP, 1f))
        val forget = PillButton(app, "Forget", Icon.TRASH)
        forget.setOnClickListener {
            DeskClient.forget()
            showCurrent()
        }
        row.addView(forget, lp(app.dpi(120f), app.dpi(44f)))
        current.addView(row)
    }

    private fun search() {
        if (busy) return
        busy = true
        list.removeAllViews()
        searching.text = "Searching…"
        var found = 0
        DeskClient.discover(onFound = { pc ->
            found++
            val b = PillButton(app, "${pc.name}   ·   ${pc.address}", Icon.PC, Theme.SURFACE)
            b.setOnClickListener {
                DeskClient.choose(pc)
                app.toast("Connecting to ${pc.name}…")
            }
            list.addView(b, lp(MATCH, app.dpi(56f)).apply { topMargin = app.dpi(8f) })
        }, onDone = {
            busy = false
            searching.text = if (found == 0) "No PCs found. Make sure Deskmate is running on your PC, or type its address below." else ""
        })
    }
}

/** Shared helper: a small dim heading. */
fun MainActivity.heading(text: String): TextView = label(text.uppercase(), 12f, Theme.DIM).apply {
    letterSpacing = 0.08f
    gravity = Gravity.START
}
