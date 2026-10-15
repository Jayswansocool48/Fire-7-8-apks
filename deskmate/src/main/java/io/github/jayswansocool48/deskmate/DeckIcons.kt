package io.github.jayswansocool48.deskmate

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject

/**
 * The key icons, received from the PC (icons.json) so the tablet and the settings page
 * always draw the same set. Cached so the deck still looks right while offline.
 */
object DeckIcons {
    private class Shape(val path: Path, val fill: Boolean)

    private var raw = JSONObject()
    private val cache = HashMap<String, List<Shape>>()

    fun load(prefs: SharedPreferences) {
        raw = try { JSONObject(prefs.getString("icons", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        cache.clear()
    }

    fun update(icons: JSONObject, prefs: SharedPreferences) {
        raw = icons
        cache.clear()
        prefs.edit().putString("icons", icons.toString()).apply()
    }

    fun has(name: String?) = !name.isNullOrEmpty() && raw.has(name)

    /** Draws a 24×24 line icon centered at (cx, cy). Returns false if the icon is unknown. */
    fun draw(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int, paint: Paint): Boolean {
        val shapes = cache.getOrPut(name) { build(name) }
        if (shapes.isEmpty()) return false
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        paint.color = color
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (s in shapes) {
            paint.style = if (s.fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            canvas.drawPath(s.path, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.restore()
        return true
    }

    private fun build(name: String): List<Shape> {
        val arr = raw.optJSONArray(name) ?: return emptyList()
        val out = ArrayList<Shape>()
        for (i in 0 until arr.length()) {
            var s = arr.optString(i)
            val fill = s.startsWith("F")
            if (fill) s = s.substring(1)
            val path = try {
                when {
                    s.startsWith("c:") -> {
                        val n = s.substring(2).split(",").map { it.trim().toFloat() }
                        Path().apply { addCircle(n[0], n[1], n[2], Path.Direction.CW) }
                    }
                    s.startsWith("r:") -> {
                        val n = s.substring(2).split(",").map { it.trim().toFloat() }
                        val r = n.getOrElse(4) { 0f }
                        Path().apply { addRoundRect(RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]), r, r, Path.Direction.CW) }
                    }
                    else -> SvgPath.parse(s)
                }
            } catch (e: Exception) {
                continue
            }
            out.add(Shape(path, fill))
        }
        return out
    }
}

/** Minimal SVG path-data parser: M L H V C S Q Z, absolute and relative. */
object SvgPath {
    fun parse(d: String): Path {
        val p = Path()
        val t = Tokens(d)
        var cx = 0f; var cy = 0f
        var sx = 0f; var sy = 0f
        var c2x = 0f; var c2y = 0f
        var lastCubic = false
        var cmd = ' '
        while (t.more()) {
            if (t.atCommand()) cmd = t.command() else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            var cubic = false
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + t.num(); cy = oy + t.num()
                    p.moveTo(cx, cy)
                    sx = cx; sy = cy
                    cmd = if (rel) 'l' else 'L' // further pairs are lines
                }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); p.lineTo(cx, cy) }
                'H' -> { cx = ox + t.num(); p.lineTo(cx, cy) }
                'V' -> { cy = oy + t.num(); p.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    c2x = ox + t.num(); c2y = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.cubicTo(x1, y1, c2x, c2y, cx, cy)
                    cubic = true
                }
                'S' -> {
                    val x1 = if (lastCubic) 2 * cx - c2x else cx
                    val y1 = if (lastCubic) 2 * cy - c2y else cy
                    c2x = ox + t.num(); c2y = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.cubicTo(x1, y1, c2x, c2y, cx, cy)
                    cubic = true
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.quadTo(x1, y1, cx, cy)
                }
                'Z' -> {
                    p.close()
                    cx = sx; cy = sy
                    cmd = ' '
                }
                else -> return p
            }
            lastCubic = cubic
        }
        return p
    }

    private class Tokens(val s: String) {
        var i = 0
        private fun skip() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }
        fun more(): Boolean { skip(); return i < s.length }
        fun atCommand(): Boolean { skip(); return i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E' }
        fun command(): Char { skip(); return s[i++] }
        fun num(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && !dot))) {
                if (s[i] == '.') dot = true
                i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (start == i) throw IllegalArgumentException("number expected at $i in $s")
            return s.substring(start, i).toFloat()
        }
    }
}
