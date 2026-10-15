package dev.inkwell.paint

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Floating layer list, top layer first, like ibisPaint / Procreate. */
class LayersPanel(context: Context, private val onChanged: (structural: Boolean) -> Unit) : LinearLayout(context) {
    private lateinit var doc: PaintDocument
    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val opacityLabel = TextView(context)
    private val opacityBar = SeekBar(context)
    private val thumbs = ArrayList<View>()
    private var binding = false

    init {
        orientation = VERTICAL
        background = roundedBg(Palette.PANEL, context.dp(14f))
        val pad = context.dpi(8f)
        setPadding(pad, pad, pad, pad)
        elevation = context.dp(8f)
        isClickable = true // don't let touches fall through to the canvas

        val title = TextView(context).apply {
            text = "Layers"
            setTextColor(Palette.TEXT)
            textSize = 16f
            setPadding(context.dpi(6f), 0, 0, context.dpi(4f))
        }
        addView(title)

        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun action(icon: Icon, desc: String, run: () -> Unit) {
            val b = ToolButton(context, icon)
            b.contentDescription = desc
            b.setOnClickListener { run() }
            actions.addView(b, LayoutParams(0, context.dpi(40f), 1f))
        }
        action(Icon.PLUS, "Add layer") {
            if (!doc.addLayer()) toast("Layer limit reached (${doc.maxLayers})") else onChanged(true)
        }
        action(Icon.TRASH, "Delete layer") {
            if (!doc.deleteActiveLayer()) toast("Can't delete the last layer") else onChanged(true)
        }
        action(Icon.UP, "Move layer up") { if (doc.moveActive(1)) onChanged(true) }
        action(Icon.DOWN, "Move layer down") { if (doc.moveActive(-1)) onChanged(true) }
        action(Icon.MERGE, "Merge down") {
            if (!doc.mergeDown()) toast("Nothing below to merge into") else onChanged(true)
        }
        action(Icon.CLEAR, "Clear layer") {
            doc.clearActive()
            onChanged(false)
        }
        addView(actions)

        list.orientation = VERTICAL
        scroll.addView(list)
        scroll.isVerticalScrollBarEnabled = true
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0))

        opacityLabel.setTextColor(Palette.TEXT_DIM)
        opacityLabel.textSize = 12f
        opacityLabel.setPadding(context.dpi(6f), context.dpi(8f), 0, 0)
        addView(opacityLabel)
        opacityBar.max = 100
        opacityBar.progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        opacityBar.thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (binding || !fromUser) return
                doc.activeLayer.opacity = progress / 100f
                opacityLabel.text = "Opacity ${progress}%"
                onChanged(false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        addView(opacityBar)
    }

    fun setDocument(d: PaintDocument) {
        doc = d
        refresh()
    }

    /** Rebuilds the rows (cheap: at most 16 layers). */
    fun refresh() {
        if (!::doc.isInitialized) return
        list.removeAllViews()
        thumbs.clear()
        val rowH = context.dpi(52f)
        for (i in doc.layers.indices.reversed()) list.addView(row(i), LayoutParams(LayoutParams.MATCH_PARENT, rowH))
        val maxH = min(rowH * doc.layers.size, (resources.displayMetrics.heightPixels * 0.45f).toInt())
        scroll.layoutParams = scroll.layoutParams.also { it.height = maxH }
        binding = true
        val pct = (doc.activeLayer.opacity * 100).roundToInt()
        opacityBar.progress = pct
        opacityLabel.text = "Opacity ${pct}%  ·  ${doc.layers.size}/${doc.maxLayers} layers"
        binding = false
    }

    /** Redraws thumbnails after painting, without rebuilding rows. */
    fun refreshThumbs() {
        for (t in thumbs) t.invalidate()
    }

    private fun row(index: Int): View {
        val layer = doc.layers[index]
        val active = index == doc.activeIndex
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = context.dpi(4f)
            setPadding(p, p, p, p)
            background = if (active) roundedBg(Palette.ROW_ACTIVE, context.dp(10f)) else null
            isClickable = true
            setOnClickListener {
                if (index == doc.activeIndex) rename(layer) else {
                    doc.setActive(index)
                    onChanged(true)
                }
            }
        }
        val thumb = Thumb(context, layer, doc.width.toFloat() / doc.height)
        thumbs.add(thumb)
        row.addView(thumb, LayoutParams(context.dpi(58f), LayoutParams.MATCH_PARENT))
        val name = TextView(context).apply {
            text = layer.name
            setTextColor(if (layer.visible) Palette.TEXT else Palette.TEXT_DIM)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dpi(10f), 0, context.dpi(4f), 0)
        }
        row.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val eye = ToolButton(context, if (layer.visible) Icon.EYE else Icon.EYE_OFF)
        eye.contentDescription = "Show or hide layer"
        eye.setOnClickListener {
            layer.visible = !layer.visible
            onChanged(true)
        }
        row.addView(eye, LayoutParams(context.dpi(40f), context.dpi(40f)))
        return row
    }

    private fun rename(layer: Layer) {
        val input = EditText(context).apply {
            setText(layer.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("Rename layer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    layer.name = n
                    onChanged(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private class Thumb(context: Context, private val layer: Layer, private val aspect: Float) : View(context) {
        private val checker = Paint().apply { shader = checkerShader(context.dpi(4f)) }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x44FFFFFF
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            var w = width.toFloat()
            var h = w / aspect
            if (h > height) { h = height.toFloat(); w = h * aspect }
            rect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            canvas.drawRect(rect, checker)
            canvas.drawBitmap(layer.bitmap, null, rect, bmpPaint)
            border.strokeWidth = context.dp(1f)
            border.color = if (layer.visible) 0x44FFFFFF else Color.TRANSPARENT
            canvas.drawRect(rect, border)
        }
    }
}
