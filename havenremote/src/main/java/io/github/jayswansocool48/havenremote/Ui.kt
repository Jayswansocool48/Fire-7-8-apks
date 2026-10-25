package io.github.jayswansocool48.havenremote

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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

private fun c(v: Long) = v.toInt()

/** Haven's pastel colors (Lavender Dream, same as the TV's default look). */
object P {
    val TOP = c(0xFFEDE4FF)
    val BOTTOM = c(0xFFFFE3F1)
    val BLOBS = intArrayOf(c(0xFFC9B6FF), c(0xFFFFB8DA), c(0xFFB8F0DE), c(0xFFFFD9B8))
    val TEXT = c(0xFF3D3358)
    val DIM = c(0xFF7A6F96)
    val ACCENT = c(0xFF8B6CFF)
    val PINK = c(0xFFFF7EB6)
    val MINT = c(0xFF4CCB9E)
    val PEACH = c(0xFFFFA477)
    val CARD = c(0xE6FFFFFF)
    val SOFT = c(0xFFF3EEFF)
    val LAV = c(0xFFDCCFFF)
    val ROSE = c(0xFFFFD3E6)
    val MINTY = c(0xFFCDF3E4)
    val SUN = c(0xFFFFE6C9)
    val SKY = c(0xFFD3E7FF)
    val TILES = intArrayOf(LAV, ROSE, MINTY, SUN, SKY, c(0xFFFFF3BF))
    val WHITE = c(0xFFFFFFFF)
}

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

fun Context.text(s: String, size: Float, color: Int = P.TEXT, bold: Boolean = false) = TextView(this).apply {
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

fun Context.card() = vbox().apply {
    background = rounded(P.CARD, dp(26f))
    elevation = dp(3f)
    val p = dpi(18f)
    setPadding(p, p, p, p)
}

/** Squishes down when pressed and springs back: the remote's signature feel. */
@SuppressLint("ClickableViewAccessibility")
fun View.squishy(repeat: Boolean = false, onPress: () -> Unit) {
    isClickable = true
    var repeating: Runnable? = null
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).start()
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                v.isPressed = true
                if (repeat) {
                    onPress()
                    val r = object : Runnable {
                        override fun run() {
                            onPress()
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            v.postDelayed(this, 160)
                        }
                    }
                    repeating = r
                    v.postDelayed(r, 450)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(4f)).setDuration(260).start()
                v.isPressed = false
                repeating?.let { v.removeCallbacks(it) }
                repeating = null
                val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                if (!repeat && e.actionMasked == MotionEvent.ACTION_UP && inside) onPress()
            }
        }
        true
    }
}

/** A chunky rounded button. */
fun Context.button(label: String, color: Int = P.ACCENT, textColor: Int = P.WHITE, repeat: Boolean = false, onPress: () -> Unit) =
    text(label, 16f, textColor, bold = true).apply {
        gravity = Gravity.CENTER
        val ph = dpi(18f)
        val pv = dpi(13f)
        setPadding(ph, pv, ph, pv)
        background = rounded(color, dp(30f))
        elevation = dp(3f)
        squishy(repeat, onPress)
    }

/** A round remote button with a symbol, and an optional caption under it. */
fun Context.roundKey(symbol: String, caption: String?, color: Int, size: Float = 64f, repeat: Boolean = false, onPress: () -> Unit): LinearLayout {
    val box = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
    val b = text(symbol, size * 0.36f, P.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = oval(color)
        elevation = dp(4f)
        squishy(repeat, onPress)
    }
    box.addView(b, LinearLayout.LayoutParams(dpi(size), dpi(size)))
    if (caption != null) box.addView(text(caption, 12f, P.DIM).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = dpi(6f) })
    return box
}

/** Soft gradient with big blurry pastel blobs drifting slowly. */
class BlobBackground(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private var size = -1
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private val blobs = arrayOf(
        floatArrayOf(0.10f, 0.12f, 0.45f, 1.0f, 0.0f),
        floatArrayOf(0.90f, 0.30f, 0.40f, 0.7f, 1.7f),
        floatArrayOf(0.20f, 0.75f, 0.50f, 0.9f, 3.1f),
        floatArrayOf(0.85f, 0.92f, 0.40f, 1.2f, 4.4f),
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 36_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (size != width * 31 + height) {
            size = width * 31 + height
            bgPaint.shader = LinearGradient(0f, 0f, w, h, P.TOP, P.BOTTOM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val m = Math.max(w, h)
        for ((i, b) in blobs.withIndex()) {
            val color = P.BLOBS[i % P.BLOBS.size]
            val wob = phase * b[3] + b[4]
            val cx = (b[0] + 0.07f * Math.cos(wob.toDouble()).toFloat()) * w
            val cy = (b[1] + 0.05f * Math.sin(wob.toDouble()).toFloat()) * h
            val r = b[2] * m * (1f + 0.06f * Math.sin((wob * 1.3f).toDouble()).toFloat())
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(color and 0x00FFFFFF or (0xA8 shl 24), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
