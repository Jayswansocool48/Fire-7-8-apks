package io.github.jayswansocool48.deskmate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class DeckKey(
    val id: String,
    val pos: Int,
    val title: String,
    val icon: String,
    val image: Bitmap?,
    val color: Int,
    val type: String,
    val value: String,
    val keys: List<DeckKey>,
) {
    val runsOnPC get() = type == "keys" || type == "run" || type == "open" || type == "text"

    companion object {
        val BACK = DeckKey("__back", 0, "Back", "back", null, 0xFF141417.toInt(), "back", "", emptyList())

        fun parseList(arr: JSONArray?): List<DeckKey> {
            if (arr == null) return emptyList()
            val out = ArrayList<DeckKey>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parse(it)) }
            return out
        }

        private fun parse(o: JSONObject): DeckKey {
            val a = o.optJSONObject("action") ?: JSONObject()
            return DeckKey(
                o.optString("id"), o.optInt("pos"), o.optString("title"), o.optString("icon"),
                decodeImage(o.optString("image")), parseColor(o.optString("color"), 0xFF1C1C20.toInt()),
                a.optString("type"), a.optString("value"), parseList(o.optJSONArray("keys")),
            )
        }

        private fun decodeImage(dataUrl: String): Bitmap? {
            if (!dataUrl.startsWith("data:")) return null
            return try {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** What a live key shows instead of (or on top of) its icon. */
class LiveFace(
    val big: String? = null,
    val progress: Float = -1f,
    val alert: Boolean = false,
    val icon: String? = null,
    val title: String? = null,
)

/**
 * Stream Deck-style key grid, drawn by hand so it stays smooth on the Fire tablets.
 * Tap = press, hold = long press, swipe sideways = change page.
 */
class DeckView(context: Context) : View(context) {
    interface Listener {
        fun onKey(key: DeckKey)
        fun onLongKey(key: DeckKey)
        fun onSwipe(direction: Int)
        fun onSettings()
    }

    var listener: Listener? = null
    var cols = 5
    var rows = 3
    private var slots = arrayOfNulls<DeckKey>(15)
    var live: (DeckKey) -> LiveFace? = { null }
    var pageCount = 1
    var pageIndex = 0
    var showDots = true
    var statusText = ""
    var statusColor = Theme.DIM
    var offline = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private var highlight: Shader? = null
    private var highlightSize = 0f

    private var keySize = 0f
    private var gap = 0f
    private var originX = 0f
    private var originY = 0f
    private val gear = RectF()

    private var pressed = -1
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var longFired = false
    private var gearDown = false
    private var flashIndex = -1
    private var flashStart = 0L
    private var slideFrom = 0f
    private var slideStart = 0L
    private val decel = DecelerateInterpolator(2f)

    private val longPress = Runnable {
        val k = slots.getOrNull(pressed)
        if (k != null && !swiping) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onLongKey(k)
            pressed = -1
            invalidate()
        }
    }

    fun setKeys(cols: Int, rows: Int, keys: List<DeckKey>, inFolder: Boolean) {
        this.cols = cols.coerceIn(1, 8)
        this.rows = rows.coerceIn(1, 5)
        slots = arrayOfNulls(this.cols * this.rows)
        for (k in keys) if (k.pos in slots.indices && !(inFolder && k.pos == 0)) slots[k.pos] = k
        if (inFolder && slots.isNotEmpty()) slots[0] = DeckKey.BACK
        requestLayout()
        invalidate()
    }

    fun visibleKeys(): List<DeckKey> = slots.filterNotNull()

    /** Slides the grid in from the side when the page changes. */
    fun slide(direction: Int) {
        slideFrom = direction * width * 0.35f
        slideStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun layoutGrid() {
        val margin = context.dp(14f)
        val bottom = context.dp(40f)
        val aw = width - margin * 2
        val ah = height - margin - bottom
        val ratio = 0.16f
        keySize = min(aw / (cols + ratio * (cols - 1)), ah / (rows + ratio * (rows - 1)))
        gap = keySize * ratio
        val gw = keySize * cols + gap * (cols - 1)
        val gh = keySize * rows + gap * (rows - 1)
        originX = (width - gw) / 2f
        originY = margin + (ah - gh) / 2f
        val g = context.dp(40f)
        gear.set(width - margin - g, height - bottom + (bottom - g) / 2f, width - margin, height - bottom + (bottom + g) / 2f)
    }

    private fun slotAt(x: Float, y: Float): Int {
        val col = ((x - originX) / (keySize + gap)).toInt()
        val row = ((y - originY) / (keySize + gap)).toInt()
        if (x < originX || y < originY || col !in 0 until cols || row !in 0 until rows) return -1
        val lx = x - originX - col * (keySize + gap)
        val ly = y - originY - row * (keySize + gap)
        if (lx > keySize || ly > keySize) return -1
        return row * cols + col
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        layoutGrid()
        val now = SystemClock.uptimeMillis()
        var animating = false

        var offset = 0f
        if (slideFrom != 0f) {
            val t = ((now - slideStart) / 200f).coerceIn(0f, 1f)
            offset = slideFrom * (1 - decel.getInterpolation(t))
            if (t >= 1f) slideFrom = 0f else animating = true
        }

        canvas.save()
        canvas.translate(offset, 0f)
        for (i in slots.indices) {
            val x = originX + (i % cols) * (keySize + gap)
            val y = originY + (i / cols) * (keySize + gap)
            canvas.save()
            canvas.translate(x, y)
            val k = slots[i]
            if (k == null) {
                drawEmpty(canvas)
            } else {
                val scale = if (i == pressed && !swiping) 0.92f else 1f
                if (scale != 1f) canvas.scale(scale, scale, keySize / 2, keySize / 2)
                drawKey(canvas, k)
                if (i == flashIndex) {
                    val a = 1f - (now - flashStart) / 260f
                    if (a > 0f) {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb((a * 90).toInt(), 255, 255, 255)
                        rect.set(0f, 0f, keySize, keySize)
                        canvas.drawRoundRect(rect, keySize * 0.18f, keySize * 0.18f, paint)
                        animating = true
                    } else flashIndex = -1
                }
            }
            canvas.restore()
        }
        canvas.restore()
        drawBottom(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawEmpty(canvas: Canvas) {
        val r = keySize * 0.18f
        rect.set(0f, 0f, keySize, keySize)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B0B0D.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        paint.color = 0xFF17171A.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawKey(canvas: Canvas, k: DeckKey) {
        val s = keySize
        val r = s * 0.18f
        val face = live(k)
        rect.set(0f, 0f, s, s)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = k.color
        canvas.drawRoundRect(rect, r, r, paint)

        if (k.image != null) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(k.image, null, rect, bmpPaint)
            canvas.restore()
        }

        // Glassy top highlight, like a lit key cap.
        if (highlight == null || highlightSize != s) {
            highlight = LinearGradient(0f, 0f, 0f, s * 0.55f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            highlightSize = s
        }
        paint.shader = highlight
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        if (face?.alert == true) {
            paint.color = 0x66E53E4F
            canvas.drawRoundRect(rect, r, r, paint)
        }

        val title = face?.title ?: k.title
        val hasTitle = title.isNotEmpty()
        if (k.image == null) {
            val big = face?.big
            if (big != null) {
                textPaint.color = Color.WHITE
                textPaint.textSize = s * (if (big.length > 4) 0.22f else 0.27f)
                textPaint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawText(big, s / 2, s * (if (hasTitle) 0.5f else 0.58f), textPaint)
            } else {
                val icon = face?.icon ?: k.icon
                if (icon.isNotEmpty()) {
                    DeckIcons.draw(canvas, icon, s / 2, if (hasTitle) s * 0.42f else s / 2, s * 0.44f, Color.WHITE, paint)
                }
            }
            if (face != null && face.progress >= 0f) {
                val bw = s * 0.62f
                val by = s * 0.62f
                val bh = s * 0.045f
                rect.set((s - bw) / 2, by, (s + bw) / 2, by + bh)
                paint.color = 0x33FFFFFF
                canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
                rect.right = rect.left + bw * face.progress.coerceIn(0f, 1f)
                paint.color = if (face.progress > 0.9f && k.type == "stat") Theme.BAD else Theme.ACCENT
                canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
                rect.set(0f, 0f, s, s)
            }
        }

        if (k.type == "folder") {
            DeckIcons.draw(canvas, "folder", s * 0.84f, s * 0.15f, s * 0.17f, 0xAAFFFFFF.toInt(), paint)
        }

        if (hasTitle) {
            textPaint.color = Color.WHITE
            textPaint.textSize = s * 0.135f
            textPaint.setShadowLayer(s * 0.02f, 0f, s * 0.01f, 0xCC000000.toInt())
            var t = title
            while (t.length > 1 && textPaint.measureText(t) > s * 0.9f) t = t.dropLast(2) + "…"
            canvas.drawText(t, s / 2, s * 0.86f, textPaint)
            textPaint.setShadowLayer(0f, 0f, 0f, 0)
        }

        if (offline && k.runsOnPC) {
            paint.color = 0x88000000.toInt()
            rect.set(0f, 0f, s, s)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        // Thin light edge on top.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        paint.color = 0x1FFFFFFF
        rect.set(0.5f, 0.5f, s - 0.5f, s - 0.5f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBottom(canvas: Canvas) {
        val cy = gear.centerY()
        // Status, bottom left.
        paint.color = statusColor
        val margin = context.dp(18f)
        canvas.drawCircle(margin + context.dp(4f), cy, context.dp(4f), paint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = context.dp(13f)
        textPaint.color = Theme.DIM
        canvas.drawText(statusText, margin + context.dp(14f), cy + context.dp(4.5f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        // Page dots, centered.
        if (showDots && pageCount > 1) {
            val step = context.dp(16f)
            val start = width / 2f - step * (pageCount - 1) / 2f
            for (i in 0 until pageCount) {
                paint.color = if (i == pageIndex) Color.WHITE else 0xFF3A3A40.toInt()
                canvas.drawCircle(start + i * step, cy, context.dp(if (i == pageIndex) 4.5f else 3.5f), paint)
            }
        }
        // Settings gear, bottom right.
        Icons.draw(canvas, Icon.SETTINGS, gear.centerX(), cy, context.dp(24f), if (gearDown) Color.WHITE else 0xFF6E6E78.toInt(), paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                swiping = false
                longFired = false
                gearDown = gear.contains(e.x, e.y) || (e.x > gear.left - context.dp(12f) && e.y > gear.top - context.dp(8f))
                pressed = if (gearDown) -1 else slotAt(e.x, e.y)
                if (pressed >= 0 && slots[pressed] != null) postDelayed(longPress, 550) else pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!swiping && abs(dx) > context.dp(40f) && abs(dx) > abs(dy) * 1.3f) {
                    swiping = true
                    removeCallbacks(longPress)
                    invalidate()
                } else if (pressed >= 0 && slotAt(e.x, e.y) != pressed && !swiping) {
                    removeCallbacks(longPress)
                    pressed = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dx = e.x - downX
                if (swiping) {
                    listener?.onSwipe(if (dx < 0) 1 else -1)
                } else if (gearDown && abs(dx) < context.dp(20f)) {
                    listener?.onSettings()
                } else if (!longFired && pressed >= 0) {
                    val k = slots[pressed]
                    if (k != null) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        flashIndex = pressed
                        flashStart = SystemClock.uptimeMillis()
                        listener?.onKey(k)
                    }
                }
                pressed = -1
                gearDown = false
                swiping = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = -1
                gearDown = false
                swiping = false
                invalidate()
            }
        }
        return true
    }
}

