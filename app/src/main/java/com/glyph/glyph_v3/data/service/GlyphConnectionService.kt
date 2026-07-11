package com.glyph.glyph_v3.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.models.WalkieTalkieSession
import com.glyph.glyph_v3.data.repo.WalkieTalkieRepository
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.ui.calls.IncomingCallActivity
import com.glyph.glyph_v3.ui.walkietalkie.WalkieTalkieIncomingActivity
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GlyphConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "GlyphConnService"

        private data class IncomingCallInfo(
            val callId: String,
            val callerName: String,
            val callerPhone: String,
            val callerAvatar: String,
            val callType: CallType,
            val sessionKind: String = "call",
            // Walkie-talkie extra fields for zero-latency offer processing
            val wtInitiatorId: String = "",
            val wtOfferBase64: String = "",
            val wtOfferRevision: Int = 0,
            val wtCreatedAt: Long = 0L
        )

        private val pendingIncomingCalls = ConcurrentHashMap<String, IncomingCallInfo>()
        private val activeConnections = ConcurrentHashMap<String, GlyphSelfManagedConnection>()

        fun cacheIncomingCall(
            callId: String,
            callerName: String,
            callerPhone: String,
            callerAvatar: String,
            callType: CallType
        ) {
            pendingIncomingCalls[callId] = IncomingCallInfo(
                callId = callId,
                callerName = callerName,
                callerPhone = callerPhone,
                callerAvatar = callerAvatar,
                callType = callType,
                sessionKind = "call"
            )
        }

        fun cacheIncomingWalkieTalkie(
            sessionId: String,
            initiatorName: String,
            initiatorId: String = "",
            offerBase64: String = "",
            offerRevision: Int = 0,
            createdAt: Long = 0L
        ) {
            pendingIncomingCalls[sessionId] = IncomingCallInfo(
                callId = sessionId,
                callerName = initiatorName,
                callerPhone = "",
                callerAvatar = "",
                callType = CallType.VOICE,
                sessionKind = "walkie_talkie",
                wtInitiatorId = initiatorId,
                wtOfferBase64 = offerBase64,
                wtOfferRevision = offerRevision,
                wtCreatedAt = createdAt
            )
        }

        fun markAnswered(callId: String) {
            activeConnections[callId]?.markAnswered()
        }

        fun markRejected(callId: String) {
            activeConnections.remove(callId)?.markDisconnected(DisconnectCause.REJECTED)
        }

        fun markDisconnected(callId: String, cause: Int) {
            activeConnections.remove(callId)?.markDisconnected(cause)
        }

        fun updateSpeakerState(callId: String, enabled: Boolean) {
            activeConnections[callId]?.setPreferredSpeakerphone(enabled)
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        val extras = request.extras
        val callId = extras?.getString(GlyphTelecomManager.EXTRA_CALL_ID).orEmpty()
        val info = pendingIncomingCalls.remove(callId)
            ?: IncomingCallInfo(
                callId = callId,
                callerName = extras?.getString(GlyphTelecomManager.EXTRA_CALLER_NAME).orEmpty(),
                callerPhone = extras?.getString(GlyphTelecomManager.EXTRA_CALLER_PHONE).orEmpty(),
                callerAvatar = extras?.getString(GlyphTelecomManager.EXTRA_CALLER_AVATAR).orEmpty(),
                callType = runCatching {
                    CallType.valueOf(extras?.getString(GlyphTelecomManager.EXTRA_CALL_TYPE) ?: CallType.VOICE.name)
                }.getOrDefault(CallType.VOICE),
                sessionKind = extras?.getString(GlyphTelecomManager.EXTRA_SESSION_KIND).orEmpty().ifBlank { "call" }
            )

        val connection = GlyphSelfManagedConnection(applicationContext, info)
        activeConnections[info.callId] = connection
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ) {
        val callId = request.extras?.getString(GlyphTelecomManager.EXTRA_CALL_ID).orEmpty()
        pendingIncomingCalls.remove(callId)
        Log.w(TAG, "Telecom rejected incoming connection for call=$callId")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    private class GlyphSelfManagedConnection(
        private val appContext: Context,
        private val info: IncomingCallInfo
    ) : Connection() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var sessionMonitorJob: Job? = null
        private var audioRouteEnforcementJob: Job? = null
        private var answered = false
        private var preferredSpeakerphone = info.sessionKind == "call" && info.callType == CallType.VIDEO

        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            connectionCapabilities = CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
            setAudioModeIsVoip(true)
            setCallerDisplayName(info.callerName, TelecomManager.PRESENTATION_ALLOWED)
            setAddress(
                Uri.fromParts(PhoneAccount.SCHEME_TEL, info.callerPhone.ifBlank { info.callId }, null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            setInitializing()
            setRinging()
            if (info.sessionKind == "walkie_talkie") {
                monitorWalkieTalkieSession()
            }
        }

        override fun onShowIncomingCallUi() {
            CoroutineScope(Dispatchers.Main).launch {
                if (info.sessionKind == "walkie_talkie") {
                    WalkieTalkieIncomingNotificationHelper.show(
                        context = appContext,
                        sessionId = info.callId,
                        initiatorId = info.wtInitiatorId,
                        initiatorName = info.callerName,
                        createdAt = info.wtCreatedAt,
                        offerBase64 = info.wtOfferBase64,
                        offerRevision = info.wtOfferRevision
                    )
                } else {
                    CallNotificationHelper.showIncomingCallNotification(
                        context = appContext,
                        callId = info.callId,
                        callerName = info.callerName,
                        callerPhone = info.callerPhone,
                        callerAvatar = info.callerAvatar,
                        callType = info.callType
                    )
                }
            }
        }

        override fun onAnswer() {
            answerCall()
        }

        override fun onAnswer(videoState: Int) {
            answerCall()
        }

        override fun onReject() {
            if (info.sessionKind == "walkie_talkie") {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { WalkieTalkieRepository().rejectSession(info.callId) }
                }
            } else {
                CallManager.declineCall(appContext, info.callId)
            }
            markDisconnected(DisconnectCause.REJECTED)
        }

        override fun onDisconnect() {
            if (info.sessionKind == "walkie_talkie") {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { WalkieTalkieRepository().cancelSession(info.callId) }
                }
            } else {
                CallManager.endCall(appContext)
            }
            markDisconnected(DisconnectCause.LOCAL)
        }

        fun markAnswered() {
            answered = true
            setActive()
            scheduleAudioRouteEnforcement()
        }

        fun setPreferredSpeakerphone(enabled: Boolean) {
            preferredSpeakerphone = enabled
            if (answered) {
                scheduleAudioRouteEnforcement()
            }
        }

        override fun onCallAudioStateChanged(state: CallAudioState?) {
            super.onCallAudioStateChanged(state)
            if (answered) {
                applyPreferredAudioRoute()
            }
        }

        fun markDisconnected(causeCode: Int) {
            audioRouteEnforcementJob?.cancel()
            sessionMonitorJob?.cancel()
            WalkieTalkieIncomingNotificationHelper.cancel(appContext)
            setDisconnected(DisconnectCause(causeCode))
            destroy()
            scope.cancel()
        }

        private fun scheduleAudioRouteEnforcement() {
            applyPreferredAudioRoute()
            audioRouteEnforcementJob?.cancel()
            audioRouteEnforcementJob = scope.launch {
                repeat(4) {
                    delay(150)
                    applyPreferredAudioRoute()
                }
            }
        }

        private fun applyPreferredAudioRoute() {
            if (info.sessionKind != "call") return

            val route = if (preferredSpeakerphone) {
                CallAudioState.ROUTE_SPEAKER
            } else {
                CallAudioState.ROUTE_WIRED_OR_EARPIECE
            }

            runCatching {
                setAudioRoute(route)
            }.onFailure { error ->
                Log.w(TAG, "Failed to apply Telecom audio route for call=${info.callId}", error)
            }
        }

        private fun answerCall() {
            CallLockScreenHelper.pulseScreenWake(appContext, "$TAG:TelecomAnswerWake")
            val intent = if (info.sessionKind == "walkie_talkie") {
                WalkieTalkieIncomingActivity.createIntent(
                    context = appContext,
                    sessionId = info.callId,
                    initiatorId = info.wtInitiatorId,
                    initiatorName = info.callerName,
                    createdAt = info.wtCreatedAt,
                    offerBase64 = info.wtOfferBase64,
                    offerRevision = info.wtOfferRevision
                ).apply {
                    putExtra(WalkieTalkieIncomingNotificationHelper.EXTRA_WT_AUTO_ACCEPT, true)
                }
            } else {
                IncomingCallActivity.createIntent(
                    context = appContext,
                    callId = info.callId,
                    callerName = info.callerName,
                    callerPhone = info.callerPhone,
                    callerAvatar = info.callerAvatar,
                    callType = info.callType
                ).apply {
                    putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
                }
            }
            appContext.startActivity(intent)
        }

        private fun monitorWalkieTalkieSession() {
            sessionMonitorJob?.cancel()
            sessionMonitorJob = scope.launch {
                WalkieTalkieRepository().observeSession(info.callId).collectLatest { session ->
                    val status = session?.status ?: WalkieTalkieSession.STATUS_CANCELLED
                    if (status == WalkieTalkieSession.STATUS_CANCELLED ||
                        status == WalkieTalkieSession.STATUS_REJECTED ||
                        status == WalkieTalkieSession.STATUS_ENDED
                    ) {
                        val cause = when (status) {
                            WalkieTalkieSession.STATUS_REJECTED -> DisconnectCause.REJECTED
                            WalkieTalkieSession.STATUS_CANCELLED -> DisconnectCause.CANCELED
                            else -> DisconnectCause.REMOTE
                        }
                        markDisconnected(cause)
                    }
                }
            }
        }
    }
}