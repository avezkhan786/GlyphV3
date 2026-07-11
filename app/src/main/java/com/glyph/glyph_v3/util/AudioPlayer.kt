package com.glyph.glyph_v3.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()
    
    private val _completionEvents = MutableSharedFlow<Uri?>()
    val completionEvents: SharedFlow<Uri?> = _completionEvents.asSharedFlow()
    
    val isPlayerInitialized: Boolean
        get() = player != null

    private var currentUri: Uri? = null

    fun play(uri: Uri) {
        if (currentUri == uri && player != null) {
            resume()
            return
        }
        
        // Stop current if different URI (preserving currentUri for comparison)
        stop(reset = (currentUri != uri)) 
        currentUri = uri
        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener {
                    start()
                    _isPlaying.value = true
                    startProgressTracker()
                }
                setOnCompletionListener {
                    // Emit completion event before resetting
                    // CRITICAL: Capture the specific URI that finished, because currentUri will be nullified by stop()
                    val finishedUri = uri
                    CoroutineScope(Dispatchers.Main).launch {
                        _completionEvents.emit(finishedUri)
                    }
                    // Reset UI to start
                    stop(reset = true)
                }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("AudioPlayer", "MediaPlayer error: $what, $extra")
                    stop(reset = true)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stop(reset = true)
        }
    }

    fun pause() {
        try {
            if (player?.isPlaying == true) {
                player?.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isPlaying.value = false
        progressJob?.cancel()
    }
    
    fun resume() {
        try {
            // Check for completion before resuming
            if (player?.currentPosition == player?.duration) {
                player?.seekTo(0)
                _progress.value = 0f
                _currentPosition.value = 0
            }
            player?.start()
            _isPlaying.value = true
            startProgressTracker()
        } catch (e: Exception) {
            e.printStackTrace()
            stop(reset = true)
        }
    }

    fun stop(reset: Boolean = true) {
        try {
            player?.let { p ->
                // Use a separate try-catch for state-dependent calls
                try {
                    if (p.isPlaying) {
                        p.stop()
                    }
                } catch (e: Exception) {
                    // Ignore: player might be in Error state or already stopped
                }
                p.release()
            }
        } catch (e: Exception) {
            // Ignore during cleanup
        }
        player = null
        _isPlaying.value = false
        if (reset) {
            _progress.value = 0f
            _currentPosition.value = 0
            currentUri = null
        }
        progressJob?.cancel()
    }
    
    fun seekTo(position: Int) {
        try {
            player?.seekTo(position)
            _currentPosition.value = position
            // Update progress immediately for smooth UI scrubbing
            player?.duration?.let { total ->
                if (total > 0) {
                    _progress.value = position.toFloat() / total
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun setSpeed(speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                player?.playbackParams = player?.playbackParams?.setSpeed(speed) ?: android.media.PlaybackParams().setSpeed(speed)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && player != null && player!!.isPlaying) {
                val current = player!!.currentPosition
                val total = player!!.duration
                if (total > 0) {
                    _progress.value = current.toFloat() / total
                    _currentPosition.value = current
                }
                delay(50)
            }
        }
    }
}
