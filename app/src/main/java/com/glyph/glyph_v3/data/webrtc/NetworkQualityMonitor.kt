package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport

/**
 * Monitors network quality during a WebRTC call using two signals:
 *
 * 1. **WebRTC stats polling** — Reads RTCStatsReport every 2 seconds to calculate
 *    estimated bandwidth, round-trip time, and packet loss percentage.
 *
 * 2. **Android ConnectivityManager** — Detects the underlying transport (WiFi, LTE, 3G)
 *    to pre-seed an appropriate quality profile before stats are available.
 *
 * The combined result is exposed as a [NetworkQuality] state flow that the
 * [AdaptiveVideoController] consumes to adjust capture/encoding parameters.
 */
class NetworkQualityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetQualityMonitor"
        private const val POLL_INTERVAL_MS = 2_000L

        // Thresholds for quality classification
        private const val STRONG_BANDWIDTH_BPS = 1_200_000L   // 1.2 Mbps
        private const val MEDIUM_BANDWIDTH_BPS = 500_000L     // 500 kbps
        private const val HIGH_PACKET_LOSS = 8.0              // 8%
        private const val MEDIUM_PACKET_LOSS = 3.0            // 3%
        private const val HIGH_RTT_MS = 300.0
        private const val MEDIUM_RTT_MS = 150.0
    }

    enum class NetworkQuality { STRONG, MEDIUM, WEAK }

    enum class NetworkType { WIFI, LTE, MOBILE_3G, UNKNOWN }

    private val _quality = MutableStateFlow(NetworkQuality.STRONG)
    val quality: StateFlow<NetworkQuality> = _quality.asStateFlow()

    private val _networkType = MutableStateFlow(NetworkType.UNKNOWN)
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    // Previous stats snapshot for delta calculation
    private var prevBytesSent = 0L
    private var prevBytesReceived = 0L
    private var prevTimestamp = 0L
    private var prevPacketsSent = 0L
    private var prevPacketsLost = 0L

    // Smoothed values for stability (exponential moving average)
    private var smoothedBandwidth = -1.0
    private var smoothedRtt = -1.0
    private var smoothedPacketLoss = -1.0
    private val alpha = 0.15  // EMA smoothing — low value = very stable, prevents oscillation

    // Hysteresis: quality must be consistently different for this many polls before switching
    private var pendingQuality: NetworkQuality? = null
    private var pendingQualityCount = 0
    private val hysteresisThreshold = 3  // Need 3 consecutive readings (~6 seconds)

    /**
     * Start polling WebRTC stats and detecting network type.
     * Call this once the PeerConnection is established.
     */
    fun start(peerConnection: PeerConnection) {
        stop()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Detect initial network type
        detectNetworkType()

        // Pre-seed quality from network type before stats are available
        _quality.value = when (_networkType.value) {
            NetworkType.WIFI -> NetworkQuality.STRONG
            NetworkType.LTE -> NetworkQuality.STRONG
            NetworkType.MOBILE_3G -> NetworkQuality.MEDIUM
            NetworkType.UNKNOWN -> NetworkQuality.MEDIUM
        }

        pollJob = scope?.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    detectNetworkType()
                    pollStats(peerConnection)
                } catch (e: Exception) {
                    Log.w(TAG, "Stats poll error", e)
                }
            }
        }

    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null
        resetState()
    }

    /**
     * Detect the current network transport type.
     */
    private fun detectNetworkType() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)

        _networkType.value = when {
            capabilities == null -> NetworkType.UNKNOWN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // Differentiate LTE from 3G using downstream bandwidth hint
                val downKbps = capabilities.linkDownstreamBandwidthKbps
                if (downKbps >= 5_000) NetworkType.LTE else NetworkType.MOBILE_3G
            }
            else -> NetworkType.UNKNOWN
        }
    }

    /**
     * Poll RTCStatsReport and compute smoothed bandwidth, RTT, and packet loss.
     * Uses suspendCancellableCoroutine to bridge the callback-based getStats API.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun pollStats(peerConnection: PeerConnection) {
        val report = suspendCancellableCoroutine<RTCStatsReport?> { cont ->
            peerConnection.getStats { report -> cont.resume(report) {} }
        } ?: return

        var totalBytesSent = 0L
        var totalBytesReceived = 0L
        var totalPacketsSent = 0L
        var totalPacketsLost = 0L
        var currentRtt = -1.0
        var reportTimestamp = 0L

        for (stats in report.statsMap.values) {
            when (stats.type) {
                // Outbound RTP stats — gives us bytes sent and packets sent
                "outbound-rtp" -> {
                    val members = stats.members
                    val bytes = (members["bytesSent"] as? Number)?.toLong() ?: 0L
                    val packets = (members["packetsSent"] as? Number)?.toLong() ?: 0L
                    totalBytesSent += bytes
                    totalPacketsSent += packets
                    if (reportTimestamp == 0L) {
                        reportTimestamp = (stats.timestampUs / 1000.0).toLong()
                    }
                }
                // Inbound RTP stats — gives us bytes received and packets lost
                "inbound-rtp" -> {
                    val members = stats.members
                    val bytes = (members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    val lost = (members["packetsLost"] as? Number)?.toLong() ?: 0L
                    totalBytesReceived += bytes
                    totalPacketsLost += lost
                }
                // Candidate pair — gives us current RTT
                "candidate-pair" -> {
                    val members = stats.members
                    val nominated = members["nominated"] as? Boolean ?: false
                    if (nominated) {
                        val rtt = (members["currentRoundTripTime"] as? Number)?.toDouble()
                        if (rtt != null && rtt > 0) {
                            currentRtt = rtt * 1000.0  // Convert seconds to ms
                        }
                    }
                }
            }
        }

        if (prevTimestamp > 0 && reportTimestamp > prevTimestamp) {
            val deltaTimeMs = (reportTimestamp - prevTimestamp).coerceAtLeast(1)
            val deltaBytes = (totalBytesSent - prevBytesSent) + (totalBytesReceived - prevBytesReceived)
            val bandwidthBps = (deltaBytes * 8 * 1000) / deltaTimeMs

            // Packet loss percentage
            val deltaPacketsSent = totalPacketsSent - prevPacketsSent
            val deltaPacketsLost = totalPacketsLost - prevPacketsLost
            val lossPercent = if (deltaPacketsSent > 0) {
                (deltaPacketsLost.toDouble() / deltaPacketsSent * 100).coerceIn(0.0, 100.0)
            } else 0.0

            // Apply exponential moving average for stability
            smoothedBandwidth = if (smoothedBandwidth < 0) {
                bandwidthBps.toDouble()
            } else {
                alpha * bandwidthBps + (1 - alpha) * smoothedBandwidth
            }

            if (currentRtt > 0) {
                smoothedRtt = if (smoothedRtt < 0) currentRtt
                else alpha * currentRtt + (1 - alpha) * smoothedRtt
            }

            smoothedPacketLoss = if (smoothedPacketLoss < 0) lossPercent
            else alpha * lossPercent + (1 - alpha) * smoothedPacketLoss

            // Classify quality based on combined metrics
            val newQuality = classifyQuality(
                bandwidthBps = smoothedBandwidth.toLong(),
                rttMs = smoothedRtt,
                packetLossPercent = smoothedPacketLoss
            )

            // Hysteresis: require multiple consecutive readings at the new level
            // before actually switching. This prevents brief dips from causing
            // disruptive quality changes.
            if (newQuality != _quality.value) {
                if (newQuality == pendingQuality) {
                    pendingQualityCount++
                } else {
                    pendingQuality = newQuality
                    pendingQualityCount = 1
                }
                if (pendingQualityCount >= hysteresisThreshold) {
                    _quality.value = newQuality
                    pendingQuality = null
                    pendingQualityCount = 0
                }
            } else {
                // Current quality is confirmed — reset pending
                pendingQuality = null
                pendingQualityCount = 0
            }
        }

        prevBytesSent = totalBytesSent
        prevBytesReceived = totalBytesReceived
        prevTimestamp = reportTimestamp
        prevPacketsSent = totalPacketsSent
        prevPacketsLost = totalPacketsLost
    }

    private fun classifyQuality(
        bandwidthBps: Long,
        rttMs: Double,
        packetLossPercent: Double
    ): NetworkQuality {
        // Weak: any critical metric is bad
        if (bandwidthBps < MEDIUM_BANDWIDTH_BPS ||
            packetLossPercent > HIGH_PACKET_LOSS ||
            (rttMs > 0 && rttMs > HIGH_RTT_MS)
        ) {
            return NetworkQuality.WEAK
        }

        // Strong: all metrics are good
        if (bandwidthBps >= STRONG_BANDWIDTH_BPS &&
            packetLossPercent < MEDIUM_PACKET_LOSS &&
            (rttMs <= 0 || rttMs < MEDIUM_RTT_MS)
        ) {
            return NetworkQuality.STRONG
        }

        return NetworkQuality.MEDIUM
    }

    private fun resetState() {
        prevBytesSent = 0L
        prevBytesReceived = 0L
        prevTimestamp = 0L
        prevPacketsSent = 0L
        prevPacketsLost = 0L
        smoothedBandwidth = -1.0
        smoothedRtt = -1.0
        smoothedPacketLoss = -1.0
        pendingQuality = null
        pendingQualityCount = 0
    }
}
