package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.models.*
import com.glyph.glyph_v3.data.repo.GroupCallSignalingRepository
import com.glyph.glyph_v3.data.service.CallForegroundService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton group call manager. Orchestrates full-mesh multi-party calls:
 * - Each participant maintains a peer connection to every other participant
 * - Signaling is pairwise via Firestore
 * - Participants can join/leave dynamically
 */
object GroupCallManager {

    private const val TAG = "GroupCallManager"
    private const val MAX_PARTICIPANTS = 8
    private const val RING_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Public state ─────────────────────────────────────────────────

    private val _groupCallData = MutableStateFlow<GroupCallData?>(null)
    val groupCallData: StateFlow<GroupCallData?> = _groupCallData.asStateFlow()

    private val _participants = MutableStateFlow<List<GroupCallParticipant>>(emptyList())
    val participants: StateFlow<List<GroupCallParticipant>> = _participants.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0)
    val callDurationSeconds: StateFlow<Int> = _callDurationSeconds.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isInGroupCall = MutableStateFlow(false)
    val isInGroupCall: StateFlow<Boolean> = _isInGroupCall.asStateFlow()

    /** Remote video tracks keyed by remote user ID */
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    /** Local video track for self-preview */
    private val _localVideoTrack = MutableStateFlow<org.webrtc.VideoTrack?>(null)
    val localVideoTrack: StateFlow<org.webrtc.VideoTrack?> = _localVideoTrack.asStateFlow()

    /** Participant UI states (derived from participants + connection state) */
    private val _participantUiStates = MutableStateFlow<List<GroupCallParticipantUiState>>(emptyList())
    val participantUiStates: StateFlow<List<GroupCallParticipantUiState>> = _participantUiStates.asStateFlow()

    val currentGroupCallId: String? get() = _groupCallData.value?.groupCallId

    // ── Internal state ───────────────────────────────────────────────

    /** Peer connections keyed by remote user ID */
    private val peerConnections = ConcurrentHashMap<String, WebRtcCallClient>()

    /** Jobs for observing signaling/ICE per peer */
    private val signalingJobs = ConcurrentHashMap<String, Job>()
    private val iceJobs = ConcurrentHashMap<String, Job>()
    private val iceForwardJobs = ConcurrentHashMap<String, Job>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val processedCandidates = ConcurrentHashMap<String, MutableSet<String>>()
    private val peerOfferRevisions = ConcurrentHashMap<String, Int>()
    private val peerAnswerRevisions = ConcurrentHashMap<String, Int>()
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()

    private var audioManager: CallAudioManager? = null
    private var participantObserverJob: Job? = null
    private var callObserverJob: Job? = null
    private var timerJob: Job? = null
    private var appContext: Context? = null
    private val teardownInProgress = AtomicBoolean(false)

    // ── Shared media resources (group calls) ─────────────────────
    // All peer connections in a group call share a single camera capturer,
    // EglBase, PeerConnectionFactory, and local audio/video tracks to prevent
    // camera re-opening crashes and hardware codec exhaustion.
    private var sharedEglBase: EglBase? = null
    private var sharedFactory: PeerConnectionFactory? = null
    private var sharedVideoCapturer: CameraVideoCapturer? = null
    private var sharedSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var sharedVideoSource: VideoSource? = null
    private var sharedLocalVideoTrack: VideoTrack? = null
    private var sharedAudioSource: AudioSource? = null
    private var sharedLocalAudioTrack: AudioTrack? = null
    @Volatile private var sharedResourcesInitialized = false

    // ── Start group call (creator) ───────────────────────────────────

    fun startGroupCall(
        context: Context,
        callType: CallType,
        participantIds: List<String>,
        participantNames: Map<String, String>,
        participantAvatars: Map<String, String>
    ) {
        if (_isInGroupCall.value) {
            Log.w(TAG, "Already in a group call, ignoring")
            return
        }

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        appContext = context.applicationContext
        val groupCallId = UUID.randomUUID().toString()

        val callData = GroupCallData(
            groupCallId = groupCallId,
            creatorId = myUid,
            creatorName = "",
            creatorAvatar = "",
            type = if (callType == CallType.VIDEO) "VIDEO" else "VOICE",
            status = "active"
        )

        _groupCallData.value = callData
        _isInGroupCall.value = true
        _callDurationSeconds.value = 0
        _isMicMuted.value = false
        _isVideoEnabled.value = callType == CallType.VIDEO
        _isSpeakerOn.value = true

        // Initialize audio
        audioManager = CallAudioManager(context).also {
            it.start(speakerDefault = true)
        }
        _isSpeakerOn.value = audioManager?.isSpeakerOn ?: true

        scope.launch {
            try {
                // Create group call document
                GroupCallSignalingRepository.createGroupCall(callData)

                // Add self as connected participant
                val myName = resolveUserName(myUid)
                val myAvatar = resolveUserAvatar(myUid)
                GroupCallSignalingRepository.addParticipant(
                    groupCallId,
                    GroupCallParticipant(
                        userId = myUid,
                        userName = myName,
                        userAvatar = myAvatar,
                        status = "connected",
                        videoEnabled = callType == CallType.VIDEO,
                        audioEnabled = true,
                        joinedAt = System.currentTimeMillis()
                    )
                )

                // Add invited participants (ringing)
                for (participantId in participantIds) {
                    if (participantId == myUid) continue
                    GroupCallSignalingRepository.addParticipant(
                        groupCallId,
                        GroupCallParticipant(
                            userId = participantId,
                            userName = participantNames[participantId] ?: "",
                            userAvatar = participantAvatars[participantId] ?: "",
                            status = "ringing",
                            videoEnabled = false,
                            audioEnabled = true
                        )
                    )
                }

                // Start observing participants and call state
                observeGroupCallState(groupCallId)
                observeParticipants(groupCallId, myUid, callType)

                // Start foreground service
                CallForegroundService.start(context, groupCallId, callType, isIncoming = false)

                // Send FCM notifications to participants
                sendGroupCallInvitations(groupCallId, callType, participantIds, myUid, myName, myAvatar)

                startTimer()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start group call", e)
                leaveGroupCall(context)
            }
        }
    }

    // ── Join group call (invitee) ────────────────────────────────────

    fun joinGroupCall(
        context: Context,
        groupCallId: String,
        callType: CallType
    ) {
        if (_isInGroupCall.value) {
            Log.w(TAG, "Already in a group call, ignoring join")
            return
        }

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        appContext = context.applicationContext

        _isInGroupCall.value = true
        _callDurationSeconds.value = 0
        _isMicMuted.value = false
        _isVideoEnabled.value = callType == CallType.VIDEO
        _isSpeakerOn.value = true

        audioManager = CallAudioManager(context).also {
            it.start(speakerDefault = true)
        }
        _isSpeakerOn.value = audioManager?.isSpeakerOn ?: true

        scope.launch {
            try {
                val callData = GroupCallSignalingRepository.getGroupCallData(groupCallId)
                if (callData == null || callData.groupCallState() == GroupCallState.ENDED) {
                    Log.e(TAG, "Group call $groupCallId not found or ended")
                    leaveGroupCall(context)
                    return@launch
                }
                _groupCallData.value = callData

                // Update own participant status to connected
                GroupCallSignalingRepository.updateParticipantStatus(groupCallId, myUid, "connected")

                // Start observing
                observeGroupCallState(groupCallId)
                observeParticipants(groupCallId, myUid, callType)

                CallForegroundService.start(context, groupCallId, callType, isIncoming = true)

                startTimer()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to join group call", e)
                leaveGroupCall(context)
            }
        }
    }

    // ── Add participant to active group call ─────────────────────────

    fun addParticipant(
        context: Context,
        userId: String,
        userName: String,
        userAvatar: String
    ) {
        val groupCallId = _groupCallData.value?.groupCallId ?: return
        val callType = _groupCallData.value?.callType() ?: CallType.VIDEO
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (_participants.value.size >= MAX_PARTICIPANTS) {
            Log.w(TAG, "Max participants ($MAX_PARTICIPANTS) reached")
            return
        }
        if (_participants.value.any { it.userId == userId }) {
            Log.w(TAG, "User $userId already in call")
            return
        }

        scope.launch {
            try {
                GroupCallSignalingRepository.addParticipant(
                    groupCallId,
                    GroupCallParticipant(
                        userId = userId,
                        userName = userName,
                        userAvatar = userAvatar,
                        status = "ringing",
                        videoEnabled = false,
                        audioEnabled = true
                    )
                )

                val myName = resolveUserName(myUid)
                val myAvatar = resolveUserAvatar(myUid)
                sendGroupCallInvitations(
                    groupCallId, callType, listOf(userId), myUid, myName, myAvatar
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to add participant $userId", e)
            }
        }
    }

    // ── Seamless upgrade from 1:1 call to group call ──────────────

    /**
     * Migrates an active 1:1 call into a group call WITHOUT dropping
     * the existing peer connection. The [detachedInfo] carries the live
     * WebRtcCallClient and audio manager from [CallManager].
     *
     * Flow:
     * 1. Creates a new group call doc in Firestore.
     * 2. Adds self (connected) + existing peer (connected) as participants.
     * 3. Registers the existing WebRtcCallClient in [peerConnections].
     * 4. Starts observing participants for new joiners.
     * 5. Optionally invites additional new participants.
     *
     * The existing peer connection is NEVER torn down — audio/video
     * continue uninterrupted.
     *
     * @return the newly created group call ID.
     */
    fun migrateFromOneToOneCall(
        context: Context,
        detachedInfo: CallManager.DetachedCallInfo,
        groupCallId: String,
        existingPeerId: String,
        existingPeerName: String,
        existingPeerAvatar: String,
        newParticipantIds: List<String> = emptyList(),
        newParticipantNames: Map<String, String> = emptyMap(),
        newParticipantAvatars: Map<String, String> = emptyMap()
    ): String? {
        if (_isInGroupCall.value) {
            Log.w(TAG, "Already in a group call, cannot migrate")
            return currentGroupCallId
        }

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        appContext = context.applicationContext
        val callType = detachedInfo.callData.callType()

        val callData = GroupCallData(
            groupCallId = groupCallId,
            creatorId = myUid,
            type = if (callType == CallType.VIDEO) "VIDEO" else "VOICE",
            status = "active",
            upgradedFromCallId = detachedInfo.callData.callId
        )

        _groupCallData.value = callData
        _isInGroupCall.value = true
        _callDurationSeconds.value = detachedInfo.callDurationSeconds
        _isMicMuted.value = detachedInfo.isMicMuted
        _isVideoEnabled.value = detachedInfo.isVideoEnabled
        _isSpeakerOn.value = detachedInfo.isSpeakerOn

        // Take over the audio manager from the 1:1 call
        audioManager = detachedInfo.audioManager

        // Register the existing peer connection — NO new connection created
        val existingClient = detachedInfo.client
        peerConnections[existingPeerId] = existingClient
        processedCandidates[existingPeerId] = mutableSetOf()
        connectedPeers.add(existingPeerId) // Already connected

        // Extract shared media resources from the migrated client so future
        // peer connections reuse the same camera, factory, and tracks.
        extractSharedResourcesFromClient(existingClient)

        // Capture the existing remote video track
        val remoteTrack = existingClient.remoteVideoTrack.value
        if (remoteTrack != null) {
            _remoteVideoTracks.value = mapOf(existingPeerId to remoteTrack)
        }
        _localVideoTrack.value = existingClient.localVideoTrack.value

        // Populate _participants immediately so the UI has data before Firestore syncs
        val myName = ""  // Will be resolved properly in async block
        val selfParticipant = GroupCallParticipant(
            userId = myUid,
            userName = myName,
            userAvatar = "",
            status = "connected",
            videoEnabled = detachedInfo.isVideoEnabled,
            audioEnabled = !detachedInfo.isMicMuted,
            joinedAt = System.currentTimeMillis()
        )
        val peerParticipant = GroupCallParticipant(
            userId = existingPeerId,
            userName = existingPeerName,
            userAvatar = existingPeerAvatar,
            status = "connected",
            videoEnabled = true,
            audioEnabled = true,
            joinedAt = System.currentTimeMillis()
        )
        _participants.value = listOf(selfParticipant, peerParticipant)

        // Set up ICE forward and connection state listeners for the existing peer
        setupExistingPeerListeners(existingPeerId, existingClient, groupCallId, myUid)

        scope.launch {
            try {
                // Create group call document
                GroupCallSignalingRepository.createGroupCall(callData)

                // Add self as connected participant
                val myName = resolveUserName(myUid)
                val myAvatar = resolveUserAvatar(myUid)
                GroupCallSignalingRepository.addParticipant(
                    groupCallId,
                    GroupCallParticipant(
                        userId = myUid,
                        userName = myName,
                        userAvatar = myAvatar,
                        status = "connected",
                        videoEnabled = detachedInfo.isVideoEnabled,
                        audioEnabled = !detachedInfo.isMicMuted,
                        joinedAt = System.currentTimeMillis()
                    )
                )

                // Add existing peer as connected (they are already in the call)
                GroupCallSignalingRepository.addParticipant(
                    groupCallId,
                    GroupCallParticipant(
                        userId = existingPeerId,
                        userName = existingPeerName,
                        userAvatar = existingPeerAvatar,
                        status = "connected",
                        videoEnabled = true,
                        audioEnabled = true,
                        joinedAt = System.currentTimeMillis()
                    )
                )

                // Start observing participants and call state
                observeGroupCallState(groupCallId)
                observeParticipants(groupCallId, myUid, callType)

                // Start foreground service
                CallForegroundService.start(context, groupCallId, callType, isIncoming = false)

                // Continue the timer from the 1:1 call duration
                startTimer()

                // Invite new participants if any were selected
                if (newParticipantIds.isNotEmpty()) {
                    val myResolvedName = myName
                    val myResolvedAvatar = myAvatar
                    for (participantId in newParticipantIds) {
                        if (participantId == myUid || participantId == existingPeerId) continue
                        GroupCallSignalingRepository.addParticipant(
                            groupCallId,
                            GroupCallParticipant(
                                userId = participantId,
                                userName = newParticipantNames[participantId] ?: "",
                                userAvatar = newParticipantAvatars[participantId] ?: "",
                                status = "ringing",
                                videoEnabled = false,
                                audioEnabled = true
                            )
                        )
                    }
                    val idsToInvite = newParticipantIds.filter { it != myUid && it != existingPeerId }
                    if (idsToInvite.isNotEmpty()) {
                        sendGroupCallInvitations(
                            groupCallId, callType, idsToInvite,
                            myUid, myResolvedName, myResolvedAvatar
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to group call", e)
            }
        }

        updateUiStates()
        return groupCallId
    }

    /**
     * Sets up ICE forwarding and connection state listeners for an existing
     * peer connection that was migrated from a 1:1 call. Since this connection
     * is already established, we only need the ICE forward (for potential
     * network changes) and track observers.
     */
    private fun setupExistingPeerListeners(
        remoteUid: String,
        client: WebRtcCallClient,
        groupCallId: String,
        localUid: String
    ) {
        val pairKey = GroupCallSignalingRepository.pairKey(localUid, remoteUid)

        // Forward any future local ICE candidates (for network changes or ICE restarts)
        iceForwardJobs[remoteUid] = scope.launch {
            client.onIceCandidate.collect { candidate ->
                scope.launch(Dispatchers.IO) {
                    forwardIceCandidate(groupCallId, pairKey, localUid, candidate)
                }
            }
        }

        // Listen for connection state changes
        connectionJobs[remoteUid] = scope.launch {
            launch {
                client.onConnectionConnected.collect {
                    connectedPeers.add(remoteUid)
                    updateUiStates()
                }
            }
            launch {
                client.onConnectionFailed.collect {
                    Log.w(TAG, "Connection to migrated peer $remoteUid failed")
                    connectedPeers.remove(remoteUid)
                    updateUiStates()
                }
            }
            launch {
                client.remoteVideoTrack.collect { track ->
                    val current = _remoteVideoTracks.value.toMutableMap()
                    if (track != null) {
                        current[remoteUid] = track
                    } else {
                        current.remove(remoteUid)
                    }
                    _remoteVideoTracks.value = current
                    updateUiStates()
                }
            }
            launch {
                client.localVideoTrack.collect { track ->
                    if (peerConnections.keys.firstOrNull() == remoteUid || _localVideoTrack.value == null) {
                        _localVideoTrack.value = track
                    }
                }
            }
        }
    }

    // ── Upgrade 1:1 call to group call (LEGACY — kept for backward compat) ──

    /**
     * @deprecated Use [migrateFromOneToOneCall] for seamless upgrade.
     * This method ends the 1:1 call and creates a new disconnected group call.
     */
    @Deprecated("Use migrateFromOneToOneCall for seamless upgrade", ReplaceWith("migrateFromOneToOneCall(...)"))
    fun upgradeToGroupCall(
        context: Context,
        existingCallId: String,
        existingPeerId: String,
        existingPeerName: String,
        existingPeerAvatar: String,
        newParticipantIds: List<String>,
        newParticipantNames: Map<String, String>,
        newParticipantAvatars: Map<String, String>,
        callType: CallType
    ) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // End the 1:1 call first
        CallManager.endCall(context)

        // Start group call with the existing peer + new participants
        val allParticipantIds = mutableListOf(existingPeerId).apply {
            addAll(newParticipantIds)
        }
        val allNames = mutableMapOf(existingPeerId to existingPeerName).apply {
            putAll(newParticipantNames)
        }
        val allAvatars = mutableMapOf(existingPeerId to existingPeerAvatar).apply {
            putAll(newParticipantAvatars)
        }

        startGroupCall(context, callType, allParticipantIds, allNames, allAvatars)
    }

    // ── Leave group call ─────────────────────────────────────────────

    fun leaveGroupCall(context: Context) {
        if (!teardownInProgress.compareAndSet(false, true)) return

        val groupCallId = _groupCallData.value?.groupCallId
        val myUid = FirebaseAuth.getInstance().currentUser?.uid

        scope.launch {
            try {
                if (groupCallId != null && myUid != null) {
                    GroupCallSignalingRepository.updateParticipantStatus(groupCallId, myUid, "left")

                    // If no connected participants remain, end the call
                    val remaining = GroupCallSignalingRepository.getParticipants(groupCallId)
                        .filter { it.participantStatus() == GroupCallParticipantStatus.CONNECTED && it.userId != myUid }
                    if (remaining.isEmpty()) {
                        GroupCallSignalingRepository.endGroupCall(groupCallId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error leaving group call", e)
            }
            cleanup(context)
        }
    }

    // ── Media controls ───────────────────────────────────────────────

    fun toggleMicrophone() {
        val newState = !_isMicMuted.value
        _isMicMuted.value = newState
        peerConnections.values.forEach { it.setMicrophoneMuted(newState) }
        updateLocalMediaState()
    }

    fun toggleVideo() {
        val newState = !_isVideoEnabled.value
        _isVideoEnabled.value = newState

        // Handle shared capturer/track for group call
        if (sharedResourcesInitialized && sharedLocalVideoTrack != null) {
            sharedLocalVideoTrack?.setEnabled(newState)
            if (newState) {
                try {
                    sharedVideoCapturer?.startCapture(
                        WebRtcCallClient.DEFAULT_CAPTURE_WIDTH,
                        WebRtcCallClient.DEFAULT_CAPTURE_HEIGHT,
                        WebRtcCallClient.DEFAULT_CAPTURE_FPS
                    )
                } catch (_: Exception) {}
            } else {
                try { sharedVideoCapturer?.stopCapture() } catch (_: Exception) {}
            }
            _localVideoTrack.value = if (newState) sharedLocalVideoTrack else null
        }

        peerConnections.values.forEach { client ->
            client.updateVideoNegotiationState(
                localVideoEnabled = newState,
                receiveRemoteVideo = true
            )
        }
        updateLocalMediaState()
    }

    fun toggleSpeaker() {
        audioManager?.toggleSpeaker()
        _isSpeakerOn.value = audioManager?.isSpeakerOn ?: false
    }

    fun switchCamera() {
        // Switch camera on the first peer connection (they share the same capturer)
        peerConnections.values.firstOrNull()?.switchCamera()
    }

    private fun updateLocalMediaState() {
        val groupCallId = _groupCallData.value?.groupCallId ?: return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        scope.launch {
            try {
                GroupCallSignalingRepository.updateParticipantMedia(
                    groupCallId, myUid,
                    videoEnabled = _isVideoEnabled.value,
                    audioEnabled = !_isMicMuted.value
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update media state", e)
            }
        }
    }

    // ── Shared media resource lifecycle ──────────────────────────────

    /**
     * Create the shared EglBase, PeerConnectionFactory, camera capturer,
     * and local audio/video tracks that all group-call peer connections will
     * reuse.  Called on a background thread via [Dispatchers.Default].
     */
    @Synchronized
    private fun initializeSharedMediaResources(context: Context, callType: CallType) {
        if (sharedResourcesInitialized) return
        val appCtx = context.applicationContext

        WebRtcCallClient.ensureFactoryInitialized(appCtx)

        val egl = EglBase.create()
        sharedEglBase = egl

        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
        sharedFactory = factory

        // Shared audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        sharedAudioSource = factory.createAudioSource(audioConstraints)
        sharedLocalAudioTrack = factory.createAudioTrack(
            "group_audio_${UUID.randomUUID()}", sharedAudioSource
        ).apply { setEnabled(true) }

        // Shared video track (video calls only)
        if (callType == CallType.VIDEO) {
            val capturer = createSharedCameraCapturer(appCtx)
            if (capturer != null) {
                sharedVideoCapturer = capturer
                val helper = SurfaceTextureHelper.create("GroupCaptureThread", egl.eglBaseContext)
                sharedSurfaceTextureHelper = helper
                val source = factory.createVideoSource(capturer.isScreencast)
                sharedVideoSource = source
                capturer.initialize(helper, appCtx, source.capturerObserver)
                capturer.startCapture(
                    WebRtcCallClient.DEFAULT_CAPTURE_WIDTH,
                    WebRtcCallClient.DEFAULT_CAPTURE_HEIGHT,
                    WebRtcCallClient.DEFAULT_CAPTURE_FPS
                )
                sharedLocalVideoTrack = factory.createVideoTrack(
                    "group_video_${UUID.randomUUID()}", source
                ).apply { setEnabled(true) }
            }
        }

        _localVideoTrack.value = sharedLocalVideoTrack
        sharedResourcesInitialized = true
    }

    private fun createSharedCameraCapturer(context: Context): CameraVideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                enumerator.createCapturer(name, null)?.let { return it }
            }
        }
        for (name in enumerator.deviceNames) {
            if (enumerator.isBackFacing(name)) {
                enumerator.createCapturer(name, null)?.let { return it }
            }
        }
        return null
    }

    /**
     * Take ownership of an existing [WebRtcCallClient]'s media resources
     * (factory, EGL, camera, tracks) so they can be shared with future peers.
     * The source client is marked as shared so it won't dispose them on teardown.
     */
    private fun extractSharedResourcesFromClient(client: WebRtcCallClient) {
        if (sharedResourcesInitialized) return
        sharedEglBase = client.internalEglBase
        sharedFactory = client.internalFactory
        sharedLocalAudioTrack = client.internalAudioTrack
        sharedAudioSource = client.internalAudioSource
        sharedLocalVideoTrack = client.internalVideoTrack
        sharedVideoSource = client.internalVideoSource
        sharedVideoCapturer = client.internalCapturer
        sharedSurfaceTextureHelper = client.internalSurfaceHelper
        client.markAsSharedResources()
        sharedResourcesInitialized = true
        _localVideoTrack.value = sharedLocalVideoTrack
    }

    private fun disposeSharedResources() {
        if (!sharedResourcesInitialized) return
        try { sharedVideoCapturer?.stopCapture() } catch (_: Exception) {}
        sharedVideoCapturer?.dispose()
        sharedVideoCapturer = null
        sharedLocalVideoTrack?.setEnabled(false)
        sharedLocalVideoTrack?.dispose()
        sharedLocalVideoTrack = null
        sharedLocalAudioTrack?.setEnabled(false)
        sharedLocalAudioTrack?.dispose()
        sharedLocalAudioTrack = null
        sharedVideoSource?.dispose()
        sharedVideoSource = null
        sharedAudioSource?.dispose()
        sharedAudioSource = null
        sharedSurfaceTextureHelper?.dispose()
        sharedSurfaceTextureHelper = null
        sharedFactory?.dispose()
        sharedFactory = null
        sharedEglBase?.release()
        sharedEglBase = null
        sharedResourcesInitialized = false
    }

    // ── Peer connection management ───────────────────────────────────

    private suspend fun establishPeerConnection(
        context: Context,
        groupCallId: String,
        localUid: String,
        remoteUid: String,
        callType: CallType
    ) {
        if (peerConnections.containsKey(remoteUid)) {
            return
        }


        // Ensure shared media resources are ready (heavy init on background thread)
        if (!sharedResourcesInitialized) {
            val existingClient = peerConnections.values.firstOrNull()
            if (existingClient != null) {
                extractSharedResourcesFromClient(existingClient)
            } else {
                withContext(Dispatchers.Default) {
                    initializeSharedMediaResources(context, callType)
                }
            }
        }

        val client = WebRtcCallClient(context, groupCallId, localUid, callType)
        client.initializeShared(
            sharedEglBase!!, sharedFactory!!, sharedLocalAudioTrack!!,
            sharedAudioSource, sharedLocalVideoTrack, sharedVideoSource,
            sharedVideoCapturer, sharedSurfaceTextureHelper
        )
        peerConnections[remoteUid] = client
        processedCandidates[remoteUid] = mutableSetOf()
        peerOfferRevisions[remoteUid] = 0
        peerAnswerRevisions[remoteUid] = 0

        val pairKey = GroupCallSignalingRepository.pairKey(localUid, remoteUid)
        val isOfferer = GroupCallSignalingRepository.isOfferer(localUid, remoteUid)

        // Forward local ICE candidates to Firestore
        iceForwardJobs[remoteUid] = scope.launch {
            client.onIceCandidate.collect { candidate ->
                scope.launch(Dispatchers.IO) {
                    forwardIceCandidate(groupCallId, pairKey, localUid, candidate)
                }
            }
        }

        // Listen for connection state changes
        connectionJobs[remoteUid] = scope.launch {
            launch {
                client.onConnectionConnected.collect {
                    connectedPeers.add(remoteUid)
                    updateUiStates()
                }
            }
            launch {
                client.onConnectionFailed.collect {
                    Log.w(TAG, "Connection to peer $remoteUid failed")
                    connectedPeers.remove(remoteUid)
                    updateUiStates()
                }
            }
            launch {
                client.remoteVideoTrack.collect { track ->
                    val current = _remoteVideoTracks.value.toMutableMap()
                    if (track != null) {
                        current[remoteUid] = track
                    } else {
                        current.remove(remoteUid)
                    }
                    _remoteVideoTracks.value = current
                    updateUiStates()
                }
            }
            launch {
                client.localVideoTrack.collect { track ->
                    // Only update from the first peer connection (they share the camera)
                    if (peerConnections.keys.firstOrNull() == remoteUid || _localVideoTrack.value == null) {
                        _localVideoTrack.value = track
                    }
                }
            }
        }

        // Observe remote ICE candidates
        iceJobs[remoteUid] = scope.launch {
            GroupCallSignalingRepository.observeIceCandidates(groupCallId, pairKey, localUid)
                .collect { candidates ->
                    val processed = processedCandidates[remoteUid] ?: return@collect
                    for (c in candidates) {
                        val key = "${c.sdpMid}_${c.sdpMLineIndex}_${c.candidate.hashCode()}"
                        if (key in processed) continue
                        processed.add(key)
                        client.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate))
                    }
                }
        }

        // Observe signaling document for offer/answer exchange
        signalingJobs[remoteUid] = scope.launch {
            if (isOfferer) {
                // We are the offerer — create and send offer, wait for answer
                try {
                    val offer = client.createOffer()
                    val rev = 1
                    peerOfferRevisions[remoteUid] = rev
                    GroupCallSignalingRepository.setOffer(
                        groupCallId, pairKey, offer.description, localUid, rev
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create offer for $remoteUid", e)
                    return@launch
                }

                // Listen for answer
                GroupCallSignalingRepository.observeSignaling(groupCallId, pairKey)
                    .collect { signaling ->
                        if (signaling == null) return@collect
                        val remoteAnswerRev = signaling.answerRevision
                        val localAnswerRev = peerAnswerRevisions[remoteUid] ?: 0

                        if (signaling.answer.isNotEmpty() && remoteAnswerRev > localAnswerRev) {
                            try {
                                val remoteSdp = SessionDescription(
                                    SessionDescription.Type.ANSWER, signaling.answer
                                )
                                client.setRemoteDescription(remoteSdp)
                                peerAnswerRevisions[remoteUid] = remoteAnswerRev
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to apply answer from $remoteUid", e)
                            }
                        }
                    }
            } else {
                // We are the answerer — wait for offer, then create answer
                GroupCallSignalingRepository.observeSignaling(groupCallId, pairKey)
                    .collect { signaling ->
                        if (signaling == null) return@collect
                        val remoteOfferRev = signaling.offerRevision
                        val localOfferRev = peerOfferRevisions[remoteUid] ?: 0

                        if (signaling.offer.isNotEmpty() && remoteOfferRev > localOfferRev) {
                            try {
                                val remoteSdp = SessionDescription(
                                    SessionDescription.Type.OFFER, signaling.offer
                                )
                                client.setRemoteDescription(remoteSdp)
                                peerOfferRevisions[remoteUid] = remoteOfferRev

                                val answer = client.createAnswer()
                                val answerRev = (peerAnswerRevisions[remoteUid] ?: 0) + 1
                                peerAnswerRevisions[remoteUid] = answerRev
                                GroupCallSignalingRepository.setAnswer(
                                    groupCallId, pairKey, answer.description, answerRev
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to handle offer from $remoteUid", e)
                            }
                        }
                    }
            }
        }
    }

    private fun teardownPeerConnection(remoteUid: String) {

        signalingJobs.remove(remoteUid)?.cancel()
        iceJobs.remove(remoteUid)?.cancel()
        iceForwardJobs.remove(remoteUid)?.cancel()
        connectionJobs.remove(remoteUid)?.cancel()
        processedCandidates.remove(remoteUid)
        peerOfferRevisions.remove(remoteUid)
        peerAnswerRevisions.remove(remoteUid)
        connectedPeers.remove(remoteUid)

        peerConnections.remove(remoteUid)?.dispose()

        val tracks = _remoteVideoTracks.value.toMutableMap()
        tracks.remove(remoteUid)
        _remoteVideoTracks.value = tracks

        updateUiStates()
    }

    // ── Observers ────────────────────────────────────────────────────

    private fun observeGroupCallState(groupCallId: String) {
        callObserverJob?.cancel()
        callObserverJob = scope.launch {
            GroupCallSignalingRepository.observeGroupCall(groupCallId).collect { data ->
                if (data == null) return@collect
                _groupCallData.value = data
                if (data.groupCallState() == GroupCallState.ENDED) {
                    val ctx = appContext ?: return@collect
                    cleanup(ctx)
                }
            }
        }
    }

    private fun observeParticipants(
        groupCallId: String,
        localUid: String,
        callType: CallType
    ) {
        participantObserverJob?.cancel()
        participantObserverJob = scope.launch {
            GroupCallSignalingRepository.observeParticipants(groupCallId).collect { participantList ->
                _participants.value = participantList
                val ctx = appContext ?: return@collect

                // Establish peer connections with newly connected participants
                val connectedParticipants = participantList.filter {
                    it.userId != localUid &&
                        it.participantStatus() == GroupCallParticipantStatus.CONNECTED
                }

                for (participant in connectedParticipants) {
                    if (!peerConnections.containsKey(participant.userId)) {
                        establishPeerConnection(ctx, groupCallId, localUid, participant.userId, callType)
                    }
                }

                // Teardown peer connections for participants who left
                val activeParticipantIds = connectedParticipants.map { it.userId }.toSet()
                val peersToRemove = peerConnections.keys.filter { it !in activeParticipantIds }
                for (peerId in peersToRemove) {
                    teardownPeerConnection(peerId)
                }

                updateUiStates()
            }
        }
    }

    private fun updateUiStates() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val participantList = _participants.value
        val states = participantList
            .filter { it.participantStatus() != GroupCallParticipantStatus.LEFT &&
                      it.participantStatus() != GroupCallParticipantStatus.DECLINED &&
                      it.participantStatus() != GroupCallParticipantStatus.NO_ANSWER }
            .map { p ->
                GroupCallParticipantUiState(
                    userId = p.userId,
                    userName = p.userName,
                    userAvatar = p.userAvatar,
                    videoEnabled = if (p.userId == myUid) _isVideoEnabled.value else p.videoEnabled,
                    audioEnabled = if (p.userId == myUid) !_isMicMuted.value else p.audioEnabled,
                    isConnected = p.userId == myUid || connectedPeers.contains(p.userId),
                    isSelf = p.userId == myUid
                )
            }
        _participantUiStates.value = states
    }

    // ── FCM notifications ────────────────────────────────────────────

    private suspend fun sendGroupCallInvitations(
        groupCallId: String,
        callType: CallType,
        participantIds: List<String>,
        callerUid: String,
        callerName: String,
        callerAvatar: String
    ) {
        val db = FirebaseFirestore.getInstance()
        for (userId in participantIds) {
            if (userId == callerUid) continue
            try {
                // Write a notification trigger document that the Cloud Function will pick up
                // This follows the same pattern as 1:1 calls
                val tokenSnapshot = db.collection("users").document(userId)
                    .get().await()
                val fcmToken = tokenSnapshot.getString("fcmToken") ?: continue

                // Store the invitation as a subcollection document that triggers FCM
                db.collection("groupCallInvitations").add(
                    mapOf(
                        "groupCallId" to groupCallId,
                        "callType" to (if (callType == CallType.VIDEO) "VIDEO" else "VOICE"),
                        "targetUserId" to userId,
                        "targetFcmToken" to fcmToken,
                        "callerUserId" to callerUid,
                        "callerName" to callerName,
                        "callerAvatar" to callerAvatar,
                        "participantCount" to (participantIds.size + 1),
                        "createdAt" to System.currentTimeMillis(),
                        "processed" to false
                    )
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send group call invitation to $userId", e)
            }
        }
    }

    // ── ICE forwarding ───────────────────────────────────────────────

    private suspend fun forwardIceCandidate(
        groupCallId: String,
        pairKey: String,
        localUid: String,
        candidate: IceCandidate
    ) {
        val candidateData = GroupCallIceCandidate(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp,
            fromUserId = localUid
        )
        var lastError: Exception? = null
        repeat(5) { attempt ->
            try {
                GroupCallSignalingRepository.addIceCandidate(groupCallId, pairKey, candidateData)
                return
            } catch (e: Exception) {
                lastError = e
                delay(((attempt + 1) * 500).toLong())
            }
        }
        Log.e(TAG, "Dropping ICE candidate after retries for pair=$pairKey", lastError)
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    private fun cleanup(context: Context) {

        callObserverJob?.cancel()
        callObserverJob = null
        participantObserverJob?.cancel()
        participantObserverJob = null
        timerJob?.cancel()
        timerJob = null

        // Teardown all peer connections
        val peerIds = peerConnections.keys.toList()
        for (peerId in peerIds) {
            teardownPeerConnection(peerId)
        }

        // Dispose shared media resources (factory, camera, tracks)
        disposeSharedResources()

        audioManager?.stop()
        audioManager = null

        _groupCallData.value = null
        _participants.value = emptyList()
        _participantUiStates.value = emptyList()
        _remoteVideoTracks.value = emptyMap()
        _localVideoTrack.value = null
        _callDurationSeconds.value = 0
        _isMicMuted.value = false
        _isVideoEnabled.value = true
        _isSpeakerOn.value = true
        _isInGroupCall.value = false
        appContext = null

        CallForegroundService.stop(context.applicationContext)
        teardownInProgress.set(false)
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

    // ── Utility ──────────────────────────────────────────────────────

    private suspend fun resolveUserName(userId: String): String {
        return try {
            FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .get().await()
                .getString("name") ?: ""
        } catch (e: Exception) { "" }
    }

    private suspend fun resolveUserAvatar(userId: String): String {
        return try {
            FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .get().await()
                .getString("profileImageUrl") ?: ""
        } catch (e: Exception) { "" }
    }

    /** EGL base context for rendering video */
    val eglBaseContext: org.webrtc.EglBase.Context?
        get() = sharedEglBase?.eglBaseContext ?: peerConnections.values.firstOrNull()?.eglBaseContext
}
