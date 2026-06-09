package com.glyph.glyph_v3.ui.groups

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.GroupChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the two-step "Create group" flow:
 *   Step 1 [Step.SELECT_MEMBERS] — multi-select registered contacts.
 *   Step 2 [Step.GROUP_DETAILS]  — pick name / icon / description, then create.
 *
 * Strictly additive to the 1:1 stack. Uses [GroupChatRepository] for the actual create call.
 */
class CreateGroupViewModel(
    private val groupChatRepository: GroupChatRepository,
    private val firebaseRepository: FirebaseRepository
) : ViewModel() {

    enum class Step { SELECT_MEMBERS, GROUP_DETAILS, GROUP_PERMISSIONS }

    data class UiState(
        val step: Step = Step.SELECT_MEMBERS,
        val isLoadingUsers: Boolean = true,
        val allUsers: List<User> = emptyList(),
        val searchQuery: String = "",
        val selectedUserIds: Set<String> = emptySet(),
        val groupName: String = "",
        val groupDescription: String = "",
        val groupIconUri: Uri? = null,
        val isCreating: Boolean = false,
        val createdChatId: String? = null,
        val errorMessage: String? = null
    ) {
        val filteredUsers: List<User>
            get() {
                val q = searchQuery.trim()
                if (q.isEmpty()) return allUsers
                return allUsers.filter { it.username.contains(q, ignoreCase = true) ||
                        it.phoneNumber.contains(q, ignoreCase = true) }
            }

        val canProceedFromSelection: Boolean
            get() = selectedUserIds.isNotEmpty() &&
                    selectedUserIds.size + 1 <= GroupChatRepository.MAX_GROUP_MEMBERS

        val canCreate: Boolean
            get() = !isCreating && groupName.trim().isNotEmpty() && selectedUserIds.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadUsers()
    }

    private fun loadUsers() {
        _state.update { it.copy(isLoadingUsers = true) }
        firebaseRepository.getAllUsers(
            forceRefresh = false,
            onSuccess = { users ->
                _state.update { it.copy(isLoadingUsers = false, allUsers = users) }
            },
            onFailure = { e ->
                _state.update {
                    it.copy(
                        isLoadingUsers = false,
                        errorMessage = e.message ?: "Failed to load contacts"
                    )
                }
            }
        )
    }

    fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleUser(userId: String) {
        _state.update {
            val newSet = it.selectedUserIds.toMutableSet()
            if (!newSet.add(userId)) newSet.remove(userId)
            it.copy(selectedUserIds = newSet)
        }
    }

    fun goToDetailsStep() {
        if (!_state.value.canProceedFromSelection) return
        _state.update { it.copy(step = Step.GROUP_DETAILS) }
    }

    fun goBackToSelection() {
        _state.update { it.copy(step = Step.SELECT_MEMBERS) }
    }

    fun goToPermissionsStep() {
        _state.update { it.copy(step = Step.GROUP_PERMISSIONS) }
    }

    fun goBackToDetailsStep() {
        _state.update { it.copy(step = Step.GROUP_DETAILS) }
    }

    fun onGroupNameChanged(name: String) {
        if (name.length > 100) return
        _state.update { it.copy(groupName = name) }
    }

    fun onGroupDescriptionChanged(description: String) {
        if (description.length > 500) return
        _state.update { it.copy(groupDescription = description) }
    }

    fun onGroupIconPicked(uri: Uri?) {
        _state.update { it.copy(groupIconUri = uri) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun createGroup() {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(isCreating = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val chatId = groupChatRepository.createGroup(
                    name = s.groupName.trim(),
                    iconUri = s.groupIconUri,
                    memberUids = s.selectedUserIds.toList()
                )
                if (s.groupDescription.isNotBlank()) {
                    runCatching {
                        groupChatRepository.updateGroupInfo(
                            chatId = chatId,
                            description = s.groupDescription.trim()
                        )
                    }
                }
                _state.update { it.copy(isCreating = false, createdChatId = chatId) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isCreating = false,
                        errorMessage = e.message ?: "Failed to create group"
                    )
                }
            }
        }
    }
}

class CreateGroupViewModelFactory(
    private val groupChatRepository: GroupChatRepository,
    private val firebaseRepository: FirebaseRepository = FirebaseRepository()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CreateGroupViewModel::class.java)) {
            return CreateGroupViewModel(groupChatRepository, firebaseRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
