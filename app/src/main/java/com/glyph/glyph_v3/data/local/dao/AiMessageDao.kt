package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.glyph.glyph_v3.data.local.entity.AiMessage
import kotlinx.coroutines.flow.Flow

/**
 * DAO for AI Agent conversation messages.
 *
 * Follows the same patterns as [MessageDao] / [TranslationCacheDao]:
 *   • `suspend` for one-shot writes
 *   • `Flow` for observable reads
 *   • `REPLACE` strategy for upserts
 */
@Dao
interface AiMessageDao {

    // ── Observable reads ─────────────────────────────────

    /** All messages ordered chronologically (for adapter). */
    @Query("SELECT * FROM ai_messages ORDER BY timestamp ASC")
    fun getMessages(): Flow<List<AiMessage>>

    /** Messages for a specific mode. */
    @Query("SELECT * FROM ai_messages WHERE mode = :mode ORDER BY timestamp ASC")
    fun getMessagesByMode(mode: String): Flow<List<AiMessage>>

    // ── One-shot reads ───────────────────────────────────

    /** Last N messages (for building the Gemini context window). */
    @Query("SELECT * FROM (SELECT * FROM ai_messages ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    suspend fun getRecentMessages(limit: Int): List<AiMessage>

    /** Count of all messages. */
    @Query("SELECT COUNT(*) FROM ai_messages")
    suspend fun getMessageCount(): Int

    // ── Writes ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AiMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AiMessage>)

    @Update
    suspend fun update(message: AiMessage): Int

    /** Mark a streaming placeholder as completed with the final text. */
    @Query("UPDATE ai_messages SET text = :text, isStreaming = 0 WHERE id = :id")
    suspend fun completeStreaming(id: String, text: String): Int

    // ── Deletes ──────────────────────────────────────────

    @Query("DELETE FROM ai_messages")
    suspend fun clearAll(): Int

    @Query("DELETE FROM ai_messages WHERE mode = :mode")
    suspend fun clearByMode(mode: String): Int

    @Query("DELETE FROM ai_messages WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
