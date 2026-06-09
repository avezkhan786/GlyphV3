package com.glyph.glyph_v3.ui.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.util.StartupTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-warms all real-time transport layers (RTDB WebSocket, Firestore TCP, auth token) for a
 * specific chat BEFORE [ChatActivity] is started.  The 300–500 ms activity-launch animation
 * window is exploited so that by the time the user can interact with the input field the
 * RTDB WebSocket handshake is already complete and Firebase listeners attach near-instantly.
 *
 * ### What is warmed
 * 1. `FirebaseDatabase.goOnline()` — forces the RTDB WebSocket TLS handshake to start now
 *    instead of lazily on the first listener attach inside ChatActivity.
 * 2. `keepSynced(true)` on the chat-specific typing and presence RTDB paths — Firebase SDK
 *    will eagerly download these paths so the first `ValueEventListener` receives cached data
 *    immediately rather than waiting for a server round-trip.
 * 3. `auth.currentUser.getIdToken(false)` — ensures a valid token is cached; avoids a
 *    blocking token-refresh inside the send-message path.
 * 4. Firestore messages sub-collection — a cache-only `get()` touch primes the Firestore
 *    channel so the real `addSnapshotListener` in [ChatActivity.loadMessages] hits an already-
 *    open TCP connection.
 * 5. `repository.primeRealtimeTransportForForeground()` — repository-level transport prime that
 *    also refreshes the pending_messages keepSynced flag.
 *
 * ### Safety
 * - All work is fire-and-forget on [Dispatchers.IO]; nothing blocks the calling thread.
 * - Per-chat debounce (30 s) prevents duplicate work when the user quickly re-opens a chat.
 * - All exceptions are caught; a warm-up failure never affects the normal open path.
 */
object ChatConnectionPrewarmer {

    private const val TAG = "ChatPrewarmer"
    private const val PREWARM_DEBOUNCE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tracks the last time each chatId was pre-warmed (monotonic elapsed ms). */
    private val lastPrewarmAtMs = ConcurrentHashMap<String, Long>()

    /**
     * Begin asynchronous pre-warming for [chatId].  Safe to call from the Main thread
     * immediately before [android.content.Context.startActivity] is invoked.
     *
     * @param repository The shared [RealtimeMessageRepository] instance from [GlyphApplication].
     * @param chatId     The chat that is about to be opened.
     * @param otherUserId The UID of the conversation partner (used to prime per-user RTDB paths).
     */
    fun prewarmForChatOpen(
        repository: RealtimeMessageRepository,
        chatId: String,
        otherUserId: String
    ) {
        val now = System.currentTimeMillis()
        val last = lastPrewarmAtMs[chatId] ?: 0L
        if (now - last < PREWARM_DEBOUNCE_MS) {
            ChatOpenTrace.event(chatId, "transport_prewarm_debounced", "age=${now - last}ms")
            return
        }
        lastPrewarmAtMs[chatId] = now

        scope.launch {
            try {
                StartupTrace.logStage("chat_prewarmer_start", "chatId=$chatId other=$otherUserId")
                ChatOpenTrace.event(chatId, "transport_prewarm_start", "other=${otherUserId.take(8)}")

                val rtdb = FirebaseDatabase.getInstance()
                val auth = FirebaseAuth.getInstance()
                val myUid = auth.currentUser?.uid

                // ── 1. Wake the RTDB WebSocket ────────────────────────────────────────────
                // goOnline() is idempotent: if the WebSocket is already open this is a no-op.
                // On cold start or after long idle it begins the TLS + Firebase auth handshake
                // NOW, giving the handshake the full activity-launch transition window (~300 ms)
                // to complete before the user touches the input.
                rtdb.goOnline()

                // ── 2. Mark chat-specific RTDB paths for eager sync ───────────────────────
                // keepSynced(true) tells the SDK to download and cache these paths immediately.
                // When ChatActivity attaches its ValueEventListeners they receive the cached
                // value synchronously (no server round-trip required for first emission).
                if (myUid != null) {
                    // Typing indicator: other user's typing state toward me
                    rtdb.reference
                        .child("chats").child(chatId)
                        .child("typing").child(otherUserId).child(myUid)
                        .keepSynced(true)
                }
                // Presence path for the conversation partner
                rtdb.reference.child("presence").child(otherUserId).keepSynced(true)

                // ── 3. Refresh the auth token (background, no-await) ──────────────────────
                // A stale token causes a blocking round-trip inside sendMessage(); refreshing
                // it here (during the launch animation) ensures it is ready before the user
                // types their first character.
                auth.currentUser?.getIdToken(false)?.addOnFailureListener { e ->
                    Log.w(TAG, "Pre-warm token refresh failed (non-fatal)", e)
                }

                // ── 4. Touch Firestore to warm the TCP connection ─────────────────────────
                // A cache-only get() costs nothing if the data is already local.  If it misses
                // the cache the failure is swallowed here — the real addSnapshotListener from
                // ChatActivity will still succeed, but its TCP connection is now already open.
                FirebaseFirestore.getInstance()
                    .collection("chats").document(chatId)
                    .collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .limitToLast(1)
                    .get(Source.CACHE)
                    .addOnFailureListener { /* cache miss is expected — TCP is still warmed */ }

                // ── 5. Repository-level transport prime ───────────────────────────────────
                // Ensures pending_messages keepSynced is set and queues a background auth warm.
                repository.primeRealtimeTransportForForeground("chat_open_prewarmer")

                StartupTrace.logStage("chat_prewarmer_done", "chatId=$chatId")
                ChatOpenTrace.event(chatId, "transport_prewarm_done")
            } catch (e: Exception) {
                ChatOpenTrace.event(chatId, "transport_prewarm_fail", "error=${e::class.java.simpleName}")
                Log.w(TAG, "Pre-warm failed for chatId=$chatId (non-fatal)", e)
            }
        }
    }

    /**
     * Pre-warm multiple chats at once (called from the chat list to prime likely next-opens).
     * Each chat is debounced independently.
     */
    fun prewarmChats(
        repository: RealtimeMessageRepository,
        chats: List<Pair<String, String>> // (chatId, otherUserId)
    ) {
        chats.forEach { (chatId, otherUserId) ->
            prewarmForChatOpen(repository, chatId, otherUserId)
        }
    }
}
