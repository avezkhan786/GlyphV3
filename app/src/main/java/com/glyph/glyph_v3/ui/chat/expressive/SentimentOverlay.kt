package com.glyph.glyph_v3.ui.chat.expressive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

/**
 * Sentiment expression overlay featuring flying emoji animations.
 * 
 * This component displays animated emoji particles that match the detected
 * emotional tone of the text being typed. Emojis float upward naturally
 * and fade away smoothly without any background overlay.
 * 
 * Performance characteristics:
 * - GPU-accelerated emoji animations
 * - Runs at 60fps on mid-range devices
 * - Automatically suspends when feature is inactive (isActive = false)
 * - Gracefully resets to neutral state when sentiment clears
 * - No visual overlay - only flying emojis
 * 
 * Usage:
 * ```
 * Box {
 *     SentimentOverlay(sentiment, isActive)
 *     ChatMessages() // Content on top
 * }
 * ```
 * 
 * @param sentiment Current detected sentiment
 * @param isActive Whether the expressive typing feature is active
 * @param modifier Standard Compose modifier
 */
@Composable
fun SentimentOverlay(
    sentiment: SentimentType,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    layer: EmojiLayer = EmojiLayer.BACKGROUND,
    romanticAnimationMode: RomanticAnimationMode = RomanticAnimationMode.EMOJI_PARTICLES,
    romanticComposerTopPx: Int = 0
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Gradient background layer (subtle overlay) - Only render in background layer
        if (layer == EmojiLayer.BACKGROUND) {
            SentimentGradientBackground(
                sentiment = sentiment,
                isActive = isActive,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f) // Behind emojis
            )
        }

        val shouldUseEmojiParticles =
            sentiment != SentimentType.ROMANTIC ||
                romanticAnimationMode == RomanticAnimationMode.EMOJI_PARTICLES

        if (shouldUseEmojiParticles) {
            FlyingEmojiAnimation(
                sentiment = sentiment,
                isActive = isActive,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
                layer = layer
            )
        } else if (layer == EmojiLayer.FOREGROUND && sentiment == SentimentType.ROMANTIC && isActive) {
            RomanticLottieOverlay(
                animationMode = romanticAnimationMode,
                isActive = isActive,
                composerTopPx = romanticComposerTopPx,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            )
        }
    }
}
