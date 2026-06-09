package com.glyph.glyph_v3.ui.calls

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glyph.glyph_v3.R

private enum class CallSound {
    OUTGOING_RINGING,
    CONNECTED,
    CALL_ENDED,
    BUSY
}

object CallSoundPlayer {

    private const val TAG = "CallSoundPlayer"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var currentSound: CallSound? = null
    private var scheduledStop: Runnable? = null

    @Synchronized
    fun startOutgoingRinging(context: Context) {
        if (currentSound == CallSound.OUTGOING_RINGING && mediaPlayer?.isPlaying == true) {
            return
        }
        play(context = context, sound = CallSound.OUTGOING_RINGING, looping = true)
    }

    @Synchronized
    fun playCallEnded(context: Context) {
        play(context = context, sound = CallSound.CALL_ENDED, looping = false)
    }

    @Synchronized
    fun playConnected(context: Context) {
        play(context = context, sound = CallSound.CONNECTED, looping = false)
    }

    @Synchronized
    fun playBusy(context: Context, durationMs: Long = 3_500L) {
        play(context = context, sound = CallSound.BUSY, looping = false)

        val stopRunnable = Runnable {
            stop()
        }
        scheduledStop = stopRunnable
        mainHandler.postDelayed(stopRunnable, durationMs)
    }

    @Synchronized
    fun stop() {
        clearScheduledStop()
        releasePlayer(stopPlayback = true)
    }

    @Synchronized
    private fun play(context: Context, sound: CallSound, looping: Boolean) {
        clearScheduledStop()
        releasePlayer(stopPlayback = true)

        val resId = when (sound) {
            CallSound.OUTGOING_RINGING -> R.raw.phone_ringing
            CallSound.CONNECTED -> R.raw.connect
            CallSound.CALL_ENDED -> R.raw.call_end
            CallSound.BUSY -> R.raw.call_busy
        }

        try {
            mediaPlayer = MediaPlayer.create(context.applicationContext, resId)?.apply {
                isLooping = looping
                setOnCompletionListener {
                    synchronized(this@CallSoundPlayer) {
                        releasePlayer(stopPlayback = false)
                    }
                }
                start()
            }
            currentSound = sound
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play call sound: $sound", e)
            releasePlayer(stopPlayback = true)
        }
    }

    @Synchronized
    private fun releasePlayer(stopPlayback: Boolean) {
        val player = mediaPlayer ?: run {
            currentSound = null
            return
        }

        player.setOnCompletionListener(null)
        if (stopPlayback) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: IllegalStateException) {
            }
        }
        player.release()
        mediaPlayer = null
        currentSound = null
    }

    @Synchronized
    private fun clearScheduledStop() {
        scheduledStop?.let { mainHandler.removeCallbacks(it) }
        scheduledStop = null
    }
}