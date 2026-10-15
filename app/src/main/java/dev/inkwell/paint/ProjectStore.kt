package dev.inkwell.paint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Autosaves the current artwork to app storage and exports PNGs to the gallery. */
class ProjectStore(private val context: Context) {
    private val dir = File(context.filesDir, "project")
    private val metaFile = File(dir, "meta.json")
    private val io = Executors.newSingleThreadExecutor()

    fun load(): PaintDocument? = try {
        if (!metaFile.exists()) null else readProject()
    } catch (t: Throwable) {
        null // Corrupt save or out of memory: start fresh rather than crash.
    }

    private fun readProject(): PaintDocument? {
        val meta = JSONObject(metaFile.readText())
        val doc = PaintDocument(meta.getInt("width"), meta.getInt("height"))
        doc.layerCounter = meta.optInt("counter", 1)
        val arr = meta.getJSONArray("layers")
        var maxId = 0L
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getLong("id")
            maxId = maxOf(maxId, id)
            val decoded = BitmapFactory.decodeFile(File(dir, layerFile(id)).path, opts)
            val bmp = if (decoded != null && decoded.width == doc.width && decoded.height == doc.height) {
                decoded
            } else {
                Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            }
            val layer = Layer(id, o.getString("name"), bmp)
            layer.opacity = o.optDouble("opacity", 1.0).toFloat()
            layer.visible = o.optBoolean("visible", true)
            layer.dirty = false
            doc.layers.add(layer)
        }
        if (doc.layers.isEmpty()) return null
        doc.nextLayerId = maxId + 1
        doc.setActive(meta.optInt("active", doc.layers.size - 1))
        return doc
    }

    /** Copies changed layers on the UI thread, then writes PNGs in the background. */
    fun saveAsync(doc: PaintDocument) {
        val meta = JSONObject()
        meta.put("width", doc.width)
        meta.put("height", doc.height)
        meta.put("active", doc.activeIndex)
        meta.put("counter", doc.layerCounter)
        val arr = JSONArray()
        val toWrite = ArrayList<Pair<File, Bitmap>>()
        val keep = HashSet<String>()
        keep.add(metaFile.name)
        for (l in doc.layers) {
            val f = File(dir, layerFile(l.id))
            keep.add(f.name)
            arr.put(JSONObject().put("id", l.id).put("name", l.name).put("opacity", l.opacity.toDouble()).put("visible", l.visible))
            if (l.dirty || !f.exists()) {
                try {
                    toWrite.add(f to l.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    l.dirty = false
                } catch (oom: OutOfMemoryError) {
                    // Skip this round; the layer stays dirty and is saved next time.
                }
            }
        }
        meta.put("layers", arr)
        val metaText = meta.toString()

        io.execute {
            try {
                dir.mkdirs()
                for ((file, bmp) in toWrite) {
                    val tmp = File(dir, file.name + ".tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    tmp.renameTo(file)
                    bmp.recycle()
                }
                val tmpMeta = File(dir, "meta.json.tmp")
                tmpMeta.writeText(metaText)
                tmpMeta.renameTo(metaFile)
                dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            } catch (e: IOException) {
                // Disk full or similar; the next save will try again.
            }
        }
    }

    /** Runs [work] on the background thread, after any pending save. */
    fun runInBackground(work: () -> Unit) = io.execute(work)

    private fun layerFile(id: Long) = "layer_$id.png"

    companion object {
        /** Saves a PNG to Pictures/Inkwell and returns where it went. Call off the UI thread. */
        @Throws(IOException::class)
        fun exportPng(context: Context, bmp: Bitmap): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "Inkwell_$stamp.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Inkwell")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Couldn't create the image file")
                val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the image file")
                out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val folder = File(pictures, "Inkwell")
                if (!folder.exists() && !folder.mkdirs()) throw IOException("Couldn't create Pictures/Inkwell")
                val file = File(folder, name)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            }
            return "Pictures/Inkwell/$name"
        }
    }
}
