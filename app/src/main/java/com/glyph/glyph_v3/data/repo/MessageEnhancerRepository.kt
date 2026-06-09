package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repository for AI message enhancement via Firebase Cloud Functions.
 *
 * Architecture:
 * - Singleton with in-memory LRU cache
 * - Calls `enhanceMessage` Cloud Function (Gemini 2.5 Flash)
 * - Thread-safe with per-key mutex deduplication
 * - Error handling with sealed class hierarchy
 *
 * Follows the same pattern as TranslationRepository for consistency.
 */
class MessageEnhancerRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EnhancerRepo"
        private const val MAX_CACHE_SIZE = 30

        @Volatile
        private var INSTANCE: MessageEnhancerRepository? = null

        fun getInstance(context: Context): MessageEnhancerRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageEnhancerRepository(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val memoryCache = ConcurrentHashMap<String, EnhancementResult>()
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()

    // ─── Data classes ────────────────────────────────────

    data class EnhancementResult(
        val originalText: String,
        val enhancedText: String,
        val action: String,
        val cached: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    sealed class EnhancementError : Exception() {
        data object RateLimited : EnhancementError() {
            override val message: String = "Too many requests. Please wait."
        }
        data object TextTooLong : EnhancementError() {
            override val message: String = "Text too long (max 1000 characters)."
        }
        data object NoInternetConnection : EnhancementError() {
            override val message: String = "No internet connection."
        }
        data object NetworkError : EnhancementError() {
            override val message: String = "Network error. Check connection."
        }
        data class ServerError(override val message: String) : EnhancementError()
    }

    // ─── Public API ──────────────────────────────────────

    /**
     * Pre-warm the Cloud Function to minimize cold start latency.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        try {
            val data = hashMapOf(
                "text" to "warmup",
                "action" to "enhance"
            )
            functions.getHttpsCallable("enhanceMessage")
                .withTimeout(5, TimeUnit.SECONDS)
                .call(data)
        } catch (e: Exception) {
        }
    }

    /**
     * Enhance a message with the specified action.
     *
     * @param text The message text to enhance
     * @param action One of: "enhance", "grammar", "translate", "tone"
     * @param options Optional parameters (targetLanguage for translate, tone for tone adjustment)
     * @return EnhancementResult with original and enhanced text
     * @throws EnhancementError on failure
     */
    suspend fun enhance(
        text: String,
        action: String,
        options: Map<String, String>? = null
    ): EnhancementResult = withContext(Dispatchers.IO) {
        // Validate
        if (text.isBlank()) throw EnhancementError.ServerError("Text is empty.")
        if (text.length > 1000) throw EnhancementError.TextTooLong
        if (!isNetworkAvailable()) throw EnhancementError.NoInternetConnection

        val cacheKey = buildCacheKey(text, action, options)

        // Deduplicate concurrent requests for the same key
        val mutex = requestMutexes.getOrPut(cacheKey) { Mutex() }
        mutex.withLock {
            // Check memory cache
            memoryCache[cacheKey]?.let { cached ->
                return@withContext cached.copy(cached = true)
            }

            // Call Cloud Function
            try {

                val data = hashMapOf<String, Any>(
                    "text" to text.trim(),
                    "action" to action
                )
                if (options != null) {
                    data["options"] = options
                }

                val result = functions
                    .getHttpsCallable("enhanceMessage")
                    .withTimeout(25, TimeUnit.SECONDS)
                    .call(data)
                    .await()

                @Suppress("UNCHECKED_CAST")
                val responseData = result.data as? Map<String, Any>
                    ?: throw EnhancementError.ServerError("Invalid response format")

                val enhancedText = responseData["enhancedText"] as? String
                    ?: throw EnhancementError.ServerError("No enhanced text in response")

                val enhancementResult = EnhancementResult(
                    originalText = text,
                    enhancedText = enhancedText,
                    action = action,
                    cached = responseData["cached"] as? Boolean ?: false
                )

                // Store in memory cache (with size limit)
                if (memoryCache.size >= MAX_CACHE_SIZE) {
                    // Evict oldest entry
                    val oldest = memoryCache.entries.minByOrNull { it.value.timestamp }
                    oldest?.let { memoryCache.remove(it.key) }
                }
                memoryCache[cacheKey] = enhancementResult

                return@withContext enhancementResult

            } catch (e: EnhancementError) {
                throw e
            } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
                Log.e(TAG, "Cloud Function error: ${e.code} ${e.message}")
                throw when (e.code) {
                    com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                        EnhancementError.RateLimited
                    com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                        EnhancementError.ServerError(e.message ?: "Invalid request")
                    else ->
                        EnhancementError.ServerError(e.message ?: "Enhancement failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Enhancement error: ${e.message}", e)
                throw EnhancementError.NetworkError
            } finally {
                requestMutexes.remove(cacheKey)
            }
        }
    }

    /**
     * Clear the in-memory cache.
     */
    fun clearCache() {
        memoryCache.clear()
    }

    // ─── Private helpers ─────────────────────────────────

    private fun buildCacheKey(text: String, action: String, options: Map<String, String>?): String {
        val optStr = options?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value}" } ?: ""
        return "${text.trim().hashCode()}::$action::$optStr"
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
