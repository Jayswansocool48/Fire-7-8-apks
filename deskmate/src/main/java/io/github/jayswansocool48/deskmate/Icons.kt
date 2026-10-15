package io.github.jayswansocool48.deskmate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

enum class Icon {
    BACK, REFRESH, PC, PAD, MONITOR, MEDIA, NOTES, GAMEPAD, PLAY, PAUSE, NEXT, PREV,
    VOLUME, VOLUME_OFF, MIC, MIC_OFF, PLUS, MINUS, TRASH, EDIT, UPLOAD, DOWNLOAD, TIMER, LINK, CHECK, SETTINGS,
}

/** Line icons on a 24×24 grid, drawn in code. */
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
        val oldStyle = paint.style
        for (p in parts) {
            paint.style = if (p.fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            paint.strokeWidth = if (p.fill) 1f else p.width
            canvas.drawPath(p.path, paint)
        }
        paint.style = oldStyle
        canvas.restore()
    }

    private fun path(block: Path.() -> Unit) = Path().apply(block)
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = path { moveTo(x0, y0); lineTo(x1, y1) }
    private fun poly(vararg p: Float, close: Boolean = true) = path {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
        if (close) close()
    }
    private fun circle(x: Float, y: Float, r: Float) = path { addCircle(x, y, r, Path.Direction.CW) }
    private fun rect(l: Float, t: Float, r: Float, b: Float, rad: Float) = path {
        addRoundRect(android.graphics.RectF(l, t, r, b), rad, rad, Path.Direction.CW)
    }
    private fun speaker() = poly(4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f)
    private fun mic() = listOf(
        Part(rect(9f, 3f, 15f, 14f, 3f)),
        Part(path { moveTo(6f, 11f); cubicTo(6f, 18.5f, 18f, 18.5f, 18f, 11f) }),
        Part(line(12f, 17f, 12f, 21f)),
    )

    private fun build(icon: Icon): List<Part> = when (icon) {
        Icon.BACK -> listOf(Part(poly(15f, 5f, 8f, 12f, 15f, 19f, close = false)))
        Icon.REFRESH -> listOf(
            Part(path { moveTo(19f, 12f); cubicTo(19f, 16f, 16f, 19f, 12f, 19f); cubicTo(8f, 19f, 5f, 16f, 5f, 12f); cubicTo(5f, 8f, 8f, 5f, 12f, 5f); lineTo(17f, 5f) }),
            Part(poly(14f, 2f, 17f, 5f, 14f, 8f, close = false)),
        )
        Icon.PC -> listOf(Part(rect(3f, 4f, 21f, 16f, 2f)), Part(line(9f, 20f, 15f, 20f)), Part(line(12f, 16f, 12f, 20f)))
        Icon.PAD -> listOf(
            Part(rect(3.5f, 3.5f, 10.5f, 10.5f, 2f)), Part(rect(13.5f, 3.5f, 20.5f, 10.5f, 2f)),
            Part(rect(3.5f, 13.5f, 10.5f, 20.5f, 2f)), Part(rect(13.5f, 13.5f, 20.5f, 20.5f, 2f), fill = true),
        )
        Icon.MONITOR -> listOf(Part(poly(3f, 14f, 7f, 14f, 10f, 6f, 14f, 19f, 17f, 11f, 21f, 11f, close = false)))
        Icon.MEDIA -> listOf(
            Part(line(9f, 17f, 9f, 5f)), Part(line(9f, 5f, 19f, 3.5f)), Part(line(19f, 3.5f, 19f, 15f)),
            Part(circle(6.5f, 17.5f, 2.5f), fill = true), Part(circle(16.5f, 15.5f, 2.5f), fill = true),
        )
        Icon.NOTES -> listOf(
            Part(rect(5f, 3f, 19f, 21f, 2f)), Part(line(8.5f, 8f, 15.5f, 8f)), Part(line(8.5f, 12f, 15.5f, 12f)), Part(line(8.5f, 16f, 12.5f, 16f)),
        )
        Icon.GAMEPAD -> listOf(
            Part(path { moveTo(7f, 7f); lineTo(17f, 7f); cubicTo(21f, 7f, 22.5f, 17f, 19.5f, 18f); cubicTo(17.5f, 18.6f, 16f, 15f, 14.5f, 15f); lineTo(9.5f, 15f); cubicTo(8f, 15f, 6.5f, 18.6f, 4.5f, 18f); cubicTo(1.5f, 17f, 3f, 7f, 7f, 7f); close() }),
            Part(line(6f, 11f, 10f, 11f)), Part(line(8f, 9f, 8f, 13f)),
            Part(circle(15.5f, 10f, 0.8f), fill = true), Part(circle(17.5f, 12f, 0.8f), fill = true),
        )
        Icon.PLAY -> listOf(Part(poly(7f, 4.5f, 19f, 12f, 7f, 19.5f), fill = true))
        Icon.PAUSE -> listOf(Part(rect(6f, 4.5f, 10f, 19.5f, 1f), fill = true), Part(rect(14f, 4.5f, 18f, 19.5f, 1f), fill = true))
        Icon.NEXT -> listOf(Part(poly(5f, 5f, 15f, 12f, 5f, 19f), fill = true), Part(line(18f, 5f, 18f, 19f), width = 2.5f))
        Icon.PREV -> listOf(Part(poly(19f, 5f, 9f, 12f, 19f, 19f), fill = true), Part(line(6f, 5f, 6f, 19f), width = 2.5f))
        Icon.VOLUME -> listOf(
            Part(speaker()),
            Part(path { moveTo(15.5f, 9f); cubicTo(17f, 10.5f, 17f, 13.5f, 15.5f, 15f) }),
            Part(path { moveTo(18f, 6.5f); cubicTo(21f, 9.5f, 21f, 14.5f, 18f, 17.5f) }),
        )
        Icon.VOLUME_OFF -> listOf(Part(speaker()), Part(line(16f, 9.5f, 21f, 14.5f)), Part(line(21f, 9.5f, 16f, 14.5f)))
        Icon.MIC -> mic()
        Icon.MIC_OFF -> mic() + Part(line(4f, 3f, 20f, 21f))
        Icon.PLUS -> listOf(Part(line(12f, 5f, 12f, 19f)), Part(line(5f, 12f, 19f, 12f)))
        Icon.MINUS -> listOf(Part(line(5f, 12f, 19f, 12f)))
        Icon.TRASH -> listOf(
            Part(line(4f, 7f, 20f, 7f)), Part(poly(9.5f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 7f, close = false)),
            Part(poly(6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f, close = false)),
        )
        Icon.EDIT -> listOf(Part(poly(4f, 20f, 5f, 15.5f, 16f, 4.5f, 19.5f, 8f, 8.5f, 19f)), Part(line(13.5f, 7f, 17f, 10.5f)))
        Icon.UPLOAD -> listOf(Part(line(12f, 15f, 12f, 4f)), Part(poly(7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f, close = false)), Part(poly(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f, close = false)))
        Icon.DOWNLOAD -> listOf(Part(line(12f, 4f, 12f, 15f)), Part(poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f, close = false)), Part(poly(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f, close = false)))
        Icon.TIMER -> listOf(Part(circle(12f, 13.5f, 7.5f)), Part(line(12f, 13.5f, 12f, 9f)), Part(line(9.5f, 3f, 14.5f, 3f)))
        Icon.LINK -> listOf(
            Part(path { moveTo(10f, 14f); lineTo(14f, 10f) }),
            Part(path { moveTo(9f, 7.5f); lineTo(11f, 5.5f); cubicTo(13f, 3.5f, 16.5f, 3.5f, 18.5f, 5.5f); cubicTo(20.5f, 7.5f, 20.5f, 11f, 18.5f, 13f); lineTo(16.5f, 15f) }),
            Part(path { moveTo(15f, 16.5f); lineTo(13f, 18.5f); cubicTo(11f, 20.5f, 7.5f, 20.5f, 5.5f, 18.5f); cubicTo(3.5f, 16.5f, 3.5f, 13f, 5.5f, 11f); lineTo(7.5f, 9f) }),
        )
        Icon.CHECK -> listOf(Part(poly(5f, 12.5f, 10f, 17.5f, 19f, 7f, close = false)))
        Icon.SETTINGS -> listOf(
            Part(circle(12f, 12f, 3f)),
            Part(line(12f, 2.5f, 12f, 5.5f)), Part(line(12f, 18.5f, 12f, 21.5f)), Part(line(2.5f, 12f, 5.5f, 12f)), Part(line(18.5f, 12f, 21.5f, 12f)),
            Part(line(5.3f, 5.3f, 7.4f, 7.4f)), Part(line(16.6f, 16.6f, 18.7f, 18.7f)), Part(line(5.3f, 18.7f, 7.4f, 16.6f)), Part(line(16.6f, 7.4f, 18.7f, 5.3f)),
        )
    }
}
