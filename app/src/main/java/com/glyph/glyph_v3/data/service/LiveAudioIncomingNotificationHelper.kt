package com.glyph.glyph_v3.data.service

import android.app.ActivityOptions
import android.app.PendingIntent.CanceledException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.liveaudio.LiveAudioIncomingActivity
import com.glyph.glyph_v3.utils.CallLockScreenHelper

/**
 * Shows a high-priority incoming-live-audio notification. Its full-screen intent
 * temporarily foregrounds the app so microphone access can start legally on Android 14.
 */
object LiveAudioIncomingNotificationHelper {

    private const val TAG = "LiveAudioIncomingNotif"
    private const val CHANNEL_ID = "glyph_live_audio_incoming"
    const val NOTIFICATION_ID = 9011
    const val EXTRA_LIVE_AUDIO_SESSION_ID = "live_audio_session_id"
    const val EXTRA_LIVE_AUDIO_LISTENER_NAME = "live_audio_listener_name"

    private fun backgroundStartOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }

        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    fun show(context: Context, sessionId: String, listenerId: String, listenerName: String) {
        ensureChannel(context)

        val tapIntent = LiveAudioIncomingActivity.createIntent(
            context = context,
            sessionId = sessionId,
            listenerName = listenerName
        )
        val tapPending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("$listenerName wants to listen")
            .setContentText("Starting live audio sharing")
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPending)
            .setFullScreenIntent(tapPending, true)
            .setTimeoutAfter(60_000L)
            .build()

        CallLockScreenHelper.pulseScreenWake(context, "$TAG:Wake")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
        try {
            tapPending.send(
                context,
                0,
                null,
                null,
                null,
                null,
                backgroundStartOptions()
            )
        } catch (e: CanceledException) {
            Log.w(TAG, "PendingIntent launch for LiveAudioIncomingActivity failed; notification fallback remains active", e)
        } catch (e: Exception) {
            Log.w(TAG, "Direct LiveAudioIncomingActivity launch failed; notification fallback remains active", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming Live Audio",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts you when someone wants to listen to your live audio"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
