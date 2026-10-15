package io.github.jayswansocool48.deskmate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

object Theme {
    val BG = 0xFF000000.toInt()
    val SURFACE = 0xFF141416.toInt()
    val SURFACE2 = 0xFF1F1F23.toInt()
    val LINE = 0xFF2A2A2E.toInt()
    val TEXT = 0xFFECEEF4.toInt()
    val DIM = 0xFF9097A8.toInt()
    val ACCENT = 0xFF3D8BFF.toInt()
    val OK = 0xFF3DD68C.toInt()
    val WARN = 0xFFF2B84B.toInt()
    val BAD = 0xFFFF6B7A.toInt()

    val PAD = 0xFF7C8CFF.toInt()
    val MONITOR = 0xFF3DD6B5.toInt()
    val MEDIA = 0xFFFF7AA8.toInt()
    val NOTES = 0xFFF2B84B.toInt()
    val DEV = 0xFF5FB4FF.toInt()
}

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun parseColor(s: String?, fallback: Int): Int = try {
    if (s.isNullOrBlank()) fallback else Color.parseColor(s)
} catch (e: IllegalArgumentException) {
    fallback
}

fun Context.label(text: String, size: Float = 15f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) paint.isFakeBoldText = true
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** A rounded, pressable button with an optional icon and label. */
class PillButton(context: Context, var text: String, var icon: Icon? = null, var color: Int = Theme.SURFACE2) : View(context) {
    var textColor = Theme.TEXT
    var iconSize = context.dp(22f)
    var textSize = context.dp(15f)
    var radius = context.dp(14f)
    var active = false
        set(v) { field = v; invalidate() }
    var activeColor = Theme.ACCENT

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = text
    }

    fun update(text: String = this.text, icon: Icon? = this.icon) {
        this.text = text
        this.icon = icon
        contentDescription = text
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = if (active) activeColor else color
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (isPressed) {
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        val fg = if (active) Color.WHITE else textColor
        paint.textSize = textSize
        val tw = if (text.isEmpty()) 0f else paint.measureText(text)
        val ic = icon
        val gap = if (ic != null && text.isNotEmpty()) context.dp(8f) else 0f
        val iw = if (ic != null) iconSize else 0f
        // Stack icon above text when the button is tall and narrow.
        val stacked = ic != null && text.isNotEmpty() && (iw + gap + tw > width - context.dp(16f) || height > context.dp(76f))
        if (stacked) {
            val total = iw + context.dp(6f) + textSize
            val top = (height - total) / 2f
            Icons.draw(canvas, ic!!, width / 2f, top + iw / 2f, iw, fg, paint)
            drawText(canvas, fg, width / 2f, top + iw + context.dp(6f) + textSize * 0.8f, width - context.dp(12f))
        } else {
            val start = (width - (iw + gap + tw)) / 2f
            if (ic != null) Icons.draw(canvas, ic, start + iw / 2f, height / 2f, iw, fg, paint)
            if (text.isNotEmpty()) {
                drawText(canvas, fg, start + iw + gap + tw / 2f, height / 2f + textSize * 0.35f, width - context.dp(12f))
            }
        }
    }

    private fun drawText(canvas: Canvas, color: Int, cx: Float, baseline: Float, maxWidth: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        var t = text
        while (t.length > 1 && paint.measureText(t) > maxWidth) t = t.dropLast(2) + "…"
        canvas.drawText(t, cx, baseline, paint)
        paint.isFakeBoldText = false
        paint.textAlign = Paint.Align.LEFT
    }
}

/** Circular gauge showing a percentage. */
class GaugeView(context: Context, private val title: String, private val color: Int) : View(context) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var caption = ""
        set(v) { field = v; invalidate() }
    var available = true
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height - context.dp(22f).toInt()).toFloat()
        val stroke = size * 0.09f
        val cx = width / 2f
        val cy = size / 2f
        arc.set(cx - size / 2 + stroke, cy - size / 2 + stroke, cx + size / 2 - stroke, cy + size / 2 - stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Theme.SURFACE2
        canvas.drawArc(arc, 135f, 270f, false, paint)
        if (available) {
            paint.color = when {
                value > 90 -> Theme.BAD
                value > 75 -> Theme.WARN
                else -> color
            }
            canvas.drawArc(arc, 135f, 270f * value / 100f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Theme.TEXT
        paint.textSize = size * 0.24f
        paint.isFakeBoldText = true
        canvas.drawText(if (available) "${value.toInt()}%" else "–", cx, cy + size * 0.08f, paint)
        paint.isFakeBoldText = false
        paint.textSize = size * 0.1f
        paint.color = Theme.DIM
        canvas.drawText(caption, cx, cy + size * 0.24f, paint)
        paint.textSize = context.dp(14f)
        paint.color = Theme.TEXT
        canvas.drawText(title, cx, height - context.dp(4f), paint)
    }
}

/** Scrolling line graph of recent values, one or two series. */
class GraphView(context: Context, private val colorA: Int, private val colorB: Int = 0) : View(context) {
    private val capacity = 60
    private val a = ArrayDeque<Float>()
    private val b = ArrayDeque<Float>()
    var fixedMax = 0f // 0 = scale to data
    var label = ""
    var formatter: (Float) -> String = { "${it.toInt()}" }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun add(va: Float, vb: Float = 0f) {
        a.addLast(va)
        b.addLast(vb)
        while (a.size > capacity) a.removeFirst()
        while (b.size > capacity) b.removeFirst()
        invalidate()
    }

    fun clear() {
        a.clear()
        b.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(12f)
        paint.style = Paint.Style.FILL
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        val top = context.dp(30f)
        val pad = context.dp(10f)
        val gh = height - top - pad
        val gw = width - pad * 2
        var max = fixedMax
        if (max <= 0f) {
            max = 1f
            for (v in a) if (v > max) max = v
            if (colorB != 0) for (v in b) if (v > max) max = v
            max *= 1.15f
        }
        paint.color = Theme.LINE
        paint.strokeWidth = context.dp(1f)
        for (i in 1..3) {
            val y = top + gh * i / 4f
            canvas.drawLine(pad, y, pad + gw, y, paint)
        }
        fun series(values: ArrayDeque<Float>, color: Int) {
            if (values.size < 2) return
            path.reset()
            val step = gw / (capacity - 1)
            val x0 = pad + gw - step * (values.size - 1)
            values.forEachIndexed { i, v ->
                val x = x0 + step * i
                val y = top + gh - gh * (v / max).coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2.5f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = color
            canvas.drawPath(path, paint)
        }
        series(a, colorA)
        if (colorB != 0) series(b, colorB)
        paint.style = Paint.Style.FILL
        paint.textSize = context.dp(13f)
        paint.color = Theme.DIM
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, pad, context.dp(20f), paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatter(max), width - pad, context.dp(20f), paint)
        paint.textAlign = Paint.Align.LEFT
    }
}

/** One thin bar per CPU core. */
class CoreBarsView(context: Context) : View(context) {
    var values: FloatArray = FloatArray(0)
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(12f)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.textSize = context.dp(13f)
        paint.color = Theme.DIM
        canvas.drawText("Cores (${values.size})", context.dp(10f), context.dp(20f), paint)
        if (values.isEmpty()) return
        val top = context.dp(30f)
        val pad = context.dp(10f)
        val gap = context.dp(3f)
        val bw = (width - pad * 2 - gap * (values.size - 1)) / values.size
        val gh = height - top - pad
        for ((i, v) in values.withIndex()) {
            val x = pad + i * (bw + gap)
            paint.color = Theme.SURFACE2
            canvas.drawRect(x, top, x + bw, top + gh, paint)
            paint.color = if (v > 90) Theme.BAD else if (v > 75) Theme.WARN else Theme.MONITOR
            canvas.drawRect(x, top + gh * (1 - v / 100f), x + bw, top + gh, paint)
        }
    }
}

fun humanBytes(b: Double): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${v.toInt()} ${units[i]}" else String.format("%.1f %s", v, units[i])
}

/** Lays children out in equal columns that fit the width; each cell has a fixed height. */
class FlowGrid(context: Context, var minCell: Float, var cellHeight: Float, var gap: Float) : android.view.ViewGroup(context) {
    private var cols = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cols = ((w + gap) / (minCell + gap)).toInt().coerceAtLeast(1)
        val cw = ((w - gap * (cols - 1)) / cols).toInt()
        val ch = cellHeight.toInt()
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        val rows = (childCount + cols - 1) / cols
        setMeasuredDimension(w, if (rows == 0) 0 else (rows * ch + (rows - 1) * gap).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = ((width - gap * (cols - 1)) / cols)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = ((i % cols) * (cw + gap)).toInt()
            val y = ((i / cols) * (cellHeight + gap)).toInt()
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
