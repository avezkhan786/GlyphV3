package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
        // Edge-to-edge: let the header background extend under the status bar
        // (mirrors PrivacySettingsActivity / ChatActivity pastel treatment).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val lightStatusBar = ThemeManager.getCurrentTheme(this) != ThemeManager.THEME_DARK
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

    val listState = rememberLazyListState()
    LaunchedEffect(openMessageId, sorted) {
        if (openMessageId != null) {
            val idx = sorted.indexOfFirst { it.id == openMessageId }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (glyphTheme.gradientHeader != null)
                            Modifier.background(glyphTheme.gradientHeader!!) else Modifier
                    )
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OfficialGlyphAvatar(size = 36.dp, contentScale = 0.84f)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Glyph Official",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Announcements",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (glyphTheme.gradientHeader != null) {
                            Color.Transparent
                        } else {
                            glyphTheme.surfaceHeader
                        },
                        titleContentColor = glyphTheme.textPrimary,
                        navigationIconContentColor = glyphTheme.headerIcon,
                        actionIconContentColor = glyphTheme.headerIcon
                    )
                )
            }
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No official messages yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.id }) { message ->
                    OfficialMessageBubble(message) { openOfficialMessage(context, message) }
                }
            }
        }
    }
}

@Composable
private fun OfficialMessageBubble(message: OfficialMessage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.title.ifBlank { "Glyph Official" },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (message.pinned) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "PINNED",
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFFB45309), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatOfficialTime(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (message.imageUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                AsyncImage(
                    model = message.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .heightIn(max = 240.dp),
                    contentScale = ContentScale.Crop
                )
            }

            if (message.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (message.deepLink.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap to open",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
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
 * Opens an official message's deep link if it is an http(s)/app-scheme URI;
 * otherwise surfaces the message body as a toast. Mirrors
 * `StatusFragment.openOfficialMessage`.
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
            Toast.makeText(context, message.body.ifBlank { message.title }, Toast.LENGTH_LONG).show()
        }
    } else {
        Toast.makeText(context, message.body.ifBlank { message.title }, Toast.LENGTH_LONG).show()
    }
}
