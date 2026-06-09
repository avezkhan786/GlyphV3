package com.glyph.glyph_v3.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.LiveAudioAudience
import com.glyph.glyph_v3.data.models.LiveAudioConfig
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.LiveAudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LiveAudioSettingsState(
    val isLoading: Boolean = true,
    val shareMicEnabled: Boolean = false,
    val audience: LiveAudioAudience = LiveAudioAudience.EVERYONE,
    val allowedUserIds: Set<String> = emptySet(),
    val allUsers: List<User> = emptyList(),
    val isStreaming: Boolean = false,
    val listenerCount: Int = 0,
    val searchQuery: String = "",
    val savedSuccessfully: Boolean = false,
    val error: String? = null
) {
    /** Users filtered by search query. */
    val filteredUsers: List<User>
        get() = if (searchQuery.isBlank()) allUsers
        else allUsers.filter {
            it.username.contains(searchQuery, ignoreCase = true) ||
                    it.phoneNumber.contains(searchQuery)
        }
}

class LiveAudioSettingsViewModel : ViewModel() {

    private data class PersistedState(
        val shareMicEnabled: Boolean,
        val audience: LiveAudioAudience,
        val allowedUserIds: Set<String>
    )

    companion object {
        private const val TAG = "LiveAudioSettingsVM"
    }

    private val repository = LiveAudioRepository()
    private val firebaseRepository = FirebaseRepository()
    private var persistedState: PersistedState? = null

    private val _state = MutableStateFlow(LiveAudioSettingsState())
    val state: StateFlow<LiveAudioSettingsState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                // Load config and users in parallel
                val config = repository.getMyConfig()
                val users = suspendCancellableCoroutine<List<User>> { cont ->
                    firebaseRepository.getAllUsers(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resume(emptyList()) }
                    )
                }

                _state.update { s ->
                    s.copy(
                        isLoading = false,
                        shareMicEnabled = config?.shareMic ?: false,
                        audience = config?.audienceEnum() ?: LiveAudioAudience.SELECTED_USERS,
                        allowedUserIds = config?.allowedUsers?.toSet() ?: emptySet(),
                        isStreaming = config?.isStreaming ?: false,
                        listenerCount = config?.currentListeners?.size ?: 0,
                        allUsers = users
                    )
                }
                persistedState = PersistedState(
                    shareMicEnabled = config?.shareMic ?: false,
                    audience = config?.audienceEnum() ?: LiveAudioAudience.SELECTED_USERS,
                    allowedUserIds = config?.allowedUsers?.toSet() ?: emptySet()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings", e)
                _state.update { it.copy(isLoading = false, error = "Failed to load settings") }
            }
        }
    }

    fun toggleShareMic(enabled: Boolean) {
        _state.update { it.copy(shareMicEnabled = enabled) }
        if (!enabled) {
            _state.update { it.copy(isStreaming = false, listenerCount = 0) }
        }
        // Auto-save immediately so other users see the change in real-time
        // without requiring an explicit "Save" tap.
        viewModelScope.launch {
            try {
                val s = _state.value
                val config = LiveAudioConfig(
                    shareMic = enabled,
                    audience = s.audience.name,
                    allowedUsers = s.allowedUserIds.toList(),
                    isStreaming = if (!enabled) false else s.isStreaming,
                    currentListeners = emptyList()
                )
                repository.saveConfig(config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-save shareMic toggle", e)
            }
        }
    }

    fun setAudience(audience: LiveAudioAudience) {
        _state.update { it.copy(audience = audience) }
    }

    fun toggleUser(userId: String) {
        _state.update { s ->
            val newSet = if (userId in s.allowedUserIds) {
                s.allowedUserIds - userId
            } else {
                s.allowedUserIds + userId
            }
            s.copy(allowedUserIds = newSet)
        }
    }

    fun updateSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun save() {
        viewModelScope.launch {
            try {
                val s = _state.value
                val pendingState = PersistedState(
                    shareMicEnabled = s.shareMicEnabled,
                    audience = s.audience,
                    allowedUserIds = s.allowedUserIds
                )
                if (pendingState == persistedState) {
                    _state.update { it.copy(savedSuccessfully = true) }
                    return@launch
                }
                val config = LiveAudioConfig(
                    shareMic = s.shareMicEnabled,
                    audience = s.audience.name,
                    allowedUsers = s.allowedUserIds.toList(),
                    isStreaming = if (!s.shareMicEnabled) false else s.isStreaming,
                    currentListeners = if (!s.shareMicEnabled) emptyList()
                    else emptyList() // Listeners managed by manager at runtime
                )
                repository.saveConfig(config)
                persistedState = pendingState
                _state.update { it.copy(savedSuccessfully = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
                _state.update { it.copy(error = "Failed to save") }
            }
        }
    }

    fun stopSharingNow() {
        viewModelScope.launch {
            try {
                repository.updateConfigFields(
                    mapOf(
                        "isStreaming" to false,
                        "currentListeners" to emptyList<String>()
                    )
                )
                val ctx = com.glyph.glyph_v3.data.service.LiveAudioSharingManager
                // The manager's stopBroadcasting will be called from the activity
                _state.update { it.copy(isStreaming = false, listenerCount = 0) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop sharing", e)
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
