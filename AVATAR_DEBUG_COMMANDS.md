# Avatar Caching Diagnostic Commands

## Check if app is running
```bash
adb shell ps | findstr com.glyph.glyph_v3
```

## View all avatar caching logs (Real-time)
```bash
adb logcat -s AvatarCacheManager:* ChatActivity:* ProfilePreviewDialog:* ChatListAdapter:* RealtimeMessageRepo:*
```

## View recent avatar logs (Last 100 lines)
```bash
adb logcat -d -s AvatarCacheManager:V ChatActivity:D ProfilePreviewDialog:D | Select-Object -Last 100
```

## Check cache directory contents
```bash
adb shell run-as com.glyph.glyph_v3 ls -lh files/avatars/
```

## Count cached avatars
```bash
adb shell run-as com.glyph.glyph_v3 ls files/avatars/*.jpg | Measure-Object -Line
```

## Check cache directory size
```bash
adb shell run-as com.glyph.glyph_v3 du -sh files/avatars/
```

## View a specific cached avatar file
```bash
# Replace USER_ID with actual user ID
adb shell run-as com.glyph.glyph_v3 ls -lh files/avatars/USER_ID.jpg
```

## Pull cache directory to inspect locally
```bash
adb pull /data/data/com.glyph.glyph_v3/files/avatars/ ./avatars_debug/
```

## Clear app data (reset cache)
```bash
adb shell pm clear com.glyph.glyph_v3
```

## Watch logs in real-time with filtering
```bash
adb logcat | Select-String -Pattern "Avatar|Profile|Chat" -Context 0,2
```

## Export logs to file
```bash
adb logcat -d > avatar_debug_logs.txt
```

## Common Log Patterns to Look For

### Successful Cache
```
AvatarCacheManager: ✓ Successfully cached avatar for user abc123 (45123 bytes)
ChatActivity: Loading from cache: /data/user/0/.../files/avatars/abc123.jpg
```

### Cache Miss (First Load)
```
AvatarCacheManager: Cache MISS for user abc123
ChatActivity: Loading from URL: https://...
AvatarCacheManager: Attempting to cache avatar for user abc123
```

### Initialization Success
```
AvatarCacheManager: ✓ AvatarCacheManager initialized - Directory: .../files/avatars, Writable: true
```

### Common Errors

#### Not Initialized
```
AvatarCacheManager: getLocalAvatarPath called before init()
```
**Fix**: Ensure `AvatarCacheManager.init(this)` is called in `GlyphApplication.onCreate()`

#### Download Failed
```
AvatarCacheManager: ✗ Failed to download avatar for user abc123 - Glide returned null bitmap
```
**Fix**: Check network connectivity, verify URL is valid

#### Permission Denied
```
AvatarCacheManager: ✗ Error caching avatar: Permission denied
```
**Fix**: Check app has storage permissions, verify directory is writable

#### Wrong User ID
```
ChatActivity: Loading avatar for null - URL: https://...
```
**Fix**: Ensure otherUserId is correctly extracted from chat participants

## Quick Diagnostics

### 1. Check if cache is working
Run app, open a chat with an avatar, then run:
```bash
adb logcat -d | Select-String "Successfully cached avatar"
```
Should see: `✓ Successfully cached avatar for user...`

### 2. Verify cache directory exists
```bash
adb shell run-as com.glyph.glyph_v3 test -d files/avatars && echo "EXISTS" || echo "MISSING"
```

### 3. Check if Glide is downloading
```bash
adb logcat -d -s Glide:*
```

### 4. Monitor live avatar operations
```bash
adb logcat -c  # Clear logs first
# Open app, navigate to chat list
adb logcat -s AvatarCacheManager:*
```

## Step-by-Step Debugging

### Problem: Avatars not appearing in chat list

1. **Check initialization**
```bash
adb logcat -d | Select-String "AvatarCacheManager initialized"
```
Should see initialization log on app start.

2. **Check user IDs**
```bash
adb logcat -d | Select-String "Loading avatar for"
```
Verify user IDs are not null or empty.

3. **Check URLs**
```bash
adb logcat -d | Select-String "avatarUrl"
```
Verify URLs are valid and not empty.

4. **Check cache directory**
```bash
adb shell run-as com.glyph.glyph_v3 ls files/avatars/
```
Should see .jpg files if caching is working.

### Problem: Avatars not updating after profile change

1. **Check update logic**
```bash
adb logcat -d | Select-String "Update check"
```
Should see: `Update check for user X: needsUpdate=true`

2. **Check metadata**
```bash
adb shell run-as com.glyph.glyph_v3 cat files/avatars/USER_ID.meta
```
Should show MD5 hash of URL.

3. **Force re-download**
```bash
adb shell run-as com.glyph.glyph_v3 rm files/avatars/USER_ID.jpg
adb shell run-as com.glyph.glyph_v3 rm files/avatars/USER_ID.meta
```
Then open the app again.

## Performance Monitoring

### Measure cache hit rate
```bash
$hits = (adb logcat -d | Select-String "Cache HIT").Count
$misses = (adb logcat -d | Select-String "Cache MISS").Count
$total = $hits + $misses
if ($total -gt 0) { 
    $rate = [math]::Round(($hits / $total) * 100, 2)
    Write-Host "Cache Hit Rate: $rate% ($hits hits, $misses misses)"
}
```

### Check avatar load times
```bash
adb logcat -d | Select-String "Avatar loaded in"
```

## Emergency Reset

If things are completely broken:

1. **Clear app data**
```bash
adb shell pm clear com.glyph.glyph_v3
```

2. **Reinstall app**
```bash
adb uninstall com.glyph.glyph_v3
# Then rebuild and install from Android Studio
```

3. **Check Android Studio Logcat**
Open Android Studio > Logcat > Filter by "AvatarCache"
