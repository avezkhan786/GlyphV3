package com.glyph.glyph_v3.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

data class ChatNotificationPrefs(
    val soundUri: String = "Default",   // "Default", "None", or a content:// URI string
    val vibrate: Boolean = true,
    val popup: Boolean = true
)

object ChatNotificationHelper {
    private const val TAG = "ChatNotifHelper"

    private const val CHAT_NOTIFICATION_ID = 0
    private const val CHAT_SUMMARY_NOTIFICATION_ID = 1

    const val CHANNEL_ID: String = "glyph_chat_channel"
    const val GROUP_KEY: String = "com.glyph.glyph_v3.CHAT_MESSAGES"

    /**
     * Returns a channel ID that encodes the current notification preferences.
     * On Android O+, channel properties (sound, vibration, importance) are immutable
     * after creation, so we create distinct channels per preference combination.
     */
    private fun channelIdForPrefs(prefs: ChatNotificationPrefs): String {
        val v = if (prefs.vibrate) "1" else "0"
        val p = if (prefs.popup) "1" else "0"
        val soundTag = when (prefs.soundUri) {
            "Default" -> "def"
            "None" -> "none"
            else -> prefs.soundUri.hashCode().toUInt().toString(16)
        }
        return "glyph_chat_v${v}_p${p}_s${soundTag}"
    }

    /**
     * Returns the current chat channel ID based on stored preferences.
     * Useful for callers (e.g. NotificationActionReceiver) that need a valid channel
     * without re-creating it.
     */
    fun currentChannelId(context: Context): String {
        val prefs = kotlinx.coroutines.runBlocking {
            ChatNotificationPrefs(
                soundUri = com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationSoundFlow(context).first(),
                vibrate = com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationVibrateFlow(context).first(),
                popup = com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationPopupFlow(context).first()
            )
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return ensureChatChannel(context, manager, prefs)
    }

    /**
     * Ensures the notification channel matching [prefs] exists (O+ only).
     * Cleans up stale `glyph_chat_*` channels that no longer match.
     */
    private fun ensureChatChannel(
        context: Context,
        manager: NotificationManager,
        prefs: ChatNotificationPrefs
    ): String {
        val channelId = channelIdForPrefs(prefs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(channelId)
            if (existing == null) {
                // Clean up old dynamic chat channels
                manager.notificationChannels
                    .filter { it.id.startsWith("glyph_chat_") && it.id != channelId }
                    .forEach { manager.deleteNotificationChannel(it.id) }

                val importance = if (prefs.popup)
                    NotificationManager.IMPORTANCE_HIGH
                else
                    NotificationManager.IMPORTANCE_DEFAULT

                val soundUri: Uri? = when (prefs.soundUri) {
                    "Default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    "None" -> null
                    else -> runCatching { Uri.parse(prefs.soundUri) }.getOrNull()
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val channel = NotificationChannel(channelId, "Chat Messages", importance).apply {
                    description = "Incoming chat messages"
                    enableVibration(prefs.vibrate)
                    enableLights(true)
                    lightColor = android.graphics.Color.BLUE
                    setBypassDnd(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setShowBadge(true)
                    if (soundUri != null) {
                        setSound(soundUri, audioAttributes)
                    } else {
                        setSound(null, null)
                    }
                }
                manager.createNotificationChannel(channel)
            }
        }
        return channelId
    }

    fun groupKeyForChat(chatId: String): String = "$GROUP_KEY.$chatId"

    fun notificationTagForChat(chatId: String): String = "chat_$chatId"

    fun notificationIdsForChat(chatId: String): Pair<Int, Int> {
        val raw = chatId.hashCode()
        val base = if (raw == Int.MIN_VALUE) 0 else kotlin.math.abs(raw)
        val messageNotificationId = base
        val summaryNotificationId = base xor 0x40000000
        return messageNotificationId to summaryNotificationId
    }

    /**
     * Masks a square [src] bitmap into a circle. Android does NOT auto-clip
     * notification large icons or Person icons, so we must do it ourselves.
     */
    fun circularize(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 1. Draw filled circle as the mask shape
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        // 2. SRC_IN: keep only pixels inside the circle, drawing src on top
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val offsetX = ((src.width - size) / 2).toFloat()
        val offsetY = ((src.height - size) / 2).toFloat()
        canvas.drawBitmap(src, -offsetX, -offsetY, paint)
        return output
    }

    fun loadAvatarBitmap(context: Context, url: String?): Bitmap? {
        return try {
            val safeUrl = url?.takeIf { it.isNotBlank() } ?: return null
            // Download as a plain square bitmap. We pass this to both:
            //   - setLargeIcon()                  → shown as a square in some contexts
            //   - IconCompat.createWithAdaptiveBitmap() → Android auto-clips to circle
            // Do NOT circularize here; adaptive bitmap rendering handles it.
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(safeUrl)
                .override(256, 256)
                .centerCrop()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .submit()
                .get()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            null
        }
    }

    fun loadAvatarWithFallback(
        context: Context,
        url: String?,
        name: String?
    ): Bitmap? {
        // Minimal fallback: avoid introducing custom letter-avatar styling.
        return loadAvatarBitmap(context, url)
    }

    fun showChatNotification(
        context: Context,
        chatId: String,
        otherUserId: String,
        senderName: String,
        messages: List<NotificationCompat.MessagingStyle.Message>,
        avatarBitmap: Bitmap?,
        statusPreviewBitmap: Bitmap? = null,
        silent: Boolean = true,
        prefs: ChatNotificationPrefs = ChatNotificationPrefs(),
        skipArchiveCheck: Boolean = false,
        // Phase 7: when non-null, render as a group conversation. The header / shortcut
        // continue to use [senderName] (which callers should set to the group name for
        // groups), but MessagingStyle is configured with conversationTitle = groupName
        // and isGroupConversation = true so per-message senders render correctly.
        groupName: String? = null
    ) {
        if (messages.isEmpty()) return

        val normalizedChatId = chatId.trim()

        // Global safety gate: never display notifications for archived chats.
        // This covers all callers (FCM, media download updater, etc.).
        val isArchived = if (skipArchiveCheck) {
            false
        } else {
            try {
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val candidateIds = linkedSetOf(normalizedChatId)
                if (currentUserId.isNotBlank() && otherUserId.isNotBlank()) {
                    candidateIds.add("${currentUserId}_${otherUserId}")
                    candidateIds.add("${otherUserId}_${currentUserId}")
                    candidateIds.add(listOf(currentUserId, otherUserId).sorted().joinToString("_"))
                }

                runBlocking(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(context.applicationContext).chatDao()
                    candidateIds.any { id -> dao.getChatById(id)?.isArchived == true }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking archive status for notification", e)
                false
            }
        }

        // Resolve sender name with device contact priority
        val resolvedSenderName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = otherUserId,
            remoteProfileName = senderName
        )
        val effectiveSenderName = if (resolvedSenderName.isNotBlank()) resolvedSenderName else senderName

        if (isArchived) {

            // Ensure we don't keep rebuilding notifications from cached unread store.
            runCatching { UnreadMessageStore.clearMessages(normalizedChatId) }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationTag = notificationTagForChat(normalizedChatId)
            notificationManager.cancel(notificationTag, CHAT_NOTIFICATION_ID)
            notificationManager.cancel(notificationTag, CHAT_SUMMARY_NOTIFICATION_ID)
            return
        }

        // Keep a stable request code for intents/actions.
        val (notificationId, summaryNotificationId) = notificationIdsForChat(normalizedChatId)
        val groupKey = groupKeyForChat(normalizedChatId)
        val notificationTag = notificationTagForChat(normalizedChatId)

        // If multiple media items exist, use a BigPictureStyle collage.
        // MessagingStyle attachments frequently render only the latest image.
        val (collageBitmap, collageCount) = buildMediaCollageBitmap(context, messages)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("chat_id", normalizedChatId)
            putExtra("other_user_id", otherUserId)
            putExtra("other_username", senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Resolve the notification channel based on user preferences (sound/vibrate/popup).
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeChannelId = ensureChatChannel(context, notificationManager, prefs)

        val builderPriority = if (prefs.popup)
            NotificationCompat.PRIORITY_MAX
        else
            NotificationCompat.PRIORITY_DEFAULT

        val notificationBuilder = NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(builderPriority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)

        if (silent) {
            notificationBuilder.setSilent(true)
        } else {
            notificationBuilder
                .setSilent(false)
                .setOnlyAlertOnce(false)
            // Pre-O: apply sound/vibrate on the builder directly
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val soundUri: android.net.Uri? = when (prefs.soundUri) {
                    "Default" -> defaultSoundUri
                    "None" -> null
                    else -> runCatching { android.net.Uri.parse(prefs.soundUri) }.getOrNull() ?: defaultSoundUri
                }
                if (soundUri != null) notificationBuilder.setSound(soundUri)
                if (prefs.vibrate) {
                    notificationBuilder.setVibrate(longArrayOf(0, 250, 100, 250))
                }
            }
        }

        // Circularize once; reuse for setLargeIcon, Person.icon, and the shortcut icon.
        val circularAvatarBitmap = avatarBitmap?.let { circularize(it) }
        if (circularAvatarBitmap != null) {
            notificationBuilder.setLargeIcon(circularAvatarBitmap)
        }

        // -----------------------------------------------------------------------
        // Android 12+ Conversation Shortcut — REQUIRED for avatar in collapsed row
        // -----------------------------------------------------------------------
        // On Android 12+, MessagingStyle's collapsed notification header icon comes
        // exclusively from a linked ShortcutInfo, NOT from setLargeIcon() or
        // Person.icon on messages (those only appear in the *expanded* template).
        // Without a shortcut the system falls back to the app's smallIcon (app icon).
        //
        // Fix: push a long-lived dynamic shortcut carrying the avatar and call
        // setShortcutId() on the builder.  Android then renders the shortcut icon
        // in the collapsed conversation header on API 26+ devices.
        // -----------------------------------------------------------------------
        val shortcutId = "chat_$otherUserId"
        if (circularAvatarBitmap != null) {
            try {
                val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("chat_id", normalizedChatId)
                    putExtra("other_user_id", otherUserId)
                    putExtra("other_username", senderName)
                }
                val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                    .setLongLived(true)
                    .setShortLabel(senderName)
                    .setIntent(shortcutIntent)
                    .setIcon(IconCompat.createWithBitmap(circularAvatarBitmap))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                notificationBuilder.setShortcutId(shortcutId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push dynamic shortcut for avatar", e)
            }
        }

        val replyRemoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_CHAT_ID, chatId)
            putExtra(NotificationActionReceiver.EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(NotificationActionReceiver.EXTRA_OTHER_USERNAME, senderName)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            "Reply",
            replyPendingIntent
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_CHAT_ID, chatId)
            putExtra(NotificationActionReceiver.EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }

        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            "Mark as read",
            markReadPendingIntent
        ).build()

        notificationBuilder
            .addAction(replyAction)
            .addAction(markReadAction)

        // Build the canonical sender Person using the pre-circularized bitmap.
        // createWithBitmap() on an already-circular ARGB_8888 bitmap is the correct API
        // for notification Person icons — it shows in BOTH the compact (setLargeIcon slot)
        // and the expanded MessagingStyle conversation avatar on all Android versions.
        val senderIcon = circularAvatarBitmap?.let { IconCompat.createWithBitmap(it) }
        val senderPerson = Person.Builder()
            .setName(effectiveSenderName)
            .setKey(otherUserId)
            .apply { if (senderIcon != null) setIcon(senderIcon) }
            .build()

        val me = Person.Builder().setName("Me").build()
        val isGroupConversation = !groupName.isNullOrBlank()
        val messagingStyle = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroupConversation) groupName else null)
            .setGroupConversation(isGroupConversation)

        // For 1:1 chats we re-apply the canonical [senderPerson] (with avatar) so the
        // bubble always shows the right icon even if callers built bare Person objects.
        // For groups every message can have a different sender, so we MUST preserve the
        // Person attached by the caller — otherwise every bubble would look like the
        // group-header icon.
        for (msg in messages) {
            val effectivePerson = if (isGroupConversation) (msg.person ?: senderPerson) else senderPerson
            val rebuiltMsg = NotificationCompat.MessagingStyle.Message(
                msg.text, msg.timestamp, effectivePerson
            )
            if (msg.dataUri != null) {
                rebuiltMsg.setData(msg.dataMimeType, msg.dataUri)
            }
            messagingStyle.addMessage(rebuiltMsg)
        }

        val latestMessage = messages.lastOrNull()
        val latestText = latestMessage?.text?.toString() ?: "New message"
        val isSingleStatusReaction = messages.size == 1 && (
            latestText == "Liked your status" || latestText.startsWith("Replied to your status:")
        )
        val explicitStatusPreviewBitmap = statusPreviewBitmap?.let { circularize(it) }
        val statusReplyPreviewBitmap = if (
            isSingleStatusReaction &&
            latestMessage?.dataUri != null &&
            latestMessage.dataMimeType?.startsWith("image") == true
        ) {
            decodeDownsampledBitmap(context, latestMessage.dataUri!!, 256, 256)?.let { circularize(it) }
        } else {
            null
        }
        val statusLikePreviewBitmap = if (
            messages.size == 1 &&
            latestText == "Liked your status" &&
            latestMessage?.dataUri != null &&
            latestMessage.dataMimeType?.startsWith("image") == true
        ) {
            decodeDownsampledBitmap(context, latestMessage.dataUri!!, 1024, 1024)
        } else {
            null
        }

        if (collageBitmap != null && collageCount >= 2) {
            val summaryText = when (collageCount) {
                2 -> "\uD83D\uDCF7 2 media"
                3 -> "\uD83D\uDCF7 3 media"
                4 -> "\uD83D\uDCF7 4 media"
                else -> "\uD83D\uDCF7 Media"
            }

            val bigPictureStyle = NotificationCompat.BigPictureStyle()
                .bigPicture(collageBitmap)
                .setSummaryText(summaryText)

            notificationBuilder
                .setStyle(bigPictureStyle)
                .setContentTitle(senderName)
                .setContentText(summaryText)
                .setSubText(latestText)
                .setNumber(messages.size)
        } else if (isSingleStatusReaction && (explicitStatusPreviewBitmap != null || statusReplyPreviewBitmap != null)) {
            val customPreview = explicitStatusPreviewBitmap ?: statusReplyPreviewBitmap!!
            val contentView = buildStatusReplyRemoteViews(
                packageName = context.packageName,
                senderName = senderName,
                bodyText = latestText,
                statusThumbnail = customPreview
            )

            notificationBuilder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setContentTitle(senderName)
                .setContentText(latestText)
                .setNumber(messages.size)
        } else if (statusLikePreviewBitmap != null) {
            val bigPictureStyle = NotificationCompat.BigPictureStyle()
                .bigPicture(statusLikePreviewBitmap)
                .setSummaryText(latestText)

            notificationBuilder
                .setStyle(bigPictureStyle)
                .setContentTitle(senderName)
                .setContentText(latestText)
                .setNumber(messages.size)
        } else {
            notificationBuilder
                .setStyle(messagingStyle)
                .setContentTitle(senderName)
                .setContentText(latestText)
                .setNumber(messages.size)
        }

        // Channel is already ensured above via ensureChatChannel(). No hardcoded channel here.

        try {
            // Use (tag,id) so we can replace the phase-1 system FCM notification.
            notificationManager.notify(notificationTag, CHAT_NOTIFICATION_ID, notificationBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display notification", e)
        }

        val summaryNotification = NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText("${messages.size} messages")
            )
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()

        if (messages.size > 1) {
            notificationManager.notify(notificationTag, CHAT_SUMMARY_NOTIFICATION_ID, summaryNotification)
        } else {
            notificationManager.cancel(notificationTag, CHAT_SUMMARY_NOTIFICATION_ID)
        }
    }

    private fun buildStatusReplyRemoteViews(
        packageName: String,
        senderName: String,
        bodyText: String,
        statusThumbnail: Bitmap
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_status_reply).apply {
            setTextViewText(R.id.tvStatusSender, senderName)
            setTextViewText(R.id.tvStatusBody, bodyText)
            setImageViewBitmap(R.id.ivStatusThumb, statusThumbnail)
            setTextColor(R.id.tvStatusSender, android.graphics.Color.WHITE)
            setTextColor(R.id.tvStatusBody, android.graphics.Color.parseColor("#DADCE0"))
            setViewVisibility(R.id.ivStatusThumb, android.view.View.VISIBLE)
        }
    }

    private fun buildMediaCollageBitmap(
        context: Context,
        messages: List<NotificationCompat.MessagingStyle.Message>
    ): Pair<Bitmap?, Int> {
        val attachmentUrisNewestFirst = messages
            .asReversed()
            .asSequence()
            .filter { it.dataUri != null && (it.dataMimeType?.startsWith("image") == true) }
            .mapNotNull { it.dataUri }
            .take(4)
            .toList()

        val count = attachmentUrisNewestFirst.size
        if (count < 2) {
            return Pair(null, count)
        }

        return try {
            val sizePx = 768
            val bitmaps = attachmentUrisNewestFirst.mapNotNull { decodeDownsampledBitmap(context, it, 512, 512) }
            if (bitmaps.size < 2) return Pair(null, bitmaps.size)

            val collage = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(collage)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val rects: List<Rect> = when (bitmaps.size) {
                2 -> listOf(
                    Rect(0, 0, sizePx / 2, sizePx),
                    Rect(sizePx / 2, 0, sizePx, sizePx)
                )
                3 -> listOf(
                    Rect(0, 0, sizePx / 2, sizePx / 2),
                    Rect(sizePx / 2, 0, sizePx, sizePx / 2),
                    Rect(0, sizePx / 2, sizePx, sizePx)
                )
                else -> listOf(
                    Rect(0, 0, sizePx / 2, sizePx / 2),
                    Rect(sizePx / 2, 0, sizePx, sizePx / 2),
                    Rect(0, sizePx / 2, sizePx / 2, sizePx),
                    Rect(sizePx / 2, sizePx / 2, sizePx, sizePx)
                )
            }

            for (i in 0 until minOf(bitmaps.size, rects.size)) {
                drawCenterCrop(canvas, paint, bitmaps[i], rects[i])
            }

            Pair(collage, minOf(bitmaps.size, 4))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build collage bitmap", e)
            Pair(null, count)
        }
    }

    private fun decodeDownsampledBitmap(
        context: Context,
        uri: android.net.Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            }

            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun drawCenterCrop(
        canvas: Canvas,
        paint: Paint,
        bitmap: Bitmap,
        dest: Rect
    ) {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return

        val destW = dest.width().toFloat()
        val destH = dest.height().toFloat()
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val destAspect = destW / destH

        val srcRect = if (srcAspect > destAspect) {
            // Crop left/right
            val newW = (srcH * destAspect).toInt()
            val x0 = (srcW - newW) / 2
            Rect(x0, 0, x0 + newW, srcH)
        } else {
            // Crop top/bottom
            val newH = (srcW / destAspect).toInt()
            val y0 = (srcH - newH) / 2
            Rect(0, y0, srcW, y0 + newH)
        }

        canvas.drawBitmap(bitmap, srcRect, dest, paint)
    }
}
