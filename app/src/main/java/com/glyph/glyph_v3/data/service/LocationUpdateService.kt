package com.glyph.glyph_v3.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.LocationRepository
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Foreground service that pushes the device location to Firebase RTDB
 * for **all active sharing targets** tracked by [ActiveSharingRegistry].
 *
 * Two modes:
 * 1. **Passive** (default when map background is on): Low-frequency updates (~30 s)
 * 2. **Live sharing**: High-frequency updates (~5 s) + rich ongoing notification
 *    with a countdown timer and a "Stop Sharing" action button.
 */
class LocationUpdateService : Service() {

    companion object {
        private const val TAG = "LocationUpdateService"
        const val CHANNEL_ID = "glyph_location_channel"
        const val LIVE_CHANNEL_ID = "glyph_live_location_channel"
        const val NOTIFICATION_ID = 8892

        const val ACTION_START_PASSIVE = "action_start_passive"
        const val ACTION_START_LIVE = "action_start_live"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_LIVE_SHARING = "action_stop_live_sharing"

        const val EXTRA_DURATION_MS = "extra_duration_ms"

        /** Start/resume the service in passive mode (or upgrade if live targets exist). */
        fun startPassive(context: Context) {
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = ACTION_START_PASSIVE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Start/resume the service in live mode (high-frequency). */
        fun startLive(context: Context, durationMs: Long) {
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = ACTION_START_LIVE
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Format milliseconds as "MM:SS" or "H:MM:SS". */
        fun formatCountdown(remainingMs: Long): String {
            if (remainingMs <= 0) return "0:00"
            val totalSeconds = remainingMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isLiveMode = false
    private var stopRequested = false
    private var stopLiveSharingRequested = false

    private fun getPublishTargets(): Set<String> = ActiveSharingRegistry.getLiveTargets()

    // ── Countdown timer for live notification ─────────────────────────────
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isLiveMode) return
            updateLiveNotification()
            notificationHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ActiveSharingRegistry.init(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PASSIVE, ACTION_START_LIVE -> {
                stopRequested = false
                stopLiveSharingRequested = false
                ensureForegroundStarted()
                adjustServiceMode()
            }
            ACTION_STOP_LIVE_SHARING -> {
                stopRequested = true
                stopLiveSharingRequested = true
                handleStopLiveSharingAction()
            }
            ACTION_STOP -> {
                stopRequested = true
                stopSelf()
            }
            null -> {
                stopRequested = false
                stopLiveSharingRequested = false
                Log.w(TAG, "[SERVICE] onStartCommand with null intent — recalculating mode")
                ensureForegroundStarted()
                adjustServiceMode()
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundStarted() {
        val notification = if (isLiveMode) {
            buildLiveNotification()
        } else {
            buildPassiveNotification("Glyph Map active")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Called when the user taps "Stop Sharing" in the notification.
     * Stops all live targets, cleans up Firebase, and either drops to passive or stops the service.
     */
    private fun handleStopLiveSharingAction() {
        val liveTargets = ActiveSharingRegistry.getLiveTargets().toSet()
        if (liveTargets.isNotEmpty()) {
            MapCameraShareForegroundService.stop(this)
            LocationRepository.stopAllLiveSharing(liveTargets)
            for (target in liveTargets) {
                ActiveSharingRegistry.removeLiveTarget(target)
            }
        }
        // Stop the countdown
        notificationHandler.removeCallbacks(countdownRunnable)
        isLiveMode = false

        // Drop to passive if passive targets remain, otherwise stop entirely
        if (ActiveSharingRegistry.hasAnyTargets()) {
            adjustServiceMode()
        } else {
            stopSelf()
        }
    }

    private fun pruneBlockedTargets() {
        val blockedTargets = ActiveSharingRegistry.getAllTargets().filterTo(linkedSetOf()) { targetUserId ->
            com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(targetUserId).isBlocked
        }
        if (blockedTargets.isEmpty()) {
            return
        }

        for (target in blockedTargets) {
            if (ActiveSharingRegistry.getLiveTargets().contains(target)) {
                MapCameraShareForegroundService.stop(this, target)
                LocationRepository.stopLiveSharing(target)
            }
            ActiveSharingRegistry.removeTarget(target)
        }
    }

    /**
     * Determine the correct update interval based on the [ActiveSharingRegistry]
     * and (re)configure location updates accordingly.
     */
    private fun adjustServiceMode() {
        pruneBlockedTargets()
        if (!ActiveSharingRegistry.hasAnyTargets()) {
            stopSelf()
            return
        }
        if (ActiveSharingRegistry.hasAnyLiveTargets()) {
            isLiveMode = true
            MapCameraShareForegroundService.ensureRunning(this)
            startForegroundLive()
            // Kick off the per-second countdown
            notificationHandler.removeCallbacks(countdownRunnable)
            notificationHandler.postDelayed(countdownRunnable, 1_000L)
            requestLocationUpdates(intervalMs = 5_000L, fastestMs = 3_000L)
        } else {
            isLiveMode = false
            notificationHandler.removeCallbacks(countdownRunnable)
            startForegroundWithNotification("Glyph Map active")
            requestLocationUpdates(intervalMs = 30_000L, fastestMs = 15_000L)
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────

    private fun startForegroundLive() {
        val notification = buildLiveNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateLiveNotification() {
        val notification = buildLiveNotification()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLiveNotification(): Notification {
        val liveTargets = ActiveSharingRegistry.getLiveTargets().toList()

        // Determine display name and remaining time
        val title: String
        val remainingMs: Long
        if (liveTargets.isEmpty()) {
            title = "Sharing live location"
            remainingMs = 0L
        } else {
            // Use the first (or only) live target
            val target = liveTargets.first()
            val name = ActiveSharingRegistry.getLiveDisplayName(target).ifEmpty { "User" }
            val count = liveTargets.size
            title = if (count == 1) "Sharing live location with $name"
                    else "Sharing live location with $count people"

            val start = ActiveSharingRegistry.getLiveStartTime(target)
            val duration = ActiveSharingRegistry.getLiveDuration(target)
            remainingMs = maxOf(0L, duration - (System.currentTimeMillis() - start))
        }

        val timerText = "Time remaining: ${formatCountdown(remainingMs)}"

        // "Stop Sharing" PendingIntent → re-delivers to onStartCommand
        val stopIntent = Intent(this, LocationUpdateService::class.java).apply {
            action = ACTION_STOP_LIVE_SHARING
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, LIVE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(timerText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)           // non-dismissible by swipe
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)     // don't re-alert on every update
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_close,
                "Stop Sharing",
                stopPi
            )
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildPassiveNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPassiveNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Glyph")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Passive map background channel — silent, minimal
        val passiveChannel = NotificationChannel(
            CHANNEL_ID,
            "Location (Map Background)",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows when Glyph map background is active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(passiveChannel)

        // Live sharing channel — visible, no sound
        val liveChannel = NotificationChannel(
            LIVE_CHANNEL_ID,
            "Live Location Sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while you are sharing your live location"
            setShowBadge(true)
        }
        nm.createNotificationChannel(liveChannel)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMs: Long, fastestMs: Long) {
        // Stop any existing callback
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted, stopping service")
            stopSelf()
            return
        }

        // Request a fresh high-accuracy fix immediately and push to all targets
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                pruneBlockedTargets()
                if (location != null) {
                    val targets = getPublishTargets()
                    if (targets.isNotEmpty()) {
                        LocationRepository.updateMyLocationForAllTargets(
                            targets = targets,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            heading = location.bearing,
                            speed = location.speed,
                            altitude = location.altitude
                        )
                    }
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                        if (cached != null) {
                            val targets = getPublishTargets()
                            if (targets.isNotEmpty()) {
                                LocationRepository.updateMyLocationForAllTargets(
                                    targets = targets,
                                    latitude = cached.latitude,
                                    longitude = cached.longitude,
                                    accuracy = cached.accuracy,
                                    heading = cached.bearing,
                                    speed = cached.speed,
                                    altitude = cached.altitude
                                )
                            }
                        } else {
                            Log.e(TAG, "Both getCurrentLocation and lastLocation returned null!")
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "getCurrentLocation failed: ${e.message}", e)
            }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(fastestMs)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                pruneBlockedTargets()

                // Check for expired live targets and clean them up
                val expired = ActiveSharingRegistry.removeExpiredLiveTargets()
                for (target in expired) {
                    MapCameraShareForegroundService.stop(this@LocationUpdateService, target)
                    LocationRepository.stopLiveSharing(target)
                }

                // Only explicit live sharing may publish coordinates to RTDB.
                val targets = getPublishTargets()
                if (targets.isEmpty()) {
                    if (!ActiveSharingRegistry.hasAnyTargets()) {
                        stopSelf()
                    }
                    return
                }

                LocationRepository.updateMyLocationForAllTargets(
                    targets = targets,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    heading = location.bearing,
                    speed = location.speed,
                    altitude = location.altitude
                )

                // If all live targets expired, drop to passive interval
                if (expired.isNotEmpty() && !ActiveSharingRegistry.hasAnyLiveTargets() && isLiveMode) {
                    isLiveMode = false
                    notificationHandler.removeCallbacks(countdownRunnable)
                    requestLocationUpdates(30_000L, 15_000L)
                    startForegroundWithNotification("Glyph Map active")
                }
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationHandler.removeCallbacks(countdownRunnable)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        // Only explicit user stop actions should end live sharing. Service destruction can
        // happen during normal foreground-service restarts, process reclaim, or lifecycle
        // transitions; preserve backend state so camera/location sharing can continue.
        if (stopLiveSharingRequested) {
            val liveTargets = ActiveSharingRegistry.getLiveTargets()
            if (liveTargets.isNotEmpty()) {
                MapCameraShareForegroundService.stop(this)
                LocationRepository.stopAllLiveSharing(liveTargets)
                for (target in liveTargets) {
                    ActiveSharingRegistry.removeLiveTarget(target)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
