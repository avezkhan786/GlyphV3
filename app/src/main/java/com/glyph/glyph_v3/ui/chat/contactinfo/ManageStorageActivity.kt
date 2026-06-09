package com.glyph.glyph_v3.ui.chat.contactinfo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.media.MediaViewerActivity
import com.glyph.glyph_v3.ui.media.VideoPlayerActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ──────────────────────────────────────────────────────
// WhatsApp-style Manage Storage screen
// ──────────────────────────────────────────────────────

private val WaDarkBg = Color(0xFF0B141A)
private val WaDarkSurface = Color(0xFF111B21)
private val WaDivider = Color(0xFF222D34)
private val WaGreen = Color(0xFF00A884)
private val WaTextPrimary = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaSelectedBorder = Color(0xFF00A884)

/** Lightweight model for storage items */
internal data class StorageMediaItem(
    val messageId: String,
    val thumbnailUrl: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val timestamp: Long
)

/** Sort modes for storage view */
enum class StorageSortMode(val label: String) {
    SIZE("Size"),
    NEWEST("Newest"),
    OLDEST("Oldest")
}

class ManageStorageActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_CONTACT_NAME = "extra_contact_name"
        private const val EXTRA_CONTACT_AVATAR = "extra_contact_avatar"

        fun newIntent(
            context: Context,
            chatId: String,
            contactName: String,
            contactAvatar: String = ""
        ): Intent {
            return Intent(context, ManageStorageActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_AVATAR, contactAvatar)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
        val contactAvatar = intent.getStringExtra(EXTRA_CONTACT_AVATAR) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                ManageStorageScreen(
                    chatId = chatId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageStorageScreen(
    chatId: String,
    contactName: String,
    contactAvatar: String,
    onBackClick: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val surfaceBg = if (theme.isDark) WaDarkBg else theme.backgroundPrimary
    val cardBg = if (theme.isDark) WaDarkSurface else theme.backgroundElevated
    val dividerColor = if (theme.isDark) WaDivider else theme.divider
    val textPrimary = if (theme.isDark) WaTextPrimary else theme.textPrimary
    val textSecondary = if (theme.isDark) WaTextSecondary else theme.textSecondary
    val accentGreen = if (theme.isDark) WaGreen else theme.actionPrimary

    // ── State ────────────────────────────────────────────────────
    var mediaItems by remember { mutableStateOf<List<StorageMediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalSizeBytes by remember { mutableLongStateOf(0L) }
    var sortMode by remember { mutableStateOf(StorageSortMode.SIZE) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var selectAllChecked by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ── Load media items ─────────────────────────────────────────
    LaunchedEffect(chatId) {
        isLoading = true
        mediaItems = loadStorageItems(chatId, context)
        totalSizeBytes = mediaItems.sumOf { it.sizeBytes }
        isLoading = false
    }

    // ── Sorted items ─────────────────────────────────────────────
    val sortedItems by remember(mediaItems, sortMode) {
        derivedStateOf {
            when (sortMode) {
                StorageSortMode.SIZE -> mediaItems.sortedByDescending { it.sizeBytes }
                StorageSortMode.NEWEST -> mediaItems.sortedByDescending { it.timestamp }
                StorageSortMode.OLDEST -> mediaItems.sortedBy { it.timestamp }
            }
        }
    }

    // Select all tracking
    LaunchedEffect(selectAllChecked) {
        selectedIds = if (selectAllChecked) sortedItems.map { it.messageId }.toSet() else emptySet()
    }

    val isSelectionMode = selectedIds.isNotEmpty()

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            "${selectedIds.size} selected",
                            color = textPrimary,
                            fontSize = 20.sp
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Small avatar
                            if (contactAvatar.isNotEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(contactAvatar)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column {
                                Text(
                                    contactName,
                                    color = textPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatBytes(totalSizeBytes),
                                    color = textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            selectedIds = emptySet()
                            selectAllChecked = false
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            painter = painterResource(
                                id = if (isSelectionMode) R.drawable.ic_arrow_back else R.drawable.ic_arrow_back
                            ),
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            showDeleteConfirm = true
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = "Delete",
                                tint = Color(0xFFEF5350)
                            )
                        }
                    } else {
                        // Sort button
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_list),
                                    contentDescription = "Sort",
                                    tint = textPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                containerColor = cardBg
                            ) {
                                StorageSortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                mode.label,
                                                color = if (sortMode == mode) accentGreen else textPrimary,
                                                fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            sortMode = mode
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(surfaceBg)
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // Sort label + Select all
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sortMode.label.uppercase(),
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        selectAllChecked = !selectAllChecked
                    }
                ) {
                    Text(
                        text = "Select all",
                        color = textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Checkbox(
                        checked = selectAllChecked,
                        onCheckedChange = { selectAllChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = accentGreen,
                            uncheckedColor = textSecondary,
                            checkmarkColor = Color.Black
                        )
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = accentGreen, strokeWidth = 2.dp)
                }
            } else if (sortedItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No media in this chat",
                        color = textSecondary,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sortedItems, key = { it.messageId }) { item ->
                        StorageMediaTile(
                            item = item,
                            isSelected = item.messageId in selectedIds,
                            accentGreen = accentGreen,
                            textPrimary = textPrimary,
                            onTap = {
                                if (isSelectionMode) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    selectedIds = if (item.messageId in selectedIds) {
                                        selectedIds - item.messageId
                                    } else {
                                        selectedIds + item.messageId
                                    }
                                    selectAllChecked = selectedIds.size == sortedItems.size
                                } else {
                                    // Open viewer
                                    if (item.isVideo) {
                                        context.startActivity(
                                            VideoPlayerActivity.newIntent(context, item.thumbnailUrl)
                                        )
                                    } else {
                                        context.startActivity(
                                            MediaViewerActivity.newIntent(context, item.thumbnailUrl)
                                        )
                                    }
                                }
                            },
                            onLongPress = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                selectedIds = selectedIds + item.messageId
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = if (theme.isDark) WaDarkSurface else theme.backgroundElevated,
            title = {
                Text(
                    "Delete ${selectedIds.size} item${if (selectedIds.size > 1) "s" else ""}?",
                    color = textPrimary,
                    fontWeight = FontWeight.Medium
                )
            },
            text = {
                val selectedSize = sortedItems
                    .filter { it.messageId in selectedIds }
                    .sumOf { it.sizeBytes }
                Text(
                    "This will free up ${formatBytes(selectedSize)} of storage. This action cannot be undone.",
                    color = textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Remove from local state (actual Firestore/Room deletion would happen here)
                    mediaItems = mediaItems.filter { it.messageId !in selectedIds }
                    totalSizeBytes = mediaItems.sumOf { it.sizeBytes }
                    selectedIds = emptySet()
                    selectAllChecked = false
                    showDeleteConfirm = false
                }) {
                    Text("DELETE", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL", color = accentGreen)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageMediaTile(
    item: StorageMediaItem,
    isSelected: Boolean,
    accentGreen: Color,
    textPrimary: Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val borderMod = if (isSelected) {
        Modifier.border(2.dp, accentGreen, RoundedCornerShape(4.dp))
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(borderMod)
            .background(Color(0xFF1A2328))
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
    ) {
        // Resolve URI for Coil
        val imageData: Any = when {
            item.thumbnailUrl.startsWith("/") -> java.io.File(item.thumbnailUrl)
            item.thumbnailUrl.startsWith("file://") ->
                java.io.File(android.net.Uri.parse(item.thumbnailUrl).path ?: item.thumbnailUrl)
            item.thumbnailUrl.startsWith("content://") ->
                android.net.Uri.parse(item.thumbnailUrl)
            else -> item.thumbnailUrl
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageData)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Size badge (top-right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatBytes(item.sizeBytes),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Video badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Selection checkmark overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(accentGreen.copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accentGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Load media items for a chat with size information.
 * Uses Room local DB first, falls back to Firestore.
 */
private suspend fun loadStorageItems(
    chatId: String,
    context: Context
): List<StorageMediaItem> {
    if (chatId.isEmpty()) return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<StorageMediaItem>()

            // Try Room local DB first
            try {
                val db = com.glyph.glyph_v3.data.local.AppDatabase.getDatabase(context)
                val localMessages = db.messageDao().getMediaMessages(chatId, 200)

                for (msg: com.glyph.glyph_v3.data.local.entity.LocalMessage in localMessages) {
                    val url = resolveMediaUrl(msg.localUri, msg.imageUrl ?: msg.videoUrl)
                    if (url != null) {
                        val fileSize = msg.fileSize ?: estimateFileSize(url, context)
                        items.add(
                            StorageMediaItem(
                                messageId = msg.id,
                                thumbnailUrl = url,
                                isVideo = msg.type == com.glyph.glyph_v3.data.models.MessageType.VIDEO,
                                sizeBytes = fileSize,
                                timestamp = msg.timestamp
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ManageStorage", "Room fallback: ${e.message}")
            }

            // If Room returned nothing, try Firestore
            if (items.isEmpty()) {
                try {
                    val snapshot = FirebaseFirestore.getInstance()
                        .collection("chats").document(chatId)
                        .collection("messages")
                        .whereIn("type", listOf("image", "video"))
                        .orderBy("timestamp", Query.Direction.DESCENDING)
                        .limit(200)
                        .get()
                        .await()

                    for (doc in snapshot.documents) {
                        val type = doc.getString("type") ?: continue
                        val mediaUrl = doc.getString("mediaUrl") ?: continue
                        val thumb = doc.getString("thumbnailUrl") ?: mediaUrl
                        val ts = doc.getLong("timestamp") ?: 0L
                        val size = doc.getLong("fileSize") ?: estimateFileSize(thumb, context)

                        items.add(
                            StorageMediaItem(
                                messageId = doc.id,
                                thumbnailUrl = thumb,
                                isVideo = type == "video",
                                sizeBytes = size,
                                timestamp = ts
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ManageStorage", "Firestore fallback: ${e.message}")
                }
            }

            items
        } catch (e: Exception) {
            android.util.Log.e("ManageStorage", "loadStorageItems error: ${e.message}")
            emptyList()
        }
    }
}

/** Resolve media URL same as ContactInfoScreen */
private fun resolveMediaUrl(localUri: String?, remoteUrl: String?): String? {
    if (!localUri.isNullOrEmpty()) {
        if (localUri.startsWith("content://")) return localUri
        val path = if (localUri.startsWith("file://")) {
            android.net.Uri.parse(localUri).path ?: localUri
        } else localUri
        if (path.startsWith("/") && java.io.File(path).exists()) return path
    }
    return remoteUrl?.takeIf { it.isNotEmpty() }
}

/** Estimate file size from local path or return a placeholder */
private fun estimateFileSize(url: String, context: Context): Long {
    return try {
        when {
            url.startsWith("/") -> java.io.File(url).length()
            url.startsWith("file://") -> {
                val path = android.net.Uri.parse(url).path
                if (path != null) java.io.File(path).length() else 0L
            }
            url.startsWith("content://") -> {
                val uri = android.net.Uri.parse(url)
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize
                } ?: 0L
            }
            else -> 0L // Remote URL — size unknown without HEAD request
        }
    } catch (_: Exception) {
        0L
    }
}
