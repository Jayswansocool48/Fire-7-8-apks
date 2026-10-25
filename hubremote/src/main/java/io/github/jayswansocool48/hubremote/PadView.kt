package io.github.jayswansocool48.hubremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The touch pad. In Navigate mode: swipe to move (keep holding after a swipe to keep
 * moving), tap for OK, press and hold for Options. In Pointer mode it's a trackpad for
 * Hub's web browser: drag moves the pointer, tap clicks, two fingers scroll.
 */
class PadView(context: Context) : View(context) {
    var pointerMode = false
        set(v) { field = v; invalidate() }

    private val step = context.dp(46f)
    private val dotGap = context.dp(26f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val chevron = Path()

    // touch state
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var moved = false
    private var tracking = false
    private var lastX = 0f
    private var lastY = 0f
    private var held: String? = null
    private var lastStep = 0L
    private var accX = 0f
    private var accY = 0f
    private var scrollAcc = 0f
    private var pointers = 0

    // drawing state
    private var glowX = 0f
    private var glowY = 0f
    private var glowA = 0f
    private var pulse = 0f
    private val trail = ArrayList<FloatArray>()
    private val flash = HashMap<String, Long>()

    private val longPress = Runnable {
        if (tracking && !moved && pointers == 1) {
            tick()
            if (!pointerMode) Hub.key("Info")
            tracking = false
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            val h = held ?: return
            if (SystemClock.uptimeMillis() - lastStep > 420) send(h, false)
            postDelayed(this, 170)
        }
    }

    private val flush = Runnable { flushPointer() }

    init { isHapticFeedbackEnabled = true }

    private fun send(dir: String, haptic: Boolean = true) {
        flash[dir] = SystemClock.uptimeMillis()
        if (haptic) tick()
        Hub.key(mapOf("up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right")[dir]!!)
        postInvalidateOnAnimation()
    }

    private fun flushPointer() {
        if (accX == 0f && accY == 0f) return
        val d = resources.displayMetrics.density
        val dx = Math.round(accX / d * 1.7f)
        val dy = Math.round(accY / d * 1.7f)
        accX = 0f
        accY = 0f
        if (dx != 0 || dy != 0) Hub.post("/api/remote", json("type" to "pointer", "dx" to dx, "dy" to dy)) { _, _ -> }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.getX(0)
        val y = e.getY(0)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointers = 1
                startX = x; startY = y; lastX = x; lastY = y
                downAt = SystemClock.uptimeMillis()
                moved = false
                tracking = true
                held = null
                trail.clear()
                glow(x, y)
                postDelayed(longPress, 560)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { pointers = e.pointerCount; moved = true; removeCallbacks(longPress) }
            MotionEvent.ACTION_MOVE -> {
                glow(x, y)
                if (!tracking) return true
                if (e.pointerCount >= 2) {
                    scrollAcc += y - lastY
                    if (abs(scrollAcc) > context.dp(24f)) {
                        Hub.post("/api/remote", json("type" to "scroll", "dy" to Math.round(-scrollAcc / resources.displayMetrics.density * 6))) { _, _ -> }
                        scrollAcc = 0f
                    }
                } else if (pointerMode) {
                    accX += x - lastX
                    accY += y - lastY
                    if (hypot(x - startX, y - startY) > context.dp(6f)) moved = true
                    removeCallbacks(flush)
                    postDelayed(flush, 40)
                } else {
                    val dx = x - startX
                    val dy = y - startY
                    if (max(abs(dx), abs(dy)) >= step) {
                        val dir = if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                        moved = true
                        removeCallbacks(longPress)
                        send(dir)
                        startX = x; startY = y
                        lastStep = SystemClock.uptimeMillis()
                        if (held == null) postDelayed(repeat, 170)
                        held = dir
                    } else if (held != null && hypot(dx, dy) > context.dp(14f)) {
                        held = null
                        removeCallbacks(repeat)
                    }
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> pointers = e.pointerCount - 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                removeCallbacks(repeat)
                held = null
                if (e.actionMasked == MotionEvent.ACTION_UP && tracking && !moved && SystemClock.uptimeMillis() - downAt < 450) {
                    tick()
                    pulse = 1f
                    if (pointerMode) Hub.post("/api/remote", json("type" to "click")) { _, _ -> } else Hub.key("Select")
                }
                removeCallbacks(flush)
                flushPointer()
                tracking = false
                pointers = 0
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun glow(x: Float, y: Float) {
        glowX = x; glowY = y; glowA = 1f
        trail.add(floatArrayOf(x, y))
        if (trail.size > 18) trail.removeAt(0)
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = context.dp(28f)
        val accent = Theme.accent
        rect.set(0f, 0f, w, h)

        // glass surface
        paint.shader = LinearGradient(0f, 0f, 0f, h, mix(accent, Theme.DEEP, 0.18f), Theme.DEEPER, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        stroke.strokeWidth = context.dp(1f)
        stroke.color = mix(accent, Theme.LINE, 0.35f)
        c.drawRoundRect(rect, r, r, stroke)

        // dot grid that lights up near the finger
        var yy = dotGap / 2
        while (yy < h) {
            var xx = dotGap / 2
            while (xx < w) {
                var a = 0.13f
                var s = context.dp(1.2f)
                if (glowA > 0f) {
                    val k = max(0f, 1f - hypot(xx - glowX, yy - glowY) / context.dp(120f)) * glowA
                    a += k * 0.8f
                    s += k * context.dp(2.2f)
                }
                paint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 243, 244, 255)
                c.drawCircle(xx, yy, s, paint)
                xx += dotGap
            }
            yy += dotGap
        }

        // comet trail + glow
        if (glowA > 0f && trail.size > 1) {
            stroke.color = accent
            for (i in 1 until trail.size) {
                val f = i.toFloat() / trail.size
                stroke.alpha = (f * glowA * 0.85f * 255).toInt().coerceIn(0, 255)
                stroke.strokeWidth = context.dp(2f + f * 7f)
                c.drawLine(trail[i - 1][0], trail[i - 1][1], trail[i][0], trail[i][1], stroke)
            }
            stroke.alpha = 255
        }
        if (glowA > 0f) {
            paint.shader = RadialGradient(glowX, glowY, context.dp(90f), (accent and 0x00FFFFFF) or ((0x88 * glowA).toInt() shl 24), accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(glowX, glowY, context.dp(90f), paint)
            paint.shader = null
        }
        if (pulse > 0f) {
            stroke.color = accent
            stroke.alpha = (pulse * 255).toInt()
            stroke.strokeWidth = context.dp(3f)
            c.drawCircle(glowX, glowY, context.dp(24f) + (1 - pulse) * context.dp(60f), stroke)
            stroke.alpha = 255
        }

        // direction chevrons flash when a swipe registers
        if (!pointerMode) {
            val now = SystemClock.uptimeMillis()
            drawChevron(c, w / 2, context.dp(26f), 0f, now - (flash["up"] ?: 0))
            drawChevron(c, w / 2, h - context.dp(26f), 180f, now - (flash["down"] ?: 0))
            drawChevron(c, context.dp(26f), h / 2, -90f, now - (flash["left"] ?: 0))
            drawChevron(c, w - context.dp(26f), h / 2, 90f, now - (flash["right"] ?: 0))
        }

        // hint
        if (!tracking && glowA <= 0f) {
            paint.color = Theme.DIM
            paint.textSize = context.dp(13f)
            paint.textAlign = Paint.Align.CENTER
            val hint = if (pointerMode) "Drag to move the pointer, tap to click, two fingers to scroll" else "Swipe to move, tap to select, hold for options"
            c.drawText(hint, w / 2, h / 2, paint)
        }

        // keep animating while anything is fading
        var again = false
        if (glowA > 0f) { glowA = if (tracking) glowA else glowA * 0.92f; if (glowA < 0.02f) { glowA = 0f; trail.clear() }; again = true }
        if (pulse > 0f) { pulse = if (pulse > 0.02f) pulse * 0.88f else 0f; again = true }
        if (flash.values.any { SystemClock.uptimeMillis() - it < 400 }) again = true
        if (again) postInvalidateOnAnimation()
    }

    private fun drawChevron(c: Canvas, cx: Float, cy: Float, rot: Float, age: Long) {
        val a = if (age < 400) 0.14f + 0.8f * (1f - age / 400f) else 0.14f
        stroke.color = Theme.SNOW
        stroke.alpha = (a * 255).toInt()
        stroke.strokeWidth = context.dp(3f)
        val s = context.dp(9f)
        c.save()
        c.rotate(rot, cx, cy)
        chevron.reset()
        chevron.moveTo(cx - s, cy + s / 2)
        chevron.lineTo(cx, cy - s / 2)
        chevron.lineTo(cx + s, cy + s / 2)
        c.drawPath(chevron, stroke)
        c.restore()
        stroke.alpha = 255
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 255
        val r = (ch(a, 16) * t + ch(b, 16) * (1 - t)).toInt()
        val g = (ch(a, 8) * t + ch(b, 8) * (1 - t)).toInt()
        val bl = (ch(a, 0) * t + ch(b, 0) * (1 - t)).toInt()
        return Color.rgb(r, g, bl)
    }
}
