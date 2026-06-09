package com.glyph.glyph_v3.ui.walkietalkie

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.data.service.WalkieTalkieIncomingNotificationHelper
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight trampoline that brings the app to the foreground from a
 * full-screen notification intent, then hands off to the actual incoming WT UI.
 * This is more reliable on OEM builds that suppress direct heavy activity
 * launches from background FCM handling.
 */
class WalkieTalkieIncomingDispatchActivity : AppCompatActivity() {

    companion object {
        private const val MIN_FOREGROUND_HOLD_MS = 2_000L
        private const val RETRY_WINDOW_MS = 8_000L
        private const val RETRY_INTERVAL_MS = 1_000L

        fun createIntent(
            context: Context,
            sessionId: String,
            initiatorId: String,
            initiatorName: String,
            createdAt: Long = 0L,
            offerBase64: String = "",
            offerRevision: Int = 0
        ): Intent = Intent(context, WalkieTalkieIncomingDispatchActivity::class.java).apply {
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_SESSION_ID, sessionId)
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_ID, initiatorId)
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_NAME, initiatorName)
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_CREATED_AT, createdAt)
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_B64, offerBase64)
            putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_REVISION, offerRevision)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private var dispatched = false
    private var createdAtElapsedMs = 0L
    private var retryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAtElapsedMs = SystemClock.elapsedRealtime()
        CallLockScreenHelper.prepareActivityWindow(this)
        CallLockScreenHelper.pulseScreenWake(this, "WTIncomingDispatch:Wake")
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
        retryJob?.cancel()
    }

    private fun dispatchIfNeeded(intent: Intent?) {
        if (dispatched) return

        val sessionId = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_SESSION_ID)
        val initiatorId = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_ID)
        val initiatorName = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_NAME)
        val createdAt = intent?.getLongExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_CREATED_AT, 0L) ?: 0L
        val offerBase64 = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_B64).orEmpty()
        val offerRevision = intent?.getIntExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_REVISION, 0) ?: 0
        val autoAccept = intent?.getBooleanExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_AUTO_ACCEPT, false) == true

        if (sessionId.isNullOrEmpty() || initiatorId.isNullOrEmpty() || initiatorName.isNullOrEmpty()) {
            finishQuietly()
            return
        }

        dispatched = true
        launchIncomingUi(sessionId, initiatorId, initiatorName, createdAt, offerBase64, offerRevision, autoAccept)
    }

    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return

        val sessionId = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_SESSION_ID)
        val initiatorId = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_ID)
        val initiatorName = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_NAME)
        val createdAt = intent?.getLongExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_CREATED_AT, 0L) ?: 0L
        val offerBase64 = intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_B64).orEmpty()
        val offerRevision = intent?.getIntExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_REVISION, 0) ?: 0
        val autoAccept = intent?.getBooleanExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_AUTO_ACCEPT, false) == true

        if (sessionId.isNullOrEmpty() || initiatorId.isNullOrEmpty() || initiatorName.isNullOrEmpty()) {
            return
        }

        retryJob = lifecycleScope.launch {
            val deadline = SystemClock.elapsedRealtime() + RETRY_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline && !isFinishing) {
                delay(RETRY_INTERVAL_MS)
                launchIncomingUi(sessionId, initiatorId, initiatorName, createdAt, offerBase64, offerRevision, autoAccept)
            }
        }
    }

    private fun launchIncomingUi(
        sessionId: String,
        initiatorId: String,
        initiatorName: String,
        createdAt: Long,
        offerBase64: String,
        offerRevision: Int,
        autoAccept: Boolean
    ) {
        val incomingIntent = WalkieTalkieIncomingActivity.createIntent(
            context = applicationContext,
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            offerBase64 = offerBase64,
            offerRevision = offerRevision
        ).apply {
            if (autoAccept) {
                putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_AUTO_ACCEPT, true)
            }
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        startActivity(incomingIntent)
        lifecycleScope.launch {
            val remainingHoldMs =
                MIN_FOREGROUND_HOLD_MS - (SystemClock.elapsedRealtime() - createdAtElapsedMs)
            if (remainingHoldMs > 0) {
                delay(remainingHoldMs)
            }
            delay(500)
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        retryJob?.cancel()
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}