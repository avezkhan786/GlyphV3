# Last Seen Timestamp Fix

## Issue Analysis
The issue was that whenever User A sent a message to User B, User B's "Last Seen" timestamp would update to "just now", even if User B did not open the app.

### Root Cause
1. **FCM Wake-up**: When a message is sent, a high-priority FCM notification is delivered to the recipient's device.
2. **Device Wake-up**: This wakes up the device from Doze mode and activates the radio.
3. **Network Change**: The radio activation causes a network capability change (or availability event).
4. **NetworkMonitor**: `NetworkConnectivityMonitor` detects this network availability event.
5. **Incorrect Presence Update**: `NetworkConnectivityMonitor` was unconditionally calling `PresenceManager.goOnline()`.
6. **Presence Writing**: `goOnline()` updates the user's presence node in Firebase (`isOnline: true`, `lastSeen: <now>`).

This sequence caused the recipient's device to briefly mark itself as "Online" and update "Last Seen" merely because it received a message in the background.

## The Fix
Modified `NetworkConnectivityMonitor.kt` to check if the app is actually visible to the user before setting presence to Online.

```kotlin
    private fun handleReconnection() {
        if (FirebaseAuth.getInstance().currentUser != null) {
            try {
                // Give network a moment to stabilize before updating presence
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // CRITICAL FIX: Only go online if the app is actually visible to the user.
                    // If this is a background wake-up (e.g. FCM), do NOT set presence to online.
                    if (AppVisibilityTracker.isAppVisible) {
                        PresenceManager.goOnline()
                        Log.d(TAG, "App is visible - setting presence to ONLINE")
                    } else {
                        Log.d(TAG, "App is in background - SKIPPING presence update (FCM wake?)")
                    }
                    
                    // Force restart of message sync to catch any pending messages
                    val app = context.applicationContext as? com.glyph.glyph_v3.GlyphApplication
                    app?.repository?.startIncomingMessageSync()
                    Log.d(TAG, "Incoming sync restarted after network reconnection")
                }, 1000) 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update presence on network change", e)
            }
        }
    }
```

### Verification
- **Message Sync**: The message sync (`startIncomingMessageSync()`) is still called unconditionally, ensuring messages are received in the background.
- **Presence**: `PresenceManager.goOnline()` is now gated by `AppVisibilityTracker.isAppVisible`.
- **App Open**: When the user actually opens the app, `MainActivity.onResume()` calls `PresenceManager.goOnline()`, ensuring presence is correctly set when the user is active.

This ensures that receiving a message (and the subsequent network activity) does NOT trigger a presence update.
