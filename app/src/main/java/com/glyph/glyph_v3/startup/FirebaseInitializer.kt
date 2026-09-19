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
 * Minimal pre-first-frame work only: enables RTDB disk persistence, which must
 * happen before the very first RTDB operation.
 *
 * Connection warm-up (goOnline, keepSynced, token refresh) was moved out of the
 * cold-start critical path — it competed with first-frame layout for CPU and is
 * not needed to render the first frame. It now runs after the chat list's first
 * frame via GlyphApplication.warmFirebaseConnections() (called from
 * MainActivity.deferredHeavyStartup). Presence transport is unaffected: RTDB
 * already connects pre-first-frame through PresenceManager (auth listener /
 * onResume priming).
 */
class FirebaseInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "FirebaseInitializer"
    }

    override fun create(context: Context) {
        try {
            StartupTrace.logStage("firebase_init_start")
            Log.d(TAG, "=== FirebaseInitializer.create() START ===")

            // ============================================================
            // Enable RTDB disk persistence BEFORE the very first RTDB
            // operation. This lets the SDK persist the auth token and
            // in-flight writes to disk so that:
            //   (a) cold starts don't need a full TLS + auth re-handshake, and
            //   (b) outgoing writes queued while offline survive process restart.
            //
            // DEFERRED (was here previously): goOnline(), keepSynced(presence),
            // keepSynced(walkieTalkieSessions) and the force token refresh.
            // See GlyphApplication.warmFirebaseConnections().
            // ============================================================
            try {
                FirebaseDatabase.getInstance().setPersistenceEnabled(true)
                Log.d(TAG, "RTDB persistence enabled")
            } catch (e: Exception) {
                // Thrown only if called after the first RTDB operation (e.g. on
                // hot reload in dev builds). Safe to ignore in production.
                Log.w(TAG, "RTDB persistence already enabled (safe to ignore)", e)
            }

            Log.d(TAG, "=== FirebaseInitializer.create() COMPLETE ===")
            StartupTrace.logStage("firebase_init_complete")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // No dependencies - Firebase can initialize independently
        return emptyList()
    }
}
