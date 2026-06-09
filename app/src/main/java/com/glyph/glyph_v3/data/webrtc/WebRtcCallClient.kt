package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.glyph.glyph_v3.data.models.CallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC peer connection manager for voice/video calls.
 * Wraps the WebRTC API and handles ICE, SDP, and media tracks.
 */
class WebRtcCallClient(
    private val context: Context,
    private val callId: String,
    private val localUserId: String,
    initialCallType: CallType
) {

    companion object {
        private const val TAG = "WebRtcCallClient"

        // Default capture: 640x480 at 24fps — sufficient for mobile video calls
        // and conserves bandwidth. The AdaptiveVideoController will scale up to
        // 720p on strong networks or down to 360p on weak ones.
        const val DEFAULT_CAPTURE_WIDTH = 640
        const val DEFAULT_CAPTURE_HEIGHT = 480
        const val DEFAULT_CAPTURE_FPS = 24

        // PeerConnectionFactory.initialize() MUST only be called once per process.
        // Calling it again (e.g. on a second call) causes native JNI crashes.
        @Volatile private var factoryInitialized = false

        @Synchronized
        internal fun ensureFactoryInitialized(context: Context) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State
    private val _connectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.IceConnectionState> = _connectionState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _remoteAudioTrack = MutableStateFlow<AudioTrack?>(null)
    val remoteAudioTrack: StateFlow<AudioTrack?> = _remoteAudioTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCameraFlow: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _onIceCandidate = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 64)
    val onIceCandidate: SharedFlow<IceCandidate> = _onIceCandidate.asSharedFlow()

    private val _onConnectionFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionFailed: SharedFlow<Unit> = _onConnectionFailed.asSharedFlow()

    private val _onConnectionConnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionConnected: SharedFlow<Unit> = _onConnectionConnected.asSharedFlow()

    // Emitted when ICE enters DISCONNECTED (transient, may self-recover)
    private val _onConnectionDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionDisconnected: SharedFlow<Unit> = _onConnectionDisconnected.asSharedFlow()

    private val _onRenegotiationNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onRenegotiationNeeded: SharedFlow<Unit> = _onRenegotiationNeeded.asSharedFlow()

    // ICE candidate queue — candidates that arrive before setRemoteDescription is called
    // are buffered here and flushed once the remote description is set.
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var sawLocalRelayCandidate = false
    @Volatile private var sawRemoteRelayCandidate = false

    // WebRTC components
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var _localVideoTrackInternal: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var _localAudioTrackInternal: AudioTrack? = null
    private var localVideoRequested = initialCallType == CallType.VIDEO
    private var receiveRemoteVideoRequested = initialCallType == CallType.VIDEO
    private var isFrontCamera = true
        set(value) {
            field = value
            _isFrontCamera.value = value
        }

    // Shared resources mode (for group calls) — when true, dispose() won't
    // release factory, EGL, camera, or local tracks since GroupCallManager owns them.
    private var isSharedResources = false

    // Adaptive video support
    val networkQualityMonitor = NetworkQualityMonitor(context)
    val adaptiveVideoController = AdaptiveVideoController()

    val eglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    // ── Initialization ───────────────────────────────────────────────

    fun initialize() {
        eglBase = EglBase.create()
        WebRtcIceConfig.logConfiguredServers(TAG)

        // Guard: initialize only once per process to prevent JNI crash on repeated calls
        ensureFactoryInitialized(context)

        // Hardware-accelerated encoding: DefaultVideoEncoderFactory prefers HW codecs
        // (MediaCodec H.264/VP8) and falls back to software only if unavailable.
        // The (enableIntelVp8Encoder=true, enableH264HighProfile=true) flags ensure
        // maximum hardware acceleration across devices.
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        createPeerConnection()
        createLocalTracks()

    }

    /**
     * Initialize using shared media resources for group calls.
     * Creates only a PeerConnection and reuses the shared camera, tracks,
     * factory, and EGL context — preventing multiple camera openings and
     * hardware codec exhaustion when multiple group call peers exist.
     */
    fun initializeShared(
        sharedEglBase: EglBase,
        sharedFactory: PeerConnectionFactory,
        sharedAudioTrack: AudioTrack,
        sharedAudioSource: AudioSource?,
        sharedVideoTrack: VideoTrack?,
        sharedVideoSource: VideoSource?,
        sharedCapturer: CameraVideoCapturer?,
        sharedSurfaceHelper: SurfaceTextureHelper?
    ) {
        isSharedResources = true
        eglBase = sharedEglBase
        peerConnectionFactory = sharedFactory
        _localAudioTrackInternal = sharedAudioTrack
        localAudioSource = sharedAudioSource
        _localVideoTrackInternal = sharedVideoTrack
        localVideoSource = sharedVideoSource
        videoCapturer = sharedCapturer
        surfaceTextureHelper = sharedSurfaceHelper

        if (sharedVideoTrack != null) {
            _localVideoTrack.value = sharedVideoTrack
        }

        createPeerConnection()

        val pc = peerConnection ?: return
        pc.addTrack(sharedAudioTrack)
        if (sharedVideoTrack != null && localVideoRequested) {
            pc.addTrack(sharedVideoTrack)
        }

    }

    // Internal accessors for GroupCallManager to extract shared resources during migration
    internal val internalEglBase get() = eglBase
    internal val internalFactory get() = peerConnectionFactory
    internal val internalAudioTrack get() = _localAudioTrackInternal
    internal val internalAudioSource get() = localAudioSource
    internal val internalVideoTrack get() = _localVideoTrackInternal
    internal val internalVideoSource get() = localVideoSource
    internal val internalCapturer get() = videoCapturer
    internal val internalSurfaceHelper get() = surfaceTextureHelper
    internal fun markAsSharedResources() { isSharedResources = true }

    private fun createPeerConnection() {
        val rtcConfig = WebRtcIceConfig.createRtcConfiguration().apply {
            // NACK and FEC for packet loss recovery are enabled by default in
            // WebRTC when using VP8/H.264. The jitter buffer adapts automatically.
            // DTLS is enabled by default for security.
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (WebRtcIceConfig.parseCandidate(candidate).type == "relay") {
                    sawLocalRelayCandidate = true
                }
                WebRtcIceConfig.logLocalCandidate(TAG, candidate)
                _onIceCandidate.tryEmit(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                WebRtcIceConfig.logIceState(TAG, newState, sawLocalRelayCandidate, sawRemoteRelayCandidate)
                _connectionState.value = newState
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _onConnectionConnected.tryEmit(Unit)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // ICE has permanently given up — signal failure for recovery/teardown
                        Log.w(TAG, "ICE FAILED — signalling failure")
                        _onConnectionFailed.tryEmit(Unit)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // Transient loss (radio handoff, screen-off, brief congestion).
                        // GATHER_CONTINUALLY will re-gather and ICE will self-recover in most cases.
                        // Do NOT end the call here — just notify so CallManager can start a watchdog.
                        Log.w(TAG, "ICE DISCONNECTED — waiting for self-recovery")
                        _onConnectionDisconnected.tryEmit(Unit)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state=$newState")
                if (newState == PeerConnection.IceGatheringState.COMPLETE && !sawLocalRelayCandidate) {
                    Log.w(TAG, "ICE gathering completed without relay candidates; TURN allocation may be unavailable")
                }
            }
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    _remoteVideoTrack.value = track
                }
                stream?.audioTracks?.firstOrNull()?.let { track ->
                    _remoteAudioTrack.value = track
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                _remoteVideoTrack.value = null
                _remoteAudioTrack.value = null
            }

            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {
                _onRenegotiationNeeded.tryEmit(Unit)
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> {
                            _remoteVideoTrack.value = track
                        }
                        is AudioTrack -> {
                            _remoteAudioTrack.value = track
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            }
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    private fun createLocalTracks() {
        val factory = peerConnectionFactory ?: return
        val pc = peerConnection ?: return

        // Audio track (always)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        _localAudioTrackInternal = factory.createAudioTrack("audio_${UUID.randomUUID()}", localAudioSource)
        _localAudioTrackInternal?.setEnabled(true)
        pc.addTrack(_localAudioTrackInternal!!)

        // Video track is optional and may be added later during renegotiation.
        if (localVideoRequested) {
            createVideoTrack(factory, pc)
        }
    }

    private fun createVideoTrack(factory: PeerConnectionFactory, pc: PeerConnection) {
        videoCapturer = createCameraCapturer()
        if (videoCapturer == null) {
            Log.e(TAG, "No camera available")
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        // Start at the default resolution (640x480 @ 24fps) instead of 720p/30fps.
        // The AdaptiveVideoController will scale up on strong networks.
        videoCapturer!!.startCapture(DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT, DEFAULT_CAPTURE_FPS)

        _localVideoTrackInternal = factory.createVideoTrack("video_${UUID.randomUUID()}", localVideoSource)
        _localVideoTrackInternal?.setEnabled(true)
        _localVideoTrack.value = _localVideoTrackInternal
        pc.addTrack(_localVideoTrackInternal!!)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }

        // Try front camera first
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) {
                    isFrontCamera = true
                    return capturer
                }
            }
        }
        // Fallback to back camera
        for (name in enumerator.deviceNames) {
            if (enumerator.isBackFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) {
                    isFrontCamera = false
                    return capturer
                }
            }
        }
        return null
    }

    // ── SDP Operations ───────────────────────────────────────────────

    suspend fun createOffer(restartIce: Boolean = false): SessionDescription = suspendCancellableCoroutine { cont ->
        val shouldReceiveVideo = receiveRemoteVideoRequested || localVideoRequested
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (shouldReceiveVideo) "true" else "false"))
            if (restartIce) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                        cont.resumeWithException(Exception("Set local desc failed: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
                cont.resumeWithException(Exception("Create offer failed: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val shouldReceiveVideo = receiveRemoteVideoRequested || localVideoRequested
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (shouldReceiveVideo) "true" else "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(Exception("Set local desc failed: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(Exception("Create answer failed: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine { cont ->
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                // Flush any ICE candidates that arrived before the remote description was set.
                // This is critical on the caller side: callee candidates can arrive from Firestore
                // before setRemoteDescription(answer) completes. Without this flush, they would be
                // silently dropped by WebRTC and the connection would never establish.
                synchronized(pendingRemoteIceCandidates) {
                    if (pendingRemoteIceCandidates.isNotEmpty()) {
                        pendingRemoteIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                        pendingRemoteIceCandidates.clear()
                    }
                }
                cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                cont.resumeWithException(Exception("Set remote desc failed: $error"))
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (WebRtcIceConfig.parseCandidate(candidate).type == "relay") {
            sawRemoteRelayCandidate = true
        }
        if (remoteDescriptionSet) {
            WebRtcIceConfig.logRemoteCandidate(TAG, candidate, buffered = false)
            peerConnection?.addIceCandidate(candidate)
        } else {
            // Buffer candidate — remote description not set yet.
            // These will be flushed inside setRemoteDescription() on success.
            WebRtcIceConfig.logRemoteCandidate(TAG, candidate, buffered = true)
            synchronized(pendingRemoteIceCandidates) {
                pendingRemoteIceCandidates.add(candidate)
            }
        }
    }

    /**
     * Trigger an ICE restart. Used to recover from FAILED or prolonged DISCONNECTED states.
     * This re-gathers candidates using fresh ICE credentials. The initiator must re-negotiate
     * (create a new offer with iceRestart=true) for the restart to fully complete, but calling
     * this first kicks the gathering process immediately.
     */
    fun restartIce() {
        val pc = peerConnection ?: return
        pc.restartIce()
    }

    // ── Media Controls ───────────────────────────────────────────────

    fun setMicrophoneMuted(muted: Boolean) {
        _localAudioTrackInternal?.setEnabled(!muted)
    }

    fun updateVideoNegotiationState(localVideoEnabled: Boolean, receiveRemoteVideo: Boolean) {
        localVideoRequested = localVideoEnabled
        receiveRemoteVideoRequested = receiveRemoteVideo

        if (isSharedResources) {
            // In shared mode, capturer lifecycle is managed by GroupCallManager
            return
        }

        if (localVideoEnabled) {
            ensureLocalVideoTrack()
        } else {
            _localVideoTrackInternal?.setEnabled(false)
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        }
    }

    fun setVideoEnabled(enabled: Boolean) {
        updateVideoNegotiationState(
            localVideoEnabled = enabled,
            receiveRemoteVideo = receiveRemoteVideoRequested
        )
    }

    /**
     * Switch capture resolution between HD (1280×720 @ 30fps) and SD (640×480 @ 24fps).
     * Only called on explicit user action — never automatically — to avoid visible camera restarts.
     */
    fun setHdMode(enabled: Boolean) {
        val (w, h, fps) = if (enabled) {
            Triple(1280, 720, 30)
        } else {
            Triple(DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT, DEFAULT_CAPTURE_FPS)
        }
        try {
            videoCapturer?.changeCaptureFormat(w, h, fps)
        } catch (e: Exception) {
            Log.w(TAG, "changeCaptureFormat failed", e)
        }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) {
                isFrontCamera = front
            }
            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "Camera switch error: $error")
            }
        })
    }

    fun isFrontCamera(): Boolean = isFrontCamera

    private fun ensureLocalVideoTrack() {
        val factory = peerConnectionFactory ?: return
        val pc = peerConnection ?: return

        if (_localVideoTrackInternal != null) {
            _localVideoTrackInternal?.setEnabled(true)
            try {
                videoCapturer?.startCapture(DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT, DEFAULT_CAPTURE_FPS)
            } catch (_: Exception) {}
            _localVideoTrack.value = _localVideoTrackInternal
            return
        }

        createVideoTrack(factory, pc)
    }

    /**
     * Start adaptive quality monitoring. Call this after the PeerConnection
     * transitions to CONNECTED state.
     */
    fun startAdaptiveQuality() {
        val pc = peerConnection ?: return
        networkQualityMonitor.start(pc)
        adaptiveVideoController.start(
            qualityFlow = networkQualityMonitor.quality,
            pc = pc
        )
    }

    /**
     * Notify the adaptive controller that the app is in background/foreground.
     */
    fun setAppInBackground(background: Boolean) {
        adaptiveVideoController.setBackground(background, networkQualityMonitor.quality.value)
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun dispose() {

        // Stop adaptive quality monitoring first
        adaptiveVideoController.stop()
        networkQualityMonitor.stop()

        _remoteVideoTrack.value = null
        _remoteAudioTrack.value = null
        _connectionState.value = PeerConnection.IceConnectionState.CLOSED

        if (!isSharedResources) {
            // Only dispose media resources if we own them
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            videoCapturer?.dispose()
            _localVideoTrackInternal?.setEnabled(false)
            _localVideoTrackInternal?.dispose()
            _localAudioTrackInternal?.setEnabled(false)
            _localAudioTrackInternal?.dispose()
            localVideoSource?.dispose()
            localAudioSource?.dispose()
            surfaceTextureHelper?.dispose()
        }

        // Null out references regardless of ownership
        videoCapturer = null
        _localVideoTrackInternal = null
        _localVideoTrack.value = null
        _localAudioTrackInternal = null
        localVideoSource = null
        localAudioSource = null
        surfaceTextureHelper = null

        synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.clear()
        }
        remoteDescriptionSet = false
        sawLocalRelayCandidate = false
        sawRemoteRelayCandidate = false

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        if (!isSharedResources) {
            peerConnectionFactory?.dispose()
            eglBase?.release()
        }
        peerConnectionFactory = null
        eglBase = null

        scope.cancel()
    }
}
