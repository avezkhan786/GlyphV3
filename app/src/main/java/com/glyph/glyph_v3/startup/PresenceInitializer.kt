package com.glyph.glyph_v3.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.glyph.glyph_v3.util.StartupTrace
import com.google.firebase.auth.FirebaseAuth
import com.glyph.glyph_v3.data.repo.PresenceManager

/**
 * Presence manager initializer.
 *
 * Initializes Firebase Presence tracking and sets up auth state listeners
 * for managing user online/offline status.
 *
 * Dependencies: FirebaseInitializer (must run after Firebase is configured)
 */
class PresenceInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "PresenceInitializer"
    }

    override fun create(context: Context) {
        try {
            StartupTrace.logStage("presence_init_start")
            Log.d(TAG, "=== PresenceInitializer.create() START ===")

            // Initialize Presence manager context
            PresenceManager.initContext(context)

            // Set up auth state listener for presence tracking
            initializePresence(context)

            Log.d(TAG, "=== PresenceInitializer.create() COMPLETE ===")
            StartupTrace.logStage("presence_init_complete")
        } catch (e: Exception) {
            Log.e(TAG, "Presence initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // Presence depends on Firebase being initialized first
        return listOf(FirebaseInitializer::class.java)
    }

    /**
     * Initialize presence tracking with auth state listener.
     */
    private fun initializePresence(context: Context) {
        try {
            // Only initialize if user is logged in
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            Log.d(TAG, "initializePresence - currentUser: ${currentUser?.uid ?: "NULL"}")
            if (currentUser != null) {
                persistLastKnownAuthUid(context, currentUser.uid)
                PresenceManager.initialize()
            }

            // Listen for auth state changes
            auth.addAuthStateListener { authState ->
                try {
                    if (authState.currentUser != null) {
                        StartupTrace.logStage("auth_state_ready", "uid=${authState.currentUser?.uid}")
                        persistLastKnownAuthUid(context, authState.currentUser?.uid)
                        PresenceManager.initialize()
                    } else {
                        StartupTrace.logStage("auth_state_cleared")
                        persistLastKnownAuthUid(context, null)
                        PresenceManager.cleanup()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in auth state listener", e)
                }
            }

            Log.d(TAG, "initializePresence() completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing presence", e)
        }
    }

    private fun persistLastKnownAuthUid(context: Context, userId: String?) {
        val prefs = context.getSharedPreferences("auth_session_state", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (userId.isNullOrBlank()) {
            editor.remove("last_auth_uid")
        } else {
            editor.putString("last_auth_uid", userId)
        }
        editor.apply()
    }
}
