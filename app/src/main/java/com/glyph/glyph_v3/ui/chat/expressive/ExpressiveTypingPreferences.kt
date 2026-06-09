package com.glyph.glyph_v3.ui.chat.expressive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the Live Expressive Typing toggle state.
 *
 * Privacy rule: Live typing preview works ONLY when BOTH users have it enabled.
 * If either user disables it, the feature is off for both.
 *
 * Storage:
 *   - Firestore: users/{uid}/settings/expressiveTyping.enabled (source of truth)
 *   - SharedPreferences: local cache for fast reads
 */
class ExpressiveTypingPreferences private constructor(context: Context) {

    companion object {
        private const val TAG = "ExpressivePrefs"
        private const val PREFS_NAME = "expressive_typing_prefs"
        private const val KEY_ENABLED = "expressive_typing_enabled"
        private const val FIRESTORE_FIELD = "expressiveTypingEnabled"

        @Volatile
        private var instance: ExpressiveTypingPreferences? = null

        fun getInstance(context: Context): ExpressiveTypingPreferences {
            return instance ?: synchronized(this) {
                instance ?: ExpressiveTypingPreferences(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /**
     * Toggle expressive typing on/off. Updates both local cache and Firestore.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled

        // Sync to Firestore
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        firestore.collection("users").document(uid)
            .update(FIRESTORE_FIELD, enabled)
            .addOnSuccessListener { }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync to Firestore", e)
                // Firestore update failed — create field if document exists but field doesn't
                firestore.collection("users").document(uid)
                    .set(mapOf(FIRESTORE_FIELD to enabled), com.google.firebase.firestore.SetOptions.merge())
            }
    }

    /**
     * Observe whether the OTHER user has expressive typing enabled.
     * Returns a Flow<Boolean> from Firestore real-time listener.
     */
    fun observeOtherUserEnabled(otherUserId: String): Flow<Boolean> = callbackFlow {
        if (otherUserId.isBlank()) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val docRef = firestore.collection("users").document(otherUserId)

        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error observing other user's expressive setting", error)
                trySend(false)
                return@addSnapshotListener
            }
            val enabled = snapshot?.getBoolean(FIRESTORE_FIELD) ?: true // Default to true
            trySend(enabled)
        }

        awaitClose { listener.remove() }
    }

    /**
     * Check if both the current user and the other user have expressive typing enabled.
     * Both must be true for the feature to be active.
     */
    fun observeMutualEnabled(otherUserId: String): Flow<Boolean> = callbackFlow {
        if (otherUserId.isBlank()) {
            trySend(false)
            close()
            return@callbackFlow
        }

        var selfEnabled = _isEnabled.value
        var otherEnabled = true
        var selfListener: ListenerRegistration? = null
        var otherListener: ListenerRegistration? = null

        fun emit() {
            val mutual = selfEnabled && otherEnabled
            trySend(mutual)
        }

        // Listen to self changes
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() }
        if (uid != null) {
            selfListener = firestore.collection("users").document(uid)
                .addSnapshotListener { snapshot, _ ->
                    selfEnabled = snapshot?.getBoolean(FIRESTORE_FIELD) ?: true
                    _isEnabled.value = selfEnabled
                    prefs.edit().putBoolean(KEY_ENABLED, selfEnabled).apply()
                    emit()
                }
        }

        // Listen to other user changes
        otherListener = firestore.collection("users").document(otherUserId)
            .addSnapshotListener { snapshot, _ ->
                otherEnabled = snapshot?.getBoolean(FIRESTORE_FIELD) ?: true
                emit()
            }

        emit()

        awaitClose {
            selfListener?.remove()
            otherListener?.remove()
        }
    }

    /**
     * Load initial state from Firestore to sync local cache.
     */
    fun syncFromFirestore() {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val enabled = doc.getBoolean(FIRESTORE_FIELD) ?: true
                prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
                _isEnabled.value = enabled
            }
    }
}
