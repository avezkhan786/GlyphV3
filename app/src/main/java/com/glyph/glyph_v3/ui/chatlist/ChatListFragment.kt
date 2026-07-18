package com.glyph.glyph_v3.ui.chatlist

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.service.DraftMessageStore
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.OfficialContentRepository
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.databinding.FragmentChatListBinding
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.OfficialChatActivity
import com.glyph.glyph_v3.ui.chat.ChatConnectionPrewarmer
import com.glyph.glyph_v3.ui.chat.ChatOpenPrefetcher
import com.glyph.glyph_v3.ui.profile.ProfilePreviewDialog
import com.glyph.glyph_v3.ui.users.UserListActivity
import com.glyph.glyph_v3.util.StartupTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var chatAdapter: ChatListAdapter
    private lateinit var repository: RealtimeMessageRepository
    
    private var chatsJob: Job? = null
    private var presenceJob: Job? = null
    private var lastPredictivePrefetchChatIds: List<String> = emptyList()
    
    // Store presence data in a StateFlow for combining with chats
    private val presenceStateFlow = MutableStateFlow<Map<String, PresenceManager.PresenceStatus>>(emptyMap())
    private var currentUserIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disable enter/return animations for this fragment
        enterTransition = null
        returnTransition = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val app = requireContext().applicationContext as GlyphApplication
        app.ensureSharedRepositoryStartup(reason = "chat_list_legacy_open")
        repository = app.getOrCreateRealtimeRepository()

        DraftMessageStore.init(requireContext().applicationContext)
        
        setupRecyclerView()
        loadLocalChatsWithPresence()
        
        // Start listening for incoming messages
        repository.startIncomingMessageSync()
        
        binding.fabNewChat.setOnClickListener {
            startActivity(Intent(requireContext(), UserListActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        val headerAdapter = ChatListHeaderAdapter()
        chatAdapter = ChatListAdapter(
            onChatClick = { chat ->
                if (chat.isOfficial) {
                    startActivity(OfficialChatActivity.newIntent(requireContext()))
                } else {
                    val otherUserId = if (chat.isGroup) "" else (chat.participants.firstOrNull { it != repository.currentUserId } ?: "")
                    val displayName = if (chat.isGroup) chat.groupName.ifBlank { "Group" } else chat.otherUsername
                    val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
                    openChat(chat.id, otherUserId, displayName, displayAvatar)
                }
            },
            onAvatarClick = { chat, view ->
                showProfilePreview(chat, view)
            },
            currentUserId = repository.currentUserId
        )

        val concatAdapter = ConcatAdapter(headerAdapter, chatAdapter)

        binding.recyclerViewChats.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = concatAdapter

            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        Glide.with(this@ChatListFragment).resumeRequests()
                    } else {
                        Glide.with(this@ChatListFragment).pauseRequests()
                    }
                }
            })
        }
    }
    
    private fun showProfilePreview(chat: Chat, sourceView: View) {
        val otherUserId = chat.participants.firstOrNull { it != repository.currentUserId } ?: ""
        
        // Get source view location on screen
        val location = IntArray(2)
        sourceView.getLocationOnScreen(location)
        val startX = location[0]
        val startY = location[1]
        val startWidth = sourceView.width
        val startHeight = sourceView.height
        
        val dialog = ProfilePreviewDialog.newInstance(
            userId = if (chat.isGroup) "" else otherUserId,
            userName = if (chat.isGroup) chat.groupName.ifBlank { "Group" } else chat.otherUsername,
            userAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar,
            chatId = chat.id,
            startX = startX,
            startY = startY,
            startWidth = startWidth,
            startHeight = startHeight
        )
        dialog.show(childFragmentManager, "profile_preview")
    }
    
    private fun loadLocalChatsWithPresence() {
        chatsJob?.cancel()
        presenceJob?.cancel()
        
        // Collect chats and combine with presence data
        chatsJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Merge both block sets so presence is hidden in either direction
                val blockedFlow = BlockRepository.myBlockedUsers
                    .combine(BlockRepository.blockedByUsers) { mine, theirs -> mine + theirs }

                repository.getLocalChats()
                    .combine(presenceStateFlow) { localChats, presenceMap ->
                        Pair(localChats, presenceMap)
                    }
                    .combine(blockedFlow) { (localChats, presenceMap), blockedUserIds ->
                        // Check if we need to start/update presence observation
                        val newUserIds = localChats.map { it.otherUserId }.distinct()
                        if (newUserIds != currentUserIds) {
                            currentUserIds = newUserIds
                            startPresenceObservation(newUserIds)
                        }
                        
                        // Preload avatars in background (non-blocking)
                        launch(Dispatchers.IO) {
                            val avatarsToCache = localChats.mapNotNull { chat ->
                                if (!chat.isGroup && chat.otherUserAvatar.isNotEmpty()) {
                                    chat.otherUserId to chat.otherUserAvatar
                                } else {
                                    null
                                }
                            }
                            val groupAvatarsToCache = localChats.mapNotNull { chat ->
                                if (chat.isGroup && chat.groupIconUrl.isNotEmpty()) {
                                    chat.id to chat.groupIconUrl
                                } else {
                                    null
                                }
                            }
                            com.glyph.glyph_v3.data.cache.AvatarCacheManager.preloadAvatars(
                                avatarsToCache, 
                                requireContext()
                            )
                            com.glyph.glyph_v3.data.cache.AvatarCacheManager.preloadGroupAvatars(
                                groupAvatarsToCache,
                                requireContext()
                            )
                        }
                        
                        // Convert LocalChat to Chat with presence data
                        localChats.map { local ->
                            // Check if avatar is missing and trigger sync
                            if (local.otherUserAvatar.isEmpty()) {
                                repository.syncChatUserInfo(local.id, local.otherUserId)
                            }
                            
                            val isBlocked = local.otherUserId in blockedUserIds
                            val presence = presenceMap[local.otherUserId]
                            Chat(
                                id = local.id,
                                participants = if (local.isGroup) {
                                    com.glyph.glyph_v3.data.repo.GroupChatRepository.decodeStringList(local.participantsJson)
                                } else {
                                    listOf(repository.currentUserId ?: "", local.otherUserId)
                                },
                                lastMessage = local.lastMessage,
                                lastMessageTimestamp = if (local.lastMessageTimestamp > 0) java.util.Date(local.lastMessageTimestamp) else null,
                                lastMessageSenderId = local.lastMessageSenderId,
                                lastMessageStatus = local.lastMessageStatus,
                                unreadCount = local.unreadCount,
                                otherUsername = local.otherUsername,
                                otherUserAvatar = local.otherUserAvatar,
                                isOtherUserOnline = if (isBlocked) false else (presence?.isOnline ?: false),
                                otherUserLastSeen = if (isBlocked) 0L else (presence?.lastSeen ?: 0L),
                                draft = DraftMessageStore.getDraft(local.id),
                                isGroup = local.isGroup,
                                groupName = local.groupName,
                                groupIconUrl = local.groupIconUrl,
                                groupDescription = local.groupDescription,
                                createdBy = local.createdBy,
                                createdAt = local.createdAt
                            )
                        }
                    }
                    .combine(OfficialContentRepository.officialMessages) { chats, msgs -> chats to msgs }
                    .combine(OfficialContentRepository.lastOpenedAtFlow) { (chats, msgs), lastOpened ->
                        buildChatListWithOfficial(chats, msgs, lastOpened)
                    }
                    .flowOn(Dispatchers.Default)
                    .collect { chats ->
                        chatAdapter.submitList(chats)
                        warmLikelyNextChats(chats)
                    }
            }
        }
    }
    
    private fun startPresenceObservation(userIds: List<String>) {
        presenceJob?.cancel()
        
        if (userIds.isEmpty()) {
            presenceStateFlow.value = emptyMap()
            return
        }

        PresenceManager.primeTransport("chat_list_presence_observe_legacy", forceTokenRefresh = true)
        
        presenceJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                PresenceManager.observeMultipleUsersPresence(userIds).collect { presenceMap ->
                    presenceStateFlow.value = presenceMap
                }
            } catch (e: Exception) {
                Log.e("ChatListFragment", "Error observing presence", e)
                presenceStateFlow.value = emptyMap()
            }
        }
    }
    
    private fun openChat(chatId: String, otherUserId: String, otherUsername: String, otherUserAvatar: String) {
        val appContext = requireContext().applicationContext
        StartupTrace.logStage(
            "chat_list_open_tap",
            "chatId=$chatId other=$otherUserId username=${otherUsername.isNotBlank()} avatar=${otherUserAvatar.isNotBlank()}"
        )

        val intent = ChatActivity.newIntent(
            requireContext(),
            chatId,
            otherUserId,
            otherUsername,
            otherUserAvatar
        )
        StartupTrace.logStage("chat_list_open_start_activity", "chatId=$chatId")
        startActivity(intent)

        ChatConnectionPrewarmer.prewarmForChatOpen(repository, chatId, otherUserId)

        // Run primeChatOpen in background so navigation does not wait for prefetch work.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val primeStartedAt = android.os.SystemClock.elapsedRealtime()
            StartupTrace.logStage("chat_list_open_prime_start_async", "chatId=$chatId")
            runCatching {
                ChatOpenPrefetcher.primeChatOpen(
                    context = appContext,
                    repository = repository,
                    chatId = chatId,
                    source = "chat_list_tap",
                    peerUserId = otherUserId,
                    peerAvatarUrl = otherUserAvatar
                )
            }
            StartupTrace.logStage(
                "chat_list_open_prime_end_async",
                "chatId=$chatId elapsed=${android.os.SystemClock.elapsedRealtime() - primeStartedAt}ms"
            )
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.clearUnreadCount(chatId) }
        }
    }

    private fun warmLikelyNextChats(chats: List<Chat>) {
        val candidateIds = chats.take(2).map { it.id }
        if (candidateIds.isEmpty() || candidateIds == lastPredictivePrefetchChatIds) return

        lastPredictivePrefetchChatIds = candidateIds
        ChatOpenPrefetcher.warmChatsAsync(
            context = requireContext().applicationContext,
            repository = repository,
            chatIds = candidateIds,
            source = "chat_list_visible"
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatsJob?.cancel()
        presenceJob?.cancel()
        _binding = null
    }
}
