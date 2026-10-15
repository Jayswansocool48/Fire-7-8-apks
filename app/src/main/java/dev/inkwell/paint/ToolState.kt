package dev.inkwell.paint

import android.content.SharedPreferences

enum class Tool { BRUSH, ERASER, FILL, PICKER }

/** Current tool, color and per-brush size/opacity. Remembered between launches. */
class ToolState {
    var tool = Tool.BRUSH
        private set
    var previousTool = Tool.BRUSH
        private set
    var presetIndex = 0
    var color = 0xFF202024.toInt()
    var stabilizer = 0.3f
    private val brushSizes = FloatArray(Brushes.presets.size) { Brushes.presets[it].defaultSize }
    private val brushOpacities = FloatArray(Brushes.presets.size) { Brushes.presets[it].defaultOpacity }
    private var eraserSize = Brushes.eraser.defaultSize
    private var eraserOpacity = 1f
    private var fillOpacity = 1f
    val recentColors = ArrayList<Int>()

    val preset: BrushPreset get() = Brushes.presets[presetIndex]
    val strokePreset: BrushPreset get() = if (tool == Tool.ERASER) Brushes.eraser else preset

    var size: Float
        get() = if (tool == Tool.ERASER) eraserSize else brushSizes[presetIndex]
        set(v) {
            if (tool == Tool.ERASER) eraserSize = v else brushSizes[presetIndex] = v
        }

    var opacity: Float
        get() = when (tool) {
            Tool.ERASER -> eraserOpacity
            Tool.FILL -> fillOpacity
            else -> brushOpacities[presetIndex]
        }
        set(v) {
            when (tool) {
                Tool.ERASER -> eraserOpacity = v
                Tool.FILL -> fillOpacity = v
                else -> brushOpacities[presetIndex] = v
            }
        }

    fun select(t: Tool) {
        if (t == tool) return
        if (tool != Tool.PICKER) previousTool = tool
        tool = t
    }

    fun addRecent(c: Int) {
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > 10) recentColors.removeAt(recentColors.size - 1)
    }

    fun save(p: SharedPreferences) {
        val e = p.edit()
        e.putString("tool", tool.name)
        e.putInt("preset", presetIndex)
        e.putInt("color", color)
        e.putFloat("stabilizer", stabilizer)
        for (i in brushSizes.indices) {
            e.putFloat("size$i", brushSizes[i])
            e.putFloat("opacity$i", brushOpacities[i])
        }
        e.putFloat("eraserSize", eraserSize)
        e.putFloat("eraserOpacity", eraserOpacity)
        e.putFloat("fillOpacity", fillOpacity)
        e.putString("recent", recentColors.joinToString(","))
        e.apply()
    }

    fun load(p: SharedPreferences) {
        tool = runCatching { Tool.valueOf(p.getString("tool", "BRUSH")!!) }.getOrDefault(Tool.BRUSH)
        if (tool == Tool.PICKER) tool = Tool.BRUSH
        presetIndex = p.getInt("preset", 0).coerceIn(0, Brushes.presets.size - 1)
        color = p.getInt("color", color)
        stabilizer = p.getFloat("stabilizer", stabilizer)
        for (i in brushSizes.indices) {
            brushSizes[i] = p.getFloat("size$i", brushSizes[i])
            brushOpacities[i] = p.getFloat("opacity$i", brushOpacities[i])
        }
        eraserSize = p.getFloat("eraserSize", eraserSize)
        eraserOpacity = p.getFloat("eraserOpacity", eraserOpacity)
        fillOpacity = p.getFloat("fillOpacity", fillOpacity)
        recentColors.clear()
        p.getString("recent", "")!!.split(',').forEach { s -> s.toIntOrNull()?.let { recentColors.add(it) } }
    }
}
