package io.github.jayswansocool48.havenremote

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
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
    private val tabIcons = ArrayList<ImageView>()
    private val tabLabels = ArrayList<TextView>()
    private val tabBoxes = ArrayList<LinearLayout>()
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
    private lateinit var restartLabel: TextView
    private var restartArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = P.TOP
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
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

        setSwipe(getSharedPreferences("remote", Context.MODE_PRIVATE).getBoolean("swipe", false))
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
        val bar = hbox().apply { setPadding(dpi(24f), dpi(18f), dpi(20f), dpi(10f)) }
        bar.addView(text("Haven", 26f, P.TEXT, bold = true))
        bar.addView(View(this), lp(0, 1, 1f))
        val chip = hbox().apply {
            background = rounded(P.CARD, dp(18f))
            setPadding(dpi(12f), dpi(8f), dpi(14f), dpi(8f))
        }
        statusDot = View(this)
        chip.addView(statusDot, LinearLayout.LayoutParams(dpi(8f), dpi(8f)))
        statusText = text("", 13f, P.TEXT).apply { maxLines = 1 }
        chip.addView(statusText, lp(WRAP, WRAP).apply { leftMargin = dpi(8f) })
        chip.squishy {
            when (tv.state) {
                TvLink.State.ON -> showTab(0)
                TvLink.State.CONNECTING, TvLink.State.APPROVE -> {}
                else -> if (tv.host == null) showTab(2) else tv.connect()
            }
        }
        bar.addView(chip)
        return bar
    }

    private fun bottomNav(): View {
        val nav = hbox().apply {
            background = rounded(P.WHITE, dp(28f))
            elevation = dp(6f)
            setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        }
        val tabsDef = listOf(R.drawable.ic_remote to "Remote", R.drawable.ic_apps to "Apps", R.drawable.ic_settings to "Setup")
        for ((i, d) in tabsDef.withIndex()) {
            val box = vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(8f), 0, dpi(8f))
            }
            val ic = icon(d.first, P.DIM)
            box.addView(ic, LinearLayout.LayoutParams(dpi(22f), dpi(22f)))
            val label = text(d.second, 12f, P.DIM, bold = true)
            box.addView(label, lp(WRAP, WRAP).apply { topMargin = dpi(4f) })
            box.squishy { showTab(i) }
            tabIcons += ic
            tabLabels += label
            tabBoxes += box
            nav.addView(box, lp(0, WRAP, 1f))
        }
        val wrap = FrameLayout(this).apply { setPadding(dpi(18f), dpi(6f), dpi(18f), dpi(16f)) }
        wrap.addView(nav, FrameLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    private fun showTab(i: Int) {
        tab = i
        for ((n, p) in pages.withIndex()) p.visibility = if (n == i) View.VISIBLE else View.GONE
        for (n in tabBoxes.indices) {
            val on = n == i
            tabBoxes[n].background = if (on) rounded(P.SOFT, dp(20f)) else null
            tabIcons[n].imageTintList = ColorStateList.valueOf(if (on) P.ACCENT else P.DIM)
            tabLabels[n].setTextColor(if (on) P.ACCENT else P.DIM)
        }
        hideKeyboard()
        if (i == 1 && allApps.isEmpty()) loadApps()
    }

    private fun updateStatus() {
        val (color, label) = when (tv.state) {
            TvLink.State.ON -> P.MINT to "Connected"
            TvLink.State.CONNECTING -> P.PEACH to "Connecting…"
            TvLink.State.APPROVE -> P.PEACH to "Press OK on the TV"
            TvLink.State.FAILED -> P.PINK to "Tap to reconnect"
            TvLink.State.OFF -> P.DIM to if (tv.host == null) "No TV yet" else "Not connected"
        }
        statusDot.background = oval(color)
        statusText.text = label
        if (::setupStatus.isInitialized) {
            setupStatus.text = tv.message
            setupStatus.visibility = if (tv.message.isEmpty()) View.GONE else View.VISIBLE
        }
        if (tv.state == TvLink.State.APPROVE) toast("On the TV, tick \"Always allow\" and press OK")
        if (tv.state == TvLink.State.ON && tab == 1 && allApps.isEmpty()) loadApps()
    }

    // ---------------------------------------------------------------- remote

    private fun remotePage(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(28f), dpi(6f), dpi(28f), dpi(16f))
        }
        scroll.addView(col)

        // Top row: sleep, Haven home, keyboard.
        val top = hbox()
        top.addView(roundKey(R.drawable.ic_power, P.WHITE, P.PINK, 48f, 22f) { tv.key(26) })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(button("Haven", P.WHITE, P.ACCENT, R.drawable.ic_home) { tv.openHaven() }.apply { elevation = dp(2f) })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(roundKey(R.drawable.ic_keyboard, P.WHITE, P.TEXT, 48f, 22f) { toggleKeyboard() })
        col.addView(top, lp(MATCH, WRAP))

        // Keyboard (hidden until the keyboard button is tapped).
        keyboardBox = vbox().apply {
            visibility = View.GONE
            background = rounded(P.CARD, dp(22f))
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
        }
        typed = EditText(this).apply {
            hint = "Type on the TV"
            setHintTextColor(P.DIM)
            setTextColor(P.TEXT)
            typeface = Fonts.medium(this@MainActivity)
            textSize = 16f
            background = rounded(P.SOFT, dp(14f))
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
        val kRow = hbox()
        kRow.addView(typed, lp(0, WRAP, 1f))
        kRow.addView(roundKey(R.drawable.ic_backspace, P.SOFT, P.TEXT, 44f, 20f, repeat = true) { tv.key(67) }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { leftMargin = dpi(8f) })
        kRow.addView(roundKey(R.drawable.ic_send, P.ACCENT, P.WHITE, 44f, 20f) { sendTyped(enter = true) }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { leftMargin = dpi(8f) })
        keyboardBox.addView(kRow, lp(MATCH, WRAP))
        col.addView(keyboardBox, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // Buttons / Swipe switch.
        val modes = hbox().apply {
            background = rounded(withWhite(0x99), dp(18f))
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        modeButtons = text("Buttons", 13f, P.TEXT, bold = true)
        modeSwipe = text("Swipe", 13f, P.DIM, bold = true)
        for (m in listOf(modeButtons, modeSwipe)) {
            m.gravity = Gravity.CENTER
            m.setPadding(dpi(20f), dpi(7f), dpi(20f), dpi(7f))
            modes.addView(m)
        }
        modeButtons.squishy { setSwipe(false) }
        modeSwipe.squishy { setSwipe(true) }
        col.addView(modes, lp(WRAP, WRAP).apply { topMargin = dpi(22f) })

        // The d-pad.
        pad = PadView(this) { tv.key(it) }
        val padSize = Math.min(resources.displayMetrics.widthPixels - dpi(96f), dpi(290f))
        col.addView(pad, lp(padSize, padSize).apply { topMargin = dpi(20f) })

        // Back / Home / Menu.
        val nav = hbox().apply { gravity = Gravity.CENTER }
        nav.addView(roundKey(R.drawable.ic_back, P.WHITE) { tv.key(4) })
        nav.addView(roundKey(R.drawable.ic_home, P.WHITE) { tv.key(3) }, LinearLayout.LayoutParams(dpi(60f), dpi(60f)).apply { leftMargin = dpi(36f); rightMargin = dpi(36f) })
        nav.addView(roundKey(R.drawable.ic_menu, P.WHITE) { tv.key(82) })
        col.addView(nav, lp(MATCH, WRAP).apply { topMargin = dpi(26f) })

        // Media and volume share one soft card.
        val controls = vbox().apply {
            background = rounded(P.CARD, dp(28f))
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        }
        val media = hbox().apply { gravity = Gravity.CENTER }
        media.addView(roundKey(R.drawable.ic_rewind, P.SOFT, P.TEXT, 52f, 24f, repeat = true) { tv.key(89) })
        media.addView(roundKey(R.drawable.ic_play_pause, P.ACCENT, P.WHITE, 64f, 28f) { tv.key(85) }, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { leftMargin = dpi(28f); rightMargin = dpi(28f) })
        media.addView(roundKey(R.drawable.ic_forward, P.SOFT, P.TEXT, 52f, 24f, repeat = true) { tv.key(90) })
        controls.addView(media, lp(MATCH, WRAP))
        controls.addView(View(this).apply { setBackgroundColor(P.SOFT) }, lp(MATCH, dpi(1f)).apply { topMargin = dpi(14f); bottomMargin = dpi(14f) })
        val vol = hbox().apply { gravity = Gravity.CENTER }
        vol.addView(roundKey(R.drawable.ic_vol_down, P.SOFT, P.TEXT, 52f, 24f, repeat = true) { tv.key(25) })
        vol.addView(View(this), lp(0, 1, 1f))
        vol.addView(roundKey(R.drawable.ic_mute, P.SOFT, P.DIM, 44f, 20f) { tv.key(164) })
        vol.addView(View(this), lp(0, 1, 1f))
        vol.addView(roundKey(R.drawable.ic_vol_up, P.SOFT, P.TEXT, 52f, 24f, repeat = true) { tv.key(24) })
        controls.addView(vol, lp(MATCH, WRAP))
        col.addView(controls, lp(MATCH, WRAP).apply { topMargin = dpi(26f) })

        col.addView(text("Your phone's volume buttons work too.", 12f, P.DIM).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        return scroll
    }

    private fun withWhite(alpha: Int) = (alpha shl 24) or 0xFFFFFF

    private fun setSwipe(on: Boolean) {
        pad.swipeMode = on
        modeButtons.background = if (!on) rounded(P.WHITE, dp(14f)) else null
        modeSwipe.background = if (on) rounded(P.WHITE, dp(14f)) else null
        modeButtons.setTextColor(if (!on) P.TEXT else P.DIM)
        modeSwipe.setTextColor(if (on) P.TEXT else P.DIM)
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
        val col = vbox().apply { setPadding(dpi(20f), dpi(6f), dpi(20f), 0) }
        val row = hbox()
        val searchBox = hbox().apply {
            background = rounded(P.CARD, dp(18f))
            setPadding(dpi(14f), 0, dpi(8f), 0)
        }
        searchBox.addView(icon(R.drawable.ic_search, P.DIM), LinearLayout.LayoutParams(dpi(20f), dpi(20f)))
        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(P.DIM)
            setTextColor(P.TEXT)
            typeface = Fonts.medium(this@MainActivity)
            textSize = 15f
            background = null
            setPadding(dpi(10f), dpi(13f), dpi(10f), dpi(13f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderApps()
            })
        }
        searchBox.addView(search, lp(0, WRAP, 1f))
        row.addView(searchBox, lp(0, WRAP, 1f))
        row.addView(roundKey(R.drawable.ic_refresh, P.CARD, P.TEXT, 48f, 22f) { loadApps() }, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { leftMargin = dpi(10f) })
        col.addView(row, lp(MATCH, WRAP))
        appsNote = text("", 13f, P.DIM).apply { setPadding(dpi(4f), dpi(12f), dpi(4f), 0) }
        col.addView(appsNote, lp(MATCH, WRAP))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        appsGrid = vbox().apply { setPadding(0, dpi(6f), 0, dpi(20f)) }
        scroll.addView(appsGrid)
        col.addView(scroll, lp(MATCH, 0, 1f))
        return col
    }

    private fun loadApps() {
        if (tv.host == null) {
            appsNote.text = "Pick your TV in Setup first."
            return
        }
        appsNote.text = "Getting the TV's apps…"
        tv.apps { list, fromHaven ->
            if (list == null) {
                appsNote.text = "Couldn't get the TV's apps. Is the TV on?"
                return@apps
            }
            allApps = list
            appsNote.text = if (fromHaven) "${list.size} apps · tap one to open it on the TV" else "Open Haven on the TV to see app names and icons."
            renderApps()
        }
    }

    private fun renderApps() {
        if (!::appsGrid.isInitialized) return
        appsGrid.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = allApps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
        val cols = 4
        var row: LinearLayout? = null
        for ((i, app) in shown.withIndex()) {
            if (i % cols == 0) {
                row = hbox().apply { gravity = Gravity.TOP }
                appsGrid.addView(row, lp(MATCH, WRAP).apply { topMargin = dpi(18f) })
            }
            row!!.addView(appCell(app, i), lp(0, WRAP, 1f))
        }
        val rest = shown.size % cols
        if (rest > 0) repeat(cols - rest) { row?.addView(View(this), lp(0, 1, 1f)) }
    }

    /** A home-screen style app: rounded icon tile with the name under it. */
    private fun appCell(app: TvApp, i: Int): View {
        val cell = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val bubble = FrameLayout(this).apply {
            background = rounded(P.WHITE, dp(20f))
            elevation = dp(2f)
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val letter = text(app.label.take(1).uppercase(), 22f, P.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(P.TILES[i % P.TILES.size], dp(20f))
        }
        bubble.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))
        bubble.addView(img, FrameLayout.LayoutParams(dpi(42f), dpi(42f), Gravity.CENTER))
        cell.addView(bubble, LinearLayout.LayoutParams(dpi(62f), dpi(62f)))
        val name = text(app.label, 12f, P.TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(0f, 1.05f)
            setPadding(dpi(2f), 0, dpi(2f), 0)
        }
        cell.addView(name, lp(MATCH, WRAP).apply { topMargin = dpi(8f) })
        tv.icon(app.pkg) { bmp ->
            if (bmp != null) {
                img.setImageBitmap(bmp)
                letter.visibility = View.GONE
            }
        }
        cell.squishy {
            tv.launch(app)
            toast("Opening ${app.label}")
        }
        return cell
    }

    // ---------------------------------------------------------------- setup

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(P.DIM)
        setTextColor(P.TEXT)
        typeface = Fonts.medium(this@MainActivity)
        textSize = 16f
        background = rounded(P.SOFT, dp(14f))
        setPadding(dpi(16f), dpi(13f), dpi(16f), dpi(13f))
        maxLines = 1
    }

    private fun Context.sectionTitle(s: String) = text(s, 18f, P.TEXT, bold = true)
    private fun Context.blurb(s: String) = text(s, 14f, P.DIM).apply { setLineSpacing(0f, 1.25f) }

    private fun setupPage(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = vbox().apply { setPadding(dpi(20f), dpi(6f), dpi(20f), dpi(24f)) }
        scroll.addView(col)

        // Your TV
        val tvCard = card()
        tvCard.addView(sectionTitle("Your TV"))
        tvCard.addView(blurb("Haven finds the TV on your Wi-Fi. You can also type its address from Settings › My Fire TV › About › Network."), lp(MATCH, WRAP).apply { topMargin = dpi(6f) })
        hostField = field("TV address, like 192.168.1.42").apply {
            setText(tv.host.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        tvCard.addView(hostField, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })
        val buttons = hbox().apply { setPadding(0, dpi(10f), 0, 0) }
        buttons.addView(button("Find my TV", P.SOFT, P.TEXT, R.drawable.ic_search) { findTvs() }, lp(0, WRAP, 1f))
        buttons.addView(button("Connect", P.ACCENT) {
            hideKeyboard()
            tv.connect(hostField.text.toString())
        }, lp(0, WRAP, 1f).apply { leftMargin = dpi(10f) })
        tvCard.addView(buttons, lp(MATCH, WRAP))
        foundBox = vbox()
        tvCard.addView(foundBox, lp(MATCH, WRAP))
        setupStatus = text(tv.message, 13f, P.ACCENT, bold = true).apply { visibility = if (tv.message.isEmpty()) View.GONE else View.VISIBLE }
        tvCard.addView(setupStatus, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        col.addView(tvCard, lp(MATCH, WRAP))

        // First time
        val first = card()
        first.addView(sectionTitle("First time on this TV"))
        val steps = listOf(
            "Install Haven on the TV.",
            "On the TV, open Settings › My Fire TV › About and press OK on the TV's name 7 times.",
            "Go to My Fire TV › Developer options and turn on ADB debugging.",
            "Here, tap Find my TV, then Connect.",
            "On the TV, tick Always allow and press OK. You only do this once.",
        )
        for ((i, s) in steps.withIndex()) {
            val r = hbox().apply { gravity = Gravity.TOP }
            r.addView(text("${i + 1}", 12f, P.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(P.SOFT)
            }, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
            r.addView(text(s, 14f, P.TEXT).apply { setLineSpacing(0f, 1.25f) }, lp(0, WRAP, 1f).apply { leftMargin = dpi(12f); topMargin = dpi(2f) })
            first.addView(r, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        }
        col.addView(first, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // Home button
        val home = card()
        home.addView(sectionTitle("Make Haven the home screen"))
        home.addView(blurb("The Home button and turning the TV on will open Haven instead of the Fire TV home. Apps you already use to open your launcher keep working."), lp(MATCH, WRAP).apply { topMargin = dpi(6f) })
        homeResult = text("", 13f, P.ACCENT, bold = true).apply { visibility = View.GONE }
        home.addView(button("Make Haven the home screen", P.ACCENT, P.WHITE, R.drawable.ic_home) {
            homeResult.visibility = View.VISIBLE
            homeResult.text = "Working on it…"
            tv.makeHavenHome { homeResult.text = it }
        }, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })
        home.addView(homeResult, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        col.addView(home, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })

        // More
        val more = card()
        more.addView(sectionTitle("More"))
        val restart = button("Restart the TV", P.SOFT, P.TEXT, R.drawable.ic_refresh) {}
        restartLabel = restart.getChildAt(restart.childCount - 1) as TextView
        restart.squishy {
            if (!restartArmed) {
                restartArmed = true
                restartLabel.text = "Tap again to restart"
                restart.postDelayed({
                    restartArmed = false
                    restartLabel.text = "Restart the TV"
                }, 4000)
            } else {
                restartArmed = false
                restartLabel.text = "Restart the TV"
                tv.reboot()
            }
        }
        more.addView(restart, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })
        more.addView(button("Disconnect", P.SOFT, P.TEXT, R.drawable.ic_close) { tv.disconnect() }, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        col.addView(more, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })
        return scroll
    }

    private fun findTvs() {
        hideKeyboard()
        foundBox.removeAllViews()
        setupStatus.visibility = View.VISIBLE
        setupStatus.text = "Looking for Haven on your Wi-Fi…"
        val seen = HashSet<String>()
        tv.findTvs({ name, host ->
            if (!seen.add(host)) return@findTvs
            foundBox.addView(button("$name  ·  $host", P.MINTY, P.TEXT, R.drawable.ic_tv) {
                hostField.setText(host)
                tv.connect(host)
            }, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
            if (seen.size == 1 && tv.host == null) {
                hostField.setText(host)
                tv.connect(host)
            }
        }) {
            setupStatus.text = if (seen.isEmpty()) "Didn't find Haven. Open it on the TV, or type the TV's address." else "Found ${seen.size} TV${if (seen.size == 1) "" else "s"}"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
