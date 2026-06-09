package com.glyph.glyph_v3.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.data.repo.WalkieTalkieRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles Accept / Decline actions from the walkie-talkie incoming notification.
 * Mirrors [CallActionReceiver] for voice calls.
 */
class WalkieTalkieActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WTActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getStringExtra(
            WalkieTalkieIncomingNotificationHelper.EXTRA_WT_SESSION_ID
        ) ?: return


        when (action) {
            WalkieTalkieIncomingNotificationHelper.ACTION_DECLINE_WT -> {
                // Cancel the notification
                WalkieTalkieIncomingNotificationHelper.cancel(context)

                // End the RTDB session so the initiator knows
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        WalkieTalkieRepository().rejectSession(sessionId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to end WT session on decline", e)
                    }
                }
            }
        }
    }
}
