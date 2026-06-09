package com.glyph.glyph_v3.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.settings.WalkieTalkieSettingsActivity
import com.glyph.glyph_v3.ui.walkietalkie.WalkieTalkieActiveActivity

/**
 * Foreground service that keeps the microphone active during live audio sharing.
 *
 * Lifecycle:
 *  - Started ONLY when the broadcaster's mic becomes active (on-demand).
 *  - Stopped immediately when the last listener disconnects or sharing is disabled.
 *  - Uses START_NOT_STICKY to avoid auto-restart after system kill.
 */
class LiveAudioForegroundService : Service() {

    companion object {
        private const val TAG = "LiveAudioFgService"
        const val CHANNEL_ID = "glyph_live_audio_sharing"
        const val NOTIFICATION_ID = 9010
        private const val ACTION_STOP = "com.glyph.glyph_v3.ACTION_STOP_LIVE_AUDIO"
        const val EXTRA_MODE = "mode"
        private const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        private const val EXTRA_NOTIFICATION_TEXT = "notification_text"
        private const val EXTRA_SHOW_DISABLE_AUTO_ACCEPT = "show_disable_auto_accept"
        private const val EXTRA_REQUIRES_MICROPHONE_ACCESS = "requires_microphone_access"
        private const val EXTRA_WT_SESSION_ID = "wt_fgs_session_id"
        private const val EXTRA_WT_PEER_ID = "wt_fgs_peer_id"
        private const val EXTRA_WT_PEER_NAME = "wt_fgs_peer_name"
        const val MODE_LIVE_AUDIO = "live_audio"
        const val MODE_WALKIE_TALKIE = "walkie_talkie"

        fun start(
            context: Context,
            mode: String = MODE_LIVE_AUDIO,
            notificationTitle: String? = null,
            notificationText: String? = null,
            showDisableAutoAcceptAction: Boolean = false,
            requiresMicrophoneAccess: Boolean = true,
            wtSessionId: String? = null,
            wtPeerId: String? = null,
            wtPeerName: String? = null
        ) {
            val intent = Intent(context, LiveAudioForegroundService::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_SHOW_DISABLE_AUTO_ACCEPT, showDisableAutoAcceptAction)
                .putExtra(EXTRA_REQUIRES_MICROPHONE_ACCESS, requiresMicrophoneAccess)

            if (!notificationTitle.isNullOrBlank()) {
                intent.putExtra(EXTRA_NOTIFICATION_TITLE, notificationTitle)
            }
            if (!notificationText.isNullOrBlank()) {
                intent.putExtra(EXTRA_NOTIFICATION_TEXT, notificationText)
            }
            if (!wtSessionId.isNullOrBlank()) {
                intent.putExtra(EXTRA_WT_SESSION_ID, wtSessionId)
            }
            if (!wtPeerId.isNullOrBlank()) {
                intent.putExtra(EXTRA_WT_PEER_ID, wtPeerId)
            }
            if (!wtPeerName.isNullOrBlank()) {
                intent.putExtra(EXTRA_WT_PEER_NAME, wtPeerName)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveAudioForegroundService::class.java))
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            if (manager.getNotificationChannel(CHANNEL_ID) != null) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Audio Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown when your microphone is being shared live"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    private var currentMode: String = MODE_LIVE_AUDIO
    private var currentTitle: String? = null
    private var currentText: String? = null
    private var showDisableAutoAcceptAction: Boolean = false
    private var requiresMicrophoneAccess: Boolean = true
    private var currentWtSessionId: String? = null
    private var currentWtPeerId: String? = null
    private var currentWtPeerName: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (currentMode == MODE_WALKIE_TALKIE) {
                WalkieTalkieManager.getInstance(applicationContext).disconnect()
            } else {
                LiveAudioSharingManager.getInstance(applicationContext).stopBroadcasting()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        currentMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_LIVE_AUDIO
        intent?.getStringExtra(EXTRA_NOTIFICATION_TITLE)?.let { currentTitle = it }
        intent?.getStringExtra(EXTRA_NOTIFICATION_TEXT)?.let { currentText = it }
        if (intent?.hasExtra(EXTRA_SHOW_DISABLE_AUTO_ACCEPT) == true) {
            showDisableAutoAcceptAction = intent.getBooleanExtra(EXTRA_SHOW_DISABLE_AUTO_ACCEPT, false)
        }
        if (intent?.hasExtra(EXTRA_REQUIRES_MICROPHONE_ACCESS) == true) {
            requiresMicrophoneAccess = intent.getBooleanExtra(EXTRA_REQUIRES_MICROPHONE_ACCESS, true)
        }
        intent?.getStringExtra(EXTRA_WT_SESSION_ID)?.let { if (it.isNotBlank()) currentWtSessionId = it }
        intent?.getStringExtra(EXTRA_WT_PEER_ID)?.let { if (it.isNotBlank()) currentWtPeerId = it }
        intent?.getStringExtra(EXTRA_WT_PEER_NAME)?.let { if (it.isNotBlank()) currentWtPeerName = it }
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For background auto-accept receive mode on Android 14+, we must avoid declaring
            // microphone FGS access until the app is in an eligible state. Listening-only WT
            // sessions therefore start as phoneCall-only and can be upgraded later if needed.
            val foregroundServiceType = if (requiresMicrophoneAccess) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }

            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType
                )
            } catch (securityException: SecurityException) {
                if (requiresMicrophoneAccess) {
                    Log.w(
                        TAG,
                        "FGS microphone access denied; falling back to phoneCall-only foreground type",
                        securityException
                    )
                    requiresMicrophoneAccess = false
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    throw securityException
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GlyphV3:LiveAudioWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LiveAudioForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settingsPending = PendingIntent.getActivity(
            this,
            100,
            Intent(this, WalkieTalkieSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disableAutoAcceptPending = PendingIntent.getBroadcast(
            this,
            101,
            Intent(this, WalkieTalkieAutoAcceptActionReceiver::class.java).apply {
                action = WalkieTalkieAutoAcceptActionReceiver.ACTION_DISABLE_AUTO_ACCEPT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isWt = currentMode == MODE_WALKIE_TALKIE
        val defaultTitle = if (isWt) "Walkie-Talkie Active" else "Live audio sharing in progress"
        val defaultText = if (isWt) "Push-to-talk session in progress" else "Your microphone is being shared"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(currentTitle ?: defaultTitle)
            .setContentText(currentText ?: defaultText)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_close,
                if (isWt) "End Session" else "Stop Sharing",
                stopPending
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (isWt) {
            // Prefer opening the active PTT screen when we have session details.
            // Fall back to settings if the session hasn't been attached yet.
            val wtActiveIntent = run {
                val sid = currentWtSessionId
                    ?: WalkieTalkieManager.getInstance(applicationContext).activeSessionId.value
                val pid = currentWtPeerId
                    ?: WalkieTalkieManager.getInstance(applicationContext).connectedPeerId.value
                if (!sid.isNullOrBlank() && !pid.isNullOrBlank()) {
                    WalkieTalkieActiveActivity.createIntent(
                        context = this,
                        sessionId = sid,
                        peerId = pid,
                        peerName = currentWtPeerName.orEmpty()
                    )
                } else null
            }
            val contentPending = if (wtActiveIntent != null) {
                PendingIntent.getActivity(
                    this, 102, wtActiveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                settingsPending
            }
            builder.setContentIntent(contentPending)
            if (showDisableAutoAcceptAction) {
                builder.addAction(
                    R.drawable.ic_privacy,
                    "Disable Auto-Accept",
                    disableAutoAcceptPending
                )
            }
        }

        return builder.build()
    }
}
