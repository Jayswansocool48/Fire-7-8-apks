package dev.inkwell.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

abstract class HistoryAction {
    abstract val bytes: Long
    abstract fun undo(doc: PaintDocument)
    abstract fun redo(doc: PaintDocument)
}

/** Stores only the changed rectangle of one layer, before and after. */
class PixelAction(
    private val layer: Layer,
    private val rect: Rect,
    private val before: Bitmap,
    private val after: Bitmap,
) : HistoryAction() {
    override val bytes = before.byteCount.toLong() + after.byteCount

    private fun apply(src: Bitmap) {
        Canvas(layer.bitmap).drawBitmap(src, rect.left.toFloat(), rect.top.toFloat(), copyPaint)
        layer.dirty = true
    }

    override fun undo(doc: PaintDocument) = apply(before)
    override fun redo(doc: PaintDocument) = apply(after)
}

class AddLayerAction(private val layer: Layer, private val index: Int) : HistoryAction() {
    override val bytes = 0L
    override fun undo(doc: PaintDocument) { doc.removeLayerAt(index) }
    override fun redo(doc: PaintDocument) { doc.insertLayerAt(index, layer) }
}

class DeleteLayerAction(private val layer: Layer, private val index: Int) : HistoryAction() {
    override val bytes = layer.bitmap.byteCount.toLong()
    override fun undo(doc: PaintDocument) { doc.insertLayerAt(index, layer) }
    override fun redo(doc: PaintDocument) { doc.removeLayerAt(index) }
}

class MoveLayerAction(private val from: Int, private val to: Int) : HistoryAction() {
    override val bytes = 0L
    override fun undo(doc: PaintDocument) = doc.moveLayer(to, from)
    override fun redo(doc: PaintDocument) = doc.moveLayer(from, to)
}

class MergeDownAction(
    private val upper: Layer,
    private val upperIndex: Int,
    private val pixels: PixelAction,
) : HistoryAction() {
    override val bytes = pixels.bytes + upper.bitmap.byteCount

    override fun undo(doc: PaintDocument) {
        pixels.undo(doc)
        doc.insertLayerAt(upperIndex, upper)
    }

    override fun redo(doc: PaintDocument) {
        pixels.redo(doc)
        doc.removeLayerAt(upperIndex)
    }
}

/** Undo/redo stacks with a memory budget, so old steps are dropped before RAM runs out. */
class History(private val maxBytes: Long) {
    private val undoStack = ArrayList<HistoryAction>()
    private val redoStack = ArrayList<HistoryAction>()
    private var undoBytes = 0L

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(action: HistoryAction) {
        undoStack.add(action)
        undoBytes += action.bytes
        redoStack.clear()
        trim()
    }

    fun undo(doc: PaintDocument): Boolean {
        if (undoStack.isEmpty()) return false
        // removeAt(size - 1), not removeLast(): removeLast() crashes on Android before API 35.
        val a = undoStack.removeAt(undoStack.size - 1)
        undoBytes -= a.bytes
        a.undo(doc)
        redoStack.add(a)
        return true
    }

    fun redo(doc: PaintDocument): Boolean {
        if (redoStack.isEmpty()) return false
        val a = redoStack.removeAt(redoStack.size - 1)
        a.redo(doc)
        undoStack.add(a)
        undoBytes += a.bytes
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        undoBytes = 0
    }

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > MAX_STEPS || undoBytes > maxBytes)) {
            undoBytes -= undoStack.removeAt(0).bytes
        }
    }

    companion object {
        const val MAX_STEPS = 40
    }
}
