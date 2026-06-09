package com.glyph.glyph_v3.ui.chat.map.routing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Manages non-dismissible navigation notifications.
 * Shows ongoing notification with "Stop Navigation" button when routing is active.
 */
class RoutingNotificationManager(private val context: Context) {

    companion object {
        // v2 channel ID — forces creation of a fresh IMPORTANCE_HIGH channel
        // (the old "glyph_routing_channel" was created with IMPORTANCE_LOW and is immutable)
        const val CHANNEL_ID = "glyph_routing_channel_v2"
        const val NOTIFICATION_ID = 9001

        // Broadcast action for stop navigation button (system broadcast → BroadcastReceiver)
        const val ACTION_STOP_NAVIGATION = "com.glyph.STOP_NAVIGATION"
        // Local broadcast action sent to notify the in-app activity to stop navigation
        const val ACTION_STOP_NAVIGATION_LOCAL = "com.glyph.STOP_NAVIGATION_LOCAL"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_USER_ID = "extra_user_id"

        fun showNavigationNotification(
            context: Context,
            otherUserName: String,
            state: RoutingUiState
        ) {
            val manager = RoutingNotificationManager(context)
            val mainActivityIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, context::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.showNavigationNotification(
                otherUserName,
                state.formattedDistance,
                state.formattedEta,
                state.chatId,
                mainActivityIntent
            )
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannelCreated()
    }

    private fun ensureChannelCreated() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigation",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown while you are navigating to someone"
            setShowBadge(true)
            enableLights(true)
            enableVibration(false)
            lightColor = android.graphics.Color.BLUE
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Show the ongoing navigation notification.
     *
     * @param otherUserName The name of the user you're navigating to.
     * @param distance      Formatted distance string (e.g., "1.2 km").
     * @param eta           Formatted ETA (e.g., "5 mins").
     * @param chatId        Chat ID for routing context.
     * @param mainActivityIntent Pending intent to open the main activity.
     */
    fun showNavigationNotification(
        otherUserName: String,
        distance: String,
        eta: String,
        chatId: String,
        mainActivityIntent: PendingIntent
    ) {
        // Delegate to NavigationForegroundService so the notification is hosted by a
        // foreground service — the only way to make it truly non-dismissible on Android 14+.
        NavigationForegroundService.start(context, otherUserName, distance, eta, chatId)
    }

    /**
     * Cancel/dismiss the navigation notification.
     */
    fun cancelNavigationNotification() {
        NavigationForegroundService.stop(context)
    }

    /**
     * Update the notification with new routing info.
     */
    fun updateNavigationNotification(
        otherUserName: String,
        distance: String,
        eta: String,
        chatId: String,
        mainActivityIntent: PendingIntent
    ) {
        showNavigationNotification(otherUserName, distance, eta, chatId, mainActivityIntent)
    }
}
