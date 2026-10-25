package io.github.jayswansocool48.havenremote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider

/**
 * The big round d-pad. In Buttons mode, press an edge for a direction and the middle for OK
 * (hold to repeat). In Swipe mode the whole circle is a touchpad: swipe to move, tap for OK.
 */
@SuppressLint("ViewConstructor")
class PadView(ctx: Context, private val onKey: (Int) -> Unit) : View(ctx) {

    var swipeMode = false
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = P.WHITE }
    private val press = Paint(Paint.ANTI_ALIAS_FLAG)
    private val center = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.ACCENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Fonts.bold(ctx)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.DIM
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium(ctx)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pressed = -1 // keycode being held, -1 for none
    private val oval = RectF()
    private val path = Path()

    // Swipe tracking.
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var trailX = -1f
    private var trailY = -1f

    private val repeater = object : Runnable {
        override fun run() {
            if (pressed < 0) return
            onKey(pressed)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            postDelayed(this, 150)
        }
    }

    init {
        elevation = ctx.dp(4f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val r get() = Math.min(width, height) / 2f
    private val inner get() = r * 0.36f

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, r, fill)

        if (swipeMode) {
            hint.textSize = r * 0.1f
            canvas.drawText("Swipe to move", cx, cy - r * 0.05f, hint)
            canvas.drawText("Tap for OK", cx, cy + r * 0.12f, hint)
            if (trailX >= 0) {
                dot.color = P.LAV
                canvas.drawCircle(trailX, trailY, r * 0.16f, dot)
            }
            return
        }

        // A pastel wedge under whichever direction is held.
        val dir = when (pressed) {
            UP -> 270f
            RIGHT -> 0f
            DOWN -> 90f
            LEFT -> 180f
            else -> -1f
        }
        if (dir >= 0) {
            press.color = P.ROSE
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, dir - 45f, 90f, true, press)
        }

        // Chevrons.
        arrow.strokeWidth = r * 0.055f
        val d = r * 0.68f
        val s = r * 0.1f
        for ((code, ax, ay) in listOf(Triple(UP, 0f, -1f), Triple(DOWN, 0f, 1f), Triple(LEFT, -1f, 0f), Triple(RIGHT, 1f, 0f))) {
            val x = cx + ax * d
            val y = cy + ay * d
            arrow.color = if (pressed == code) P.PINK else P.ACCENT
            path.reset()
            if (ax == 0f) {
                path.moveTo(x - s, y - ay * s * 0.5f)
                path.lineTo(x, y + ay * s * 0.5f)
                path.lineTo(x + s, y - ay * s * 0.5f)
            } else {
                path.moveTo(x - ax * s * 0.5f, y - s)
                path.lineTo(x + ax * s * 0.5f, y)
                path.lineTo(x - ax * s * 0.5f, y + s)
            }
            canvas.drawPath(path, arrow)
        }

        // The OK button.
        center.color = if (pressed == OK) P.PINK else P.ACCENT
        val ir = inner * (if (pressed == OK) 0.92f else 1f)
        canvas.drawCircle(cx, cy, ir, center)
        label.textSize = r * 0.15f
        canvas.drawText("OK", cx, cy - (label.descent() + label.ascent()) / 2f, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        return if (swipeMode) swipe(e) else buttons(e)
    }

    private fun buttons(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = e.x - cx
                val dy = e.y - cy
                val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist > r) return false
                pressed = if (dist < inner) OK else if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 0) RIGHT else LEFT
                } else {
                    if (dy > 0) DOWN else UP
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey(pressed)
                postDelayed(repeater, 450)
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                removeCallbacks(repeater)
                animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                invalidate()
            }
        }
        return true
    }

    private fun swipe(e: MotionEvent): Boolean {
        val step = context.dp(42f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downAt = e.eventTime
                moved = false
                trailX = e.x
                trailY = e.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                trailX = e.x
                trailY = e.y
                val dx = e.x - downX
                val dy = e.y - downY
                if (Math.abs(dx) > step || Math.abs(dy) > step) {
                    val code = if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) RIGHT else LEFT) else (if (dy > 0) DOWN else UP)
                    onKey(code)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    moved = true
                    // Keep going: each further step's worth of travel is another press.
                    downX = e.x
                    downY = e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && e.eventTime - downAt < 350) {
                    onKey(OK)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                trailX = -1f
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                trailX = -1f
                invalidate()
            }
        }
        return true
    }

    companion object {
        const val UP = 19
        const val DOWN = 20
        const val LEFT = 21
        const val RIGHT = 22
        const val OK = 23
    }
}
