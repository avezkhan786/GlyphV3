package com.glyph.glyph_v3.ui.chat.expressive

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/**
 * Manages real-time live typing data via Firebase Realtime Database.
 *
 * Data path: liveTyping/{chatId}/{senderUserId}/{receiverUserId}
 *
 * Features:
 *   - Debounced sending (150ms) to reduce traffic
 *   - Auto-clear after 1s of inactivity
 *   - Immediate clear on message send
 *   - Lifecycle-aware (cleanup on destroy)
 */
class LiveTypingRepository {

    companion object {
        private const val TAG = "LiveTypingRepo"
        private const val LIVE_TYPING_PATH = "liveTyping"
        private const val DEBOUNCE_MS = 80L
        private const val INACTIVITY_CLEAR_MS = 1000L
        private const val LIVE_TYPING_FRESHNESS_MS = 5000L
        private const val LIVE_TYPING_MAX_FUTURE_SKEW_MS = 10000L
    }

    private val database = FirebaseDatabase.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val liveTypingRef = database.getReference(LIVE_TYPING_PATH)
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sendJob: Job? = null
    private var clearJob: Job? = null
    private var currentChatId: String? = null
    private var currentOtherUserId: String? = null
    @Volatile
    private var lastSentLiveText: String = ""

    private fun chatDoc(chatId: String) = firestore.collection("chats").document(chatId)

    private fun LiveTypingPayload.isFresh(now: Long = System.currentTimeMillis()): Boolean {
        if (timestamp <= 0L) return false
        val ageMs = now - timestamp
        return ageMs in -LIVE_TYPING_MAX_FUTURE_SKEW_MS until LIVE_TYPING_FRESHNESS_MS
    }

    private fun extractFirestorePayload(
        data: Map<String, Any?>?,
        senderId: String,
        receiverId: String
    ): LiveTypingPayload? {
        val liveTypingStates = data?.get("liveTypingStates") as? Map<*, *> ?: return null
        val senderStates = liveTypingStates[senderId] as? Map<*, *> ?: return null
        val receiverStateRaw = senderStates[receiverId] as? Map<*, *> ?: return null
        if (receiverStateRaw.keys.any { it !is String }) return null
        val receiverState = receiverStateRaw.entries.associate { (key, value) ->
            key as String to value
        }
        val payload = LiveTypingPayload.fromMap(receiverState)
        return payload.takeIf { it.expressiveEnabled && it.liveText.isNotEmpty() }
    }

    private fun mirrorLiveText(chatId: String, senderId: String, receiverId: String, payload: LiveTypingPayload?) {
        val mirroredPayload = payload?.toMap() ?: mapOf(
            "chatId" to chatId,
            "senderId" to senderId,
            "liveText" to "",
            "timestamp" to System.currentTimeMillis(),
            "expressiveEnabled" to false
        )

        chatDoc(chatId)
            .set(
                mapOf(
                    "liveTypingStates" to mapOf(
                        senderId to mapOf(
                            receiverId to mirroredPayload
                        )
                    )
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { e -> Log.e(TAG, "Failed to mirror live typing to Firestore", e) }
    }

    /**
     * Send live typing text with debouncing.
     * Cancels previous pending send if user types again within 150ms.
     */
    fun sendLiveText(chatId: String, otherUserId: String, text: String) {
        val userId = auth.currentUser?.uid ?: return
        currentChatId = chatId
        currentOtherUserId = otherUserId
        database.goOnline()
        liveTypingRef.keepSynced(true)
        liveTypingRef.child(chatId).keepSynced(true)

        // Cancel previous debounce
        sendJob?.cancel()
        // Cancel the inactivity clear timer
        clearJob?.cancel()

        if (text.isEmpty()) {
            // Immediately clear when text is empty
            clearLiveText(chatId, otherUserId)
            return
        }

        // Skip redundant writes when the text has not changed.
        if (text == lastSentLiveText) {
            clearJob = scope.launch {
                delay(INACTIVITY_CLEAR_MS + DEBOUNCE_MS)
                clearLiveText(chatId, otherUserId)
            }
            return
        }

        sendJob = scope.launch {
            delay(DEBOUNCE_MS)
            val payload = LiveTypingPayload(
                chatId = chatId,
                senderId = userId,
                liveText = text,
                timestamp = System.currentTimeMillis(),
                expressiveEnabled = true
            )
            try {
                lastSentLiveText = text
                liveTypingRef.child(chatId).child(userId).child(otherUserId).setValue(payload.toMap())
                    .addOnSuccessListener { }
                    .addOnFailureListener { e -> Log.e(TAG, "RTDB setValue FAILED (check rules!)", e) }
                mirrorLiveText(chatId, userId, otherUserId, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send live text", e)
            }
        }

        // Schedule auto-clear after inactivity
        clearJob = scope.launch {
            delay(INACTIVITY_CLEAR_MS + DEBOUNCE_MS)
            clearLiveText(chatId, otherUserId)
        }
    }

    /**
     * Clear live typing data for the current user in a chat.
     * Called when message is sent, user leaves chat, or text is cleared.
     */
    fun clearLiveText(chatId: String, otherUserId: String) {
        val userId = auth.currentUser?.uid ?: return
        sendJob?.cancel()
        clearJob?.cancel()
        lastSentLiveText = ""
        try {
            liveTypingRef.child(chatId).child(userId).child(otherUserId).removeValue()
            mirrorLiveText(chatId, userId, otherUserId, payload = null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear live text", e)
        }
    }

    /**
     * Observe live typing text from the other user in a chat.
     * Returns a Flow<LiveTypingPayload?> that emits on every change.
     */
    fun observeLiveTyping(chatId: String, otherUserId: String): Flow<LiveTypingPayload?> = callbackFlow {
        val myUserId = auth.currentUser?.uid
        if (myUserId == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val ref = liveTypingRef.child(chatId).child(otherUserId).child(myUserId)
        var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null
        var rtdbPayload: LiveTypingPayload? = null
        var firestorePayload: LiveTypingPayload? = null

        fun emitMergedPayload() {
            val now = System.currentTimeMillis()
            val freshFirestorePayload = firestorePayload?.takeIf { it.isFresh(now) }
            val freshRtdbPayload = rtdbPayload?.takeIf { it.isFresh(now) }
            trySend(freshFirestorePayload ?: freshRtdbPayload)
        }

        database.goOnline()
        liveTypingRef.keepSynced(true)
        liveTypingRef.child(chatId).keepSynced(true)
        ref.keepSynced(true)


        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    rtdbPayload = null
                    emitMergedPayload()
                    return
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val map = snapshot.value as? Map<String, Any?> ?: run {
                        rtdbPayload = null
                        emitMergedPayload()
                        return
                    }
                    rtdbPayload = LiveTypingPayload.fromMap(map).takeIf {
                        it.expressiveEnabled && it.liveText.isNotEmpty() && it.isFresh()
                    }
                    emitMergedPayload()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse live typing data", e)
                    rtdbPayload = null
                    emitMergedPayload()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Live typing listener CANCELLED (PERMISSION DENIED?)", error.toException())
                rtdbPayload = null
                emitMergedPayload()
            }
        }

        firestoreListener = chatDoc(chatId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Failed to observe Firestore live typing", error)
                return@addSnapshotListener
            }
            firestorePayload = extractFirestorePayload(snapshot?.data, otherUserId, myUserId)
            emitMergedPayload()
        }

        ref.addValueEventListener(listener)

        // Set up onDisconnect handler to auto-clear if user disconnects
        val userId = auth.currentUser?.uid
        if (userId != null) {
            liveTypingRef.child(chatId).child(userId).child(otherUserId).onDisconnect().removeValue()
        }

        awaitClose {
            ref.removeEventListener(listener)
            firestoreListener?.remove()
        }
    }

    /**
     * Clean up when leaving the chat screen.
     */
    fun cleanup() {
        sendJob?.cancel()
        clearJob?.cancel()
        currentChatId?.let { chatId ->
            val otherUserId = currentOtherUserId
            if (otherUserId != null) {
                clearLiveText(chatId, otherUserId)
            }
        }
        currentChatId = null
        currentOtherUserId = null
    }

    fun destroy() {
        cleanup()
        scope.cancel()
    }
}
