package com.glyph.glyph_v3.ui.chatlist

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.data.service.DraftMessageStore
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.ui.chat.ChatConnectionPrewarmer
import com.glyph.glyph_v3.ui.chat.ChatOpenPrefetcher
import com.glyph.glyph_v3.ui.aiagent.AiAgentActivity
import com.glyph.glyph_v3.ui.aiagent.AiAgentConstants
import com.glyph.glyph_v3.ui.profile.ProfilePreviewDialog
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.users.UserListActivity
import com.glyph.glyph_v3.util.ChatOpenTrace
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import androidx.activity.OnBackPressedCallback
import androidx.viewpager2.widget.ViewPager2
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.ui.status.StatusNavigationBus
import com.google.firebase.auth.FirebaseAuth

class ChatListComposeFragment : Fragment() {

    private var repository: RealtimeMessageRepository? = null
    private var groupRepository: com.glyph.glyph_v3.data.repo.GroupChatRepository? = null
    private lateinit var viewModel: ChatListViewModel

    private var chatsJob: Job? = null
    private var presenceJob: Job? = null
    private var typingJob: Job? = null
    private var groupTypingJob: Job? = null
    private var currentGroupTypingChatIds: List<String> = emptyList()
    private var repositoryInitJob: Job? = null
    private var hasStartedChatListData = false

    private val presenceStateFlow = MutableStateFlow<Map<String, PresenceManager.PresenceStatus>>(emptyMap())
    private val typingStateFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val groupTypingUsersStateFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val groupSenderNamesStateFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private var currentUserIds: List<String> = emptyList()
    private var lastPredictivePrefetchChatIds: Set<String> = emptySet()
    private val requestedUserInfoChatIds = ConcurrentHashMap.newKeySet<String>()
    private val requestedAvatarPreloadKeys = ConcurrentHashMap.newKeySet<String>()
    private val requestedGroupSenderIds = ConcurrentHashMap.newKeySet<String>()
    private val firebaseRepository = FirebaseRepository()

    private var composeView: ComposeView? = null
    private var hasRequestedSecondaryTabPreload = false

    // Navigation dedup: prevent double-tap from opening the same chat twice
    private var lastNavigationChatId: String? = null
    private var lastNavigationUptimeMs: Long = 0L

    companion object {
        private const val NAVIGATION_GUARD_WINDOW_MS = 600L

        fun newInstance(isArchivedMode: Boolean = false, isLockedMode: Boolean = false): ChatListComposeFragment {
            return ChatListComposeFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("is_archived_mode", isArchivedMode)
                    putBoolean("is_locked_mode", isLockedMode)
                }
            }
        }
    }
    
    private var isArchivedMode: Boolean = false
    private var isLockedMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.logStage("chat_list_fragment_onCreate", "archived=$isArchivedMode locked=$isLockedMode")

        isArchivedMode = arguments?.getBoolean("is_archived_mode") ?: false
        isLockedMode = arguments?.getBoolean("is_locked_mode") ?: false
        enterTransition = null
        returnTransition = null

        // Start repository pre-warming as early as possible (onCreate instead of
        // onCreateView) so DB creation + repository init overlap with layout
        // inflation and Compose setup rather than blocking the first frame.
        val app = requireContext().applicationContext as GlyphApplication
        app.ensureSharedRepositoryStartup(reason = "chat_list_compose_open")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        StartupTrace.logStage("chat_list_fragment_onCreateView_start")

        val app = requireContext().applicationContext as GlyphApplication
        repository = app.repository
        groupRepository = app.getOrCreateGroupChatRepository()

        DraftMessageStore.init(requireContext().applicationContext)

        val cacheKey = when {
            isLockedMode -> "locked"
            isArchivedMode -> "archived"
            else -> "main"
        }
        val factory = ChatListViewModelFactory(repository, cacheKey)
        viewModel = ViewModelProvider(this, factory)[ChatListViewModel::class.java]
        repository?.let(viewModel::attachRepository)

        // OPTIMIZATION: Create ComposeView immediately but defer setContent to after first frame
        val view = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        composeView = view

        // Set up Compose UI immediately so content displays
        setupComposeContent()

        StartupTrace.logStage("chat_list_fragment_onCreateView_end")
        return view
    }

    // Set up Compose UI with chat list content
    private fun setupComposeContent() {
        val composeViewLocal = composeView ?: return
        if (composeViewLocal.childCount > 0) return // Already set up

        StartupTrace.logStage("chat_list_compose_setup_start")

        composeViewLocal.setContent {
            GlyphThemeProvider {
                val uiState by viewModel.uiState.collectAsState()
                val contactStatusGroups by StatusRepository.contactStatuses.collectAsState()
                val groupSenderNames by groupSenderNamesStateFlow.collectAsState()
                ChatListScreen(
                        title = "Glyph",
                        chats = uiState.chats,
                        groupSenderNamesByUserId = groupSenderNames,
                        statusRingStatesByUserId = remember(contactStatusGroups) {
                            buildMap {
                                contactStatusGroups.forEach { group ->
                                    if (!group.allViewed) {
                                        put(group.userId, ChatStatusRingState.UNSEEN)
                                    }
                                }
                            }
                        },
                        selectedChatIds = uiState.selectedChatIds,
                        isSelectionMode = uiState.isSelectionMode,
                        showDeleteConfirmation = uiState.showDeleteConfirmation,
                        isInitialLoading = uiState.isInitialLoading,
                        archivedChatsCount = uiState.archivedChatsCount,
                        hasUnreadArchivedMessages = uiState.hasUnreadArchivedMessages,
                        lockedChatsCount = if (isLockedMode) 0 else uiState.lockedChatsCount,
                        hasUnreadLockedMessages = if (isLockedMode) false else uiState.hasUnreadLockedMessages,
                        isLockedChatsHidden = uiState.isLockedChatsHidden,
                        secretCodeMatch = uiState.secretCodeMatch,
                        clearSearchTrigger = uiState.clearSearchTrigger,
                        onSearchQueryChanged = { query ->
                            // Only attempt PIN verification when "Hide locked chats" is active.
                            // When the feature is off the section is always visible; no injection needed.
                            if (!uiState.isLockedChatsHidden) {
                                if (uiState.secretCodeMatch) viewModel.updateSecretCodeMatch(false)
                                return@ChatListScreen
                            }
                            if (query.isEmpty()) {
                                viewModel.updateSecretCodeMatch(false)
                            } else {
                                val ctx = context ?: return@ChatListScreen
                                val match = ChatSettingsDataStore.verifySecretCode(ctx, query)
                                viewModel.updateSecretCodeMatch(match)
                            }
                        },
                        onLockedChatsClick = {
                            val ctx = context ?: return@ChatListScreen
                            val fragmentActivity = ctx as? androidx.fragment.app.FragmentActivity
                                ?: return@ChatListScreen
                            val executor = ContextCompat.getMainExecutor(fragmentActivity)
                            val biometricPrompt = BiometricPrompt(
                                fragmentActivity, executor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(
                                        result: BiometricPrompt.AuthenticationResult
                                    ) {
                                        startActivity(Intent(ctx, LockedChatsActivity::class.java))
                                    }
                                }
                            )
                            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                .setTitle("Locked chats")
                                .setSubtitle("Verify your identity to view locked chats")
                                .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                )
                                .build()
                            biometricPrompt.authenticate(promptInfo)
                        },
                        currentUserId = currentUserId(),
                        onNewChatClick = {
                            context?.let { ctx ->
                                startActivity(Intent(ctx, UserListActivity::class.java))
                            }
                        },
                        onChatClick = { chat ->
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelection(chat.id)
                            } else if (chat.id == AiAgentConstants.AI_AGENT_CHAT_ID) {
                                context?.let { ctx ->
                                    startActivity(AiAgentActivity.newIntent(ctx))
                                }
                            } else {
                                val otherUserId = if (chat.isGroup) "" else otherParticipantId(chat)
                                val displayName = if (chat.isGroup) chat.groupName.ifBlank { "Group" } else chat.otherUsername
                                val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
                                navigateToChat(chat.id, otherUserId, displayName, displayAvatar, isCompose = true)
                            }
                        },
                        onChatLongClick = { chat -> viewModel.toggleSelection(chat.id) },
                        onClearSelection = { viewModel.clearSelection() },
                        onPinChats = { viewModel.pinSelectedChats() },
                        onDeleteChats = { viewModel.deleteSelectedChats() },
                        onConfirmDelete = { viewModel.confirmDeleteSelectedChats() },
                        onDismissDelete = { viewModel.dismissDeleteConfirmation() },
                        onMuteChats = { viewModel.muteSelectedChats() },
                        onArchiveChats = { viewModel.archiveSelectedChats() },
                        onUnarchiveChats = { viewModel.unarchiveSelectedChats() },
                        isArchivedMode = isArchivedMode,
                        onBackClick = { activity?.finish() },
                        onArchivedFolderClick = {
                            context?.let { ctx ->
                                startActivity(Intent(ctx, ArchivedChatsActivity::class.java))
                            }
                        },
                        onAvatarClick = { chat, avatarBoundsInWindow ->
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelection(chat.id)
                            } else {
                                val otherUserId = if (chat.isGroup) "" else otherParticipantId(chat)
                                val hasUnviewedStatus = contactStatusGroups.any {
                                    it.userId == otherUserId && !it.allViewed
                                }
                                if (hasUnviewedStatus) {
                                    openContactStatusFromChatList(otherUserId)
                                } else {
                                    showProfilePreview(chat, avatarBoundsInWindow)
                                }
                            }
                        },
                        // Undo delete
                        pendingDeleteChatIds = uiState.pendingDeleteChatIds,
                        pendingDeleteCount = uiState.pendingDeleteCount,
                        showUndoBar = uiState.showUndoBar,
                        undoProgress = uiState.undoProgress,
                        onUndoDelete = { viewModel.undoPendingDelete() }
                    )
                }
            }
        StartupTrace.logStage("chat_list_compose_setup_end")
    }
    private fun openContactStatusFromChatList(userId: String) {
        if (userId.isBlank()) return
        StatusNavigationBus.openContactStatus(userId)
        activity?.findViewById<ViewPager2>(R.id.main_view_pager)?.setCurrentItem(1, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StartupTrace.logStage("chat_list_fragment_onViewCreated")

        // Start data loading after first frame
        view.post {
            if (isViewDestroyed()) return@post
            StartupTrace.logStage("chat_list_deferred_data_setup_start")
            observeChatListReadyForSecondaryPreload()
            ensureRepositoryReadyAndStart()

            // Refresh "hide locked chats" state (readable from SharedPreferences, updated on resume)
            if (!isLockedMode && !isArchivedMode) {
                refreshLockedChatsHiddenState()
            }
        }
    }

    private fun isViewDestroyed(): Boolean {
        return view == null || isDetached || (view != null && view?.parent == null)
    }

    override fun onResume() {
        super.onResume()
        if (!isLockedMode && !isArchivedMode) {
            refreshLockedChatsHiddenState()
            viewModel.clearSearchBar()
        }
    }

    private fun refreshLockedChatsHiddenState() {
        val ctx = context ?: return
        val hidden = ChatSettingsDataStore.isHideLockedChatsEnabled(ctx) &&
                     ChatSettingsDataStore.isSecretCodeSet(ctx)
        viewModel.updateLockedChatsHidden(hidden)
    }

    private fun observeChatListReadyForSecondaryPreload() {
        if (isArchivedMode || isLockedMode) return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .map { state -> !state.isInitialLoading }
                    .distinctUntilChanged()
                    .collect { isReady ->
                        if (isReady) {
                            StartupTrace.logStage("chat_list_ui_ready", "chats=${viewModel.uiState.value.chats.size}")
                            requestSecondaryTabPreload()
                        }
                    }
            }
        }
    }

    private fun requestSecondaryTabPreload() {
        if (hasRequestedSecondaryTabPreload) return
        hasRequestedSecondaryTabPreload = true
        (activity as? MainActivity)?.preloadSecondaryTabsAfterChatReady()
    }

    private fun ensureRepositoryReadyAndStart() {
        if (repository != null) {
            startChatListData()
            return
        }

        repositoryInitJob?.cancel()
        repositoryInitJob = viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext as GlyphApplication

            // OPTIMIZATION: Don't block on repository creation - the Application
            // class should have already prewarmed it. If not, create it async.
            val readyRepository = repository ?: app.getOrCreateRealtimeRepository()

            withContext(Dispatchers.Main.immediate) {
                repository = readyRepository
                viewModel.attachRepository(readyRepository)
                startChatListData()
            }
        }
    }

    private fun startChatListData() {
        if (hasStartedChatListData) return
        val repository = repository ?: return
        hasStartedChatListData = true
        viewModel.attachRepository(repository)

        // OPTIMIZATION: Start seedInitialChats immediately (it's already optimized with parallel loads)
        seedInitialChats()

        // OPTIMIZATION: Start message sync on IO thread without blocking
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            repository.startIncomingMessageSync()
        }

        // OPTIMIZATION: Start all observation flows in parallel without blocking
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getArchivedChats()
                .map { chats ->
                    val hasUnread = chats.any { it.unreadCount > 0 }
                    Pair(chats.size, hasUnread)
                }
                .distinctUntilChanged()
                .collect { (count, hasUnread) ->
                    viewModel.updateArchivedChatsCount(count, hasUnread)
                }
        }

        if (!isLockedMode) {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.getLocalChats()
                    .combine(ChatSettingsDataStore.observeLockedChatVersion()) { chats, _ -> chats }
                    .map { chats ->
                        val ctx = requireContext()
                        val locked = chats.filter { ChatSettingsDataStore.isChatLocked(ctx, it.id) }
                        Pair(locked.size, locked.any { it.unreadCount > 0 })
                    }
                    .distinctUntilChanged()
                    .collect { (count, hasUnread) ->
                        viewModel.updateLockedChatsCount(count, hasUnread)
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val chatsFlow = if (isArchivedMode) repository.getArchivedChats() else repository.getLocalChats()
            chatsFlow
                .map { chats -> chats.map { it.id } }
                .distinctUntilChanged()
                .collect { chatIds ->
                    if (chatIds.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            repository.syncAllReadReceipts(chatIds)
                        }
                    }
                }
        }

        // OPTIMIZATION: Defer the expensive presence/typing flow setup slightly to let
        // the initial chat list render first
        loadLocalChatsWithPresence()
    }

    private fun currentUserId(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun otherParticipantId(chat: Chat): String {
        val currentUserId = currentUserId().orEmpty()
        return chat.participants.firstOrNull { participant ->
            participant.isNotEmpty() && participant != currentUserId
        }.orEmpty()
    }

    private fun seedInitialChats() {
        val repository = repository ?: return

        // OPTIMIZATION: Skip seeding if we already have cached data from Application prewarming
        // or from a previous Fragment instance. The combined flow in loadLocalChatsWithPresence
        // will refresh it with presence/typing info momentarily anyway.
        if (viewModel.uiState.value.chats.isNotEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // OPTIMIZATION: Load initial chats in parallel with archived chats count
            // This reduces initial load time by ~50-100ms on cold starts
            val localChatsDeferred = async {
                if (isArchivedMode) repository.getArchivedChatsOnce()
                else repository.getLocalChatsOnce()
            }

            val localChats = localChatsDeferred.await()

            // Update archived chats count in parallel (non-blocking)
            if (!isArchivedMode) {
                launch {
                    val archivedChats = repository.getArchivedChatsOnce()
                    if (archivedChats.isNotEmpty()) {
                        val hasUnread = archivedChats.any { it.unreadCount > 0 }
                        withContext(Dispatchers.Main.immediate) {
                            viewModel.updateArchivedChatsCount(archivedChats.size, hasUnread)
                        }
                    }
                }
            }

            if (localChats.isEmpty()) {
                // Ensure we transition out of the loading state even when there
                // are no chats yet, so the user sees the empty-state UI (AI agent
                // row + "No chats yet") instead of shimmer placeholders forever.
                withContext(Dispatchers.Main.immediate) {
                    viewModel.updateChats(emptyList())
                }
                return@launch
            }

            val initialChats = mapLocalChatsToUi(
                localChats = localChats,
                presenceMap = emptyMap(),
                typingMap = emptyMap(),
                groupTypingUsersByChatId = emptyMap(),
                groupSenderNamesByUserId = emptyMap(),
                blockedUserIds = emptySet()
            )

            withContext(Dispatchers.Main.immediate) {
                viewModel.updateChats(initialChats)
            }

            // Launch these in parallel instead of sequentially
            launch { scheduleChatListUserInfoSync(localChats) }
            launch { scheduleGroupSenderNameSync(localChats) }
            launch { scheduleChatListAvatarPreload(localChats) }
            launch { warmLikelyNextChats(localChats) }
        }
    }

    private fun showProfilePreview(chat: Chat, avatarBoundsInWindow: Rect) {
        // Safety checks
        if (!isAdded || isDetached || childFragmentManager.isDestroyed) {
            return
        }
        
        val otherUserId = otherParticipantId(chat)

        val cv = composeView ?: return

        try {
            val locScreen = IntArray(2)
            val locWindow = IntArray(2)
            cv.getLocationOnScreen(locScreen)
            cv.getLocationInWindow(locWindow)

            val dx = locScreen[0] - locWindow[0]
            val dy = locScreen[1] - locWindow[1]

            val startX = avatarBoundsInWindow.left + dx
            val startY = avatarBoundsInWindow.top + dy
            val startWidth = avatarBoundsInWindow.width()
            val startHeight = avatarBoundsInWindow.height()

            // Ensure valid dimensions
            if (startWidth <= 0 || startHeight <= 0) {
                Log.w("ChatListComposeFragment", "Invalid avatar dimensions: $startWidth x $startHeight")
                return
            }

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
        } catch (e: Exception) {
            Log.e("ChatListComposeFragment", "Error showing profile preview", e)
        }
    }

    private fun loadLocalChatsWithPresence() {
        val repository = repository ?: return

        chatsJob?.cancel()
        presenceJob?.cancel()
        typingJob?.cancel()

        chatsJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val chatsFlow = if (isArchivedMode) {
                    repository.getArchivedChats()
                } else {
                    repository.getLocalChats()
                }
                val lockedStateFlow = ChatSettingsDataStore.observeLockedChatVersion()
                
                // Merge both block sets into a single flow of user-IDs whose
                // presence should be hidden (either I blocked them or they blocked me).
                val blockedFlow = BlockRepository.myBlockedUsers
                    .combine(BlockRepository.blockedByUsers) { mine, theirs -> mine + theirs }

                chatsFlow
                    .combine(lockedStateFlow) { localChats, _ -> localChats }
                    .combine(presenceStateFlow) { localChats, presenceMap ->
                        Pair(localChats, presenceMap)
                    }
                    .combine(typingStateFlow) { (localChats, presenceMap), typingMap ->
                        Triple(localChats, presenceMap, typingMap)
                    }
                    .combine(groupTypingUsersStateFlow) { (localChats, presenceMap, typingMap), groupTypingUsersByChatId ->
                        Quadruple(localChats, presenceMap, typingMap, groupTypingUsersByChatId)
                    }
                    .combine(groupSenderNamesStateFlow) { (localChats, presenceMap, typingMap, groupTypingUsersByChatId), groupSenderNamesByUserId ->
                        Quintuple(localChats, presenceMap, typingMap, groupTypingUsersByChatId, groupSenderNamesByUserId)
                    }
                    .combine(blockedFlow) { (localChats, presenceMap, typingMap, groupTypingUsersByChatId, groupSenderNamesByUserId), blockedUserIds ->
                        val nonGroupChats = localChats.filterNot { isEffectivelyGroupChat(it) }
                        val groupChats = localChats.filter { isEffectivelyGroupChat(it) }
                        val groupChatIds = groupChats.map { it.id }.distinct()
                        val selfUid = currentUserId().orEmpty()

                        val nonGroupUserIds = nonGroupChats
                            .map { it.otherUserId }
                            .filter { it.isNotBlank() }
                            .distinct()

                        // Include group member UIDs in the shared presence observation so
                        // that groupOnlineCount is populated from the same presenceMap.
                        // Self is excluded to avoid observing our own presence node.
                        val groupMemberIds = groupChats
                            .flatMap { com.glyph.glyph_v3.data.repo.GroupChatRepository.decodeStringList(it.participantsJson) }
                            .filter { it.isNotBlank() && it != selfUid }
                            .distinct()

                        val newUserIds = (nonGroupUserIds + groupMemberIds).distinct()
                        if (newUserIds != currentUserIds) {
                            currentUserIds = newUserIds
                            startPresenceObservation(newUserIds)
                            startTypingObservation(nonGroupChats.map { it.id to it.otherUserId })
                        }
                        startGroupTypingObservation(groupChatIds)

                        val activelyTypingGroupUserIds = groupTypingUsersByChatId.values.flatten().toSet()
                        ensureGroupSenderNamesLoaded(activelyTypingGroupUserIds)

                        scheduleChatListUserInfoSync(localChats)
                        scheduleGroupSenderNameSync(localChats)
                        scheduleChatListAvatarPreload(localChats)
                        warmLikelyNextChats(localChats)

                        mapLocalChatsToUi(
                            localChats = localChats,
                            presenceMap = presenceMap,
                            typingMap = typingMap,
                            groupTypingUsersByChatId = groupTypingUsersByChatId,
                            groupSenderNamesByUserId = groupSenderNamesByUserId,
                            blockedUserIds = blockedUserIds
                        )
                    }
                    .combine(ContactDisplayNameResolver.cacheVersion) { chats, _ -> chats }
                    .flowOn(Dispatchers.Default)
                    .collect { chats ->
                        viewModel.updateChats(chats)
                    }
            }
        }
    }

    private fun mapLocalChatsToUi(
        localChats: List<com.glyph.glyph_v3.data.local.entity.LocalChat>,
        presenceMap: Map<String, PresenceManager.PresenceStatus>,
        typingMap: Map<String, Boolean>,
        groupTypingUsersByChatId: Map<String, Set<String>>,
        groupSenderNamesByUserId: Map<String, String>,
        blockedUserIds: Set<String>
    ): List<Chat> {
        val ctx = context ?: return emptyList()
        val currentUid = currentUserId().orEmpty()
        return localChats.map { local ->
            val effectiveIsGroup = isEffectivelyGroupChat(local)
            val isBlocked = !effectiveIsGroup && local.otherUserId in blockedUserIds
            val presence = if (effectiveIsGroup) null else presenceMap[local.otherUserId]
            val isTyping = if (effectiveIsGroup) false else (typingMap["${local.id}_${local.otherUserId}"] ?: false)
            val groupTypingUserIds = if (effectiveIsGroup) {
                groupTypingUsersByChatId[local.id].orEmpty()
            } else {
                emptySet()
            }

            // Group presence: filter the shared presenceMap for online group members.
            // selfUid excluded; presenceMap already contains group member data from
            // the expanded startPresenceObservation() call above.
            val groupOnlineUserIds: List<String>
            val groupOnlineNames: List<String>
            if (effectiveIsGroup) {
                val members = com.glyph.glyph_v3.data.repo.GroupChatRepository
                    .decodeStringList(local.participantsJson)
                groupOnlineUserIds = members.filter { uid ->
                    uid != currentUid && presenceMap[uid]?.isOnline == true
                }
                groupOnlineNames = groupOnlineUserIds.mapNotNull { uid ->
                    groupSenderNamesByUserId[uid]?.takeIf { it.isNotBlank() }
                }
            } else {
                groupOnlineUserIds = emptyList()
                groupOnlineNames = emptyList()
            }
            val typingText = if (effectiveIsGroup) {
                formatGroupTypingIndicatorText(groupTypingUserIds, groupSenderNamesByUserId, currentUid)
            } else if (isTyping) {
                "typing..."
            } else {
                ""
            }
            val locked = ChatSettingsDataStore.isChatLocked(ctx, local.id)
            Chat(
                id = local.id,
                participants = if (effectiveIsGroup) {
                    com.glyph.glyph_v3.data.repo.GroupChatRepository.decodeStringList(local.participantsJson)
                } else {
                    listOf(currentUserId().orEmpty(), local.otherUserId)
                },
                lastMessage = local.lastMessage,
                lastMessageTimestamp = if (local.lastMessageTimestamp > 0) java.util.Date(local.lastMessageTimestamp) else null,
                lastMessageSenderId = local.lastMessageSenderId,
                lastMessageStatus = local.lastMessageStatus,
                unreadCount = local.unreadCount,
                otherUsername = if (!effectiveIsGroup && local.otherUserId.isNotBlank()) {
                    ContactDisplayNameResolver.getDisplayName(
                        otherUserId = local.otherUserId,
                        remoteProfileName = local.otherUsername
                    )
                } else local.otherUsername,
                otherUserAvatar = local.otherUserAvatar,
                isOtherUserOnline = if (isBlocked) false else (presence?.isOnline ?: false),
                isOtherUserInChat = if (isBlocked) false else (presence?.viewingChatId == local.id),
                otherUserLastSeen = if (isBlocked) 0L else (presence?.lastSeen ?: 0L),
                isOtherUserTyping = if (effectiveIsGroup) typingText.isNotBlank() else if (isBlocked) false else isTyping,
                typingText = typingText,
                isLocked = locked,
                draft = DraftMessageStore.getDraft(local.id),
                isGroup = effectiveIsGroup,
                groupName = local.groupName,
                groupIconUrl = local.groupIconUrl,
                groupDescription = local.groupDescription,
                createdBy = local.createdBy,
                createdAt = local.createdAt,
                groupOnlineCount = groupOnlineUserIds.size,
                groupOnlineUserNames = groupOnlineNames
            )
        }.let { chats ->
            // In locked mode show ONLY locked chats; in normal mode exclude them
            if (isLockedMode) chats.filter { it.isLocked } else chats
        }
    }

    private fun startPresenceObservation(userIds: List<String>) {
        presenceJob?.cancel()

        if (userIds.isEmpty()) {
            presenceStateFlow.value = emptyMap()
            return
        }

        PresenceManager.primeTransport("chat_list_presence_observe", forceTokenRefresh = true)

        presenceJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                PresenceManager.observeMultipleUsersPresence(userIds).collect { presenceMap ->
                    presenceStateFlow.value = presenceMap
                }
            } catch (e: Exception) {
                Log.e("ChatListComposeFragment", "Error observing presence", e)
                presenceStateFlow.value = emptyMap()
            }
        }
    }

    private fun startTypingObservation(chatUserPairs: List<Pair<String, String>>) {
        typingJob?.cancel()

        if (chatUserPairs.isEmpty()) {
            typingStateFlow.value = emptyMap()
            return
        }

        PresenceManager.primeTransport("chat_list_typing_observe")

        typingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val typingFlows = chatUserPairs.map { (chatId, otherUserId) ->
                    PresenceManager.observeTypingStatus(chatId, otherUserId).flowOn(Dispatchers.IO)
                }

                kotlinx.coroutines.flow.combine(typingFlows) { typingStatuses ->
                    chatUserPairs.mapIndexed { index, (chatId, otherUserId) ->
                        "${chatId}_$otherUserId" to typingStatuses[index]
                    }.toMap()
                }.collect { typingMap ->
                    typingStateFlow.value = typingMap
                }
            } catch (e: Exception) {
                Log.e("ChatListComposeFragment", "Error observing typing status", e)
                typingStateFlow.value = emptyMap()
            }
        }
    }

    private fun startGroupTypingObservation(chatIds: List<String>) {
        val distinctChatIds = chatIds.distinct()
        if (distinctChatIds == currentGroupTypingChatIds) return
        currentGroupTypingChatIds = distinctChatIds

        groupTypingJob?.cancel()
        if (distinctChatIds.isEmpty()) {
            groupTypingUsersStateFlow.value = emptyMap()
            return
        }

        val repository = groupRepository ?: return
        groupTypingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val flows = distinctChatIds.map { chatId ->
                    repository.observeGroupTyping(chatId)
                        .map { typingUsers -> chatId to typingUsers }
                        .flowOn(Dispatchers.IO)
                }

                kotlinx.coroutines.flow.combine(flows) { entries ->
                    entries.associate { it }
                }.collect { typingByChatId ->
                    groupTypingUsersStateFlow.value = typingByChatId
                }
            } catch (e: Exception) {
                Log.e("ChatListComposeFragment", "Error observing group typing", e)
                groupTypingUsersStateFlow.value = emptyMap()
            }
        }
    }

    private fun scheduleGroupSenderNameSync(localChats: List<com.glyph.glyph_v3.data.local.entity.LocalChat>) {
        val currentUid = currentUserId().orEmpty()
        val currentMap = groupSenderNamesStateFlow.value
        val missingSenderIds = localChats.asSequence()
            .filter { it.isGroup }
            .map { it.lastMessageSenderId }
            .filter { it.isNotBlank() && it != currentUid }
            .distinct()
            .filter { it !in currentMap && requestedGroupSenderIds.add(it) }
            .toList()

        if (missingSenderIds.isEmpty()) return

        firebaseRepository.getAllUsers(
            forceRefresh = false,
            onSuccess = { users ->
                // Cache userId → phone mappings for contact name resolution
                users.forEach { user ->
                    if (user.phoneNumber.isNotBlank()) {
                        ContactDisplayNameResolver.cacheUserPhone(user.id, user.phoneNumber)
                    }
                }
                val resolved = users.asSequence()
                    .filter { it.id in missingSenderIds }
                    .mapNotNull { user ->
                        val name = ContactDisplayNameResolver.getDisplayName(
                            otherUserId = user.id,
                            remoteProfileName = user.username,
                            remotePhoneNumber = user.phoneNumber
                        )
                        if (name.isBlank()) null else user.id to name
                    }
                    .toMap()
                if (resolved.isNotEmpty()) {
                    groupSenderNamesStateFlow.value = groupSenderNamesStateFlow.value + resolved
                }
                missingSenderIds.forEach { requestedGroupSenderIds.remove(it) }
            },
            onFailure = {
                missingSenderIds.forEach { requestedGroupSenderIds.remove(it) }
            }
        )
    }

    private fun ensureGroupSenderNamesLoaded(userIds: Set<String>) {
        val currentUid = currentUserId().orEmpty()
        val currentMap = groupSenderNamesStateFlow.value
        val missingUserIds = userIds
            .asSequence()
            .filter { it.isNotBlank() && it != currentUid }
            .filter { it !in currentMap && requestedGroupSenderIds.add(it) }
            .toList()

        if (missingUserIds.isEmpty()) return

        firebaseRepository.getAllUsers(
            forceRefresh = false,
            onSuccess = { users ->
                // Cache userId → phone mappings for contact name resolution
                users.forEach { user ->
                    if (user.phoneNumber.isNotBlank()) {
                        ContactDisplayNameResolver.cacheUserPhone(user.id, user.phoneNumber)
                    }
                }
                val resolved = users.asSequence()
                    .filter { it.id in missingUserIds }
                    .mapNotNull { user ->
                        val name = ContactDisplayNameResolver.getDisplayName(
                            otherUserId = user.id,
                            remoteProfileName = user.username,
                            remotePhoneNumber = user.phoneNumber
                        )
                        if (name.isBlank()) null else user.id to name
                    }
                    .toMap()
                if (resolved.isNotEmpty()) {
                    groupSenderNamesStateFlow.value = groupSenderNamesStateFlow.value + resolved
                }
                missingUserIds.forEach { requestedGroupSenderIds.remove(it) }
            },
            onFailure = {
                missingUserIds.forEach { requestedGroupSenderIds.remove(it) }
            }
        )
    }

    private fun formatGroupTypingIndicatorText(
        typingUserIds: Set<String>,
        groupSenderNamesByUserId: Map<String, String>,
        currentUserId: String
    ): String {
        if (typingUserIds.isEmpty()) return ""

        val names = linkedSetOf<String>()
        typingUserIds.forEach { uid ->
            if (uid == currentUserId) return@forEach
            groupSenderNamesByUserId[uid]?.trim()?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }

        val knownNames = names.toList()
        val unknownCount = (typingUserIds.size - knownNames.size).coerceAtLeast(0)

        return when {
            knownNames.isEmpty() -> {
                if (typingUserIds.size == 1) "Someone is typing..." else "${typingUserIds.size} people are typing..."
            }
            knownNames.size == 1 && unknownCount == 0 -> {
                "${knownNames[0]} is typing..."
            }
            knownNames.size >= 2 && unknownCount == 0 && typingUserIds.size == 2 -> {
                "${knownNames[0]} and ${knownNames[1]} are typing..."
            }
            knownNames.size >= 2 && unknownCount == 0 -> {
                val others = (typingUserIds.size - 2).coerceAtLeast(0)
                if (others == 0) {
                    "${knownNames[0]} and ${knownNames[1]} are typing..."
                } else {
                    "${knownNames[0]}, ${knownNames[1]}, and $others others are typing..."
                }
            }
            knownNames.size == 1 && unknownCount == 1 -> {
                "${knownNames[0]} and 1 other are typing..."
            }
            knownNames.size == 1 -> {
                "${knownNames[0]} and $unknownCount others are typing..."
            }
            else -> {
                "${knownNames[0]}, ${knownNames[1]}, and $unknownCount others are typing..."
            }
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    private fun scheduleChatListUserInfoSync(localChats: List<com.glyph.glyph_v3.data.local.entity.LocalChat>) {
        val repository = repository ?: return
        val chatsNeedingInfo = localChats.filter { chat ->
            !isEffectivelyGroupChat(chat) &&
            chat.otherUserId.isNotBlank() &&
                (chat.otherUsername.isBlank() || chat.otherUserAvatar.isBlank()) &&
                requestedUserInfoChatIds.add(chat.id)
        }
        if (chatsNeedingInfo.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            chatsNeedingInfo.forEach { chat ->
                repository.syncChatUserInfo(chat.id, chat.otherUserId)
            }
        }
    }

    private fun scheduleChatListAvatarPreload(localChats: List<com.glyph.glyph_v3.data.local.entity.LocalChat>) {
        val appContext = context?.applicationContext ?: return
        val avatarsToCache = localChats.mapNotNull { chat ->
            if (isEffectivelyGroupChat(chat)) return@mapNotNull null
            val avatarUrl = chat.otherUserAvatar.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cachedVisibility = AvatarVisibilityRepository.getCachedProfilePhotoVisibility(chat.otherUserId)
            if (cachedVisibility?.isVisible != true) return@mapNotNull null
            val preloadKey = "${chat.otherUserId}|$avatarUrl"
            if (!requestedAvatarPreloadKeys.add(preloadKey)) return@mapNotNull null
            chat.otherUserId to avatarUrl
        }
        val groupAvatarsToCache = localChats.mapNotNull { chat ->
            if (!isEffectivelyGroupChat(chat)) return@mapNotNull null
            val avatarUrl = chat.groupIconUrl.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val preloadKey = "${chat.id}|$avatarUrl"
            if (!requestedAvatarPreloadKeys.add(preloadKey)) return@mapNotNull null
            chat.id to avatarUrl
        }
        if (avatarsToCache.isEmpty() && groupAvatarsToCache.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (avatarsToCache.isNotEmpty()) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.preloadAvatars(
                    avatarsToCache,
                    appContext
                )
            }
            if (groupAvatarsToCache.isNotEmpty()) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.preloadGroupAvatars(
                    groupAvatarsToCache,
                    appContext
                )
            }
        }
    }

    private fun isEffectivelyGroupChat(local: com.glyph.glyph_v3.data.local.entity.LocalChat): Boolean {
        return local.isGroup || com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(local.id)
    }

    private fun navigateToChat(chatId: String, otherUserId: String, otherUsername: String, otherUserAvatar: String, isCompose: Boolean) {
        val repository = repository ?: return
        val hostActivity = activity ?: return
        val appContext = hostActivity.applicationContext
        ChatOpenTrace.event(
            chatId = chatId,
            stage = "fragment_navigate_enter",
            details = "compose=$isCompose other=${otherUserId.take(8)} avatar=${otherUserAvatar.isNotBlank()}"
        )

        // Debounce: ignore rapid double-taps on the same chat
        val now = SystemClock.uptimeMillis()
        if (lastNavigationChatId == chatId && now - lastNavigationUptimeMs < NAVIGATION_GUARD_WINDOW_MS) {
            ChatOpenTrace.event(chatId, "fragment_navigate_debounced", "delta=${now - lastNavigationUptimeMs}ms")
            return
        }
        lastNavigationChatId = chatId
        lastNavigationUptimeMs = now

        ChatOpenPrefetcher.noteChatOpenStarting(chatId)

        // Fire-and-forget: prime cache in background. ChatActivity's prefill
        // handles its own three-layer cache independently. This just increases
        // the chance retained media preloads are decoded before first bind.
        val primeSource = if (isCompose) "chat_list_compose_tap" else "chat_list_tap"
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                ChatOpenPrefetcher.primeChatOpen(
                    context = appContext,
                    repository = repository,
                    chatId = chatId,
                    source = primeSource,
                    peerUserId = otherUserId,
                    peerAvatarUrl = otherUserAvatar
                )
            }
        }

        val intent = ChatActivity.newIntent(hostActivity, chatId, otherUserId, otherUsername, otherUserAvatar)
        ChatOpenTrace.event(chatId, "start_activity", "username=${otherUsername.isNotBlank()} avatar=${otherUserAvatar.isNotBlank()}")
        hostActivity.startActivity(intent)

        ChatOpenTrace.event(chatId, "transport_prewarm_queue", "source=fragment_navigate")
        ChatConnectionPrewarmer.prewarmForChatOpen(repository, chatId, otherUserId)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.clearUnreadCount(chatId) }
        }
    }

    private fun warmLikelyNextChats(localChats: List<com.glyph.glyph_v3.data.local.entity.LocalChat>) {
        val repository = repository ?: return
        val candidates = localChats
            .asSequence()
            .filter { !it.isArchived }
            .take(6)  // Warm top 6 chats so most taps hit a pre-cached snapshot
            .toList()
        val candidateIds = candidates.map { it.id }
        if (candidateIds.isEmpty()) return
        val deduped = candidateIds.filter { it !in lastPredictivePrefetchChatIds }
        if (deduped.isEmpty()) return

        lastPredictivePrefetchChatIds = candidateIds.toSet()
        val appContext = context?.applicationContext ?: return

        ChatConnectionPrewarmer.prewarmChats(
            repository = repository,
            chats = candidates.map { it.id to it.otherUserId }
        )

        ChatOpenPrefetcher.warmChatsAsync(
            context = appContext,
            repository = repository,
            chatIds = candidateIds,
            source = if (isArchivedMode) "chat_list_compose_archived" else "chat_list_compose_visible"
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatsJob?.cancel()
        presenceJob?.cancel()
        typingJob?.cancel()
        repositoryInitJob?.cancel()
        repositoryInitJob = null
        hasStartedChatListData = false
        hasRequestedSecondaryTabPreload = false
        lastPredictivePrefetchChatIds = emptySet()
        requestedUserInfoChatIds.clear()
        requestedAvatarPreloadKeys.clear()
        composeView = null
    }
}
