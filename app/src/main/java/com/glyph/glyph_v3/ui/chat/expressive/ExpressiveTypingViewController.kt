package com.glyph.glyph_v3.ui.chat.expressive

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Lightweight state observer that bridges ExpressiveTypingManager with the
 * RecyclerView-based typing indicator.
 *
 * Instead of managing an overlay, this controller observes the manager's state
 * and notifies the ChatActivity via [onExpressiveStateChanged]. The activity then
 * passes the expressive data through the ChatListItem.TypingIndicator to the
 * ViewHolder, which renders it inline as a chat bubble.
 *
 * Usage:
 *   val controller = ExpressiveTypingViewController()
 *   controller.onExpressiveStateChanged = { isActive, text, sentiment, isMutuallyEnabled, sourceTimestampMs -> ... }
 *   controller.bind(manager, lifecycleScope)
 *   // cleanup:
 *   controller.unbind()
 */
class ExpressiveTypingViewController {

    companion object {
        private const val TAG = "ExpressiveVC"
    }

    /**
     * Callback invoked whenever the expressive state changes.
     * Parameters: isActive (has text?), liveText, sentiment, isMutuallyEnabled
     */
    var onExpressiveStateChanged: ((Boolean, String, SentimentType, Boolean, Long) -> Unit)? = null

    private var bindJob: Job? = null
    private var wasActive = false
    private var wasMutuallyEnabled = false
    private var lastLiveText = ""
    private var lastSentiment: SentimentType = SentimentType.NEUTRAL

    /**
     * Bind to an ExpressiveTypingManager and relay state changes to the callback.
     */
    fun bind(manager: ExpressiveTypingManager, scope: CoroutineScope) {
        bindJob?.cancel()
        bindJob = scope.launch {
            manager.expressiveState.collectLatest { state ->
                withContext(Dispatchers.Main) {
                    val isActive = state.isOtherUserTypingExpressive && 
                                   state.liveText.isNotEmpty()
                    
                    val isMutuallyEnabled = state.isFeatureActive

                    val textChanged = state.liveText != lastLiveText
                    val sentimentChanged = state.sentiment != lastSentiment
                    if (isActive != wasActive || isMutuallyEnabled != wasMutuallyEnabled || textChanged || sentimentChanged) {
                        wasActive = isActive
                        wasMutuallyEnabled = isMutuallyEnabled
                        lastLiveText = state.liveText
                        lastSentiment = state.sentiment
                        onExpressiveStateChanged?.invoke(
                            isActive,
                            state.liveText,
                            state.sentiment,
                            isMutuallyEnabled,
                            state.sourceTimestampMs
                        )
                    }
                }
            }
        }
    }

    /**
     * Clean up.
     */
    fun unbind() {
        bindJob?.cancel()
        wasActive = false
        wasMutuallyEnabled = false
        lastLiveText = ""
        lastSentiment = SentimentType.NEUTRAL
        onExpressiveStateChanged = null
    }
}
