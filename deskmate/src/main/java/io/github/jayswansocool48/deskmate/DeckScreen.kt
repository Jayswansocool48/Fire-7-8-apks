package io.github.jayswansocool48.deskmate

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import org.json.JSONObject
import java.util.Date
import kotlin.math.roundToInt

/** The home screen: a Stream Deck-style grid of keys set up on the PC. */
class DeckScreen(app: MainActivity) : Screen(app) {
    override val title = "Deskmate"

    private val prefs = app.getSharedPreferences("deskmate-deck", Context.MODE_PRIVATE)
    private val view = DeckView(app)
    private val handler = Handler(Looper.getMainLooper())

    private var cols = 5
    private var rows = 3
    private var pages: List<Pair<String, List<DeckKey>>> = emptyList()
    private var pageIndex = prefs.getInt("page", 0)
    private val folders = ArrayList<DeckKey>()

    private var stats: JSONObject? = null
    private var media: JSONObject? = null
    private var statsOn = false
    private var shown = false

    private class TimerState(var lengthMs: Long, var endAt: Long = 0, var remaining: Long = lengthMs, var doneAt: Long = 0)
    private val timers = HashMap<String, TimerState>()

    private val messages: (JSONObject) -> Unit = { m ->
        when (m.optString("op")) {
            "deckChanged" -> fetch()
            "stats" -> {
                stats = m.optJSONObject("stats")
                view.invalidate()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (view.visibleKeys().any { it.type == "clock" || it.type == "timer" }) {
                checkTimers()
                view.invalidate()
            }
            handler.postDelayed(this, 500)
        }
    }

    private val mediaPoll = object : Runnable {
        override fun run() {
            refreshMedia()
            handler.postDelayed(this, 2500)
        }
    }

    init {
        addView(view, LayoutParams(MATCH, MATCH))
        DeckIcons.load(prefs)
        view.live = { liveFace(it) }
        view.listener = object : DeckView.Listener {
            override fun onKey(key: DeckKey) = press(key)
            override fun onLongKey(key: DeckKey) = longPress(key)
            override fun onSwipe(direction: Int) = swipe(direction)
            override fun onSettings() = settings()
        }
        prefs.getString("deck", null)?.let { showDeck(JSONObject(it), save = false) }
        refreshView()
    }

    override fun onShow() {
        shown = true
        DeskClient.onMessage(messages)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        handler.removeCallbacks(mediaPoll)
        handler.post(mediaPoll)
        if (DeskClient.online) fetch()
        updateSubscriptions()
    }

    override fun onHide() {
        shown = false
        DeskClient.removeMessage(messages)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(mediaPoll)
        if (statsOn) {
            DeskClient.send("stats", "on" to false)
            statsOn = false
        }
    }

    override fun onConnectionChanged() {
        if (DeskClient.online) {
            statsOn = false
            fetch()
        }
        refreshView()
    }

    override fun handleBack(): Boolean {
        if (folders.isEmpty()) return false
        folders.removeAt(folders.size - 1)
        view.slide(-1)
        refreshView()
        return true
    }

    // ------------------------------------------------------------ loading

    private fun fetch() {
        DeskClient.request("icons") { m ->
            m.optJSONObject("icons")?.let {
                DeckIcons.update(it, prefs)
                view.invalidate()
            }
        }
        DeskClient.request("deck") { m ->
            if (m.optString("op") == "deck") showDeck(m, save = true)
        }
    }

    private fun showDeck(m: JSONObject, save: Boolean) {
        cols = m.optInt("cols", 5)
        rows = m.optInt("rows", 3)
        val arr = m.optJSONArray("pages")
        val list = ArrayList<Pair<String, List<DeckKey>>>()
        if (arr != null) for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            list.add(p.optString("name") to DeckKey.parseList(p.optJSONArray("keys")))
        }
        pages = list
        if (pageIndex >= pages.size) pageIndex = 0
        // Re-find open folders by id in the new deck.
        val ids = folders.map { it.id }
        folders.clear()
        var keys = pages.getOrNull(pageIndex)?.second ?: emptyList()
        for (id in ids) {
            val f = keys.firstOrNull { it.id == id && it.type == "folder" } ?: break
            folders.add(f)
            keys = f.keys
        }
        if (save) prefs.edit().putString("deck", m.toString()).apply()
        refreshView()
    }

    private fun refreshView() {
        val keys = folders.lastOrNull()?.keys ?: pages.getOrNull(pageIndex)?.second ?: emptyList()
        view.setKeys(cols, rows, keys, folders.isNotEmpty())
        view.pageCount = pages.size
        view.pageIndex = pageIndex
        view.showDots = folders.isEmpty()
        view.offline = !DeskClient.online
        val (text, color) = when (DeskClient.state) {
            DeskClient.State.ONLINE -> DeskClient.pcName to Theme.OK
            DeskClient.State.CONNECTING -> "Connecting to ${DeskClient.pcName}…" to Theme.WARN
            DeskClient.State.NEED_PAIR -> "Waiting for pairing code" to Theme.WARN
            DeskClient.State.NO_PC -> "Not connected · tap ⚙" to Theme.DIM
            DeskClient.State.OFFLINE -> "Can't reach ${DeskClient.pcName}" to Theme.BAD
        }
        view.statusText = if (pages.isEmpty() && DeskClient.online) "Loading keys…" else text
        view.statusColor = color
        view.invalidate()
        updateSubscriptions()
    }

    private fun updateSubscriptions() {
        val want = shown && DeskClient.online && view.visibleKeys().any { it.type == "stat" }
        if (want != statsOn) {
            DeskClient.send("stats", "on" to want)
            statsOn = want
        }
    }

    private fun refreshMedia() {
        if (!shown || !DeskClient.online) return
        if (view.visibleKeys().none { it.type == "media" && it.value in setOf("playpause", "mute", "micmute") }) return
        DeskClient.request("mediaInfo") { m ->
            media = m.optJSONObject("info")
            view.invalidate()
        }
    }

    // ------------------------------------------------------------ live keys

    private fun liveFace(k: DeckKey): LiveFace? = when (k.type) {
        "stat" -> statFace(k)
        "clock" -> {
            val now = Date()
            val time = DateFormat.getTimeFormat(app).format(now).replace(" AM", "").replace(" PM", "")
            LiveFace(big = time, title = DateFormat.format("EEE d", now).toString())
        }
        "timer" -> {
            val t = timerFor(k)
            val left = if (t.endAt > 0) t.endAt - System.currentTimeMillis() else t.remaining
            val secs = ((left.coerceAtLeast(0) + 999) / 1000).toInt()
            val flashing = t.doneAt > 0 && (System.currentTimeMillis() - t.doneAt) < 6000 && (System.currentTimeMillis() / 500) % 2 == 0L
            LiveFace(
                big = String.format("%02d:%02d", secs / 60, secs % 60),
                progress = if (t.endAt > 0 || t.remaining < t.lengthMs) 1f - left.toFloat() / t.lengthMs else -1f,
                alert = flashing,
                title = if (t.endAt > 0) k.title else if (t.remaining in 1 until t.lengthMs) "Paused" else k.title,
            )
        }
        "media" -> mediaFace(k)
        else -> null
    }

    private fun statFace(k: DeckKey): LiveFace {
        val s = stats ?: return LiveFace(big = "–")
        fun pct(v: Double) = LiveFace(big = "${v.roundToInt()}%", progress = (v / 100).toFloat())
        return when (k.value) {
            "cpu" -> pct(s.optDouble("cpu"))
            "ram" -> {
                val t = s.optDouble("memTotal")
                if (t > 0) pct(100 * s.optDouble("memUsed") / t) else LiveFace(big = "–")
            }
            "gpu" -> s.optJSONObject("gpu")?.let { pct(it.optDouble("util")) } ?: LiveFace(big = "–")
            "gputemp" -> s.optJSONObject("gpu")?.let { LiveFace(big = "${it.optDouble("temp").roundToInt()}°") } ?: LiveFace(big = "–")
            "cputemp" -> s.optDouble("cpuTemp").let { if (it > 0) LiveFace(big = "${it.roundToInt()}°") else LiveFace(big = "–") }
            "net" -> LiveFace(big = shortRate(s.optDouble("netRx")))
            "disk" -> {
                val t = s.optDouble("diskTotal")
                val u = s.optDouble("diskUsed")
                LiveFace(big = shortSize(t - u), progress = if (t > 0) (u / t).toFloat() else -1f)
            }
            else -> LiveFace(big = "–")
        }
    }

    private fun mediaFace(k: DeckKey): LiveFace? {
        val m = media ?: return null
        return when (k.value) {
            "playpause" -> LiveFace(icon = if (m.optBoolean("playing")) "pause" else "play")
            "mute" -> {
                val v = m.optDouble("volume", -1.0)
                if (m.optBoolean("muted")) LiveFace(icon = "mute", alert = true, title = "Muted")
                else if (v >= 0) LiveFace(big = "${(v * 100).roundToInt()}%", progress = v.toFloat())
                else null
            }
            "micmute" -> if (m.optBoolean("micMuted")) LiveFace(icon = "mic-off", alert = true, title = "Mic off") else null
            else -> null
        }
    }

    private fun shortRate(bps: Double): String = shortSize(bps)

    private fun shortSize(b: Double): String {
        val units = arrayOf("B", "K", "M", "G", "T")
        var v = b
        var i = 0
        while (v >= 1000 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100 || i == 0) "${v.roundToInt()}${units[i]}" else String.format("%.1f%s", v, units[i])
    }

    private fun timerFor(k: DeckKey): TimerState {
        val len = (k.value.toIntOrNull() ?: 25).coerceIn(1, 600) * 60_000L
        return timers.getOrPut(k.id) { TimerState(len) }.also {
            if (it.lengthMs != len && it.endAt == 0L) { it.lengthMs = len; it.remaining = len }
        }
    }

    private fun checkTimers() {
        val now = System.currentTimeMillis()
        for (t in timers.values) {
            if (t.endAt in 1..now) {
                t.endAt = 0
                t.remaining = 0
                t.doneAt = now
                beep()
                app.toast("Time's up!")
            }
        }
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1800)
            handler.postDelayed({ tone.release() }, 2500)
        } catch (e: RuntimeException) {
        }
    }

    // ------------------------------------------------------------ actions

    private fun press(k: DeckKey) {
        when (k.type) {
            "back" -> handleBack()
            "folder" -> {
                folders.add(k)
                view.slide(1)
                refreshView()
            }
            "page" -> swipe(if (k.value == "prev") -1 else 1)
            "media" -> {
                if (!needOnline()) return
                when (k.value) {
                    "volumeup" -> DeskClient.send("volumeStep", "step" to 0.05)
                    "volumedown" -> DeskClient.send("volumeStep", "step" to -0.05)
                    "mute" -> DeskClient.send("mute", "mic" to false)
                    "micmute" -> DeskClient.send("mute", "mic" to true)
                    else -> DeskClient.send("media", "action" to k.value)
                }
                handler.postDelayed({ refreshMedia() }, 300)
            }
            "stat" -> app.push(MonitorScreen(app))
            "clock" -> {}
            "timer" -> {
                val t = timerFor(k)
                t.doneAt = 0
                if (t.endAt > 0) {
                    t.remaining = (t.endAt - System.currentTimeMillis()).coerceAtLeast(0)
                    t.endAt = 0
                } else {
                    if (t.remaining <= 0) t.remaining = t.lengthMs
                    t.endAt = System.currentTimeMillis() + t.remaining
                }
                view.invalidate()
            }
            "tool" -> when (k.value) {
                "monitor" -> app.push(MonitorScreen(app))
                "media" -> app.push(MediaScreen(app))
                "notes" -> app.push(NotesScreen(app))
                "dev" -> app.push(DevScreen(app))
            }
            else -> if (needOnline()) DeskClient.send("press", "id" to k.id)
        }
    }

    private fun longPress(k: DeckKey) {
        if (k.type == "timer") {
            timers[k.id]?.let { it.endAt = 0; it.remaining = it.lengthMs; it.doneAt = 0 }
            app.toast("Timer reset")
            view.invalidate()
        } else press(k)
    }

    private fun needOnline(): Boolean {
        if (DeskClient.online) return true
        app.toast(if (DeskClient.state == DeskClient.State.NO_PC) "Connect to your PC first (⚙ at the bottom right)" else "Not connected to ${DeskClient.pcName}")
        return false
    }

    private fun swipe(direction: Int) {
        if (folders.isNotEmpty() || pages.size < 2) return
        val next = pageIndex + direction
        if (next !in pages.indices) return
        pageIndex = next
        prefs.edit().putInt("page", pageIndex).apply()
        view.slide(direction)
        refreshView()
    }

    private fun settings() {
        val items = arrayOf(
            if (DeskClient.host == null) "Connect to your PC" else "PC: ${DeskClient.pcName} (change)",
            "System monitor",
            "Media & volume",
            "Notes, timer & clipboard",
            "Game Dev Panel",
            "How do I edit keys?",
        )
        AlertDialog.Builder(app)
            .setTitle("Deskmate")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> app.push(ConnectScreen(app))
                    1 -> app.push(MonitorScreen(app))
                    2 -> app.push(MediaScreen(app))
                    3 -> app.push(NotesScreen(app))
                    4 -> app.push(DevScreen(app))
                    5 -> AlertDialog.Builder(app)
                        .setTitle("Editing keys")
                        .setMessage("Keys are set up on your PC, like the Stream Deck app.\n\n" +
                            "Open Deskmate's settings page on the PC (it opens when Deskmate starts, or go to http://127.0.0.1:47801). " +
                            "Drag actions onto keys, pick icons or your own images, add pages and folders. " +
                            "Changes show up here instantly.\n\nOn the tablet: swipe left or right to change pages, and hold the timer key to reset it.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }
}
