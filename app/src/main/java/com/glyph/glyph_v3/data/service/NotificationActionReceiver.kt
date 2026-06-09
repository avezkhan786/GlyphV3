package com.glyph.glyph_v3.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.glyph.glyph_v3.ACTION_REPLY"
        const val ACTION_MARK_READ = "com.glyph.glyph_v3.ACTION_MARK_READ"
        const val ACTION_CAMERA_INVITE_ACCEPT = "com.glyph.glyph_v3.ACTION_CAMERA_INVITE_ACCEPT"
        const val ACTION_CAMERA_INVITE_DECLINE = "com.glyph.glyph_v3.ACTION_CAMERA_INVITE_DECLINE"
        /** Intra-app broadcast that tells a foreground ChatActivity to dismiss its in-map invite prompt. */
        const val ACTION_DISMISS_CAMERA_INVITE_LOCAL = "com.glyph.glyph_v3.ACTION_DISMISS_CAMERA_INVITE_LOCAL"
        const val KEY_REPLY_TEXT = "key_reply_text"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
        const val EXTRA_OTHER_USERNAME = "extra_other_username"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_NOTIFICATION_TAG = "extra_notification_tag"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        private const val TAG = "NotifActionReceiver"
        private const val CHANNEL_ID = "glyph_chat_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        when (intent.action) {
            ACTION_REPLY -> {
                val replyText = getReplyText(intent)
                if (!replyText.isNullOrBlank()) {
                    val otherUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: return
                    val otherUsername = intent.getStringExtra(EXTRA_OTHER_USERNAME) ?: "Unknown"
                    handleReply(context, chatId, otherUserId, otherUsername, replyText, notificationId)
                }
            }
            ACTION_MARK_READ -> {
                handleMarkAsRead(context, chatId, notificationId)
            }
            ACTION_CAMERA_INVITE_ACCEPT -> {
                handleCameraInviteAccept(context, chatId, intent, notificationId)
            }
            ACTION_CAMERA_INVITE_DECLINE -> {
                handleCameraInviteDecline(context, chatId, intent, notificationId)
            }
        }
    }

    private fun getReplyText(intent: Intent): String? {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        return remoteInput?.getCharSequence(KEY_REPLY_TEXT)?.toString()
    }

    private fun handleReply(
        context: Context,
        chatId: String,
        otherUserId: String,
        otherUsername: String,
        replyText: String,
        notificationId: Int
    ) {

        // Immediately update notification to show reply was sent (stops the spinner)
        updateNotificationWithReply(context, chatId, otherUserId, otherUsername, replyText, notificationId)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send the message
                val db = AppDatabase.getDatabase(context)
                val repository = RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), context.applicationContext)
                repository.sendMessage(chatId, replyText, otherUserId, otherUsername, "")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending reply", e)

                // Fallback: enqueue background work to retry when network is available.
                scheduleReplyWork(context.applicationContext, chatId, otherUserId, otherUsername, replyText)
            }
        }
    }

    private fun scheduleReplyWork(
        context: Context,
        chatId: String,
        otherUserId: String,
        otherUsername: String,
        replyText: String
    ) {
        val input = workDataOf(
            NotificationReplyWorker.KEY_CHAT_ID to chatId,
            NotificationReplyWorker.KEY_OTHER_USER_ID to otherUserId,
            NotificationReplyWorker.KEY_OTHER_USERNAME to otherUsername,
            NotificationReplyWorker.KEY_REPLY_TEXT to replyText
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = OneTimeWorkRequestBuilder<NotificationReplyWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("notif_reply")
            .addTag("chat_$chatId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "notif_reply_${chatId}_${System.currentTimeMillis()}",
                ExistingWorkPolicy.KEEP,
                work
            )
    }
    
    private fun updateNotificationWithReply(
        context: Context,
        chatId: String,
        otherUserId: String,
        otherUsername: String,
        replyText: String,
        notificationId: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationTag = ChatNotificationHelper.notificationTagForChat(chatId)
        
        // Use the dynamic channel that matches current user preferences
        val activeChannelId = ChatNotificationHelper.currentChannelId(context)
        
        // Get existing unread messages
        UnreadMessageStore.init(context)
        val existingMessages = UnreadMessageStore.getMessages(chatId)
        
        // Build persons for the conversation
        val otherPerson = Person.Builder()
            .setName(otherUsername)
            .build()
        
        val mePerson = Person.Builder()
            .setName("You")
            .build()
        
        // Create MessagingStyle with existing messages + the reply
        val messagingStyle = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(otherUsername)
        
        // Add existing unread messages from the other person
        for (msg in existingMessages) {
            messagingStyle.addMessage(msg.text, msg.timestamp, otherPerson)
        }
        
        // Add the reply from "You"
        messagingStyle.addMessage(replyText, System.currentTimeMillis(), mePerson)
        
        // Build updated notification without sound (to avoid annoying the user)
        val notificationBuilder = NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Don't make sound/vibration on update
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(ChatNotificationHelper.groupKeyForChat(chatId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        
        // Re-add the actions
        val replyRemoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(EXTRA_OTHER_USERNAME, otherUsername)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        
        val replyPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
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
            action = ACTION_MARK_READ
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        
        val markReadPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            markReadIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            "Mark as read",
            markReadPendingIntent
        ).build()
        
        notificationBuilder
            .addAction(replyAction)
            .addAction(markReadAction)
        
        // Replace the existing per-chat notification (and the phase-1 system alert if present).
        notificationManager.notify(notificationTag, 0, notificationBuilder.build())
    }

    private fun handleMarkAsRead(context: Context, chatId: String, notificationId: Int) {

        // Clear the unread messages for this chat
        UnreadMessageStore.init(context)
        UnreadMessageStore.clearMessages(chatId)

        // Dismiss the notification (message + summary)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationTag = ChatNotificationHelper.notificationTagForChat(chatId)
        notificationManager.cancel(notificationTag, 0)
        notificationManager.cancel(notificationTag, 1)
        
        // Update status on server
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val repository = RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), context.applicationContext)
                repository.markChatAsRead(chatId)
            } catch (e: Exception) {
                Log.e(TAG, "Error marking chat as read on server", e)
            }
        }
    }

    private fun handleCameraInviteAccept(
        context: Context,
        chatId: String,
        intent: Intent,
        notificationId: Int
    ) {
        val notifTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG) ?: ""
        val senderUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: ""
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""

        // Dismiss notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifTag, notificationId)

        // Write accepted command to RTDB so the inviter gets notified
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val myName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
        if (myUid.isNotEmpty() && chatId.isNotEmpty() && requestId.isNotEmpty()) {
            com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
                .sendCameraInviteAcceptedCommand(
                    chatId = chatId,
                    targetUserId = senderUserId,
                    requestId = requestId,
                    responderUserId = myUid,
                    responderName = myName
                )
        }

        // Open the chat screen with camera auto-enabled
        val openIntent = com.glyph.glyph_v3.ui.chat.ChatActivity.newIntent(
            context = context,
            chatId = chatId,
            otherUserId = senderUserId,
            enableCamera = true
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(openIntent)
    }

    private fun handleCameraInviteDecline(
        context: Context,
        chatId: String,
        intent: Intent,
        notificationId: Int
    ) {
        val notifTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG) ?: ""
        val senderUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: ""
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""

        // Dismiss notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifTag, notificationId)

        // Write declined command to RTDB so the inviter gets notified
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val myName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
        if (myUid.isNotEmpty() && chatId.isNotEmpty() && requestId.isNotEmpty()) {
            com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
                .sendCameraInviteDeclinedCommand(
                    chatId = chatId,
                    targetUserId = senderUserId,
                    requestId = requestId,
                    responderUserId = myUid,
                    responderName = myName
                )
        }

        // Clear the in-map invite prompt if the chat is currently open
        com.glyph.glyph_v3.data.service.MapVideoSessionRegistry
            .getByChatId(chatId)
            ?.dismissIncomingCameraInvite()

        // Also dismiss via local broadcast so ChatActivity's local session manager clears the prompt
        // (the registry only holds the foreground-service session; ChatActivity may have a local one).
        val dismissIntent = android.content.Intent(ACTION_DISMISS_CAMERA_INVITE_LOCAL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        context.sendBroadcast(dismissIntent)
    }
}
