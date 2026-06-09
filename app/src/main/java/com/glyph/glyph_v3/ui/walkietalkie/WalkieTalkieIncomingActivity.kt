package com.glyph.glyph_v3.ui.walkietalkie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.models.WalkieTalkieSession
import com.glyph.glyph_v3.data.service.LiveAudioForegroundService
import com.glyph.glyph_v3.data.service.WalkieTalkieIncomingNotificationHelper
import com.glyph.glyph_v3.data.service.WalkieTalkieManager
import com.glyph.glyph_v3.data.repo.WalkieTalkieRepository
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.glyph.glyph_v3.R
import java.io.File

/**
 * Full-screen incoming walkie-talkie activity with Accept / Decline buttons.
 * Mirrors IncomingCallActivity for voice calls.
 *
 * - Launched via full-screen intent (shows accept/decline UI on lock screen)
 * - Launched via notification Accept button with [EXTRA_WT_AUTO_ACCEPT] = true → immediately connects
 * - After accept + connection: launches [WalkieTalkieActiveActivity] (works on lock screen)
 */
class WalkieTalkieIncomingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WTIncoming"
        private const val MIN_FOREGROUND_HOLD_MS = 2_000L
        private const val TIMEOUT_MS = 20_000L
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val CONNECT_RETRY_INTERVAL_MS = 2_500L
        private const val CONNECT_RETRY_WINDOW_MS = 12_000L

        fun createIntent(
            context: Context,
            sessionId: String,
            initiatorId: String,
            initiatorName: String,
            createdAt: Long = 0L,
            offerBase64: String = "",
            offerRevision: Int = 0
        ): Intent = Intent(context, WalkieTalkieIncomingActivity::class.java).apply {
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

    private var createdAtElapsedMs = 0L
    private var accepted = false
    private var activeLaunchStarted = false
    private var connectionObserverJob: Job? = null
    private var connectRetryJob: Job? = null
    private var unansweredTimeoutJob: Job? = null
    private var sessionStatusJob: Job? = null
    private var ringtonePlayer: MediaPlayer? = null

    private lateinit var modeBadgeView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var callerNameView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var featureRowView: LinearLayout
    private lateinit var actionRowView: LinearLayout
    private lateinit var footerTextView: TextView
    private lateinit var callerAvatarView: ImageView
    private lateinit var acceptButtonView: Button
    private lateinit var declineButtonView: Button

    private val sessionId: String get() =
        intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_SESSION_ID).orEmpty()
    private val initiatorId: String get() =
        intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_ID).orEmpty()
    private val initiatorName: String get() =
        intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_INITIATOR_NAME).orEmpty()
    private val autoAccept: Boolean get() =
        intent?.getBooleanExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_AUTO_ACCEPT, false) == true
    private val createdAt: Long get() =
        intent?.getLongExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_CREATED_AT, 0L) ?: 0L
    private val offerBase64: String get() =
        intent?.getStringExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_B64).orEmpty()
    private val offerRevision: Int get() =
        intent?.getIntExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_OFFER_REVISION, 0) ?: 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAtElapsedMs = SystemClock.elapsedRealtime()

        // ── Stale session guard: reject if request is too old ──
        if (createdAt > 0L) {
            val ageMs = System.currentTimeMillis() - createdAt
            if (ageMs > com.glyph.glyph_v3.data.models.WalkieTalkieSession.SESSION_REQUEST_TTL_MS) {
                Log.w(TAG, "WT incoming UI opened for stale session $sessionId (${ageMs}ms old) — dismissing")
                WalkieTalkieIncomingNotificationHelper.cancel(this)
                if (initiatorName.isNotBlank()) {
                    WalkieTalkieIncomingNotificationHelper.showMissed(this, initiatorName, sessionId)
                }
                finishQuietly()
                return
            }
        }

        // Lock screen presentation — same as IncomingCallActivity
        CallLockScreenHelper.prepareActivityWindow(this)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:IncomingWake")
        configureEdgeToEdge()
        setContentView(R.layout.activity_walkie_talkie_incoming)
        bindViews()

        if (sessionId.isEmpty() || initiatorName.isEmpty()) {
            finishQuietly()
            return
        }

        WalkieTalkieIncomingNotificationHelper.cancel(this)

        renderIncomingState()
        loadCallerAvatar()
        observeSessionStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        createdAtElapsedMs = SystemClock.elapsedRealtime()
        accepted = false
        activeLaunchStarted = false
        connectionObserverJob?.cancel()
        connectRetryJob?.cancel()
        unansweredTimeoutJob?.cancel()
        sessionStatusJob?.cancel()
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:NewIntentWake")
        if (sessionId.isEmpty() || initiatorName.isEmpty()) {
            finishQuietly()
            return
        }
        WalkieTalkieIncomingNotificationHelper.cancel(this)
        renderIncomingState()
        loadCallerAvatar()
        observeSessionStatus()
    }

    override fun onDestroy() {
        sessionStatusJob?.cancel()
        stopRingtone()
        super.onDestroy()
    }

    private fun bindViews() {
        modeBadgeView = findViewById(R.id.wtModeBadge)
        titleView = findViewById(R.id.wtTitle)
        subtitleView = findViewById(R.id.wtSubtitle)
        callerNameView = findViewById(R.id.wtCallerName)
        statusTextView = findViewById(R.id.wtStatusText)
        featureRowView = findViewById(R.id.wtFeatureRow)
        actionRowView = findViewById(R.id.wtActionRow)
        footerTextView = findViewById(R.id.wtFooterText)
        callerAvatarView = findViewById(R.id.wtCallerAvatar)
        acceptButtonView = findViewById<Button>(R.id.wtAcceptButton).apply {
            setOnClickListener { acceptWalkieTalkie() }
        }
        declineButtonView = findViewById<Button>(R.id.wtDeclineButton).apply {
            setOnClickListener { declineWalkieTalkie() }
        }
    }

    private fun renderIncomingState() {
        val manager = WalkieTalkieManager.getInstance(applicationContext)
        val alreadyHandling = manager.activeSessionId.value == sessionId &&
            manager.state.value in setOf(
                WalkieTalkieManager.State.CONNECTING,
                WalkieTalkieManager.State.CONNECTED_IDLE,
                WalkieTalkieManager.State.TRANSMITTING,
                WalkieTalkieManager.State.RECEIVING
            )

        when {
            alreadyHandling -> {
                // Auto-accept already in progress from the FCM/RTDB path — don't re-trigger.
                // Skip the accept/decline UI and go straight to the active screen observer.
                accepted = true
                stopRingtone()
                WalkieTalkieIncomingNotificationHelper.cancel(this)
                showConnectingUi()
                observeAndLaunchActive()
            }
            autoAccept -> {
                showConnectingUi()
                acceptWalkieTalkie()
            }
            else -> {
                showIncomingUi()
                startUnansweredTimeout()
            }
        }
    }

    private fun ringtoneUri(): Uri {
        return Uri.parse("android.resource://$packageName/${R.raw.wt_ringing}")
    }

    private fun startRingtone() {
        if (ringtonePlayer?.isPlaying == true) return
        stopRingtone()
        runCatching {
            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@WalkieTalkieIncomingActivity, ringtoneUri())
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to start WT ringtone", error)
        }
    }

    private fun stopRingtone() {
        ringtonePlayer?.runCatching {
            stop()
            reset()
            release()
        }
        ringtonePlayer = null
    }

    private fun startUnansweredTimeout() {
        unansweredTimeoutJob?.cancel()
        unansweredTimeoutJob = lifecycleScope.launch {
            delay(TIMEOUT_MS)
            if (!accepted && !isFinishing) {
                declineWalkieTalkie()
            }
        }
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    // ───────────────────── UI ─────────────────────

    private fun showIncomingUi() {
        callerNameView.text = initiatorName
        titleView.text = "Incoming radio ping"
        subtitleView.text = "Half-duplex voice channel"
        statusTextView.text = "Accept to jump into a private push-to-talk lane. Your mic stays muted until you hold Talk."
        modeBadgeView.text = "WALKIE-TALKIE"
        actionRowView.visibility = View.VISIBLE
        featureRowView.visibility = View.VISIBLE
        footerTextView.visibility = View.VISIBLE
        acceptButtonView.text = "Join Channel"
        declineButtonView.text = "Decline"
        startRingtone()
    }

    private fun showConnectingUi() {
        stopRingtone()
        callerNameView.text = initiatorName
        titleView.text = "Securing channel"
        subtitleView.text = "Building the live talk lane"
        statusTextView.text = "Connecting… keep the screen awake for a moment while the audio route locks in."
        modeBadgeView.text = "CHANNEL STARTUP"
        actionRowView.visibility = View.GONE
        featureRowView.visibility = View.GONE
        footerTextView.visibility = View.INVISIBLE
    }

    private fun loadCallerAvatar() {
        callerAvatarView.setImageResource(R.drawable.ic_default_avatar)

        val cachedAvatarPath = initiatorId.takeIf { it.isNotBlank() }
            ?.let { AvatarCacheManager.getLocalAvatarPath(it) }

        if (!cachedAvatarPath.isNullOrBlank()) {
            Glide.with(this)
                .load(File(cachedAvatarPath))
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(callerAvatarView)
            return
        }

        if (initiatorId.isBlank()) {
            return
        }

        lifecycleScope.launch {
            val remoteAvatarUrl = try {
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(initiatorId)
                    .get()
                    .await()
                    .getString("profileImageUrl")
                    .orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve WT caller avatar", e)
                ""
            }

            if (remoteAvatarUrl.isBlank() || isDestroyed) {
                return@launch
            }

            Glide.with(this@WalkieTalkieIncomingActivity)
                .load(remoteAvatarUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(callerAvatarView)

            launch {
                runCatching {
                    AvatarCacheManager.cacheAvatar(
                        userId = initiatorId,
                        avatarUrl = remoteAvatarUrl,
                        context = applicationContext
                    )
                }
            }
        }
    }

    // ───────────────────── ACCEPT / DECLINE ─────────────────────

    private fun acceptWalkieTalkie() {
        if (accepted) return

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE
            )
            return
        }

        accepted = true
        stopRingtone()
        WalkieTalkieIncomingNotificationHelper.cancel(this)

        val manager = WalkieTalkieManager.getInstance(applicationContext)

        // If already connected to this session, go straight to active screen
        if (manager.activeSessionId.value == sessionId &&
            manager.state.value in setOf(
                WalkieTalkieManager.State.CONNECTING,
                WalkieTalkieManager.State.CONNECTED_IDLE,
                WalkieTalkieManager.State.TRANSMITTING,
                WalkieTalkieManager.State.RECEIVING
            )
        ) {
            launchActiveScreen()
            return
        }

        // Start FGS + connect
        LiveAudioForegroundService.start(applicationContext, LiveAudioForegroundService.MODE_WALKIE_TALKIE)
        manager.handleIncomingRequestFromFcm(
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            initialOfferBase64 = offerBase64,
            initialOfferRevision = offerRevision
        )

        // Show connecting UI and watch for connection
        showConnectingUi()
        observeAndLaunchActive()
        startConnectRetryLoop()
    }

    private fun declineWalkieTalkie() {
        stopRingtone()
        WalkieTalkieIncomingNotificationHelper.cancel(this)

        // End RTDB session
        lifecycleScope.launch {
            try {
                WalkieTalkieRepository().rejectSession(sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to end WT session on decline", e)
            }
        }
        finishQuietly()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                acceptWalkieTalkie()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied, declining WT")
                declineWalkieTalkie()
            }
        }
    }

    // ───────────────────── CONNECTION OBSERVATION ─────────────────────

    private fun observeAndLaunchActive() {
        connectionObserverJob?.cancel()
        connectionObserverJob = lifecycleScope.launch {
            val manager = WalkieTalkieManager.getInstance(applicationContext)
            manager.state.collectLatest { state ->
                when (state) {
                    WalkieTalkieManager.State.CONNECTING,
                    WalkieTalkieManager.State.CONNECTED_IDLE,
                    WalkieTalkieManager.State.TRANSMITTING,
                    WalkieTalkieManager.State.RECEIVING -> {
                        connectRetryJob?.cancel()
                        val remainingHoldMs =
                            MIN_FOREGROUND_HOLD_MS - (SystemClock.elapsedRealtime() - createdAtElapsedMs)
                        if (remainingHoldMs > 0) delay(remainingHoldMs)
                        launchActiveScreen()
                    }
                    WalkieTalkieManager.State.DISCONNECTING -> {
                        // Session cancelled / timed out while we were connecting — dismiss immediately.
                        connectRetryJob?.cancel()
                        finishQuietly()
                    }
                    WalkieTalkieManager.State.IDLE -> {
                        // Connection failed or was reset.
                        // Use a generous delay because cold RTDB connections
                        // can take several seconds on first launch.
                        if (accepted) {
                            delay(5_000)
                            if (manager.state.value == WalkieTalkieManager.State.IDLE) {
                                connectRetryJob?.cancel()
                                Log.w(TAG, "State still IDLE after 5s — giving up")
                                finishQuietly()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Safety: close after timeout even if connection never established
        lifecycleScope.launch {
            delay(30_000L)
            if (!isFinishing && !activeLaunchStarted) {
                Log.w(TAG, "Connection timeout — finishing incoming activity")
                finishQuietly()
            }
        }
    }

    private fun startConnectRetryLoop() {
        connectRetryJob?.cancel()
        connectRetryJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val manager = WalkieTalkieManager.getInstance(applicationContext)
            while (!isFinishing && !activeLaunchStarted) {
                delay(CONNECT_RETRY_INTERVAL_MS)
                val state = manager.state.value
                // Stop retrying once we've reached a settled state
                if (state == WalkieTalkieManager.State.CONNECTED_IDLE ||
                    state == WalkieTalkieManager.State.TRANSMITTING ||
                    state == WalkieTalkieManager.State.RECEIVING ||
                    state == WalkieTalkieManager.State.DISCONNECTING
                ) {
                    return@launch
                }

                if (SystemClock.elapsedRealtime() - startedAt >= CONNECT_RETRY_WINDOW_MS) {
                    Log.w(TAG, "WT still not connected after retry window")
                    return@launch
                }

                // Only retry if the manager has gone fully IDLE (setup coroutine failed+cleaned up).
                // If it's still CONNECTING the setup is in progress — don't interrupt it.
                if (state != WalkieTalkieManager.State.IDLE) {
                    continue
                }

                Log.d(TAG, "WT connect retry — state was IDLE, re-triggering setup with offer")
                // Always re-pass the offer and revision so the responder can process it
                // even if the previous attempt failed before setRemoteDescription.
                manager.handleIncomingRequestFromFcm(
                    sessionId = sessionId,
                    initiatorId = initiatorId,
                    initiatorName = initiatorName,
                    createdAt = createdAt,
                    initialOfferBase64 = offerBase64,
                    initialOfferRevision = offerRevision
                )
            }
        }
    }

    // ───────────────────── LAUNCH ACTIVE SCREEN ─────────────────────

    private fun launchActiveScreen() {
        if (activeLaunchStarted) return
        activeLaunchStarted = true

        val manager = WalkieTalkieManager.getInstance(applicationContext)
        val initId = initiatorId.ifEmpty {
            manager.connectedPeerId.value.orEmpty()
        }.ifEmpty {
            Log.w(TAG, "No initiator ID — cannot launch active screen")
            finishQuietly()
            return
        }


        val activeIntent = WalkieTalkieActiveActivity.createIntent(
            context = applicationContext,
            sessionId = sessionId,
            peerId = initId,
            peerName = initiatorName
        )
        startActivity(activeIntent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0) // Seamless transition — no flash
    }

    private fun finishQuietly() {
        connectionObserverJob?.cancel()
        connectRetryJob?.cancel()
        unansweredTimeoutJob?.cancel()
        sessionStatusJob?.cancel()
        stopRingtone()
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun observeSessionStatus() {
        sessionStatusJob?.cancel()
        sessionStatusJob = lifecycleScope.launch {
            var receivedRealSnapshot = false
            WalkieTalkieRepository().observeSession(sessionId).collectLatest { session ->
                val status = session?.status
                if (session == null) {
                    if (!receivedRealSnapshot) {
                        Log.d(TAG, "WT session null before first real snapshot; waiting for cold Firebase reconnect")
                        if (offerBase64.isBlank()) {
                            delay(35_000L)
                            if (!receivedRealSnapshot && !accepted && !isFinishing) {
                                Log.w(TAG, "WT session never appeared after cold reconnect wait")
                                finishQuietly()
                            }
                        }
                        return@collectLatest
                    }
                    Log.w(TAG, "WT session disappeared after initial snapshot while incoming UI visible")
                    finishQuietly()
                    return@collectLatest
                }
                receivedRealSnapshot = true

                when (status) {
                    WalkieTalkieSession.STATUS_CANCELLED,
                    WalkieTalkieSession.STATUS_TIMEOUT,
                    WalkieTalkieSession.STATUS_REJECTED,
                    WalkieTalkieSession.STATUS_ENDED -> {
                        WalkieTalkieIncomingNotificationHelper.cancel(this@WalkieTalkieIncomingActivity)
                        stopRingtone()
                        finishQuietly()
                    }
                    WalkieTalkieSession.STATUS_ACTIVE -> {
                        if (!accepted) {
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
