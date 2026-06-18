package com.glyph.glyph_v3.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import com.glyph.glyph_v3.data.cache.IceCandidateHistoryCache
import com.glyph.glyph_v3.data.cache.UserProfileCache
import com.glyph.glyph_v3.data.models.WalkieTalkieFloor
import com.glyph.glyph_v3.data.models.WalkieTalkieIceCandidate
import com.glyph.glyph_v3.data.models.WalkieTalkiePttEffectEvent
import com.glyph.glyph_v3.data.models.WalkieTalkieSession
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptSettingsRepository
import com.glyph.glyph_v3.data.repo.WalkieTalkieRepository
import com.glyph.glyph_v3.data.webrtc.WebRtcIceConfig
import com.glyph.glyph_v3.data.webrtc.WalkieTalkiePeerClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the walkie-talkie feature: bidirectional WebRTC audio with
 * push-to-talk floor control.
 *
 * State machine:
 * ```
 *  IDLE ─► REQUESTING ─► CONNECTING ─► CONNECTED_IDLE ◄─► TRANSMITTING
 *                                             ▲                │
 *                                             └── RECEIVING ◄──┘
 *                                                      │
 *  IDLE ◄──────────── DISCONNECTING ◄──────────────────┘
 * ```
 *
 * Audio flow:
 *  - WebRTC connection is established ONCE with both peers having audio tracks.
 *  - Both tracks start MUTED. PTT just flips the local track on/off (0 ms latency).
 *  - RTDB floor control is for UI coordination only — audio gating is local.
 *
 * This is a process-scoped singleton. Use [getInstance] to access.
 */
class WalkieTalkieManager private constructor(
    private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch(Dispatchers.Default) {
            runCatching { WebRtcIceConfig.refreshIceServersIfConfigured() }
                .onFailure { error -> Log.w(TAG, "Failed to prefetch TURN credentials", error) }
            runCatching { WalkieTalkiePeerClient.prewarm(context) }
                .onFailure { error -> Log.w(TAG, "Failed to prewarm WT WebRTC stack", error) }
            runCatching { WalkieTalkieEffectPlayer.preload(context) }
                .onFailure { error -> Log.w(TAG, "Failed to prewarm WT effect player", error) }
        }
    }

    companion object {
        private const val TAG = "WalkieTalkieMgr"
        // Only block when no relay is configured (dynamic fetch via HTTP).
        private const val TURN_READY_WAIT_NO_RELAY_MS = 3_500L
        // 25 s gives TURN-backed reconnects time to finish on slower mobile or
        // carrier-filtered networks before we terminate the session.
        private const val CONNECTION_RECOVERY_TIMEOUT_MS = 25_000L
        /** Interval between ICE-restart offer refreshes while waiting for the callee to answer.
         *  Must be long enough for the callee to complete a full setRemoteDescription + createAnswer
         *  round-trip (typically 2-5 s) plus RTDB propagation latency.  Once the callee rings
         *  the loop is cancelled entirely — this only covers the pre-ringing window. */
        // 12 s gives the initial offer → FCM → RTDB reconnect → ringing path enough
        // time to complete on a 60-second-idle device before an ICE restart is triggered.
        // Reduced from 20 s after connection-aware retry loops make ringing writes faster;
        // the loop is cancelled as soon as STATUS_RINGING is observed.
        private const val OFFER_REFRESH_INTERVAL_MS = 12_000L
        /** Caller-side timeout before the callee has even rung.  After the callee marks
         *  STATUS_RINGING the timeout is cancelled and restarted with SESSION_REQUEST_TTL_MS
         *  so the user has time to manually accept. */
        private const val CALLING_TIMEOUT_MS = 60_000L
        /** Max WebRTC reconnect attempts before giving up and terminating. */
        private const val MAX_RECONNECT_RETRIES = 3

        @Volatile
        private var INSTANCE: WalkieTalkieManager? = null

        fun getInstance(context: Context): WalkieTalkieManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalkieTalkieManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // ── Public state ────────────────────────────────────────────────

    enum class State {
        IDLE,
        /** Initiator: waiting for responder to accept */
        REQUESTING,
        /** Both: WebRTC negotiation in progress */
        CONNECTING,
        /** Both: connected, neither talking */
        CONNECTED_IDLE,
        /** Local user is holding PTT — transmitting */
        TRANSMITTING,
        /** Remote user is holding PTT — receiving */
        RECEIVING,
        /** Tearing down */
        DISCONNECTING
    }

    enum class SessionPhase {
        IDLE,
        REQUESTING,
        RINGING,
        CONNECTED,
        ENDED,
        CANCELLED,
        TIMED_OUT
    }

    private enum class TerminationReason {
        LOCAL_CANCEL,
        LOCAL_REJECT,
        LOCAL_END,
        REMOTE_CANCEL,
        REMOTE_REJECT,
        REMOTE_END,
        ERROR,
        INVALID_SESSION,
        TIMEOUT,
        SHUTDOWN
    }

    private data class IncomingRequestSeed(
        val sessionId: String,
        val initiatorId: String,
        val initiatorName: String,
        val createdAt: Long,
        val initialOffer: String = "",
        val initialOfferRevision: Int = 0
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val isLocalPttActive = AtomicBoolean(false)

    private val _sessionPhase = MutableStateFlow(SessionPhase.IDLE)
    val sessionPhase: StateFlow<SessionPhase> = _sessionPhase.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _connectedPeerId = MutableStateFlow<String?>(null)
    val connectedPeerId: StateFlow<String?> = _connectedPeerId.asStateFlow()

    /** Emitted once when the remote user denies a floor claim (PTT rejected). */
    private val _floorDenied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val floorDenied: SharedFlow<Unit> = _floorDenied.asSharedFlow()

    /** Emitted when the outgoing call times out before the callee accepts. */
    private val _callTimedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val callTimedOut: SharedFlow<Unit> = _callTimedOut.asSharedFlow()

    /** Emitted with the remote user's ID when their PTT state changes. */
    private val _remoteSpeaking = MutableStateFlow(false)
    val remoteSpeaking: StateFlow<Boolean> = _remoteSpeaking.asStateFlow()

    // ── Internal ────────────────────────────────────────────────────

    private val repository = WalkieTalkieRepository()
    private val autoAcceptSettingsRepository = WalkieTalkieAutoAcceptSettingsRepository()
    private val currentUserId: String get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private var peerClient: WalkieTalkiePeerClient? = null
    private var sessionObserverJob: Job? = null
    private var iceObserverJob: Job? = null
    private var peerEventJob: Job? = null
    private var floorObserverJob: Job? = null
    private var pttEffectObserverJob: Job? = null
    private var incomingRequestJob: Job? = null
    private var incomingSetupJob: Job? = null
    private var connectionRecoveryJob: Job? = null
    private var negotiationTaskJob: Job? = null
    private var pendingOfferRefreshJob: Job? = null
    private var callingTimeoutJob: Job? = null
    private var isCurrentSessionInitiator = false
    private var latestLocalOfferRevision = 0
    private var latestRemoteOfferRevision = 0
    private var latestLocalAnswerRevision = 0
    private var latestRemoteAnswerRevision = 0
    private var connectionRetryCount = 0
    /** Deduplicates remote ICE candidates across RTDB reconnect re-deliveries.
     *  RTDB fires child_added for ALL existing children on every reconnect, causing
     *  duplicate candidates to be added to WebRTC without this guard. */
    private val processedCandidates = HashSet<String>()

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn: Boolean = false
    private var savedMicrophoneMute: Boolean = false
    private var savedCommunicationDevice: AudioDeviceInfo? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var audioSessionStarted = false
    private var hasConnectedOnce = false
    private var wasRemoteSpeaking = false
    private var lastHandledRemotePttEffectSequence = 0L
    private val terminationInProgress = AtomicBoolean(false)
    private var setupWakeLock: PowerManager.WakeLock? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun currentUserDisplayName(): String {
        val cachedName = UserProfileCache.get(context, currentUserId)?.username
            ?.trim()
            .orEmpty()
        if (cachedName.isNotEmpty()) return cachedName

        val authName = FirebaseAuth.getInstance().currentUser?.displayName
            ?.trim()
            .orEmpty()
        if (authName.isNotEmpty()) return authName

        return currentUserId
    }

    fun primeTransport(reason: String, forceTokenRefresh: Boolean = false) {
        repository.primeTransport(reason, forceTokenRefresh)
    }

    private suspend fun ensureTurnReadyForPeerSetup(role: String): Boolean {
        if (WebRtcIceConfig.hasRelayConfigured()) {
            // Static relay is already configured. The dynamic refresh is
            // prefetched asynchronously in init{} — never block session setup
            // waiting for it. Launch a background refresh to keep creds fresh.
            scope.launch(Dispatchers.IO) {
                runCatching { WebRtcIceConfig.refreshIceServersIfConfigured() }
                    .onFailure { error -> Log.w(TAG, "WT $role: background TURN refresh failed", error) }
            }
            return true
        }

        // No relay configured — block for dynamic fetch
        val turnReady = withTimeoutOrNull(TURN_READY_WAIT_NO_RELAY_MS) {
            WebRtcIceConfig.refreshIceServersIfConfigured()
        } ?: false

        if (!turnReady && !WebRtcIceConfig.hasRelayConfigured()) {
            Log.w(TAG, "WT $role: TURN credentials not ready before peer setup; cross-network calls may fail")
        }
        return turnReady
    }

    /**
     * Retry [operation] with RTDB-connection-aware backoff.
     *
     * On the first failure, waits for the RTDB WebSocket to reconnect (via
     * `.info/connected`) instead of using fixed delays.  Once connected, falls
     * back to short exponential backoff (500 ms, 1 s, 2 s, 3 s, 5 s) capped at
     * [maxTotalMs].  This replaces the old 0/3/8/15/25 s fixed-delay schedule
     * that wasted 10-25 s of dead time on cold RTDB starts.
     *
     * On warm RTDB the first attempt (0 ms) succeeds immediately — zero overhead.
     */
    private suspend fun retryWithConnectionAwareBackoff(
        label: String,
        maxTotalMs: Long = 25_000L,
        operation: suspend () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val delays = longArrayOf(0L, 500L, 1_000L, 2_000L, 3_000L, 5_000L)
        var waitedForConnection = false

        for (i in delays.indices) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= maxTotalMs) break

            if (i > 0) {
                val remaining = maxTotalMs - (System.currentTimeMillis() - startTime)
                if (remaining <= 0L) break
                delay(delays[i].coerceAtMost(remaining))
            }

            if (operation()) {
                Log.d(TAG, "$label: succeeded (attempt=${i + 1}, elapsed=${System.currentTimeMillis() - startTime}ms)")
                return true
            }

            // After the first failure, wait for RTDB to reconnect before retrying.
            // This replaces the old fixed 3/8/15/25 s blind delays with a signal-driven
            // wait that resumes as soon as the WebSocket is up.
            if (!waitedForConnection && i == 0) {
                waitedForConnection = true
                Log.d(TAG, "$label: first attempt failed — waiting for RTDB connection")
                val remaining = maxTotalMs - (System.currentTimeMillis() - startTime)
                if (remaining > 1_000L) {
                    repository.awaitRealtimeConnection(remaining.coerceAtMost(20_000L))
                }
            }
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  INITIATOR SIDE — user taps "Walkie-Talkie" button
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start a walkie-talkie session with another user.
     * Creates an RTDB session and waits for responder to join.
     */
    fun startSession(peerId: String) {
        if (_state.value != State.IDLE) {
            Log.w(TAG, "startSession ignored — state=${_state.value}")
            return
        }
        stopWatchingForRequests()
        _state.value = State.REQUESTING
        _sessionPhase.value = SessionPhase.REQUESTING
        _connectedPeerId.value = peerId

        // Launch heavy session setup on Dispatchers.Default to keep the UI thread
        // free for the overlay entrance animation. Only the FGS start (which calls
        // startForegroundService) must happen on the main thread.
        scope.launch(Dispatchers.Default) {
            try {
                val sessionId = repository.newSessionId()
                _activeSessionId.value = sessionId

                // Prime RTDB transport immediately — kick off WebSocket reconnect
                // before any other setup so the connection is warm by the time we
                // need to write the session/offer. On cold start (idle 20+ min)
                // this saves 2-5 s of RTDB reconnect time.
                repository.primeTransport("startSession")

                // Start FGS for mic access — must be on main thread
                val peerDisplayName = UserProfileCache.get(context, peerId)?.username?.trim()
                    .takeUnless { it.isNullOrBlank() } ?: "User"
                withContext(Dispatchers.Main) {
                    LiveAudioForegroundService.start(
                        context = context,
                        mode = LiveAudioForegroundService.MODE_WALKIE_TALKIE,
                        notificationTitle = "Walkie-Talkie: $peerDisplayName",
                        notificationText = "Push-to-talk session connecting…",
                        wtSessionId = sessionId,
                        wtPeerId = peerId,
                        wtPeerName = peerDisplayName
                    )
                }

                withContext(Dispatchers.Main) { startAudioSession() }
                ensureTurnReadyForPeerSetup(role = "initiator")

                // History-based relay optimisation: if previous sessions with this peer
                // required TURN relay, skip host/srflx candidate trials and go straight
                // to TURN. Reduces cross-network connect time from ~60 s to ~2-5 s.
                val initiatorRelayOnly = IceCandidateHistoryCache.shouldPreferRelay(
                    context, currentUserId, peerId
                )

                // Initialize bidirectional WebRTC (as initiator)
                // WebRTC PeerConnectionFactory.createPeerConnection and createOffer involve
                // native socket creation and ICE candidate gathering — run on Default.
                val client = WalkieTalkiePeerClient(
                    context, sessionId, currentUserId, isInitiator = true,
                    relayOnly = initiatorRelayOnly
                )
                client.initialize()
                peerClient = client
                isCurrentSessionInitiator = true
                latestLocalOfferRevision = 0
                latestRemoteOfferRevision = 0
                latestLocalAnswerRevision = 0
                latestRemoteAnswerRevision = 0

                observePeerEvents(client)

                // Create the first offer before writing the session so the responder can
                // answer immediately after the wake-up push lands.
                val offer = client.createOffer()
                latestLocalOfferRevision = 1

                // RTDB writes are async and work from any dispatcher
                repository.createSession(
                    responderId = peerId,
                    initiatorName = currentUserDisplayName(),
                    sessionId = sessionId,
                    initialOffer = offer.description,
                    initialOfferRevision = latestLocalOfferRevision
                )

                // Keep the session + candidates RTDB paths synced locally so a brief
                // network blip during ICE negotiation doesn't stall candidate delivery.
                repository.primeSession(sessionId)

                // Start observing ICE candidates
                observeIceCandidates(sessionId, client)

                // Watch for answer from responder
                observeSessionAsInitiator(sessionId, client)
                startPendingOfferRefreshLoop(sessionId)

                // Caller-side timeout: if callee doesn't connect within CALLING_TIMEOUT_MS,
                // cancel the session and return to idle so the caller isn't stuck forever.
                callingTimeoutJob?.cancel()
                callingTimeoutJob = scope.launch {
                    delay(CALLING_TIMEOUT_MS)
                    if (!hasConnectedOnce &&
                        (_state.value == State.REQUESTING || _state.value == State.CONNECTING)
                    ) {
                        Log.w(TAG, "WT calling timeout — callee did not connect within ${CALLING_TIMEOUT_MS}ms")
                        terminateSession(TerminationReason.TIMEOUT)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "startSession failed", e)
                terminateSession(TerminationReason.ERROR)
            }
        }
    }

    private fun observeSessionAsInitiator(sessionId: String, client: WalkieTalkiePeerClient) {
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            repository.observeSession(sessionId).collect { session ->
                if (session == null) return@collect

                val remoteAnswerRevision = session.answerRevision.coerceAtLeast(
                    if (!session.answer.isNullOrBlank()) 1 else 0
                )

                when {
                    session.status == WalkieTalkieSession.STATUS_RINGING -> {
                        if (_sessionPhase.value != SessionPhase.RINGING) {
                            _sessionPhase.value = SessionPhase.RINGING
                            // CRITICAL: callee has received the offer and is setting up WebRTC.
                            // Stop sending ICE-restart offers — each new offer invalidates the
                            // callee's in-progress setRemoteDescription + createAnswer, causing
                            // a WebRTC state error and disconnecting them.
                            pendingOfferRefreshJob?.cancel()
                            pendingOfferRefreshJob = null
                            Log.d(TAG, "WT callee ringing — offer refresh loop stopped for session=$sessionId")
                            // Restart calling timeout with a longer window now that the callee
                            // is actively ringing (user may need time to tap Accept).
                            callingTimeoutJob?.cancel()
                            callingTimeoutJob = scope.launch {
                                delay(WalkieTalkieSession.SESSION_REQUEST_TTL_MS)
                                if (!hasConnectedOnce && _activeSessionId.value == sessionId) {
                                    Log.w(TAG, "WT ringing timeout — callee did not accept within ${WalkieTalkieSession.SESSION_REQUEST_TTL_MS}ms")
                                    terminateSession(TerminationReason.TIMEOUT)
                                }
                            }
                        }
                    }
                    !session.answer.isNullOrBlank() && remoteAnswerRevision > latestRemoteAnswerRevision -> {
                        val answeredOfferRevision = session.answeredOfferRevision.coerceAtLeast(0)
                        if (answeredOfferRevision in 1 until latestLocalOfferRevision) {
                            latestRemoteAnswerRevision = remoteAnswerRevision
                            return@collect
                        }
                        try {
                            pendingOfferRefreshJob?.cancel()
                            pendingOfferRefreshJob = null
                            client.setRemoteDescription(
                                SessionDescription(SessionDescription.Type.ANSWER, session.answer)
                            )
                            latestRemoteAnswerRevision = remoteAnswerRevision
                            _state.value = State.CONNECTING
                            // Answer received — cancel whatever pre-ringing or ringing timeout is
                            // running and give ICE a dedicated window to establish connectivity.
                            // Without this, if STATUS_RINGING was never delivered (e.g. RTDB
                            // write failed silently), the 30s pre-ringing timer fires and kills
                            // the session while ICE is still doing connectivity checks, which
                            // is the primary reason the first call fails every time.
                            callingTimeoutJob?.cancel()
                            callingTimeoutJob = scope.launch {
                                delay(WalkieTalkieSession.SESSION_REQUEST_TTL_MS)
                                if (!hasConnectedOnce && _activeSessionId.value == sessionId) {
                                    Log.w(TAG, "WT ICE timeout — did not connect within ${WalkieTalkieSession.SESSION_REQUEST_TTL_MS}ms of answer")
                                    terminateSession(TerminationReason.TIMEOUT)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Initiator: failed to set answer", e)
                            terminateSession(TerminationReason.ERROR)
                        }
                    }
                    session.status == WalkieTalkieSession.STATUS_CANCELLED -> {
                        terminateSession(TerminationReason.REMOTE_CANCEL, signalRemote = false)
                    }
                    session.status == WalkieTalkieSession.STATUS_TIMEOUT -> {
                        terminateSession(TerminationReason.TIMEOUT, signalRemote = false)
                    }
                    session.status == WalkieTalkieSession.STATUS_ENDED -> {
                        terminateSession(TerminationReason.REMOTE_END, signalRemote = false)
                    }
                    session.status == WalkieTalkieSession.STATUS_REJECTED -> {
                        terminateSession(TerminationReason.REMOTE_REJECT, signalRemote = false)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  RESPONDER SIDE — incoming walkie-talkie request
    // ═══════════════════════════════════════════════════════════════

    /** Start watching for incoming WT requests. */
    fun startWatchingForRequests() {
        repository.primeTransport("startWatchingForRequests")

        // Attach the Firebase listener permanently — idempotent, no-op if already attached.
        // We never detach/re-attach during normal operation to avoid the Firebase RTDB
        // "listen() called twice for same QuerySpec" crash that occurs when the SDK
        // reissues listen() for all registered queries on reconnect before a pending
        // unlisten is fully processed.
        repository.attachIncomingRequestListener()

        // Gate event processing: only collect when IDLE and no collection is running.
        if (_state.value != State.IDLE) return
        if (incomingRequestJob?.isActive == true) return
        incomingRequestJob = scope.launch {
            repository.incomingRequestEvents().collect { session ->
                handleObservedIncomingRequest(session)
            }
        }
    }

    private fun handleObservedIncomingRequest(session: WalkieTalkieSession) {
        // Reject stale/expired sessions observed via RTDB
        if (!session.isJoinable()) {
            Log.w(TAG, "Ignoring non-joinable incoming WT request ${session.sessionId} (status=${session.status})")
            return
        }

        val initiatorName = session.initiatorName.takeIf { it.isNotBlank() } ?: session.initiatorId
        val seed = IncomingRequestSeed(
            sessionId = session.sessionId,
            initiatorId = session.initiatorId,
            initiatorName = initiatorName,
            createdAt = session.createdAt,
            initialOffer = session.offer.orEmpty(),
            initialOfferRevision = session.offerRevision
        )
        val seedOfferBase64 = encodeOffer(seed.initialOffer)

        scope.launch {
            runCatching { repository.markSessionRinging(session.sessionId) }
                .onFailure { error -> Log.w(TAG, "Failed to mark WT session ringing", error) }
        }

        scope.launch {
            val shouldAutoAccept = runCatching {
                autoAcceptSettingsRepository.shouldAutoAcceptIncomingCall(context, session.initiatorId)
            }.getOrElse { error ->
                Log.w(TAG, "Failed to evaluate WT auto-accept policy", error)
                false
            }

            if (shouldAutoAccept) {
                LiveAudioForegroundService.start(
                    context = context,
                    mode = LiveAudioForegroundService.MODE_WALKIE_TALKIE,
                    notificationTitle = "Walkie-Talkie Active",
                    notificationText = "Receiving live audio from $initiatorName",
                    showDisableAutoAcceptAction = true,
                    requiresMicrophoneAccess = false,
                    wtSessionId = seed.sessionId,
                    wtPeerId = seed.initiatorId,
                    wtPeerName = seed.initiatorName
                )
                handleIncomingRequestFromFcm(
                    sessionId = seed.sessionId,
                    initiatorId = seed.initiatorId,
                    initiatorName = seed.initiatorName,
                    createdAt = seed.createdAt,
                    initialOfferBase64 = seedOfferBase64,
                    initialOfferRevision = seed.initialOfferRevision,
                    backgroundReceiveOnly = true
                )
            } else {
                WalkieTalkieIncomingNotificationHelper.show(
                    context = context,
                    sessionId = seed.sessionId,
                    initiatorId = seed.initiatorId,
                    initiatorName = seed.initiatorName,
                    createdAt = seed.createdAt,
                    offerBase64 = seedOfferBase64,
                    offerRevision = seed.initialOfferRevision
                )
            }
        }
    }

    fun stopWatchingForRequests() {
        // Cancel event collection but do NOT detach the Firebase listener.
        // The listener must stay registered to avoid the "listen() called twice"
        // crash that occurs when detach + re-attach races Firebase reconnection.
        incomingRequestJob?.cancel()
        incomingRequestJob = null
    }

    /**
     * Called when FCM delivers a WALKIE_TALKIE_REQUEST while backgrounded.
     */
    fun handleIncomingRequestFromFcm(
        sessionId: String,
        initiatorId: String = "",
        initiatorName: String,
        createdAt: Long = 0L,
        initialOfferBase64: String = "",
        initialOfferRevision: Int = 0,
        backgroundReceiveOnly: Boolean = false
    ) {
        if (_state.value != State.IDLE) {
            if (_activeSessionId.value == sessionId) {
                when (_state.value) {
                    State.CONNECTED_IDLE,
                    State.TRANSMITTING,
                    State.RECEIVING -> {
                        return
                    }
                    State.CONNECTING -> {
                        if (peerClient != null) {
                            return
                        }
                        if (incomingSetupJob?.isActive == true) {
                            return
                        }
                    }
                    else -> {
                    }
                }
            } else {
                Log.w(TAG, "handleIncomingRequestFromFcm: stale state=${_state.value}, force-resetting")
                val staleSessionId = _activeSessionId.value
                cleanupInternal()
                if (staleSessionId != null) {
                    scope.launch { try { repository.endSession(staleSessionId, endReason = WalkieTalkieSession.END_REASON_ERROR) } catch (_: Exception) {} }
                }
            }
        }
        // Set CONNECTING synchronously so observers never see IDLE after accept
        stopWatchingForRequests()
        _state.value = State.CONNECTING
        _sessionPhase.value = SessionPhase.CONNECTED
        _activeSessionId.value = sessionId
        val seed = IncomingRequestSeed(
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            initialOffer = decodeOffer(initialOfferBase64),
            initialOfferRevision = initialOfferRevision
        )
        handleIncomingRequest(seed, backgroundReceiveOnly)
    }

    private fun decodeOffer(offerBase64: String): String {
        if (offerBase64.isBlank()) return ""
        return runCatching {
            String(Base64.decode(offerBase64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to decode initial WT offer from FCM", error)
            ""
        }
    }

    private fun encodeOffer(offer: String): String {
        if (offer.isBlank()) return ""
        return Base64.encodeToString(offer.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun handleIncomingRequest(
        seed: IncomingRequestSeed,
        backgroundReceiveOnly: Boolean = false
    ) {
        if (seed.createdAt > 0L) {
            val ageMs = System.currentTimeMillis() - seed.createdAt
            if (ageMs > WalkieTalkieSession.SESSION_REQUEST_TTL_MS) {
                Log.w(TAG, "Ignoring stale WT session ${seed.sessionId} (${ageMs}ms old)")
                WalkieTalkieIncomingNotificationHelper.cancel(context)
                WalkieTalkieIncomingNotificationHelper.showMissed(context, seed.initiatorName, seed.sessionId)
                // Use TIMEOUT + signalRemote=true so the initiator receives STATUS_TIMEOUT
                // via RTDB and can clean up immediately, instead of staying on a "calling"
                // screen until its own 60 s timeout fires.  The previous approach of a
                // fire-and-forget cancelSession() failed silently when RTDB was cold (which
                // is the exact reason this stale-rejection path fires in the first place).
                terminateSession(TerminationReason.TIMEOUT, signalRemote = true)
                return
            }
        }
        // Allow CONNECTING (set synchronously by handleIncomingRequestFromFcm) and IDLE
        if (_state.value != State.IDLE && _state.value != State.CONNECTING) {
            Log.w(TAG, "Ignoring incoming WT request — already active in state ${_state.value}")
            scope.launch { try { repository.rejectSession(seed.sessionId) } catch (_: Exception) {} }
            return
        }

        _state.value = State.CONNECTING
        _sessionPhase.value = SessionPhase.CONNECTED
        _activeSessionId.value = seed.sessionId
        if (seed.initiatorId.isNotBlank()) {
            _connectedPeerId.value = seed.initiatorId
        }
        isCurrentSessionInitiator = false
        latestLocalOfferRevision = 0
        latestRemoteOfferRevision = 0
        latestLocalAnswerRevision = 0
        latestRemoteAnswerRevision = 0

        if (peerClient != null && _activeSessionId.value == seed.sessionId) {
            return
        }

        if (incomingSetupJob?.isActive == true) {
            return
        }

        acquireSetupWakeLock()
        // Launch heavy session setup on Dispatchers.Default to keep the UI thread
        // free for any incoming-call UI animations. Only the FGS start (which calls
        // startForegroundService) must happen on the main thread.
        incomingSetupJob = scope.launch(Dispatchers.Default) {
            try {
                // Start FGS — must be on main thread
                withContext(Dispatchers.Main) {
                    LiveAudioForegroundService.start(
                        context = context,
                        mode = LiveAudioForegroundService.MODE_WALKIE_TALKIE,
                        requiresMicrophoneAccess = !backgroundReceiveOnly
                    )
                }

                withContext(Dispatchers.Main) { startAudioSession(requiresMicrophoneAccess = !backgroundReceiveOnly) }
                repository.primeSession(seed.sessionId)
                ensureTurnReadyForPeerSetup(role = "responder")

                // History-based relay optimisation (same logic as initiator side).
                val initiatorId = seed.initiatorId.takeIf { it.isNotBlank() } ?: ""
                val responderRelayOnly = initiatorId.isNotBlank() &&
                    IceCandidateHistoryCache.shouldPreferRelay(context, currentUserId, initiatorId)

                // Initialize bidirectional WebRTC (as responder)
                // WebRTC PeerConnection creation involves native socket setup — run on Default.
                val client = WalkieTalkiePeerClient(
                    context,
                    seed.sessionId,
                    currentUserId,
                    isInitiator = false,
                    createLocalAudio = !backgroundReceiveOnly,
                    relayOnly = responderRelayOnly
                )
                client.initialize()
                peerClient = client

                observePeerEvents(client)

                // Ensure STATUS_RINGING is written to RTDB so the initiator's offer-refresh
                // loop stops and their callingTimeout is extended to the ringing window.
                // Retry with backoff — on cold start (app idle for a long time) RTDB is
                // offline when FCM wakes the process and the first write silently fails.
                // Without a confirmed ringing write the caller's 30 s pre-ringing timer fires
                // and terminates the session before ICE can connect (the "first call always
                // fails" bug on cold receiver).
                scope.launch {
                    // Retry markSessionRinging with RTDB-connection-aware backoff.
                    // On warm RTDB the first attempt at 0 ms succeeds instantly.
                    // On cold RTDB we wait for .info/connected before retrying,
                    // avoiding the old 0/3/8/15/25 s fixed-delay schedule that
                    // wasted 10-25 s of dead time.
                    retryWithConnectionAwareBackoff(
                        label = "Responder: markSessionRinging",
                        operation = {
                            if (_activeSessionId.value != seed.sessionId) return@retryWithConnectionAwareBackoff false
                            runCatching { repository.markSessionRinging(seed.sessionId) }.isSuccess
                        }
                    )
                }

                // Observe ICE and session changes before applying SDP so restarts are handled.
                observeIceCandidates(seed.sessionId, client)
                observeSessionAsResponder(
                    seed.sessionId,
                    client,
                    hasInitialOffer = seed.initialOffer.isNotBlank()
                )

                if (seed.initialOffer.isNotBlank()) {
                    // Offer arrived via FCM/RTDB — process it directly.
                    // Do NOT launch a concurrent getSession() fetch here: both paths
                    // would call processResponderSessionSnapshot before either updates
                    // latestRemoteOfferRevision, causing double setRemoteDescription →
                    // WebRTC error → terminateSession(ERROR) → caller sees STATUS_ENDED.
                    processResponderSessionSnapshot(
                        seed.sessionId,
                        WalkieTalkieSession(
                            sessionId = seed.sessionId,
                            initiatorId = seed.initiatorId,
                            initiatorName = seed.initiatorName,
                            responderId = currentUserId,
                            offer = seed.initialOffer,
                            offerRevision = seed.initialOfferRevision.coerceAtLeast(1),
                            status = WalkieTalkieSession.STATUS_REQUESTING,
                            createdAt = seed.createdAt
                        ),
                        client
                    )
                }
                // When seed has no initial offer, observeSessionAsResponder
                // delivers it via the RTDB listener (already started above).
                // The reaper handles the timeout if RTDB never delivers.

            } catch (e: Exception) {
                Log.e(TAG, "Responder: setup failed", e)
                terminateSession(TerminationReason.ERROR)
            } finally {
                incomingSetupJob = null
            }
        }
    }

    private fun observeSessionAsResponder(
        sessionId: String,
        client: WalkieTalkiePeerClient,
        hasInitialOffer: Boolean = false
    ) {
        sessionObserverJob?.cancel()
        // Whether we have ever received a non-null snapshot for this session.
        // Until the first real snapshot arrives we treat all nulls as RTDB reconnect
        // noise — on a cold start (30+ min idle) RTDB emits one or more null snapshots
        // while its WebSocket is reconnecting before the data resolves.  Only after a
        // real session value has been delivered does a subsequent null mean the node was
        // genuinely deleted.
        var receivedRealSnapshot = false
        sessionObserverJob = scope.launch {
            // Safety reaper: if RTDB never delivers a real session snapshot within the
            // window, the session node is gone and we should terminate cleanly.
            //
            // When the FCM payload already carries the offer (hasInitialOffer = true) we
            // skip the reaper entirely: we know the session exists, the SDP exchange can
            // complete using only the data from FCM + the RTDB write paths, and the reaper
            // was firing prematurely on cold starts where RTDB reconnect takes > 15 s.
            //
            // When we have NO offer we rely on RTDB to deliver it, so we keep the reaper
            // but give RTDB a generous 35 s window (cold-start TLS + auth + propagation
            // takes 5–20 s; 35 s is a safe upper bound).
            val reaperJob = if (!hasInitialOffer) launch {
                delay(35_000L)
                if (!receivedRealSnapshot && _activeSessionId.value == sessionId) {
                    Log.w(TAG, "Responder: session $sessionId never delivered by RTDB after 35s — terminating")
                    terminateSession(TerminationReason.INVALID_SESSION, signalRemote = false)
                }
            } else null
            repository.observeSession(sessionId).collect { session ->
                if (session == null) {
                    if (!receivedRealSnapshot) {
                        // RTDB cold-reconnect noise — we haven't seen real data yet.
                        // The reaper above handles the truly-missing case with a timeout.
                        Log.d(TAG, "Responder: session $sessionId null before first real snapshot — RTDB reconnecting")
                        return@collect
                    }
                    // A null AFTER a real snapshot means the node was deleted.
                    Log.w(TAG, "Responder: session $sessionId deleted after real snapshot received")
                    reaperJob?.cancel()
                    terminateSession(TerminationReason.INVALID_SESSION, signalRemote = false)
                    return@collect
                }
                receivedRealSnapshot = true
                reaperJob?.cancel() // real data arrived — reaper no longer needed

                // Check terminal statuses FIRST — do not attempt SDP processing
                // on a session that is already over.
                when (session.status) {
                    WalkieTalkieSession.STATUS_CANCELLED -> {
                        reaperJob?.cancel()
                        terminateSession(TerminationReason.REMOTE_CANCEL, signalRemote = false)
                        return@collect
                    }
                    WalkieTalkieSession.STATUS_TIMEOUT -> {
                        reaperJob?.cancel()
                        terminateSession(TerminationReason.TIMEOUT, signalRemote = false)
                        return@collect
                    }
                    WalkieTalkieSession.STATUS_REJECTED -> {
                        reaperJob?.cancel()
                        terminateSession(TerminationReason.REMOTE_REJECT, signalRemote = false)
                        return@collect
                    }
                    WalkieTalkieSession.STATUS_ENDED -> {
                        reaperJob?.cancel()
                        terminateSession(TerminationReason.REMOTE_END, signalRemote = false)
                        return@collect
                    }
                }

                processResponderSessionSnapshot(sessionId, session, client)
            }
        }
    }

    private suspend fun processResponderSessionSnapshot(
        sessionId: String,
        session: WalkieTalkieSession,
        client: WalkieTalkiePeerClient
    ) {
        val remoteOfferRevision = session.offerRevision.coerceAtLeast(
            if (!session.offer.isNullOrBlank()) 1 else 0
        )

        if (session.offer.isNullOrBlank() || remoteOfferRevision <= latestRemoteOfferRevision) {
            return
        }

        try {
            // Claim the revision eagerly so any concurrent call to processResponderSessionSnapshot
            // (e.g. from observeSessionAsResponder firing at the same time as a seed-offer call)
            // sees the revision as already consumed and returns early, preventing a double
            // setRemoteDescription() which would throw a WebRTC state error.
            latestRemoteOfferRevision = remoteOfferRevision

            client.setRemoteDescription(
                SessionDescription(SessionDescription.Type.OFFER, session.offer)
            )

            val nextAnswerRevision = (latestLocalAnswerRevision + 1).coerceAtLeast(1)
            val answer = client.createAnswer()
            latestLocalAnswerRevision = nextAnswerRevision

            // Retry setAnswer with RTDB-connection-aware backoff.
            // On warm RTDB the first attempt at 0 ms succeeds instantly.
            // On cold RTDB we wait for .info/connected before retrying.
            val answerWritten = retryWithConnectionAwareBackoff(
                label = "Responder: setAnswer",
                operation = {
                    if (_activeSessionId.value != sessionId) return@retryWithConnectionAwareBackoff false
                    runCatching {
                        repository.setAnswer(
                            sessionId,
                            answer.description,
                            nextAnswerRevision,
                            answeredOfferRevision = remoteOfferRevision,
                            iceRestart = session.iceRestart
                        )
                    }.isSuccess
                }
            )
            if (!answerWritten) {
                Log.e(TAG, "Responder: setAnswer failed after all retries for revision=$remoteOfferRevision")
                terminateSession(TerminationReason.ERROR)
                return
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Responder: offer handling cancelled for revision $remoteOfferRevision")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Responder: failed to handle offer revision $remoteOfferRevision", e)
            terminateSession(TerminationReason.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PUSH-TO-TALK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Press PTT — claim floor and unmute mic.
     * Audio starts flowing instantly; RTDB claim is for UI coordination.
     */
    fun pressPtt() {
        val sessionId = _activeSessionId.value ?: return
        val currentState = _state.value
        if (currentState != State.CONNECTED_IDLE) {
            if (currentState == State.RECEIVING) {
                // Floor is held by peer — deny
                _floorDenied.tryEmit(Unit)
                vibrateError()
            }
            return
        }

        // Optimistic: unmute mic IMMEDIATELY for zero-latency audio
        isLocalPttActive.set(true)
        peerClient?.setMicEnabled(true)
        _state.value = State.TRANSMITTING
        vibratePress()

        // Then claim floor in RTDB (for remote UI)
        scope.launch {
            runCatching { repository.emitPttEffect(sessionId, WalkieTalkiePttEffectEvent.PHASE_PRESS) }
                .onFailure { error -> Log.w(TAG, "Failed to emit remote PTT press effect", error) }
            val claimed = repository.claimFloor(sessionId)
            if (!claimed) {
                // Someone else got the floor first — revert
                peerClient?.setMicEnabled(false)
                isLocalPttActive.set(false)
                _state.value = State.RECEIVING
                _floorDenied.tryEmit(Unit)
                vibrateError()
            }
        }
    }

    /**
     * Release PTT — release floor and mute mic.
     */
    fun releasePtt() {
        val sessionId = _activeSessionId.value ?: return
        if (_state.value != State.TRANSMITTING) {
            isLocalPttActive.set(false)
            return
        }

        // Mute mic IMMEDIATELY
        isLocalPttActive.set(false)
        peerClient?.setMicEnabled(false)
        _state.value = State.CONNECTED_IDLE
        vibrateRelease()

        // Release floor in RTDB
        scope.launch {
            runCatching { repository.emitPttEffect(sessionId, WalkieTalkiePttEffectEvent.PHASE_RELEASE) }
                .onFailure { error -> Log.w(TAG, "Failed to emit remote PTT release effect", error) }
            repository.releaseFloor(sessionId)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DISCONNECT
    // ═══════════════════════════════════════════════════════════════

    /** Disconnect the walkie-talkie session. */
    fun disconnect() {
        val reason = when {
            !hasConnectedOnce && isCurrentSessionInitiator -> TerminationReason.LOCAL_CANCEL
            !hasConnectedOnce && !isCurrentSessionInitiator && _activeSessionId.value != null -> TerminationReason.LOCAL_REJECT
            else -> TerminationReason.LOCAL_END
        }
        terminateSession(reason)
    }

    fun shutdown() {
        stopWatchingForRequests()
        // Detach the Firebase listener on full shutdown (logout / process exit only)
        repository.detachIncomingRequestListener()
        terminateSession(TerminationReason.SHUTDOWN)
        scope.launch { repository.cleanupStaleSessions() }
    }

    /**
     * Called on network reconnect to clean up any stale incoming WT sessions
     * that arrived while offline. Dismisses stale notifications and shows
     * "missed" indicators where appropriate.
     */
    fun cleanupStaleSessionsOnReconnect() {
        scope.launch {
            // If we reconnected mid-setup (CONNECTING, not yet established), validate that the
            // server session is still joinable before continuing. A cancelled / timed-out session
            // would otherwise leave the manager in a permanent CONNECTING state.
            val currentSid = _activeSessionId.value
            if (currentSid != null && _state.value == State.CONNECTING && !hasConnectedOnce) {
                val serverSession = runCatching {
                    withTimeoutOrNull(5_000L) { repository.getSession(currentSid) }
                }.getOrNull()
                val responderShouldAbort = !isCurrentSessionInitiator &&
                    (serverSession == null || !serverSession.isJoinable())
                if (responderShouldAbort) {
                    val initiatorName = serverSession?.initiatorName
                        ?.takeIf { it.isNotBlank() }
                        ?: _connectedPeerId.value
                            ?.let { peerId -> UserProfileCache.get(context, peerId)?.username?.takeIf { it.isNotBlank() } }
                        ?: "Someone"
                    Log.w(
                        TAG,
                        "WT reconnect: session $currentSid is stale or missing after reconnect; terminating and showing missed notification"
                    )
                    terminateSession(TerminationReason.INVALID_SESSION, signalRemote = false)
                    WalkieTalkieIncomingNotificationHelper.cancel(context)
                    WalkieTalkieIncomingNotificationHelper.showMissed(context, initiatorName, currentSid)
                    return@launch
                }

                if (serverSession != null && !serverSession.isJoinable()) {
                    Log.w(TAG, "WT reconnect: initiator session $currentSid no longer joinable (status=${serverSession.status}) — terminating")
                    terminateSession(TerminationReason.INVALID_SESSION, signalRemote = false)
                    return@launch
                }
            }

            // Only run the full stale-session sweep when idle — don't disrupt an active session
            if (_state.value != State.IDLE) {
                startWatchingForRequests()
                return@launch
            }

            try {
                val missed = repository.cleanupStaleIncomingSessions()
                for ((sessionId, initiatorName) in missed) {
                    val name = initiatorName.takeIf { it.isNotBlank() } ?: "Someone"
                    WalkieTalkieIncomingNotificationHelper.showMissed(context, name, sessionId)
                }

                if (missed.isNotEmpty()) {
                    // Only cancel the incoming notification if stale sessions were found.
                    // Avoids racing with a valid notification shown by a fresh FCM.
                    WalkieTalkieIncomingNotificationHelper.cancel(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect WT cleanup failed", e)
            }

            // Always restart listening after reconnect so the callee's RTDB
            // observer picks up any new session that arrived while offline.
            startWatchingForRequests()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SHARED — ICE, peer events, floor observation, cleanup
    // ═══════════════════════════════════════════════════════════════

    private fun observeIceCandidates(sessionId: String, client: WalkieTalkiePeerClient) {
        iceObserverJob?.cancel()
        iceObserverJob = scope.launch {
            // Forward local candidates to RTDB. Each write is fired in its own coroutine
            // so candidates are forwarded concurrently — no blocking on write completion
            // before the next candidate can be forwarded. WebRTC emits 5–8 candidates in
            // quick succession; sequential awaited writes would add 1–3 s of latency.
            launch {
                client.onIceCandidate.collect { candidate ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            repository.addIceCandidate(
                                sessionId,
                                WalkieTalkieIceCandidate(
                                    candidate = candidate.sdp,
                                    sdpMLineIndex = candidate.sdpMLineIndex,
                                    sdpMid = candidate.sdpMid ?: "",
                                    fromUserId = currentUserId,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send ICE candidate", e)
                        }
                    }
                }
            }
            // Receive remote candidates.
            // No timestamp-based stale filter here — RTDB re-delivers ALL child_added
            // events on every reconnect, so old candidates would be incorrectly dropped
            // after 15 s by a time-based filter.  Instead we rely on processedCandidates
            // deduplication (same pattern as CallManager) to silently skip re-deliveries.
            launch {
                repository.observeIceCandidates(sessionId).collect { candidate ->
                    val key = "${candidate.sdpMid}_${candidate.sdpMLineIndex}_${candidate.candidate.hashCode()}"
                    if (key in processedCandidates) return@collect
                    processedCandidates.add(key)
                    client.addIceCandidate(
                        IceCandidate(
                            candidate.sdpMid,
                            candidate.sdpMLineIndex,
                            candidate.candidate
                        )
                    )
                }
            }
        }
    }

    private fun observePeerEvents(client: WalkieTalkiePeerClient) {
        peerEventJob?.cancel()
        peerEventJob = scope.launch {
            launch {
                client.onConnectionConnected.collect {
                    connectionRecoveryJob?.cancel()
                    connectionRecoveryJob = null
                    callingTimeoutJob?.cancel()
                    callingTimeoutJob = null
                    connectionRetryCount = 0
                    val isNotAlready = _state.value != State.CONNECTED_IDLE &&
                            _state.value != State.TRANSMITTING &&
                            _state.value != State.RECEIVING
                    if (isNotAlready) {
                        _state.value = State.CONNECTED_IDLE
                        _sessionPhase.value = SessionPhase.CONNECTED
                        hasConnectedOnce = true
                        releaseSetupWakeLock()
                        // Record relay/direct outcome so the next call to this peer
                        // can skip wasted host/srflx candidate checks if relay is always needed.
                        val peerId = _connectedPeerId.value
                        if (!peerId.isNullOrBlank()) {
                            IceCandidateHistoryCache.recordOutcome(
                                context = context,
                                localUserId = currentUserId,
                                remoteUserId = peerId,
                                usedRelay = client.wasRelayUsed()
                            )
                        }
                        // Start floor observation now that we're connected
                        val sid = _activeSessionId.value
                        if (sid != null) {
                            observeFloor(sid)
                            observeRemotePttEffects(sid)
                        }
                    }
                    vibrateConnected()
                }
            }
            launch {
                client.onConnectionFailed.collect {
                    Log.w(TAG, "WebRTC connection failed — attempting recovery")
                    scheduleConnectionRecovery("failed")
                }
            }
            launch {
                client.onConnectionDisconnected.collect {
                    Log.w(TAG, "WebRTC disconnected — waiting for recovery")
                    scheduleConnectionRecovery("disconnected")
                }
            }
        }
    }

    private fun scheduleConnectionRecovery(reason: String) {
        if (connectionRecoveryJob?.isActive == true) {
            return
        }

        if (connectionRetryCount >= MAX_RECONNECT_RETRIES) {
            Log.w(TAG, "WT exceeded max reconnect retries ($MAX_RECONNECT_RETRIES) after $reason — disconnecting")
            terminateSession(TerminationReason.ERROR)
            return
        }

        connectionRetryCount++
        connectionRecoveryJob = scope.launch {
            if (isCurrentSessionInitiator) {
                triggerIceRestartNegotiation(reason)
            }
            delay(CONNECTION_RECOVERY_TIMEOUT_MS)
            val connectedStates = setOf(State.CONNECTED_IDLE, State.TRANSMITTING, State.RECEIVING)
            if (_state.value !in connectedStates) {
                Log.w(TAG, "WT connection did not recover after $reason; disconnecting")
                terminateSession(TerminationReason.ERROR)
            } else {
            }
            connectionRecoveryJob = null
        }
    }

    private fun startPendingOfferRefreshLoop(sessionId: String) {
        pendingOfferRefreshJob?.cancel()
        pendingOfferRefreshJob = scope.launch {
            while (_activeSessionId.value == sessionId && !hasConnectedOnce) {
                delay(OFFER_REFRESH_INTERVAL_MS)

                if (_activeSessionId.value != sessionId || hasConnectedOnce) {
                    break
                }

                // Once callee has acknowledged (RINGING or beyond), stop refresh loop.
                // Sending a new ICE-restart offer while the callee's setRemoteDescription +
                // createAnswer round-trip is in progress causes a WebRTC signaling error on
                // the callee that terminates the connection.  This is the primary guard;
                // the loop is also cancelled directly in observeSessionAsInitiator when
                // STATUS_RINGING is first observed.
                if (_sessionPhase.value != SessionPhase.REQUESTING) {
                    break
                }

                if (_state.value != State.REQUESTING) {
                    continue
                }

                if (negotiationTaskJob?.isActive == true) {
                    continue
                }

                triggerIceRestartNegotiation("pre-answer refresh")
            }
        }
    }

    private fun triggerIceRestartNegotiation(reason: String) {
        val sessionId = _activeSessionId.value ?: return
        val client = peerClient ?: return
        if (!isCurrentSessionInitiator) {
            return
        }
        if (negotiationTaskJob?.isActive == true) {
            return
        }

        negotiationTaskJob = scope.launch {
            try {
                Log.w(TAG, "Starting WT ICE restart negotiation for session=$sessionId reason=$reason")
                client.restartIce()
                val nextOfferRevision = (latestLocalOfferRevision + 1).coerceAtLeast(1)
                val restartOffer = client.createOffer(restartIce = true)
                latestLocalOfferRevision = nextOfferRevision
                val sessionStatus = when (_sessionPhase.value) {
                    SessionPhase.REQUESTING -> WalkieTalkieSession.STATUS_REQUESTING
                    SessionPhase.RINGING -> WalkieTalkieSession.STATUS_RINGING
                    else -> WalkieTalkieSession.STATUS_ACTIVE
                }
                repository.setOffer(
                    sessionId = sessionId,
                    sdp = restartOffer.description,
                    offerRevision = nextOfferRevision,
                    iceRestart = true,
                    status = sessionStatus
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed WT ICE restart negotiation for session=$sessionId", e)
            } finally {
                negotiationTaskJob = null
            }
        }
    }

    private fun observeFloor(sessionId: String) {
        floorObserverJob?.cancel()
        floorObserverJob = scope.launch {
            repository.observeFloor(sessionId).collect { floor ->
                val connectedStates = setOf(
                    State.CONNECTED_IDLE, State.TRANSMITTING, State.RECEIVING
                )
                if (_state.value !in connectedStates) return@collect

                when {
                    floor.isHeldBy(currentUserId) -> {
                        // Keep TRANSMITTING only while the local touch is still active.
                        if (isLocalPttActive.get()) {
                            if (_state.value != State.TRANSMITTING) {
                                _state.value = State.TRANSMITTING
                            }
                        } else if (_state.value != State.CONNECTED_IDLE) {
                            _state.value = State.CONNECTED_IDLE
                        }
                        _remoteSpeaking.value = false
                        wasRemoteSpeaking = false
                    }
                    floor.isHeld() -> {
                        // Peer holds the floor — move to RECEIVING
                        _state.value = State.RECEIVING
                        _remoteSpeaking.value = true
                        wasRemoteSpeaking = true
                        // Make sure our mic is off
                        peerClient?.setMicEnabled(false)
                    }
                    else -> {
                        // Floor is free
                        if (isLocalPttActive.get()) {
                            if (_state.value != State.TRANSMITTING) {
                                _state.value = State.TRANSMITTING
                            }
                        } else if (_state.value != State.TRANSMITTING) {
                            _state.value = State.CONNECTED_IDLE
                        }
                        _remoteSpeaking.value = false
                        wasRemoteSpeaking = false
                    }
                }
            }
        }
    }

    private fun observeRemotePttEffects(sessionId: String) {
        pttEffectObserverJob?.cancel()
        pttEffectObserverJob = scope.launch {
            repository.observePttEffects(sessionId).collect { event ->
                if (event.senderId == currentUserId) return@collect
                if (event.sequence <= lastHandledRemotePttEffectSequence) return@collect

                lastHandledRemotePttEffectSequence = event.sequence
                WalkieTalkieEffectPlayer.playRemoteFloorToggle(context)
            }
        }
    }

    private fun cleanupInternal() {
        connectionRecoveryJob?.cancel(); connectionRecoveryJob = null
        negotiationTaskJob?.cancel(); negotiationTaskJob = null
        pendingOfferRefreshJob?.cancel(); pendingOfferRefreshJob = null
        callingTimeoutJob?.cancel(); callingTimeoutJob = null
        incomingSetupJob?.cancel(); incomingSetupJob = null
        sessionObserverJob?.cancel(); sessionObserverJob = null
        iceObserverJob?.cancel(); iceObserverJob = null
        peerEventJob?.cancel(); peerEventJob = null
        floorObserverJob?.cancel(); floorObserverJob = null
        pttEffectObserverJob?.cancel(); pttEffectObserverJob = null

        peerClient?.dispose()
        peerClient = null

        releaseSetupWakeLock()
        stopAudioSession()
        runCatching { vibrator.cancel() }
        LiveAudioForegroundService.stop(context)

        _activeSessionId.value = null
        _connectedPeerId.value = null
        _remoteSpeaking.value = false
        wasRemoteSpeaking = false
        _state.value = State.IDLE
    _sessionPhase.value = SessionPhase.IDLE
        isCurrentSessionInitiator = false
    hasConnectedOnce = false
        connectionRetryCount = 0
    lastHandledRemotePttEffectSequence = 0L
        isLocalPttActive.set(false)
        processedCandidates.clear()
        latestLocalOfferRevision = 0
        latestRemoteOfferRevision = 0
        latestLocalAnswerRevision = 0
        latestRemoteAnswerRevision = 0
        WalkieTalkieEffectPlayer.cleanup()
        terminationInProgress.set(false)


        // Restart RTDB event collection so the callee can receive the next
        // incoming call immediately — even if the app is already in the foreground
        // (AppVisibilityTracker only fires startWatchingForRequests on resume
        // transitions, not after in-session state resets).
        startWatchingForRequests()
    }

    // ── Haptic feedback ─────────────────────────────────────────────

    private fun vibratePress() = vibrate(30L)
    private fun vibrateRelease() = vibrate(15L)
    private fun vibrateConnected() = vibrate(50L)

    private fun vibrateError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 40, 60, 40), -1)
        }
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun terminateSession(
        reason: TerminationReason,
        signalRemote: Boolean = true
    ) {
        val alreadyIdle = _state.value == State.IDLE && _activeSessionId.value == null && peerClient == null
        if (alreadyIdle && !terminationInProgress.get()) {
            cleanupInternal()
            return
        }
        if (!terminationInProgress.compareAndSet(false, true)) {
            return
        }

        val sessionId = _activeSessionId.value
        _state.value = State.DISCONNECTING
        _sessionPhase.value = when (reason) {
            TerminationReason.LOCAL_CANCEL,
            TerminationReason.REMOTE_CANCEL -> SessionPhase.CANCELLED
            TerminationReason.TIMEOUT -> SessionPhase.TIMED_OUT
            TerminationReason.LOCAL_END,
            TerminationReason.REMOTE_END,
            TerminationReason.LOCAL_REJECT,
            TerminationReason.REMOTE_REJECT,
            TerminationReason.ERROR,
            TerminationReason.INVALID_SESSION,
            TerminationReason.SHUTDOWN -> SessionPhase.ENDED
        }

        if (reason == TerminationReason.TIMEOUT) {
            _callTimedOut.tryEmit(Unit)
        }

        connectionRecoveryJob?.cancel(); connectionRecoveryJob = null
    pendingOfferRefreshJob?.cancel(); pendingOfferRefreshJob = null
        incomingSetupJob?.cancel(); incomingSetupJob = null
        sessionObserverJob?.cancel(); sessionObserverJob = null
        iceObserverJob?.cancel(); iceObserverJob = null
        peerEventJob?.cancel(); peerEventJob = null
        floorObserverJob?.cancel(); floorObserverJob = null

        // Tear down local UI/audio/WebRTC immediately. Remote termination signaling can
        // finish in the background; keeping the screen stuck in DISCONNECTING until the
        // network round-trip completes makes hang-up feel broken on slow links.
        cleanupInternal()

        if (!signalRemote || sessionId == null) {
            return
        }

        scope.launch {
            try {
                when (reason) {
                    TerminationReason.LOCAL_CANCEL -> repository.cancelSession(sessionId)
                    TerminationReason.LOCAL_REJECT -> repository.rejectSession(sessionId)
                    TerminationReason.LOCAL_END -> repository.endSession(sessionId)
                    TerminationReason.TIMEOUT -> repository.timeoutSession(sessionId)
                    TerminationReason.ERROR -> repository.endSession(
                        sessionId,
                        endReason = WalkieTalkieSession.END_REASON_ERROR
                    )
                    TerminationReason.SHUTDOWN -> repository.endSession(
                        sessionId,
                        endReason = WalkieTalkieSession.END_REASON_COMPLETED
                    )
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "WT termination signal failed for reason=$reason", e)
            }
        }
    }

    private fun acquireSetupWakeLock() {
        if (setupWakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        setupWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GlyphV3:WTSetup"
        ).apply {
            setReferenceCounted(false)
            acquire(CALLING_TIMEOUT_MS)
        }
    }

    private fun releaseSetupWakeLock() {
        setupWakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        setupWakeLock = null
    }

    private fun startAudioSession(requiresMicrophoneAccess: Boolean = true) {
        if (audioSessionStarted) return
        savedAudioMode = audioManager.mode
        savedMicrophoneMute = audioManager.isMicrophoneMute
        savedCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            @Suppress("DEPRECATION")
            run {
                savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            null
        }
        // Effect player is preloaded asynchronously in init{} on Dispatchers.Default.
        // Calling preload here would synchronously construct SoundPool on the calling
        // thread, causing a frame drop during session startup.
        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (requiresMicrophoneAccess) {
            audioManager.isMicrophoneMute = false
        }
        routeAudioToSpeakerphone()
        audioSessionStarted = true
    }

    private fun stopAudioSession() {
        if (audioSessionStarted) {
            runCatching {
                audioManager.isMicrophoneMute = savedMicrophoneMute
                restoreAudioRouting()
                audioManager.mode = savedAudioMode
                if (audioManager.mode != AudioManager.MODE_NORMAL) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to restore WT audio session", error)
            }
        }
        abandonAudioFocus()
        audioSessionStarted = false
        savedAudioMode = AudioManager.MODE_NORMAL
        savedSpeakerphoneOn = false
        savedMicrophoneMute = false
        savedCommunicationDevice = null
    }

    private fun routeAudioToSpeakerphone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speakerDevice != null) {
                audioManager.setCommunicationDevice(speakerDevice)
                return
            }
        }

        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun restoreAudioRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = savedCommunicationDevice
            if (device != null) {
                audioManager.setCommunicationDevice(device)
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }
}
