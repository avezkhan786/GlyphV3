package com.glyph.glyph_v3.util

import android.os.SystemClock
import android.util.Log
import com.glyph.glyph_v3.BuildConfig

object StartupTrace {
    private const val TAG = "StartupTrace"
    private val processStartMs = SystemClock.elapsedRealtime()

    fun logStage(stage: String, details: String? = null) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = SystemClock.elapsedRealtime() - processStartMs
        val suffix = details?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""
        Log.d(TAG, "[$elapsedMs ms] $stage$suffix")
    }
}