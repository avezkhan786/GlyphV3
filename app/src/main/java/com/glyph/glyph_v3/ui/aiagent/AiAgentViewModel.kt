package com.glyph.glyph_v3.ui.aiagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.local.entity.AiMessage
import com.glyph.glyph_v3.data.repo.AiAgentRepository
import com.glyph.glyph_v3.data.repo.AiMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI Agent screen.
 *
 * Responsibilities:
 * • Collects messages from Room via [AiAgentRepository.getMessages]
 * • Dispatches user messages to the Cloud Function
 * • Manages mode switching and consent flow
 * • Emits one-shot events via SharedFlow
 *
 * Follows the same Factory pattern as [ChatViewModel].
 */
class AiAgentViewModel(
    private val repository: AiAgentRepository,
    private val prefs: SharedPreferences
) : ViewModel() {

    companion object {
        private const val TAG = "AiAgentVM"
        private const val PREF_SEARCH_CONSENT = "ai_search_consent_granted"
    }

    // ─── State ───────────────────────────────────────────

    private val _uiState = MutableStateFlow(
        AiAgentUiState(
            searchConsentGranted = prefs.getBoolean(PREF_SEARCH_CONSENT, false)
        )
    )
    val uiState: StateFlow<AiAgentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AiAgentEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AiAgentEvent> = _events.asSharedFlow()

    // ─── Init ────────────────────────────────────────────

    init {
        observeMessages()
        warmUp()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            repository.getMessages()
                .distinctUntilChanged()
                .catch { e ->
                    Log.e(TAG, "Error observing messages", e)
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages, messageCount = messages.size) }
                    // Auto-scroll when a new message arrives
                    if (messages.isNotEmpty()) {
                        _events.emit(AiAgentEvent.ScrollToBottom)
                    }
                }
        }
    }

    private fun warmUp() {
        viewModelScope.launch {
            try {
                repository.warmUp()
            } catch (e: Exception) {
            }
        }
    }

    // ─── Public actions ──────────────────────────────────

    /**
     * Send a text message to the AI Agent in the current mode.
     */
    fun sendMessage(text: String, searchOptions: Map<String, String>? = null) {
        val currentMode = _uiState.value.currentMode

        // Gate search mode behind consent
        if (currentMode == AiMode.SEARCH && !_uiState.value.searchConsentGranted) {
            _uiState.update { it.copy(showSearchConsent = true) }
            return
        }

        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = repository.sendMessage(text, currentMode, searchOptions)
                _uiState.update { it.copy(isLoading = false) }

                // Auto-switch mode if the CF routed to a different one
                response.suggestedMode?.let { suggested ->
                    val newMode = AiMode.fromValue(suggested)
                    if (newMode != currentMode) {
                        _uiState.update { it.copy(currentMode = newMode) }
                        _events.emit(AiAgentEvent.ModeAutoSwitched(newMode))
                    }
                }
            } catch (e: AiAgentRepository.AgentError) {
                Log.e(TAG, "Agent error: ${e.message}")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _events.emit(AiAgentEvent.ShowError(e.message ?: "Something went wrong"))
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = "Something went wrong") }
                _events.emit(AiAgentEvent.ShowError("Something went wrong"))
            }
        }
    }

    /**
     * Switch AI mode tab.
     */
    fun switchMode(mode: AiMode) {
        _uiState.update { it.copy(currentMode = mode, error = null) }
    }

    /**
     * Trigger one of the weekly dashboard shortcuts.
     * Automatically switches to Search mode, gates on consent, then sends the
     * query with a `shortcutType` option so the Cloud Function uses the
     * dedicated weekly-insight handler instead of a keyword search.
     *
     * @param shortcutType one of "weekly_summary", "weekly_documents", "weekly_media"
     * @param displayText  the human-readable label shown in the chat bubble
     */
    fun sendShortcutQuery(shortcutType: String, displayText: String) {
        // 1. Force Search mode and update chip via event
        if (_uiState.value.currentMode != AiMode.SEARCH) {
            _uiState.update { it.copy(currentMode = AiMode.SEARCH, error = null) }
            viewModelScope.launch {
                _events.emit(AiAgentEvent.ModeAutoSwitched(AiMode.SEARCH))
            }
        }

        // 2. Gate behind consent just like sendMessage() does
        if (!_uiState.value.searchConsentGranted) {
            _uiState.update { it.copy(showSearchConsent = true) }
            return
        }

        // 3. Dispatch with the shortcutType option so the CF routes to the right handler
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.sendMessage(
                    text = displayText,
                    mode = AiMode.SEARCH,
                    searchOptions = mapOf("shortcutType" to shortcutType)
                )
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: AiAgentRepository.AgentError) {
                Log.e(TAG, "Shortcut error: ${e.message}")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _events.emit(AiAgentEvent.ShowError(e.message ?: "Something went wrong"))
            } catch (e: Exception) {
                Log.e(TAG, "Shortcut unexpected error: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false) }
                _events.emit(AiAgentEvent.ShowError("Something went wrong"))
            }
        }
    }

    /**
     * User granted consent for search mode.
     */
    fun grantSearchConsent() {
        prefs.edit().putBoolean(PREF_SEARCH_CONSENT, true).apply()
        _uiState.update {
            it.copy(
                searchConsentGranted = true,
                showSearchConsent = false
            )
        }
    }

    /**
     * User dismissed the consent dialog.
     */
    fun dismissSearchConsent() {
        _uiState.update { it.copy(showSearchConsent = false) }
    }

    /**
     * Clear all conversation history.
     */
    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                repository.clearHistory()
                _uiState.update { it.copy(isLoading = false) }
                _events.emit(AiAgentEvent.HistoryCleared)
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Dismiss the current error.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Navigate to a specific chat (from search result source citation).
     */
    fun navigateToSourceChat(source: AiAgentRepository.SourceCitation) {
        // Resolve the actual other participant from chatId (format: uid1_uid2)
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val parts = source.chatId.split("_")
        val otherUid = parts.firstOrNull { it != currentUid } ?: source.senderId

        viewModelScope.launch {
            _events.emit(
                AiAgentEvent.NavigateToChat(
                    chatId = source.chatId,
                    otherUserId = otherUid,
                    otherUsername = source.conversationWith
                )
            )
        }
    }
}

/**
 * Factory for [AiAgentViewModel].
 *
 * Follows the same pattern as `ChatViewModelFactory`.
 */
class AiAgentViewModelFactory(
    private val repository: AiAgentRepository,
    private val prefs: SharedPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiAgentViewModel::class.java)) {
            return AiAgentViewModel(repository, prefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
