package com.glyph.glyph_v3.ui.chat.map.routing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.glyph.glyph_v3.R

/**
 * Manages non-dismissible notifications for active navigation sessions.
 * Displays real-time route info (distance, ETA, duration) and travel mode.
 */
class NavigationNotificationManager(private val context: Context) {
    companion object {
        private const val TAG = "NavNotifications"
        private const val CHANNEL_ID = "navigation_active"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_NAME = "Active Navigation"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for active navigation"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show a non-dismissible "navigation active" notification with real-time route info.
     *
     * @param otherUserName Name of the destination user.
     * @param distance Formatted distance string (e.g., "5.2 km").
     * @param eta Formatted ETA string (e.g., "2:45 PM").
     * @param duration Formatted duration string (e.g., "12 min").
     * @param travelMode Current travel mode ("Driving" or "Walking").
     * @param isDark Whether dark theme is active.
     */
    fun showNavigationActive(
        otherUserName: String,
        distance: String,
        eta: String,
        duration: String,
        travelMode: String,
        isDark: Boolean
    ) {
        val contentText = "Navigating to $otherUserName • $distance away • ETA $eta"
        val bigText = """
            Navigating to $otherUserName
            Distance: $distance
            ETA: $eta
            Duration: $duration
            Mode: $travelMode
        """.trimIndent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Navigation Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_location_on)
            .setOngoing(true)  // Non-dismissible
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setColor(if (isDark) 0xFF42A5F5.toInt() else 0xFF1A73E8.toInt())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Cancel the navigation notification (called when navigation stops).
     */
    fun cancelNavigationNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
