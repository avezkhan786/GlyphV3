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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.LocationRepository
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MapCameraShareForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "glyph_live_camera_feed_channel"
        private const val NOTIFICATION_ID = 8893

        private const val ACTION_START = "action_start_live_camera_share"
        private const val ACTION_STOP = "action_stop_live_camera_share"

        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_TARGET_USER_ID = "extra_target_user_id"
        private const val EXTRA_OTHER_USER_NAME = "extra_other_user_name"
        private const val EXTRA_OTHER_USER_AVATAR = "extra_other_user_avatar"

        fun start(
            context: Context,
            chatId: String,
            targetUserId: String,
            otherUserName: String,
            otherUserAvatar: String
        ) {
            val intent = Intent(context, MapCameraShareForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_TARGET_USER_ID, targetUserId)
                putExtra(EXTRA_OTHER_USER_NAME, otherUserName)
                putExtra(EXTRA_OTHER_USER_AVATAR, otherUserAvatar)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun ensureRunning(context: Context) {
            ActiveSharingRegistry.init(context)
            val active = ActiveMapCameraShareStore.get(context) ?: return
            if (!ActiveSharingRegistry.getLiveTargets().contains(active.targetUserId)) return
            start(
                context = context,
                chatId = active.chatId,
                targetUserId = active.targetUserId,
                otherUserName = active.otherUserName,
                otherUserAvatar = active.otherUserAvatar
            )
        }

        fun stop(context: Context, targetUserId: String? = null) {
            val active = ActiveMapCameraShareStore.get(context)
            if (targetUserId != null && active?.targetUserId != targetUserId) return
            val intent = Intent(context, MapCameraShareForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: MapVideoSessionManager? = null
    private var activeSession: ActiveMapCameraShareStore.Session? = null
    private var stopRequested = false

    private enum class StopReason {
        PRESERVE_SHARE,
        DISABLE_SHARE
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                stopRequested = false
                val session = ActiveMapCameraShareStore.Session(
                    chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty(),
                    targetUserId = intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty(),
                    otherUserName = intent.getStringExtra(EXTRA_OTHER_USER_NAME).orEmpty(),
                    otherUserAvatar = intent.getStringExtra(EXTRA_OTHER_USER_AVATAR).orEmpty()
                )
                if (session.chatId.isBlank() || session.targetUserId.isBlank()) {
                    stopRequested = true
                    stopSelf()
                } else {
                    startOrRestoreSession(session)
                }
            }

            ACTION_STOP -> {
                stopRequested = true
                stopCurrentSession(clearStore = true, stopReason = StopReason.DISABLE_SHARE)
                stopSelf()
            }

            else -> {
                stopRequested = false
                val restored = ActiveMapCameraShareStore.get(this)
                if (restored != null) {
                    startOrRestoreSession(restored)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startOrRestoreSession(session: ActiveMapCameraShareStore.Session) {
        val myUserId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            stopRequested = true
            stopSelf()
            return
        }
        if (com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(session.targetUserId).isBlocked) {
            stopRequested = true
            stopCurrentSession(clearStore = true, stopReason = StopReason.DISABLE_SHARE)
            stopSelf()
            return
        }

        if (activeSession != null && activeSession != session) {
            stopCurrentSession(clearStore = false, stopReason = StopReason.DISABLE_SHARE)
        }

        activeSession = session
        ActiveMapCameraShareStore.save(this, session)
        LocationRepository.setCameraSharingEnabled(session.targetUserId, true)

        if (manager == null) {
            manager = MapVideoSessionManager(
                context = applicationContext,
                scope = serviceScope,
                chatId = session.chatId,
                myUserId = myUserId,
                otherUserId = session.targetUserId,
                allowOutgoingAudio = hasMicrophonePermission()
            )
        }

        manager?.let {
            MapVideoSessionRegistry.register(
                chatId = session.chatId,
                myUserId = myUserId,
                otherUserId = session.targetUserId,
                manager = it
            )
        }

        startForegroundWithNotification(session)
        manager?.setAutoSendEnabled(true)
        // Signal the target user to auto-open their video overlay (no acceptance required).
        MapVideoSignalingRepository.writeCameraBroadcastCommand(
            chatId = session.chatId,
            targetUserId = session.targetUserId
        )
    }

    private fun startForegroundWithNotification(session: ActiveMapCameraShareStore.Session) {
        val notification = buildNotification(session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceTypes()
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypes(): Int {
        var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasMicrophonePermission()) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return serviceTypes
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(session: ActiveMapCameraShareStore.Session): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            ChatActivity.newIntent(
                context = this,
                chatId = session.chatId,
                otherUserId = session.targetUserId,
                otherUsername = session.otherUserName,
                otherUserAvatar = session.otherUserAvatar
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Live Camera Feed Active")
            .setContentText("Your camera and audio are currently being shared along with live location. Tap to return to the app.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopCurrentSession(clearStore: Boolean, stopReason: StopReason) {
        val session = activeSession ?: ActiveMapCameraShareStore.get(this)
        MapVideoSessionRegistry.clear(manager)
        if (stopReason == StopReason.DISABLE_SHARE) {
            manager?.setAutoSendEnabled(false)
        }
        manager?.onDestroy()
        manager = null
        // Clear the broadcast signal only when the user explicitly stops sharing or the
        // session becomes invalid. A transient service teardown should preserve backend state
        // so the feed remains discoverable and can be restored on restart.
        if (session != null && stopReason == StopReason.DISABLE_SHARE) {
            MapVideoSignalingRepository.clearCameraBroadcastCommand(
                chatId = session.chatId,
                targetUserId = session.targetUserId
            )
        }
        if (stopReason == StopReason.DISABLE_SHARE) {
            session?.let { LocationRepository.setCameraSharingEnabled(it.targetUserId, false) }
        }
        activeSession = null
        if (clearStore) {
            ActiveMapCameraShareStore.clear(this)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live Camera Feed",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while your camera is shared with live location"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        stopCurrentSession(
            clearStore = stopRequested,
            stopReason = if (stopRequested) StopReason.DISABLE_SHARE else StopReason.PRESERVE_SHARE
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
