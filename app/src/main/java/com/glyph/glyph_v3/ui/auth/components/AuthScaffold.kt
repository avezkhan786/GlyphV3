package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Edge-to-edge auth screen scaffold.
 *
 * Paints the full window with the theme background (or gradient for PastelSky),
 * handles safe-drawing insets, and provides an optional top bar with back button.
 *
 * Usage:
 * ```
 * AuthScaffold(
 *     showBackButton = true,
 *     onBackClick = { finish() }
 * ) { padding ->
 *     // content with padding applied
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScaffold(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    topPadding: Dp = 0.dp,
    content: @Composable ColumnScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val theme = glyphTheme
    val bgBrush: Brush? = theme.backgroundGradient

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (bgBrush != null) Modifier.background(bgBrush)
                else Modifier.background(theme.backgroundPrimary)
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Optional back button bar
            if (showBackButton) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        if (onBackClick != null) {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = theme.iconPrimary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }

            // Content with insets-aware padding
            val contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 0.dp,
                bottom = 0.dp
            )
            content(contentPadding)
        }
    }
}
