package com.glyph.glyph_v3.data.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class DeliveryReceiptWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DeliveryReceiptWorker"
        private const val TRACE_TAG = "MessageTrace"

        const val KEY_CHAT_ID = "chat_id"
        const val KEY_SENDER_ID = "sender_id"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_DELIVERED_AT = "delivered_at"
        const val KEY_TRIGGER = "trigger"

        private fun uniqueWorkName(chatId: String, messageId: String): String {
            return "delivery_receipt_${chatId}_${messageId}"
        }

        private fun traceKey(chatId: String, messageId: String): String {
            return "${chatId.takeLast(6)}/${messageId.take(8)}"
        }

        fun enqueue(
            context: Context,
            chatId: String,
            senderId: String,
            messageId: String,
            deliveredAt: Long,
            trigger: String = "unknown"
        ) {
            val inputData = Data.Builder()
                .putString(KEY_CHAT_ID, chatId)
                .putString(KEY_SENDER_ID, senderId)
                .putString(KEY_MESSAGE_ID, messageId)
                .putLong(KEY_DELIVERED_AT, deliveredAt)
                .putString(KEY_TRIGGER, trigger)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DeliveryReceiptWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName(chatId, messageId), ExistingWorkPolicy.KEEP, request)

        }

        fun cancel(context: Context, chatId: String, messageId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(chatId, messageId))
        }
    }

    override suspend fun doWork(): Result {
        val chatId = inputData.getString(KEY_CHAT_ID) ?: return Result.failure()
        val senderId = inputData.getString(KEY_SENDER_ID) ?: return Result.failure()
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val deliveredAt = inputData.getLong(KEY_DELIVERED_AT, System.currentTimeMillis())
        val trigger = inputData.getString(KEY_TRIGGER) ?: "unknown"
        val trace = traceKey(chatId, messageId)


        val recipientId = FirebaseAuth.getInstance().currentUser?.uid
        if (recipientId.isNullOrBlank()) {
            Log.w(TAG, "No auth user yet; retrying delivery receipt for $messageId")
            return Result.retry()
        }

        return try {
            FirebaseDatabase.getInstance()
                .reference
                .child("delivery_receipts")
                .child(senderId)
                .child(chatId)
                .child(messageId)
                .setValue(
                    mapOf(
                        "status" to "DELIVERED",
                        "recipientId" to recipientId,
                        "deliveredAt" to deliveredAt
                    )
                )
                .await()

            writeFirestoreDeliveryStateIfNotRead(chatId, messageId, deliveredAt)

            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Delivery receipt retry failed for $messageId", error)
            Result.retry()
        }
    }

    private suspend fun writeFirestoreDeliveryStateIfNotRead(
        chatId: String,
        messageId: String,
        deliveredAt: Long
    ) {
        val firestore = FirebaseFirestore.getInstance()
        val messageRef = firestore
            .collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(messageRef)
            val currentStatus = snapshot.getString("status")
            if (currentStatus != "READ" && currentStatus != "PLAYED") {
                transaction.set(
                    messageRef,
                    mapOf(
                        "status" to "DELIVERED",
                        "deliveredTimestamp" to deliveredAt,
                        "deliveredAt" to deliveredAt
                    ),
                    SetOptions.merge()
                )
            }
            true
        }.await()
    }
}