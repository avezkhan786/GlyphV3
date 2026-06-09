package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glyph.glyph_v3.data.local.entity.LocalCallLog
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: LocalCallLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLogs(callLogs: List<LocalCallLog>)

    @Query("SELECT * FROM call_logs WHERE callerId = :userId OR receiverId = :userId ORDER BY createdAt DESC, endedAt DESC, answeredAt DESC")
    fun observeCallLogs(userId: String): Flow<List<LocalCallLog>>

    @Query("SELECT * FROM call_logs WHERE callerId = :userId OR receiverId = :userId ORDER BY createdAt DESC, endedAt DESC, answeredAt DESC LIMIT :limit")
    suspend fun getRecentCallLogs(userId: String, limit: Int): List<LocalCallLog>

    @Query("SELECT COUNT(*) FROM call_logs WHERE callerId = :userId OR receiverId = :userId")
    suspend fun countCallLogs(userId: String): Int

    @Query("DELETE FROM call_logs WHERE callId IN (:callIds)")
    suspend fun deleteCallLogs(callIds: List<String>): Int
}