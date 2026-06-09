package com.glyph.glyph_v3.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.service.AppVisibilityTracker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * ThemeManager - Centralized theme management for the entire app
 * 
 * Usage:
 * 1. Call ThemeManager.init(context) in Application.onCreate()
 * 2. Call ThemeManager.applyTheme(this) in every Activity.onCreate() BEFORE super.onCreate()
 * 3. Use ThemeManager.setTheme(activity, theme) when user changes theme
 */
object ThemeManager {
    private const val PREFS_NAME = "glyph_theme_prefs"
    private const val KEY_THEME = "selected_theme"
    
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_PASTEL_SKY = "pastel_sky"
    const val THEME_SYSTEM = "system"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get the currently saved theme preference
     */
    fun getCurrentTheme(context: Context): String {
        return getPreferences(context).getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
    }
    
    /**
     * Save theme preference
     */
    fun saveTheme(context: Context, theme: String) {
        getPreferences(context).edit().putString(KEY_THEME, theme).apply()
    }
    
    /**
     * Initialize theme - call this ONCE in Application.onCreate()
     * This sets up the night mode based on saved preference
     */
    fun init(context: Context) {
        val theme = getCurrentTheme(context)
        applyNightMode(theme)
    }
    
    /**
     * Apply night mode setting based on theme
     */
    private fun applyNightMode(theme: String) {
        val mode = when (theme) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_PASTEL_SKY -> AppCompatDelegate.MODE_NIGHT_NO  // Light-based theme
            THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
    
    /**
     * Apply theme to a specific activity - call in Activity.onCreate() BEFORE super.onCreate()
     * This sets the correct style resource for the activity
     */
    fun applyTheme(activity: Activity, deepDark: Boolean = false) {
        val theme = getCurrentTheme(activity)
        var styleRes = getThemeStyleRes(theme)
        
        // Special override for Chat/AI screens in Dark Theme
        if (deepDark && styleRes == R.style.Theme_GlyphV3_Dark) {
            styleRes = R.style.Theme_GlyphV3_Dark_Deep
        }
        
        activity.setTheme(styleRes)
    }
    
    /**
     * Get the style resource ID for a theme
     */
    private fun getThemeStyleRes(theme: String): Int {
        return when (theme) {
            THEME_LIGHT -> R.style.Theme_GlyphV3_Light
            THEME_DARK -> R.style.Theme_GlyphV3_Dark
            THEME_PASTEL_SKY -> R.style.Theme_GlyphV3_PastelSky
            THEME_SYSTEM -> {
                // For system theme, check current night mode
                when (AppCompatDelegate.getDefaultNightMode()) {
                    AppCompatDelegate.MODE_NIGHT_YES -> R.style.Theme_GlyphV3_Dark
                    else -> R.style.Theme_GlyphV3_Light
                }
            }
            else -> R.style.Theme_GlyphV3_Light
        }
    }
    
    /**
     * Change theme and apply it - use this when user selects a new theme
     * This saves the preference, updates night mode, and recreates the activity
     */
    fun setTheme(activity: Activity, theme: String) {
        saveTheme(activity, theme)
        applyNightMode(theme)
        
        // Update wallpaper cache for the new theme off the main thread so the next
        // chat open can apply the wallpaper immediately.
        ChatWallpaperManager.warmCurrentWallpaperAsync(activity.applicationContext)

        // Recreate all activities so the new theme applies immediately across the app
        // AppVisibilityTracker maintains a weak list of activities and will safely recreate them.
        AppVisibilityTracker.recreateAllActivities()
    }
    
    /**
     * Get theme resource ID (public version)
     */
    fun getThemeResId(theme: String): Int {
        return when (theme) {
            THEME_LIGHT -> R.style.Theme_GlyphV3_Light
            THEME_PASTEL_SKY -> R.style.Theme_GlyphV3_PastelSky
            THEME_DARK -> R.style.Theme_GlyphV3_Dark
            THEME_SYSTEM -> R.style.Theme_GlyphV3_Light
            else -> R.style.Theme_GlyphV3_Light
        }
    }
    
    /**
     * Check if current theme is dark mode
     */
    fun isDarkMode(context: Context): Boolean {
        val theme = getCurrentTheme(context)
        return theme == THEME_DARK || 
               (theme == THEME_SYSTEM && context.resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }
    
    /**
     * Get theme display name
     */
    fun getThemeDisplayName(theme: String): String {
        return when (theme) {
            THEME_LIGHT -> "Light Mode"
            THEME_PASTEL_SKY -> "Pastel-Sky ✨"
            THEME_DARK -> "Dark Mode"
            THEME_SYSTEM -> "Match System"
            else -> "Unknown"
        }
    }
}
