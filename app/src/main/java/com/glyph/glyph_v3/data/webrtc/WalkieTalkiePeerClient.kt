package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.util.Log
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
 * Bidirectional, audio-only WebRTC peer for walkie-talkie.
 *
 * Unlike [LiveAudioPeerClient] (one-way):
 *  - BOTH peers create a local audio track (microphone).
 *  - Both tracks start MUTED — PTT unmutes the local track for instant audio.
 *  - The remote audio track is always enabled (the receiver hears immediately).
 *
 * Roles:
 *  - INITIATOR: creates the SDP offer.
 *  - RESPONDER: creates the SDP answer.
 *  Both sides have identical audio capabilities.
 */
class WalkieTalkiePeerClient(
    private val context: Context,
    private val sessionId: String,
    private val localUserId: String,
    private val isInitiator: Boolean,
    private val createLocalAudio: Boolean = true,
    /**
     * When true the peer connection will use IceTransportsType.RELAY, skipping
     * host and srflx candidate checks entirely.  Set this when connection history
     * shows the peer always needs TURN (e.g. both on different carrier networks).
     * Cuts first-connect time from ~60 s to ~2-5 s in those scenarios.
     */
    private val relayOnly: Boolean = false
) {

    companion object {
        private const val TAG = "WalkieTalkiePeer"
        @Volatile private var factoryInitialized = false
        @Volatile private var sharedFactory: PeerConnectionFactory? = null

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

        fun prewarm(context: Context) {
            getSharedFactory(context)
        }

        private fun getSharedFactory(context: Context): PeerConnectionFactory {
            ensureFactoryInitialized(context)
            return sharedFactory ?: synchronized(this) {
                sharedFactory ?: PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options().apply {
                        disableEncryption = false
                        disableNetworkMonitor = false
                    })
                    .createPeerConnectionFactory()
                    .also {
                        sharedFactory = it
                    }
            }
        }
    }

    // ── State Flows ─────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.IceConnectionState> = _connectionState.asStateFlow()

    private val _onIceCandidate = MutableSharedFlow<IceCandidate>(
        replay = 32,
        extraBufferCapacity = 64
    )
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
    /** Set to true when ICE reaches CONNECTED/COMPLETED with relay candidates present. */
    @Volatile private var connectedViaRelay = false

    /** Whether the successful connection used a TURN relay path on the local side. */
    fun wasRelayUsed(): Boolean = connectedViaRelay

    // ── Initialization ──────────────────────────────────────────────

    fun initialize() {
        peerConnectionFactory = getSharedFactory(context)

        createPeerConnection()
        if (createLocalAudio) {
            createLocalAudioTrack()
        }

    }

    private fun createPeerConnection() {
        WebRtcIceConfig.logConfiguredServers(TAG)
        val rtcConfig = WebRtcIceConfig.createRtcConfiguration(relayOnly = relayOnly)
        if (relayOnly) {
            Log.d(TAG, "Creating PeerConnection in RELAY-ONLY mode (history-based optimisation)")
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
                        if (sawLocalRelayCandidate) {
                            connectedViaRelay = true
                        }
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
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack(
            "wt_audio_track_$sessionId",
            localAudioSource
        )?.apply {
            // Start MUTED — PTT will enable this
            setEnabled(false)
        }

        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("wt_audio_stream"))
        }

    }

    // ── SDP Negotiation ─────────────────────────────────────────────

    /** Create an SDP offer (initiator side). */
    suspend fun createOffer(restartIce: Boolean = false): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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

    /** Create an SDP answer (responder side). */
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

    // ── PTT Mute Control ────────────────────────────────────────────

    /** Enable or disable the local microphone track (PTT control). */
    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    /** Returns whether the local mic track is currently enabled. */
    fun isMicEnabled(): Boolean = localAudioTrack?.enabled() == true

    fun restartIce() {
        peerConnection?.restartIce()
    }

    // ── Cleanup ─────────────────────────────────────────────────────

    fun dispose() {
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null

            localAudioSource?.dispose()
            localAudioSource = null

            peerConnectionFactory = null
            sawLocalRelayCandidate = false
            sawRemoteRelayCandidate = false

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error during dispose", e)
        }
    }
}
