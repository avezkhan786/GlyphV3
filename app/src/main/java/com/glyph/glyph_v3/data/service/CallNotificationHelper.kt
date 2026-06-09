package com.glyph.glyph_v3.data.service

import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.models.CallType.VIDEO
import com.glyph.glyph_v3.data.models.CallType.VOICE
import com.glyph.glyph_v3.ui.calls.ActiveCallActivity
import com.glyph.glyph_v3.ui.calls.IncomingCallActivity
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds high-priority heads-up notifications for incoming calls,
 * replicating the WhatsApp notification style.
 */
object CallNotificationHelper {

    private const val TAG = "CallNotifHelper"
    const val INCOMING_NOTIFICATION_ID = 9002
    const val MISSED_NOTIFICATION_ID = 9003
    private const val MISSED_CHANNEL_ID = CallForegroundService.MISSED_CHANNEL_ID

    const val ACTION_ACCEPT_CALL = "com.glyph.glyph_v3.ACTION_ACCEPT_CALL"
    const val ACTION_DECLINE_CALL = "com.glyph.glyph_v3.ACTION_DECLINE_CALL"
    const val ACTION_DECLINE_GROUP_CALL = "com.glyph.glyph_v3.ACTION_DECLINE_GROUP_CALL"
    const val ACTION_CLEAR_MISSED_CALLS = "com.glyph.glyph_v3.ACTION_CLEAR_MISSED_CALLS"
    const val GROUP_CALL_NOTIFICATION_ID = 9004

    private const val PREFS_MISSED_CALLS = "missed_call_counts"

    private fun backgroundStartOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }

        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    /**
     * Show a heads-up incoming call notification that launches the fullscreen incoming call UI.
     */
    suspend fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerName: String,
        callerPhone: String,
        callerAvatar: String,
        callType: CallType
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationManager.getNotificationChannel(CallForegroundService.currentIncomingChannelId) == null) {
            CallForegroundService.ensureNotificationChannels(context)
        }
        val channelId = CallForegroundService.currentIncomingChannelId

        // Full-screen intent → opens IncomingCallActivity
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_PHONE, callerPhone)
            putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, callerAvatar)
            putExtra(IncomingCallActivity.EXTRA_CALL_TYPE, callType.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundStartOptions()
        )

        // Reuse the same accept flow as the fullscreen incoming UI.
        val acceptIntent = IncomingCallActivity.createIntent(
            context = context,
            callId = callId,
            callerName = callerName,
            callerPhone = callerPhone,
            callerAvatar = callerAvatar,
            callType = callType
        ).apply {
            putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val acceptPending = PendingIntent.getActivity(
            context,
            101,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundStartOptions()
        )

        // Decline action
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE_CALL
            putExtra("call_id", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context, 102, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeLabel = if (callType == CallType.VIDEO) "Incoming video call" else "Incoming voice call"

        // Build a Person with the caller avatar — CallStyle uses this to render
        // the avatar in the prominent large icon slot of the heads-up popup.
        val callerPerson = Person.Builder()
            .setName(callerName)
            .build()

        // NotificationCompat.CallStyle is the correct API for call notifications:
        // it renders the Person.icon in the big left/centre avatar position and
        // provides styled Answer / Decline buttons automatically.
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePending, acceptPending))
            .setContentText(callTypeLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(35_000L)  // Auto-cancel after 35s if observer misses the timeout
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        }
        CallLockScreenHelper.pulseScreenWake(context, "$TAG:IncomingCallWake")

        // Reliability first: always post the incoming-call notification so the user
        // has at least one surface even if a direct activity launch is blocked.
        notificationManager.notify(INCOMING_NOTIFICATION_ID, builder.build())

        val launchedFullscreenUi = if (shouldLaunchIncomingUi(context)) {
            launchIncomingCallUi(
                context = context,
                callId = callId,
                callerName = callerName,
                callerPhone = callerPhone,
                callerAvatar = callerAvatar,
                callType = callType,
                fallbackPendingIntent = fullScreenPending
            )
        } else {
            false
        }

        if (callerAvatar.isNotEmpty()) {
            CoroutineScope(Dispatchers.Main).launch {
                val rawAvatarBitmap: Bitmap? = try {
                    withContext(Dispatchers.IO) {
                        Glide.with(context.applicationContext)
                            .asBitmap()
                            .load(callerAvatar)
                            .override(256, 256)
                            .centerCrop()
                            .submit()
                            .get()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load avatar", e)
                    null
                }
                val avatarBitmap = rawAvatarBitmap?.let { ChatNotificationHelper.circularize(it) } ?: return@launch

                val updatedPerson = Person.Builder()
                    .setName(callerName)
                    .setIcon(IconCompat.createWithBitmap(avatarBitmap))
                    .build()

                val updatedBuilder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_call)
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(updatedPerson, declinePending, acceptPending))
                    .setContentText(callTypeLabel)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setTimeoutAfter(35_000L)
                    .setContentIntent(fullScreenPending)
                    .setFullScreenIntent(fullScreenPending, true)
                    .setLargeIcon(avatarBitmap)

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    updatedBuilder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                }

                notificationManager.notify(INCOMING_NOTIFICATION_ID, updatedBuilder.build())
            }
        }

        if (!launchedFullscreenUi) {
        } else {
        }
    }

    private fun shouldLaunchIncomingUi(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val isLocked = when {
            keyguardManager == null -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> keyguardManager.isDeviceLocked
            else -> keyguardManager.isKeyguardLocked
        }
        val isInteractive = powerManager?.isInteractive ?: true

        return !AppVisibilityTracker.isAppVisible || isLocked || !isInteractive
    }

    private fun launchIncomingCallUi(
        context: Context,
        callId: String,
        callerName: String,
        callerPhone: String,
        callerAvatar: String,
        callType: CallType,
        fallbackPendingIntent: PendingIntent
    ): Boolean {
        val incomingIntent = IncomingCallActivity.createIntent(
            context = context,
            callId = callId,
            callerName = callerName,
            callerPhone = callerPhone,
            callerAvatar = callerAvatar,
            callType = callType
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        try {
            context.startActivity(incomingIntent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Direct IncomingCallActivity launch failed, trying full-screen pending intent", e)
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
                return true
            } catch (pendingIntentError: PendingIntent.CanceledException) {
                Log.w(TAG, "Full-screen pending intent launch failed", pendingIntentError)
                return false
            }
        }
    }

    fun cancelIncomingNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(INCOMING_NOTIFICATION_ID)
    }

    fun cancelGroupCallNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(GROUP_CALL_NOTIFICATION_ID)
    }

    suspend fun showMissedCallNotification(
        context: Context,
        callerName: String,
        callerAvatar: String,
        callType: CallType
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Track voice and video missed calls for this caller separately
        val prefs = context.getSharedPreferences(PREFS_MISSED_CALLS, Context.MODE_PRIVATE)
        val baseKey = callerName.lowercase().trim()
        val typeKey = if (callType == VIDEO) "${baseKey}_video" else "${baseKey}_voice"
        prefs.edit().putInt(typeKey, prefs.getInt(typeKey, 0) + 1).apply()

        val voiceCount = prefs.getInt("${baseKey}_voice", 0)
        val videoCount = prefs.getInt("${baseKey}_video", 0)

        val title = buildMissedCallTitle(callerName, voiceCount, videoCount)

        // Load avatar as a plain square then circularize — same pattern as chat notifications.
        val rawBitmap: Bitmap? = if (callerAvatar.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(callerAvatar)
                        .override(256, 256)
                        .centerCrop()
                        .submit()
                        .get()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load missed-call avatar", e)
                null
            }
        } else null
        val circularBitmap = rawBitmap?.let { ChatNotificationHelper.circularize(it) }

        // deleteIntent fires when the user dismisses the notification — resets the count
        val clearIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_CLEAR_MISSED_CALLS
            putExtra("caller_name", callerName)
        }
        val clearPending = PendingIntent.getBroadcast(
            context, 200, clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build a Person with the caller avatar — required for MessagingStyle icon rendering.
        val avatarIcon = circularBitmap?.let { IconCompat.createWithBitmap(it) }
        val callerPerson = Person.Builder()
            .setName(callerName)
            .apply { if (avatarIcon != null) setIcon(avatarIcon) }
            .build()

        // Push a long-lived dynamic shortcut so Android 12+ renders the avatar
        // in the collapsed conversation header (left/center icon position).
        val shortcutId = "missed_call_${baseKey}"
        if (circularBitmap != null) {
            try {
                val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                }
                val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                    .setLongLived(true)
                    .setShortLabel(callerName)
                    .setIntent(shortcutIntent)
                    .setIcon(IconCompat.createWithBitmap(circularBitmap))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push dynamic shortcut for missed call", e)
            }
        }

        // MessagingStyle places the caller's avatar in the prominent left/center
        // icon position — identical to how chat notifications render sender avatars.
        val style = NotificationCompat.MessagingStyle(callerPerson)
            .addMessage(title, System.currentTimeMillis(), callerPerson)

        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed1)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setDeleteIntent(clearPending)
            .setContentIntent(clearPending)

        if (circularBitmap != null) {
            builder.setLargeIcon(circularBitmap)
            builder.setShortcutId(shortcutId)
        }

        manager.notify(MISSED_NOTIFICATION_ID, builder.build())
    }

    private fun buildMissedCallTitle(callerName: String, voice: Int, video: Int): String {
        val parts = mutableListOf<String>()
        if (voice > 0) parts += if (voice == 1) "1 missed voice call" else "$voice missed voice calls"
        if (video > 0) parts += if (video == 1) "1 missed video call" else "$video missed video calls"
        return when (parts.size) {
            0 -> "Missed call from $callerName"
            1 -> "${parts[0]} from $callerName"
            else -> "${parts[0]} and ${parts[1]} from $callerName"
        }
    }
}
