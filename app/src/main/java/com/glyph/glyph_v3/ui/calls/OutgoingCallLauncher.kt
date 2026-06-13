package com.glyph.glyph_v3.ui.calls

import android.content.Context
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.webrtc.CallManager

enum class OutgoingCallLaunchResult {
    STARTED,
    ANOTHER_CALL_ACTIVE,
    FAILED
}

object OutgoingCallLauncher {
    fun launch(
        context: Context,
        target: FavoriteCallTarget,
        callType: CallType
    ): OutgoingCallLaunchResult {
        val activeState = CallManager.callState.value
        if (activeState != null && activeState != CallState.ENDED) {
            return OutgoingCallLaunchResult.ANOTHER_CALL_ACTIVE
        }

        // Store the raw profile/display name (target.displayName) — do NOT
        // pre-resolve here. The chat list stores raw otherUsername and resolves
        // at display time via ContactDisplayNameResolver.getDisplayName().
        // CallsViewModel.toFlatHistoryItem() does the same resolution at render
        // time, using the same 2-param overload as ChatListAdapter.
        CallManager.startOutgoingCall(
            context = context,
            receiverId = target.userId,
            receiverName = target.displayName,
            receiverAvatar = target.avatarUrl,
            callType = callType
        )

        val callData = CallManager.callData.value ?: return OutgoingCallLaunchResult.FAILED

        // ActiveCallActivity also resolves at display time via
        // ContactDisplayNameResolver.getDisplayName() with phone from callData.
        context.startActivity(
            ActiveCallActivity.createIntent(
                context = context,
                callId = callData.callId,
                callType = callType,
                contactName = target.displayName,
                contactAvatar = target.avatarUrl
            )
        )
        return OutgoingCallLaunchResult.STARTED
    }
}