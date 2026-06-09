package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lightweight, audio-only WebRTC peer connection for live audio sharing.
 *
 * Unlike [WebRtcCallClient] (full voice/video calls), this class:
 *  - Never creates video sources, tracks, or camera capturers
 *  - Uses low-bitrate Opus audio constraints
 *  - Has a smaller memory footprint and faster setup
 *
 * Roles:
 *  - BROADCASTER: creates a local audio track (microphone) and sends it
 *  - LISTENER: receives the remote audio track and plays it
 */
class LiveAudioPeerClient(
    private val context: Context,
    private val sessionId: String,
    private val localUserId: String,
    private val isBroadcaster: Boolean
) {

    companion object {
        private const val TAG = "LiveAudioPeer"
        @Volatile private var factoryInitialized = false

        @Synchronized
        private fun ensureFactoryInitialized(context: Context) {
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

    // ── State Flows ─────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.IceConnectionState> = _connectionState.asStateFlow()

    private val _onIceCandidate = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 64)
    val onIceCandidate: SharedFlow<IceCandidate> = _onIceCandidate.asSharedFlow()

    private val _onConnectionConnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionConnected: SharedFlow<Unit> = _onConnectionConnected.asSharedFlow()

    private val _onConnectionFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionFailed: SharedFlow<Unit> = _onConnectionFailed.asSharedFlow()

    private val _onConnectionDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onConnectionDisconnected: SharedFlow<Unit> = _onConnectionDisconnected.asSharedFlow()

    // ── WebRTC components ───────────────────────────────────────────

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var sawLocalRelayCandidate = false
    @Volatile private var sawRemoteRelayCandidate = false

    // ── Initialization ──────────────────────────────────────────────

    fun initialize() {
        ensureFactoryInitialized(context)

        // Audio-only factory — skip video encoder/decoder factories entirely
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        createPeerConnection()

        if (isBroadcaster) {
            createLocalAudioTrack()
        }

    }

    private fun createPeerConnection() {
        val rtcConfig = WebRtcIceConfig.createRtcConfiguration()

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
                        _onConnectionFailed.tryEmit(Unit)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
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
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")
    }

    private fun createLocalAudioTrack() {
        // Audio constraints for low-latency, mono, noise-suppressed audio
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack(
            "live_audio_track_$sessionId",
            localAudioSource
        )?.apply {
            setEnabled(true)
        }

        // Add audio track to the peer connection
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("live_audio_stream"))
        }

    }

    // ── SDP Negotiation ─────────────────────────────────────────────

    /** Create an SDP offer (broadcaster side). */
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(RuntimeException("setLocalDescription failed: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(RuntimeException("createOffer failed: $error"))
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** Create an SDP answer (listener side). */
    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(RuntimeException("setLocalDescription failed: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(RuntimeException("createAnswer failed: $error"))
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** Set the remote session description and flush buffered ICE candidates. */
    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine { cont ->
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                // Flush buffered ICE candidates
                synchronized(pendingRemoteIceCandidates) {
                    for (candidate in pendingRemoteIceCandidates) {
                        peerConnection?.addIceCandidate(candidate)
                    }
                    pendingRemoteIceCandidates.clear()
                }
                cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                cont.resumeWithException(RuntimeException("setRemoteDescription failed: $error"))
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /** Add a remote ICE candidate (or buffer if remote description not yet set). */
    fun addIceCandidate(candidate: IceCandidate) {
        if (WebRtcIceConfig.parseCandidate(candidate).type == "relay") {
            sawRemoteRelayCandidate = true
        }
        if (remoteDescriptionSet) {
            WebRtcIceConfig.logRemoteCandidate(TAG, candidate, buffered = false)
            peerConnection?.addIceCandidate(candidate)
        } else {
            WebRtcIceConfig.logRemoteCandidate(TAG, candidate, buffered = true)
            synchronized(pendingRemoteIceCandidates) {
                pendingRemoteIceCandidates.add(candidate)
            }
        }
    }

    // ── Mute / unmute ───────────────────────────────────────────────

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // ── Cleanup ─────────────────────────────────────────────────────

    fun dispose() {
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null

            localAudioSource?.dispose()
            localAudioSource = null

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            sawLocalRelayCandidate = false
            sawRemoteRelayCandidate = false

            // Do NOT dispose factory — it's shared process-wide
        } catch (e: Exception) {
            Log.w(TAG, "Error during dispose", e)
        }
    }
}
