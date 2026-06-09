package com.glyph.glyph_v3.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import com.glyph.glyph_v3.data.models.Message

/**
 * PERFORMANCE: Marked as @Stable to tell Compose the callbacks are stable
 * and won't trigger recomposition when the same instance is passed.
 * This prevents status icon flicker and unnecessary message row recompositions.
 */
@Stable
data class AudioPlaybackState(
    val isPlaying: Boolean,
    val progress: Float?,
    val playingMessageId: String?,
    val audioDuration: Long?,
    val onPlay: (Message) -> Unit,
    val onPause: () -> Unit,
    val onSeek: (Message, Float) -> Unit
)

val LocalAudioPlaybackState = compositionLocalOf<AudioPlaybackState?> { null }
