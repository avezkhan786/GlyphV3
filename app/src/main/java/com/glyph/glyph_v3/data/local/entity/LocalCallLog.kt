package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_logs",
    indices = [
        Index(value = ["callerId"]),
        Index(value = ["receiverId"]),
        Index(value = ["createdAt"])
    ]
)
data class LocalCallLog(
    @PrimaryKey val callId: String,
    val callerId: String,
    val receiverId: String,
    val callerName: String,
    val callerAvatar: String = "",
    val callerPhone: String = "",
    val receiverName: String,
    val receiverAvatar: String = "",
    val receiverPhone: String = "",
    val type: String,
    val status: String,
    val createdAt: Long,
    val answeredAt: Long = 0L,
    val endedAt: Long = 0L
)