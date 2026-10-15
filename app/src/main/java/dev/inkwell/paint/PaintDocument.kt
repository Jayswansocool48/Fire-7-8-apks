package dev.inkwell.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The artwork: a stack of layers (index 0 = bottom) plus undo history. */
class PaintDocument(val width: Int, val height: Int) {
    val layers = ArrayList<Layer>()
    var activeIndex = 0
        private set
    var nextLayerId = 1L
    var layerCounter = 0
    val history: History
    val maxLayers: Int

    init {
        val heap = Runtime.getRuntime().maxMemory()
        val perLayer = width.toLong() * height * 4
        // Reserve room for the view's caches and the stroke buffers.
        val overhead = perLayer * 3 + width.toLong() * height
        maxLayers = ((heap / 2 - overhead) / perLayer).toInt().coerceIn(2, 16)
        history = History((heap / 4).coerceIn(12L shl 20, 96L shl 20))
    }

    val activeLayer: Layer get() = layers[activeIndex]
    val bounds: Rect get() = Rect(0, 0, width, height)

    fun newLayer(name: String): Layer =
        Layer(nextLayerId++, name, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

    fun canAddLayer() = layers.size < maxLayers

    fun setActive(index: Int) {
        activeIndex = index.coerceIn(0, layers.size - 1)
    }

    // --- Raw structure edits (used by history; no undo recorded) ---

    fun insertLayerAt(index: Int, layer: Layer) {
        layers.add(index, layer)
        activeIndex = index
    }

    fun removeLayerAt(index: Int): Layer {
        val l = layers.removeAt(index)
        activeIndex = (index - 1).coerceAtLeast(0).coerceAtMost(layers.size - 1)
        return l
    }

    fun moveLayer(from: Int, to: Int) {
        val l = layers.removeAt(from)
        layers.add(to, l)
        activeIndex = to
    }

    // --- User operations (recorded in history) ---

    fun addLayer(): Boolean {
        if (!canAddLayer()) return false
        layerCounter++
        val layer = newLayer("Layer $layerCounter")
        val i = activeIndex + 1
        insertLayerAt(i, layer)
        history.push(AddLayerAction(layer, i))
        return true
    }

    fun addImageLayer(src: Bitmap): Boolean {
        if (!canAddLayer()) return false
        val layer = newLayer("Image")
        val s = min(1f, min(width.toFloat() / src.width, height.toFloat() / src.height))
        val dw = src.width * s
        val dh = src.height * s
        val dst = RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2)
        Canvas(layer.bitmap).drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val i = activeIndex + 1
        insertLayerAt(i, layer)
        history.push(AddLayerAction(layer, i))
        return true
    }

    fun deleteActiveLayer(): Boolean {
        if (layers.size <= 1) return false
        val i = activeIndex
        val l = removeLayerAt(i)
        history.push(DeleteLayerAction(l, i))
        return true
    }

    fun moveActive(delta: Int): Boolean {
        val from = activeIndex
        val to = from + delta
        if (to < 0 || to >= layers.size) return false
        moveLayer(from, to)
        history.push(MoveLayerAction(from, to))
        return true
    }

    fun mergeDown(): Boolean {
        val i = activeIndex
        if (i == 0) return false
        val upper = layers[i]
        val lower = layers[i - 1]
        val r = bounds
        val before = cropBitmap(lower.bitmap, r)
        if (upper.visible) {
            val p = Paint().apply { alpha = (upper.opacity * 255).roundToInt() }
            Canvas(lower.bitmap).drawBitmap(upper.bitmap, 0f, 0f, p)
        }
        val after = cropBitmap(lower.bitmap, r)
        lower.dirty = true
        removeLayerAt(i)
        history.push(MergeDownAction(upper, i, PixelAction(lower, r, before, after)))
        return true
    }

    fun clearActive() {
        val l = activeLayer
        val r = bounds
        val before = cropBitmap(l.bitmap, r)
        l.bitmap.eraseColor(Color.TRANSPARENT)
        val after = cropBitmap(l.bitmap, r)
        l.dirty = true
        history.push(PixelAction(l, r, before, after))
    }

    /** Color of all visible layers combined at one pixel, or null if it's transparent. */
    fun sampleColor(x: Int, y: Int): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        val px = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(px)
        val p = Paint()
        for (l in layers) {
            if (!l.visible) continue
            p.alpha = (l.opacity * 255).roundToInt()
            c.drawBitmap(l.bitmap, -x.toFloat(), -y.toFloat(), p)
        }
        val v = px.getPixel(0, 0)
        px.recycle()
        return if (Color.alpha(v) < 8) null else v or 0xFF000000.toInt()
    }

    fun flatten(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint()
        for (l in layers) {
            if (!l.visible) continue
            p.alpha = (l.opacity * 255).roundToInt()
            c.drawBitmap(l.bitmap, 0f, 0f, p)
        }
        return out
    }

    /**
     * Paint-bucket fill on the active layer. Scanline fill, then grows the area by one
     * pixel so it tucks under anti-aliased line edges instead of leaving a halo.
     */
    fun floodFill(sx: Int, sy: Int, color: Int, opacity: Float, tolerance: Int = 40): Boolean {
        if (sx !in 0 until width || sy !in 0 until height) return false
        val layer = activeLayer
        val w = width
        val h = height
        val px = IntArray(w * h)
        layer.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val target = px[sy * w + sx]
        val mask = BooleanArray(w * h)

        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        var stack = IntArray(4096)
        var sp = 0
        stack[sp++] = sy * w + sx
        while (sp > 0) {
            val idx = stack[--sp]
            if (mask[idx]) continue
            val y = idx / w
            val row = y * w
            var lx = idx - row
            var rx = lx
            while (lx > 0 && !mask[row + lx - 1] && similar(px[row + lx - 1], target, tolerance)) lx--
            while (rx < w - 1 && !mask[row + rx + 1] && similar(px[row + rx + 1], target, tolerance)) rx++
            for (x in lx..rx) mask[row + x] = true
            if (lx < minX) minX = lx
            if (rx > maxX) maxX = rx
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            var pass = 0
            while (pass < 2) {
                val ny = if (pass == 0) y - 1 else y + 1
                pass++
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var inSpan = false
                for (x in lx..rx) {
                    val i = nrow + x
                    val ok = !mask[i] && similar(px[i], target, tolerance)
                    if (ok && !inSpan) {
                        if (sp == stack.size) stack = stack.copyOf(sp * 2)
                        stack[sp++] = i
                        inSpan = true
                    } else if (!ok) {
                        inSpan = false
                    }
                }
            }
        }

        // Grow by one pixel.
        val x0 = max(0, minX - 1); val x1 = min(w - 1, maxX + 1)
        val y0 = max(0, minY - 1); val y1 = min(h - 1, maxY + 1)
        var extra = IntArray(1024)
        var en = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val i = y * w + x
                if (mask[i]) continue
                if ((x > 0 && mask[i - 1]) || (x < w - 1 && mask[i + 1]) ||
                    (y > 0 && mask[i - w]) || (y < h - 1 && mask[i + w])
                ) {
                    if (en == extra.size) extra = extra.copyOf(en * 2)
                    extra[en++] = i
                }
            }
        }
        for (k in 0 until en) mask[extra[k]] = true

        // Blend the fill color over what's there (source-over, unpremultiplied).
        val sa = opacity.coerceIn(0f, 1f) * Color.alpha(color) / 255f
        val sr = Color.red(color); val sg = Color.green(color); val sb = Color.blue(color)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val i = y * w + x
                if (!mask[i]) continue
                val d = px[i]
                val da = (d ushr 24) / 255f
                val f = da * (1f - sa)
                val oa = sa + f
                if (oa <= 0f) { px[i] = 0; continue }
                val r = ((sr * sa + ((d shr 16) and 0xFF) * f) / oa).roundToInt().coerceIn(0, 255)
                val g = ((sg * sa + ((d shr 8) and 0xFF) * f) / oa).roundToInt().coerceIn(0, 255)
                val b = ((sb * sa + (d and 0xFF) * f) / oa).roundToInt().coerceIn(0, 255)
                px[i] = ((oa * 255).roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val rect = Rect(x0, y0, x1 + 1, y1 + 1)
        val before = cropBitmap(layer.bitmap, rect)
        layer.bitmap.setPixels(px, y0 * w + x0, w, x0, y0, rect.width(), rect.height())
        val after = cropBitmap(layer.bitmap, rect)
        layer.dirty = true
        history.push(PixelAction(layer, rect, before, after))
        return true
    }

    private fun similar(a: Int, b: Int, tol: Int): Boolean {
        val aa = a ushr 24
        val ba = b ushr 24
        if (aa < 16 && ba < 16) return true
        if (abs(aa - ba) > tol) return false
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol
    }

    companion object {
        fun createBlank(width: Int, height: Int): PaintDocument {
            val doc = PaintDocument(width, height)
            val bg = doc.newLayer("Background")
            bg.bitmap.eraseColor(Color.WHITE)
            doc.layers.add(bg)
            doc.layerCounter = 1
            doc.layers.add(doc.newLayer("Layer 1"))
            doc.setActive(1)
            return doc
        }
    }
}
