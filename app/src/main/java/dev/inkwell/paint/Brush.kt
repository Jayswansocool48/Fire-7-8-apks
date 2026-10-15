package dev.inkwell.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A brush is a round "dab" stamped along the stroke.
 * hardness: 1 = crisp edge, 0 = fully soft.  spacing: gap between dabs as a fraction of size.
 * flow: how much each dab adds.  grain: paper texture applied to the stroke.
 */
class BrushPreset(
    val name: String,
    val icon: Icon,
    val hardness: Float,
    val spacing: Float,
    val flow: Float,
    val grain: Boolean,
    val pressureSize: Boolean,
    val defaultSize: Float,
    val defaultOpacity: Float,
)

object Brushes {
    val presets = listOf(
        BrushPreset("Ink pen", Icon.PEN, 1f, 0.06f, 1f, false, true, 6f, 1f),
        BrushPreset("Pencil", Icon.PENCIL, 0.5f, 0.12f, 0.6f, true, true, 4f, 0.9f),
        BrushPreset("Marker", Icon.MARKER, 0.9f, 0.05f, 1f, false, false, 24f, 0.5f),
        BrushPreset("Airbrush", Icon.AIRBRUSH, 0f, 0.08f, 0.12f, false, true, 90f, 0.8f),
    )
    val eraser = BrushPreset("Eraser", Icon.ERASER, 0.8f, 0.06f, 1f, false, false, 30f, 1f)

    private const val MASK = 128
    private const val GRAIN = 96
    private val masks = HashMap<Int, Bitmap>()

    fun maskFor(hardness: Float): Bitmap =
        masks.getOrPut((hardness * 100).roundToInt()) { buildMask(hardness) }

    private fun buildMask(hardness: Float): Bitmap {
        val c = (MASK - 1) / 2f
        val px = IntArray(MASK * MASK)
        for (y in 0 until MASK) {
            for (x in 0 until MASK) {
                val d = hypot(x - c, y - c) / (MASK / 2f)
                val a = when {
                    d >= 1f -> 0f
                    d <= hardness -> 1f
                    else -> {
                        val t = (d - hardness) / (1f - hardness)
                        1f - t * t * (3f - 2f * t)
                    }
                }
                px[y * MASK + x] = (a * 255).roundToInt() shl 24
            }
        }
        val argb = Bitmap.createBitmap(px, MASK, MASK, Bitmap.Config.ARGB_8888)
        val alpha = argb.extractAlpha()
        argb.recycle()
        return alpha
    }

    /** Soft paper-grain alpha values (0..255), generated once. */
    private val grainAlpha: IntArray by lazy {
        val rnd = Random(7)
        val raw = FloatArray(GRAIN * GRAIN) { rnd.nextFloat() }
        val out = IntArray(GRAIN * GRAIN)
        for (y in 0 until GRAIN) {
            for (x in 0 until GRAIN) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    sum += raw[((y + dy + GRAIN) % GRAIN) * GRAIN + (x + dx + GRAIN) % GRAIN]
                }
                // Mix fine noise with blurred noise, then push contrast.
                val v = (0.55f * sum / 9f + 0.45f * raw[y * GRAIN + x]).coerceIn(0f, 1f)
                val a = 0.2f + 0.8f * v.pow(1.4f)
                out[y * GRAIN + x] = (a * 255).roundToInt().coerceIn(0, 255)
            }
        }
        out
    }

    fun grainTile(color: Int): Bitmap {
        val rgb = color and 0xFFFFFF
        val g = grainAlpha
        val px = IntArray(g.size) { (g[it] shl 24) or rgb }
        return Bitmap.createBitmap(px, GRAIN, GRAIN, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Draws one stroke at a time.
 *
 * Dabs go into an alpha-only [buffer]; the stroke is then laid over the layer at the chosen
 * opacity into [composite]. That way a stroke never darkens where it overlaps itself (like
 * Procreate / ibisPaint), and opacity can be applied once for the whole stroke.
 * Only the changed rectangle is updated each frame, which keeps it fast on old CPUs.
 */
class StrokeRenderer(width: Int, height: Int) {
    val buffer: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val composite: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bufCanvas = Canvas(buffer)
    private val compCanvas = Canvas(composite)
    private val canvasRect = Rect(0, 0, width, height)

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val dabRect = RectF()

    var active = false
        private set
    private var layer: Layer? = null
    private var preset = Brushes.presets[0]
    private var size = 10f
    private var stabilize = 0f
    private var mask: Bitmap? = null

    private var bx = 0f
    private var by = 0f
    private var bp = 1f
    private var residual = 0f
    private val strokeBounds = Rect()
    private val frameDirty = Rect()

    fun begin(
        layer: Layer, preset: BrushPreset, eraser: Boolean, size: Float, opacity: Float,
        color: Int, stabilizer: Float, x: Float, y: Float, pressure: Float,
    ) {
        this.layer = layer
        this.preset = preset
        this.size = size
        stabilize = stabilizer.coerceIn(0f, 0.95f)
        mask = if (preset.hardness >= 0.95f) null else Brushes.maskFor(preset.hardness)
        dabPaint.alpha = (preset.flow * 255).roundToInt().coerceIn(1, 255)

        inkPaint.reset()
        when {
            eraser -> {
                inkPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                inkPaint.color = Color.BLACK
            }
            preset.grain -> {
                inkPaint.shader = BitmapShader(Brushes.grainTile(color), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                inkPaint.color = Color.BLACK
            }
            else -> inkPaint.color = color or 0xFF000000.toInt()
        }
        inkPaint.alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()

        compCanvas.drawBitmap(layer.bitmap, 0f, 0f, copyPaint)
        strokeBounds.setEmpty()
        frameDirty.setEmpty()
        bx = x; by = y; bp = pressure
        dab(x, y, diameter(pressure))
        residual = spacingFor(pressure)
        active = true
    }

    fun move(x: Float, y: Float, pressure: Float) {
        if (!active) return
        val k = 1f - stabilize
        segment(bx + (x - bx) * k, by + (y - by) * k, bp + (pressure - bp) * k)
    }

    /** Updates [composite] where new dabs landed. Returns true if anything changed. */
    fun flush(): Boolean {
        val l = layer ?: return false
        if (frameDirty.isEmpty) return false
        if (!frameDirty.intersect(canvasRect)) {
            frameDirty.setEmpty()
            return false
        }
        compCanvas.drawBitmap(l.bitmap, frameDirty, frameDirty, copyPaint)
        compCanvas.drawBitmap(buffer, frameDirty, frameDirty, inkPaint)
        strokeBounds.union(frameDirty)
        frameDirty.setEmpty()
        return true
    }

    /** Finishes the stroke, writes it into the layer and returns the undo step. */
    fun end(x: Float, y: Float, pressure: Float): PixelAction? {
        val l = layer ?: return null
        if (stabilize > 0f) repeat(6) { move(x, y, pressure) }
        segment(x, y, pressure)
        flush()
        active = false
        layer = null
        val r = Rect(strokeBounds)
        if (r.isEmpty) return null
        val before = cropBitmap(l.bitmap, r)
        Canvas(l.bitmap).drawBitmap(composite, r, r, copyPaint)
        val after = cropBitmap(l.bitmap, r)
        bufCanvas.drawRect(r, clearPaint)
        l.dirty = true
        return PixelAction(l, r, before, after)
    }

    fun cancel() {
        if (!active) return
        val r = Rect(strokeBounds)
        r.union(frameDirty)
        if (!r.isEmpty) bufCanvas.drawRect(r, clearPaint)
        strokeBounds.setEmpty()
        frameDirty.setEmpty()
        active = false
        layer = null
    }

    private fun diameter(p: Float): Float =
        if (preset.pressureSize) max(1f, size * (0.25f + 0.75f * p)) else max(1f, size)

    private fun spacingFor(p: Float): Float = max(0.5f, diameter(p) * preset.spacing)

    private fun segment(nx: Float, ny: Float, np: Float) {
        val dx = nx - bx
        val dy = ny - by
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        var d = residual
        while (d <= len) {
            val t = d / len
            val p = bp + (np - bp) * t
            dab(bx + dx * t, by + dy * t, diameter(p))
            d += spacingFor(p)
        }
        residual = d - len
        bx = nx; by = ny; bp = np
    }

    private fun dab(x: Float, y: Float, d: Float) {
        val r = d / 2f
        val m = mask
        if (m == null) {
            bufCanvas.drawCircle(x, y, r, dabPaint)
        } else {
            dabRect.set(x - r, y - r, x + r, y + r)
            bufCanvas.drawBitmap(m, null, dabRect, dabPaint)
        }
        frameDirty.union(
            floor(x - r).toInt() - 1, floor(y - r).toInt() - 1,
            ceil(x + r).toInt() + 1, ceil(y + r).toInt() + 1,
        )
    }
}
