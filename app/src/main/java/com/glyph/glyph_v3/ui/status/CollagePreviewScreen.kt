package com.glyph.glyph_v3.ui.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Defines different layout grid arrangements for collages.
 */
enum class CollageLayout(val label: String) {
    GRID_2x2("2×2"),
    VERTICAL_STACK("Vertical"),
    TOP_LARGE("1+2"),
    BOTTOM_LARGE("2+1"),
    GRID_2x3("2×3"),
    GRID_3x2("3×2")
}

/**
 * WhatsApp-style collage/layout preview screen.
 * After selecting images, users can switch between different grid layouts
 * and then send the collage as a single image status.
 */
@Composable
fun CollagePreviewScreen(
    imageUris: List<Uri>,
    isUploading: Boolean,
    onSend: (List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val theme = glyphTheme
    var selectedLayout by remember { mutableStateOf(CollageLayout.GRID_2x2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Surface(
                onClick = {},
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = "Done",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                    fontSize = 14.sp
                )
            }
        }

        // ── Collage preview area ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            CollageGrid(
                uris = imageUris,
                layout = selectedLayout,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        when (selectedLayout) {
                            CollageLayout.VERTICAL_STACK -> 0.65f
                            CollageLayout.TOP_LARGE, CollageLayout.BOTTOM_LARGE -> 0.75f
                            else -> 1f
                        }
                    )
            )
        }

        // ── Layout selector strip ──
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val layouts = getAvailableLayouts(imageUris.size)
            itemsIndexed(layouts) { _, layout ->
                LayoutOption(
                    layout = layout,
                    isSelected = selectedLayout == layout,
                    imageCount = imageUris.size,
                    onClick = { selectedLayout = layout }
                )
            }
        }

        // ── Bottom send bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            FloatingActionButton(
                onClick = {
                    if (!isUploading) {
                        onSend(imageUris)
                    }
                },
                containerColor = theme.actionPrimary,
                modifier = Modifier.size(52.dp)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Renders images in the selected collage layout.
 */
@Composable
private fun CollageGrid(
    uris: List<Uri>,
    layout: CollageLayout,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    when (layout) {
        CollageLayout.GRID_2x2 -> {
            Column(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (rowStart in uris.indices step 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in rowStart until minOf(rowStart + 2, uris.size)) {
                            CollageImage(
                                uri = uris[i],
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                        // Fill placeholders if odd number
                        if (rowStart + 1 >= uris.size && uris.size % 2 != 0) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color.DarkGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack, // placeholder icon
                                    contentDescription = null,
                                    tint = Color.Gray.copy(alpha = 0.5f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        CollageLayout.VERTICAL_STACK -> {
            Column(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                uris.forEach { uri ->
                    CollageImage(
                        uri = uri,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        CollageLayout.TOP_LARGE -> {
            Column(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Top large image
                if (uris.isNotEmpty()) {
                    CollageImage(
                        uri = uris[0],
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(2f)
                    )
                }
                // Bottom row
                if (uris.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in 1 until uris.size) {
                            CollageImage(
                                uri = uris[i],
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }

        CollageLayout.BOTTOM_LARGE -> {
            Column(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Top row
                if (uris.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in 0 until uris.size - 1) {
                            CollageImage(
                                uri = uris[i],
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
                // Bottom large
                CollageImage(
                    uri = uris.last(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                )
            }
        }

        CollageLayout.GRID_2x3 -> {
            Column(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Top row: 2 images
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (i in 0 until minOf(2, uris.size)) {
                        CollageImage(
                            uri = uris[i],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
                // Middle row: next 2
                if (uris.size > 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in 2 until minOf(4, uris.size)) {
                            CollageImage(
                                uri = uris[i],
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
                // Bottom row: remainder
                if (uris.size > 4) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in 4 until uris.size) {
                            CollageImage(
                                uri = uris[i],
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }

        CollageLayout.GRID_3x2 -> {
            Row(
                modifier = modifier.clip(RoundedCornerShape(12.dp)),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Left column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val leftItems = uris.filterIndexed { idx, _ -> idx % 2 == 0 }
                    leftItems.forEach { uri ->
                        CollageImage(
                            uri = uri,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
                // Right column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val rightItems = uris.filterIndexed { idx, _ -> idx % 2 == 1 }
                    rightItems.forEach { uri ->
                        CollageImage(
                            uri = uri,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                    // Placeholder if odd
                    if (uris.size % 2 != 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.DarkGray)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageImage(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(2.dp))
    )
}

@Composable
private fun LayoutOption(
    layout: CollageLayout,
    isSelected: Boolean,
    imageCount: Int,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isSelected) theme.backgroundPrimary else Color.Transparent,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, Color.White)
        } else null,
        modifier = Modifier.size(48.dp)
    ) {
        // Mini layout preview icon
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            MiniLayoutPreview(layout, isSelected)
        }
    }
}

@Composable
private fun MiniLayoutPreview(layout: CollageLayout, isSelected: Boolean) {
    val color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f)
    val size = 28.dp
    val gap = 2.dp

    when (layout) {
        CollageLayout.GRID_2x2 -> {
            Column(
                modifier = Modifier.size(size),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                }
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                }
            }
        }
        CollageLayout.VERTICAL_STACK -> {
            Column(
                modifier = Modifier.size(size),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Box(Modifier.fillMaxWidth().weight(1f).background(color, RoundedCornerShape(1.dp)))
                Box(Modifier.fillMaxWidth().weight(1f).background(color, RoundedCornerShape(1.dp)))
            }
        }
        CollageLayout.TOP_LARGE -> {
            Column(
                modifier = Modifier.size(size),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Box(Modifier.fillMaxWidth().weight(2f).background(color, RoundedCornerShape(1.dp)))
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                }
            }
        }
        CollageLayout.BOTTOM_LARGE -> {
            Column(
                modifier = Modifier.size(size),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                }
                Box(Modifier.fillMaxWidth().weight(2f).background(color, RoundedCornerShape(1.dp)))
            }
        }
        CollageLayout.GRID_2x3 -> {
            Column(
                modifier = Modifier.size(size),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                repeat(3) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        CollageLayout.GRID_3x2 -> {
            Row(
                modifier = Modifier.size(size),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                repeat(2) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        Box(Modifier.fillMaxWidth().weight(1f).background(color, RoundedCornerShape(1.dp)))
                        Box(Modifier.fillMaxWidth().weight(1f).background(color, RoundedCornerShape(1.dp)))
                        Box(Modifier.fillMaxWidth().weight(1f).background(color, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
    }
}

/**
 * Returns layouts available for the given image count.
 */
private fun getAvailableLayouts(count: Int): List<CollageLayout> {
    return when {
        count <= 1 -> listOf(CollageLayout.GRID_2x2)
        count == 2 -> listOf(CollageLayout.GRID_2x2, CollageLayout.VERTICAL_STACK)
        count == 3 -> listOf(CollageLayout.GRID_2x2, CollageLayout.VERTICAL_STACK, CollageLayout.TOP_LARGE, CollageLayout.BOTTOM_LARGE)
        count <= 4 -> listOf(CollageLayout.GRID_2x2, CollageLayout.VERTICAL_STACK, CollageLayout.TOP_LARGE, CollageLayout.BOTTOM_LARGE, CollageLayout.GRID_2x3)
        else -> CollageLayout.entries.toList()
    }
}
