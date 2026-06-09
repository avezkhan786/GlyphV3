package com.glyph.glyph_v3.utils

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R

/**
 * Extension functions and utilities for theme-aware color resolution
 */

/**
 * Get theme-aware background color
 */
fun Context.getThemeBackgroundColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_background)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_background)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_background)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_background)
            } else {
                ContextCompat.getColor(this, R.color.light_background)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_background)
    }
}

/**
 * Get theme-aware surface color
 */
fun Context.getThemeSurfaceColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_surface)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_surface)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_surface)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_surface)
            } else {
                ContextCompat.getColor(this, R.color.light_surface)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_surface)
    }
}

/**
 * Get theme-aware primary color
 */
fun Context.getThemePrimaryColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_primary)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_primary)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_sky_blue)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_primary)
            } else {
                ContextCompat.getColor(this, R.color.light_primary)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_primary)
    }
}

/**
 * Get theme-aware text color
 */
fun Context.getThemeTextColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_text)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_text_primary)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_text)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_text)
            } else {
                ContextCompat.getColor(this, R.color.light_text)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_text)
    }
}

/**
 * Get theme-aware secondary text color
 */
fun Context.getThemeTextSecondaryColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_text_secondary)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_text_secondary)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_text_secondary)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_text_secondary)
            } else {
                ContextCompat.getColor(this, R.color.light_text_secondary)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_text_secondary)
    }
}

/**
 * Get theme-aware bubble color for own messages
 */
fun Context.getThemeBubbleOwnColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_bubble_own)
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_bubble_outgoing_start)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_bubble_own)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_bubble_own)
            } else {
                ContextCompat.getColor(this, R.color.light_bubble_own)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_bubble_own)
    }
}

/**
 * Get theme-aware bubble color for other messages
 */
fun Context.getThemeBubbleOtherColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_bubble_incoming)
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_bubble_other)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_bubble_other)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_bubble_other)
            } else {
                ContextCompat.getColor(this, R.color.light_bubble_other)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_bubble_other)
    }
}

/**
 * Get theme-aware send button color
 */
fun Context.getThemeSendButtonColor(): Int {
    return when (ThemeManager.getCurrentTheme(this)) {
        ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(this, R.color.pastel_send_button_start)
        ThemeManager.THEME_LIGHT -> ContextCompat.getColor(this, R.color.light_send_button)
        ThemeManager.THEME_DARK -> ContextCompat.getColor(this, R.color.dark_send_button)
        ThemeManager.THEME_SYSTEM -> {
            if (isDarkModeActive()) {
                ContextCompat.getColor(this, R.color.dark_send_button)
            } else {
                ContextCompat.getColor(this, R.color.light_send_button)
            }
        }
        else -> ContextCompat.getColor(this, R.color.light_send_button)
    }
}

/**
 * Check if dark mode is currently active (system-wide)
 */
fun Context.isDarkModeActive(): Boolean {
    return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
