package com.glyph.glyph_v3.ui.chatlist

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.data.service.DraftMessageStore
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.profile.ProfilePreviewDialog
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Rect as AndroidRect

class LockedChatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = applicationContext as GlyphApplication
        val repository = app.repository ?: run {
            val db = AppDatabase.getDatabase(this)
            RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), this)
        }

        DraftMessageStore.init(applicationContext)

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                LockedChatsScreen(
                    repository = repository,
                    onBackClick = { finish() },
                    onChatClick = { chat ->
                        val otherUserId = if (chat.isGroup) "" else (chat.participants.firstOrNull { it != repository.currentUserId } ?: "")
                        val displayName = if (chat.isGroup) {
                            chat.groupName.ifBlank { "Group" }
                        } else {
                            ContactDisplayNameResolver.getDisplayName(
                                otherUserId = otherUserId,
                                remoteProfileName = chat.otherUsername
                            )
                        }
                        val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
                        startActivity(
                            ChatActivity.newIntent(this, chat.id, otherUserId, displayName, displayAvatar)
                        )
                    },
                    onAvatarClick = { chat, rect ->
                        val otherUserId = if (chat.isGroup) "" else (chat.participants.firstOrNull { it != repository.currentUserId } ?: "")
                        try {
                            ProfilePreviewDialog.newInstance(
                                userId = otherUserId,
                                userName = if (chat.isGroup) {
                                    chat.groupName.ifBlank { "Group" }
                                } else {
                                    ContactDisplayNameResolver.getDisplayName(
                                        otherUserId = otherUserId,
                                        remoteProfileName = chat.otherUsername
                                    )
                                },
                                userAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar,
                                chatId = chat.id,
                                startX = rect.left,
                                startY = rect.top,
                                startWidth = rect.width(),
                                startHeight = rect.height()
                            ).show(supportFragmentManager, "profile_preview")
                        } catch (_: Exception) {}
                    },
                    onSettingsClick = {
                        startActivity(Intent(this, ChatLockSettingsActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockedChatsScreen(
    repository: RealtimeMessageRepository,
    onBackClick: () -> Unit,
    onChatClick: (Chat) -> Unit,
    onAvatarClick: (Chat, AndroidRect) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firebaseRepository = remember { FirebaseRepository() }
    val surfaceBg = glyphTheme.backgroundPrimary
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val accentGreen = Color(0xFF00A884)
    val dividerColor = glyphTheme.bubbleBorder
    var groupSenderNamesByUserId by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val pendingGroupSenderIds = remember { mutableSetOf<String>() }

    // Load locked chats from Room
    val lockedChats = produceState<List<Chat>>(initialValue = emptyList()) {
        repository.getLocalChats()
            .combine(ChatSettingsDataStore.observeLockedChatVersion()) { localChats, _ -> localChats }
            .map { localChats ->
                localChats.filter { ChatSettingsDataStore.isChatLocked(context, it.id) }
                    .map { local ->
                        Chat(
                            id = local.id,
                            participants = if (local.isGroup) {
                                com.glyph.glyph_v3.data.repo.GroupChatRepository.decodeStringList(local.participantsJson)
                            } else {
                                listOf(repository.currentUserId ?: "", local.otherUserId)
                            },
                            lastMessage = local.lastMessage,
                            lastMessageTimestamp = if (local.lastMessageTimestamp > 0) Date(local.lastMessageTimestamp) else null,
                            lastMessageSenderId = local.lastMessageSenderId,
                            lastMessageStatus = local.lastMessageStatus,
                            unreadCount = local.unreadCount,
                            otherUsername = local.otherUsername,
                            otherUserAvatar = local.otherUserAvatar,
                            isGroup = local.isGroup,
                            groupName = local.groupName,
                            groupIconUrl = local.groupIconUrl,
                            groupDescription = local.groupDescription,
                            createdBy = local.createdBy,
                            createdAt = local.createdAt,
                            isLocked = true,
                            draft = DraftMessageStore.getDraft(local.id)
                        )
                    }
            }
            .collect { chats ->
                val avatarsToWarm = chats.mapNotNull { chat ->
                    if (chat.isGroup) return@mapNotNull null
                    val otherUserId = chat.participants.firstOrNull { it != repository.currentUserId && it.isNotBlank() }
                        ?: return@mapNotNull null
                    val avatarUrl = chat.otherUserAvatar.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    otherUserId to avatarUrl
                }.distinctBy { it.first to it.second }
                val groupAvatarsToWarm = chats.mapNotNull { chat ->
                    if (!chat.isGroup) return@mapNotNull null
                    val avatarUrl = chat.groupIconUrl.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    chat.id to avatarUrl
                }.distinctBy { it.first to it.second }

                if (avatarsToWarm.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        AvatarCacheManager.preloadAvatars(avatarsToWarm, context.applicationContext)
                    }
                }
                if (groupAvatarsToWarm.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        AvatarCacheManager.preloadGroupAvatars(groupAvatarsToWarm, context.applicationContext)
                    }
                }

                value = chats
            }
    }

    LaunchedEffect(lockedChats.value) {
        val missingSenderIds = lockedChats.value.asSequence()
            .filter { it.isGroup }
            .map { it.lastMessageSenderId }
            .filter { it.isNotBlank() && it != repository.currentUserId }
            .distinct()
            .filter { it !in groupSenderNamesByUserId && pendingGroupSenderIds.add(it) }
            .toList()

        if (missingSenderIds.isEmpty()) return@LaunchedEffect

        firebaseRepository.getAllUsers(
            forceRefresh = false,
            onSuccess = { users ->
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
                    groupSenderNamesByUserId = groupSenderNamesByUserId + resolved
                }
                missingSenderIds.forEach { pendingGroupSenderIds.remove(it) }
            },
            onFailure = {
                missingSenderIds.forEach { pendingGroupSenderIds.remove(it) }
            }
        )
    }

    // Banner dismissal
    var showBanner by remember {
        mutableStateOf(!ChatSettingsDataStore.isLockedBannerDismissed(context))
    }

    // 3-dot menu state
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Locked chats",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Back",
                            tint = glyphTheme.iconPrimary
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_vert),
                                contentDescription = "More",
                                tint = glyphTheme.iconPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color(0xFF233138)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("Chat lock settings", color = textPrimary)
                                },
                                onClick = {
                                    showMenu = false
                                    onSettingsClick()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg,
                    titleContentColor = textPrimary
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceBg)
                    .navigationBarsPadding()
            ) {
                // Encryption footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lock),
                        contentDescription = "Encrypted",
                        tint = textSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Your personal messages are ",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "end-to-end encrypted",
                        color = accentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Info banner
            item(key = "banner") {
                AnimatedVisibility(
                    visible = showBanner,
                    exit = fadeOut() + shrinkVertically()
                ) {
                    LockedDevicesBanner(
                        onDismiss = {
                            showBanner = false
                            scope.launch {
                                ChatSettingsDataStore.setLockedBannerDismissed(context, true)
                            }
                        },
                        onCreateSecretCode = {
                            showBanner = false
                            scope.launch {
                                ChatSettingsDataStore.setLockedBannerDismissed(context, true)
                            }
                            onSettingsClick()
                        }
                    )
                }
            }

            // Locked chats
            items(lockedChats.value, key = { it.id }) { chat ->
                LockedChatRow(
                    chat = chat,
                    currentUserId = repository.currentUserId,
                    groupSenderNamesByUserId = groupSenderNamesByUserId,
                    onClick = { onChatClick(chat) },
                    onAvatarClick = { rect -> onAvatarClick(chat, rect) }
                )
            }

            // Empty state
            if (lockedChats.value.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chat_lock),
                                contentDescription = null,
                                tint = textSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No locked chats",
                                color = textSecondary,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedDevicesBanner(
    onDismiss: () -> Unit,
    onCreateSecretCode: () -> Unit
) {
    val accentGreen = Color(0xFF00A884)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A2A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCreateSecretCode() }
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chat_lock),
                contentDescription = null,
                tint = accentGreen,
                modifier = Modifier
                    .size(28.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Locked on linked devices",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("To open chats on supported linked devices, ")
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "create a secret code",
                    color = accentGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Dismiss",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LockedChatRow(
    chat: Chat,
    currentUserId: String?,
    groupSenderNamesByUserId: Map<String, String>,
    onClick: () -> Unit,
    onAvatarClick: (AndroidRect) -> Unit
) {
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val context = LocalContext.current
    val otherUserId = remember(chat.participants, currentUserId) {
        chat.participants.firstOrNull { it != currentUserId && it.isNotEmpty() } ?: ""
    }
    val displayName = if (chat.isGroup) {
        chat.groupName.ifBlank { "Group" }
    } else {
        ContactDisplayNameResolver.getDisplayName(
            otherUserId = otherUserId,
            remoteProfileName = chat.otherUsername
        )
    }
    val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
    val avatarVisibilityState by remember(otherUserId) {
        AvatarVisibilityRepository.observeProfilePhotoVisibility(otherUserId)
    }.collectAsState()
    val canShowAvatar = if (chat.isGroup) true else avatarVisibilityState.isVisible
    val visibleAvatarUrl = remember(canShowAvatar, displayAvatar) {
        displayAvatar.takeIf { canShowAvatar && it.isNotBlank() }.orEmpty()
    }
    val localAvatarPath = when {
        !canShowAvatar -> null
        chat.isGroup -> AvatarCacheManager.getLocalGroupAvatarPath(chat.id)
        otherUserId.isNotEmpty() -> AvatarCacheManager.getLocalAvatarPath(otherUserId)
        else -> null
    }
    val avatarCacheKey = remember(chat.id, otherUserId, localAvatarPath, visibleAvatarUrl, chat.isGroup) {
        if (chat.isGroup) {
            AvatarCacheManager.buildGroupAvatarCacheKey(
                chatId = chat.id,
                localAvatarPath = localAvatarPath,
                avatarUrl = visibleAvatarUrl
            )
        } else {
            AvatarCacheManager.buildAvatarCacheKey(
                userId = otherUserId,
                localAvatarPath = localAvatarPath,
                avatarUrl = visibleAvatarUrl
            )
        }
    }
    val imageFile = remember(localAvatarPath) { localAvatarPath?.let(::File) }
    val avatarRequest = remember(imageFile, visibleAvatarUrl, avatarCacheKey, context) {
        val src = imageFile ?: visibleAvatarUrl.takeIf { it.isNotBlank() }
        src?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(avatarCacheKey)
                .diskCacheKey(avatarCacheKey)
                .crossfade(false)
                .build()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with position tracking for profile preview
        var avatarTopLeft by remember { mutableStateOf(Offset.Zero) }
        var avatarSize by remember { mutableStateOf(IntSize.Zero) }
        var isPositioned by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A3942))
                .onGloballyPositioned { coordinates ->
                    avatarTopLeft = coordinates.positionInWindow()
                    avatarSize = coordinates.size
                    isPositioned = true
                }
                .clickable(enabled = isPositioned) {
                    if (isPositioned && avatarSize.width > 0 && avatarSize.height > 0) {
                        onAvatarClick(
                            AndroidRect(
                                avatarTopLeft.x.toInt(),
                                avatarTopLeft.y.toInt(),
                                (avatarTopLeft.x + avatarSize.width).toInt(),
                                (avatarTopLeft.y + avatarSize.height).toInt()
                            )
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (avatarRequest != null) {
                AsyncImage(
                    model = avatarRequest,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                if (chat.isGroup) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_group),
                        contentDescription = null,
                        tint = Color(0xFFFFD166),
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_default_avatar),
                        contentDescription = null,
                        tint = Color(0xFF8D9598),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Name + last message
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status indicator for sent messages
                if (chat.lastMessageSenderId == currentUserId && chat.lastMessage.isNotEmpty()) {
                    Icon(
                        painter = painterResource(
                            id = when (chat.lastMessageStatus) {
                                "READ" -> R.drawable.ic_double_check_blue
                                "DELIVERED" -> R.drawable.ic_double_check
                                else -> R.drawable.ic_check
                            }
                        ),
                        contentDescription = null,
                        tint = if (chat.lastMessageStatus == "READ") Color(0xFF53BDEB) else textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = buildChatListSubtitle(chat, currentUserId, groupSenderNamesByUserId)
                        .ifEmpty { "No messages yet" },
                    color = textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Timestamp
        chat.lastMessageTimestamp?.let { ts ->
            Text(
                text = formatTimestamp(ts),
                color = textSecondary,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatTimestamp(date: Date): String {
    val now = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { time = date }
    return when {
        now.get(Calendar.DATE) == msgCal.get(Calendar.DATE) &&
                now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        now.get(Calendar.DATE) - msgCal.get(Calendar.DATE) == 1 &&
                now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) -> "Yesterday"
        else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(date)
    }
}
