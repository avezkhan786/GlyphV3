package com.glyph.glyph_v3.data.webrtc

import android.util.Log
import com.glyph.glyph_v3.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ParsedIceCandidate(
    val type: String,
    val protocol: String,
    val address: String,
    val port: String
) {
    fun summary(): String {
        val addressPart = if (address.isBlank()) "" else " address=$address"
        val portPart = if (port.isBlank()) "" else " port=$port"
        return "type=$type protocol=$protocol$addressPart$portPart"
    }
}

object WebRtcIceConfig {
    private const val TAG = "WebRtcIceConfig"
    // Larger pool pre-allocates TURN relay slots before offer/answer so relay
    // candidates are available immediately after setLocalDescription.
    private const val ICE_CANDIDATE_POOL_SIZE = 10
    private const val DYNAMIC_ICE_CACHE_TTL_MS = 15 * 60 * 1000L
    private const val DYNAMIC_ICE_CONNECT_TIMEOUT_MS = 8_000
    private const val DYNAMIC_ICE_READ_TIMEOUT_MS = 8_000
    private const val DYNAMIC_ICE_FETCH_RETRY_COUNT = 2
    private const val DYNAMIC_ICE_FETCH_RETRY_DELAY_MS = 1_500L

    private val stunUrls = listOf(
        "stun:stun.relay.metered.ca:80",
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    private val dynamicRefreshMutex = Mutex()

    @Volatile
    private var dynamicIceServers: List<PeerConnection.IceServer> = emptyList()

    @Volatile
    private var dynamicServerUrls: List<String> = emptyList()

    @Volatile
    private var dynamicTurnSpecs: List<TurnServerSpec> = emptyList()

    @Volatile
    private var dynamicIceRefreshedAtMs: Long = 0L

    private data class TurnServerSpec(
        val url: String,
        val username: String,
        val password: String
    )

    private fun turnSpecs(): List<TurnServerSpec> = listOf(
        TurnServerSpec(
            url = BuildConfig.TURN_SERVER_URL1,
            username = BuildConfig.TURN_SERVER_USERNAME1,
            password = BuildConfig.TURN_SERVER_PASSWORD1
        ),
        TurnServerSpec(
            url = BuildConfig.TURN_SERVER_URL2,
            username = BuildConfig.TURN_SERVER_USERNAME2,
            password = BuildConfig.TURN_SERVER_PASSWORD2
        ),
        TurnServerSpec(
            url = BuildConfig.TURN_SERVER_URL3,
            username = BuildConfig.TURN_SERVER_USERNAME3,
            password = BuildConfig.TURN_SERVER_PASSWORD3
        ),
        TurnServerSpec(
            url = BuildConfig.TURN_SERVER_URL4,
            username = BuildConfig.TURN_SERVER_USERNAME4,
            password = BuildConfig.TURN_SERVER_PASSWORD4
        ),
        TurnServerSpec(
            url = BuildConfig.TURN_SERVER_URL5,
            username = BuildConfig.TURN_SERVER_USERNAME5,
            password = BuildConfig.TURN_SERVER_PASSWORD5
        )
    )
        .filter { it.url.isNotBlank() }
        .flatMap { expandTurnSpec(it) }

    private fun expandTurnSpec(spec: TurnServerSpec): List<TurnServerSpec> {
        val normalized = spec.url.trim()
        if (normalized.isBlank()) return emptyList()

        val hasTransport = normalized.contains("transport=", ignoreCase = true)
        val isTurn = normalized.startsWith("turn:", ignoreCase = true)
        val isTurns = normalized.startsWith("turns:", ignoreCase = true)

        if ((!isTurn && !isTurns) || hasTransport) {
            return listOf(spec.copy(url = normalized))
        }

        if (isTurns) {
            return listOf(spec.copy(url = normalized))
        }

        // When transport is omitted, include both variants so restricted networks
        // can still reach TURN over TCP if UDP is blocked.
        return listOf(
            spec.copy(url = appendTransportQuery(normalized, "udp")),
            spec.copy(url = appendTransportQuery(normalized, "tcp"))
        )
    }

    private fun appendTransportQuery(url: String, transport: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}transport=$transport"
    }

    suspend fun refreshIceServersIfConfigured(force: Boolean = false): Boolean {
        val endpoint = BuildConfig.TURN_CREDENTIALS_URL.trim()
        if (endpoint.isBlank()) {
            return false
        }

        val now = System.currentTimeMillis()
        if (!force && dynamicIceServers.isNotEmpty() && now - dynamicIceRefreshedAtMs < DYNAMIC_ICE_CACHE_TTL_MS) {
            return true
        }

        return dynamicRefreshMutex.withLock {
            val recheckNow = System.currentTimeMillis()
            if (!force && dynamicIceServers.isNotEmpty() && recheckNow - dynamicIceRefreshedAtMs < DYNAMIC_ICE_CACHE_TTL_MS) {
                return@withLock true
            }

            val fetched = withContext(Dispatchers.IO) {
                fetchDynamicIceServers(endpoint, BuildConfig.TURN_CREDENTIALS_AUTH_HEADER.trim())
            }

            if (fetched.isEmpty()) {
                Log.w(TAG, "TURN credential endpoint returned no ICE servers: $endpoint")
                return@withLock false
            }

            dynamicIceServers = fetched.map { createIceServer(it.url, it.username, it.password) }
            dynamicServerUrls = fetched.map { it.url }
            dynamicTurnSpecs = fetched
                .filter {
                    it.url.startsWith("turn:", ignoreCase = true) ||
                        it.url.startsWith("turns:", ignoreCase = true)
                }
                .map { TurnServerSpec(url = it.url, username = it.username, password = it.password) }
            dynamicIceRefreshedAtMs = System.currentTimeMillis()
            val dynamicTurnCount = dynamicTurnSpecs.size
            Log.d(TAG, "Loaded ${dynamicIceServers.size} ICE servers ($dynamicTurnCount TURN) from TURN credential endpoint")
            true
        }
    }

    fun hasRelayConfigured(): Boolean {
        val staticHasRelay = turnSpecs().any {
            (it.url.startsWith("turn:", ignoreCase = true) || it.url.startsWith("turns:", ignoreCase = true)) &&
                it.username.isNotBlank() && it.password.isNotBlank()
        }
        val dynamicHasRelay = dynamicTurnSpecs.any {
            it.username.isNotBlank() && it.password.isNotBlank()
        }
        return staticHasRelay || dynamicHasRelay
    }

    fun createIceServers(): List<PeerConnection.IceServer> {
        val staticServers = createStaticIceServers()
        val dynamicServersSnapshot = dynamicIceServers
        if (dynamicServersSnapshot.isEmpty()) {
            return staticServers
        }

        val merged = LinkedHashMap<String, PeerConnection.IceServer>()
        (dynamicServersSnapshot + staticServers).forEach { server ->
            server.urls.forEach { url ->
                merged.putIfAbsent(url, server)
            }
        }
        return merged.values.toList()
    }

    /**
     * @param relayOnly When true, sets IceTransportsType.RELAY so WebRTC skips
     *   host and srflx candidate checks and goes straight to TURN relay allocation.
     *   Use this when history shows direct/srflx paths always fail for this user pair
     *   (e.g. both on carrier-grade NAT on different networks) to cut setup from
     *   ~60 s down to 2-5 s.
     */
    fun createRtcConfiguration(relayOnly: Boolean = false): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(createIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = ICE_CANDIDATE_POOL_SIZE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
    }

    fun configuredServerUrls(): List<String> {
        val configuredTurnUrls = dynamicServerUrls.takeIf { it.isNotEmpty() } ?: turnSpecs().map { it.url }
        return (stunUrls + configuredTurnUrls).distinct()
    }

    fun logConfiguredServers(tag: String) {
        val resolvedUrls = configuredServerUrls()
        val turnCount = resolvedUrls.count { it.startsWith("turn", ignoreCase = true) }
        Log.d(
            tag,
            "WebRTC ICE config: stun=${stunUrls.size} turn=$turnCount transports=${configuredTurnTransports()} policy=ALL tcp=ENABLED pool=$ICE_CANDIDATE_POOL_SIZE"
        )
        if (turnCount == 0) {
            Log.w(tag, "No TURN servers configured; cross-network WebRTC may fail behind restrictive NATs")
        }
        currentTurnSpecs().forEachIndexed { index, spec ->
            Log.d(
                tag,
                "TURN[$index] url=${sanitizeServerUrl(spec.url)} usernameSet=${spec.username.isNotBlank()} passwordSet=${spec.password.isNotBlank()}"
            )
            if (spec.username.isBlank() || spec.password.isBlank()) {
                Log.w(tag, "TURN[$index] is missing credentials; relay allocation will likely fail")
            }
        }
    }

    private fun createStaticIceServers(): List<PeerConnection.IceServer> {
        val stunServers = stunUrls.map { createIceServer(it, username = "", password = "") }
        val turnServers = turnSpecs().map { createIceServer(it.url, it.username, it.password) }
        return stunServers + turnServers
    }

    private fun createIceServer(url: String, username: String, password: String): PeerConnection.IceServer {
        return PeerConnection.IceServer.builder(url).apply {
            if (username.isNotBlank()) {
                setUsername(username)
            }
            if (password.isNotBlank()) {
                setPassword(password)
            }
        }.createIceServer()
    }

    private fun currentTurnSpecs(): List<TurnServerSpec> {
        val dynamicSpecs = dynamicServerUrls.map { TurnServerSpec(url = it, username = "dynamic", password = "dynamic") }
        return if (dynamicSpecs.isNotEmpty()) dynamicSpecs else turnSpecs()
    }

    private data class FetchedIceServer(
        val url: String,
        val username: String,
        val password: String
    )

    private suspend fun fetchDynamicIceServers(endpoint: String, authHeader: String): List<FetchedIceServer> {
        repeat(DYNAMIC_ICE_FETCH_RETRY_COUNT) { attempt ->
            var connection: HttpURLConnection? = null
            val result = runCatching {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = DYNAMIC_ICE_CONNECT_TIMEOUT_MS
                    readTimeout = DYNAMIC_ICE_READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    if (authHeader.isNotBlank()) {
                        setRequestProperty("Authorization", authHeader)
                    }
                }

                val responseCode = connection?.responseCode ?: -1
                if (responseCode !in 200..299) {
                    throw IllegalStateException("TURN credential endpoint failed with HTTP $responseCode")
                }

                val responseBody = connection?.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                parseFetchedIceServers(responseBody)
            }.onFailure { error ->
                val tryNum = attempt + 1
                Log.w(TAG, "Failed to refresh TURN credentials from $endpoint (attempt=$tryNum/${DYNAMIC_ICE_FETCH_RETRY_COUNT})", error)
            }.getOrDefault(emptyList()).also {
                connection?.disconnect()
            }

            if (result.isNotEmpty()) {
                return result
            }

            if (attempt < DYNAMIC_ICE_FETCH_RETRY_COUNT - 1) {
                delay(DYNAMIC_ICE_FETCH_RETRY_DELAY_MS)
            }
        }
        return emptyList()
    }

    private fun parseFetchedIceServers(responseBody: String): List<FetchedIceServer> {
        if (responseBody.isBlank()) return emptyList()

        val array = when {
            responseBody.trimStart().startsWith("[") -> JSONArray(responseBody)
            else -> {
                val root = JSONObject(responseBody)
                root.optJSONArray("iceServers")
                    ?: root.optJSONObject("data")?.optJSONArray("iceServers")
                    ?: JSONArray()
            }
        }

        val servers = mutableListOf<FetchedIceServer>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val username = item.optString("username")
            val password = item.optString("credential").ifBlank { item.optString("password") }
            val urls = mutableListOf<String>()

            val urlsArray = item.optJSONArray("urls")
            if (urlsArray != null) {
                for (urlIndex in 0 until urlsArray.length()) {
                    val url = urlsArray.optString(urlIndex).trim()
                    if (url.isNotBlank()) {
                        urls += url
                    }
                }
            }

            val singleUrl = item.optString("url").trim().ifBlank { item.optString("urls").trim() }
            if (singleUrl.isNotBlank()) {
                urls += singleUrl
            }

            urls.distinct().forEach { url ->
                servers += FetchedIceServer(url = url, username = username, password = password)
            }
        }
        return servers
    }

    fun logLocalCandidate(tag: String, candidate: IceCandidate) {
        val details = parseCandidate(candidate)
        Log.d(tag, "ICE local candidate gathered: ${details.summary()}")
    }

    fun logRemoteCandidate(tag: String, candidate: IceCandidate, buffered: Boolean) {
        val details = parseCandidate(candidate)
        Log.d(tag, "ICE remote candidate ${if (buffered) "buffered" else "applied"}: ${details.summary()}")
    }

    fun logIceState(tag: String, state: PeerConnection.IceConnectionState?, localRelay: Boolean? = null, remoteRelay: Boolean? = null) {
        val relaySummary = buildString {
            if (localRelay != null) append(" localRelay=$localRelay")
            if (remoteRelay != null) append(" remoteRelay=$remoteRelay")
        }
        Log.d(tag, "ICE state=$state$relaySummary")
    }

    fun parseCandidate(candidate: IceCandidate): ParsedIceCandidate = parseCandidate(candidate.sdp)

    fun parseCandidate(candidateSdp: String): ParsedIceCandidate {
        val parts = candidateSdp.trim().split(Regex("\\s+"))
        val protocol = parts.getOrNull(2).orEmpty().lowercase().ifBlank { "unknown" }
        val address = parts.getOrNull(4).orEmpty()
        val port = parts.getOrNull(5).orEmpty()
        val typeIndex = parts.indexOf("typ")
        val type = parts.getOrNull(typeIndex + 1).orEmpty().lowercase().ifBlank { "unknown" }
        return ParsedIceCandidate(
            type = type,
            protocol = protocol,
            address = address,
            port = port
        )
    }

    private fun configuredTurnTransports(): String {
        val transports = configuredServerUrls()
            .map { spec ->
                when {
                    spec.startsWith("turns:", ignoreCase = true) -> "tls"
                    spec.contains("transport=tcp", ignoreCase = true) -> "tcp"
                    spec.contains("transport=udp", ignoreCase = true) -> "udp"
                    else -> "udp/default"
                }
            }
            .distinct()
            .sorted()
        return transports.joinToString(prefix = "[", postfix = "]")
    }

    private fun sanitizeServerUrl(url: String): String {
        return url.substringBefore('@').substringBefore('?') + url.substringAfter('?', "").let { query ->
            if (query.isBlank()) "" else "?${query}"
        }
    }
}