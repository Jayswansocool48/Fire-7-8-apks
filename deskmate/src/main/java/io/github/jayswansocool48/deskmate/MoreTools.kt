package io.github.jayswansocool48.deskmate

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Notes, Timer & Clipboard

class NotesScreen(app: MainActivity) : Screen(app) {
    override val title = "Notes & Timer"
    override val accent = Theme.NOTES
    private val prefs = app.getSharedPreferences("deskmate-notes", Context.MODE_PRIVATE)
    private val outer = LinearLayout(app)
    private val notesCard = app.vbox()
    private val side = app.vbox()
    private val notes = EditText(app)
    private val clock = app.label("25:00", 72f, Theme.TEXT, bold = true)
    private val startBtn = PillButton(app, "Start", Icon.PLAY, Theme.NOTES).apply { textColor = 0xFF1B1D24.toInt() }
    private val phase = app.label("Focus", 14f, Theme.DIM)
    private val handler = Handler(Looper.getMainLooper())

    // Timer state survives leaving the screen: either a running end time or a paused remainder.
    private var lengthMs = prefs.getLong("length", 25 * 60_000L)
    private var endAt = prefs.getLong("endAt", 0L)
    private var remaining = prefs.getLong("remaining", lengthMs)
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (endAt > 0) handler.postDelayed(this, 250)
        }
    }

    init {
        // Notes
        notesCard.background = rounded(Theme.SURFACE, app.dp(18f))
        val p = app.dpi(16f)
        notesCard.setPadding(p, p, p, p)
        notesCard.addView(app.heading("Notes"))
        notes.apply {
            setText(prefs.getString("notes", ""))
            hint = "Ideas, to-dos, bugs to fix…"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { prefs.edit().putString("notes", s?.toString() ?: "").apply() }
            })
        }
        notesCard.addView(notes, lp(MATCH, 0, 1f))

        // Timer
        val timer = app.vbox().apply {
            background = rounded(Theme.SURFACE, app.dp(18f))
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val th = app.hbox()
        th.addView(app.heading("Focus timer"), lp(0, WRAP, 1f))
        th.addView(phase)
        timer.addView(th, lp(MATCH, WRAP))
        clock.gravity = Gravity.CENTER
        clock.typeface = android.graphics.Typeface.MONOSPACE
        timer.addView(clock, lp(MATCH, WRAP))
        val presets = app.hbox()
        for ((mins, name) in listOf(25 to "Focus", 15 to "Long break", 5 to "Break")) {
            val b = PillButton(app, "$mins min").apply { textSize = app.dp(14f) }
            b.setOnClickListener { setLength(mins, name) }
            presets.addView(b, lp(0, app.dpi(40f), 1f).apply { leftMargin = app.dpi(4f); rightMargin = app.dpi(4f) })
        }
        timer.addView(presets, lp(MATCH, WRAP))
        val controls = app.hbox()
        startBtn.setOnClickListener { if (endAt > 0) pause() else start() }
        val reset = PillButton(app, "Reset", Icon.REFRESH)
        reset.setOnClickListener { reset() }
        controls.addView(startBtn, lp(0, app.dpi(52f), 1f).apply { rightMargin = app.dpi(6f) })
        controls.addView(reset, lp(0, app.dpi(52f), 1f).apply { leftMargin = app.dpi(6f) })
        timer.addView(controls, lp(MATCH, WRAP).apply { topMargin = app.dpi(10f) })
        side.addView(timer, lp(MATCH, WRAP))

        // Clipboard
        val clip = app.vbox().apply {
            background = rounded(Theme.SURFACE, app.dp(18f))
            setPadding(p, p, p, p)
        }
        clip.addView(app.heading("Clipboard"))
        val sendNotes = PillButton(app, "Send notes to PC", Icon.UPLOAD)
        val sendClip = PillButton(app, "Send tablet clipboard to PC", Icon.UPLOAD)
        val getClip = PillButton(app, "Get PC clipboard", Icon.DOWNLOAD)
        sendNotes.setOnClickListener {
            val s = notes.selectionStart
            val e = notes.selectionEnd
            val text = if (s in 0 until e) notes.text.substring(s, e) else notes.text.toString()
            sendToPc(text)
        }
        sendClip.setOnClickListener {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
            if (text.isNullOrEmpty()) app.toast("The tablet's clipboard is empty") else sendToPc(text)
        }
        getClip.setOnClickListener { fetchFromPc() }
        for (b in listOf(sendNotes, sendClip, getClip)) {
            b.textSize = app.dp(14f)
            clip.addView(b, lp(MATCH, app.dpi(48f)).apply { topMargin = app.dpi(8f) })
        }
        side.addView(clip, lp(MATCH, WRAP).apply { topMargin = app.dpi(14f) })

        outer.addView(notesCard)
        outer.addView(side)
        pad(outer, 16f)
        addView(outer)
        layoutForOrientation()
        updateClock()
    }

    private fun layoutForOrientation() {
        val landscape = app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        outer.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (landscape) {
            notesCard.layoutParams = lp(0, MATCH, 1f)
            side.layoutParams = lp(app.dpi(330f), MATCH).apply { leftMargin = app.dpi(14f) }
        } else {
            notesCard.layoutParams = lp(MATCH, 0, 1f)
            side.layoutParams = lp(MATCH, WRAP).apply { topMargin = app.dpi(14f) }
        }
    }

    override fun onOrientationChanged() = layoutForOrientation()
    override fun onShow() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onHide() { handler.removeCallbacks(tick); save() }

    private fun save() {
        prefs.edit().putLong("length", lengthMs).putLong("endAt", endAt).putLong("remaining", remaining).apply()
    }

    private fun setLength(mins: Int, name: String) {
        lengthMs = mins * 60_000L
        endAt = 0
        remaining = lengthMs
        phase.text = name
        save()
        updateClock()
    }

    private fun start() {
        if (remaining <= 0) remaining = lengthMs
        endAt = System.currentTimeMillis() + remaining
        save()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun pause() {
        remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
        endAt = 0
        save()
        updateClock()
    }

    private fun reset() {
        endAt = 0
        remaining = lengthMs
        save()
        updateClock()
    }

    private fun updateClock() {
        val left = if (endAt > 0) endAt - System.currentTimeMillis() else remaining
        if (endAt > 0 && left <= 0) {
            endAt = 0
            remaining = 0
            save()
            finished()
        }
        val secs = ((left.coerceAtLeast(0) + 999) / 1000).toInt()
        clock.text = String.format("%02d:%02d", secs / 60, secs % 60)
        startBtn.update(if (endAt > 0) "Pause" else if (remaining in 1 until lengthMs) "Resume" else "Start", if (endAt > 0) Icon.PAUSE else Icon.PLAY)
    }

    private fun finished() {
        clock.text = "00:00"
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1800)
            handler.postDelayed({ tone.release() }, 2500)
        } catch (e: RuntimeException) {
        }
        app.toast("Time's up!")
        clock.setTextColor(Theme.NOTES)
        handler.postDelayed({ clock.setTextColor(Theme.TEXT) }, 4000)
    }

    private fun sendToPc(text: String) {
        if (!DeskClient.online) { app.toast("Not connected to your PC"); return }
        if (text.isEmpty()) { app.toast("Nothing to send"); return }
        DeskClient.request("clipSet", "text" to text) { m ->
            if (m.optString("op") == "ok") app.toast("Copied on your PC. Paste it there with Ctrl+V.")
        }
    }

    private fun fetchFromPc() {
        if (!DeskClient.online) { app.toast("Not connected to your PC"); return }
        DeskClient.request("clipGet") { m ->
            if (m.optString("op") != "clip") return@request
            val text = m.optString("text")
            if (text.isEmpty()) { app.toast("Your PC's clipboard is empty"); return@request }
            AlertDialog.Builder(app)
                .setTitle("PC clipboard")
                .setMessage(if (text.length > 600) text.take(600) + "…" else text)
                .setPositiveButton("Add to notes") { _, _ ->
                    val cur = notes.text.toString()
                    notes.setText(if (cur.isEmpty()) text else "$cur\n$text")
                    notes.setSelection(notes.text.length)
                }
                .setNeutralButton("Copy on tablet") { _, _ ->
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("From PC", text))
                    app.toast("Copied")
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}

// ---------------------------------------------------------------- Game Dev Panel

class DevScreen(app: MainActivity) : Screen(app) {
    override val title = "Game Dev Panel"
    override val accent = Theme.DEV
    override val actions = listOf<Pair<Icon, () -> Unit>>(Icon.PLUS to { addControl() }, Icon.EDIT to { editing = !editing; render() })
    private val prefs = app.getSharedPreferences("deskmate-dev", Context.MODE_PRIVATE)
    private val banner = offlineBanner()
    private val list = app.vbox()
    private var controls = JSONArray(prefs.getString("controls", null) ?: DEFAULT)
    private var editing = false

    init {
        val col = app.vbox()
        pad(col, 16f)
        col.addView(banner, lp(MATCH, WRAP).apply { bottomMargin = app.dpi(12f) })
        col.addView(app.label("Each change is sent to your game as {\"name\": …, \"value\": …} over UDP on the PC (port 47810 by default, set in Deskmate's settings). Tap + to add controls.", 13f, Theme.DIM),
            lp(MATCH, WRAP).apply { bottomMargin = app.dpi(12f) })
        col.addView(list, lp(MATCH, WRAP))
        addView(scroll(col))
        render()
    }

    override fun onConnectionChanged() = refreshBanner(banner)

    private fun save() = prefs.edit().putString("controls", controls.toString()).apply()

    private fun send(name: String, value: Any) {
        DeskClient.send("dev", "name" to name, "value" to value)
    }

    private fun render() {
        list.removeAllViews()
        if (controls.length() == 0) {
            list.addView(app.label("No controls yet. Tap + at the top to add a slider, switch or button.", 15f, Theme.DIM))
        }
        for (i in 0 until controls.length()) {
            val c = controls.optJSONObject(i) ?: continue
            list.addView(controlView(i, c), lp(MATCH, WRAP).apply { bottomMargin = app.dpi(10f) })
        }
    }

    private fun controlView(index: Int, c: JSONObject): View {
        val card = app.vbox().apply {
            background = rounded(Theme.SURFACE, app.dp(16f))
            val p = app.dpi(14f)
            setPadding(p + p / 3, p, p, p)
        }
        val name = c.optString("name")
        val head = app.hbox()
        head.addView(app.label(name, 17f, Theme.TEXT, bold = true), lp(0, WRAP, 1f))
        val valueLabel = app.label("", 16f, Theme.DEV)
        head.addView(valueLabel)
        if (editing) {
            val del = PillButton(app, "", Icon.TRASH)
            del.setOnClickListener {
                controls.remove(index)
                save()
                render()
            }
            head.addView(del, lp(app.dpi(44f), app.dpi(40f)).apply { leftMargin = app.dpi(10f) })
        }
        card.addView(head)

        when (c.optString("type")) {
            "slider" -> {
                val min = c.optDouble("min", 0.0)
                val max = c.optDouble("max", 1.0)
                val whole = min == Math.floor(min) && max == Math.floor(max) && max - min <= 1000
                val steps = if (whole) (max - min).toInt().coerceAtLeast(1) else 1000
                fun valueAt(p: Int): Double {
                    val v = min + (max - min) * p / steps
                    return if (whole) v.roundToInt().toDouble() else (v * 1000).roundToInt() / 1000.0
                }
                fun fmt(v: Double) = if (whole) v.toInt().toString() else String.format("%.3f", v).trimEnd('0').trimEnd('.')
                val sb = SeekBar(app)
                sb.max = steps
                val start = c.optDouble("value", min)
                sb.progress = (((start - min) / (max - min).coerceAtLeast(1e-9)) * steps).roundToInt()
                valueLabel.text = fmt(valueAt(sb.progress))
                sb.progressTintList = ColorStateList.valueOf(Theme.DEV)
                sb.thumbTintList = ColorStateList.valueOf(Theme.DEV)
                var last = 0L
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        val v = valueAt(p)
                        valueLabel.text = fmt(v)
                        val now = System.currentTimeMillis()
                        if (fromUser && now - last > 40) {
                            last = now
                            send(name, if (whole) v.toInt() else v)
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        val v = valueAt(s.progress)
                        send(name, if (whole) v.toInt() else v)
                        c.put("value", v)
                        save()
                    }
                })
                card.addView(sb, lp(MATCH, app.dpi(48f)))
                val range = app.hbox()
                range.addView(app.label(fmt(min), 12f, Theme.DIM), lp(0, WRAP, 1f))
                range.addView(app.label(fmt(max), 12f, Theme.DIM))
                card.addView(range)
            }
            "toggle" -> {
                val on = c.optBoolean("value", false)
                val b = PillButton(app, if (on) "On" else "Off", if (on) Icon.CHECK else null).apply {
                    active = on
                    activeColor = Theme.DEV
                }
                b.setOnClickListener {
                    val v = !c.optBoolean("value", false)
                    c.put("value", v)
                    save()
                    b.active = v
                    b.update(if (v) "On" else "Off", if (v) Icon.CHECK else null)
                    send(name, v)
                }
                card.addView(b, lp(MATCH, app.dpi(52f)).apply { topMargin = app.dpi(8f) })
            }
            else -> {
                val b = PillButton(app, "Send", Icon.PLAY, Theme.DEV).apply { textColor = 0xFFFFFFFF.toInt() }
                b.setOnClickListener {
                    b.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    send(name, true)
                }
                card.addView(b, lp(MATCH, app.dpi(52f)).apply { topMargin = app.dpi(8f) })
            }
        }
        return card
    }

    private fun addControl() {
        val box = app.vbox().apply {
            val p = app.dpi(20f)
            setPadding(p, app.dpi(8f), p, 0)
        }
        fun field(hint: String, numeric: Boolean): EditText = EditText(app).apply {
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val name = field("Name your game listens for, e.g. speed", false)
        box.addView(name)
        val types = RadioGroup(app).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = listOf("slider" to "Slider", "toggle" to "Switch", "button" to "Button")
        ids.forEachIndexed { i, (_, label) ->
            types.addView(RadioButton(app).apply { text = label; id = 100 + i })
        }
        types.check(100)
        box.addView(types)
        val minF = field("Min (default 0)", true)
        val maxF = field("Max (default 1)", true)
        box.addView(minF)
        box.addView(maxF)
        types.setOnCheckedChangeListener { _, id ->
            val slider = id == 100
            minF.visibility = if (slider) View.VISIBLE else View.GONE
            maxF.visibility = if (slider) View.VISIBLE else View.GONE
        }
        AlertDialog.Builder(app)
            .setTitle("Add control")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { app.toast("Give the control a name"); return@setPositiveButton }
                val type = ids[(types.checkedRadioButtonId - 100).coerceIn(0, 2)].first
                val c = JSONObject().put("name", n).put("type", type)
                if (type == "slider") {
                    val mn = minF.text.toString().toDoubleOrNull() ?: 0.0
                    var mx = maxF.text.toString().toDoubleOrNull() ?: 1.0
                    if (mx <= mn) mx = mn + 1
                    c.put("min", mn).put("max", mx).put("value", mn)
                }
                controls.put(c)
                save()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val DEFAULT = """[
          {"name":"speed","type":"slider","min":0,"max":20,"value":5},
          {"name":"gravity","type":"slider","min":0,"max":2,"value":1},
          {"name":"god_mode","type":"toggle","value":false},
          {"name":"spawn_enemy","type":"button"}
        ]"""
    }
}

