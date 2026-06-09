package com.glyph.glyph_v3.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Base Activity that automatically applies the current theme.
 * All activities should extend this class to ensure consistent theming.
 */
abstract class ThemedActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate to ensure it's applied before any UI is created
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
    }
    
    override fun onResume() {
        super.onResume()
        // Check if theme changed while activity was in background
        val currentTheme = ThemeManager.getCurrentTheme(this)
        if (shouldRecreateForThemeChange(currentTheme)) {
            recreate()
        }
        // Force bottom navigation bar to rebind colors after theme switch
        val bottomNav = findViewById<com.glyph.glyph_v3.ui.widgets.GlyphBottomNavigationView?>(com.glyph.glyph_v3.R.id.bottom_navigation)
        bottomNav?.rebindColors()
    }
    
    private fun shouldRecreateForThemeChange(newTheme: String): Boolean {
        // Get the current applied theme from activity
        val currentThemeResId = ThemeManager.getThemeResId(ThemeManager.getCurrentTheme(this))
        val newThemeResId = ThemeManager.getThemeResId(newTheme)
        
        // Only recreate if theme resource actually changed
        return currentThemeResId != newThemeResId
    }
}
