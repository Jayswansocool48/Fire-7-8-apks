package io.github.jayswansocool48.havenremote

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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.content.res.ColorStateList
import android.widget.TextView

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

private fun c(v: Long) = v.toInt()

/** Haven's pastel colors (Lavender Dream, same as the TV's default look). */
object P {
    val TOP = c(0xFFF3EEFF)
    val BOTTOM = c(0xFFFDEFF5)
    val BLOBS = intArrayOf(c(0xFFC9B6FF), c(0xFFFFB8DA), c(0xFFB8F0DE), c(0xFFFFD9B8))
    val TEXT = c(0xFF2F2847)
    val DIM = c(0xFF8A81A3)
    val ACCENT = c(0xFF8467F5)
    val PINK = c(0xFFFF7EB6)
    val MINT = c(0xFF4CCB9E)
    val PEACH = c(0xFFFFA477)
    val CARD = c(0xF2FFFFFF)
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
    background = rounded(P.CARD, dp(24f))
    val p = dpi(20f)
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

/** A rounded button with a label (and optional icon). */
fun Context.button(label: String, color: Int = P.ACCENT, textColor: Int = P.WHITE, iconRes: Int = 0, repeat: Boolean = false, onPress: () -> Unit): LinearLayout =
    hbox().apply {
        gravity = Gravity.CENTER
        val ph = dpi(18f)
        val pv = dpi(14f)
        setPadding(ph, pv, ph, pv)
        background = rounded(color, dp(18f))
        if (iconRes != 0) addView(icon(iconRes, textColor), LinearLayout.LayoutParams(dpi(20f), dpi(20f)).apply { rightMargin = dpi(8f) })
        addView(text(label, 15f, textColor, bold = true))
        squishy(repeat, onPress)
    }

fun Context.icon(res: Int, color: Int) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    scaleType = ImageView.ScaleType.FIT_CENTER
}

/** A round remote button with an icon. */
fun Context.roundKey(iconRes: Int, bg: Int, tint: Int = P.TEXT, size: Float = 60f, iconSize: Float = 26f, repeat: Boolean = false, onPress: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = oval(bg)
        elevation = dp(if (bg == P.WHITE) 2f else 0f)
        addView(icon(iconRes, tint), FrameLayout.LayoutParams(dpi(iconSize), dpi(iconSize), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
        squishy(repeat, onPress)
    }

/** Soft pastel gradient with two big, very faint color glows. Static, so it's easy on the battery. */
class BlobBackground(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private var size = -1

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (size != width * 31 + height) {
            size = width * 31 + height
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.4f, h, P.TOP, P.BOTTOM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        glow(canvas, -0.1f * w, 0.05f * h, 0.8f * w, P.BLOBS[0])
        glow(canvas, 1.1f * w, 0.75f * h, 0.9f * w, P.BLOBS[1])
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val a = color and 0x00FFFFFF
        paint.shader = RadialGradient(x, y, r, intArrayOf(a or (0x90 shl 24), a or (0x30 shl 24), a), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
    }
}
