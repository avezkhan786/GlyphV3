package com.glyph.glyph_v3.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Provides group-level online presence by filtering the global PresenceManager
 * presence data for the given set of member UIDs.
 *
 * Reuses PresenceManager.observeMultipleUsersPresence so no extra RTDB
 * connections are opened. When the chat list already observes the same users
 * (for 1:1 presence), the underlying Flow is shared automatically via the
 * callbackFlow / collect lifecycle within each coroutine scope.
 *
 * Usage:
 *   val onlineUids: Flow<List<String>> =
 *       GroupPresenceRepository.observeGroupOnlineUsers(members, selfUid)
 */
object GroupPresenceRepository {

    /**
     * Returns a [Flow] that emits the list of UIDs currently online for
     * a group, excluding [selfUid].
     *
     * The flow stays active as long as the collector's coroutine is alive.
     * Listeners are cleaned up automatically on cancellation.
     *
     * @param memberIds All UIDs that belong to the group (from participantsJson).
     * @param selfUid   The current authenticated user's UID – excluded from results.
     */
    fun observeGroupOnlineUsers(memberIds: List<String>, selfUid: String): Flow<List<String>> {
        val targets = memberIds
            .filter { it.isNotBlank() && it != selfUid }
            .distinct()

        if (targets.isEmpty()) return flowOf(emptyList())

        return PresenceManager.observeMultipleUsersPresence(targets)
            .map { presenceMap ->
                targets.filter { uid -> presenceMap[uid]?.isOnline == true }
            }
            .distinctUntilChanged()
    }
}
