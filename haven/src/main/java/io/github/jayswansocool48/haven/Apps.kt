package io.github.jayswansocool48.haven

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

/** An installed app Haven can open. One per package; TV (leanback) entry points win. */
class AppEntry(
    val pkg: String,
    val activity: String,
    val label: String,
    val tv: Boolean,
) {
    val component get() = ComponentName(pkg, activity)
}

/** Installed apps plus everything Haven remembers: favorites, hidden apps and look. */
class AppRepo(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager
    val prefs: SharedPreferences = ctx.getSharedPreferences("haven", Context.MODE_PRIVATE)

    fun load(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun add(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val found = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: RuntimeException) {
                emptyList()
            }
            for (ri in found) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || byPkg.containsKey(ai.packageName)) continue
                val label = try {
                    ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                } catch (e: RuntimeException) {
                    ""
                }
                byPkg[ai.packageName] = AppEntry(ai.packageName, ai.name, label.ifEmpty { ai.packageName }, tv)
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        return byPkg.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    // ---- favorites (ordered) and hidden apps ----

    var favorites: List<String>
        get() = prefs.getString("favorites", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("favorites", v.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden", HashSet(v)).apply()

    fun isFavorite(pkg: String) = pkg in favorites
    fun addFavorite(pkg: String) {
        if (!isFavorite(pkg)) favorites = favorites + pkg
    }
    fun removeFavorite(pkg: String) {
        favorites = favorites - pkg
    }
    fun moveFavorite(pkg: String, by: Int): Boolean {
        val list = favorites.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + by
        if (i < 0 || j < 0 || j >= list.size) return false
        list.removeAt(i)
        list.add(j, pkg)
        favorites = list
        return true
    }
    fun hide(pkg: String) {
        hidden = hidden + pkg
    }
    fun unhide(pkg: String) {
        hidden = hidden - pkg
    }

    /** On first run, pin the streaming apps that are installed so the home screen isn't empty. */
    fun seedFavorites(apps: List<AppEntry>) {
        if (prefs.getBoolean("seeded", false)) return
        val popular = listOf(
            "com.netflix.ninja", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.amazon.avod", "com.disney.disneyplus", "com.hulu.plus", "com.wbd.stream",
            "com.spotify.tv.android", "com.amazon.cloud9", "com.crunchyroll.crunchyroid",
            "com.peacocktv.peacockandroid", "tv.pluto.android", "com.amazon.rialto",
        )
        val installed = apps.map { it.pkg }.toSet()
        var picks = popular.filter { it in installed }
        if (picks.size < 3) picks = (picks + apps.filter { it.tv }.map { it.pkg }).distinct().take(6)
        favorites = picks.take(8)
        prefs.edit().putBoolean("seeded", true).apply()
    }

    // ---- look ----

    var themeId: String
        get() = prefs.getString("theme", THEMES[0].id) ?: THEMES[0].id
        set(v) = prefs.edit().putString("theme", v).apply()
    var animate: Boolean
        get() = prefs.getBoolean("animate", true)
        set(v) = prefs.edit().putBoolean("animate", v).apply()
    var clock24: Boolean
        get() = prefs.getBoolean("clock24", false)
        set(v) = prefs.edit().putBoolean("clock24", v).apply()
    var showHidden: Boolean
        get() = prefs.getBoolean("showHidden", false)
        set(v) = prefs.edit().putBoolean("showHidden", v).apply()
    var useBanners: Boolean
        get() = prefs.getBoolean("banners", true)
        set(v) = prefs.edit().putBoolean("banners", v).apply()

    // ---- artwork ----

    /** The wide TV banner an app ships for Android TV launchers, if it has one. */
    fun banner(app: AppEntry): Drawable? = try {
        pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.pkg)
    } catch (e: Exception) {
        null
    }

    fun icon(app: AppEntry): Drawable = try {
        pm.getActivityIcon(app.component)
    } catch (e: Exception) {
        pm.defaultActivityIcon
    }

    fun launchIntent(app: AppEntry): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (app.tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(app.component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

fun Drawable.toBitmap(size: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null && bitmap.width == size && bitmap.height == size) return bitmap
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp
}
