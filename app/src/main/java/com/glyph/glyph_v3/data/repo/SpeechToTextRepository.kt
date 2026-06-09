package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Repository for Speech-to-Text operations.
 *
 * Responsibilities:
 * - Encode audio file to Base64
 * - Call Firebase Cloud Function (speechToText)
 * - Handle errors gracefully
 * - Thread-safe, coroutine-based
 *
 * Architecture:
 * - Singleton pattern (matches TranslationRepository)
 * - All heavy IO on Dispatchers.IO
 * - No API keys on client side (Cloud Function handles auth)
 */
class SpeechToTextRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToTextRepo"
        private const val FUNCTION_NAME = "speechToText"
        private const val TIMEOUT_SECONDS = 120L
        private const val MAX_AUDIO_SIZE_BYTES = 7_500_000L // ~7.5MB raw

        @Volatile
        private var INSTANCE: SpeechToTextRepository? = null

        fun getInstance(context: Context): SpeechToTextRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeechToTextRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    /**
     * Result of a speech-to-text operation.
     */
    data class SpeechToTextResult(
        val recognizedText: String?,
        val translatedText: String?,
        val timings: Map<String, Any>? = null
    )

    /**
     * Error types for UI handling.
     */
    sealed class SpeechToTextError(message: String) : Exception(message) {
        data object NoInternet : SpeechToTextError("No internet connection.")
        data object AudioTooLarge : SpeechToTextError("Recording is too long. Maximum 5 minutes.")
        data object NoSpeechDetected : SpeechToTextError("No speech detected in the recording.")
        data object RateLimited : SpeechToTextError("Too many requests. Please wait a moment.")
        data class ServerError(val errorMessage: String) : SpeechToTextError(errorMessage)
        data class UnknownError(val errorMessage: String) : SpeechToTextError(errorMessage)
    }

    /**
     * Convert recorded audio to text, optionally translating it.
     *
     * @param audioFile The recorded audio file (.m4a / AAC in MPEG-4 container)
     * @param targetLanguage Optional BCP-47 language code to translate into (e.g., "es", "fr")
     * @param languageHint Optional hint for the source language
     * @return SpeechToTextResult with recognized and optionally translated text
     * @throws SpeechToTextError on failure
     */
    suspend fun recognizeAndTranslate(
        audioFile: File,
        targetLanguage: String? = null,
        languageHint: String? = null
    ): SpeechToTextResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // 1. Validate network
        if (!isNetworkAvailable()) {
            throw SpeechToTextError.NoInternet
        }

        // 2. Validate file
        if (!audioFile.exists()) {
            throw SpeechToTextError.UnknownError("Audio file not found.")
        }
        if (audioFile.length() > MAX_AUDIO_SIZE_BYTES) {
            throw SpeechToTextError.AudioTooLarge
        }
        if (audioFile.length() < 1000) {
            throw SpeechToTextError.NoSpeechDetected
        }

        // 3. Ensure auth token is fresh (prevents UNAUTHENTICATED errors)
        try {
            auth.currentUser?.getIdToken(true)?.await()
        } catch (e: Exception) {
            Log.w(TAG, "Auth token refresh failed (non-fatal): ${e.message}")
        }

        // 4. Encode to Base64
        val audioBytes = audioFile.readBytes()
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        // 5. Build request data
        val requestData = hashMapOf<String, Any>(
            "audioBase64" to audioBase64
        )
        if (!targetLanguage.isNullOrBlank()) {
            requestData["targetLanguage"] = targetLanguage
        }
        if (!languageHint.isNullOrBlank()) {
            requestData["languageHint"] = languageHint
        }

        // 6. Call Cloud Function
        try {
            val result = functions.getHttpsCallable(FUNCTION_NAME)
                .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .call(requestData)
                .await()

            val resultData = result.data as? Map<*, *>
            if (resultData == null) {
                Log.e(TAG, "Null response from cloud function")
                throw SpeechToTextError.ServerError("Empty response from server.")
            }

            // Check for no_speech error
            val error = resultData["error"] as? String
            if (error == "no_speech") {
                throw SpeechToTextError.NoSpeechDetected
            }

            val recognizedText = resultData["recognizedText"] as? String
            val translatedText = resultData["translatedText"] as? String

            @Suppress("UNCHECKED_CAST")
            val timings = resultData["timings"] as? Map<String, Any>

            val elapsed = System.currentTimeMillis() - t0

            if (recognizedText.isNullOrBlank()) {
                throw SpeechToTextError.NoSpeechDetected
            }

            SpeechToTextResult(
                recognizedText = recognizedText,
                translatedText = translatedText,
                timings = timings
            )
        } catch (e: SpeechToTextError) {
            throw e // Re-throw our own errors
        } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
            Log.e(TAG, "Cloud function error: ${e.code} ${e.message}", e)
            when (e.code) {
                com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    throw SpeechToTextError.RateLimited
                com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    throw SpeechToTextError.ServerError(e.message ?: "Invalid input.")
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    throw SpeechToTextError.ServerError("Authentication required. Please sign in again.")
                else ->
                    throw SpeechToTextError.ServerError(e.message ?: "Server error. Please try again.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "STT failed", e)
            throw SpeechToTextError.UnknownError(e.message ?: "Unknown error occurred.")
        }
    }

    /**
     * Quick helper to check network connectivity.
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
