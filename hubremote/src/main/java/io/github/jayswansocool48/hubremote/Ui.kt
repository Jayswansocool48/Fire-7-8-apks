package io.github.jayswansocool48.hubremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

/** Same palette as the Hub control page: a blue-violet night, with the TV's accent color. */
object Theme {
    val NIGHT = 0xFF0E1024.toInt()
    val DEEP = 0xFF171A36.toInt()
    val DEEPER = 0xFF12142C.toInt()
    val RAISE = 0xFF21254A.toInt()
    val LINE = 0xFF2C315C.toInt()
    val MIST = 0xFFA9B0D6.toInt()
    val DIM = 0xFF7880AA.toInt()
    val SNOW = 0xFFF3F4FF.toInt()
    val CORAL = 0xFFFF6B6B.toInt()
    val OK = 0xFF4BD6A0.toInt()
    val ACCENT_DEFAULT = 0xFF4F7CFF.toInt()
    val accent get() = Hub.accent
}

val ACCENTS = listOf("#4F7CFF", "#7C5CFF", "#E85DBA", "#FF6B6B", "#FF9F43", "#F5C542", "#4BD6A0", "#2EC4E6")

fun parseColor(hex: String, fallback: Int = Theme.ACCENT_DEFAULT): Int = try { Color.parseColor(hex) } catch (e: Exception) { fallback }

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.label(text: String, size: Float = 15f, color: Int = Theme.SNOW, bold: Boolean = false) = TextView(this).apply {
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

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

/** A rounded button. Primary ones use the TV's accent color. */
fun Context.button(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 15f
    gravity = Gravity.CENTER
    paint.isFakeBoldText = true
    setTextColor(if (danger) Theme.CORAL else Theme.SNOW)
    background = if (primary) rounded(Theme.accent, dp(12f)) else rounded(Theme.RAISE, dp(12f), Theme.LINE, dpi(1f))
    val h = dpi(16f)
    val v = dpi(12f)
    setPadding(h, v, h, v)
    isClickable = true
    setOnClickListener { tick(); onClick() }
}

fun Context.input(hint: String, text: String = "", url: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(text)
    textSize = 15f
    setTextColor(Theme.SNOW)
    setHintTextColor(Theme.DIM)
    isSingleLine = true
    inputType = if (url) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
    background = rounded(Theme.DEEPER, dp(10f), Theme.LINE, dpi(1f))
    val p = dpi(12f)
    setPadding(p, p, p, p)
}

/** An input with a button next to it. */
fun Context.field(hint: String, action: String, text: String = "", url: Boolean = false, primary: Boolean = false, onGo: (String, EditText) -> Unit): LinearLayout {
    val row = hbox()
    val e = input(hint, text, url)
    row.addView(e, lp(0, WRAP, 1f))
    row.addView(button(action, primary) { onGo(e.text.toString().trim(), e) }, lp(WRAP, WRAP).apply { leftMargin = dpi(8f) })
    e.setOnEditorActionListener { _, _, _ -> onGo(e.text.toString().trim(), e); true }
    return row
}

/** A list box with rows separated by hairlines. */
class ListBox(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = rounded(Theme.DEEP, context.dp(14f), Theme.LINE, context.dpi(1f))
        clipToOutline = true
    }

    fun row(title: String, sub: String? = null, left: View? = null, right: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(Theme.LINE) }, lp(MATCH, 1))
        val r = context.hbox()
        val p = context.dpi(14f)
        r.setPadding(p, context.dpi(10f), p, context.dpi(10f))
        r.minimumHeight = context.dpi(56f)
        if (left != null) r.addView(left, lp(WRAP, WRAP).apply { rightMargin = context.dpi(12f) })
        val t = context.vbox()
        t.addView(context.label(title, 15f, Theme.SNOW, bold = false).apply { maxLines = 2 })
        if (sub != null) t.addView(context.label(sub, 13f, Theme.DIM))
        r.addView(t, lp(0, WRAP, 1f))
        if (right != null) r.addView(right, lp(WRAP, WRAP).apply { leftMargin = context.dpi(8f) })
        if (onClick != null) {
            r.isClickable = true
            r.setOnClickListener { r.tick(); onClick() }
        }
        addView(r, lp(MATCH, WRAP))
        return r
    }

    fun clear() = removeAllViews()
}

/** On/off switch in the accent color. */
class Toggle(context: Context) : View(context) {
    var on = false
        set(v) { field = v; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { tick(); on = !on; onChange?.invoke(on) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(50f), context.dpi(30f))

    override fun onDraw(c: Canvas) {
        p.color = if (on) Theme.accent else Theme.LINE
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, p)
        p.color = Color.WHITE
        val r = height / 2f - context.dp(3f)
        c.drawCircle(if (on) width - height / 2f else height / 2f, height / 2f, r, p)
    }
}

/** Pill-shaped choice chips that wrap onto new lines. */
class Chips(context: Context) : ViewGroup(context) {
    private val gap = context.dpi(8f)
    private val items = ArrayList<Pair<String, TextView>>()
    var onPick: ((String) -> Unit)? = null

    fun add(key: String, text: String) {
        val tv = context.label(text, 14f).apply {
            val h = context.dpi(15f)
            val v = context.dpi(9f)
            setPadding(h, v, h, v)
            setOnClickListener { tick(); select(key); onPick?.invoke(key) }
        }
        items.add(key to tv)
        addView(tv)
        select(null)
    }

    fun select(key: String?) {
        for ((k, tv) in items) {
            val on = k == key
            tv.background = if (on) rounded(Theme.accent, context.dp(99f)) else rounded(Theme.RAISE, context.dp(99f), Theme.LINE, context.dpi(1f))
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
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
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** Lays children out in equal columns, as many as fit [minCell]. */
class Grid(context: Context, private val minCell: Int, private val fixedCols: Int = 0) : ViewGroup(context) {
    private val gap = context.dpi(12f)

    private fun cols(w: Int) = if (fixedCols > 0) fixedCols else maxOf(1, (w + gap) / (minCell + gap))

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val n = cols(w)
        val cell = (w - gap * (n - 1)) / n
        var h = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
            rowH = maxOf(rowH, c.measuredHeight)
            if (i % n == n - 1 || i == childCount - 1) { h += rowH + gap; rowH = 0 }
        }
        setMeasuredDimension(w, maxOf(0, h - gap))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val n = cols(w)
        val cell = (w - gap * (n - 1)) / n
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (i % n) * (cell + gap)
            c.layout(x, y, x + cell, y + c.measuredHeight)
            rowH = maxOf(rowH, c.measuredHeight)
            if (i % n == n - 1) { y += rowH + gap; rowH = 0 }
        }
    }
}

/** Keeps a fixed width:height ratio (for thumbnails and the browser view). */
class Aspect(context: Context, private val ratio: Float) : android.widget.FrameLayout(context) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = (w / ratio).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

fun Context.spacer(h: Float) = View(this).apply { layoutParams = lp(MATCH, dpi(h)) }
