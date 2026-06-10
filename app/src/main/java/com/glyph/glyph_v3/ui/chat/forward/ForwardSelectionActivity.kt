package com.glyph.glyph_v3.ui.chat.forward

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.R
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.ChatConnectionPrewarmer
import com.glyph.glyph_v3.ui.chat.ChatOpenPrefetcher
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ForwardSelectionActivity : ComponentActivity() {
    private val db by lazy { AppDatabase.getDatabase(applicationContext) }
    private val messageDao by lazy { db.messageDao() }
    private val chatDao by lazy { db.chatDao() }
    private val repository by lazy {
        RealtimeMessageRepository(
            messageDao = messageDao,
            chatDao = chatDao,
            deletedMessageDao = db.deletedMessageDao(),
            context = applicationContext
        )
    }
    private val firebaseRepository by lazy { FirebaseRepository() }
    private val gson = Gson()
    private val mediaItemListType = object : TypeToken<List<MediaItem>>() {}.type

    private var uiState by mutableStateOf(ForwardUiState())
    private var sourceChatId: String = ""
    private var messageIds: List<String> = emptyList()
    private var payloadToken: String? = null
    private var cachedMessages: List<LocalMessage> = emptyList()
    private val launchElapsedMs = SystemClock.elapsedRealtime()

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { loadForwardTargets() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#01090C")
        window.navigationBarColor = android.graphics.Color.parseColor("#01090C")
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.anim_stay)

        sourceChatId = intent.getStringExtra(EXTRA_SOURCE_CHAT_ID).orEmpty()
        messageIds = intent.getStringArrayListExtra(EXTRA_MESSAGE_IDS).orEmpty().filter { it.isNotBlank() }
        payloadToken = intent.getStringExtra(EXTRA_PAYLOAD_TOKEN)
        cachedMessages = ForwardMessageCache.get(payloadToken, messageIds, sourceChatId)
        Log.d(TAG, "open sourceChatId=$sourceChatId selected=${messageIds.size} cacheHits=${cachedMessages.size}")

        if (messageIds.isEmpty()) {
            Toast.makeText(this, "No messages selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        uiState = uiState.copy(
            messageCount = messageIds.size,
            sourcePreview = buildSourcePreview(cachedMessages),
            unsupportedStatusCount = cachedMessages.count { !it.canForwardToStatus() }
        )

        setContent {
            ForwardSelectionScreen(
                state = uiState,
                onBack = { finish() },
                onQueryChange = { uiState = uiState.copy(query = it) },
                onSearchClick = {
                    val nextActive = !uiState.searchActive
                    uiState = uiState.copy(searchActive = nextActive, query = if (nextActive) uiState.query else "")
                },
                onCaptionChange = { uiState = uiState.copy(caption = it.withForwardInputCapitalization()) },
                onTargetClick = ::toggleTarget,
                onSendClick = ::forwardSelectedTargets,
                onNewChatClick = { Toast.makeText(this, "Select a contact below", Toast.LENGTH_SHORT).show() }
            )
        }

        loadForwardTargets()
        loadSourcePreviewIfNeeded()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.anim_stay, R.anim.slide_out_bottom)
    }

    override fun onDestroy() {
        if (isFinishing) ForwardMessageCache.remove(payloadToken)
        super.onDestroy()
    }

    private fun toggleTarget(target: ForwardTarget) {
        if (uiState.isSending) return
        if (target.type == ForwardTargetType.GLYPH_AI) {
            Toast.makeText(this, "Glyph AI forwarding isn't available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val next = uiState.selectedKeys.toMutableSet()
        if (!next.add(target.key)) next.remove(target.key)
        uiState = uiState.copy(selectedKeys = next)
        Log.d(TAG, "target_toggle key=${target.key} selected=${target.key in next} selectedKeys=$next")
    }

    private fun loadSourcePreviewIfNeeded() {
        if (uiState.sourcePreview != null) return
        lifecycleScope.launch {
            val sourceMessages = withContext(Dispatchers.IO) { resolveSourceMessages() }
            if (sourceMessages.isNotEmpty()) cachedMessages = sourceMessages
            val preview = buildSourcePreview(sourceMessages)
            if (preview != uiState.sourcePreview) {
                uiState = uiState.copy(
                    sourcePreview = preview,
                    unsupportedStatusCount = sourceMessages.count { !it.canForwardToStatus() }
                )
            }
        }
    }

    private fun loadForwardTargets() {
        lifecycleScope.launch {
            uiState = uiState.copy(isLoading = true)
            val targets = withContext(Dispatchers.IO) { buildForwardTargets() }
            uiState = uiState.copy(
                isLoading = false,
                targets = targets,
                selectedKeys = uiState.selectedKeys.filterTo(mutableSetOf()) { key -> targets.any { it.key == key } }
            )
            Log.d(TAG, "targets_loaded count=${targets.size} elapsed=${SystemClock.elapsedRealtime() - launchElapsedMs}ms")
        }
    }

    private suspend fun buildForwardTargets(): List<ForwardTarget> {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val localChats = chatDao.getAllChatsOnce()
            .filter { it.id != sourceChatId && it.otherUserId.isNotBlank() }
            .distinctBy { it.otherUserId }

        val localTargets = localChats.mapIndexed { index, chat ->
            chat.toForwardTarget(
                section = if (index < FREQUENT_LIMIT) ForwardSection.FREQUENT else ForwardSection.RECENT
            )
        }

        val localUserIds = localChats.map { it.otherUserId }.toSet()
        val contactTargets = if (hasContactsPermission()) {
            val phoneKeys = readDevicePhoneKeys()
            if (phoneKeys.isEmpty()) {
                emptyList()
            } else {
                fetchRegisteredUsers()
                    .asSequence()
                    .filter { it.id.isNotBlank() && it.id != currentUserId && it.id !in localUserIds }
                    .filter { user -> normalizePhoneKey(user.phoneNumber) in phoneKeys }
                    .sortedBy { it.username.lowercase() }
                    .map { user -> user.toForwardTarget(currentUserId) }
                    .toList()
            }
        } else {
            emptyList()
        }

        return listOf(ForwardTarget.myStatus(), ForwardTarget.glyphAi()) + localTargets + contactTargets
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun readDevicePhoneKeys(): Set<String> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
        val keys = LinkedHashSet<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normalizedIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (cursor.moveToNext()) {
                val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                val normalizedNumber = if (normalizedIndex >= 0) cursor.getString(normalizedIndex) else null
                normalizePhoneKey(normalizedNumber ?: rawNumber).takeIf { it.isNotBlank() }?.let(keys::add)
            }
        }
        return keys
    }

    private suspend fun fetchRegisteredUsers(): List<User> = suspendCancellableCoroutine { continuation ->
        firebaseRepository.getAllUsers(
            onSuccess = { users -> if (continuation.isActive) continuation.resume(users) },
            onFailure = { if (continuation.isActive) continuation.resume(emptyList()) }
        )
    }

    private fun forwardSelectedTargets() {
        val selectedTargets = uiState.targets.filter { it.key in uiState.selectedKeys }
        if (selectedTargets.isEmpty() || uiState.isSending) return
        val caption = uiState.caption.trim()
        val sourceChatIdSnapshot = sourceChatId
        val messageIdsSnapshot = messageIds.toList()
        val cachedMessagesSnapshot = cachedMessages.toList()
        Log.d(
            TAG,
            "send_trigger targets=${selectedTargets.map { it.key }} messages=${messageIds.map { it.take(8) }} captionLen=${caption.length}"
        )

        uiState = uiState.copy(isSending = true)

        backgroundForwardScope.launch {
            val orderedMessages = resolveSourceMessages(
                messageIds = messageIdsSnapshot,
                cachedMessages = cachedMessagesSnapshot,
                sourceChatId = sourceChatIdSnapshot
            )
            val result = forwardToTargets(selectedTargets, caption, orderedMessages)
            Log.d(
                TAG,
                "send_complete targets=${selectedTargets.size} success=${result.successCount} unsupportedStatus=${result.unsupportedStatusCount}"
            )
        }

        navigateAfterForwardTap(selectedTargets)
    }

    private fun navigateAfterForwardTap(selectedTargets: List<ForwardTarget>) {
        val singleChatTarget = selectedTargets.singleOrNull()?.takeIf { it.type == ForwardTargetType.CHAT }
        if (singleChatTarget != null) {
            val appContext = applicationContext
            if (isFinishing || isDestroyed) return

            startActivity(
                ChatActivity.newIntent(
                    context = this@ForwardSelectionActivity,
                    chatId = singleChatTarget.chatId,
                    otherUserId = singleChatTarget.userId,
                    otherUsername = singleChatTarget.title,
                    otherUserAvatar = singleChatTarget.avatarUrl,
                    forceScrollToBottom = true
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )

            ChatConnectionPrewarmer.prewarmForChatOpen(
                repository = repository,
                chatId = singleChatTarget.chatId,
                otherUserId = singleChatTarget.userId
            )
            
            // Run primeChatOpen in background so navigation does not wait for prefetch work.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    ChatOpenPrefetcher.primeChatOpen(
                        context = appContext,
                        repository = repository,
                        chatId = singleChatTarget.chatId,
                        source = "forward_selection_tap"
                    )
                }
            }
            finish()
            return
        }
        finish()
    }

    private suspend fun resolveSourceMessages(
        messageIds: List<String> = this.messageIds,
        cachedMessages: List<LocalMessage> = this.cachedMessages,
        sourceChatId: String = this.sourceChatId
    ): List<LocalMessage> {
        val cachedById = cachedMessages.associateBy { it.id }
        val cachedOrdered = messageIds.mapNotNull(cachedById::get)
            .filter { !it.isDeletedForAll }
        if (cachedOrdered.isNotEmpty()) return cachedOrdered

        Log.w(TAG, "payload_cache_miss sourceChatId=$sourceChatId ids=${messageIds.size}; using local fallback")
        val sourceMessages = messageDao.getMessagesByIds(messageIds).associateBy { it.id }
        return messageIds.mapNotNull { sourceMessages[it] }
            .filter { !it.isDeletedForAll }
    }

    private suspend fun forwardToTargets(
        targets: List<ForwardTarget>,
        caption: String,
        orderedMessages: List<LocalMessage>
    ): ForwardResult {
        if (orderedMessages.isEmpty()) return ForwardResult(successCount = 0, unsupportedStatusCount = 0)

        var successCount = 0
        var unsupportedStatusCount = 0

        for (target in targets) {
            when (target.type) {
                ForwardTargetType.CHAT -> {
                    var forwardedForTarget = 0
                    var nextClientTimestamp = System.currentTimeMillis()

                    if (caption.isNotBlank()) {
                        repository.sendMessage(
                            chatId = target.chatId,
                            text = caption,
                            otherUserId = target.userId,
                            otherUsername = target.title,
                            otherUserAvatar = target.avatarUrl,
                            clientTimestamp = nextClientTimestamp++
                        )
                    }

                    for (message in orderedMessages) {
                        val forwarded = repository.sendForwardedMessage(
                            targetChatId = target.chatId,
                            original = message,
                            otherUserId = target.userId,
                            otherUsername = target.title,
                            otherUserAvatar = target.avatarUrl,
                            clientTimestamp = nextClientTimestamp++
                        )
                        if (forwarded) {
                            successCount++
                            forwardedForTarget++
                        } else {
                            Log.w(TAG, "forward_failed target=${target.key} message=${message.id.take(8)} type=${message.type}")
                        }
                    }

                    if (caption.isNotBlank() && forwardedForTarget == 0) {
                        Log.w(TAG, "caption_sent_without_forwarded_media target=${target.key}")
                    }
                }
                ForwardTargetType.STATUS -> {
                    for (message in orderedMessages) {
                        val statusResult = forwardMessageToStatus(message, caption)
                        if (statusResult.forwarded) successCount++
                        if (statusResult.unsupported) unsupportedStatusCount++
                    }
                }
                ForwardTargetType.GLYPH_AI -> Unit
            }
        }
        return ForwardResult(successCount = successCount, unsupportedStatusCount = unsupportedStatusCount)
    }

    private suspend fun forwardMessageToStatus(message: LocalMessage, caption: String): StatusForwardOutcome {
        return when (message.type) {
            MessageType.SYSTEM -> StatusForwardOutcome(unsupported = true)
            MessageType.TEXT,
            MessageType.STATUS_REPLY -> {
                val text = message.text.takeIf { it.isNotBlank() }
                    ?: message.statusText.takeIf { !it.isNullOrBlank() }
                    ?: return StatusForwardOutcome(unsupported = true)
                val result = StatusRepository.publishForwardedStatus(
                    type = StatusType.TEXT,
                    text = text,
                    backgroundColor = message.statusBgColor ?: DEFAULT_TEXT_STATUS_COLOR
                )
                StatusForwardOutcome(forwarded = result.isSuccess)
            }
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> {
                val mediaUrl = message.imageUrl?.takeIf { it.isNotBlank() }
                    ?: message.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: return StatusForwardOutcome(unsupported = true)
                val result = StatusRepository.publishForwardedStatus(
                    type = StatusType.IMAGE,
                    mediaUrl = mediaUrl,
                    thumbnailUrl = message.thumbnailUrl.orEmpty(),
                    caption = caption.ifBlank { message.text }
                )
                StatusForwardOutcome(forwarded = result.isSuccess)
            }
            MessageType.VIDEO -> {
                val mediaUrl = message.videoUrl?.takeIf { it.isNotBlank() }
                    ?: return StatusForwardOutcome(unsupported = true)
                val result = StatusRepository.publishForwardedStatus(
                    type = StatusType.VIDEO,
                    mediaUrl = mediaUrl,
                    thumbnailUrl = message.thumbnailUrl.orEmpty(),
                    caption = caption.ifBlank { message.text },
                    durationMs = message.videoDuration ?: 0L
                )
                StatusForwardOutcome(forwarded = result.isSuccess)
            }
            MessageType.AUDIO -> {
                val mediaUrl = message.audioUrl?.takeIf { it.isNotBlank() }
                    ?: return StatusForwardOutcome(unsupported = true)
                val result = StatusRepository.publishForwardedStatus(
                    type = StatusType.VOICE,
                    mediaUrl = mediaUrl,
                    durationMs = message.audioDuration
                )
                StatusForwardOutcome(forwarded = result.isSuccess)
            }
            MessageType.MEDIA_GROUP -> {
                val items = parseMediaItems(message.mediaItems)
                if (items.isEmpty()) return StatusForwardOutcome(unsupported = true)
                var posted = 0
                var unsupported = 0
                for (item in items) {
                    val mediaUrl = item.url.takeIf { it.isNotBlank() }
                    if (mediaUrl == null) {
                        unsupported++
                        continue
                    }
                    val type = if (item.type == MediaType.VIDEO) StatusType.VIDEO else StatusType.IMAGE
                    val result = StatusRepository.publishForwardedStatus(
                        type = type,
                        mediaUrl = mediaUrl,
                        thumbnailUrl = item.thumbnailUrl.orEmpty(),
                        caption = caption.ifBlank { message.text },
                        durationMs = item.duration
                    )
                    if (result.isSuccess) posted++
                }
                StatusForwardOutcome(forwarded = posted > 0, unsupported = posted == 0 || unsupported > 0)
            }
            MessageType.CONTACT,
            MessageType.DOCUMENT -> StatusForwardOutcome(unsupported = true)
        }
    }

    private fun parseMediaItems(json: String?): List<MediaItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<MediaItem>>(json, mediaItemListType) }
            .getOrDefault(emptyList())
    }

    private fun buildSourcePreview(messages: List<LocalMessage>): ForwardSourcePreview? {
        val previewModel = messages.asSequence()
            .filter { !it.isDeletedForAll }
            .mapNotNull { it.forwardPreviewModel() }
            .firstOrNull()
            ?: return null
        return ForwardSourcePreview(thumbnailModel = previewModel)
    }

    private fun LocalMessage.forwardPreviewModel(): String? {
        val model = when (type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> localUri ?: thumbnailUrl ?: imageUrl
            MessageType.VIDEO -> localUri ?: thumbnailUrl ?: videoUrl
            MessageType.MEDIA_GROUP -> parseMediaItems(mediaItems)
                .firstNotNullOfOrNull { item ->
                    item.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: item.displayUrl.takeIf { it.isNotBlank() }
                }
            MessageType.TEXT,
            MessageType.STATUS_REPLY,
            MessageType.SYSTEM,
            MessageType.AUDIO,
            MessageType.CONTACT,
            MessageType.DOCUMENT -> null
        }
        return model?.takeIf { it.isNotBlank() }
    }

    private fun LocalChat.toForwardTarget(section: ForwardSection): ForwardTarget {
        return ForwardTarget(
            key = "chat:$id",
            type = ForwardTargetType.CHAT,
            section = section,
            chatId = id,
            userId = otherUserId,
            title = ContactDisplayNameResolver.getDisplayName(
                otherUserId = otherUserId,
                remoteProfileName = otherUsername
            ),
            subtitle = lastMessage.ifBlank { "Tap to forward" },
            avatarUrl = otherUserAvatar
        )
    }

    private fun User.toForwardTarget(currentUserId: String): ForwardTarget {
        return ForwardTarget(
            key = "user:$id",
            type = ForwardTargetType.CHAT,
            section = ForwardSection.CONTACTS,
            chatId = buildChatId(currentUserId, id),
            userId = id,
            title = ContactDisplayNameResolver.getDisplayName(
                otherUserId = id,
                remoteProfileName = username,
                remotePhoneNumber = phoneNumber
            ),
            subtitle = phoneNumber.ifBlank { "Available on Glyph" },
            avatarUrl = profileImageUrl.ifBlank { profileImageFullUrl }
        )
    }

    private fun buildChatId(currentUserId: String, otherUserId: String): String {
        if (currentUserId.isBlank()) return otherUserId
        return if (currentUserId < otherUserId) "${currentUserId}_$otherUserId" else "${otherUserId}_$currentUserId"
    }

    companion object {
        private const val TAG = "ForwardSelection"
        const val EXTRA_SOURCE_CHAT_ID = "source_chat_id"
        const val EXTRA_MESSAGE_IDS = "message_ids"
        const val EXTRA_PAYLOAD_TOKEN = "payload_token"
        private const val FREQUENT_LIMIT = 3
        private val DEFAULT_TEXT_STATUS_COLOR = 0xFF1B5E20.toInt()
        private val backgroundForwardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun createIntent(
            context: Context,
            sourceChatId: String,
            messageIds: ArrayList<String>,
            payloadToken: String? = null
        ): Intent {
            return Intent(context, ForwardSelectionActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_CHAT_ID, sourceChatId)
                putStringArrayListExtra(EXTRA_MESSAGE_IDS, messageIds)
                payloadToken?.let { putExtra(EXTRA_PAYLOAD_TOKEN, it) }
            }
        }
    }
}

@Composable
private fun ForwardSelectionScreen(
    state: ForwardUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onCaptionChange: (String) -> Unit,
    onTargetClick: (ForwardTarget) -> Unit,
    onSendClick: () -> Unit,
    onNewChatClick: () -> Unit
) {
    val rows = remember(state.targets, state.query) { buildRows(state.targets, state.query) }
    val selectedCount = state.selectedKeys.size
    val selectedNames = remember(state.targets, state.selectedKeys) {
        state.targets
            .filter { it.key in state.selectedKeys }
            .joinToString(", ") { it.title }
    }
    val listBottomPadding by animateDpAsState(
        targetValue = when {
            state.sourcePreview != null && selectedCount > 0 -> 166.dp
            state.sourcePreview != null -> 112.dp
            selectedCount > 0 -> 144.dp
            else -> 108.dp
        },
        label = "forwardListBottomPadding"
    )
    val showStatusUnsupported = state.unsupportedStatusCount > 0 &&
        state.targets.any { it.key in state.selectedKeys && it.type == ForwardTargetType.STATUS }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ForwardColors.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ForwardTopBar(
                selectedCount = selectedCount,
                onBack = onBack,
                onSearchClick = onSearchClick,
                onNewChatClick = onNewChatClick
            )
            AnimatedVisibility(
                visible = state.searchActive || state.query.isNotBlank(),
                enter = expandVertically(animationSpec = tween(160)) + fadeIn(animationSpec = tween(160)),
                exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(120))
            ) {
                ForwardSearchBar(query = state.query, onQueryChange = onQueryChange)
            }
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ForwardColors.accent)
                }
            } else if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No chats found", color = ForwardColors.secondaryText, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = listBottomPadding)
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ForwardRow.Header -> SectionHeader(row.title)
                            is ForwardRow.Target -> ForwardTargetRow(
                                target = row.target,
                                selected = row.target.key in state.selectedKeys,
                                enabled = !state.isSending,
                                onClick = { onTargetClick(row.target) }
                            )
                        }
                    }
                }
            }
        }

        ForwardBottomPanel(
            selectedCount = selectedCount,
            selectedNames = selectedNames,
            sourcePreview = state.sourcePreview,
            caption = state.caption,
            showUnsupportedHint = showStatusUnsupported,
            isSending = state.isSending,
            onCaptionChange = onCaptionChange,
            onSendClick = onSendClick,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ForwardTopBar(
    selectedCount: Int,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    onNewChatClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForwardColors.topBar)
            .statusBarsPadding()
            .height(68.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Forward to",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$selectedCount selected",
                color = ForwardColors.secondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewChatClick) {
            Icon(
                painter = painterResource(R.drawable.ic_group),
                contentDescription = "New group",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ForwardBottomPanel(
    selectedCount: Int,
    selectedNames: String,
    sourcePreview: ForwardSourcePreview?,
    caption: String,
    showUnsupportedHint: Boolean,
    isSending: Boolean,
    onCaptionChange: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showSendButton = selectedCount > 0 || caption.isNotBlank()
    var inputHeightPx by remember { mutableIntStateOf(0) }
    val thumbnailHeight = with(LocalDensity.current) {
        inputHeightPx.takeIf { it > 0 }?.toDp() ?: ForwardInputMinHeight
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ForwardColors.topBar)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (showUnsupportedHint) {
            Text(
                text = "Can't send this message type",
                color = ForwardColors.warning,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sourcePreview != null) {
                ForwardPreviewThumbnail(
                    model = sourcePreview.thumbnailModel,
                    height = thumbnailHeight
                )
                Spacer(modifier = Modifier.width(ForwardBottomBarGap))
            }
            ForwardCaptionField(
                caption = caption,
                onCaptionChange = onCaptionChange,
                onHeightMeasured = { inputHeightPx = it },
                modifier = Modifier.weight(1f)
            )
            AnimatedVisibility(
                visible = showSendButton,
                enter = expandHorizontally(animationSpec = tween(150), expandFrom = Alignment.Start) + fadeIn(animationSpec = tween(120)),
                exit = shrinkHorizontally(animationSpec = tween(120), shrinkTowards = Alignment.Start) + fadeOut(animationSpec = tween(90))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ForwardSendButton(
                        enabled = !isSending && showSendButton,
                        isSending = isSending,
                        onClick = onSendClick
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = expandVertically(animationSpec = tween(170)) + fadeIn(animationSpec = tween(170)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(110))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedNames,
                    color = ForwardColors.selectedNamesText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ForwardCaptionField(
    caption: String,
    onCaptionChange: (String) -> Unit,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = caption,
        onValueChange = onCaptionChange,
        singleLine = false,
        minLines = 1,
        maxLines = 5,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Normal),
        cursorBrush = SolidColor(ForwardColors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier
            .heightIn(min = ForwardInputMinHeight, max = ForwardInputMaxHeight)
            .onSizeChanged { onHeightMeasured(it.height) }
            .clip(RoundedCornerShape(4.dp))
            .background(ForwardColors.inputBackground),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                ) {
                    if (caption.isEmpty()) {
                        Text(text = "Add a message...", color = ForwardColors.inputHint, fontSize = 17.sp)
                    }
                    innerTextField()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(ForwardColors.accent)
                )
            }
        }
    )
}

@Composable
private fun ForwardPreviewThumbnail(model: String, height: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ColorDrawable(android.graphics.Color.rgb(42, 42, 42))
            }
        },
        update = { imageView ->
            Glide.with(context)
                .load(model)
                .placeholder(ColorDrawable(android.graphics.Color.rgb(42, 42, 42)))
                .error(ColorDrawable(android.graphics.Color.rgb(42, 42, 42)))
                .centerCrop()
                .into(imageView)
        },
        modifier = Modifier
            .width(ForwardPreviewWidth)
            .height(height)
            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 0.dp, bottomStart = 7.dp, bottomEnd = 0.dp))
    )
}

@Composable
private fun ForwardSendButton(
    enabled: Boolean,
    isSending: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.95f else 1f, label = "forwardSendPress")

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(scale)
            .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(ForwardColors.accent)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSending) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ForwardSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForwardColors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ForwardColors.searchBackground)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = ForwardColors.secondaryText, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(ForwardColors.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(text = "Search", color = ForwardColors.secondaryText, fontSize = 16.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = ForwardColors.secondaryText,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun ForwardTargetRow(
    target: ForwardTarget,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(start = 32.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TargetAvatar(target)
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = target.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = target.subtitle,
                color = ForwardColors.secondaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        SelectionCircle(selected = selected)
    }
}

@Composable
private fun TargetAvatar(target: ForwardTarget) {
    if (target.type == ForwardTargetType.STATUS) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ForwardColors.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        return
    }

    if (target.type == ForwardTargetType.GLYPH_AI) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF8A3FFC), Color(0xFFB45CFF), Color(0xFF20D6C7))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "AI", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    if (target.avatarUrl.isNotBlank()) {
        val context = LocalContext.current
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ColorDrawable(android.graphics.Color.rgb(35, 50, 56))
                }
            },
            update = { imageView ->
                Glide.with(context)
                    .load(target.avatarUrl)
                    .placeholder(ColorDrawable(android.graphics.Color.rgb(35, 50, 56)))
                    .error(ColorDrawable(android.graphics.Color.rgb(35, 50, 56)))
                    .circleCrop()
                    .into(imageView)
            },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ForwardColors.avatarBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = target.title.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SelectionCircle(selected: Boolean) {
    val scale by animateFloatAsState(targetValue = if (selected) 1f else 0.9f, label = "forwardSelectionScale")
    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(ForwardColors.accent)
                else Modifier.border(2.dp, ForwardColors.selectorBorder, CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(80))
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }
}

private fun buildRows(targets: List<ForwardTarget>, query: String): List<ForwardRow> {
    val normalizedQuery = query.trim().lowercase()
    val filtered = if (normalizedQuery.isBlank()) {
        targets
    } else {
        targets.filter {
            it.title.lowercase().contains(normalizedQuery) || it.subtitle.lowercase().contains(normalizedQuery)
        }
    }

    if (normalizedQuery.isNotBlank()) {
        return if (filtered.isEmpty()) emptyList() else listOf(ForwardRow.Header("Results")) + filtered.map { ForwardRow.Target(it) }
    }

    val rows = mutableListOf<ForwardRow>()
    val status = filtered.filter { it.section == ForwardSection.STATUS }
    if (status.isNotEmpty()) {
        rows += status.map { ForwardRow.Target(it) }
    }

    val aiTargets = filtered.filter { it.section == ForwardSection.GLYPH_AI }
    rows += aiTargets.map { ForwardRow.Target(it) }

    val frequent = filtered.filter { it.section == ForwardSection.FREQUENT }
    if (frequent.isNotEmpty()) {
        rows += ForwardRow.Header("Frequently contacted")
        rows += frequent.map { ForwardRow.Target(it) }
    }

    val recent = filtered.filter { it.section == ForwardSection.RECENT }
    if (recent.isNotEmpty()) {
        rows += ForwardRow.Header("Recent chats")
        rows += recent.map { ForwardRow.Target(it) }
    }

    val contacts = filtered.filter { it.section == ForwardSection.CONTACTS }
    if (contacts.isNotEmpty()) {
        rows += ForwardRow.Header("All Contacts")
        rows += contacts.map { ForwardRow.Target(it) }
    }
    return rows
}

private fun normalizePhoneKey(value: String?): String {
    val digits = value.orEmpty().filter { it.isDigit() }
    if (digits.length < 7) return ""
    return if (digits.length > 10) digits.takeLast(10) else digits
}

private fun String.withForwardInputCapitalization(): String {
    val firstLetterIndex = indexOfFirst { it.isLetter() }
    if (firstLetterIndex < 0) return this
    val firstLetter = this[firstLetterIndex]
    if (!firstLetter.isLowerCase()) return this
    return replaceRange(firstLetterIndex, firstLetterIndex + 1, firstLetter.uppercaseChar().toString())
}

private fun LocalMessage.canForwardToStatus(): Boolean {
    return when (type) {
        MessageType.TEXT,
        MessageType.STATUS_REPLY -> text.isNotBlank() || !statusText.isNullOrBlank()
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.STICKER,
        MessageType.KLIPY_EMOJI,
        MessageType.MEME -> !imageUrl.isNullOrBlank() || !thumbnailUrl.isNullOrBlank()
        MessageType.VIDEO -> !videoUrl.isNullOrBlank()
        MessageType.AUDIO -> !audioUrl.isNullOrBlank()
        MessageType.MEDIA_GROUP -> !mediaItems.isNullOrBlank()
        MessageType.CONTACT,
        MessageType.DOCUMENT,
        MessageType.SYSTEM -> false
    }
}

private data class ForwardUiState(
    val isLoading: Boolean = true,
    val targets: List<ForwardTarget> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val query: String = "",
    val searchActive: Boolean = false,
    val isSending: Boolean = false,
    val caption: String = "",
    val messageCount: Int = 0,
    val sourcePreview: ForwardSourcePreview? = null,
    val unsupportedStatusCount: Int = 0
)

private data class ForwardSourcePreview(
    val thumbnailModel: String
)

private data class ForwardTarget(
    val key: String,
    val type: ForwardTargetType,
    val section: ForwardSection,
    val chatId: String,
    val userId: String,
    val title: String,
    val subtitle: String,
    val avatarUrl: String
) {
    companion object {
        fun myStatus(): ForwardTarget {
            return ForwardTarget(
                key = "status:my",
                type = ForwardTargetType.STATUS,
                section = ForwardSection.STATUS,
                chatId = "",
                userId = "",
                title = "My status",
                subtitle = "My contacts",
                avatarUrl = ""
            )
        }

        fun glyphAi(): ForwardTarget {
            return ForwardTarget(
                key = "ai:glyph",
                type = ForwardTargetType.GLYPH_AI,
                section = ForwardSection.GLYPH_AI,
                chatId = "",
                userId = "",
                title = "Glyph AI",
                subtitle = "Ask me anything",
                avatarUrl = ""
            )
        }
    }
}

private enum class ForwardTargetType { STATUS, CHAT, GLYPH_AI }

private enum class ForwardSection { STATUS, GLYPH_AI, FREQUENT, RECENT, CONTACTS }

private sealed class ForwardRow(open val key: String) {
    data class Header(val title: String) : ForwardRow("header:$title")
    data class Target(val target: ForwardTarget) : ForwardRow(target.key)
}

private data class StatusForwardOutcome(
    val forwarded: Boolean = false,
    val unsupported: Boolean = false
)

private data class ForwardResult(
    val successCount: Int,
    val unsupportedStatusCount: Int
)

private object ForwardColors {
    val background = Color(0xFF01090C)
    val topBar = Color(0xFF01090C)
    val searchBackground = Color(0xFF111B21)
    val inputBackground = Color(0xFF2A2A2A)
    val inputHint = Color(0xFFC8C8C8)
    val secondaryText = Color(0xFFA0A0A0)
    val selectedNamesText = Color(0xFFCFCFCF)
    val selectorBorder = Color(0xFF7C858B)
    val accent = Color(0xFF25D366)
    val warning = Color(0xFFFFB74D)
    val avatarBackground = Color(0xFF173B60)
}

private val ForwardPreviewWidth = 86.dp
private val ForwardBottomBarGap = 8.dp
private val ForwardInputMinHeight = 64.dp
private val ForwardInputMaxHeight = 160.dp