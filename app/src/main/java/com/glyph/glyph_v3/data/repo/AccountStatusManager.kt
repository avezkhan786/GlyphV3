package com.glyph.glyph_v3.data.repo

/**
 * Process-wide singleton that tracks whether the current user's account is
 * restricted (suspended / banned / blocked). Updated by MainActivity's
 * Firestore real-time listener, read by any component that needs to gate
 * sensitive actions (message sends, calls, media uploads).
 */
object AccountStatusManager {

    @Volatile
    var isRestricted: Boolean = false
        private set

    @Volatile
    var status: String = "active"
        private set

    fun setRestricted(s: String) {
        status = s
        isRestricted = s != "active"
    }

    fun clear() {
        status = "active"
        isRestricted = false
    }
}
