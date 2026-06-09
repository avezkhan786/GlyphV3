package com.glyph.glyph_v3.ui.groups

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.GroupChatRepository
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the Group Info screen: surfaces the live group metadata + member roster and exposes
 * admin actions (rename / change icon / add member / remove member / promote / demote / leave).
 *
 * Reads metadata from the local Room mirror (kept in sync by [GroupChatRepository]) and resolves
 * member display info via [FirebaseRepository.getAllUsers].
 */
class GroupInfoViewModel(
    application: Application,
    private val chatId: String,
    private val groupChatRepository: GroupChatRepository,
    private val firebaseRepository: FirebaseRepository
) : AndroidViewModel(application) {

    data class MemberRow(
        val user: User,
        val isAdmin: Boolean,
        val isSelf: Boolean
    )

    data class UiState(
        val isLoading: Boolean = true,
        val groupName: String = "",
        val groupIconUrl: String = "",
        val groupDescription: String = "",
        val createdBy: String = "",
        val createdAt: Long = 0L,
        val members: List<MemberRow> = emptyList(),
        val availableUsers: List<User> = emptyList(),
        val isCurrentUserAdmin: Boolean = false,
        val isCurrentUserMember: Boolean = false,
        val errorMessage: String? = null,
        val infoMessage: String? = null,
        val didLeave: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _memberOnlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val memberOnlineStatus: StateFlow<Map<String, Boolean>> = _memberOnlineStatus.asStateFlow()

    private val currentUid: String? = FirebaseAuth.getInstance().uid

    init {
        observeChat()
    }

    private fun observeChat() {
        val app = getApplication<GlyphApplication>()
        val chatDao = AppDatabase.getDatabase(app).chatDao()
        viewModelScope.launch {
            chatDao.observeChatById(chatId).collect { localChat ->
                if (localChat == null || !localChat.isGroup) {
                    _state.update {
                        it.copy(isLoading = false, errorMessage = "Group not found")
                    }
                    return@collect
                }
                val participants = GroupChatRepository.decodeStringList(localChat.participantsJson)
                val admins = GroupChatRepository.decodeStringList(localChat.adminsJson).toSet()
                _state.update {
                    it.copy(
                        groupName = localChat.groupName,
                        groupIconUrl = localChat.groupIconUrl,
                        groupDescription = localChat.groupDescription,
                        createdBy = localChat.createdBy,
                        createdAt = localChat.createdAt,
                        isCurrentUserAdmin = currentUid != null && currentUid in admins,
                        isCurrentUserMember = currentUid != null && currentUid in participants
                    )
                }
                resolveMembers(participants, admins)
            }
        }
    }

    private fun resolveMembers(participantIds: List<String>, adminIds: Set<String>) {
        firebaseRepository.getAllUsers(
            forceRefresh = false,
            onSuccess = { allUsers ->
                viewModelScope.launch(Dispatchers.Default) {
                    val byId = allUsers.associateBy { it.id }
                    val rows = participantIds.map { uid ->
                        val user = byId[uid] ?: User(id = uid, username = "Unknown")
                        MemberRow(
                            user = user,
                            isAdmin = uid in adminIds,
                            isSelf = uid == currentUid
                        )
                    }.sortedWith(
                        compareByDescending<MemberRow> { it.isSelf }
                            .thenByDescending { it.isAdmin }
                            .thenBy { it.user.username.lowercase() }
                    )
                    withContext(Dispatchers.Main) {
                        val available = allUsers
                            .filter { user -> user.id !in participantIds && user.id != currentUid }
                            .sortedBy { user -> user.username.ifBlank { user.phoneNumber }.lowercase() }
                        _state.update {
                            it.copy(
                                isLoading = false,
                                members = rows,
                                availableUsers = available
                            )
                        }
                        observeMembersPresence(participantIds)
                    }
                }
            },
            onFailure = { e ->
                _state.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load members")
                }
            }
        )
    }

    private var presenceJob: kotlinx.coroutines.Job? = null
    private fun observeMembersPresence(memberIds: List<String>) {
        presenceJob?.cancel()
        if (memberIds.isEmpty()) return
        presenceJob = viewModelScope.launch {
            PresenceManager.observeMultipleUsersPresence(memberIds)
                .map { presenceMap -> presenceMap.mapValues { (_, status) -> status.isOnline } }
                .distinctUntilChanged()
                .collectLatest { onlineMap -> _memberOnlineStatus.value = onlineMap }
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }
    fun consumeInfo() = _state.update { it.copy(infoMessage = null) }

    fun renameGroup(newName: String) = adminAction("rename") {
        groupChatRepository.updateGroupInfo(chatId, name = newName.trim())
    }

    fun updateDescription(newDescription: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.updateGroupDescription(chatId, newDescription)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Failed to update description") }
            }
        }
    }

    fun changeIcon(uri: Uri) = adminAction("change icon") {
        groupChatRepository.updateGroupInfo(chatId, iconUri = uri)
    }

    fun removeIcon() = adminAction("remove icon") {
        groupChatRepository.updateGroupInfo(chatId, clearIcon = true)
    }

    fun addMembers(userIds: List<String>) = adminAction("add members") {
        groupChatRepository.addMembers(chatId, userIds)
        _state.update { it.copy(infoMessage = "Members added") }
    }

    fun removeMember(userId: String) = adminAction("remove member") {
        groupChatRepository.removeMember(chatId, userId)
    }

    fun promote(userId: String) = adminAction("promote") {
        groupChatRepository.promoteAdmin(chatId, userId)
    }

    fun demote(userId: String) = adminAction("demote") {
        groupChatRepository.demoteAdmin(chatId, userId)
    }

    fun leaveGroup() {
        viewModelScope.launch {
            try {
                groupChatRepository.leaveGroup(chatId)
                _state.update { it.copy(didLeave = true, infoMessage = "You left the group") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Failed to leave group") }
            }
        }
    }

    private inline fun adminAction(label: String, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Failed to $label") }
            }
        }
    }
}

class GroupInfoViewModelFactory(
    private val application: Application,
    private val chatId: String,
    private val groupChatRepository: GroupChatRepository,
    private val firebaseRepository: FirebaseRepository = FirebaseRepository()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GroupInfoViewModel::class.java)) {
            return GroupInfoViewModel(
                application, chatId, groupChatRepository, firebaseRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
