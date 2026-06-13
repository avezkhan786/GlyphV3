package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.entity.LocalCallLog
import com.glyph.glyph_v3.data.models.CallData
import kotlinx.coroutines.flow.Flow

object CallLogRepository {
    private const val PREFS_NAME = "call_log_history"
    private const val KEY_REMOTE_IMPORT_PREFIX = "remote_history_imported_v2_"

    fun observeCallLogs(context: Context, userId: String): Flow<List<LocalCallLog>> {
        return callLogDao(context).observeCallLogs(userId)
    }

    suspend fun upsertCallLog(
        context: Context,
        callData: CallData,
        statusOverride: String? = null,
        endedAtOverride: Long? = null
    ) {
        val effectiveStatus = statusOverride ?: callData.status
        val effectiveEndedAt = when {
            endedAtOverride != null -> endedAtOverride
            effectiveStatus in TERMINAL_STATUSES && callData.endedAt <= 0L -> System.currentTimeMillis()
            else -> callData.endedAt
        }

        Log.d(
            TAG,
            "upsertCallLog callId=${callData.callId} status=$effectiveStatus caller=${callData.callerId} receiver=${callData.receiverId} createdAt=${callData.createdAt} answeredAt=${callData.answeredAt} endedAt=$effectiveEndedAt"
        )

        callLogDao(context).insertCallLog(
            LocalCallLog(
                callId = callData.callId,
                callerId = callData.callerId,
                receiverId = callData.receiverId,
                callerName = callData.callerName,
                callerAvatar = callData.callerAvatar,
                callerPhone = callData.callerPhone,
                receiverName = callData.receiverName,
                receiverAvatar = callData.receiverAvatar,
                receiverPhone = callData.receiverPhone,
                type = callData.type,
                status = effectiveStatus,
                createdAt = callData.createdAt,
                answeredAt = callData.answeredAt,
                endedAt = effectiveEndedAt
            )
        )

        val currentUserId = currentUserIdFor(callData)
        if (currentUserId.isNotBlank()) {
            val currentCount = callLogDao(context).countCallLogs(currentUserId)
            Log.d(TAG, "upsertCallLog stored callId=${callData.callId}; userId=$currentUserId count=$currentCount")
        }
    }

    suspend fun upsertCallLogs(context: Context, callDataList: List<CallData>) {
        if (callDataList.isEmpty()) return

        val now = System.currentTimeMillis()
        val logs = callDataList
            .filter { it.callId.isNotBlank() }
            .map { callData ->
                val effectiveEndedAt = when {
                    callData.endedAt > 0L -> callData.endedAt
                    callData.status in TERMINAL_STATUSES -> now
                    else -> 0L
                }

                LocalCallLog(
                    callId = callData.callId,
                    callerId = callData.callerId,
                    receiverId = callData.receiverId,
                    callerName = callData.callerName,
                    callerAvatar = callData.callerAvatar,
                    callerPhone = callData.callerPhone,
                    receiverName = callData.receiverName,
                    receiverAvatar = callData.receiverAvatar,
                    receiverPhone = callData.receiverPhone,
                    type = callData.type,
                    status = callData.status,
                    createdAt = callData.createdAt,
                    answeredAt = callData.answeredAt,
                    endedAt = effectiveEndedAt
                )
            }

        callLogDao(context).insertCallLogs(logs)

        val userIds = logs
            .flatMap { listOf(it.callerId, it.receiverId) }
            .filter { it.isNotBlank() }
            .distinct()
        userIds.forEach { userId ->
            val currentCount = callLogDao(context).countCallLogs(userId)
            Log.d(TAG, "upsertCallLogs stored=${logs.size} userId=$userId count=$currentCount")
        }
    }

    suspend fun deleteCallLogs(context: Context, callIds: Collection<String>) {
        if (callIds.isEmpty()) return
        callLogDao(context).deleteCallLogs(callIds.distinct())
    }

    suspend fun countCallLogs(context: Context, userId: String): Int {
        return callLogDao(context).countCallLogs(userId)
    }

    suspend fun getRecentCallLogs(context: Context, userId: String, limit: Int = 5): List<LocalCallLog> {
        return callLogDao(context).getRecentCallLogs(userId, limit)
    }

    suspend fun logRecentCallLogs(context: Context, userId: String, reason: String, limit: Int = 5) {
        val recentLogs = getRecentCallLogs(context, userId, limit)
        Log.d(
            TAG,
            "logRecentCallLogs reason=$reason userId=$userId count=${recentLogs.size} rows=${recentLogs.joinToString { row -> row.callId + ":" + row.status + ":caller=" + row.callerId + ":receiver=" + row.receiverId + ":createdAt=" + row.createdAt + ":endedAt=" + row.endedAt }}"
        )
    }

    fun wasRemoteHistoryImported(context: Context, userId: String): Boolean {
        return prefs(context).getBoolean(importKey(userId), false)
    }

    fun markRemoteHistoryImported(context: Context, userId: String) {
        prefs(context).edit().putBoolean(importKey(userId), true).apply()
    }

    private fun callLogDao(context: Context) =
        AppDatabase.getDatabase(context.applicationContext).callLogDao()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun importKey(userId: String): String = "$KEY_REMOTE_IMPORT_PREFIX$userId"

    private fun currentUserIdFor(callData: CallData): String {
        return callData.callerId.ifBlank { callData.receiverId }
    }

    private val TERMINAL_STATUSES = setOf("ended", "declined", "missed", "no_answer", "busy")

    private const val TAG = "CallLogRepository"
}