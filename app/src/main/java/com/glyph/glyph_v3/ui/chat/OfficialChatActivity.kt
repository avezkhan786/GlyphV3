package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.OFFICIAL_USER_ID
import com.glyph.glyph_v3.data.models.OfficialMessage
import com.glyph.glyph_v3.data.repo.OfficialContentRepository
import com.glyph.glyph_v3.ui.chat.ConversationType
import com.glyph.glyph_v3.ui.chat.OfficialConversationFooter
import com.glyph.glyph_v3.ui.chat.ReadOnlyConversation
import com.glyph.glyph_v3.ui.chat.rememberOfficialConversationFooterState
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only "Glyph Official" chat screen.
 *
 * Opened from the chat list when the user taps the synthetic "Glyph Official" row
 * (or from a notification). It shows the portal's official *messages* as incoming
 * bubbles and intentionally exposes NO call / buzz / walkie-talkie / input controls
 * — company messages are one-directional. Tapping a message opens its deep link.
 *
 * The chat list's unread badge is cleared via [OfficialContentRepository.markOpened]
 * on create/resume.
 */
class OfficialChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: transparent system bars let the composable backgrounds show through
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val lightStatusBar = false // dark header -> light status bar icons
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = lightStatusBar
        OfficialContentRepository.markOpened()
        val openMessageId = intent.getStringExtra(EXTRA_OPEN_MESSAGE_ID)

        setContent {
            GlyphThemeProvider {
                OfficialChatScreen(openMessageId = openMessageId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OfficialContentRepository.markOpened()
    }

    companion object {
        const val EXTRA_OPEN_MESSAGE_ID = "official_message_id"
        fun newIntent(context: Context): Intent =
            Intent(context, OfficialChatActivity::class.java)
    }
}

// ─────────────────────────────────────────────────────────────
// Cached formatters — thread-safe, zero-allocation after init
// ─────────────────────────────────────────────────────────────
private val DateHeaderFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val OfficialTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
private val ZoneIdSystem = ZoneId.systemDefault()

// ─────────────────────────────────────────────────────────────
// Bubble position in group for proper stacked rounding
// ─────────────────────────────────────────────────────────────
private enum class BubblePosition {
    Single,    // Only message in group — fully rounded
    First,     // First in group (oldest, at top of group) — round top
    Middle,    // Middle in group — minimal rounding (straight sides)
    Last       // Last in group (newest, at bottom of group) — round bottom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficialChatScreen(openMessageId: String?) {
    val context = LocalContext.current
    val theme = glyphTheme
    val messages by OfficialContentRepository.officialMessages.collectAsState()

    // Sort messages chronologically: oldest first, pinned messages first within each date
    val sorted = remember(messages) {
        messages.sortedWith(
            compareBy<OfficialMessage> { it.publishedAt.takeIf { it > 0 } ?: it.createdAt }
        )
    }

    // Group consecutive messages by date label — O(n) with mutable inner lists
    val grouped = remember(sorted) {
        derivedStateOf {
            if (sorted.isEmpty()) return@derivedStateOf emptyList<Pair<String, List<OfficialMessage>>>()
            val groups = mutableListOf<Pair<String, MutableList<OfficialMessage>>>()
            for (msg in sorted) {
                val label = dateLabelFor(msg)
                val lastGroup = groups.lastOrNull()
                if (lastGroup == null || lastGroup.first != label) {
                    groups.add(label to mutableListOf(msg))
                } else {
                    lastGroup.second.add(msg)
                }
            }
            groups.map { (label, msgs) -> label to msgs.toList() }
        }
    }

    val groupedList = grouped.value
    val listState = rememberLazyListState()

    // Reusable read-only footer banner. The "Glyph Official" chat is always read-only,
    // so the conversation's type drives the banner's visibility (see setConversation).
    val footerState = rememberOfficialConversationFooterState(
        ReadOnlyConversation(
            id = OFFICIAL_USER_ID,
            type = ConversationType.OFFICIAL_ANNOUNCEMENT
        )
    )

    // Track if we've auto-scrolled to avoid re-scrolling on data updates
    val hasAutoScrolled = remember { mutableStateOf(false) }

    // Auto-scroll to bottom (newest messages) when chat first opens
    LaunchedEffect(groupedList) {
        // Only scroll once on initial load, not on every data update
        if (!hasAutoScrolled.value && groupedList.isNotEmpty()) {
            hasAutoScrolled.value = true
            // Calculate the index of the last item (bottom of list in normal order)
            // Each group has 1 header + N messages
            val lastIndex = groupedList.sumOf { it.second.size } + groupedList.size
            listState.scrollToItem(maxOf(0, lastIndex - 1))
        }
    }

    // Scroll to a specific message (e.g. from a notification deep-link)
    if (openMessageId != null) {
        LaunchedEffect(openMessageId, groupedList) {
            val targetIndex = findLazyColumnIndex(groupedList, openMessageId)
            if (targetIndex != null) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    Scaffold(
        containerColor = theme.backgroundPrimary,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surfaceHeader)
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OfficialGlyphAvatar(size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Glyph Official",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = theme.textPrimary
                                )
                                Text(
                                    "Announcements",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textSecondary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = "Back",
                                tint = theme.headerIcon
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = theme.textPrimary,
                        navigationIconContentColor = theme.headerIcon,
                        actionIconContentColor = theme.headerIcon
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (sorted.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(theme.backgroundPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No official messages yet",
                        color = theme.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    // NO reverseLayout - normal chat order: oldest at top, newest at bottom
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(theme.backgroundPrimary),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                groupedList.forEachIndexed { groupIndex, (dateLabel, msgs) ->
                    item(key = "header_${groupIndex}_$dateLabel", contentType = "date_header") {
                        DateHeaderChip(dateLabel)
                    }
                    msgs.forEachIndexed { msgIndex, message ->
                        // msgIndex 0 = oldest (top of group) = round top
                        // msgIndex size-1 = newest (bottom of group) = round bottom
                        val positionInGroup = when {
                            msgs.size == 1 -> BubblePosition.Single
                            msgIndex == 0 -> BubblePosition.First   // Oldest in group
                            msgIndex == msgs.size - 1 -> BubblePosition.Last   // Newest in group
                            else -> BubblePosition.Middle
                        }
                        item(key = "msg_${message.id}", contentType = "message_bubble") {
                            OfficialMessageBubble(
                                message = message,
                                positionInGroup = positionInGroup,
                                onClick = { openOfficialMessage(context, message) }
                            )
                        }
                    }
                }
            }

            // Pinned read-only footer banner — sits above the navigation gesture area
            // and never overlaps the message list (which scrolls behind it).
            OfficialConversationFooter(
                state = footerState,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
}

@Composable
private fun DateHeaderChip(label: String) {
    val theme = glyphTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.dateHeaderBackground,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = theme.dateHeaderText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun dateLabelFor(message: OfficialMessage): String {
    val ts = if (message.publishedAt > 0) message.publishedAt else message.createdAt
    if (ts <= 0) return ""
    val localDate = Instant.ofEpochMilli(ts).atZone(ZoneIdSystem).toLocalDate()
    val today = LocalDate.now(ZoneIdSystem)
    val yesterday = today.minusDays(1)
    return when {
        localDate == today -> "Today"
        localDate == yesterday -> "Yesterday"
        else -> localDate.format(DateHeaderFormatter)
    }
}

@Composable
private fun OfficialMessageBubble(
    message: OfficialMessage,
    positionInGroup: BubblePosition = BubblePosition.Single,
    onClick: () -> Unit
) {
    val theme = glyphTheme

    // Cache bubble shapes — matches ChatScreen BubbleShapeCache for incoming (isSelf=false)
    // Normal layout: position 0 is at top (oldest in group), round top
    val shape = remember(positionInGroup) {
        val r = 18.dp  // fully rounded
        val s = 6.dp   // slightly rounded tail (screen-edge side, connecting corners)
        when (positionInGroup) {
            BubblePosition.Single -> RoundedCornerShape(r)
            BubblePosition.First  -> RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = r, bottomStart = s)
            BubblePosition.Middle -> RoundedCornerShape(topStart = s, topEnd = s, bottomEnd = s, bottomStart = s)
            BubblePosition.Last   -> RoundedCornerShape(topStart = s, topEnd = r, bottomEnd = r, bottomStart = r)
        }
    }

    // Stable callback keyed by message id to avoid recomposition
    val stableOnClick = remember(message.id) { { onClick() } }

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable(onClick = stableOnClick),
            shape = shape,
            color = Color(0xFF414839), // Official bubble — forest green
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.title.ifBlank { "Glyph Official" },
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = theme.textSecondary
                    )
                    if (message.pinned) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PINNED",
                            fontSize = 9.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(theme.actionWarning, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                if (message.imageUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    AsyncImage(
                        model = message.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .heightIn(max = 220.dp),
                        contentScale = ContentScale.Crop
                    )
                }

                if (message.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.textPrimary
                    )
                }

                if (message.deepLink.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap to open",
                        color = theme.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Timestamp aligned bottom-end like a chat bubble
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        formatOfficialTime(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textTertiary
                    )
                }
            }
        }
    }
}

private fun formatOfficialTime(message: OfficialMessage): String {
    val ts = if (message.publishedAt > 0) message.publishedAt else message.createdAt
    if (ts <= 0) return ""
    return try {
        Instant.ofEpochMilli(ts).atZone(ZoneIdSystem).format(OfficialTimeFormatter)
    } catch (_: Exception) {
        ""
    }
}

/**
 * Finds the LazyColumn index for a message, accounting for interspersed date headers.
 * Returns null if the message id is not found in any group.
 */
private fun findLazyColumnIndex(
    grouped: List<Pair<String, List<OfficialMessage>>>,
    targetId: String
): Int? {
    var index = 0
    for ((_, msgs) in grouped) {
        index++ // date header
        for (msg in msgs) {
            if (msg.id == targetId) return index
            index++
        }
    }
    return null
}

/**
 * Opens an official message's deep link if it is an http(s)/app-scheme URI.
 * Since the message bubble already displays all content, no toast or dialog is shown.
 * Tapping simply attempts to open the link - no feedback is provided.
 */
private fun openOfficialMessage(context: Context, message: OfficialMessage) {
    val deepLink = message.deepLink
    if (deepLink.isNotBlank() &&
        (deepLink.startsWith("http://") ||
            deepLink.startsWith("https://") ||
            deepLink.startsWith("glyph://"))
    ) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
        } catch (e: Exception) {
            // No feedback - user can see the deep link hint in the bubble
            // and try again if desired. No toast shown.
        }
    } else {
        // No feedback - message content is already visible in the bubble
        // No toast shown.
    }
}