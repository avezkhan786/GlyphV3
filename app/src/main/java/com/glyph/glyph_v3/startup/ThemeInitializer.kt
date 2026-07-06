package com.glyph.glyph_v3.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Theme manager initializer.
 *
 * Initializes the ThemeManager which handles app theme switching
 * (Light, Dark, Pastel-Sky) and applies the user's saved theme preference.
 *
 * This initializer should run early so the correct theme is applied
 * before any UI components are created.
 */
class ThemeInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "ThemeInitializer"
    }

    override fun create(context: Context) {
        try {
            StartupTrace.logStage("theme_init_start")

            // Initialize theme settings globally (sets AppCompatDelegate night mode)
            ThemeManager.init(context)

            StartupTrace.logStage("theme_init_complete")
            Log.d(TAG, "Theme initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "Theme initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // Theme has no dependencies - can initialize independently
        return emptyList()
    }
}
