package io.github.jayswansocool48.haven

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens Haven when the TV starts. Fire OS 7 allows this directly; on Fire OS 8 the
 * Home button watcher does the same job once Amazon's home screen appears.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        ShareServer.start(ctx.applicationContext)
        try {
            ctx.startActivity(Intent(ctx, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}
