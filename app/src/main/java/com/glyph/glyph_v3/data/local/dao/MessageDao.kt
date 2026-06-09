package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LocalMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<LocalMessage>)

    @Query("""
        SELECT m.* FROM messages m
        LEFT JOIN deleted_messages d ON m.id = d.id
        WHERE m.chatId = :chatId AND d.id IS NULL
        ORDER BY
            COALESCE(m.serverTimestamp, m.timestamp) ASC,
            m.id ASC
    """)
    fun getMessages(chatId: String): Flow<List<LocalMessage>>

    @Query("""
        SELECT m.* FROM messages m
        LEFT JOIN deleted_messages d ON m.id = d.id
        WHERE m.chatId = :chatId AND d.id IS NULL
        ORDER BY COALESCE(m.serverTimestamp, m.timestamp) DESC, m.id DESC LIMIT :limit
    """)
    suspend fun getRecentMessages(chatId: String, limit: Int): List<LocalMessage>

    /**
     * Reactive windowed query for WhatsApp-style pagination.
     *
     * Returns the most recent [limit] messages (newest-first), reacting to DB changes.
     * The repository reverses this to ascending (oldest -> newest) for the UI.
     *
     * As the user scrolls up, the caller increases [limit] and re-subscribes, which
     * loads older messages from the local DB without ever holding the entire history
     * in the adapter at once.
     */
    @Query("""
        SELECT m.* FROM messages m
        LEFT JOIN deleted_messages d ON m.id = d.id
        WHERE m.chatId = :chatId AND d.id IS NULL
        ORDER BY COALESCE(m.serverTimestamp, m.timestamp) DESC, m.id DESC LIMIT :limit
    """)
    fun getRecentMessagesFlow(chatId: String, limit: Int): Flow<List<LocalMessage>>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: MessageStatus): Int

    @Query("UPDATE messages SET status = :status, deliveredTimestamp = :timestamp WHERE id = :id")
    suspend fun updateMessageDelivered(id: String, status: MessageStatus, timestamp: Long): Int

    @Query("UPDATE messages SET status = :status, deliveredTimestamp = COALESCE(:deliveredTimestamp, deliveredTimestamp), readTimestamp = COALESCE(:readTimestamp, readTimestamp) WHERE id = :id")
    suspend fun updateMessageReceiptState(id: String, status: MessageStatus, deliveredTimestamp: Long?, readTimestamp: Long?): Int

    @Query("UPDATE messages SET status = 'READ', readTimestamp = :readTime WHERE chatId = :chatId AND isIncoming = 0 AND COALESCE(deliveredTimestamp, timestamp) <= :readTime AND status != 'READ' AND status != 'PLAYED'")
    suspend fun markSentMessagesAsRead(chatId: String, readTime: Long): Int

    @Query("SELECT id FROM messages WHERE chatId = :chatId AND isIncoming = 0 AND COALESCE(deliveredTimestamp, timestamp) <= :readTime AND status != 'READ' AND status != 'PLAYED'")
    suspend fun getSentMessageIdsToMarkRead(chatId: String, readTime: Long): List<String>
    
    @Query("UPDATE messages SET mediaItems = :mediaItems, status = :status WHERE id = :id")
    suspend fun updateMediaGroupMessage(id: String, mediaItems: String, status: MessageStatus): Int
    
    @Query("UPDATE messages SET mediaItems = :mediaItems WHERE id = :id")
    suspend fun updateMessageMediaItems(id: String, mediaItems: String): Int
    
    @Query("UPDATE messages SET status = :status, readTimestamp = :readTime WHERE chatId = :chatId AND isIncoming = 1 AND status != 'READ'")
    suspend fun markIncomingMessagesAsRead(chatId: String, readTime: Long, status: MessageStatus): Int

    @Query("SELECT id FROM messages WHERE chatId = :chatId AND isIncoming = 1 AND status != 'READ' AND timestamp <= :readTime")
    suspend fun getUnreadIncomingMessageIdsUpTo(chatId: String, readTime: Long): List<String>
    
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): LocalMessage?

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<LocalMessage>

    @Query("SELECT * FROM messages WHERE isIncoming = 0 AND status IN (:statuses) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getOutgoingMessagesByStatuses(statuses: List<MessageStatus>, limit: Int): List<LocalMessage>

    @Query("UPDATE messages SET isDeletedForAll = :isDeletedForAll, deletedAt = :deletedAt WHERE id = :id")
    suspend fun updateDeletedForAllState(id: String, isDeletedForAll: Boolean, deletedAt: Long?): Int

    @Query("UPDATE messages SET isDeletedForAll = 1, deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun markMessagesDeletedForAll(ids: List<String>, deletedAt: Long?): Int
    
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String): Int

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String): Int

    @Query("SELECT id FROM messages WHERE chatId = :chatId")
    suspend fun getAllMessageIdsByChatId(chatId: String): List<String>

    @Query("UPDATE messages SET text = :text, isEdited = 1, editedAt = :editedAt WHERE id = :id")
    suspend fun updateMessageText(id: String, text: String, editedAt: Long): Int

    /** Replace the reactions JSON for a single message. Pass null to clear. */
    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE id = :id")
    suspend fun updateReactions(id: String, reactionsJson: String?): Int

    // ── Group per-recipient receipts (groups only) ─────────────────────────
    /** Replace deliveredTo JSON for a single message. Pass null to clear. */
    @Query("UPDATE messages SET deliveredToJson = :deliveredToJson WHERE id = :id")
    suspend fun updateDeliveredTo(id: String, deliveredToJson: String?): Int

    /** Replace readBy JSON for a single message. Pass null to clear. */
    @Query("UPDATE messages SET readByJson = :readByJson WHERE id = :id")
    suspend fun updateReadBy(id: String, readByJson: String?): Int

    @Query("UPDATE messages SET localUri = :localUri WHERE id = :id")
    suspend fun updateLocalUri(id: String, localUri: String): Int

    @Query("UPDATE messages SET mediaWidth = :mediaWidth, mediaHeight = :mediaHeight WHERE id = :id")
    suspend fun updateMediaDimensions(id: String, mediaWidth: Int, mediaHeight: Int): Int

    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId AND isIncoming = 1")
    suspend fun getLatestIncomingMessageTimestamp(chatId: String): Long?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY COALESCE(serverTimestamp, timestamp) DESC, id DESC LIMIT 1")
    suspend fun getLatestMessage(chatId: String): LocalMessage?

    @Query("SELECT MAX(COALESCE(serverTimestamp, timestamp)) FROM messages WHERE chatId = :chatId")
    suspend fun getLatestMessageTimestamp(chatId: String): Long?

    @Query("UPDATE messages SET serverTimestamp = :serverTimestamp WHERE id = :id AND (serverTimestamp IS NULL OR serverTimestamp != :serverTimestamp)")
    suspend fun updateServerTimestamp(id: String, serverTimestamp: Long): Int

    // Sync the sender's client timestamp from Firestore so all devices use an identical
    // ordering key for pending (pre-ACK) messages. Only updates if the stored value differs.
    @Query("UPDATE messages SET timestamp = :timestamp WHERE id = :id AND timestamp != :timestamp")
    suspend fun updateClientTimestamp(id: String, timestamp: Long): Int
    
    @Query("""
        SELECT * FROM messages 
        WHERE isIncoming = 1 
        AND (type = 'IMAGE' OR type = 'VIDEO' OR type = 'AUDIO' OR type = 'MEDIA_GROUP' OR type = 'GIF' OR type = 'STICKER' OR type = 'KLIPY_EMOJI' OR type = 'MEME') 
        AND (status = 'PENDING_DOWNLOAD' OR status = 'DOWNLOAD_FAILED' OR status = 'SENT' OR status = 'DELIVERED')
        AND (type = 'MEDIA_GROUP' OR localUri IS NULL OR localUri = '')
    """)
    suspend fun getMessagesWithPendingDownload(): List<LocalMessage>

    /**
     * Returns the most recent [limit] image/video/group/sticker messages for a chat,
     * excluding tombstoned (deleted-for-me) and deleted-for-all messages.
     * Used by ContactInfoScreen to populate the media grid from local storage first.
     */
    @Query("""
        SELECT m.* FROM messages m
        LEFT JOIN deleted_messages d ON m.id = d.id
        WHERE m.chatId = :chatId
          AND d.id IS NULL
          AND m.isDeletedForAll = 0
          AND m.type IN ('IMAGE', 'VIDEO', 'MEDIA_GROUP', 'GIF', 'MEME', 'STICKER', 'KLIPY_EMOJI')
        ORDER BY m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun getMediaMessages(chatId: String, limit: Int): List<LocalMessage>

    // ── Starred / Pinned ──────────────────────────────────────────────────────

    @Query("UPDATE messages SET isStarred = :starred WHERE id IN (:ids)")
    suspend fun starMessages(ids: List<String>, starred: Boolean): Int

    @Query("UPDATE messages SET isPinned = :pinned, pinnedUntil = :until WHERE id IN (:ids)")
    suspend fun pinMessages(ids: List<String>, pinned: Boolean, until: Long?): Int

    @Query("UPDATE messages SET isPinned = 0, pinnedUntil = NULL WHERE id IN (:ids)")
    suspend fun unpinMessages(ids: List<String>): Int

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isStarred = 1 ORDER BY timestamp DESC")
    suspend fun getStarredMessages(chatId: String): List<LocalMessage>

    /** Returns pinned messages that have not yet expired. Pass currentTimeMs = System.currentTimeMillis(). */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND isPinned = 1
          AND (pinnedUntil IS NULL OR pinnedUntil > :currentTimeMs)
        ORDER BY timestamp DESC
    """)
    suspend fun getActivePinnedMessages(chatId: String, currentTimeMs: Long): List<LocalMessage>

    /** Flow version — automatically re-emits when any pin changes. */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND isPinned = 1
          AND (pinnedUntil IS NULL OR pinnedUntil > :currentTimeMs)
        ORDER BY timestamp DESC
    """)
    fun observeActivePinnedMessages(chatId: String, currentTimeMs: Long): kotlinx.coroutines.flow.Flow<List<LocalMessage>>
}
