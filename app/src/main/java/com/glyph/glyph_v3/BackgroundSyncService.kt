package com.glyph.glyph_v3

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Deferred heavy init: Firebase, Room, presence, network, sync.
 * Starts after LeanLauncher shows chat list.
 */
class BackgroundSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BackgroundSync", "Starting deferred sync (Firebase/Room/presence/network)")
        // Initialize heavy SDKs here, after launcher has shown chat list
        try {
            // Placeholders for deferred init: Firebase, Room, Presence, Network
            // Actual init calls would go in background coroutine
        } catch (e: Exception) { Log.e("BackgroundSync", "Sync init error", e) }
        return START_NOT_STICKY
    }
}
