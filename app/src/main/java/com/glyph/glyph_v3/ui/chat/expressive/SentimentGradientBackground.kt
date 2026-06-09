package com.glyph.glyph_v3.ui.chat.expressive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import android.util.Log

/**
 * Sentiment gradient background component (currently disabled).
 * 
 * This component was originally designed to display animated gradient backgrounds
 * based on detected sentiment, but has been disabled to avoid visual overlay.
 * Only flying emoji animations are now visible.
 * 
 * Keeping this component for potential future re-enablement or customization.
 * 
 * @param sentiment The current detected sentiment (not currently used for rendering)
 * @param isActive Whether the feature is active (not currently used for rendering)
 * @param modifier Standard Compose modifier
 */
@Composable
fun SentimentGradientBackground(
    sentiment: SentimentType,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Convert Android.graphics.Color to Compose Color with smooth interpolation
    val targetColors = remember(sentiment) {
        sentiment.gradientColors.map { androidColor ->
            Color(androidColor)
        }
    }

    // Animated color transitions - spring physics for natural movement
    val animatedColors = targetColors.mapIndexed { index, targetColor ->
        animateColorAsState(
            targetValue = targetColor,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "SentimentColor$index"
        ).value
    }

    // Gradient background disabled - showing only flying emojis
    val alpha by animateFloatAsState(
        targetValue = 0f, // Gradient disabled - transparent
        animationSpec = tween(
            durationMillis = 1200,
            easing = FastOutSlowInEasing
        ),
        label = "SentimentAlpha"
    )

    // Animated gradient position for dynamic movement
    val infiniteTransition = rememberInfiniteTransition(label = "GradientShift")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GradientOffsetX"
    )
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GradientOffsetY"
    )

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        if (animatedColors.size < 2) return@Canvas

        // Create radial gradient with animated center position
        val centerX = size.width * (0.3f + offsetX * 0.4f)
        val centerY = size.height * (0.3f + offsetY * 0.4f)

        val gradient = Brush.radialGradient(
            colors = animatedColors,
            center = Offset(centerX, centerY),
            radius = size.maxDimension * 1.2f,
            tileMode = TileMode.Clamp
        )

        drawRect(
            brush = gradient,
            alpha = alpha
        )
    }

    // Debug logging
    LaunchedEffect(sentiment, isActive) {
    }
}
