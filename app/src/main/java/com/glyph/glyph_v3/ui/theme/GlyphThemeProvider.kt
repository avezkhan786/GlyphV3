package com.glyph.glyph_v3.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * 🎨 Unified Theme Provider - Single Entry Point for All Themes
 * 
 * This composable automatically detects the current theme from ThemeManager
 * and provides the appropriate semantic tokens via GlyphTheme composable.
 * 
 * Usage:
 * ```
 * GlyphThemeProvider {
 *     YourScreen()
 * }
 * ```
 * 
 * Then in your composables, access theme tokens:
 * ```
 * Text(
 *     text = "Hello",
 *     color = glyphTheme.textPrimary
 * )
 * ```
 */
@Composable
fun GlyphThemeProvider(
    context: Context = LocalContext.current,
    isDeepDark: Boolean = false,
    content: @Composable () -> Unit
) {
    // Auto-detect current theme from ThemeManager
    val currentTheme = remember(context) {
        ThemeManager.getCurrentTheme(context)
    }
    
    // Select appropriate theme tokens
    val tokens = remember(currentTheme, isDeepDark) {
        when (currentTheme) {
            ThemeManager.THEME_PASTEL_SKY -> PastelSkyThemeTokens
            ThemeManager.THEME_DARK -> if (isDeepDark) DeepDarkThemeTokens else DarkThemeTokens
            else -> LightThemeTokens
        }
    }
    
    // Create Material3 color scheme for compatibility
    val colorScheme = remember(tokens) {
        if (tokens.isDark) {
            darkColorScheme(
                primary = tokens.actionPrimary,
                onPrimary = tokens.textInverse,
                secondary = tokens.actionSecondary,
                background = tokens.backgroundPrimary,
                surface = tokens.backgroundElevated,
                onSurface = tokens.textPrimary,
                error = tokens.actionError
            )
        } else {
            lightColorScheme(
                primary = tokens.actionPrimary,
                onPrimary = tokens.textPrimary,
                secondary = tokens.actionSecondary,
                background = tokens.backgroundPrimary,
                surface = tokens.backgroundElevated,
                onSurface = tokens.textPrimary,
                error = tokens.actionError
            )
        }
    }
    
    // Provide both GlyphTheme tokens and MaterialTheme for compatibility
    MaterialTheme(colorScheme = colorScheme) {
        GlyphTheme(tokens = tokens) {
            content()
        }
    }
}

/**
 * 🎨 Legacy Compose Theme Wrapper (for backward compatibility)
 * 
 * This is a drop-in replacement for the old GlyphComposeTheme.
 * Internally uses GlyphThemeProvider but maintains the same API.
 */
@Composable
fun GlyphComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    GlyphThemeProvider(content = content)
}
