package com.glyph.glyph_v3.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pre-computed theme values for message bubbles.
 * Created once at list level to eliminate per-message theme lookups.
 * 
 * PERFORMANCE: This data class eliminates:
 * - 4 theme object lookups per message
 * - 3 conditional checks (isSelf) per message
 * - 1 BorderStroke allocation per message
 */
@Immutable
data class BubbleThemeSet(
    val outgoingGradient: Brush?,
    val outgoingSolid: Color,
    val outgoingText: Color,
    val incomingGradient: Brush?,
    val incomingSolid: Color,
    val incomingText: Color,
    val timestamp: Color,
    val borderStroke: BorderStroke,
    val cornerRadiusMedium: Float
)

/**
 * Pre-computed audio playback information.
 * Created once at list level to eliminate per-message state derivation.
 * 
 * PERFORMANCE: This eliminates:
 * - O(n) derivedStateOf calculations (where n = number of audio messages)
 * - String formatting inside message rows
 * - Multiple remember keys per audio message
 */
@Immutable
data class AudioPlaybackInfo(
    val messageId: String,
    val isPlaying: Boolean,
    val progress: Float,
    val formattedPosition: String,
    val audioDuration: Long
)
