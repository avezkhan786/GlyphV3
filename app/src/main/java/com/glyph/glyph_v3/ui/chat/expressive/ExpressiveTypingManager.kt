package com.glyph.glyph_v3.ui.chat.expressive

import android.util.Log
import com.glyph.glyph_v3.data.repo.BlockRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Central orchestrator for the Live Expressive Typing feature.
 *
 * Coordinates:
 *   - LiveTypingRepository: sending/receiving real-time text
 *   - SentimentAnalysisService: AI-powered emotion detection
 *   - ExpressiveTypingPreferences: toggle/privacy logic
 *
 * Exposed state:
 *   - expressiveState: Flow<ExpressiveState> — everything the UI needs
 *
 * Sentiment Linger: When typing stops, sentiment animations remain visible
 * for SENTIMENT_LINGER_MS (2.5s) before fading out, giving users time to
 * see the emotional feedback.
 *
 * Lifecycle: create in ChatActivity/ViewModel, call cleanup() in onDestroy.
 */
class ExpressiveTypingManager(
    private val chatId: String,
    private val otherUserId: String,
    private val preferences: ExpressiveTypingPreferences,
    private val liveTypingRepo: LiveTypingRepository = LiveTypingRepository(),
    private val sentimentService: SentimentAnalysisService = SentimentAnalysisService()
) {

    companion object {
        private const val TAG = "ExpressiveManager"
        private const val SENTIMENT_LINGER_MS = 2500L // Keep animations visible for 2.5s after typing stops
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lingerJob: Job? = null

    /**
     * UI state emitted to the view layer.
     */
    data class ExpressiveState(
        val isFeatureActive: Boolean = false,  // Both users have it enabled
        val liveText: String = "",              // Text being typed by the other user
        val sentiment: SentimentType = SentimentType.NEUTRAL,
        val isOtherUserTypingExpressive: Boolean = false,
        val sourceTimestampMs: Long = 0L
    )

    private val _state = MutableStateFlow(ExpressiveState())
    val expressiveState: StateFlow<ExpressiveState> = _state.asStateFlow()

    private var observeJob: Job? = null

    /**
     * Start observing the other user's live typing and mutual feature toggle.
     * Call this when the chat screen becomes active.
     */
    fun startObserving() {
        observeJob?.cancel()
        lingerJob?.cancel()
        sentimentService.reset()
        _state.value = ExpressiveState()
        observeJob = scope.launch {
            // Combine mutual-enabled status with live typing data
            val mutualFlow = preferences.observeMutualEnabled(otherUserId)
            val liveTypingFlow = liveTypingRepo.observeLiveTyping(chatId, otherUserId)
            val blockStatusFlow = BlockRepository.observeBlockStatus(otherUserId)

            launch {
                combine(mutualFlow, liveTypingFlow, blockStatusFlow) { mutual, payload, blockStatus ->
                    Triple(mutual, payload, blockStatus)
                }.collectLatest { (mutual, payload, blockStatus) ->

                    // Cancel any pending linger timeout when new data arrives
                    if (payload != null && payload.liveText.isNotEmpty()) {
                        lingerJob?.cancel()
                    }

                    if (blockStatus.isBlocked || !mutual || payload == null || payload.liveText.isEmpty()) {
                        // Check if we currently have active content to linger
                        val currentState = _state.value
                        val shouldLinger = currentState.isOtherUserTypingExpressive && 
                            currentState.sentiment != SentimentType.NEUTRAL

                        if (shouldLinger) {
                            // Keep sentiment visible for a grace period before clearing
                            lingerJob?.cancel()
                            lingerJob = scope.launch {
                                delay(SENTIMENT_LINGER_MS)
                                _state.update { it.copy(
                                    isFeatureActive = mutual && !blockStatus.isBlocked,
                                    liveText = "",
                                    isOtherUserTypingExpressive = false,
                                    sentiment = SentimentType.NEUTRAL,
                                    sourceTimestampMs = 0L
                                ) }
                            }
                        } else {
                            // No active sentiment, clear immediately
                            _state.update { it.copy(
                                isFeatureActive = mutual && !blockStatus.isBlocked,
                                liveText = "",
                                isOtherUserTypingExpressive = false,
                                sentiment = SentimentType.NEUTRAL
                            ) }
                        }
                    } else {
                        // Feature active and we have live text
                        _state.update { it.copy(
                            isFeatureActive = true,
                            liveText = payload.liveText,
                            isOtherUserTypingExpressive = true,
                            sourceTimestampMs = payload.timestamp
                        ) }
                        // Trigger sentiment analysis (throttled internally)
                        sentimentService.analyzeText(payload.liveText)
                    }
                }
            }

            // Observe sentiment updates and propagate to state
            launch {
                sentimentService.sentiment.collectLatest { sentiment ->
                    _state.update { it.copy(sentiment = sentiment) }
                }
            }
        }
    }

    /**
     * Called when the local user types. Sends live text if feature is active.
     */
    fun onLocalTextChanged(text: String) {
        if (BlockRepository.getBlockStatus(otherUserId).isBlocked) {
            liveTypingRepo.clearLiveText(chatId, otherUserId)
            return
        }
        if (!_state.value.isFeatureActive && !preferences.isEnabled.value) {
            return
        }
        liveTypingRepo.sendLiveText(chatId, otherUserId, text)
    }

    /**
     * Called when a message is sent. Immediately clears live preview.
     */
    fun onMessageSent() {
        lingerJob?.cancel()
        liveTypingRepo.clearLiveText(chatId, otherUserId)
        sentimentService.reset()
    }

    /**
     * Stop observing (e.g., when leaving the chat screen or going to background).
     */
    fun stopObserving() {
        observeJob?.cancel()
        lingerJob?.cancel()
        liveTypingRepo.cleanup()
        sentimentService.reset()
        _state.value = ExpressiveState()
    }

    /**
     * Full cleanup — call in onDestroy.
     */
    fun destroy() {
        stopObserving()
        liveTypingRepo.destroy()
        sentimentService.destroy()
        scope.cancel()
    }
}
