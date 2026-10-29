package io.github.jayswansocool48.hubremote

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Base for each tab: a scrolling column that stays readable on wide screens. */
abstract class Screen(val act: MainActivity, scroll: Boolean = true) : FrameLayout(act) {
    protected val body: LinearLayout = act.vbox()

    init {
        val p = act.dpi(18f)
        body.setPadding(p, act.dpi(6f), p, act.dpi(36f))
        if (scroll) {
            val sv = ScrollView(act).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
            val holder = object : FrameLayout(act) {
                override fun onMeasure(wSpec: Int, hSpec: Int) {
                    val w = MeasureSpec.getSize(wSpec)
                    val maxW = act.dpi(720f)
                    super.onMeasure(if (w > maxW) MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY) else wSpec, hSpec)
                }
            }
            holder.addView(body, LayoutParams(MATCH, WRAP))
            val center = FrameLayout(act)
            center.addView(holder, LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
            sv.addView(center)
            addView(sv, LayoutParams(MATCH, MATCH))
        } else {
            addView(body, LayoutParams(MATCH, MATCH))
        }
    }

    open fun onShow() {}
    open fun onHide() {}
    open fun onBack(): Boolean = false
    /** True when Back stays inside this tab (like going up a folder), so there's no tab to preview. */
    open fun wantsBack(): Boolean = false

    protected fun heading(text: String, lede: String? = null) {
        body.addView(act.label(text, 24f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        if (lede != null) body.addView(act.label(lede, 15f, Theme.MIST), lp(MATCH, WRAP).apply { topMargin = act.dpi(4f); bottomMargin = act.dpi(6f) })
    }

    protected fun section(text: String, note: String? = null) {
        body.addView(act.label(text, 17f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(26f); bottomMargin = act.dpi(8f) })
        if (note != null) body.addView(act.label(note, 13f, Theme.DIM), lp(MATCH, WRAP).apply { bottomMargin = act.dpi(10f) })
    }

    protected fun add(v: View, top: Float = 10f) = body.addView(v, lp(MATCH, WRAP).apply { topMargin = act.dpi(top) })
}

// =====================================================================  Remote

class RemoteScreen(act: MainActivity) : Screen(act, scroll = false) {
    private val pad = PadView(act)

    init {
        val wide = act.resources.configuration.screenWidthDp > act.resources.configuration.screenHeightDp && act.resources.configuration.screenWidthDp >= 600
        val modes = act.hbox().apply { background = rounded(Theme.DEEP, act.dp(99f), Theme.LINE, act.dpi(1f)); setPadding(act.dpi(4f), act.dpi(4f), act.dpi(4f), act.dpi(4f)) }
        val nav = act.label("Navigate", 14f, Theme.SNOW, bold = true)
        val ptr = act.label("Pointer", 14f, Theme.MIST, bold = true)
        fun setMode(pointer: Boolean) {
            pad.pointerMode = pointer
            nav.background = if (!pointer) rounded(Theme.SNOW, act.dp(99f)) else null
            ptr.background = if (pointer) rounded(Theme.SNOW, act.dp(99f)) else null
            nav.setTextColor(if (!pointer) Theme.NIGHT else Theme.MIST)
            ptr.setTextColor(if (pointer) Theme.NIGHT else Theme.MIST)
        }
        for (t in listOf(nav, ptr)) {
            t.setPadding(act.dpi(18f), act.dpi(8f), act.dpi(18f), act.dpi(8f))
            modes.addView(t)
        }
        nav.setOnClickListener { it.tick(); setMode(false) }
        ptr.setOnClickListener { it.tick(); setMode(true) }
        setMode(false)

        val controls = act.vbox()
        val keys = Grid(act, act.dpi(90f), fixedCols = 3)
        for ((k, name) in listOf("Back" to "Back", "Home" to "Home", "Info" to "✱ Options", "Rev" to "◀◀", "Play" to "▶ ❚❚", "Fwd" to "▶▶")) {
            keys.addView(keyButton(name) { Hub.key(k) }, ViewGroupLp())
        }
        controls.addView(keys, lp(MATCH, WRAP))
        val row = act.hbox()
        val rocker = act.hbox().apply { background = rounded(Theme.DEEP, act.dp(14f), Theme.LINE, act.dpi(1f)) }
        for ((k, name) in listOf("VolumeDown" to "Vol −", "VolumeMute" to "Mute", "VolumeUp" to "Vol +")) {
            rocker.addView(act.label(name, 14f, Theme.SNOW, bold = true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { it.tick(); Hub.key(k) }
            }, lp(0, act.dpi(50f), 1f))
        }
        row.addView(rocker, lp(0, WRAP, 1f))
        row.addView(IconButton(act, IconButton.KEYBOARD) { keyboard() }, lp(act.dpi(50f), act.dpi(50f)).apply { leftMargin = act.dpi(10f) })
        row.addView(keyButton("Hub") {
            Hub.post("/api/tv/hub", JSONObject()) { _, e -> act.toast(e ?: "Opening Hub") }
        }, lp(act.dpi(76f), act.dpi(50f)).apply { leftMargin = act.dpi(10f) })
        controls.addView(row, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })

        if (wide) {
            val split = act.hbox()
            split.gravity = Gravity.TOP
            val left = act.vbox()
            left.gravity = Gravity.CENTER_HORIZONTAL
            left.addView(modes, lp(WRAP, WRAP))
            left.addView(pad, lp(MATCH, 0, 1f).apply { topMargin = act.dpi(10f) })
            split.addView(left, lp(0, MATCH, 1.15f))
            split.addView(controls, lp(0, WRAP, 1f).apply { leftMargin = act.dpi(18f); topMargin = act.dpi(46f) })
            body.addView(split, lp(MATCH, MATCH))
        } else {
            body.gravity = Gravity.CENTER_HORIZONTAL
            body.addView(modes, lp(WRAP, WRAP))
            body.addView(pad, lp(MATCH, 0, 1f).apply { topMargin = act.dpi(10f) })
            body.addView(controls, lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })
        }
    }

    private fun ViewGroupLp() = android.view.ViewGroup.LayoutParams(MATCH, WRAP)

    private fun keyButton(text: String, onClick: () -> Unit) = act.label(text, 15f, Theme.SNOW, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = act.dpi(52f)
        background = rounded(Theme.DEEP, act.dp(14f), Theme.LINE, act.dpi(1f))
        isClickable = true
        setOnClickListener { it.tick(); onClick() }
    }

    private fun keyboard() {
        val box = act.vbox()
        val p = act.dpi(20f)
        box.setPadding(p, act.dpi(8f), p, 0)
        box.addView(act.label("Goes into whatever text box is open on the TV, like a search screen.", 14f, Theme.MIST))
        val e = act.input("Text to send")
        box.addView(e, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        val chips = Chips(act)
        chips.add("Backspace", "Delete")
        chips.add("Enter", "Enter")
        chips.add("Search", "Search")
        chips.onPick = { k -> Hub.key(k); chips.select(null) }
        box.addView(chips, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Type on the TV")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val t = e.text.toString()
                if (t.isNotEmpty()) Hub.post("/api/tv/text", json("text" to t)) { _, err -> act.toast(err ?: "Sent to the TV") }
            }
            .setNegativeButton("Close", null)
            .show()
        e.requestFocus()
    }
}

// =====================================================================  Channels

class ChannelsScreen(act: MainActivity) : Screen(act) {
    private val grid = Grid(act, act.dpi(150f))
    private val note = act.label("Loading channels…", 14f, Theme.DIM)
    private var apps = ArrayList<JSONObject>()
    private val search = act.input("Find a channel")

    init {
        heading("Channels", "Tap to open on the TV. Star the ones you use most and they lead the row on Hub too.")
        add(search, 8f)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        add(note, 12f)
        add(grid, 8f)
    }

    override fun onShow() = load()

    private fun load() {
        Hub.get("/api/tv/apps") { j, e ->
            if (e != null) {
                note.text = "$e. Check the TV address on the Hub tab, and on the TV set Settings › System › Advanced system settings › Control by mobile apps › Permissive."
                note.visibility = View.VISIBLE
                return@get
            }
            note.visibility = View.GONE
            apps = ArrayList(j!!.getJSONArray("apps").objects())
            render()
        }
    }

    private fun render() {
        grid.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        for (a in apps.filter { it.optString("name").lowercase().contains(q) }) {
            val tile = act.vbox().apply { background = rounded(Theme.DEEP, act.dp(14f), Theme.LINE, act.dpi(1f)); clipToOutline = true }
            val frame = Aspect(act, 4f / 3f)
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Theme.RAISE) }
            frame.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val fav = a.optBoolean("fav")
            val star = act.label(if (fav) "★" else "☆", 18f, if (fav) Theme.accent else Theme.SNOW).apply {
                gravity = Gravity.CENTER
                background = rounded(0x99000000.toInt(), act.dp(99f))
                setOnClickListener {
                    it.tick()
                    val now = !a.optBoolean("fav")
                    a.put("fav", now)
                    Hub.post("/api/tv/favorite", json("id" to a.optString("id"), "fav" to now)) { _, err ->
                        if (err != null) act.toast(err) else { apps.sortByDescending { x -> x.optBoolean("fav") }; render() }
                    }
                }
            }
            frame.addView(star, FrameLayout.LayoutParams(act.dpi(34f), act.dpi(34f), Gravity.END or Gravity.TOP).apply { setMargins(0, act.dpi(6f), act.dpi(6f), 0) })
            tile.addView(frame, lp(MATCH, WRAP))
            tile.addView(act.label(a.optString("name"), 13f).apply { maxLines = 1; setPadding(act.dpi(10f), act.dpi(8f), act.dpi(10f), act.dpi(9f)) })
            tile.isClickable = true
            tile.setOnClickListener {
                it.tick()
                it.press()
                Hub.post("/api/tv/launch", json("id" to a.optString("id"))) { _, err -> act.toast(err ?: "Opening ${a.optString("name")}") }
            }
            Images.load(img, "/api/tv/icon/" + enc(a.optString("id")), act.dpi(220f))
            grid.addView(tile)
        }
    }
}

// =====================================================================  Browser

class BrowserScreen(act: MainActivity) : Screen(act) {
    private val shot = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    private val title = act.label("", 13f, Theme.DIM)
    private var streaming = false
    private var visible = false
    private val client = "tab" + (Math.random() * 99999).toInt()

    init {
        heading("TV browser", "What Hub's browser shows on the TV, live. Tap to click, drag to scroll.")
        add(act.field("Search or type a web address", "Go", url = true, primary = true) { u, _ ->
            if (u.isNotEmpty()) browse(json("type" to "goto", "url" to u))
        }, 8f)
        val frame = Aspect(act, 16f / 9f)
        frame.background = rounded(Color.BLACK, act.dp(14f), Theme.LINE, act.dpi(1f))
        frame.clipToOutline = true
        frame.addView(shot, FrameLayout.LayoutParams(MATCH, MATCH))
        add(frame, 12f)
        touch(frame)
        val bar = Chips(act)
        for ((k, n) in listOf("back" to "Back", "forward" to "Forward", "reload" to "Reload", "home" to "Home", "show" to "Show on TV")) bar.add(k, n)
        bar.onPick = { k ->
            bar.select(null)
            if (k == "show") Hub.post("/api/tv/hub", json("app" to "browser")) { _, e -> act.toast(e ?: "Showing on the TV") }
            else browse(json("type" to k))
        }
        add(bar, 12f)
        add(act.field("Type into the page (tap a text box first)", "Type + Enter") { t, e ->
            if (t.isNotEmpty()) { browse(json("type" to "type", "text" to t, "enter" to true)); e.setText("") }
        }, 12f)
        add(title, 8f)
    }

    private fun browse(a: JSONObject) = Hub.post("/action", a) { j, e -> if (e != null) act.toast(e) else title.text = j?.optString("title") }

    private fun touch(frame: View) {
        var downY = 0f
        var lastY = 0f
        var moved = false
        frame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.y; lastY = e.y; moved = false; v.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.y - downY) > act.dp(8f)) moved = true
                    val dy = e.y - lastY
                    if (moved && Math.abs(dy) > act.dp(18f)) {
                        browse(json("type" to "scroll", "dy" to Math.round(-dy / v.height * 720 * 3), "x" to Math.round(e.x / v.width * 1280), "y" to Math.round(e.y / v.height * 720)))
                        lastY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    v.tick()
                    browse(json("type" to "click", "x" to Math.round(e.x / v.width * 1280), "y" to Math.round(e.y / v.height * 720)))
                }
            }
            true
        }
    }

    override fun onShow() {
        visible = true
        if (streaming) return
        streaming = true
        Hub.pool.execute {
            var first = true
            while (visible) {
                val data = Hub.bytes("/frame.jpg?c=$client&n=${System.nanoTime()}" + if (first) "" else "&wait=1", 15000)
                first = false
                if (data == null) { Thread.sleep(1500); continue }
                val bmp = Images.decode(data, 1280) ?: continue
                Hub.main.post { shot.setImageBitmap(bmp) }
            }
            streaming = false
        }
    }

    override fun onHide() { visible = false }
}

// =====================================================================  Send

class SendScreen(act: MainActivity) : Screen(act) {
    private val progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
    private val crumbs = act.label("", 14f, Theme.MIST)
    private val lib = ListBox(act)
    private var path = ""

    // What to watch: Hub's recommendations, one at a time
    private val recBox = act.vbox()
    private val recItems = ArrayList<JSONObject>()
    private var recIndex = 0
    private var recKind = "any"
    private var recBusy = false
    private val nowBox = act.vbox()
    private val histList = ListBox(act)
    private var histSig = ""
    private val onHub: () -> Unit = { renderLive() }

    init {
        heading("Watch and send", "Get something to watch, ask the TV a question, draw it a note, or send photos and links. You can also use Share › Hub Remote from any app.")

        add(nowBox, 0f)
        section("What to watch")
        val kinds = Chips(act)
        kinds.add("any", "Anything")
        kinds.add("tv", "Shows")
        kinds.add("movie", "Movies")
        kinds.select("any")
        kinds.onPick = { k -> recKind = k; recItems.clear(); recIndex = 0; loadRec(true) }
        add(kinds, 0f)
        add(recBox, 12f)

        section("Keep watching", "What you opened from Hub. Tap a title for details, Open to start it again.")
        add(histList, 0f)

        section("Ask the TV")
        val asked = act.label("", 13f, Theme.DIM)
        val answer = act.label("", 18f, Theme.SNOW, serif = true)
        val answerWatch = act.button("Watch on the TV", primary = true) {}.apply { visibility = View.GONE }
        val answerBox = act.vbox().apply {
            background = rounded(Theme.DEEP, act.dp(14f), Theme.LINE, act.dpi(1f))
            val p = act.dpi(16f)
            setPadding(p, p, p, p)
            addView(asked)
            addView(answer, lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
            addView(answerWatch, lp(WRAP, WRAP).apply { topMargin = act.dpi(12f) })
            visibility = View.GONE
        }
        add(act.field("Recommend a funny movie. Will it rain tomorrow?", "Ask", primary = true) { q, e ->
            if (q.isEmpty()) return@field
            answerBox.visibility = View.VISIBLE
            answerWatch.visibility = View.GONE
            asked.text = q
            answer.text = "Thinking it over"
            Hub.post("/api/ask", json("q" to q)) { j, err ->
                answer.text = err ?: j?.optString("answer")
                if (err == null) e.setText("")
                val rec = j?.optJSONObject("rec")
                if (rec != null) {
                    val app = rec.optString("appName")
                    answerWatch.text = if (app.isNotEmpty()) "Watch on $app" else "Find it on the TV"
                    answerWatch.setOnClickListener { v -> v.tick(); v.press()
                        Hub.post("/api/recommend/watch", rec) { _, e2 -> act.toast(e2 ?: "Opening ${rec.optString("title")} on the TV") } }
                    answerWatch.visibility = View.VISIBLE
                }
            }
        }, 0f)
        add(answerBox, 10f)

        section("Draw a note", "It shows up in the corner of the TV's home screen.")
        val draw = DrawView(act).apply { color = 0xFFECEDF0.toInt() }
        add(draw, 0f)
        val pens = Chips(act)
        val penColors = linkedMapOf("Cream" to 0xFFECEDF0.toInt(), "Butter" to 0xFFE8E39A.toInt(), "Ember" to 0xFF9AB0E5.toInt(),
            "Rose" to 0xFFE8A3C8.toInt(), "Sky" to 0xFF9CC3E0.toInt(), "Sage" to 0xFFA9CF9C.toInt())
        for (k in penColors.keys) pens.add(k, k)
        pens.select("Cream")
        pens.onPick = { k -> draw.color = penColors[k]!! }
        add(pens, 10f)
        val sizes = Chips(act)
        sizes.add("6", "Thin")
        sizes.add("12", "Medium")
        sizes.add("22", "Thick")
        sizes.select("12")
        sizes.onPick = { k -> draw.brush = k.toFloat() }
        add(sizes, 8f)
        val drawBar = act.hbox()
        drawBar.addView(act.button("Undo") { draw.undo() })
        drawBar.addView(act.button("Clear") { draw.clear() }, lp(WRAP, WRAP).apply { leftMargin = act.dpi(8f) })
        drawBar.addView(View(act), lp(0, 1, 1f))
        drawBar.addView(act.button("Send to TV", primary = true) {
            if (draw.isEmpty) { act.toast("Draw something first"); return@button }
            Hub.uploadBytes("/api/notes/draw", draw.png(), "note.png") { _, err ->
                if (err != null) act.toast(err) else { act.toast("Sent to the TV"); draw.clear() }
            }
        })
        add(drawBar, 10f)

        section("Photos and videos")
        val pick = act.vbox().apply {
            background = rounded(Theme.DEEP, act.dp(20f), (Theme.accent and 0x00FFFFFF) or 0x90000000.toInt(), act.dpi(1.5f))
            gravity = Gravity.CENTER
            val p = act.dpi(24f)
            setPadding(p, p, p, p)
            addView(act.label("Choose photos or videos", 18f, Theme.SNOW, bold = true).apply { gravity = Gravity.CENTER })
            addView(act.label("They show on the TV as soon as they upload", 13f, Theme.DIM).apply { gravity = Gravity.CENTER })
            isClickable = true
            setOnClickListener { it.tick(); act.pickMedia(arrayOf("image/*", "video/*", "audio/*"), true) { sendFiles(it) } }
        }
        add(pick, 0f)
        add(act.label("Send only photos and videos you have the right to show. They stay on your server in the Sent to TV folder, where you can delete them.", 13f, Theme.DIM), 8f)
        add(progress, 8f)
        section("Send a link")
        add(act.field("Paste a link", "Show on TV", url = true, primary = true) { u, e -> if (u.isNotEmpty()) { sendLink(u); e.setText("") } }, 0f)
        section("From your server", "Your CasaOS media folders. Tap something to play it on the TV.")
        add(crumbs, 0f)
        add(lib, 8f)
    }

    override fun onShow() {
        load(path)
        if (recItems.isEmpty()) loadRec(false)
        Hub.onChange(onHub)
        renderLive()
    }

    override fun onHide() { Hub.removeChange(onHub) }

    /** What's playing on the TV right now, and Keep watching, from the status Hub polls. */
    private fun renderLive() {
        val s = Hub.status ?: return
        nowBox.removeAllViews()
        val np = s.optJSONObject("nowPlaying")
        if (np != null && np.optString("appName").isNotEmpty()) {
            val card = act.hbox().apply {
                background = rounded(Theme.DEEP, act.dp(18f), Theme.LINE, act.dpi(1f))
                val p = act.dpi(14f); setPadding(p, p, p, p)
            }
            val txtCol = act.vbox()
            val state = when (np.optString("state")) { "play" -> "Playing"; "pause" -> "Paused"; else -> "Open" }
            txtCol.addView(act.label("Now on the TV  ·  $state", 12f, Theme.DIM))
            txtCol.addView(act.label(np.optString("appName"), 20f, Theme.SNOW, serif = true))
            val pos = np.optLong("position", 0); val dur = np.optLong("duration", 0)
            fun mmss(ms: Long): String { val t = ms / 1000; return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) else String.format(Locale.US, "%d:%02d", t / 60, t % 60) }
            if (dur > 0) txtCol.addView(act.label(mmss(pos) + " of " + mmss(dur), 13f, Theme.MIST).apply { fontFeatureSettings = "tnum" })
            card.addView(txtCol, lp(0, WRAP, 1f))
            for ((k, label) in listOf("Rev" to "◀◀", "Play" to "▶❚❚", "Fwd" to "▶▶")) card.addView(act.button(label) { Hub.key(k) }, lp(WRAP, WRAP).apply { leftMargin = act.dpi(6f) })
            nowBox.addView(card, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        }
        val hist = s.optJSONArray("history") ?: JSONArray()
        if (hist.toString() == histSig) return
        histSig = hist.toString()
        histList.clear()
        val items = hist.objects()
        if (items.isEmpty()) histList.row("Nothing yet. Shows and movies you open from Hub land here.")
        for (it in items.take(12)) {
            val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Theme.RAISE, act.dp(8f)); clipToOutline = true }
            val art = it.optString("backdrop").ifEmpty { it.optString("poster") }
            if (art.isNotEmpty()) Images.load(thumb, "/api/thumb?u=" + enc(art), 200)
            val sub = listOf(it.optString("appName"), agoText(it.optLong("at"))).filter { x -> x.isNotEmpty() }.joinToString("  ·  ")
            lateinit var row: LinearLayout
            row = histList.row(it.optString("title"), sub, thumb, act.button("Open", primary = true) { Titles.play(act, it) }) { Titles.show(act, it) }
            (thumb.layoutParams as LinearLayout.LayoutParams).apply { width = act.dpi(80f); height = act.dpi(45f) }
            row.requestLayout()
        }
    }

    private fun agoText(t: Long): String {
        if (t <= 0) return ""
        val s = System.currentTimeMillis() / 1000 - t
        return when { s < 3600 -> "${maxOf(1, s / 60)} min ago"; s < 86400 -> "${s / 3600} h ago"; s < 172800 -> "yesterday"; else -> "${s / 86400} days ago" }
    }

    override fun wantsBack() = path.isNotEmpty()

    // ---- What to watch

    private fun recSkeleton() {
        recBox.removeAllViews()
        val row = act.hbox().apply { gravity = Gravity.TOP }
        row.addView(act.skeleton(act.dpi(96f), act.dpi(144f), 12f))
        val col = act.vbox()
        for ((w, h) in listOf(90f to 12f, 200f to 24f, 130f to 12f, 220f to 12f, 180f to 12f)) {
            col.addView(act.skeleton(act.dpi(w), act.dpi(h), 6f), lp(act.dpi(w), act.dpi(h)).apply { bottomMargin = act.dpi(10f) })
        }
        row.addView(col, lp(0, WRAP, 1f).apply { leftMargin = act.dpi(16f) })
        recBox.addView(card(row), lp(MATCH, WRAP))
    }

    private fun card(inner: View) = act.vbox().apply {
        background = rounded(Theme.DEEP, act.dp(18f), Theme.LINE, act.dpi(1f))
        val p = act.dpi(14f)
        setPadding(p, p, p, p)
        addView(inner, lp(MATCH, WRAP))
    }

    private fun loadRec(fresh: Boolean) {
        if (recBusy) return
        recBusy = true
        if (recItems.isEmpty() || fresh) recSkeleton()
        Hub.get("/api/recommend?n=8&kind=$recKind" + if (fresh) "&fresh=1" else "") { j, e ->
            recBusy = false
            if (e != null) {
                if (recItems.isEmpty()) { recBox.removeAllViews(); recBox.addView(act.label("Couldn't get picks: $e", 14f, Theme.DIM)) }
                return@get
            }
            if (fresh) { recItems.clear(); recIndex = 0 }
            for (it in j!!.getJSONArray("items").objects()) if (recItems.none { r -> r.optString("key") == it.optString("key") }) recItems.add(it)
            renderRec(true)
        }
    }

    private fun renderRec(animate: Boolean) {
        recBox.removeAllViews()
        val it = recItems.getOrNull(recIndex)
        if (it == null) {
            recBox.addView(act.label("Nothing new to recommend. Save a few shows on the Hub tab and picks follow them.", 14f, Theme.DIM))
            return
        }
        val row = act.hbox().apply { gravity = Gravity.TOP }
        val poster = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Theme.RAISE, act.dp(12f))
            clipToOutline = true
            contentDescription = it.optString("title") + " poster"
        }
        if (it.optString("poster").isNotEmpty()) Images.load(poster, "/api/thumb?u=" + enc(it.optString("poster")), 300)
        row.addView(poster, lp(act.dpi(96f), act.dpi(144f)))
        val col = act.vbox()
        col.addView(act.label("Hub recommends", 12f, Theme.DIM))
        col.addView(act.label(it.optString("title"), 22f, Theme.SNOW, serif = true).apply { setOnClickListener { _ -> Titles.show(act, it) } }, lp(MATCH, WRAP).apply { topMargin = act.dpi(4f) })
        val genres = it.optJSONArray("genres")?.let { g -> (0 until minOf(2, g.length())).joinToString(", ") { i -> g.getString(i) } } ?: ""
        val meta = listOf(it.optString("year"), if (it.optString("kind") == "movie") "Movie" else "Show", genres).filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        col.addView(act.label(meta, 13f, Theme.MIST), lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
        if (it.optString("reason").isNotEmpty()) col.addView(act.label(it.optString("reason"), 14f, Theme.accent, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
        if (it.optString("summary").isNotEmpty()) col.addView(act.label(it.optString("summary"), 14f, Theme.MIST).apply { maxLines = 4 }, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        row.addView(col, lp(0, WRAP, 1f).apply { leftMargin = act.dpi(16f) })

        val acts = Flow(act)
        val app = it.optString("appName")
        acts.addView(act.button(if (app.isNotEmpty()) "Watch on $app" else "Find it on the TV", primary = true) { Titles.play(act, it) })
        acts.addView(act.button("Details") { Titles.show(act, it) })
        acts.addView(act.button("Another pick") { nextRec() })
        lateinit var save: TextView
        save = act.button("Save") {
            save.isEnabled = false
            save.text = "Saved"
            Hub.post("/api/shows/add", it) { _, e ->
                if (e != null) { save.isEnabled = true; save.text = "Save"; act.toast(e) } else act.toast("Saved to My Shows. Picks will lean this way.")
            }
        }
        acts.addView(save)
        acts.addView(act.button("Not for me", danger = true) { passRec(it) })

        val box = act.vbox()
        box.addView(row, lp(MATCH, WRAP))
        box.addView(acts, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        val c = card(box)
        recBox.addView(c, lp(MATCH, WRAP))
        Hub.post("/api/recommend/seen", json("key" to it.optString("key"))) { _, _ -> }
        if (animate) {
            Spring.set(c, "alpha", 0f); c.alpha = 0f
            Spring.set(c, "x", act.dp(24f)); c.translationX = act.dp(24f)
            c.springAlpha(1f); c.springX(0f)
        }
        if (recItems.size - recIndex <= 2) loadRec(false)
    }

    private fun nextRec() {
        if (recIndex < recItems.size - 1) { recIndex++; renderRec(true) } else { recIndex = recItems.size; loadRec(false) }
    }

    private fun passRec(it: JSONObject) {
        val at = recIndex
        act.undoable("Hidden ${it.optString("title")}",
            apply = { recItems.removeAt(at); if (recIndex >= recItems.size) recIndex = maxOf(0, recItems.size - 1); renderRec(true) },
            undo = { recItems.add(minOf(at, recItems.size), it); recIndex = at; renderRec(true) },
            commit = { Hub.post("/api/recommend/pass", json("key" to it.optString("key"))) { _, _ -> } })
    }

    override fun onBack(): Boolean {
        if (path.isEmpty()) return false
        load(path.substringBeforeLast('/', ""))
        return true
    }

    fun sendLink(url: String) {
        Hub.post("/api/send/link", json("url" to url)) { j, e ->
            act.toast(e ?: when (j?.optString("opened")) {
                "youtube" -> "Playing in YouTube"
                "crunchyroll" -> "Opening Crunchyroll"
                "player" -> "Playing on the TV"
                else -> "Opened in Hub's browser"
            })
        }
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uri = uris[0]
        val name = act.nameOf(uri)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        act.toast("Sending $name…")
        Hub.upload("/api/send/file", uri, name, { progress.progress = it }) { _, e ->
            progress.visibility = View.GONE
            act.toast(e ?: "On the TV: $name")
            if (e == null) sendFiles(uris.drop(1))
        }
    }

    private fun load(p: String) {
        path = p
        crumbs.text = if (p.isEmpty()) "Server" else "Server › " + p.replace("/", " › ")
        Hub.get("/api/library?path=" + enc(p)) { j, e ->
            lib.clear()
            if (e != null) { lib.row(e); return@get }
            val dirs = j!!.getJSONArray("dirs").objects()
            val files = j.getJSONArray("files").objects()
            if (p.isNotEmpty()) lib.row("‹ Back", null) { load(p.substringBeforeLast('/', "")) }
            for (d in dirs) lib.row(d.optString("name"), "Folder") { load(d.optString("path")) }
            for (f in files) {
                val kind = f.optString("kind")
                val thumb = if (kind == "photo") ImageView(act).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Theme.RAISE, act.dp(8f))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(act.dpi(44f), act.dpi(44f))
                    Images.load(this, "/api/img?w=120&h=120&fit=cover&src=" + enc(f.optString("path")), 120)
                } else null
                val kindName = mapOf("video" to "Video", "audio" to "Music", "photo" to "Photo")[kind] ?: kind
                val mb = String.format(Locale.US, "%.1f MB", f.optLong("size") / 1048576.0)
                val row = lib.row(f.optString("name"), "$kindName, $mb", thumb) {
                    Hub.post("/api/tv/play", json("path" to f.optString("path"))) { _, err -> act.toast(err ?: "Playing on the TV") }
                }
                thumb?.let { (it.layoutParams as LinearLayout.LayoutParams).apply { width = act.dpi(44f); height = act.dpi(44f) } }
                row.requestLayout()
            }
            if (dirs.isEmpty() && files.isEmpty()) lib.row(if (p.isEmpty()) "No media folders found. Add /DATA as a volume in the CasaOS app (see the setup notes)." else "Nothing playable in this folder.")
        }
    }
}

// =====================================================================  Hub (customize)

class HubScreen(act: MainActivity) : Screen(act) {
    private val walls = act.hbox()
    private val swatches = act.hbox()
    private val rows = ListBox(act)
    private val widgets = ListBox(act)
    private val prefs = ListBox(act)
    private val placeInfo = act.label("", 13f, Theme.DIM)
    private val ssMin = Chips(act)
    private val ssSrc = Chips(act)
    private val ytList = ListBox(act)
    private val aniResults = ListBox(act)
    private val aniList = ListBox(act)
    private val evList = ListBox(act)
    private val notes = act.vbox()
    private val server = ListBox(act)
    private val showResults = ListBox(act)
    private val passedLine = act.label("", 13f, Theme.DIM)
    private val about = ListBox(act)
    private val looks = Chips(act)
    private val modes = ListBox(act)
    private val people = ListBox(act)
    private val showList = ListBox(act)
    private val quoteList = ListBox(act)
    private val aiBox = act.vbox()
    private var tvApps: List<JSONObject>? = null
    private var loaded = false

    init {
        heading("Your Hub", "Changes show up on the TV within a second or two.")
        section("Wallpaper")
        add(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(walls) }, 0f)
        section("Home screen look")
        looks.add("hub", "Hub: picks and rows")
        looks.add("console", "PlayStation (PS4): tiles and waves")
        looks.onPick = { k -> saveHub(json("theme" to k), if (k == "console") "The TV switches to the PlayStation look" else "The TV switches to the Hub look") }
        add(looks, 0f)
        add(modes, 10f)
        section("People", "Each profile keeps its own shows, Keep watching, picks and look.")
        add(people, 0f)
        add(act.field("Add a person", "Add") { n, e ->
            if (n.isEmpty()) return@field
            Hub.post("/api/profiles/add", json("name" to n)) { _, err -> if (err != null) act.toast(err) else { e.setText(""); act.toast("Added $n"); reload() } }
        }, 10f)
        section("Accent color")
        add(swatches, 0f)
        section("Top of the home screen")
        add(widgets, 0f)
        section("Rows under the dock", "Press Down on the TV to see these. Use the arrows to reorder.")
        add(rows, 0f)
        section("Clock and weather")
        add(prefs, 0f)
        add(act.field("Weather location, e.g. Detroit, Michigan", "Save") { t, _ ->
            Hub.post("/api/weather/location", json("location" to t)) { j, e ->
                if (e != null) act.toast(e) else {
                    val pl = j?.optJSONObject("place")
                    placeInfo.text = if (pl != null) "Showing weather for ${pl.optString("name")}, ${pl.optString("region")}" else "Weather off"
                    act.toast("Saved")
                }
            }
        }, 10f)
        add(placeInfo, 6f)
        section("Screensaver", "Photos slowly pan across the screen with the clock and weather after Hub sits idle.")
        for ((k, n) in listOf("0" to "Never", "2" to "2 min", "5" to "5 min", "10" to "10 min", "20" to "20 min", "30" to "30 min")) ssMin.add(k, n)
        for ((k, n) in listOf("gallery" to "Gallery photos", "wallpapers" to "My wallpapers", "bing" to "Bing photo")) ssSrc.add(k, n)
        ssMin.onPick = { k -> saveHub(json("screensaver" to json("minutes" to k.toInt(), "source" to (Hub.hub().optJSONObject("screensaver")?.optString("source") ?: "gallery")))) }
        ssSrc.onPick = { k -> saveHub(json("screensaver" to json("minutes" to (Hub.hub().optJSONObject("screensaver")?.optInt("minutes", 5) ?: 5), "source" to k))) }
        add(ssMin, 0f)
        add(ssSrc, 10f)

        section("YouTube", "Latest uploads from these channels show on Hub. Picking one plays it in the YouTube app.")
        add(act.field("@channel or channel link", "Add") { t, e ->
            if (t.isEmpty()) return@field
            act.toast("Looking up $t…")
            Hub.post("/api/youtube/add", json("channel" to t)) { j, err ->
                if (err != null) act.toast(err) else { e.setText(""); act.toast("Added ${j!!.getJSONObject("channel").optString("name")}"); renderYT(j.getJSONArray("channels")) }
            }
        }, 0f)
        add(ytList, 10f)

        section("Anime", "Follow shows to see when the next episode airs. With none followed, Hub shows today's popular episodes. Picking one opens Crunchyroll.")
        add(act.field("Search a show", "Search") { q, _ ->
            if (q.isEmpty()) return@field
            Hub.get("/api/anime/search?q=" + enc(q)) { j, err ->
                aniResults.clear()
                if (err != null) { act.toast(err); return@get }
                aniResults.visibility = View.VISIBLE
                for (a in j!!.getJSONArray("results").objects()) {
                    animeRow(aniResults, a, act.button("Follow", primary = true) {
                        Hub.post("/api/anime/add", json("id" to a.optInt("id"), "title" to a.optString("title"))) { k, e2 ->
                            if (e2 != null) act.toast(e2) else { aniResults.visibility = View.GONE; act.toast("Following ${a.optString("title")}"); renderAnime(k!!.getJSONArray("anime")) }
                        }
                    })
                }
            }
        }, 0f)
        aniResults.visibility = View.GONE
        add(aniResults, 10f)
        add(aniList, 10f)

        section("Calendar", "Paste your calendar's secret iCal link (Google Calendar: Settings › your calendar › Secret address in iCal format).")
        add(act.field("https://calendar.google.com/…/basic.ics", "Save", url = true) { u, _ ->
            Hub.post("/api/calendar", json("url" to u)) { _, e -> act.toast(e ?: "Calendar saved") }
        }, 0f)
        add(act.button("Add a quick event") { quickEvent() }, 10f)
        add(evList, 10f)

        section("My Shows", "Shows and movies you like. They fill the My Shows row, and Hub's recommendations follow them.")
        add(act.field("Search a show or movie", "Search") { q, _ -> if (q.isNotEmpty()) searchShows(q) }, 0f)
        showResults.visibility = View.GONE
        add(showResults, 10f)
        add(showList, 12f)
        add(passedLine, 8f)

        section("Quotes", "One shows over the dock and changes every 10 minutes. With none, Hub shows a greeting.")
        val qRow = act.hbox()
        val qText = act.input("Quote")
        val qBy = act.input("From (show)")
        qRow.addView(qText, lp(0, WRAP, 2f))
        qRow.addView(qBy, lp(0, WRAP, 1f).apply { leftMargin = act.dpi(8f) })
        qRow.addView(act.button("Add") {
            val t = qText.text.toString().trim()
            if (t.isEmpty()) return@button
            Hub.post("/api/quotes/add", json("text" to t, "by" to qBy.text.toString().trim())) { j, err ->
                if (err != null) act.toast(err) else { qText.setText(""); qBy.setText(""); renderQuotes(j!!.getJSONArray("quotes")); act.toast("Added") }
            }
        }, lp(WRAP, WRAP).apply { leftMargin = act.dpi(8f) })
        add(qRow, 0f)
        add(quoteList, 10f)

        section("Text notes", "Notes show up on the TV's home screen. To draw one, use Send.")
        add(act.field("Leave a note on the TV", "Post", primary = true) { t, e ->
            if (t.isEmpty()) return@field
            val colors = listOf("#D9E2F2", "#D6CCF0", "#CBE3CF", "#CFE6E0", "#C9D6EA")
            Hub.post("/api/notes/add", json("text" to t, "color" to colors.random())) { _, err ->
                if (err != null) act.toast(err) else { e.setText(""); act.toast("Posted to the TV"); reload() }
            }
        }, 0f)
        add(notes, 10f)

        section("Ask the TV and AI picks", "Weather, your calendar, apps, sleep timers and recommendations work without an AI. Connect one for any other question.")
        add(aiBox, 0f)

        section("Server")
        add(server, 0f)

        section("About Hub")
        add(about, 0f)
    }

    // ---- My Shows

    private fun thumb(u: String) = "/api/thumb?u=" + enc(u)

    private fun posterView(u: String): ImageView = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(Theme.RAISE, act.dp(8f))
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(act.dpi(46f), act.dpi(68f))
        if (u.isNotEmpty()) Images.load(this, thumb(u), 140)
    }

    private fun fixPoster(row: View, v: ImageView) {
        (v.layoutParams as LinearLayout.LayoutParams).apply { width = act.dpi(46f); height = act.dpi(68f) }
        row.requestLayout()
    }

    private fun searchShows(q: String) {
        showResults.clear()
        showResults.visibility = View.VISIBLE
        showResults.row("Searching…")
        Hub.get("/api/shows/search?q=" + enc(q)) { j, err ->
            showResults.clear()
            if (err != null) { showResults.row(err); return@get }
            val res = j!!.getJSONArray("results").objects()
            if (res.isEmpty()) showResults.row("Nothing found. Try another spelling.")
            for (r in res) {
                val pv = posterView(r.optString("poster"))
                val sub = listOf(r.optString("year"), if (r.optString("kind") == "movie") "Movie" else "TV show", r.optString("network")).filter { it.isNotEmpty() }.joinToString(" ")
                val row = showResults.row(r.optString("title"), sub, pv, act.button("Add", primary = true) {
                    Hub.post("/api/shows/add", r) { k, e2 ->
                        if (e2 != null) act.toast(e2) else {
                            val app = k!!.getJSONObject("show").optString("appName")
                            act.toast("Added ${r.optString("title")}" + if (app.isNotEmpty()) " ($app)" else "")
                            showResults.visibility = View.GONE
                            reload()
                        }
                    }
                })
                fixPoster(row, pv)
            }
        }
    }

    private fun renderShows(shows: List<JSONObject>) {
        showList.clear()
        if (shows.isEmpty()) showList.row("No shows yet. Search above, or save picks from What to watch.")
        for (sh in shows) {
            val pv = posterView(sh.optString("poster"))
            val sub = listOf(listOf(sh.optString("year"), if (sh.optString("kind") == "movie") "Movie" else "TV show").filter { it.isNotEmpty() }.joinToString(" "),
                sh.optString("appName").ifEmpty { "Any app" }).joinToString(", ")
            lateinit var row: LinearLayout
            row = showList.row(sh.optString("title"), sub, pv, act.label("Edit", 14f, Theme.accent, bold = true).apply {
                minHeight = act.dpi(44f); minWidth = act.dpi(44f); gravity = Gravity.CENTER
                setOnClickListener { v -> v.tick(); editShow(sh, row) }
            }) { Titles.show(act, sh) }
            fixPoster(row, pv)
        }
    }

    private fun renderPassed(n: Int) {
        passedLine.visibility = if (n > 0) View.VISIBLE else View.GONE
        passedLine.text = "$n ${if (n == 1) "title" else "titles"} marked Not for me. Tap to show them in picks again."
        passedLine.minHeight = act.dpi(44f)
        passedLine.setOnClickListener { v -> v.tick()
            Hub.post("/api/recommend/reset", JSONObject()) { _, e -> if (e != null) act.toast(e) else { renderPassed(0); act.toast("Hidden titles can come back in picks") } }
        }
    }

    private fun editShow(sh: JSONObject, row: View) {
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sh.optString("title"))
            .setMessage("Open in: " + sh.optString("appName").ifEmpty { "any app (Roku search)" })
            .setPositiveButton("Choose app") { _, _ -> chooseApp(sh) }
            .setNeutralButton("Remove") { _, _ ->
                removable(row, "Removed ${sh.optString("title")}") { Hub.post("/api/shows/delete", json("id" to sh.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
            }
            .setNegativeButton("Close", null).show()
    }

    /** Hides a row now and deletes it on the server once the Undo toast goes away. */
    private fun removable(row: View, text: String, commit: () -> Unit) =
        act.undoable(text, apply = { row.visibility = View.GONE }, undo = { row.visibility = View.VISIBLE }, commit = commit)

    private fun chooseApp(sh: JSONObject) {
        val go = { apps: List<JSONObject> ->
            val names = listOf("Any app (Roku search)") + apps.map { it.optString("name") }
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Open ${sh.optString("title")} in")
                .setItems(names.toTypedArray()) { _, i ->
                    val a = if (i == 0) null else apps[i - 1]
                    Hub.post("/api/shows/update", json("id" to sh.optString("id"), "app" to (a?.optString("id") ?: ""), "appName" to (a?.optString("name") ?: ""))) { _, e ->
                        act.toast(e ?: "Saved"); reload()
                    }
                }.show()
        }
        tvApps?.let { go(it); return }
        Hub.get("/api/tv/apps") { j, e ->
            if (e != null) { act.toast(e); return@get }
            tvApps = j!!.getJSONArray("apps").objects()
            go(tvApps!!)
        }
    }

    private fun renderQuotes(arr: JSONArray) {
        quoteList.clear()
        val list = arr.objects()
        if (list.isEmpty()) quoteList.row("No quotes yet.")
        for (q in list) {
            lateinit var row: LinearLayout
            row = quoteList.row("\u201C${q.optString("text")}\u201D", q.optString("by").ifEmpty { null }, null, act.button("Delete", danger = true) {
                removable(row, "Quote deleted") { Hub.post("/api/quotes/delete", json("id" to q.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
            })
        }
    }

    private fun renderAI(ai: JSONObject) {
        aiBox.removeAllViews()
        val prov = Chips(act)
        prov.add("ollama", "Ollama on my PC")
        prov.add("claude", "Claude (API key)")
        prov.add("", "Built-in only")
        var provider = ai.optString("provider")
        prov.select(provider)
        val key = act.input(if (ai.optBoolean("hasKey")) "Key saved on the server" else "Claude API key (sk-ant-…)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val url = act.input("Ollama address, e.g. 10.0.0.142", ai.optString("url"))
        val model = act.input("Model (blank for the default)", ai.optString("model"))
        val share = android.widget.CheckBox(act).apply {
            isChecked = ai.optBoolean("share")
            text = "Share weather, calendar and shows with the AI. Turns on better answers and AI picks. With Claude, this goes to Anthropic; with Ollama it stays on your PC."
            setTextColor(Theme.MIST)
            textSize = 14f
            typeface = Fonts.regular
            buttonTintList = android.content.res.ColorStateList.valueOf(Theme.accent)
            minHeight = act.dpi(44f)
        }
        fun vis() {
            key.visibility = if (provider == "claude") View.VISIBLE else View.GONE
            url.visibility = if (provider == "ollama") View.VISIBLE else View.GONE
            model.visibility = if (provider.isEmpty()) View.GONE else View.VISIBLE
            share.visibility = if (provider.isEmpty()) View.GONE else View.VISIBLE
        }
        prov.onPick = { k -> provider = k; vis() }
        aiBox.addView(prov, lp(MATCH, WRAP))
        aiBox.addView(key, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        aiBox.addView(url, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        aiBox.addView(model, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        aiBox.addView(share, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        aiBox.addView(act.label("Your key stays on the Hub server and never comes back to this tablet.", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
        aiBox.addView(act.button("Save AI settings", primary = true) {
            val b = json("provider" to provider, "url" to url.text.toString().trim(), "model" to model.text.toString().trim(), "share" to share.isChecked)
            if (key.text.toString().isNotBlank()) b.put("key", key.text.toString().trim())
            Hub.post("/api/ai", b) { _, e -> act.toast(e ?: "Saved. Try asking something on Watch and send."); if (e == null) reload() }
        }, lp(WRAP, WRAP).apply { topMargin = act.dpi(12f) })
        vis()
    }

    // ---- look, morning and night, people

    private fun hourName(h: Int) = "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12 || h == 24) "AM" else "PM"}"

    private fun renderLook(hub: JSONObject, s: JSONObject) {
        looks.select(hub.optString("theme", "hub").ifEmpty { "hub" })
        val md = hub.optJSONObject("modes") ?: JSONObject()
        val morning = md.optInt("morning", 5)
        val night = md.optInt("night", 21)
        fun save(auto: Boolean = md.optBoolean("auto", true), mo: Int = morning, ni: Int = night) =
            saveHub(json("modes" to json("auto" to auto, "morning" to mo, "night" to ni)))
        modes.clear()
        modes.row("Morning and night looks", "Mornings lead with weather and your day. Nights dim the TV and add a sleep timer.", null,
            Toggle(act).apply { on = md.optBoolean("auto", true); onChange = { v -> save(auto = v) } })
        modes.row("Morning starts", hourName(morning), null, act.button("Change") {
            val hours = (3..12).toList()
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Morning starts at")
                .setItems(hours.map { hourName(it) }.toTypedArray()) { _, i -> save(mo = hours[i]) }.show()
        })
        modes.row("Night starts", hourName(night), null, act.button("Change") {
            val hours = (17..24).toList()
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Night starts at")
                .setItems(hours.map { hourName(it) }.toTypedArray()) { _, i -> save(ni = hours[i]) }.show()
        })
        people.clear()
        for (p in (s.optJSONArray("profiles") ?: JSONArray()).objects()) {
            val dot = android.view.View(act).apply { background = circle(parseColor(p.optString("color"))) }
            val current = p.optBoolean("current")
            lateinit var row: LinearLayout
            val right = if (current) act.label("Watching", 13f, Theme.accent, bold = true) else act.button("Remove", danger = true) {
                removable(row, "Removed ${p.optString("name")}") { Hub.post("/api/profiles/delete", json("id" to p.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
            }
            row = people.row(p.optString("name"), if (current) null else "Tap to switch", dot, right) {
                if (!current) Hub.post("/api/profiles/switch", json("id" to p.optString("id"))) { _, e -> act.toast(e ?: "Switched to ${p.optString("name")}"); Hub.refresh(); reload() }
            }
            (dot.layoutParams as LinearLayout.LayoutParams).apply { width = act.dpi(28f); height = act.dpi(28f) }
        }
    }

    // ---- About: privacy, updates, removing Hub

    private fun renderAbout(version: String) {
        about.clear()
        about.row("Privacy and terms", "What Hub keeps and who it talks to", null, null) { openLegal() }
        val ver = act.label("Hub server $version", 13f, Theme.DIM)
        lateinit var upd: TextView
        upd = act.button("Check") {
            upd.isEnabled = false
            ver.text = "Checking GitHub"
            Hub.get("/api/update/check") { j, e ->
                upd.isEnabled = true
                when {
                    e != null -> ver.text = e
                    j!!.has("error") -> ver.text = j.optString("error")
                    j.optBoolean("newer") -> {
                        ver.text = "Hub server ${j.optString("current")}. Version ${j.optString("latest")} is ready."
                        upd.text = "Update"
                        upd.setOnClickListener { v -> v.tick(); upd.isEnabled = false; ver.text = "Updating and restarting"
                            Hub.post("/api/update/apply", JSONObject()) { _, e2 -> if (e2 != null) { upd.isEnabled = true; act.toast(e2) } else upd.postDelayed({ reload() }, 6000) } }
                    }
                    else -> ver.text = "Hub server ${j.optString("current")} is the latest."
                }
            }
        }
        val updRow = about.row("Updates", null, null, upd)
        ((updRow.getChildAt(updRow.childCount - 2) as? LinearLayout))?.addView(ver)
        about.row("Remove Hub", "Deletes Hub from the server and, with its developer password, from the TV", null,
            act.button("Remove", danger = true) { removeHub() })
        about.row("Uninstall Hub Remote", "Removes this app and what it saved on this tablet", null,
            act.button("Uninstall", danger = true) { act.uninstallSelf() })
    }

    private fun openLegal() {
        Hub.get("/api/legal") { j, e ->
            if (e != null) { act.toast(e); return@get }
            val col = act.vbox()
            val p = act.dpi(22f)
            col.setPadding(p, act.dpi(8f), p, p)
            fun part(title: String, rows: JSONArray) {
                col.addView(act.label(title, 22f, Theme.SNOW, serif = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONArray(i)
                    col.addView(act.label(r.getString(0), 15f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })
                    col.addView(act.label(r.getString(1), 14f, Theme.MIST), lp(MATCH, WRAP).apply { topMargin = act.dpi(4f) })
                }
            }
            part("Privacy", j!!.getJSONArray("privacy"))
            part("Terms", j.getJSONArray("terms"))
            col.addView(act.label("Updated ${j.optString("updated")}.", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(ScrollView(act).apply { addView(col) }).setPositiveButton("Close", null).show()
        }
    }

    private fun removeHub() {
        val col = act.vbox()
        val p = act.dpi(22f)
        col.setPadding(p, act.dpi(8f), p, 0)
        col.addView(act.label("This deletes Hub's settings, notes, wallpapers and sent files from your server. Your media folders stay. To take Hub off the TV too, type the TV's developer password (the one you set when you turned on developer mode).", 14f, Theme.MIST))
        val pass = act.input("TV developer password (optional)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = act.input("Type REMOVE")
        col.addView(pass, lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })
        col.addView(confirm, lp(MATCH, WRAP).apply { topMargin = act.dpi(10f) })
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Remove Hub").setView(col)
            .setPositiveButton("Remove Hub") { _, _ ->
                if (confirm.text.toString().trim() != "REMOVE") { act.toast("Type REMOVE to confirm"); return@setPositiveButton }
                Hub.post("/api/uninstall", json("confirm" to "REMOVE", "password" to pass.text.toString())) { j, e ->
                    if (e != null) { act.toast(e); return@post }
                    val tv = j!!.optString("tv")
                    val msg = (if (tv == "removed") "Hub is off the TV. " else if (tv.startsWith("failed")) "The TV channel stayed (${tv.removePrefix("failed: ")}). " else "") +
                        "The server deleted Hub's files. Last step on CasaOS: open the Hub Server app's menu and choose Uninstall."
                    AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Hub removed").setMessage(msg)
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onShow() = reload()

    private fun saveHub(patch: JSONObject, msg: String? = "Saved") {
        Hub.post("/api/hub/config", patch) { _, e -> if (e != null) act.toast(e) else { if (msg != null) act.toast(msg); Hub.refresh(); reload() } }
    }

    private fun reload() {
        Hub.get("/api/status") { s, e ->
            if (e != null) { act.toast(e); return@get }
            render(s!!)
        }
    }

    private fun render(s: JSONObject) {
        val hub = s.getJSONObject("hub")
        renderWalls(hub)
        renderSwatches(hub.optString("accent"))
        sortable(rows, hub.getJSONArray("rows"), mapOf("myshows" to "Hub's pick and My Shows", "youtube" to "New on YouTube", "anime" to "Anime episodes", "agenda" to "Up next (calendar)"), "rows")
        sortable(widgets, hub.getJSONArray("widgets"), mapOf("weather" to "Weather and what to wear", "note" to "Latest note", "quote" to "Quote over the dock"), "widgets")
        renderShows((s.optJSONArray("shows") ?: JSONArray()).objects())
        renderPassed(s.optInt("passed"))
        renderAbout(s.optString("version"))
        renderLook(hub, s)
        renderQuotes(s.optJSONArray("quotes") ?: JSONArray())
        renderAI(s.optJSONObject("ai") ?: JSONObject())

        prefs.clear()
        prefs.row("24-hour clock", null, null, Toggle(act).apply { on = hub.optBoolean("clock24"); onChange = { saveHub(json("clock24" to it)) } })
        prefs.row("Celsius", "Off shows Fahrenheit", null, Toggle(act).apply { on = hub.optString("units") == "C"; onChange = { saveHub(json("units" to if (it) "C" else "F")) } })
        val nameBox = act.input("Name", hub.optString("name")).apply { minWidth = act.dpi(140f) }
        nameBox.setOnEditorActionListener { _, _, _ -> saveHub(json("name" to nameBox.text.toString().trim())); true }
        prefs.row("Your name", "For the greeting on Hub (press Enter to save)", null, nameBox)
        val pl = s.optJSONObject("place")
        placeInfo.text = if (pl != null) "Showing weather for ${pl.optString("name")}, ${pl.optString("region")}" else "No weather location set"

        val ss = hub.optJSONObject("screensaver")
        ssMin.select((ss?.optInt("minutes", 5) ?: 5).toString())
        ssSrc.select(ss?.optString("source", "gallery") ?: "gallery")

        renderYT(s.optJSONArray("youtube") ?: JSONArray())
        renderAnime(s.optJSONArray("anime") ?: JSONArray())
        renderEvents(s.optJSONArray("events") ?: JSONArray())
        renderNotes(s.optJSONArray("notes") ?: JSONArray())

        val set = s.getJSONObject("settings")
        val ad = s.getJSONObject("adblock")
        server.clear()
        server.row("Ad blocker", "${ad.optInt("blocked")} ads blocked so far, ${ad.optInt("domains")} sites on the list", null,
            Toggle(act).apply { on = set.optBoolean("adblock"); onChange = { v -> setting("adblock", v) } })
        server.row("Open Hub when the TV turns on", null, null, Toggle(act).apply { on = set.optBoolean("autolaunch"); onChange = { v -> setting("autolaunch", v) } })
        server.row("Keep Hub open", "Return to Hub after 45 s on the Roku home screen", null, Toggle(act).apply { on = set.optBoolean("kiosk"); onChange = { v -> setting("kiosk", v) } })
        server.row("TV address", set.optString("tv_ip", "not set"), null, act.button("Change") { askText("TV address", set.optString("tv_ip")) { v -> Hub.post("/api/settings", json("tv_ip" to v)) { _, e -> act.toast(e ?: "Saved"); Hub.refresh() } } })
        server.row("Hub server", "${Hub.server}, version ${s.optString("version")}", null, act.button("Change") { act.show("connect") })
        server.row("TMDB key (optional)", if (s.optBoolean("hasTmdb")) "Saved. Movie details and where-to-watch use it." else "A free key from themoviedb.org adds backdrops, cast and where-to-watch for more titles.", null,
            act.button(if (s.optBoolean("hasTmdb")) "Change" else "Add") {
                askText("TMDB key", "") { v -> Hub.post("/api/settings", json("tmdb_key" to v)) { _, e -> act.toast(e ?: "Saved"); reload() } }
            })
    }

    private fun setting(k: String, v: Boolean) = Hub.post("/api/settings", json(k to v)) { _, e -> act.toast(e ?: "Saved") }

    private fun askText(title: String, value: String, then: (String) -> Unit) {
        val e = act.input(title, value)
        val box = FrameLayout(act).apply { setPadding(act.dpi(20f), act.dpi(8f), act.dpi(20f), 0); addView(e) }
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> then(e.text.toString().trim()) }.setNegativeButton("Cancel", null).show()
    }

    private fun renderWalls(hub: JSONObject) {
        val cur = hub.optJSONObject("wallpaper") ?: JSONObject()
        walls.removeAllViews()
        walls.addView(wallTile(null, "Add a photo", false) {
            act.pickMedia(arrayOf("image/*"), false) { uris ->
                act.toast("Uploading wallpaper…")
                Hub.upload("/api/wallpapers/upload", uris[0], act.nameOf(uris[0]), {}) { _, e -> act.toast(e ?: "Wallpaper set"); reload() }
            }
        })
        Hub.get("/api/wallpapers") { j, _ ->
            val names = j?.optJSONArray("wallpapers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Hub.get("/api/presets") { p, _ ->
                for (n in names) walls.addView(wallTile("/api/wallpapers/" + enc(n), "Photo", cur.optString("type") == "custom" && cur.optString("value") == n,
                    onDelete = { Hub.post("/api/wallpapers/delete", json("name" to n)) { _, _ -> reload() } }) {
                    saveHub(json("wallpaper" to json("type" to "custom", "value" to n)), "Wallpaper set")
                })
                walls.addView(wallTile(null, "Bing daily", cur.optString("type") == "bing", color = 0xFF2C3F6E.toInt()) {
                    saveHub(json("wallpaper" to json("type" to "bing")), "A new photo every day")
                })
                for (pr in p?.optJSONArray("presets")?.objects() ?: emptyList()) {
                    val n = pr.optString("name")
                    walls.addView(wallTile("/api/presets/$n.jpg", n.replaceFirstChar { it.uppercase() }, cur.optString("type") == "preset" && cur.optString("value") == n) {
                        saveHub(json("wallpaper" to json("type" to "preset", "value" to n)), "Wallpaper set")
                    })
                }
            }
        }
    }

    private fun wallTile(img: String?, name: String, selected: Boolean, color: Int = Theme.DEEP, onDelete: (() -> Unit)? = null, onClick: () -> Unit): View {
        val f = FrameLayout(act)
        f.background = rounded(color, act.dp(12f), if (selected) Theme.SNOW else Theme.LINE, act.dpi(if (selected) 3f else 1f))
        f.clipToOutline = true
        if (img != null) {
            val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            Images.load(iv, img, act.dpi(150f))
        }
        val t = act.label(name, 12f, Theme.SNOW, bold = true).apply { setShadowLayer(6f, 0f, 1f, Color.BLACK) }
        f.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, if (img == null && onDelete == null && color == Theme.DEEP) Gravity.CENTER else Gravity.BOTTOM or Gravity.START).apply { setMargins(act.dpi(8f), 0, 0, act.dpi(6f)) })
        if (onDelete != null) {
            // a small dot to look at, with a 44 dp area to tap
            val x = act.label("×", 16f, Theme.SNOW).apply { gravity = Gravity.CENTER; background = circle(0xAA000000.toInt()) }
            val hit = FrameLayout(act).apply {
                addView(x, FrameLayout.LayoutParams(act.dpi(26f), act.dpi(26f), Gravity.END or Gravity.TOP).apply { setMargins(0, act.dpi(4f), act.dpi(4f), 0) })
                contentDescription = "Delete wallpaper"
                setOnClickListener { it.tick(); onDelete() }
            }
            f.addView(hit, FrameLayout.LayoutParams(act.dpi(44f), act.dpi(44f), Gravity.END or Gravity.TOP))
        }
        f.isClickable = true
        f.setOnClickListener { it.tick(); onClick() }
        f.layoutParams = LinearLayout.LayoutParams(act.dpi(144f), act.dpi(81f)).apply { rightMargin = act.dpi(10f) }
        return f
    }

    private fun renderSwatches(current: String) {
        swatches.removeAllViews()
        for (c in ACCENTS) {
            val sel = c.equals(current, ignoreCase = true)
            val v = View(act).apply {
                background = circle(parseColor(c), Theme.SNOW, if (sel) act.dpi(3f) else 0)
                isClickable = true
                contentDescription = "Accent $c"
                setOnClickListener { it.tick(); it.press(); saveHub(json("accent" to c), null) }
            }
            swatches.addView(v, lp(act.dpi(44f), act.dpi(44f)).apply { rightMargin = act.dpi(8f) })
        }
    }

    /** Rows/widgets: on/off switches with up/down arrows to reorder. */
    private fun sortable(box: ListBox, arr: JSONArray, names: Map<String, String>, key: String) {
        box.clear()
        val items = arr.objects().map { JSONObject(it.toString()) }.toMutableList()
        fun save() = saveHub(json(key to JSONArray(items)))
        for ((i, it) in items.withIndex()) {
            val arrows = act.hbox()
            for ((sym, d) in listOf("↑" to -1, "↓" to 1)) {
                val enabled = i + d in items.indices
                arrows.addView(act.label(sym, 18f, if (enabled) Theme.MIST else Theme.LINE, bold = true).apply {
                    gravity = Gravity.CENTER
                    if (enabled) setOnClickListener { v -> v.tick(); val m = items.removeAt(i); items.add(i + d, m); save() }
                }, lp(act.dpi(36f), act.dpi(40f)))
            }
            box.row(names[it.optString("id")] ?: it.optString("id"), null, arrows, Toggle(act).apply {
                on = it.optBoolean("on")
                onChange = { v -> it.put("on", v); save() }
            })
        }
    }

    private fun renderYT(arr: JSONArray) {
        ytList.clear()
        val list = arr.objects()
        if (list.isEmpty()) ytList.row("No channels yet.")
        for (c in list) {
            lateinit var row: LinearLayout
            row = ytList.row(c.optString("name"), null, null, act.button("Remove", danger = true) {
                removable(row, "Removed ${c.optString("name")}") { Hub.post("/api/youtube/delete", json("id" to c.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
            })
        }
    }

    private fun animeRow(box: ListBox, a: JSONObject, right: View): LinearLayout {
        val cover = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Theme.RAISE, act.dp(8f))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(act.dpi(42f), act.dpi(60f))
        }
        if (a.optString("cover").isNotEmpty()) Images.load(cover, a.optString("cover"), 120)
        val sub = if (!a.isNull("airingAt") && a.has("airingAt"))
            "Episode ${a.optInt("episode")}, " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(a.optLong("airingAt") * 1000))
        else a.optString("status").lowercase().replace('_', ' ')
        val row = box.row(a.optString("title"), sub, cover, right)
        (cover.layoutParams as LinearLayout.LayoutParams).apply { width = act.dpi(42f); height = act.dpi(60f) }
        row.requestLayout()
        return row
    }

    private fun renderAnime(arr: JSONArray) {
        aniList.clear()
        val list = arr.objects()
        if (list.isEmpty()) aniList.row("Not following any shows.")
        for (a in list) {
            lateinit var row: LinearLayout
            row = animeRow(aniList, a, act.button("Unfollow", danger = true) {
                removable(row, "Unfollowed ${a.optString("title")}") { Hub.post("/api/anime/delete", json("id" to a.optInt("id"))) { _, e -> if (e != null) act.toast(e) } }
            })
        }
    }

    private fun quickEvent() {
        val cal = Calendar.getInstance()
        val e = act.input("What's happening?")
        val box = FrameLayout(act).apply { setPadding(act.dpi(20f), act.dpi(8f), act.dpi(20f), 0); addView(e) }
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Quick event").setView(box)
            .setPositiveButton("Pick a time") { _, _ ->
                val title = e.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                DatePickerDialog(act, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, d ->
                    TimePickerDialog(act, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, min ->
                        cal.set(y, m, d, h, min, 0)
                        Hub.post("/api/events/add", json("title" to title, "start" to cal.timeInMillis / 1000.0)) { _, err -> act.toast(err ?: "Added"); reload() }
                    }, cal.get(Calendar.HOUR_OF_DAY), 0, false).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renderEvents(arr: JSONArray) {
        evList.clear()
        val list = arr.objects()
        evList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (ev in list) {
            val whenText = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date((ev.optDouble("start") * 1000).toLong()))
            lateinit var row: LinearLayout
            row = evList.row(ev.optString("title"), whenText, null, act.button("Delete", danger = true) {
                removable(row, "Event deleted") { Hub.post("/api/events/delete", json("id" to ev.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
            })
        }
    }

    private fun renderNotes(arr: JSONArray) {
        notes.removeAllViews()
        for (n in arr.objects()) {
            val card = act.hbox().apply {
                background = rounded(parseColor(n.optString("color"), 0xFFD9E2F2.toInt()), act.dp(14f))
                val p = act.dpi(14f)
                setPadding(p, p, act.dpi(6f), p)
            }
            if (n.optString("kind") == "drawing") {
                card.background = rounded(Theme.DEEPER, act.dp(14f), Theme.LINE, act.dpi(1f))
                val img = ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
                Images.load(img, n.optString("image"), 600)
                card.addView(img, lp(0, act.dpi(110f), 1f))
            } else {
                card.addView(act.label(n.optString("text"), 15f, 0xFF151821.toInt(), bold = true), lp(0, WRAP, 1f))
            }
            val drawn = n.optString("kind") == "drawing"
            card.addView(act.label("×", 22f, if (drawn) Theme.MIST else 0x99000000.toInt()).apply {
                gravity = Gravity.CENTER
                contentDescription = "Delete note"
                setOnClickListener { v -> v.tick()
                    removable(card, "Note deleted") { Hub.post("/api/notes/delete", json("id" to n.optString("id"))) { _, e -> if (e != null) act.toast(e) } }
                }
            }, lp(act.dpi(44f), act.dpi(44f)))
            notes.addView(card, lp(MATCH, WRAP).apply { bottomMargin = act.dpi(8f) })
        }
    }
}

// =====================================================================  Connect

class ConnectScreen(act: MainActivity) : Screen(act) {
    private val found = ListBox(act)
    private val status = act.label("", 14f, Theme.MIST)

    init {
        heading("Connect to your Hub server", "Hub Remote talks to the Hub Server app on your CasaOS box. Make sure this tablet is on the same Wi-Fi.")
        add(act.button("Find my server", primary = true) { scan() }, 14f)
        add(status, 10f)
        add(found, 8f)
        section("Or type its address")
        add(act.field("e.g. 10.0.0.134", "Connect", text = Hub.server) { t, _ -> if (t.isNotEmpty()) connect(t) }, 0f)
    }

    override fun onShow() { if (Hub.server.isEmpty()) scan() }

    private fun scan() {
        status.text = "Looking for Hub servers on this Wi-Fi…"
        found.clear()
        Hub.discover { list ->
            status.text = if (list.isEmpty()) "No Hub server found. Check that the Hub Server app is running in CasaOS, or type its address below." else "Tap your server:"
            for (h in list) found.row(h, "Hub server") { connect(h) }
        }
    }

    private fun connect(host: String) {
        Hub.setServer(host)
        act.toast("Connected to $host")
        act.show("remote")
    }
}
