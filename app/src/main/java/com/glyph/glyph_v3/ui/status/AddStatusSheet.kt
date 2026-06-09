package com.glyph.glyph_v3.ui.status

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Data class representing a media item from the device gallery.
 */
data class GalleryMediaItem(
    val uri: Uri,
    val mimeType: String = "",       // authoritative MIME from MediaStore
    val duration: Long = 0L, // ms
    val bucketName: String = "",
    val bucketId: String = "",
    val dateAddedSeconds: Long = 0L
) {
    /** True when the MediaStore MIME type is a video type. */
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

private data class MediaFolder(
    val key: String,
    val label: String,
    val itemCount: Int,
    val previewUri: Uri?,
    val latestTimestamp: Long,
    val sourceType: FolderSourceType
)

private enum class FolderSourceType {
    All,
    Videos,
    Bucket
}

private data class GalleryPickerData(
    val allItems: List<GalleryMediaItem>,
    val folders: List<MediaFolder>
)

/**
 * WhatsApp-style "Add status" bottom sheet with option cards at top
 * and a gallery grid below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStatusSheet(
    onDismiss: () -> Unit,
    onTextStatus: () -> Unit,
    onVoiceStatus: () -> Unit,
    onLayoutStatus: (List<Uri>) -> Unit,
    onMediaSelected: (List<GalleryMediaItem>) -> Unit,
    onCameraClick: () -> Unit,
    onGifStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hasMediaPermission by remember { mutableStateOf(hasMediaReadPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasMediaPermission = granted.values.all { it }
    }
    var pickerData by remember { mutableStateOf<GalleryPickerData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var folderPickerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasMediaPermission) {
            permissionLauncher.launch(mediaReadPermissions())
        }
    }

    LaunchedEffect(hasMediaPermission) {
        if (!hasMediaPermission) {
            pickerData = null
            return@LaunchedEffect
        }

        isLoading = true
        pickerData = withContext(Dispatchers.IO) {
            loadGalleryPickerData(context)
        }
        isLoading = false
    }

    val folders = pickerData?.folders.orEmpty()
    val defaultFolderKey = folders.firstOrNull()?.key ?: FOLDER_KEY_RECENTS
    var selectedFolderKey by remember(defaultFolderKey) { mutableStateOf(defaultFolderKey) }

    LaunchedEffect(defaultFolderKey) {
        if (folders.isNotEmpty() && folders.none { it.key == selectedFolderKey }) {
            selectedFolderKey = defaultFolderKey
        }
    }

    val selectedFolder = remember(folders, selectedFolderKey) {
        folders.firstOrNull { it.key == selectedFolderKey } ?: folders.firstOrNull()
    }
    val filteredItems = remember(pickerData, selectedFolderKey) {
        filterGalleryItems(
            items = pickerData?.allItems.orEmpty(),
            selectedFolderKey = selectedFolderKey
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        dragHandle = null,
        containerColor = theme.backgroundPrimary,
        contentColor = theme.textPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color.Black.copy(alpha = 0.32f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(theme.textTertiary.copy(alpha = 0.4f))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = theme.textPrimary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Add status",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(48.dp))
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StatusOptionCard(
                        icon = Icons.Default.Edit,
                        label = "Text",
                        tint = theme.actionPrimary,
                        onClick = {
                            onDismiss()
                            onTextStatus()
                        }
                    )
                }
                item {
                    StatusOptionCard(
                        icon = Icons.Default.MusicNote,
                        label = "Music",
                        tint = theme.actionPrimary,
                        onClick = { }
                    )
                }
                item {
                    StatusOptionCard(
                        icon = Icons.Default.GridView,
                        label = "Layout",
                        tint = theme.actionPrimary,
                        onClick = {
                            onDismiss()
                            onLayoutStatus(emptyList())
                        }
                    )
                }
                item {
                    StatusOptionCard(
                        icon = Icons.Default.Mic,
                        label = "Voice",
                        tint = theme.actionPrimary,
                        onClick = {
                            onDismiss()
                            onVoiceStatus()
                        }
                    )
                }
                item {
                    StatusOptionCard(
                        icon = Icons.Default.Gif,
                        label = "GIF",
                        tint = theme.actionPrimary,
                        onClick = {
                            onDismiss()
                            onGifStatus()
                        }
                    )
                }
                item {
                    StatusOptionCard(
                        icon = Icons.Default.AutoAwesome,
                        label = "AI images",
                        tint = theme.actionPrimary,
                        onClick = { }
                    )
                }
            }

            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.clickable(enabled = folders.isNotEmpty()) {
                        folderPickerExpanded = !folderPickerExpanded
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedFolder?.label ?: "Recents",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = " ▾",
                        color = theme.textPrimary,
                        fontSize = 14.sp
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasMediaPermission -> {
                        MediaPermissionState(
                            onRequestPermission = {
                                permissionLauncher.launch(mediaReadPermissions())
                            }
                        )
                    }

                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = theme.actionPrimary)
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(theme.backgroundElevated)
                                        .clickable {
                                            onDismiss()
                                            onCameraClick()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = "Camera",
                                            tint = theme.actionPrimary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Camera",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = theme.textPrimary
                                        )
                                    }
                                }
                            }

                            items(filteredItems, key = { it.uri }) { item ->
                                GalleryThumbnail(
                                    item = item,
                                    onClick = {
                                        onDismiss()
                                        onMediaSelected(listOf(item))
                                    }
                                )
                            }
                        }
                    }
                }

                if (folderPickerExpanded && folders.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.12f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                folderPickerExpanded = false
                            }
                    )

                    FolderPickerOverlay(
                        folders = folders,
                        selectedFolderKey = selectedFolderKey,
                        onFolderSelected = { folder ->
                            selectedFolderKey = folder.key
                            folderPickerExpanded = false
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaPermissionState(
    onRequestPermission: () -> Unit
) {
    val theme = glyphTheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PermMedia,
            contentDescription = null,
            tint = theme.actionPrimary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Allow access to your photos and videos",
            style = MaterialTheme.typography.titleMedium,
            color = theme.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Glyph needs media access to show your device folders and recent status content.",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = theme.actionPrimary)
        ) {
            Text(text = "Allow access")
        }
    }
}

@Composable
private fun FolderPickerOverlay(
    folders: List<MediaFolder>,
    selectedFolderKey: String,
    onFolderSelected: (MediaFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme

    Surface(
        modifier = modifier
            .fillMaxWidth(0.68f)
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        color = theme.backgroundElevated,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            items(folders, key = { it.key }) { folder ->
                FolderRow(
                    folder = folder,
                    selected = folder.key == selectedFolderKey,
                    onClick = { onFolderSelected(folder) }
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: MediaFolder,
    selected: Boolean,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderThumbnail(
            previewUri = folder.previewUri,
            contentDescription = folder.label,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.label,
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatItemCount(folder.itemCount),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textSecondary
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = theme.actionPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun FolderThumbnail(
    previewUri: Uri?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val fallbackPainter = rememberVectorPainter(Icons.Default.PhotoLibrary)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = theme.backgroundPrimary
    ) {
        if (previewUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(previewUri)
                    .size(200)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = fallbackPainter,
                placeholder = fallbackPainter
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.backgroundPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = theme.actionPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusOptionCard(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = theme.backgroundElevated,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            theme.borderSecondary.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .width(100.dp)
            .height(80.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GalleryThumbnail(
    item: GalleryMediaItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val durationText = remember(item.duration) { formatDuration(item.duration) }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri)
                .crossfade(true)
                .size(300)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video duration overlay
        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎬",
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = durationText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (item.mimeType == "image/gif") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "GIF",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Query the device MediaStore for recent images and videos.
 */
fun loadGalleryMedia(context: Context, limit: Int = 200): List<GalleryMediaItem> {
    return loadGalleryPickerData(context).allItems.take(limit)
}

private fun loadGalleryPickerData(context: Context): GalleryPickerData {
    val items = mutableListOf<GalleryMediaItem>()

    val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID
    )
    val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_ID
    )

    // Images
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        imageProjection,
        null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        val bucketCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

        while (cursor.moveToNext()) {
            val id       = cursor.getLong(idCol)
            val mime     = cursor.getString(mimeCol) ?: "image/jpeg"
            val bucket   = cursor.getString(bucketCol) ?: ""
            val bucketId = cursor.getString(bucketIdCol) ?: bucket
            val date     = cursor.getLong(dateCol)
            val uri      = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            items.add(
                GalleryMediaItem(
                    uri              = uri,
                    mimeType         = mime,
                    bucketName       = bucket,
                    bucketId         = bucketId,
                    dateAddedSeconds = date
                )
            )
        }
    }

    // Videos
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        videoProjection,
        null, null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        val bucketCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
        val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

        while (cursor.moveToNext()) {
            val id       = cursor.getLong(idCol)
            val duration = cursor.getLong(durationCol)
            // Default to video/mp4 when MIME is empty — all rows here come from the Video table
            val mime     = cursor.getString(mimeCol)?.takeIf { it.isNotBlank() } ?: "video/mp4"
            val bucket   = cursor.getString(bucketCol) ?: ""
            val bucketId = cursor.getString(bucketIdCol) ?: bucket
            val date     = cursor.getLong(dateCol)
            val uri      = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            items.add(
                GalleryMediaItem(
                    uri              = uri,
                    mimeType         = mime,
                    duration         = duration,
                    bucketName       = bucket,
                    bucketId         = bucketId,
                    dateAddedSeconds = date
                )
            )
        }
    }

    val sortedItems = items.sortedByDescending { it.dateAddedSeconds }
    return GalleryPickerData(
        allItems = sortedItems,
        folders = buildMediaFolders(sortedItems)
    )
}

private fun buildMediaFolders(items: List<GalleryMediaItem>): List<MediaFolder> {
    if (items.isEmpty()) {
        return listOf(
            MediaFolder(
                key = FOLDER_KEY_RECENTS,
                label = "Recents",
                itemCount = 0,
                previewUri = null,
                latestTimestamp = 0L,
                sourceType = FolderSourceType.All
            )
        )
    }

    val folders = mutableListOf<MediaFolder>()
    folders += MediaFolder(
        key = FOLDER_KEY_RECENTS,
        label = "Recents",
        itemCount = items.size,
        previewUri = items.firstOrNull()?.uri,
        latestTimestamp = items.maxOfOrNull { it.dateAddedSeconds } ?: 0L,
        sourceType = FolderSourceType.All
    )

    val videos = items.filter { it.isVideo }
    if (videos.isNotEmpty()) {
        folders += MediaFolder(
            key = FOLDER_KEY_VIDEOS,
            label = "Videos",
            itemCount = videos.size,
            previewUri = videos.firstOrNull()?.uri,
            latestTimestamp = videos.maxOfOrNull { it.dateAddedSeconds } ?: 0L,
            sourceType = FolderSourceType.Videos
        )
    }

    val groupedBuckets = items
        .filter { it.bucketId.isNotBlank() || it.bucketName.isNotBlank() }
        .groupBy { bucketGroupKey(it) }
        .mapNotNull { (_, bucketItems) ->
            val first = bucketItems.firstOrNull() ?: return@mapNotNull null
            MediaFolder(
                key = folderKeyForBucket(first.bucketId.ifBlank { first.bucketName }),
                label = first.bucketName.ifBlank { "Unknown" },
                itemCount = bucketItems.size,
                previewUri = bucketItems.firstOrNull()?.uri,
                latestTimestamp = bucketItems.maxOfOrNull { it.dateAddedSeconds } ?: 0L,
                sourceType = FolderSourceType.Bucket
            )
        }
        .sortedWith(compareByDescending<MediaFolder> { folderPriority(it.label) }.thenByDescending { it.latestTimestamp })

    return folders + groupedBuckets
}

private fun filterGalleryItems(
    items: List<GalleryMediaItem>,
    selectedFolderKey: String
): List<GalleryMediaItem> {
    return when {
        selectedFolderKey == FOLDER_KEY_RECENTS -> items
        selectedFolderKey == FOLDER_KEY_VIDEOS -> items.filter { it.isVideo }
        selectedFolderKey.startsWith(FOLDER_KEY_BUCKET_PREFIX) -> {
            val bucketKey = selectedFolderKey.removePrefix(FOLDER_KEY_BUCKET_PREFIX)
            items.filter { bucketGroupKey(it) == bucketKey }
        }
        else -> items
    }
}

private fun hasMediaReadPermission(context: Context): Boolean {
    val permissions = mediaReadPermissions()
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun mediaReadPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun bucketGroupKey(item: GalleryMediaItem): String {
    return item.bucketId.ifBlank { item.bucketName.trim().lowercase(Locale.ROOT) }
}

private fun folderKeyForBucket(bucketValue: String): String {
    return FOLDER_KEY_BUCKET_PREFIX + bucketValue
}

private fun folderPriority(label: String): Int {
    return when (label.lowercase(Locale.ROOT)) {
        "camera" -> 100
        "downloads" -> 90
        "whatsapp" -> 80
        "screenshots" -> 70
        else -> 10
    }
}

private fun formatItemCount(count: Int): String {
    return NumberFormat.getIntegerInstance().format(count) + " items"
}

private const val FOLDER_KEY_RECENTS = "folder_recents"
private const val FOLDER_KEY_VIDEOS = "folder_videos"
private const val FOLDER_KEY_BUCKET_PREFIX = "folder_bucket_"
