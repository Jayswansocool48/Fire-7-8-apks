package dev.inkwell.paint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity(), CanvasView.Listener {

    private val tools = ToolState()
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ProjectStore
    private lateinit var doc: PaintDocument

    private lateinit var canvasView: CanvasView
    private lateinit var layersPanel: LayersPanel
    private lateinit var sizeSlider: VerticalSlider
    private lateinit var opacitySlider: VerticalSlider
    private lateinit var colorButton: SwatchView
    private lateinit var brushBtn: ToolButton
    private lateinit var eraserBtn: ToolButton
    private lateinit var fillBtn: ToolButton
    private lateinit var pickerBtn: ToolButton
    private lateinit var undoBtn: ToolButton
    private lateinit var redoBtn: ToolButton
    private lateinit var layersBtn: ToolButton
    private var toast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("inkwell", MODE_PRIVATE)
        tools.load(prefs)
        store = ProjectStore(this)
        doc = store.load() ?: newBlankDocument(landscape = true, square = false)
        buildUi()
        canvasView.setDocument(doc)
        layersPanel.setDocument(doc)
        applyToolUi()
        updateHistoryButtons()
    }

    override fun onPause() {
        super.onPause()
        store.saveAsync(doc)
        tools.save(prefs)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    /** Canvas matches the tablet's screen: 1024×600 on the Fire 7, 1280×800 on the Fire HD 8. */
    @Suppress("DEPRECATION")
    private fun newBlankDocument(landscape: Boolean, square: Boolean): PaintDocument {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val long = max(dm.widthPixels, dm.heightPixels)
        val short = min(dm.widthPixels, dm.heightPixels)
        return when {
            square -> PaintDocument.createBlank(short, short)
            landscape -> PaintDocument.createBlank(long, short)
            else -> PaintDocument.createBlank(short, long)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // Top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BAR)
            setPadding(dpi(4f), 0, dpi(4f), 0)
            elevation = dp(4f)
        }
        fun barButton(icon: Icon, desc: String, onClick: (View) -> Unit): ToolButton {
            val b = ToolButton(this, icon)
            b.contentDescription = desc
            b.setOnClickListener(onClick)
            bar.addView(b, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
            return b
        }
        barButton(Icon.MENU, "Menu") { showMenu(it) }
        brushBtn = barButton(tools.preset.icon, "Brush") { v ->
            if (tools.tool == Tool.BRUSH) showBrushPopup(v) else selectTool(Tool.BRUSH)
        }
        eraserBtn = barButton(Icon.ERASER, "Eraser") { selectTool(Tool.ERASER) }
        fillBtn = barButton(Icon.FILL, "Fill") { selectTool(Tool.FILL) }
        pickerBtn = barButton(Icon.PICKER, "Eyedropper") { selectTool(Tool.PICKER) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undoBtn = barButton(Icon.UNDO, "Undo") { canvasView.undo() }
        redoBtn = barButton(Icon.REDO, "Redo") { canvasView.redo() }
        layersBtn = barButton(Icon.LAYERS, "Layers") { toggleLayers() }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(52f)))

        // Canvas area with floating panels
        val area = FrameLayout(this)
        canvasView = CanvasView(this, tools)
        canvasView.listener = this
        area.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(Palette.PANEL, dp(14f))
            setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(6f))
            elevation = dp(6f)
            isClickable = true
        }
        sizeSlider = VerticalSlider(this, "Size").apply {
            formatter = { "${sliderToSize(it).roundToInt()}" }
            onChange = { v ->
                tools.size = sliderToSize(v)
                canvasView.showBrushPreview(tools.size)
            }
        }
        side.addView(sizeSlider, LinearLayout.LayoutParams(dpi(40f), dpi(140f)))
        colorButton = SwatchView(this, tools.color).apply {
            ringed = true
            contentDescription = "Color"
            setOnClickListener { showColorPicker() }
        }
        side.addView(colorButton, LinearLayout.LayoutParams(dpi(40f), dpi(40f)).apply {
            topMargin = dpi(6f); bottomMargin = dpi(6f)
        })
        opacitySlider = VerticalSlider(this, "Opacity").apply {
            onChange = { v -> tools.opacity = max(0.01f, v) }
        }
        side.addView(opacitySlider, LinearLayout.LayoutParams(dpi(40f), dpi(140f)))
        area.addView(side, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
        ).apply { leftMargin = dpi(8f) })

        layersPanel = LayersPanel(this) { structural ->
            if (structural) layersPanel.refresh() else layersPanel.refreshThumbs()
            canvasView.invalidateCaches()
            updateHistoryButtons()
        }
        layersPanel.visibility = View.GONE
        area.addView(layersPanel, FrameLayout.LayoutParams(dpi(270f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            rightMargin = dpi(8f); topMargin = dpi(8f)
        })

        root.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    /** Squared curve so small sizes get most of the slider's travel. */
    private fun sliderToSize(v: Float) = 1f + 199f * v * v
    private fun sizeToSlider(s: Float) = sqrt(((s - 1f) / 199f).coerceIn(0f, 1f))

    private fun selectTool(t: Tool) {
        tools.select(t)
        applyToolUi()
    }

    private fun applyToolUi() {
        brushBtn.icon = tools.preset.icon
        brushBtn.selectedTool = tools.tool == Tool.BRUSH
        eraserBtn.selectedTool = tools.tool == Tool.ERASER
        fillBtn.selectedTool = tools.tool == Tool.FILL
        pickerBtn.selectedTool = tools.tool == Tool.PICKER
        val strokes = tools.tool == Tool.BRUSH || tools.tool == Tool.ERASER
        sizeSlider.isEnabled = strokes
        opacitySlider.isEnabled = tools.tool != Tool.PICKER
        sizeSlider.value = sizeToSlider(tools.size)
        opacitySlider.value = tools.opacity
        colorButton.color = tools.color
    }

    private fun updateHistoryButtons() {
        undoBtn.isEnabled = doc.history.canUndo
        redoBtn.isEnabled = doc.history.canRedo
    }

    private fun toggleLayers() {
        val show = layersPanel.visibility != View.VISIBLE
        layersPanel.visibility = if (show) View.VISIBLE else View.GONE
        layersBtn.selectedTool = show
        if (show) layersPanel.refresh()
    }

    private fun message(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ------------------------------------------------------------------ popups

    private fun showBrushPopup(anchor: View) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Palette.PANEL, dp(14f))
            setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(10f))
        }
        val popup = PopupWindow(content, dpi(240f), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for ((i, preset) in Brushes.presets.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (i == tools.presetIndex) roundedBg(Palette.ROW_ACTIVE, dp(10f)) else null
                isClickable = true
                setOnClickListener {
                    tools.presetIndex = i
                    applyToolUi()
                    popup.dismiss()
                }
            }
            row.addView(ToolButton(this, preset.icon).apply { isClickable = false }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
            row.addView(TextView(this).apply {
                text = preset.name
                setTextColor(Palette.TEXT)
                textSize = 15f
            })
            content.addView(row)
        }
        val label = TextView(this).apply {
            setTextColor(Palette.TEXT_DIM)
            textSize = 12f
            setPadding(dpi(6f), dpi(10f), 0, 0)
            text = "Stabilizer ${(tools.stabilizer * 100).roundToInt()}%"
        }
        content.addView(label)
        content.addView(SeekBar(this).apply {
            max = 100
            progress = (tools.stabilizer * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tools.stabilizer = p / 100f
                    label.text = "Stabilizer $p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(8f)
        popup.showAsDropDown(anchor)
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(Menu.NONE, 1, 1, "New canvas…")
        menu.menu.add(Menu.NONE, 2, 2, "Import image as layer…")
        menu.menu.add(Menu.NONE, 3, 3, "Export PNG to gallery")
        menu.menu.add(Menu.NONE, 4, 4, "Fit canvas to screen")
        menu.menu.add(Menu.NONE, 5, 5, "Flip canvas horizontally")
        menu.menu.add(Menu.NONE, 6, 6, "How to use")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmNewCanvas()
                2 -> pickImage()
                3 -> exportWithPermission()
                4 -> canvasView.fitToView()
                5 -> canvasView.flipView()
                6 -> showHelp()
            }
            true
        }
        menu.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Gestures")
            .setMessage(
                "• One finger: draw\n" +
                    "• Two fingers: move, zoom and rotate\n" +
                    "• Tap with two fingers: undo\n" +
                    "• Tap with three fingers: redo\n" +
                    "• Drag the side sliders up/down for size and opacity\n" +
                    "• Tap the brush button again to pick a brush and set the stabilizer\n" +
                    "• Tap the active layer to rename it\n\n" +
                    "Your drawing saves automatically when you leave the app."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showColorPicker() {
        val original = tools.color
        var chosen = original
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), 0)
        }
        val picker = ColorPickerView(this, original)
        val preview = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val oldSwatch = SwatchView(this, original)
        val newSwatch = SwatchView(this, original).apply { ringed = true }
        val hex = TextView(this).apply {
            setTextColor(Palette.TEXT)
            textSize = 14f
            setPadding(dpi(10f), 0, 0, 0)
        }
        fun setChosen(c: Int, fromPicker: Boolean) {
            chosen = c or 0xFF000000.toInt()
            newSwatch.color = chosen
            hex.text = String.format("#%06X", chosen and 0xFFFFFF)
            if (!fromPicker) picker.setColor(chosen)
        }
        picker.onColorChanged = { setChosen(it, true) }
        setChosen(original, true)
        preview.addView(oldSwatch.apply { setOnClickListener { setChosen(original, false) } }, LinearLayout.LayoutParams(dpi(36f), dpi(36f)))
        preview.addView(newSwatch, LinearLayout.LayoutParams(dpi(36f), dpi(36f)))
        preview.addView(hex)

        content.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(230f)))
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })

        fun swatchRow(colors: List<Int>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in colors) {
                row.addView(SwatchView(this, c).apply { setOnClickListener { setChosen(c, false) } },
                    LinearLayout.LayoutParams(0, dpi(32f), 1f))
            }
            // Pad short rows so swatches keep the same size.
            for (k in colors.size until 10) row.addView(View(this), LinearLayout.LayoutParams(0, dpi(32f), 1f))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        }
        swatchRow(PRESETS.subList(0, 10))
        swatchRow(PRESETS.subList(10, 20))
        if (tools.recentColors.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "Recent"
                setTextColor(Palette.TEXT_DIM)
                textSize = 12f
                setPadding(0, dpi(8f), 0, 0)
            })
            swatchRow(tools.recentColors.take(10))
        }

        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("Use color") { _, _ ->
                tools.color = chosen
                tools.addRecent(chosen)
                colorButton.color = chosen
                if (tools.tool == Tool.PICKER || tools.tool == Tool.ERASER) selectTool(Tool.BRUSH)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ files

    private fun confirmNewCanvas() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val long = max(dm.widthPixels, dm.heightPixels)
        val short = min(dm.widthPixels, dm.heightPixels)
        val options = arrayOf("Landscape  ${long}×${short}", "Portrait  ${short}×${long}", "Square  ${short}×${short}")
        AlertDialog.Builder(this)
            .setTitle("New canvas (current drawing is replaced)")
            .setItems(options) { _, which ->
                doc = newBlankDocument(landscape = which == 0, square = which == 2)
                canvasView.setDocument(doc)
                layersPanel.setDocument(doc)
                updateHistoryButtons()
                store.saveAsync(doc)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Import image"), REQ_IMPORT)
        } catch (e: Exception) {
            message("No app found to pick images")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (!doc.canAddLayer()) {
            message("Layer limit reached (${doc.maxLayers})")
            return
        }
        val bmp = decodeScaled(uri)
        if (bmp == null) {
            message("Couldn't open that image")
            return
        }
        doc.addImageLayer(bmp)
        bmp.recycle()
        canvasView.invalidateCaches()
        layersPanel.refresh()
        updateHistoryButtons()
    }

    /** Decodes at no more than twice the canvas size, so big photos don't run out of memory. */
    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= doc.width && bounds.outHeight / (sample * 2) >= doc.height) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: Throwable) {
        null
    }

    private fun exportWithPermission() {
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        export()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) export()
        else message("Storage permission is needed to save to the gallery")
    }

    private fun export() {
        val flat = try {
            doc.flatten()
        } catch (oom: OutOfMemoryError) {
            message("Not enough memory to export")
            return
        }
        message("Exporting…")
        store.runInBackground {
            val result = try {
                "Saved to " + ProjectStore.exportPng(applicationContext, flat)
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            flat.recycle()
            runOnUiThread { message(result) }
        }
    }

    // ------------------------------------------------------------------ CanvasView.Listener

    override fun onColorPicked(color: Int) {
        tools.color = color
        colorButton.color = color
    }

    override fun onToolChanged() {
        if (tools.tool != Tool.PICKER) tools.addRecent(tools.color)
        applyToolUi()
    }

    override fun onHistoryChanged() {
        updateHistoryButtons()
        if (layersPanel.visibility == View.VISIBLE) layersPanel.refresh()
    }

    override fun onLayerContentChanged() {
        if (layersPanel.visibility == View.VISIBLE) layersPanel.refreshThumbs()
    }

    override fun onMessage(text: String) = message(text)

    companion object {
        private const val REQ_IMPORT = 10
        private const val REQ_STORAGE = 11

        private val PRESETS = listOf(
            0xFF000000, 0xFF3C3C44, 0xFF7A7A85, 0xFFBDBDC6, 0xFFFFFFFF,
            0xFF6B4226, 0xFFA8714A, 0xFFF1C9A5, 0xFFFFE0C7, 0xFFE8B4B8,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF7CB342, 0xFF2E7D32,
            0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60,
        ).map { it.toInt() }
    }
}
