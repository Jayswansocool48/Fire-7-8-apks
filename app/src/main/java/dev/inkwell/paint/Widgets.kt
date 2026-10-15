package dev.inkwell.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/** Square icon button with a "selected" highlight. */
class ToolButton(context: Context, icon: Icon) : View(context) {
    var icon: Icon = icon
        set(v) { field = v; invalidate() }
    var selectedTool = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(4f)
        rect.set(pad, pad, width - pad, height - pad)
        val r = context.dp(10f)
        if (selectedTool) {
            bgPaint.color = (Palette.ACCENT and 0x00FFFFFF) or 0x55000000
            canvas.drawRoundRect(rect, r, r, bgPaint)
        }
        if (isPressed) {
            bgPaint.color = 0x22FFFFFF
            canvas.drawRoundRect(rect, r, r, bgPaint)
        }
        val color = when {
            !isEnabled -> 0x55E8E8EC
            selectedTool -> Color.WHITE
            else -> Palette.TEXT
        }
        Icons.draw(canvas, icon, width / 2f, height / 2f, min(width, height) * 0.5f, color, paint)
    }
}

/** Round color swatch. Also used as the current-color button. */
class SwatchView(context: Context, color: Int) : View(context) {
    var color: Int = color
        set(v) { field = v; invalidate() }
    var ringed = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(3f)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(if (ringed) 2.5f else 1.5f)
        paint.color = if (ringed) Color.WHITE else 0x66FFFFFF
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/**
 * Procreate-style vertical slider: drag up/down anywhere on it to change the value
 * relative to where your finger started, so it never jumps.
 */
class VerticalSlider(context: Context, private val label: String) : View(context) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var formatter: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    var onChange: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(10f)
        color = Palette.TEXT_DIM
    }
    private val track = RectF()
    private var startY = 0f
    private var startValue = 0f
    private var dragging = false

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        val text = context.dp(14f)
        track.set(context.dp(8f), text, width - context.dp(8f), height - text)
        val r = track.width() / 2f
        paint.color = 0x33FFFFFF
        canvas.drawRoundRect(track, r, r, paint)
        val fillTop = track.bottom - track.height() * value
        paint.color = if (dragging) Palette.ACCENT else 0xCCE8E8EC.toInt()
        canvas.save()
        canvas.clipRect(track.left, fillTop, track.right, track.bottom)
        canvas.drawRoundRect(track, r, r, paint)
        canvas.restore()
        textPaint.color = if (dragging) Color.WHITE else Palette.TEXT_DIM
        canvas.drawText(formatter(value), width / 2f, text - context.dp(3f), textPaint)
        textPaint.color = Palette.TEXT_DIM
        canvas.drawText(label, width / 2f, height - context.dp(2f), textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startY = e.y
                startValue = value
                dragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val trackH = (height - context.dp(28f)).coerceAtLeast(1f)
                value = startValue + (startY - e.y) / trackH
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}

/** Saturation/brightness square with a hue bar underneath. */
class ColorPickerView(context: Context, color: Int) : View(context) {
    private val hsv = FloatArray(3)
    var onColorChanged: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svRect = RectF()
    private val hueRect = RectF()
    private var svShader: Shader? = null
    private var darkShader: Shader? = null
    private var shaderHue = -1f
    private var hueShader: Shader? = null
    private var dragTarget = 0 // 1 = square, 2 = hue bar

    init { setColor(color) }

    val color: Int get() = Color.HSVToColor(hsv)

    fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = context.dp(4f)
        val hueH = context.dp(26f)
        svRect.set(pad, pad, w - pad, h - pad * 2 - hueH - context.dp(8f))
        hueRect.set(pad, svRect.bottom + context.dp(12f), w - pad, h - pad)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        hueShader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
        shaderHue = -1f
        darkShader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (shaderHue != hsv[0]) {
            val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            svShader = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
            shaderHue = hsv[0]
        }
        if (darkShader == null) {
            darkShader = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        val r = context.dp(8f)
        paint.style = Paint.Style.FILL
        // Two passes instead of a ComposeShader: older GPUs can't combine two gradients.
        paint.shader = svShader
        canvas.drawRoundRect(svRect, r, r, paint)
        paint.shader = darkShader
        canvas.drawRoundRect(svRect, r, r, paint)
        paint.shader = hueShader
        canvas.drawRoundRect(hueRect, r, r, paint)
        paint.shader = null

        // Markers
        val mx = svRect.left + hsv[1] * svRect.width()
        val my = svRect.top + (1f - hsv[2]) * svRect.height()
        val hx = hueRect.left + hsv[0] / 360f * hueRect.width()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(3f)
        paint.color = Color.WHITE
        canvas.drawCircle(mx, my, context.dp(9f), paint)
        canvas.drawRoundRect(hx - context.dp(4f), hueRect.top - context.dp(2f), hx + context.dp(4f), hueRect.bottom + context.dp(2f), r, r, paint)
        paint.strokeWidth = context.dp(1f)
        paint.color = 0x88000000.toInt()
        canvas.drawCircle(mx, my, context.dp(10.5f), paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragTarget = if (e.y > (svRect.bottom + hueRect.top) / 2f) 2 else 1
                update(e)
            }
            MotionEvent.ACTION_MOVE -> update(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragTarget = 0
        }
        return true
    }

    private fun update(e: MotionEvent) {
        if (dragTarget == 1) {
            hsv[1] = ((e.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((e.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        } else if (dragTarget == 2) {
            hsv[0] = ((e.x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.9f
        }
        invalidate()
        onColorChanged?.invoke(color)
    }
}
