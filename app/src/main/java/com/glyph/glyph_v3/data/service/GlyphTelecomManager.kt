package com.glyph.glyph_v3.data.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.glyph.glyph_v3.data.models.CallType

object GlyphTelecomManager {

    private const val TAG = "GlyphTelecom"
    const val EXTRA_CALL_ID = "glyph_call_id"
    const val EXTRA_CALLER_NAME = "glyph_caller_name"
    const val EXTRA_CALLER_PHONE = "glyph_caller_phone"
    const val EXTRA_CALLER_AVATAR = "glyph_caller_avatar"
    const val EXTRA_CALL_TYPE = "glyph_call_type"
    const val EXTRA_SESSION_KIND = "glyph_session_kind"

    private const val SESSION_KIND_CALL = "call"
    private const val SESSION_KIND_WALKIE_TALKIE = "walkie_talkie"

    private const val PHONE_ACCOUNT_ID = "glyph_self_managed_account"
    @Volatile
    private var phoneAccountRegistrationAttempted = false

    private fun telecomManager(context: Context): TelecomManager? {
        return context.getSystemService(TelecomManager::class.java)
    }

    fun phoneAccountHandle(context: Context): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, GlyphConnectionService::class.java),
            PHONE_ACCOUNT_ID
        )
    }

    fun ensurePhoneAccountRegistered(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (phoneAccountRegistrationAttempted) {
            return
        }

        val telecomManager = telecomManager(context) ?: return
        val handle = phoneAccountHandle(context)

        val phoneAccount = PhoneAccount.builder(handle, "Glyph Calls")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()

        runCatching {
            telecomManager.registerPhoneAccount(phoneAccount)
            phoneAccountRegistrationAttempted = true
        }.onFailure { error ->
            Log.w(TAG, "Failed to register self-managed phone account", error)
        }
    }

    fun reportIncomingCall(
        context: Context,
        callId: String,
        callerName: String,
        callerPhone: String,
        callerAvatar: String,
        callType: CallType
    ): Boolean {
        val telecomManager = telecomManager(context) ?: return false
        ensurePhoneAccountRegistered(context)

        val handle = phoneAccountHandle(context)
        GlyphConnectionService.cacheIncomingCall(
            callId = callId,
            callerName = callerName,
            callerPhone = callerPhone,
            callerAvatar = callerAvatar,
            callType = callType
        )

        val address = Uri.fromParts(
            PhoneAccount.SCHEME_TEL,
            callerPhone.ifBlank { callId.take(12) },
            null
        )

        val extras = Bundle().apply {
            putString(EXTRA_CALL_ID, callId)
            putString(EXTRA_CALLER_NAME, callerName)
            putString(EXTRA_CALLER_PHONE, callerPhone)
            putString(EXTRA_CALLER_AVATAR, callerAvatar)
            putString(EXTRA_CALL_TYPE, callType.name)
            putString(EXTRA_SESSION_KIND, SESSION_KIND_CALL)
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
            putInt(
                TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                if (callType == CallType.VIDEO) {
                    VideoProfile.STATE_BIDIRECTIONAL
                } else {
                    VideoProfile.STATE_AUDIO_ONLY
                }
            )
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !telecomManager.isIncomingCallPermitted(handle)) {
                Log.w(TAG, "Incoming call not permitted by Telecom for handle=$handle")
                false
            } else {
                telecomManager.addNewIncomingCall(handle, extras)
                true
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to report incoming call to Telecom: $callId", error)
            false
        }
    }

    fun reportIncomingWalkieTalkie(
        context: Context,
        sessionId: String,
        initiatorName: String,
        initiatorId: String = "",
        offerBase64: String = "",
        offerRevision: Int = 0,
        createdAt: Long = 0L
    ): Boolean {
        val telecomManager = telecomManager(context) ?: return false
        ensurePhoneAccountRegistered(context)

        val handle = phoneAccountHandle(context)
        GlyphConnectionService.cacheIncomingWalkieTalkie(
            sessionId = sessionId,
            initiatorName = initiatorName,
            initiatorId = initiatorId,
            offerBase64 = offerBase64,
            offerRevision = offerRevision,
            createdAt = createdAt
        )

        val address = Uri.fromParts(
            PhoneAccount.SCHEME_TEL,
            sessionId.take(12),
            null
        )

        val extras = Bundle().apply {
            putString(EXTRA_CALL_ID, sessionId)
            putString(EXTRA_CALLER_NAME, initiatorName)
            putString(EXTRA_CALLER_PHONE, "")
            putString(EXTRA_CALLER_AVATAR, "")
            putString(EXTRA_CALL_TYPE, CallType.VOICE.name)
            putString(EXTRA_SESSION_KIND, SESSION_KIND_WALKIE_TALKIE)
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !telecomManager.isIncomingCallPermitted(handle)) {
                Log.w(TAG, "Incoming WT not permitted by Telecom for handle=$handle")
                false
            } else {
                telecomManager.addNewIncomingCall(handle, extras)
                true
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to report incoming WT to Telecom: $sessionId", error)
            false
        }
    }

    fun markCallAnswered(callId: String) {
        GlyphConnectionService.markAnswered(callId)
    }

    fun markCallRejected(callId: String) {
        GlyphConnectionService.markRejected(callId)
    }

    fun markCallDisconnected(callId: String, cause: Int = DisconnectCause.LOCAL) {
        GlyphConnectionService.markDisconnected(callId, cause)
    }

    fun updateCallSpeakerState(callId: String, enabled: Boolean) {
        GlyphConnectionService.updateSpeakerState(callId, enabled)
    }
}