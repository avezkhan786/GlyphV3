package com.glyph.glyph_v3.data.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository

class NotificationReplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NotifReplyWorker"

        const val KEY_CHAT_ID = "chat_id"
        const val KEY_OTHER_USER_ID = "other_user_id"
        const val KEY_OTHER_USERNAME = "other_username"
        const val KEY_REPLY_TEXT = "reply_text"
    }

    override suspend fun doWork(): Result {
        val chatId = inputData.getString(KEY_CHAT_ID) ?: return Result.failure()
        val otherUserId = inputData.getString(KEY_OTHER_USER_ID) ?: return Result.failure()
        val otherUsername = inputData.getString(KEY_OTHER_USERNAME) ?: "Unknown"
        val replyText = inputData.getString(KEY_REPLY_TEXT) ?: return Result.failure()

        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repo = RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), applicationContext)

            if (repo.currentUserId == null) {
                Log.w(TAG, "No auth user yet; retrying")
                return Result.retry()
            }

            repo.sendMessage(chatId, replyText, otherUserId, otherUsername, "")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send inline reply; will retry", e)
            Result.retry()
        }
    }
}
