package dev.inkwell.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The drawing surface.
 *  • 1 finger / stylus: draw with the current tool
 *  • 2 fingers: pan, pinch-zoom, rotate (snaps to 90°)
 *  • 2-finger tap: undo   • 3-finger tap: redo
 *
 * To stay fast on weak GPUs it draws only three bitmaps per frame: all layers below the
 * active one (cached), the active layer (live while stroking), and all layers above (cached).
 */
class CanvasView(context: Context, private val tools: ToolState) : View(context) {

    interface Listener {
        fun onColorPicked(color: Int)
        fun onToolChanged()
        fun onHistoryChanged()
        fun onLayerContentChanged()
        fun onMessage(text: String)
    }

    var listener: Listener? = null
    lateinit var doc: PaintDocument
        private set
    private lateinit var renderer: StrokeRenderer

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val pt = FloatArray(2)
    private val mv = FloatArray(9)

    private var below: Bitmap? = null
    private var above: Bitmap? = null
    private var hasBelow = false
    private var hasAbove = false
    private var cacheValid = false

    private val viewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkerPaint = Paint().apply { shader = checkerShader(12) }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
        color = 0x55000000
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var previewRadius = 0f
    private var previewUntil = 0L

    fun setDocument(d: PaintDocument) {
        if (::renderer.isInitialized) renderer.cancel()
        doc = d
        renderer = StrokeRenderer(d.width, d.height)
        below = null
        above = null
        cacheValid = false
        if (width > 0) fitToView()
        invalidate()
    }

    /** Call after anything that changes layers other than the live stroke. */
    fun invalidateCaches() {
        cacheValid = false
        invalidate()
    }

    // ---------------------------------------------------------------- view transform

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (::renderer.isInitialized) fitToView()
    }

    fun fitToView() {
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / doc.width, height.toFloat() / doc.height) * 0.92f
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        viewMatrix.postTranslate((width - doc.width * s) / 2f, (height - doc.height * s) / 2f)
        invalidate()
    }

    fun flipView() {
        viewMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        invalidate()
    }

    private fun currentScale(): Float {
        viewMatrix.getValues(mv)
        return sqrt(mv[Matrix.MSCALE_X] * mv[Matrix.MSCALE_X] + mv[Matrix.MSKEW_Y] * mv[Matrix.MSKEW_Y])
    }

    private fun currentRotation(): Float {
        viewMatrix.getValues(mv)
        return Math.toDegrees(atan2(mv[Matrix.MSKEW_Y].toDouble(), mv[Matrix.MSCALE_X].toDouble())).toFloat()
    }

    private fun toCanvas(x: Float, y: Float): FloatArray {
        viewMatrix.invert(inverse)
        pt[0] = x; pt[1] = y
        inverse.mapPoints(pt)
        return pt
    }

    /** Briefly shows the brush size as a circle in the middle of the screen. */
    fun showBrushPreview(diameter: Float) {
        previewRadius = diameter / 2f * currentScale()
        previewUntil = SystemClock.uptimeMillis() + 700
        invalidate()
        postDelayed({ invalidate() }, 750)
    }

    // ---------------------------------------------------------------- drawing

    private fun ensureCaches() {
        if (cacheValid) return
        val w = doc.width
        val h = doc.height
        val b = below?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { below = it }
        val a = above?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { above = it }
        val p = Paint()
        b.eraseColor(Color.TRANSPARENT)
        a.eraseColor(Color.TRANSPARENT)
        val cb = Canvas(b)
        val ca = Canvas(a)
        hasBelow = false
        hasAbove = false
        for ((i, l) in doc.layers.withIndex()) {
            if (i == doc.activeIndex || !l.visible) continue
            p.alpha = (l.opacity * 255).roundToInt()
            if (i < doc.activeIndex) {
                cb.drawBitmap(l.bitmap, 0f, 0f, p)
                hasBelow = true
            } else {
                ca.drawBitmap(l.bitmap, 0f, 0f, p)
                hasAbove = true
            }
        }
        cacheValid = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.BG)
        if (!::renderer.isInitialized) return
        ensureCaches()
        val dw = doc.width.toFloat()
        val dh = doc.height.toFloat()

        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawRect(0f, 0f, dw, dh, checkerPaint)
        // Smooth when zoomed out, crisp pixels when zoomed in.
        viewPaint.isFilterBitmap = currentScale() < 2f
        viewPaint.alpha = 255
        if (hasBelow) canvas.drawBitmap(below!!, 0f, 0f, viewPaint)
        val active = doc.activeLayer
        if (active.visible) {
            viewPaint.alpha = (active.opacity * 255).roundToInt()
            canvas.drawBitmap(if (renderer.active) renderer.composite else active.bitmap, 0f, 0f, viewPaint)
            viewPaint.alpha = 255
        }
        if (hasAbove) canvas.drawBitmap(above!!, 0f, 0f, viewPaint)
        canvas.drawRect(0f, 0f, dw, dh, borderPaint)
        canvas.restore()

        if (SystemClock.uptimeMillis() < previewUntil) {
            val cx = width / 2f
            val cy = height / 2f
            val r = max(1f, previewRadius)
            previewPaint.strokeWidth = context.dp(3f)
            previewPaint.color = 0x99000000.toInt()
            canvas.drawCircle(cx, cy, r, previewPaint)
            previewPaint.strokeWidth = context.dp(1.5f)
            previewPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, previewPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    private enum class Mode { NONE, DRAW, GESTURE, TAP_TOOL, BLOCKED }

    private var mode = Mode.NONE
    private var drawPointerId = -1
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var travel = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0

    private var gestureStart = 0L
    private var gestureMoved = false
    private var anchored = false
    private var idA = -1
    private var idB = -1
    private val startMatrix = Matrix()
    private var startCx = 0f
    private var startCy = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRotation = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::renderer.isInitialized) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                when (mode) {
                    Mode.DRAW -> {
                        // A second finger right after the first means a gesture, not a dot.
                        if (e.eventTime - downTime < 250 && travel < context.dp(24f)) {
                            renderer.cancel()
                            invalidate()
                            startGesture(e, -1)
                        }
                    }
                    else -> startGesture(e, -1)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> continueStroke(e)
                Mode.GESTURE -> updateGesture(e)
                Mode.TAP_TOOL -> {
                    travel = max(travel, hypot(e.x - downX, e.y - downY))
                    if (tools.tool == Tool.PICKER) pickAt(e.x, e.y)
                }
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.GESTURE) {
                    if (e.pointerCount - 1 >= 2) startGesture(e, e.actionIndex) else anchored = false
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.DRAW -> endStroke(e)
                    Mode.GESTURE -> {
                        if (!gestureMoved && e.eventTime - gestureStart < 300) {
                            if (maxPointers == 2) undo() else if (maxPointers >= 3) redo()
                        }
                    }
                    Mode.TAP_TOOL -> {
                        if (tools.tool == Tool.FILL && travel < context.dp(12f)) fillAt(e.x, e.y)
                        if (tools.tool == Tool.PICKER) {
                            tools.select(tools.previousTool)
                            listener?.onToolChanged()
                        }
                    }
                    else -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW) renderer.cancel()
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        downTime = e.eventTime
        downX = e.x
        downY = e.y
        travel = 0f
        maxPointers = 1
        gestureStart = 0L
        gestureMoved = false
        when (tools.tool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (!doc.activeLayer.visible) {
                    listener?.onMessage("This layer is hidden")
                    mode = Mode.BLOCKED
                } else {
                    mode = Mode.DRAW
                    beginStroke(e)
                }
            }
            Tool.FILL -> mode = Mode.TAP_TOOL
            Tool.PICKER -> {
                mode = Mode.TAP_TOOL
                pickAt(e.x, e.y)
            }
        }
    }

    private fun pressureOf(e: MotionEvent, index: Int, hist: Int): Float {
        if (e.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return 1f
        val p = if (hist < 0) e.getPressure(index) else e.getHistoricalPressure(index, hist)
        return p.coerceIn(0.05f, 1f)
    }

    private fun beginStroke(e: MotionEvent) {
        drawPointerId = e.getPointerId(0)
        lastX = e.x
        lastY = e.y
        val c = toCanvas(e.x, e.y)
        renderer.begin(
            doc.activeLayer, tools.strokePreset, tools.tool == Tool.ERASER, tools.size,
            tools.opacity, tools.color, tools.stabilizer, c[0], c[1], pressureOf(e, 0, -1),
        )
        renderer.flush()
        invalidate()
    }

    private fun continueStroke(e: MotionEvent) {
        val i = e.findPointerIndex(drawPointerId)
        if (i < 0) return
        for (h in 0 until e.historySize) {
            addPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), pressureOf(e, i, h))
        }
        addPoint(e.getX(i), e.getY(i), pressureOf(e, i, -1))
        if (renderer.flush()) invalidate()
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        travel += hypot(x - lastX, y - lastY)
        lastX = x
        lastY = y
        val c = toCanvas(x, y)
        renderer.move(c[0], c[1], p)
    }

    private fun endStroke(e: MotionEvent) {
        val i = e.findPointerIndex(drawPointerId).coerceAtLeast(0)
        val c = toCanvas(e.getX(i), e.getY(i))
        val action = renderer.end(c[0], c[1], pressureOf(e, i, -1))
        if (action != null) {
            doc.history.push(action)
            listener?.onHistoryChanged()
            listener?.onLayerContentChanged()
        }
        invalidate()
    }

    private fun pickAt(x: Float, y: Float) {
        val c = toCanvas(x, y)
        val color = doc.sampleColor(c[0].toInt(), c[1].toInt()) ?: return
        listener?.onColorPicked(color)
    }

    private fun fillAt(x: Float, y: Float) {
        if (!doc.activeLayer.visible) {
            listener?.onMessage("This layer is hidden")
            return
        }
        val c = toCanvas(x, y)
        if (doc.floodFill(c[0].toInt(), c[1].toInt(), tools.color, tools.opacity)) {
            invalidate()
            listener?.onHistoryChanged()
            listener?.onLayerContentChanged()
        }
    }

    fun undo() {
        if (renderer.active) return
        if (doc.history.undo(doc)) {
            invalidateCaches()
            listener?.onHistoryChanged()
            listener?.onLayerContentChanged()
        }
    }

    fun redo() {
        if (renderer.active) return
        if (doc.history.redo(doc)) {
            invalidateCaches()
            listener?.onHistoryChanged()
            listener?.onLayerContentChanged()
        }
    }

    // ---------------------------------------------------------------- 2-finger gestures

    private fun startGesture(e: MotionEvent, excludeIndex: Int) {
        mode = Mode.GESTURE
        if (gestureStart == 0L) gestureStart = e.eventTime
        var a = -1
        var b = -1
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex) continue
            if (a < 0) a = i else if (b < 0) { b = i; break }
        }
        if (a < 0 || b < 0) {
            anchored = false
            return
        }
        idA = e.getPointerId(a)
        idB = e.getPointerId(b)
        startCx = (e.getX(a) + e.getX(b)) / 2f
        startCy = (e.getY(a) + e.getY(b)) / 2f
        startDist = max(1f, hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a)))
        startAngle = angle(e.getX(a), e.getY(a), e.getX(b), e.getY(b))
        startMatrix.set(viewMatrix)
        startScale = currentScale()
        startRotation = currentRotation()
        anchored = true
    }

    private fun angle(x0: Float, y0: Float, x1: Float, y1: Float) =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    private fun updateGesture(e: MotionEvent) {
        if (!anchored) return
        val a = e.findPointerIndex(idA)
        val b = e.findPointerIndex(idB)
        if (a < 0 || b < 0) return
        val cx = (e.getX(a) + e.getX(b)) / 2f
        val cy = (e.getY(a) + e.getY(b)) / 2f
        val dist = max(1f, hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a)))
        var rot = angle(e.getX(a), e.getY(a), e.getX(b), e.getY(b)) - startAngle
        while (rot > 180f) rot -= 360f
        while (rot < -180f) rot += 360f

        val slop = context.dp(12f)
        if (abs(cx - startCx) + abs(cy - startCy) > slop || abs(dist - startDist) > slop || abs(rot) > 8f) {
            gestureMoved = true
        }

        val targetScale = (startScale * dist / startDist).coerceIn(0.1f, 24f)
        val s = targetScale / startScale

        // Snap to 0/90/180/270° when close.
        val total = startRotation + rot
        val nearest = Math.round(total / 90f) * 90f
        if (abs(total - nearest) < 6f) rot += nearest - total

        viewMatrix.set(startMatrix)
        viewMatrix.postScale(s, s, startCx, startCy)
        viewMatrix.postRotate(rot, startCx, startCy)
        viewMatrix.postTranslate(cx - startCx, cy - startCy)
        invalidate()
    }
}
