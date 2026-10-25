package io.github.jayswansocool48.haven

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
    val panel: Int,
    val tiles: IntArray,
)

private fun c(v: Long) = v.toInt()

val THEMES = listOf(
    HavenTheme(
        "lavender", "Lavender Dream",
        c(0xFFEDE4FF), c(0xFFFFE3F1),
        intArrayOf(c(0xFFC9B6FF), c(0xFFFFB8DA), c(0xFFB8F0DE), c(0xFFFFD9B8)),
        c(0xFF3D3358), c(0xFF7A6F96), c(0xFF8B6CFF), c(0xF2FFFFFF),
        intArrayOf(c(0xFFD9CCFF), c(0xFFFFD1E6), c(0xFFC9F2E4), c(0xFFFFE2C7), c(0xFFCFE6FF), c(0xFFFFF1B8)),
    ),
    HavenTheme(
        "mint", "Mint Soda",
        c(0xFFDDF7EE), c(0xFFE3EEFF),
        intArrayOf(c(0xFFA8EBD3), c(0xFFB9D6FF), c(0xFFFFF0A8), c(0xFFD7C4FF)),
        c(0xFF22433F), c(0xFF5D7F7A), c(0xFF2BB58C), c(0xF2FFFFFF),
        intArrayOf(c(0xFFC4F1E0), c(0xFFD2E3FF), c(0xFFFFF5C2), c(0xFFE3D7FF), c(0xFFFFD9E6), c(0xFFD1F5F7)),
    ),
    HavenTheme(
        "peach", "Peach Fizz",
        c(0xFFFFE8DA), c(0xFFFFF5CC),
        intArrayOf(c(0xFFFFC2A3), c(0xFFFFE08A), c(0xFFFFB3C7), c(0xFFC6EBB0)),
        c(0xFF4A3328), c(0xFF8F6C5C), c(0xFFFF7E5F), c(0xF2FFFFFF),
        intArrayOf(c(0xFFFFD4BF), c(0xFFFFEDA8), c(0xFFFFCCD9), c(0xFFD6F0C4), c(0xFFFFE0CC), c(0xFFE8DDFF)),
    ),
    HavenTheme(
        "cotton", "Cotton Candy",
        c(0xFFFFDDEE), c(0xFFDDE9FF),
        intArrayOf(c(0xFFFFADD2), c(0xFFADC8FF), c(0xFFE0B8FF), c(0xFFB8FFF0)),
        c(0xFF43304F), c(0xFF86709A), c(0xFFFF6FAE), c(0xF2FFFFFF),
        intArrayOf(c(0xFFFFCCE3), c(0xFFCCDCFF), c(0xFFEBD1FF), c(0xFFCCFFF4), c(0xFFFFF0CC), c(0xFFFFD6CC)),
    ),
    HavenTheme(
        "night", "Starry Night",
        c(0xFF231D3B), c(0xFF3A2950),
        intArrayOf(c(0xFF6B4FD8), c(0xFFD85FA0), c(0xFF3FA8A0), c(0xFF4F6BD8)),
        c(0xFFF5EEFF), c(0xFFB8AED0), c(0xFFFFA8D5), c(0xF2342A4D),
        intArrayOf(c(0xFF4A3D73), c(0xFF5E3A63), c(0xFF2F5560), c(0xFF3D4A73), c(0xFF5C4A3A), c(0xFF4D3A5E)),
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

fun Context.text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = if (bold) Fonts.bold(this@text) else Fonts.medium(this@text)
    includeFontPadding = false
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** The bouncy grow-on-focus that every focusable thing on the home screen uses. */
fun bounce(v: View, focused: Boolean, scale: Float = 1.1f) {
    v.animate().cancel()
    v.animate()
        .scaleX(if (focused) scale else 1f)
        .scaleY(if (focused) scale else 1f)
        .translationY(if (focused) -v.context.dp(4f) else 0f)
        .setInterpolator(OvershootInterpolator(if (focused) 3f else 1.5f))
        .setDuration(if (focused) 260 else 180)
        .start()
    v.elevation = if (focused) v.context.dp(12f) else v.context.dp(2f)
}

/** A rounded pill button for menus and settings. */
fun Context.pill(label: String, t: HavenTheme, onClick: () -> Unit): TextView = text(label, 17f, t.text, bold = true).apply {
    gravity = Gravity.CENTER_VERTICAL
    val ph = dpi(22f)
    val pv = dpi(13f)
    setPadding(ph, pv, ph, pv)
    val idle = rounded(t.tiles[0] and 0x00FFFFFF or (0x99 shl 24), dp(40f))
    val focus = rounded(t.accent, dp(40f), 0xFFFFFFFF.toInt(), dpi(3f))
    background = idle
    isFocusable = true
    isClickable = true
    setOnFocusChangeListener { v, has ->
        background = if (has) focus else idle
        setTextColor(if (has) 0xFFFFFFFF.toInt() else t.text)
        bounce(v, has, 1.05f)
    }
    setOnClickListener { onClick() }
}

/**
 * The pastel background: a soft gradient with big blurry blobs that slowly drift around.
 * Turning animation off in Settings freezes the blobs (easier on slow TVs).
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

    // Each blob: x, y (0..1), radius (fraction of height), speed and offset of its little orbit.
    private val blobs = arrayOf(
        floatArrayOf(0.12f, 0.18f, 0.55f, 1.0f, 0.0f),
        floatArrayOf(0.85f, 0.25f, 0.50f, 0.7f, 1.7f),
        floatArrayOf(0.30f, 0.90f, 0.60f, 0.9f, 3.1f),
        floatArrayOf(0.78f, 0.85f, 0.45f, 1.2f, 4.4f),
    )

    private fun start() {
        if (anim != null || !isAttachedToWindow) return
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 40_000
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
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.4f, h, theme.top, theme.bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        for ((i, b) in blobs.withIndex()) {
            val color = theme.blobs[i % theme.blobs.size]
            val wobble = phase * b[3] + b[4]
            val cx = (b[0] + 0.06f * Math.cos(wobble.toDouble()).toFloat()) * w
            val cy = (b[1] + 0.08f * Math.sin(wobble.toDouble()).toFloat()) * h
            val r = b[2] * h * (1f + 0.06f * Math.sin((wobble * 1.3f).toDouble()).toFloat())
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(color and 0x00FFFFFF or (0xB0 shl 24), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
