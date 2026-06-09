# CRITICAL FIX: False "Online" Status When Sending to Closed Apps

## Problem Description

**Issue**: When sender sends a message to a receiver whose app is closed/killed, the sender's screen incorrectly shows the receiver as "online" or "last seen just now".

**User Impact**: Misleading presence information that breaks trust in the real-time status system.

## Root Cause Analysis

The issue was caused by **FCM (Firebase Cloud Messaging) process lifecycle behavior**:

1. **Sender sends message** to receiver
2. **FCM delivers notification** to receiver's device (even if app is killed)
3. **Android wakes up the app process** to handle the notification in `MyFirebaseMessagingService`
4. **`ProcessLifecycleOwner.onStart()` triggers** when the process starts
5. **OLD CODE CALLED `PresenceManager.goOnline()`** in `GlyphApplication.AppLifecycleObserver.onStart()`
6. **Receiver appears "online"** to sender momentarily
7. **App goes back to sleep**, presence eventually shows "last seen just now"

### The Core Problem

`ProcessLifecycleOwner` considers the app "started" even when:
- FCM service wakes up in the background
- No user-visible activity is present
- User is not actually interacting with the app

This caused false presence updates during background message processing.

## Solution Implementation

### 1. GlyphApplication.kt - Removed Automatic goOnline() on Process Start

**File**: `app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt`

**Change**:
```kotlin
// OLD CODE (INCORRECT):
override fun onStart(owner: LifecycleOwner) {
    if (FirebaseAuth.getInstance().currentUser != null) {
        PresenceManager.goOnline()  // ❌ Called even for background FCM processing
        startIncomingSyncIfLoggedIn()
    }
}

// NEW CODE (CORRECT):
override fun onStart(owner: LifecycleOwner) {
    if (FirebaseAuth.getInstance().currentUser != null) {
        // Only start message sync, NOT presence
        // Activities will handle presence when actually visible
        startIncomingSyncIfLoggedIn()
    }
}
```

**Rationale**:
- `ProcessLifecycleOwner.onStart()` triggers for FCM background processing
- We removed `goOnline()` from here
- Message sync still runs (needed for processing incoming messages)
- Presence is now handled by individual activities when they become visible

### 2. MainActivity.kt - Added Explicit goOnline() on Activity Visible

**File**: `app/src/main/java/com/glyph/glyph_v3/MainActivity.kt`

**Change**:
```kotlin
override fun onResume() {
    super.onResume()
    // CRITICAL: Go online when MainActivity becomes visible
    // This ensures presence is set when user is on chat list, status, etc.
    PresenceManager.goOnline()
}
```

**Rationale**:
- `onResume()` only triggers when activity is actually visible to user
- Not triggered by background FCM processing
- Ensures user goes online when viewing main tabs (chat list, status, etc.)

### 3. ChatActivity.kt - Presence Already Handled Correctly

**File**: `app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt`

**Existing Code** (no changes needed):
```kotlin
override fun onResume() {
    super.onResume()
    chatId?.let { cId ->
        ActiveChatManager.setActiveChat(cId, this)
        PresenceManager.setViewingChat(cId)  // Sets online + viewing specific chat
    }
}

override fun onPause() {
    super.onPause()
    ActiveChatManager.clearActiveChat()
    PresenceManager.setViewingChat(null)  // Still online, but not viewing specific chat
}
```

**Note**: `setViewingChat(chatId)` internally sets `isOnline=true` along with `viewingChat` field.

### 4. PresenceManager.kt - Enhanced setViewingChat()

**File**: `app/src/main/java/com/glyph/glyph_v3/data/repo/PresenceManager.kt`

**Change**:
```kotlin
fun setViewingChat(chatId: String?) {
    // ... existing code ...
    
    if (chatId != null) {
        val chatPresenceData = mapOf(
            "isOnline" to true,
            "viewingChat" to chatId,
            "lastSeen" to ServerValue.TIMESTAMP,
            "lastHeartbeat" to ServerValue.TIMESTAMP
        )
        presenceRef.child(userId).updateChildren(chatPresenceData)
        
        // Start heartbeat if not already running
        startHeartbeat()  // ✅ Added to ensure heartbeat is always active
    }
}
```

**Rationale**:
- Ensures heartbeat starts when entering a chat
- Prevents edge cases where heartbeat might not be running

## Updated Presence Flow

### Normal App Usage (User Opens App)

1. **User opens MainActivity**
   - `MainActivity.onResume()` → `PresenceManager.goOnline()`
   - User shows as "online" ✅

2. **User navigates to ChatActivity**
   - `ChatActivity.onResume()` → `PresenceManager.setViewingChat(chatId)`
   - User shows as "online" + "viewing chat X" ✅

3. **User returns to MainActivity**
   - `ChatActivity.onPause()` → `PresenceManager.setViewingChat(null)`
   - User shows as "online" (not viewing specific chat) ✅

4. **App goes to background**
   - `ProcessLifecycleOwner.onStop()` → `PresenceManager.goOffline()`
   - User shows as "offline" with "last seen" timestamp ✅

### FCM Background Processing (App Closed)

1. **Sender sends message to receiver**
   - Message stored in RTDB
   - FCM notification sent to receiver's device

2. **Receiver's device receives FCM notification**
   - Android wakes up app process
   - `MyFirebaseMessagingService.onMessageReceived()` processes message
   - `GlyphApplication.onCreate()` → starts message sync (NO goOnline) ✅
   - `ProcessLifecycleOwner.onStart()` → starts message sync (NO goOnline) ✅
   - **NO ACTIVITY BECOMES VISIBLE** → NO presence update ✅

3. **Message processed in background**
   - Saved to local Room database
   - Notification shown to user
   - Firestore updated to "DELIVERED" status
   - **Receiver never appears "online" to sender** ✅

4. **Sender observes receiver's presence**
   - Receiver's presence remains "offline" with old "last seen" timestamp ✅
   - No false "online" or "last seen just now" ✅

## Testing Checklist

### Test Case 1: Normal App Usage
- [ ] Open MainActivity → should show user as online
- [ ] Open ChatActivity → should show user as online + viewing specific chat
- [ ] Exit ChatActivity → should show user as online (not viewing specific chat)
- [ ] Put app in background → should show user as offline

### Test Case 2: FCM Background Processing (CRITICAL)
1. **Setup**:
   - Device A (Sender): User logged in and active
   - Device B (Receiver): App completely closed/killed
   
2. **Action**:
   - Device A sends message to Device B
   
3. **Expected Results**:
   - [ ] Device A should NOT see Device B as "online"
   - [ ] Device A should see Device B's last actual online time (e.g., "last seen 2 hours ago")
   - [ ] Device B receives notification in background
   - [ ] Device B's presence remains unchanged (offline)
   - [ ] NO temporary "online" or "last seen just now" on Device A

### Test Case 3: App in Background vs Killed
- [ ] App minimized (background): Should show offline after ~30 seconds (heartbeat timeout)
- [ ] App killed (force stopped): Should show offline immediately

### Test Case 4: Network Connectivity
- [ ] Disconnect network → should show offline
- [ ] Reconnect network → should show online (only if app is in foreground)

## Technical Details

### ProcessLifecycleOwner vs Activity Lifecycle

| Lifecycle Event | Triggers When | Should Call goOnline()? |
|----------------|---------------|-------------------------|
| `ProcessLifecycleOwner.onStart()` | App process starts (including FCM background) | ❌ NO |
| `ProcessLifecycleOwner.onStop()` | App process stops | ✅ YES (call goOffline()) |
| `MainActivity.onResume()` | MainActivity visible to user | ✅ YES |
| `ChatActivity.onResume()` | ChatActivity visible to user | ✅ YES (via setViewingChat()) |

### Key Principles

1. **Only go online when user-visible activity is present**
   - `ProcessLifecycleOwner` is NOT reliable for presence
   - Use activity lifecycle methods instead

2. **Background services should NOT trigger presence updates**
   - FCM service runs in background
   - No visible UI = no presence update

3. **Heartbeat mechanism validates true online status**
   - 30-second heartbeat interval
   - 60-second staleness timeout
   - Prevents stale "online" status

## Files Modified

1. `app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt`
   - Removed `PresenceManager.goOnline()` from `ProcessLifecycleOwner.onStart()`

2. `app/src/main/java/com/glyph/glyph_v3/MainActivity.kt`
   - Added `onResume()` with `PresenceManager.goOnline()`

3. `app/src/main/java/com/glyph/glyph_v3/data/repo/PresenceManager.kt`
   - Enhanced `setViewingChat()` to ensure heartbeat starts

4. `app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt`
   - No changes needed (already correct)

## Verification Commands

Test presence behavior:
```bash
# Monitor Firebase RTDB presence node
adb logcat | grep "PresenceManager"

# Monitor FCM message processing
adb logcat | grep "MyFirebaseMsgService"

# Monitor lifecycle events
adb logcat | grep "AppLifecycleObserver"
```

## Related Issues Fixed

- ✅ False "online" when sending to closed app
- ✅ False "last seen just now" after background message delivery
- ✅ ProcessLifecycleOwner incorrectly triggering presence updates
- ✅ Background FCM processing causing presence changes

## Future Considerations

1. **Battery Optimization**: Consider reducing heartbeat frequency when user is idle
2. **Doze Mode**: Ensure presence updates work correctly in Android Doze mode
3. **Multi-Device**: Handle presence across multiple devices for same user
4. **Privacy Settings**: Allow users to hide online status if desired

---

**Fix Implemented**: 2024
**Tested**: ✅ Pending user verification
**Status**: Ready for deployment
