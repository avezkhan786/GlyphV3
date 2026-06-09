package com.glyph.glyph_v3.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.util.FormatUtils
import com.glyph.glyph_v3.util.AudioPlayer
import com.glyph.glyph_v3.data.models.MessageStatus
import androidx.compose.runtime.*
import coil.compose.AsyncImage
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageDetailsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val messageContent = intent.getStringExtra(EXTRA_CONTENT) ?: ""
        val messageType = intent.getStringExtra(EXTRA_TYPE) ?: "TEXT"
        val sentTimestamp = intent.getLongExtra(EXTRA_SENT_TIME, 0L)
        val receivedTimestamp = intent.getLongExtra(EXTRA_RECEIVED_TIME, 0L)
        val deliveredTimestamp = intent.getLongExtra(EXTRA_DELIVERED_TIME, 0L)
        val readTimestamp = intent.getLongExtra(EXTRA_READ_TIME, 0L)
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val status = intent.getStringExtra(EXTRA_STATUS) ?: "SENT"
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: ""
        val senderPhone = intent.getStringExtra(EXTRA_SENDER_PHONE) ?: ""
        val senderAvatar = intent.getStringExtra(EXTRA_SENDER_AVATAR) ?: ""
        
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        val audioDuration = intent.getLongExtra(EXTRA_AUDIO_DURATION, 0L)
        val audioLocalPath = intent.getStringExtra(EXTRA_AUDIO_LOCAL_PATH)
        
        val mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
        val mediaThumbnailUrl = intent.getStringExtra(EXTRA_MEDIA_THUMB_URL)

        setContent {
            GlyphThemeProvider {
                MessageDetailsScreen(
                    content = messageContent,
                    type = messageType,
                    sentTime = sentTimestamp,
                    receivedTime = receivedTimestamp,
                    deliveredTime = deliveredTimestamp,
                    readTime = readTimestamp,
                    isIncoming = isIncoming,
                    status = status,
                    senderName = senderName,
                    senderPhone = senderPhone,
                    senderAvatar = senderAvatar,
                    audioUrl = audioUrl,
                    audioDuration = audioDuration,
                    audioLocalPath = audioLocalPath,
                    mediaUrl = mediaUrl,
                    mediaThumbnailUrl = mediaThumbnailUrl,
                    onBackClick = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_SENT_TIME = "extra_sent_time"
        private const val EXTRA_RECEIVED_TIME = "extra_received_time"
        private const val EXTRA_DELIVERED_TIME = "extra_delivered_time"
        private const val EXTRA_READ_TIME = "extra_read_time"
        private const val EXTRA_IS_INCOMING = "extra_is_incoming"
        private const val EXTRA_STATUS = "extra_status"
        private const val EXTRA_SENDER_NAME = "extra_sender_name"
        private const val EXTRA_SENDER_PHONE = "extra_sender_phone"
        private const val EXTRA_SENDER_AVATAR = "extra_sender_avatar"
        private const val EXTRA_AUDIO_URL = "extra_audio_url"
        private const val EXTRA_AUDIO_DURATION = "extra_audio_duration"
        private const val EXTRA_AUDIO_LOCAL_PATH = "extra_audio_local_path"
        private const val EXTRA_MEDIA_URL = "extra_media_url"
        private const val EXTRA_MEDIA_THUMB_URL = "extra_media_thumb_url"

        fun newIntent(
            context: Context,
            content: String,
            type: String,
            sentTime: Long,
            receivedTime: Long,
            deliveredTime: Long,
            readTime: Long,
            isIncoming: Boolean,
            status: String = "SENT",
            senderName: String = "",
            senderPhone: String = "",
            senderAvatar: String = "",
            audioUrl: String? = null,
            audioDuration: Long = 0L,
            audioLocalPath: String? = null,
            mediaUrl: String? = null,
            mediaThumbnailUrl: String? = null
        ): Intent {
            return Intent(context, MessageDetailsActivity::class.java).apply {
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_SENT_TIME, sentTime)
                putExtra(EXTRA_RECEIVED_TIME, receivedTime)
                putExtra(EXTRA_DELIVERED_TIME, deliveredTime)
                putExtra(EXTRA_READ_TIME, readTime)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_SENDER_NAME, senderName)
                putExtra(EXTRA_SENDER_PHONE, senderPhone)
                putExtra(EXTRA_SENDER_AVATAR, senderAvatar)
                putExtra(EXTRA_AUDIO_URL, audioUrl)
                putExtra(EXTRA_AUDIO_DURATION, audioDuration)
                putExtra(EXTRA_AUDIO_LOCAL_PATH, audioLocalPath)
                putExtra(EXTRA_MEDIA_URL, mediaUrl)
                putExtra(EXTRA_MEDIA_THUMB_URL, mediaThumbnailUrl)
            }
        }

        fun newIntent(
            context: Context, 
            message: com.glyph.glyph_v3.data.models.Message,
            currentUserPhone: String = "",
            currentUserAvatar: String = "",
            otherUserPhone: String = "",
            otherUsername: String = "",
            otherUserAvatar: String = ""
        ): Intent {
            val isIncoming = message.senderId != com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            return newIntent(
                context,
                content = message.text ?: "Media",
                type = message.type.name,
                sentTime = message.timestamp,
                receivedTime = message.timestamp,
                deliveredTime = message.deliveredTimestamp ?: message.timestamp,
                readTime = message.readTimestamp ?: message.timestamp,
                isIncoming = isIncoming,
                status = message.status.name,
                senderName = if (isIncoming) otherUsername else "You",
                senderPhone = if (isIncoming) otherUserPhone else currentUserPhone,
                senderAvatar = if (isIncoming) otherUserAvatar else currentUserAvatar,
                audioUrl = message.audioUrl,
                audioDuration = message.audioDuration,
                audioLocalPath = if (message.type == com.glyph.glyph_v3.data.models.MessageType.AUDIO) message.localUri else null,
                mediaUrl = when (message.type) {
                    com.glyph.glyph_v3.data.models.MessageType.MEDIA_GROUP -> message.mediaItemsList.firstOrNull()?.displayUrl ?: ""
                    else -> message.displayModel
                },
                mediaThumbnailUrl = when (message.type) {
                    com.glyph.glyph_v3.data.models.MessageType.MEDIA_GROUP -> message.mediaItemsList.firstOrNull()?.thumbnailUrl
                    else -> message.thumbnailUrl
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsScreen(
    content: String,
    type: String,
    sentTime: Long,
    receivedTime: Long,
    deliveredTime: Long,
    readTime: Long,
    isIncoming: Boolean,
    status: String,
    senderName: String,
    senderPhone: String,
    senderAvatar: String,
    audioUrl: String? = null,
    audioDuration: Long = 0L,
    audioLocalPath: String? = null,
    mediaUrl: String? = null,
    mediaThumbnailUrl: String? = null,
    onBackClick: () -> Unit
) {
    val view = LocalView.current
    val isDarkTheme = glyphTheme.isDark
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (glyphTheme.backgroundGradient != null) {
                    Modifier.background(glyphTheme.backgroundGradient!!)
                } else {
                    Modifier.background(glyphTheme.surfaceHeader)
                }
            )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Details", color = glyphTheme.textPrimary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = glyphTheme.headerIcon
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Message Preview Card
                Card(
                    shape = RoundedCornerShape(glyphTheme.cornerRadiusLarge),
                    colors = CardDefaults.cardColors(
                        containerColor = glyphTheme.backgroundSecondary
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = glyphTheme.elevationLow),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 32.dp, horizontal = 16.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (type == "AUDIO") {
                            AudioMessagePreview(
                                isIncoming = isIncoming,
                                audioDuration = audioDuration,
                                audioUrl = audioUrl,
                                audioLocalPath = audioLocalPath,
                                sentTime = sentTime,
                                status = status
                            )
                        } else if (type == "IMAGE" || type == "VIDEO" || type == "MEDIA_GROUP") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Card(
                                    shape = RoundedCornerShape(glyphTheme.cornerRadiusMedium),
                                    modifier = Modifier
                                        .size(220.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.05f))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AsyncImage(
                                            model = mediaThumbnailUrl ?: mediaUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        if (type == "VIDEO") {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_play_circle),
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(48.dp)
                                            )
                                        }
                                        if (type == "MEDIA_GROUP") {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(8.dp)
                                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_image_placeholder),
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                if (content.isNotEmpty() && content != "Media" && content != "Photo" && content != "Video") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = content,
                                        color = glyphTheme.textPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(glyphTheme.cornerRadiusMedium),
                                color = if (isIncoming) glyphTheme.bubbleIncomingBackground else glyphTheme.bubbleOutgoingBackground,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    if (type == "TEXT") {
                                        Text(
                                            text = content,
                                            color = if (isIncoming) glyphTheme.bubbleIncomingText else glyphTheme.bubbleOutgoingText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        Text(
                                            text = content.takeIf { it != "Media" && it != "Photo" && it != "Video" } ?: "Media Message ($type)",
                                            color = if (isIncoming) glyphTheme.bubbleIncomingText else glyphTheme.bubbleOutgoingText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Status Section
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = glyphTheme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val statusItems = mutableListOf<Triple<String, Long, Int>>()
                    if (status == "READ") statusItems.add(Triple("Read", readTime, R.drawable.ic_double_check))
                    if (status == "READ" || status == "DELIVERED") statusItems.add(Triple("Delivered", deliveredTime, R.drawable.ic_double_check))
                    statusItems.add(Triple("Sent", sentTime, R.drawable.ic_check))

                    statusItems.forEachIndexed { index, item ->
                        val shape = when {
                            statusItems.size == 1 -> RoundedCornerShape(glyphTheme.cornerRadiusMedium)
                            index == 0 -> RoundedCornerShape(
                                topStart = glyphTheme.cornerRadiusMedium,
                                topEnd = glyphTheme.cornerRadiusMedium,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp
                            )
                            index == statusItems.size - 1 -> RoundedCornerShape(
                                topStart = 0.dp,
                                topEnd = 0.dp,
                                bottomStart = glyphTheme.cornerRadiusMedium,
                                bottomEnd = glyphTheme.cornerRadiusMedium
                            )
                            else -> RectangleShape
                        }
                        
                        DetailCard(shape = shape) {
                            StatusRow(
                                label = item.first,
                                timestamp = item.second,
                                iconRes = item.third,
                                iconTint = if (item.first == "Read") glyphTheme.indicatorMessageStatus else glyphTheme.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // From Section
                Text(
                    text = "From",
                    style = MaterialTheme.typography.titleMedium,
                    color = glyphTheme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                DetailCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(glyphTheme.cornerRadiusSmall),
                            color = glyphTheme.backgroundTinted,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (senderAvatar.isNotEmpty()) {
                                    AsyncImage(
                                        model = senderAvatar,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_account),
                                        contentDescription = null,
                                        tint = glyphTheme.textSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = senderName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = glyphTheme.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            if (senderPhone.isNotEmpty()) {
                                Text(
                                    text = senderPhone,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = glyphTheme.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailCard(
    shape: Shape = RoundedCornerShape(glyphTheme.cornerRadiusMedium),
    content: @Composable () -> Unit
) {
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = glyphTheme.backgroundSecondary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = glyphTheme.elevationLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    timestamp: Long,
    iconRes: Int,
    iconTint: Color = glyphTheme.textSecondary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = glyphTheme.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        
        Text(
            text = formatDetailsTime(timestamp),
            style = MaterialTheme.typography.bodyMedium,
            color = glyphTheme.textPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

fun formatDetailsTime(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Date()
    val diff = now.time - timestamp
    val oneDay = 24 * 60 * 60 * 1000L
    
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    
    val isToday = dayFormat.format(date) == dayFormat.format(now)
    val isYesterday = dayFormat.format(date) == dayFormat.format(Date(now.time - oneDay))
    
    return when {
        isToday -> "Today, ${timeFormat.format(date)}"
        isYesterday -> "Yesterday, ${timeFormat.format(date)}"
        else -> dateFormat.format(date)
    }
}

@Composable
fun AudioMessagePreview(
    isIncoming: Boolean,
    audioDuration: Long,
    audioUrl: String?,
    audioLocalPath: String?,
    sentTime: Long,
    status: String
) {
    val context = LocalContext.current
    val audioPlayer = remember { AudioPlayer(context) }
    val isPlayingAudio by audioPlayer.isPlaying.collectAsState()
    val audioProgress by audioPlayer.progress.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stop()
        }
    }

    val contentColor = if (isIncoming) glyphTheme.bubbleIncomingText else glyphTheme.bubbleOutgoingText
    val bubbleBgColor = if (isIncoming) glyphTheme.bubbleIncomingBackground else glyphTheme.bubbleOutgoingBackground
    
    val currentPosition = if (isPlayingAudio || audioProgress > 0) {
        val posSec = (audioProgress * audioDuration / 1000).toLong()
        String.format("%d:%02d", posSec / 60, posSec % 60)
    } else "0:00"

    VoiceMessageBubble(
        isSelf = !isIncoming,
        isPlaying = isPlayingAudio,
        progress = audioProgress,
        currentPosition = currentPosition,
        totalDuration = String.format("%d:%02d", (audioDuration / 1000) / 60, (audioDuration / 1000) % 60),
        onPlayPause = {
            if (isPlayingAudio) {
                audioPlayer.pause()
            } else {
                if (audioPlayer.isPlayerInitialized) {
                    audioPlayer.resume()
                } else {
                   val playUri = when {
                       !audioLocalPath.isNullOrEmpty() -> {
                           val file = File(audioLocalPath)
                           if (file.exists()) Uri.fromFile(file) else Uri.parse(audioLocalPath)
                       }
                       !audioUrl.isNullOrEmpty() -> Uri.parse(audioUrl)
                       else -> null
                   }
                   playUri?.let { audioPlayer.play(it) }
                }
            }
        },
        onSeek = { pos -> 
            audioPlayer.seekTo((pos * audioDuration).toInt())
        },
        shape = RoundedCornerShape(glyphTheme.cornerRadiusMedium),
        backgroundColor = bubbleBgColor,
        gradient = if (!isIncoming) glyphTheme.gradientBubbleOutgoing else glyphTheme.gradientBubbleIncoming,
        contentColor = contentColor,
        durationMs = audioDuration,
        timestamp = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(sentTime)),
        status = try { MessageStatus.valueOf(status) } catch(e: Exception) { MessageStatus.SENT },
        modifier = Modifier
    )
}
