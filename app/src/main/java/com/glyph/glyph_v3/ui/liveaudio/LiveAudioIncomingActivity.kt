package com.glyph.glyph_v3.ui.liveaudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.data.service.LiveAudioIncomingNotificationHelper
import com.glyph.glyph_v3.data.service.LiveAudioSharingManager
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Temporary foreground surface used to legally start live-audio microphone capture on Android 14.
 * It is launched from a full-screen notification intent, starts the live-audio flow, then closes.
 */
class LiveAudioIncomingActivity : AppCompatActivity() {

    companion object {
        private const val MIN_FOREGROUND_HOLD_MS = 2_000L
        private const val RETRY_WINDOW_MS = 8_000L
        private const val RETRY_INTERVAL_MS = 1_000L

        fun createIntent(
            context: Context,
            sessionId: String,
            listenerName: String
        ): Intent = Intent(context, LiveAudioIncomingActivity::class.java).apply {
            putExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_SESSION_ID, sessionId)
            putExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_LISTENER_NAME, listenerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private var dispatched = false
    private var createdAtElapsedMs = 0L
    private var retryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAtElapsedMs = SystemClock.elapsedRealtime()
        CallLockScreenHelper.prepareActivityWindow(this)
        observeAndFinish()
    }

    override fun onResume() {
        super.onResume()
        dispatchIfNeeded(intent)
        ensureRetryLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatched = false
    }

    private fun dispatchIfNeeded(intent: Intent?) {
        if (dispatched) return
        val sessionId = intent?.getStringExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_SESSION_ID)
        val listenerName = intent?.getStringExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_LISTENER_NAME)
        if (sessionId.isNullOrEmpty() || listenerName.isNullOrEmpty()) {
            finishQuietly()
            return
        }

        dispatched = true
        LiveAudioSharingManager.getInstance(applicationContext)
            .handleIncomingRequestFromFcm(sessionId, listenerName)
    }

    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return
        val sessionId = intent?.getStringExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_SESSION_ID)
        val listenerName = intent?.getStringExtra(LiveAudioIncomingNotificationHelper.EXTRA_LIVE_AUDIO_LISTENER_NAME)
        if (sessionId.isNullOrEmpty() || listenerName.isNullOrEmpty()) return

        retryJob = lifecycleScope.launch {
            val manager = LiveAudioSharingManager.getInstance(applicationContext)
            val deadline = SystemClock.elapsedRealtime() + RETRY_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline && !isFinishing) {
                delay(RETRY_INTERVAL_MS)
                val state = manager.state.value
                if (state == LiveAudioSharingManager.State.STREAMING ||
                    state == LiveAudioSharingManager.State.STOPPING
                ) {
                    return@launch
                }
                if (state == LiveAudioSharingManager.State.IDLE) {
                    manager.handleIncomingRequestFromFcm(sessionId, listenerName)
                }
            }
        }
    }

    private fun observeAndFinish() {
        val manager = LiveAudioSharingManager.getInstance(applicationContext)
        lifecycleScope.launch {
            manager.state.collectLatest { state ->
                when (state) {
                    LiveAudioSharingManager.State.STREAMING -> {
                        val remainingHoldMs =
                            MIN_FOREGROUND_HOLD_MS - (SystemClock.elapsedRealtime() - createdAtElapsedMs)
                        if (remainingHoldMs > 0) {
                            delay(remainingHoldMs)
                        }
                        delay(500)
                        finishQuietly()
                    }
                    else -> Unit
                }
            }
        }

        lifecycleScope.launch {
            delay(RETRY_WINDOW_MS)
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}