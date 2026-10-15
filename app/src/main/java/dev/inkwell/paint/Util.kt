package dev.inkwell.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)

object Palette {
    val BG = 0xFF2A2A30.toInt()
    val BAR = 0xFF1C1C21.toInt()
    val PANEL = 0xF01C1C21.toInt()
    val ROW_ACTIVE = 0xFF33384F.toInt()
    val TEXT = 0xFFE8E8EC.toInt()
    val TEXT_DIM = 0xFF9A9AA6.toInt()
    val ACCENT = 0xFF7C9CFF.toInt()
}

/** Copies pixels exactly (no blending). Only used on the UI thread. */
val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

fun cropBitmap(src: Bitmap, r: Rect): Bitmap {
    val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, r, Rect(0, 0, r.width(), r.height()), copyPaint)
    return out
}

fun roundedBg(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

/** Transparency checkerboard, shown behind the artwork. */
fun checkerShader(cell: Int): BitmapShader {
    val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val c = Canvas(tile)
    c.drawColor(0xFFFFFFFF.toInt())
    val p = Paint().apply { color = 0xFFDCDCE0.toInt() }
    c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
    c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
    return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}
