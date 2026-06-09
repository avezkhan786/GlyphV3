package com.glyph.glyph_v3.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

        /** Dynamic incoming call channel ID — updated by [ensureNotificationChannels]. */
        @Volatile
        var currentIncomingChannelId: String = INCOMING_CHANNEL_ID
            private set

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_IS_INCOMING = "is_incoming"

        const val ACTION_END_CALL = "com.glyph.glyph_v3.ACTION_END_CALL"
        private const val ACTION_STOP_SERVICE = "com.glyph.glyph_v3.ACTION_STOP_CALL_SERVICE"

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

            // If the channel ID changed, delete old incoming call channels
            manager.notificationChannels
                .filter { it.id.startsWith("glyph_incoming_call_") && it.id != dynamicIncomingChannelId }
                .forEach { manager.deleteNotificationChannel(it.id) }

            // Update the companion constant so CallNotificationHelper uses the right channel
            currentIncomingChannelId = dynamicIncomingChannelId

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
            val serviceType = if (callType == CallType.VIDEO) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
