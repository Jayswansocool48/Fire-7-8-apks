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
            nav.background = if (!pointer) rounded(Theme.accent, act.dp(99f)) else null
            ptr.background = if (pointer) rounded(Theme.accent, act.dp(99f)) else null
            nav.setTextColor(if (!pointer) Color.WHITE else Theme.MIST)
            ptr.setTextColor(if (pointer) Color.WHITE else Theme.MIST)
        }
        for (t in listOf(nav, ptr)) {
            t.setPadding(act.dpi(18f), act.dpi(8f), act.dpi(18f), act.dpi(8f))
            modes.addView(t)
        }
        nav.setOnClickListener { it.tick(); setMode(false) }
        ptr.setOnClickListener { it.tick(); setMode(true) }
        setMode(false)

        val controls = act.vbox()
        val keys = Grid(act, act.dpi(90f))
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
            val star = act.label(if (fav) "★" else "☆", 18f, if (fav) 0xFFFFD166.toInt() else Color.WHITE).apply {
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
                it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
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

    init {
        heading("Send to TV", "Photos and videos open full screen on Hub. YouTube links play in the YouTube app; other links open in Hub's browser. You can also use Share → Hub Remote from any app.")
        val pick = act.vbox().apply {
            background = rounded((Theme.accent and 0x00FFFFFF) or 0x18000000, act.dp(20f), (Theme.accent and 0x00FFFFFF) or 0x90000000.toInt(), act.dpi(1.5f))
            gravity = Gravity.CENTER
            val p = act.dpi(24f)
            setPadding(p, p, p, p)
            addView(act.label("Choose photos or videos", 18f, Theme.SNOW, bold = true).apply { gravity = Gravity.CENTER })
            addView(act.label("They show on the TV as soon as they upload", 13f, Theme.DIM).apply { gravity = Gravity.CENTER })
            isClickable = true
            setOnClickListener { it.tick(); act.pickMedia(arrayOf("image/*", "video/*", "audio/*"), true) { sendFiles(it) } }
        }
        add(pick, 12f)
        add(progress, 8f)
        section("Send a link")
        add(act.field("Paste a link", "Show on TV", url = true, primary = true) { u, e -> if (u.isNotEmpty()) { sendLink(u); e.setText("") } }, 0f)
        section("From your server", "Your CasaOS media folders. Tap something to play it on the TV.")
        add(crumbs, 0f)
        add(lib, 8f)
    }

    override fun onShow() = load(path)

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
    private var loaded = false

    init {
        heading("Your Hub", "Changes show up on the TV within a second or two.")
        section("Wallpaper")
        add(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(walls) }, 0f)
        section("Accent color")
        add(swatches, 0f)
        section("Home screen rows", "Use the arrows to reorder. Switch off what you don't want.")
        add(rows, 0f)
        section("Widgets")
        add(widgets, 0f)
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

        section("Notes", "Notes show up on the TV's home screen.")
        add(act.field("Leave a note on the TV", "Post", primary = true) { t, e ->
            if (t.isEmpty()) return@field
            val colors = listOf("#FFD166", "#A0E7E5", "#FFAEBC", "#B4F8C8", "#C9B6FF")
            Hub.post("/api/notes/add", json("text" to t, "color" to colors.random())) { _, err ->
                if (err != null) act.toast(err) else { e.setText(""); act.toast("Posted to the TV"); reload() }
            }
        }, 0f)
        add(notes, 10f)

        section("Server")
        add(server, 0f)
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
        sortable(rows, hub.getJSONArray("rows"), mapOf("widgets" to "Widgets", "youtube" to "YouTube", "anime" to "Anime episodes", "apps" to "Hub apps", "channels" to "Channels"), "rows")
        sortable(widgets, hub.getJSONArray("widgets"), mapOf("weather" to "Weather", "agenda" to "Up next (calendar)", "notes" to "Notes"), "widgets")

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
                walls.addView(wallTile(null, "Bing daily", cur.optString("type") == "bing", color = 0xFF2B5C8A.toInt()) {
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
        val t = act.label(name, 12f, Color.WHITE, bold = true).apply { setShadowLayer(6f, 0f, 1f, Color.BLACK) }
        f.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, if (img == null && onDelete == null && color == Theme.DEEP) Gravity.CENTER else Gravity.BOTTOM or Gravity.START).apply { setMargins(act.dpi(8f), 0, 0, act.dpi(6f)) })
        if (onDelete != null) {
            val x = act.label("×", 16f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(0xAA000000.toInt(), act.dp(99f)); setOnClickListener { onDelete() } }
            f.addView(x, FrameLayout.LayoutParams(act.dpi(26f), act.dpi(26f), Gravity.END or Gravity.TOP).apply { setMargins(0, act.dpi(4f), act.dpi(4f), 0) })
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
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(parseColor(c))
                    if (sel) setStroke(act.dpi(3f), Theme.SNOW)
                }
                isClickable = true
                setOnClickListener { it.tick(); saveHub(json("accent" to c), null) }
            }
            swatches.addView(v, lp(act.dpi(36f), act.dpi(36f)).apply { rightMargin = act.dpi(10f) })
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
        for (c in list) ytList.row(c.optString("name"), null, null, act.button("Remove", danger = true) {
            Hub.post("/api/youtube/delete", json("id" to c.optString("id"))) { j, e -> if (e != null) act.toast(e) else renderYT(j!!.getJSONArray("channels")) }
        })
    }

    private fun animeRow(box: ListBox, a: JSONObject, right: View) {
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
    }

    private fun renderAnime(arr: JSONArray) {
        aniList.clear()
        val list = arr.objects()
        if (list.isEmpty()) aniList.row("Not following any shows.")
        for (a in list) animeRow(aniList, a, act.button("Unfollow", danger = true) {
            Hub.post("/api/anime/delete", json("id" to a.optInt("id"))) { j, e -> if (e != null) act.toast(e) else renderAnime(j!!.getJSONArray("anime")) }
        })
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
            evList.row(ev.optString("title"), whenText, null, act.button("Delete", danger = true) {
                Hub.post("/api/events/delete", json("id" to ev.optString("id"))) { _, e -> act.toast(e ?: "Deleted"); reload() }
            })
        }
    }

    private fun renderNotes(arr: JSONArray) {
        notes.removeAllViews()
        for (n in arr.objects()) {
            val card = act.hbox().apply {
                background = rounded(parseColor(n.optString("color"), 0xFFFFD166.toInt()), act.dp(12f))
                val p = act.dpi(14f)
                setPadding(p, p, act.dpi(6f), p)
            }
            card.addView(act.label(n.optString("text"), 15f, 0xFF1D1A10.toInt(), bold = true), lp(0, WRAP, 1f))
            card.addView(act.label("×", 20f, 0x99000000.toInt()).apply {
                gravity = Gravity.CENTER
                setOnClickListener { Hub.post("/api/notes/delete", json("id" to n.optString("id"))) { _, e -> act.toast(e ?: "Deleted"); reload() } }
            }, lp(act.dpi(36f), act.dpi(36f)))
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
