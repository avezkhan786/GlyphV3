package com.glyph.glyph_v3.ui.chat.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Manages MediaPlayer instances for TTS audio playback.
 *
 * Features:
 * - Uses Android MediaPlayer for better format compatibility
 * - Streams from URL or plays local file
 * - Auto-releases when playback completes
 * - Thread-safe: MediaPlayer calls are synchronized
 */
class TtsAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TtsAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentSourceId: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            val durationMs = player.duration.takeIf { it > 0 } ?: 0
            val positionMs = try {
                player.currentPosition.coerceAtLeast(0)
            } catch (_: IllegalStateException) {
                0
            }
            onPlaybackProgress?.invoke(positionMs, durationMs)
            if (player.isPlaying) {
                progressHandler.postDelayed(this, 200L)
            }
        }
    }

    var onPlaybackComplete: (() -> Unit)? = null
    var onPlaybackError: ((Exception) -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackPrepared: ((Int) -> Unit)? = null
    var onPlaybackProgress: ((Int, Int) -> Unit)? = null

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    /**
     * Play audio from a local file path or remote URL.
     * If already playing the same source, this is a no-op.
     */
    fun play(source: String, sourceId: String) {
        
        // If already playing the same source, skip
        if (currentSourceId == sourceId && mediaPlayer?.isPlaying == true) {
            return
        }

        stop()

        try {
            val player = MediaPlayer()
            mediaPlayer = player
            currentSourceId = sourceId
            stopProgressUpdates()

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            if (source.startsWith("http")) {
                player.setDataSource(source)
            } else {
                val file = File(source)
                if (!file.exists()) {
                    val msg = "Audio file MISSION: $source"
                    Log.e(TAG, msg)
                    throw Exception(msg)
                }
                
                // Use FileDescriptor
                val fis = java.io.FileInputStream(file)
                try {
                    player.setDataSource(fis.fd)
                } finally {
                    fis.close() 
                }
            }

            player.setOnPreparedListener {
                onPlaybackPrepared?.invoke(player.duration.takeIf { duration -> duration > 0 } ?: 0)
                onPlaybackStarted?.invoke()
                player.start()
                startProgressUpdates()
            }

            player.setOnCompletionListener {
                stopProgressUpdates()
                currentSourceId = null
                val durationMs = player.duration.takeIf { it > 0 } ?: 0
                onPlaybackProgress?.invoke(durationMs, durationMs)
                onPlaybackComplete?.invoke()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer ERROR: what=$what, extra=$extra")
                stopProgressUpdates()
                currentSourceId = null
                stop()
                onPlaybackError?.invoke(Exception("Playback error ($what/$extra)"))
                true
            }

            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "play() CRITICAL FAILURE", e)
            stopProgressUpdates()
            currentSourceId = null
            onPlaybackError?.invoke(e)
        }
    }

    fun stop() {
        try {
            stopProgressUpdates()
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player", e)
        }
        mediaPlayer = null
        currentSourceId = null
    }

    fun release() {
        stop()
        onPlaybackComplete = null
        onPlaybackError = null
        onPlaybackStarted = null
        onPlaybackPrepared = null
        onPlaybackProgress = null
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressHandler.post(progressTicker)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressTicker)
    }
}
