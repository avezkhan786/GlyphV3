package com.glyph.glyph_v3.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptScope
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptSettings
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalkieTalkieSettingsState(
    val isLoading: Boolean = true,
    val autoAcceptEnabled: Boolean = false,
    val scope: WalkieTalkieAutoAcceptScope = WalkieTalkieAutoAcceptScope.CONTACTS,
    val allowedUserIds: Set<String> = emptySet(),
    val contacts: List<User> = emptyList(),
    val searchQuery: String = "",
    val savedSuccessfully: Boolean = false,
    val error: String? = null
) {
    val filteredContacts: List<User>
        get() = if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.username.contains(searchQuery, ignoreCase = true) ||
                    it.phoneNumber.contains(searchQuery, ignoreCase = true)
            }
        }
}

class WalkieTalkieSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private data class PersistedState(
        val autoAcceptEnabled: Boolean,
        val scope: WalkieTalkieAutoAcceptScope,
        val allowedUserIds: Set<String>
    )

    companion object {
        private const val TAG = "WalkieTalkieSettingsVM"
    }

    private val repository = WalkieTalkieAutoAcceptSettingsRepository()
    private var persistedState: PersistedState? = null

    private val _state = MutableStateFlow(WalkieTalkieSettingsState())
    val state: StateFlow<WalkieTalkieSettingsState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                val settings = repository.getSettings(getApplication())
                val contacts = repository.getSelectableContacts()
                _state.value = WalkieTalkieSettingsState(
                    isLoading = false,
                    autoAcceptEnabled = settings.enabled,
                    scope = settings.scope,
                    allowedUserIds = settings.allowedUserIds,
                    contacts = contacts
                )
                persistedState = PersistedState(
                    autoAcceptEnabled = settings.enabled,
                    scope = settings.scope,
                    allowedUserIds = settings.allowedUserIds
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load WT auto-accept settings", error)
                _state.update { it.copy(isLoading = false, error = "Failed to load settings") }
            }
        }
    }

    fun setAutoAcceptEnabled(enabled: Boolean) {
        _state.update { it.copy(autoAcceptEnabled = enabled, savedSuccessfully = false) }
    }

    fun setScope(scope: WalkieTalkieAutoAcceptScope) {
        _state.update { it.copy(scope = scope, savedSuccessfully = false) }
    }

    fun toggleAllowedUser(userId: String) {
        _state.update { state ->
            val updated = if (userId in state.allowedUserIds) {
                state.allowedUserIds - userId
            } else {
                state.allowedUserIds + userId
            }
            state.copy(allowedUserIds = updated, savedSuccessfully = false)
        }
    }

    fun updateSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun save() {
        viewModelScope.launch {
            try {
                val pendingState = PersistedState(
                    autoAcceptEnabled = _state.value.autoAcceptEnabled,
                    scope = _state.value.scope,
                    allowedUserIds = _state.value.allowedUserIds
                )
                if (pendingState == persistedState) {
                    _state.update { it.copy(savedSuccessfully = true) }
                    return@launch
                }
                repository.saveSettings(
                    getApplication(),
                    WalkieTalkieAutoAcceptSettings(
                        enabled = _state.value.autoAcceptEnabled,
                        scope = _state.value.scope,
                        allowedUserIds = _state.value.allowedUserIds
                    )
                )
                persistedState = pendingState
                _state.update { it.copy(savedSuccessfully = true) }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save WT auto-accept settings", error)
                _state.update { it.copy(error = "Failed to save settings") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}