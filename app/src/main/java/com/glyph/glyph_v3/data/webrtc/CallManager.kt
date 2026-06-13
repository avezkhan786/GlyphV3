package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.data.models.CallData
import com.glyph.glyph_v3.data.models.CallMode
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.models.IceCandidateData
import com.glyph.glyph_v3.data.models.OutgoingCallUiStatus
import com.glyph.glyph_v3.data.models.VideoUpgradeRequestState
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.CallLogRepository
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.service.CallForegroundService
import com.glyph.glyph_v3.data.service.GlyphTelecomManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton call manager. Orchestrates the entire call lifecycle:
 * - Creating outgoing calls
 * - Accepting incoming calls
 * - WebRTC setup + ICE exchange via Firestore
 * - Call timer
 * - Ending / declining
 */
object CallManager {

    private const val TAG = "CallManager"
    private const val CALL_TIMEOUT_MS = 30_000L  // 30 seconds ring timeout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Public state ─────────────────────────────────────────────────

    private val _callState = MutableStateFlow<CallState?>(null)
    val callState: StateFlow<CallState?> = _callState.asStateFlow()

    private val _callData = MutableStateFlow<CallData?>(null)
    val callData: StateFlow<CallData?> = _callData.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0)
    val callDurationSeconds: StateFlow<Int> = _callDurationSeconds.asStateFlow()

    private val _outgoingCallUiStatus = MutableStateFlow<OutgoingCallUiStatus?>(null)
    val outgoingCallUiStatus: StateFlow<OutgoingCallUiStatus?> = _outgoingCallUiStatus.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _callMode = MutableStateFlow(CallMode.VOICE)
    val callMode: StateFlow<CallMode> = _callMode.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isRemoteVideoEnabled = MutableStateFlow(false)
    val isRemoteVideoEnabled: StateFlow<Boolean> = _isRemoteVideoEnabled.asStateFlow()

    private val _videoUpgradeRequestState = MutableStateFlow(VideoUpgradeRequestState.NONE)
    val videoUpgradeRequestState: StateFlow<VideoUpgradeRequestState> = _videoUpgradeRequestState.asStateFlow()

    private val _videoUpgradeRequesterId = MutableStateFlow("")
    val videoUpgradeRequesterId: StateFlow<String> = _videoUpgradeRequesterId.asStateFlow()

    private val _isHdMode = MutableStateFlow(false)
    val isHdMode: StateFlow<Boolean> = _isHdMode.asStateFlow()

    private val _rtcClient = MutableStateFlow<WebRtcCallClient?>(null)
    val rtcClientFlow: StateFlow<WebRtcCallClient?> = _rtcClient.asStateFlow()

    /** Current network quality as detected by the NetworkQualityMonitor. */
    val networkQuality: StateFlow<NetworkQualityMonitor.NetworkQuality>
        get() = webRtcClient?.networkQualityMonitor?.quality
            ?: MutableStateFlow(NetworkQualityMonitor.NetworkQuality.STRONG)

    /**
     * Emitted when the remote peer upgrades the 1:1 call to a group call.
     * The value is the group call ID that the local user should join.
     * ActiveCallActivity observes this to seamlessly migrate.
     */
    private val _upgradedToGroupCallId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val upgradedToGroupCallId: SharedFlow<String> = _upgradedToGroupCallId.asSharedFlow()

    // ── Internal ─────────────────────────────────────────────────────

    private var webRtcClient: WebRtcCallClient? = null
    private var audioManager: CallAudioManager? = null
    private var callObserverJob: Job? = null
    private var iceCandidateJob: Job? = null
    private var iceForwardJob: Job? = null
    private var connectionConnectedJob: Job? = null
    private var connectionDisconnectedJob: Job? = null
    private var connectionFailedJob: Job? = null
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null
    private var ringingObserverJob: Job? = null
    private var renegotiationObserverJob: Job? = null
    private var negotiationTaskJob: Job? = null
    private var blockMonitorJob: Job? = null
    private var isInitiator = false
    private var firestoreCallCreated = false
    private var webRtcClientCallId: String? = null
    private var webRtcClientUserId: String? = null
    private var webRtcClientCallType: CallType? = null
    private val teardownInProgress = AtomicBoolean(false)
    private val processedCandidates = mutableSetOf<String>()
    private var latestLocalOfferRevision = 0
    private var latestLocalAnswerRevision = 0
    private var latestRemoteOfferRevision = 0
    private var latestRemoteAnswerRevision = 0
    private var latestMediaStateRevision = 0
    private var pendingTerminalCallLogStatus: String? = null

    val currentCallId: String? get() = _callData.value?.callId
    val currentCallType: CallType? get() = _callData.value?.callType()
    val rtcClient: WebRtcCallClient? get() = webRtcClient

    fun cacheIncomingCallSignal(callData: CallData) {
        val activeState = _callState.value
        val isActiveCall = when (activeState) {
            CallState.INITIATING,
            CallState.RINGING,
            CallState.ACCEPTED,
            CallState.CONNECTED -> true
            else -> false
        }

        if (isActiveCall && currentCallId != callData.callId) {
            return
        }

        _callData.value = callData
        if (!isActiveCall || currentCallId == callData.callId) {
            _callState.value = CallState.RINGING
            _outgoingCallUiStatus.value = null
            _callDurationSeconds.value = 0
            _isMicMuted.value = false
            _isSpeakerOn.value = false
            _callMode.value = if (callData.callType() == CallType.VIDEO) CallMode.VIDEO else CallMode.VOICE
            _isVideoEnabled.value = false
            _isRemoteVideoEnabled.value = callData.callType() == CallType.VIDEO
            _videoUpgradeRequestState.value = VideoUpgradeRequestState.NONE
            _videoUpgradeRequesterId.value = ""
            _isHdMode.value = false
        }
    }

    // ── Initiate outgoing call ───────────────────────────────────────

    fun startOutgoingCall(
        context: Context,
        receiverId: String,
        receiverName: String,
        receiverAvatar: String,
        callType: CallType
    ) {
        if (_callState.value != null && _callState.value != CallState.ENDED) {
            Log.w(TAG, "Already in a call, ignoring startOutgoing")
            return
        }
        isInitiator = true
        firestoreCallCreated = false
        processedCandidates.clear()
        latestLocalOfferRevision = 0
        latestLocalAnswerRevision = 0
        latestRemoteOfferRevision = 0
        latestRemoteAnswerRevision = 0
        latestMediaStateRevision = 0

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val callId = UUID.randomUUID().toString()
        val authPhone = FirebaseAuth.getInstance().currentUser?.phoneNumber.orEmpty().trim()

        val data = CallData(
            callId = callId,
            callerId = myUid,
            receiverId = receiverId,
            callerName = "",  // Will be fetched/set by the service
            callerPhone = authPhone,
            callerAvatar = "",
            receiverName = receiverName,
            receiverAvatar = receiverAvatar,
            type = if (callType == CallType.VIDEO) "VIDEO" else "VOICE",
            callMode = if (callType == CallType.VIDEO) "VIDEO" else "VOICE",
            status = "ringing",
            callerVideoEnabled = callType == CallType.VIDEO,
            receiverVideoEnabled = false,
            createdAt = System.currentTimeMillis()
        )

        _callData.value = data
        _callState.value = CallState.INITIATING
        _outgoingCallUiStatus.value = OutgoingCallUiStatus.CONNECTING
        _callDurationSeconds.value = 0
        _isMicMuted.value = false
        _isSpeakerOn.value = callType == CallType.VIDEO
        _callMode.value = if (callType == CallType.VIDEO) CallMode.VIDEO else CallMode.VOICE
        _isVideoEnabled.value = callType == CallType.VIDEO
        _isRemoteVideoEnabled.value = false
        _videoUpgradeRequestState.value = VideoUpgradeRequestState.NONE
        _videoUpgradeRequesterId.value = ""
        _isHdMode.value = false
        syncTelecomSpeakerState()

        // Initialize WebRTC
        initializeWebRtc(context, callId, myUid, callType)

        scope.launch {
            try {
                if (BlockRepository.fetchBlockStatus(receiverId).isBlocked) {
                    cleanup(context)
                    return@launch
                }
                val resolvedCallerPhone = data.callerPhone.ifBlank { resolveCurrentUserPhone(myUid) }
                val resolvedReceiverPhone = resolveUserPhone(receiverId)
                val persistedCallData = data.copy(
                    callerPhone = resolvedCallerPhone.ifBlank { data.callerPhone },
                    receiverPhone = resolvedReceiverPhone.ifBlank { data.receiverPhone }
                )
                _callData.value = persistedCallData
                startBlockMonitor(context, receiverId)

                // Create offer locally first (no Firestore yet — callee not notified)
                val offer = webRtcClient!!.createOffer()
                _outgoingCallUiStatus.value = OutgoingCallUiStatus.CALLING

                // Single merged write: creates doc + embeds offer SDP.
                // Saves ~300ms vs createCall() + setOffer() as two separate roundtrips.
                // This also triggers the Cloud Function that sends INCOMING_CALL FCM.
                CallSignalingRepository.createCallWithOffer(persistedCallData, offer.description)
                latestLocalOfferRevision = 1
                firestoreCallCreated = true

                // Start observing AFTER creating the document. The Firestore security rule
                // `allow read: if uid == resource.data.callerId` requires the document to
                // exist — starting the listener before creation produces PERMISSION_DENIED,
                // which permanently kills the listener and leaves the caller stuck on
                // "Ringing" forever. The callee cannot physically respond faster than the
                // FCM round-trip + user interaction (several seconds), so there is no
                // realistic race window between document creation and observer start.
                observeCallDocument(callId, myUid, context)

                // Start foreground service
                CallForegroundService.start(context, callId, callType, isIncoming = false)

                // Start timeout
                startTimeout(context, callId)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start outgoing call", e)
                endCall(context)
            }
        }
    }

    // ── Receiver-side ringing phase observation ──────────────────────
    //
    // Watches the Firestore call document while the receiver is in the ringing
    // state (before accepting). If the caller cancels or the call expires, we
    // update callState, cancel the incoming-call notification and show a missed-
    // call notification — even when IncomingCallActivity is not in the foreground.

    fun startRingingObservation(
        callId: String,
        callerName: String,
        callerAvatar: String,
        callType: com.glyph.glyph_v3.data.models.CallType,
        context: Context
    ) {
        // Only one watcher at a time; also skip if already accepting/accepted.
        if (ringingObserverJob?.isActive == true) return
        val appCtx = context.applicationContext

        ringingObserverJob = scope.launch {
            // Guard: prevents showing the missed-call notification more than once if
            // both the Firestore observer and the backup timer happen to fire close together.
            var missedHandled = false

            // Backup timer: if the Firestore observer misses the NO_ANSWER update (e.g. Doze
            // mode, background network restriction), fire after 33 s — just before the
            // setTimeoutAfter(35 s) on the incoming-call notification — and show the missed
            // call notification directly.  This job is a child of ringingObserverJob, so it
            // is automatically cancelled if the Firestore path handles the terminal state first.
            val backupTimerJob = launch {
                delay(33_000L)
                if (!missedHandled &&
                    (_callState.value == null || _callState.value == CallState.RINGING)
                ) {
                    missedHandled = true
                    _callState.value = CallState.MISSED
                    com.glyph.glyph_v3.data.service.CallNotificationHelper
                        .cancelIncomingNotification(appCtx)
                    com.glyph.glyph_v3.data.service.CallNotificationHelper
                        .showMissedCallNotification(appCtx, callerName, callerAvatar, callType)
                    scope.launch {
                        delay(800)
                        _callState.value = null
                        _callData.value = null
                    }
                }
                // Cancel parent job so the Firestore observer stops too.
                ringingObserverJob?.cancel()
            }

            try {
                // Use collect (not collectLatest) so that a second Firestore snapshot
                // arriving while showMissedCallNotification is suspended does NOT cancel
                // the in-progress notification.
                CallSignalingRepository.observeCall(callId).collect { data ->
                    if (data == null) return@collect
                    when (data.callState()) {
                        CallState.ENDED, CallState.MISSED, CallState.NO_ANSWER -> {
                            // Caller hung up or call expired — notify receiver
                            if (!missedHandled) {
                                missedHandled = true
                                backupTimerJob.cancel()
                                _callState.value = CallState.MISSED
                                com.glyph.glyph_v3.data.service.CallNotificationHelper
                                    .cancelIncomingNotification(appCtx)
                                com.glyph.glyph_v3.data.service.CallNotificationHelper
                                    .showMissedCallNotification(appCtx, callerName, callerAvatar, callType)
                            }
                            ringingObserverJob?.cancel()
                            // Reset state so future outgoing calls are not blocked by the
                            // stale MISSED state (startOutgoingCall guards on non-null state).
                            scope.launch {
                                delay(800)
                                _callState.value = null
                                _callData.value = null
                            }
                        }
                        CallState.DECLINED -> {
                            // Declined from this device via another path — just clean up
                            backupTimerJob.cancel()
                            com.glyph.glyph_v3.data.service.CallNotificationHelper
                                .cancelIncomingNotification(appCtx)
                            ringingObserverJob?.cancel()
                            scope.launch {
                                delay(800)
                                _callState.value = null
                                _callData.value = null
                            }
                        }
                        CallState.ACCEPTED -> {
                            // Accepted (possibly on another device) — dismiss ringing
                            backupTimerJob.cancel()
                            ringingObserverJob?.cancel()
                            scope.launch {
                                delay(800)
                                _callState.value = null
                                _callData.value = null
                            }
                        }
                        else -> {
                            // Cache the full call data (including the offer SDP) while the
                            // user is deciding to accept. acceptIncomingCall() can then use
                            // this directly and skip a redundant Firestore read (~300ms saved).
                            if (data.offer.isNotEmpty()) {
                                _callData.value = data
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ringing observer error for $callId", e)
            }
        }
    }

    fun stopRingingObservation() {
        ringingObserverJob?.cancel()
        ringingObserverJob = null
    }

    fun prepareIncomingVideoPreview(context: Context, callId: String) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        _callMode.value = CallMode.VIDEO
        _isVideoEnabled.value = true
        _isRemoteVideoEnabled.value = true
        initializeWebRtc(
            context = context.applicationContext,
            callId = callId,
            userId = myUid,
            callType = CallType.VIDEO,
            configureAudio = false
        )
    }

    fun releaseIncomingVideoPreview(callId: String) {
        if (_callState.value == CallState.ACCEPTED || _callState.value == CallState.CONNECTED) {
            return
        }
        if (webRtcClientCallId != callId) {
            return
        }

        webRtcClient?.dispose()
        webRtcClient = null
        _rtcClient.value = null
        webRtcClientCallId = null
        webRtcClientUserId = null
        webRtcClientCallType = null
    }

    // ── Accept incoming call ─────────────────────────────────────────

    fun acceptIncomingCall(context: Context, callId: String, callType: CallType) {
        isInitiator = false
        firestoreCallCreated = true
        _outgoingCallUiStatus.value = null
        processedCandidates.clear()
        latestLocalOfferRevision = 0
        latestLocalAnswerRevision = 0
        latestRemoteOfferRevision = 1
        latestRemoteAnswerRevision = 0
        latestMediaStateRevision = 0

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        _callState.value = CallState.ACCEPTED
        _isMicMuted.value = false
        _isSpeakerOn.value = callType == CallType.VIDEO
        _callMode.value = if (callType == CallType.VIDEO) CallMode.VIDEO else CallMode.VOICE
        _isVideoEnabled.value = callType == CallType.VIDEO
        _isRemoteVideoEnabled.value = callType == CallType.VIDEO
        _videoUpgradeRequestState.value = VideoUpgradeRequestState.NONE
        _videoUpgradeRequesterId.value = ""
        _isHdMode.value = false
        syncTelecomSpeakerState()

        // Initialize WebRTC
        initializeWebRtc(context, callId, myUid, callType)

        // Start observing the call document and ICE candidates IMMEDIATELY — before the
        // async SDP exchange below. Caller ICE candidates written to Firestore during the
        // ringing phase are in the candidates subcollection right now. Starting iceCandidateJob
        // here ensures we pick them up in the first Firestore snapshot and buffer them.
        // setRemoteDescription (called below) will flush the buffer on success.
        observeCallDocument(callId, myUid, context)

        scope.launch {
            try {
                // Use pre-fetched call data cached during the ringing phase if available.
                // startRingingObservation() caches data (including offer) while the user
                // is deciding to accept, so we can skip a ~300ms Firestore read.
                val data = run {
                    val cached = _callData.value
                    if (cached != null && cached.callId == callId && cached.offer.isNotEmpty()) {
                        cached
                    } else {
                        CallSignalingRepository.getCallData(callId)
                    }
                }
                if (data == null || data.offer.isEmpty()) {
                    Log.e(TAG, "No offer found for call $callId")
                    endCall(context)
                    return@launch
                }

                val acceptedData = if (callType == CallType.VIDEO) {
                    data.copy(
                        callMode = CallMode.VIDEO.name,
                        receiverVideoEnabled = true,
                        mediaStateRevision = data.mediaStateRevision + 1,
                        videoUpgradeRequestState = VideoUpgradeRequestState.NONE.name,
                        videoUpgradeRequesterId = "",
                        signalingEvent = "VIDEO_ENABLED"
                    )
                } else {
                    data
                }

                _callData.value = acceptedData
                if (callType == CallType.VIDEO) {
                    latestMediaStateRevision = acceptedData.mediaStateRevision
                    applyMediaState(acceptedData, myUid)
                }
                startBlockMonitor(context, if (acceptedData.callerId == myUid) acceptedData.receiverId else acceptedData.callerId)

                // Set remote description (the offer). Any caller ICE candidates already buffered
                // by iceCandidateJob (started above) are flushed automatically on success.
                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, data.offer)
                webRtcClient!!.setRemoteDescription(remoteSdp)
                latestRemoteOfferRevision = data.offerRevision.coerceAtLeast(1)

                // Create & send answer
                val answer = webRtcClient!!.createAnswer()
                // setAnswer() already writes status="accepted" + the answer SDP atomically.
                // No second updateCallStatus("accepted") needed — that was a ~300ms wasted write.
                latestLocalAnswerRevision = 1
                CallSignalingRepository.setAnswer(
                    callId = callId,
                    sdp = answer.description,
                    answerRevision = latestLocalAnswerRevision,
                    markAccepted = true,
                    iceRestart = false,
                    additionalUpdates = if (callType == CallType.VIDEO) {
                        mapOf(
                            "callMode" to CallMode.VIDEO.name,
                            "receiverVideoEnabled" to true,
                            "mediaStateRevision" to acceptedData.mediaStateRevision,
                            "videoUpgradeRequestState" to VideoUpgradeRequestState.NONE.name,
                            "videoUpgradeRequesterId" to "",
                            "signalingEvent" to "VIDEO_ENABLED"
                        )
                    } else {
                        emptyMap()
                    }
                )

                // Start foreground service
                CallForegroundService.start(context, callId, callType, isIncoming = true)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept incoming call", e)
                endCall(context)
            }
        }
    }

    // ── Decline call ─────────────────────────────────────────────────

    fun declineCall(context: Context, callId: String) {
        scope.launch {
            try {
                pendingTerminalCallLogStatus = "declined"
                _callData.value
                    ?.takeIf { it.callId == callId }
                    ?.let { persistTerminalCallLog(context, it, statusOverride = "declined") }
                CallSignalingRepository.updateCallStatus(callId, "declined")
            } catch (e: Exception) {
                Log.e(TAG, "Error declining call", e)
            }
            GlyphTelecomManager.markCallRejected(callId)
            cleanup(context)
        }
    }

    // ── End call ─────────────────────────────────────────────────────

    fun endCall(context: Context) {
        val callId = _callData.value?.callId ?: run {
            cleanup(context)
            return
        }
        scope.launch {
            val currentState = _callState.value
            val newStatus = when (currentState) {
                CallState.RINGING -> if (isInitiator) "missed" else "declined"
                CallState.INITIATING -> "missed"
                else -> "ended"
            }
            pendingTerminalCallLogStatus = newStatus
            _callData.value
                ?.takeIf { it.callId == callId }
                ?.let { persistTerminalCallLog(context, it, statusOverride = newStatus) }

            // Only update Firestore if the call document was actually created.
            // If the user cancelled during the 2-second grace period the doc
            // doesn't exist yet, so we skip the remote update and just cleanup.
            if (firestoreCallCreated) {
                try {
                    CallSignalingRepository.updateCallStatus(callId, newStatus)
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending call", e)
                }
            }
            cleanup(context)
        }
    }

    // ── Seamless upgrade to group call ──────────────────────────────

    /**
     * Result of detaching the WebRTC client for group call migration.
     * The peer connection stays alive; only CallManager state is cleaned up.
     */
    data class DetachedCallInfo(
        val client: WebRtcCallClient,
        val audioManager: CallAudioManager?,
        val callData: CallData,
        val isMicMuted: Boolean,
        val isVideoEnabled: Boolean,
        val isSpeakerOn: Boolean,
        val callDurationSeconds: Int
    )

    /**
     * Detaches the active WebRTC client without disposing it, allowing
     * a seamless handover to [GroupCallManager]. The 1:1 call document
     * is updated with [upgradedToGroupCallId] so the remote peer can
     * detect the upgrade and migrate as well.
     *
     * Returns null if there is no active call or client.
     */
    fun detachForGroupUpgrade(groupCallId: String): DetachedCallInfo? {
        val client = webRtcClient ?: return null
        val data = _callData.value ?: return null

        // Cancel all observer/timer jobs but do NOT dispose the peer connection
        callObserverJob?.cancel(); callObserverJob = null
        iceCandidateJob?.cancel(); iceCandidateJob = null
        iceForwardJob?.cancel(); iceForwardJob = null
        renegotiationObserverJob?.cancel(); renegotiationObserverJob = null
        negotiationTaskJob?.cancel(); negotiationTaskJob = null
        connectionConnectedJob?.cancel(); connectionConnectedJob = null
        connectionDisconnectedJob?.cancel(); connectionDisconnectedJob = null
        connectionFailedJob?.cancel(); connectionFailedJob = null
        timerJob?.cancel(); timerJob = null
        timeoutJob?.cancel(); timeoutJob = null
        ringingObserverJob?.cancel(); ringingObserverJob = null
        blockMonitorJob?.cancel(); blockMonitorJob = null

        val detached = DetachedCallInfo(
            client = client,
            audioManager = audioManager,
            callData = data,
            isMicMuted = _isMicMuted.value,
            isVideoEnabled = _isVideoEnabled.value,
            isSpeakerOn = _isSpeakerOn.value,
            callDurationSeconds = _callDurationSeconds.value
        )

        // Null out references without disposing
        webRtcClient = null
        _rtcClient.value = null
        audioManager = null
        webRtcClientCallId = null
        webRtcClientUserId = null
        webRtcClientCallType = null

        // Save context before nulling for foreground service stop
        val savedContext = appContext

        // Reset state flows
        firestoreCallCreated = false
        latestLocalOfferRevision = 0
        latestLocalAnswerRevision = 0
        latestRemoteOfferRevision = 0
        latestRemoteAnswerRevision = 0
        latestMediaStateRevision = 0
        isInitiator = false
        appContext = null
        processedCandidates.clear()

        _callState.value = null
        _callData.value = null
        _callDurationSeconds.value = 0
        _outgoingCallUiStatus.value = null
        _isMicMuted.value = false
        _isSpeakerOn.value = false
        _isHdMode.value = false
        _callMode.value = CallMode.VOICE
        _isVideoEnabled.value = false
        _isRemoteVideoEnabled.value = false
        _videoUpgradeRequestState.value = VideoUpgradeRequestState.NONE
        _videoUpgradeRequesterId.value = ""

        // Signal the remote peer about the upgrade via Firestore
        scope.launch(Dispatchers.IO) {
            try {
                CallSignalingRepository.updateCallFields(
                    data.callId,
                    mapOf("upgradedToGroupCallId" to groupCallId)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write upgradedToGroupCallId", e)
            }
        }

        // Stop the foreground service (GroupCallManager will start its own)
        if (savedContext != null) {
            CallForegroundService.stop(savedContext.applicationContext)
        }

        return detached
    }

    // ── Media controls ───────────────────────────────────────────────

    fun toggleMicrophone() {
        val newState = !_isMicMuted.value
        _isMicMuted.value = newState
        webRtcClient?.setMicrophoneMuted(newState)
    }

    fun toggleSpeaker() {
        audioManager?.toggleSpeaker()
        _isSpeakerOn.value = audioManager?.isSpeakerOn ?: false
        syncTelecomSpeakerState()
    }

    fun reapplyAudioRoute() {
        audioManager?.reapplyCurrentRoute()
        _isSpeakerOn.value = audioManager?.isSpeakerOn ?: _isSpeakerOn.value
        syncTelecomSpeakerState()
    }

    private fun syncTelecomSpeakerState() {
        val callId = _callData.value?.callId ?: return
        GlyphTelecomManager.updateCallSpeakerState(callId, _isSpeakerOn.value)
    }

    fun toggleVideo() {
        val data = _callData.value ?: return
        val localUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val enableVideo = !_isVideoEnabled.value

        if (enableVideo) {
            requestOrAcceptVideoUpgrade(data, localUserId)
        } else {
            disableLocalVideo(data, localUserId)
        }
    }

    fun switchCamera() {
        webRtcClient?.switchCamera()
    }

    fun toggleHdMode() {
        val newState = !_isHdMode.value
        _isHdMode.value = newState
        webRtcClient?.setHdMode(newState)
    }

    private fun requestOrAcceptVideoUpgrade(callData: CallData, localUserId: String) {
        val amCaller = callData.callerId == localUserId
        val remoteVideoEnabled = if (amCaller) callData.receiverVideoEnabled else callData.callerVideoEnabled
        val incomingRequestPending = callData.resolvedVideoUpgradeRequestState() == VideoUpgradeRequestState.PENDING &&
            callData.videoUpgradeRequesterId.isNotBlank() &&
            callData.videoUpgradeRequesterId != localUserId

        val nextRequestState = when {
            incomingRequestPending || remoteVideoEnabled -> VideoUpgradeRequestState.ACCEPTED
            else -> VideoUpgradeRequestState.PENDING
        }
        val requesterId = when {
            incomingRequestPending -> callData.videoUpgradeRequesterId
            nextRequestState == VideoUpgradeRequestState.PENDING -> localUserId
            else -> ""
        }
        val signalingEvent = when {
            incomingRequestPending -> "VIDEO_UPGRADE_ACCEPT"
            remoteVideoEnabled -> "VIDEO_ENABLED"
            else -> "VIDEO_UPGRADE_REQUEST"
        }

        updateLocalVideoDocumentState(
            callData = callData,
            localUserId = localUserId,
            enabled = true,
            requestState = nextRequestState,
            requesterId = requesterId,
            signalingEvent = signalingEvent
        )
    }

    private fun disableLocalVideo(callData: CallData, localUserId: String) {
        updateLocalVideoDocumentState(
            callData = callData,
            localUserId = localUserId,
            enabled = false,
            requestState = if (callData.resolvedVideoUpgradeRequestState() == VideoUpgradeRequestState.PENDING) {
                VideoUpgradeRequestState.REJECTED
            } else {
                VideoUpgradeRequestState.NONE
            },
            requesterId = if (callData.resolvedVideoUpgradeRequestState() == VideoUpgradeRequestState.PENDING) {
                callData.videoUpgradeRequesterId
            } else {
                ""
            },
            signalingEvent = if (callData.resolvedVideoUpgradeRequestState() == VideoUpgradeRequestState.PENDING &&
                callData.videoUpgradeRequesterId != localUserId) {
                "VIDEO_UPGRADE_REJECT"
            } else {
                "VIDEO_DISABLED"
            }
        )
    }

    private fun updateLocalVideoDocumentState(
        callData: CallData,
        localUserId: String,
        enabled: Boolean,
        requestState: VideoUpgradeRequestState,
        requesterId: String,
        signalingEvent: String
    ) {
        val amCaller = callData.callerId == localUserId
        val remoteVideoEnabled = if (amCaller) callData.receiverVideoEnabled else callData.callerVideoEnabled
        val nextCallerVideoEnabled = if (amCaller) enabled else callData.callerVideoEnabled
        val nextReceiverVideoEnabled = if (amCaller) callData.receiverVideoEnabled else enabled
        val nextCallMode = if (nextCallerVideoEnabled || nextReceiverVideoEnabled) CallMode.VIDEO else CallMode.VOICE
        val nextMediaStateRevision = callData.mediaStateRevision + 1
        val nextData = callData.copy(
            callMode = nextCallMode.name,
            callerVideoEnabled = nextCallerVideoEnabled,
            receiverVideoEnabled = nextReceiverVideoEnabled,
            mediaStateRevision = nextMediaStateRevision,
            videoUpgradeRequestState = requestState.name,
            videoUpgradeRequesterId = requesterId,
            signalingEvent = signalingEvent
        )

        _callData.value = nextData
        applyMediaState(nextData, localUserId)

        scope.launch {
            try {
                CallSignalingRepository.updateCallFields(
                    callData.callId,
                    mapOf(
                        "callMode" to nextCallMode.name,
                        "callerVideoEnabled" to nextCallerVideoEnabled,
                        "receiverVideoEnabled" to nextReceiverVideoEnabled,
                        "mediaStateRevision" to nextMediaStateRevision,
                        "videoUpgradeRequestState" to requestState.name,
                        "videoUpgradeRequesterId" to requesterId,
                        "signalingEvent" to signalingEvent
                    )
                )
                maybeRefreshForegroundService(callData.callId)
                if (isInitiator && _callState.value == CallState.CONNECTED) {
                    triggerMediaNegotiation(callData.callId, signalingEvent.lowercase())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update local video state for call=${callData.callId}", e)
            }
        }
    }

    /**
     * Notify the adaptive video controller that the app is in background or foreground.
     * When in background, video resolution/bitrate/FPS are reduced to save bandwidth
     * and battery. Called by ActiveCallActivity lifecycle callbacks.
     */
    fun setAppInBackground(background: Boolean) {
        webRtcClient?.setAppInBackground(background)
    }

    private fun applyMediaState(callData: CallData, localUserId: String = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()) {
        if (localUserId.isBlank()) return

        val amCaller = callData.callerId == localUserId
        val localVideoEnabled = if (amCaller) callData.callerVideoEnabled else callData.receiverVideoEnabled
        val remoteVideoEnabled = if (amCaller) callData.receiverVideoEnabled else callData.callerVideoEnabled
        val requestState = callData.resolvedVideoUpgradeRequestState()
        val callMode = when {
            localVideoEnabled || remoteVideoEnabled || callData.resolvedCallMode() == CallMode.VIDEO -> CallMode.VIDEO
            else -> CallMode.VOICE
        }

        _callMode.value = callMode
        _isVideoEnabled.value = localVideoEnabled
        _isRemoteVideoEnabled.value = remoteVideoEnabled
        _videoUpgradeRequestState.value = requestState
        _videoUpgradeRequesterId.value = callData.videoUpgradeRequesterId

        webRtcClient?.updateVideoNegotiationState(
            localVideoEnabled = localVideoEnabled,
            receiveRemoteVideo = remoteVideoEnabled || requestState != VideoUpgradeRequestState.NONE || callMode == CallMode.VIDEO
        )
        maybeRefreshForegroundService(callData.callId)
    }

    private fun maybeRefreshForegroundService(callId: String) {
        val context = context() ?: return
        val foregroundType = if (_isVideoEnabled.value || _callMode.value == CallMode.VIDEO) {
            CallType.VIDEO
        } else {
            CallType.VOICE
        }
        CallForegroundService.start(context, callId, foregroundType, isIncoming = !isInitiator)
    }

    private suspend fun resolveCurrentUserPhone(userId: String): String {
        val authPhone = FirebaseAuth.getInstance().currentUser?.phoneNumber.orEmpty().trim()
        if (authPhone.isNotEmpty()) return authPhone

        return resolveUserPhone(userId)
    }

    /**
     * Resolves any user's phone number from Firestore.
     * Also caches it in [ContactDisplayNameResolver] for future UI lookups.
     */
    private suspend fun resolveUserPhone(userId: String): String {
        if (userId.isBlank()) return ""

        return try {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .get()
                .await()

            val phone = listOf("phoneNumber", "phone", "mobile")
                .asSequence()
                .mapNotNull { key -> snapshot.getString(key) }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()

            if (phone.isNotEmpty()) {
                com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver.cacheUserPhone(userId, phone)
            }

            phone
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve user phone for call signaling", e)
            ""
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun initializeWebRtc(
        context: Context,
        callId: String,
        userId: String,
        callType: CallType,
        configureAudio: Boolean = true
    ) {
        val sameClient = webRtcClient != null &&
            webRtcClientCallId == callId &&
            webRtcClientUserId == userId &&
            webRtcClientCallType == callType

        if (!sameClient) {
            webRtcClient?.dispose()
            webRtcClient = WebRtcCallClient(context, callId, userId, callType).also { it.initialize() }
            _rtcClient.value = webRtcClient
            webRtcClientCallId = callId
            webRtcClientUserId = userId
            webRtcClientCallType = callType
        }

        webRtcClient?.updateVideoNegotiationState(
            localVideoEnabled = _isVideoEnabled.value,
            receiveRemoteVideo = _isRemoteVideoEnabled.value ||
                _videoUpgradeRequestState.value != VideoUpgradeRequestState.NONE ||
                _callMode.value == CallMode.VIDEO
        )

        if (configureAudio) {
            audioManager?.stop()
            audioManager = CallAudioManager(context).also {
                it.start(speakerDefault = callType == CallType.VIDEO)
            }
            _isSpeakerOn.value = audioManager?.isSpeakerOn ?: false
            syncTelecomSpeakerState()
        }

        // Forward local ICE candidates to Firestore.
        // Each write is fired in an independent coroutine so candidates are written
        // concurrently — no blocking on write completion before the next candidate
        // can be forwarded. This is critical because WebRTC can emit 5–8 candidates
        // in quick succession; sequential awaited writes would add 1–3 s of latency.
        iceForwardJob?.cancel()
        iceForwardJob = scope.launch {
            webRtcClient?.onIceCandidate?.collect { candidate ->
                scope.launch(Dispatchers.IO) {
                    forwardIceCandidateWithRetry(callId, userId, candidate)
                }
            }
        }

        renegotiationObserverJob?.cancel()
        renegotiationObserverJob = scope.launch {
            webRtcClient?.onRenegotiationNeeded?.collect {
                if (isInitiator && _callState.value == CallState.CONNECTED) {
                    triggerMediaNegotiation(callId, "peer_connection_renegotiation")
                }
            }
        }

        // Cancel existing connection-state listener jobs before re-creating them.
        // If initializeWebRtc is called twice for the same call (e.g. prepareIncomingVideoPreview
        // then acceptIncomingCall), we MUST cancel the previous listeners first — otherwise
        // duplicate listeners fire and onConnectionFailed can call endCall() during ICE.
        connectionConnectedJob?.cancel()
        connectionDisconnectedJob?.cancel()
        connectionFailedJob?.cancel()

        // Listen for connection state
        connectionConnectedJob = scope.launch {
            webRtcClient?.onConnectionConnected?.collect {
                _callState.value = CallState.CONNECTED
                if (isInitiator) {
                    _outgoingCallUiStatus.value = OutgoingCallUiStatus.CONNECTED
                }
                timeoutJob?.cancel()
                startTimer()

                // Start adaptive video quality monitoring once connected.
                // This begins polling WebRTC stats and adjusting capture/bitrate
                // based on real network conditions.
                webRtcClient?.startAdaptiveQuality()

                scope.launch {
                    try {
                        CallSignalingRepository.updateCallStatus(callId, "connected")
                    } catch (_: Exception) {}
                }
            }
        }

        // DISCONNECTED: transient loss (radio handoff, brief congestion, screen-off).
        // Do NOT end the call immediately — GATHER_CONTINUALLY will re-gather candidates
        // and ICE will self-recover in most cases within seconds.
        // If still disconnected after 15s, attempt ICE restart; give another 20s before giving up.
        connectionDisconnectedJob = scope.launch {
            webRtcClient?.onConnectionDisconnected?.collect {
                Log.w(TAG, "ICE DISCONNECTED — starting 15s watchdog")
                delay(15_000)
                val state = webRtcClient?.connectionState?.value
                if (state == org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "Still DISCONNECTED after 15s — triggering negotiated ICE restart")
                    triggerIceRestartNegotiation(callId, "ice_disconnected")
                    // Give the ICE restart 20 more seconds to recover
                    delay(20_000)
                    val stateAfterRestart = webRtcClient?.connectionState?.value
                    if (stateAfterRestart == org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED ||
                        stateAfterRestart == org.webrtc.PeerConnection.IceConnectionState.FAILED) {
                        Log.e(TAG, "ICE restart failed to recover — ending call")
                        endCall(context)
                    }
                }
            }
        }

        // FAILED: ICE has permanently given up all candidate pairs.
        // Attempt ICE restart first; if it doesn't recover within 20s, end the call.
        connectionFailedJob = scope.launch {
            webRtcClient?.onConnectionFailed?.collect {
                Log.w(TAG, "ICE FAILED — attempting negotiated ICE restart before ending call")
                triggerIceRestartNegotiation(callId, "ice_failed")
                delay(20_000)
                val stateAfterRestart = webRtcClient?.connectionState?.value
                if (stateAfterRestart != org.webrtc.PeerConnection.IceConnectionState.CONNECTED &&
                    stateAfterRestart != org.webrtc.PeerConnection.IceConnectionState.COMPLETED) {
                    Log.e(TAG, "ICE restart did not recover after FAILED — ending call")
                    endCall(context)
                } else {
                }
            }
        }
    }

    private fun observeCallDocument(callId: String, myUid: String) {
        callObserverJob?.cancel()
        iceCandidateJob?.cancel()

        // Observe call status changes
        callObserverJob = scope.launch {
            try {
            CallSignalingRepository.observeCall(callId).collect { data ->
                if (data == null) return@collect
                firestoreCallCreated = true
                _callData.value = data
                applyMediaState(data, myUid)

                // Detect if the remote peer upgraded this call to a group call
                if (data.upgradedToGroupCallId.isNotEmpty()) {
                    _upgradedToGroupCallId.tryEmit(data.upgradedToGroupCallId)
                    return@collect // Stop processing — UI will handle migration
                }

                if (data.mediaStateRevision > latestMediaStateRevision) {
                    latestMediaStateRevision = data.mediaStateRevision
                    if (isInitiator && _callState.value == CallState.CONNECTED) {
                        triggerMediaNegotiation(callId, "media_state_revision_${data.mediaStateRevision}")
                    }
                }

                val remoteOfferRevision = data.offerRevision.coerceAtLeast(if (data.offer.isNotEmpty()) 1 else 0)
                val remoteAnswerRevision = data.answerRevision.coerceAtLeast(if (data.answer.isNotEmpty()) 1 else 0)

                if (!isInitiator && data.offer.isNotEmpty() && remoteOfferRevision > latestRemoteOfferRevision) {
                    handleRemoteOfferUpdate(callId, data, remoteOfferRevision)
                }

                if (isInitiator && data.answer.isNotEmpty() && remoteAnswerRevision > latestRemoteAnswerRevision) {
                    handleRemoteAnswerUpdate(data, remoteAnswerRevision)
                }

                when (data.callState()) {
                    CallState.RINGING -> {
                        if (isInitiator) {
                            _callState.value = CallState.RINGING
                            _outgoingCallUiStatus.value = OutgoingCallUiStatus.RINGING
                        }
                    }
                    CallState.ACCEPTED -> {
                        // Update caller UI state immediately when the Firestore doc shows
                        // status=accepted. handleRemoteAnswerUpdate() (called above) runs
                        // asynchronously and sets latestRemoteAnswerRevision AFTER this block
                        // executes, so we must NOT gate on latestRemoteAnswerRevision > 0 here —
                        // that condition is always false on the first (and typically only)
                        // snapshot that carries the answer, causing the caller to remain stuck
                        // on "Ringing" forever even after the callee accepted.
                        if (isInitiator && _callState.value != CallState.CONNECTED) {
                            _callState.value = CallState.ACCEPTED
                            _outgoingCallUiStatus.value = OutgoingCallUiStatus.CONNECTING
                        }
                    }
                    CallState.ENDED, CallState.DECLINED, CallState.MISSED, CallState.NO_ANSWER, CallState.BUSY -> {
                        val ctx = context() ?: return@collect
                        // Emit the actual terminal state FIRST (before cleanup overwrites it)
                        // so that observers (e.g. ActiveCallActivity) can react appropriately.
                        // This mirrors how NO_ANSWER is emitted in startTimeout().
                        val terminalState = data.callState()
                        pendingTerminalCallLogStatus = data.status
                        persistTerminalCallLog(ctx, data, statusOverride = data.status)
                        _callState.value = terminalState
                        cleanup(ctx)
                    }
                    else -> {
                        if (isInitiator && _callState.value == CallState.INITIATING) {
                            _outgoingCallUiStatus.value = OutgoingCallUiStatus.CALLING
                        }
                    }
                }
            }
            } catch (e: Exception) {
                // The Firestore listener can close with an error (e.g. PERMISSION_DENIED if the
                // doc is not yet created, or auth token expiry). Log and end the call so the
                // UI is not left in a permanently stuck state.
                Log.e(TAG, "Call document observer terminated with error for $callId", e)
                val ctx = context()
                if (ctx != null) endCall(ctx) else _callState.value = CallState.ENDED
            }
        }

        // Observe ICE candidates
        iceCandidateJob = scope.launch {
            CallSignalingRepository.observeIceCandidates(callId, myUid).collect { candidates ->
                for (c in candidates) {
                    val key = "${c.sdpMid}_${c.sdpMLineIndex}_${c.candidate.hashCode()}"
                    if (key in processedCandidates) continue
                    processedCandidates.add(key)
                    val iceCandidate = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
                    webRtcClient?.addIceCandidate(iceCandidate)
                }
            }
        }
    }

    // We need a Context reference for cleanup — store it when we init
    private var appContext: Context? = null
    private fun context(): Context? = appContext

    private fun observeCallDocument(callId: String, myUid: String, ctx: Context) {
        appContext = ctx.applicationContext
        observeCallDocument(callId, myUid)
    }

    private fun startTimeout(context: Context, callId: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CALL_TIMEOUT_MS)
            if (_callState.value == CallState.RINGING) {
                // Emit NO_ANSWER FIRST so ActiveCallActivity observers see it before cleanup
                pendingTerminalCallLogStatus = "no_answer"
                _callData.value
                    ?.takeIf { it.callId == callId }
                    ?.let { persistTerminalCallLog(context, it, statusOverride = "no_answer") }
                _callState.value = CallState.NO_ANSWER
                try {
                    CallSignalingRepository.updateCallStatus(callId, "no_answer")
                } catch (_: Exception) {}
                // cleanup will see NO_ANSWER in _callState and preserve it
                cleanup(context)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        _callDurationSeconds.value = 0
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _callDurationSeconds.value += 1
            }
        }
    }

    private fun cleanup(context: Context) {
        if (!teardownInProgress.compareAndSet(false, true)) {
            return
        }

        val finishedCallId = _callData.value?.callId
        val finalCallSnapshot = _callData.value
        try {
            callObserverJob?.cancel()
            callObserverJob = null
            iceCandidateJob?.cancel()
            iceCandidateJob = null
            iceForwardJob?.cancel()
            iceForwardJob = null
            renegotiationObserverJob?.cancel()
            renegotiationObserverJob = null
            negotiationTaskJob?.cancel()
            negotiationTaskJob = null
            connectionConnectedJob?.cancel()
            connectionConnectedJob = null
            connectionDisconnectedJob?.cancel()
            connectionDisconnectedJob = null
            connectionFailedJob?.cancel()
            connectionFailedJob = null
            timerJob?.cancel()
            timerJob = null
            timeoutJob?.cancel()
            timeoutJob = null
            ringingObserverJob?.cancel()
            ringingObserverJob = null
            blockMonitorJob?.cancel()
            blockMonitorJob = null

            firestoreCallCreated = false
            latestLocalOfferRevision = 0
            latestLocalAnswerRevision = 0
            latestRemoteOfferRevision = 0
            latestRemoteAnswerRevision = 0
            latestMediaStateRevision = 0
            isInitiator = false
            appContext = null

            webRtcClient?.dispose()
            webRtcClient = null
            _rtcClient.value = null
            webRtcClientCallId = null
            webRtcClientUserId = null
            webRtcClientCallType = null

            audioManager?.stop()
            audioManager = null
            _outgoingCallUiStatus.value = null
            _isMicMuted.value = false
            _isSpeakerOn.value = false
            _isHdMode.value = false

            // Preserve NO_ANSWER / DECLINED / MISSED if already emitted so the
            // calling activity can still observe them and show the appropriate screen.
            if (_callState.value != CallState.NO_ANSWER && _callState.value != CallState.DECLINED && _callState.value != CallState.MISSED && _callState.value != CallState.BUSY) {
                _callState.value = CallState.ENDED
            }

            if (finalCallSnapshot != null && finalCallSnapshot.callId.isNotBlank()) {
                val finalStatus = resolveTerminalCallLogStatus(finalCallSnapshot)
                persistTerminalCallLog(context, finalCallSnapshot, statusOverride = finalStatus)
            } else {
                Log.w(
                    TAG,
                    "Skipping terminal call-log persistence because final snapshot is missing or blank. snapshot=${finalCallSnapshot?.callId} currentState=${_callState.value} firestoreCallCreated=$firestoreCallCreated"
                )
            }
            pendingTerminalCallLogStatus = null

            _callMode.value = CallMode.VOICE
            _isVideoEnabled.value = false
            _isRemoteVideoEnabled.value = false
            _videoUpgradeRequestState.value = VideoUpgradeRequestState.NONE
            _videoUpgradeRequesterId.value = ""
            processedCandidates.clear()

            // Short delay then null out state
            scope.launch {
                delay(800)
                _callState.value = null
                _callData.value = null
                _callDurationSeconds.value = 0
                teardownInProgress.set(false)
            }

            CallForegroundService.stop(context.applicationContext)
            finishedCallId?.let { GlyphTelecomManager.markCallDisconnected(it) }
        } catch (error: Exception) {
            teardownInProgress.set(false)
            Log.e(TAG, "Call teardown failed", error)
        }

    }

    private fun startBlockMonitor(context: Context, otherUserId: String) {
        if (otherUserId.isBlank()) return

        blockMonitorJob?.cancel()
        blockMonitorJob = scope.launch {
            BlockRepository.observeBlockStatus(otherUserId).collectLatest { status ->
                val currentState = _callState.value ?: return@collectLatest
                if (!status.isBlocked) return@collectLatest
                if (currentState == CallState.ENDED ||
                    currentState == CallState.DECLINED ||
                    currentState == CallState.MISSED ||
                    currentState == CallState.NO_ANSWER ||
                    currentState == CallState.BUSY
                ) {
                    return@collectLatest
                }

                val callId = _callData.value?.callId
                if (!callId.isNullOrBlank() && firestoreCallCreated) {
                    pendingTerminalCallLogStatus = "ended"
                    _callData.value
                        ?.takeIf { it.callId == callId }
                        ?.let { persistTerminalCallLog(context, it, statusOverride = "ended") }
                    runCatching {
                        CallSignalingRepository.updateCallStatus(callId, "ended")
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to mark blocked call as ended: $callId", error)
                    }
                }
                cleanup(context)
            }
        }
    }

    private fun resolveTerminalCallLogStatus(callData: CallData): String {
        pendingTerminalCallLogStatus?.takeIf { it.isNotBlank() }?.let { return it }

        return when (_callState.value) {
            CallState.DECLINED -> "declined"
            CallState.MISSED -> "missed"
            CallState.NO_ANSWER -> "no_answer"
            CallState.BUSY -> "busy"
            CallState.RINGING -> if (isInitiator) "missed" else "declined"
            CallState.INITIATING -> "missed"
            else -> callData.status.ifBlank { "ended" }
        }
    }

    private fun persistTerminalCallLog(
        context: Context,
        callData: CallData,
        statusOverride: String
    ) {
        if (callData.callId.isBlank()) {
            Log.w(TAG, "Skipping terminal call-log persistence because callId is blank; caller=${callData.callerId} receiver=${callData.receiverId} status=$statusOverride")
            return
        }

        val appCtx = context.applicationContext
        val effectiveEndedAt = if (callData.endedAt > 0L) callData.endedAt else System.currentTimeMillis()
        Log.d(
            TAG,
            "Persisting terminal call log callId=${callData.callId} status=$statusOverride caller=${callData.callerId} receiver=${callData.receiverId} createdAt=${callData.createdAt} answeredAt=${callData.answeredAt} endedAt=$effectiveEndedAt"
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                CallLogRepository.upsertCallLog(
                    context = appCtx,
                    callData = callData,
                    statusOverride = statusOverride,
                    endedAtOverride = effectiveEndedAt
                )
                val localUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                if (localUserId.isNotBlank()) {
                    val currentCount = CallLogRepository.countCallLogs(appCtx, localUserId)
                    Log.d(TAG, "Persisted terminal call log callId=${callData.callId}; localUserId=$localUserId count=$currentCount")
                    CallLogRepository.logRecentCallLogs(appCtx, localUserId, reason = "after_terminal_persist")
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist local call log for ${callData.callId}", error)
            }
        }
    }

    private suspend fun forwardIceCandidateWithRetry(callId: String, userId: String, candidate: IceCandidate) {
        val details = WebRtcIceConfig.parseCandidate(candidate)
        val candidateData = IceCandidateData(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp,
            fromUserId = userId
        )
        var lastError: Exception? = null
        repeat(8) { attempt ->
            try {
                CallSignalingRepository.addIceCandidate(callId, candidateData)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Failed to forward ICE candidate attempt=${attempt + 1}: ${details.summary()}", e)
                val retryDelayMs = when (attempt) {
                    0 -> 250L
                    1 -> 500L
                    2 -> 1_000L
                    3 -> 1_500L
                    4 -> 2_000L
                    5 -> 3_000L
                    else -> 4_000L
                }
                delay(retryDelayMs)
            }
        }
        Log.e(TAG, "Dropping ICE candidate after retries: ${details.summary()}", lastError)
    }

    private fun triggerMediaNegotiation(callId: String, reason: String) {
        triggerNegotiation(callId, reason, restartIce = false)
    }

    private fun triggerIceRestartNegotiation(callId: String, reason: String) {
        triggerNegotiation(callId, reason, restartIce = true)
    }

    private fun triggerNegotiation(callId: String, reason: String, restartIce: Boolean) {
        if (!isInitiator) {
            Log.w(TAG, "Waiting for remote offer; local side is not initiator. reason=$reason restartIce=$restartIce")
            return
        }
        if (negotiationTaskJob?.isActive == true) {
            return
        }
        negotiationTaskJob = scope.launch {
            try {
                Log.w(TAG, "Starting negotiation for call=$callId reason=$reason restartIce=$restartIce")
                if (restartIce) {
                    webRtcClient?.restartIce()
                }
                val nextOfferRevision = latestLocalOfferRevision + 1
                val restartOffer = webRtcClient?.createOffer(restartIce = restartIce) ?: return@launch
                latestLocalOfferRevision = nextOfferRevision
                CallSignalingRepository.setOffer(
                    callId = callId,
                    sdp = restartOffer.description,
                    offerRevision = nextOfferRevision,
                    iceRestart = restartIce
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send negotiation offer for call=$callId restartIce=$restartIce", e)
            } finally {
                negotiationTaskJob = null
            }
        }
    }

    private fun handleRemoteOfferUpdate(callId: String, data: CallData, remoteOfferRevision: Int) {
        if (negotiationTaskJob?.isActive == true) {
            return
        }
        negotiationTaskJob = scope.launch {
            try {
                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, data.offer)
                webRtcClient?.setRemoteDescription(remoteSdp)
                latestRemoteOfferRevision = remoteOfferRevision

                val nextAnswerRevision = (latestLocalAnswerRevision + 1).coerceAtLeast(1)
                val answer = webRtcClient?.createAnswer() ?: return@launch
                latestLocalAnswerRevision = nextAnswerRevision
                CallSignalingRepository.setAnswer(
                    callId = callId,
                    sdp = answer.description,
                    answerRevision = nextAnswerRevision,
                    markAccepted = _callState.value != CallState.CONNECTED,
                    iceRestart = data.iceRestart
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle remote offer revision=$remoteOfferRevision", e)
            } finally {
                negotiationTaskJob = null
            }
        }
    }

    private fun handleRemoteAnswerUpdate(data: CallData, remoteAnswerRevision: Int) {
        if (negotiationTaskJob?.isActive == true && latestRemoteAnswerRevision >= remoteAnswerRevision) {
            return
        }
        negotiationTaskJob = scope.launch {
            try {
                val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, data.answer)
                webRtcClient?.setRemoteDescription(remoteSdp)
                latestRemoteAnswerRevision = remoteAnswerRevision
                if (_callState.value != CallState.CONNECTED) {
                    _callState.value = CallState.ACCEPTED
                    if (isInitiator) {
                        _outgoingCallUiStatus.value = OutgoingCallUiStatus.CONNECTING
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle remote answer revision=$remoteAnswerRevision", e)
            } finally {
                negotiationTaskJob = null
            }
        }
    }
}
