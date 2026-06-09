package com.glyph.glyph_v3.data.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.walkietalkie.WalkieTalkieIncomingActivity
import com.glyph.glyph_v3.utils.CallLockScreenHelper

/**
 * Shows a call-style incoming walkie-talkie notification with Accept / Decline buttons,
 * mirroring the voice-call notification UX from [CallNotificationHelper].
 */
object WalkieTalkieIncomingNotificationHelper {

    private const val TAG = "WtIncomingNotif"
    private const val CHANNEL_ID = "glyph_walkie_talkie_incoming_v2"
    private const val MISSED_CHANNEL_ID = "glyph_walkie_talkie_missed"
    const val NOTIFICATION_ID = 9012
    private const val MISSED_NOTIFICATION_ID_BASE = 9020
    const val EXTRA_WT_SESSION_ID = "wt_session_id"
    const val EXTRA_WT_INITIATOR_ID = "wt_initiator_id"
    const val EXTRA_WT_INITIATOR_NAME = "wt_initiator_name"
    const val EXTRA_WT_CREATED_AT = "wt_created_at"
    const val EXTRA_WT_OFFER_B64 = "wt_offer_b64"
    const val EXTRA_WT_OFFER_REVISION = "wt_offer_revision"
    const val EXTRA_WT_AUTO_ACCEPT = "wt_auto_accept"

    const val ACTION_ACCEPT_WT = "com.glyph.glyph_v3.ACTION_ACCEPT_WT"
    const val ACTION_DECLINE_WT = "com.glyph.glyph_v3.ACTION_DECLINE_WT"

    private fun backgroundStartOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    private fun ringtoneUri(context: Context): Uri {
        return Uri.parse("android.resource://${context.packageName}/${R.raw.wt_ringing}")
    }

    fun show(
        context: Context,
        sessionId: String,
        initiatorId: String,
        initiatorName: String,
        createdAt: Long = 0L,
        offerBase64: String = "",
        offerRevision: Int = 0
    ) {
        ensureChannel(context)

        // Full-screen intent → opens WalkieTalkieIncomingActivity (no auto-accept)
        val fullScreenIntent = WalkieTalkieIncomingActivity.createIntent(
            context = context,
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            offerBase64 = offerBase64,
            offerRevision = offerRevision
        )
        val fullScreenPending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundStartOptions()
        )

        // Accept button → WalkieTalkieIncomingActivity with auto-accept flag
        val acceptIntent = WalkieTalkieIncomingActivity.createIntent(
            context = context,
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            offerBase64 = offerBase64,
            offerRevision = offerRevision
        ).apply {
            putExtra(EXTRA_WT_AUTO_ACCEPT, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val acceptPending = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundStartOptions()
        )

        // Decline button → WalkieTalkieActionReceiver broadcast
        val declineIntent = Intent(context, WalkieTalkieActionReceiver::class.java).apply {
            action = ACTION_DECLINE_WT
            putExtra(EXTRA_WT_SESSION_ID, sessionId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID + 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder().setName(initiatorName).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_audio)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson, declinePending, acceptPending
                )
            )
            .setContentText("Incoming walkie-talkie")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000L)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(ringtoneUri(context))
        }

        CallLockScreenHelper.pulseScreenWake(context, "$TAG:Wake")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, builder.build())

        val launchedFullscreenUi = launchIncomingWtUi(
            context = context,
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            offerBase64 = offerBase64,
            offerRevision = offerRevision,
            fallbackPendingIntent = fullScreenPending
        )

        if (!launchedFullscreenUi) {
        } else {
        }
    }

    private fun shouldLaunchIncomingUi(context: Context): Boolean {
        // Always launch the incoming activity directly.
        //
        // Rationale: Android's full-screen intent fires automatically only when the device
        // is locked / screen-off.  When the app is in the foreground, Android downgrades the
        // notification to a heads-up banner which can be:
        //   • Suppressed by OEM notification filters
        //   • Missed on devices with small status bars
        //   • Delayed by a queued notification flush
        //
        // Directly starting WalkieTalkieIncomingActivity is the only reliable way to show
        // the accept/decline UI instantly in every device state:
        //   • App foreground   → activity appears immediately on top of the current screen
        //   • Screen off/locked → activity wakes the screen (FLAG_KEEP_SCREEN_ON + keyguard dismiss)
        //   • App background   → FLAG_ACTIVITY_NEW_TASK brings it to the front
        //
        // WalkieTalkieIncomingActivity uses FLAG_ACTIVITY_SINGLE_TOP so any duplicate launch
        // (e.g. both this path and Android's own full-screen-intent fire) is folded into a
        // single onNewIntent() call — no double UI.
        return true
    }

    private fun launchIncomingWtUi(
        context: Context,
        sessionId: String,
        initiatorId: String,
        initiatorName: String,
        createdAt: Long,
        offerBase64: String,
        offerRevision: Int,
        fallbackPendingIntent: PendingIntent
    ): Boolean {
        if (!shouldLaunchIncomingUi(context)) {
            return false
        }

        val incomingIntent = WalkieTalkieIncomingActivity.createIntent(
            context = context,
            sessionId = sessionId,
            initiatorId = initiatorId,
            initiatorName = initiatorName,
            createdAt = createdAt,
            offerBase64 = offerBase64,
            offerRevision = offerRevision
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        try {
            context.startActivity(incomingIntent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Direct WTIncoming launch failed, trying full-screen pending intent", e)
            try {
                fallbackPendingIntent.send(
                    context,
                    0,
                    null,
                    null,
                    null,
                    null,
                    backgroundStartOptions()
                )
            } catch (pendingIntentError: PendingIntent.CanceledException) {
                Log.w(TAG, "WT full-screen pending intent launch also failed", pendingIntentError)
            }
            return false
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming Walkie-Talkie",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts you when someone wants to start a walkie-talkie session"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(
                ringtoneUri(context),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureMissedChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MISSED_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            MISSED_CHANNEL_ID,
            "Missed Walkie-Talkie",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for walkie-talkie sessions you missed"
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Show a non-intrusive "Missed Walkie Talkie" notification.
     * Used when a session expired or was cancelled while the device was offline.
     */
    fun showMissed(context: Context, initiatorName: String, sessionId: String) {
        ensureMissedChannel(context)

        val notificationId = MISSED_NOTIFICATION_ID_BASE + (sessionId.hashCode() and 0x7FFF)
        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_audio)
            .setContentTitle("Missed Walkie-Talkie Call")
            .setContentText("Missed walkie-talkie call from $initiatorName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, builder.build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
