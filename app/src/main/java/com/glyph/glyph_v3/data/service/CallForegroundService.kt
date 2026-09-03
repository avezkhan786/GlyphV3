package com.glyph.glyph_v3.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.ui.calls.ActiveCallActivity
import kotlinx.coroutines.flow.first

/**
 * Foreground service that keeps the call alive when app is backgrounded.
 * Required for audio/camera access in background.
 */
class CallForegroundService : Service() {

    companion object {
        private const val TAG = "CallFgService"
        const val INCOMING_CHANNEL_ID = "glyph_incoming_call_channel_v2"
        const val MISSED_CHANNEL_ID = "glyph_missed_call_channel"
        const val ONGOING_CHANNEL_ID = "glyph_ongoing_call"
        const val NOTIFICATION_ID = 9001

        /**
         * Silent incoming-call channel used when the fullscreen IncomingCallActivity
         * is or will be shown. The activity itself plays the ringtone, so the channel
         * must NOT play a sound — otherwise the user hears the ringtone twice (once
         * from the channel-sound on the heads-up, once from the activity's own
         * Ringtone.play() loop). Vibration is still honoured so the user gets the
         * haptic feedback in their pocket even with the screen on.
         *
         * The dynamic part (vib on/off) matches the audible channel so changed
         * vibrate settings take effect without leaking stale channels.
         */
        const val INCOMING_SILENT_CHANNEL_PREFIX = "glyph_incoming_call_silent"

        /** Dynamic incoming call channel ID — updated by [ensureNotificationChannels]. */
        @Volatile
        var currentIncomingChannelId: String = INCOMING_CHANNEL_ID
            private set

        /** Dynamic silent incoming call channel ID — updated by [ensureNotificationChannels]. */
        @Volatile
        var currentSilentIncomingChannelId: String = INCOMING_SILENT_CHANNEL_PREFIX + "_v0"
            private set

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_IS_INCOMING = "is_incoming"

        const val ACTION_END_CALL = "com.glyph.glyph_v3.ACTION_END_CALL"
        private const val ACTION_STOP_SERVICE = "com.glyph.glyph_v3.ACTION_STOP_CALL_SERVICE"

        /**
         * Resolves the FGS type the OS will actually accept given the runtime
         * permissions currently held by the app. On Android 14+ (targetSDK=34),
         * requesting FOREGROUND_SERVICE_TYPE_CAMERA without CAMERA (or
         * SYSTEM_CAMERA) granted throws SecurityException from startForeground().
         * We narrow the requested type to whatever the app is actually allowed
         * to use, so a missing camera permission degrades gracefully to mic-only
         * instead of crashing the whole process.
         *
         * Note: on Android < 14 the type was implicit and the type bitmap is
         * simply ignored by startForeground(id, notification).
         */
        internal fun resolveServiceType(context: Context, requested: Int): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return requested
            }
            val pm = context.packageManager
            val micGranted = pm.checkPermission(
                android.Manifest.permission.RECORD_AUDIO, context.packageName
            ) == PackageManager.PERMISSION_GRANTED
            val cameraGranted = pm.checkPermission(
                android.Manifest.permission.CAMERA, context.packageName
            ) == PackageManager.PERMISSION_GRANTED

            var resolved = 0
            if (micGranted && (requested and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) {
                resolved = resolved or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (cameraGranted && (requested and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0) {
                resolved = resolved or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            // Phone-call type is needed for the in-call style notification & to
            // keep the call alive when backgrounded; the FOREGROUND_SERVICE_PHONE_CALL
            // permission is declared in the manifest so it is always allowed.
            if ((requested and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) != 0) {
                resolved = resolved or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            return resolved
        }

        fun start(context: Context, callId: String, callType: CallType, isIncoming: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALL_TYPE, callType.name)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
        }

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // Read user call preferences
            val callRingtonePref = kotlinx.coroutines.runBlocking {
                com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .callRingtoneFlow(context).first()
            }
            val callVibratePref = kotlinx.coroutines.runBlocking {
                com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .callVibrateFlow(context).first()
            }

            val ringtoneUri: android.net.Uri = when (callRingtonePref) {
                "Default", "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                "None" -> android.net.Uri.EMPTY
                else -> runCatching { android.net.Uri.parse(callRingtonePref) }.getOrNull()
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            // Build a dynamic channel ID so changed ringtone/vibrate settings take effect
            val vibTag = if (callVibratePref) "1" else "0"
            val soundTag = if (callRingtonePref == "Default" || callRingtonePref.isBlank()) "def"
                           else callRingtonePref.hashCode().toUInt().toString(16)
            val dynamicIncomingChannelId = "glyph_incoming_call_v${vibTag}_s${soundTag}"
            val dynamicSilentIncomingChannelId = "${INCOMING_SILENT_CHANNEL_PREFIX}_v$vibTag"

            // If the channel ID changed, delete old AUDIBLE incoming call channels.
            // CRITICAL: don't blanket-delete all "glyph_incoming_call_*" — that would
            // also delete the silent channel, and on the next call the silent channel
            // would be recreated mid-ringing. Filter on the audible-channel pattern
            // ("glyph_incoming_call_v" — does NOT start with "glyph_incoming_call_silent").
            manager.notificationChannels
                .filter {
                    it.id.startsWith("glyph_incoming_call_") &&
                        !it.id.startsWith(INCOMING_SILENT_CHANNEL_PREFIX) &&
                        it.id != dynamicIncomingChannelId
                }
                .forEach { manager.deleteNotificationChannel(it.id) }

            // Update the companion constant so CallNotificationHelper uses the right channel
            currentIncomingChannelId = dynamicIncomingChannelId

            // Clean up old silent channels if vibrate preference changed
            manager.notificationChannels
                .filter {
                    it.id.startsWith(INCOMING_SILENT_CHANNEL_PREFIX) &&
                        it.id != dynamicSilentIncomingChannelId
                }
                .forEach { manager.deleteNotificationChannel(it.id) }
            currentSilentIncomingChannelId = dynamicSilentIncomingChannelId

            val ringtoneAudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (manager.getNotificationChannel(dynamicIncomingChannelId) == null) {
                val incomingChannel = NotificationChannel(
                    dynamicIncomingChannelId,
                    "Incoming Calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for incoming voice and video calls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(callVibratePref)
                    if (callRingtonePref != "None") {
                        setSound(ringtoneUri, ringtoneAudioAttributes)
                    } else {
                        setSound(null, null)
                    }
                }
                manager.createNotificationChannel(incomingChannel)
            }

            // Silent channel for the heads-up shown alongside the fullscreen
            // IncomingCallActivity. The activity plays the ringtone itself, so the
            // channel must stay silent — otherwise the user hears two overlapping
            // ringtones (channel sound + activity sound). Vibration is preserved so
            // the user still feels the call. Importance is HIGH so the heads-up
            // banner still appears, but no sound is emitted by the system.
            if (manager.getNotificationChannel(dynamicSilentIncomingChannelId) == null) {
                val silentChannel = NotificationChannel(
                    dynamicSilentIncomingChannelId,
                    "Incoming Calls (in-call)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Silent notification shown when an incoming call is already on screen"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(callVibratePref)
                    // CRITICAL: no sound on this channel — the activity plays it.
                    setSound(null, null)
                }
                manager.createNotificationChannel(silentChannel)
            }

            val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notifAudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val missedChannel = NotificationChannel(
                MISSED_CHANNEL_ID,
                "Missed Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for missed voice and video calls"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableVibration(true)
                setSound(notifUri, notifAudioAttributes)
            }
            manager.createNotificationChannel(missedChannel)

            val ongoingChannel = NotificationChannel(
                ONGOING_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification shown during active calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(ongoingChannel)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_CALL) {
            com.glyph.glyph_v3.data.webrtc.CallManager.endCall(applicationContext)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: ""
        val callType = try {
            CallType.valueOf(intent?.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE")
        } catch (_: Exception) { CallType.VOICE }

        val notification = buildOngoingNotification(callId, callType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val requested = if (callType == CallType.VIDEO) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            val serviceType = resolveServiceType(this, requested)
            if (serviceType == 0) {
                // No allowed FGS type — we'd crash with SecurityException. Bail out
                // cleanly; the call UI is already up and WebRTC is local, so a
                // missing FGS just means no background survival.
                Log.w(TAG, "No allowed FGS type for callType=$callType; skipping startForeground")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildOngoingNotification(callId: String, callType: CallType): Notification {
        val tapIntent = Intent(this, ActiveCallActivity::class.java).apply {
            putExtra(ActiveCallActivity.EXTRA_CALL_ID, callId)
            putExtra(ActiveCallActivity.EXTRA_CALL_TYPE, callType.name)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPending = PendingIntent.getService(
            this, 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (callType == CallType.VIDEO) "Video call" else "Voice call"

        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText("Ongoing call • Tap to return")
            .setContentIntent(tapPending)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "End", endPending)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GlyphV3:CallWakeLock"
        ).apply { acquire(60 * 60 * 1000L) } // 1 hour max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
