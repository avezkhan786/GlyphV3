package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glyph.glyph_v3.data.local.entity.LocalDeletedMessage

@Dao
interface DeletedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedMessage(message: LocalDeletedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedMessages(messages: List<LocalDeletedMessage>)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_messages WHERE id = :id)")
    suspend fun isMessageDeletedForMe(id: String): Boolean

    @Query("DELETE FROM deleted_messages WHERE id = :id")
    suspend fun removeDeletedTombstone(id: String): Int

    @Query("DELETE FROM deleted_messages WHERE chatId = :chatId")
    suspend fun removeAllTombstonesForChat(chatId: String): Int
}
