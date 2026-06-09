package com.glyph.glyph_v3.ui.aiagent

import androidx.compose.runtime.Stable
import com.glyph.glyph_v3.data.local.entity.AiMessage
import com.glyph.glyph_v3.data.repo.AiAgentRepository
import com.glyph.glyph_v3.data.repo.AiMode

/**
 * UI state for the AI Agent screen.
 *
 * Follows the same `@Stable data class` pattern as `ChatUiState`.
 */
@Stable
data class AiAgentUiState(
    /** All conversation messages (sorted chronologically). */
    val messages: List<AiMessage> = emptyList(),

    /** Current AI mode tab. */
    val currentMode: AiMode = AiMode.CHAT,

    /** Whether an AI request is in flight. */
    val isLoading: Boolean = false,

    /** Last error, if any. Cleared on next successful send. */
    val error: String? = null,

    /** Whether user has granted search consent. */
    val searchConsentGranted: Boolean = false,

    /** Whether the consent bottom-sheet is currently shown. */
    val showSearchConsent: Boolean = false,

    /** Whether the user is currently recording voice input. */
    val isRecordingVoice: Boolean = false,

    /** Total messages stored locally. */
    val messageCount: Int = 0
)

/**
 * Sealed interface for one-shot UI events (Snackbar, navigation, etc.).
 */
sealed interface AiAgentEvent {
    data class ShowError(val message: String) : AiAgentEvent
    data class NavigateToChat(
        val chatId: String,
        val otherUserId: String,
        val otherUsername: String
    ) : AiAgentEvent
    data object ScrollToBottom : AiAgentEvent
    data object HistoryCleared : AiAgentEvent
    /** Emitted when the CF auto-routed to a different mode. */
    data class ModeAutoSwitched(val newMode: AiMode) : AiAgentEvent
}

/**
 * AI Agent sentinel constants used to identify the virtual chat entry.
 */
object AiAgentConstants {
    const val AI_AGENT_CHAT_ID = "glyph_ai_agent"
    const val AI_AGENT_USER_ID = "glyph_ai_agent"
    const val AI_AGENT_USERNAME = "Glyph AI"
    const val AI_AGENT_LAST_MESSAGE = "Ask me anything..."
}
