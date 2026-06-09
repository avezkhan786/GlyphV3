package com.glyph.glyph_v3.ui.status

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * WhatsApp-style layout/collage multi-select gallery screen.
 * Select up to 6 images, shows numbered badges on selections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutPickerScreen(
    onDone: (List<Uri>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var galleryItems by remember { mutableStateOf<List<GalleryMediaItem>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Load gallery (images only for layout)
    LaunchedEffect(Unit) {
        galleryItems = loadGalleryMedia(context, limit = 300)
            .filter { !it.isVideo }
    }

    // Bucket filtering
    val buckets = remember(galleryItems) {
        listOf("All") + galleryItems.map { it.bucketName }.distinct().filter { it.isNotEmpty() }
    }
    var selectedBucket by remember { mutableStateOf("All") }
    val filteredItems = remember(galleryItems, selectedBucket) {
        if (selectedBucket == "All") galleryItems
        else galleryItems.filter { it.bucketName == selectedBucket }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
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
        // ── Drag handle ──
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

        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = theme.textPrimary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Start layout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.size(48.dp))
        }

        // Subtitle
        Text(
            text = "Choose up to 6 photos for your layout.",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            textAlign = TextAlign.Center
        )

        // ── Bucket dropdown ──
        var bucketExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.clickable { bucketExpanded = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedBucket == "All") "Recent" else selectedBucket,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = theme.textPrimary
                )
                Text(text = " ▾", color = theme.textPrimary, fontSize = 14.sp)
            }
            DropdownMenu(
                expanded = bucketExpanded,
                onDismissRequest = { bucketExpanded = false }
            ) {
                buckets.forEach { bucket ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (bucket == "All") "Recent" else bucket,
                                color = theme.textPrimary
                            )
                        },
                        onClick = {
                            selectedBucket = bucket
                            bucketExpanded = false
                        }
                    )
                }
            }
        }

        // ── Grid ──
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(filteredItems) { item ->
                val selectionIndex = selectedUris.indexOf(item.uri)
                val isSelected = selectionIndex >= 0

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .clickable {
                            selectedUris = if (isSelected) {
                                selectedUris.filter { it != item.uri }
                            } else if (selectedUris.size < 6) {
                                selectedUris + item.uri
                            } else {
                                selectedUris
                            }
                        }
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

                    // Dimmed overlay when selected
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f))
                        )
                    }

                    // Selection badge (top-right)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) theme.actionPrimary
                                else Color.White.copy(alpha = 0.6f)
                            )
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Text(
                                text = "${selectionIndex + 1}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom bar with thumbnail strip + confirm button ──
            if (selectedUris.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.backgroundElevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedUris.forEach { uri ->
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(uri)
                                    .crossfade(true)
                                    .size(120)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    FloatingActionButton(
                        onClick = { onDone(selectedUris) },
                        containerColor = theme.actionPrimary,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Done",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
