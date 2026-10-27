package io.github.jayswansocool48.hubremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

/** Same palette as the Hub control page and the TV: warm ink, cream text, one accent. */
object Theme {
    val NIGHT = 0xFF14120F.toInt()
    val DEEP = 0xFF1E1A16.toInt()
    val DEEPER = 0xFF191612.toInt()
    val RAISE = 0xFF2A251F.toInt()
    val LINE = 0xFF3A332B.toInt()
    val MIST = 0xFFBDB3A3.toInt()
    val DIM = 0xFF8F8678.toInt()
    val SNOW = 0xFFF2EADC.toInt()
    val CORAL = 0xFFE07A5F.toInt()
    val OK = 0xFF8FB38A.toInt()
    val ACCENT_DEFAULT = 0xFFD9894A.toInt()
    val accent get() = Hub.accent
}

/** Same list as the server's ACCENTS. */
val ACCENTS = listOf("#D9894A", "#C9A44C", "#8FB38A", "#5FA3A0", "#C46F5E", "#7E97C9", "#C58A9B", "#B9A58F")

fun parseColor(hex: String, fallback: Int = Theme.ACCENT_DEFAULT): Int = try { Color.parseColor(hex) } catch (e: Exception) { fallback }

/** Manrope for text, Fraunces for headings. Both ship in the APK (SIL Open Font License). */
object Fonts {
    lateinit var regular: Typeface
    lateinit var semibold: Typeface
    lateinit var serif: Typeface
    fun init(ctx: Context) {
        if (::regular.isInitialized) return
        fun load(name: String, fallback: Typeface) = try { Typeface.createFromAsset(ctx.assets, "fonts/$name") } catch (e: Exception) { fallback }
        regular = load("manrope-regular.ttf", Typeface.DEFAULT)
        semibold = load("manrope-semibold.ttf", Typeface.DEFAULT_BOLD)
        serif = load("fraunces.ttf", Typeface.SERIF)
    }
}

/** A filled shape with continuous (squircle) corners. Clipping uses the nearest rounded rect,
 *  since older Android only clips to simple outlines. */
class Squircle(private val fill: Int, private val radius: Float, private val stroke: Int = 0, private val strokeW: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onBoundsChange(b: Rect) { super.onBoundsChange(b); build(b) }

    private fun build(b: Rect) {
        path.reset()
        val inset = strokeW / 2f
        val l = b.left + inset; val t = b.top + inset; val r = b.right - inset; val bt = b.bottom - inset
        // a squircle corner covers more of the edge than a circular one with the same look
        val rad = min(radius * 1.25f, min(r - l, bt - t) / 2f)
        if (rad <= 0f) { path.addRect(l, t, r, bt, Path.Direction.CW); return }
        val n = 4.0 // superellipse exponent: 2 is a circle, higher is squarer
        val steps = 10
        fun corner(cx: Float, cy: Float, sx: Float, sy: Float, from: Double, first: Boolean) {
            for (i in 0..steps) {
                val a = from + (Math.PI / 2) * i / steps
                val c = cos(a); val sn = sin(a)
                val x = cx + sx * rad * (abs(c).pow(2.0 / n)).toFloat() * (if (c < 0) -1 else 1)
                val y = cy + sy * rad * (abs(sn).pow(2.0 / n)).toFloat() * (if (sn < 0) -1 else 1)
                if (first && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        corner(r - rad, t + rad, 1f, 1f, -Math.PI / 2, true)      // top right
        corner(r - rad, bt - rad, 1f, 1f, 0.0, false)             // bottom right
        corner(l + rad, bt - rad, 1f, 1f, Math.PI / 2, false)     // bottom left
        corner(l + rad, t + rad, 1f, 1f, Math.PI, false)          // top left
        path.close()
    }

    override fun draw(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawPath(path, paint)
        if (strokeW > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeW.toFloat()
            paint.color = stroke
            c.drawPath(path, paint)
        }
    }

    override fun getOutline(o: Outline) { o.setRoundRect(bounds, min(radius, min(bounds.width(), bounds.height()) / 2f)) }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): Drawable = Squircle(color, radius, stroke, strokeW)

/** A plain circle (accent swatches and round buttons). */
fun circle(color: Int, stroke: Int = 0, strokeW: Int = 0) = android.graphics.drawable.GradientDrawable().apply {
    shape = android.graphics.drawable.GradientDrawable.OVAL
    setColor(color)
    if (strokeW > 0) setStroke(strokeW, stroke)
}

/**
 * Closed-form damped springs (stiffness k, damping c, mass 1) for every UI move.
 * A new target starts from where the value is now, with its current speed, so you can
 * reverse or redirect any animation mid-flight.
 */
object Spring {
    class Preset(val k: Float, val c: Float)
    val SMOOTH = Preset(170f, 26f)
    val SNAPPY = Preset(380f, 32f)
    val BOUNCY = Preset(300f, 17f)

    private class State(var pos: Float) {
        var vel = 0f; var target = pos; var x0 = 0f; var v0 = 0f; var t0 = 0L
        var preset = SMOOTH; var running = false; var onValue: (Float) -> Unit = {}; var done: (() -> Unit)? = null; var eps = 0.001f
    }
    private val states = WeakHashMap<View, HashMap<String, State>>()

    private fun eval(x0: Float, v0: Float, p: Preset, t: Float): Pair<Float, Float> {
        val w = sqrt(p.k); val z = p.c / (2 * w)
        if (z < 1f) {
            val wd = w * sqrt(1 - z * z); val b = (v0 + z * w * x0) / wd
            val e = exp(-z * w * t); val cs = cos(wd * t); val sn = sin(wd * t)
            return e * (x0 * cs + b * sn) to e * (-z * w * (x0 * cs + b * sn) + (-x0 * wd * sn + b * wd * cs))
        }
        val b = v0 + w * x0; val e = exp(-w * t)
        return (x0 + b * t) * e to (b - w * (x0 + b * t)) * e
    }

    private fun reduced(v: View) = try {
        Settings.Global.getFloat(v.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }

    /** Springs [key] on [view] from its current value (or [from] the first time) to [to]. */
    fun to(view: View, key: String, from: Float, to: Float, preset: Preset = SMOOTH, done: (() -> Unit)? = null, apply: (Float) -> Unit) {
        val map = states.getOrPut(view) { HashMap() }
        val s = map.getOrPut(key) { State(from) }
        val now = System.nanoTime()
        if (s.running) { val (d, v) = eval(s.x0, s.v0, s.preset, (now - s.t0) / 1e9f); s.pos = s.target + d; s.vel = v }
        s.target = to; s.onValue = apply; s.done = done; s.preset = preset
        s.eps = max(0.0005f, abs(s.pos - to) * 0.002f)
        if (reduced(view)) { s.pos = to; s.vel = 0f; s.running = false; apply(to); done?.invoke(); return }
        s.x0 = s.pos - to; s.v0 = s.vel; s.t0 = now
        if (s.running) return
        s.running = true
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!s.running) return
                val (d, v) = eval(s.x0, s.v0, s.preset, (System.nanoTime() - s.t0) / 1e9f)
                s.pos = s.target + d; s.vel = v
                if (abs(d) < s.eps && abs(v) < s.eps * 10) {
                    s.pos = s.target; s.vel = 0f; s.running = false; s.onValue(s.target); s.done?.invoke(); return
                }
                s.onValue(s.pos)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** Jumps a value without animating (for setting a start point). */
    fun set(view: View, key: String, value: Float) {
        val s = states.getOrPut(view) { HashMap() }.getOrPut(key) { State(value) }
        s.running = false; s.pos = value; s.vel = 0f; s.target = value
    }
}

fun View.springAlpha(to: Float, preset: Spring.Preset = Spring.SMOOTH, done: (() -> Unit)? = null) =
    Spring.to(this, "alpha", alpha, to, preset, done) { alpha = it.coerceIn(0f, 1f) }
fun View.springY(to: Float, preset: Spring.Preset = Spring.SMOOTH, done: (() -> Unit)? = null) =
    Spring.to(this, "y", translationY, to, preset, done) { translationY = it }
fun View.springX(to: Float, preset: Spring.Preset = Spring.SMOOTH, done: (() -> Unit)? = null) =
    Spring.to(this, "x", translationX, to, preset, done) { translationX = it }
fun View.springScale(to: Float, preset: Spring.Preset = Spring.SNAPPY, done: (() -> Unit)? = null) =
    Spring.to(this, "scale", scaleX, to, preset, done) { scaleX = it; scaleY = it }

/** A quick press: squeeze, then spring back with a little overshoot. */
fun View.press() { Spring.set(this, "scale", 0.94f); scaleX = 0.94f; scaleY = 0.94f; springScale(1f, Spring.BOUNCY) }

fun Context.label(text: String, size: Float = 15f, color: Int = Theme.SNOW, bold: Boolean = false, serif: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    Fonts.init(context)
    typeface = if (serif) Fonts.serif else if (bold) Fonts.semibold else Fonts.regular
    includeFontPadding = false
    setLineSpacing(dp(2f), 1f)
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
    Fonts.init(context)
    typeface = Fonts.semibold
    setTextColor(if (primary) Theme.NIGHT else if (danger) Theme.CORAL else Theme.SNOW)
    background = if (primary) rounded(Theme.SNOW, dp(12f)) else rounded(Theme.RAISE, dp(12f), Theme.LINE, dpi(1f))
    minHeight = dpi(44f)
    minWidth = dpi(44f)
    val h = dpi(16f)
    val v = dpi(12f)
    setPadding(h, v, h, v)
    isClickable = true
    setOnClickListener { tick(); press(); onClick() }
}

fun Context.input(hint: String, text: String = "", url: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(text)
    textSize = 15f
    setTextColor(Theme.SNOW)
    setHintTextColor(Theme.DIM)
    Fonts.init(context)
    typeface = Fonts.regular
    minHeight = dpi(44f)
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
    private var knob = 0f
    var on = false
        set(v) {
            val first = !laidOut
            field = v
            if (first) { knob = if (v) 1f else 0f; Spring.set(this, "knob", knob); invalidate() }
            else Spring.to(this, "knob", knob, if (v) 1f else 0f, Spring.BOUNCY) { knob = it; invalidate() }
        }
    private val laidOut get() = isAttachedToWindow && width > 0
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { tick(); on = !on; onChange?.invoke(on) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(52f), context.dpi(44f))

    override fun onDraw(c: Canvas) {
        val k = knob.coerceIn(-0.1f, 1.1f)
        p.color = blend(Theme.LINE, Theme.accent, k.coerceIn(0f, 1f))
        val th = context.dp(30f)
        val top = (height - th) / 2f
        c.drawRoundRect(0f, top, width.toFloat(), top + th, th / 2f, th / 2f, p)
        p.color = Theme.SNOW
        val r = th / 2f - context.dp(3f)
        val x0 = th / 2f
        val x1 = width - th / 2f
        c.drawCircle(x0 + (x1 - x0) * k, height / 2f, r, p)
    }
}

/** Lays children left to right and wraps onto new lines. */
open class Flow(context: Context) : ViewGroup(context) {
    protected val gap = context.dpi(8f)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
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
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** Pill-shaped choice chips that wrap onto new lines. */
class Chips(context: Context) : Flow(context) {
    private val items = ArrayList<Pair<String, TextView>>()
    var onPick: ((String) -> Unit)? = null

    fun add(key: String, text: String) {
        val tv = context.label(text, 14f).apply {
            val h = context.dpi(15f)
            val v = context.dpi(9f)
            setPadding(h, v, h, v)
            minHeight = context.dpi(44f)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { tick(); press(); select(key); onPick?.invoke(key) }
        }
        items.add(key to tv)
        addView(tv)
        select(null)
    }

    fun select(key: String?) {
        for ((k, tv) in items) {
            val on = k == key
            tv.background = if (on) rounded(Theme.SNOW, context.dp(99f)) else rounded(Theme.RAISE, context.dp(99f), Theme.LINE, context.dpi(1f))
            tv.setTextColor(if (on) Theme.NIGHT else Theme.SNOW)
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

fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt() and 255
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** A gray block in the shape of content that is still loading. */
fun Context.skeleton(w: Int, h: Int, radius: Float = 8f) = View(this).apply {
    background = rounded(Theme.RAISE, dp(radius))
    layoutParams = LinearLayout.LayoutParams(w, h)
    alpha = 0.7f
    // a slow breathing pulse while it waits
    fun pulse(up: Boolean) { if (isAttachedToWindow || !up) Spring.to(this, "alpha", alpha, if (up) 1f else 0.5f, Spring.Preset(12f, 5f)) { a -> alpha = a } }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        val r = object : Runnable { var up = true; override fun run() { pulse(up); up = !up; postDelayed(this, 900) } }
        override fun onViewAttachedToWindow(v: View) { post(r) }
        override fun onViewDetachedFromWindow(v: View) { removeCallbacks(r) }
    })
}

fun Context.spacer(h: Float) = View(this).apply { layoutParams = lp(MATCH, dpi(h)) }
