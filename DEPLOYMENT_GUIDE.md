# PRESENCE AND NOTIFICATION FIX - DEPLOYMENT GUIDE

## Quick Start - What Was Fixed

### ✅ Presence System
1. **Added heartbeat mechanism** - Prevents stale "online" status
2. **Fixed lifecycle management** - Removed incorrect `cleanup()` call from ChatActivity
3. **Added per-chat tracking** - Shows when user is viewing specific chat
4. **Implemented staleness detection** - Client-side validation of presence freshness

### ✅ Notification System
1. **Upgraded to MAX priority** - Ensures notifications wake device
2. **Enhanced notification channel** - Proper Android O+ configuration
3. **Added network monitoring** - Auto-reconnects presence on network restore
4. **Added required permissions** - WAKE_LOCK, ACCESS_NETWORK_STATE

---

## Files Modified

### 1. PresenceManager.kt
**Changes:**
- Added `HEARTBEAT_INTERVAL_MS` (30s) and `PRESENCE_TIMEOUT_MS` (60s) constants
- Added heartbeat job and coroutine scope
- Added `isAppInForeground` and `activeChatId` state tracking
- Added `startHeartbeat()` and `stopHeartbeat()` methods
- Added `setViewingChat(chatId)` method for per-chat presence
- Updated `initialize()`, `goOnline()`, `goOffline()` with heartbeat logic
- Updated `observeUserPresence()` and `observeMultipleUsersPresence()` with staleness detection
- Updated `cleanup()` with better state management

**Impact:** ⚠️ **CRITICAL** - Core presence logic changed

### 2. ChatActivity.kt
**Changes:**
- `onResume()`: Added `PresenceManager.setViewingChat(chatId)`
- `onPause()`: Added `PresenceManager.setViewingChat(null)`
- `onDestroy()`: **REMOVED** `PresenceManager.cleanup()` call (critical fix)

**Impact:** ⚠️ **CRITICAL** - Fixes false offline status

### 3. MyFirebaseMessagingService.kt
**Changes:**
- Changed notification priority from `PRIORITY_HIGH` to `PRIORITY_MAX`
- Added `setVisibility(VISIBILITY_PRIVATE)`
- Added `setOnlyAlertOnce(false)`
- Enhanced notification channel with lights, vibration, badge support
- Added try-catch for notification display with logging

**Impact:** ⚠️ **HIGH** - Improves notification reliability

### 4. AndroidManifest.xml
**Changes:**
- Added `android.permission.WAKE_LOCK`
- Added `android.permission.ACCESS_NETWORK_STATE`
- Added `android.permission.FOREGROUND_SERVICE`
- Registered `NetworkStateReceiver`

**Impact:** ⚠️ **HIGH** - Required for full functionality

### 5. NetworkStateReceiver.kt (NEW FILE)
**Purpose:** Monitors network connectivity changes and refreshes presence when connection is restored

**Impact:** ⚠️ **MEDIUM** - Improves presence accuracy after network issues

---

## Testing Instructions

### Before Deploying
1. ✅ Read the full `PRESENCE_AND_NOTIFICATION_FIX.md` document
2. ✅ Review all code changes in PresenceManager.kt
3. ✅ Verify AndroidManifest.xml changes
4. ✅ Check that Firebase RTDB rules allow presence writes

### After Deploying

#### Test Suite 1: Presence Accuracy
```
1. Open app → Check Firebase RTDB: /presence/{userId}/isOnline should be true within 2s
2. Wait 60s → Check Firebase RTDB: /presence/{userId}/lastHeartbeat should update every 30s
3. Kill app → Check Firebase RTDB: /presence/{userId}/isOnline should be false within 60s
4. Airplane mode ON → Check Firebase RTDB: User should go offline within 60s
5. Airplane mode OFF → Check Firebase RTDB: User should go online within 3s
```

#### Test Suite 2: Chat Presence
```
1. Open ChatActivity → Check Firebase RTDB: /presence/{userId}/viewingChat should equal chatId
2. Leave ChatActivity → Check Firebase RTDB: /presence/{userId}/viewingChat should be null
3. Background app → Check Firebase RTDB: /presence/{userId}/isOnline should be false
```

#### Test Suite 3: Notifications
```
1. With app OPEN and viewing chat → Send message → Should NOT show notification
2. With app OPEN but NOT viewing chat → Send message → Should show notification
3. With app in BACKGROUND → Send message → Should show notification within 5s
4. With app KILLED → Send message → Should show notification within 5s
5. Device in DOZE mode → Send message → Notification should wake device
```

---

## Rollback Plan

If issues occur after deployment:

### Immediate Rollback (< 5 minutes)
1. Revert `ChatActivity.kt`:
   ```kotlin
   // In onDestroy(), add back:
   PresenceManager.cleanup()
   ```
2. Revert `PresenceManager.kt` to previous version via Git
3. Rebuild and redeploy

### Partial Rollback (Keep some fixes)
If notifications work but presence doesn't:
1. Keep notification changes (MyFirebaseMessagingService.kt)
2. Keep NetworkStateReceiver
3. Revert only PresenceManager.kt and ChatActivity.kt changes

---

## Configuration Required

### Firebase Realtime Database Rules
```json
{
  "rules": {
    "presence": {
      "$uid": {
        ".read": true,
        ".write": "$uid === auth.uid"
      }
    },
    "pending_messages": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": true
      }
    }
  }
}
```

### Firebase Cloud Functions (Optional but Recommended)
Deploy the stale presence cleanup function:
```javascript
exports.cleanupStalePresence = functions.pubsub
  .schedule('every 5 minutes')
  .onRun(async (context) => {
    // See functions/index.js for full implementation
  });
```

---

## Performance Monitoring

### Metrics to Track
1. **Heartbeat Success Rate**: Should be > 99%
   - Check: Firebase Analytics custom event "heartbeat_sent"
   
2. **Notification Delivery Time**: Should be < 5s at 95th percentile
   - Check: Firebase Analytics custom event "notification_received"
   
3. **False Offline Rate**: Should be < 1%
   - Check: Compare presence state with actual app usage

4. **Presence Update Latency**: Should be < 2s
   - Check: Time between `goOnline()` call and RTDB update

### Logging
All presence operations log to Logcat with tag `PresenceManager`:
```
adb logcat -s PresenceManager
```

All notification operations log with tag `MyFirebaseMsgService`:
```
adb logcat -s MyFirebaseMsgService
```

---

## Troubleshooting

### Issue: User shows offline when they're online
**Possible Causes:**
1. Heartbeat job not running → Check if `isAppInForeground` is true
2. Network issue → Check Firebase RTDB connection state
3. RTDB rules blocking writes → Check Firebase Console

**Solution:**
```kotlin
// Add this debug log in PresenceManager.startHeartbeat()
Log.d(TAG, "Heartbeat job active: ${heartbeatJob?.isActive}, foreground: $isAppInForeground")
```

### Issue: User shows online when app is killed
**Possible Cause:** onDisconnect() handler didn't fire

**Solution:** This is expected for up to 60 seconds (heartbeat timeout). If longer, check:
1. Firebase RTDB connection state
2. onDisconnect() handler setup in `initialize()`

### Issue: Notifications not received when app is killed
**Possible Causes:**
1. Battery optimization blocking notifications
2. Missing WAKE_LOCK permission
3. FCM token not registered

**Solution:**
1. Check Settings → Apps → YourApp → Battery → Unrestricted
2. Verify `android.permission.WAKE_LOCK` in manifest
3. Check Firestore: `/users/{userId}/fcmToken` exists

---

## Success Criteria

✅ **Deployment is successful if:**
1. Heartbeats appear in Firebase RTDB every 30s for online users
2. Users go offline within 60s of killing app
3. Notifications arrive within 5s when app is killed
4. No crashes or ANRs related to presence or notifications
5. Battery drain < 1% additional per day

❌ **Rollback if:**
1. More than 5% of users experience false offline status
2. Notification delivery rate drops below 95%
3. Crash rate increases by more than 0.1%
4. Battery drain exceeds 2% additional per day

---

## Next Steps

1. **Phase 1** (Complete): Core presence and notification fixes
2. **Phase 2** (Future): Add typing indicator presence tracking
3. **Phase 3** (Future): Implement adaptive heartbeat (battery-aware)
4. **Phase 4** (Future): Add presence analytics and monitoring dashboard

---

## Support

If issues arise:
1. Check logs: `adb logcat -s PresenceManager,MyFirebaseMsgService`
2. Review Firebase Console for RTDB errors
3. Check Firebase Analytics for custom events
4. Consult `PRESENCE_AND_NOTIFICATION_FIX.md` for detailed documentation

---

**Last Updated:** December 14, 2025  
**Version:** 1.0.0  
**Status:** ✅ Ready for Production
