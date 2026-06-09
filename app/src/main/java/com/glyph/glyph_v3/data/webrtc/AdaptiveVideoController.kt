package com.glyph.glyph_v3.data.webrtc

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.PeerConnection
import org.webrtc.RtpSender

/**
 * Dynamically adapts video encoding bitrate based on network quality.
 *
 * IMPORTANT: Camera capture resolution is kept FIXED (640×480) to avoid video
 * flashing. Only the encoding bitrate is adjusted via RtpSender.setParameters()
 * which is seamless and invisible to the user. This matches how WhatsApp and
 * Google Meet handle adaptive quality — the encoder scales internally without
 * disrupting the camera pipeline.
 *
 * | Quality | Max Bitrate |
 * |---------|-------------|
 * | STRONG  | 1500 kbps   |
 * | MEDIUM  | 800 kbps    |
 * | WEAK    | 300 kbps    |
 * | BG      | 150 kbps    |
 */
class AdaptiveVideoController {

    companion object {
        private const val TAG = "AdaptiveVideo"

        // Minimum interval between bitrate switches to prevent flapping
        private const val SWITCH_COOLDOWN_MS = 8_000L

        // Wait for the encoder to stabilize before adapting
        private const val WARMUP_DELAY_MS = 6_000L
    }

    // ── Bitrate profiles (camera resolution stays fixed) ────────────

    data class VideoProfile(
        val maxBitrateBps: Int,
        val label: String
    )

    private val strongProfile     = VideoProfile(1_500_000, "strong/1500kbps")
    private val mediumProfile     = VideoProfile(800_000,   "medium/800kbps")
    private val weakProfile       = VideoProfile(300_000,   "weak/300kbps")
    private val backgroundProfile = VideoProfile(150_000,   "bg/150kbps")

    private val _currentProfile = MutableStateFlow(mediumProfile)
    val currentProfile: StateFlow<VideoProfile> = _currentProfile.asStateFlow()

    private var scope: CoroutineScope? = null
    private var adaptJob: Job? = null
    private var lastSwitchTime = 0L
    private var isInBackground = false
    private var peerConnection: PeerConnection? = null
    private var videoSender: RtpSender? = null

    /**
     * Begin adaptive bitrate monitoring.
     *
     * @param qualityFlow the network quality signal from [NetworkQualityMonitor]
     * @param pc the PeerConnection for RtpSender bitrate adjustment
     */
    fun start(
        qualityFlow: StateFlow<NetworkQualityMonitor.NetworkQuality>,
        pc: PeerConnection?
    ) {
        stop()
        peerConnection = pc
        videoSender = findVideoSender(pc)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        adaptJob = scope?.launch {
            // Let the encoder stabilize after connection before we start adjusting.
            // Touching encoding params too early causes keyframe storms and jitter.
            delay(WARMUP_DELAY_MS)

            // Apply initial bitrate based on whatever quality was detected during warmup
            applyBitrate(profileForQuality(qualityFlow.value))
            lastSwitchTime = System.currentTimeMillis()

            qualityFlow.collect { quality ->
                val now = System.currentTimeMillis()
                if (now - lastSwitchTime < SWITCH_COOLDOWN_MS) return@collect

                val targetProfile = if (isInBackground) {
                    backgroundProfile
                } else {
                    profileForQuality(quality)
                }

                if (targetProfile.maxBitrateBps != _currentProfile.value.maxBitrateBps) {
                    lastSwitchTime = now
                    applyBitrate(targetProfile)
                }
            }
        }

    }

    fun stop() {
        adaptJob?.cancel()
        adaptJob = null
        scope?.cancel()
        scope = null
        peerConnection = null
        videoSender = null
    }

    /**
     * Called when the app enters or leaves the background.
     * In background: drop to lowest bitrate to save bandwidth/battery.
     * In foreground: resume network-appropriate bitrate.
     */
    fun setBackground(background: Boolean, currentQuality: NetworkQualityMonitor.NetworkQuality) {
        isInBackground = background
        val targetProfile = if (background) {
            backgroundProfile
        } else {
            profileForQuality(currentQuality)
        }
        if (targetProfile.maxBitrateBps != _currentProfile.value.maxBitrateBps) {
            applyBitrate(targetProfile)
        }
    }

    private fun profileForQuality(quality: NetworkQualityMonitor.NetworkQuality): VideoProfile {
        return when (quality) {
            NetworkQualityMonitor.NetworkQuality.STRONG -> strongProfile
            NetworkQualityMonitor.NetworkQuality.MEDIUM -> mediumProfile
            NetworkQualityMonitor.NetworkQuality.WEAK -> weakProfile
        }
    }

    /**
     * Adjust encoding bitrate via RtpSender.setParameters(). This is seamless—
     * no camera restart, no keyframe storm, no visible glitch.
     */
    private fun applyBitrate(profile: VideoProfile) {
        _currentProfile.value = profile

        val sender = videoSender ?: findVideoSender(peerConnection).also { videoSender = it }
        if (sender == null) {
            Log.w(TAG, "No video sender found")
            return
        }

        try {
            val params = sender.parameters
            if (params.encodings.isEmpty()) return

            for (encoding in params.encodings) {
                encoding.maxBitrateBps = profile.maxBitrateBps
                // Don't set minBitrateBps — let the encoder go as low as needed
                // to avoid frame drops when bandwidth is truly constrained.
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bitrate parameters", e)
        }
    }

    private fun findVideoSender(pc: PeerConnection?): RtpSender? {
        return pc?.senders?.firstOrNull { sender ->
            sender.track()?.kind() == "video"
        }
    }
}
