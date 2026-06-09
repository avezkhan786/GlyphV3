package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deleted_messages")
data class LocalDeletedMessage(
    @PrimaryKey val id: String,
    val chatId: String,
    val deletedAt: Long
)
