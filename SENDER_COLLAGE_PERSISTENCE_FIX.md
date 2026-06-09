# Sender-Side Collage Image Persistence Fix

## Issue
Multi-media (collage) images sent by the current user were not displaying after app restart. The images would only show on the receiver side after download, but the sender's copy was lost after restart.

## Root Cause
When sending a collage, the compressed media files were saved to the app's cache directory (`context.cacheDir`). Cache files can be cleared by the system at any time, and Android specifically clears them on app uninstall or explicit cache clearing. After app restart, these cache files no longer exist, so the sender-side display fails.

**Flow that was broken:**
```
1. User selects 3 images to send
2. Images compressed to cache: /cache/img1.jpg, /cache/img2.jpg, /cache/img3.jpg
3. mediaItems stored with localUri pointing to cache paths
4. User sees images momentarily while message uploads
5. App is closed/killed
6. System clears cache on restart (or user cleared cache)
7. App reopens → mediaItems point to deleted cache files → blank collage
```

## Solution
Persist sent media items to the app's persistent media storage immediately after upload completes, before saving to database. This ensures the sender always has a copy to display.

**Fixed Flow:**
```
1. User selects 3 images to send
2. Images compressed and uploaded to Firebase
3. New items created with Firebase URLs and cache localUri
4. BEFORE saving to DB: Copy compressed files from cache to persistent storage
5. Update localUri in mediaItems to point to persistent location
6. Save updated mediaItems with persistent paths to database
7. App restart → localUri points to persistent storage → images load correctly
```

## Changes Made

### File: [RealtimeMessageRepository.kt](app/src/main/java/com/glyph/glyph_v3/data/repo/RealtimeMessageRepository.kt)

#### 1. Added Initial Upload Logging (Lines ~468-469)
**Purpose:** Track when group media sending starts
```kotlin
Log.d(TAG, "sendGroupedMediaMessage START: messageId=$messageId, itemCount=${uris.size}")
Log.d(TAG, "Saving initial MEDIA_GROUP message: $messageId with ${mediaItems.size} items")
```

#### 2. Added Media Persistence Logic (Lines ~594-633)
**Purpose:** After upload completes but before database save, persist media to storage

**What it does:**
- For each uploaded media item:
  - Extract the file from the compressed cache location
  - Call `MediaStorageManager.saveMediaFromUri()` to copy it to persistent app storage
  - Update the item's `localUri` field to point to the persistent location
  - Log success or failure for each item
- If persistence fails for an item, keeps the cache reference as fallback
- Creates `persistedItems` list with updated localUri values
- Saves the `persistedItems` to database instead of original `uploadedItems`

**Key code block:**
```kotlin
val persistedItems = uploadedItems.mapIndexed { index, item ->
    try {
        // Extract compressed file from cache
        val itemUri = Uri.parse(item.localUri ?: return@mapIndexed item)
        val mediaType = if (item.type == MediaType.VIDEO) 
            MediaStorageManager.MediaType.VIDEO 
        else 
            MediaStorageManager.MediaType.IMAGE
        
        // Save to persistent storage
        val persistedFile = MediaStorageManager.saveMediaFromUri(
            context = context,
            chatId = chatId,
            messageId = messageId,
            mediaType = mediaType,
            sourceUri = itemUri
        )
        
        // Update localUri to persistent location
        if (persistedFile != null) {
            Log.d(TAG, "Persisted sent media item: ${item.url} to ${persistedFile.absolutePath}")
            item.copy(localUri = persistedFile.absolutePath)
        } else {
            Log.w(TAG, "Failed to persist media item, keeping cache reference")
            item
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error persisting media item $index for sent message", e)
        item
    }
}
```

#### 3. Added Final Update Logging (Line ~635)
**Purpose:** Confirm when updated message is saved to database
```kotlin
Log.d(TAG, "Updating sent MEDIA_GROUP message with persisted items: $messageId")
```

## Expected Logcat Output

When sending a 3-image collage:
```
D/RealtimeMessageRepository: sendGroupedMediaMessage START: messageId=msg-123, itemCount=3
D/RealtimeMessageRepository: Saving initial MEDIA_GROUP message: msg-123 with 3 items
D/RealtimeMessageRepository: Persisted sent media item: https://firebasestorage.googleapis.com/... to /data/data/com.glyph.glyph_v3/files/media/chat-456/msg-123/item1.jpg
D/RealtimeMessageRepository: Persisted sent media item: https://firebasestorage.googleapis.com/... to /data/data/com.glyph.glyph_v3/files/media/chat-456/msg-123/item2.jpg
D/RealtimeMessageRepository: Persisted sent media item: https://firebasestorage.googleapis.com/... to /data/data/com.glyph.glyph_v3/files/media/chat-456/msg-123/item3.jpg
D/RealtimeMessageRepository: Updating sent MEDIA_GROUP message with persisted items: msg-123
```

## Testing Instructions

### Test 1: Send Collage and Verify Display
1. Open app
2. Send a collage with 3+ images
3. Wait for message to complete sending
4. Collage should display in chat with all images visible
5. Check logcat for persistence logs (should see "Persisted sent media item" 3 times)

**Success Criteria:** All images display, logcat shows persistence logs

### Test 2: App Restart - Sender Side
1. Send collage and wait for completion
2. Kill app process completely
3. Reopen app
4. Navigate to the chat with the sent collage
5. **Collage images should load instantly, not be blank**

**Success Criteria:** Sender sees all images after restart without requiring Firebase download

### Test 3: Database Verification
1. Download app database using Device File Explorer
2. Open in SQLite viewer
3. Query: `SELECT mediaItems FROM messages WHERE type='MEDIA_GROUP' AND isIncoming=0 LIMIT 1`
4. Inspect mediaItems JSON for each item

**Expected mediaItems structure:**
```json
[
  {
    "url": "https://firebasestorage.googleapis.com/...",
    "localUri": "/data/data/com.glyph.glyph_v3/files/media/chat-abc/msg-123/item1.jpg",
    "type": "IMAGE",
    "fileSize": 45678,
    "width": 1080,
    "height": 1920
  },
  ...
]
```

**Success Criteria:** localUri points to persistent app storage directory (`/data/data/com.glyph.glyph_v3/files/`), not cache

### Test 4: Both Sender and Receiver After Restart
1. Send collage
2. Receiver sees images (auto-downloads them)
3. Kill app on BOTH devices
4. Reopen app on BOTH
5. **Both should see images from local storage**

**Success Criteria:** Neither device requests Firebase URLs after restart

## Fallback Behavior
If media persistence fails for any item:
- The code logs a warning: "Failed to persist media item, keeping cache reference"
- The item retains its cache localUri
- If cache still exists, the image can display
- If cache is gone, the item will fall back to Firebase remote URL
- This prevents the sending from failing completely while still attempting persistence

## Storage Paths
Sent media items are persisted to:
```
/data/data/com.glyph.glyph_v3/files/media/{chatId}/{messageId}/
```

This directory:
- Survives app restarts
- Survives system cache clearing
- Is part of the app's app-specific external files directory
- Is deleted only when the app is uninstalled

## Related Code

| Component | Purpose |
|-----------|---------|
| MediaStorageManager.saveMediaFromUri() | Copies file from source URI to persistent storage |
| Message.mediaItemsToJson() | Serializes MediaItem list to JSON |
| MessageDao.insertMessage() | Saves LocalMessage with mediaItems to database |
| MediaItem.copy(localUri=...) | Creates updated MediaItem with new localUri |

## Compatibility
- ✅ Works with existing receiver-side download logic
- ✅ Works with existing merge logic in syncMessages
- ✅ Works with existing ACK tracking system
- ✅ Graceful fallback if persistence fails
- ✅ No breaking changes to API or database schema
