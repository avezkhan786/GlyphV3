package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.cache.StatusThumbnailCache
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.BlockStatus
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.data.media.MediaTransferManager
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.utils.ChatWallpaperManager
import com.glyph.glyph_v3.utils.MessageCacheManager
import androidx.compose.runtime.Stable
import android.media.SoundPool
import android.media.AudioAttributes
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.chat.expressive.SentimentType
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver

@Stable
data class ChatUiState(
    val messages: ChatListItemList = ChatListItemList(),
    val otherUserUsername: String = "",
    val otherUserAvatar: String = "",
    val otherUserPresence: String = "", // Online, Last seen...
    val isTyping: Boolean = false, // Other user is typing
    val isSelfTyping: Boolean = false,
    val unreadCount: Int = 0,
    val showScrollToBottom: Boolean = false,
    val replyToMessage: Message? = null,
    val selectionMode: Boolean = false,
    val selectedMessageIds: SelectedMessagesSet = SelectedMessagesSet(),
    val inputText: String = "",
    val wallpaperPath: String? = null,
    val wallpaperDimming: Float = 0f,
    val mediaProgress: MediaProgressMap = MediaProgressMap(),
    val currentUserPhone: String = "",
    val otherUserPhone: String = "",
    val currentUserId: String = "",
    val otherUserId: String = "",
    // Edit mode
    val editingMessage: Message? = null,
    val isEditMode: Boolean = false,
    // One-shot UI message (Compose host should consume then clear)
    val toastMessage: String? = null,
    // Expressive typing sentiment
    val expressiveSentiment: SentimentType = SentimentType.NEUTRAL,
    val isExpressiveActive: Boolean = false,
    val expressiveLiveText: String = "",
    // Block state
    val blockStatus: BlockStatus = BlockStatus.NOT_BLOCKED,
    // Pinned messages (active, non-expired) for this chat
    val pinnedMessages: List<LocalMessage> = emptyList(),
    // WhatsApp-style pagination: whether older messages can still be paged in from the local DB,
    // and whether an older page is currently being loaded.
    val hasMoreOlderMessages: Boolean = false,
    val isLoadingOlderMessages: Boolean = false
)

class ChatViewModel(
    private val context: Context,
    private val repository: RealtimeMessageRepository,
    private val mediaTransferManager: MediaTransferManager,
    private val firebaseRepository: FirebaseRepository,
    private val chatId: String,
    private val otherUserId: String,
    initialOtherUsername: String,
    initialOtherUserAvatar: String
) : ViewModel() {

    companion object {
        private const val DELETE_FOR_ALL_WINDOW_MS: Long = 48 * 60 * 60 * 1000 // 48 hours – must match server

        // WhatsApp-style windowed pagination. We never load the whole history into the
        // adapter at once; we load the most recent INITIAL_WINDOW messages and page in
        // OLDER_PAGE_SIZE more each time the user scrolls near the top.
        // 200 messages gives a comfortable first-scroll buffer (~230 list items)
        // without the heavy opening cost of 500 messages (which took 2289ms).
        private const val INITIAL_WINDOW = 200
        private const val OLDER_PAGE_SIZE = 60

        /**
         * Deterministic message comparator using COALESCE(serverTimestamp, clientTimestamp).
         */
        val MESSAGE_ORDER_COMPARATOR: Comparator<Message> =
            compareBy({ it.orderingTimestamp }, { it.id })
    }

    private val _uiState = MutableStateFlow(ChatUiState(
        otherUserUsername = initialOtherUsername,
        otherUserAvatar = initialOtherUserAvatar
    ))

    // Guard against concurrent/double "Clear chat" execution.
    @Volatile
    private var clearChatInProgress = false

    // STRICT READ RECEIPT VISIBILITY TRACKING
    private val _isChatVisible = MutableStateFlow(false)
    private var hasMarkedAsRead = false // Prevent redundant markChatAsRead calls
    
    // CRITICAL: Cache highest status seen per message to prevent flickering
    private val messageStatusCache = mutableMapOf<String, MessageStatus>()

    // WhatsApp-style pagination state. The live message subscription is bounded by this
    // limit; growing it (via loadOlderMessages) re-subscribes Room with a larger LIMIT,
    // prepending older history. Guards prevent concurrent/duplicate page loads.
    private val messageWindowLimit = MutableStateFlow(INITIAL_WINDOW)
    @Volatile
    private var isLoadingOlderMessages = false
    // Track previous emission's message count for growth-based hasMoreOlderMessages detection.
    // When the window grows and the Room query returns more messages than before, there may
    // be even more older history to page in. When the count stops growing, we've hit the end.
    private var previousMessageCount = 0

    fun onChatResumed() {
        if (!_isChatVisible.value) {
            _isChatVisible.value = true
            // Only mark as read once per session to avoid repeated updates
            if (!hasMarkedAsRead) {
                repository.markChatAsRead(chatId)
                hasMarkedAsRead = true
            }
        }
    }

    fun onChatPaused() {
        _isChatVisible.value = false
        hasMarkedAsRead = false // Reset for next session
    }
    
    // Status ranking for monotonic enforcement
    private fun statusRank(status: MessageStatus): Int {
        return when (status) {
            MessageStatus.SENDING -> 0
            MessageStatus.FAILED -> 1
            MessageStatus.SENT -> 2
            MessageStatus.DELIVERED -> 3
            MessageStatus.READ -> 4
            MessageStatus.PLAYED -> 5
            MessageStatus.PENDING_DOWNLOAD -> 0
            MessageStatus.DOWNLOADING -> 0
            MessageStatus.DOWNLOAD_FAILED -> 1
            MessageStatus.DOWNLOADED -> 3
            else -> 0
        }
    }

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageSyncListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var readReceiptListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var typingJob: Job? = null
    private val zoneId: ZoneId by lazy { ZoneId.systemDefault() }
    private val dateFormatter: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.getDefault())
    }

    // Sound effects
    private var soundPool: SoundPool? = null
    private var soundSentId: Int = 0
    private var soundReceivedId: Int = 0
    private var lastMessageId: String? = null
    
    // Cache for processed messages to avoid redundant computation
    private var lastProcessedMessages: List<Message>? = null
    private var lastProcessedResult: List<ChatListItem>? = null

    // Prevent duplicate sends and race conditions
    private var isSending = false

    init {
        initSoundPool()
        loadWallpaper()
        loadMessages()
        observePresence()
        observeTyping()
        observeMediaProgress()
        loadUserDetails()
        observeBlockStatus()
        observeContactCacheChanges()
    }

    private fun observeContactCacheChanges() {
        viewModelScope.launch {
            ContactDisplayNameResolver.cacheVersion.collect { _ ->
                // When device contacts change, re-resolve the display name
                if (otherUserId.isNotEmpty()) {
                    val currentState = _uiState.value
                    val resolvedName = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = otherUserId,
                        remoteProfileName = currentState.otherUserUsername,
                        remotePhoneNumber = currentState.otherUserPhone
                    )
                    if (resolvedName != currentState.otherUserUsername) {
                        _uiState.update { it.copy(otherUserUsername = resolvedName) }
                    }
                }
            }
        }
    }

    private fun loadUserDetails() {
        val authUid = firebaseRepository.currentUserId
        firebaseRepository.getUser { user ->
            user?.let { u ->
                _uiState.update { it.copy(
                    currentUserPhone = u.phoneNumber,
                    currentUserId = u.id.ifEmpty { authUid ?: "" }
                ) }
            }
        }
        if (otherUserId.isNotEmpty()) {
            _uiState.update { it.copy(otherUserId = otherUserId) }
            firebaseRepository.getUser(otherUserId) { user ->
                user?.let { u ->
                    // Cache phone number for contact name resolution
                    if (u.phoneNumber.isNotBlank()) {
                        ContactDisplayNameResolver.cacheUserPhone(u.id, u.phoneNumber)
                    }
                    // Resolve display name with device contact priority
                    val currentDisplayName = _uiState.value.otherUserUsername
                    val resolvedName = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = otherUserId,
                        remoteProfileName = u.username.ifBlank { currentDisplayName },
                        remotePhoneNumber = u.phoneNumber
                    )
                    _uiState.update { it.copy(
                        otherUserPhone = u.phoneNumber,
                        otherUserUsername = resolvedName
                    ) }
                }
            }
        }
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        soundSentId = soundPool?.load(context, R.raw.message_sent, 1) ?: 0
        soundReceivedId = soundPool?.load(context, R.raw.message_received, 1) ?: 0
    }

    private fun playSound(soundId: Int) {
        if (soundId != 0) {
            soundPool?.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f)
        }
    }

    private fun loadWallpaper() {
        // 1. Try to use cached values immediately for zero-latency display
        val cachedPath = ChatWallpaperManager.getCachedWallpaperPath()
        val cachedDimming = ChatWallpaperManager.getCachedWallpaperDimming()
        
        if (cachedPath != null) {
            val fullPath = ChatWallpaperManager.assetUri(cachedPath).toString()
            _uiState.update { it.copy(wallpaperPath = fullPath, wallpaperDimming = cachedDimming) }
        }

        // 2. Launch async check to ensure we have the latest from DataStore
        viewModelScope.launch {
            val folder = ChatWallpaperManager.getEffectiveThemeFolder(context)
            val resolvedPath = ChatWallpaperManager.resolveWallpaperToApply(context, folder)
            val dimming = ChatWallpaperManager.getWallpaperDimming(context, folder)
            
            val fullPath = if (resolvedPath != null) {
                ChatWallpaperManager.assetUri(resolvedPath).toString()
            } else null

            _uiState.update { it.copy(wallpaperPath = fullPath, wallpaperDimming = dimming) }
        }
    }

    /**
     * Grows the live message window by one page so older history is paged in from the
     * local DB. Called when the user scrolls near the top of the list. No-ops when a
     * page is already loading or there is nothing older left to load.
     */
    fun loadOlderMessages() {
        if (isLoadingOlderMessages) return
        if (!_uiState.value.hasMoreOlderMessages) return
        isLoadingOlderMessages = true
        _uiState.update { it.copy(isLoadingOlderMessages = true) }
        messageWindowLimit.value = messageWindowLimit.value + OLDER_PAGE_SIZE
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun loadMessages() {
        if (chatId.isEmpty()) return

        viewModelScope.launch {
            val latestLocalTimestamp = withContext(Dispatchers.IO) {
                repository.getLatestLocalMessageTimestamp(chatId)
            }
            messageSyncListener?.remove()
            messageSyncListener = repository.syncMessages(chatId, latestLocalTimestamp)
        }
        
        // Start syncing read receipts
        if (otherUserId.isNotEmpty()) {
            readReceiptListener?.remove()
            readReceiptListener = repository.syncReadReceipts(chatId, otherUserId)
        }
        
        viewModelScope.launch {
            // CRITICAL: Defer status sync until after initial messages load
            // This prevents Firestore from overwriting cached status on chat open
            // OPTIMIZATION: Try to load from cache or DB immediately for instant display
            val cachedMessages = MessageCacheManager.getMessages(chatId)
            if (cachedMessages != null) {
                 // CRITICAL: Pre-warm message lazies off the main thread BEFORE emitting to Compose.
                 // This avoids first-scroll jank caused by JSON parsing (mediaItems), timestamp
                 // formatting, and aspect ratio computation happening lazily on the UI thread.
                 val warmed = withContext(Dispatchers.Default) {
                     cachedMessages.forEach { item ->
                         val msg = (item as? ChatListItem.MessageItem)?.message
                         msg?.warmUpForUi()
                     }
                     cachedMessages
                 }
                 // Never let the live window shrink below what we already show on a warm
                 // open (the cache may hold a larger snapshot from a previous session).
                 val shownMessageCount = warmed.count { it is ChatListItem.MessageItem }
                 messageWindowLimit.value = maxOf(INITIAL_WINDOW, shownMessageCount)
                 _uiState.update { it.copy(messages = ChatListItemList(warmed)) }
            } else {
                 // Fallback to quick DB fetch if not in memory cache
                 val recent = withContext(Dispatchers.IO) {
                     repository.getRecentMessages(chatId, INITIAL_WINDOW)
                 }
                 messageWindowLimit.value = INITIAL_WINDOW
                 if (recent.isNotEmpty()) {
                     val listItems = withContext(Dispatchers.Default) {
                         val sortedMessages = recent.sortedWith(MESSAGE_ORDER_COMPARATOR)
                         sortedMessages.forEach { it.warmUpForUi() }
                         processMessagesWithHeaders(sortedMessages)
                     }
                     _uiState.update { it.copy(messages = ChatListItemList(listItems)) }
                 }
            }

            messageWindowLimit
                .flatMapLatest { limit -> repository.getRecentMessagesFlow(chatId, limit) }
                .distinctUntilChanged { old, new ->
                    // Only emit if message list actually changed
                    if (old.size != new.size) return@distinctUntilChanged false
                    // Check if any message ID, status, text, or ordering timestamp changed
                    old.zip(new).all { (a, b) ->
                        a.id == b.id && 
                        a.status == b.status && 
                        a.text == b.text &&
                        a.editedAt == b.editedAt &&
                        a.serverTimestamp == b.serverTimestamp
                    }
                }
                .map { messages ->
                    // CRITICAL: Enforce monotonic status - never downgrade
                    messages.map { message ->
                        val cachedStatus = messageStatusCache[message.id]
                        if (cachedStatus != null) {
                            // Compare status ranks - only upgrade, never downgrade
                            val currentRank = statusRank(message.status)
                            val cachedRank = statusRank(cachedStatus)
                            if (currentRank > cachedRank) {
                                // Upgrade: use new status
                                messageStatusCache[message.id] = message.status
                                message
                            } else {
                                // Keep cached higher status
                                message.copy(status = cachedStatus)
                            }
                        } else {
                            // First time seeing this message
                            messageStatusCache[message.id] = message.status
                            message
                        }
                    }
                }
                .collectLatest { messages ->
                    // WhatsApp-style pagination bookkeeping: use growth detection so that
                    // hasMoreOlderMessages stays true as long as each larger window returns
                    // more messages than the previous one. Falls back to window-fill check
                    // for the first emission (previousMessageCount starts at 0).
                    val moreOlder = messages.size > previousMessageCount || messages.size >= messageWindowLimit.value
                    previousMessageCount = messages.size
                    if (isLoadingOlderMessages ||
                        _uiState.value.hasMoreOlderMessages != moreOlder ||
                        _uiState.value.isLoadingOlderMessages) {
                        isLoadingOlderMessages = false
                        _uiState.update {
                            it.copy(
                                hasMoreOlderMessages = moreOlder,
                                isLoadingOlderMessages = false
                            )
                        }
                    }
                    if (messages.isEmpty()) {
                        _uiState.update { it.copy(messages = ChatListItemList(emptyList())) }
                        return@collectLatest
                    }

                    // COALESCE ordering: sort by serverTimestamp (if available) else clientTimestamp.
                    // This keeps messages at their correct chronological position whether or not
                    // they have a Firestore ACK yet, preventing the jarring settled-first jump.
                    val sortedMessages: List<Message> =
                        messages.sortedWith(MESSAGE_ORDER_COMPARATOR)

                    val latestMsg = sortedMessages.last()
                    
                    // Only flag for entry animation if it's a genuine new incoming message
                    // AND it arrived within a reasonable window (last 10 seconds)
                    val now = System.currentTimeMillis()
                    val isNewIncoming = lastMessageId != null && 
                                        latestMsg.id != lastMessageId && 
                                        latestMsg.isIncoming &&
                                        (now - latestMsg.timestamp < 10000)
                    
                    // Play sound for new incoming message
                    if (isNewIncoming) {
                        playSound(soundReceivedId)
                    }

                    // STRICT SEQUENCING: Hide typing indicator before showing new message
                    // If we have a new incoming message and the typing indicator is visible,
                    // we hide it first and wait briefly to ensure atomic transition.
                    if (isNewIncoming && _uiState.value.isTyping) {
                        _uiState.update { it.copy(isTyping = false) }
                        delay(150) // Atomic hide first
                    }

                    // Cache check: Only process if messages changed
                    val listItems = if (messages == lastProcessedMessages && lastProcessedResult != null) {
                        lastProcessedResult!!
                    } else {
                        withContext(Dispatchers.Default) {
                            // Already sorted above, reuse for efficiency
                            // CRITICAL: Warm lazily computed message fields off main thread.
                            // This prevents the "first scroll" hitch where many items compute
                            // formattedTime / media JSON / aspect ratio during scrolling.
                            sortedMessages.forEach { it.warmUpForUi() }

                            val result = processMessagesWithHeaders(sortedMessages)
                            lastProcessedMessages = messages
                            lastProcessedResult = result
                            
                            // Update global cache for next time
                            MessageCacheManager.putMessages(chatId, result)

                            // Pre-cache thumbnails for status reply messages so they appear
                            // instantly on next open without hitting the network.
                            val statusReplies = sortedMessages.filter {
                                it.type == MessageType.STATUS_REPLY
                            }
                            if (statusReplies.isNotEmpty()) {
                                StatusThumbnailCache.preload(context, statusReplies)
                            }

                            result
                        }
                    }

                    // Flag only the newly arrived incoming message for entry animation
                    val displayListItems = if (isNewIncoming) {
                        listItems.map { item ->
                            if (item is ChatListItem.MessageItem && item.message.id == latestMsg.id) {
                                item.copy(shouldAnimateEntry = true)
                            } else {
                                item
                            }
                        }
                    } else {
                        listItems
                    }
                    
                    // Mark incoming messages as read
                    // CRITICAL FIX: Only mark as read if:
                    // 1. Chat screen is explicitly visible (RESUMED)
                    // 2. There are actually unread incoming messages
                    // 3. We haven't already marked this batch as read
                    val hasUnreadIncoming = messages.any { it.isIncoming && it.status != MessageStatus.READ }
                    if (hasUnreadIncoming && _isChatVisible.value && !hasMarkedAsRead) {
                        repository.markChatAsRead(chatId)
                        hasMarkedAsRead = true
                    }

                    _uiState.update { currentState ->
                        currentState.copy(
                            messages = ChatListItemList(displayListItems),
                            unreadCount = 0 
                        )
                    }
                    lastMessageId = latestMsg.id
                }
        }
        
        // CRITICAL: Start status observer AFTER initial load completes
        // Delay briefly to ensure UI has stabilized with cached values
        viewModelScope.launch {
            delay(500) // Let initial messages render from database
            repository.observeMessageStatusUpdates(chatId)
        }
    }

    private fun processMessagesWithHeaders(messages: List<Message>): List<ChatListItem> {
        val tempResult = mutableListOf<ChatListItem>()
        var lastDate: LocalDate? = null
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)

        // 1. Add Headers and Messages
        var currentHeaderText = ""
        for (message in messages) {
            val messageDate = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
            if (messageDate != lastDate) {
                currentHeaderText = when (messageDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateFormatter.format(messageDate)
                }
                tempResult.add(ChatListItem.DateHeader(currentHeaderText))
                lastDate = messageDate
            }
            tempResult.add(ChatListItem.MessageItem(message, dateString = currentHeaderText))
        }

        // 2. Compute Grouping
        val groupedResult = mutableListOf<ChatListItem>()
        for (i in tempResult.indices) {
            val item = tempResult[i]
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val prev = if (i > 0) tempResult[i - 1] else null
                val next = if (i < tempResult.size - 1) tempResult[i + 1] else null

                val hasPrevSame = prev is ChatListItem.MessageItem && prev.message.senderId == msg.senderId
                val hasNextSame = next is ChatListItem.MessageItem && next.message.senderId == msg.senderId

                val groupPos = when {
                    !hasPrevSame && !hasNextSame -> BubbleGroupPosition.SINGLE
                    !hasPrevSame && hasNextSame -> BubbleGroupPosition.TOP
                    hasPrevSame && hasNextSame -> BubbleGroupPosition.MIDDLE
                    else -> BubbleGroupPosition.BOTTOM
                }
                groupedResult.add(item.copy(groupPosition = groupPos))
            } else {
                groupedResult.add(item)
            }
        }

        // 3. Reverse for UI (LazyColumn reverseLayout = true)
        return groupedResult.reversed()
    }

    private fun observePresence() {
        if (otherUserId.isEmpty()) return
        viewModelScope.launch {
            combine(
                PresenceManager.observeUserPresence(otherUserId),
                BlockRepository.observeBlockStatus(otherUserId)
            ) { presence, blockStatus ->
                // Keep block status in sync with the UI state
                _uiState.update { it.copy(blockStatus = blockStatus) }

                if (blockStatus.isBlocked) {
                    // If blocked in either direction, hide presence info entirely
                    _uiState.update { it.copy(
                        otherUserPresence = "",
                        isTyping = false
                    ) }
                    null
                } else {
                    when {
                        presence.isOnline && presence.viewingChatId == chatId -> "Online in-chat"
                        presence.isOnline -> "Online"
                        else -> formatLastSeen(presence.lastSeen)
                    }
                }
            }.collect { statusText ->
                if (statusText != null) {
                    _uiState.update { it.copy(otherUserPresence = statusText) }
                }
            }
        }
    }

    private fun formatLastSeen(timestamp: Long): String {
        if (timestamp == 0L) return "Last seen recently"
        
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        val sdfTime = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        val sdfDate = java.text.SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
        
        return when {
            diff < 60000 -> "last seen just now"
            diff < 3600000 -> "last seen ${diff / 60000} minutes ago"
            isToday(timestamp) -> "last seen today at ${sdfTime.format(java.util.Date(timestamp))}"
            isYesterday(timestamp) -> "last seen yesterday at ${sdfTime.format(java.util.Date(timestamp))}"
            else -> "last seen ${sdfDate.format(java.util.Date(timestamp))}"
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val date = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        return date == java.time.LocalDate.now(zoneId)
    }

    private fun isYesterday(timestamp: Long): Boolean {
        val date = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        return date == java.time.LocalDate.now(zoneId).minusDays(1)
    }

    private fun observeTyping() {
        if (chatId.isEmpty() || otherUserId.isEmpty()) return
        viewModelScope.launch {
            PresenceManager.observeTypingStatus(chatId, otherUserId).collectLatest { isTyping ->
                _uiState.update { it.copy(isTyping = isTyping) }
            }
        }
    }

    private fun observeMediaProgress() {
        viewModelScope.launch {
            MediaProgressManager.progressMap.collectLatest { progressMap ->
                _uiState.update { it.copy(mediaProgress = MediaProgressMap(progressMap.mapValues { entry -> entry.value.progress })) }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Block State
    // ──────────────────────────────────────────────

    private fun observeBlockStatus() {
        if (otherUserId.isEmpty()) return
        viewModelScope.launch {
            BlockRepository.observeBlockStatus(otherUserId).collectLatest { status ->
                if (status.isBlocked) {
                    stopTyping()
                }
            }
        }
    }

    fun blockUser() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BlockRepository.blockUser(otherUserId)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to block user", e)
                _uiState.update { it.copy(toastMessage = "Failed to block user. Please try again.") }
            }
        }
    }

    fun unblockUser() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BlockRepository.unblockUser(otherUserId)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to unblock user", e)
                _uiState.update { it.copy(toastMessage = "Failed to unblock user. Please try again.") }
            }
        }
    }

    fun downloadMedia(message: Message) {
        if (message.type == MessageType.MEDIA_GROUP) {
            viewModelScope.launch {
                repository.updateMessageStatus(message.id, MessageStatus.DOWNLOADING)
                mediaTransferManager.startGroupDownload(message)
            }
            return
        }

        val remoteUrl = when (message.type) {
            MessageType.IMAGE -> message.imageUrl
            MessageType.VIDEO -> message.videoUrl
            MessageType.AUDIO -> message.audioUrl
            MessageType.DOCUMENT -> message.imageUrl   // document URL stored in imageUrl
            else -> null
        }

        if (remoteUrl.isNullOrEmpty()) return

        viewModelScope.launch {
            repository.updateMessageStatus(message.id, MessageStatus.DOWNLOADING)
            mediaTransferManager.startDownload(
                messageId = message.id,
                chatId = chatId,
                mediaType = message.type,
                remoteUrl = remoteUrl,
                expectedSize = message.fileSize
            )
        }
    }

    private fun stopTyping() {
        typingJob?.cancel()
        // Fire and forget presence update on IO to avoid blocking UI
        viewModelScope.launch(Dispatchers.IO) {
            PresenceManager.setTypingStatus(chatId, otherUserId, false)
        }
        _uiState.update { it.copy(isSelfTyping = false) }
    }

    fun setReplyToMessage(message: Message?) {
        _uiState.update { it.copy(replyToMessage = message) }
    }

    fun clearReplyToMessage() {
        _uiState.update { it.copy(replyToMessage = null) }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Block guard: prevent sending messages to/from blocked users
        val blockStatus = _uiState.value.blockStatus
        if (blockStatus.isBlocked) {
            val msg = when (blockStatus) {
                BlockStatus.I_BLOCKED_THEM, BlockStatus.MUTUAL_BLOCK -> 
                    "Unblock this contact to send messages."
                BlockStatus.THEY_BLOCKED_ME -> 
                    "You can't send messages to this contact."
                else -> "Cannot send message."
            }
            _uiState.update { it.copy(toastMessage = msg) }
            return
        }
        
        // Prevent race conditions / duplicate sends
        if (isSending) return
        
        // Check if we're in edit mode
        if (_uiState.value.isEditMode && _uiState.value.editingMessage != null) {
            // Edit existing message
            val editingMsg = _uiState.value.editingMessage!!
            if (!isMessageEditable(editingMsg)) {
                cancelEditMode()
                return
            }
            if (text.trim() != editingMsg.text) {
                viewModelScope.launch {
                    repository.editMessage(
                        messageId = editingMsg.id,
                        newText = text.trim(),
                        chatId = chatId,
                        otherUserId = otherUserId
                    )
                }
            }
            // Exit edit mode
            cancelEditMode()
            return
        }

        isSending = true
        
        // OPTIMISTIC UPDATE: Clear input immediately to prevent freezing perception
        val messageText = text.trim()
        val replyTo = _uiState.value.replyToMessage
        
        // Capture context needed for sending before clearing state
        val currentOtherUserId = otherUserId
        val currentOtherUsername = _uiState.value.otherUserUsername.ifEmpty { "Unknown" }
        val currentOtherUserAvatar = _uiState.value.otherUserAvatar
        
        // Clear UI state immediately
        stopTyping()
        _uiState.update { it.copy(inputText = "", replyToMessage = null) }
        
        viewModelScope.launch {
            try {
                // Play sound immediately for better feedback
                playSound(soundSentId)
                
                // Perform network/DB operation on IO dispatcher
                withContext(Dispatchers.IO) {
                    repository.sendMessage(
                        chatId, 
                        messageText, 
                        currentOtherUserId, 
                        currentOtherUsername, 
                        currentOtherUserAvatar,
                        replyToMessageId = replyTo?.id,
                        replyToText = replyTo?.text,
                        replyToSenderId = replyTo?.senderId,
                        replyToType = replyTo?.type
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to send message", e)
                // Restore text on failure so user doesn't lose it
                _uiState.update { 
                    it.copy(
                        inputText = messageText,
                        // Could also restore replyToMessage if needed, but it's trickier to track.
                        // For now just restoring text is the critical part.
                        toastMessage = "Failed to send message. Please try again."
                    ) 
                }
            } finally {
                isSending = false
            }
        }
    }

    fun sendMedia(
        uris: List<Uri>, 
        quality: CompressionQuality, 
        overrides: Map<Uri, CompressionQuality>
    ) {
        if (uris.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // UI updates must be on main thread
                withContext(Dispatchers.Main) {
                    stopTyping()
                    playSound(soundSentId)
                }

                // If multiple items, send as grouped message
                if (uris.size > 1) {
                    repository.sendGroupedMediaMessage(
                        chatId,
                        uris,
                        otherUserId,
                        _uiState.value.otherUserUsername.ifEmpty { "Unknown" },
                        _uiState.value.otherUserAvatar,
                        quality,
                        overrides
                    )
                } else {
                    // Single item
                    val uri = uris.first()
                    val itemQuality = overrides[uri] ?: quality
                    // ContentResolver access needs context, safe to do here generally, 
                    // but better if repository handles it or we do it carefully.
                    // Assuming context access is thread-safe enough or cached.
                    val mimeType = context.contentResolver.getType(uri)
                    
                    if (mimeType?.startsWith("video/") == true) {
                        repository.sendVideoMessage(
                            chatId, uri, otherUserId, 
                            _uiState.value.otherUserUsername.ifEmpty { "Unknown" }, 
                            _uiState.value.otherUserAvatar
                        )
                    } else {
                        repository.sendImageMessage(
                            chatId, uri, otherUserId, 
                            _uiState.value.otherUserUsername.ifEmpty { "Unknown" }, 
                            _uiState.value.otherUserAvatar
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error sending media", e)
            }
        }
    }

    fun sendImage(uri: Uri) {
        viewModelScope.launch {
            stopTyping()
            repository.sendImageMessage(
                chatId,
                uri,
                otherUserId,
                _uiState.value.otherUserUsername.ifEmpty { "Unknown" },
                _uiState.value.otherUserAvatar
            )
            playSound(soundSentId)
        }
    }

    fun sendVideo(uri: Uri) {
        viewModelScope.launch {
            stopTyping()
            repository.sendVideoMessage(
                chatId,
                uri,
                otherUserId,
                _uiState.value.otherUserUsername.ifEmpty { "Unknown" },
                _uiState.value.otherUserAvatar
            )
            playSound(soundSentId)
        }
    }

    fun sendVoice(file: java.io.File, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                stopTyping()
                playSound(soundSentId)
            }
            repository.sendVoiceMessage(
                chatId,
                file,
                duration,
                otherUserId,
                _uiState.value.otherUserUsername.ifEmpty { "Unknown" },
                _uiState.value.otherUserAvatar
            )
        }
    }

    fun sendDocument(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                stopTyping()
                playSound(soundSentId)
            }
            repository.sendDocumentMessage(
                chatId,
                uri,
                otherUserId,
                _uiState.value.otherUserUsername.ifEmpty { "Unknown" },
                _uiState.value.otherUserAvatar
            )
        }
    }

    fun toggleSelection(messageId: String) {
        _uiState.update { currentState ->
            val newSelectedIds = currentState.selectedMessageIds.ids.toMutableSet()
            if (newSelectedIds.contains(messageId)) {
                newSelectedIds.remove(messageId)
            } else {
                newSelectedIds.add(messageId)
            }
            
            currentState.copy(
                selectedMessageIds = SelectedMessagesSet(newSelectedIds),
                selectionMode = newSelectedIds.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { 
            it.copy(
                selectedMessageIds = SelectedMessagesSet(),
                selectionMode = false
            )
        }
    }
    
    fun deleteSelectedMessages() {
        val selectedIds = _uiState.value.selectedMessageIds.ids.toList()
        if (selectedIds.isNotEmpty()) {
            viewModelScope.launch {
                repository.deleteMessages(chatId, selectedIds)
                clearSelection()
            }
        }
    }

    /**
     * Clear all messages in this chat — WhatsApp-style "Clear Chat".
     * Permanently removes messages from local DB, Firestore, local media files,
     * and inserts tombstones to prevent Firestore sync from re-inserting them.
     */
    fun clearChat() {
        // Guard against double execution (e.g. rapid double-tap on the confirm button).
        if (clearChatInProgress) return
        clearChatInProgress = true
        viewModelScope.launch {
            try {
                repository.clearChatMessages(chatId)
                // Reset UI state: clear messages, selection, reply, edit mode
                _uiState.update {
                    it.copy(
                        messages = ChatListItemList(),
                        selectedMessageIds = SelectedMessagesSet(),
                        selectionMode = false,
                        replyToMessage = null,
                        editingMessage = null,
                        isEditMode = false,
                        inputText = "",
                        toastMessage = "Chat cleared"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to clear chat") }
            } finally {
                clearChatInProgress = false
            }
        }
    }

    fun deleteSelectedMessagesForAll() {
        val selectedIds = _uiState.value.selectedMessageIds.ids.toList()
        if (selectedIds.isNotEmpty()) {
            viewModelScope.launch {
                val result = repository.deleteMessagesForAll(chatId, selectedIds)

                if (result.deletedIds.isNotEmpty()) {
                    clearSelection()
                }

                val message = when {
                    result.failureMessage != null -> "Couldn't delete for all. ${result.failureMessage}"
                    result.rejected.isNotEmpty() && result.deletedIds.isEmpty() -> {
                        val reason = result.rejected.values.firstOrNull() ?: "unknown"
                        "Couldn't delete for all ($reason)"
                    }
                    result.rejected.isNotEmpty() -> "Some messages couldn't be deleted for all"
                    result.deletedIds.isEmpty() -> "Couldn't delete for all"
                    else -> null
                }

                if (message != null) {
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }
        }
    }

    fun consumeToastMessage() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun isEligibleForDeleteForAll(message: Message): Boolean {
        if (message.isIncoming) return false
        if (message.isDeletedForAll) return false
        val now = System.currentTimeMillis()
        return (now - message.timestamp) <= DELETE_FOR_ALL_WINDOW_MS
    }

    fun areSelectedMessagesEligibleForDeleteForAll(): Boolean {
        val selectedIds = _uiState.value.selectedMessageIds.ids
        if (selectedIds.isEmpty()) return false
        val selectedMessages = _uiState.value.messages.items
            .filterIsInstance<ChatListItem.MessageItem>()
            .filter { it.message.id in selectedIds }
            .map { it.message }
        if (selectedMessages.isEmpty()) return false
        return selectedMessages.all { isEligibleForDeleteForAll(it) }
    }

    fun getSelectedMessagesText(): String {
        val selectedIds = _uiState.value.selectedMessageIds.ids
        val messages = _uiState.value.messages.items
            .filterIsInstance<ChatListItem.MessageItem>()
            .filter { it.message.id in selectedIds }
            .filter { !it.message.isDeletedForAll }
            .sortedBy { it.message.timestamp } // Sort by time
            
        return messages.joinToString("\n") { it.message.text ?: "" }
    }

    fun getSelectedMessage(): Message? {
        val selectedIds = _uiState.value.selectedMessageIds.ids
        if (selectedIds.size != 1) return null
        
        val id = selectedIds.first()
        return _uiState.value.messages.items
            .filterIsInstance<ChatListItem.MessageItem>()
            .find { it.message.id == id }
            ?.message
    }

    fun pinSelectedMessages() {
        // TODO: Implement pinning logic in repository
        clearSelection()
    }

    fun onReply(message: Message) {
        _uiState.update { it.copy(replyToMessage = message) }
    }

    fun onCancelReply() {
        _uiState.update { it.copy(replyToMessage = null) }
    }

    fun onTyping(text: String) {
        _uiState.update { it.copy(inputText = text) }
        
        val isTyping = text.isNotEmpty()
        if (isTyping != _uiState.value.isSelfTyping) {
             if (!isTyping) {
                 stopTyping()
             } else {
                 _uiState.update { it.copy(isSelfTyping = true) }
                 // Debounce typing updates
                 typingJob?.cancel()
                 typingJob = viewModelScope.launch(Dispatchers.IO) {
                     PresenceManager.setTypingStatus(chatId, otherUserId, true)
                     delay(3000)
                     withContext(Dispatchers.Main) {
                         stopTyping()
                     }
                 }
             }
        }
    }

    // Edit mode functions
    fun isMessageEditable(message: Message): Boolean {
        // Only text messages that are outgoing can be edited
        if (message.type != MessageType.TEXT || message.isIncoming) return false
        if (message.isDeletedForAll) return false
        
        // Check time window (1 hour)
        val currentTime = System.currentTimeMillis()
        val timeSinceMessage = currentTime - message.timestamp
        val editTimeWindow = 60 * 60 * 1000L
        
        return timeSinceMessage <= editTimeWindow
    }

    fun enterEditMode(message: Message) {
        if (!isMessageEditable(message)) return
        
        // Clear selection mode if active
        if (_uiState.value.selectionMode) {
            clearSelection()
        }
        
        // Clear reply mode if active
        if (_uiState.value.replyToMessage != null) {
            _uiState.update { it.copy(replyToMessage = null) }
        }
        
        _uiState.update { 
            it.copy(
                editingMessage = message,
                isEditMode = true,
                inputText = message.text
            ) 
        }
    }

    fun cancelEditMode() {
        _uiState.update { 
            it.copy(
                editingMessage = null,
                isEditMode = false,
                inputText = ""
            ) 
        }
    }

    fun setViewingChat(isViewing: Boolean) {
        if (isViewing) {
            PresenceManager.setViewingChat(chatId)
        } else {
            PresenceManager.setViewingChat(null)
        }
    }

    /**
     * Updates the expressive typing state from ExpressiveTypingManager.
     * Called by ChatActivity when sentiment analysis detects emotional content.
     */
    fun updateExpressiveState(
        sentiment: SentimentType,
        isActive: Boolean,
        liveText: String = ""
    ) {
        _uiState.update {
            it.copy(
                expressiveSentiment = sentiment,
                isExpressiveActive = isActive,
                expressiveLiveText = liveText
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        messageSyncListener?.remove()
        readReceiptListener?.remove()
        soundPool?.release()
        soundPool = null
        PresenceManager.setViewingChat(null)
    }
}

class ChatViewModelFactory(
    private val context: Context,
    private val repository: RealtimeMessageRepository,
    private val mediaTransferManager: MediaTransferManager,
    private val chatId: String,
    private val otherUserId: String,
    private val otherUsername: String,
    private val otherUserAvatar: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                context,
                repository,
                mediaTransferManager,
                FirebaseRepository(),
                chatId,
                otherUserId,
                otherUsername,
                otherUserAvatar
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
