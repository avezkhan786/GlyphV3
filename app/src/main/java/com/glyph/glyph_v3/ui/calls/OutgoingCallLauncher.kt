package com.glyph.glyph_v3.ui.calls

import android.content.Context
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
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

        val resolvedDisplayName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = target.userId,
            remoteProfileName = target.displayName
        )

        CallManager.startOutgoingCall(
            context = context,
            receiverId = target.userId,
            receiverName = resolvedDisplayName,
            receiverAvatar = target.avatarUrl,
            callType = callType
        )

        val callData = CallManager.callData.value ?: return OutgoingCallLaunchResult.FAILED

        context.startActivity(
            ActiveCallActivity.createIntent(
                context = context,
                callId = callData.callId,
                callType = callType,
                contactName = resolvedDisplayName,
                contactAvatar = target.avatarUrl
            )
        )
        return OutgoingCallLaunchResult.STARTED
    }
}