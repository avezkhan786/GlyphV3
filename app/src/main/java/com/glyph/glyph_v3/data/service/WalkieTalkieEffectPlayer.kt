package com.glyph.glyph_v3.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.glyph.glyph_v3.R

object WalkieTalkieEffectPlayer {

    private const val TAG = "WTEffectPlayer"
    private const val DEFAULT_VOLUME = 1.0f

    private var soundPool: SoundPool? = null
    private var effectSoundId: Int = 0
    private var isSoundLoaded = false
    private var playOnLoad = false
    private var fallbackPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    @Synchronized
    fun preload(context: Context) {
        ensureLoaded(context.applicationContext)
    }

    @Synchronized
    fun playRemoteFloorToggle(context: Context) {
        ensureLoaded(context.applicationContext)

        playOrQueue()
    }

    @Synchronized
    private fun playOrQueue() {
        if (isSoundLoaded && effectSoundId != 0) {
            val streamId = soundPool?.play(effectSoundId, DEFAULT_VOLUME, DEFAULT_VOLUME, 1, 0, 1f) ?: 0
            if (streamId == 0) {
                Log.w(TAG, "WT effect playback failed in SoundPool; using fallback player")
                playWithFallbackPlayer()
            }
            return
        }

        playOnLoad = true
    }

    @Synchronized
    fun cleanup() {
        soundPool?.release()
        soundPool = null
        fallbackPlayer?.release()
        fallbackPlayer = null
        effectSoundId = 0
        isSoundLoaded = false
        playOnLoad = false
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        appContext = context.applicationContext
        if (soundPool != null) {
            return
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool?.setOnLoadCompleteListener { pool, sampleId, status ->
            if (status != 0) {
                Log.w(TAG, "Failed to load WT effect sound, status=$status")
                return@setOnLoadCompleteListener
            }

            if (sampleId == effectSoundId) {
                isSoundLoaded = true
                if (playOnLoad) {
                    playOnLoad = false
                    val streamId = pool.play(effectSoundId, DEFAULT_VOLUME, DEFAULT_VOLUME, 1, 0, 1f)
                    if (streamId == 0) {
                        Log.w(TAG, "WT effect queued playback failed in SoundPool; using fallback player")
                        playWithFallbackPlayer()
                    }
                }
            }
        }

        try {
            effectSoundId = soundPool?.load(context, R.raw.walkie_talkie_effect, 1) ?: 0
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load WT effect sound", error)
            cleanup()
        }
    }

    @Synchronized
    private fun playWithFallbackPlayer() {
        try {
            val context = appContext ?: return
            val player = fallbackPlayer ?: MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                        .build()
                )
                setVolume(DEFAULT_VOLUME, DEFAULT_VOLUME)
                setOnCompletionListener { completedPlayer ->
                    completedPlayer.pause()
                    completedPlayer.seekTo(0)
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "WT fallback player error what=$what extra=$extra")
                    mp.reset()
                    mp.release()
                    fallbackPlayer = null
                    true
                }
                val afd = context.resources.openRawResourceFd(R.raw.walkie_talkie_effect)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
            }.also { fallbackPlayer = it }

            if (player.isPlaying) {
                player.pause()
                player.seekTo(0)
            }
            player.setVolume(DEFAULT_VOLUME, DEFAULT_VOLUME)
            player.start()
        } catch (error: Exception) {
            Log.e(TAG, "Failed fallback WT effect playback", error)
            fallbackPlayer?.release()
            fallbackPlayer = null
        }
    }
}