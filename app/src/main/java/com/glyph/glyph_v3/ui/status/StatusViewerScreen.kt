package com.glyph.glyph_v3.ui.status

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.asPaddingValues
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.StatusCacheManager
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.preferences.StatusNotificationPrefs
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider
import com.glyph.glyph_v3.ui.chat.state.TextInputState
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.media3.common.MediaItem as ExoMediaItem

private const val STATUS_DISPLAY_DURATION_MS = 5000L
private const val STATUS_TAP_MAX_DURATION_MS = 220L
private const val DELETE_DIALOG_ANIMATION_MS = 180

private enum class DeleteDialogAction {
    DISMISS,
    CONFIRM
}

@Composable
fun StatusViewerScreen(
    statuses: List<Status>,
    ownerName: String,
    ownerAvatarUrl: String,
    isMine: Boolean,
    initialIndex: Int = 0,
    onViewStatus: (String) -> Unit,
    onDeleteStatus: (String) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onViewersClick: (String) -> Unit = {},
    onReply: (status: Status, replyText: String) -> Unit = { _, _ -> },
    onLikeStatus: (status: Status) -> Unit = {},
    isReplying: Boolean = false,
    replyStatusId: String? = null,
    isViewersSheetOpen: Boolean = false
) {
    val theme = glyphTheme
    val bottomSafeAreaPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, (statuses.size - 1).coerceAtLeast(0))) }
    val currentStatus = statuses.getOrNull(currentIndex) ?: run {
        onClose()
        return
    }
    val activity = LocalContext.current.findActivity()
    val currentUserId = StatusRepository.currentUserId
    val optimisticLikedStatusIds = remember { mutableStateListOf<String>() }
    val hasLikedCurrentStatus = currentUserId != null && (
        currentStatus.likedByIds.contains(currentUserId) || optimisticLikedStatusIds.contains(currentStatus.id)
    )
    val statusSurfaceColor = remember(currentStatus) {
        when (currentStatus.type) {
            StatusType.TEXT -> Color(currentStatus.backgroundColor)
            StatusType.VOICE -> {
                val hash = currentStatus.userId.hashCode()
                val hue = ((hash and 0xFFFF) % 360).toFloat()
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.62f)))
            }
            else -> Color.Black
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val originalNavigationBarColor = window?.navigationBarColor
        val originalLightNavigationBars = insetsController?.isAppearanceLightNavigationBars

        onDispose {
            if (window != null && originalNavigationBarColor != null) {
                window.navigationBarColor = originalNavigationBarColor
            }
            if (insetsController != null && originalLightNavigationBars != null) {
                insetsController.isAppearanceLightNavigationBars = originalLightNavigationBars
            }
        }
    }

    // Mark as viewed
    LaunchedEffect(currentStatus.id) {
        if (!isMine && currentUserId != null && currentUserId !in currentStatus.viewerIds) {
            onViewStatus(currentStatus.id)
        }
    }

    // Local cache: resolve local path for current status
    var localMediaPath by remember(currentStatus.id) { mutableStateOf<String?>(null) }
    var localThumbnailPath by remember(currentStatus.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(currentStatus.id) {
        localMediaPath = null
        localThumbnailPath = null
        if (currentStatus.type != StatusType.TEXT) {
            val (resolvedMediaPath, resolvedThumbnailPath) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val mediaPath = StatusCacheManager.getLocalPath(currentStatus)
                val thumbnailPath = if (currentStatus.type == StatusType.VIDEO) {
                    StatusCacheManager.getLocalThumbnailPath(currentStatus)
                } else {
                    null
                }
                mediaPath to thumbnailPath
            }
            localMediaPath = resolvedMediaPath
            localThumbnailPath = resolvedThumbnailPath
        }
    }

    // Prefetch next statuses in background
    LaunchedEffect(currentIndex) {
        val upcoming = statuses.drop(currentIndex + 1).take(3)
        if (upcoming.isNotEmpty()) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                StatusCacheManager.prefetch(upcoming)
            }
        }
    }

    // Progress animation
    val timedProgress = remember { Animatable(0f) }
    var mediaProgress by remember { mutableFloatStateOf(0f) }
    var isGesturePaused by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteInFlightStatusId by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showGetNotificationsDialog by remember { mutableStateOf(false) }
    var showMuteNotificationsDialog by remember { mutableStateOf(false) }
    // Notification preference state for the status owner (only used when !isMine)
    val ownerUserId = currentStatus.userId
    var isNotificationsEnabled by remember(ownerUserId) { mutableStateOf(false) }
    val contextForNotifPrefs = LocalContext.current
    LaunchedEffect(ownerUserId) {
        if (!isMine) {
            isNotificationsEnabled = StatusNotificationPrefs.isEnabled(contextForNotifPrefs, ownerUserId)
        }
    }
    val textInputState = remember { TextInputState() }
    var isReplyFocused by remember { mutableStateOf(false) }
    var isPickerVisible by remember { mutableStateOf(false) }
    var pickerSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    }
    val density = LocalDensity.current
    val fallbackKeyboardHeightPx = remember(density) { with(density) { 270.dp.roundToPx() } }
    val inputGapPx = with(density) { -11.dp.roundToPx() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    var kbdHeightPx by remember { mutableIntStateOf(fallbackKeyboardHeightPx) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var isKeyboardAnimating by remember { mutableStateOf(false) }
    var animatedImeBottomPx by remember { mutableIntStateOf(0) }
    var emojiPickerPanel by remember { mutableStateOf<EmojiPickerPanel?>(null) }
    val scope = rememberCoroutineScope()
    val isDeleteInFlight by remember(currentStatus.id) { derivedStateOf { deleteInFlightStatusId == currentStatus.id } }
    val isInteractionPaused by remember(isViewersSheetOpen) { derivedStateOf { isGesturePaused || showDeleteDialog || showGetNotificationsDialog || showMuteNotificationsDialog || showOverflowMenu || deleteInFlightStatusId != null || isReplyFocused || isPickerVisible || isViewersSheetOpen } }
    val hostActivity = activity as? FragmentActivity
    val pickerNavBarColor = theme.backgroundPrimary // Base theme color for panels
    val darkThemeNavBarColor = Color(0xFF081A1A)

    LaunchedEffect(statusSurfaceColor, isPickerVisible, theme.isDark) {
        val window = activity?.window ?: return@LaunchedEffect
        val navColor = if (isPickerVisible) {
            if (theme.isDark) darkThemeNavBarColor else theme.backgroundPrimary
        } else {
            Color(statusSurfaceColor.toArgb())
        }
        window.navigationBarColor = navColor.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            navColor.luminance() > 0.5f
    }

    // Use KeyboardHeightProvider for reliable keyboard height (persisted across sessions).
    // This is the authoritative height used to size the emoji picker panel identically to
    // the keyboard, preventing the height mismatch seen when using Compose insets alone.
    DisposableEffect(hostActivity) {
        if (hostActivity != null) {
            val kbp = KeyboardHeightProvider(hostActivity)
            kbdHeightPx = kbp.getKeyboardHeight()
            kbp.onHeightChanged = { kbdHeightPx = it }
            kbp.onKeyboardVisibilityChanged = { keyboardVisible = it }
            kbp.start()
            onDispose { kbp.stop() }
        } else {
            onDispose { }
        }
    }

    // Mirror ChatActivity inset behavior: use the full IME bottom inset, then keep the
    // input above whichever is larger between IME, picker, and navigation bar, with one
    // explicit gap. Subtracting nav bars from IME undercounts on three-button devices.
    val pickerHeightPx = kbdHeightPx + navBottomPx
    val activeImePx = if (isKeyboardAnimating) animatedImeBottomPx else imeBottomPx
    val effectiveImePx = if (isPickerVisible) maxOf(activeImePx, pickerHeightPx) else activeImePx
    val effectiveBottomPx = maxOf(effectiveImePx, navBottomPx) + inputGapPx
    val effectiveBottomDp = with(density) { (effectiveBottomPx).toDp() }

    DisposableEffect(view, emojiPickerPanel) {
        val callback = object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                    isKeyboardAnimating = true
                }
                super.onPrepare(animation)
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                animatedImeBottomPx = imeInsets.bottom

                val panel = emojiPickerPanel
                if (panel != null && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                    panel.expandForSearch(imeInsets.bottom)
                }

                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                super.onEnd(animation)
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return

                isKeyboardAnimating = false

                val rootInsets = ViewCompat.getRootWindowInsets(view)
                val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                animatedImeBottomPx = imeBottom
                val panel = emojiPickerPanel
                val inputHasFocus = isReplyFocused

                if (panel != null) {
                    if (imeBottom > 0 && isPickerVisible && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(imeBottom)
                    } else if (imeBottom > 0 && isPickerVisible && !inputHasFocus) {
                        panel.expandForSearch(imeBottom)
                    } else if (imeBottom > 0 && isPickerVisible && inputHasFocus) {
                        isPickerVisible = false
                        panel.hide()
                    } else if (imeBottom == 0 && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(0)
                    }
                }
            }
        }

        ViewCompat.setWindowInsetsAnimationCallback(view, callback)
        onDispose {
            ViewCompat.setWindowInsetsAnimationCallback(view, null)
        }
    }

    // Auto-clear focus when keyboard is hidden (but not when switching to picker)
    LaunchedEffect(imeBottomPadding) {
        if (imeBottomPadding == 0.dp && isReplyFocused && !isPickerVisible) {
            focusManager.clearFocus()
            isReplyFocused = false
        }
    }

    BackHandler(enabled = !showDeleteDialog && deleteInFlightStatusId == null) {
        onBack()
    }

    // Back closes emoji picker first
    BackHandler(enabled = isPickerVisible) {
        isPickerVisible = false
        pickerSearchFocused = false
        emojiPickerPanel?.clearAllSearchFocus()
        emojiPickerPanel?.hide()
    }

    // Voice status MediaPlayer
    var voiceMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var voicePlaybackProgress by remember { mutableFloatStateOf(0f) }
    var voiceIsPlaying by remember { mutableStateOf(false) }
    var resumeVoiceAfterGesturePause by remember { mutableStateOf(false) }

    LaunchedEffect(currentStatus.id) {
        timedProgress.snapTo(0f)
        mediaProgress = 0f
        voicePlaybackProgress = 0f
        isGesturePaused = false
        showDeleteDialog = false
        resumeVoiceAfterGesturePause = false
    }

    LaunchedEffect(deleteInFlightStatusId, statuses) {
        val pendingDeleteId = deleteInFlightStatusId ?: return@LaunchedEffect
        if (statuses.none { it.id == pendingDeleteId }) {
            deleteInFlightStatusId = null
            return@LaunchedEffect
        }
        delay(1600)
        if (deleteInFlightStatusId == pendingDeleteId) {
            deleteInFlightStatusId = null
        }
    }

    // For VOICE statuses, manage MediaPlayer lifecycle
    DisposableEffect(currentStatus.id) {
        onDispose {
            voiceMediaPlayer?.release()
            voiceMediaPlayer = null
            voiceIsPlaying = false
        }
    }

    // For VOICE statuses auto-play when status changes
    LaunchedEffect(currentStatus.id, localMediaPath) {
        if (currentStatus.type == StatusType.VOICE) {
            val path = localMediaPath ?: return@LaunchedEffect
            voiceMediaPlayer?.release()
            val mp = MediaPlayer().apply {
                setDataSource(path)
                prepare()
            }
            voiceMediaPlayer = mp
            if (!isGesturePaused) {
                mp.start()
                voiceIsPlaying = true
            }
            mp.setOnCompletionListener {
                    mediaProgress = 1f
                    voicePlaybackProgress = 1f
                    voiceIsPlaying = false
                    scope.launch {
                        if (currentIndex < statuses.size - 1) {
                            currentIndex++
                        } else {
                            onClose()
                        }
                    }
                }
        }
    }

    // Sync hold-to-pause with voice MediaPlayer.
    LaunchedEffect(isInteractionPaused, voiceMediaPlayer, currentStatus.id) {
        val mp = voiceMediaPlayer ?: return@LaunchedEffect
        if (currentStatus.type == StatusType.VOICE) {
            if (isInteractionPaused) {
                resumeVoiceAfterGesturePause = mp.isPlaying
                if (resumeVoiceAfterGesturePause) mp.pause()
                voiceIsPlaying = false
            } else if (resumeVoiceAfterGesturePause && !mp.isPlaying) {
                mp.start()
                voiceIsPlaying = true
                resumeVoiceAfterGesturePause = false
            }
        }
    }

    // Update voice playback progress
    LaunchedEffect(currentStatus.id, voiceMediaPlayer, voiceIsPlaying) {
        if (currentStatus.type == StatusType.VOICE) {
            val mp = voiceMediaPlayer ?: return@LaunchedEffect
            val dur = mp.duration.takeIf { it > 0 } ?: currentStatus.durationMs.toInt().coerceAtLeast(1)
            while (isActive) {
                val fraction = (mp.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                voicePlaybackProgress = fraction
                mediaProgress = fraction
                if (!voiceIsPlaying) break
                withFrameNanos { }
            }
        }
    }

    val displayDurationMs = remember(currentStatus.id) {
        when (currentStatus.type) {
            StatusType.VOICE -> currentStatus.durationMs.takeIf { it > 0 } ?: 30_000L
            else -> STATUS_DISPLAY_DURATION_MS
        }
    }

    LaunchedEffect(currentStatus.id, isInteractionPaused) {
        // VIDEO and VOICE drive their progress from the media clock.
        if (!isInteractionPaused && currentStatus.type != StatusType.VOICE && currentStatus.type != StatusType.VIDEO) {
            val remainingDurationMs = ((1f - timedProgress.value) * displayDurationMs)
                .roundToInt()
                .coerceAtLeast(0)
            if (remainingDurationMs == 0) {
                if (currentIndex < statuses.size - 1) {
                    currentIndex++
                } else {
                    onClose()
                }
                return@LaunchedEffect
            }

            timedProgress.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = remainingDurationMs,
                    easing = LinearEasing
                )
            )
            // Auto-advance
            if (currentIndex < statuses.size - 1) {
                currentIndex++
            } else {
                onClose()
            }
        }
    }

    // Stable derived values hoisted to avoid conditional remember violations and
    // to prevent redundant allocations on every recomposition.
    val fontPreset = remember(currentStatus.fontStyle) { resolveFontPreset(currentStatus.fontStyle) }
    val imageData: Any = remember(localMediaPath, currentStatus.mediaUrl) {
        localMediaPath?.let { java.io.File(it) } ?: currentStatus.mediaUrl
    }
    val videoUri: android.net.Uri? = remember(localMediaPath, currentStatus.mediaUrl) {
        when {
            !localMediaPath.isNullOrEmpty() -> java.io.File(localMediaPath!!).toUri()
            currentStatus.mediaUrl.isNotEmpty() -> android.net.Uri.parse(currentStatus.mediaUrl)
            else -> null
        }
    }
    val timestampText = remember(currentStatus.timestamp) {
        DateUtils.getRelativeTimeSpanString(
            currentStatus.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(currentStatus.id, currentIndex, statuses.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    isGesturePaused = true
                    val up = waitForUpOrCancellation()
                    val wasTap = up != null &&
                        (up.uptimeMillis - down.uptimeMillis) <= STATUS_TAP_MAX_DURATION_MS &&
                        (up.position - down.position).getDistance() <= viewConfiguration.touchSlop

                    isGesturePaused = false

                    if (wasTap) {
                        if (up.position.x < size.width / 3f) {
                            if (currentIndex > 0) currentIndex--
                        } else if (currentIndex < statuses.size - 1) {
                            currentIndex++
                        } else {
                            onClose()
                        }
                    }
                }
            }
    ) {
        // Status content
        when (currentStatus.type) {
            StatusType.TEXT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(currentStatus.backgroundColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentStatus.text,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = fontPreset.fontWeight,
                        fontFamily = fontPreset.fontFamily,
                        fontStyle = fontPreset.fontStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
            StatusType.IMAGE -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageData)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Status image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            StatusType.VIDEO -> {
                if (videoUri != null) {
                    VideoStatusPlayer(
                        uri = videoUri,
                        isPaused = isInteractionPaused,
                        onProgress = { fraction -> mediaProgress = fraction },
                        onEnded = {
                            scope.launch {
                                if (currentIndex < statuses.size - 1) currentIndex++
                                else onClose()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Video not yet available — show thumbnail while loading
                    val thumbData = localThumbnailPath?.let { java.io.File(it) }
                        ?: currentStatus.thumbnailUrl.ifEmpty { null }
                    if (thumbData != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(thumbData).crossfade(true).build(),
                            contentDescription = "Video thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            StatusType.VOICE -> {
                // WhatsApp-style voice status background with player card
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(statusSurfaceColor),
                    contentAlignment = Alignment.Center
                ) {
                    val mp = voiceMediaPlayer
                    val totalSecs = when {
                        mp != null && mp.duration > 0 -> mp.duration / 1000
                        currentStatus.durationMs > 0 -> (currentStatus.durationMs / 1000).toInt()
                        else -> 0
                    }
                    val currentSecs = if (mp != null && mp.duration > 0) {
                        mp.currentPosition / 1000
                    } else 0
                    val elapsed = if (totalSecs > 0) currentSecs else 0
                    val displaySecs = if (voiceIsPlaying) elapsed else totalSecs

                    VoiceStatusPlayerCard(
                        ownerAvatarUrl = ownerAvatarUrl,
                        ownerName = if (isMine) "My status" else
                            ContactDisplayNameResolver.getDisplayName(
                                otherUserId = ownerUserId,
                                remoteProfileName = ownerName
                            ),
                        playbackFraction = voicePlaybackProgress,
                        isPlaying = voiceIsPlaying,
                        displaySeconds = displaySecs,
                        statusId = currentStatus.id,
                        onPlayPause = {
                            val player = voiceMediaPlayer ?: return@VoiceStatusPlayerCard
                            if (player.isPlaying) {
                                player.pause()
                                voiceIsPlaying = false
                                resumeVoiceAfterGesturePause = false
                            } else {
                                player.start()
                                voiceIsPlaying = true
                            }
                        }
                    )
                }
            }
        }

        // Progress bars
        val activeProgress = when (currentStatus.type) {
            StatusType.VOICE, StatusType.VIDEO -> mediaProgress
            else -> timedProgress.value
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            statuses.forEachIndexed { index, _ ->
                StoryProgressSegment(
                    progress = when {
                        index < currentIndex -> 1f
                        index == currentIndex -> activeProgress
                        else -> 0f
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                )
            }
        }

        // Header: avatar, name, time, close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .statusBarsPadding()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(ownerAvatarUrl.ifEmpty { null })
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .build(),
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = if (isMine) "My status" else
                        ContactDisplayNameResolver.getDisplayName(
                            otherUserId = ownerUserId,
                            remoteProfileName = ownerName
                        ),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp
                )
                Text(
                    text = timestampText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (isMine) {
                IconButton(
                    onClick = { if (!isDeleteInFlight) showDeleteDialog = true },
                    enabled = !isDeleteInFlight
                ) {
                    if (isDeleteInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Delete,
                            "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.95f)
                        )
                    }
                }
            }

            // Overflow menu (3-dot) — only visible for other users' statuses
            if (!isMine) {
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        if (isNotificationsEnabled) {
                            DropdownMenuItem(
                                text = { Text("Mute notifications") },
                                onClick = {
                                    showOverflowMenu = false
                                    showMuteNotificationsDialog = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Get notifications") },
                                onClick = {
                                    showOverflowMenu = false
                                    showGetNotificationsDialog = true
                                }
                            )
                        }
                    }
                }
            }

        }

        // Bottom area: caption and viewers (for owners) or reply (for others)
        // Note: Caption is now moved inside the respective isMine/!isMine columns to ensure
        // it stacks correctly above the input/viewers area and doesn't get obscured.

        if (isMine) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
                    .padding(bottom = bottomSafeAreaPadding + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentStatus.caption.isNotEmpty()) {
                    Text(
                        text = currentStatus.caption,
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 12.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onViewersClick(currentStatus.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = "Viewers",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${currentStatus.viewerIds.size}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Reply input for other people's statuses (WhatsApp-style)
        if (!isMine) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.BottomCenter)
            ) {
                // Emoji Picker Panel (mirrors MediaPreviewScreen pattern)
                if (hostActivity != null) {
                    AndroidView(
                        factory = { viewContext ->
                            android.widget.FrameLayout(viewContext).apply {
                                clipChildren = false
                                clipToPadding = false

                                val panel = EmojiPickerPanel(viewContext).apply {
                                    emojiPickerPanel = this
                                    attachToActivity(hostActivity)
                                    onSystemEmojiSelected = { emoji ->
                                        textInputState.updateText(textInputState.text.value + emoji)
                                    }
                                    onSearchFocusChanged = { hasFocus ->
                                        pickerSearchFocused = hasFocus
                                    }
                                    onDragClose = {
                                        isPickerVisible = false
                                        pickerSearchFocused = false
                                    }
                                    onCollapseToCompactRequested = {
                                        pickerSearchFocused = false
                                        collapseToCompact()
                                    }
                                }

                                addView(
                                    panel,
                                    android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.BOTTOM
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.BottomCenter),
                        update = { host ->
                            val panel = emojiPickerPanel ?: return@AndroidView
                            emojiPickerPanel = panel
                            
                            val compactHeightPx = kbdHeightPx.coerceAtLeast(fallbackKeyboardHeightPx)
                            panel.setPickerHeight(compactHeightPx, navBottomPx)

                            val panelLayoutParams = panel.layoutParams as? android.widget.FrameLayout.LayoutParams
                                ?: android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.BOTTOM
                                )
                            if (panelLayoutParams.gravity != Gravity.BOTTOM) {
                                panelLayoutParams.gravity = Gravity.BOTTOM
                                panel.layoutParams = panelLayoutParams
                            }

                            host.clipChildren = false
                            host.clipToPadding = false
                            if (isPickerVisible) {
                                if (!panel.isPickerVisible) panel.show(animate = false)
                                if (pickerSearchFocused && imeBottomPx > 0) {
                                    panel.expandForSearch(imeBottomPx)
                                } else {
                                    panel.collapseToCompact()
                                }
                            } else if (panel.isPickerVisible) {
                                panel.clearAllSearchFocus()
                                panel.hide()
                            }
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                        .padding(bottom = effectiveBottomDp)
                ) {
                    if (currentStatus.caption.isNotEmpty()) {
                        Text(
                            text = currentStatus.caption,
                            color = Color.White,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 12.dp)
                        )
                    }

                    // Sending Progress Banner (Standardized Theme)
                    AnimatedVisibility(
                        visible = isReplying && replyStatusId == currentStatus.id,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(theme.surfaceOverlay.copy(alpha = 0.82f))
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = theme.textPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Sending reply...",
                                    color = theme.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // High-Performance Input Shell (Standardized Chat UX)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            // Standardized Input Pill
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .wrapContentHeight(),
                                shape = RoundedCornerShape(26.dp),
                                color = theme.surfaceInput,
                                border = BorderStroke(1.dp, theme.borderInput)
                            ) {
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.heightIn(min = 52.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        // Isolated Input Slot (High-Frequency)
                                        BasicTextField(
                                            value = textInputState.text.value,
                                            onValueChange = { textInputState.updateText(it) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(top = 8.dp, bottom = 8.dp)
                                                .padding(start = 16.dp, end = 8.dp)
                                                .focusRequester(focusRequester)
                                                .onFocusChanged { isReplyFocused = it.isFocused },
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 17.sp,
                                                color = theme.textPrimary
                                            ),
                                            cursorBrush = SolidColor(theme.cursorColor),
                                            decorationBox = { innerTextField ->
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    if (textInputState.text.value.isEmpty()) {
                                                        Text(
                                                            "Reply...",
                                                            style = androidx.compose.ui.text.TextStyle(
                                                                fontSize = 17.sp,
                                                                color = theme.textPlaceholder
                                                            )
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )

                                        // Right Icons Slot (Emoji Only)
                                        Row(
                                            modifier = Modifier.wrapContentWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Emoji / Keyboard toggle button
                                            IconButton(
                                                onClick = {
                                                    if (isPickerVisible) {
                                                        pickerSearchFocused = false
                                                        emojiPickerPanel?.clearAllSearchFocus()
                                                        emojiPickerPanel?.collapseToCompact()
                                                        focusRequester.requestFocus()
                                                        inputMethodManager.showSoftInput(
                                                            view,
                                                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                                                        )
                                                    } else {
                                                        val panel = emojiPickerPanel
                                                        val wasKeyboardVisible = imeBottomPx > 0 || keyboardVisible
                                                        focusRequester.freeFocus()
                                                        panel?.setPickerHeight(
                                                            kbdHeightPx.coerceAtLeast(fallbackKeyboardHeightPx),
                                                            navBottomPx
                                                        )
                                                        panel?.collapseToCompact()
                                                        panel?.show(animate = !wasKeyboardVisible)
                                                        isPickerVisible = true
                                                        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                                                    }
                                                },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(
                                                        id = if (isPickerVisible) R.drawable.ic_keyboard else R.drawable.ic_emoji
                                                    ),
                                                    contentDescription = if (isPickerVisible) "Keyboard" else "Emoji, GIF and sticker picker",
                                                    tint = theme.emojiIcon,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Heart (empty) → Send (typing)
                            val hasText by textInputState.hasText
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(theme.surfaceInput, CircleShape)
                                    .border(1.dp, theme.borderInput, CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        val text = textInputState.text.value
                                        if (text.isNotBlank()) {
                                            onReply(currentStatus, text.trim())
                                            textInputState.clear()
                                            focusManager.clearFocus()
                                            isReplyFocused = false
                                            } else if (!hasLikedCurrentStatus) {
                                                optimisticLikedStatusIds.add(currentStatus.id)
                                                onLikeStatus(currentStatus)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (hasText) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = theme.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (hasLikedCurrentStatus) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "React",
                                        tint = if (hasLikedCurrentStatus) Color(0xFF26D367) else theme.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    if (showDeleteDialog) {
        DeleteStatusConfirmationDialog(
            isDeleting = isDeleteInFlight,
                onDismissRequest = { showDeleteDialog = false },
                onConfirmDelete = {
                    if (deleteInFlightStatusId == null) {
                        deleteInFlightStatusId = currentStatus.id
                        showDeleteDialog = false
                        onDeleteStatus(currentStatus.id)
                    }
                }
            )
        }

    if (showGetNotificationsDialog) {
        StatusNotificationConfirmDialog(
            title = "Get Notifications?",
            message = "You'll be notified when \"$ownerName\" adds a new status",
            confirmLabel = "Get notifications",
            onDismiss = { showGetNotificationsDialog = false },
            onConfirm = {
                showGetNotificationsDialog = false
                scope.launch {
                    StatusNotificationPrefs.setEnabled(context, ownerUserId, true)
                    isNotificationsEnabled = true
                }
            }
        )
    }

    if (showMuteNotificationsDialog) {
        StatusNotificationConfirmDialog(
            title = "Mute notifications",
            message = "You'll no longer be notified when \"$ownerName\" adds a new status",
            confirmLabel = "Mute notifications",
            onDismiss = { showMuteNotificationsDialog = false },
            onConfirm = {
                showMuteNotificationsDialog = false
                scope.launch {
                    StatusNotificationPrefs.setEnabled(context, ownerUserId, false)
                    isNotificationsEnabled = false
                }
            }
        )
    }
}
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
private fun DeleteStatusConfirmationDialog(
    isDeleting: Boolean,
    onDismissRequest: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val theme = glyphTheme
    var isVisible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<DeleteDialogAction?>(null) }
    val containerColor = if (theme.isDark) Color(0xFF0B1014) else theme.backgroundElevated
    val borderColor = if (theme.isDark) Color(0xFF2D2D2D) else theme.borderSecondary
    val badgeColor = if (theme.isDark) Color(0xFF0D2422) else theme.backgroundTinted
    val badgeIconColor = theme.textPrimary
    val titleColor = theme.textPrimary
    val messageColor = if (theme.isDark) Color(0xFF9EA7AC) else theme.textSecondary
    val noteColor = if (theme.isDark) Color(0xFF0EA5E9) else theme.textLink
    val cancelButtonColor = if (theme.isDark) Color(0xFF0D2422) else theme.backgroundTinted
    val cancelTextColor = theme.textPrimary
    val confirmButtonColor = if (theme.isDark) Color(0xFF1DB954) else theme.actionPrimary
    val confirmTextColor = if (theme.isDark) Color(0xFF000000) else theme.textInverse

    LaunchedEffect(Unit) {
        isVisible = true
    }

    LaunchedEffect(isVisible, pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        if (!isVisible) {
            delay(DELETE_DIALOG_ANIMATION_MS.toLong())
            pendingAction = null
            when (action) {
                DeleteDialogAction.DISMISS -> onDismissRequest()
                DeleteDialogAction.CONFIRM -> onConfirmDelete()
            }
        }
    }

    fun closeWith(action: DeleteDialogAction) {
        if (pendingAction != null || isDeleting) return
        pendingAction = action
        isVisible = false
    }

    Dialog(
        onDismissRequest = { closeWith(DeleteDialogAction.DISMISS) },
        properties = DialogProperties(
            dismissOnBackPress = !isDeleting,
            dismissOnClickOutside = !isDeleting,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)),
                exit = fadeOut(animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(DELETE_DIALOG_ANIMATION_MS))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = containerColor,
                    border = BorderStroke(1.dp, borderColor),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = badgeColor,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = badgeIconColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Delete status?",
                                color = titleColor,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Are you sure you want to delete this status? This action cannot be undone.",
                            color = messageColor,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This status will disappear immediately from your updates.",
                            color = noteColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { closeWith(DeleteDialogAction.DISMISS) },
                                modifier = Modifier.weight(1f),
                                enabled = !isDeleting,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = cancelButtonColor,
                                    contentColor = cancelTextColor
                                ),
                                border = null
                            ) {
                                Text("Cancel", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { closeWith(DeleteDialogAction.CONFIRM) },
                                modifier = Modifier.weight(1f),
                                enabled = !isDeleting,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = confirmButtonColor,
                                    contentColor = confirmTextColor,
                                    disabledContainerColor = confirmButtonColor.copy(alpha = 0.45f),
                                    disabledContentColor = confirmTextColor.copy(alpha = 0.7f)
                                )
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable animated confirmation dialog for the status notification actions
 * ("Get notifications" / "Mute notifications").
 */
@Composable
private fun StatusNotificationConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val theme = glyphTheme
    var isVisible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<DeleteDialogAction?>(null) }

    val containerColor = if (theme.isDark) Color(0xFF0B1014) else theme.backgroundElevated
    val borderColor = if (theme.isDark) Color(0xFF2D2D2D) else theme.borderSecondary
    val messageColor = if (theme.isDark) Color(0xFF9EA7AC) else theme.textSecondary
    val cancelButtonColor = if (theme.isDark) Color(0xFF0D2422) else theme.backgroundTinted
    val confirmButtonColor = if (theme.isDark) Color(0xFF1DB954) else theme.actionPrimary
    val confirmTextColor = if (theme.isDark) Color(0xFF000000) else theme.textInverse

    LaunchedEffect(Unit) { isVisible = true }

    LaunchedEffect(isVisible, pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        if (!isVisible) {
            delay(DELETE_DIALOG_ANIMATION_MS.toLong())
            pendingAction = null
            when (action) {
                DeleteDialogAction.DISMISS -> onDismiss()
                DeleteDialogAction.CONFIRM -> onConfirm()
            }
        }
    }

    fun closeWith(action: DeleteDialogAction) {
        if (pendingAction != null) return
        pendingAction = action
        isVisible = false
    }

    Dialog(
        onDismissRequest = { closeWith(DeleteDialogAction.DISMISS) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)),
                exit = fadeOut(animationSpec = tween(DELETE_DIALOG_ANIMATION_MS)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(DELETE_DIALOG_ANIMATION_MS))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = containerColor,
                    border = BorderStroke(1.dp, borderColor),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)
                    ) {
                        Text(
                            text = title,
                            color = theme.textPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = message,
                            color = messageColor,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            TextButton(
                                onClick = { closeWith(DeleteDialogAction.DISMISS) },
                                colors = ButtonDefaults.textButtonColors(contentColor = theme.textPrimary)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.Medium)
                            }
                            Button(
                                onClick = { closeWith(DeleteDialogAction.CONFIRM) },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = confirmButtonColor,
                                    contentColor = confirmTextColor
                                )
                            ) {
                                Text(confirmLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** WhatsApp-style voice status player card shown over the coloured background. */
@Composable
private fun VoiceStatusPlayerCard(
    ownerAvatarUrl: String,
    ownerName: String,
    playbackFraction: Float,
    isPlaying: Boolean,
    displaySeconds: Int,
    statusId: String,
    onPlayPause: () -> Unit
) {
    val minutes = displaySeconds / 60
    val seconds = displaySeconds % 60
    val timeText = "%d:%02d".format(minutes, seconds)

    // Pseudo-random waveform derived from statusId so it's consistent
    val bars = remember(statusId) {
        val seed = statusId.hashCode()
        val rng = java.util.Random(seed.toLong())
        FloatArray(40) { i ->
            val base = 0.15f + abs(sin(i * 0.4f)) * 0.4f
            base + rng.nextFloat() * 0.45f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1A2E).copy(alpha = 0.82f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with play/pause overlay
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(ownerAvatarUrl.ifEmpty { null })
                            .placeholder(R.drawable.ic_default_avatar)
                            .error(R.drawable.ic_default_avatar)
                            .build(),
                        contentDescription = ownerName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    // Mic badge at bottom-right
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                            .align(Alignment.BottomEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Waveform + play button column
                Column(modifier = Modifier.weight(1f)) {
                    // Waveform canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        val barW = 3.dp.toPx()
                        val gap = 2.5.dp.toPx()
                        val step = barW + gap
                        val totalBars = bars.size
                        val centerY = size.height / 2f
                        val playedCount = (playbackFraction * totalBars).toInt()

                        bars.forEachIndexed { i, amp ->
                            val barH = amp * size.height * 0.85f
                            val x = i * step
                            val color = if (i < playedCount) Color(0xFF25D366) else Color.White.copy(alpha = 0.45f)
                            drawLine(
                                color = color,
                                start = Offset(x + barW / 2, centerY - barH / 2),
                                end = Offset(x + barW / 2, centerY + barH / 2),
                                strokeWidth = barW,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Play/pause button row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Duration
                Text(
                    text = timeText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StoryProgressSegment(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White)
        )
    }
}


/**
 * Plays a video status using ExoPlayer.
 * Drives [onProgress] from the player clock on each frame and calls [onEnded] when playback finishes.
 */
@Composable
private fun VideoStatusPlayer(
    uri: android.net.Uri,
    isPaused: Boolean,
    onProgress: (Float) -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
        }
    }

    // Sync pause state
    LaunchedEffect(isPaused, exoPlayer) {
        if (isPaused) exoPlayer.pause() else exoPlayer.play()
    }

    // Poll playback position on each frame so the story bar stays locked to playback.
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            val duration = exoPlayer.duration
            if (duration > 0) {
                onProgress((exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
            }
            if (exoPlayer.isPlaying) {
                withFrameNanos { }
            } else {
                delay(50)
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    onProgress(1f)
                    onEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier,
        update = { it.player = exoPlayer }
    )
}