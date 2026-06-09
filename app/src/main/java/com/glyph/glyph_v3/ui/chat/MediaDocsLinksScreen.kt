package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.ui.media.MediaViewerActivity
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Theme constants matching ContactInfoScreen WhatsApp dark theme (2026 baseline)
// ─────────────────────────────────────────────────────────────────────────────
private val WhatsAppDarkSurface = Color(0xFF0B141A)  // Main background (page)
private val WhatsAppCardBg      = Color(0xFF0B1014)  // Card / content sections
private val WhatsAppDarkDivider = Color(0xFF222D34)  // Divider
private val WhatsAppTextPrimary = Color(0xFFE9EDEF)  // Primary text
private val WhatsAppTextSecondary = Color(0xFF8696A0) // Secondary text
private val WhatsAppGreen        = Color(0xFF25D366)  // Green accent
private val ThumbBg              = Color(0xFF1A2328)  // Media thumbnail placeholder
private val SelectionRowHighlight = Color(0xFF1D3A47) // Blue-tinted row bg for selected docs/links
private val SelectionCircleFill  = Color(0xFF00A884)  // Filled teal-green selection circle

// ─────────────────────────────────────────────────────────────────────────────
// Internal domain models
// ─────────────────────────────────────────────────────────────────────────────

internal data class TimedMediaItem(
    val item: ChatMediaItem,
    val timestamp: Long,
    val messageId: String   // DB message ID for star / delete / pin operations
)

internal data class MediaSection(
    val label: String,
    val rows: List<List<TimedMediaItem>>  // chunked into rows of 3
)

internal data class DocItem(
    val messageId: String,
    val fileName: String,
    val fileSize: Long?,
    val localUri: String?,
    val remoteUrl: String?,
    val senderLabel: String,
    val timestamp: Long,
    val caption: String?
)

internal data class DocSection(
    val label: String,
    val items: List<DocItem>
)

internal data class LinkPreviewItem(
    val url: String,
    val displayDomain: String,
    val messageText: String,
    val senderLabel: String,
    val timestamp: Long,
    val messageId: String = ""  // DB message ID for delete / star operations
)

internal data class LinkSection(
    val label: String,
    val items: List<LinkPreviewItem>
)

// ─────────────────────────────────────────────────────────────────────────────
// Selection ID helpers (stable keys used to track which items are selected)
// ─────────────────────────────────────────────────────────────────────────────

// selectionId is used in Set<String> for selection tracking.
// For media we embed the messageId directly so we can extract it cheaply.
private fun TimedMediaItem.selectionId() = "media_$messageId"
private fun DocItem.selectionId()        = "doc_$messageId"
private fun LinkPreviewItem.selectionId() = "link_${messageId}_${url.hashCode()}"

/** Extracts a DB messageId from any selection-id string. */
private fun extractMessageId(selectionId: String): String = when {
    selectionId.startsWith("media_") -> selectionId.removePrefix("media_")
    selectionId.startsWith("doc_")   -> selectionId.removePrefix("doc_")
    selectionId.startsWith("link_")  -> selectionId.substringAfter("link_").substringBefore("_")
    else -> ""
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private val URL_REGEX = Regex(
    """(https?://[^\s<>\[\]{}|\\^`"]+|www\.[^\s<>\[\]{}|\\^`"]+)""",
    RegexOption.IGNORE_CASE
)

private fun String.extractUrls(): List<String> = URL_REGEX.findAll(this)
    .map { m ->
        m.value.let { if (it.startsWith("www.", ignoreCase = true)) "https://$it" else it }
    }.toList()

private fun String.toDisplayDomain(): String = runCatching {
    Uri.parse(this).host?.removePrefix("www.") ?: this
}.getOrDefault(this)

private fun Long.toDateGroupLabel(): String {
    val nowMs = System.currentTimeMillis()
    val diffMs = nowMs - this
    val diffDays = diffMs / (24L * 3600 * 1000)
    val cal = Calendar.getInstance().also { it.timeInMillis = this }
    val nowCal = Calendar.getInstance()
    val msgYear = cal.get(Calendar.YEAR)
    val nowYear = nowCal.get(Calendar.YEAR)
    val monthName = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
        ?.uppercase(Locale.getDefault()) ?: "UNKNOWN"
    return when {
        diffDays < 1L  -> "TODAY"
        diffDays < 2L  -> "YESTERDAY"
        diffDays < 7L  -> "THIS WEEK"
        diffDays < 30L -> "LAST MONTH"
        msgYear == nowYear -> monthName
        else -> "$monthName $msgYear"
    }
}

private fun Long.toReadableTime(): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(this))

private fun Long?.toReadableSize(): String {
    val bytes = this ?: return ""
    return when {
        bytes < 1024L        -> "${bytes} B"
        bytes < 1024 * 1024L -> "${"%.1f".format(bytes / 1024f)} kB"
        else                 -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    }
}

private fun Long.toShortDate(): String =
    SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(this))

/** Returns the uppercase file extension for display (e.g. "PDF", "JPG"). */
private fun String.fileExtension(): String {
    val name = substringAfterLast('/').substringBefore('?')
    val ext = name.substringAfterLast('.', "")
    return ext.uppercase(Locale.getDefault()).take(4)
}

private fun fileNameFromUrlOrText(text: String?, url: String?): String {
    if (!text.isNullOrBlank()) return text.trim()
    if (!url.isNullOrBlank()) {
        val decoded = Uri.decode(url)
        val lastSlash = decoded.lastIndexOf('/')
        val raw = if (lastSlash >= 0) decoded.substring(lastSlash + 1) else decoded
        val qMark = raw.indexOf('?')
        return if (qMark > 0) raw.substring(0, qMark) else raw
    }
    return "Document"
}

private fun resolveCoilData(localUri: String?, remoteUrl: String?): Any? {
    if (!localUri.isNullOrEmpty()) {
        val uri = Uri.parse(localUri)
        if (uri.scheme == "file" || localUri.startsWith("/")) {
            val path = uri.path ?: localUri
            if (File(path).exists()) return File(path)
        } else {
            return uri
        }
    }
    return remoteUrl?.takeIf { it.isNotEmpty() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data loaders (Room-first, Firestore fallback pattern)
// ─────────────────────────────────────────────────────────────────────────────

private val MEDIA_TYPES = setOf(
    MessageType.IMAGE, MessageType.VIDEO, MessageType.MEDIA_GROUP,
    MessageType.GIF, MessageType.MEME, MessageType.STICKER, MessageType.KLIPY_EMOJI
)

private suspend fun loadMediaSections(chatId: String, context: Context): List<MediaSection> {
    if (chatId.isEmpty()) return emptyList()
    return try {
        val rawMessages = AppDatabase.getDatabase(context)
            .messageDao()
            .getMediaMessages(chatId, 300)

        val timedItems: List<TimedMediaItem> = rawMessages.flatMap { msg ->
            val ts = msg.timestamp
            when (msg.type) {
                MessageType.VIDEO -> {
                    val remoteThumb = msg.thumbnailUrl ?: msg.videoUrl
                    val url = resolveCoilData(msg.localUri, remoteThumb)?.toString()
                    listOfNotNull(url?.let { TimedMediaItem(ChatMediaItem(it, true), ts, msg.id) })
                }
                MessageType.MEDIA_GROUP -> {
                    val itemsJson = msg.mediaItems
                    if (!itemsJson.isNullOrEmpty()) {
                        try {
                            val arr = com.google.gson.Gson().fromJson(
                                itemsJson,
                                Array<com.glyph.glyph_v3.data.models.MediaItem>::class.java
                            )
                            arr.mapNotNull { item ->
                                val remoteUrl = if (item.isVideo) (item.thumbnailUrl ?: item.url) else item.url
                                val url = resolveCoilData(item.localUri, remoteUrl)?.toString()
                                url?.let { TimedMediaItem(ChatMediaItem(it, item.isVideo), ts, msg.id) }
                            }
                        } catch (e: Exception) { emptyList() }
                    } else {
                        val url = resolveCoilData(msg.localUri, msg.imageUrl)?.toString()
                        listOfNotNull(url?.let { TimedMediaItem(ChatMediaItem(it, false), ts, msg.id) })
                    }
                }
                else -> {
                    val url = resolveCoilData(msg.localUri, msg.imageUrl)?.toString()
                    listOfNotNull(url?.let { TimedMediaItem(ChatMediaItem(it, false), ts, msg.id) })
                }
            }
        }

        // Group by date label, preserving order (newest first from Room)
        val grouped = LinkedHashMap<String, MutableList<TimedMediaItem>>()
        timedItems.forEach { timed ->
            val label = timed.timestamp.toDateGroupLabel()
            grouped.getOrPut(label) { mutableListOf() }.add(timed)
        }

        grouped.map { (label, items) ->
            MediaSection(label = label, rows = items.chunked(3))
        }
    } catch (e: Exception) {
        android.util.Log.e("MDLScreen", "loadMediaSections failed", e)
        emptyList()
    }
}

private suspend fun loadDocs(chatId: String, context: Context): List<DocSection> {
    if (chatId.isEmpty()) return emptyList()
    return try {
        val items = AppDatabase.getDatabase(context)
            .messageDao()
            .getRecentMessages(chatId, 300)
            .filter { it.type == MessageType.DOCUMENT }
            .map { msg ->
                DocItem(
                    messageId = msg.id,
                    fileName = fileNameFromUrlOrText(msg.text.takeIf { it.isNotBlank() }, msg.imageUrl),
                    fileSize = msg.fileSize,
                    localUri = msg.localUri,
                    remoteUrl = msg.imageUrl,
                    senderLabel = if (msg.isIncoming) "Contact" else "You",
                    timestamp = msg.timestamp,
                    caption = msg.documentCaption
                )
            }
        val grouped = LinkedHashMap<String, MutableList<DocItem>>()
        items.forEach { doc ->
            val label = doc.timestamp.toDateGroupLabel()
            grouped.getOrPut(label) { mutableListOf() }.add(doc)
        }
        grouped.map { (label, list) -> DocSection(label, list) }
    } catch (e: Exception) {
        android.util.Log.e("MDLScreen", "loadDocs failed", e)
        emptyList()
    }
}

private suspend fun loadLinks(chatId: String, context: Context): List<LinkSection> {
    if (chatId.isEmpty()) return emptyList()
    return try {
        val items = AppDatabase.getDatabase(context)
            .messageDao()
            .getRecentMessages(chatId, 500)
            .filter { it.type == MessageType.TEXT }
            .flatMap { msg ->
                msg.text.extractUrls().map { url ->
                    LinkPreviewItem(
                        url = url,
                        displayDomain = url.toDisplayDomain(),
                        messageText = msg.text,
                        senderLabel = if (msg.isIncoming) "Contact" else "You",
                        timestamp = msg.timestamp,
                        messageId = msg.id
                    )
                }
            }
            .distinctBy { it.url }
        val grouped = LinkedHashMap<String, MutableList<LinkPreviewItem>>()
        items.forEach { link ->
            val label = link.timestamp.toDateGroupLabel()
            grouped.getOrPut(label) { mutableListOf() }.add(link)
        }
        grouped.map { (label, list) -> LinkSection(label, list) }
    } catch (e: Exception) {
        android.util.Log.e("MDLScreen", "loadLinks failed", e)
        emptyList()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaDocsLinksScreen(
    contactName: String,
    chatId: String,
    onBackClick: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Theme-aware colors (WhatsApp dark theme matching ContactInfoScreen)
    val surfaceBg   = if (theme.isDark) WhatsAppDarkSurface else theme.backgroundPrimary
    val elevatedBg  = if (theme.isDark) WhatsAppCardBg else theme.backgroundElevated
    val textPrimary = if (theme.isDark) WhatsAppTextPrimary else theme.textPrimary
    val textSecond  = if (theme.isDark) WhatsAppTextSecondary else theme.textSecondary
    val divider     = if (theme.isDark) WhatsAppDarkDivider else theme.divider
    val tabActive   = WhatsAppGreen  // Green accent across all themes
    val tabInactive = if (theme.isDark) theme.iconSecondary else theme.iconSecondary

    // Pager state driving both tab selection and horizontal swipe
    val pagerState = rememberPagerState(pageCount = { 3 })

    // Reload key — incrementing causes all produceState blocks below to re-run,
    // which removes deleted / re-queries starred / pinned items without a full recompose.
    var reloadKey by remember { mutableStateOf(0) }

    // Load data (re-run whenever chatId or reloadKey changes)
    val mediaSections by produceState<List<MediaSection>>(
        initialValue = emptyList(), chatId, reloadKey
    ) { value = loadMediaSections(chatId, context) }

    val docs by produceState<List<DocSection>>(
        initialValue = emptyList(), chatId, reloadKey
    ) { value = loadDocs(chatId, context) }

    val links by produceState<List<LinkSection>>(
        initialValue = emptyList(), chatId, reloadKey
    ) { value = loadLinks(chatId, context) }

    // ── Selection state ───────────────────────────────────────────────────────
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds     by remember { mutableStateOf(setOf<String>()) }

    // Dialog state
    var showDeleteDialog  by remember { mutableStateOf(false) }
    var deleteFromGallery by remember { mutableStateOf(true) }
    var showForwardPicker by remember { mutableStateOf(false) }
    var showPinDialog     by remember { mutableStateOf(false) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Action handlers ───────────────────────────────────────────────────────

    /** Returns the DB message IDs for all currently selected selection-IDs. */
    fun selectedMessageIds(): List<String> =
        selectedIds.map { extractMessageId(it) }.filter { it.isNotEmpty() }.distinct()

    fun exitSelection() {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    val onStar: () -> Unit = star@{
        if (selectedIds.isEmpty()) return@star
        val msgIds = selectedMessageIds()
        scope.launch {
            try {
                AppDatabase.getDatabase(context).messageDao().starMessages(msgIds, starred = true)
                snackbarHostState.showSnackbar("Added to starred messages")
            } catch (e: Exception) {
                android.util.Log.e("MDLScreen", "Star failed", e)
            }
        }
        exitSelection()
    }

    val onPin: () -> Unit = pin@{
        if (selectedIds.isEmpty()) return@pin
        showPinDialog = true
    }

    // ── Pin duration dialog ─────────────────────────────────────────────────
    if (showPinDialog) {
        PinDurationDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { durationMs ->
                showPinDialog = false
                val msgIds = selectedMessageIds()
                val until = System.currentTimeMillis() + durationMs
                scope.launch {
                    try {
                        AppDatabase.getDatabase(context).messageDao()
                            .pinMessages(msgIds, pinned = true, until = until)
                        val label = if (msgIds.size == 1) "Message pinned" else "${msgIds.size} messages pinned"
                        snackbarHostState.showSnackbar(label)
                    } catch (e: Exception) {
                        android.util.Log.e("MDLScreen", "Pin failed", e)
                    }
                }
                exitSelection()
            }
        )
    }

    // Exit selection mode when user swipes to a different tab
    LaunchedEffect(pagerState.currentPage) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedIds     = emptySet()
        }
    }

    // Hardware / swipe back exits selection mode first
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds     = emptySet()
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF233138),
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text = "Delete selected media?",
                    color = textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            },
            text = {
                if (pagerState.currentPage == 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFromGallery = !deleteFromGallery }
                    ) {
                        Checkbox(
                            checked = deleteFromGallery,
                            onCheckedChange = { deleteFromGallery = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor   = WhatsAppGreen,
                                uncheckedColor = textSecond,
                                checkmarkColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Also delete media received in this chat from the device gallery",
                            color = textPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    Text(
                        text = "This will delete the selected items.",
                        color = textSecond,
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val msgIds = selectedMessageIds()
                    scope.launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            // Delete-for-me: local tombstone + remove from messages table
                            val now = System.currentTimeMillis()
                            msgIds.forEach { id ->
                                db.deletedMessageDao().insertDeletedMessage(
                                    com.glyph.glyph_v3.data.local.entity.LocalDeletedMessage(
                                        id = id, chatId = chatId, deletedAt = now
                                    )
                                )
                                db.messageDao().deleteMessage(id)
                            }
                            reloadKey++   // Remove items from the grid immediately
                        } catch (e: Exception) {
                            android.util.Log.e("MDLScreen", "Delete failed", e)
                        }
                    }
                    exitSelection()
                }) {
                    Text("Delete", color = WhatsAppGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = WhatsAppGreen)
                }
            }
        )
    }

    // ── Forward picker sheet ───────────────────────────────────────────────────
    if (showForwardPicker) {
        ForwardPickerDialog(
            chatId      = chatId,
            messageIds  = selectedMessageIds(),
            context     = context,
            onDismiss   = { showForwardPicker = false },
            onForwarded = { count ->
                showForwardPicker = false
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (count == 1) "Forwarded to 1 chat" else "Forwarded to $count chats"
                    )
                }
                exitSelection()
            }
        )
    }

    Scaffold(
        containerColor = surfaceBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = elevatedBg,
                        titleContentColor = textPrimary,
                        navigationIconContentColor = textPrimary,
                        actionIconContentColor = textPrimary
                    ),
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.size(48.dp),
                            onClick = {
                                if (isSelectionMode) {
                                    isSelectionMode = false
                                    selectedIds     = emptySet()
                                } else {
                                    onBackClick()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_v2),
                                contentDescription = if (isSelectionMode) "Deselect" else "Back",
                                tint = textPrimary,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                    },
                    title = {
                        if (isSelectionMode) {
                            Text(
                                text = "${selectedIds.size}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary
                            )
                        } else {
                            Text(
                                text = contactName,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            // ── Star ────────────────────────────────────────────────
                            IconButton(
                                onClick = onStar,
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_star_v2),
                                    contentDescription = "Star",
                                    tint = if (selectedIds.isNotEmpty()) textPrimary
                                           else textSecond.copy(alpha = 0.4f),
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            // ── Delete ───────────────────────────────────────────────
                            IconButton(
                                onClick  = { if (selectedIds.isNotEmpty()) showDeleteDialog = true },
                                enabled  = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bin_v2),
                                    contentDescription = "Delete",
                                    tint = if (selectedIds.isNotEmpty()) textPrimary
                                           else textSecond.copy(alpha = 0.4f),
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            // ── Forward ──────────────────────────────────────────────
                            IconButton(
                                onClick = { if (selectedIds.isNotEmpty()) showForwardPicker = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_forward_v2),
                                    contentDescription = "Forward",
                                    tint = if (selectedIds.isNotEmpty()) textPrimary
                                           else textSecond.copy(alpha = 0.4f),
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            // ── Pin ───────────────────────────────────────────────────
                            IconButton(
                                onClick = onPin,
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_keep_v2),
                                    contentDescription = "Pin",
                                    tint = if (selectedIds.isNotEmpty()) textPrimary
                                           else textSecond.copy(alpha = 0.4f),
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                        }
                    }
                )

                // Tab row – always visible (WhatsApp keeps tabs visible during selection)
                val tabTitles = listOf("Media", "Docs", "Links")
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = elevatedBg,
                    contentColor = tabActive,
                    divider = { HorizontalDivider(thickness = 0.5.dp, color = divider) },
                    indicator = { positions ->
                        if (pagerState.currentPage < positions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[pagerState.currentPage]),
                                color = tabActive,
                                height = 3.dp
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(tabTitles.size) { index ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick  = { scope.launch { pagerState.animateScrollToPage(index) } },
                            selectedContentColor   = tabActive,
                            unselectedContentColor = tabInactive
                        ) {
                            Text(
                                text = tabTitles[index],
                                fontSize = 14.sp,
                                fontWeight = if (pagerState.currentPage == index)
                                    FontWeight.SemiBold else FontWeight.Normal,
                                color = if (pagerState.currentPage == index) tabActive else tabInactive,
                                letterSpacing = 0.3.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) { page ->
            when (page) {
                0 -> MediaTabContent(
                    mediaSections    = mediaSections,
                    context          = context,
                    surfaceBg        = surfaceBg,
                    elevatedBg       = elevatedBg,
                    textSecond       = textSecond,
                    isSelectionMode  = isSelectionMode,
                    selectedIds      = selectedIds,
                    onToggleSelect   = { id ->
                        val newSet = if (id in selectedIds) selectedIds - id else selectedIds + id
                        selectedIds = newSet
                        if (newSet.isEmpty()) isSelectionMode = false
                    },
                    onEnterSelection = { id ->
                        isSelectionMode = true
                        selectedIds     = setOf(id)
                    }
                )
                1 -> DocsTabContent(
                    sections         = docs,
                    textPrimary      = textPrimary,
                    textSecond       = textSecond,
                    surfaceBg        = elevatedBg,
                    elevatedBg       = elevatedBg,
                    divider          = divider,
                    context          = context,
                    isSelectionMode  = isSelectionMode,
                    selectedIds      = selectedIds,
                    onToggleSelect   = { id ->
                        val newSet = if (id in selectedIds) selectedIds - id else selectedIds + id
                        selectedIds = newSet
                        if (newSet.isEmpty()) isSelectionMode = false
                    },
                    onEnterSelection = { id ->
                        isSelectionMode = true
                        selectedIds     = setOf(id)
                    }
                )
                2 -> LinksTabContent(
                    sections         = links,
                    textPrimary      = textPrimary,
                    textSecond       = textSecond,
                    surfaceBg        = elevatedBg,
                    elevatedBg       = elevatedBg,
                    divider          = divider,
                    context          = context,
                    isSelectionMode  = isSelectionMode,
                    selectedIds      = selectedIds,
                    onToggleSelect   = { id ->
                        val newSet = if (id in selectedIds) selectedIds - id else selectedIds + id
                        selectedIds = newSet
                        if (newSet.isEmpty()) isSelectionMode = false
                    },
                    onEnterSelection = { id ->
                        isSelectionMode = true
                        selectedIds     = setOf(id)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Media tab
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTabContent(
    mediaSections: List<MediaSection>,
    context: Context,
    surfaceBg: Color,
    elevatedBg: Color,
    textSecond: Color,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit
) {
    if (mediaSections.isEmpty()) {
        EmptyStateBox("No media shared yet", textSecond, surfaceBg)
        return
    }

    val allFlat by remember(mediaSections) {
        derivedStateOf { mediaSections.flatMap { s -> s.rows.flatten() } }
    }
    val viewerItems by remember(allFlat) {
        derivedStateOf {
            allFlat.map { t ->
                MediaItem(
                    url = t.item.thumbnailUrl,
                    type = if (t.item.isVideo) MediaType.VIDEO else MediaType.IMAGE,
                    thumbnailUrl = if (t.item.isVideo) t.item.thumbnailUrl else null
                )
            }
        }
    }

    val density = LocalDensity.current
    val screenWidthDp = with(density) {
        androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels.toDp()
    }
    val cellSize: Dp = remember(screenWidthDp) { (screenWidthDp - 4.dp) / 3 }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(elevatedBg),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        mediaSections.forEach { section ->
            stickyHeader(key = "header_${section.label}") {
                MediaSectionHeader(label = section.label, textColor = textSecond, bg = elevatedBg)
            }
            items(
                items = section.rows,
                key   = { row -> "row_${row.firstOrNull()?.item?.thumbnailUrl}_${row.firstOrNull()?.timestamp}" }
            ) { row ->
                MediaRow(
                    row              = row,
                    cellSize         = cellSize,
                    allFlat          = allFlat,
                    viewerItems      = viewerItems,
                    context          = context,
                    isSelectionMode  = isSelectionMode,
                    selectedIds      = selectedIds,
                    onToggleSelect   = onToggleSelect,
                    onEnterSelection = onEnterSelection
                )
            }
        }
    }
}

@Composable
private fun MediaSectionHeader(label: String, textColor: Color, bg: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.0.sp
        )
    }
}

@Composable
private fun MediaRow(
    row: List<TimedMediaItem>,
    cellSize: Dp,
    allFlat: List<TimedMediaItem>,
    viewerItems: List<MediaItem>,
    context: Context,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        row.forEach { timedItem ->
            val id          = timedItem.selectionId()
            val isSelected  = id in selectedIds
            val globalIndex = allFlat.indexOf(timedItem)
            MediaThumb(
                item            = timedItem,
                size            = cellSize,
                isSelectionMode = isSelectionMode,
                isSelected      = isSelected,
                onTap = {
                    if (isSelectionMode) {
                        onToggleSelect(id)
                    } else {
                        context.startActivity(
                            MediaViewerActivity.newIntentWithMultipleMedia(
                                context    = context,
                                mediaItems = viewerItems,
                                startIndex = globalIndex.coerceAtLeast(0)
                            )
                        )
                    }
                },
                onLongPress = {
                    if (isSelectionMode) onToggleSelect(id) else onEnterSelection(id)
                }
            )
        }
        // Fill empty cells in row to keep grid aligned
        repeat(3 - row.size) {
            Spacer(modifier = Modifier.size(cellSize))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumb(
    item: TimedMediaItem,
    size: Dp,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ThumbBg)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        val data = remember(item.item.thumbnailUrl) {
            item.item.thumbnailUrl.let { url ->
                when {
                    url.startsWith("/")           -> File(url)
                    url.startsWith("file://")     -> File(Uri.parse(url).path ?: url)
                    url.startsWith("content://")  -> Uri.parse(url)
                    else                          -> url
                }
            }
        }
        val ctx = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(data)
                .crossfade(true)
                .memoryCacheKey(item.item.thumbnailUrl)
                .diskCacheKey(item.item.thumbnailUrl)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Video overlay badge (suppressed when selected – selection tint takes visual priority)
        if (item.item.isVideo && !isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
            Icon(
                painter = painterResource(R.drawable.ic_play_circle),
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
            )
        }

        // ── Selection overlay (WhatsApp style centered checkmark) ─────────────────
        if (isSelectionMode) {
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.40f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared: animated selection circle (used in Docs & Links rows)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SelectionCircle(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isSelected) SelectionCircleFill else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (isSelected) SelectionCircleFill else WhatsAppTextSecondary,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = isSelected,
            enter   = scaleIn(tween(120)) + fadeIn(tween(120)),
            exit    = scaleOut(tween(100)) + fadeOut(tween(100))
        ) {
            Icon(
                imageVector        = Icons.Filled.Check,
                contentDescription = null,
                tint               = Color.White,
                modifier           = Modifier.size(15.dp)
            )
        }
    }
}

private fun saveMediaToGallery(context: Context, url: String) {
    try {
        if (url.startsWith("/") || url.startsWith("file://")) {
            val path = if (url.startsWith("file://")) Uri.parse(url).path ?: url else url
            val srcFile = File(path)
            if (!srcFile.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val mimeType = when (srcFile.extension.lowercase()) {
                "mp4", "mkv", "mov" -> "video/mp4"
                "gif"               -> "image/gif"
                else                -> "image/jpeg"
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, srcFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (mimeType.startsWith("video")) Environment.DIRECTORY_MOVIES
                    else Environment.DIRECTORY_PICTURES)
            }
            val uri = context.contentResolver.insert(
                if (mimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
            )
            uri?.let { dest ->
                context.contentResolver.openOutputStream(dest)?.use { out ->
                    srcFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Download from remote is handled by the system", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not save: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Docs tab
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocsTabContent(
    sections: List<DocSection>,
    textPrimary: Color,
    textSecond: Color,
    surfaceBg: Color,
    elevatedBg: Color,
    divider: Color,
    context: Context,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit
) {
    if (sections.isEmpty()) {
        EmptyStateBox("No documents shared yet", textSecond, surfaceBg)
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceBg),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        sections.forEach { section ->
            stickyHeader(key = "doc_header_${section.label}") {
                MediaSectionHeader(label = section.label, textColor = textSecond, bg = elevatedBg)
            }
            items(section.items, key = { it.messageId }) { doc ->
                DocRow(
                    doc              = doc,
                    textPrimary      = textPrimary,
                    textSecond       = textSecond,
                    bg               = elevatedBg,
                    divider          = divider,
                    context          = context,
                    isSelectionMode  = isSelectionMode,
                    isSelected       = doc.selectionId() in selectedIds,
                    onToggleSelect   = { onToggleSelect(doc.selectionId()) },
                    onEnterSelection = { onEnterSelection(doc.selectionId()) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(
    doc: DocItem,
    textPrimary: Color,
    textSecond: Color,
    bg: Color,
    divider: Color,
    context: Context,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEnterSelection: () -> Unit
) {
    val rowBg = if (isSelected) SelectionRowHighlight else bg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        val url = doc.localUri?.takeIf { it.isNotEmpty() } ?: doc.remoteUrl
                        if (!url.isNullOrEmpty()) {
                            try {
                                val uri = when {
                                    url.startsWith("content://") -> Uri.parse(url)
                                    url.startsWith("http://") || url.startsWith("https://") -> Uri.parse(url)
                                    url.startsWith("/") -> {
                                        val file = File(url)
                                        if (file.exists()) {
                                            androidx.core.content.FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", file
                                            )
                                        } else null
                                    }
                                    else -> Uri.parse(url)
                                }
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onLongClick = {
                    if (isSelectionMode) onToggleSelect() else onEnterSelection()
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Format-specific file icon
        DocFormatIcon(fileName = doc.fileName)

        // Meta (takes remaining width)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = doc.fileName,
                color = textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            val ext = doc.fileName.fileExtension()
            val sizeStr = doc.fileSize.toReadableSize()
            val subtitle = buildString {
                if (sizeStr.isNotEmpty()) append(sizeStr)
                if (sizeStr.isNotEmpty() && ext.isNotEmpty()) append("  $ext")
                else if (ext.isNotEmpty()) append(ext)
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = textSecond,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Date or animated selection circle
        if (isSelectionMode) {
            SelectionCircle(isSelected = isSelected)
        } else {
            Text(text = doc.timestamp.toShortDate(), color = textSecond, fontSize = 12.sp)
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color     = divider,
        modifier  = Modifier.padding(start = 78.dp)
    )
}

/**
 * Renders a WhatsApp-style file format badge (red PDF, blue DOC, gray JPG, etc.).
 * Falls back to a generic document icon for unknown formats.
 */
@Composable
private fun DocFormatIcon(fileName: String) {
    val ext = fileName.fileExtension()
    val formatInfo = when (ext) {
        "PDF"                           -> Triple(Color(0xFFE53935), Color.White, "PDF")
        "DOC", "DOCX"                   -> Triple(Color(0xFF1565C0), Color.White, ext)
        "XLS", "XLSX"                   -> Triple(Color(0xFF2E7D32), Color.White, ext)
        "PPT", "PPTX"                   -> Triple(Color(0xFFE65100), Color.White, ext)
        "TXT"                           -> Triple(Color(0xFF546E7A), Color.White, "TXT")
        "ZIP", "RAR", "7Z"              -> Triple(Color(0xFF6A1B9A), Color.White, "ZIP")
        "JPG", "JPEG"                   -> Triple(Color(0xFF546E7A), Color.White, "JPG")
        "PNG"                           -> Triple(Color(0xFF00695C), Color.White, "PNG")
        "GIF"                           -> Triple(Color(0xFF00838F), Color.White, "GIF")
        "MP4", "MKV", "MOV"             -> Triple(Color(0xFF37474F), Color.White, "VID")
        "MP3", "WAV", "AAC", "OGG"      -> Triple(Color(0xFF4527A0), Color.White, "AUD")
        else                            -> null
    }

    if (formatInfo != null) {
        val (bgColor, labelColor, label) = formatInfo
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = labelColor,
                fontSize = if (label.length <= 3) 13.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1D3A4A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_attachment_document),
                contentDescription = null,
                tint = Color(0xFF53BDEB),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Links tab
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinksTabContent(
    sections: List<LinkSection>,
    textPrimary: Color,
    textSecond: Color,
    surfaceBg: Color,
    elevatedBg: Color,
    divider: Color,
    context: Context,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit
) {
    if (sections.isEmpty()) {
        EmptyStateBox("No links shared yet", textSecond, surfaceBg)
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceBg),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        sections.forEach { section ->
            stickyHeader(key = "link_header_${section.label}") {
                MediaSectionHeader(label = section.label, textColor = textSecond, bg = elevatedBg)
            }
            items(section.items, key = { it.url }) { link ->
                LinkCard(
                    link             = link,
                    textPrimary      = textPrimary,
                    textSecond       = textSecond,
                    bg               = elevatedBg,
                    divider          = divider,
                    context          = context,
                    isSelectionMode  = isSelectionMode,
                    isSelected       = link.selectionId() in selectedIds,
                    onToggleSelect   = { onToggleSelect(link.selectionId()) },
                    onEnterSelection = { onEnterSelection(link.selectionId()) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkCard(
    link: LinkPreviewItem,
    textPrimary: Color,
    textSecond: Color,
    bg: Color,
    divider: Color,
    context: Context,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEnterSelection: () -> Unit
) {
    val rowBg = if (isSelected) SelectionRowHighlight else bg

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onLongClick = {
                    if (isSelectionMode) onToggleSelect() else onEnterSelection()
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Chain-link icon in a circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1D3A4A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_link),
                    contentDescription = null,
                    tint = Color(0xFFB0BEC5),
                    modifier = Modifier.size(22.dp)
                )
            }
            // URL + domain
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.url,
                    color = textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = link.displayDomain,
                    color = textSecond,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Selection circle on right edge
            if (isSelectionMode) {
                SelectionCircle(isSelected = isSelected)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = divider)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateBox(message: String, textSecond: Color, bg: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = textSecond.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// Forward Picker Dialog
// Presents a searchable list of all local chats. The user picks one or more
// conversations to forward to; on confirm the selected messages are sent to
// each chosen chat via RealtimeMessageRepository.sendForwardedMessage().
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ForwardPickerDialog(
    chatId: String,
    messageIds: List<String>,
    context: Context,
    onDismiss: () -> Unit,
    onForwarded: (Int) -> Unit
) {
    if (messageIds.isEmpty()) {
        onDismiss()
        return
    }

    val scope = rememberCoroutineScope()

    // Load local chats
    val allChats by produceState<List<com.glyph.glyph_v3.data.local.entity.LocalChat>>(
        initialValue = emptyList()
    ) {
        value = try {
            AppDatabase.getDatabase(context).chatDao().getAllChatIds()
                .mapNotNull { id -> AppDatabase.getDatabase(context).chatDao().getChatById(id) }
                .filter { it.id != chatId } // Don't forward to the originating chat
                .sortedByDescending { it.lastMessageTimestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    var selectedChatIds by remember { mutableStateOf(setOf<String>()) }
    var searchQuery     by remember { mutableStateOf("") }
    var isForwarding    by remember { mutableStateOf(false) }

    val filtered by remember(allChats, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) allChats
            else allChats.filter {
                it.otherUsername.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val dialogBg = Color(0xFF233138)
    val textColor = WhatsAppTextPrimary
    val subColor  = WhatsAppTextSecondary

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!isForwarding) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(16.dp))
                .background(dialogBg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Forward to",
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedChatIds.isNotEmpty()) {
                        Text(
                            text = "${selectedChatIds.size} selected",
                            color = WhatsAppGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // ── Search bar ────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF182229))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = subColor,
                        modifier = Modifier.size(18.dp)
                    )
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = textColor,
                            fontSize = 15.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(WhatsAppGreen),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text("Search chats", color = subColor, fontSize = 15.sp)
                            }
                            inner()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 0.5.dp, color = WhatsAppDarkDivider)

                // ── Chat list ─────────────────────────────────────────────────
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (allChats.isEmpty()) "No other chats" else "No results",
                            color = subColor,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(filtered, key = { it.id }) { chat ->
                            val isSelected = chat.id in selectedChatIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) SelectionRowHighlight else Color.Transparent
                                    )
                                    .clickable {
                                        selectedChatIds = if (isSelected)
                                            selectedChatIds - chat.id
                                        else
                                            selectedChatIds + chat.id
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Avatar
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1D3A4A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (chat.otherUserAvatar.isNotEmpty()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(chat.otherUserAvatar)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Text(
                                            text = chat.otherUsername.take(1).uppercase(),
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                // Name + last message
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chat.otherUsername,
                                        color = textColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (chat.lastMessage.isNotEmpty()) {
                                        Text(
                                            text = chat.lastMessage,
                                            color = subColor,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // Selection circle
                                SelectionCircle(isSelected = isSelected)
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = WhatsAppDarkDivider,
                                modifier = Modifier.padding(start = 74.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = WhatsAppDarkDivider)

                // ── Action row ────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isForwarding) {
                        Text("Cancel", color = WhatsAppGreen)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedChatIds.isEmpty() || isForwarding) return@Button
                            isForwarding = true
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(context)
                                val repo = com.glyph.glyph_v3.data.repo.RealtimeMessageRepository(
                                    db.messageDao(),
                                    db.chatDao(),
                                    db.deletedMessageDao(),
                                    context
                                )
                                var successCount = 0
                                for (targetChatId in selectedChatIds) {
                                    val targetChat = db.chatDao().getChatById(targetChatId) ?: continue
                                    for (msgId in messageIds) {
                                        try {
                                            if (repo.sendForwardedMessage(
                                                targetChatId  = targetChatId,
                                                originalMessageId = msgId,
                                                otherUserId   = targetChat.otherUserId,
                                                otherUsername = targetChat.otherUsername,
                                                otherUserAvatar = targetChat.otherUserAvatar
                                            )) {
                                                successCount++
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("ForwardPicker", "Forward failed for $msgId to $targetChatId", e)
                                        }
                                    }
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    onForwarded(successCount)
                                }
                            }
                        },
                        enabled = selectedChatIds.isNotEmpty() && !isForwarding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhatsAppGreen,
                            contentColor   = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isForwarding) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Forwarding…")
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_forward_v2),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (selectedChatIds.isEmpty()) "Forward"
                                       else "Forward (${selectedChatIds.size})"
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PinDurationDialog — WhatsApp-style dialog for choosing pin duration
// ─────────────────────────────────────────────────────────────────────────────

/** Duration constants in milliseconds. */
object PinDuration {
    val HOURS_24 = 24L * 60 * 60 * 1000
    val DAYS_7   = 7L  * 24 * 60 * 60 * 1000
    val DAYS_30  = 30L * 24 * 60 * 60 * 1000
}

@Composable
fun PinDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (durationMs: Long) -> Unit
) {
    // 7 days is the default (matching WhatsApp behavior)
    var selected by remember { mutableStateOf(PinDuration.DAYS_7) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF13181C))
                    .padding(top = 28.dp, bottom = 12.dp, start = 24.dp, end = 24.dp)
            ) {
                // ── Title ─────────────────────────────────────────────────────
                Text(
                    text = "Choose how long your pin lasts",
                    color = Color(0xFFE9EDEF),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 26.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── Subtitle ──────────────────────────────────────────────────
                Text(
                    text = "You can unpin at any time.",
                    color = Color(0xFF8696A0),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ── Duration options ──────────────────────────────────────────
                listOf(
                    PinDuration.HOURS_24 to "24 hours",
                    PinDuration.DAYS_7   to "7 days",
                    PinDuration.DAYS_30  to "30 days"
                ).forEach { (duration, label) ->
                    PinDurationOption(
                        label     = label,
                        isSelected = selected == duration,
                        onClick    = { selected = duration }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Buttons ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            color = WhatsAppGreen,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onConfirm(selected) }
                    ) {
                        Text(
                            text = "Pin",
                            color = WhatsAppGreen,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDurationOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Custom radio circle matching WhatsApp style
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Color(0xFF25D366).copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .border(
                    width = if (isSelected) 0.dp else 1.5.dp,
                    color = if (isSelected) Color.Transparent else Color(0xFF8696A0),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(width = 1.5.dp, color = WhatsAppGreen, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(WhatsAppGreen)
                    )
                }
            }
        }

        Text(
            text = label,
            color = Color(0xFFE9EDEF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}