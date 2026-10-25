package io.github.jayswansocool48.haven

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Fire TV doesn't let you pick another home app, so this watches for Amazon's home screen
 * appearing (after Home is pressed, or at power-on) and opens Haven on top of it.
 * It only looks at which app's window opened; it never reads what's on screen.
 */
class HomeWatcher : AccessibilityService() {

    private var stockHomes: Set<String> = emptySet()
    private var lastJump = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ShareServer.start(applicationContext)
        stockHomes = findStockHomes()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in STOCK_PACKAGES && pkg !in stockHomes.map { it.substringBefore('/') }) return
        val cls = e.className?.toString().orEmpty()
        val isHome = cls.contains("HomeActivity", ignoreCase = true) ||
            stockHomes.contains("$pkg/$cls") ||
            (cls.isEmpty() && pkg in STOCK_PACKAGES)
        if (!isHome) return
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil || now - lastJump < 800) return
        lastJump = now
        try {
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (ex: Exception) {
        }
    }

    override fun onInterrupt() {}

    private fun findStockHomes(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != "com.android.settings" }
            .map { "${it.packageName}/${it.name}" }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    companion object {
        /** Amazon's home screen apps on Fire OS 6, 7 and 8. */
        val STOCK_PACKAGES = setOf("com.amazon.tv.launcher", "com.amazon.firebat", "com.amazon.tv.leanbacklauncher")

        @Volatile private var pausedUntil = 0L

        fun pauseFor(ms: Long) {
            pausedUntil = SystemClock.elapsedRealtime() + ms
        }

        fun isOn(ctx: Context): Boolean {
            val me = ComponentName(ctx, HomeWatcher::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c.packageName == me.packageName && c.className == me.className
            }
        }
    }
}
