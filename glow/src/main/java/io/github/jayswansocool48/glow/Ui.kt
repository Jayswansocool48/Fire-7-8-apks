package io.github.jayswansocool48.glow

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

object Theme {
    val BG = 0xFF0B0B10.toInt()
    val CARD = 0xFF15151C.toInt()
    val CARD2 = 0xFF1D1D26.toInt()
    val LINE = 0xFF2A2A35.toInt()
    val TEXT = 0xFFECEEF4.toInt()
    val DIM = 0xFF9097A8.toInt()
    val ACCENT = 0xFFFF8A3D.toInt()
    val PURPLE = 0xFF7A5CFF.toInt()
    val OK = 0xFF3DD68C.toInt()
    val WARN = 0xFFF2B84B.toInt()
}

val SWATCHES = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFFFFD6A0.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF3B30.toInt(), 0xFFFF2D95.toInt(),
    0xFFB36BFF.toInt(), 0xFF5F6BFF.toInt(), 0xFF1E9BFF.toInt(), 0xFF32D0FF.toInt(), 0xFF3DD6B5.toInt(),
    0xFF34C759.toInt(), 0xFFA8E000.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(),
)

fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)
fun parseHex(s: String?, fallback: Int = Color.WHITE): Int = try {
    if (s.isNullOrBlank()) fallback else Color.parseColor(s)
} catch (e: IllegalArgumentException) {
    fallback
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.label(text: String, size: Float = 15f, color: Int = Theme.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) paint.isFakeBoldText = true
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun Context.card() = vbox().apply {
    background = rounded(Theme.CARD, dp(18f), Theme.LINE, dpi(1f))
    val p = dpi(16f)
    setPadding(p, p, p, p)
}

/** A rounded text button. */
fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 15f
    gravity = Gravity.CENTER
    paint.isFakeBoldText = true
    setTextColor(if (primary) 0xFF1A0D00.toInt() else Theme.TEXT)
    background = rounded(if (primary) Theme.ACCENT else Theme.CARD2, dp(12f), if (primary) Theme.ACCENT else Theme.LINE, dpi(1f))
    val h = dpi(16f)
    val v = dpi(12f)
    setPadding(h, v, h, v)
    isClickable = true
    setOnClickListener { onClick() }
}

/** On/off switch drawn like the website's. */
class Toggle(context: Context) : View(context) {
    var on = false
        set(v) { field = v; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        setOnClickListener {
            on = !on
            onChange?.invoke(on)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(56f), context.dpi(32f))

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        p.color = if (on) Theme.OK else Theme.LINE
        c.drawRoundRect(r, height / 2f, height / 2f, p)
        p.color = Color.WHITE
        val rad = height / 2f - context.dp(3f)
        val cx = if (on) width - height / 2f else height / 2f
        c.drawCircle(cx, height / 2f, rad, p)
    }
}

/** Segmented tabs: Color | Effect | … */
class Tabs(context: Context, private val items: List<Pair<String, String>>, val onPick: (String) -> Unit) : LinearLayout(context) {
    private val views = HashMap<String, TextView>()
    var selected = ""
        set(v) {
            field = v
            for ((k, tv) in views) {
                tv.setTextColor(if (k == v) Theme.TEXT else Theme.DIM)
                tv.background = if (k == v) rounded(Theme.LINE, context.dp(10f)) else null
            }
        }

    init {
        orientation = HORIZONTAL
        background = rounded(Theme.CARD2, context.dp(13f))
        val p = context.dpi(4f)
        setPadding(p, p, p, p)
        for ((key, name) in items) {
            val tv = context.label(name, 14f, Theme.DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, context.dpi(11f), 0, context.dpi(11f))
                setOnClickListener { onPick(key) }
            }
            views[key] = tv
            addView(tv, lp(0, WRAP, 1f))
        }
    }
}

/** A labeled 0-100 slider that reports while dragging. */
class Slider(context: Context, name: String, max: Int = 100, val onValue: (Int) -> Unit) : LinearLayout(context) {
    private val bar = SeekBar(context)
    private val value = context.label("", 13f, Theme.DIM)
    var dragging = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(context.label(name, 14f, Theme.DIM), lp(context.dpi(96f), WRAP))
        bar.max = max
        bar.progressTintList = ColorStateList.valueOf(Theme.ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Theme.LINE)
        addView(bar, lp(0, context.dpi(44f), 1f))
        value.gravity = Gravity.END
        addView(value, lp(context.dpi(40f), WRAP))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                value.text = p.toString()
                if (fromUser) onValue(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { dragging = false; onValue(s.progress) }
        })
    }

    fun set(v: Int) {
        if (dragging) return
        bar.progress = v
        value.text = v.toString()
    }
}

/** Wraps children onto new lines, like CSS flex-wrap. */
class Flow(context: Context) : ViewGroup(context) {
    private val gap = context.dpi(8f)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun Context.chip(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTextColor(Theme.TEXT)
    val h = dpi(14f)
    val v = dpi(9f)
    setPadding(h, v, h, v)
    setOnClickListener { onClick() }
}

fun TextView.chipOn(on: Boolean) {
    background = rounded(if (on) Theme.ACCENT else Theme.CARD2, context.dp(99f), if (on) Theme.ACCENT else Theme.LINE, context.dpi(1f))
    setTextColor(if (on) 0xFF1A0D00.toInt() else Theme.TEXT)
    paint.isFakeBoldText = on
}

/** Round color dots in a row that wraps. */
class Swatches(context: Context, val onPick: (Int) -> Unit) : View(context) {
    var selected = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val size = context.dp(40f)
    private val gap = context.dp(10f)

    private fun perRow(w: Int) = maxOf(1, ((w + gap) / (size + gap)).toInt())

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val rows = (SWATCHES.size + perRow(w) - 1) / perRow(w)
        setMeasuredDimension(w, (rows * (size + gap)).toInt())
    }

    override fun onDraw(c: Canvas) {
        val n = perRow(width)
        for ((i, col) in SWATCHES.withIndex()) {
            val cx = (i % n) * (size + gap) + size / 2
            val cy = (i / n) * (size + gap) + size / 2
            p.style = Paint.Style.FILL
            p.color = col
            c.drawCircle(cx, cy, size / 2 - context.dp(3f), p)
            if ((col and 0xFFFFFF) == (selected and 0xFFFFFF)) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.dp(2.5f)
                p.color = Color.WHITE
                c.drawCircle(cx, cy, size / 2 - context.dp(1f), p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val n = perRow(width)
            val col = (e.x / (size + gap)).toInt()
            val row = (e.y / (size + gap)).toInt()
            val i = row * n + col
            if (col < n && i in SWATCHES.indices) {
                selected = SWATCHES[i]
                onPick(SWATCHES[i])
            }
        }
        return true
    }
}

fun Context.spacer(h: Float) = View(this).apply { layoutParams = lp(MATCH, dpi(h)) }
