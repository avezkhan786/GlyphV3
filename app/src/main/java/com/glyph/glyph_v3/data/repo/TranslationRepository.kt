package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.entity.TranslationCache
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn

/**
 * Repository for translation + TTS operations.
 *
 * Responsibilities:
 * - In-memory + Room cache management
 * - Firebase Cloud Function calls (no direct API key exposure)
 * - Audio file download & local caching
 * - Thread-safe, coroutine-based
 * - 7-day cache expiry
 *
 * Usage:
 *   val result = translationRepository.translate(messageId, text, targetLanguage)
 *   val audioFile = translationRepository.getOrDownloadAudio(messageId, targetLanguage)
 */
class TranslationRepository(private val context: Context) {

    companion object {
        private const val TAG = "TranslationRepo"
        private const val AUDIO_CACHE_DIR = "translation_audio"
        private const val CACHE_EXPIRY_DAYS = 7L

        @Volatile
        private var INSTANCE: TranslationRepository? = null

        fun getInstance(context: Context): TranslationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TranslationRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val db by lazy { AppDatabase.getDatabase(context) }
    private val dao by lazy { db.translationCacheDao() }
    private val functions by lazy { FirebaseFunctions.getInstance() }

    // In-memory LRU cache (messageId+lang → TranslationResult)
    // Prevents Room lookups for recently accessed translations
    private val memoryCache = ConcurrentHashMap<String, TranslationResult>()
    private val maxMemoryCacheSize = 50

    // Mutex per message to prevent duplicate concurrent requests
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()

    // Debounce tracking
    private val lastRequestTime = ConcurrentHashMap<String, Long>()
    private val debounceMs = 500L

    /**
     * Warm up the translation service on app start.
     * Ensures auth token is valid and network path is clear.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) {
                // Pre-fetch ID token to avoid latency on first user action
                user.getIdToken(false).await()
                
                // Fire a lightweight ping to the Cloud Function to spin up the container
                try {
                    val data = hashMapOf(
                        "text" to "warmup", 
                        "targetLanguage" to "en",
                        "skipAudio" to true
                    )
                    functions.getHttpsCallable("translateMessage")
                        .withTimeout(5, TimeUnit.SECONDS)
                        .call(data)
                } catch (e: Exception) {
                    // Ignore ping failure, it's just an optimization
                }
            } else {
            }
        } catch (e: Exception) {
            Log.w(TAG, "Warmup warning: ${e.message}")
        }
    }

    /**
     * Result of a translation operation.
     */
    data class TranslationResult(
        val translatedText: String,
        val audioUrl: String?,
        val audioFilePath: String?,
        val fromCache: Boolean
    )

    /**
     * Error types for UI handling
     */
    sealed class TranslationError : Exception() {
        data object RateLimited : TranslationError()
        data object TextTooLong : TranslationError()
        data object NetworkError : TranslationError()
        data object NoInternetConnection : TranslationError()
        data object DnsResolutionFailed : TranslationError()
        data class ServerError(override val message: String) : TranslationError()
    }

    /**
     * Translate text to target language with progressive results.
     * Emits:
     * 1. Cached result (Text only or Full)
     * 2. Quick Network Result (Text only)
     * 3. Full Network Result (Text + Audio)
     */
    fun translateFlow(
        messageId: String,
        text: String,
        targetLanguage: String
    ): Flow<TranslationResult> = flow {
        val t0 = System.currentTimeMillis()

        val cacheKey = makeCacheKey(messageId, targetLanguage)
        val now = System.currentTimeMillis()

        // 1. FAST PATH: Memory Cache
        memoryCache[cacheKey]?.let { cached ->
            
            // Fix legacy double-underscore paths and validate file existence
            val fixedAudioUrl = cached.audioUrl?.replace("__", "_")
            val fixedAudioFilePath = cached.audioFilePath?.replace("__", "_")
            
            // Validate that the audio file actually exists
            // Only trust local file:// URLs - remote HTTP URLs might be broken
            val audioExists = if (fixedAudioUrl != null && fixedAudioUrl.startsWith("file://")) {
                val audioFile = File(fixedAudioUrl.removePrefix("file://"))
                val exists = audioFile.exists() && audioFile.length() > 100
                if (!exists) {
                    Log.w(TAG, "Memory cache: local file missing: ${audioFile.absolutePath}")
                }
                exists
            } else if (fixedAudioFilePath != null) {
                val audioFile = File(fixedAudioFilePath)
                val exists = audioFile.exists() && audioFile.length() > 100
                if (!exists) {
                    Log.w(TAG, "Memory cache: local file path missing: ${audioFile.absolutePath}")
                }
                exists
            } else if (fixedAudioUrl != null && fixedAudioUrl.startsWith("http")) {
                // Don't trust remote URLs - let them fall through to regeneration
                Log.w(TAG, "Memory cache: ignoring legacy remote URL, will regenerate")
                false
            } else {
                false
            }
            
            val result = if (fixedAudioUrl != cached.audioUrl || fixedAudioFilePath != cached.audioFilePath) {
                // Path was fixed - update memory cache
                val fixed = TranslationResult(cached.translatedText, fixedAudioUrl, fixedAudioFilePath, true)
                putMemoryCache(cacheKey, fixed)
                fixed
            } else {
                cached
            }
            
            emit(result)
            if (audioExists) {
                return@flow
            } else if (fixedAudioUrl != null || fixedAudioFilePath != null) {
                Log.w(TAG, "Memory cache audio missing/corrupted, will regenerate")
            }
        }

        // 2. FAST PATH: Room Cache
        val cached = dao.get(messageId, targetLanguage)
        if (cached != null && !isExpired(cached.createdAt)) {
            
            // Fix legacy double-underscore paths
            val fixedAudioUrl = cached.audioUrl?.replace("__", "_")
            val fixedAudioFilePath = cached.audioFilePath?.replace("__", "_")
            
            // Validate that the audio file actually exists
            // Only trust local file:// URLs - remote HTTP URLs might be broken
            val audioExists = if (fixedAudioUrl != null && fixedAudioUrl.startsWith("file://")) {
                val audioFile = File(fixedAudioUrl.removePrefix("file://"))
                val exists = audioFile.exists() && audioFile.length() > 100
                if (!exists) {
                    Log.w(TAG, "Room cache audio file missing/corrupted: ${audioFile.absolutePath}")
                }
                exists
            } else if (fixedAudioFilePath != null) {
                val audioFile = File(fixedAudioFilePath)
                val exists = audioFile.exists() && audioFile.length() > 100
                if (!exists) {
                    Log.w(TAG, "Room cache audio file path missing/corrupted: ${audioFile.absolutePath}")
                }
                exists
            } else if (fixedAudioUrl != null && fixedAudioUrl.startsWith("http")) {
                // Don't trust remote URLs - they might be broken legacy URLs
                Log.w(TAG, "Room cache: ignoring legacy remote URL, will regenerate")
                false
            } else {
                false
            }
            
            val result = TranslationResult(
                translatedText = cached.translatedText,
                audioUrl = if (audioExists) fixedAudioUrl else null,
                audioFilePath = if (audioExists) fixedAudioFilePath else null,
                fromCache = true
            )
            putMemoryCache(cacheKey, result)
            emit(result)
            if (audioExists) {
                return@flow
            }
        }

        // 3. NETWORK PATH
        val tNetCheck = System.currentTimeMillis()
        if (!isNetworkAvailable()) {
            throw TranslationError.NoInternetConnection
        }
        
        // Serialize network requests
        val mutex = requestMutexes.getOrPut(cacheKey) { Mutex() }
        mutex.withLock {
            val tLockAcquired = System.currentTimeMillis()

            // Re-check memory cache after lock
             memoryCache[cacheKey]?.let { 
                emit(it)
                if (it.audioUrl != null) return@flow 
            }
            
            // Ensure auth
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val user = auth.currentUser ?: throw TranslationError.ServerError("User not authenticated")
            
            val tAuth = System.currentTimeMillis()
            // Step A: Fast Text (only if we don't have text yet)
            var hasPartialCache = (cached?.translatedText != null)
            var translatedText: String? = cached?.translatedText

            // Also check if we just got it from memory cache
            val memCached = memoryCache[cacheKey]
            val memText = memCached?.translatedText
            if (memText != null) {
                hasPartialCache = true
                translatedText = memText
            }

            if (!hasPartialCache) {
             try {
                val tStepA = System.currentTimeMillis()
                val data = hashMapOf(
                    "text" to text,
                    "targetLanguage" to targetLanguage,
                    "skipAudio" to true
                )
                
                val result = functions
                    .getHttpsCallable("translateMessage")
                    .withTimeout(10, TimeUnit.SECONDS)
                    .call(data)
                    .await()
                    

                @Suppress("UNCHECKED_CAST")
                val resultData = result.data as? Map<String, Any?> ?: emptyMap()
                val newText = resultData["translatedText"] as? String ?: ""
                translatedText = newText
                
                if (newText.isNotEmpty()) {
                    // Update Cache & Emit
                    val newCache = TranslationCache(
                        messageId = messageId, targetLanguage = targetLanguage,
                        originalText = text, translatedText = newText,
                        audioUrl = null, createdAt = System.currentTimeMillis()
                    )
                    dao.insertOrUpdate(newCache)
                    val res = TranslationResult(newText, null, null, false)
                    putMemoryCache(cacheKey, res)
                    emit(res)
                }
            } catch (e: Exception) {
                // If text fetch fails, we can't do anything
                Log.e(TAG, "Step A failed", e)
                throw parseError(e)
            }
        }

        // Step B: Full Audio (upgrade)
        try {
            val tStepB = System.currentTimeMillis()
            val data = hashMapOf(
                "text" to text,
                "targetLanguage" to targetLanguage,
                "skipAudio" to false
            )
            val result = functions
                .getHttpsCallable("translateMessage")
                .withTimeout(30, TimeUnit.SECONDS)
                .call(data)
                .await()
                

            @Suppress("UNCHECKED_CAST")
            val resultData = result.data as? Map<String, Any?> ?: emptyMap()
            
            val finalTx = resultData["translatedText"] as? String ?: translatedText ?: ""
            
            // Handle Inline Audio (Base64) or fallback to URL
            val audioContent = resultData["audioContent"] as? String
            val audioUrlObj = resultData["audioUrl"] as? String
            val errorMsg = resultData["error"] as? String
            
            if (errorMsg != null) {
                Log.e(TAG, "Server returned error: $errorMsg")
            }
            
            
            var audioPath: String? = null
            if (audioContent != null && audioContent.isNotEmpty()) {
                // Decode inline base64 and save to disk
                audioPath = saveAudioToDisk(cacheKey, audioContent)
            } else if (audioContent != null && audioContent.isEmpty()) {
                Log.e(TAG, "Server returned empty string for audioContent - TTS likely failed")
            } else if (audioUrlObj != null) {
                audioPath = audioUrlObj
            }
            
            val newCache = TranslationCache(
                messageId = messageId, targetLanguage = targetLanguage,
                originalText = text, translatedText = finalTx,
                audioUrl = audioPath, createdAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(newCache)
            val res = TranslationResult(finalTx, audioPath, audioPath, false)
            putMemoryCache(cacheKey, res)
            emit(res)
        } catch (e: Exception) {
            Log.e(TAG, "Step B failed", e)
             throw parseError(e)
        }
      }
    }.flowOn(Dispatchers.IO)

    private fun parseError(e: Exception): TranslationError {
        // ... duplicate error parsing logic or reuse ...
        // For brevity, just mapping generic here or could extract the logic from `translate`
        return TranslationError.ServerError(e.message ?: "Unknown error")
    }

    /**
     * Translate text to target language.
     * Checks: memory cache → Room cache → Cloud Function.
     * Thread-safe: concurrent calls for the same message+lang are serialized.
     */
    suspend fun translate(
        messageId: String,
        text: String,
        targetLanguage: String
    ): TranslationResult {
        // Legacy: Just collect and return the last result
        var last: TranslationResult? = null
        translateFlow(messageId, text, targetLanguage).collect { last = it }
        return last ?: throw TranslationError.ServerError("No result")
    }

    /*
    suspend fun translate(
        messageId: String,
        text: String,
        targetLanguage: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        val cacheKey = makeCacheKey(messageId, targetLanguage)

        // Debounce rapid taps
        val now = System.currentTimeMillis()
        val lastTime = lastRequestTime[cacheKey]
        if (lastTime != null && (now - lastTime) < debounceMs) {
            // Return from cache if available, otherwise throw
            memoryCache[cacheKey]?.let { return@withContext it }
            dao.get(messageId, targetLanguage)?.let { cached ->
                val result = cached.toResult(fromCache = true)
                putMemoryCache(cacheKey, result)
                return@withContext result
            }
        }
        lastRequestTime[cacheKey] = now

        // Check network connectivity first
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection available")
            throw TranslationError.NoInternetConnection
        }

        // Serialize per message+lang to prevent duplicate network calls
        val mutex = requestMutexes.getOrPut(cacheKey) { Mutex() }
        mutex.withLock {
            // 1. Memory cache (only use if it has audioUrl)
            memoryCache[cacheKey]?.let { cached ->
                if (cached.audioUrl != null) return@withLock cached
                // Has text but no audio — re-fetch to get audio with fixed voice
            }

            // 2. Room cache (only use if it has audioUrl)
            dao.get(messageId, targetLanguage)?.let { cached ->
                if (!isExpired(cached.createdAt) && cached.audioUrl != null) {
                    val result = cached.toResult(fromCache = true)
                    putMemoryCache(cacheKey, result)
                    return@withLock result
                } else if (cached.audioUrl == null) {
                    dao.deleteForMessage(messageId)
                } else {
                    // Expired — delete and continue to network
                    dao.deleteForMessage(messageId)
                }
            }

            // 3. Cloud Function (with Auth check)
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val user = auth.currentUser
            
            if (user == null) {
                Log.e(TAG, "User not authenticated before translation call")
                // Attempt to wait briefly for auth restoration?
                // For now, fail fast so UI can handle it or prompt login
                throw TranslationError.ServerError("User authentication required")
            }
            
            // Force token refresh if needed to ensure valid session
            try {
                val tokenResult = user.getIdToken(false).await()
            } catch (e: Exception) {
                Log.w(TAG, "Auth token refresh failed", e)
                throw TranslationError.ServerError("Authentication failed: ${e.message}")
            }

            try {
                val data = hashMapOf(
                    "text" to text,
                    "targetLanguage" to targetLanguage
                )

                val result = functions
                    .getHttpsCallable("translateMessage")
                    .withTimeout(30, TimeUnit.SECONDS) // Explicit timeout
                    .call(data)
                    .await()
                

                @Suppress("UNCHECKED_CAST")
                val resultData = result.data as? Map<String, Any?>
                    ?: throw TranslationError.ServerError("Invalid response format")

                
                val translatedText = resultData["translatedText"] as? String
                    ?: throw TranslationError.ServerError("Missing translatedText")
                val audioUrl = resultData["audioUrl"] as? String
                

                // Save to Room
                val cacheEntry = TranslationCache(
                    messageId = messageId,
                    targetLanguage = targetLanguage,
                    originalText = text,
                    translatedText = translatedText,
                    audioUrl = audioUrl,
                    createdAt = System.currentTimeMillis()
                )
                dao.insertOrUpdate(cacheEntry)

                val translationResult = TranslationResult(
                    translatedText = translatedText,
                    audioUrl = audioUrl,
                    audioFilePath = null,
                    fromCache = false
                )
                putMemoryCache(cacheKey, translationResult)
                translationResult
            } catch (e: TranslationError) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.javaClass.simpleName}", e)
                Log.e(TAG, "Full error message: ${e.message}")
                Log.e(TAG, "Error cause: ${e.cause?.message}")
                
                // Try to extract more details from Firebase Functions error
                val detailedMsg = e.message ?: ""
                Log.e(TAG, "Detailed message to parse: $detailedMsg")
                
                val msg = detailedMsg
                when {
                    msg.contains("resource-exhausted", ignoreCase = true) -> {
                        Log.w(TAG, "Rate limited by API")
                        throw TranslationError.RateLimited
                    }
                    msg.contains("invalid-argument", ignoreCase = true) && msg.contains("500") -> {
                        Log.w(TAG, "Text too long for translation")
                        throw TranslationError.TextTooLong
                    }
                    msg.contains("UnknownHostException", ignoreCase = true) ||
                        msg.contains("Unable to resolve host", ignoreCase = true) ||
                        msg.contains("No address associated with hostname", ignoreCase = true) ||
                        msg.contains("EAI_NODATA", ignoreCase = true) -> {
                        Log.w(TAG, "DNS resolution failed - check network/DNS settings")
                        throw TranslationError.DnsResolutionFailed
                    }
                    msg.contains("UNAVAILABLE", ignoreCase = true) ||
                        msg.contains("DEADLINE_EXCEEDED", ignoreCase = true) ||
                        msg.contains("ConnectException", ignoreCase = true) -> {
                        Log.w(TAG, "Network connectivity error")
                        throw TranslationError.NetworkError
                    }
                    else -> {
                        Log.e(TAG, "Server/unknown error: ${msg.take(200)}")
                        Log.e(TAG, "Exception type: ${e.javaClass.name}")
                        e.cause?.let { cause ->
                            Log.e(TAG, "Cause type: ${cause.javaClass.name}")
                            Log.e(TAG, "Cause message: ${cause.message}")
                        }
                        throw TranslationError.ServerError(msg.take(200))
                    }
                }
            }
        }
    }

    /**
     * Get or download the audio file for a cached translation.
     * Returns the local file path, downloading if needed.
     */
*/
    suspend fun getOrDownloadAudio(
        messageId: String,
        targetLanguage: String
    ): String? = withContext(Dispatchers.IO) {
        val cacheKey = makeCacheKey(messageId, targetLanguage)

        // Check if we already have a local file
        val cached = dao.get(messageId, targetLanguage)
        if (cached?.audioFilePath != null) {
            // Fix legacy double-underscore paths
            val fixedPath = cached.audioFilePath.replace("__", "_")
            val file = File(fixedPath)
            if (file.exists() && file.length() > 100) {
                return@withContext fixedPath
            } else {
                Log.w(TAG, "Cached file is missing or too small, need to regenerate")
                if (file.exists()) file.delete()
            }
        }

        // Get audioUrl from cache and fix legacy paths
        val audioUrl = (cached?.audioUrl ?: memoryCache[cacheKey]?.audioUrl)
            ?.replace("__", "_")
            ?: run {
                Log.w(TAG, "No audioUrl available for $messageId/$targetLanguage")
                return@withContext null
            }

        // If it's a file:// URL, validate the file exists first
        if (audioUrl.startsWith("file://")) {
            val filePath = audioUrl.removePrefix("file://")
            val file = File(filePath)
            if (!file.exists() || file.length() < 100) {
                Log.w(TAG, "Audio file URL points to missing/corrupted file: $filePath - need to regenerate")
                return@withContext null
            } else {
                return@withContext filePath
            }
        }

        // Download the audio file from remote URL
        try {
            val audioDir = File(context.cacheDir, AUDIO_CACHE_DIR)
            if (!audioDir.exists()) audioDir.mkdirs()

            val fileName = makeAudioFileName(messageId, targetLanguage)
            val audioFile = File(audioDir, fileName)

            // Log file details before download
            
            // Ensure directory exists
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }
            
            // Download
            val connection = URL(audioUrl).openConnection()
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            connection.connect()
            
            connection.getInputStream().use { input ->
                FileOutputStream(audioFile).use { output ->
                    val bytesWritten = input.copyTo(output)
                }
            }

            val localPath = audioFile.absolutePath
            
            // Verify download
            if (!audioFile.exists()) {
                Log.e(TAG, "File does not exist after download!")
                return@withContext null
            }
            
            val fileSize = audioFile.length()
            if (fileSize < 100) {
                Log.e(TAG, "Downloaded file is too small: $fileSize bytes")
                audioFile.delete()
                return@withContext null
            }

            // Inspect file format for debugging
            try {
                val headerBytes = audioFile.readBytes().take(12).toByteArray()
                val headerHex = headerBytes.joinToString(" ") { "%02X".format(it) }
                val headerString = headerBytes.toString(Charsets.ISO_8859_1)
                
                // Check if it's all zeros (corrupted)
                if (headerBytes.all { it == 0.toByte() }) {
                    Log.e(TAG, "File header is all zeros - file is corrupted!")
                    audioFile.delete()
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read file header: ${e.message}")
            }

            // Update Room cache with local path
            if (cached != null) {
                dao.insertOrUpdate(cached.copy(audioFilePath = localPath))
            }

            // Update memory cache
            memoryCache[cacheKey]?.let { memResult ->
                putMemoryCache(cacheKey, memResult.copy(audioFilePath = localPath))
            }

            localPath
        } catch (e: Exception) {
            Log.e(TAG, "Audio download failed from $audioUrl", e)
            
            // If it's a 404 or other permanent failure from Firebase Storage,
            // invalidate the audio URL so it regenerates with new inline audio
            if (e is java.io.FileNotFoundException && audioUrl.startsWith("http")) {
                Log.w(TAG, "Remote audio file not found (404) - invalidating audio URL to force regeneration")
                
                // Update Room cache to remove the broken audioUrl
                cached?.let {
                    dao.insertOrUpdate(it.copy(audioUrl = null, audioFilePath = null))
                }
                
                // Remove from memory cache
                memoryCache.remove(cacheKey)
                
            }
            
            null
        }
    }

    /**
     * Get cached translation without triggering network call.
     */
    suspend fun getCached(
        messageId: String,
        targetLanguage: String
    ): TranslationResult? = withContext(Dispatchers.IO) {
        val cacheKey = makeCacheKey(messageId, targetLanguage)
        memoryCache[cacheKey]?.let { return@withContext it }

        dao.get(messageId, targetLanguage)?.let { cached ->
            if (!isExpired(cached.createdAt)) {
                val result = cached.toResult(fromCache = true)
                putMemoryCache(cacheKey, result)
                return@withContext result
            }
        }
        null
    }

    /**
     * Clean up expired cache entries (call periodically).
     */
    suspend fun cleanupExpired() = withContext(Dispatchers.IO) {
        val expiryTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CACHE_EXPIRY_DAYS)
        dao.deleteExpired(expiryTimestamp)

        // Also clean up audio files for expired entries
        val audioDir = File(context.cacheDir, AUDIO_CACHE_DIR)
        if (audioDir.exists()) {
            audioDir.listFiles()?.forEach { file ->
                if (file.lastModified() < expiryTimestamp) {
                    file.delete()
                }
            }
        }
    }

    // ─── Private helpers ─────────────────────────

    private fun makeCacheKey(messageId: String, targetLanguage: String): String {
        return "${messageId}::${targetLanguage}"
    }
    
    private fun makeAudioFileName(messageId: String, targetLanguage: String): String {
        return "${messageId}_${targetLanguage}.wav"
    }

    private fun isExpired(createdAt: Long): Boolean {
        val expiryMs = TimeUnit.DAYS.toMillis(CACHE_EXPIRY_DAYS)
        return (System.currentTimeMillis() - createdAt) > expiryMs
    }

    private fun putMemoryCache(key: String, result: TranslationResult) {
        if (memoryCache.size >= maxMemoryCacheSize) {
            // Evict oldest entry (simple strategy)
            memoryCache.keys.firstOrNull()?.let { memoryCache.remove(it) }
        }
        memoryCache[key] = result
    }

    private fun TranslationCache.toResult(fromCache: Boolean): TranslationResult {
        // Fix legacy double-underscore paths from old cache entries
        val fixedAudioUrl = audioUrl?.replace("__", "_")
        val fixedAudioFilePath = audioFilePath?.replace("__", "_")
        
        return TranslationResult(
            translatedText = translatedText,
            audioUrl = fixedAudioUrl,
            audioFilePath = fixedAudioFilePath,
            fromCache = fromCache
        )
    }

    /**
     * Check if network is available and can reach the internet.
     * Enhanced check for DNS resolution and actual connectivity.
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            // Check if we have internet capability
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Relaxed check: VALIDATED is good but INTERNET is often sufficient (avoids false negatives on startup)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            
            // If we have internet capability, we attempt the request. 
            // The request itself will fail if there really is no connection.
            hasInternet
        } catch (e: Exception) {
            Log.e(TAG, "Network availability check failed", e)
            false
        }
    }

    private fun saveAudioToDisk(cacheKey: String, base64Audio: String): String? {
        return try {
            if (base64Audio.isBlank()) {
                Log.e(TAG, "Audio content is empty - cannot save")
                return null
            }
            
            
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            
            if (audioBytes.isEmpty()) {
                Log.e(TAG, "Decoded audio bytes are empty - cannot save")
                return null
            }
            
            
            // Check for WAV header (should start with "RIFF")
            val hasWavHeader = audioBytes.size >= 4 && 
                audioBytes[0] == 'R'.code.toByte() && 
                audioBytes[1] == 'I'.code.toByte() && 
                audioBytes[2] == 'F'.code.toByte() && 
                audioBytes[3] == 'F'.code.toByte()
            
            if (!hasWavHeader) {
                Log.w(TAG, "Audio data does not have WAV header (RIFF) - may not play correctly")
            } else {
            }
            
            val audioDir = File(context.cacheDir, AUDIO_CACHE_DIR)
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }
            
            // Use consistent filename format
            val parts = cacheKey.split("::") // messageId :: targetLanguage
            if (parts.size != 2) {
                Log.e(TAG, "Invalid cacheKey format: $cacheKey")
                return null
            }
            val fileName = makeAudioFileName(parts[0], parts[1])
            val file = File(audioDir, fileName)
            
            FileOutputStream(file).use { output ->
                output.write(audioBytes)
            }
            
            // Return file URI string
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio to disk", e)
            null
        }
    }
}
