package com.glyph.glyph_v3.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.ui.calls.ActiveCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles Accept/Decline actions from the incoming call notification.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // ACTION_CLEAR_MISSED_CALLS doesn't carry a call_id — handle it before the guard
        if (action == CallNotificationHelper.ACTION_CLEAR_MISSED_CALLS) {
            val callerName = intent.getStringExtra("caller_name") ?: return
            val baseKey = callerName.lowercase().trim()
            val prefs = context.getSharedPreferences("missed_call_counts", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("${baseKey}_voice")
                .remove("${baseKey}_video")
                .apply()
            return
        }

        // ACTION_DECLINE_GROUP_CALL uses group_call_id, not call_id — handle before the guard
        if (action == CallNotificationHelper.ACTION_DECLINE_GROUP_CALL) {
            CallNotificationHelper.cancelGroupCallNotification(context)
            val groupCallId = intent.getStringExtra("group_call_id") ?: return
            val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
            CoroutineScope(Dispatchers.Main).launch {
                com.glyph.glyph_v3.data.repo.GroupCallSignalingRepository
                    .updateParticipantStatus(groupCallId, myUid, "declined")
            }
            return
        }

        val callId = intent.getStringExtra("call_id") ?: return

        when (action) {
            CallNotificationHelper.ACTION_ACCEPT_CALL -> {
                val callType = try {
                    CallType.valueOf(intent.getStringExtra("call_type") ?: "VOICE")
                } catch (_: Exception) { CallType.VOICE }

                val callerName   = intent.getStringExtra("caller_name") ?: ""
                val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
                val callIntent = ActiveCallActivity.createIntent(
                    context = context,
                    callId = callId,
                    callType = callType,
                    contactName = callerName,
                    contactAvatar = callerAvatar,
                    autoAccept = true
                )
                context.startActivity(callIntent)
            }

            CallNotificationHelper.ACTION_DECLINE_CALL -> {
                CallNotificationHelper.cancelIncomingNotification(context)
                CallManager.declineCall(context, callId)
            }
        }
    }
}
