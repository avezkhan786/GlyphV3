package com.glyph.glyph_v3.ui.chat.expressive

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

/**
 * Performs sentiment analysis using Google Cloud Natural Language API
 * via Firebase Cloud Functions. Throttled to avoid excessive API calls.
 *
 * Architecture:
 *   Input text → debounce 600ms → Cloud Function → SentimentType result
 *   Previous in-flight jobs are cancelled when new text arrives.
 */
class SentimentAnalysisService {

    companion object {
        private const val TAG = "SentimentService"
        private const val THROTTLE_MS = 220L
        private const val MIN_TEXT_LENGTH = 3

        fun classifyTextLocally(text: String): SentimentType {
            val normalized = text.lowercase().trim()
            if (normalized.isEmpty()) return SentimentType.NEUTRAL

            return when {
                isRomanticText(normalized) -> SentimentType.ROMANTIC
                isFunnyText(normalized) -> SentimentType.FUNNY
                isAngryText(normalized) -> SentimentType.ANGRY
                isSadText(normalized) -> SentimentType.SAD
                isExcitedText(normalized) -> SentimentType.EXCITED
                isPositiveText(normalized) -> SentimentType.POSITIVE
                isNegativeText(normalized) -> SentimentType.NEGATIVE
                else -> SentimentType.NEUTRAL
            }
        }

        private fun isRomanticText(text: String): Boolean {
            val keywords = listOf("love", "miss you", "heart", "darling", "babe", "sweetheart",
                "honey", "kiss", "hug", "romantic", "❤", "💕", "💗", "😘", "🥰", "💋",
                "i love", "love you", "my love", "forever", "soulmate", "gorgeous", "beautiful")
            return keywords.any { text.contains(it) }
        }

        private fun isFunnyText(text: String): Boolean {
            val keywords = listOf("haha", "lol", "lmao", "rofl", "😂", "🤣", "😄",
                "funny", "hilarious", "joke", "dying", "dead 💀", "💀", "bruh",
                "ahahaha", "hehe", "jk", "😆")
            return keywords.any { text.contains(it) }
        }

        private fun isAngryText(text: String): Boolean {
            val keywords = listOf("angry", "furious", "hate", "mad", "annoyed", "frustrated",
                "pissed", "ugh", "wtf", "😡", "🤬", "😤", "💢", "rage", "sick of")
            return keywords.any { text.contains(it) }
        }

        private fun hasAngryIndicatorsText(text: String): Boolean {
            return text.contains("!") && (isAngryText(text) || text.count { it == '!' } >= 3)
        }

        private fun isSadText(text: String): Boolean {
            val keywords = listOf("sad", "crying", "depressed", "lonely", "heartbroken",
                "miss", "sorry", "😢", "😭", "🥺", "💔", "upset", "cry", "tears",
                "hurts", "pain", "broken")
            return keywords.any { text.contains(it) }
        }

        private fun isExcitedText(text: String): Boolean {
            val keywords = listOf("amazing", "awesome", "omg", "can't wait", "excited",
                "yay", "woah", "incredible", "🎉", "🔥", "⚡", "🚀", "!!!",
                "let's go", "so good", "insane", "fire")
            val exclamationCount = text.count { it == '!' }
            return keywords.any { text.contains(it) } || exclamationCount >= 3
        }

        private fun isPositiveText(text: String): Boolean {
            val keywords = listOf("good", "great", "nice", "happy", "thanks", "thank",
                "wonderful", "perfect", "cool", "fine", "glad", "pleased", "😊",
                "👍", "appreciate", "excellent", "fantastic")
            return keywords.any { text.contains(it) }
        }

        private fun isNegativeText(text: String): Boolean {
            val keywords = listOf("bad", "terrible", "awful", "horrible", "wrong",
                "disappointed", "unfortunate", "sucks", "worst", "not good")
            return keywords.any { text.contains(it) }
        }
    }

    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var analysisJob: Job? = null

    private val _sentiment = MutableStateFlow(SentimentType.NEUTRAL)
    val sentiment: StateFlow<SentimentType> = _sentiment.asStateFlow()

    /**
     * Submit text for sentiment analysis. Automatically throttles & cancels previous jobs.
     */
    fun analyzeText(text: String) {
        // Cancel any in-flight analysis
        analysisJob?.cancel()

        if (text.length < MIN_TEXT_LENGTH) {
            _sentiment.value = SentimentType.NEUTRAL
            return
        }

        val localSentiment = analyzeLocally(text)
        _sentiment.value = localSentiment

        // If local analysis already identifies a strong expressive class, skip cloud analysis.
        // This keeps typing feedback immediate and reduces network traffic.
        if (localSentiment != SentimentType.NEUTRAL) {
            return
        }

        analysisJob = scope.launch {
            delay(THROTTLE_MS) // Throttle: wait before making API call
            try {
                val result = callSentimentFunction(text)
                if (isActive) {
                    _sentiment.value = result
                }
            } catch (e: CancellationException) {
                // Expected when new text arrives before analysis completes
            } catch (e: Exception) {
                Log.e(TAG, "Sentiment analysis failed, using local fallback", e)
                if (isActive) {
                    _sentiment.value = analyzeLocally(text)
                }
            }
        }
    }

    /**
     * Call Firebase Cloud Function for sentiment analysis.
     * The Cloud Function wraps Google Cloud Natural Language API.
     */
    private suspend fun callSentimentFunction(text: String): SentimentType {
        return try {
            val data = hashMapOf("text" to text)
            val result = functions.getHttpsCallable("analyzeSentiment").call(data).await()
            @Suppress("UNCHECKED_CAST")
            val response = result.data as? Map<String, Any?> ?: return analyzeLocally(text)
            val score = (response["score"] as? Number)?.toFloat() ?: 0f
            val magnitude = (response["magnitude"] as? Number)?.toFloat() ?: 0f
            mapScoreToSentiment(score, magnitude, text)
        } catch (e: Exception) {
            Log.w(TAG, "Cloud function unavailable, using local analysis", e)
            analyzeLocally(text)
        }
    }

    /**
     * Maps NLP API score (-1.0 to 1.0) and magnitude to a SentimentType.
     * Also inspects text for nuanced categories (romantic, funny, excited).
     */
    private fun mapScoreToSentiment(score: Float, magnitude: Float, text: String): SentimentType {
        val lowerText = text.lowercase()

        // Check for specific sentiment patterns first
        if (isRomantic(lowerText)) return SentimentType.ROMANTIC
        if (isFunny(lowerText)) return SentimentType.FUNNY

        return when {
            score > 0.5f && magnitude > 1.5f -> SentimentType.EXCITED
            score > 0.25f -> SentimentType.POSITIVE
            score < -0.5f && magnitude > 1.5f -> SentimentType.ANGRY
            score < -0.25f && hasAngryIndicators(lowerText) -> SentimentType.ANGRY
            score < -0.25f -> SentimentType.SAD
            score < -0.1f -> SentimentType.NEGATIVE
            else -> SentimentType.NEUTRAL
        }
    }

    /**
     * Local keyword-based fallback when Cloud Function is unavailable.
     * Not as accurate but provides immediate results.
     */
    private fun analyzeLocally(text: String): SentimentType {
        return classifyTextLocally(text)
    }

    private fun isRomantic(text: String): Boolean {
        return isRomanticText(text)
    }

    private fun isFunny(text: String): Boolean {
        return isFunnyText(text)
    }

    private fun isAngry(text: String): Boolean {
        return isAngryText(text)
    }

    private fun hasAngryIndicators(text: String): Boolean {
        return hasAngryIndicatorsText(text)
    }

    private fun isSad(text: String): Boolean {
        return isSadText(text)
    }

    private fun isExcited(text: String): Boolean {
        return isExcitedText(text)
    }

    private fun isPositive(text: String): Boolean {
        return isPositiveText(text)
    }

    private fun isNegative(text: String): Boolean {
        return isNegativeText(text)
    }

    fun reset() {
        analysisJob?.cancel()
        _sentiment.value = SentimentType.NEUTRAL
    }

    fun destroy() {
        analysisJob?.cancel()
        scope.cancel()
    }
}
