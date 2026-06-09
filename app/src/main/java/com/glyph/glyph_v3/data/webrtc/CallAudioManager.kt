package com.glyph.glyph_v3.data.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages audio routing, echo cancellation, and speaker/earpiece switching for calls.
 */
class CallAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var previousMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var previousCommunicationDeviceId: Int? = null
    private var previousMicrophoneMute: Boolean? = null
    private var previousBluetoothScoOn: Boolean? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isStarted = false
    private var _isSpeakerOn = false
    private var isAudioDeviceCallbackRegistered = false

    val isSpeakerOn: Boolean get() = _isSpeakerOn

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_GAIN && isStarted) {
            reapplyPreferredRoute()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (isStarted) {
                reapplyPreferredRoute()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (isStarted) {
                reapplyPreferredRoute()
            }
        }
    }

    fun start(speakerDefault: Boolean) {
        if (isStarted) {
            stop()
        }

        previousMode = audioManager.mode
        previousMicrophoneMute = audioManager.isMicrophoneMute
        previousBluetoothScoOn = legacyBluetoothScoState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDeviceId = audioManager.communicationDevice?.id
        } else {
            @Suppress("DEPRECATION")
            previousSpeakerphone = audioManager.isSpeakerphoneOn
        }

        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        disableBluetoothSco()
        registerAudioDeviceCallback()
        setSpeaker(speakerDefault)
        isStarted = true
    }

    fun stop() {
        try {
            unregisterAudioDeviceCallback()
            resetCommunicationAudioState()
        } finally {
            abandonAudioFocus()
            clearCachedState()
            isStarted = false
        }
    }

    fun setSpeaker(enabled: Boolean) {
        _isSpeakerOn = enabled
        reapplyPreferredRoute()
    }

    fun reapplyCurrentRoute() {
        reapplyPreferredRoute()
    }

    private fun reapplyPreferredRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeToCommunicationDevice(
                if (_isSpeakerOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = _isSpeakerOn
        }
    }

    fun toggleSpeaker() {
        setSpeaker(!_isSpeakerOn)
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun registerAudioDeviceCallback() {
        if (isAudioDeviceCallbackRegistered) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        isAudioDeviceCallbackRegistered = true
    }

    private fun unregisterAudioDeviceCallback() {
        if (!isAudioDeviceCallbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        isAudioDeviceCallbackRegistered = false
    }

    private fun routeToCommunicationDevice(targetType: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        val targetDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == targetType
        } ?: return false

        if (audioManager.communicationDevice?.id == targetDevice.id) {
            return true
        }

        audioManager.clearCommunicationDevice()
        return audioManager.setCommunicationDevice(targetDevice)
    }

    private fun resetCommunicationAudioState() {
        disableBluetoothSco()
        audioManager.isMicrophoneMute = previousMicrophoneMute ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prevId = previousCommunicationDeviceId
            if (prevId != null) {
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == prevId }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            previousSpeakerphone?.let {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = it
            }
        }
        val targetMode = previousMode
            ?.takeUnless {
                it == AudioManager.MODE_IN_COMMUNICATION ||
                    it == AudioManager.MODE_IN_CALL
            }
            ?: AudioManager.MODE_NORMAL
        audioManager.mode = targetMode
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        _isSpeakerOn = false
    }

    private fun clearCachedState() {
        previousMode = null
        previousSpeakerphone = null
        previousCommunicationDeviceId = null
        previousMicrophoneMute = null
        previousBluetoothScoOn = null
        isAudioDeviceCallbackRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun legacyBluetoothScoState(): Boolean = audioManager.isBluetoothScoOn

    @Suppress("DEPRECATION")
    private fun disableBluetoothSco() {
        runCatching {
            if (audioManager.isBluetoothScoOn || previousBluetoothScoOn == true) {
                audioManager.stopBluetoothSco()
            }
            audioManager.isBluetoothScoOn = false
        }.onFailure { error ->
            Log.w(TAG, "Failed to disable Bluetooth SCO during call teardown", error)
        }
    }
}
