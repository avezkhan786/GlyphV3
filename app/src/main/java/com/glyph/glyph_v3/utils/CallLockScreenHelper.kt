package com.glyph.glyph_v3.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager

/**
 * Centralizes lock-screen presentation for incoming and active call surfaces.
 * We intentionally avoid dismissing keyguard here: call UIs should stay usable
 * on top of the lock screen without forcing credential entry.
 */
object CallLockScreenHelper {

    fun prepareActivityWindow(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun pulseScreenWake(context: Context, tag: String, durationMs: Long = 5_000L) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isInteractive) {
            return
        }

        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            tag
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(durationMs)
    }
}