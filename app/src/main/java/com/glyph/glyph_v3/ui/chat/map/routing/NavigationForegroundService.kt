package com.glyph.glyph_v3.ui.chat.map.routing

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R

/**
 * Foreground service that hosts the non-dismissible navigation notification.
 *
 * Unlike a regular [android.app.NotificationManager.notify] call, a foreground-service
 * notification cannot be swiped away by the user on any Android version — matching the
 * behaviour of the live-location-sharing notification in [com.glyph.glyph_v3.data.service.LocationUpdateService].
 */
class NavigationForegroundService : Service() {

    companion object {
        private const val TAG = "NavForegroundService"

        const val ACTION_START = "action_nav_start"
        const val ACTION_STOP  = "action_nav_stop"

        const val EXTRA_USER_NAME = "extra_nav_user_name"
        const val EXTRA_DISTANCE  = "extra_nav_distance"
        const val EXTRA_ETA       = "extra_nav_eta"
        const val EXTRA_CHAT_ID   = "extra_nav_chat_id"

        /** Start (or update) the foreground navigation notification. */
        fun start(
            context: Context,
            userName: String,
            distance: String,
            eta: String,
            chatId: String
        ) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_NAME, userName)
                putExtra(EXTRA_DISTANCE,  distance)
                putExtra(EXTRA_ETA,       eta)
                putExtra(EXTRA_CHAT_ID,   chatId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop the foreground navigation notification. */
        fun stop(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Ensure the notification channel exists before startForeground() is called
        RoutingNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val userName = intent.getStringExtra(EXTRA_USER_NAME) ?: "User"
                val distance = intent.getStringExtra(EXTRA_DISTANCE) ?: ""
                val eta      = intent.getStringExtra(EXTRA_ETA)      ?: ""
                val chatId   = intent.getStringExtra(EXTRA_CHAT_ID)  ?: ""

                val notification = buildNotification(userName, distance, eta, chatId)

                // startForeground() makes this notification truly non-dismissible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        RoutingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(RoutingNotificationManager.NOTIFICATION_ID, notification)
                }

            }

            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        userName: String,
        distance: String,
        eta: String,
        chatId: String
    ): Notification {
        val title = "Navigating to $userName"
        val contentText = if (distance.isNotEmpty() && eta.isNotEmpty()) {
            "$distance away • ETA: $eta"
        } else {
            "Calculating route…"
        }

        // Tap → re-open the app
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val contentPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop Navigation" button → broadcast to RoutingNotificationBroadcastReceiver
        val stopIntent = Intent(this, RoutingNotificationBroadcastReceiver::class.java).apply {
            action = RoutingNotificationManager.ACTION_STOP_NAVIGATION
            putExtra(RoutingNotificationManager.EXTRA_CHAT_ID, chatId)
        }
        val stopPi = PendingIntent.getBroadcast(
            this,
            chatId.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // On Android 14 (targetSdk=34), location-type foreground service notifications are
        // user-dismissible. Only mediaPlayback/mediaProjection/phoneCall remain non-dismissible.
        // Fix: set a deleteIntent that immediately re-posts the notification when swiped away,
        // making it effectively non-dismissible until navigation is intentionally stopped.
        val reshowIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_USER_NAME, userName)
            putExtra(EXTRA_DISTANCE, distance)
            putExtra(EXTRA_ETA, eta)
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val reshowPi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                chatId.hashCode() + 1,
                reshowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                chatId.hashCode() + 1,
                reshowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, RoutingNotificationManager.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setDeleteIntent(reshowPi)
            .addAction(R.drawable.ic_close, "Stop Navigation", stopPi)
            .build()
    }
}
