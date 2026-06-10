package com.glyph.glyph_v3.ui.status

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.models.UserStatusGroup
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import androidx.compose.ui.res.colorResource
import kotlin.math.roundToInt

@Composable
fun StatusListScreen(
    uiState: StatusUiState,
    onMyStatusClick: () -> Unit,
    onAddTextStatus: () -> Unit,
    onAddMediaStatus: () -> Unit,
    onContactStatusClick: (UserStatusGroup) -> Unit
) {
    val context = LocalContext.current
    val theme = glyphTheme
    val currentTheme = ThemeManager.getCurrentTheme(context)
    val contactGroups = uiState.contactStatusGroups
    val (unviewedGroups, viewedGroups) = remember(contactGroups) {
        contactGroups.partition { !it.allViewed }
    }
    val surfaceBackgroundColor = theme.backgroundPrimary

    Scaffold(
        containerColor = if (theme.gradientPrimary != null) Color.Transparent else surfaceBackgroundColor,
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = onAddTextStatus,
                    containerColor = theme.backgroundElevated,
                    contentColor = theme.iconSecondary
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Text status")
                }
                FloatingActionButton(
                    onClick = onAddMediaStatus,
                    containerColor = theme.actionPrimary,
                    contentColor = theme.textInverse
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(
                    if (theme.gradientPrimary != null) {
                        Modifier.background(theme.gradientPrimary!!)
                    } else {
                        Modifier.background(surfaceBackgroundColor)
                    }
                ),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── My Status ──
            item {
                MyStatusRow(
                    statuses = uiState.myStatuses,
                    username = uiState.myUsername,
                    avatarUrl = uiState.myAvatarUrl,
                    isUploading = uiState.isUploading,
                    uploadProgress = uiState.uploadProgress,
                    uploadStage = uiState.uploadStage,
                    onClick = onMyStatusClick,
                    onAddClick = onAddMediaStatus
                )
            }

            // ── Recent updates header ──
            if (contactGroups.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent updates",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (unviewedGroups.isEmpty()) {
                    item {
                        RecentUpdatesEmptyState()
                    }
                }

                // ── Unviewed statuses ──
                items(unviewedGroups, key = { it.userId }) { group ->
                    ContactStatusRow(
                        group = group,
                        onClick = { onContactStatusClick(group) }
                    )
                }

                // ── Viewed statuses ──
                if (viewedGroups.isNotEmpty()) {
                    item {
                        Text(
                            text = "Viewed updates",
                            style = MaterialTheme.typography.labelMedium,
                            color = theme.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(viewedGroups, key = { it.userId }) { group ->
                        ContactStatusRow(
                            group = group,
                            onClick = { onContactStatusClick(group) }
                        )
                    }
                }
            }

            // ── Empty state ──
            if (contactGroups.isEmpty() && uiState.myStatuses.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val composition by rememberLottieComposition(
                            LottieCompositionSpec.Asset("panda_sleeping.lottie")
                        )
                        val progress by animateLottieCompositionAsState(
                            composition = composition,
                            iterations = LottieConstants.IterateForever
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LottieAnimation(
                                composition = composition,
                                progress = { progress },
                                modifier = Modifier.size(220.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No status updates yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = theme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the camera or pencil button to share a status",
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyStatusRow(
    statuses: List<com.glyph.glyph_v3.data.models.Status>,
    username: String,
    avatarUrl: String,
    isUploading: Boolean,
    uploadProgress: Float,
    uploadStage: UploadStage,
    onClick: () -> Unit,
    onAddClick: () -> Unit
) {
    val theme = glyphTheme
    val determinate = uploadStage != UploadStage.PREPARING && uploadStage != UploadStage.COMPRESSING
    val showsPercent = uploadStage == UploadStage.UPLOADING || uploadStage == UploadStage.DONE
    val animatedProgress by animateFloatAsState(
        targetValue = uploadProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "statusListUploadProgress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (statuses.isNotEmpty()) onClick() else onAddClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            StatusAvatar(
                imageUrl = avatarUrl,
                statusCount = statuses.size,
                viewedCount = statuses.size, // My own statuses always "viewed"
                size = 54
            )
            if (statuses.isEmpty()) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add",
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(theme.actionPrimary)
                        .padding(2.dp),
                    tint = theme.textInverse
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "My status",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary
            )
            if (isUploading) {
                Text(
                    text = if (determinate && showsPercent) {
                        "${uploadStage.label} ${("${(uploadProgress * 100).roundToInt()}%")}".trim()
                    } else {
                        uploadStage.label
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (determinate) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(theme.textSecondary.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(RoundedCornerShape(999.dp))
                                .background(theme.actionPrimary)
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = theme.actionPrimary,
                        trackColor = theme.textSecondary.copy(alpha = 0.2f)
                    )
                }
            } else {
                Text(
                    text = if (statuses.isNotEmpty()) {
                        "Tap to view · ${statuses.size} update${if (statuses.size > 1) "s" else ""}"
                    } else {
                        "Tap to add status update"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary
                )
            }
        }
    }

    HorizontalDivider(color = theme.divider, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun RecentUpdatesEmptyState() {
    val theme = glyphTheme
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("panda_sleeping.lottie")
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .size(220.dp)
                .offset(y = (-20).dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "You’re all caught up right now.",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            modifier = Modifier.offset(y = (-50).dp)
        )
    }
}

@Composable
private fun ContactStatusRow(
    group: UserStatusGroup,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    val myUid = remember { StatusRepository.currentUserId ?: "" }
    val viewedCount = remember(group.statuses, myUid) {
        group.statuses.count { myUid in it.viewerIds }
    }
    val timestampText = remember(group.lastStatusTimestamp) {
        DateUtils.getRelativeTimeSpanString(
            group.lastStatusTimestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusAvatar(
            imageUrl = group.profileImageUrl,
            statusCount = group.statuses.size,
            viewedCount = viewedCount,
            size = 50
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ContactDisplayNameResolver.getDisplayName(
                    otherUserId = group.userId,
                    remoteProfileName = group.username
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = theme.textPrimary
            )
            Text(
                text = timestampText,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )
        }
    }
}

@Composable
fun StatusAvatar(
    imageUrl: String,
    statusCount: Int = 0,
    viewedCount: Int = 0,
    size: Int = 50
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val hasStatus = statusCount > 0
    val allViewed = viewedCount >= statusCount

    val unviewedColor = theme.actionPrimary
    val viewedColor = theme.borderSecondary
    val strokeWidthDp = 2.5f
    val gapDegrees = if (statusCount > 1) 6f else 0f
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { strokeWidthDp.dp.toPx() } }

    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
        // Segmented ring
        if (hasStatus) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = strokeWidthPx / 2f
                val arcSize = Size(this.size.width - strokeWidthPx, this.size.height - strokeWidthPx)
                val topLeft = Offset(inset, inset)

                if (statusCount == 1) {
                    // Single full ring
                    drawArc(
                        color = if (allViewed) viewedColor else unviewedColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx)
                    )
                } else {
                    val totalGap = gapDegrees * statusCount
                    val segmentSweep = (360f - totalGap) / statusCount

                    for (i in 0 until statusCount) {
                        val startAngle = -90f + i * (segmentSweep + gapDegrees)
                        val color = if (i < viewedCount) viewedColor else unviewedColor
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = segmentSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx)
                        )
                    }
                }
            }
        }

        // Avatar image inside the ring
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl.ifEmpty { null })
                .crossfade(true)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .build(),
            contentDescription = "Avatar",
            modifier = Modifier
                .padding((strokeWidthDp + 1.5f).dp)
                .fillMaxSize()
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}
