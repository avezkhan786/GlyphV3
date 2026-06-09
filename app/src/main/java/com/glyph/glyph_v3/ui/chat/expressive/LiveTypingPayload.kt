package com.glyph.glyph_v3.ui.chat.expressive

/**
 * Data sent/received through Firebase Realtime Database for live typing preview.
 */
data class LiveTypingPayload(
    val chatId: String = "",
    val senderId: String = "",
    val liveText: String = "",
    val timestamp: Long = 0L,
    val expressiveEnabled: Boolean = true
) {
    /** Convert to Firebase-compatible map */
    fun toMap(): Map<String, Any?> = mapOf(
        "chatId" to chatId,
        "senderId" to senderId,
        "liveText" to liveText,
        "timestamp" to timestamp,
        "expressiveEnabled" to expressiveEnabled
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): LiveTypingPayload {
            return LiveTypingPayload(
                chatId = map["chatId"] as? String ?: "",
                senderId = map["senderId"] as? String ?: "",
                liveText = map["liveText"] as? String ?: "",
                timestamp = (map["timestamp"] as? Long) ?: 0L,
                expressiveEnabled = map["expressiveEnabled"] as? Boolean ?: false
            )
        }
    }
}
