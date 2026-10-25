package io.github.jayswansocool48.haven

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

/** One pastel look for the whole home screen. */
class HavenTheme(
    val id: String,
    val name: String,
    val top: Int,
    val bottom: Int,
    val blobs: IntArray,
    val text: Int,
    val dim: Int,
    val accent: Int,
    /** Settings sheet background. */
    val panel: Int,
    /** Round header buttons and unfocused rows. */
    val glass: Int,
    val tiles: IntArray,
    val dark: Boolean = false,
)

private fun c(v: Long) = v.toInt()

val THEMES = listOf(
    HavenTheme(
        "lavender", "Lavender Dream",
        c(0xFFF1EBFF), c(0xFFFCEAF3),
        intArrayOf(c(0xFFD9CBFF), c(0xFFFFD0E5), c(0xFFCDF2E6)),
        c(0xFF2F2847), c(0xFF8A81A3), c(0xFF8467F5), c(0xFFFBF9FF), c(0x99FFFFFF),
        intArrayOf(c(0xFFE6DDFF), c(0xFFFFE0EE), c(0xFFDDF5EC), c(0xFFFFEBD9), c(0xFFDFEDFF), c(0xFFFFF4CF)),
    ),
    HavenTheme(
        "mint", "Mint Soda",
        c(0xFFE6F8F1), c(0xFFEAF1FF),
        intArrayOf(c(0xFFBFEFDD), c(0xFFCFE0FF), c(0xFFFFF3C4)),
        c(0xFF1F3B37), c(0xFF6E8C87), c(0xFF22A884), c(0xFFF8FDFB), c(0x99FFFFFF),
        intArrayOf(c(0xFFD3F4E8), c(0xFFDEEAFF), c(0xFFFFF6D3), c(0xFFEAE2FF), c(0xFFFFE3EC), c(0xFFDDF6F7)),
    ),
    HavenTheme(
        "peach", "Peach Fizz",
        c(0xFFFFEFE5), c(0xFFFFF7DD),
        intArrayOf(c(0xFFFFD3BE), c(0xFFFFE9A8), c(0xFFFFCCDA)),
        c(0xFF43302A), c(0xFF9A7B6E), c(0xFFF2775A), c(0xFFFFFCF9), c(0x99FFFFFF),
        intArrayOf(c(0xFFFFE0D1), c(0xFFFFF0BF), c(0xFFFFDCE5), c(0xFFE0F2D3), c(0xFFFFE8D9), c(0xFFEDE5FF)),
    ),
    HavenTheme(
        "cotton", "Cotton Candy",
        c(0xFFFFE8F2), c(0xFFE8EFFF),
        intArrayOf(c(0xFFFFC6DF), c(0xFFC7D8FF), c(0xFFE8CCFF)),
        c(0xFF3A2B45), c(0xFF8E7A9C), c(0xFFEE5F9E), c(0xFFFFFAFC), c(0x99FFFFFF),
        intArrayOf(c(0xFFFFDAEA), c(0xFFDAE4FF), c(0xFFF0DEFF), c(0xFFD9FFF6), c(0xFFFFF3D9), c(0xFFFFE1DA)),
    ),
    HavenTheme(
        "night", "Starry Night",
        c(0xFF1E1A30), c(0xFF2C2240),
        intArrayOf(c(0xFF4B3A8F), c(0xFF7A3D6B), c(0xFF2D5F63)),
        c(0xFFF4EFFF), c(0xFFA79FC0), c(0xFFB59BFF), c(0xFF2A2440), c(0x26FFFFFF),
        intArrayOf(c(0xFF3A3160), c(0xFF4A3150), c(0xFF294650), c(0xFF33395E), c(0xFF4A3D33), c(0xFF3D3150)),
        dark = true,
    ),
)

fun themeById(id: String?) = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

object Fonts {
    private var medium: Typeface? = null
    private var bold: Typeface? = null
    fun medium(ctx: Context): Typeface = medium ?: load(ctx, "fonts/Poppins-Medium.ttf", Typeface.DEFAULT).also { medium = it }
    fun bold(ctx: Context): Typeface = bold ?: load(ctx, "fonts/Poppins-Bold.ttf", Typeface.DEFAULT_BOLD).also { bold = it }
    private fun load(ctx: Context, path: String, fallback: Typeface) = try {
        Typeface.createFromAsset(ctx.assets, path)
    } catch (e: RuntimeException) {
        fallback
    }
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun oval(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun withAlpha(color: Int, alpha: Int) = color and 0x00FFFFFF or (alpha shl 24)

fun Context.text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = if (bold) Fonts.bold(this@text) else Fonts.medium(this@text)
    includeFontPadding = false
}

fun Context.icon(res: Int, color: Int) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    scaleType = ImageView.ScaleType.FIT_CENTER
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** The gentle grow-on-focus used by cards and round buttons. */
fun bounce(v: View, focused: Boolean, scale: Float = 1.1f) {
    v.animate().cancel()
    v.animate()
        .scaleX(if (focused) scale else 1f)
        .scaleY(if (focused) scale else 1f)
        .setInterpolator(if (focused) OvershootInterpolator(1.6f) else DecelerateInterpolator())
        .setDuration(if (focused) 240 else 160)
        .start()
    v.elevation = if (focused) v.context.dp(14f) else 0f
}

/** A round icon button for the top of the home screen. [onFocus] tells the header which one is selected. */
fun Context.roundButton(res: Int, t: HavenTheme, onFocus: (Boolean) -> Unit, onClick: () -> Unit): FrameLayout {
    val size = dpi(54f)
    val b = FrameLayout(this)
    val img = icon(res, t.text)
    b.addView(img, FrameLayout.LayoutParams(dpi(26f), dpi(26f), Gravity.CENTER))
    b.background = oval(t.glass)
    b.isFocusable = true
    b.isClickable = true
    b.layoutParams = LinearLayout.LayoutParams(size, size)
    b.setOnFocusChangeListener { v, has ->
        v.background = oval(if (has) t.accent else t.glass)
        img.imageTintList = ColorStateList.valueOf(if (has) 0xFFFFFFFF.toInt() else t.text)
        bounce(v, has, 1.14f)
        onFocus(has)
    }
    b.setOnClickListener { onClick() }
    return b
}

/**
 * A row in the settings sheet: icon (or any leading view), label, and a value on the right.
 * Selected rows fill with the accent color.
 */
class SheetRow(ctx: Context, private val t: HavenTheme, lead: View?, label: String, value: String?, onClick: () -> Unit) : LinearLayout(ctx) {
    val label: TextView = ctx.text(label, 17f, t.text)
    val value: TextView? = value?.let { ctx.text(it, 15f, t.dim) }
    private val lead = lead

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val ph = ctx.dpi(18f)
        val pv = ctx.dpi(15f)
        setPadding(ph, pv, ph, pv)
        isFocusable = true
        isClickable = true
        if (lead != null) addView(lead, LayoutParams(ctx.dpi(22f), ctx.dpi(22f)).apply { rightMargin = ctx.dpi(16f) })
        this.label.maxLines = 1
        this.label.ellipsize = TextUtils.TruncateAt.END
        addView(this.label, LayoutParams(0, WRAP, 1f))
        this.value?.let { addView(it, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dpi(12f) }) }
        setOnFocusChangeListener { _, has -> paint(has) }
        setOnClickListener { onClick() }
        paint(false)
    }

    private fun paint(focused: Boolean) {
        background = if (focused) rounded(t.accent, context.dp(16f)) else null
        label.setTextColor(if (focused) 0xFFFFFFFF.toInt() else t.text)
        label.typeface = if (focused) Fonts.bold(context) else Fonts.medium(context)
        value?.setTextColor(if (focused) withAlpha(0xFFFFFFFF.toInt(), 0xD0) else t.dim)
        (lead as? ImageView)?.imageTintList = ColorStateList.valueOf(if (focused) 0xFFFFFFFF.toInt() else t.dim)
    }
}

/**
 * The pastel background: a soft gradient with a few big, very blurry color blobs
 * that drift slowly. Turning animation off in Settings holds them still.
 */
class BlobBackground(ctx: Context) : View(ctx) {
    var theme: HavenTheme = THEMES[0]
        set(v) {
            field = v
            shaderSize = -1
            invalidate()
        }
    var animated = true
        set(v) {
            field = v
            if (v) start() else stop()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private var shaderSize = -1
    private var phase = 0f
    private var anim: ValueAnimator? = null

    // Each blob: x, y (0..1), radius (fraction of width), speed and offset of its little orbit.
    private val blobs = arrayOf(
        floatArrayOf(0.05f, 0.10f, 0.42f, 1.0f, 0.0f),
        floatArrayOf(0.95f, 0.30f, 0.36f, 0.7f, 2.1f),
        floatArrayOf(0.55f, 1.05f, 0.40f, 0.85f, 4.2f),
    )

    private fun start() {
        if (anim != null || !isAttachedToWindow) return
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 70_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        anim?.cancel()
        anim = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && animated) start() else if (visibility != VISIBLE) {
            anim?.cancel()
            anim = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (shaderSize != width * 31 + height) {
            shaderSize = width * 31 + height
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.3f, h, theme.top, theme.bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        for ((i, b) in blobs.withIndex()) {
            val color = theme.blobs[i % theme.blobs.size]
            val wobble = phase * b[3] + b[4]
            val cx = (b[0] + 0.05f * Math.cos(wobble.toDouble()).toFloat()) * w
            val cy = (b[1] + 0.07f * Math.sin(wobble.toDouble()).toFloat()) * h
            val r = b[2] * w
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, 0xC0), withAlpha(color, 0x50), withAlpha(color, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
