package io.github.jayswansocool48.hubremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream

/** A small sketch pad for drawn notes. Strokes are kept as points so they can be redrawn
 *  at the TV note's size (900x576, transparent) when sent. */
class DrawView(context: Context) : View(context) {
    private class Stroke(val color: Int, val width: Float, val pts: ArrayList<FloatArray> = ArrayList())

    private val strokes = ArrayList<Stroke>()
    private var cur: Stroke? = null
    var color = 0xFFECEDF0.toInt()
    var brush = 12f // in export pixels (900 wide)
    var onChange: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    val isEmpty get() = strokes.isEmpty()

    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate(); onChange?.invoke() }
    fun clear() { strokes.clear(); invalidate(); onChange?.invoke() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * 192f / 300f).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / width * 900f
        val y = e.y / height * 576f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                cur = Stroke(color, brush).also { it.pts.add(floatArrayOf(x, y)); strokes.add(it) }
            }
            MotionEvent.ACTION_MOVE -> {
                val s = cur ?: return true
                for (i in 0 until e.historySize) s.pts.add(floatArrayOf(e.getHistoricalX(i) / width * 900f, e.getHistoricalY(i) / height * 576f))
                s.pts.add(floatArrayOf(x, y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cur = null; onChange?.invoke() }
        }
        invalidate()
        return true
    }

    private fun drawStrokes(c: Canvas, scale: Float) {
        for (s in strokes) {
            paint.color = s.color
            paint.strokeWidth = s.width * scale
            val p = s.pts
            if (p.size == 1) {
                paint.style = Paint.Style.FILL
                c.drawCircle(p[0][0] * scale, p[0][1] * scale, s.width * scale / 2, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            val path = Path()
            path.moveTo(p[0][0] * scale, p[0][1] * scale)
            for (i in 1 until p.size - 1) {
                val mx = (p[i][0] + p[i + 1][0]) / 2 * scale
                val my = (p[i][1] + p[i + 1][1]) / 2 * scale
                path.quadTo(p[i][0] * scale, p[i][1] * scale, mx, my)
            }
            path.lineTo(p[p.size - 1][0] * scale, p[p.size - 1][1] * scale)
            c.drawPath(path, paint)
        }
    }

    override fun onDraw(c: Canvas) {
        val r = context.dp(20f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = Theme.DEEPER
        c.drawRoundRect(rect, r, r, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = context.dp(1f)
        bg.color = Theme.LINE
        c.drawRoundRect(rect, r, r, bg)
        bg.style = Paint.Style.FILL
        if (strokes.isEmpty()) {
            bg.color = Theme.DIM
            bg.textSize = context.dp(15f)
            bg.typeface = Fonts.regular
            bg.textAlign = Paint.Align.CENTER
            c.drawText("Draw here", width / 2f, height / 2f, bg)
        }
        c.save()
        c.clipRect(rect)
        drawStrokes(c, width / 900f)
        c.restore()
    }

    /** The drawing as a transparent 900x576 PNG, the size of the note on the TV. */
    fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(900, 576, Bitmap.Config.ARGB_8888)
        drawStrokes(Canvas(bmp), 1f)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
