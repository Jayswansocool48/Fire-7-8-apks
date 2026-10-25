package io.github.jayswansocool48.havenremote

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Haven Remote: a pastel remote for the Fire TV running Haven. */
class MainActivity : Activity() {

    private lateinit var tv: TvLink
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private var tab = 0

    // Remote page
    private lateinit var pad: PadView
    private lateinit var keyboardBox: LinearLayout
    private lateinit var typed: EditText
    private lateinit var modeButtons: TextView
    private lateinit var modeSwipe: TextView

    // Apps page
    private lateinit var appsGrid: LinearLayout
    private lateinit var appsNote: TextView
    private lateinit var search: EditText
    private var allApps: List<TvApp> = emptyList()

    // Setup page
    private lateinit var hostField: EditText
    private lateinit var foundBox: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var homeResult: TextView
    private lateinit var restartButton: TextView
    private var restartArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = P.TOP
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.navigationBarColor = P.BOTTOM

        tv = TvLink(this)
        tv.listener = { updateStatus() }

        val root = FrameLayout(this)
        root.addView(BlobBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val column = vbox()
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        column.addView(topBar(), lp(MATCH, WRAP))
        val stack = FrameLayout(this)
        pages = listOf(remotePage(), appsPage(), setupPage())
        for (p in pages) stack.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(stack, lp(MATCH, 0, 1f))
        column.addView(bottomNav(), lp(MATCH, WRAP))
        setContentView(root)

        updateStatus()
        if (tv.host == null) showTab(2) else {
            showTab(0)
            tv.connect()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tv.host != null && (tv.state == TvLink.State.OFF || tv.state == TvLink.State.FAILED)) tv.connect()
    }

    override fun onDestroy() {
        tv.stopFinding()
        super.onDestroy()
    }

    /** The phone's own volume buttons change the TV's volume while the remote is showing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tab == 0 && tv.state == TvLink.State.ON) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) tv.key(code)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- chrome

    private fun topBar(): View {
        val bar = hbox().apply { setPadding(dpi(22f), dpi(16f), dpi(18f), dpi(8f)) }
        bar.addView(text("Haven", 30f, P.TEXT, bold = true))
        bar.addView(text(" ✿", 24f, P.PINK, bold = true))
        bar.addView(View(this), lp(0, 1, 1f))
        val chip = hbox().apply {
            background = rounded(P.CARD, dp(20f))
            elevation = dp(2f)
            setPadding(dpi(12f), dpi(8f), dpi(14f), dpi(8f))
        }
        statusDot = View(this)
        chip.addView(statusDot, LinearLayout.LayoutParams(dpi(10f), dpi(10f)))
        statusText = text("", 13f, P.TEXT).apply { maxLines = 1 }
        chip.addView(statusText, lp(WRAP, WRAP).apply { leftMargin = dpi(8f) })
        chip.squishy {
            when (tv.state) {
                TvLink.State.ON -> showTab(0)
                TvLink.State.CONNECTING, TvLink.State.APPROVE -> {}
                else -> if (tv.host == null) showTab(2) else tv.connect()
            }
        }
        val clp = lp(WRAP, WRAP)
        bar.addView(chip, clp)
        return bar
    }

    private fun bottomNav(): View {
        val nav = hbox().apply {
            background = rounded(P.CARD, dp(30f))
            elevation = dp(8f)
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
        }
        val names = listOf("🎮  Remote", "✦  Apps", "⚙  Setup")
        tabs = names.mapIndexed { i, n ->
            text(n, 15f, P.DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(12f), 0, dpi(12f))
                squishy { showTab(i) }
            }
        }
        for (t in tabs) nav.addView(t, lp(0, WRAP, 1f))
        val wrap = FrameLayout(this).apply { setPadding(dpi(16f), dpi(6f), dpi(16f), dpi(16f)) }
        wrap.addView(nav, FrameLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    private fun showTab(i: Int) {
        tab = i
        for ((n, p) in pages.withIndex()) p.visibility = if (n == i) View.VISIBLE else View.GONE
        for ((n, t) in tabs.withIndex()) {
            t.background = if (n == i) rounded(P.ACCENT, dp(24f)) else null
            t.setTextColor(if (n == i) P.WHITE else P.DIM)
        }
        hideKeyboard()
        if (i == 1 && allApps.isEmpty()) loadApps()
    }

    private fun updateStatus() {
        val (color, label) = when (tv.state) {
            TvLink.State.ON -> P.MINT to "TV connected"
            TvLink.State.CONNECTING -> P.PEACH to "Connecting…"
            TvLink.State.APPROVE -> P.PEACH to "Press OK on the TV"
            TvLink.State.FAILED -> P.PINK to "Tap to reconnect"
            TvLink.State.OFF -> P.DIM to if (tv.host == null) "No TV yet" else "Not connected"
        }
        statusDot.background = oval(color)
        statusText.text = label
        if (::setupStatus.isInitialized) setupStatus.text = tv.message
        if (tv.state == TvLink.State.APPROVE) toast("Look at the TV: tick \"Always allow\" and press OK")
        if (tv.state == TvLink.State.ON && tab == 1 && allApps.isEmpty()) loadApps()
    }

    // ---------------------------------------------------------------- remote

    private fun remotePage(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24f), dpi(8f), dpi(24f), dpi(16f))
        }
        scroll.addView(col)

        // Top row: power, Haven home, keyboard.
        val top = hbox()
        top.addView(roundKey("💤", null, P.ROSE, 52f) { tv.key(26) })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(button("✿  Haven home", P.WHITE, P.ACCENT) { tv.openHaven() })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(roundKey("⌨", null, P.SKY, 52f) { toggleKeyboard() })
        col.addView(top, lp(MATCH, WRAP))

        // Keyboard (hidden until ⌨).
        keyboardBox = vbox().apply {
            visibility = View.GONE
            background = rounded(P.CARD, dp(24f))
            setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f))
        }
        typed = EditText(this).apply {
            hint = "Type here, it appears on the TV"
            setHintTextColor(P.DIM)
            setTextColor(P.TEXT)
            typeface = Fonts.medium(this@MainActivity)
            textSize = 16f
            background = rounded(P.SOFT, dp(18f))
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) {
                    sendTyped(enter = true)
                    true
                } else false
            }
        }
        keyboardBox.addView(typed, lp(MATCH, WRAP))
        val kRow = hbox().apply { setPadding(0, dpi(10f), 0, 0) }
        kRow.addView(button("Send", P.ACCENT) { sendTyped(enter = false) }, lp(0, WRAP, 1f))
        kRow.addView(button("⌫", P.ROSE, P.TEXT, repeat = true) { tv.key(67) }, lp(0, WRAP, 1f).apply { leftMargin = dpi(8f) })
        kRow.addView(button("Enter ⏎", P.MINTY, P.TEXT) {
            sendTyped(enter = false)
            tv.key(66)
        }, lp(0, WRAP, 1f).apply { leftMargin = dpi(8f) })
        keyboardBox.addView(kRow, lp(MATCH, WRAP))
        col.addView(keyboardBox, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // Buttons / Swipe switch.
        val modes = hbox().apply {
            background = rounded(P.CARD, dp(20f))
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        modeButtons = text("Buttons", 13f, P.WHITE, bold = true)
        modeSwipe = text("Swipe", 13f, P.DIM, bold = true)
        for (m in listOf(modeButtons, modeSwipe)) {
            m.gravity = Gravity.CENTER
            m.setPadding(dpi(18f), dpi(7f), dpi(18f), dpi(7f))
            modes.addView(m)
        }
        modeButtons.squishy { setSwipe(false) }
        modeSwipe.squishy { setSwipe(true) }
        col.addView(modes, lp(WRAP, WRAP).apply { topMargin = dpi(16f) })

        // The d-pad.
        pad = PadView(this) { tv.key(it) }
        val padSize = Math.min(resources.displayMetrics.widthPixels - dpi(80f), dpi(300f))
        col.addView(pad, lp(padSize, padSize).apply { topMargin = dpi(16f) })

        // Back / Home / Menu.
        val nav = hbox().apply { gravity = Gravity.CENTER }
        nav.addView(roundKey("↩", "Back", P.LAV) { tv.key(4) })
        nav.addView(roundKey("⌂", "Home", P.MINTY) { tv.key(3) }, lp(WRAP, WRAP).apply { leftMargin = dpi(28f); rightMargin = dpi(28f) })
        nav.addView(roundKey("☰", "Menu", P.SUN) { tv.key(82) })
        col.addView(nav, lp(MATCH, WRAP).apply { topMargin = dpi(22f) })

        // Media.
        val media = hbox().apply { gravity = Gravity.CENTER }
        media.addView(roundKey("⏪", null, P.WHITE, 56f, repeat = true) { tv.key(89) })
        media.addView(roundKey("⏯", null, P.ROSE, 64f) { tv.key(85) }, lp(WRAP, WRAP).apply { leftMargin = dpi(24f); rightMargin = dpi(24f) })
        media.addView(roundKey("⏩", null, P.WHITE, 56f, repeat = true) { tv.key(90) })
        col.addView(media, lp(MATCH, WRAP).apply { topMargin = dpi(18f) })

        // Volume.
        val vol = hbox().apply {
            gravity = Gravity.CENTER
            background = rounded(P.CARD, dp(34f))
            elevation = dp(3f)
            setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        }
        vol.addView(roundKey("−", null, P.LAV, 52f, repeat = true) { tv.key(25) })
        vol.addView(text("Volume", 14f, P.DIM, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        vol.addView(roundKey("🔇", null, P.SOFT, 46f) { tv.key(164) })
        vol.addView(text("", 14f), lp(dpi(10f), WRAP))
        vol.addView(roundKey("+", null, P.LAV, 52f, repeat = true) { tv.key(24) })
        col.addView(vol, lp(MATCH, WRAP).apply { topMargin = dpi(22f) })

        col.addView(text("Tip: your phone's volume buttons work too.", 12f, P.DIM).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        return scroll
    }

    private fun setSwipe(on: Boolean) {
        pad.swipeMode = on
        modeButtons.background = if (!on) rounded(P.ACCENT, dp(16f)) else null
        modeSwipe.background = if (on) rounded(P.ACCENT, dp(16f)) else null
        modeButtons.setTextColor(if (!on) P.WHITE else P.DIM)
        modeSwipe.setTextColor(if (on) P.WHITE else P.DIM)
        getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putBoolean("swipe", on).apply()
    }

    private fun toggleKeyboard() {
        if (keyboardBox.visibility == View.VISIBLE) {
            keyboardBox.visibility = View.GONE
            hideKeyboard()
        } else {
            keyboardBox.visibility = View.VISIBLE
            typed.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(typed, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendTyped(enter: Boolean) {
        val s = typed.text.toString()
        if (s.isNotEmpty()) tv.type(s)
        if (enter) tv.key(66)
        typed.setText("")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ---------------------------------------------------------------- apps

    private fun appsPage(): View {
        val col = vbox().apply { setPadding(dpi(20f), dpi(8f), dpi(20f), 0) }
        val row = hbox()
        search = EditText(this).apply {
            hint = "🔍  Find an app"
            setHintTextColor(P.DIM)
            setTextColor(P.TEXT)
            typeface = Fonts.medium(this@MainActivity)
            textSize = 15f
            background = rounded(P.CARD, dp(22f))
            setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderApps()
            })
        }
        row.addView(search, lp(0, WRAP, 1f))
        row.addView(roundKey("↻", null, P.WHITE, 46f) { loadApps() }, lp(WRAP, WRAP).apply { leftMargin = dpi(10f) })
        col.addView(row, lp(MATCH, WRAP))
        appsNote = text("", 13f, P.DIM).apply { setPadding(dpi(4f), dpi(10f), dpi(4f), 0) }
        col.addView(appsNote, lp(MATCH, WRAP))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        appsGrid = vbox().apply { setPadding(0, dpi(10f), 0, dpi(20f)) }
        scroll.addView(appsGrid)
        col.addView(scroll, lp(MATCH, 0, 1f))
        return col
    }

    private fun loadApps() {
        if (tv.host == null) {
            appsNote.text = "Pick your TV in Setup first."
            return
        }
        appsNote.text = "Asking the TV…"
        tv.apps { list, fromHaven ->
            if (list == null) {
                appsNote.text = "Couldn't get the TV's apps. Is it on and connected?"
                return@apps
            }
            allApps = list
            appsNote.text = if (fromHaven) "${list.size} apps on the TV · tap one to open it" else "Open Haven on the TV to see app names and icons."
            renderApps()
        }
    }

    private fun renderApps() {
        if (!::appsGrid.isInitialized) return
        appsGrid.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = allApps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
        val cols = 3
        var row: LinearLayout? = null
        for ((i, app) in shown.withIndex()) {
            if (i % cols == 0) {
                row = hbox().apply { gravity = Gravity.TOP }
                appsGrid.addView(row, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
            }
            row!!.addView(appCard(app, i), lp(0, WRAP, 1f).apply {
                if (i % cols > 0) leftMargin = dpi(12f)
            })
        }
        // Keep the last row's cards the same width as the others.
        val rest = shown.size % cols
        if (rest > 0) repeat(cols - rest) { row?.addView(View(this), lp(0, 1, 1f).apply { leftMargin = dpi(12f) }) }
    }

    private fun appCard(app: TvApp, i: Int): View {
        val card = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(P.CARD, dp(22f))
            elevation = dp(2f)
            setPadding(dpi(8f), dpi(14f), dpi(8f), dpi(12f))
        }
        val bubble = FrameLayout(this).apply { background = rounded(P.TILES[i % P.TILES.size], dp(18f)) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val letter = text(app.label.take(1).uppercase(), 24f, P.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        bubble.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))
        bubble.addView(img, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.CENTER))
        card.addView(bubble, LinearLayout.LayoutParams(dpi(62f), dpi(62f)))
        val name = text((if (app.fav) "♥ " else "") + app.label, 13f, P.TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(0f, 1.1f)
        }
        card.addView(name, lp(MATCH, WRAP).apply { topMargin = dpi(8f) })
        tv.icon(app.pkg) { bmp ->
            if (bmp != null) {
                img.setImageBitmap(bmp)
                letter.visibility = View.GONE
            }
        }
        card.squishy {
            tv.launch(app)
            toast("Opening ${app.label} on the TV ✿")
        }
        return card
    }

    // ---------------------------------------------------------------- setup

    private fun setupPage(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = vbox().apply { setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(24f)) }
        scroll.addView(col)

        // Your TV
        val tvCard = card()
        tvCard.addView(text("Your TV", 20f, P.TEXT, bold = true))
        tvCard.addView(text("Haven on the TV shows up here by itself. You can also type the TV's address (Settings ▸ My Fire TV ▸ About ▸ Network).", 13f, P.DIM).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP).apply { topMargin = dpi(6f) })
        hostField = EditText(this).apply {
            hint = "TV address, like 192.168.1.42"
            setText(tv.host.orEmpty())
            setHintTextColor(P.DIM)
            setTextColor(P.TEXT)
            typeface = Fonts.medium(this@MainActivity)
            textSize = 16f
            background = rounded(P.SOFT, dp(18f))
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        tvCard.addView(hostField, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        val buttons = hbox().apply { setPadding(0, dpi(12f), 0, 0) }
        buttons.addView(button("🔍  Find my TV", P.SKY, P.TEXT) { findTvs() }, lp(0, WRAP, 1f))
        buttons.addView(button("Connect", P.ACCENT) {
            hideKeyboard()
            tv.connect(hostField.text.toString())
        }, lp(0, WRAP, 1f).apply { leftMargin = dpi(10f) })
        tvCard.addView(buttons, lp(MATCH, WRAP))
        foundBox = vbox()
        tvCard.addView(foundBox, lp(MATCH, WRAP))
        setupStatus = text(tv.message, 14f, P.ACCENT, bold = true)
        tvCard.addView(setupStatus, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        col.addView(tvCard, lp(MATCH, WRAP))

        // First time
        val first = card()
        first.addView(text("First time on this TV?", 20f, P.TEXT, bold = true))
        val steps = listOf(
            "Install Haven on the TV.",
            "On the TV: Settings ▸ My Fire TV ▸ About. Press OK on the TV's name 7 times until it says you're a developer.",
            "Back in My Fire TV ▸ Developer options, turn on ADB debugging.",
            "Here, tap Find my TV (or type its address), then Connect.",
            "The TV asks \"Allow USB debugging?\" Tick Always allow and press OK. That's it, just once.",
        )
        for ((i, s) in steps.withIndex()) {
            val r = hbox().apply { gravity = Gravity.TOP }
            r.addView(text("${i + 1}", 13f, P.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(if (i % 2 == 0) P.ACCENT else P.PINK)
            }, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
            r.addView(text(s, 14f, P.TEXT).apply { setLineSpacing(0f, 1.2f) }, lp(0, WRAP, 1f).apply { leftMargin = dpi(10f) })
            first.addView(r, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        }
        col.addView(first, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // Home button
        val home = card()
        home.addView(text("Make Haven the TV's home screen", 20f, P.TEXT, bold = true))
        home.addView(text("After this, the Home button and turning the TV on open Haven instead of the Fire TV home. Any app you already use to open your launcher keeps working.", 13f, P.DIM).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP).apply { topMargin = dpi(6f) })
        homeResult = text("", 14f, P.ACCENT, bold = true)
        home.addView(button("🏠  Make Haven the home screen", P.PINK) {
            homeResult.text = "Working on it…"
            tv.makeHavenHome { homeResult.text = it }
        }, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        home.addView(homeResult, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        col.addView(home, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // More
        val more = card()
        more.addView(text("More", 20f, P.TEXT, bold = true))
        restartButton = button("Restart the TV", P.SUN, P.TEXT) {
            if (!restartArmed) {
                restartArmed = true
                restartButton.text = "Tap again to restart"
                restartButton.postDelayed({
                    restartArmed = false
                    restartButton.text = "Restart the TV"
                }, 4000)
            } else {
                restartArmed = false
                restartButton.text = "Restart the TV"
                tv.reboot()
            }
        }
        more.addView(restartButton, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        more.addView(button("Disconnect", P.SOFT, P.TEXT) { tv.disconnect() }, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        col.addView(more, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // Remember Buttons / Swipe.
        if (getSharedPreferences("remote", Context.MODE_PRIVATE).getBoolean("swipe", false)) {
            pad.post { setSwipe(true) }
        } else {
            pad.post { setSwipe(false) }
        }
        return scroll
    }

    private fun findTvs() {
        hideKeyboard()
        foundBox.removeAllViews()
        setupStatus.text = "Looking for Haven on your Wi-Fi…"
        val seen = HashSet<String>()
        tv.findTvs({ name, host ->
            if (!seen.add(host)) return@findTvs
            foundBox.addView(button("📺  $name  ·  $host", P.MINTY, P.TEXT) {
                hostField.setText(host)
                tv.connect(host)
            }, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
            if (seen.size == 1 && tv.host == null) {
                hostField.setText(host)
                tv.connect(host)
            }
        }) {
            setupStatus.text = if (seen.isEmpty()) "Didn't find Haven. Open it on the TV, or type the TV's address." else "Found ${seen.size} TV${if (seen.size == 1) "" else "s"} ✿"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
