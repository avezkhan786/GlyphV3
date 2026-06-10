package com.glyph.glyph_v3.ui.status

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.ViewerInfo
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusViewersSheet(
    viewers: List<ViewerInfo>,
    expectedCount: Int,
    onDismiss: () -> Unit
) {
    val theme = glyphTheme
    val isLoading = viewers.isEmpty() && expectedCount > 0
    // Stable count shown in the header: use real count if available, fallback to expected
    val displayCount by remember(viewers.size, expectedCount) {
        derivedStateOf { if (viewers.isNotEmpty()) viewers.size else expectedCount }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = theme.backgroundElevated,
        contentColor = theme.textPrimary,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = theme.actionPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (displayCount == 0) "No views yet" else "Viewed by $displayCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary
                )
            }

            HorizontalDivider(
                color = theme.divider,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            when {
                expectedCount == 0 && viewers.isEmpty() -> {
                    // Confirmed empty
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No one has viewed this status yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.textSecondary
                        )
                    }
                }

                isLoading -> {
                    // Skeleton rows — one per expected viewer, renders instantly
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(expectedCount) {
                            ViewerSkeletonRow()
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(viewers, key = { it.user.id }) { info ->
                            ViewerRow(info = info)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerRow(info: ViewerInfo) {
    val theme = glyphTheme
    val context = LocalContext.current
    val seenText = remember(info.seenAt) { formatSeenAt(info.seenAt) }
    val avatarRequest = remember(info.user.profileImageUrl) {
        ImageRequest.Builder(context)
            .data(info.user.profileImageUrl.ifEmpty { null })
            .placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
            .memoryCacheKey(info.user.profileImageUrl)
            .diskCacheKey(info.user.profileImageUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = avatarRequest,
            contentDescription = info.user.username,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ContactDisplayNameResolver.getDisplayName(
                    otherUserId = info.user.id,
                    remoteProfileName = info.user.username,
                    remotePhoneNumber = info.user.phoneNumber
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (seenText.isNotEmpty()) {
                Text(
                    text = seenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ViewerSkeletonRow() {
    val theme = glyphTheme
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmerX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1100, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            theme.backgroundElevated,
            theme.textTertiary.copy(alpha = 0.18f),
            theme.backgroundElevated
        ),
        start = Offset(shimmerX, 0f),
        end = Offset(shimmerX + 300f, 100f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(shimmerBrush)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.30f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmerBrush)
            )
        }
    }
}

/**
 * Returns a human-readable "seen at" string.
 * - 0 / unknown → empty (no label shown)
 * - Today → "Today at 3:45 PM"
 * - Yesterday → "Yesterday at 11:20 AM"
 * - This year → "Jan 12 at 9:00 AM"
 * - Older → "Jan 12, 2025 at 9:00 AM"
 */
private fun formatSeenAt(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val timePart = SimpleDateFormat("h:mm a", Locale.getDefault()).format(epochMs)

    val now = Calendar.getInstance()
    val seen = Calendar.getInstance().apply { timeInMillis = epochMs }

    return when {
        DateUtils.isToday(epochMs) -> "Today at $timePart"
        isYesterday(seen, now) -> "Yesterday at $timePart"
        seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> {
            val datePart = SimpleDateFormat("MMM d", Locale.getDefault()).format(epochMs)
            "$datePart at $timePart"
        }
        else -> {
            val datePart = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(epochMs)
            "$datePart at $timePart"
        }
    }
}

private fun isYesterday(seen: Calendar, now: Calendar): Boolean {
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return seen.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        seen.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
}
