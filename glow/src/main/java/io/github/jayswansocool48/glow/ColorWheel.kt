package io.github.jayswansocool48.glow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Hue around the ring, saturation from the middle out. Drag to pick. */
class ColorWheel(context: Context, val onColor: (color: Int, done: Boolean) -> Unit) : View(context) {
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hue = 0f
    private var sat = 1f

    var color: Int
        get() = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
        set(c) {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hue = hsv[0]
            sat = hsv[1]
            invalidate()
        }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val s = min(w, context.dpi(300f))
        setMeasuredDimension(w, s)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = min(w, h) / 2f
        if (r <= 1f) return
        val cx = w / 2f
        val cy = h / 2f
        val sweep = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        val radial = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        wheel.shader = ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - context.dp(4f)
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, r, wheel)
        val a = Math.toRadians(hue.toDouble())
        val kx = cx + (cos(a) * sat * r).toFloat()
        val ky = cy + (sin(a) * sat * r).toFloat()
        knob.style = Paint.Style.FILL
        knob.color = color
        c.drawCircle(kx, ky, context.dp(14f), knob)
        knob.style = Paint.Style.STROKE
        knob.strokeWidth = context.dp(3f)
        knob.color = Color.WHITE
        c.drawCircle(kx, ky, context.dp(14f), knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = min(width, height) / 2f - context.dp(4f)
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP)
                var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                if (deg < 0) deg += 360f
                hue = deg
                sat = min(1f, hypot(dx, dy) / r)
                invalidate()
                onColor(color, e.action == MotionEvent.ACTION_UP)
            }
        }
        return true
    }
}
