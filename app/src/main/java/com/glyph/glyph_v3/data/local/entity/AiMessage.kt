package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for AI Agent conversation messages.
 *
 * All AI conversation history is stored **only on-device** (Room) to keep
 * Firebase costs at zero. Before each Cloud Function call the client reads
 * the last N messages from this table and sends them as the context window.
 *
 * Table: "ai_messages"
 */
@Entity(
    tableName = "ai_messages",
    indices = [Index(value = ["timestamp"])]
)
data class AiMessage(
    @PrimaryKey val id: String,

    /** "user" or "model" */
    val role: String,

    /** The message text (user query or AI response) */
    val text: String,

    /** Unix epoch millis */
    val timestamp: Long = System.currentTimeMillis(),

    /** Which AI mode produced/received this: "chat", "search", "app" */
    val mode: String = "chat",

    /**
     * JSON-encoded list of source citations (only for search results).
     * Format: [{"chatId":"...","msgId":"...","text":"...","timestamp":123,"senderId":"..."}]
     * Null for non-search messages.
     */
    val sourcesJson: String? = null,

    /**
     * True while streaming response is in progress (optimistic placeholder).
     * Once the full response arrives this is flipped to false.
     */
    val isStreaming: Boolean = false
)
