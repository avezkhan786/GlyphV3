package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.dao.AiMessageDao
import com.glyph.glyph_v3.data.local.entity.AiMessage
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repository for the Global AI Agent.
 *
 * Architecture:
 * ─────────────
 * • **Stateless Cloud Function** – the CF receives the full context window in
 *   each request and stores nothing on Firebase. Zero RTDB / Firestore writes.
 * • **Room as single source of truth** – all conversation history lives in
 *   [AiMessage] entities on-device.
 * • **Client-assembled context** – before each call, the last [CONTEXT_WINDOW]
 *   messages are read from Room & serialised into the `contents[]` array sent
 *   to the CF, which forwards them to Gemini.
 * • **Three modes**: CHAT (general chat), SEARCH (chat intelligence),
 *   APP (app intelligence).
 * • **Memory LRU** for App Intelligence answers (highly reusable).
 *
 * Follows the same singleton + sealed-error pattern as
 * [MessageEnhancerRepository] and [TranslationRepository].
 */
class AiAgentRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AiAgentRepo"
        private const val FUNCTION_NAME = "glyphAiAgent"
        private const val TIMEOUT_CHAT_SECONDS = 30L
        private const val TIMEOUT_SEARCH_SECONDS = 45L
        private const val TIMEOUT_APP_SECONDS = 15L
        private const val CONTEXT_WINDOW = 15
        private const val MAX_APP_CACHE_SIZE = 50
        private const val MAX_TEXT_LENGTH = 2000

        @Volatile
        private var INSTANCE: AiAgentRepository? = null

        fun getInstance(context: Context): AiAgentRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiAgentRepository(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    // ─── Dependencies ────────────────────────────────────

    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val dao: AiMessageDao by lazy {
        AppDatabase.getDatabase(context).aiMessageDao()
    }

    // ─── Caches & concurrency ────────────────────────────

    /** In-memory LRU for App Intelligence (key = query hash, value = response). */
    private val appIntelCache = ConcurrentHashMap<String, String>()

    /** Per-request mutex to deduplicate identical concurrent calls. */
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()

    // ─── Data classes ────────────────────────────────────

    data class AgentResponse(
        val reply: String,
        val mode: String,
        val sources: List<SourceCitation>? = null,
        val navigationHint: String? = null,
        val cached: Boolean = false,
        /** If the CF auto-routed to a different mode, this is set. */
        val suggestedMode: String? = null
    )

    data class SourceCitation(
        val chatId: String = "",
        val msgId: String = "",
        val text: String = "",
        val timestamp: Long = 0L,
        val senderId: String = "",
        val conversationWith: String = ""
    )

    sealed class AgentError : Exception() {
        data object NoInternetConnection : AgentError() {
            override val message: String = "No internet connection."
        }
        data object RateLimited : AgentError() {
            override val message: String = "Too many requests. Please wait a moment."
        }
        data object TextTooLong : AgentError() {
            override val message: String = "Message too long (max $MAX_TEXT_LENGTH characters)."
        }
        data object SearchConsentRequired : AgentError() {
            override val message: String = "Permission required to search your chats."
        }
        data object NetworkError : AgentError() {
            override val message: String = "Network error. Check your connection."
        }
        data class ServerError(override val message: String) : AgentError()
    }

    // ─── Public API ──────────────────────────────────────

    /** Observable stream of all AI messages (for adapter). */
    fun getMessages(): Flow<List<AiMessage>> = dao.getMessages()

    /**
     * Send a message to the AI Agent.
     *
     * 1. Persist user message to Room (optimistic, instant UI update).
     * 2. Read last [CONTEXT_WINDOW] messages from Room as context.
     * 3. Call Cloud Function with context + user query.
     * 4. Persist AI response to Room.
     * 5. Return [AgentResponse].
     */
    suspend fun sendMessage(
        text: String,
        mode: AiMode,
        searchOptions: Map<String, String>? = null
    ): AgentResponse = withContext(Dispatchers.IO) {
        // Validate
        if (text.isBlank()) throw AgentError.ServerError("Message is empty.")
        if (text.length > MAX_TEXT_LENGTH) throw AgentError.TextTooLong
        if (!isNetworkAvailable()) throw AgentError.NoInternetConnection

        val modeStr = mode.value
        val dedupeKey = "${text.trim().hashCode()}::$modeStr"
        val mutex = requestMutexes.getOrPut(dedupeKey) { Mutex() }

        mutex.withLock {
            try {
                // ── 1. Check app-intelligence cache ──
                if (mode == AiMode.APP) {
                    appIntelCache[dedupeKey]?.let { cached ->
                        // Still persist user msg + cached model msg
                        val userMsg = persistUserMessage(text, modeStr)
                        persistModelMessage(cached, modeStr)
                        return@withContext AgentResponse(
                            reply = cached,
                            mode = modeStr,
                            cached = true
                        )
                    }
                }

                // ── 2. Persist user message (optimistic) ──
                val userMsg = persistUserMessage(text, modeStr)

                // ── 3. Build context window ──
                val historyMessages = dao.getRecentMessages(CONTEXT_WINDOW)
                val contentsArray = historyMessages
                    .filter { !it.isStreaming }
                    .map { msg ->
                        hashMapOf<String, Any>(
                            "role" to msg.role,
                            "text" to msg.text
                        )
                    }

                // ── 4. Build Cloud Function payload ──
                val data = hashMapOf<String, Any>(
                    "mode" to modeStr,
                    "message" to text.trim(),
                    "contents" to contentsArray
                )
                // Always send the device timezone so the server formats times in the user's local time
                val mergedOptions = HashMap<String, String>()
                mergedOptions["timezone"] = java.util.TimeZone.getDefault().id
                if (!searchOptions.isNullOrEmpty()) {
                    mergedOptions.putAll(searchOptions)
                }
                data["options"] = mergedOptions

                // ── 5. Call Cloud Function ──
                // Use search timeout as max — CF may auto-route chat→search
                val timeout = when (mode) {
                    AiMode.CHAT -> TIMEOUT_SEARCH_SECONDS  // CF can auto-route to search
                    AiMode.SEARCH -> TIMEOUT_SEARCH_SECONDS
                    AiMode.APP -> TIMEOUT_APP_SECONDS
                }


                val result = functions
                    .getHttpsCallable(FUNCTION_NAME)
                    .withTimeout(timeout, TimeUnit.SECONDS)
                    .call(data)
                    .await()

                @Suppress("UNCHECKED_CAST")
                val responseData = result.data as? Map<String, Any>
                    ?: throw AgentError.ServerError("Invalid response format")

                val reply = responseData["reply"] as? String
                    ?: throw AgentError.ServerError("No reply in response")

                val navigationHint = responseData["navigationHint"] as? String
                val suggestedMode = responseData["suggestedMode"] as? String

                // Parse sources (search mode only)
                val sources = parseSourceCitations(responseData)

                // ── 6. Persist AI response ──
                val sourcesJson = if (sources != null) {
                    serializeSources(sources)
                } else null

                persistModelMessage(reply, modeStr, sourcesJson)

                // ── 7. Cache app intelligence answer ──
                if (mode == AiMode.APP) {
                    if (appIntelCache.size >= MAX_APP_CACHE_SIZE) {
                        // Simple eviction: remove a random entry
                        appIntelCache.keys.firstOrNull()?.let { appIntelCache.remove(it) }
                    }
                    appIntelCache[dedupeKey] = reply
                }

                return@withContext AgentResponse(
                    reply = reply,
                    mode = modeStr,
                    sources = sources,
                    navigationHint = navigationHint,
                    cached = false,
                    suggestedMode = suggestedMode
                )

            } catch (e: AgentError) {
                throw e
            } catch (e: FirebaseFunctionsException) {
                Log.e(TAG, "Cloud Function error: ${e.code} ${e.message}")
                throw when (e.code) {
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> AgentError.RateLimited
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> AgentError.SearchConsentRequired
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                        AgentError.ServerError(e.message ?: "Invalid request")
                    else -> AgentError.ServerError(e.message ?: "AI Agent request failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent error: ${e.message}", e)
                throw AgentError.NetworkError
            } finally {
                requestMutexes.remove(dedupeKey)
            }
        }
    }

    /** Pre-warm the Cloud Function to reduce cold-start latency. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        try {
            val data = hashMapOf<String, Any>(
                "mode" to "warmup",
                "message" to "ping",
                "contents" to emptyList<Any>()
            )
            functions.getHttpsCallable(FUNCTION_NAME)
                .withTimeout(5, TimeUnit.SECONDS)
                .call(data)
        } catch (e: Exception) {
        }
    }

    /** Clear all conversation history (Room). */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
        appIntelCache.clear()
    }

    /** Clear only messages of a specific mode. */
    suspend fun clearHistoryByMode(mode: AiMode) = withContext(Dispatchers.IO) {
        dao.clearByMode(mode.value)
    }

    /** Get message count. */
    suspend fun getMessageCount(): Int = withContext(Dispatchers.IO) {
        dao.getMessageCount()
    }

    /** Clear the in-memory App Intelligence cache. */
    fun clearAppIntelCache() {
        appIntelCache.clear()
    }

    // ─── Private helpers ─────────────────────────────────

    private suspend fun persistUserMessage(text: String, mode: String): AiMessage {
        val msg = AiMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            mode = mode
        )
        dao.insert(msg)
        return msg
    }

    private suspend fun persistModelMessage(
        text: String,
        mode: String,
        sourcesJson: String? = null
    ): AiMessage {
        val msg = AiMessage(
            id = UUID.randomUUID().toString(),
            role = "model",
            text = text,
            timestamp = System.currentTimeMillis(),
            mode = mode,
            sourcesJson = sourcesJson
        )
        dao.insert(msg)
        return msg
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSourceCitations(responseData: Map<String, Any>): List<SourceCitation>? {
        val sourcesRaw = responseData["sources"] as? List<Map<String, Any>> ?: return null
        if (sourcesRaw.isEmpty()) return null
        return sourcesRaw.map { src ->
            SourceCitation(
                chatId = src["chatId"] as? String ?: "",
                msgId = src["msgId"] as? String ?: "",
                text = src["text"] as? String ?: "",
                timestamp = (src["timestamp"] as? Number)?.toLong() ?: 0L,
                senderId = src["senderId"] as? String ?: "",
                conversationWith = src["conversationWith"] as? String ?: ""
            )
        }
    }

    private fun serializeSources(sources: List<SourceCitation>): String {
        val jsonArray = org.json.JSONArray()
        for (src in sources) {
            val obj = org.json.JSONObject()
            obj.put("chatId", src.chatId)
            obj.put("msgId", src.msgId)
            obj.put("text", src.text)
            obj.put("timestamp", src.timestamp)
            obj.put("senderId", src.senderId)
            obj.put("conversationWith", src.conversationWith)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * AI Agent modes.
 */
enum class AiMode(val value: String) {
    CHAT("chat"),
    SEARCH("search"),
    APP("app");

    companion object {
        fun fromValue(value: String): AiMode = entries.firstOrNull { it.value == value } ?: CHAT
    }
}
