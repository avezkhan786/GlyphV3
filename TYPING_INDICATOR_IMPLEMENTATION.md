# Typing Indicator Implementation

## Overview
The typing indicator shows when the other user is typing in a chat, appearing in the chat list screen with animated dots.

## Implementation Details

### 1. Data Flow
```
ChatActivity (User types)
    ↓
PresenceManager.setTypingStatus()
    ↓
Firebase RTDB (/chats/{chatId}/typing/{userId})
    ↓
PresenceManager.observeTypingStatus()
    ↓
ChatListComposeFragment.startTypingObservation()
    ↓
Chat.isOtherUserTyping
    ↓
ChatListScreen (Shows TypingIndicator)
```

### 2. Key Components

#### ChatActivity.kt
- Monitors text input with `addTextChangedListener`
- Calls `PresenceManager.setTypingStatus(chatId, isTyping)` when typing state changes
- Auto-resets typing status after 3 seconds of inactivity
- Shows typing indicator in chat header when other user is typing

#### PresenceManager.kt
- `setTypingStatus(chatId, isTyping)`: Sets typing status in RTDB at `/chats/{chatId}/typing/{userId}`
- `observeTypingStatus(chatId, otherUserId)`: Returns Flow<Boolean> observing other user's typing status

#### ChatListComposeFragment.kt
- `typingStateFlow`: MutableStateFlow tracking typing status for all chats
- `startTypingObservation()`: Combines multiple typing status flows using `Flow.combine()`
- Maps typing status to chat using key: `"${chatId}_${otherUserId}"`
- Updates `Chat.isOtherUserTyping` field in real-time

#### Chat.kt (Model)
- Added field: `var isOtherUserTyping: Boolean = false`

#### ChatListScreen.kt (Compose UI)
- `ChatRow`: Conditionally shows `TypingIndicator()` when `chat.isOtherUserTyping` is true
- `TypingIndicator()`: Displays green italic "typing" text with 3 animated dots
  - Uses `rememberInfiniteTransition` for smooth animation
  - Alpha animation: 0.3f → 1.0f (600ms duration)
  - Staggered delay: 200ms per dot
  - RepeatMode.Reverse for pulsing effect

### 3. Animation Specs
```kotlin
animationSpec = infiniteRepeatable(
    animation = tween(
        durationMillis = 600,
        delayMillis = index * 200,  // 0ms, 200ms, 400ms
        easing = LinearEasing
    ),
    repeatMode = RepeatMode.Reverse
)
```

### 4. Firebase RTDB Structure
```
/chats
  /{chatId}
    /typing
      /{userId}: true/false
```

### 5. Features
- ✅ Real-time typing detection across all chats
- ✅ Auto-reset after 3 seconds of inactivity
- ✅ Smooth animated dots (WhatsApp-style)
- ✅ Green color matching app theme
- ✅ Replaces last message text when typing
- ✅ Works independently for each chat
- ✅ No performance impact (uses efficient Flow.combine)

### 6. Testing
1. Open chat on Device A
2. Start typing (don't send)
3. Check Device B chat list - should show "typing..." with animated dots
4. Stop typing on Device A
5. After 3 seconds, typing indicator disappears on Device B

## Notes
- Typing status is stored in RTDB (not Firestore) for real-time performance
- Typing indicator automatically hides status icons while active
- Uses same presence infrastructure for consistency
- Lifecycle-aware: automatically cancels observers when fragment is destroyed
