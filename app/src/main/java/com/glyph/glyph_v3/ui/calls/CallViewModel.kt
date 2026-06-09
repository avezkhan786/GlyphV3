package com.glyph.glyph_v3.ui.calls

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.CallData
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.webrtc.CallManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

class CallViewModel(application: Application) : AndroidViewModel(application) {

    val callState: StateFlow<CallState?> = CallManager.callState
    val callData: StateFlow<CallData?> = CallManager.callData
    val callDurationSeconds: StateFlow<Int> = CallManager.callDurationSeconds
    val isMicMuted: StateFlow<Boolean> = CallManager.isMicMuted
    val isSpeakerOn: StateFlow<Boolean> = CallManager.isSpeakerOn
    val isVideoEnabled: StateFlow<Boolean> = CallManager.isVideoEnabled

    val localVideoTrack: StateFlow<VideoTrack?> =
        CallManager.rtcClient?.localVideoTrack ?: MutableStateFlow(null)

    val remoteVideoTrack: StateFlow<VideoTrack?> =
        CallManager.rtcClient?.remoteVideoTrack ?: MutableStateFlow(null)

    val formattedDuration: StateFlow<String> = callDurationSeconds.map { seconds ->
        val mins = seconds / 60
        val secs = seconds % 60
        "%d:%02d".format(mins, secs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "0:00")

    fun startOutgoingCall(
        receiverId: String,
        receiverName: String,
        receiverAvatar: String,
        callType: CallType
    ) {
        CallManager.startOutgoingCall(
            getApplication(),
            receiverId, receiverName, receiverAvatar, callType
        )
    }

    fun acceptCall(callId: String, callType: CallType) {
        CallManager.acceptIncomingCall(getApplication(), callId, callType)
    }

    fun declineCall(callId: String) {
        CallManager.declineCall(getApplication(), callId)
    }

    fun endCall() {
        CallManager.endCall(getApplication())
    }

    fun toggleMicrophone() = CallManager.toggleMicrophone()
    fun toggleSpeaker() = CallManager.toggleSpeaker()
    fun toggleVideo() = CallManager.toggleVideo()
    fun switchCamera() = CallManager.switchCamera()
}
