package dev.inkwell.paint

import android.graphics.Bitmap

class Layer(val id: Long, var name: String, val bitmap: Bitmap) {
    var visible = true
    var opacity = 1f

    /** Pixels changed since the last autosave. */
    var dirty = true
}
