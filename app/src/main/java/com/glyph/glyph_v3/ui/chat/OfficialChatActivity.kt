package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.OfficialMessage
import com.glyph.glyph_v3.data.repo.OfficialContentRepository
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
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
        // Edge-to-edge: let the header background extend under the status & nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Uniform color under the navigation bar (matches main background for seamless look)
        window.navigationBarColor = MainBackground.toArgb()
        window.statusBarColor = TopAppBarColor.toArgb()
        val lightStatusBar = false // dark header -> light status bar icons
        WindowCompat.getInsetsController(window, window.decorView)
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
// Dark forest palette — exact hex codes from the spec
// ─────────────────────────────────────────────────────────────
private val MainBackground        = Color(0xFF10110E) // app background
private val TopAppBarColor        = Color(0xFF1E1F1C) // top app bar / bottom input / dividers
private val IncomingBubble        = Color(0xFF292927) // incoming bubble
private val OutgoingBubble        = Color(0xFF414839) // outgoing bubble (forest green)
private val OutgoingBubbleHi      = Color(0xFF4B5440) // outgoing highlight (e.g. pinned)
private val MessageText           = Color(0xFFF1F1EC) // primary message text
private val SecondaryText         = Color(0xFFB6B6AF) // secondary text
private val HintText              = Color(0xFF8D8F88) // hint text
private val IconColor             = Color(0xFFE8E8E3) // icons
private val OutlineButtonColor    = Color(0xFF4A4A47) // outline buttons

// ─────────────────────────────────────────────────────────────
// Bubble position in group for proper stacked rounding
// ─────────────────────────────────────────────────────────────
private enum class BubblePosition {
    Single,    // Only message in group — fully rounded
    First,     // First in group — round top
    Middle,    // Middle in group — minimal rounding (straight sides)
    Last       // Last in group — round bottom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficialChatScreen(openMessageId: String?) {
    val context = LocalContext.current
    val messages by OfficialContentRepository.officialMessages.collectAsState()

    val sorted = remember(messages) {
        messages.sortedWith(
            compareByDescending<OfficialMessage> { it.pinned }
                .thenBy { if (it.publishedAt > 0) it.publishedAt else it.createdAt }
        )
    }

    // Group messages by date label (e.g. "Today", "Yesterday", "Jul 22, 2026")
    // for stacked grouped style similar to Telegram's chat list scroll.
    val grouped = remember(sorted) {
        val groups = mutableListOf<Pair<String, List<OfficialMessage>>>()
        for (msg in sorted) {
            val label = dateLabelFor(msg)
            if (groups.isEmpty() || groups.last().first != label) {
                groups.add(label to listOf(msg))
            } else {
                groups[groups.size - 1] = label to (groups.last().second + msg)
            }
        }
        groups
    }

    val listState = rememberLazyListState()
    LaunchedEffect(openMessageId, sorted) {
        if (openMessageId != null) {
            val idx = sorted.indexOfFirst { it.id == openMessageId }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }

    Scaffold(
        containerColor = MainBackground,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TopAppBarColor)
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
                                    color = MessageText
                                )
                                Text(
                                    "Announcements",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecondaryText
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = "Back",
                                tint = IconColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MessageText,
                        navigationIconContentColor = IconColor,
                        actionIconContentColor = IconColor
                    )
                )
            }
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainBackground)
                    .padding(padding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No official messages yet",
                    color = SecondaryText
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainBackground)
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                grouped.forEachIndexed { groupIndex, (dateLabel, msgs) ->
                    item(key = "header_$dateLabel") {
                        DateHeaderChip(dateLabel)
                    }
                    msgs.forEachIndexed { msgIndex, message ->
                        val positionInGroup = when {
                            msgs.size == 1 -> BubblePosition.Single
                            msgIndex == 0 -> BubblePosition.First
                            msgIndex == msgs.size - 1 -> BubblePosition.Last
                            else -> BubblePosition.Middle
                        }
                        item(key = "msg_${message.id}") {
                            OfficialMessageBubble(
                                message = message,
                                positionInGroup = positionInGroup,
                                onClick = { openOfficialMessage(context, message) }
                            )
                        }
                    }
                }
                // Extra bottom space behind the navigation bar for a uniform color
                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun DateHeaderChip(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TopAppBarColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = SecondaryText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun dateLabelFor(message: OfficialMessage): String {
    val ts = if (message.publishedAt > 0) message.publishedAt else message.createdAt
    if (ts <= 0) return ""
    val date = Date(ts)
    val cal = java.util.Calendar.getInstance().apply { time = date }
    val today = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when {
        isSameDay(cal, today) -> "Today"
        isSameDay(cal, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}

private fun isSameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean {
    return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun OfficialMessageBubble(
    message: OfficialMessage,
    positionInGroup: BubblePosition = BubblePosition.Single,
    onClick: () -> Unit
) {
    // Wrap in a Row to align bubble to the start (left) - incoming message style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        // Chat bubble with Telegram-like rounded corners:
        // smaller radius on bottom-left (tail corner), larger on other corners
        // Stacked grouped style: consecutive messages share visual grouping via sub-radius
        // Official messages are incoming from Glyph — use OutgoingBubble for the new color
        val bubbleColor = OutgoingBubble

        // Dynamic rounding based on bubble position in group:
        // Tail corner (bottomStart): 4dp for stacked, 18dp for standalone single
        val shape = when (positionInGroup) {
            BubblePosition.Single -> RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp
            )
            BubblePosition.First -> RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 4.dp
            )
            BubblePosition.Middle -> RoundedCornerShape(
                topStart = 0.dp, topEnd = 4.dp, bottomEnd = 4.dp, bottomStart = 4.dp
            )
            BubblePosition.Last -> RoundedCornerShape(
                topStart = 0.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 4.dp
            )
        }

        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable { onClick() },
            shape = shape,
            color = bubbleColor,
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
                        color = SecondaryText
                    )
                    if (message.pinned) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PINNED",
                            fontSize = 9.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFFB45309), RoundedCornerShape(4.dp))
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
                        color = MessageText
                    )
                }

                if (message.deepLink.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap to open",
                        color = SecondaryText,
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
                        color = HintText
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
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
    } catch (_: Exception) {
        ""
    }
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