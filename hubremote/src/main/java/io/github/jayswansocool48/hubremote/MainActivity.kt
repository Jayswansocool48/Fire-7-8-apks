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
import android.widget.Toast

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
    private var toast: Toast? = null
    private val listener: () -> Unit = { updateHeader() }

    // pending file-picker callbacks
    private var onPicked: ((List<Uri>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.NIGHT
        window.navigationBarColor = Theme.NIGHT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Hub.init(this)
        Hub.onError = { toast(it) }
        build()
        if (Hub.server.isEmpty()) show("connect") else show("remote")
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.onChange(listener)
        Hub.startPolling()
        screens[current]?.onShow()
    }

    override fun onPause() {
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
        header.addView(Mark(this), lp(dpi(30f), dpi(30f)))
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
        setContentView(root)
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
        s.alpha = 0f
        s.translationY = dp(10f)
        s.animate().alpha(1f).translationY(0f).setDuration(200).start()
        for ((k, t) in tabs) t.active = k == key
        s.onShow()
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

    fun toast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
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

    @Deprecated("Back on Android 7")
    override fun onBackPressed() {
        if (screens[current]?.onBack() == true) return
        if (current != "remote" && Hub.server.isNotEmpty()) show("remote") else finish()
    }
}

/** The four-square Hub mark, in the TV's accent color. */
class Mark(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = width * 0.44f
        val g = width * 0.12f
        val alphas = intArrayOf(255, 184, 128, 77)
        for (i in 0..3) {
            p.color = Theme.accent
            p.alpha = alphas[i]
            val x = (i % 2) * (s + g)
            val y = (i / 2) * (s + g)
            c.drawRoundRect(x, y, x + s, y + s, s * 0.3f, s * 0.3f, p)
        }
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
        p.isFakeBoldText = active
        c.drawText(name, cx, height / 2f + context.dp(20f), p)
    }
}
