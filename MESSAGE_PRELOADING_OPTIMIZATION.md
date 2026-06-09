# Message Preloading Optimization

## Problem
On mid-range devices, messages were appearing with a slight delay after opening the chat screen. This "pop-in" effect made the app feel less fluid compared to the instant wallpaper loading.

## Solution
Implemented a two-tier preloading strategy in `ChatViewModel` and `RealtimeMessageRepository`:

### 1. In-Memory Caching (`MessageCacheManager`)
- **What:** A static `ConcurrentHashMap` that stores processed `ChatListItem`s for recently opened chats.
- **How:** 
    - When `ChatViewModel` processes messages, it saves the result to `MessageCacheManager`.
    - When `ChatViewModel` initializes, it checks this cache first.
    - If data exists, it updates the UI state **immediately** (synchronously), resulting in zero-latency display.

### 2. Fast Database Fallback (`getRecentMessages`)
- **What:** If the memory cache is empty (e.g., first app launch), we perform a quick one-shot query to the local Room database.
- **How:**
    - `RealtimeMessageRepository.getRecentMessages(chatId, 50)` fetches the last 50 messages.
    - This is much faster than waiting for the `Flow` to initialize and emit.
    - These messages are displayed while the real-time `Flow` is connecting.

## Implementation Details

### `MessageCacheManager.kt`
New utility class to handle the in-memory cache.

```kotlin
object MessageCacheManager {
    private val cache = ConcurrentHashMap<String, List<ChatListItem>>()
    // ... methods to get/put/clear
}
```

### `ChatViewModel.kt`
Updated `loadMessages()` to use the cache and fallback.

```kotlin
viewModelScope.launch {
    // 1. Try Cache (Instant)
    val cachedMessages = MessageCacheManager.getMessages(chatId)
    if (cachedMessages != null) {
         _uiState.update { it.copy(messages = cachedMessages) }
    } else {
         // 2. Try DB Snapshot (Fast)
         val recent = repository.getRecentMessages(chatId, 50)
         if (recent.isNotEmpty()) {
             // Process and update UI
         }
    }

    // 3. Start Real-time Flow (Persistent)
    repository.getMessages(chatId).collectLatest { ... }
}
```

## Benefits
- **Instant Feedback:** Users see messages immediately upon opening the chat.
- **Smoother Transition:** Eliminates the blank screen -> messages pop-in transition.
- **Reduced Jank:** Offloads initial processing from the critical path of the Flow collection.

## Further Optimizations
- **Pre-fetching:** We could call `MessageCacheManager.putMessages` from the Chat List screen for the top 3-5 chats to ensure they are ready even before the user taps them.
- **Memory Management:** The cache currently grows indefinitely. We might want to limit it to the last 5-10 accessed chats if memory becomes an issue.
