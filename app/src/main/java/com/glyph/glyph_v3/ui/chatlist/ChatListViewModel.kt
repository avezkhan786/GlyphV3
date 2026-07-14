package com.glyph.glyph_v3.ui.chatlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.Chat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import androidx.compose.runtime.Stable
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository

@Stable
data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val selectedChatIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val isInitialLoading: Boolean = true,
    val archivedChatsCount: Int = 0,
    val hasUnreadArchivedMessages: Boolean = false,
    val lockedChatsCount: Int = 0,
    val hasUnreadLockedMessages: Boolean = false,
    val isLockedChatsHidden: Boolean = false,
    val secretCodeMatch: Boolean = false,
    /** Non-empty while a delete is pending undo. Chats are hidden but not yet removed from DB. */
    val pendingDeleteChatIds: Set<String> = emptySet(),
    /** 0f..1f progress of the undo countdown (1f = time is up). */
    val undoProgress: Float = 0f,
    /** True while the undo Snackbar should be visible. */
    val showUndoBar: Boolean = false,
    /** How many chats are in the current pending-delete batch (for label). */
    val pendingDeleteCount: Int = 0,
    /** Incremented each time the search bar should be cleared (e.g. on resume after locked chats). */
    val clearSearchTrigger: Int = 0
)

class ChatListViewModel(
    private var repository: RealtimeMessageRepository? = null,
    private val cacheKey: String = "main"
) : ViewModel() {

    companion object {
        private const val TAG = "ChatListViewModel"
        /** Duration of the undo countdown in milliseconds. */
        const val UNDO_TIMEOUT_MS = 5_000L
        private const val UNDO_TICK_MS = 50L

        /**
         * Process-level snapshot: survives Activity/Fragment recreation within the same process.
         * Keyed by mode ("main", "archived", "locked") to avoid cross-contamination.
         * Cleared only when the process is fully killed by the OS.
         */
        private val chatSnapshot = mutableMapOf<String, List<Chat>>()

        /**
         * Pre-seed the process-level cache from Application cold-start prefetching.
         * When called before the first ViewModel is created, the ViewModel starts with
         * [isInitialLoading] = false and chats already populated, eliminating the
         * shimmer-placeholder step on cold start.
         */
        fun prewarmCache(cacheKey: String, chats: List<Chat>) {
            if (chats.isNotEmpty()) {
                chatSnapshot[cacheKey] = chats
            }
        }
    }

    private val _uiState = MutableStateFlow(
        ChatListUiState(
            chats = chatSnapshot[cacheKey] ?: emptyList(),
            isInitialLoading = chatSnapshot[cacheKey].isNullOrEmpty()
        )
    )
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    /** Job for the undo countdown timer — cancelled on undo. */
    private var undoTimerJob: Job? = null

    fun attachRepository(repository: RealtimeMessageRepository) {
        this.repository = repository
    }

    fun updateChats(chats: List<Chat>) {
        chatSnapshot[cacheKey] = chats
        _uiState.update { it.copy(chats = chats, isInitialLoading = false) }
    }

    fun updateArchivedChatsCount(count: Int, hasUnread: Boolean) {
        _uiState.update { it.copy(archivedChatsCount = count, hasUnreadArchivedMessages = hasUnread) }
    }

    private fun applyArchiveSelectionLocally(selectedChatIds: Set<String>, archive: Boolean) {
        if (selectedChatIds.isEmpty()) return

        _uiState.update { currentState ->
            val selectedChats = currentState.chats.filter { it.id in selectedChatIds }
            val remainingChats = currentState.chats.filterNot { it.id in selectedChatIds }
            val nextArchivedCount = if (archive) {
                currentState.archivedChatsCount + selectedChats.size
            } else {
                (currentState.archivedChatsCount - selectedChats.size).coerceAtLeast(0)
            }
            val nextHasUnreadArchivedMessages = if (archive) {
                currentState.hasUnreadArchivedMessages || selectedChats.any { it.unreadCount > 0 }
            } else {
                remainingChats.any { it.unreadCount > 0 }
            }

            chatSnapshot[cacheKey] = remainingChats
            currentState.copy(
                chats = remainingChats,
                archivedChatsCount = nextArchivedCount,
                hasUnreadArchivedMessages = nextHasUnreadArchivedMessages,
                selectedChatIds = emptySet(),
                isSelectionMode = false,
                showDeleteConfirmation = false
            )
        }
    }

    fun updateLockedChatsCount(count: Int, hasUnread: Boolean = false) {
        _uiState.update { it.copy(lockedChatsCount = count, hasUnreadLockedMessages = hasUnread) }
    }

    fun updateLockedChatsHidden(hidden: Boolean) {
        _uiState.update { it.copy(isLockedChatsHidden = hidden) }
    }

    fun updateSecretCodeMatch(match: Boolean) {
        _uiState.update { it.copy(secretCodeMatch = match) }
    }

    fun clearSearchBar() {
        _uiState.update { it.copy(secretCodeMatch = false, clearSearchTrigger = it.clearSearchTrigger + 1) }
    }

    fun toggleSelection(chatId: String) {
        _uiState.update { currentState ->
            val newSelection = currentState.selectedChatIds.toMutableSet()
            if (newSelection.contains(chatId)) {
                newSelection.remove(chatId)
            } else {
                newSelection.add(chatId)
            }
            
            currentState.copy(
                selectedChatIds = newSelection,
                isSelectionMode = newSelection.isNotEmpty()
            )
        }
    }
    
    fun selectChat(chatId: String) {
         _uiState.update { currentState ->
            val newSelection = currentState.selectedChatIds.toMutableSet()
            newSelection.add(chatId)
            currentState.copy(
                selectedChatIds = newSelection,
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update { 
            it.copy(
                selectedChatIds = emptySet(),
                isSelectionMode = false,
                showDeleteConfirmation = false
            )
        }
    }

    fun requestDeleteSelectedChats() {
         _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun confirmDeleteSelectedChats() {
        val selected = _uiState.value.selectedChatIds.toSet()
        if (selected.isEmpty()) return

        // Cancel any previous undo timer (commit that batch immediately)
        commitPendingDeleteNow()

        // Start soft-delete: hide chats, show undo bar, begin countdown
        _uiState.update {
            it.copy(
                pendingDeleteChatIds = selected,
                pendingDeleteCount = selected.size,
                showUndoBar = true,
                undoProgress = 0f,
                showDeleteConfirmation = false,
                selectedChatIds = emptySet(),
                isSelectionMode = false
            )
        }

        // Start countdown timer
        undoTimerJob = viewModelScope.launch {
            val steps = (UNDO_TIMEOUT_MS / UNDO_TICK_MS).toInt()
            for (i in 1..steps) {
                delay(UNDO_TICK_MS)
                _uiState.update { it.copy(undoProgress = i.toFloat() / steps) }
            }
            // Timer expired — commit the delete
            performActualDelete(selected)
        }
    }

    /** User tapped "Undo" — cancel the pending delete and restore chats. */
    fun undoPendingDelete() {
        undoTimerJob?.cancel()
        undoTimerJob = null
        _uiState.update {
            it.copy(
                pendingDeleteChatIds = emptySet(),
                pendingDeleteCount = 0,
                showUndoBar = false,
                undoProgress = 0f
            )
        }
    }

    /** Immediately commit any pending delete (e.g. before starting a new one). */
    private fun commitPendingDeleteNow() {
        undoTimerJob?.cancel()
        undoTimerJob = null
        val pending = _uiState.value.pendingDeleteChatIds
        if (pending.isNotEmpty()) {
            viewModelScope.launch { performActualDelete(pending) }
        }
    }

    /** Actually remove chats from the local database. */
    private suspend fun performActualDelete(chatIds: Set<String>) {
        try {
            repository?.deleteChats(chatIds.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete chats", e)
        } finally {
            _uiState.update {
                it.copy(
                    pendingDeleteChatIds = emptySet(),
                    pendingDeleteCount = 0,
                    showUndoBar = false,
                    undoProgress = 0f
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Commit any pending delete when ViewModel is destroyed (user leaves screen)
        commitPendingDeleteNow()
    }
    
    fun deleteSelectedChats() {
        requestDeleteSelectedChats()
    }
    
    fun pinSelectedChats() {
        // TODO: Implement pin logic
        clearSelection()
    }
    
    fun archiveSelectedChats() {
        val selected = _uiState.value.selectedChatIds.toSet()
        if (selected.isEmpty()) return
        val activeRepository = repository ?: run {
            clearSelection()
            return
        }

        applyArchiveSelectionLocally(selected, archive = true)
        
        viewModelScope.launch {
            try {
                activeRepository.archiveChats(selected.toList(), true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to archive chats - notifications may still appear", e)
            }
        }
    }
    
    fun unarchiveSelectedChats() {
        val selected = _uiState.value.selectedChatIds.toSet()
        if (selected.isEmpty()) return
        val activeRepository = repository ?: run {
            clearSelection()
            return
        }

        applyArchiveSelectionLocally(selected, archive = false)
        
        viewModelScope.launch {
            try {
                activeRepository.archiveChats(selected.toList(), false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unarchive chats", e)
            }
        }
    }

    fun muteSelectedChats() {
        // TODO: Implement mute logic
        clearSelection()
    }
}

class ChatListViewModelFactory(
    private val repository: RealtimeMessageRepository? = null,
    private val cacheKey: String = "main"
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatListViewModel(repository, cacheKey) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
