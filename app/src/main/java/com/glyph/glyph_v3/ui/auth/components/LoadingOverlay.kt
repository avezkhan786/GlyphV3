package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.ui.auth.AuthAnimationUtils
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Full-screen semi-transparent loading overlay with a centered card.
 *
 * Blocks all input when visible.
 *
 * @param visible Whether the overlay is shown.
 * @param message Text displayed below the spinner (e.g. "Sending verification code...").
 */
@Composable
fun LoadingOverlay(
    visible: Boolean,
    message: String = "Loading..."
) {
    val theme = glyphTheme

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = AuthAnimationUtils.fadeInTween),
        exit = fadeOut(animationSpec = AuthAnimationUtils.fadeInTween)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.surfaceOverlay)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { /* consume clicks */ }
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.backgroundElevated),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = theme.actionPrimary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.textPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
