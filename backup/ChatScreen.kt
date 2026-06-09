package com.glyph.glyph_v3.ui.chat

/**
 * ChatScreen.kt - PERFORMANCE OPTIMIZED for 60fps+ scrolling
 * 
 * Key optimizations implemented:
 * 1. @Immutable/@Stable annotations on all data classes
 * 2. Stabilized callbacks with remember to prevent recomposition
 * 3. Smart scroll-to-bottom: jump near bottom then smooth decelerate
 * 4. Image loading with thumbnails, size constraints, and memory cache keys
 * 5. Cached computed values (colors, shapes, formatters) with remember
 * 6. Graphics layer for GPU-accelerated animations
 * 7. Optimized LazyColumn with proper keys and contentType
 * 8. Reduced animation complexity in ChatInput
 * 9. User scroll conflict prevention
 * 10. Minimal state reads and lambda allocations
 * 
 * Performance validation:
 * - Use "adb shell dumpsys gfxinfo <package> framestats" to measure frame times
 * - Enable "Profile GPU Rendering" in Developer Options (bars should stay under green line)
 * - Use Layout Inspector to verify no unnecessary recompositions
 * - Target: 16ms per frame (60fps) or 8ms (120fps) with zero jank
 */

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.gestures.detectTapGestures
import com.glyph.glyph_v3.ui.chat.LocalAudioPlaybackState
import com.glyph.glyph_v3.ui.chat.AudioPlaybackState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import com.glyph.glyph_v3.util.FormatUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import android.content.Context
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.font.FontStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.activity.compose.BackHandler
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.MediaItem
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.util.Log

// MessageStatusRead removed - now using glyphTheme.indicatorMessageStatus

// PERFORMANCE DEBUG LOGGING - Set to true to measure scroll performance
private const val PERF_DEBUG_ENABLED = true
private const val PERF_TAG = "ChatPerf"

// Performance metrics tracking
private object PerfMetrics {
    var totalBubbleCompositions = 0
    var slowBubbleCompositions = 0
    var cacheSizeChecks = 0
    
    fun reset() {
        totalBubbleCompositions = 0
        slowBubbleCompositions = 0
        cacheSizeChecks = 0
    }
    
    fun logSummary() {
        if (PERF_DEBUG_ENABLED && totalBubbleCompositions > 0) {
            val slowPercentage = (slowBubbleCompositions * 100.0 / totalBubbleCompositions)
            Log.i(PERF_TAG, "📊 PERFORMANCE SUMMARY:")
            Log.i(PERF_TAG, "   Total bubbles composed: $totalBubbleCompositions")
            Log.i(PERF_TAG, "   Slow compositions (>5ms): $slowBubbleCompositions (${String.format("%.1f", slowPercentage)}%)")
            Log.i(PERF_TAG, "   Cache checks: $cacheSizeChecks")
        }
    }
}

private fun logPerf(message: String) {
    if (PERF_DEBUG_ENABLED) {
        Log.d(PERF_TAG, "[${Thread.currentThread().name}] $message")
    }
}

private inline fun <T> measureTime(label: String, slowThresholdMs: Double = 5.0, block: () -> T): T {
    if (!PERF_DEBUG_ENABLED) return block()
    
    val start = System.nanoTime()
    return block().also {
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        if (elapsed > slowThresholdMs) {
            Log.w(PERF_TAG, "⏱️ SLOW: $label took ${String.format("%.2f", elapsed)}ms")
            if (label.startsWith("MessageBubble")) {
                PerfMetrics.slowBubbleCompositions++
            }
        }
    }
}

// BubbleGroupPosition moved to ChatListItem.kt

// ChatScreenUiItem and computeBubbleGrouping removed as logic moved to ViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
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
    val deleteForAllWindowMs = 60 * 60 * 1000L
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // KEYBOARD HANDLING: Track IME insets for instant transitions
    // No animation - instant shift when keyboard appears/disappears
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle logic if needed, or let user retry
    }

    // Voice Recording State
    var isRecording by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf("0:00") }
    var recordingAmplitude by remember { mutableIntStateOf(0) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val audioRecorder = remember { com.glyph.glyph_v3.util.AudioRecorder(context) }
    
    // Audio Playback State
    // Lazily create the audio player only when first needed to avoid work during initial load
    val audioPlayerState = remember { mutableStateOf<com.glyph.glyph_v3.util.AudioPlayer?>(null) }
    val audioPlayer = audioPlayerState.value
    val isPlayingAudio by (audioPlayer?.isPlaying ?: snapshotFlow { false }).collectAsState(initial = false)
    val audioProgress by (audioPlayer?.progress ?: snapshotFlow { 0f }).collectAsState(initial = 0f)
    var playingMessageId by remember { mutableStateOf<String?>(null) }

    // PERFORMANCE: Use rememberUpdatedState to capture latest values without causing AudioPlaybackState recreation
    // This prevents message row recomposition when audio state changes
    val currentPlayingMessageId by rememberUpdatedState(playingMessageId)
    val currentIsPlaying by rememberUpdatedState(isPlayingAudio)
    val currentProgress by rememberUpdatedState(audioProgress)
    
    // PERFORMANCE: AudioPlaybackState is @Stable, callbacks capture stable references
    // The state values are read via rememberUpdatedState to avoid recreation
    val audioPlaybackState = remember {
        AudioPlaybackState(
            isPlaying = false, // Initial placeholder - consumers should derive actual state
            progress = 0f,
            playingMessageId = null,
            onPlay = { message ->
                val player = audioPlayerState.value ?: run {
                    val created = com.glyph.glyph_v3.util.AudioPlayer(context)
                    audioPlayerState.value = created
                    created
                }

                if (currentPlayingMessageId == message.id && player.isPlayerInitialized) {
                    player.resume()
                } else {
                    playingMessageId = message.id
                    
                    // Prefer local file if available to support "relay" architecture
                    val playUri = when {
                        !message.localUri.isNullOrEmpty() -> {
                            val file = java.io.File(message.localUri)
                            if (file.exists()) {
                                android.net.Uri.fromFile(file)
                            } else {
                                android.net.Uri.parse(message.localUri)
                            }
                        }
                        !message.audioUrl.isNullOrEmpty() -> {
                            android.net.Uri.parse(message.audioUrl)
                        }
                        else -> null
                    }

                    playUri?.let { uri ->
                        player.play(uri)
                    }
                }
            },
            onPause = {
                audioPlayerState.value?.pause()
            },
            onSeek = { message, progress ->
                val player = audioPlayerState.value ?: return@AudioPlaybackState
                if (currentPlayingMessageId == message.id) {
                    val duration = message.audioDuration
                    val seekMs = (progress * duration).toInt()
                    player.seekTo(seekMs)
                }
            },
            audioDuration = null
        )
    }
    
    // PERFORMANCE: Provide derived audio state to consumers without recomposing entire tree
    // VoiceMessageBubble will read these via derivedStateOf pattern
    val audioPlaybackDerivedState = remember(currentIsPlaying, currentProgress, currentPlayingMessageId) {
        AudioPlaybackState(
            isPlaying = currentIsPlaying,
            progress = currentProgress,
            playingMessageId = currentPlayingMessageId,
            audioDuration = null,
            onPlay = audioPlaybackState.onPlay,
            onPause = audioPlaybackState.onPause,
            onSeek = audioPlaybackState.onSeek
        )
    }
    
    // Timer logic
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                recordingDuration = String.format("%d:%02d", minutes, seconds)
                recordingAmplitude = audioRecorder.getAmplitude()
                delay(100)
            }
        } else {
            recordingDuration = "0:00"
            recordingAmplitude = 0
        }
    }

    // Warm-up window: avoid optional background work (prefetch, gestures) that can
    // steal CPU during the critical first interactions right after opening.
    // PERFORMANCE: No artificial warmup delay - user can interact immediately
    val screenOpenUptimeMs = remember { SystemClock.uptimeMillis() }
    val isWarmup = false // Always false - no warmup delay needed
    
    // PERFORMANCE TRACKING: Log chat opening performance
    LaunchedEffect(Unit) {
        val startTime = System.nanoTime()
        logPerf("🚀 ChatScreen opening...")
        PerfMetrics.reset()
        TextLayoutCache.resetStats()
        
        // Wait for first composition
        withFrameNanos { }
        val firstFrameMs = (System.nanoTime() - startTime) / 1_000_000.0
        logPerf("🎨 First frame rendered in ${String.format("%.2f", firstFrameMs)}ms")
        
        // Log performance summary after first frame
        PerfMetrics.logSummary()
    }

    // OPTIMIZATION: Removed artificial startup throttle and delayed scroll
    // This ensures instant interactivity and eliminates visual jumps
    
    val isFirstComposition = remember { mutableStateOf(true) }
    var didJumpToBottom by remember { mutableStateOf(false) }
    
    // Immediate scroll to bottom handling
    LaunchedEffect(uiState.messages.items.firstOrNull()?.id) {
        if (didJumpToBottom || uiState.messages.items.isEmpty()) return@LaunchedEffect
        
        // Immediately mark as handled - reverseLayout handles the positioning naturally
        // No forced scrollToItem(0) needed as it causes a visual jump
        didJumpToBottom = true
        isFirstComposition.value = false
    }
    
    // Track user's scroll interaction to prevent auto-scroll conflicts
    val isUserScrolling = remember { mutableStateOf(false) }
    
    // PERFORMANCE: Disable expensive features during scroll AND warmup.
    // During warmup, we want to disable swipe gestures and other expensive features
    // to allow the initial composition to settle without additional overhead.
    val isFastScrolling by remember {
        derivedStateOf { listState.isScrollInProgress || isWarmup }
    }
    
    // WhatsApp-like centered delete confirmation dialog state
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    if (showDeleteDialog) {
        val selectedIds = uiState.selectedMessageIds.ids
        val selectedMessages = uiState.messages.items
            .filterIsInstance<ChatListItem.MessageItem>()
            .filter { it.message.id in selectedIds }
            .map { it.message }

        val anySelectedDeletedForAll = selectedMessages.any { it.isDeletedForAll }
        val now = System.currentTimeMillis()
        val canDeleteForAll = selectedMessages.isNotEmpty() &&
            !anySelectedDeletedForAll &&
            selectedMessages.all { !it.isIncoming && (now - it.timestamp) <= deleteForAllWindowMs }

        DeleteConfirmationDialog(
            canDeleteForAll = canDeleteForAll,
            onDismiss = { showDeleteDialog = false },
            onDeleteForEveryone = {
                // One clean confirmation haptic on sender action.
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
    
    LaunchedEffect(isFirstComposition.value, listState.isScrollInProgress) {
        if (isFirstComposition.value) return@LaunchedEffect // Skip during initial render
        if (listState.isScrollInProgress) {
            isUserScrolling.value = true
        } else {
            // Reset after scroll settles - reduced delay for better responsiveness
            delay(150)
            isUserScrolling.value = false
        }
    }

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    
    // Media Selection State
    var showCompressionDialog by remember { mutableStateOf(false) }
    var selectedMediaUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    // Handle back press for overlays and selection
    BackHandler(enabled = showAttachmentMenu || showCompressionDialog || uiState.selectionMode || uiState.isEditMode) {
        if (showAttachmentMenu) {
            showAttachmentMenu = false
        } else if (showCompressionDialog) {
            showCompressionDialog = false
        } else if (uiState.isEditMode) {
            onCancelEditMode()
        } else if (uiState.selectionMode) {
            onClearSelection()
        }
    }

    // Media Pickers
    val pickMultipleMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMediaUris = uris
            showCompressionDialog = true
            showAttachmentMenu = false
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        // Handle bitmap or use TakePicture with Uri
        // For simplicity, let's stick to gallery for now or implement Uri creation
    }
    
    // Stabilize callbacks to prevent recomposition - use rememberUpdatedState for proper updates
    val currentOnSendMessage by rememberUpdatedState(onSendMessage)
    val currentOnTyping by rememberUpdatedState(onTyping)
    val currentOnBackClick by rememberUpdatedState(onBackClick)
    val currentOnDownloadMedia by rememberUpdatedState(onDownloadMedia)
    
    val stableSendMessage = remember { { text: String -> currentOnSendMessage(text) } }
    val stableTyping = remember { { text: String -> currentOnTyping(text) } }
    val stableBackClick = remember { { currentOnBackClick() } }
    val stableGalleryClick = remember { { 
        pickMultipleMediaLauncher.launch("*/*")
    } }
    val stableCameraClick = remember { { 
        // Implement camera logic
        showAttachmentMenu = false
    } }
    val stableMediaClick = remember { { msg: Message, index: Int ->
        val mediaItems = msg.getMediaItemsList()
        if (mediaItems.isNotEmpty()) {
            context.startActivity(
                com.glyph.glyph_v3.ui.media.MediaViewerActivity.newIntentWithMultipleMedia(
                    context, mediaItems, index, msg.timestamp
                )
            )
        } else {
            val viewerUrl = msg.localUri?.takeIf { it.isNotEmpty() }
                ?: msg.imageUrl ?: ""
            if (viewerUrl.isNotEmpty()) {
                context.startActivity(
                    com.glyph.glyph_v3.ui.media.MediaViewerActivity.newIntent(
                        context, viewerUrl, msg.timestamp
                    )
                )
            }
        }
    } }
    
    val stableDownloadMedia = remember { { msg: Message -> currentOnDownloadMedia(msg) } }
    
    // Show scroll to bottom button if we are not at the bottom (index 0)
    // PERFORMANCE: Use derivedStateOf to avoid recomposition when not needed
    // Also skip during warmup to prevent FAB animations from causing jank
    val showScrollToBottom by remember {
        derivedStateOf {
            // Increased threshold to reduce button flickering and recomposition
            // Only show after warmup to prevent animations during cold start
            !isWarmup && (
                listState.firstVisibleItemIndex > 3 || 
                (listState.firstVisibleItemIndex > 0 && listState.firstVisibleItemScrollOffset > 100)
            )
        }
    }
    
    // PERFORMANCE: Disable FAB animation during warmup period
    // Use simpler animation spec to reduce frame drops
    val fabScale by animateFloatAsState(
        targetValue = if (showScrollToBottom && !isWarmup) 1f else 0f,
        animationSpec = if (showScrollToBottom && !isWarmup) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        } else {
            tween(durationMillis = 100) // Faster exit
        },
        label = "FabScale"
    )
    val fabAlpha by animateFloatAsState(
        targetValue = if (showScrollToBottom && !isWarmup) 1f else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "FabAlpha"
    )

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = uiState.selectionMode,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
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

                    val selectedIds = uiState.selectedMessageIds.ids
                    val selectedMessages = uiState.messages.items
                        .filterIsInstance<ChatListItem.MessageItem>()
                        .filter { it.message.id in selectedIds }
                        .map { it.message }

                    val anySelectedDeletedForAll = selectedMessages.any { it.isDeletedForAll }
                    
                    val showEdit = selectedMsg != null && 
                                   selectedMsg.type == MessageType.TEXT && 
                                   !selectedMsg.isIncoming &&
                                   !selectedMsg.isDeletedForAll &&
                                   (System.currentTimeMillis() - selectedMsg.timestamp) <= (15 * 60 * 1000)
                    
                    SelectionTopBar(
                        selectedCount = uiState.selectedMessageIds.ids.size,
                        onClearSelection = onClearSelection,
                        onDeleteSelected = { showDeleteDialog = true },
                        onReply = { 
                            selectedMsg?.let { onReply(it) }
                        },
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
                        showReplyAction = (uiState.selectedMessageIds.ids.size == 1) && !anySelectedDeletedForAll,
                        showForwardAction = !anySelectedDeletedForAll,
                        showStarAction = !anySelectedDeletedForAll,
                        showMenuActions = !anySelectedDeletedForAll
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
        },
        floatingActionButton = {
            // Positioned manually in Box to match XML layout (above input)
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .then(
                    if (uiState.wallpaperPath == null) {
                        if (glyphTheme.backgroundGradient != null) {
                            Modifier.background(glyphTheme.backgroundGradient!!)
                        } else {
                            Modifier.background(glyphTheme.backgroundPrimary)
                        }
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
                // Dimming
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(color = Color.Black.copy(alpha = uiState.wallpaperDimming))
                        }
                )
            }

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // PERFORMANCE: Use the derived state that updates with audio changes
                // This ensures VoiceMessageBubble gets updated without recomposing the entire tree
                CompositionLocalProvider(LocalAudioPlaybackState provides audioPlaybackDerivedState) {
                    // Message List Container with Sticky Header Overlay
                    MessageList(
                        messages = uiState.messages,
                        listState = listState,
                        isTyping = uiState.isTyping,
                        mediaProgress = uiState.mediaProgress,
                        selectedIds = uiState.selectedMessageIds,
                        onMediaClick = stableMediaClick,
                        onDownloadMedia = stableDownloadMedia,
                        onToggleSelection = onToggleSelection,
                        onReply = onReply,
                        currentUserPhone = uiState.currentUserPhone,
                        otherUsername = uiState.otherUserUsername,
                        isFastScrolling = isFastScrolling,
                        isWarmup = isWarmup,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                // Input Area
                ChatInput(
                    text = uiState.inputText,
                    onTextChanged = stableTyping,
                    onSendClick = {
                        stableSendMessage(uiState.inputText)
                    },
                    onAttachClick = {
                        showAttachmentMenu = !showAttachmentMenu
                    },
                    onCameraClick = stableCameraClick,
                    replyToMessage = uiState.replyToMessage,
                    onCancelReply = onCancelReply,
                    otherUsername = uiState.otherUserUsername,
                    editingMessage = uiState.editingMessage,
                    onCancelEdit = onCancelEditMode,
                    isRecording = isRecording,
                    isLocked = isLocked,
                    recordingDuration = recordingDuration,
                    recordingAmplitude = recordingAmplitude,
                    imeBottomPadding = imeBottomPadding,
                    onMicDown = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            if (!isRecording) {
                                isRecording = true
                                isLocked = false
                                val file = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                                audioRecorder.start(file)
                                recordingFile = file
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onMicUp = {
                        if (isRecording && !isLocked) {
                            isRecording = false
                            val duration = audioRecorder.stop()
                            recordingFile?.let { file ->
                                onSendVoice(file, duration)
                            }
                        }
                    },
                    onMicLocked = {
                        isLocked = true
                    },
                    onStopRecording = {
                        isRecording = false
                        isLocked = false
                        val duration = audioRecorder.stop()
                        recordingFile?.let { file ->
                            onSendVoice(file, duration)
                        }
                    },
                    onCancelRecording = {
                        isRecording = false
                        isLocked = false
                        audioRecorder.cancel()
                    }
                )
            }

            // Attachment Menu Overlay
            AttachmentMenu(
                visible = showAttachmentMenu,
                onDismiss = { showAttachmentMenu = false },
                onGalleryClick = stableGalleryClick,
                onCameraClick = stableCameraClick
            )
            
            // Media Compression Dialog
            if (showCompressionDialog) {
                MediaCompressionDialog(
                    uris = selectedMediaUris,
                    onDismiss = { showCompressionDialog = false },
                    onConfirm = { quality, overrides ->
                        showCompressionDialog = false
                        onSendMedia(selectedMediaUris, quality, overrides)
                    }
                )
            }
            
            // Scroll to Bottom FAB (Custom positioning)
            // ULTRA-OPTIMIZED: Use lambda-based graphicsLayer to avoid recomposing ChatScreen during fade
            if (showScrollToBottom || fabAlpha > 0.01f) {
                SmallFloatingActionButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        scope.launch {
                            val currentIndex = listState.firstVisibleItemIndex
                            if (currentIndex > 10) {
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
                        .padding(bottom = 80.dp, end = 16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabAlpha
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

@Composable
internal fun DeleteConfirmationDialog(
    canDeleteForAll: Boolean,
    onDismiss: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit
) {
    // Prevent double-taps triggering actions twice.
    var actionTaken by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!actionTaken) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true
        )
    ) {
        var animateIn by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateIn = true }
        val alpha by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "DeleteDialogAlpha"
        )
        val scale by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0.96f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "DeleteDialogScale"
        )

        Surface(
            modifier = Modifier
                .graphicsLayer {
                    this.alpha = alpha
                    this.scaleX = scale
                    this.scaleY = scale
                },
            shape = RoundedCornerShape(18.dp),
            color = if (glyphTheme.isDark) glyphTheme.surfaceInput else glyphTheme.surfaceHeader,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = "Delete message?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = glyphTheme.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = glyphTheme.bubbleBorder)
                Spacer(modifier = Modifier.height(8.dp))

                val buttonTextColor = glyphTheme.textPrimary

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (canDeleteForAll) {
                        TextButton(
                            onClick = {
                                if (actionTaken) return@TextButton
                                actionTaken = true
                                onDeleteForEveryone()
                            },
                            enabled = !actionTaken,
                            modifier = Modifier.wrapContentWidth(Alignment.End)
                        ) {
                            Text(
                                text = "Delete for everyone",
                                color = buttonTextColor,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            if (actionTaken) return@TextButton
                            actionTaken = true
                            onDeleteForMe()
                        },
                        enabled = !actionTaken,
                        modifier = Modifier.wrapContentWidth(Alignment.End)
                    ) {
                        Text(
                            text = "Delete for me",
                            color = buttonTextColor,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    TextButton(
                        onClick = { if (!actionTaken) onDismiss() },
                        enabled = !actionTaken,
                        modifier = Modifier.wrapContentWidth(Alignment.End)
                    ) {
                        Text(
                            text = "Cancel",
                            color = buttonTextColor,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    username: String,
    avatar: String,
    presence: String,
    onBackClick: () -> Unit
) {
    val gradient = glyphTheme.gradientHeader
    
    Box(modifier = Modifier.fillMaxWidth().then(if (gradient != null) Modifier.background(gradient) else Modifier)) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Back Arrow and Avatar group
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val offsetPx = (-9).dp.roundToPx()
                                layout(placeable.width + offsetPx, placeable.height) {
                                    placeable.placeRelative(offsetPx, 0)
                                }
                            }
                            .clickable { /* Open profile */ }
                    ) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            IconButton(
                                onClick = onBackClick,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back),
                                    contentDescription = "Back",
                                    tint = glyphTheme.headerIcon,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(avatar)
                                .crossfade(false) // Disable crossfade for performance
                                .size(120) // Small fixed size for avatar
                                .allowHardware(true) // Enable hardware bitmaps for GPU rendering
                                .memoryCacheKey("avatar_$avatar") // Explicit cache key
                                .diskCacheKey("avatar_$avatar")
                                .placeholder(R.drawable.ic_default_avatar)
                                .error(R.drawable.ic_default_avatar)
                                .build(),
                            contentDescription = null, // Performance: Skip for avatar
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val offsetPx = (-5).dp.roundToPx()
                                    layout(placeable.width + offsetPx, placeable.height) {
                                        placeable.placeRelative(offsetPx, 0)
                                    }
                                }
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                    
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 3.dp)
                            .clickable { /* Open profile */ }
                    ) {
                        Text(
                            text = username,
                            style = MaterialTheme.typography.titleMedium,
                            color = glyphTheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (presence.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(0.dp))
                            
                            // Presence text with scroll animation - Optimized
                            Box(modifier = Modifier.clipToBounds()) {
                                val scrollX = remember { Animatable(0f) }
                                val textMeasurer = rememberTextMeasurer()
                                val density = LocalDensity.current
                                val style = MaterialTheme.typography.bodySmall
                                val isInitialRender = remember { mutableStateOf(true) }
                                
                                LaunchedEffect(Unit) {
                                    delay(300) // Defer animation until after initial render
                                    isInitialRender.value = false
                                }
                                
                                LaunchedEffect(isInitialRender.value, presence) {
                                    if (isInitialRender.value) return@LaunchedEffect // Skip during initial render
                                    if (presence.startsWith("last seen", ignoreCase = true)) {
                                        val prefix = "last seen "
                                        val width = textMeasurer.measure(
                                            text = prefix,
                                            style = style
                                        ).size.width.toFloat()
                                        
                                        delay(500)
                                        scrollX.animateTo(
                                            targetValue = width,
                                            animationSpec = tween(
                                                durationMillis = 1200,
                                                easing = Easing { OvershootInterpolator(1.5f).getInterpolation(it) }
                                            )
                                        )
                                    } else {
                                        scrollX.snapTo(0f)
                                    }
                                }
                                
                                Text(
                                    text = presence,
                                    style = style,
                                    color = if (presence == "Online" || presence == "Online in-chat") {
                                        Color(0xFF4CAF50) // Green color
                                    } else {
                                        glyphTheme.textSecondary
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.graphicsLayer {
                                        translationX = -scrollX.value
                                    }
                                )
                            }
                        }
                    }
                }
            },
            navigationIcon = {},
            actions = {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .offset(x = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        IconButton(onClick = { /* Video Call */ }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_videocam), 
                                contentDescription = "Video Call",
                                tint = glyphTheme.headerIcon,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                        IconButton(onClick = { /* Voice Call */ }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_call), 
                                contentDescription = "Voice Call",
                                tint = glyphTheme.headerIcon,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { /* Quick Actions */ }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bolt), 
                                contentDescription = "Quick Actions",
                                tint = glyphTheme.headerIcon,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { /* Menu */ }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_vert), 
                                contentDescription = "Menu",
                                tint = glyphTheme.headerIcon,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (gradient != null) Color.Transparent 
                                else if (glyphTheme.isDark) glyphTheme.surfaceInput 
                                else glyphTheme.surfaceHeader,
                titleContentColor = glyphTheme.textPrimary,
                navigationIconContentColor = glyphTheme.headerIcon,
                actionIconContentColor = glyphTheme.headerIcon
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onReply: () -> Unit = {},
    onEdit: () -> Unit = {},
    onForward: () -> Unit = {},
    onStar: () -> Unit = {},
    onCopy: () -> Unit = {},
    onPin: () -> Unit = {},
    onViewDetails: () -> Unit = {},
    showEditAction: Boolean = false,
    showReplyAction: Boolean = true,
    showForwardAction: Boolean = true,
    showStarAction: Boolean = true,
    showMenuActions: Boolean = true
) {
    val gradient = glyphTheme.gradientHeader
    var showMenu by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxWidth().then(if (gradient != null) Modifier.background(gradient) else Modifier)) {
        TopAppBar(
            title = {
                Text(
                    text = selectedCount.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = glyphTheme.textPrimary
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = "Clear Selection",
                        tint = glyphTheme.headerIcon
                    )
                }
            },
            actions = {
                if (selectedCount == 1 && showEditAction) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = "Edit",
                            tint = glyphTheme.headerIcon
                        )
                    }
                }
                if (selectedCount == 1 && showReplyAction) {
                    IconButton(onClick = onReply) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_reply),
                            contentDescription = "Reply",
                            tint = glyphTheme.headerIcon
                        )
                    }
                }
                if (showStarAction) {
                    IconButton(onClick = onStar) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_star),
                            contentDescription = "Star",
                            tint = glyphTheme.headerIcon
                        )
                    }
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete",
                        tint = glyphTheme.headerIcon
                    )
                }
                if (showForwardAction) {
                    IconButton(onClick = onForward) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_forward),
                            contentDescription = "Forward",
                            tint = glyphTheme.headerIcon
                        )
                    }
                }
                if (showMenuActions) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_vert),
                                contentDescription = "Menu",
                                tint = glyphTheme.headerIcon
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = {
                                    showMenu = false
                                    onCopy()
                                }
                            )
                            if (selectedCount == 1) {
                                DropdownMenuItem(
                                    text = { Text("View details") },
                                    onClick = {
                                        showMenu = false
                                        onViewDetails()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Pin") },
                                onClick = {
                                    showMenu = false
                                    onPin()
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (gradient != null) Color.Transparent 
                                else if (glyphTheme.isDark) glyphTheme.surfaceInput 
                                else glyphTheme.surfaceHeader,
                titleContentColor = glyphTheme.textPrimary,
                navigationIconContentColor = glyphTheme.headerIcon,
                actionIconContentColor = glyphTheme.headerIcon
            )
        )
    }
}

@Composable
fun DateHeader(
    date: String, 
    isStuck: Boolean = false,
    textMeasurer: TextMeasurer,
    density: Density
) {
    val bgColor = glyphTheme.dateHeaderBackground
    val textColor = glyphTheme.dateHeaderText
    
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = textColor
    )
    
    // PERFORMANCE: Use global cache for date headers - they repeat often
    val textLayoutResult = TextLayoutCache.getOrMeasure(
        messageId = "date_$date",
        text = date,
        maxWidthPx = Int.MAX_VALUE,
        styleKey = 0 // Date headers have consistent style
    ) {
        textMeasurer.measure(date, textStyle)
    }
    
    val paddingH = with(density) { 12.dp.toPx() }
    val paddingV = with(density) { 6.dp.toPx() }
    val radiusPx = with(density) { 12.dp.toPx() }
    
    val width = textLayoutResult.size.width.toFloat() + (paddingH * 2f)
    val height = textLayoutResult.size.height.toFloat() + (paddingV * 2f)

    // Center the date header using fillMaxWidth and layout modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            modifier = Modifier
                .size(
                    width = with(density) { width.toDp() },
                    height = with(density) { height.toDp() }
                )
                .drawBehind {
                    drawRoundRect(
                        color = bgColor,
                        cornerRadius = CornerRadius(radiusPx, radiusPx)
                    )
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(paddingH, paddingV)
                    )
                }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun MessageList(
    messages: ChatListItemList,
    listState: LazyListState,
    isTyping: Boolean,
    mediaProgress: MediaProgressMap,
    selectedIds: SelectedMessagesSet,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    onToggleSelection: (String) -> Unit = {},
    onReply: (Message) -> Unit = {},
    currentUserPhone: String = "",
    otherUsername: String = "",
    isFastScrolling: Boolean = false, // PERFORMANCE: Disable expensive features during fast scroll
    isWarmup: Boolean = false, // PERFORMANCE: Disable optional work during warm-up
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    // CRITICAL: Keep the list as lightweight as possible.
    // - No image prefetch work
    // - No sticky header overlay / scroll observers
    // - No message staging/coordination state
    
    // PERFORMANCE: Aggressive async pre-warming for instant scroll
    // Pre-warm ALL messages in background - zero blocking on main thread
    val preWarmedCount = remember { mutableStateOf(0) }
    val currentMessageCount = messages.items.size
    
    // CRITICAL: Track if initial warmup is complete to defer expensive work
    val isInitialWarmupComplete = remember { mutableStateOf(false) }
    
    // Async pre-warm when message count increases
    LaunchedEffect(currentMessageCount) {
        if (currentMessageCount > preWarmedCount.value) {
            withContext(Dispatchers.Default) {
                val textMaxWidthInt = with(density) { 
                    ((configuration.screenWidthDp.dp * 0.90f).toPx() - (12.dp.toPx() * 2)).toInt() 
                }
                
                val textStyle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp)
                val timeStyle = TextStyle(fontSize = 13.sp)
                
                val allMessages = messages.items
                    .filterIsInstance<ChatListItem.MessageItem>()
                    .map { it.message }
                
                // Pre-warm ALL remaining messages aggressively (no limit)
                val messagesToWarm = allMessages.drop(preWarmedCount.value)
                
                if (messagesToWarm.isNotEmpty()) {
                    // Pre-warm message text layouts
                    TextLayoutCache.preWarm(
                        messages = messagesToWarm,
                        textMeasurer = textMeasurer,
                        textStyle = textStyle,
                        timeStyle = timeStyle,
                        maxWidthPx = textMaxWidthInt
                    )
                    val newCount = preWarmedCount.value + messagesToWarm.size
                    logPerf("🔥 TextLayoutCache pre-warmed +${messagesToWarm.size} messages (total: $newCount/${allMessages.size}), cache: ${TextLayoutCache.size()}")
                    preWarmedCount.value = newCount
                    
                    // CRITICAL: Pre-warm reply preview layouts to prevent scroll jank
                    val repliesInBatch = messagesToWarm.filter { it.replyToMessageId != null }
                    if (repliesInBatch.isNotEmpty()) {
                        // Compute styles and dimensions needed for reply warmup
                        val replyMaxWidthPx = with(density) { 
                            ((configuration.screenWidthDp.dp * 0.90f) - 16.dp).toPx()
                        }
                        val paddingPx = with(density) { 8.dp.toPx() }
                        val accentWidthPx = with(density) { 3.dp.toPx() }
                        val accentSpacingPx = with(density) { 8.dp.toPx() }
                        
                        // Create reply typography styles (lightweight computation)
                        val senderStyle = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val messageStyle = TextStyle(
                            fontSize = 13.sp
                        )
                        
                        TextLayoutCache.preWarmReplyPreviews(
                            messages = repliesInBatch,
                            textMeasurer = textMeasurer,
                            senderStyle = senderStyle,
                            messageStyle = messageStyle,
                            maxWidthPx = replyMaxWidthPx,
                            paddingPx = paddingPx,
                            accentWidthPx = accentWidthPx,
                            accentSpacingPx = accentSpacingPx,
                            currentUserPhone = currentUserPhone,
                            otherUsername = otherUsername
                        )
                        logPerf("🎯 Reply previews pre-warmed: ${repliesInBatch.size} replies in background")
                    }
                    
                    // Mark warmup as complete after BOTH message AND reply pre-warming
                    if (!isInitialWarmupComplete.value) {
                        isInitialWarmupComplete.value = true
                        logPerf("✅ Initial cache warmup complete (text + replies) - ready for smooth scroll")
                    }
                    
                    // Single GC after batch pre-warming
                    if (newCount >= allMessages.size) {
                        delay(50)
                        System.gc()
                        logPerf("♻️ GC completed after pre-warming")
                    }
                }
            }
        }
    }
    
    // PERFORMANCE: Track message count changes and log
    LaunchedEffect(messages.items.size, isTyping, isWarmup) {
        logPerf("📊 MessageList: ${messages.items.size} items, typing=$isTyping, warmup=$isWarmup, fastScroll=$isFastScrolling")
    }
    
    val messagesState = messages.items
    
    // Pre-calculate bubble widths to avoid reading configuration in every item
    val screenWidth = configuration.screenWidthDp.dp
    val maxTextWidth = remember { screenWidth * 0.90f }
    val maxMediaWidth = remember { screenWidth * 0.85f }
    
    // PERFORMANCE: Track scroll state and log cache stats periodically
    LaunchedEffect(listState) {
        var lastCacheLogTime = 0L
        snapshotFlow { 
            Triple(listState.firstVisibleItemIndex, listState.isScrollInProgress, listState.layoutInfo.visibleItemsInfo.size)
        }.collect { (index, scrolling, visible) ->
            if (scrolling && index % 10 == 0) {
                logPerf("📜 Scroll: index=$index, visible=$visible items")
                // Log cache stats during scroll
                val now = System.currentTimeMillis()
                if (now - lastCacheLogTime > 1000) {
                    lastCacheLogTime = now
                    TextLayoutCache.logStats()
                }
            }
        }
    }

    // Reply Highlight State
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Scroll to reply logic
    fun scrollToReply(replyId: String) {
        val index = messages.items.indexOfFirst { it is ChatListItem.MessageItem && it.message.id == replyId }
        if (index != -1) {
            coroutineScope.launch {
                // Calculate actual index in LazyColumn (accounting for typing indicator)
                // Note: reverseLayout=true means index 0 is at the bottom visually, but logically index 0 is the first item added.
                // We always have typing indicator at index 0 (even if invisible/collapsed), so messages start at index 1.
                val typingOffset = 1
                val targetIndex = index + typingOffset
                
                // Check if item is already visible
                val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                
                if (!isVisible) {
                    listState.animateScrollToItem(targetIndex)
                }
                
                // Trigger highlight animation
                highlightedMessageId = null // Reset to force re-trigger
                delay(50)
                highlightedMessageId = replyId
            }
        }
    }

    // NOTE: Image prefetching and sticky header overlays intentionally removed.
    // They are non-essential and can contend for CPU/IO during the first scroll on mid-range devices.
    
    // PERFORMANCE: Track scroll position and velocity
    LaunchedEffect(listState) {
        snapshotFlow { 
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (index, offset, scrolling) ->
            if (scrolling && index % 10 == 0) { // Log every 10 items
                logPerf("📜 Scroll position: index=$index, offset=$offset")
            }
        }
    }
    
    // PERFORMANCE: Pre-compute selection state once at list level
    val isSelectionMode = selectedIds.ids.isNotEmpty()
    val selectedIdSet = remember(selectedIds.ids) { selectedIds.ids }
    
    // Create theme objects once at top level
    val theme = glyphTheme
    
    // PERFORMANCE: Pre-compute density conversions to avoid repeated with(density) calls
    val densityConversions = remember(density) {
        object {
            val padding8px = with(density) { 8.dp.toPx() }
            val accent3px = with(density) { 3.dp.toPx() }
            val accentSpacing8px = with(density) { 8.dp.toPx() }
        }
    }
    
    // PERFORMANCE: Pre-compute typography styles to avoid MaterialTheme reads per message
    val senderStyleBold = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp
    )
    val messageStyleBase = MaterialTheme.typography.bodySmall.copy(
        fontSize = 13.sp
    )
    val replyTypography = remember(senderStyleBold, messageStyleBase) {
        object {
            val senderStyleBold = senderStyleBold
            val messageStyleBase = messageStyleBase
        }
    }
    
    // PERFORMANCE: Pre-compute BubbleThemeSet at list level (read once, use in all messages)
    val bubbleTheme = remember(theme) {
        BubbleThemeSet(
            outgoingGradient = theme.gradientBubbleOutgoing,
            incomingGradient = theme.gradientBubbleIncoming,
            outgoingSolid = theme.bubbleOutgoingBackground,
            incomingSolid = theme.bubbleIncomingBackground,
            outgoingText = theme.bubbleOutgoingText,
            incomingText = theme.bubbleIncomingText,
            timestamp = theme.bubbleTimestamp,
            borderStroke = BorderStroke(0.5.dp, theme.bubbleBorder),
            cornerRadiusMedium = theme.cornerRadiusMedium.value
        )
    }
    
    // PERFORMANCE: Pre-compute all bubble shapes (4 positions × 2 directions = 8 shapes)
    val bubbleShapes = remember(theme.cornerRadiusMedium) {
        BubbleGroupPosition.values().flatMap { position ->
            listOf(true, false).map { isSelf ->
                (position to isSelf) to getBubbleShape(isSelf, position, theme.cornerRadiusMedium)
            }
        }.toMap()
    }
    
    // PERFORMANCE: Pre-compute AudioPlaybackInfo at list level
    val audioPlaybackState = LocalAudioPlaybackState.current
    val currentAudioInfo = remember(audioPlaybackState?.playingMessageId, audioPlaybackState?.isPlaying, audioPlaybackState?.progress) {
        if (audioPlaybackState != null && audioPlaybackState.playingMessageId != null) {
            val progress = audioPlaybackState.progress ?: 0f
            val messageDuration = audioPlaybackState.audioDuration ?: 1000L
            val posSec = (progress * messageDuration / 1000).toLong()
            val formattedPos = String.format("%d:%02d", posSec / 60, posSec % 60)
            
            AudioPlaybackInfo(
                messageId = audioPlaybackState.playingMessageId!!,
                isPlaying = audioPlaybackState.isPlaying,
                progress = progress,
                formattedPosition = formattedPos,
                audioDuration = messageDuration
            )
        } else null
    }
    
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 4.dp, top = 4.dp),
            flingBehavior = ScrollableDefaults.flingBehavior()
            // REMOVED: userScrollEnabled block - user can now scroll immediately
        ) {
            // Typing indicator - keep stable index but only show when actually typing
            item(
                key = "typing_indicator",
                contentType = "typing_indicator"
            ) {
                AnimatedVisibility(
                    visible = isTyping,
                    enter = fadeIn(animationSpec = tween(120)) + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    TypingIndicator(
                        textMeasurer = textMeasurer,
                        density = density
                    )
                }
            }

            // Use items() for better performance and stable keys
            items(
                items = messagesState,
                key = { it.id },
                contentType = { item ->
                    when (item) {
                        is ChatListItem.MessageItem -> {
                            // More specific content types for better recycling
                            // PERFORMANCE: Include isIncoming to separate recycling pools for better layout stability
                            val direction = if (item.message.isIncoming) "in" else "out"
                            when (item.message.type) {
                                MessageType.IMAGE -> "message_image_$direction"
                                MessageType.VIDEO -> "message_video_$direction"
                                MessageType.AUDIO -> "message_audio_$direction"
                                MessageType.MEDIA_GROUP -> "message_group_$direction"
                                else -> if (item.message.replyToMessageId != null) "message_text_reply_$direction" else "message_text_$direction"
                            }
                        }
                        is ChatListItem.DateHeader -> "date_header"
                    }
                }
            ) { item ->
                // PERFORMANCE: Use key() to scope state per item, preventing cross-contamination
                key(item.id) {
                    when (item) {
                        is ChatListItem.MessageItem -> {
                            // PERFORMANCE: Direct read from pre-computed state (no derivedStateOf)
                            val isSelected = selectedIdSet.contains(item.message.id)

                            // PERFORMANCE: Direct callback references (no inline lambda allocations)
                            val handleNormalClick: () -> Unit = {
                                if (item.message.type == MessageType.IMAGE || item.message.type == MessageType.VIDEO) {
                                    val isDownloading = item.message.status == MessageStatus.DOWNLOADING
                                    val isUploading = item.message.status == MessageStatus.SENDING
                                    val isPendingDownload = item.message.status == MessageStatus.PENDING_DOWNLOAD || item.message.status == MessageStatus.DOWNLOAD_FAILED

                                    if (!isPendingDownload && !isDownloading && !isUploading) {
                                        onMediaClick(item.message, 0)
                                    } else if (isPendingDownload) {
                                        onDownloadMedia(item.message)
                                    }
                                }
                            }
                            
                            val handleToggleSelection: () -> Unit = { onToggleSelection(item.message.id) }
                            val handleReply: () -> Unit = { onReply(item.message) }

                            // PERFORMANCE: Compute reply preview width lazily only for messages with replies
                            // CRITICAL: Defer until warmup complete to prevent blocking initial scroll
                                val replyPreviewWidth = if (item.message.replyToMessageId != null && isInitialWarmupComplete.value) {
                                val accentColorLocal = if (theme.isDark) theme.actionPrimary else theme.textLink
                                val senderStyleLocal = replyTypography.senderStyleBold.copy(color = accentColorLocal)
                                val messageStyleLocal = replyTypography.messageStyleBase.copy(
                                    color = if (item.message.isIncoming) theme.bubbleIncomingText.copy(alpha = 0.8f) else theme.bubbleOutgoingText.copy(alpha = 0.8f)
                                )
                                
                                remember(item.message.id, maxTextWidth) {
                                    val replyPreviewInnerMaxWidth = maxTextWidth - 24.dp
                                    val accentColorForCache = if (theme.isDark) theme.actionPrimary else theme.textLink
                                    val isReplyToSelfLocal = item.message.replyToSenderId == currentUserPhone
                                    val senderTextLocal = if (isReplyToSelfLocal) "You" else otherUsername
                                    val messageTextLocal = when (item.message.replyToType) {
                                        MessageType.IMAGE -> "📷 Photo"
                                        MessageType.VIDEO -> "🎥 Video"
                                        MessageType.AUDIO -> "🎤 Audio"
                                        MessageType.MEDIA_GROUP -> "📷 Album"
                                        else -> item.message.replyToText ?: "Message"
                                    }
                                    
                                    // Use pre-computed density conversions
                                    val paddingPx = densityConversions.padding8px
                                    val accentWidthPx = densityConversions.accent3px
                                    val accentSpacingPx = densityConversions.accentSpacing8px
                                    val maxWidthPx = with(density) { replyPreviewInnerMaxWidth.toPx() }
                                    
                                    val layouts = TextLayoutCache.getOrMeasureReplyPreview(
                                        messageId = item.message.id,
                                        senderText = senderTextLocal,
                                        messageText = messageTextLocal,
                                        senderStyle = senderStyleLocal,
                                        messageStyle = messageStyleLocal,
                                        maxWidthPx = maxWidthPx,
                                        paddingPx = paddingPx,
                                        accentWidthPx = accentWidthPx,
                                        accentSpacingPx = accentSpacingPx,
                                        textMeasurer = textMeasurer
                                    )
                                    
                                    val outerPaddingDp = 24.dp
                                    (with(density) { layouts.minWidth.toDp() } + outerPaddingDp).coerceAtMost(maxTextWidth)
                                }
                            } else null

                            // CRITICAL PERFORMANCE: Three-tier rendering strategy
                            // 1. During fast scroll/warmup → Ultra-lightweight placeholder (sub-millisecond)
                            // 2. Selection mode or no swipe → Standard MessageBubble (no swipe overhead)
                            // 3. Normal interaction → Full SwipeableMessageBubble
                            val isSelf = !item.message.isIncoming
                            val msgMaxWidth = if (item.message.type == MessageType.IMAGE || item.message.type == MessageType.VIDEO) maxMediaWidth else maxTextWidth
                            
                            // PERFORMANCE: Full visual rendering always - proper chat app experience
                            // Swipe is disabled during fast scroll/warmup/selection to reduce gesture overhead
                            val enableSwipe = !isSelectionMode && !isFastScrolling && !isWarmup && !item.message.isDeletedForAll
                            
                            if (enableSwipe) {
                                SwipeableMessageBubble(
                                    message = item.message,
                                    groupPosition = item.groupPosition,
                                    isSelf = isSelf,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    isFastScrolling = isFastScrolling,
                                    isWarmup = isWarmup,
                                    onToggleSelection = handleToggleSelection,
                                    onNormalClick = handleNormalClick,
                                    onReply = handleReply,
                                    context = context,
                                    textMeasurer = textMeasurer,
                                    density = density,
                                    maxWidth = msgMaxWidth,
                                    onMediaClick = onMediaClick,
                                    onDownloadMedia = onDownloadMedia,
                                    progress = mediaProgress.progress[item.message.id],
                                    currentUserPhone = currentUserPhone,
                                    otherUsername = otherUsername,
                                    isHighlighted = highlightedMessageId == item.message.id,
                                    onReplyClick = { replyId -> scrollToReply(replyId) },
                                    onHighlightFinished = { highlightedMessageId = null },
                                    bubbleTheme = bubbleTheme,
                                    currentAudioInfo = currentAudioInfo,
                                    replyPreviewWidth = replyPreviewWidth,
                                    hapticFeedback = haptic,
                                    view = view
                                )
                            } else {
                                MessageBubble(
                                    message = item.message,
                                    groupPosition = item.groupPosition,
                                    isSelf = isSelf,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = handleToggleSelection,
                                    onNormalClick = handleNormalClick,
                                    onReply = handleReply,
                                    context = context,
                                    textMeasurer = textMeasurer,
                                    density = density,
                                    maxWidth = msgMaxWidth,
                                    onMediaClick = onMediaClick,
                                    onDownloadMedia = onDownloadMedia,
                                    progress = mediaProgress.progress[item.message.id],
                                    currentUserPhone = currentUserPhone,
                                    otherUsername = otherUsername,
                                    isHighlighted = highlightedMessageId == item.message.id,
                                    onReplyClick = { replyId -> scrollToReply(replyId) },
                                    onHighlightFinished = { highlightedMessageId = null },
                                    bubbleTheme = bubbleTheme,
                                    currentAudioInfo = currentAudioInfo,
                                    replyPreviewWidth = replyPreviewWidth,
                                    hapticFeedback = haptic,
                                    view = view,
                                    bubbleShapes = bubbleShapes
                                )
                            }
                        }
                        is ChatListItem.DateHeader -> {
                            DateHeader(
                                date = item.dateString, 
                                isStuck = false,
                                textMeasurer = textMeasurer,
                                density = density
                            )
                        }
                    }
                }
            }
        }

        // Sticky header overlay intentionally removed for performance.
    }
}

@Composable
private fun StickyHeaderOverlay(
    activeDateState: State<String?>,
    showStickyHeader: Boolean,
    textMeasurer: TextMeasurer,
    density: Density
) {
    val activeDate = activeDateState.value
    val headerAlpha by animateFloatAsState(
        targetValue = if (showStickyHeader && activeDate != null) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "StickyHeaderAlpha"
    )
    
    if (activeDate != null) {
         Box(
             modifier = Modifier
                 .fillMaxWidth()
                 .padding(top = 8.dp)
                 .graphicsLayer { 
                     alpha = headerAlpha 
                     // Move off-screen when invisible to prevent touch interception
                     translationY = if (headerAlpha > 0.01f) 0f else -2000f
                 },
             contentAlignment = Alignment.TopCenter
         ) {
             DateHeader(
                 date = activeDate, 
                 isStuck = true,
                 textMeasurer = textMeasurer,
                 density = density
             )
         }
    }
}

/**
 * SwipeableMessageBubble - Wraps MessageBubble with swipe-to-reply gesture
 * Implements WhatsApp-style horizontal swipe with reply icon animation
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageBubble(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isFastScrolling: Boolean = false, // PERFORMANCE: Disable swipe during fast scroll
    isWarmup: Boolean = false, // PERFORMANCE: Disable swipe during warm-up
    onToggleSelection: () -> Unit = {},
    onNormalClick: () -> Unit = {},
    onReply: () -> Unit = {},
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: Float? = null,
    currentUserPhone: String = "",
    otherUsername: String = "",
    isHighlighted: Boolean = false,
    onReplyClick: (String) -> Unit = {},
    onHighlightFinished: () -> Unit = {},
    bubbleTheme: BubbleThemeSet? = null,
    currentAudioInfo: AudioPlaybackInfo? = null,
    replyPreviewWidth: Dp? = null,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback? = null,
    view: android.view.View? = null,
    bubbleShapes: Map<Pair<BubbleGroupPosition, Boolean>, RoundedCornerShape>? = null
) {
    // CRITICAL: Short-circuit during scroll to avoid ANY state creation
    val enableSwipe = !isSelectionMode && !isFastScrolling && !isWarmup && !message.isDeletedForAll
    
    if (!enableSwipe) {
        // Lightweight path: skip all swipe state/gesture setup
        MessageBubble(
            message = message,
            groupPosition = groupPosition,
            isSelf = isSelf,
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            onToggleSelection = onToggleSelection,
            onNormalClick = onNormalClick,
            onReply = onReply,
            context = context,
            textMeasurer = textMeasurer,
            density = density,
            maxWidth = maxWidth,
            onMediaClick = onMediaClick,
            onDownloadMedia = onDownloadMedia,
            progress = progress,
            currentUserPhone = currentUserPhone,
            otherUsername = otherUsername,
            isHighlighted = isHighlighted,
            onReplyClick = onReplyClick,
            onHighlightFinished = onHighlightFinished,
            bubbleTheme = bubbleTheme,
            currentAudioInfo = currentAudioInfo,
            replyPreviewWidth = replyPreviewWidth,
            hapticFeedback = hapticFeedback,
            view = view,
            bubbleShapes = bubbleShapes
        )
        return
    }
    
    // Full swipe logic only when needed
    val view = LocalView.current

    // Swipe state
    val offsetX = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val replyThreshold = with(density) { 72.dp.toPx() }
    val maxSwipe = with(density) { 120.dp.toPx() }

    // Haptic tracking
    var lastHapticOffset by remember { mutableFloatStateOf(0f) }
    val hapticStep = with(density) { 16.dp.toPx() }

    // Track bubble height for dynamic icon sizing
    var bubbleHeight by remember { mutableStateOf(0.dp) }

    val sizeTrackingModifier = Modifier.onSizeChanged { size ->
        bubbleHeight = with(density) { size.height.toFloat().toDp() }
    }

    val swipeModifier = Modifier.pointerInput(isSelf) {
        detectHorizontalDragGestures(
            onDragStart = {
                hasTriggeredHaptic = false
                lastHapticOffset = 0f
            },
            onDragEnd = {
                scope.launch {
                    val currentOffset = offsetX.value
                    val absOffset = kotlin.math.abs(currentOffset)

                    // Trigger reply if threshold crossed
                    if (absOffset >= replyThreshold) {
                        val confirmHaptic = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            HapticFeedbackConstants.CONFIRM
                        } else {
                            HapticFeedbackConstants.LONG_PRESS
                        }
                        view.performHapticFeedback(confirmHaptic)
                        onReply()
                    }

                    // Snap back animation
                    offsetX.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
            },
            onDragCancel = {
                scope.launch { offsetX.animateTo(0f) }
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()

                val currentOffset = offsetX.value

                // Direction logic:
                // Incoming (!isSelf): Swipe RIGHT (positive)
                // Outgoing (isSelf): Swipe LEFT (negative)
                val isValidDirection = if (isSelf) {
                    dragAmount < 0 || currentOffset < 0
                } else {
                    dragAmount > 0 || currentOffset > 0
                }

                if (isValidDirection) {
                    // Apply elastic resistance
                    val targetOffset = currentOffset + dragAmount
                    val absTarget = kotlin.math.abs(targetOffset)

                    val resistance = if (absTarget > replyThreshold) {
                        1f - ((absTarget - replyThreshold) / (maxSwipe - replyThreshold)).coerceIn(0f, 1f) * 0.7f
                    } else 1f

                    val resistedDrag = dragAmount * resistance
                    val newOffset = currentOffset + resistedDrag

                    // Clamp based on direction
                    val clampedOffset = if (isSelf) {
                        newOffset.coerceIn(-maxSwipe, 0f)
                    } else {
                        newOffset.coerceIn(0f, maxSwipe)
                    }

                    scope.launch { offsetX.snapTo(clampedOffset) }

                    // Haptic feedback logic (Clock Tick)
                    val absClamped = kotlin.math.abs(clampedOffset)
                    if (kotlin.math.abs(absClamped - lastHapticOffset) > hapticStep) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastHapticOffset = absClamped
                    }

                    // Threshold crossing state
                    if (!hasTriggeredHaptic && absClamped >= replyThreshold) {
                        hasTriggeredHaptic = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } else if (hasTriggeredHaptic && absClamped < replyThreshold * 0.85f) {
                        hasTriggeredHaptic = false
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply icon (behind the bubble)
        val topPadding = if (groupPosition == BubbleGroupPosition.TOP || groupPosition == BubbleGroupPosition.SINGLE) 8.dp else 1.dp
        val verticalOffset = topPadding / 2f

        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp)
                .offset(y = verticalOffset)
        ) {
            val iconContainerSize = (bubbleHeight * 0.65f).coerceIn(28.dp, 44.dp)
            val iconSize = (iconContainerSize * 0.55f).coerceIn(16.dp, 24.dp)

            Box(
                modifier = Modifier
                    .align(if (isSelf) Alignment.CenterEnd else Alignment.CenterStart)
                    .graphicsLayer {
                        val currentOffset = offsetX.value
                        val absOffset = kotlin.math.abs(currentOffset)

                        val alphaVal = if (absOffset > 0) (absOffset / replyThreshold).coerceIn(0f, 1f) else 0f
                        val scaleVal = if (absOffset > replyThreshold * 0.8f) 1.0f else 0.8f + (alphaVal * 0.2f)

                        alpha = alphaVal
                        scaleX = scaleVal
                        scaleY = scaleVal
                        translationY = if (alphaVal > 0.01f) 0f else -2000f
                    }
                    .size(iconContainerSize)
                    .background(
                        color = glyphTheme.textPrimary,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_reply),
                    contentDescription = "Reply",
                    modifier = Modifier.size(iconSize),
                    tint = glyphTheme.backgroundPrimary
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(sizeTrackingModifier)
                .graphicsLayer { translationX = offsetX.value }
                .then(swipeModifier)
        ) {
            MessageBubble(
                message = message,
                groupPosition = groupPosition,
                isSelf = isSelf,
                isSelected = isSelected,
                isSelectionMode = isSelectionMode,
                onToggleSelection = onToggleSelection,
                onNormalClick = onNormalClick,
                onReply = onReply,
                context = context,
                textMeasurer = textMeasurer,
                density = density,
                maxWidth = maxWidth,
                onMediaClick = onMediaClick,
                onDownloadMedia = onDownloadMedia,
                progress = progress,
                currentUserPhone = currentUserPhone,
                otherUsername = otherUsername,
                isHighlighted = isHighlighted,
                onReplyClick = onReplyClick,
                onHighlightFinished = onHighlightFinished,
                bubbleTheme = bubbleTheme,
                currentAudioInfo = currentAudioInfo,
                replyPreviewWidth = replyPreviewWidth,
                hapticFeedback = hapticFeedback,
                view = view,
                bubbleShapes = bubbleShapes
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onNormalClick: () -> Unit = {},
    onReply: () -> Unit = {},
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: Float? = null,
    currentUserPhone: String = "",
    otherUsername: String = "",
    isHighlighted: Boolean = false,
    onReplyClick: (String) -> Unit = {},
    onHighlightFinished: () -> Unit = {},
    bubbleTheme: BubbleThemeSet? = null,
    currentAudioInfo: AudioPlaybackInfo? = null,
    replyPreviewWidth: Dp? = null,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback? = null,
    view: android.view.View? = null,
    bubbleShapes: Map<Pair<BubbleGroupPosition, Boolean>, RoundedCornerShape>? = null
) {
    // PERFORMANCE TRACKING
    if (PERF_DEBUG_ENABLED) {
        PerfMetrics.totalBubbleCompositions++
    }
    
    measureTime("MessageBubble[${message.id.take(8)}][${message.type}]", slowThresholdMs = 5.0) {
        MessageBubbleContent(
            message = message,
            groupPosition = groupPosition,
            isSelf = isSelf,
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            onToggleSelection = onToggleSelection,
            onNormalClick = onNormalClick,
            onReply = onReply,
            context = context,
            textMeasurer = textMeasurer,
            density = density,
            maxWidth = maxWidth,
            onMediaClick = onMediaClick,
            onDownloadMedia = onDownloadMedia,
            progress = progress,
            currentUserPhone = currentUserPhone,
            otherUsername = otherUsername,
            isHighlighted = isHighlighted,
            onReplyClick = onReplyClick,
            onHighlightFinished = onHighlightFinished,
            bubbleTheme = bubbleTheme,
            currentAudioInfo = currentAudioInfo,
            replyPreviewWidth = replyPreviewWidth,
            hapticFeedback = hapticFeedback,
            view = view,
            bubbleShapes = bubbleShapes
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleContent(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onNormalClick: () -> Unit,
    onReply: () -> Unit,
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: Float?,
    currentUserPhone: String,
    otherUsername: String,
    isHighlighted: Boolean,
    onReplyClick: (String) -> Unit,
    onHighlightFinished: () -> Unit,
    bubbleTheme: BubbleThemeSet? = null,
    currentAudioInfo: AudioPlaybackInfo? = null,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback? = null,
    view: android.view.View? = null,
    replyPreviewWidth: Dp? = null,
    bubbleShapes: Map<Pair<BubbleGroupPosition, Boolean>, RoundedCornerShape>? = null
) {
    val theme = glyphTheme
    val haptic = hapticFeedback ?: LocalHapticFeedback.current
    val view = view ?: LocalView.current
    
    // OPTIMIZATION: Use pre-computed theme if provided, otherwise fallback to local computation
    val gradient: Brush?
    val solidColor: Color
    val textColor: Color
    val timestampColor: Color
    val borderStroke: BorderStroke
    val cornerRadius: Dp
    
    if (bubbleTheme != null) {
        // Fast path: Use pre-computed theme (no lookups, no conditionals)
        gradient = if (isSelf) bubbleTheme.outgoingGradient else bubbleTheme.incomingGradient
        solidColor = if (isSelf) bubbleTheme.outgoingSolid else bubbleTheme.incomingSolid
        textColor = if (isSelf) bubbleTheme.outgoingText else bubbleTheme.incomingText
        timestampColor = bubbleTheme.timestamp
        borderStroke = bubbleTheme.borderStroke
        cornerRadius = bubbleTheme.cornerRadiusMedium.dp
    } else {
        // Fallback: Compute from theme (for backward compatibility)
        gradient = remember(isSelf) { if (isSelf) theme.gradientBubbleOutgoing else theme.gradientBubbleIncoming }
        solidColor = remember(isSelf) { if (isSelf) theme.bubbleOutgoingBackground else theme.bubbleIncomingBackground }
        textColor = remember(isSelf) { if (isSelf) theme.bubbleOutgoingText else theme.bubbleIncomingText }
        timestampColor = remember { theme.bubbleTimestamp }
        borderStroke = BorderStroke(0.5.dp, theme.bubbleBorder)
        cornerRadius = theme.cornerRadiusMedium
    }
    
    // PERFORMANCE: Only create EditAnimationState for recently edited messages
    // This avoids allocating animation state objects for 99% of messages
    val needsEditAnimation = message.isEdited && message.editedAt != null && 
        (System.currentTimeMillis() - (message.editedAt ?: 0L)) < 3000
    
    val editAnimState = if (needsEditAnimation) {
        remember(message.id) { EditAnimationState() }
    } else null
    
    // Only install edit animation effect for messages that need it
    if (needsEditAnimation && editAnimState != null) {
        LaunchedEffect(message.id, message.editedAt) {
            editAnimState.startAnimation()
        }
    }
    
    // PERFORMANCE: Only install highlight animation when actually highlighted
    val highlightAlpha = if (isHighlighted) {
        remember { Animatable(0f) }
    } else null
    
    if (isHighlighted && highlightAlpha != null) {
        LaunchedEffect(Unit) {
            highlightAlpha.animateTo(
                targetValue = 0.5f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            delay(800)
            highlightAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 500, easing = LinearEasing)
            )
            onHighlightFinished()
        }
    }
    
    // PERFORMANCE: Use pre-computed bubble shape if provided, otherwise compute
    val shape = if (bubbleShapes != null) {
        bubbleShapes[groupPosition to isSelf]!!
    } else {
        getBubbleShape(isSelf, groupPosition, cornerRadius)
    }

    // ULTRA-OPTIMIZED: Pass the layout modifier directly to sub-bubbles
    // This removes the wrapper Box node entirely, saving 1 node per message
    val topPadding = if (groupPosition == BubbleGroupPosition.TOP || groupPosition == BubbleGroupPosition.SINGLE) 8.dp else 1.dp
    
    // Full-width container for selection highlight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (isSelected) {
                    drawRect(color = Color.Black.copy(alpha = 0.2f))
                }
            }
        .combinedClickable(
            onClick = {
                if (isSelectionMode) {
                    onToggleSelection()
                } else {
                    if (!message.isDeletedForAll) {
                        onNormalClick()
                    }
                }
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleSelection()
            }
        )
        .padding(horizontal = 8.dp)
        .padding(top = topPadding)
    ) {
        // Use a high-contrast color for highlight (Darker for light themes, Lighter for dark themes)
        val highlightColor = if (theme.isDark) theme.actionPrimary else theme.textPrimary
        
        // OPTIMIZED: Build modifier chain efficiently
        val direction = if (isSelf) 1f else -1f
        
        // Base layout modifier - always needed
        val layoutModifier = Modifier
            .layout { measurable, constraints ->
                // Measure with loose constraints to allow wrap-content width
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                layout(constraints.maxWidth, placeable.height) {
                    val x = if (isSelf) constraints.maxWidth - placeable.width else 0
                    placeable.placeRelative(x, 0)
                }
            }
        
        // PERFORMANCE: Only add graphicsLayer if animation is active
        // This avoids the overhead of graphicsLayer for 99% of messages
        val bubbleModifier = if (editAnimState != null || highlightAlpha != null) {
            layoutModifier
                .graphicsLayer {
                    // Apply all transformations in single graphicsLayer for better performance
                    if (editAnimState != null) {
                        val isAnim = editAnimState.isAnimating.value
                        
                        // Translation and scale
                        translationX = editAnimState.translationX.value * direction
                        scaleX = editAnimState.scaleX.value
                        scaleY = editAnimState.scaleY.value
                        
                        // Transform origin
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            pivotFractionX = if (isSelf) 1f else 0f,
                            pivotFractionY = 0.5f
                        )
                        
                        // Shape morphing - only during animation
                        clip = true
                        this.shape = if (isAnim) {
                            getArrowShape(isSelf, editAnimState.arrowMorphProgress.value, theme.cornerRadiusMedium)
                        } else {
                            shape // Normal bubble shape
                        }
                    }
                }
                .let { mod ->
                    if (highlightAlpha != null) {
                        mod.drawWithContent {
                            drawContent()
                            if (highlightAlpha.value > 0f) {
                                drawRect(
                                    color = highlightColor.copy(alpha = highlightAlpha.value),
                                    blendMode = BlendMode.SrcAtop
                                )
                            }
                        }
                    } else mod
                }
        } else {
            layoutModifier
        }

        // Two-phase WhatsApp-style content replacement when delete-for-all applies.
        DeletedForAllTwoPhaseContent(
            messageId = message.id,
            isDeletedForAll = message.isDeletedForAll,
            isIncoming = message.isIncoming,
            onReceiverHaptic = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) },
            modifier = bubbleModifier,
            normalContent = {
                val isMediaMessage = message.type == MessageType.IMAGE || message.type == MessageType.VIDEO
                val mediaItems = if (message.type == MessageType.MEDIA_GROUP) message.getMediaItemsList() else emptyList()

                if (message.type == MessageType.MEDIA_GROUP && mediaItems.isNotEmpty()) {
                    GroupedMediaMessageBubble(
                        message = message,
                        mediaItems = mediaItems,
                        shape = shape,
                        maxWidth = maxWidth,
                        gradient = gradient,
                        solidColor = solidColor,
                        formattedTime = message.formattedTime,
                        isSelf = isSelf,
                        timestampColor = timestampColor,
                        borderStroke = borderStroke,
                        context = context,
                        onItemClick = { index ->
                            if (isSelectionMode) onToggleSelection() else onMediaClick(message, index)
                        },
                        onItemLongClick = { _ ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSelection()
                        },
                        onDownloadClick = { onDownloadMedia(message) },
                        progress = progress,
                        textMeasurer = textMeasurer,
                        density = density,
                        modifier = Modifier
                    )
                } else if (isMediaMessage && !message.imageUrl.isNullOrEmpty()) {
                    MediaMessageBubble(
                        message = message,
                        shape = shape,
                        maxWidth = maxWidth,
                        gradient = gradient,
                        solidColor = solidColor,
                        formattedTime = message.formattedTime,
                        isSelf = isSelf,
                        timestampColor = timestampColor,
                        borderStroke = borderStroke,
                        context = context,
                        onMediaClick = { onMediaClick(message, 0) },
                        onDownloadClick = { onDownloadMedia(message) },
                        mediaProgress = progress,
                        textMeasurer = textMeasurer,
                        density = density,
                        modifier = Modifier
                    )
                } else if (message.type == MessageType.AUDIO) {
                    val playbackState = LocalAudioPlaybackState.current
                    
                    // PERFORMANCE: Always use pre-computed audio info (no derivedStateOf, no fallback)
                    val isPlaying = currentAudioInfo?.messageId == message.id && currentAudioInfo.isPlaying
                    val audioProgress = if (currentAudioInfo?.messageId == message.id) currentAudioInfo.progress else 0f
                    val currentPositionFormatted = if (currentAudioInfo?.messageId == message.id) {
                        currentAudioInfo.formattedPosition
                    } else {
                        "0:00"
                    }
                    
                    val contentColor = textColor
                    
                    // Pre-calculate formatted strings outside composition to avoid allocation
                    val totalDurationFormatted = remember(message.audioDuration) {
                        String.format("%d:%02d", (message.audioDuration / 1000) / 60, (message.audioDuration / 1000) % 60)
                    }
                    
                    VoiceMessageBubble(
                        isSelf = isSelf,
                        isPlaying = isPlaying,
                        progress = audioProgress,
                        currentPosition = currentPositionFormatted,
                        totalDuration = totalDurationFormatted,
                        onPlayPause = {
                            if (isPlaying) playbackState?.onPause?.invoke() else playbackState?.onPlay?.invoke(message)
                        },
                        onSeek = { pos ->
                            playbackState?.onSeek?.invoke(message, pos)
                        },
                        // Styling parameters matching text/media bubbles
                        shape = shape,
                        backgroundColor = solidColor,
                        gradient = gradient,
                        contentColor = contentColor,
                        borderStroke = borderStroke,
                        modifier = Modifier,
                        durationMs = message.audioDuration,
                        timestamp = message.formattedTime,
                        status = message.status
                    )
                } else {
                    val hasReply = message.replyToMessageId != null

                    if (hasReply) {
                        RepliedMessageBubble(
                            message = message,
                            shape = shape,
                            maxWidth = maxWidth,
                            gradient = gradient,
                            solidColor = solidColor,
                            textColor = textColor,
                            timestampColor = timestampColor,
                            borderStroke = borderStroke,
                            isSelf = isSelf,
                            currentUserPhone = currentUserPhone,
                            otherUsername = otherUsername,
                            textMeasurer = textMeasurer,
                            density = density,
                            onReplyClick = onReplyClick,
                            replyPreviewWidth = replyPreviewWidth,
                            modifier = Modifier
                        )
                    } else {
                        TextMessageBubble(
                            message = message,
                            shape = shape,
                            maxWidth = maxWidth,
                            gradient = gradient,
                            solidColor = solidColor,
                            textColor = textColor,
                            timestampColor = timestampColor,
                            formattedTime = message.formattedTime,
                            isSelf = isSelf,
                            borderStroke = borderStroke,
                            textMeasurer = textMeasurer,
                            density = density,
                            modifier = Modifier
                        )
                    }
                }
            },
            deletedContent = {
                TextMessageBubble(
                    message = message,
                    shape = shape,
                    maxWidth = maxWidth,
                    gradient = gradient,
                    solidColor = solidColor,
                    textColor = theme.textSecondary.copy(alpha = 0.85f),
                    timestampColor = timestampColor,
                    formattedTime = message.formattedTime,
                    isSelf = isSelf,
                    borderStroke = borderStroke,
                    textMeasurer = textMeasurer,
                    density = density,
                    displayText = "This message was deleted",
                    isDeletedPlaceholder = true,
                    modifier = Modifier
                )
            }
        )
    }
}

@Composable
private fun DeletedForAllTwoPhaseContent(
    messageId: String,
    isDeletedForAll: Boolean,
    isIncoming: Boolean,
    onReceiverHaptic: () -> Unit,
    modifier: Modifier = Modifier,
    normalContent: @Composable () -> Unit,
    deletedContent: @Composable () -> Unit
) {
    // phase: 0 = normal, 1 = removing, 2 = deleted
    var phase by remember(messageId) { mutableIntStateOf(if (isDeletedForAll) 2 else 0) }
    var lastDeleted by remember(messageId) { mutableStateOf(isDeletedForAll) }

    val alpha = remember(messageId) { Animatable(1f) }
    val scale = remember(messageId) { Animatable(1f) }

    LaunchedEffect(isDeletedForAll) {
        if (!lastDeleted && isDeletedForAll) {
            // Phase 1 – Removal (scale down + fade to ~60%)
            phase = 1
            alpha.snapTo(1f)
            scale.snapTo(1f)
            scale.animateTo(0.96f, animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
            alpha.animateTo(0.6f, animationSpec = tween(durationMillis = 140, easing = LinearEasing))

            // Phase 2 – Replacement
            phase = 2
            if (isIncoming) {
                onReceiverHaptic()
            }
            // Start the new content from a subtle, calm state.
            scale.snapTo(0.96f)
            alpha.snapTo(0f)
            scale.animateTo(1f, animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 140, easing = LinearEasing))
        } else {
            // No animation on initial composition / restart / scroll.
            phase = if (isDeletedForAll) 2 else 0
            alpha.snapTo(1f)
            scale.snapTo(1f)
        }
        lastDeleted = isDeletedForAll
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            if (phase == 2 && isDeletedForAll) {
                deletedContent()
            } else {
                normalContent()
            }
        }
    }
}

/**
 * Static helper to generate bubble shapes without remember overhead
 */
private object BubbleShapeCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Triple<Boolean, BubbleGroupPosition, Dp>, RoundedCornerShape>()

    fun getShape(isSelf: Boolean, position: BubbleGroupPosition, radius: Dp): RoundedCornerShape {
        val key = Triple(isSelf, position, radius)
        return cache.getOrPut(key) {
            createShape(isSelf, position, radius)
        }
    }

    private fun createShape(isSelf: Boolean, position: BubbleGroupPosition, radius: Dp): RoundedCornerShape {
        val small = 3.dp
        return if (isSelf) {
            when (position) {
                BubbleGroupPosition.SINGLE -> RoundedCornerShape(radius)
                BubbleGroupPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = small, bottomStart = radius)
                BubbleGroupPosition.MIDDLE -> RoundedCornerShape(topStart = radius, topEnd = small, bottomEnd = small, bottomStart = radius)
                BubbleGroupPosition.BOTTOM -> RoundedCornerShape(topStart = radius, topEnd = small, bottomEnd = radius, bottomStart = radius)
            }
        } else {
            when (position) {
                BubbleGroupPosition.SINGLE -> RoundedCornerShape(radius)
                BubbleGroupPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = radius, bottomStart = small)
                BubbleGroupPosition.MIDDLE -> RoundedCornerShape(topStart = small, topEnd = radius, bottomEnd = radius, bottomStart = small)
                BubbleGroupPosition.BOTTOM -> RoundedCornerShape(topStart = small, topEnd = radius, bottomEnd = radius, bottomStart = radius)
            }
        }
    }
}

/**
 * RepliedMessageBubble - Handles layout for messages with reply preview
 * 
 * CRITICAL: Bubble width must be driven by the MAXIMUM width needed between:
 * 1. Reply preview content
 * 2. Main message text
 */
@Composable
private fun RepliedMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    textColor: Color,
    timestampColor: Color,
    borderStroke: BorderStroke,
    isSelf: Boolean,
    currentUserPhone: String,
    otherUsername: String,
    textMeasurer: TextMeasurer,
    density: Density,
    onReplyClick: (String) -> Unit,
    replyPreviewWidth: Dp? = null,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val accentColor = if (theme.isDark) theme.actionPrimary else theme.textLink
    val isReplyToSelf = message.replyToSenderId == currentUserPhone
    
    val senderText = if (isReplyToSelf) "You" else otherUsername
    val replyDisplayText = when (message.replyToType) {
        MessageType.IMAGE -> "📷 Photo"
        MessageType.VIDEO -> "🎥 Video"
        MessageType.AUDIO -> "🎤 Audio"
        MessageType.MEDIA_GROUP -> "📷 Album"
        else -> message.replyToText ?: "Message"
    }
    
    val previewBackground = if (theme.isDark) {
        if (isReplyToSelf) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.11f)
    }
    
    // Calculate required widths for both main text and preview text
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 20.sp)
    val previewTextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
    val previewSenderStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    
    val mainTextLayout = textMeasurer.measure(
        text = message.text,
        style = textStyle,
        constraints = Constraints(maxWidth = with(density) { maxWidth.toPx().toInt() })
    )
    
    val previewTextLayout = textMeasurer.measure(
        text = replyDisplayText,
        style = previewTextStyle,
        constraints = Constraints(maxWidth = with(density) { (maxWidth - 80.dp).toPx().toInt() })
    )
    
    val previewSenderLayout = textMeasurer.measure(
        text = senderText,
        style = previewSenderStyle
    )
    
    // Calculate actual widths needed (including padding and accents)
    // Main text: text width + bubble padding (24dp = 12dp * 2)
    val mainTextWidth = with(density) { mainTextLayout.size.width.toDp() + 24.dp }
    
    // Preview: max(sender, content) + accent bar (3dp) + spacer (8dp) + padding (16dp = 8dp * 2) + bubble padding (24dp)
    val previewContentWidth = maxOf(
        with(density) { previewSenderLayout.size.width.toDp() },
        with(density) { previewTextLayout.size.width.toDp() }
    ) + 3.dp + 8.dp + 16.dp + 24.dp
    
    // Timestamp/status width estimate: ~80dp for timestamp + status icon
    val metadataWidth = 80.dp
    
    // Use the larger of main text or preview, ensure it fits timestamp, with reasonable min/max
    val calculatedWidth = maxOf(
        mainTextWidth + metadataWidth,
        previewContentWidth
    ).coerceIn(140.dp, maxWidth)
    
    Surface(
        shape = shape,
        color = solidColor,
        modifier = modifier
            .width(calculatedWidth)
            .then(if (gradient != null) Modifier.background(gradient, shape) else Modifier)
            .border(borderStroke, shape)
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // Reply Preview Box (Forces width to match bubble)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clickable { message.replyToMessageId?.let(onReplyClick) },
                shape = RoundedCornerShape(8.dp),
                color = previewBackground
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(38.dp)
                            .background(accentColor, RoundedCornerShape(2.dp))
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = senderText,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = replyDisplayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    color = textColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                val timeText = if (message.isEdited) "${message.formattedTime} • edited" else message.formattedTime
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                    color = timestampColor.copy(alpha = 0.7f)
                )
                if (isSelf) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val statusIcon = when (message.status) {
                        MessageStatus.SENDING -> R.drawable.ic_clock
                        MessageStatus.SENT -> R.drawable.ic_check
                        MessageStatus.DELIVERED, MessageStatus.READ -> R.drawable.ic_double_check
                        MessageStatus.FAILED -> R.drawable.ic_error_outline
                        else -> R.drawable.ic_info
                    }
                    val statusTint = if (message.status == MessageStatus.READ) theme.indicatorMessageStatus else timestampColor
                    Icon(
                        painter = painterResource(id = statusIcon),
                        contentDescription = null,
                        tint = statusTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun getBubbleShape(isSelf: Boolean, position: BubbleGroupPosition, radius: Dp): RoundedCornerShape {
    return BubbleShapeCache.getShape(isSelf, position, radius)
}

/**
 * TextLayoutCache - Global cache for text measurements to eliminate redundant textMeasurer.measure() calls
 * 
 * CRITICAL PERFORMANCE OPTIMIZATION:
 * The textMeasurer.measure() call is expensive (5-15ms per call) and is the main source of scroll jank.
 * By caching results globally (not per-composition), we ensure:
 * 1. Text is measured ONCE per unique message/style combination
 * 2. Scroll operations re-use cached measurements instantly (<0.1ms)
 * 3. Memory is bounded via LRU eviction (max 500 entries ~= 100 messages on screen + history)
 * 
 * Keys are designed to capture all factors that affect measurement:
 * - messageId: Unique message identifier
 * - textHash: Hash of actual text content (handles edits)
 * - maxWidth: Constraint width for wrapping
 * - styleKey: Font size + line height hash
 */
private object TextLayoutCache {
    private const val MAX_CACHE_SIZE = 1000
    
    // Performance metrics
    private var cacheHits = 0
    private var cacheMisses = 0
    private var lastLogTime = 0L
    
    // Use LinkedHashMap with access-order for LRU behavior
    private val cache = object : LinkedHashMap<TextLayoutKey, TextLayoutResult>(MAX_CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextLayoutKey, TextLayoutResult>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    
    private val lock = Any()
    
    data class TextLayoutKey(
        val messageId: String,
        val textHash: Int,
        val maxWidthPx: Int,
        val styleKey: Int // Hash of fontSize + lineHeight
    )
    
    fun getOrMeasure(
        messageId: String,
        text: String,
        maxWidthPx: Int,
        styleKey: Int,
        measurer: () -> TextLayoutResult
    ): TextLayoutResult {
        val key = TextLayoutKey(messageId, text.hashCode(), maxWidthPx, styleKey)
        
        // Fast path: check cache without lock first (read-only)
        synchronized(lock) {
            cache[key]?.let { 
                cacheHits++
                logCacheStats()
                return it 
            }
        }
        
        // Cache MISS - measure and cache
        cacheMisses++
        val result = measurer()
        synchronized(lock) {
            cache[key] = result
        }
        logCacheStats()
        return result
    }
    
    private fun logCacheStats() {
        if (!PERF_DEBUG_ENABLED) return
        
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 2000) { // Log every 2 seconds
            lastLogTime = now
            val total = cacheHits + cacheMisses
            if (total > 0) {
                val hitRate = (cacheHits * 100.0 / total)
                synchronized(lock) {
                    Log.i(PERF_TAG, "💾 TextLayoutCache: ${cache.size} entries, Hit rate: ${String.format("%.1f", hitRate)}% ($cacheHits hits / $cacheMisses misses)")
                }
            }
        }
    }
    
    fun resetStats() {
        cacheHits = 0
        cacheMisses = 0
        lastLogTime = 0L
    }
    
    // Public method to log stats on demand
    fun logStats() {
        if (!PERF_DEBUG_ENABLED) return
        val total = cacheHits + cacheMisses
        if (total > 0) {
            val hitRate = (cacheHits * 100.0 / total)
            synchronized(lock) {
                Log.i(PERF_TAG, "💾 TextLayoutCache: ${cache.size} entries, Hit rate: ${String.format("%.1f", hitRate)}% ($cacheHits hits / $cacheMisses misses)")
            }
        }
    }
    
    fun size(): Int = synchronized(lock) { cache.size }
    

    /**
     * Pre-warm the cache with messages that will be displayed.
     * Call this from a background thread during chat initialization.
     */
    fun preWarm(
        messages: List<Message>,
        textMeasurer: TextMeasurer,
        textStyle: TextStyle,
        timeStyle: TextStyle,
        maxWidthPx: Int
    ) {
        val textStyleKey = (textStyle.fontSize.value.toInt() * 31) + textStyle.lineHeight.value.toInt()
        val timeStyleKey = timeStyle.fontSize.value.toInt()
        
        messages.forEach { message ->
            if (message.type == MessageType.TEXT) {
                val displayText = if (message.isDeletedForAll) "This message was deleted" else message.text
                val textKey = TextLayoutKey(message.id, displayText.hashCode(), maxWidthPx, textStyleKey)
                
                synchronized(lock) {
                    if (!cache.containsKey(textKey)) {
                        try {
                            cache[textKey] = textMeasurer.measure(
                                text = displayText,
                                style = textStyle,
                                constraints = Constraints(maxWidth = maxWidthPx)
                            )
                        } catch (e: Exception) {
                            // Ignore measurement errors during pre-warm
                        }
                    }
                }
                
                // Also cache the time layout - use same key format as TextMessageBubble
                val timeText = if (message.isEdited) "${message.formattedTime} • edited" else message.formattedTime
                val timeKey = TextLayoutKey("${message.id}_time", timeText.hashCode(), Int.MAX_VALUE, timeStyleKey)
                synchronized(lock) {
                    if (!cache.containsKey(timeKey)) {
                        try {
                            cache[timeKey] = textMeasurer.measure(
                                text = timeText,
                                style = timeStyle
                            )
                        } catch (e: Exception) {
                            // Ignore measurement errors during pre-warm
                        }
                    }
                }
            }
        }
    }
    
    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }
    
    /**
     * Pre-warm reply preview text layouts in background to avoid main thread blocking.
     * CRITICAL: Must be called on background thread (Dispatchers.Default) during warmup.
     */
    fun preWarmReplyPreviews(
        messages: List<Message>,
        textMeasurer: TextMeasurer,
        senderStyle: TextStyle,
        messageStyle: TextStyle,
        maxWidthPx: Float,
        paddingPx: Float,
        accentWidthPx: Float,
        accentSpacingPx: Float,
        currentUserPhone: String,
        otherUsername: String
    ) {
        val availableWidthPx = maxWidthPx - (paddingPx * 2) - accentWidthPx - accentSpacingPx
        val constraints = Constraints(maxWidth = availableWidthPx.toInt().coerceAtLeast(0))
        
        messages.forEach { message ->
            val key = "${message.id}-reply"
            
            // Skip if already cached
            synchronized(lock) {
                if (replyCache.containsKey(key)) return@forEach
            }
            
            try {
                // Generate reply text using same logic as rendering
                val isReplyToSelf = message.replyToSenderId == currentUserPhone
                val senderText = if (isReplyToSelf) "You" else otherUsername
                val messageText = when (message.replyToType) {
                    MessageType.IMAGE -> "📷 Photo"
                    MessageType.VIDEO -> "🎥 Video"
                    MessageType.AUDIO -> "🎤 Audio"
                    MessageType.MEDIA_GROUP -> "📷 Album"
                    else -> message.replyToText ?: "Message"
                }
                
                // Measure layouts
                val senderLayout = textMeasurer.measure(
                    text = senderText,
                    style = senderStyle,
                    constraints = constraints,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val messageLayout = textMeasurer.measure(
                    text = messageText,
                    style = messageStyle,
                    constraints = constraints,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                val innerContentWidthPx = maxOf(senderLayout.size.width, messageLayout.size.width).toFloat() +
                    (paddingPx * 2) + accentWidthPx + accentSpacingPx
                
                val result = ReplyPreviewLayouts(senderLayout, messageLayout, innerContentWidthPx)
                
                // Cache result
                synchronized(lock) {
                    replyCache[key] = result
                }
            } catch (e: Exception) {
                // Ignore measurement errors during pre-warm
            }
        }
    }
    
    /**
     * Measure reply preview text layouts and cache them.
     * Returns cached layouts to avoid heavy text measurement in message rows.
     */
    data class ReplyPreviewLayouts(
        val senderLayout: TextLayoutResult,
        val messageLayout: TextLayoutResult,
        val minWidth: Float
    )
    
    private val replyCache = mutableMapOf<String, ReplyPreviewLayouts>()
    
    fun getOrMeasureReplyPreview(
        messageId: String,
        senderText: String,
        messageText: String,
        senderStyle: TextStyle,
        messageStyle: TextStyle,
        maxWidthPx: Float,
        paddingPx: Float,
        accentWidthPx: Float,
        accentSpacingPx: Float,
        textMeasurer: TextMeasurer
    ): ReplyPreviewLayouts {
        val key = "$messageId-reply"
        
        synchronized(lock) {
            replyCache[key]?.let {
                cacheHits++
                return it
            }
        }
        
        cacheMisses++
        
        val availableWidthPx = maxWidthPx - (paddingPx * 2) - accentWidthPx - accentSpacingPx
        val constraints = Constraints(maxWidth = availableWidthPx.toInt().coerceAtLeast(0))
        
        val senderLayout = textMeasurer.measure(
            text = senderText,
            style = senderStyle,
            constraints = constraints,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val messageLayout = textMeasurer.measure(
            text = messageText,
            style = messageStyle,
            constraints = constraints,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        
        val innerContentWidthPx = maxOf(senderLayout.size.width, messageLayout.size.width).toFloat() +
            (paddingPx * 2) + accentWidthPx + accentSpacingPx
        
        val result = ReplyPreviewLayouts(senderLayout, messageLayout, innerContentWidthPx)
        
        synchronized(lock) {
            replyCache[key] = result
        }
        
        return result
    }
}

/**
 * EditAnimationState - Manages physics-based edit animation state
 * Uses spring animations for premium, tactile feel
 * OPTIMIZED: Sequential animation phases for smooth, jank-free motion
 */
@Stable
private class EditAnimationState {
    // Translation for shoot-out effect
    val translationX = Animatable(0f)
    
    // Scale for pull-back compression
    val scaleX = Animatable(1f)
    val scaleY = Animatable(1f)
    
    // Arrow morph progress (0 = bubble, 1 = arrow)
    val arrowMorphProgress = Animatable(0f)
    
    // Track if animation is active
    val isAnimating = mutableStateOf(false)
    
    suspend fun startAnimation() {
        isAnimating.value = true
        
        try {
            // Phase 1: Pull-back with morph (tension building) - PARALLEL for snappier feel
            kotlinx.coroutines.coroutineScope {
                launch {
                    translationX.animateTo(
                        targetValue = -20f, // Reduced pull-back distance
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = 400f
                        )
                    )
                }
                launch {
                    scaleX.animateTo(
                        targetValue = 0.94f, // Less extreme compression
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = 400f
                        )
                    )
                }
                launch {
                    scaleY.animateTo(
                        targetValue = 0.96f,
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = 400f
                        )
                    )
                }
                launch {
                    arrowMorphProgress.animateTo(
                        targetValue = 0.7f,
                        animationSpec = tween(
                            durationMillis = 150,
                            easing = FastOutSlowInEasing
                        )
                    )
                }

            }

            // Brief hold at compression peak
            delay(30)
            
            // Phase 2: Complete arrow morph BEFORE shoot-out for cleaner visual
            arrowMorphProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 80,
                    easing = LinearEasing
                )
            )
            
            // Phase 3: Shoot-out (explosive spring) - SEQUENTIAL for smoothness
            translationX.animateTo(
                targetValue = 1400f, // Shoot far off-screen
                animationSpec = spring(
                    dampingRatio = 0.65f, // Less bouncy for smoother exit
                    stiffness = 500f, // High stiffness for fast exit
                    visibilityThreshold = 1f
                )
            )
            
            // Phase 4: Instant repositioning (new bubble enters from opposite side)
            translationX.snapTo(-1400f)
            scaleX.snapTo(1.05f) // Slight overshoot for entry
            scaleY.snapTo(1f)
            arrowMorphProgress.snapTo(1f) // Enter as arrow
            
            // Phase 5: Settle and morph back - PARALLEL for natural deceleration
            kotlinx.coroutines.coroutineScope {
                launch {
                    translationX.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.75f,
                            stiffness = 300f,
                            visibilityThreshold = 0.5f
                        )
                    )
                }
                launch {
                    scaleX.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.75f,
                            stiffness = 300f
                        )
                    )
                }
                launch {
                    // Delay morph-back slightly so it happens as bubble settles
                    delay(100)
                    arrowMorphProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = 350f
                        )
                    )
                }
            }
        } finally {
            isAnimating.value = false
        }
    }
}

/**
 * Creates an arrow-shaped bubble for edit animation
 * Morphs between normal bubble (progress=0) and arrow (progress=1)
 */
private fun getArrowShape(isSelf: Boolean, morphProgress: Float, baseRadius: Dp): RoundedCornerShape {
    // Arrow gets more pointed as progress increases
    val arrowTip = 3.dp * (1f - morphProgress) + 0.dp * morphProgress
    val arrowBase = baseRadius * (1f - morphProgress * 0.6f)
    
    return if (isSelf) {
        // Outgoing: Arrow points right
        RoundedCornerShape(
            topStart = arrowBase,
            topEnd = arrowTip,
            bottomEnd = arrowTip,
            bottomStart = arrowBase
        )
    } else {
        // Incoming: Arrow points left
        RoundedCornerShape(
            topStart = arrowTip,
            topEnd = arrowBase,
            bottomEnd = arrowBase,
            bottomStart = arrowTip
        )
    }
}

/**
 * QuotedReplyPreview - Renders quoted message preview inside message bubbles
 * WhatsApp-style compact quoted preview with accent line
 */
@Composable
fun QuotedReplyPreview(
    replyToText: String?,
    replyToType: MessageType?,
    replyToSenderId: String?,
    isSelf: Boolean,
    currentUserPhone: String,
    otherUsername: String,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val theme = glyphTheme
    val accentColor = if (theme.isDark) theme.actionPrimary else theme.textLink
    val isReplyToSelf = replyToSenderId == currentUserPhone
    
    val senderText = if (isReplyToSelf) "You" else otherUsername
    val messageText = when(replyToType) {
        MessageType.IMAGE -> "📷 Photo"
        MessageType.VIDEO -> "🎥 Video"
        MessageType.AUDIO -> "🎤 Audio"
        MessageType.MEDIA_GROUP -> "📷 Album"
        else -> replyToText ?: "Message"
    }

    val senderStyle = MaterialTheme.typography.labelSmall.copy(
        color = accentColor,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp
    )
    
    val messageStyle = MaterialTheme.typography.bodySmall.copy(
        color = if (isSelf) theme.bubbleOutgoingText.copy(alpha = 0.8f) else theme.bubbleIncomingText.copy(alpha = 0.8f),
        fontSize = 13.sp
    )

    val padding = with(density) { 8.dp.toPx() }
    val accentWidth = with(density) { 3.dp.toPx() }
    val accentSpacing = with(density) { 8.dp.toPx() }
    
    // CRITICAL: Use maxLines=2 for proper text truncation with ellipsis
    val availableWidth = with(density) { maxWidth.toPx() } - (padding * 2) - accentWidth - accentSpacing
    val availableWidthInt = availableWidth.toInt()
    
    // Measure sender text (single line)
    val senderLayout = textMeasurer.measure(
        text = senderText,
        style = senderStyle,
        constraints = Constraints(maxWidth = availableWidthInt),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1
    )
    
    // CRITICAL: Measure message text with maxLines=2 and ellipsis
    val messageLayout = textMeasurer.measure(
        text = messageText,
        style = messageStyle,
        constraints = Constraints(maxWidth = availableWidthInt),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2
    )
    
    val height = maxOf(
        senderLayout.size.height + messageLayout.size.height + with(density) { 2.dp.toPx() },
        with(density) { 38.dp.toPx() }
    ) + (padding * 2)
    
    // Width is determined by parent container, not forced here
    val contentWidth = maxOf(senderLayout.size.width, messageLayout.size.width) + 
                      (padding * 2) + accentWidth + accentSpacing

    // Background needs a bit more contrast on light/pastel themes
    val previewBackground = if (theme.isDark) {
        if (isReplyToSelf) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)
    } else {
        // Slightly darker for better visibility on light + pastel sky
        Color.Black.copy(alpha = 0.11f)
    }
    
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { height.toDp() })
            .clip(RoundedCornerShape(8.dp))
            .background(previewBackground)
            .clickable(onClick = onClick)
            .drawBehind {
                // Vertical accent line
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(padding, padding),
                    size = Size(accentWidth, size.height - (padding * 2)),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                val textX = padding + accentWidth + accentSpacing
                drawText(
                    textLayoutResult = senderLayout,
                    topLeft = Offset(textX, padding)
                )

                drawText(
                    textLayoutResult = messageLayout,
                    topLeft = Offset(textX, padding + senderLayout.size.height + 2.dp.toPx())
                )
            }
    )
}

@Composable
fun MediaMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    timestampColor: Color,
    borderStroke: BorderStroke,
    context: Context,
    onMediaClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    mediaProgress: Float? = null,
    textMeasurer: TextMeasurer,
    density: Density,
    modifier: Modifier = Modifier
) {
    // 1. Optimized Display Model (Local first, then remote)
    val displayModel = message.displayModel

    // 2. Stable Aspect Ratio Calculation (Uses cached value from Message model)
    val aspectRatio = message.aspectRatio

    val isDownloading = message.status == MessageStatus.DOWNLOADING
    val isUploading = message.status == MessageStatus.SENDING
    val isPendingDownload = message.status == MessageStatus.PENDING_DOWNLOAD || message.status == MessageStatus.DOWNLOAD_FAILED

    // Pre-measure text for overlays to avoid nodes
    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
    
    // PERFORMANCE: Use global cache for media message time layouts
    val timeLayoutResult = TextLayoutCache.getOrMeasure(
        messageId = "${message.id}_media_time",
        text = formattedTime,
        maxWidthPx = Int.MAX_VALUE,
        styleKey = 12 // fontSize 12sp
    ) {
        textMeasurer.measure(formattedTime, timeStyle)
    }
    
    val fileSizeText = if ((message.fileSize ?: 0L) > 0) FormatUtils.formatFileSize(message.fileSize ?: 0L) else null
    val fileSizeLayoutResult = if (fileSizeText != null) {
        TextLayoutCache.getOrMeasure(
            messageId = "${message.id}_filesize",
            text = fileSizeText,
            maxWidthPx = Int.MAX_VALUE,
            styleKey = 12
        ) {
            textMeasurer.measure(fileSizeText, timeStyle)
        }
    } else null

    // CRITICAL: Status icon selection - Compose auto-optimizes via stable keys
    val statusIcon = if (isSelf) {
        when (message.status) {
            MessageStatus.SENDING -> R.drawable.ic_clock
            MessageStatus.SENT -> R.drawable.ic_check
            MessageStatus.DELIVERED, MessageStatus.READ -> R.drawable.ic_double_check
            MessageStatus.FAILED -> R.drawable.ic_error_outline
            else -> R.drawable.ic_info
        }
    } else null
    
    val statusTint = if (isSelf && message.status == MessageStatus.READ) glyphTheme.indicatorMessageStatus else Color.White
    val statusPainter = if (statusIcon != null) painterResource(id = statusIcon) else null

    Box(
        modifier = modifier
            .width(maxWidth)
            .aspectRatio(aspectRatio)
            .drawWithCache {
                val path = androidx.compose.ui.graphics.Path().apply {
                    val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)
                    when (outline) {
                        is Outline.Rectangle -> addRect(outline.rect)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Generic -> addPath(outline.path)
                    }
                }
                onDrawWithContent {
                    // 1. Draw Background (Visible if image is transparent or loading)
                    if (gradient != null) {
                        drawPath(path, brush = gradient)
                    } else {
                        drawPath(path, color = solidColor)
                    }

                    clipPath(path) {
                        this@onDrawWithContent.drawContent()
                        
                        // DRAW OVERLAYS DIRECTLY ON CANVAS (Saves ~10 nodes)
                        val padding = 12.dp.toPx()
                        val pillPaddingH = 8.dp.toPx()
                        val pillPaddingV = 4.dp.toPx()
                        val pillRadius = 12.dp.toPx()
                        val pillColor = Color.Black.copy(alpha = 0.5f)
                        
                        // 1. File Size Pill (Bottom Start)
                        fileSizeLayoutResult?.let { res ->
                            val pillWidth = res.size.width + (pillPaddingH * 2)
                            val pillHeight = res.size.height + (pillPaddingV * 2)
                            val x = padding
                            val y = size.height - padding - pillHeight
                            
                            drawRoundRect(
                                color = pillColor,
                                topLeft = Offset(x, y),
                                size = Size(pillWidth, pillHeight),
                                cornerRadius = CornerRadius(pillRadius, pillRadius)
                            )
                            drawText(
                                textLayoutResult = res,
                                topLeft = Offset(x + pillPaddingH, y + pillPaddingV)
                            )
                        }
                        
                        // 2. Timestamp Pill (Bottom End)
                        val iconSize = 24.dp.toPx()
                        val iconSpacing = 4.dp.toPx()
                        val statusAreaWidth = timeLayoutResult.size.width.toFloat() + (if (statusPainter != null) iconSize + iconSpacing else 0f)
                        
                        // Make pill height accommodate larger icon
                        val timePillHeight = maxOf(timeLayoutResult.size.height + (pillPaddingV * 2), iconSize + (pillPaddingV * 2))
                        val timePillWidth = statusAreaWidth + (pillPaddingH * 2)
                        val tx = size.width - padding - timePillWidth
                        val ty = size.height - padding - timePillHeight
                        
                        drawRoundRect(
                            color = pillColor,
                            topLeft = Offset(tx, ty),
                            size = Size(timePillWidth, timePillHeight),
                            cornerRadius = CornerRadius(pillRadius, pillRadius)
                        )
                        
                        drawText(
                            textLayoutResult = timeLayoutResult,
                            topLeft = Offset(tx + pillPaddingH, ty + pillPaddingV + 4.dp.toPx())
                        )
                        
                        if (statusPainter != null) {
                            val ix = tx + pillPaddingH + timeLayoutResult.size.width + iconSpacing
                            val iy = ty + pillPaddingV + 4.dp.toPx() + (timeLayoutResult.size.height - iconSize) / 2 + 1.dp.toPx()
                            
                            translate(ix, iy) {
                                with(statusPainter) {
                                    draw(
                                        size = Size(iconSize, iconSize),
                                        alpha = 1f,
                                        colorFilter = ColorFilter.tint(statusTint)
                                    )
                                }
                            }
                        }
                    }
                    
                    // 3. Draw Border (Outside clipPath to ensure it's visible)
                    val strokeWidthPx = borderStroke.width.toPx()
                    if (strokeWidthPx > 0.1f) {
                        drawPath(
                            path = path,
                            brush = borderStroke.brush,
                            style = Stroke(width = strokeWidthPx)
                        )
                    }
                }
            }
    ) {
        // Edge-to-edge media
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.05f))
        ) {
            // Use AsyncImage directly - it handles loading states efficiently
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(displayModel)
                    .crossfade(false) // Disable crossfade for performance
                    .size(800) // Increased size for better quality in larger bubbles
                    .allowHardware(true) // CRITICAL: Enable hardware bitmaps for GPU rendering
                    .memoryCacheKey("${message.id}_thumb")
                    .diskCacheKey("${message.id}_thumb")
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_placeholder)
                    .build(),
                contentDescription = null,
                imageLoader = context.imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Status Overlays (Progress / Download Button) - Keep these as nodes for interactivity
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isDownloading || isUploading) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (mediaProgress != null && mediaProgress > 0) mediaProgress / 100f else 0f },
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                } else if (isPendingDownload) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onDownloadClick() }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download_circle),
                            contentDescription = "Download",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else if (message.type == MessageType.VIDEO) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play_circle),
                            contentDescription = "Play Video",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TextMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    textColor: Color,
    timestampColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    borderStroke: BorderStroke,
    textMeasurer: TextMeasurer,
    density: Density,
    currentUserPhone: String = "",
    minWidth: Dp = 0.dp,
    displayText: String = message.text,
    isDeletedPlaceholder: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = textColor,
        fontStyle = if (isDeletedPlaceholder) FontStyle.Italic else FontStyle.Normal
    )
    
    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        color = timestampColor.copy(alpha = 0.7f),
        fontSize = 13.sp
    )
    
    val paddingH = with(density) { 12.dp.toPx() }
    val maxWidthPx = with(density) { maxWidth.toPx() }
    val textMaxWidth = (maxWidthPx - (paddingH * 2)).coerceAtLeast(0f)
    val textMaxWidthInt = textMaxWidth.toInt()
    
    // PERFORMANCE: Use global TextLayoutCache instead of remember
    // This eliminates redundant measurements during scroll - cache persists across compositions
    val textStyleKey = (16 * 31) + 20 // fontSize (16sp) * 31 + lineHeight (20sp)
    val textLayoutResult = TextLayoutCache.getOrMeasure(
        messageId = message.id,
        text = displayText,
        maxWidthPx = textMaxWidthInt,
        styleKey = textStyleKey
    ) {
        textMeasurer.measure(
            text = displayText,
            style = textStyle,
            constraints = Constraints(maxWidth = textMaxWidthInt)
        )
    }

    val timeText = if (!isDeletedPlaceholder && message.isEdited) "$formattedTime • edited" else formattedTime
    val timeStyleKey = 13 // fontSize (13sp)
    val timeLayoutResult = TextLayoutCache.getOrMeasure(
        messageId = "${message.id}_time",
        text = timeText,
        maxWidthPx = Int.MAX_VALUE,
        styleKey = timeStyleKey
    ) {
        textMeasurer.measure(
            text = timeText,
            style = timeStyle
        )
    }

    // CRITICAL: Status icon selection - Compose auto-optimizes via stable keys
    val statusIcon = if (isSelf) {
        when (message.status) {
            MessageStatus.SENDING -> R.drawable.ic_clock
            MessageStatus.SENT -> R.drawable.ic_check
            MessageStatus.DELIVERED, MessageStatus.READ -> R.drawable.ic_double_check
            MessageStatus.FAILED -> R.drawable.ic_error_outline
            else -> R.drawable.ic_info
        }
    } else null
    
    val statusTint = if (isSelf && message.status == MessageStatus.READ) glyphTheme.indicatorMessageStatus else timestampColor
    val statusPainter = if (statusIcon != null) painterResource(id = statusIcon) else null

    // Calculate dimensions
    val paddingV = with(density) { 8.dp.toPx() }
    val iconSize = with(density) { 22.dp.toPx() }
    val iconSpacing = with(density) { 4.dp.toPx() }
    
    val lastLineIndex = textLayoutResult.lineCount - 1
    val lastLineWidth = textLayoutResult.getLineRight(lastLineIndex)
    val lineCount = textLayoutResult.lineCount
    val longestLineWidth = textLayoutResult.size.width.toFloat()
    
    val statusAreaWidth = timeLayoutResult.size.width.toFloat() + (if (statusPainter != null) iconSize + iconSpacing else 0f)
    val horizontalGap = with(density) { 12.dp.toPx() } // Gap between text and timestamp
    
    val canFitOnLastLine = lastLineWidth + statusAreaWidth + horizontalGap < textMaxWidth

    val contentWidth = if (canFitOnLastLine) {
        maxOf(longestLineWidth, lastLineWidth + horizontalGap + statusAreaWidth)
    } else {
        maxOf(longestLineWidth, statusAreaWidth)
    }

    val contentHeight = if (canFitOnLastLine) {
        textLayoutResult.size.height.toFloat()
    } else {
        textLayoutResult.size.height.toFloat() + timeLayoutResult.size.height.toFloat() + 4f
    }

    val computedBubbleWidth = contentWidth + (paddingH * 2f)
    val minWidthPx = with(density) { 140.dp.toPx() }
    val bubbleWidth = maxOf(computedBubbleWidth, minWidthPx)
    val bubbleHeight = contentHeight + (paddingV * 2f)

    // ULTRA-FLATTENED: Single Spacer with drawWithCache
    // This reduces node count from ~8 to 1 per text message and caches drawing objects
    Spacer(
        modifier = modifier
            .size(
                width = with(density) { bubbleWidth.toDp() },
                height = with(density) { bubbleHeight.toDp() }
            )
            .drawWithCache {
                val path = androidx.compose.ui.graphics.Path().apply {
                    val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)
                    when (outline) {
                        is Outline.Rectangle -> addRect(outline.rect)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Generic -> addPath(outline.path)
                    }
                }
                
                onDrawBehind {
                    // 1. Draw Background
                    if (gradient != null) {
                        drawPath(path, brush = gradient)
                    } else {
                        drawPath(path, color = solidColor)
                    }
                    
                    // 2. Draw Border
                    val strokeWidthPx = borderStroke.width.toPx()
                    if (strokeWidthPx > 0.5f) {
                        drawPath(
                            path = path,
                            brush = borderStroke.brush,
                            style = Stroke(width = strokeWidthPx)
                        )
                    }
                    
                    // 3. Draw Message Text
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(paddingH, paddingV)
                    )
                    
                    // 4. Draw Status Area (Time + Icon)
                    val statusX = size.width - paddingH - statusAreaWidth
                    val statusY = size.height - paddingV - timeLayoutResult.size.height.toFloat() + 4.dp.toPx()
                    
                    drawText(
                        textLayoutResult = timeLayoutResult,
                        topLeft = Offset(statusX, statusY)
                    )
                    
                    if (statusPainter != null) {
                        val iconX = size.width - paddingH - iconSize
                        val iconY = size.height - paddingV - iconSize + 1.dp.toPx() + 4.dp.toPx()
                        
                        translate(iconX, iconY) {
                            with(statusPainter) {
                                draw(
                                    size = Size(iconSize, iconSize),
                                    alpha = 1f,
                                    colorFilter = ColorFilter.tint(statusTint)
                                )
                            }
                        }
                    }
                }
            }
    )
}

@Composable
fun EditPreview(
    message: Message,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val accentColor = theme.actionPrimary
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceInput.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Edit icon indicator
            Icon(
                painter = painterResource(id = R.drawable.ic_edit),
                contentDescription = "Edit",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "✏️ Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Cancel Edit",
                    tint = theme.textPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ReplyPreview(
    message: Message,
    username: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    // Use textLink for light themes to ensure readability, actionPrimary for dark theme
    val accentColor = if (theme.isDark) theme.actionPrimary else theme.textLink
    
    // Use contrasting background for better text visibility
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceInput.copy(alpha = if (theme.isDark) 0.60f else 0.78f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isIncoming) "Replying to $username" else "Replying to yourself",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when(message.type) {
                        MessageType.IMAGE -> "📷 Photo"
                        MessageType.VIDEO -> "🎥 Video"
                        MessageType.AUDIO -> "🎤 Audio"
                        MessageType.MEDIA_GROUP -> "📷 Album"
                        else -> message.text ?: ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary, 
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Cancel Reply",
                    tint = theme.textPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        // Separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.borderInput.copy(alpha = 0.3f))
        )
    }
}

@Composable
fun ChatInput(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    replyToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    otherUsername: String = "",
    editingMessage: Message? = null,
    onCancelEdit: () -> Unit = {},
    onMicDown: () -> Unit = {},
    onMicUp: () -> Unit = {},
    onMicLocked: () -> Unit = {},
    isRecording: Boolean = false,
    isLocked: Boolean = false,
    recordingDuration: String = "0:00",
    recordingAmplitude: Int = 0,
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    imeBottomPadding: Dp = 0.dp
) {
    // Stabilize state to prevent unnecessary recompositions
    val isTyping = remember(text) { text.isNotEmpty() }
    
    // Track closing animation state separately to allow smooth exit
    var isClosingReply by remember { mutableStateOf(false) }
    var isClosingEdit by remember { mutableStateOf(false) }
    
    // When replyToMessage becomes null, reset the closing flag
    LaunchedEffect(replyToMessage) {
        if (replyToMessage == null) {
            isClosingReply = false
        }
    }
    
    // When editingMessage becomes null, reset the closing flag
    LaunchedEffect(editingMessage) {
        if (editingMessage == null) {
            isClosingEdit = false
        }
    }
    
    // Remove button scale animation to reduce recomposition overhead
    // Simple instant transition is more performant and feels snappier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = 8.dp, 
                end = 8.dp, 
                top = 8.dp, 
                bottom = 5.dp + imeBottomPadding
            )
    ) {
        // No separate ReplyPreview here anymore

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Pill Container
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(26.dp),
                color = glyphTheme.surfaceInput,
                border = BorderStroke(1.dp, glyphTheme.borderInput),
                shadowElevation = 0.dp
            ) {
            Box(modifier = Modifier.then(if (glyphTheme.gradientInput != null) Modifier.background(glyphTheme.gradientInput!!) else Modifier)) {
                Column {
                    // Animated Edit Preview INSIDE the pill (takes priority over Reply)
                    AnimatedVisibility(
                        visible = (editingMessage != null && !isClosingEdit),
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(150)),
                        label = "EditPreview"
                    ) {
                        if (editingMessage != null) {
                            EditPreview(
                                message = editingMessage,
                                onCancel = {
                                    isClosingEdit = true
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(350)
                                        onCancelEdit()
                                    }
                                }
                            )
                        }
                    }
                    
                    // Animated Reply Preview INSIDE the pill
                    AnimatedVisibility(
                        visible = (replyToMessage != null && !isClosingReply && editingMessage == null),
                        enter = expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(animationSpec = tween(300)),
                        exit = shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                    ) {
                        if (replyToMessage != null) {
                            ReplyPreview(
                                message = replyToMessage,
                                username = otherUsername,
                                onCancel = {
                                    // Set closing flag to trigger smooth exit animation
                                    isClosingReply = true
                                    // Clear after animation completes
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(550) // Slightly longer than animation duration
                                        onCancelReply()
                                    }
                                }
                            )
                        }
                    }

                    Box(contentAlignment = Alignment.CenterStart) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .alpha(if (isRecording) 0f else 1f)
                    ) {
                        // Add Button
                        IconButton(onClick = onAttachClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add_2),
                                contentDescription = "Attach",
                                tint = glyphTheme.attachmentIcon
                            )
                        }
                        
                        // Text Field
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChanged,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp)
                                .heightIn(max = 100.dp),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                color = glyphTheme.textPrimary
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            cursorBrush = SolidColor(glyphTheme.cursorColor),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (text.isEmpty()) {
                                        Text(
                                            "Message",
                                            style = TextStyle(
                                                fontSize = 18.sp,
                                                color = glyphTheme.textPlaceholder
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        
                        // Right Icons - Optimized animation with graphics layer
                        Box(
                            modifier = Modifier.wrapContentWidth(),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            // Use graphics layer for GPU-accelerated transforms
                            val transition = updateTransition(targetState = isTyping, label = "InputIcons")
                            
                            val cameraScale by transition.animateFloat(
                                label = "CameraScale",
                                transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) }
                            ) { typing ->
                                if (typing) 0f else 1f
                            }
                            
                            val cameraAlpha by transition.animateFloat(
                                label = "CameraAlpha", 
                                transitionSpec = { tween(durationMillis = 150) }
                            ) { typing ->
                                if (typing) 0f else 1f
                            }
                            
                            val emojiTranslationX by transition.animateDp(
                                label = "EmojiTranslation",
                                transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) }
                            ) { typing ->
                                if (typing) 40.dp else 0.dp
                            }

                            // Camera Button (Rightmost) - Use graphics layer
                            if (cameraAlpha > 0.01f) { // Skip rendering when invisible
                                IconButton(
                                    onClick = onCameraClick,
                                    modifier = Modifier
                                        .graphicsLayer {
                                            scaleX = cameraScale
                                            scaleY = cameraScale
                                            alpha = cameraAlpha
                                        }
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_camera),
                                        contentDescription = "Camera",
                                        tint = glyphTheme.attachmentIcon
                                    )
                                }
                            }
                            
                            // Emoji Button (Left of Camera)
                            IconButton(
                                onClick = { /* Emoji */ },
                                modifier = Modifier
                                    .padding(end = 40.dp)
                                    .graphicsLayer {
                                        translationX = emojiTranslationX.toPx()
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_emoji),
                                    contentDescription = "Emoji",
                                    tint = glyphTheme.emojiIcon
                                )
                            }
                        }
                    }

                    if (isRecording) {
                        VoiceRecorderUI(
                            isRecording = isRecording,
                            isLocked = isLocked,
                            duration = recordingDuration,
                            amplitude = recordingAmplitude,
                            onCancel = onCancelRecording,
                            onSend = onStopRecording,
                            onLock = onMicLocked
                        )
                    }
                    }
                }
            }
            }
        
            Spacer(modifier = Modifier.width(8.dp))
            
            // Send/Mic Button - Optimized with graphics layer scale animation
            Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    brush = glyphTheme.gradientSendButton ?: SolidColor(glyphTheme.sendButtonBackground),
                    shape = CircleShape
                )
                .pointerInput(text) {
                    if (text.isNotEmpty()) {
                        detectTapGestures(onTap = { onSendClick() })
                    } else {
                        detectTapGestures(
                            onPress = {
                                onMicDown()
                                val startTime = System.currentTimeMillis()
                                val released = tryAwaitRelease()
                                if (released) {
                                    if (System.currentTimeMillis() - startTime < 500) {
                                        // Tap to Lock: Short tap switches to specific locked recording mode
                                        onMicLocked()
                                    } else {
                                        // Hold to Record: Long press sends on release
                                        onMicUp()
                                    }
                                } else {
                                    onMicUp() // Cancel
                                }
                            },
                            onLongPress = {
                                // Optional: Handle long press specifically if needed
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val sendScale by animateFloatAsState(
                targetValue = if (isTyping) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                label = "SendIconScale"
            )
            val micScale by animateFloatAsState(
                targetValue = if (isTyping) 0f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                label = "MicIconScale"
            )

            // Send Icon
            Icon(
                painter = painterResource(id = R.drawable.ic_send_custom),
                contentDescription = "Send",
                modifier = Modifier
                    .padding(start = 2.dp)
                    .graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                        alpha = sendScale.coerceIn(0f, 1f)
                    },
                tint = glyphTheme.sendButtonIcon
            )

            // Mic Icon
            Icon(
                painter = painterResource(id = R.drawable.ic_mic_custom),
                contentDescription = "Mic",
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = micScale
                        scaleY = micScale
                        alpha = micScale.coerceIn(0f, 1f)
                    },
                tint = glyphTheme.sendButtonIcon
            )
        }
        }
    }
}

@Composable
fun AttachmentMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onGalleryClick: () -> Unit,
    onCameraClick: () -> Unit,
    imeBottomPadding: Dp = 0.dp
) {
    // Use AnimatedVisibility for the entire overlay to handle background fade too
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 72.dp + imeBottomPadding)
                    .animateEnterExit(
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = tween(durationMillis = 300, easing = DecelerateInterpolator().asEasing())
                        ) + fadeIn(animationSpec = tween(300)),
                        exit = slideOutVertically(
                            targetOffsetY = { it / 2 },
                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(250))
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}, // Prevent clicks from passing through
                    shape = RoundedCornerShape(24.dp),
                    color = glyphTheme.backgroundElevated,
                    tonalElevation = 8.dp
                ) {
                    Box(modifier = Modifier.then(if (glyphTheme.gradientInput != null) Modifier.background(glyphTheme.gradientInput!!) else Modifier)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                        val options = remember {
                            listOf(
                                AttachmentOption("Document", R.drawable.ic_attachment_document, Color(0xFF7E57C2), {}),
                                AttachmentOption("Camera", R.drawable.ic_attachment_camera, Color(0xFFEC407A), onCameraClick),
                                AttachmentOption("Gallery", R.drawable.ic_attachment_gallery, Color(0xFFAB47BC), onGalleryClick),
                                AttachmentOption("Audio", R.drawable.ic_attachment_audio, Color(0xFFFFA726), {}),
                                AttachmentOption("Location", R.drawable.ic_attachment_location, Color(0xFF66BB6A), {}),
                                AttachmentOption("Contact", R.drawable.ic_attachment_contact, Color(0xFF42A5F5), {}),
                                AttachmentOption("Poll", R.drawable.ic_attachment_poll, Color(0xFF26A69A), {}),
                                AttachmentOption("Payment", R.drawable.ic_attachment_payment, Color(0xFF5C6BC0), {}),
                                AttachmentOption("Event", R.drawable.ic_attachment_event, Color(0xFFEF5350), {}),
                                AttachmentOption("AI Images", R.drawable.ic_attachment_ai, Color(0xFF26C6DA), {})
                            )
                        }

                        // Grid of 4 columns with staggered animation
                        options.chunked(4).forEachIndexed { rowIndex, rowOptions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                rowOptions.forEachIndexed { colIndex, option ->
                                    val itemIndex = rowIndex * 4 + colIndex
                                    AttachmentItem(
                                        option = option,
                                        delayMillis = itemIndex * 30, // Staggered delay matching XML
                                        parentVisible = visible
                                    )
                                }
                                // Fill empty spaces in the last row if needed
                                repeat(4 - rowOptions.size) {
                                    Spacer(modifier = Modifier.size(70.dp))
                                }
                            }
                            if (rowIndex < 2) { // Only add spacer between rows
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
}

// Helper to convert Android Interpolator to Compose Easing
fun android.view.animation.Interpolator.asEasing() = Easing { getInterpolation(it) }

data class AttachmentOption(
    val label: String,
    val iconRes: Int,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
fun AttachmentItem(
    option: AttachmentOption,
    delayMillis: Int,
    parentVisible: Boolean
) {
    val animationProgress = remember { Animatable(0f) }
    
    LaunchedEffect(parentVisible) {
        if (parentVisible) {
            delay(delayMillis.toLong())
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = OvershootInterpolator(1.2f).asEasing()
                )
            )
        } else {
            animationProgress.snapTo(0f)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(70.dp)
            .graphicsLayer {
                alpha = animationProgress.value
                scaleX = 0.3f + (animationProgress.value * 0.7f)
                scaleY = 0.3f + (animationProgress.value * 0.7f)
            }
            .clickable(onClick = option.onClick)
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = option.color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = option.iconRes),
                    contentDescription = option.label,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = glyphTheme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TypingIndicator(
    textMeasurer: TextMeasurer,
    density: Density
) {
    val bgColor = glyphTheme.backgroundElevated
    val textColor = glyphTheme.headerIcon
    val borderColor = glyphTheme.bubbleBorder
    
    val baseStyle = MaterialTheme.typography.bodySmall
    val textStyle = remember(textColor, baseStyle) {
        baseStyle.copy(
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = textColor
        )
    }
    
    val textLayoutResult = remember(textStyle) {
        textMeasurer.measure("Typing...", textStyle)
    }
    
    val paddingH = with(density) { 16.dp.toPx() }
    val paddingV = with(density) { 10.dp.toPx() }
    val radiusPx = with(density) { 16.dp.toPx() }
    
    val width = with(density) { (textLayoutResult.size.width.toFloat() + (paddingH * 2f)).toDp() }
    val height = with(density) { (textLayoutResult.size.height.toFloat() + (paddingV * 2f)).toDp() }

    // ULTRA-FLAT with graphicsLayer to offload to GPU during animations
    Spacer(
        modifier = Modifier
            .padding(8.dp)
            .size(width = width, height = height)
            .graphicsLayer {
                // Helps during AnimatedVisibility: prevents redraws of content
                renderEffect = null 
                clip = true
                shape = RoundedCornerShape(16.dp)
            }
            .drawBehind {
                drawRoundRect(
                    color = bgColor,
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
                
                // Draw subtle border
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = 1.dp.toPx())
                )
                
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(paddingH, paddingV)
                )
            }
    )
}

// Cached formatter to avoid expensive SimpleDateFormat creation on every frame
private val TimeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val ZoneIdSystem = java.time.ZoneId.systemDefault()

fun formatTime(timestamp: Long): String {
    return try {
        java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneIdSystem)
            .format(TimeFormatter)
    } catch (e: Exception) {
        ""
    }
}

private fun Message.prefetchImageUrl(): String? {
    val localCandidate = localUri?.takeIf { it.isNotBlank() }
    if (localCandidate != null) return localCandidate
    imageUrl?.takeIf { it.isNotBlank() }?.let { return it }
    thumbnailUrl?.takeIf { it.isNotBlank() }?.let { return it }

    return getMediaItemsList().asSequence().mapNotNull {
        it.localUri?.takeIf { uri -> uri.isNotBlank() }
            ?: it.url.takeIf { url -> url.isNotBlank() }
            ?: it.thumbnailUrl?.takeIf { thumb -> thumb.isNotBlank() }
    }.firstOrNull()
}
