package com.glyph.glyph_v3.data.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.glyph.glyph_v3.data.models.LiveAudioIceCandidate
import com.glyph.glyph_v3.data.models.LiveAudioSession
import com.glyph.glyph_v3.data.repo.LiveAudioRepository
import com.glyph.glyph_v3.data.webrtc.LiveAudioPeerClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Central manager that orchestrates the entire live audio sharing feature.
 *
 * Responsibilities:
 *  - Manages broadcaster and listener WebRTC peer clients
 *  - Coordinates Firebase signaling with WebRTC negotiation
 *  - Controls foreground service lifecycle (start/stop on demand)
 *  - Handles cleanup on disconnect, errors, and lifecycle changes
 *
 * This is a process-scoped singleton. Use [getInstance] to access.
 */
class LiveAudioSharingManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "LiveAudioManager"

        @Volatile
        private var INSTANCE: LiveAudioSharingManager? = null

        fun getInstance(context: Context): LiveAudioSharingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveAudioSharingManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    /**
     * Observable state of the manager.
     */
    enum class State {
        IDLE,
        /** Listener: waiting for broadcaster to accept */
        REQUESTING,
        /** Broadcaster: setting up mic and WebRTC */
        PREPARING,
        /** Both: WebRTC connected, audio streaming */
        STREAMING,
        /** Stopping / cleaning up */
        STOPPING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = LiveAudioRepository()
    private val currentUserId: String get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    // ── Observable state ─────────────────────────────────────────────

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _connectedPeerId = MutableStateFlow<String?>(null)
    val connectedPeerId: StateFlow<String?> = _connectedPeerId.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // ── Internal ────────────────────────────────────────────────────

    private var peerClient: LiveAudioPeerClient? = null
    private var sessionObserverJob: Job? = null
    private var incomingRequestJob: Job? = null
    private var iceObserverJob: Job? = null
    private var peerEventJob: Job? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL

    private fun resetToIdle() {
        _state.value = State.IDLE
        _connectedPeerId.value = null
        _activeSessionId.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    //  LISTENER SIDE — "Listen" button tapped
    // ═══════════════════════════════════════════════════════════════

    /**
     * Request to listen to another user's live audio.
     * This creates a signaling session and waits for the broadcaster to accept.
     */
    fun requestListen(broadcasterId: String) {
        if (_state.value != State.IDLE) {
            Log.w(TAG, "requestListen ignored — state=${_state.value}")
            return
        }
        // Stop watching for broadcasters — we're the listener now
        stopWatchingForRequests()
        _state.value = State.REQUESTING
        _connectedPeerId.value = broadcasterId

        scope.launch {
            try {
                // Verify permissions
                val allowed = repository.canListenTo(broadcasterId)
                if (!allowed) {
                    Log.w(TAG, "Not allowed to listen to $broadcasterId")
                    resetToIdle()
                    return@launch
                }

                // Create signaling session
                val sessionId = repository.createSession(broadcasterId)
                _activeSessionId.value = sessionId

                // Initialize WebRTC as listener (no mic)
                val client = LiveAudioPeerClient(
                    context, sessionId, currentUserId, isBroadcaster = false
                )
                client.initialize()
                peerClient = client

                // Listen for peer events
                observePeerEvents(client)

                // Observe session for offer from broadcaster
                observeSessionAsListener(sessionId, client)

            } catch (e: Exception) {
                Log.e(TAG, "requestListen failed", e)
                resetToIdle()
            }
        }
    }

    private fun observeSessionAsListener(sessionId: String, client: LiveAudioPeerClient) {
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            var answered = false
            repository.observeSession(sessionId).collect { session ->
                if (session == null) return@collect

                when (session.status) {
                    LiveAudioSession.STATUS_ACTIVE -> {
                        val offerSdp = session.offer ?: return@collect
                        if (!answered) {
                            answered = true
                            try {
                                // Set remote description (the offer)
                                client.setRemoteDescription(
                                    SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                                )
                                // Create and send answer
                                val answer = client.createAnswer()
                                repository.setAnswer(sessionId, answer.description)
                                _state.value = State.STREAMING
                                _isListening.value = true

                                // Route audio to speaker so the listener hears through
                                // the loudspeaker (not the earpiece). WebRTC defaults to
                                // MODE_IN_COMMUNICATION which routes to earpiece.
                                savedAudioMode = audioManager.mode
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                audioManager.isSpeakerphoneOn = true

                                // Start observing ICE candidates
                                observeIceCandidates(sessionId, client)

                            } catch (e: Exception) {
                                answered = false
                                Log.e(TAG, "Listener: negotiation failed", e)
                                stopListening()
                            }
                        }
                    }
                    LiveAudioSession.STATUS_ENDED -> {
                        cleanupInternal()
                    }
                    LiveAudioSession.STATUS_REJECTED -> {
                        cleanupInternal()
                    }
                }
            }
        }
    }

    /** Stop listening to the current broadcaster. */
    fun stopListening() {
        if (_state.value == State.IDLE) return
        val sessionId = _activeSessionId.value  // capture before any async mutation
        _state.value = State.STOPPING
        // Cancel observer synchronously so it can't re-trigger cleanupInternal / stopListening
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        scope.launch {
            try {
                sessionId?.let { repository.endSession(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error ending listener session", e)
            }
            cleanupInternal()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BROADCASTER SIDE — incoming request received
    // ═══════════════════════════════════════════════════════════════

    /**
     * Called from FCM when a LIVE_AUDIO_REQUEST arrives and the app was closed/backgrounded.
     * On Android 14+ we cannot start a microphone foreground service from background, so we
     * show a heads-up notification instead. When the user taps it the app comes to the
     * foreground and the RTDB watcher picks up the session naturally.
     * If the app is already in the foreground, dispatch directly.
     */
    fun handleIncomingRequestFromFcm(sessionId: String, listenerName: String) {
        // Samsung and other OEMs keep the process alive after the user swipes away the app.
        // If a previous session left state stuck at non-IDLE (e.g. because the mic was
        // silently unavailable and no exception was thrown), forcibly reset before serving
        // the new inbound request. FCM is authoritative — abandon stale state.
        if (_state.value != State.IDLE) {
            Log.w(TAG, "handleIncomingRequestFromFcm: stale state=${_state.value}, force-resetting")
            val staleSessionId = _activeSessionId.value
            cleanupInternal()
            if (staleSessionId != null) {
                scope.launch { try { repository.endSession(staleSessionId) } catch (_: Exception) {} }
            }
        }
        scope.launch {
            try {
                val session = repository.getSession(sessionId) ?: run {
                    Log.w(TAG, "handleIncomingRequestFromFcm: session not found: $sessionId")
                    // The FGS was pre-started in MyFirebaseMessagingService; stop it since
                    // there is no valid session to stream.
                    LiveAudioForegroundService.stop(context)
                    return@launch
                }
                if (session.status != LiveAudioSession.STATUS_REQUESTING) {
                    LiveAudioForegroundService.stop(context)
                    return@launch
                }
                // FGS was already started synchronously in MyFirebaseMessagingService using
                // the FCM high-priority exemption window. Just proceed with WebRTC setup.
                handleIncomingRequest(session)
            } catch (e: Exception) {
                Log.e(TAG, "handleIncomingRequestFromFcm failed", e)
                LiveAudioForegroundService.stop(context)
            }
        }
    }

    /** Returns true if the app process has at least one Activity in the resumed/started state. */
    private fun isAppInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        return appProcesses.any { it.processName == packageName &&
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    /**
     * Start watching for incoming listen requests.
     * Call this when shareMic is enabled.
     */
    fun startWatchingForRequests() {
        // Attach the Firebase listener permanently — idempotent, no-op if already attached.
        // Never detach/re-attach during normal operation to avoid the Firebase RTDB
        // "listen() called twice for same QuerySpec" crash.
        repository.attachIncomingRequestListener()

        if (_state.value != State.IDLE) {
            return
        }
        if (incomingRequestJob?.isActive == true) return
        incomingRequestJob = scope.launch {
            repository.incomingRequestEvents().collect { session ->
                handleIncomingRequest(session)
            }
        }
    }

    /** Stop watching for incoming requests. */
    fun stopWatchingForRequests() {
        // Cancel event collection but do NOT detach the Firebase listener.
        // The listener must stay registered to avoid the "listen() called twice" crash.
        incomingRequestJob?.cancel()
        incomingRequestJob = null
    }

    private fun handleIncomingRequest(session: LiveAudioSession) {
        // Reject sessions older than 60 seconds to avoid processing stale requests
        val ageMs = System.currentTimeMillis() - session.createdAt
        if (ageMs > 60_000L) {
            Log.w(TAG, "Ignoring stale incoming session ${session.sessionId} (${ageMs}ms old)")
            scope.launch { try { repository.endSession(session.sessionId) } catch (_: Exception) {} }
            return
        }
        if (_state.value != State.IDLE) {
            Log.w(TAG, "Ignoring incoming request — already streaming")
            // Reject the request since we only support one listener at a time
            scope.launch {
                try {
                    repository.endSession(session.sessionId)
                } catch (_: Exception) {}
            }
            return
        }

        _state.value = State.PREPARING
        _activeSessionId.value = session.sessionId
        _connectedPeerId.value = session.listenerId

        // Dismiss any incoming-request notification shown while backgrounded
        LiveAudioIncomingNotificationHelper.cancel(context)

        scope.launch {
            try {
                // Start foreground service BEFORE activating mic
                LiveAudioForegroundService.start(context)

                // Set audio mode so WebRTC uses the VOICE_COMMUNICATION AudioRecord source
                // (hardware echo cancellation). Without this, some devices route mic incorrectly.
                savedAudioMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                // Initialize WebRTC as broadcaster (with mic)
                val client = LiveAudioPeerClient(
                    context, session.sessionId, currentUserId, isBroadcaster = true
                )
                client.initialize()
                peerClient = client

                // Listen for peer events
                observePeerEvents(client)

                // Create offer and send via Firebase
                val offer = client.createOffer()
                repository.setOffer(session.sessionId, offer.description)

                // Update streaming state in Firestore
                repository.updateStreamingState(
                    isStreaming = true,
                    listenerIds = listOf(session.listenerId)
                )
                _isBroadcasting.value = true

                // Start observing ICE candidates
                observeIceCandidates(session.sessionId, client)

                // Watch for answer from listener
                observeSessionAsBroadcaster(session.sessionId, client)

            } catch (e: Exception) {
                Log.e(TAG, "Broadcaster: setup failed", e)
                stopBroadcasting()
            }
        }
    }

    private fun observeSessionAsBroadcaster(sessionId: String, client: LiveAudioPeerClient) {
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            repository.observeSession(sessionId).collect { session ->
                if (session == null) return@collect

                when {
                    session.answer != null && _state.value == State.PREPARING -> {
                        try {
                            client.setRemoteDescription(
                                SessionDescription(SessionDescription.Type.ANSWER, session.answer)
                            )
                            _state.value = State.STREAMING
                        } catch (e: Exception) {
                            Log.e(TAG, "Broadcaster: failed to set answer", e)
                            stopBroadcasting()
                        }
                    }
                    session.status == LiveAudioSession.STATUS_ENDED -> {
                        stopBroadcasting()
                    }
                }
            }
        }
    }

    /** Stop broadcasting and release all resources. */
    fun stopBroadcasting() {
        if (_state.value == State.IDLE) return
        val sessionId = _activeSessionId.value  // capture before any async mutation
        _state.value = State.STOPPING
        // Cancel observer synchronously so it can't re-trigger stopBroadcasting via STATUS_ENDED
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        scope.launch {
            try {
                sessionId?.let { repository.endSession(it) }
                repository.updateStreamingState(isStreaming = false, listenerIds = emptyList())
            } catch (e: Exception) {
                Log.w(TAG, "Error during stop broadcasting", e)
            }
            LiveAudioForegroundService.stop(context)
            cleanupInternal()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SHARED — ICE candidates, peer events, cleanup
    // ═══════════════════════════════════════════════════════════════

    private fun observeIceCandidates(sessionId: String, client: LiveAudioPeerClient) {
        iceObserverJob?.cancel()
        iceObserverJob = scope.launch {
            // Send local ICE candidates to Firebase
            launch {
                client.onIceCandidate.collect { candidate ->
                    try {
                        repository.addIceCandidate(
                            sessionId,
                            LiveAudioIceCandidate(
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
            // Receive remote ICE candidates from Firebase
            launch {
                repository.observeIceCandidates(sessionId).collect { candidate ->
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

    private fun observePeerEvents(client: LiveAudioPeerClient) {
        peerEventJob?.cancel()
        peerEventJob = scope.launch {
            launch {
                client.onConnectionFailed.collect {
                    Log.w(TAG, "WebRTC connection failed")
                    if (_isBroadcasting.value) stopBroadcasting() else stopListening()
                }
            }
            launch {
                client.onConnectionDisconnected.collect {
                    Log.w(TAG, "WebRTC connection disconnected — waiting for recovery")
                    // ICE will attempt self-recovery via GATHER_CONTINUALLY
                }
            }
        }
    }

    private fun cleanupInternal() {
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        iceObserverJob?.cancel()
        iceObserverJob = null
        peerEventJob?.cancel()
        peerEventJob = null

        peerClient?.dispose()
        peerClient = null

        // Restore audio routing
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = savedAudioMode

        _activeSessionId.value = null
        _connectedPeerId.value = null
        _isBroadcasting.value = false
        _isListening.value = false
        _state.value = State.IDLE

    }

    /** Clean up everything — called on app destroy or shareMic disabled. */
    fun shutdown() {
        stopWatchingForRequests()
        repository.detachIncomingRequestListener()  // Only detach on full shutdown
        when {
            _isBroadcasting.value -> stopBroadcasting()
            _isListening.value -> stopListening()
            else -> cleanupInternal()
        }
        scope.launch {
            repository.cleanupStaleSessions()
        }
    }
}
