package com.glyph.glyph_v3.ui.chat

import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.ui.chat.input.ChatInputShell
import com.glyph.glyph_v3.ui.chat.list.OptimizedMessageList
import com.glyph.glyph_v3.ui.chat.overlay.VoiceRecorderOverlay
import com.glyph.glyph_v3.ui.chat.state.*
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.util.AudioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

private const val PERF_TAG = "ChatPerfV2"
private const val PERF_DEBUG = true

private fun logPerf(message: String) {
    if (PERF_DEBUG) {
        Log.d(PERF_TAG, message)
    }
}

/**
 * OptimizedChatScreen - WhatsApp-level smooth chat screen
 * 
 * Architecture Overview:
 * 
 * State Management:
 * - TextInputState: High-frequency (typing, cursor) - isolated in InputSlot
 * - ReplyEditState: Low-frequency (reply/edit previews) - isolated in PreviewSlot  
 * - RecorderState: High-frequency during recording - isolated in VoiceRecorderOverlay
 * - UIVisibilityState: Medium-frequency (menus, overlays)
 * 
 * Composition Hierarchy:
 * ```
 * OptimizedChatScreen (stable scaffold)
 *  ├── ChatTopBar (stable)
 *  ├── OptimizedMessageList (list-level swipe handling)
 *  │    └── PureMessageRow (dumb, no gestures)
 *  ├── ChatInputShell (slot-based)
 *  │    ├── PreviewSlot (low-frequency)
 *  │    ├── InputSlot (high-frequency, isolated)
 *  │    └── ActionSlot (medium-frequency)
 *  └── VoiceRecorderOverlay (floating, high-frequency isolated)
 * ```
 * 
 * Performance Guarantees:
 * 1. Typing NEVER recomposes message list or previews
 * 2. Recording amplitude updates NEVER recompose anything except waveform
 * 3. Message list scrolling NEVER recomposes input area
 * 4. Swipe-to-reply handled at list level, not per-item
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizedChatScreen(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onTyping: (String) -> Unit,
    onBackClick: () -> Unit,
    onSendMedia: (List<android.net.Uri>, CompressionQuality, Map<android.net.Uri, CompressionQuality>) -> Unit,
    onSendVoice: (java.io.File, Long) -> Unit = { _, _ -> },
    onDownloadMedia: (Message) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onDeleteSelectedForAll: () -> Unit = {},
    onCopySelected: () -> Unit = {},
    onPinSelected: () -> Unit = {},
    onViewDetails: () -> Unit = {},
    onReply: (Message) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onEnterEditMode: (Message) -> Unit = {},
    onCancelEditMode: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    
    // === STATE MANAGEMENT ===
    // Each state type is isolated for minimal recomposition scope
    
    val textInputState = remember { TextInputState() }
    val replyEditState = remember { ReplyEditState() }
    val recorderState = remember { RecorderState() }
    val uiVisibilityState = remember { UIVisibilityState() }
    val messageListState = remember { MessageListState() }
    
    // Sync external state changes to our state holders
    LaunchedEffect(uiState.inputText) {
        if (textInputState.text.value != uiState.inputText) {
            textInputState.updateText(uiState.inputText)
        }
    }
    
    LaunchedEffect(uiState.replyToMessage) {
        replyEditState.setReply(uiState.replyToMessage)
    }
    
    LaunchedEffect(uiState.editingMessage) {
        replyEditState.setEditing(uiState.editingMessage)
    }
    
    // === AUDIO PLAYBACK ===
    // Lazy initialization to avoid startup overhead
    val audioPlayerState = remember { mutableStateOf<AudioPlayer?>(null) }
    val isPlayingAudio by (audioPlayerState.value?.isPlaying ?: flowOf(false)).collectAsState(initial = false)
    val audioProgress by (audioPlayerState.value?.progress ?: flowOf(0f)).collectAsState(initial = 0f)
    var playingMessageId by remember { mutableStateOf<String?>(null) }
    
    // Audio playback state for message bubbles
    val currentPlayingMessageId by rememberUpdatedState(playingMessageId)
    val currentIsPlaying by rememberUpdatedState(isPlayingAudio)
    val currentProgress by rememberUpdatedState(audioProgress)
    
    // Get duration of currently playing message
    val currentAudioDuration by remember {
        derivedStateOf {
            uiState.messages.items.filterIsInstance<ChatListItem.MessageItem>()
                .find { it.message.id == currentPlayingMessageId }
                ?.message?.audioDuration
        }
    }
    
    val audioPlaybackState = remember {
        AudioPlaybackState(
            isPlaying = false,
            progress = 0f,
            playingMessageId = null,
            audioDuration = null,
            onPlay = { message ->
                val player = audioPlayerState.value ?: AudioPlayer(context).also { audioPlayerState.value = it }
                if (currentPlayingMessageId == message.id && player.isPlayerInitialized) {
                    player.resume()
                } else {
                    playingMessageId = message.id
                    val playUri = when {
                        !message.localUri.isNullOrEmpty() -> {
                            val file = java.io.File(message.localUri)
                            if (file.exists()) android.net.Uri.fromFile(file) else android.net.Uri.parse(message.localUri)
                        }
                        !message.audioUrl.isNullOrEmpty() -> android.net.Uri.parse(message.audioUrl)
                        else -> null
                    }
                    playUri?.let { player.play(it) }
                }
            },
            onPause = { audioPlayerState.value?.pause() },
            onSeek = { message, progress ->
                if (currentPlayingMessageId == message.id) {
                    audioPlayerState.value?.seekTo((progress * message.audioDuration).toInt())
                }
            }
        )
    }
    
    val audioPlaybackDerivedState = remember(currentIsPlaying, currentProgress, currentPlayingMessageId, currentAudioDuration) {
        AudioPlaybackState(
            isPlaying = currentIsPlaying,
            progress = currentProgress,
            playingMessageId = currentPlayingMessageId,
            audioDuration = currentAudioDuration,
            onPlay = audioPlaybackState.onPlay,
            onPause = audioPlaybackState.onPause,
            onSeek = audioPlaybackState.onSeek
        )
    }
    
    // === PERFORMANCE TRACKING ===
    val screenOpenTime = remember { SystemClock.uptimeMillis() }
    var startupThrottleActive by remember { mutableStateOf(true) }
    var didJumpToBottom by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        logPerf("🚀 OptimizedChatScreen opening...")
        // Wait for 2 frames (~32ms at 60fps) to let layout settle
        repeat(2) { delay(16) }
        startupThrottleActive = false
        logPerf("✅ Startup throttle released after ${SystemClock.uptimeMillis() - screenOpenTime}ms")
    }
    
    LaunchedEffect(uiState.messages.items.firstOrNull()?.id) {
        if (didJumpToBottom || uiState.messages.items.isEmpty()) return@LaunchedEffect
        delay(16) // Wait for frame
        listState.scrollToItem(0)
        didJumpToBottom = true
    }
    
    // === SCROLL STATE ===
    val isFastScrolling by remember {
        derivedStateOf { listState.isScrollInProgress }
    }
    
    // User scroll tracking
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            messageListState.setUserScrolling(true)
        } else {
            delay(150)
            messageListState.setUserScrolling(false)
        }
    }
    
    // === SCROLL TO BOTTOM FAB ===
    val showScrollToBottom by remember {
        derivedStateOf {
            !startupThrottleActive && (
                listState.firstVisibleItemIndex > 3 ||
                (listState.firstVisibleItemIndex > 0 && listState.firstVisibleItemScrollOffset > 100)
            )
        }
    }
    
    val fabScale by animateFloatAsState(
        targetValue = if (showScrollToBottom) 1f else 0f,
        animationSpec = if (showScrollToBottom) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        } else {
            tween(durationMillis = 100)
        },
        label = "FabScale"
    )
    
    // === AUTO-SCROLL ===
    LaunchedEffect(uiState.messages.items.size) {
        if (startupThrottleActive) return@LaunchedEffect
        val isNearBottom = listState.firstVisibleItemIndex <= 3
        if (isNearBottom && !messageListState.isUserScrolling.value) {
            listState.animateScrollToItem(0)
        }
    }
    
    // === MEDIA HANDLING ===
    var selectedMediaUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    
    val pickMultipleMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMediaUris = uris
            uiVisibilityState.showCompressionDialog()
        }
    }
    
    // === DELETE DIALOG ===
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    
    // === BACK HANDLER ===
    val hasActiveOverlay by uiVisibilityState.hasActiveOverlay
    val isRecording by recorderState.isRecording
    
    BackHandler(
        enabled = hasActiveOverlay || isRecording || uiState.selectionMode || uiState.isEditMode
    ) {
        when {
            isRecording -> recorderState.cancelRecording()
            hasActiveOverlay -> uiVisibilityState.hideAll()
            uiState.isEditMode -> onCancelEditMode()
            uiState.selectionMode -> onClearSelection()
        }
    }
    
    // === STABLE CALLBACKS ===
    val stableBackClick = remember { { onBackClick() } }
    val stableGalleryClick = remember { { pickMultipleMediaLauncher.launch("*/*") } }
    val stableCameraClick = remember { { uiVisibilityState.hideAttachmentMenu() } }
    
    val stableMediaClick = remember(context) {
        { msg: Message, index: Int ->
            val mediaItems = msg.getMediaItemsList()
            if (mediaItems.isNotEmpty()) {
                context.startActivity(
                    com.glyph.glyph_v3.ui.media.MediaViewerActivity.newIntentWithMultipleMedia(
                        context, mediaItems, index, msg.timestamp
                    )
                )
            } else {
                val viewerUrl = msg.localUri?.takeIf { it.isNotEmpty() } ?: msg.imageUrl ?: ""
                if (viewerUrl.isNotEmpty()) {
                    context.startActivity(
                        com.glyph.glyph_v3.ui.media.MediaViewerActivity.newIntent(context, viewerUrl, msg.timestamp)
                    )
                }
            }
        }
    }
    
    // === UI STRUCTURE ===
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = uiState.selectionMode,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                },
                label = "TopBarTransition"
            ) { selectionMode ->
                if (selectionMode) {
                    val selectedMsg = if (uiState.selectedMessageIds.ids.size == 1) {
                        uiState.messages.items
                            .filterIsInstance<ChatListItem.MessageItem>()
                            .find { it.message.id == uiState.selectedMessageIds.ids.first() }
                            ?.message
                    } else null
                    
                    val selectedMessages = uiState.messages.items
                        .filterIsInstance<ChatListItem.MessageItem>()
                        .filter { it.message.id in uiState.selectedMessageIds.ids }
                        .map { it.message }
                    
                    val anyDeleted = selectedMessages.any { it.isDeletedForAll }
                    val showEdit = selectedMsg?.let { msg ->
                        msg.type == com.glyph.glyph_v3.data.models.MessageType.TEXT &&
                        !msg.isIncoming && !msg.isDeletedForAll &&
                        (System.currentTimeMillis() - msg.timestamp) <= (15 * 60 * 1000)
                    } ?: false
                    
                    SelectionTopBar(
                        selectedCount = uiState.selectedMessageIds.ids.size,
                        onClearSelection = onClearSelection,
                        onDeleteSelected = { showDeleteDialog = true },
                        onReply = { selectedMsg?.let(onReply) },
                        onEdit = {
                            selectedMsg?.let { msg ->
                                onClearSelection()
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                onEnterEditMode(msg)
                            }
                        },
                        onCopy = onCopySelected,
                        onPin = onPinSelected,
                        onViewDetails = onViewDetails,
                        showEditAction = showEdit,
                        showReplyAction = uiState.selectedMessageIds.ids.size == 1 && !anyDeleted,
                        showForwardAction = !anyDeleted,
                        showStarAction = !anyDeleted,
                        showMenuActions = !anyDeleted
                    )
                } else {
                    ChatTopBar(
                        username = uiState.otherUserUsername,
                        avatar = uiState.otherUserAvatar,
                        presence = uiState.otherUserPresence,
                        onBackClick = stableBackClick
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .then(
                    if (uiState.wallpaperPath == null) {
                        glyphTheme.backgroundGradient?.let { Modifier.background(it) }
                            ?: Modifier.background(glyphTheme.backgroundPrimary)
                    } else Modifier
                )
        ) {
            // Wallpaper
            if (uiState.wallpaperPath != null) {
                AsyncImage(
                    model = uiState.wallpaperPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Dimming overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = uiState.wallpaperDimming))
                )
            }
            
            Column(modifier = Modifier.fillMaxSize()) {
                // Message List with audio playback context
                CompositionLocalProvider(LocalAudioPlaybackState provides audioPlaybackDerivedState) {
                    OptimizedMessageList(
                        messages = uiState.messages,
                        listState = listState,
                        isTyping = uiState.isTyping,
                        mediaProgress = uiState.mediaProgress,
                        selectedIds = uiState.selectedMessageIds,
                        onMediaClick = stableMediaClick,
                        onDownloadMedia = onDownloadMedia,
                        onToggleSelection = onToggleSelection,
                        onReply = onReply,
                        currentUserPhone = uiState.currentUserPhone,
                        otherUsername = uiState.otherUserUsername,
                        isFastScrolling = isFastScrolling,
                        isWarmup = startupThrottleActive,
                        textMeasurer = textMeasurer,
                        density = density,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
                
                // Chat Input Shell (slot-based)
                ChatInputShell(
                    textInputState = textInputState,
                    replyEditState = replyEditState,
                    recorderState = recorderState,
                    uiVisibilityState = uiVisibilityState,
                    otherUsername = uiState.otherUserUsername,
                    onSendMessage = { text ->
                        onSendMessage(text)
                        textInputState.clear()
                        replyEditState.clearAll()
                    },
                    onSendVoice = onSendVoice,
                    onTyping = onTyping,
                    onAttachClick = { uiVisibilityState.toggleAttachmentMenu() },
                    onCameraClick = stableCameraClick,
                    onCancelReply = {
                        replyEditState.clearReply()
                        onCancelReply()
                    },
                    onCancelEdit = {
                        replyEditState.clearEdit()
                        onCancelEditMode()
                    }
                )
            }
            
            // === OVERLAYS (Rendered above content) ===
            
            // Attachment Menu
            val showAttachmentMenu by uiVisibilityState.showAttachmentMenu
            AttachmentMenu(
                visible = showAttachmentMenu,
                onDismiss = { uiVisibilityState.hideAttachmentMenu() },
                onGalleryClick = stableGalleryClick,
                onCameraClick = stableCameraClick
            )
            
            // Compression Dialog
            val showCompressionDialog by uiVisibilityState.showCompressionDialog
            if (showCompressionDialog) {
                MediaCompressionDialog(
                    uris = selectedMediaUris,
                    onDismiss = { uiVisibilityState.hideCompressionDialog() },
                    onConfirm = { quality, overrides ->
                        uiVisibilityState.hideCompressionDialog()
                        onSendMedia(selectedMediaUris, quality, overrides)
                    }
                )
            }
            
            // Voice Recorder Overlay (floating, high-frequency isolated)
            VoiceRecorderOverlay(
                recorderState = recorderState,
                onSend = {
                    recorderState.stopRecording()?.let { file ->
                        // Duration would come from AudioRecorder
                        onSendVoice(file, 0L)
                    }
                },
                onCancel = { recorderState.cancelRecording() }
            )
            
            // Delete Confirmation Dialog
            if (showDeleteDialog) {
                val selectedMessages = uiState.messages.items
                    .filterIsInstance<ChatListItem.MessageItem>()
                    .filter { it.message.id in uiState.selectedMessageIds.ids }
                    .map { it.message }
                
                val canDeleteForAll = selectedMessages.isNotEmpty() &&
                    selectedMessages.none { it.isDeletedForAll } &&
                    selectedMessages.all { 
                        !it.isIncoming && (System.currentTimeMillis() - it.timestamp) <= 60 * 60 * 1000 
                    }
                
                DeleteConfirmationDialog(
                    canDeleteForAll = canDeleteForAll,
                    onDismiss = { showDeleteDialog = false },
                    onDeleteForEveryone = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        showDeleteDialog = false
                        onDeleteSelectedForAll()
                    },
                    onDeleteForMe = {
                        showDeleteDialog = false
                        onDeleteSelected()
                    }
                )
            }
            
            // Scroll to Bottom FAB
            if (showScrollToBottom || fabScale > 0.01f) {
                SmallFloatingActionButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        scope.launch {
                            if (listState.firstVisibleItemIndex > 10) {
                                listState.scrollToItem(1)
                            }
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = glyphTheme.backgroundElevated,
                    contentColor = glyphTheme.headerIcon,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 80.dp, end = 16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_double_arrow_down),
                        contentDescription = "Scroll to bottom",
                        tint = glyphTheme.headerIcon
                    )
                }
            }
        }
    }
}
