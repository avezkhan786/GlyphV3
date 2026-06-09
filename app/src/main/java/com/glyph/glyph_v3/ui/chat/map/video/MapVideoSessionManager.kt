package com.glyph.glyph_v3.ui.chat.map.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.webrtc.WebRtcIceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "MapVideoSessionMgr"

@Volatile
private var peerConnectionFactoryInitialized = false

@Synchronized
private fun ensurePeerConnectionFactoryInitialized(context: Context) {
    if (peerConnectionFactoryInitialized) return
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
    )
    peerConnectionFactoryInitialized = true
}

private enum class PendingSdpType { OFFER, ANSWER }

private const val CAMERA_INVITE_DECLINED_NOTICE_MS = 1_500L
private const val CAMERA_INVITE_TTL_MS = 15_000L

enum class MapVideoConnectionState {
    AVATAR,
    WAITING,
    CONNECTING,
    LIVE,
    ERROR
}

enum class MapVideoMode {
    OFF,
    MANUAL_TWO_WAY,
    AUTO_SEND_ONLY,
    AUTO_SEND_AND_AUTO_RECEIVE,
    AUTO_SEND_AND_MANUAL_RECEIVE,
    AUTO_REPLY_TWO_WAY,
    AUTO_RECEIVE_ONLY,
    MANUAL_RECEIVE_ONLY
}

private data class PendingIceCandidate(
    val sessionId: String,
    val candidate: IceCandidate
)

private data class CameraCapturerSelection(
    val capturer: CameraVideoCapturer,
    val isFrontFacing: Boolean
)

data class MapVideoIncomingCameraInvite(
    val requestId: String,
    val senderName: String,
    val createdAt: Long
)

@Stable
class MapVideoSessionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val chatId: String,
    private val myUserId: String,
    private val otherUserId: String,
    private val allowOutgoingAudio: Boolean = true
) {
    private val appContext = context.applicationContext
    private val isOfferer = myUserId < otherUserId
    private val availableCameraCount = resolveAvailableCameraCount()

    var currentMode by mutableStateOf(MapVideoMode.OFF)
        private set

    val isVideoModeEnabled: Boolean
        get() = currentMode != MapVideoMode.OFF

    var isOtherUserVideoEnabled by mutableStateOf(false)
        private set

    var connectionState by mutableStateOf(MapVideoConnectionState.AVATAR)
        private set

    var hasRemoteVideo by mutableStateOf(false)
        private set

    var isFrontCameraActive by mutableStateOf(true)
        private set

    var isAudioMuted by mutableStateOf(false)
        private set

    var incomingCameraInvite by mutableStateOf<MapVideoIncomingCameraInvite?>(null)
        private set

    var isInviteDeclinedNoticeVisible by mutableStateOf(false)
        private set

    val canSwitchCamera: Boolean
        get() = availableCameraCount > 1

    private val isPublishingLocal: Boolean
        get() = currentMode == MapVideoMode.MANUAL_TWO_WAY ||
            currentMode == MapVideoMode.AUTO_SEND_ONLY ||
            currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ||
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE ||
            currentMode == MapVideoMode.AUTO_REPLY_TWO_WAY

    private val isReadyToReceiveLocal: Boolean
        get() = currentMode == MapVideoMode.MANUAL_TWO_WAY ||
            currentMode == MapVideoMode.AUTO_SEND_ONLY ||
            currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ||
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE ||
            currentMode == MapVideoMode.AUTO_REPLY_TWO_WAY ||
            currentMode == MapVideoMode.AUTO_RECEIVE_ONLY ||
            currentMode == MapVideoMode.MANUAL_RECEIVE_ONLY

    private val needsDedicatedReceiveTransceivers: Boolean
        get() = currentMode == MapVideoMode.AUTO_SEND_ONLY ||
            currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ||
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE ||
            currentMode == MapVideoMode.AUTO_REPLY_TWO_WAY ||
            (!isPublishingLocal && isReadyToReceiveLocal)

    val isManualRemoteViewEnabled: Boolean
        get() = currentMode == MapVideoMode.MANUAL_TWO_WAY ||
            currentMode == MapVideoMode.MANUAL_RECEIVE_ONLY ||
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE

    val isAutoSending: Boolean
        get() = currentMode == MapVideoMode.AUTO_SEND_ONLY ||
            currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ||
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE

    val isViewingRemoteCamera: Boolean
        get() = currentMode != MapVideoMode.OFF && currentMode != MapVideoMode.AUTO_SEND_ONLY

    val hasActiveRemoteShare: Boolean
        get() = remoteParticipantState.publishing || remoteShareExpected || hasRemoteVideo

    private var engineActive = false
    private var remoteWasEverEnabled = false
    private var isAutoReceivingFromBroadcast = false
    private var remoteShareExpected = false
    private var currentSessionId: String? = null
    private var remoteParticipantState: MapVideoParticipantState = MapVideoParticipantState()
    private var pendingRemoteSdp: Pair<PendingSdpType, MapVideoSdpMessage>? = null
    private val pendingRemoteCandidates = mutableListOf<PendingIceCandidate>()
    private val consumedCandidateKeys = mutableSetOf<String>()
    private var sawLocalRelayCandidate = false
    private var sawRemoteRelayCandidate = false
    private val localSinks = linkedSetOf<VideoSink>()
    private val remoteSinks = linkedSetOf<VideoSink>()
    private var receiveTransceiversAdded = false

    private val eglBase: EglBase = EglBase.create()
    private val audioManager: AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var previousAudioMode: Int? = null
    private var previousLegacySpeakerphoneOn: Boolean? = null
    private var previousCommunicationDeviceId: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var isAudioDeviceCallbackRegistered: Boolean = false
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && engineActive) {
            audioManager?.let { manager -> reapplyPreferredAudioRoute(manager) }
        }
    }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!engineActive) return
            audioManager?.let { manager ->
                val routed = reapplyPreferredAudioRoute(manager)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!engineActive) return
            audioManager?.let { manager ->
                val routed = reapplyPreferredAudioRoute(manager)
            }
        }
    }

    private var participantJob: Job? = null
    private var offerJob: Job? = null
    private var answerJob: Job? = null
    private var candidateJob: Job? = null
    private var commandJob: Job? = null
    private var inviteCommandJob: Job? = null
    private var inviteResponseJob: Job? = null
    private var inviteDismissJob: Job? = null
    private var inviteDeclinedNoticeJob: Job? = null
    private var createOfferJob: Job? = null
    private var acceptOfferJob: Job? = null
    private var acceptAnswerJob: Job? = null
    private var recoveryJob: Job? = null
    private var blockStatusJob: Job? = null
    private var broadcastObserverJob: Job? = null
    private var negotiationEpoch: Int = 0
    private var lastProcessedCameraInviteId: String? = null
    private var pendingOutgoingCameraInviteRequestId: String? = null
    private var lastProcessedCameraInviteResponseId: String? = null

    val shouldShowMarkerVideoBubble: Boolean
        get() = currentMode != MapVideoMode.OFF

    val eglBaseContext: EglBase.Context
        get() = eglBase.eglBaseContext

    fun toggleEnabled() {
        setEnabled(!isVideoModeEnabled)
    }

    fun setEnabled(enabled: Boolean) {
        setMode(if (enabled) MapVideoMode.MANUAL_TWO_WAY else MapVideoMode.OFF)
    }

    fun sendCameraInvite(senderName: String) {
        if (otherUserId.isBlank()) return
        if (pendingOutgoingCameraInviteRequestId != null) return
        if (remoteParticipantState.publishing) return

        val requestId = UUID.randomUUID().toString()
        pendingOutgoingCameraInviteRequestId = requestId
        clearInviteDeclinedNotice()
        Log.d(TAG, "Sending camera invite requestId=$requestId to other user")

        MapVideoSignalingRepository.sendCameraInviteCommand(
            chatId = chatId,
            targetUserId = otherUserId,
            requestId = requestId,
            senderUserId = myUserId,
            senderName = senderName.ifBlank { "Someone" }
        )
    }

    private fun shouldAutoAcceptIncomingInvite(): Boolean {
        // Keep explicit confirmation for newly requested camera access.
        // Transient receive-only state after a broadcast stops should not swallow
        // the next invite before the receiver sees it.
        return isPublishingLocal
    }

    private fun autoAcceptCameraInvite(command: MapVideoCameraInviteCommand) {
        setAutoSendEnabled(true)
        startViewingRemoteCamera(automatic = true)

        Log.d(TAG, "Auto-accepting camera invite requestId=${command.requestId}")
        MapVideoSignalingRepository.sendCameraInviteAcceptedCommand(
            chatId = chatId,
            targetUserId = otherUserId,
            requestId = command.requestId,
            responderUserId = myUserId,
            responderName = ""
        )
        MapVideoSignalingRepository.clearCameraInviteCommand(chatId, myUserId)
        dismissIncomingCameraInvite()
    }

    fun startViewingRemoteCamera(automatic: Boolean = true) {
        when (currentMode) {
            MapVideoMode.OFF -> setMode(MapVideoMode.AUTO_RECEIVE_ONLY)
            MapVideoMode.AUTO_SEND_ONLY -> {
                setMode(
                    if (automatic) {
                        MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE
                    } else {
                        MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE
                    }
                )
            }
            MapVideoMode.MANUAL_RECEIVE_ONLY -> {
                if (automatic) {
                    setMode(MapVideoMode.AUTO_RECEIVE_ONLY)
                }
            }
            MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE -> {
                if (automatic) {
                    setMode(MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE)
                }
            }
            else -> Unit
        }
    }

    fun setRemoteShareExpected(active: Boolean) {
        if (remoteShareExpected == active) return
        remoteShareExpected = active
        isOtherUserVideoEnabled = remoteParticipantState.publishing || remoteShareExpected

        if (!engineActive) {
            return
        }

        if (!active && !remoteParticipantState.enabled && !hasRemoteVideo) {
            resetWaitingState()
            return
        }

        if (active && isVideoModeEnabled && canEstablishMediaWithRemoteState()) {
            connectionState = MapVideoConnectionState.CONNECTING
            if (isOfferer && peerConnection == null && currentSessionId == null && createOfferJob?.isActive != true) {
                launchOfferIfReady()
            } else {
                acceptPendingOfferIfPossible()
                acceptPendingAnswerIfPossible()
                flushPendingRemoteCandidates()
            }
        }
    }

    fun stopViewingRemoteCamera() {
        when (currentMode) {
            MapVideoMode.AUTO_RECEIVE_ONLY,
            MapVideoMode.MANUAL_RECEIVE_ONLY,
            MapVideoMode.MANUAL_TWO_WAY,
            MapVideoMode.AUTO_REPLY_TWO_WAY -> setMode(MapVideoMode.OFF)

            MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE,
            MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE -> setMode(MapVideoMode.AUTO_SEND_ONLY)

            else -> Unit
        }
    }

    fun acceptIncomingCameraInvite(responderName: String) {
        val invite = incomingCameraInvite ?: return
        if (invite.requestId.isBlank()) {
            dismissIncomingCameraInvite()
            return
        }

        acceptCameraInviteByRequestId(invite.requestId, responderName)
    }

    fun acceptCameraInviteByRequestId(requestId: String, responderName: String) {
        if (requestId.isBlank()) return

        when (currentMode) {
            MapVideoMode.OFF,
            MapVideoMode.AUTO_RECEIVE_ONLY,
            MapVideoMode.MANUAL_RECEIVE_ONLY,
            MapVideoMode.AUTO_SEND_ONLY,
            MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE -> setMode(MapVideoMode.MANUAL_TWO_WAY)

            else -> Unit
        }

        Log.d(TAG, "Accepting incoming camera invite requestId=$requestId")
        MapVideoSignalingRepository.sendCameraInviteAcceptedCommand(
            chatId = chatId,
            targetUserId = otherUserId,
            requestId = requestId,
            responderUserId = myUserId,
            responderName = responderName.ifBlank { "Someone" }
        )
        MapVideoSignalingRepository.clearCameraInviteCommand(chatId, myUserId)
        lastProcessedCameraInviteId = requestId
        dismissIncomingCameraInvite()
    }

    fun declineIncomingCameraInvite(responderName: String = "") {
        val invite = incomingCameraInvite ?: return
        if (invite.requestId.isBlank()) {
            dismissIncomingCameraInvite()
            return
        }

        declineCameraInviteByRequestId(invite.requestId, responderName)
    }

    fun declineCameraInviteByRequestId(requestId: String, responderName: String = "") {
        if (requestId.isBlank()) return

        Log.d(TAG, "Declining incoming camera invite requestId=$requestId")
        MapVideoSignalingRepository.sendCameraInviteDeclinedCommand(
            chatId = chatId,
            targetUserId = otherUserId,
            requestId = requestId,
            responderUserId = myUserId,
            responderName = responderName.ifBlank { "Someone" }
        )
        MapVideoSignalingRepository.clearCameraInviteCommand(chatId, myUserId)
        lastProcessedCameraInviteId = requestId
        dismissIncomingCameraInvite()
    }

    fun dismissIncomingCameraInvite(markHandled: Boolean = true) {
        if (markHandled) {
            lastProcessedCameraInviteId = incomingCameraInvite?.requestId ?: lastProcessedCameraInviteId
        }
        inviteDismissJob?.cancel()
        inviteDismissJob = null
        incomingCameraInvite = null
        clearCameraInviteNotification()
    }

    private fun clearCameraInviteNotification() {
        val notificationTag = "camera_invite_$chatId"
        val notificationId = notificationTag.hashCode()
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager
        notificationManager?.cancel(notificationTag, notificationId)
    }

    fun setAutoSendEnabled(enabled: Boolean) {
        when {
            enabled && currentMode == MapVideoMode.MANUAL_RECEIVE_ONLY ->
                setMode(MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE)
            enabled && currentMode == MapVideoMode.AUTO_RECEIVE_ONLY ->
                setMode(MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE)
            enabled -> setMode(MapVideoMode.AUTO_SEND_ONLY)
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE ->
                setMode(MapVideoMode.MANUAL_RECEIVE_ONLY)
            currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ->
                setMode(MapVideoMode.AUTO_RECEIVE_ONLY)
            currentMode == MapVideoMode.AUTO_SEND_ONLY -> setMode(MapVideoMode.OFF)
        }
    }

    fun setAutoReceiveEnabled(enabled: Boolean) {
        when {
            enabled && currentMode == MapVideoMode.OFF -> setMode(MapVideoMode.AUTO_RECEIVE_ONLY)
            enabled && currentMode == MapVideoMode.AUTO_SEND_ONLY ->
                setMode(MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE)
            !enabled && currentMode == MapVideoMode.AUTO_RECEIVE_ONLY -> setMode(MapVideoMode.OFF)
            !enabled && currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE ->
                setMode(MapVideoMode.AUTO_SEND_ONLY)
        }
    }

    fun setAutoReplyTwoWayEnabled(enabled: Boolean) {
        when {
            enabled && currentMode == MapVideoMode.OFF -> setMode(MapVideoMode.AUTO_REPLY_TWO_WAY)
            enabled && currentMode == MapVideoMode.AUTO_RECEIVE_ONLY -> setMode(MapVideoMode.AUTO_REPLY_TWO_WAY)
            !enabled && currentMode == MapVideoMode.AUTO_REPLY_TWO_WAY -> setMode(MapVideoMode.OFF)
        }
    }

    fun setManualReceiveEnabled(enabled: Boolean) {
        when {
            enabled && currentMode == MapVideoMode.AUTO_SEND_ONLY ->
                setMode(MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE)
            enabled -> setMode(MapVideoMode.MANUAL_RECEIVE_ONLY)
            currentMode == MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE ->
                setMode(MapVideoMode.AUTO_SEND_ONLY)
            currentMode == MapVideoMode.MANUAL_RECEIVE_ONLY -> setMode(MapVideoMode.OFF)
        }
    }

    fun onResume() {
        ensureInviteObserver()
        if (isVideoModeEnabled && !engineActive) {
            startEngine()
        }
    }

    fun onPause() {
        if (engineActive) {
            stopEngine(resetVisualState = false)
        }
    }

    fun onDestroy() {
        blockStatusJob?.cancel()
        inviteCommandJob?.cancel()
        inviteResponseJob?.cancel()
        inviteDismissJob?.cancel()
        inviteDeclinedNoticeJob?.cancel()
        broadcastObserverJob?.cancel()
        stopEngine(resetVisualState = true)
        localVideoTrack?.let { track ->
            localSinks.forEach { sink -> track.removeSink(sink) }
        }
        remoteVideoTrack?.let { track ->
            remoteSinks.forEach { sink -> track.removeSink(sink) }
        }
        localSinks.clear()
        remoteSinks.clear()
        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        remoteAudioTrack = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun attachRemoteRenderer(renderer: VideoSink) {
        attachRemoteSink(renderer)
    }

    fun detachRemoteRenderer(renderer: VideoSink) {
        detachRemoteSink(renderer)
    }

    fun attachLocalRenderer(renderer: VideoSink) {
        attachLocalSink(renderer)
    }

    fun detachLocalRenderer(renderer: VideoSink) {
        detachLocalSink(renderer)
    }

    fun switchCamera() {
        if (!canSwitchCamera) return
        MapVideoSignalingRepository.sendCameraSwitchCommand(chatId, otherUserId)
    }

    fun switchLocalCamera() {
        if (!canSwitchCamera) return
        performLocalCameraSwitch()
    }

    fun toggleAudioMuted() {
        updateAudioMuted(!isAudioMuted)
    }

    fun updateAudioMuted(muted: Boolean) {
        if (isAudioMuted == muted) return
        isAudioMuted = muted
        localAudioTrack?.setEnabled(!muted)
        remoteAudioTrack?.setEnabled(!muted)
    }

    private fun setMode(newMode: MapVideoMode) {
        if (newMode == currentMode) return
        val previousMode = currentMode
        currentMode = newMode
        if (newMode != MapVideoMode.OFF) {
            dismissIncomingCameraInvite()
            clearInviteDeclinedNotice()
        }
        if (newMode == MapVideoMode.OFF) {
            cancelOutstandingCameraInvite()
            stopEngine(
                resetVisualState = true,
                clearSharedSession = modePublishesVideo(previousMode)
            )
            return
        }
        if (!engineActive) {
            startEngine()
            return
        }
        reconfigureActiveMode(previousMode)
    }

    private fun reconfigureActiveMode(previousMode: MapVideoMode) {
        if (!engineActive) return

        if (isPublishingLocal && !hasCameraPermission()) {
            connectionState = MapVideoConnectionState.ERROR
            currentMode = MapVideoMode.OFF
            stopEngine(resetVisualState = true)
            return
        }

        val wasPublishing = modePublishesVideo(previousMode)

        publishLocalParticipantState()

        if (!isPublishingLocal && wasPublishing) {
            stopLocalCapture()
        }

        invalidateNegotiationState()
        clearNegotiationArtifacts(resetPendingSdp = false)

        if (!canEstablishMediaWithRemoteState(remoteParticipantState)) {
            connectionState = MapVideoConnectionState.WAITING
            return
        }

        connectionState = MapVideoConnectionState.CONNECTING
        if (isOfferer) {
            launchOfferIfReady()
        } else {
            acceptPendingOfferIfPossible()
            acceptPendingAnswerIfPossible()
            flushPendingRemoteCandidates()
        }
    }

    private fun performLocalCameraSwitch() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                scope.launch {
                    isFrontCameraActive = isFrontCamera
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "Map video remote-triggered camera switch failed: $errorDescription")
            }
        })
    }

    private fun attachLocalSink(sink: VideoSink) {
        localSinks += sink
        localVideoTrack?.addSink(sink)
    }

    private fun detachLocalSink(sink: VideoSink) {
        localVideoTrack?.removeSink(sink)
        localSinks -= sink
    }

    private fun attachRemoteSink(sink: VideoSink) {
        remoteSinks += sink
        remoteVideoTrack?.addSink(sink)
    }

    private fun detachRemoteSink(sink: VideoSink) {
        remoteVideoTrack?.removeSink(sink)
        remoteSinks -= sink
    }

    private fun startEngine() {
        if (engineActive) return
        ensureInviteObserver()
        if (BlockRepository.getBlockStatus(otherUserId).isBlocked) {
            currentMode = MapVideoMode.OFF
            connectionState = MapVideoConnectionState.AVATAR
            return
        }
        if (isPublishingLocal && !hasCameraPermission()) {
            connectionState = MapVideoConnectionState.ERROR
            currentMode = MapVideoMode.OFF
            return
        }
        engineActive = true
        com.glyph.glyph_v3.data.service.ActiveChatManager.setMapVideoActive(chatId)
        ensureBlockObserver()
        startAudioRouting()
        connectionState = MapVideoConnectionState.WAITING
        publishLocalParticipantState()
        startObservers()
    }

    private fun stopEngine(resetVisualState: Boolean, clearSharedSession: Boolean = true) {
        engineActive = false
        remoteWasEverEnabled = false
        isAutoReceivingFromBroadcast = false
        com.glyph.glyph_v3.data.service.ActiveChatManager.setMapVideoActive(null)
        participantJob?.cancel()
        offerJob?.cancel()
        answerJob?.cancel()
        candidateJob?.cancel()
        commandJob?.cancel()
        invalidateNegotiationState()
        participantJob = null
        offerJob = null
        answerJob = null
        candidateJob = null
        commandJob = null

        MapVideoSignalingRepository.setParticipantState(chatId, myUserId, MapVideoParticipantState())
        MapVideoSignalingRepository.clearIncomingCandidates(chatId, myUserId)
        if (clearSharedSession && isOfferer) {
            MapVideoSignalingRepository.clearSession(chatId)
        }

        closePeerConnection()
        stopLocalCapture()
        stopAudioRouting()
        pendingRemoteSdp = null
        pendingRemoteCandidates.clear()
        consumedCandidateKeys.clear()
        hasRemoteVideo = false
        isOtherUserVideoEnabled = remoteShareExpected
        remoteParticipantState = MapVideoParticipantState()
        currentSessionId = null
        connectionState = if (resetVisualState || currentMode == MapVideoMode.OFF) {
            MapVideoConnectionState.AVATAR
        } else {
            MapVideoConnectionState.WAITING
        }
    }

    private fun modePublishesVideo(mode: MapVideoMode): Boolean {
        return when (mode) {
            MapVideoMode.MANUAL_TWO_WAY,
            MapVideoMode.AUTO_SEND_ONLY,
            MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE,
            MapVideoMode.AUTO_SEND_AND_MANUAL_RECEIVE,
            MapVideoMode.AUTO_REPLY_TWO_WAY -> true
            else -> false
        }
    }

    private fun ensureBlockObserver() {
        if (blockStatusJob?.isActive == true) return
        blockStatusJob = scope.launch {
            BlockRepository.observeBlockStatus(otherUserId).collectLatest { status ->
                if (!status.isBlocked) return@collectLatest
                currentMode = MapVideoMode.OFF
                stopEngine(resetVisualState = true)
            }
        }
    }

    private fun publishLocalParticipantState() {
        MapVideoSignalingRepository.setParticipantState(
            chatId = chatId,
            userId = myUserId,
            state = MapVideoParticipantState(
                enabled = isVideoModeEnabled,
                publishing = isPublishingLocal,
                readyToReceive = isReadyToReceiveLocal
            )
        )
    }

    private fun startAudioRouting() {
        audioManager?.let { manager ->
            if (previousAudioMode == null) previousAudioMode = manager.mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && previousCommunicationDeviceId == null) {
                previousCommunicationDeviceId = manager.communicationDevice?.id
            } else if (previousLegacySpeakerphoneOn == null) {
                previousLegacySpeakerphoneOn = getLegacySpeakerphoneState(manager)
            }
            requestAudioFocus(manager)
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            registerAudioDeviceCallback(manager)
            val routedToSpeaker = reapplyPreferredAudioRoute(manager)
        }
    }

    private fun stopAudioRouting() {
        audioManager?.let { manager ->
            unregisterAudioDeviceCallback(manager)
            restoreCommunicationDevice(manager)
            previousLegacySpeakerphoneOn?.let { wasSpeakerphoneOn ->
                setLegacySpeakerphoneState(manager, wasSpeakerphoneOn)
            }
            previousAudioMode?.let { manager.mode = it }
            abandonAudioFocus(manager)
        }
        previousCommunicationDeviceId = null
        previousLegacySpeakerphoneOn = null
        previousAudioMode = null
    }

    private fun reapplyPreferredAudioRoute(manager: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeAudioToBuiltInSpeaker(manager)
        } else {
            setLegacySpeakerphoneState(manager, true)
            true
        }
    }

    private fun routeAudioToBuiltInSpeaker(manager: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        val speakerDevice = manager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false

        val currentDeviceId = manager.communicationDevice?.id
        if (currentDeviceId == speakerDevice.id) {
            return true
        }

        manager.clearCommunicationDevice()
        return manager.setCommunicationDevice(speakerDevice)
    }

    private fun restoreCommunicationDevice(manager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        val previousDeviceId = previousCommunicationDeviceId
        if (previousDeviceId == null) {
            manager.clearCommunicationDevice()
            return
        }

        val restored = manager.availableCommunicationDevices.firstOrNull { device ->
            device.id == previousDeviceId
        }?.let { previousDevice ->
            manager.setCommunicationDevice(previousDevice)
        } ?: false

        if (!restored) {
            manager.clearCommunicationDevice()
        }
    }

    private fun requestAudioFocus(manager: AudioManager) {
        if (hasAudioFocus) return

        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
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
                .also { builtRequest -> audioFocusRequest = builtRequest }
            manager.requestAudioFocus(request)
        } else {
            requestLegacyAudioFocus(manager)
        }

        hasAudioFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            Log.w(TAG, "Map video audio focus was not granted: result=$focusResult")
        }
    }

    private fun abandonAudioFocus(manager: AudioManager) {
        if (!hasAudioFocus && audioFocusRequest == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request -> manager.abandonAudioFocusRequest(request) }
        } else {
            abandonLegacyAudioFocus(manager)
        }

        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun registerAudioDeviceCallback(manager: AudioManager) {
        if (isAudioDeviceCallbackRegistered) return
        manager.registerAudioDeviceCallback(audioDeviceCallback, null)
        isAudioDeviceCallbackRegistered = true
    }

    private fun unregisterAudioDeviceCallback(manager: AudioManager) {
        if (!isAudioDeviceCallbackRegistered) return
        manager.unregisterAudioDeviceCallback(audioDeviceCallback)
        isAudioDeviceCallbackRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun getLegacySpeakerphoneState(manager: AudioManager): Boolean = manager.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphoneState(manager: AudioManager, enabled: Boolean) {
        manager.isSpeakerphoneOn = enabled
    }

    @Suppress("DEPRECATION")
    private fun requestLegacyAudioFocus(manager: AudioManager): Int {
        return manager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    @Suppress("DEPRECATION")
    private fun abandonLegacyAudioFocus(manager: AudioManager) {
        manager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun startObservers() {
        participantJob?.cancel()
        offerJob?.cancel()
        answerJob?.cancel()
        candidateJob?.cancel()
        commandJob?.cancel()
        cancelNegotiationJobs()

        participantJob = scope.launch {
            MapVideoSignalingRepository.observeParticipantState(chatId, otherUserId).collectLatest { state ->
                remoteParticipantState = state
                isOtherUserVideoEnabled = state.publishing || remoteShareExpected
                if (state.publishing) {
                    pendingOutgoingCameraInviteRequestId = null
                    clearInviteDeclinedNotice()
                }
                if (state.enabled || remoteShareExpected) remoteWasEverEnabled = true

                if (!engineActive) return@collectLatest

                if (!state.enabled) {
                    if (remoteShareExpected) {
                        if (isOfferer && peerConnection == null && currentSessionId == null && createOfferJob?.isActive != true) {
                            launchOfferIfReady()
                        } else if (connectionState != MapVideoConnectionState.LIVE) {
                            connectionState = MapVideoConnectionState.CONNECTING
                        }
                        acceptPendingOfferIfPossible()
                        acceptPendingAnswerIfPossible()
                        flushPendingRemoteCandidates()
                        return@collectLatest
                    }

                    if (remoteWasEverEnabled) {
                        // Remote was previously enabled and has now stopped.
                        // If we are still publishing locally, tear down only the stale peer
                        // connection and enter WAITING — do NOT change the video mode, because
                        // changing the mode (e.g. MANUAL_TWO_WAY → OFF) would kill A's own
                        // camera broadcast. The remote user stopping their side must never
                        // affect the local user's broadcast or mode.
                        remoteWasEverEnabled = false
                        if (isPublishingLocal) {
                            resetWaitingState()
                        } else {
                            setEnabled(false)
                        }
                    } else {
                        resetWaitingState()
                    }
                    return@collectLatest
                }

                if (!canEstablishMediaWithRemoteState()) {
                    resetWaitingState()
                    return@collectLatest
                }

                if (isOfferer && peerConnection == null && currentSessionId == null && createOfferJob?.isActive != true) {
                    launchOfferIfReady()
                } else if (connectionState != MapVideoConnectionState.LIVE) {
                    connectionState = MapVideoConnectionState.CONNECTING
                }

                acceptPendingOfferIfPossible()
                acceptPendingAnswerIfPossible()
                flushPendingRemoteCandidates()
            }
        }

        offerJob = scope.launch {
            MapVideoSignalingRepository.observeOffer(chatId).collectLatest { offer ->
                if (!engineActive || isOfferer || offer == null || offer.fromUserId != otherUserId) return@collectLatest
                pendingRemoteSdp = PendingSdpType.OFFER to offer
                acceptPendingOfferIfPossible()
            }
        }

        answerJob = scope.launch {
            MapVideoSignalingRepository.observeAnswer(chatId).collectLatest { answer ->
                if (!engineActive || !isOfferer || answer == null || answer.fromUserId != otherUserId) return@collectLatest
                pendingRemoteSdp = PendingSdpType.ANSWER to answer
                acceptPendingAnswerIfPossible()
            }
        }

        candidateJob = scope.launch {
            MapVideoSignalingRepository.observeIceCandidates(chatId, myUserId).collectLatest { message ->
                if (!engineActive || message.fromUserId != otherUserId || message.key in consumedCandidateKeys) return@collectLatest
                consumedCandidateKeys += message.key
                val details = WebRtcIceConfig.parseCandidate(message.sdp)
                if (details.type == "relay") {
                    sawRemoteRelayCandidate = true
                }
                val iceCandidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.sdp)
                WebRtcIceConfig.logRemoteCandidate(TAG, iceCandidate, buffered = true)
                pendingRemoteCandidates += PendingIceCandidate(
                    sessionId = message.sessionId,
                    candidate = iceCandidate
                )
                flushPendingRemoteCandidates()
            }
        }

        commandJob = scope.launch {
            var lastCommandTimestamp: Long? = null
            MapVideoSignalingRepository.observeCameraSwitchCommand(chatId, myUserId).collectLatest { timestamp ->
                if (!engineActive) return@collectLatest
                if (lastCommandTimestamp == null) {
                    lastCommandTimestamp = timestamp
                    return@collectLatest
                }
                if (timestamp > (lastCommandTimestamp ?: 0L)) {
                    lastCommandTimestamp = timestamp
                    performLocalCameraSwitch()
                }
            }
        }
    }

    private fun ensureInviteObserver() {
        if (inviteCommandJob?.isActive != true) {
            inviteCommandJob = scope.launch {
                MapVideoSignalingRepository.observeCameraInviteCommand(chatId, myUserId).collectLatest { command ->
                    handleIncomingCameraInvite(command)
                }
            }
        }
        if (inviteResponseJob?.isActive != true) {
            inviteResponseJob = scope.launch {
                MapVideoSignalingRepository.observeCameraInviteResponseCommand(chatId, myUserId).collectLatest { command ->
                    handleCameraInviteResponse(command)
                }
            }
        }
        if (broadcastObserverJob?.isActive != true) {
            broadcastObserverJob = scope.launch {
                MapVideoSignalingRepository.observeCameraBroadcastCommand(chatId, myUserId).collectLatest { command ->
                    handleCameraBroadcastCommand(command)
                }
            }
        }
    }

    private fun handleIncomingCameraInvite(command: MapVideoCameraInviteCommand?) {
        if (command == null) {
            dismissIncomingCameraInvite(markHandled = false)
            return
        }
        if (command.requestId.isBlank()) return
        val now = System.currentTimeMillis()
        if (command.createdAt > 0L && now - command.createdAt >= CAMERA_INVITE_TTL_MS) {
            lastProcessedCameraInviteId = command.requestId
            MapVideoSignalingRepository.clearCameraInviteCommand(chatId, myUserId)
            dismissIncomingCameraInvite(markHandled = false)
            return
        }
        if (command.requestId == lastProcessedCameraInviteId) return
        if (incomingCameraInvite?.requestId == command.requestId) return
        if (shouldAutoAcceptIncomingInvite()) {
            lastProcessedCameraInviteId = command.requestId
            autoAcceptCameraInvite(command)
            return
        }

        lastProcessedCameraInviteId = command.requestId

        incomingCameraInvite = MapVideoIncomingCameraInvite(
            requestId = command.requestId,
            senderName = command.senderName.ifBlank { "Someone" },
            createdAt = command.createdAt
        )
        inviteDismissJob?.cancel()
        clearCameraInviteNotification()
        val dismissDelayMs = if (command.createdAt > 0L) {
            (CAMERA_INVITE_TTL_MS - (now - command.createdAt)).coerceAtLeast(0L)
        } else {
            CAMERA_INVITE_TTL_MS
        }
        inviteDismissJob = scope.launch {
            delay(dismissDelayMs)
            if (incomingCameraInvite?.requestId != command.requestId) return@launch
            lastProcessedCameraInviteId = command.requestId
            MapVideoSignalingRepository.clearCameraInviteCommand(chatId, myUserId)
            dismissIncomingCameraInvite(markHandled = false)
        }
    }

    private fun handleCameraInviteResponse(command: MapVideoCameraInviteResponseCommand?) {
        if (command == null) return
        if (command.requestId.isBlank()) return
        if (command.requestId == lastProcessedCameraInviteResponseId) return

        lastProcessedCameraInviteResponseId = command.requestId
        val matchesPendingInvite = command.requestId == pendingOutgoingCameraInviteRequestId

        // A response arrived for our pending invite — clean up RTDB nodes regardless of accept or decline.
        if (matchesPendingInvite) {
            pendingOutgoingCameraInviteRequestId = null
            MapVideoSignalingRepository.clearCameraInviteCommand(chatId, otherUserId)
            MapVideoSignalingRepository.clearCameraInviteResponseCommand(chatId, myUserId)
        }

        if (command.response == "accepted" && matchesPendingInvite) {
            startViewingRemoteCamera(automatic = true)
            return
        }

        if (command.response != "declined") return
        if (!matchesPendingInvite) return

        Log.d(TAG, "Received camera invite decline for requestId=${command.requestId}")
        showInviteDeclinedNotice()
        if (!hasActiveRemoteShare) {
            stopViewingRemoteCamera()
        }
    }

    /**
     * Called when the sender's live-location + camera broadcast starts or stops.
     * On start: automatically enter [MapVideoMode.AUTO_RECEIVE_ONLY] so the video overlay
     *   opens without requiring any user action.
     * On stop: tear down auto-receive only if we were the ones who started it (don't interrupt
     *   a session the user upgraded manually).
     */
    private fun handleCameraBroadcastCommand(command: MapVideoCameraBroadcastCommand?) {
        if (command == null) {
            // Broadcast stopped — stop auto-receive only if we auto-started it
            setRemoteShareExpected(false)
            if (isAutoReceivingFromBroadcast) {
                isAutoReceivingFromBroadcast = false
                when (currentMode) {
                    MapVideoMode.AUTO_RECEIVE_ONLY -> setAutoReceiveEnabled(false)
                    MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE -> setAutoReceiveEnabled(false)
                    else -> { /* user has a different mode — leave it alone */ }
                }
            }
            return
        }

        // Already receiving or in a user-initiated session — nothing to do
        Log.d(TAG, "cameraBroadcast received — auto-starting AUTO_RECEIVE_ONLY")
        isAutoReceivingFromBroadcast = true
        setRemoteShareExpected(true)
        startViewingRemoteCamera(automatic = true)
    }

    private fun showInviteDeclinedNotice() {
        isInviteDeclinedNoticeVisible = true
        inviteDeclinedNoticeJob?.cancel()
        inviteDeclinedNoticeJob = scope.launch {
            delay(CAMERA_INVITE_DECLINED_NOTICE_MS)
            clearInviteDeclinedNotice()
        }
    }

    private fun clearInviteDeclinedNotice() {
        inviteDeclinedNoticeJob?.cancel()
        inviteDeclinedNoticeJob = null
        isInviteDeclinedNoticeVisible = false
    }

    private fun cancelOutstandingCameraInvite() {
        val requestId = pendingOutgoingCameraInviteRequestId ?: return
        pendingOutgoingCameraInviteRequestId = null
        Log.d(TAG, "Cancelling pending camera invite requestId=$requestId")
        MapVideoSignalingRepository.clearCameraInviteCommand(chatId, otherUserId)
    }

    private fun resetWaitingState() {
        invalidateNegotiationState()
        closePeerConnection()
        pendingRemoteSdp = null
        pendingRemoteCandidates.clear()
        consumedCandidateKeys.clear()
        sawLocalRelayCandidate = false
        sawRemoteRelayCandidate = false
        currentSessionId = null
        hasRemoteVideo = false
        connectionState = MapVideoConnectionState.WAITING
    }

    private fun canEstablishMediaWithRemoteState(remote: MapVideoParticipantState = remoteParticipantState): Boolean {
        val remoteCanReceive = remote.readyToReceive || remoteShareExpected || hasRemoteVideo
        val remotePublishing = remote.publishing || remoteShareExpected || hasRemoteVideo
        return (isPublishingLocal && remoteCanReceive) ||
            (isReadyToReceiveLocal && remotePublishing)
    }

    private fun cancelNegotiationJobs() {
        createOfferJob?.cancel()
        acceptOfferJob?.cancel()
        acceptAnswerJob?.cancel()
        recoveryJob?.cancel()
        createOfferJob = null
        acceptOfferJob = null
        acceptAnswerJob = null
        recoveryJob = null
    }

    private fun invalidateNegotiationState() {
        negotiationEpoch += 1
        cancelNegotiationJobs()
    }

    private fun isNegotiationStillValid(epoch: Int, pc: PeerConnection, sessionId: String? = currentSessionId): Boolean {
        return epoch == negotiationEpoch && engineActive && peerConnection === pc && currentSessionId == sessionId
    }

    private fun isTrackCallbackStillValid(epoch: Int, sessionId: String?): Boolean {
        return epoch == negotiationEpoch && engineActive && currentSessionId == sessionId
    }

    private fun launchOfferIfReady() {
        if (!engineActive || !isVideoModeEnabled || !isOfferer || !canEstablishMediaWithRemoteState()) return
        val epoch = negotiationEpoch
        createOfferJob = scope.launch {
            try {
                MapVideoSignalingRepository.clearSession(chatId)
                consumedCandidateKeys.clear()
                pendingRemoteCandidates.clear()
                val sessionId = UUID.randomUUID().toString()
                currentSessionId = sessionId
                val pc = ensurePeerConnection()
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                val offer = pc.createOfferSuspend()
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                pc.setLocalDescriptionSuspend(offer)
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                MapVideoSignalingRepository.writeOffer(
                    chatId,
                    MapVideoSdpMessage(
                        sessionId = sessionId,
                        fromUserId = myUserId,
                        targetUserId = otherUserId,
                        type = offer.type.canonicalForm(),
                        sdp = offer.description.orEmpty()
                    )
                )
                connectionState = MapVideoConnectionState.CONNECTING
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create map video offer", t)
                handleNegotiationFailure("create-offer", t)
            } finally {
                createOfferJob = null
            }
        }
    }

    private fun acceptPendingOfferIfPossible() {
        val pending = pendingRemoteSdp ?: return
        if (pending.first != PendingSdpType.OFFER || !engineActive || !canEstablishMediaWithRemoteState()) return
        val epoch = negotiationEpoch
        acceptOfferJob?.cancel()
        acceptOfferJob = scope.launch {
            try {
                val offer = pending.second
                val sessionId = offer.sessionId
                if (shouldResetPeerConnectionForIncomingOffer(sessionId)) {
                    clearNegotiationArtifacts(resetPendingSdp = false)
                }
                currentSessionId = sessionId
                val pc = ensurePeerConnection()
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                flushPendingRemoteCandidates()
                val answer = pc.createAnswerSuspend()
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                pc.setLocalDescriptionSuspend(answer)
                if (!isNegotiationStillValid(epoch, pc, sessionId)) return@launch
                MapVideoSignalingRepository.writeAnswer(
                    chatId,
                    MapVideoSdpMessage(
                        sessionId = sessionId,
                        fromUserId = myUserId,
                        targetUserId = otherUserId,
                        type = answer.type.canonicalForm(),
                        sdp = answer.description.orEmpty()
                    )
                )
                pendingRemoteSdp = null
                connectionState = MapVideoConnectionState.CONNECTING
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to accept remote map video offer", t)
                handleNegotiationFailure("accept-offer", t)
            } finally {
                acceptOfferJob = null
            }
        }
    }

    private fun shouldResetPeerConnectionForIncomingOffer(sessionId: String): Boolean {
        val pc = peerConnection ?: return false
        if (currentSessionId != null && currentSessionId != sessionId) {
            Log.w(TAG, "Resetting responder peer connection for new session offer old=$currentSessionId new=$sessionId")
            return true
        }

        val hasAppliedSdp = pc.localDescription != null || pc.remoteDescription != null
        if (!hasAppliedSdp) {
            return false
        }

        Log.w(TAG, "Resetting responder peer connection before applying fresh remote offer for session=$sessionId")
        return true
    }

    private fun acceptPendingAnswerIfPossible() {
        val pending = pendingRemoteSdp ?: return
        if (pending.first != PendingSdpType.ANSWER || !engineActive || !isOfferer) return
        val answer = pending.second
        if (currentSessionId.isNullOrBlank() || answer.sessionId != currentSessionId) return

        val epoch = negotiationEpoch
        acceptAnswerJob?.cancel()
        acceptAnswerJob = scope.launch {
            try {
                val pc = peerConnection ?: return@launch
                if (!isNegotiationStillValid(epoch, pc, answer.sessionId)) return@launch
                if (pc.remoteDescription == null) {
                    pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
                }
                if (!isNegotiationStillValid(epoch, pc, answer.sessionId)) return@launch
                pendingRemoteSdp = null
                flushPendingRemoteCandidates()
                if (connectionState != MapVideoConnectionState.LIVE) {
                    connectionState = MapVideoConnectionState.CONNECTING
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to accept remote map video answer", t)
                handleNegotiationFailure("accept-answer", t)
            } finally {
                acceptAnswerJob = null
            }
        }
    }

    private fun ensurePeerConnection(): PeerConnection {
        peerConnection?.let { return it }
        val factory = ensurePeerConnectionFactory()
        if (isPublishingLocal) {
            ensureLocalMediaTracks(factory)
        }

        val config = WebRtcIceConfig.createRtcConfiguration()
        WebRtcIceConfig.logConfiguredServers(TAG)

        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state=$state")
                if (state == PeerConnection.IceGatheringState.COMPLETE && !sawLocalRelayCandidate) {
                    Log.e(TAG, "Map video gathered no relay candidates. TURN allocation likely failed due to reachability, auth, or quota.")
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() {
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val sessionId = currentSessionId
                val epoch = negotiationEpoch
                when (val track = transceiver?.receiver?.track()) {
                    is VideoTrack -> {
                        scope.launch { setRemoteVideoTrack(track, epoch, sessionId) }
                    }
                    is AudioTrack -> scope.launch { setRemoteAudioTrack(track, epoch, sessionId) }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val sessionId = currentSessionId
                val epoch = negotiationEpoch
                when (val track = receiver?.track()) {
                    is VideoTrack -> {
                        scope.launch { setRemoteVideoTrack(track, epoch, sessionId) }
                    }
                    is AudioTrack -> scope.launch { setRemoteAudioTrack(track, epoch, sessionId) }
                    else -> {
                        mediaStreams?.firstOrNull()?.videoTracks?.firstOrNull()?.let { videoTrack ->
                            scope.launch { setRemoteVideoTrack(videoTrack, epoch, sessionId) }
                        }
                        mediaStreams?.firstOrNull()?.audioTracks?.firstOrNull()?.let { audioTrack ->
                            scope.launch { setRemoteAudioTrack(audioTrack, epoch, sessionId) }
                        }
                    }
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                val sessionId = currentSessionId ?: return
                val safeCandidate = candidate ?: return
                val details = WebRtcIceConfig.parseCandidate(safeCandidate)
                if (details.type == "relay") {
                    sawLocalRelayCandidate = true
                }
                WebRtcIceConfig.logLocalCandidate(TAG, safeCandidate)
                MapVideoSignalingRepository.sendIceCandidate(
                    chatId = chatId,
                    targetUserId = otherUserId,
                    message = MapVideoIceCandidateMessage(
                        key = "",
                        sessionId = sessionId,
                        fromUserId = myUserId,
                        targetUserId = otherUserId,
                        sdpMid = safeCandidate.sdpMid.orEmpty(),
                        sdpMLineIndex = safeCandidate.sdpMLineIndex,
                        sdp = safeCandidate.sdp
                    )
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                scope.launch {
                    WebRtcIceConfig.logIceState(TAG, state, sawLocalRelayCandidate, sawRemoteRelayCandidate)
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            connectionState = if (hasRemoteVideo) {
                                MapVideoConnectionState.LIVE
                            } else {
                                MapVideoConnectionState.CONNECTING
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.CHECKING -> {
                            if (engineActive) connectionState = MapVideoConnectionState.CONNECTING
                            if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                                scheduleNegotiationRecovery("ice-disconnected", 3_000L)
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "Map video NAT traversal failed; no viable ICE candidate pair")
                            connectionState = MapVideoConnectionState.ERROR
                            scheduleNegotiationRecovery("ice-failed", 500L)
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            if (engineActive) connectionState = MapVideoConnectionState.WAITING
                        }
                        else -> Unit
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                scope.launch {
                    stream?.videoTracks?.firstOrNull()?.let { track -> setRemoteVideoTrack(track) }
                    stream?.audioTracks?.firstOrNull()?.let { track -> setRemoteAudioTrack(track) }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                scope.launch {
                    setRemoteVideoTrack(null)
                    setRemoteAudioTrack(null)
                }
            }
        }) ?: error("PeerConnection creation returned null")

        peerConnection = pc
        if (isPublishingLocal) {
            localVideoTrack?.let { track -> pc.addTrack(track, listOf("map_video_stream")) }
            localAudioTrack?.let { track -> pc.addTrack(track, listOf("map_video_stream")) }
        }
        if (needsDedicatedReceiveTransceivers) {
            addReceiveOnlyTransceivers(pc)
        }
        return pc
    }

    private fun addReceiveOnlyTransceivers(pc: PeerConnection) {
        if (receiveTransceiversAdded) return
        val receiveOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receiveOnly)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receiveOnly)
        receiveTransceiversAdded = true
    }

    private fun ensurePeerConnectionFactory(): PeerConnectionFactory {
        peerConnectionFactory?.let { return it }
        ensurePeerConnectionFactoryInitialized(appContext)

        val options = PeerConnectionFactory.Options()
        val factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory
        return factory
    }

    private fun ensureLocalMediaTracks(factory: PeerConnectionFactory) {
        if (localVideoTrack == null) {
            val capturerSelection = createVideoCapturer() ?: error("No camera capturer available")
            val capturer = capturerSelection.capturer
            val helper = SurfaceTextureHelper.create("MapVideoCaptureThread", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(320, 320, 15)

            val track = factory.createVideoTrack("map_video_track_$myUserId", source)
            track.setEnabled(true)
            localSinks.forEach { sink -> track.addSink(sink) }

            videoCapturer = capturer
            isFrontCameraActive = capturerSelection.isFrontFacing
            surfaceTextureHelper = helper
            localVideoSource = source
            localVideoTrack = track
        }

        if (allowOutgoingAudio && localAudioTrack == null && hasMicrophonePermission()) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val source = factory.createAudioSource(constraints)
            val track = factory.createAudioTrack("map_audio_track_$myUserId", source)
            track.setEnabled(!isAudioMuted)
            localAudioSource = source
            localAudioTrack = track
        } else if (allowOutgoingAudio && localAudioTrack == null) {
            Log.w(TAG, "Microphone permission missing; map video will start without outgoing audio")
        }
    }

    private fun createVideoCapturer(): CameraCapturerSelection? {
        val camera2Enumerator = Camera2Enumerator(appContext)
        createCapturerWithEnumerator(camera2Enumerator)?.let { return it }

        val camera1Enumerator = Camera1Enumerator(true)
        return createCapturerWithEnumerator(camera1Enumerator)
    }

    private fun createCapturerWithEnumerator(enumerator: org.webrtc.CameraEnumerator): CameraCapturerSelection? {
        val preferredCamera = enumerator.deviceNames
            .firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null

        val capturer = enumerator.createCapturer(preferredCamera, null) as? CameraVideoCapturer ?: return null
        return CameraCapturerSelection(
            capturer = capturer,
            isFrontFacing = enumerator.isFrontFacing(preferredCamera)
        )
    }

    private fun resolveAvailableCameraCount(): Int {
        val camera2Count = runCatching { Camera2Enumerator(appContext).deviceNames.size }.getOrDefault(0)
        if (camera2Count > 0) return camera2Count
        return runCatching { Camera1Enumerator(true).deviceNames.size }.getOrDefault(0)
    }

    private fun closePeerConnection() {
        remoteVideoTrack?.let { track ->
            remoteSinks.forEach { sink -> runCatching { track.removeSink(sink) } }
        }
        remoteVideoTrack = null
        remoteAudioTrack = null
        hasRemoteVideo = false
        receiveTransceiversAdded = false
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun clearNegotiationArtifacts(resetPendingSdp: Boolean) {
        closePeerConnection()
        currentSessionId = null
        if (resetPendingSdp) {
            pendingRemoteSdp = null
        }
        pendingRemoteCandidates.clear()
        consumedCandidateKeys.clear()
        sawLocalRelayCandidate = false
        sawRemoteRelayCandidate = false
    }

    private fun handleNegotiationFailure(stage: String, error: Throwable) {
        Log.e(TAG, "Map video negotiation failed at $stage", error)
        connectionState = MapVideoConnectionState.ERROR
        clearNegotiationArtifacts(resetPendingSdp = stage != "accept-offer")
        scheduleNegotiationRecovery("negotiation-$stage", 750L)
    }

    private fun scheduleNegotiationRecovery(reason: String, delayMs: Long) {
        if (!engineActive || !isVideoModeEnabled || !canEstablishMediaWithRemoteState()) {
            return
        }
        if (recoveryJob?.isActive == true) {
            return
        }
        recoveryJob = scope.launch(Dispatchers.Main) {
            Log.w(TAG, "Scheduling map video recovery in ${delayMs}ms due to $reason")
            delay(delayMs)
            if (!engineActive || !isVideoModeEnabled || !canEstablishMediaWithRemoteState()) {
                return@launch
            }

            invalidateNegotiationState()
            clearNegotiationArtifacts(resetPendingSdp = false)
            connectionState = MapVideoConnectionState.CONNECTING

            if (isOfferer) {
                launchOfferIfReady()
            } else {
                acceptPendingOfferIfPossible()
            }
        }
    }

    private fun stopLocalCapture() {
        localVideoTrack?.let { track ->
            localSinks.forEach { sink -> track.removeSink(sink) }
        }
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
    }

    private suspend fun setRemoteVideoTrack(
        track: VideoTrack?,
        epoch: Int = negotiationEpoch,
        sessionId: String? = currentSessionId
    ) {
        if (!isTrackCallbackStillValid(epoch, sessionId)) {
            return
        }
        remoteVideoTrack?.let { existing ->
            remoteSinks.forEach { sink -> runCatching { existing.removeSink(sink) } }
        }
        remoteVideoTrack = track
        hasRemoteVideo = track != null
        track?.let { remote ->
            val attachResult = runCatching {
                remoteSinks.forEach { sink -> remote.addSink(sink) }
            }
            attachResult.exceptionOrNull()?.let { error ->
                Log.w(TAG, "Ignoring disposed or stale remote video track during sink attach", error)
            }
            val attachedSuccessfully = attachResult.isSuccess
            if (!attachedSuccessfully) {
                remoteVideoTrack = null
                hasRemoteVideo = false
                if (engineActive) {
                    connectionState = if (isOtherUserVideoEnabled) {
                        MapVideoConnectionState.CONNECTING
                    } else {
                        MapVideoConnectionState.WAITING
                    }
                }
                return
            }
            connectionState = MapVideoConnectionState.LIVE
        } ?: run {
            if (engineActive) {
                connectionState = if (isOtherUserVideoEnabled) {
                    MapVideoConnectionState.CONNECTING
                } else {
                    MapVideoConnectionState.WAITING
                }
            }
        }
    }

    private suspend fun setRemoteAudioTrack(
        track: AudioTrack?,
        epoch: Int = negotiationEpoch,
        sessionId: String? = currentSessionId
    ) {
        if (!isTrackCallbackStillValid(epoch, sessionId)) {
            return
        }
        remoteAudioTrack = track
        runCatching { track?.setEnabled(!isAudioMuted) }
            .onFailure { error ->
                Log.w(TAG, "Ignoring disposed or stale remote audio track", error)
                remoteAudioTrack = null
            }
        if (track != null && engineActive) {
            audioManager?.let { manager ->
                val routed = reapplyPreferredAudioRoute(manager)
            }
        }
    }

    private fun flushPendingRemoteCandidates() {
        val sessionId = currentSessionId ?: return
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null) return

        val iterator = pendingRemoteCandidates.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (pending.sessionId == sessionId) {
                val details = WebRtcIceConfig.parseCandidate(pending.candidate)
                val added = pc.addIceCandidate(pending.candidate)
                iterator.remove()
            }
        }
    }
}

private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                val sdp = sessionDescription
                if (sdp != null) continuation.resume(sdp)
                else continuation.resumeWithException(IllegalStateException("Offer SDP was null"))
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException(error ?: "Failed to create offer"))
            }

            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                val sdp = sessionDescription
                if (sdp != null) continuation.resume(sdp)
                else continuation.resumeWithException(IllegalStateException("Answer SDP was null"))
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException(error ?: "Failed to create answer"))
            }

            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { continuation ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException(error ?: "Failed to set local description"))
            }
        }, description)
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { continuation ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                continuation.resumeWithException(IllegalStateException(error ?: "Failed to set remote description"))
            }
        }, description)
    }
