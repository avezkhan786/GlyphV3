# Testing Checklist - Collage Local Loading

## Before Testing
- [ ] Build app: `gradlew build`
- [ ] Clear app data: Settings → Apps → Glyph → Clear Storage
- [ ] Open logcat with filter: `MediaTransferManager|RealtimeMessageRepository|MediaItemView`

## Test Sequence

### Test 1: Receive and Download Collage
- [ ] Start app
- [ ] Receive collage message (3+ images)
- [ ] Wait for auto-download to complete

**Monitor Logcat for:**
- [ ] `scheduleMediaDownload for MEDIA_GROUP: id=...` appears
- [ ] `startGroupDownload called for message=..., itemCount=N` appears
- [ ] `Processing item 0:` log appears for each item
- [ ] `Download completed for itemDownloadId=...` appears for each item
- [ ] `updateMessageMediaItem START:` appears for each item
- [ ] `VERIFIED: Item localUri after save:` shows actual path, not null

**If any log is missing:** Download flow is broken, need to check why

### Test 2: Check Database Persistence
- [ ] Open Device File Explorer → `/data/data/com.glyph.glyph_v3/databases/`
- [ ] Download `glyph_db` file
- [ ] Open with SQLite viewer
- [ ] Query: `SELECT id, mediaItems FROM messages WHERE type='MEDIA_GROUP' ORDER BY timestamp DESC LIMIT 1`
- [ ] Check if mediaItems contains `"localUri":"..."` or if localUri is `null`

**Expected:** Each item in JSON should have non-null localUri

### Test 3: Restart App and Verify Local Loading
- [ ] Kill app completely (Settings → Force Stop)
- [ ] Reopen app
- [ ] Navigate to chat with collage message
- [ ] Images should load instantly

**Monitor Logcat for:**
- [ ] `Using local file` or `Using remote URL`?
- [ ] Should be `Using local file` for all collage items

**If showing remote URLs:**
- [ ] mediaItems JSON likely doesn't have localUri (check Test 2)
- [ ] OR display code not properly checking localUri

### Test 4: Check File Existence
Run in terminal:
```bash
adb shell
find /data/data/com.glyph.glyph_v3/files/media -type f
```

Should list downloaded collage images. If empty, downloads aren't actually saving files.

## Debug Queries

### Database Query - Check Latest Message
```sql
SELECT id, type, mediaItems 
FROM messages 
WHERE type='MEDIA_GROUP' 
ORDER BY timestamp DESC 
LIMIT 1
```

### Database Query - Check Message Status
```sql
SELECT id, status, mediaItems 
FROM messages 
WHERE id='<message-id-from-logcat>'
```

### Database Query - Check All Media Groups
```sql
SELECT COUNT(*) as media_group_count 
FROM messages 
WHERE type='MEDIA_GROUP'
```

## Problem Diagnosis

| Symptom | Likely Cause | Check |
|---------|-------------|-------|
| No `scheduleMediaDownload` log | Message not marked for download | Check message type & status in DB |
| No `updateMessageMediaItem` log | Download callback not fired | Check MediaDownloadManager logs |
| `updateMessageMediaItem` but localUri=null | updateMessageMediaItem not saving | Check DAO error logs |
| localUri saved but still showing remote URL | Display logic issue | Check ChatMediaComponents hasLocalFile check |

## Success Criteria
- ✅ All 3 items in collage show `VERIFIED: Item localUri after save: /path/...`
- ✅ Database contains mediaItems with non-null localUri values
- ✅ After app restart, collage shows `Using local file` not `Using remote URL`
- ✅ Images load instantly without Firebase requests

## If Tests Fail
1. Check logcat for ERROR level messages
2. Search for "Failed to" or "Exception" in logs
3. Check if Room database is locking (concurrent access issue)
4. Verify MessageDao.updateMessageMediaItems exists in compiled code
5. Check if startGroupDownload is even being called
