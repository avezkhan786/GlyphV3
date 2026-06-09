package com.glyph.glyph_v3.ui.status

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.remote.KlipyMediaItem
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as ExoMediaItem

@Composable
fun MediaPreviewScreen(
    mediaItems: List<MediaItem>,
    isUploading: Boolean,
    uploadProgress: Float,
    uploadStage: UploadStage = UploadStage.IDLE,
    uploadIndex: Int,
    uploadTotal: Int,
    onSend: (List<MediaItem>) -> Unit,
    onClose: () -> Unit,
    openPickerOnStart: Boolean = false
) {
    val context = LocalContext.current
    val theme = glyphTheme
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val focusRequester = remember { FocusRequester() }
    val fallbackKeyboardHeightPx = remember(density) { with(density) { 270.dp.roundToPx() } }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)

    // Items with per-item captions; types already resolved by the caller from MediaStore data
    var items by remember { mutableStateOf(mediaItems) }
    var currentCaptionField by remember { mutableStateOf(TextFieldValue()) }
    var isPickerVisible by remember { mutableStateOf(openPickerOnStart) }
    var pickerSearchFocused by remember { mutableStateOf(false) }
    var rememberedKeyboardHeightPx by remember { mutableIntStateOf(fallbackKeyboardHeightPx) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
    var isImportingPickerMedia by remember { mutableStateOf(false) }

    if (items.isEmpty() && !openPickerOnStart) {
        onClose()
        return
    }

    BackHandler {
        onClose()
    }

    val pagerState = rememberPagerState(pageCount = { items.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    val currentCaption = items.getOrNull(currentPage)?.caption.orEmpty()
    val animatedTopPadding by animateDpAsState(
        targetValue = with(density) { topBarHeightPx.toDp() },
        animationSpec = tween(durationMillis = 180),
        label = "mediaPreviewTopPadding"
    )
    val animatedBottomPadding by animateDpAsState(
        targetValue = with(density) { bottomOverlayHeightPx.toDp() },
        animationSpec = tween(durationMillis = 220),
        label = "mediaPreviewBottomPadding"
    )

    LaunchedEffect(currentPage, items) {
        val safeCaption = items.getOrNull(currentPage)?.caption.orEmpty()
        if (currentCaptionField.text != safeCaption) {
            currentCaptionField = TextFieldValue(
                text = safeCaption,
                selection = androidx.compose.ui.text.TextRange(safeCaption.length)
            )
        }
    }

    LaunchedEffect(imeBottomPx, navBottomPx) {
        val measuredImeHeight = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        if (measuredImeHeight > 0) {
            rememberedKeyboardHeightPx = measuredImeHeight
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Main pager (or empty-state prompt when openPickerOnStart) ──
        if (items.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = animatedTopPadding, bottom = animatedBottomPadding)
            ) { page ->
                val pageItem = items[page]
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (pageItem.type == StatusType.VIDEO) {
                        VideoPreviewPlayer(
                            uri = pageItem.uri,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(pageItem.uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Preview ${page + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        } else {
            // Empty state: user opens picker first to pick GIF/Sticker/Meme
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = with(density) { topBarHeightPx.toDp() },
                             bottom = with(density) { bottomOverlayHeightPx.toDp() }),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "Pick a GIF, Sticker or Meme\nto post as your status",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 40.dp)
                )
            }
        }

        // ── Top bar: close, counter, delete ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .onSizeChanged { topBarHeightPx = it.height },
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = if (items.isNotEmpty()) "${pagerState.currentPage + 1} / ${items.size}" else "",
                color = Color.White,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            // Delete current item
            if (items.size > 1) {
                IconButton(onClick = {
                    val removeIndex = pagerState.currentPage
                    items = items.toMutableList().apply { removeAt(removeIndex) }
                    if (removeIndex >= items.size) {
                        scope.launch { pagerState.scrollToPage((items.size - 1).coerceAtLeast(0)) }
                    }
                }) {
                    Icon(Icons.Default.Delete, "Remove", tint = Color.White)
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // ── Bottom area: thumbnails strip + caption + send ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 8.dp)
                .onSizeChanged { bottomOverlayHeightPx = it.height }
                .animateContentSize(animationSpec = tween(durationMillis = 220))
        ) {
            // Thumbnail strip (when multiple items)
            if (items.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items) { index, item ->
                        val isSelected = index == pagerState.currentPage
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(item.uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Thumb ${index + 1}",
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            2.dp,
                                            theme.actionPrimary,
                                            RoundedCornerShape(8.dp)
                                        )
                                    } else Modifier
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            StatusCaptionComposer(
                value = currentCaptionField,
                isUploading = isUploading,
                isPickerVisible = isPickerVisible,
                isImportingPickerMedia = isImportingPickerMedia,
                onValueChange = { updatedValue ->
                    currentCaptionField = updatedValue
                    items = items.toMutableList().also { list ->
                        if (currentPage < list.size) {
                            list[currentPage] = list[currentPage].copy(caption = updatedValue.text)
                        }
                    }
                },
                onFieldFocused = {
                    if (isPickerVisible) {
                        isPickerVisible = false
                        pickerSearchFocused = false
                    }
                },
                onEmojiToggle = {
                    if (isPickerVisible) {
                        isPickerVisible = false
                        pickerSearchFocused = false
                        scope.launch {
                            yield()
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    } else {
                        focusRequester.freeFocus()
                        keyboardController?.hide()
                        isPickerVisible = true
                    }
                },
                onSendClick = {
                    if (!isUploading && items.isNotEmpty()) {
                        onSend(items)
                    }
                },
                focusRequester = focusRequester
            )

            if (hostActivity != null) {
                AndroidView(
                    factory = { viewContext ->
                        EmojiPickerPanel(viewContext).apply {
                            attachToActivity(hostActivity)
                            onSystemEmojiSelected = { emoji ->
                                val updated = currentCaptionField.insertAtCursor(emoji)
                                currentCaptionField = updated
                                items = items.toMutableList().also { list ->
                                    if (currentPage < list.size) {
                                        list[currentPage] = list[currentPage].copy(caption = updated.text)
                                    }
                                }
                            }
                            onEmojiSelected = { item ->
                                scope.launch {
                                    val importedItem = addRemotePickerItem(
                                        item = item,
                                        context = context,
                                        onStart = { isImportingPickerMedia = true },
                                        onFinish = { isImportingPickerMedia = false }
                                    ) ?: return@launch
                                    val targetIndex = items.size
                                    items = items + importedItem
                                    yield()
                                    pagerState.animateScrollToPage(targetIndex)
                                }
                            }
                            onGifSelected = { item ->
                                scope.launch {
                                    val importedItem = addRemotePickerItem(
                                        item = item,
                                        context = context,
                                        onStart = { isImportingPickerMedia = true },
                                        onFinish = { isImportingPickerMedia = false }
                                    ) ?: return@launch
                                    val targetIndex = items.size
                                    items = items + importedItem
                                    yield()
                                    pagerState.animateScrollToPage(targetIndex)
                                }
                            }
                            onStickerSelected = { item ->
                                scope.launch {
                                    val importedItem = addRemotePickerItem(
                                        item = item,
                                        context = context,
                                        onStart = { isImportingPickerMedia = true },
                                        onFinish = { isImportingPickerMedia = false }
                                    ) ?: return@launch
                                    val targetIndex = items.size
                                    items = items + importedItem
                                    yield()
                                    pagerState.animateScrollToPage(targetIndex)
                                }
                            }
                            onMemeSelected = { item ->
                                scope.launch {
                                    val importedItem = addRemotePickerItem(
                                        item = item,
                                        context = context,
                                        onStart = { isImportingPickerMedia = true },
                                        onFinish = { isImportingPickerMedia = false }
                                    ) ?: return@launch
                                    val targetIndex = items.size
                                    items = items + importedItem
                                    yield()
                                    pagerState.animateScrollToPage(targetIndex)
                                }
                            }
                            onSearchFocusChanged = { hasFocus ->
                                pickerSearchFocused = hasFocus
                            }
                            onDragClose = {
                                isPickerVisible = false
                                pickerSearchFocused = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    update = { panel ->
                        val compactHeightPx = rememberedKeyboardHeightPx.coerceAtLeast(fallbackKeyboardHeightPx)
                        panel.setPickerHeight(compactHeightPx, navBottomPx)

                        if (isPickerVisible) {
                            if (!panel.isPickerVisible) {
                                panel.show()
                                // When opened from the GIF button, jump straight to the GIF tab
                                if (openPickerOnStart && items.isEmpty()) {
                                    panel.showGifTab()
                                }
                            }
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

            // Upload progress panel
            AnimatedVisibility(
                visible = isUploading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                UploadProgressPanel(
                    stage = uploadStage,
                    progress = uploadProgress,
                    uploadIndex = uploadIndex,
                    uploadTotal = uploadTotal,
                    accentColor = theme.actionPrimary
                )
            }
        }
    }
}

// ── Upload progress panel ──────────────────────────────────────────────────

@Composable
private fun UploadProgressPanel(
    stage: UploadStage,
    progress: Float,
    uploadIndex: Int,
    uploadTotal: Int,
    accentColor: Color
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val showsPercent = stage == UploadStage.UPLOADING || stage == UploadStage.DONE
    val animatedProgress by animateFloatAsState(
        targetValue = clampedProgress,
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "uploadProgress"
    )
    val indeterminate = stage == UploadStage.PREPARING || stage == UploadStage.COMPRESSING
    val percentText = "${(clampedProgress * 100).roundToInt()}%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AnimatedContent(targetState = stage.label, label = "stageLabel") { label ->
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (uploadTotal > 0) {
                Text(
                    text = if (indeterminate) {
                        if (uploadTotal > 1) "$uploadIndex / $uploadTotal" else "Working…"
                    } else if (showsPercent && uploadTotal > 1) {
                        "$uploadIndex / $uploadTotal  •  $percentText"
                    } else if (showsPercent) {
                        percentText
                    } else if (uploadTotal > 1) {
                        "$uploadIndex / $uploadTotal"
                    } else {
                        stage.label
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor)
                )
            }
        }
        if (!indeterminate) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (stage == UploadStage.RETRYING) "Reconnecting and resuming upload…"
                else "Progress reflects bytes uploaded in real time.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun VideoPreviewPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(uri))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier
    )
}

@Composable
private fun StatusCaptionComposer(
    value: TextFieldValue,
    isUploading: Boolean,
    isPickerVisible: Boolean,
    isImportingPickerMedia: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFieldFocused: () -> Unit,
    onEmojiToggle: () -> Unit,
    onSendClick: () -> Unit,
    focusRequester: FocusRequester
) {
    val theme = glyphTheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
            color = theme.surfaceInput,
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderInput),
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onFieldFocused() }
                        .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        .heightIn(min = 24.dp, max = 120.dp),
                    textStyle = TextStyle(
                        color = theme.textPrimary,
                        fontSize = 16.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.cursorColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = "Add a caption…",
                                    color = theme.textPlaceholder,
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                IconButton(
                    onClick = onEmojiToggle,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (isPickerVisible) theme.actionPrimary else theme.emojiIcon
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPickerVisible) R.drawable.ic_keyboard else R.drawable.ic_emoji
                        ),
                        contentDescription = if (isPickerVisible) "Keyboard" else "Emoji, GIF and sticker picker"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        FloatingActionButton(
            onClick = onSendClick,
            containerColor = glyphTheme.actionPrimary,
            modifier = Modifier.size(48.dp)
        ) {
            when {
                isUploading || isImportingPickerMedia -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = glyphTheme.textInverse,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "Send",
                        tint = glyphTheme.textInverse
                    )
                }
            }
        }
    }
}

internal suspend fun addRemotePickerItem(
    item: KlipyMediaItem,
    context: Context,
    onStart: () -> Unit,
    onFinish: () -> Unit
): MediaItem? {
    onStart()
    try {
        val downloadedItem = downloadPickerMediaItem(context, item)
        if (downloadedItem == null) {
            Toast.makeText(context, "Unable to add media item", Toast.LENGTH_SHORT).show()
        }
        return downloadedItem
    } finally {
        onFinish()
    }
}

internal suspend fun downloadPickerMediaItem(context: Context, item: KlipyMediaItem): MediaItem? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(item.fullUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 20000
        }

        connection.connect()
        try {
            val mimeType = inferRemoteMimeType(item.fullUrl, connection.contentType)
            val statusType = if (mimeType.startsWith("video/")) StatusType.VIDEO else StatusType.IMAGE
            val extension = inferRemoteExtension(item.fullUrl, mimeType, statusType)
            val targetFile = File(
                context.cacheDir,
                "status_picker_${System.currentTimeMillis()}_${item.id}.$extension"
            )

            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            MediaItem(
                uri = targetFile.toUri(),
                type = statusType,
                caption = ""
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

internal fun inferRemoteMimeType(url: String, reportedContentType: String?): String {
    val cleanContentType = reportedContentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.contains('/') }
    if (cleanContentType != null) return cleanContentType

    val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        ?.lowercase()
        .orEmpty()
    return when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/webp"
    }
}

internal fun inferRemoteExtension(url: String, mimeType: String, statusType: StatusType): String {
    val existingExtension = MimeTypeMap.getFileExtensionFromUrl(url)
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    if (existingExtension != null) return existingExtension

    return when {
        mimeType.contains("gif") -> "gif"
        mimeType.contains("webp") -> "webp"
        mimeType.contains("png") -> "png"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("webm") -> "webm"
        statusType == StatusType.VIDEO -> "mp4"
        else -> "webp"
    }
}

private fun TextFieldValue.insertAtCursor(snippet: String): TextFieldValue {
    val start = selection.start.coerceAtLeast(0)
    val end = selection.end.coerceAtLeast(0)
    val updatedText = text.replaceRange(start, end, snippet)
    val newCursor = start + snippet.length
    return TextFieldValue(
        text = updatedText,
        selection = androidx.compose.ui.text.TextRange(newCursor)
    )
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
