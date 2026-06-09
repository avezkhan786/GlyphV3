# System Architecture Diagrams

## Presence System Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        APP LIFECYCLE                             │
└─────────────────────────────────────────────────────────────────┘

┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ App Launches │───>│   Activity   │───>│ App Killed / │
│              │    │   Resumes    │    │  Backgrounds │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       │ initialize()      │ setViewingChat()  │ goOffline()
       │                   │                   │
       v                   v                   v
┌─────────────────────────────────────────────────────────────────┐
│                    PRESENCE MANAGER                              │
│                                                                   │
│  State:                                                          │
│  • isAppInForeground: Boolean                                   │
│  • activeChatId: String?                                        │
│  • heartbeatJob: Job?                                           │
│                                                                   │
│  Operations:                                                     │
│  • startHeartbeat() ────> Every 30s ────> Update lastHeartbeat │
│  • stopHeartbeat()                                              │
│  • setViewingChat(chatId)                                       │
└─────────────────────────────────────────────────────────────────┘
       │                   │                   │
       │ Write             │ Write             │ Write
       │                   │                   │
       v                   v                   v
┌─────────────────────────────────────────────────────────────────┐
│                  FIREBASE REALTIME DATABASE                      │
│                                                                   │
│  /presence/{userId}:                                            │
│  {                                                               │
│    "isOnline": true,                                            │
│    "lastSeen": 1702573200000,                                   │
│    "lastHeartbeat": 1702573230000,  ← Updated every 30s        │
│    "viewingChat": "chat_id" or null                             │
│  }                                                               │
│                                                                   │
│  onDisconnect() Handler:                                        │
│  → Sets isOnline=false when connection lost                     │
└─────────────────────────────────────────────────────────────────┘
       │
       │ Real-time updates
       │
       v
┌─────────────────────────────────────────────────────────────────┐
│                    OTHER USERS' DEVICES                          │
│                                                                   │
│  observeUserPresence(userId)                                    │
│  • Receives real-time updates                                   │
│  • Checks heartbeat age                                         │
│  • Shows "Online" or "Last seen X ago"                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Notification Delivery Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    MESSAGE SENDING FLOW                          │
└─────────────────────────────────────────────────────────────────┘

User A sends message
       │
       v
┌──────────────────────────────────────────────────────┐
│   RealtimeMessageRepository.sendMessage()           │
│                                                      │
│   1. Save to local DB (status: SENDING)            │
│   2. Write to RTDB: /pending_messages/{userB_id}/   │
│   3. Update local DB (status: SENT)                │
└──────────────────────────────────────────────────────┘
       │
       │ Triggers Cloud Function
       │
       v
┌─────────────────────────────────────────────────────────────────┐
│              FIREBASE CLOUD FUNCTION                             │
│                                                                   │
│  exports.sendChatNotificationRTDB                               │
│                                                                   │
│  1. Read message data from RTDB                                 │
│  2. Get recipient FCM token from Firestore                      │
│  3. Get sender info (name, avatar)                              │
│  4. Construct FCM payload (data-only, HIGH priority)            │
│  5. Send via admin.messaging().send()                           │
└─────────────────────────────────────────────────────────────────┘
       │
       │ FCM delivery
       │
       v
┌─────────────────────────────────────────────────────────────────┐
│            USER B's DEVICE (ANY STATE)                           │
│                                                                   │
│  State: Foreground / Background / Killed / Doze                │
│                                                                   │
│  MyFirebaseMessagingService.onMessageReceived()                │
│  ├─> Check if viewing this chat                                │
│  ├─> If yes: Skip notification, mark as read                   │
│  └─> If no: Show notification (MAX priority + WAKE_LOCK)       │
└─────────────────────────────────────────────────────────────────┘
       │
       │ User taps notification
       │
       v
┌──────────────────────────────────────────────────────┐
│   MainActivity opens                                │
│   → Navigates to ChatActivity                       │
│   → ActiveChatManager.setActiveChat(chatId)         │
│   → PresenceManager.setViewingChat(chatId)          │
│   → Notification dismissed                          │
└──────────────────────────────────────────────────────┘
```

---

## Heartbeat & Staleness Detection

```
Time ────────────────────────────────────────────────>

T=0s     T=30s    T=60s    T=90s    T=120s   T=180s
│         │         │         │         │         │
│         │         │         │         │         │
App       Heartbeat Heartbeat Heartbeat App      60s elapsed
Opens     Sent      Sent      Sent      Killed   since last
│         │         │         │         │        heartbeat
│         │         │         │         │         │
v         v         v         v         v         v

Firebase RTDB:
─────────────────────────────────────────────────────
isOnline:      true     true     true     true     true → false
lastHeartbeat: T=0s     T=30s    T=60s    T=90s    T=90s  T=90s
                                                           ↑
                                            onDisconnect() fires
                                            OR
                                            Client detects stale
                                            (now - lastHeartbeat > 60s)

Other Users See:
─────────────────────────────────────────────────────
Online ───────────────────────────────────> Offline
        (Green indicator)                   (Gray, show lastSeen)
```

---

## Network Recovery Scenario

```
┌─────────────────────────────────────────────────────────────────┐
│                 NETWORK DISCONNECT → RECONNECT                   │
└─────────────────────────────────────────────────────────────────┘

T=0s: App Running, WiFi ON
      └─> isOnline=true, heartbeat active

T=10s: WiFi OFF (user turns off)
       └─> Firebase RTDB detects disconnect
       └─> onDisconnect() handler fires
       └─> Sets isOnline=false in RTDB
       └─> Heartbeat stops (no network)

T=15s: Other users see: "Offline" or "Last seen 5 seconds ago"

T=30s: WiFi ON (user turns back on)
       └─> NetworkStateReceiver.onReceive() triggered
       └─> Waits 1 second for network to stabilize
       
T=31s: NetworkStateReceiver calls PresenceManager.goOnline()
       └─> Sets isOnline=true in RTDB
       └─> Restarts heartbeat job
       └─> Other users see: "Online" within 2 seconds

Result: Auto-recovery without user intervention ✅
```

---

## Notification Priority Comparison

```
┌─────────────────────────────────────────────────────────────────┐
│              NOTIFICATION PRIORITY LEVELS                        │
└─────────────────────────────────────────────────────────────────┘

PRIORITY_MIN (-2)
├─> No sound, vibration, or peek
├─> Hidden by default
└─> ❌ Never use for chat apps

PRIORITY_LOW (-1)
├─> No sound or vibration
├─> Appears in status bar
└─> ❌ Not suitable for messages

PRIORITY_DEFAULT (0)
├─> Default sound
├─> Appears in status bar
└─> ❌ May not wake device from Doze

PRIORITY_HIGH (1)              ← OLD IMPLEMENTATION
├─> Sound + vibration
├─> Peek notification
└─> ⚠️  May miss during Doze mode

PRIORITY_MAX (2)               ← NEW IMPLEMENTATION ✅
├─> Sound + vibration + lights
├─> Full-screen intent option
├─> Wakes device from Doze mode
└─> ✅ Best for time-sensitive messaging

With WAKE_LOCK permission:
├─> Device CPU stays awake
├─> Notification processed immediately
└─> ✅ Guaranteed delivery even when killed
```

---

## State Machine Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│               PRESENCE STATE MACHINE                             │
└─────────────────────────────────────────────────────────────────┘

                    ┌──────────────┐
                    │   OFFLINE    │
                    │  (isOnline=  │
                    │    false)    │
                    └──────┬───────┘
                           │
                           │ App Launch
                           │ initialize()
                           │
                           v
    ┌──────────────────────────────────────────┐
    │          ONLINE (Idle)                   │
    │        (isOnline=true)                   │
    │    (viewingChat=null)                    │
    │    Heartbeat: Active                     │
    └─────┬────────────────────────────┬───────┘
          │                            │
          │ Open ChatActivity          │ App Background
          │ setViewingChat(id)         │ goOffline()
          │                            │
          v                            v
    ┌─────────────────────┐      ┌─────────────┐
    │  ONLINE (In Chat)   │      │   OFFLINE   │
    │  (isOnline=true)    │      │             │
    │  (viewingChat=id)   │      └─────────────┘
    │  Heartbeat: Active  │            │
    └─────┬───────────────┘            │
          │                            │
          │ Leave ChatActivity         │ App Foreground
          │ setViewingChat(null)       │ goOnline()
          │                            │
          └────────────────────────────┘

Special Transitions:
• Network Lost → Heartbeat Stops → Stale after 60s
• Network Restored → Auto goOnline() → Heartbeat Restarts
• App Killed → onDisconnect() → Immediate Offline
• App Crashed → onDisconnect() → Immediate Offline
```

---

## Data Flow Visualization

```
┌─────────────────────────────────────────────────────────────────┐
│           DATA PERSISTENCE & SYNC LAYERS                         │
└─────────────────────────────────────────────────────────────────┘

User Action (Send Message)
       │
       v
┌─────────────────────────┐
│   UI Layer (Activity)   │
│   • ChatActivity        │
└────────┬────────────────┘
         │
         v
┌─────────────────────────┐
│   Repository Layer      │
│   • RealtimeMessageRepo │
└────┬─────────────┬──────┘
     │             │
     │             └──────────────────────┐
     v                                    v
┌────────────────┐              ┌─────────────────┐
│  Local Cache   │              │  Remote Cloud   │
│  (Room DB)     │              │  (Firebase)     │
│                │              │                 │
│  INSTANT       │              │  RTDB: Real-time│
│  • Messages    │◄─sync────────│  • Presence     │
│  • Chats       │              │  • Pending Msgs │
│  • Status      │              │                 │
└────────────────┘              │  Firestore:     │
     │                          │  • User data    │
     │                          │  • Chat history │
     │                          │  • FCM tokens   │
     │                          └─────────────────┘
     │                                    │
     └────────> Merged for UI <──────────┘
                      │
                      v
              ┌─────────────┐
              │  UI Update  │
              │  (LiveData) │
              └─────────────┘
```

---

## Error Recovery Paths

```
┌─────────────────────────────────────────────────────────────────┐
│                   ERROR SCENARIOS & RECOVERY                     │
└─────────────────────────────────────────────────────────────────┘

Scenario 1: Heartbeat Fails
───────────────────────────────
Error: Network timeout during heartbeat
       │
       v
Detected: Exception in heartbeat coroutine
       │
       v
Recovery: Next heartbeat (30s later) retries
       │
       v
Result: Automatic recovery, no user action needed

Scenario 2: Firebase Connection Lost
────────────────────────────────────
Error: RTDB connection drops
       │
       v
Detected: .info/connected = false
       │
       v
Recovery: 
  1. Stop heartbeat
  2. onDisconnect() sets offline
  3. NetworkStateReceiver monitors for reconnect
       │
       v
Result: Auto-reconnect when network restored

Scenario 3: Notification Not Delivered
──────────────────────────────────────
Error: FCM send fails
       │
       v
Detected: Cloud function catch block
       │
       v
Recovery:
  1. Log error
  2. Message stays in pending_messages
  3. Client polls on next app open
       │
       v
Result: Message delivered when app opened

Scenario 4: Stale Presence Data
───────────────────────────────
Error: Client shows online but heartbeat old
       │
       v
Detected: 
  - Client: (now - lastHeartbeat) > 60s
  - Server: Cloud function checks every 5 min
       │
       v
Recovery:
  - Client: Shows as offline in UI
  - Server: Updates RTDB to offline
       │
       v
Result: False positive prevented
```

---

**These diagrams are for reference during development and debugging**

*Print or save for easy access during troubleshooting sessions*
