package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.glyph.glyph_v3.data.local.entity.LocalChat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: LocalChat)

    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC")
    fun getAllChats(): Flow<List<LocalChat>>

    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC")
    suspend fun getAllChatsOnce(): List<LocalChat>

    @Query("SELECT * FROM chats WHERE isArchived = 1 ORDER BY lastMessageTimestamp DESC")
    fun getArchivedChats(): Flow<List<LocalChat>>

    @Query("SELECT * FROM chats WHERE isArchived = 1 ORDER BY lastMessageTimestamp DESC")
    suspend fun getArchivedChatsOnce(): List<LocalChat>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): LocalChat?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeChatById(chatId: String): Flow<LocalChat?>

    @Query("UPDATE chats SET isArchived = :isArchived WHERE id = :chatId")
    suspend fun updateArchivedStatus(chatId: String, isArchived: Boolean): Int

    @Query("UPDATE chats SET lastMessage = :message, lastMessageTimestamp = :timestamp, lastMessageSenderId = :senderId, lastMessageStatus = :status WHERE id = :chatId AND lastMessageTimestamp <= :timestamp")
    suspend fun updateLastMessage(chatId: String, message: String, timestamp: Long, senderId: String, status: String): Int

    @Query("UPDATE chats SET lastMessage = :message, lastMessageTimestamp = :timestamp, lastMessageSenderId = :senderId, lastMessageStatus = :status WHERE id = :chatId")
    suspend fun forceUpdateLastMessage(chatId: String, message: String, timestamp: Long, senderId: String, status: String): Int

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun incrementUnreadCount(chatId: String): Int

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun clearUnreadCount(chatId: String): Int

    @Query("UPDATE chats SET otherUsername = :username, otherUserAvatar = :avatar WHERE id = :chatId AND isGroup = 0")
    suspend fun updateUserInfo(chatId: String, username: String, avatar: String): Int

    @Query("SELECT id FROM chats")
    suspend fun getAllChatIds(): List<String>

    @Query("SELECT id FROM chats WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getTopActiveChatIds(limit: Int): List<String>

    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getTopActiveChats(limit: Int): List<LocalChat>

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String): Int

    @Query("DELETE FROM chats WHERE id IN (:chatIds)")
    suspend fun deleteChats(chatIds: List<String>): Int

    // ── Group chat helpers ─────────────────────────────────────
    @Query("UPDATE chats SET groupName = :name, groupIconUrl = :iconUrl, groupDescription = :description WHERE id = :chatId AND isGroup = 1")
    suspend fun updateGroupInfo(chatId: String, name: String, iconUrl: String, description: String): Int

    @Query("UPDATE chats SET participantsJson = :participantsJson, adminsJson = :adminsJson WHERE id = :chatId AND isGroup = 1")
    suspend fun updateGroupMembership(chatId: String, participantsJson: String, adminsJson: String): Int
}