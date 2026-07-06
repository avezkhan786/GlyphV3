package com.glyph.glyph_v3.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.glyph.glyph_v3.util.StartupTrace
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.auth.FirebaseAuth

/**
 * Firebase RTDB and Auth initializer.
 *
 * Initializes Firebase Realtime Database with disk persistence and warms
 * the connection early to reduce latency when Activities need presence/messages.
 *
 * This initializer runs early (before UI) because Firebase connection
 * establishment takes 3-15 seconds on cold start (TLS + auth handshake).
 */
class FirebaseInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "FirebaseInitializer"
    }

    override fun create(context: Context) {
        try {
            StartupTrace.logStage("firebase_init_start")

            // ============================================================
            // COLD-START FIX: Enable RTDB disk persistence BEFORE the very
            // first RTDB operation. This lets the SDK persist the auth token
            // and in-flight writes to disk so that:
            //   (a) cold starts don't need a full TLS + auth re-handshake, and
            //   (b) outgoing writes queued while offline survive process restart.
            // ============================================================
            try {
                FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            } catch (e: Exception) {
                // Thrown only if called after the first RTDB operation (e.g. on
                // hot reload in dev builds). Safe to ignore in production.
                Log.w(TAG, "RTDB persistence already enabled (safe to ignore)", e)
            }

            // ============================================================
            // COLD-START FIX: Warm Firebase RTDB connection IMMEDIATELY.
            // Firebase RTDB uses a persistent WebSocket. On cold start (or
            // after long idle), establishing this connection takes 3-15 s
            // (TLS handshake + auth). By calling goOnline() here — before
            // any UI work — the connection starts in parallel.
            // ============================================================
            warmFirebaseConnection()

            StartupTrace.logStage("firebase_init_complete")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // No dependencies - Firebase can initialize independently
        return emptyList()
    }

    /**
     * Warm Firebase RTDB connection and pre-sync presence paths.
     *
     * COLD-START FIX: Proactively warm the Firebase Realtime Database connection
     * and refresh the auth token so that presence, messages, and delivery receipts
     * are ready by the time the first Activity appears.
     */
    private fun warmFirebaseConnection() {
        try {
            val rtdb = FirebaseDatabase.getInstance()

            // 1. Force the WebSocket open immediately
            rtdb.goOnline()

            // 2. Pre-warm presence path cache so first reads don't wait for server
            rtdb.getReference("presence").keepSynced(true)
            rtdb.getReference("walkieTalkieSessions").keepSynced(true)

            // 3. Refresh auth token proactively (runs async, won't block main thread)
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)
                ?.addOnSuccessListener {
                    Log.d(TAG, "Auth token refreshed successfully")
                }
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "Proactive token refresh failed (will retry on demand)", e)
                }

            Log.d(TAG, "Firebase RTDB connection warming complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error warming Firebase connection", e)
        }
    }
}
