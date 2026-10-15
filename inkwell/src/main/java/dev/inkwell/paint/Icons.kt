package dev.inkwell.paint

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

enum class Icon {
    MENU, PEN, PENCIL, MARKER, AIRBRUSH, ERASER, FILL, PICKER, UNDO, REDO, LAYERS,
    PLUS, TRASH, EYE, EYE_OFF, UP, DOWN, MERGE, CLEAR,
}

/** Line icons drawn in code on a 24×24 grid, so the app needs no image resources. */
object Icons {
    private class Part(val path: Path, val fill: Boolean = false, val width: Float = 2f)

    private val cache = HashMap<Icon, List<Part>>()

    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        val parts = cache.getOrPut(icon) { build(icon) }
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (p in parts) {
            paint.style = if (p.fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            paint.strokeWidth = if (p.fill) 1f else p.width
            canvas.drawPath(p.path, paint)
        }
        canvas.restore()
    }

    private fun path(block: Path.() -> Unit) = Path().apply(block)
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = path { moveTo(x0, y0); lineTo(x1, y1) }
    private fun poly(vararg pts: Float, close: Boolean = true) = path {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) close()
    }
    private fun circle(x: Float, y: Float, r: Float) = path { addCircle(x, y, r, Path.Direction.CW) }
    private fun eye() = path {
        moveTo(2f, 12f)
        cubicTo(5.5f, 5.5f, 18.5f, 5.5f, 22f, 12f)
        cubicTo(18.5f, 18.5f, 5.5f, 18.5f, 2f, 12f)
        close()
    }

    private fun build(icon: Icon): List<Part> = when (icon) {
        Icon.MENU -> listOf(Part(line(4f, 6f, 20f, 6f)), Part(line(4f, 12f, 20f, 12f)), Part(line(4f, 18f, 20f, 18f)))
        Icon.PEN -> listOf(
            Part(poly(4f, 20f, 6f, 14f, 16f, 4f, 20f, 8f, 10f, 18f)),
            Part(line(14f, 6f, 18f, 10f)),
        )
        Icon.PENCIL -> listOf(
            Part(poly(4f, 20f, 5.5f, 15f, 16f, 4.5f, 19.5f, 8f, 9f, 18.5f)),
            Part(line(5.5f, 15f, 9f, 18.5f)),
            Part(poly(4f, 20f, 4.8f, 17.2f, 6.8f, 19.2f), fill = true),
        )
        Icon.MARKER -> listOf(
            Part(poly(8f, 14f, 15f, 7f, 19f, 11f, 12f, 18f)),
            Part(poly(8f, 14f, 4.5f, 19.5f, 12f, 18f), fill = true),
        )
        Icon.AIRBRUSH -> listOf(
            Part(circle(12f, 12f, 3.5f), fill = true),
            Part(circle(5f, 7f, 1.1f), fill = true),
            Part(circle(18.5f, 6f, 0.9f), fill = true),
            Part(circle(18f, 17.5f, 1.2f), fill = true),
            Part(circle(6f, 18f, 0.9f), fill = true),
            Part(circle(12f, 4.5f, 0.8f), fill = true),
            Part(circle(20f, 12f, 0.8f), fill = true),
            Part(circle(4f, 12.5f, 0.8f), fill = true),
            Part(circle(12f, 19.5f, 0.9f), fill = true),
        )
        Icon.ERASER -> listOf(
            Part(poly(4f, 15f, 13f, 6f, 19f, 12f, 12f, 19f, 8f, 19f)),
            Part(line(8.5f, 10.5f, 14.5f, 16.5f)),
            Part(line(12f, 19f, 20f, 19f)),
        )
        Icon.FILL -> listOf(
            Part(poly(4f, 12f, 11f, 5f, 18f, 12f, 11f, 19f)),
            Part(line(4f, 12f, 18f, 12f)),
            Part(line(11f, 5f, 8f, 2f)),
            Part(circle(20.5f, 17f, 1.6f), fill = true),
        )
        Icon.PICKER -> listOf(
            Part(line(4f, 20f, 13f, 11f)),
            Part(line(13.5f, 10.5f, 17.5f, 6.5f), width = 5f),
            Part(line(11f, 8f, 16f, 13f)),
        )
        Icon.UNDO -> listOf(
            Part(poly(9f, 14f, 4f, 9f, 9f, 4f, close = false)),
            Part(path { moveTo(4f, 9f); lineTo(15f, 9f); cubicTo(21.67f, 9f, 21.67f, 19f, 15f, 19f); lineTo(11f, 19f) }),
        )
        Icon.REDO -> listOf(
            Part(poly(15f, 14f, 20f, 9f, 15f, 4f, close = false)),
            Part(path { moveTo(20f, 9f); lineTo(9f, 9f); cubicTo(2.33f, 9f, 2.33f, 19f, 9f, 19f); lineTo(13f, 19f) }),
        )
        Icon.LAYERS -> listOf(
            Part(poly(12f, 3.5f, 20.5f, 8f, 12f, 12.5f, 3.5f, 8f)),
            Part(poly(3.5f, 12f, 12f, 16.5f, 20.5f, 12f, close = false)),
            Part(poly(3.5f, 16f, 12f, 20.5f, 20.5f, 16f, close = false)),
        )
        Icon.PLUS -> listOf(Part(line(12f, 5f, 12f, 19f)), Part(line(5f, 12f, 19f, 12f)))
        Icon.TRASH -> listOf(
            Part(line(4f, 7f, 20f, 7f)),
            Part(poly(9.5f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 7f, close = false)),
            Part(poly(6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f, close = false)),
            Part(line(10f, 10.5f, 10f, 16.5f)),
            Part(line(14f, 10.5f, 14f, 16.5f)),
        )
        Icon.EYE -> listOf(Part(eye()), Part(circle(12f, 12f, 3f)))
        Icon.EYE_OFF -> listOf(Part(eye()), Part(circle(12f, 12f, 3f)), Part(line(4f, 4f, 20f, 20f)))
        Icon.UP -> listOf(Part(line(12f, 19f, 12f, 5f)), Part(poly(6f, 11f, 12f, 5f, 18f, 11f, close = false)))
        Icon.DOWN -> listOf(Part(line(12f, 5f, 12f, 19f)), Part(poly(6f, 13f, 12f, 19f, 18f, 13f, close = false)))
        Icon.MERGE -> listOf(
            Part(line(12f, 4f, 12f, 14f)),
            Part(poly(8f, 10f, 12f, 14f, 16f, 10f, close = false)),
            Part(line(5f, 19f, 19f, 19f)),
        )
        Icon.CLEAR -> listOf(
            Part(poly(5f, 5f, 19f, 5f, 19f, 19f, 5f, 19f)),
            Part(line(9f, 9f, 15f, 15f)),
            Part(line(15f, 9f, 9f, 15f)),
        )
    }
}
