package io.github.jayswansocool48.hubremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var nowText: TextView
    private lateinit var nowSub: TextView
    private lateinit var sleepBtn: IconButton
    private val screens = LinkedHashMap<String, Screen>()
    private val tabs = LinkedHashMap<String, NavItem>()
    var current = "remote"
        private set
    private lateinit var toastBar: LinearLayout
    private lateinit var toastText: TextView
    private lateinit var toastUndo: TextView
    private var pending: (() -> Unit)? = null // the delete waiting behind an Undo toast
    private val hideToast = Runnable { commitPending(); toastBar.springY(dp(120f)); toastBar.springAlpha(0f) }
    private val listener: () -> Unit = { updateHeader() }

    // pending file-picker callbacks
    private var onPicked: ((List<Uri>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        window.statusBarColor = Theme.NIGHT
        window.navigationBarColor = Theme.NIGHT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Hub.init(this)
        Hub.onError = { toast(it) }
        build()
        intent?.getStringExtra("server")?.let { if (it.isNotBlank()) Hub.setServer(it) }
        if (Hub.server.isEmpty()) show("connect") else show(intent?.getStringExtra("tab") ?: "remote")
        handleShare(intent)
        predictiveBack()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "server" and "tab" extras let a link (or the emulator test) connect and open a tab
        intent.getStringExtra("server")?.let { if (it.isNotBlank()) Hub.setServer(it) }
        intent.getStringExtra("tab")?.let { show(it) }
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.onChange(listener)
        Hub.startPolling()
        screens[current]?.onShow()
    }

    override fun onPause() {
        commitPending()
        Hub.stopPolling()
        Hub.removeChange(listener)
        screens[current]?.onHide()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val keep = current
        screens[current]?.onHide()
        build()
        show(keep)
    }

    private fun landscape() = resources.configuration.screenWidthDp >= 600 && resources.configuration.screenWidthDp > resources.configuration.screenHeightDp

    private fun build() {
        screens.clear()
        tabs.clear()
        root = vbox().apply { setBackgroundColor(Theme.NIGHT) }

        // header: what's on the TV, sleep timer, power
        val header = hbox()
        val p = dpi(16f)
        header.setPadding(p, dpi(12f), p, dpi(8f))
        header.addView(Mark(this), lp(dpi(32f), dpi(32f)))
        val now = vbox()
        nowText = label("Hub Remote", 17f, Theme.SNOW, bold = true).apply { maxLines = 1 }
        nowSub = label("", 13f, Theme.DIM)
        now.addView(nowText)
        now.addView(nowSub)
        header.addView(now, lp(0, WRAP, 1f).apply { leftMargin = dpi(12f) })
        sleepBtn = IconButton(this, IconButton.MOON) { sleepSheet() }
        header.addView(sleepBtn, lp(dpi(44f), dpi(44f)))
        val power = IconButton(this, IconButton.POWER) { Hub.key("PowerOff"); toast("Turning the TV off") }
        header.addView(power, lp(dpi(44f), dpi(44f)).apply { leftMargin = dpi(10f) })
        root.addView(header, lp(MATCH, WRAP))

        content = FrameLayout(this)
        val nav = if (landscape()) vbox() else hbox()
        nav.setBackgroundColor(Theme.DEEPER)
        for ((key, name) in listOf("remote" to "Remote", "channels" to "Channels", "browser" to "Browser", "send" to "Send", "hub" to "Hub")) {
            val item = NavItem(this, key, name) { show(key) }
            tabs[key] = item
            if (landscape()) nav.addView(item, lp(MATCH, dpi(72f))) else nav.addView(item, lp(0, dpi(62f), 1f))
        }
        if (landscape()) {
            val body = hbox()
            nav.setPadding(0, dpi(8f), 0, 0)
            body.addView(nav, lp(dpi(92f), MATCH))
            body.addView(content, lp(0, MATCH, 1f))
            body.gravity = Gravity.TOP
            root.addView(body, lp(MATCH, 0, 1f))
        } else {
            root.addView(content, lp(MATCH, 0, 1f))
            root.addView(nav, lp(MATCH, WRAP))
        }
        // Keep focus off the text boxes so the keyboard only opens when you tap one.
        root.isFocusableInTouchMode = true
        root.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        // one toast bar for messages and Undo, floating over the content
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        toastText = label("", 15f, Theme.SNOW).apply { maxLines = 2 }
        toastUndo = label("Undo", 15f, Theme.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dpi(64f)
            minHeight = dpi(44f)
            setOnClickListener { tick(); pending = null; undoAction?.invoke(); undoAction = null; hideToast.run() }
        }
        toastBar = hbox().apply {
            background = rounded(Theme.RAISE, dp(16f), Theme.LINE, dpi(1f))
            setPadding(dpi(16f), dpi(4f), dpi(6f), dpi(4f))
            minimumHeight = dpi(52f)
            addView(toastText, lp(0, WRAP, 1f))
            addView(toastUndo, lp(WRAP, WRAP))
            alpha = 0f
            translationY = dp(120f)
            elevation = dp(6f)
        }
        frame.addView(toastBar, FrameLayout.LayoutParams(dpi(440f).coerceAtMost(resources.displayMetrics.widthPixels - dpi(24f)), WRAP,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(if (landscape()) 20f else 76f) })
        setContentView(frame)
        root.requestFocus()
        updateHeader()
    }

    fun show(key: String) {
        screens[current]?.let { it.onHide(); it.visibility = View.GONE }
        current = key
        val s = screens.getOrPut(key) {
            val n = when (key) {
                "channels" -> ChannelsScreen(this)
                "browser" -> BrowserScreen(this)
                "send" -> SendScreen(this)
                "hub" -> HubScreen(this)
                "connect" -> ConnectScreen(this)
                else -> RemoteScreen(this)
            }
            content.addView(n, FrameLayout.LayoutParams(MATCH, MATCH))
            n
        }
        s.visibility = View.VISIBLE
        // springs carry their speed, so tapping tabs quickly never jumps
        Spring.set(s, "alpha", 0f); s.alpha = 0f
        Spring.set(s, "y", dp(14f)); s.translationY = dp(14f)
        s.springAlpha(1f)
        s.springY(0f)
        for ((k, t) in tabs) t.active = k == key
        s.onShow()
        root.requestFocus()
    }

    fun updateHeader() {
        if (!::nowText.isInitialized) return
        nowText.text = when {
            Hub.server.isEmpty() -> "Hub Remote"
            Hub.tvApp != null -> Hub.tvApp
            Hub.serverOk -> "Can't reach the TV"
            else -> "Can't reach the Hub server"
        }
        val left = Hub.sleepLeft()
        nowSub.text = when {
            Hub.server.isEmpty() -> "Not connected yet"
            left > 0 -> "TV turns off in ${minutes(left)}"
            Hub.tvOk -> "TV connected"
            Hub.serverOk -> "Check the TV address on the Hub tab"
            else -> "Server ${Hub.server}"
        }
        nowSub.setTextColor(if (Hub.tvOk) Theme.OK else Theme.DIM)
        sleepBtn.lit = left > 0
        for (t in tabs.values) t.invalidate()
    }

    fun minutes(secs: Int): String {
        val m = (secs + 59) / 60
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun sleepSheet() {
        val opts = listOf(15, 30, 45, 60, 90, 120, 0)
        val names = opts.map { if (it == 0) "Off" else if (it < 60) "$it minutes" else if (it % 60 == 0) "${it / 60} hour${if (it > 60) "s" else ""}" else "1.5 hours" }
        val left = Hub.sleepLeft()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (left > 0) "Sleep timer: ${minutes(left)} left" else "Turn the TV off in…")
            .setItems(names.toTypedArray()) { _, i ->
                Hub.post("/api/sleep", json("minutes" to opts[i])) { _, e ->
                    if (e != null) toast(e) else { toast(if (opts[i] == 0) "Sleep timer off" else "TV turns off in ${names[i]}"); Hub.refresh() }
                }
            }.show()
    }

    private var undoAction: (() -> Unit)? = null

    fun toast(text: String) = showToast(text, null, null)

    /**
     * Deletes wait behind a toast with Undo instead of a confirm dialog. [apply] updates the screen
     * at once, [undo] puts it back, and [commit] tells the server after 5 seconds (or when the
     * next toast arrives, or the app goes to the background).
     */
    fun undoable(text: String, apply: () -> Unit, undo: () -> Unit, commit: () -> Unit) {
        apply()
        showToast(text, undo, commit)
    }

    private fun showToast(text: String, undo: (() -> Unit)?, commit: (() -> Unit)?) {
        if (!::toastBar.isInitialized) return
        commitPending()
        pending = commit
        undoAction = undo
        toastText.text = text
        toastUndo.visibility = if (undo != null) View.VISIBLE else View.GONE
        toastBar.removeCallbacks(hideToast)
        toastBar.springY(0f, Spring.BOUNCY)
        toastBar.springAlpha(1f)
        toastBar.postDelayed(hideToast, if (undo != null) 5000 else 2600)
    }

    private fun commitPending() { val c = pending; pending = null; undoAction = null; c?.invoke() }

    /** Removes Hub Remote and everything it saved on this tablet. */
    fun uninstallSelf() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Uninstall Hub Remote?")
            .setMessage("This deletes the saved server address and cached pictures on this tablet, then asks Android to remove the app. Your Hub server and TV stay as they are.")
            .setPositiveButton("Uninstall") { _, _ ->
                Hub.forget()
                cacheDir.deleteRecursively()
                val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                try { startActivity(i) } catch (e: Exception) { toast("Open Settings › Apps › Hub Remote › Uninstall") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Android 14: the back gesture previews the Remote tab as you drag, and cancels if you let go early. */
    private fun predictiveBack() {
        if (Build.VERSION.SDK_INT < 34) return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : android.window.OnBackAnimationCallback {
                var peek: Screen? = null
                override fun onBackStarted(e: android.window.BackEvent) {
                    peek = null
                    if (current == "remote" || current == "connect" || Hub.server.isEmpty()) return
                    val target = screens["remote"] ?: return
                    if (screens[current]?.wantsBack() == true) return
                    peek = target
                    target.visibility = View.VISIBLE
                    target.alpha = 0f
                }
                override fun onBackProgressed(e: android.window.BackEvent) {
                    val cur = screens[current] ?: return
                    val pk = peek ?: return
                    val p = e.progress
                    cur.translationX = p * dp(72f) * (if (e.swipeEdge == android.window.BackEvent.EDGE_RIGHT) -1 else 1)
                    cur.alpha = 1f - p * 0.6f
                    pk.alpha = p
                }
                override fun onBackCancelled() {
                    val cur = screens[current] ?: return
                    cur.springX(0f, Spring.BOUNCY); cur.springAlpha(1f)
                    peek?.let { pk -> pk.springAlpha(0f) { pk.visibility = View.GONE } }
                    peek = null
                }
                override fun onBackInvoked() {
                    val cur = screens[current]
                    cur?.translationX = 0f
                    peek = null
                    goBack()
                }
            })
    }

    private fun goBack() {
        if (screens[current]?.onBack() == true) return
        if (current != "remote" && Hub.server.isNotEmpty()) show("remote") else finish()
    }

    // ---- picking files ----

    fun pickMedia(types: Array<String>, multiple: Boolean, then: (List<Uri>) -> Unit) {
        onPicked = then
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = if (types.size == 1) types[0] else "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, types)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Choose"), 7)
        } catch (e: Exception) {
            toast("No file picker on this tablet")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        else data.data?.let { uris.add(it) }
        val cb = onPicked
        onPicked = null
        if (uris.isNotEmpty()) cb?.invoke(uris)
    }

    fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment ?: "file"
    }

    /** Shared from another app: links go to the TV, photos/videos get uploaded and shown. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        if (Hub.server.isEmpty()) { toast("Connect to your Hub server first"); return }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = ArrayList<Uri>()
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        }
        show("send")
        if (uris.isNotEmpty()) (screens["send"] as? SendScreen)?.sendFiles(uris)
        else if (!text.isNullOrBlank()) {
            val link = Regex("https?://\\S+").find(text)?.value ?: text
            (screens["send"] as? SendScreen)?.sendLink(link)
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    @Deprecated("Back before Android 14")
    override fun onBackPressed() = goBack()
}

/** The Hub mark: a sun setting behind the TV stand, in the accent color. Same as the control page. */
class Mark(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val u = width / 34f
        p.color = Theme.accent
        c.drawArc(7 * u, 11 * u, 27 * u, 31 * u, 180f, 180f, true, p)
        p.color = Theme.SNOW
        c.drawRoundRect(3 * u, 22.5f * u, 31 * u, 25.1f * u, 1.3f * u, 1.3f * u, p)
        p.alpha = 153
        c.drawRoundRect(9 * u, 27.5f * u, 25 * u, 29.7f * u, 1.1f * u, 1.1f * u, p)
        p.alpha = 255
    }
}

/** Round icon buttons drawn in code (no image files needed). */
class IconButton(context: android.content.Context, private val icon: Int, onClick: () -> Unit) : View(context) {
    companion object { const val MOON = 0; const val POWER = 1; const val KEYBOARD = 2 }
    var lit = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        setOnClickListener { tick(); onClick() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = w / 2
        p.style = Paint.Style.FILL
        p.color = if (lit) (Theme.accent and 0x00FFFFFF) or 0x40000000 else Theme.DEEP
        c.drawCircle(r, r, r - 1, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dp(1f)
        p.color = if (lit) Theme.accent else Theme.LINE
        c.drawCircle(r, r, r - context.dp(1f), p)
        p.strokeWidth = context.dp(2f)
        p.strokeCap = Paint.Cap.ROUND
        when (icon) {
            MOON -> {
                p.style = Paint.Style.FILL
                p.color = Theme.SNOW
                path.reset()
                path.addCircle(r, r, w * 0.2f, Path.Direction.CW)
                val cut = Path().apply { addCircle(r + w * 0.11f, r - w * 0.09f, w * 0.17f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, p)
            }
            POWER -> {
                p.color = Theme.CORAL
                c.drawArc(r - w * 0.18f, r - w * 0.16f, r + w * 0.18f, r + w * 0.2f, -60f, 300f, false, p)
                c.drawLine(r, r - w * 0.24f, r, r - w * 0.02f, p)
            }
            KEYBOARD -> {
                p.color = Theme.SNOW
                c.drawRoundRect(r - w * 0.24f, r - w * 0.14f, r + w * 0.24f, r + w * 0.14f, w * 0.05f, w * 0.05f, p)
                c.drawLine(r - w * 0.12f, r + w * 0.05f, r + w * 0.12f, r + w * 0.05f, p)
            }
        }
    }
}

/** A tab in the bottom bar / side rail, with a small drawn icon. */
class NavItem(context: android.content.Context, private val key: String, private val name: String, onClick: () -> Unit) : View(context) {
    var active = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { tick(); onClick() }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - context.dp(8f)
        val s = context.dp(10f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dp(1.8f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (active) Theme.accent else Theme.DIM
        when (key) {
            "remote" -> { c.drawRoundRect(cx - s * 0.5f, cy - s, cx + s * 0.5f, cy + s, s * 0.4f, s * 0.4f, p); c.drawCircle(cx, cy - s * 0.4f, s * 0.2f, p) }
            "channels" -> for (i in 0..3) {
                val x = cx - s + (i % 2) * s * 1.1f
                val y = cy - s + (i / 2) * s * 1.1f
                c.drawRoundRect(x, y, x + s * 0.85f, y + s * 0.85f, s * 0.2f, s * 0.2f, p)
            }
            "browser" -> { c.drawCircle(cx, cy, s, p); c.drawLine(cx - s, cy, cx + s, cy, p); c.drawOval(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy + s, p) }
            "send" -> { val path = Path(); path.moveTo(cx - s, cy); path.lineTo(cx + s, cy - s * 0.9f); path.lineTo(cx + s * 0.2f, cy + s); path.close(); c.drawPath(path, p) }
            else -> { c.drawLine(cx - s, cy - s * 0.4f, cx + s, cy - s * 0.4f, p); c.drawLine(cx - s, cy + s * 0.4f, cx + s, cy + s * 0.4f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx + s * 0.4f, cy - s * 0.4f, s * 0.22f, p); c.drawCircle(cx - s * 0.4f, cy + s * 0.4f, s * 0.22f, p) }
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = context.dp(11.5f)
        p.color = if (active) Theme.SNOW else Theme.DIM
        p.typeface = if (active) Fonts.semibold else Fonts.regular
        c.drawText(name, cx, height / 2f + context.dp(20f), p)
    }
}
