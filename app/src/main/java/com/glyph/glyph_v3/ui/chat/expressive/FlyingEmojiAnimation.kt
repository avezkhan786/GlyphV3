package com.glyph.glyph_v3.ui.chat.expressive

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * Defines the depth layer for flying emojis.
 */
enum class EmojiLayer {
    BACKGROUND, // Behind chat bubbles (depth)
    FOREGROUND  // In front of chat bubbles (pop)
}

/**
 * Animated flying emojis that react to detected sentiment.
 * 
 * Performance optimizations:
 * - Limited number of concurrent emojis (max 8) to prevent overhead
 * - Continuous spawning loop at 500ms intervals for consistent visual feedback
 * - GPU-accelerated transformations (graphicsLayer)
 * - Automatic cleanup after animation completes
 * - Skips spawning when sentiment is NEUTRAL or inactive
 * 
 * Animation characteristics:
 * - Emojis float upward naturally with gentle rotation
 * - Smooth cubic-eased fade in (first 15% of journey)
 * - Smooth cubic-eased fade out (last 20% of journey)
 * - Slight horizontal drift with sine wave for natural feel
 * - Dynamic scaling (slightly larger in middle of journey)
 * - Each emoji has randomized trajectory for variety
 * - Continuous spawning while sentiment is active (not just on change)
 * 
 * @param sentiment Current detected sentiment
 * @param isActive Whether feature is enabled
 * @param modifier Standard Compose modifier
 * @param layer The depth layer to render these emojis on (affects scale, speed, alpha)
 */
@Composable
fun FlyingEmojiAnimation(
    sentiment: SentimentType,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    layer: EmojiLayer = EmojiLayer.FOREGROUND // Default for backward compatibility
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Layer-specific configuration
    val maxEmojis = if (layer == EmojiLayer.BACKGROUND) 5 else 3
    val spawnDelay = if (layer == EmojiLayer.BACKGROUND) 800L else 1200L
    val duration = if (layer == EmojiLayer.BACKGROUND) 5000 else 3500

    // Active emojis with unique IDs for key stability
    var emojis by remember { mutableStateOf<List<EmojiParticle>>(emptyList()) }
    
    // Continuous emoji spawning while sentiment is active
    LaunchedEffect(sentiment, isActive, layer) {
        if (!isActive || sentiment == SentimentType.NEUTRAL || sentiment.emojis.isEmpty()) {
            // Don't immediately clear - let existing emojis finish their animation
            // They will auto-remove after 4 seconds
            return@LaunchedEffect
        }

        // Continuous spawning loop
        while (isActive && sentiment != SentimentType.NEUTRAL) {
            // Limit concurrent emojis
            if (emojis.size < maxEmojis) {
                // Spawn a new emoji
                val emoji = sentiment.emojis.random()
                val newParticle = EmojiParticle(
                    id = System.nanoTime(),
                    emoji = emoji,
                    startX = 0.2f + Random.nextFloat() * 0.6f, // Keep within 20-80% of screen width
                    drift = Random.nextFloat() * 0.15f - 0.075f, // Reduced horizontal drift
                    rotationSpeed = Random.nextFloat() * 40f - 20f // Reduced rotation speed
                )
                
                emojis = emojis + newParticle

                // Auto-remove after animation duration
                scope.launch {
                    delay(duration.toLong()) // Match animation duration
                    emojis = emojis.filter { it.id != newParticle.id }
                }
            }
            
            // Spawn more frequently for better visual effect
            delay(spawnDelay)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        emojis.forEach { particle ->
            key(particle.id) {
                FlyingEmoji(
                    particle = particle,
                    key = particle.id,
                    layer = layer,
                    animDuration = duration
                )
            }
        }
    }
}

/**
 * Individual flying emoji particle with animation state
 */
private data class EmojiParticle(
    val id: Long,
    val emoji: String,
    val startX: Float, // 0.0 to 1.0 (fraction of screen width)
    val drift: Float,  // Horizontal drift amount
    val rotationSpeed: Float // Rotation degrees per animation cycle
)

/**
 * Single animated emoji particle
 */
@Composable
private fun FlyingEmoji(
    particle: EmojiParticle,
    key: Long,
    modifier: Modifier = Modifier,
    layer: EmojiLayer,
    animDuration: Int
) {
    // Animate from 0 to 1 over 4 seconds - start immediately
    var targetProgress by remember { mutableFloatStateOf(0f) }
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = animDuration,
            easing = LinearEasing
        ),
        label = "EmojiProgress_$key"
    )
    
    // Trigger animation start
    LaunchedEffect(key) {
        targetProgress = 1f
    }

    // Smooth fade in and fade out
    val maxAlpha = if (layer == EmojiLayer.BACKGROUND) 0.9f else 1.0f
    val alpha = (when {
        progress < 0.15f -> {
            // Smooth fade in over first 15%
            val t = progress / 0.15f
            t * t * (3f - 2f * t)
        }
        progress > 0.85f -> {
            // Smooth fade out over last 15%
            val t = (1f - progress) / 0.15f
            t * t * (3f - 2f * t)
        }
        else -> 1f
    }) * maxAlpha

    // Vertical position: bottom → top with ease-out
    val verticalEasing = progress * (2f - progress) // Ease-out quad
    val offsetY = 1f - verticalEasing

    // Gentle horizontal drift (not oscillating)
    val horizontalDrift = particle.drift * progress // Linear drift
    val offsetX = (particle.startX + horizontalDrift).coerceIn(0f, 1f)

    // Gentle rotation throughout journey
    val rotation = particle.rotationSpeed * progress

    // Subtle scale variation for depth
    val baseScale = if (layer == EmojiLayer.BACKGROUND) 0.65f else 1.2f
    val scaleVariation = if (layer == EmojiLayer.BACKGROUND) 0.05f else 0.15f
    val scale = baseScale + scaleVariation * sin(progress * Math.PI.toFloat())

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        
        Text(
            text = particle.emoji,
            fontSize = 36.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (maxWidthPx * offsetX).roundToInt(),
                        y = (maxHeightPx * offsetY).roundToInt()
                    )
                }
                .graphicsLayer {
                    this.alpha = alpha
                    this.rotationZ = rotation
                    this.scaleX = scale
                    this.scaleY = scale
                }
        )
    }
}
