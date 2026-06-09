package com.glyph.glyph_v3.ui.chat.map.routing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.ui.chat.map.MapBackgroundPreferences

/**
 * Handles "Stop Navigation" button clicks from the routing notification.
 */
class RoutingNotificationBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RoutingNotifReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != RoutingNotificationManager.ACTION_STOP_NAVIGATION) return

        val chatId = intent.getStringExtra(RoutingNotificationManager.EXTRA_CHAT_ID) ?: return


        // Clear navigation state from preferences
        val prefs = MapBackgroundPreferences.getInstance(context)
        prefs.clearNavigationState(chatId)

        // Cancel the notification / stop the foreground service
        val notificationManager = RoutingNotificationManager(context)
        notificationManager.cancelNavigationNotification()

        // Notify the in-app activity (if it is in the foreground) so it can also call
        // routingManager.stopNavigation() and keep UI state in sync.
        val localIntent = Intent(RoutingNotificationManager.ACTION_STOP_NAVIGATION_LOCAL).apply {
            `package` = context.packageName
            putExtra(RoutingNotificationManager.EXTRA_CHAT_ID, chatId)
        }
        context.sendBroadcast(localIntent)
    }
}
