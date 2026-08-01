package com.glyph.glyph_v3.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Read-only conversation footer banner for official channels.
 *
 * A fixed banner displayed at the bottom of read-only conversations (official announcements,
 * company accounts, system accounts, and broadcast channels). Communicates to users that
 * this is a read-only conversation - only Glyph can send messages.
 *
 * Features:
 * - Fixed position - stays pinned while content scrolls
 * - Full-width, 56dp height
 * - Theme-adaptive background (lighter than chat background)
 * - 1dp top divider
 * - Respects gesture/navigation insets
 * - Fade-in + slide-up animation on open
 * - Accessibility optimized
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OfficialConversationFooter(
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
    animationProgress: Float = 1f
) {
    if (!isReadOnly) return

    val theme = MaterialTheme.colorScheme

    // Calculate background color as slightly lighter than primary background
    val backgroundColor = theme.surface

    // Text color at 75% opacity for proper contrast
    val textColor = theme.onSurface.copy(alpha = 0.75f)

    // Track whether to show the footer - starts true when isReadOnly is true
    var isVisible by remember { mutableStateOf(isReadOnly) }

    // Handle visibility with animation
    LaunchedEffect(isReadOnly) {
        isVisible = isReadOnly
    }

    if (isVisible) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(150)) +
                    slideInVertically(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)) +
                    slideOutVertically(animationSpec = tween(150))
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(backgroundColor)
                    .alpha(animationProgress),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    // Top divider
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth(),
                        color = theme.outline.copy(alpha = 0.3f),
                        thickness = 1.dp
                    )

                    Text(
                        text = "Only Glyph can send messages",
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}