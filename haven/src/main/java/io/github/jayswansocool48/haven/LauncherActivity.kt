package io.github.jayswansocool48.haven

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Haven's home screen: clock, a favorites row and every installed app. */
class LauncherActivity : Activity() {

    private lateinit var repo: AppRepo
    private lateinit var theme: HavenTheme
    private lateinit var root: FrameLayout
    private lateinit var bg: BlobBackground
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var favRow: LinearLayout
    private lateinit var favEmpty: TextView
    private lateinit var allTitle: TextView
    private lateinit var grid: LinearLayout

    private var apps: List<AppEntry> = emptyList()
    private var panel: View? = null
    private var panelReturnFocus: View? = null
    private var onPanelClosed: (() -> Unit)? = null
    private var moving: String? = null
    private var dirty = true
    private var resumed = false

    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)

    private var cardW = 0
    private var cardH = 0
    private var columns = 5

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            if (resumed) ui.post { reload() }
        }
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepo(this)
        theme = themeById(repo.themeId)
        buildUi()

        register(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        register(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        ShareServer.start(applicationContext)
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (dirty) reload()
        updateClock()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
            unregisterReceiver(timeReceiver)
        } catch (e: IllegalArgumentException) {
        }
        loader.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while Haven is already open: tidy up and go back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closePanel()
        stopMoving()
        scroll.smoothScrollTo(0, 0)
        favScroll.smoothScrollTo(0, 0)
        focusFirst()
    }

    @Deprecated("Haven is the home screen, so Back never leaves it.")
    override fun onBackPressed() {
        when {
            panel != null -> closePanel()
            moving != null -> stopMoving()
            else -> {
                scroll.smoothScrollTo(0, 0)
                focusFirst()
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val m = resources.displayMetrics
        val side = dpi(56f)
        val gap = dpi(26f)
        cardW = dpi(170f)
        cardH = dpi(96f)
        columns = Math.max(3, (m.widthPixels - side * 2 + gap) / (cardW + gap))
        // Stretch the cards a little so the grid fills the width exactly.
        cardW = (m.widthPixels - side * 2 - gap * (columns - 1)) / columns
        cardH = cardW * 9 / 16

        root = FrameLayout(this)
        bg = BlobBackground(this)
        bg.theme = theme
        bg.animated = repo.animate
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            isSmoothScrollingEnabled = true
        }
        page = vbox().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(side, dpi(36f), side, dpi(48f))
        }
        scroll.addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header: greeting, big clock and date on the left; two pills on the right.
        val header = hbox().apply { clipChildren = false }
        val left = vbox()
        greeting = text("", 20f, theme.dim)
        clock = text("", 66f, theme.text, bold = true)
        date = text("", 19f, theme.dim)
        left.addView(greeting)
        left.addView(clock, lp(WRAP, WRAP).apply { topMargin = dpi(4f) })
        left.addView(date, lp(WRAP, WRAP).apply { topMargin = dpi(2f) })
        header.addView(left, lp(0, WRAP, 1f))
        val pills = hbox().apply { clipChildren = false }
        pills.addView(pill("⚙  Haven", theme) { showSettings(0) })
        pills.addView(pill("📺  Fire TV settings", theme) { openFireSettings() }, lp(WRAP, WRAP).apply { leftMargin = dpi(14f) })
        header.addView(pills)
        page.addView(header)

        page.addView(sectionTitle("Favorites", "✦"), lp(MATCH, WRAP).apply { topMargin = dpi(30f) })
        favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        favRow = hbox().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dpi(18f), dpi(40f), dpi(18f))
        }
        favScroll.addView(favRow)
        page.addView(favScroll, lp(MATCH, WRAP))
        favEmpty = text("Hold OK (or press ☰) on any app to pin it here.", 17f, theme.dim).apply {
            setPadding(0, dpi(10f), 0, dpi(20f))
            visibility = View.GONE
        }
        page.addView(favEmpty)

        allTitle = sectionTitle("All apps", "✿")
        page.addView(allTitle, lp(MATCH, WRAP).apply { topMargin = dpi(14f) })
        grid = vbox().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dpi(18f), 0, 0)
        }
        page.addView(grid, lp(MATCH, WRAP))

        setContentView(root)
    }

    private fun sectionTitle(label: String, mark: String): TextView {
        val s = SpannableString("$label  $mark")
        s.setSpan(ForegroundColorSpan(theme.accent), label.length + 2, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text("", 24f, theme.text, bold = true).apply { text = s }
    }

    /** Rebuilds everything after the look changes. */
    private fun rebuild() {
        theme = themeById(repo.themeId)
        panel = null
        buildUi()
        dirty = true
        reload()
        updateClock()
    }

    // ---------------------------------------------------------------- apps

    private fun reload() {
        dirty = false
        val focusedPkg = (currentFocus as? AppTile)?.app?.pkg
        val focusedInFav = currentFocus?.parent == favRow
        apps = repo.load()
        repo.seedFavorites(apps)
        renderFavorites()
        renderGrid()
        val target = focusedPkg?.let { findTile(it, focusedInFav) }
        if (target != null) target.requestFocus() else if (currentFocus == null || currentFocus !is AppTile && panel == null) focusFirst()
    }

    private fun renderFavorites() {
        favRow.removeAllViews()
        val byPkg = apps.associateBy { it.pkg }
        val favs = repo.favorites.mapNotNull { byPkg[it] }
        for ((i, app) in favs.withIndex()) {
            val tile = makeTile(app, inFavorites = true)
            val l = lp(WRAP, WRAP)
            if (i > 0) l.leftMargin = dpi(26f)
            favRow.addView(tile, l)
        }
        favScroll.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
        favEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderGrid() {
        grid.removeAllViews()
        val hidden = repo.hidden
        val shown = apps.filter { repo.showHidden || it.pkg !in hidden }
        val s = SpannableString("All apps  ✿  ${shown.size}")
        s.setSpan(ForegroundColorSpan(theme.accent), 10, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(theme.dim), 12, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        allTitle.text = s
        var row: LinearLayout? = null
        for ((i, app) in shown.withIndex()) {
            if (i % columns == 0) {
                row = hbox().apply { clipChildren = false; clipToPadding = false }
                grid.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dpi(22f) })
            }
            val tile = makeTile(app, inFavorites = false, dimmed = app.pkg in hidden)
            val l = lp(WRAP, WRAP)
            if (i % columns > 0) l.leftMargin = dpi(26f)
            row!!.addView(tile, l)
        }
    }

    private fun makeTile(app: AppEntry, inFavorites: Boolean, dimmed: Boolean = false): AppTile {
        val tile = AppTile(this, app, theme, cardW, cardH, dimmed)
        tile.setOnClickListener { launch(tile) }
        tile.setOnLongClickListener {
            showAppMenu(app, inFavorites, tile)
            true
        }
        tile.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) {
                showAppMenu(app, inFavorites, tile)
                true
            } else {
                code == KeyEvent.KEYCODE_MENU
            }
        }
        val useBanner = repo.useBanners
        loader.execute {
            val banner = if (useBanner && app.tv) repo.banner(app) else null
            val icon = repo.icon(app)
            ui.post { tile.setArt(banner, icon) }
        }
        return tile
    }

    private fun findTile(pkg: String, inFavorites: Boolean): AppTile? {
        if (inFavorites) {
            for (i in 0 until favRow.childCount) (favRow.getChildAt(i) as? AppTile)?.let { if (it.app.pkg == pkg) return it }
        }
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as? ViewGroup ?: continue
            for (i in 0 until row.childCount) (row.getChildAt(i) as? AppTile)?.let { if (it.app.pkg == pkg) return it }
        }
        return null
    }

    private fun focusFirst() {
        val first = if (favRow.childCount > 0 && favScroll.visibility == View.VISIBLE) favRow.getChildAt(0)
        else (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        first?.requestFocus()
    }

    private fun launch(tile: AppTile) {
        tile.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
            bounce(tile, tile.isFocused, 1.12f)
            try {
                startActivity(repo.launchIntent(tile.app))
            } catch (e: Exception) {
                try {
                    val fallback = packageManager.getLeanbackLaunchIntentForPackage(tile.app.pkg)
                        ?: packageManager.getLaunchIntentForPackage(tile.app.pkg)
                    if (fallback != null) startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else toast("Couldn't open ${tile.app.label}")
                } catch (e2: Exception) {
                    toast("Couldn't open ${tile.app.label}")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- moving favorites

    private fun startMoving(pkg: String) {
        moving = pkg
        renderFavorites()
        findTile(pkg, true)?.let {
            it.requestFocus()
            it.setMoving(true)
        }
        toast("◀ ▶ to move, OK when done")
    }

    private fun stopMoving() {
        val pkg = moving ?: return
        moving = null
        findTile(pkg, true)?.setMoving(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pkg = moving
        if (pkg != null && panel == null) {
            val code = event.keyCode
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (repo.moveFavorite(pkg, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)) {
                            renderFavorites()
                            findTile(pkg, true)?.let {
                                it.requestFocus()
                                it.setMoving(true)
                                it.post { favScroll.requestChildFocus(it, it) }
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                        stopMoving()
                        return true
                    }
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- panels

    private fun showPanel(title: String, body: String?, items: List<View>, focusIndex: Int = 0) {
        closePanel(restore = false)
        if (panelReturnFocus == null) panelReturnFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(theme.top and 0x00FFFFFF or (0xB8 shl 24))
            isClickable = true
            alpha = 0f
        }
        val card = vbox().apply {
            background = rounded(theme.panel, dp(34f))
            elevation = dp(24f)
            val p = dpi(34f)
            setPadding(p, p, p, p)
            clipChildren = false
            clipToPadding = false
        }
        card.addView(text(title, 28f, theme.text, bold = true))
        if (body != null) {
            card.addView(text(body, 17f, theme.dim).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP).apply { topMargin = dpi(10f) })
        }
        val list = vbox().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        }
        for (v in items) list.addView(v, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })
        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(list)
        }
        card.addView(listScroll, lp(MATCH, WRAP).apply { topMargin = dpi(12f) })

        val w = Math.min(dpi(600f), resources.displayMetrics.widthPixels - dpi(80f))
        val clp = FrameLayout.LayoutParams(w, WRAP, Gravity.CENTER)
        clp.topMargin = dpi(30f)
        clp.bottomMargin = dpi(30f)
        scrim.addView(card, clp)
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        panel = scrim

        scrim.animate().alpha(1f).setDuration(160).start()
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.2f)).setDuration(300).start()
        val focusable = items.filter { it.isFocusable }
        (focusable.getOrNull(focusIndex) ?: focusable.firstOrNull())?.requestFocus()
    }

    private fun closePanel(restore: Boolean = true) {
        val p = panel ?: return
        panel = null
        root.removeView(p)
        val cb = onPanelClosed
        onPanelClosed = null
        cb?.invoke()
        if (restore) {
            page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val back = panelReturnFocus
            panelReturnFocus = null
            if (back != null && back.isAttachedToWindow) back.requestFocus() else focusFirst()
        }
    }

    private fun item(label: String, action: () -> Unit) = pill(label, theme, action)
    private fun note(s: String) = text(s, 16f, theme.dim).apply { setLineSpacing(0f, 1.2f) }

    private fun showAppMenu(app: AppEntry, inFavorites: Boolean, tile: AppTile) {
        panelReturnFocus = tile
        val items = ArrayList<View>()
        val fav = repo.isFavorite(app.pkg)
        items += item("▶  Open") { closePanel(); launch(tile) }
        if (fav) {
            if (inFavorites) items += item("↔  Move") {
                closePanel()
                startMoving(app.pkg)
            }
            items += item("♡  Unpin from Favorites") {
                repo.removeFavorite(app.pkg)
                closePanel(restore = false)
                page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                panelReturnFocus = null
                renderFavorites()
                (findTile(app.pkg, false) ?: run { focusFirst(); null })?.requestFocus()
            }
        } else {
            items += item("♥  Pin to Favorites") {
                repo.addFavorite(app.pkg)
                closePanel()
                renderFavorites()
                toast("Pinned ${app.label} ✦")
            }
        }
        if (app.pkg in repo.hidden) {
            items += item("👀  Show in All apps") {
                repo.unhide(app.pkg)
                closePanel(restore = false)
                page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                panelReturnFocus = null
                renderGrid()
                findTile(app.pkg, inFavorites)?.requestFocus() ?: focusFirst()
            }
        } else {
            items += item("🙈  Hide from All apps") {
                repo.hide(app.pkg)
                closePanel(restore = false)
                page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                panelReturnFocus = null
                renderGrid()
                if (inFavorites) findTile(app.pkg, true)?.requestFocus() else focusFirst()
                toast("Hidden. Find it again in Haven ▸ Hidden apps")
            }
        }
        items += item("ℹ  App info") {
            closePanel()
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
        }
        items += item("🗑  Uninstall") {
            closePanel()
            tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }
        showPanel(app.label, null, items)
    }

    private fun showSettings(focus: Int) {
        val items = ArrayList<View>()
        items += item("🎨  Look: ${theme.name}") { showThemes() }
        items += item("✨  Floating bubbles: ${if (repo.animate) "On" else "Off"}") {
            repo.animate = !repo.animate
            bg.animated = repo.animate
            showSettings(1)
        }
        items += item("🖼  App art: ${if (repo.useBanners) "TV banners" else "Icons"}") {
            repo.useBanners = !repo.useBanners
            closePanel(restore = false)
            page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            panelReturnFocus = null
            renderFavorites()
            renderGrid()
            showSettings(2)
        }
        items += item("🕒  Clock: ${if (repo.clock24) "24-hour" else "12-hour"}") {
            repo.clock24 = !repo.clock24
            updateClock()
            showSettings(3)
        }
        items += item("🙈  Hidden apps (${repo.hidden.size})") { showHidden() }
        items += item("🏠  Home button: ${if (HomeWatcher.isOn(this)) "Haven ✓" else "Set up"}") { showHomeHelp() }
        items += item("📱  Haven Remote") { showRemoteInfo() }
        items += item("📺  Open the Fire TV home screen") {
            closePanel()
            openStockHome()
        }
        items += item("Done") { closePanel() }
        showPanel("Haven", "Version ${versionName()}", items, focus)
    }

    private fun showThemes() {
        val original = theme
        val items = THEMES.map { t ->
            val label = SpannableString("●  ${t.name}${if (t.id == theme.id) "   ✓" else ""}")
            label.setSpan(ForegroundColorSpan(t.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            item("") {
                onPanelClosed = null
                repo.themeId = t.id
                closePanel(restore = false)
                panelReturnFocus = null
                rebuild()
                showSettings(0)
            }.apply {
                text = label
                val base = onFocusChangeListener
                setOnFocusChangeListener { v, has ->
                    base?.onFocusChange(v, has)
                    if (has) bg.theme = t
                }
            }
        }
        showPanel("Pick a look", "Move around to preview, press OK to keep it.", items, THEMES.indexOf(theme).coerceAtLeast(0))
        onPanelClosed = { bg.theme = original }
    }

    private fun showHidden() {
        val hidden = repo.hidden
        val byPkg = apps.associateBy { it.pkg }
        val items = ArrayList<View>()
        items += item("Show hidden apps (faded) in All apps: ${if (repo.showHidden) "On" else "Off"}") {
            repo.showHidden = !repo.showHidden
            renderGrid()
            showHidden()
        }
        for (pkg in hidden.sortedBy { byPkg[it]?.label?.lowercase() ?: it }) {
            val name = byPkg[pkg]?.label ?: pkg
            items += item("👀  Unhide $name") {
                repo.unhide(pkg)
                renderGrid()
                showHidden()
            }
        }
        items += item("Back") { showSettings(4) }
        showPanel("Hidden apps", if (hidden.isEmpty()) "Nothing hidden. Hold OK on an app ▸ Hide to tuck it away." else null, items)
    }

    private fun showHomeHelp() {
        val on = HomeWatcher.isOn(this)
        val body = if (on) {
            "Haven opens whenever you press Home. ✓\n\nTo get the Fire TV home screen back for a moment, use Haven ▸ Open the Fire TV home screen."
        } else {
            "Make the Home button open Haven:\n\n" +
                "•  Easiest: on Haven Remote, go to Setup ▸ Make Haven the TV's home screen.\n" +
                "•  Already use an app that opens your launcher automatically? Pick Haven in it instead.\n" +
                "•  Or turn on \"Haven home button\" in accessibility settings, if this TV lists it."
        }
        val items = ArrayList<View>()
        if (!on) items += item("Open accessibility settings") {
            closePanel()
            if (!tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast("This TV hides that screen. Use Haven Remote instead.")
        }
        items += item("Back") { showSettings(5) }
        showPanel("Home button", body, items)
    }

    private fun showRemoteInfo() {
        val ip = ShareServer.localIp() ?: "unknown (not on Wi-Fi?)"
        val body = "Control this TV from a phone with the Haven Remote app.\n\n" +
            "1.  On this TV: Settings ▸ My Fire TV ▸ About, press OK on the TV's name 7 times. Developer options appears.\n" +
            "2.  Settings ▸ My Fire TV ▸ Developer options ▸ turn on ADB debugging.\n" +
            "3.  On the phone, open Haven Remote ▸ Setup ▸ Find my TV, then Connect.\n" +
            "4.  On this TV, tick \"Always allow from this computer\" and press OK.\n\n" +
            "This TV's address: $ip"
        val items = ArrayList<View>()
        items += item("Open Developer options") {
            closePanel()
            if (!tryStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) toast("Turn it on first (step 1)")
        }
        items += item("Back") { showSettings(6) }
        showPanel("Haven Remote", body, items)
    }

    // ---------------------------------------------------------------- misc

    private fun updateClock() {
        if (!::clock.isInitialized) return
        val now = Date()
        val fmt = if (repo.clock24) "HH:mm" else "h:mm"
        clock.text = SimpleDateFormat(fmt, Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> "Good morning  ☀"
            in 12..16 -> "Good afternoon  ✿"
            in 17..21 -> "Good evening  ☾"
            else -> "Sweet dreams  ✧"
        }
    }

    private fun openFireSettings() {
        if (!tryStart(Intent(Settings.ACTION_SETTINGS))) {
            packageManager.getLaunchIntentForPackage("com.amazon.tv.settings.v2")?.let { tryStart(it) }
        }
    }

    /** Opens Amazon's home screen, telling the Home button watcher to leave it alone for a bit. */
    private fun openStockHome() {
        HomeWatcher.pauseFor(20_000)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0).firstOrNull { it.activityInfo.packageName != packageName }
        if (other != null) {
            tryStart(Intent(home).setComponent(ComponentName(other.activityInfo.packageName, other.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            packageManager.getLaunchIntentForPackage("com.amazon.tv.launcher")?.let { tryStart(it) } ?: toast("Couldn't find the Fire TV home screen")
        }
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
