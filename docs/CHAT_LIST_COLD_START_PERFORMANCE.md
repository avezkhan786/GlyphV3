# Chat List Cold-Start Performance — Optimization Reference

> **Purpose:** This document explains the staged-rendering problem that affected the
> chat list on cold launch, the root causes identified via on-device tracing, and
> every optimization applied. If the chat list ever regresses to "shimmer →
> rows popping in → avatars flashing → list jerking," start here.

---

## 1. Original Symptoms

On a cold app launch the chat list rendered in visible stages:

1. Top App Bar + Bottom Navigation appear.
2. After a pause (~1.5–2 s), shimmer placeholders (8 pulsing rows) appear.
3. After another brief pause, real chat rows replace the shimmer.
4. After yet another pause, profile pictures (avatars) pop in — some with a
   white flash.
5. The "Glyph Official" chat row appears late and prepends at index 0, shifting
   every other row down (a visible jerk).
6. The list jerks up/down several times in the first 2 seconds as redundant
   StateFlow emissions trigger recompositions.

The desired behaviour: the complete screen (toolbar, chat rows, avatars, unread
badges, official row) renders as one cohesive frame, like WhatsApp or Telegram.

---

## 2. Architecture Overview

The chat list is rendered by **Jetpack Compose**:

```
MainActivity
 └─ ViewPager2 (offscreenPageLimit=1)
     └─ ChatListComposeFragment (position 0)
         ├─ ChatListViewModel
         │   └─ ChatListUiState (StateFlow)
         │       ├─ chats: List<Chat>
         │       ├─ isInitialLoading: Boolean
         │       └─ archivedChatsCount / lockedChatsCount
         └─ ChatListScreen (Composable)
             └─ LazyColumn(key = { it.id })
                 ├─ AiAgentRow (pinned Glyph AI)
                 ├─ items(filteredChats) → ChatRow
                 │   └─ Avatar (AsyncImage via Coil)
                 └─ HiddenChatsSections (archive / locked)
```

The XML `ChatListFragment` + `ChatListAdapter*` files are **dead code** — never
instantiated by any Activity or adapter. All performance work targets the
Compose path.

### Data pipeline (Room-first, Firebase-enriching)

```
Application.onCreate()
  ├─ ensureCoilInitialized()          // optimized Coil singleton
  ├─ getOrCreateAppDatabase()         // synchronous Room open
  └─ ensureSharedRepositoryStartup(warmStartupChats=true)
       └─ [IO coroutine]
            ├─ warmStartupChatSnapshots()
            │    ├─ getTopActiveChats(20) → Room
            │    ├─ ChatListViewModel.prewarmCache("main", uiChats)
            │    ├─ AvatarCacheManager.preloadAvatars() → Coil memory cache
            │    └─ AvatarCacheManager.preloadGroupAvatars() → Coil memory cache
            ├─ startIncomingSyncIfLoggedIn()
            ├─ startGlobalDeliveryReceiptSync()
            └─ startGroupMetadataSync()

Fragment.onViewCreated()
  └─ startChatListData()
       ├─ seedInitialChats()          // one-shot Room query (fallback)
       ├─ loadLocalChatsWithPresence() // reactive combine flow
       └─ repository.startIncomingMessageSync() // RTDB listener
```

---

## 3. Problem 1 — Prewarm Pipeline Was Disabled

### Root cause

The codebase already contained a well-designed cold-start prewarm pipeline, but
**two critical pieces were dead code with zero call sites:**

1. **`warmStartupChatSnapshots()` never ran.** Every call site of
   `GlyphApplication.ensureSharedRepositoryStartup()` passed the default
   `warmStartupChats = false`. This is the function that (a) pre-seeds the
   `ChatListViewModel` process-level cache so `isInitialLoading` starts as
   `false` (no shimmer), and (b) enqueues the top-20 avatars into Coil's memory
   cache so they render on the first frame (no flash).

   **Verification:** `grep -n "ensureSharedRepositoryStartup"` across the
   codebase — all 5 call sites used the default `warmStartupChats = false`.

2. **`ensureCoilInitialized()` never ran.** The optimized `ImageLoader` (35%
   heap memory cache, strong references, 500 MB disk cache, crossfade off) was
   defined in `GlyphApplication.kt:508` but never called. Coil's `AsyncImage`
   fell back to the library's default, weaker configuration.

   **Verification:** `grep -rn "ensureCoilInitialized" app/src/main` — only the
   function definition, zero call sites.

### Fix

- **`GlyphApplication.onCreate`**: call `ensureCoilInitialized()` early (after
  `configureFirestoreCache()`), before any prewarm coroutine enqueues avatars.
  This installs the optimized singleton into `Coil.setImageLoader()` so the
  prewarm and `AsyncImage` share one `ImageLoader` and one memory cache.
- **`GlyphApplication.onCreate`**: add
  `ensureSharedRepositoryStartup(reason = "app_onCreate", warmStartupChats = true)`.
  This starts the prewarm on a background coroutine, overlapping with the splash
  screen.
- **`ChatListComposeFragment.onCreate`**: flip the existing call to
  `warmStartupChats = true` (harmless — guarded by `sharedRepositoryStartupComplete`).

### Before / After

| Metric | Before | After |
|---|---|---|
| Prewarm cache populated | **never** | **~150 ms** (well before ~1990 ms first frame) |
| `isInitialLoading` at first frame | `true` → shimmer | `false` → real rows |
| Avatars in Coil cache at first frame | none (post-seed preload runs at ~3000 ms) | 4 1:1 + 4 group = 8 enqueued at ~150 ms |

---

## 4. Problem 2 — Prewarm Was Sequenced After a Blocking Firestore Fetch

### Root cause

`completeSharedRepositoryStartupAsync` ran the prewarm (`warmStartupChatSnapshots`)
**after** `PrivacySettingsRepository.warmCacheIfNeeded()`. The latter is a
`suspend` function that does a Firestore `get()`. In the test environment,
Firestore was **unreachable** (`UnknownHostException`), so `warmCacheIfNeeded()`
hung (retrying) for ~3 seconds before timing out. The prewarm, sequenced after
it, ran at **~3090 ms** — long after the first frame at ~1900 ms.

### Fix

Reordered `completeSharedRepositoryStartupAsync` so the prewarm runs
**first** (right after `repository_ready`), before any Firestore call. Each
subsequent Firestore sync is wrapped in `runCatching` so a failure in one
doesn't skip the others. The prewarm only needs the local Room DB, so it
completes at ~150 ms regardless of network state.

### Before / After

| Metric | Before | After |
|---|---|---|
| `startup_chat_prefetch_scheduled` log timestamp | ~3090 ms | ~150 ms |
| First frame has prewarmed avatars | No | Yes |

---

## 5. Problem 3 — Official "Glyph Official" Row Appeared Late

### Root cause

`OfficialContentRepository.officialMessages` was a `MutableStateFlow(emptyList())`
populated **only** by a Firestore snapshot listener. The messages themselves
were never cached locally (only `seenIds` and `lastOpenedAt` were persisted).
On cold start the official row was absent from the first frame; when Firestore
eventually responded, `buildChatListWithOfficial` prepended the row at index 0
→ **every other chat shifted down** (the readjust/jerk).

### Fix

- **Local cache via SharedPreferences + Gson**: on `startListening()`, load
  cached messages from prefs and seed `_officialMessages` before the Firestore
  listener starts → official row present on the first frame on every cold start
  after the first sync.
- **Persist on every Firestore update**: each emission writes the messages back
  to the cache key, so the next cold start is instant.
- No readjust: the official row uses key `OFFICIAL_USER_ID`. Going from cache →
  Firestore update keeps the same key at index 0, so other rows never shift.

### Before / After

| Metric | Before | After |
|---|---|---|
| Official row at first frame | No (appears late → list jerks) | Yes (from cache) |
| Messages cached locally | No | Yes (Gson JSON in SharedPreferences) |

**Note:** On the very first install (empty cache), the official row appears
after the first Firestore response — that's unavoidable until content is cached.
From the second launch onward it's instant from cache.

---

## 6. Problem 4 — Avatar White Flash

### Root cause

Three separate sub-issues:

**A. Group avatars had no cold-start prewarm.**
`warmStartupChatSnapshots` only preloaded 1:1 avatars (`otherUserAvatar`).
Group icons (`groupIconUrl`) were never enqueued into Coil's memory cache at
cold start.

**B. Group avatar loading was async in the composable.**
The `Avatar` composable used a `LaunchedEffect` + async `getLocalGroupAvatarPath()`
for groups. The **first** composition always had `localAvatarPath = null`
(even when the cached file existed on disk), so `AsyncImage` was absent and
only the letter circle showed. The effect then completed, `localPath` was set,
and `AsyncImage` appeared — a flash from letter → photo.

1:1 avatars used `AvatarStateManager.observe()` which seeds `localPath`
**synchronously** from disk on first access — no flash. Groups had no equivalent.

**C. Prewarm visibility filter excluded avatars.**
The prewarm filtered 1:1 avatars through
`AvatarVisibilityRepository.getCachedProfilePhotoVisibility()?.isVisible == true`.
At ~150 ms the visibility cache is often empty → null → avatar skipped → no
Coil enqueue → cache miss at first composition → Coil decodes from file
(10–50 ms) → empty AsyncImage → letter shows behind → bitmap pops in → flash.

**D. Composables fell back to URL as data source.**
When `localPath` was null, the `Avatar` composable used the Firebase Storage
URL as the `AsyncImage` data source. This triggered a network load → empty
AsyncImage during download → letter circle behind → bitmap arrives → flash.

### Fix for (A)

Added group avatar preload to `warmStartupChatSnapshots`:
```kotlin
val groupAvatarsToWarm = topChats.mapNotNull { chat ->
    if (!isGroupChat(chat)) return@mapNotNull null
    val iconUrl = chat.groupIconUrl.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    chat.id to iconUrl
}
if (groupAvatarsToWarm.isNotEmpty()) {
    AvatarCacheManager.preloadGroupAvatars(groupAvatarsToWarm, context)
}
```

### Fix for (B)

Added `AvatarStateManager.observeGroup(chatId, remoteUrl)`, which uses the
same synchronous disk-seed pattern as the 1:1 `observe()`:
```kotlin
fun observeGroup(chatId: String, remoteUrl: String): StateFlow<AvatarState> {
    val groupCacheId = AvatarCacheManager.groupIconCacheIdPublic(chatId)
    return observe(groupCacheId, remoteUrl)
    // observe() does a synchronous getLocalAvatarPath() → seeds localPath on first access
}
```

The `Avatar` composable now uses `observeGroup` for groups (no more async
`LaunchedEffect`), so the cached group icon is present on the very first
composition.

### Fix for (C)

Removed the `visibleAvatarsToWarm` visibility filter from the prewarm.
**All** 1:1 avatars with local files are now enqueued into Coil unconditionally.
The composable's `canShowAvatar` gate (based on `blockedUserIds`) handles
blocking correctly at the UI layer.

### Fix for (D)

`AsyncImage` is now **only rendered when the local file exists**:
```kotlin
if (canShowAvatar && imageFile != null) {
    AsyncImage(...)
}
```

When the file isn't cached yet (e.g., first encounter of a new user's avatar),
only the colored letter circle shows. The prewarm downloads the file in the
background; when it completes, `AvatarStateManager` emits a new version →
composable recomposes → `imageFile` is non-null → `AsyncImage` appears with a
Coil memory-cache hit (the prewarm enqueued it) → no flash.

### Before / After

| Metric | Before | After |
|---|---|---|
| Group avatars preloaded at cold start | No | Yes (4 group icons at ~150 ms) |
| Group avatar synchronous disk seed | No (async LaunchedEffect) | Yes (observeGroup) |
| Prewarm visibility filter excludes valid avatars | Yes | Removed — all preloaded |
| AsyncImage uses URL as data source | Yes (network load → flash) | No (only shown when file cached) |

---

## 7. Problem 5 — List Jerk (Repeated Recomposition)

### Root cause

`loadLocalChatsWithPresence()` chains 6 `combine()` operators (chats +
presence + typing + group typing + sender names + blocked users + contact
cache version). Each combine source emits at least once as it initializes.
Each emission cascades through the chain to `.collect { updateChats(chats) }`,
which sets a new `StateFlow` value → Compose recomposes the entire
`LazyColumn`. The same 9-chat list was emitted **14+ times in the first
2 seconds**, each time triggering a full `updateChats` → recomposition →
visible jerk.

### Fix

Added `.distinctUntilChanged()` before the final `.collect`:
```kotlin
.distinctUntilChanged()
.flowOn(Dispatchers.Default)
.collect { chats -> viewModel.updateChats(chats) }
```

`Chat` is a `data class` — structural equality suppresses emissions where
the entire list contents are unchanged. Verified: emissions dropped from
14+ to 3 (the remaining 3 are legitimate content changes: presence data
arriving, typing status updating, contact names loading).

### Before / After

| Metric | Before | After |
|---|---|---|
| Flow emissions in first 2 s | 14+ | 3 |
| Visible list recompositions | Many redundant | Only on actual content change |

---

## 8. Problem 6 — Room Migration Destructive Wipe

### Root cause

A composite `@Index(["isArchived","lastMessageTimestamp"])` was added to
`LocalChat` with a `v38→v39` migration. The migration SQL used
`CREATE INDEX IF NOT EXISTS`. Room's schema validation compares an index's
`createSql` **verbatim**, and Room generates `CREATE INDEX` **without**
`IF NOT EXISTS`. The mismatch caused Room to fail validation. Because
`fallbackToDestructiveMigration()` was present, Room **destructively rebuilt**
the database, wiping all chats, messages, and metadata.

### Fix

Removed `IF NOT EXISTS` from the migration SQL so it exactly matches Room's
generated DDL. The index name `index_chats_isArchived_lastMessageTimestamp`
is the exact name Room auto-generates from the `@Index` annotation.

```sql
CREATE INDEX index_chats_isArchived_lastMessageTimestamp
    ON chats(isArchived, lastMessageTimestamp)
```

### Before / After

| Metric | Before | After |
|---|---|---|
| Migration validates | No (`IF NOT EXISTS` mismatch) | Yes |
| Data preserved on upgrade | No (destructive wipe) | Yes |

---

## 9. Supporting Optimizations

### Coil cache error logging
`enqueueAvatarIntoCoilMemory` had an **empty `onFailure` block** — any Coil
enqueue error was silently swallowed, so a failed prewarm would leave avatars
uncached with no log. Now logs `Log.w(TAG, "Failed to enqueue avatar...", e)`.

### Avatar index scan deferred off main thread
`AvatarCacheManager.init()` previously called `rebuildLocalAvatarIndex()` —
a synchronous directory scan of all `.jpg` files in `filesDir/avatars/` — on
the main thread during `Application.onCreate`. Now the scan is launched on a
background `CoroutineScope`. `getLocalAvatarPath()` already falls back to
`File.exists()` when the in-memory index is empty, so correctness is preserved.

### Baseline profile (baseline-prof.txt) extended
The existing profile only covered the dead XML RecyclerView chat-detail path
(`ChatActivity`, `ChatAdapter`, etc.). Added rules for the active Compose
chat-list path (`ChatListScreen`, `ChatListComposeFragment`, `ChatListViewModel`,
`OfficialChatList`, `coil/**`, `androidx.compose.runtime/**`,
`androidx.compose.foundation.lazy/**`). Rules use wildcard `HSPL` + `L`
profgen format (`profileinstaller` already a dependency).

### Distilled lessons

1. **Use `distinctUntilChanged()` on any combine flow that feeds a `StateFlow`
   driving a `LazyColumn`.** Without it, every combine-source init cascades
   redundant emissions → N recompositions → visible jerk.
2. **Always seed `StateFlow` initial value synchronously from cache.**
   `MutableStateFlow(emptyList())` + async populate → visible pop-in.
   `MutableStateFlow(readFromDisk())` → present on first frame.
3. **Match Room migration SQL verbatim to Room's generated DDL.**
   `IF NOT EXISTS`, column order, index names — any deviation fails validation.
   With `fallbackToDestructiveMigration()`, the failure mode is **total data
   loss**, not a visible error.
4. **Gate `AsyncImage` on local file existence for offline-first.**
   Falling back to a URL as the data source triggers a network load from the
   composable → empty→bitmap flash. Only show `AsyncImage` when the file is
   cached; show a letter avatar or placeholder otherwise.
5. **Preload all visible avatars unconditionally at cold start.** A visibility
   filter that depends on a lazy cache (Firestore-backed) produces cache-misses
   at cold-start time. Let the UI layer (composable) handle blocking/visibility;
   the prewarm should be maximally aggressive about populating the memory cache.

---

## 10. Known Limitations

### Debug-build JIT overhead
All measurements came from a **debug** build (`app-debug.apk`). Debug builds
are debuggable → ART runs JIT-only, no AOT compilation → Compose first
composition cold-compiles from scratch → 100+ skipped frames (the "1–2 second"
cold start). WhatsApp from the Play Store is **release + AOT-compiled**
(baseline profile applied at install time, R8 optimisations). The app-cold-start
optimizations in this document eliminate the **perceived** staging/jerk/flash,
but the absolute ~1.9 s `Displayed` time for MainActivity is dominated by
debug-JIT. A release build with the baseline profile and R8 would be
substantially faster.

### Release build crashes under R8
The release build type (`isMinifyEnabled = true`) crashes on launch with
`ExceptionInInitializerError` in `GlyphApplication`'s static initializer —
R8 strips something the static init needs. This is a missing ProGuard keep
rule. Fixing it unlocks the AOT-compiled cold start and is the next step
toward WhatsApp-comparable absolute performance.

### One avatar without local cache
The debug traces showed `sfOIJCgFObbyk9rD2HJN0RC1EBB2` with `fileExists=false`
— this user's avatar file was never downloaded to disk (Firebase Storage
download in `cacheAvatar` didn't complete, possibly due to network or
permission). The composable correctly shows only the letter avatar for this
user (no `AsyncImage`, no flash). Once the prewarm's `cacheAvatar` succeeds
on a subsequent launch, the file will be cached and the avatar will render
instantly. This is the correct offline-first behaviour — never show an
`AsyncImage` that would trigger a network load from the composable.

---

## 11. Debugging Reference

When investigating chat-list regressions, instrument the following points:

### List content stability
```kotlin
// ChatListScreen — first composition and each recomposition
LaunchedEffect(isInitialLoading, chats.size, filteredChats.size) {
    Log.d(TAG, "isInitialLoading=$isInitialLoading chats=${chats.size} filtered=${filteredChats.size}")
}
```

### Prewarm timing
```kotlin
// StartupTrace logs (already in code):
// "startup_chat_prefetch_scheduled" — when prewarmCache() and avatar preload complete
// "repository_ready" — when DB + repo are ready
// "coil_init_complete" — when Coil singleton is installed
```

### Flow emission frequency
```kotlin
// In loadLocalChatsWithPresence() .collect { }:
Log.d(TAG, "FLOW emit ${chats.size} chats")
// Expect 1–3 emissions in first 2s with distinctUntilChanged. 14+ = regression.
```

### Avatar cache state
```kotlin
// In the Avatar composable SideEffect:
val fileExists = localAvatarPath?.let { File(it).exists() && File(it).length() > 0 } ?: false
Log.d(TAG, "chatId=${chat.id} isGroup=$isGroupChat fileExists=$fileExists cacheKey=$avatarCacheKey")
// fileExists=false + cacheKey is a URL → flash incoming
// fileExists=true + cacheKey is a file-based key → should be instant
```

### Official content cache
```kotlin
// OfficialContentRepository.startListening → seedMessagesFromCache:
Log.d(TAG, "seeded ${cached.size} messages from cache")
// 0 = first install or cache cleared → official row will appear late
// >0 = cache hit → official row present on first frame
```

### Cold-start measurement (adb)
```bash
adb shell am force-stop com.glyph.glyph_v3
adb logcat -c
adb shell monkey -p com.glyph.glyph_v3 -c android.intent.category.LAUNCHER 1
# Capture for ~10 seconds, then:
grep -E "Displayed.*MainActivity|startup_chat_prefetch_scheduled|Skipped" logcat.txt
```

### Database inspection
```bash
adb exec-out run-as com.glyph.glyph_v3 cat databases/glyph_database > glyph.db
sqlite3 glyph.db "SELECT COUNT(*) FROM chats;"
sqlite3 glyph.db "PRAGMA user_version;"
sqlite3 glyph.db "SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='chats';"
```

---

## 12. Files Changed

| File | Change summary |
|---|---|
| `GlyphApplication.kt` | Prewarm activation, Coil init, prewarm reorder, group avatar preload, no visibility filter |
| `ChatListScreen.kt` | `observeGroup` for groups, `fileExists` gate on `AsyncImage`, crossfade off |
| `ChatListComposeFragment.kt` | `distinctUntilChanged` on combine flow, `warmStartupChats=true` |
| `OfficialContentRepository.kt` | Gson local cache for official messages |
| `AvatarStateManager.kt` | `observeGroup()` with synchronous disk seed |
| `AvatarCacheManager.kt` | `groupIconCacheIdPublic()`, deferred index scan, enqueue error logging |
| `AppDatabase.kt` | `MIGRATION_38_39` (composite index, no `IF NOT EXISTS`) |
| `LocalChat.kt` | Composite `@Index(["isArchived","lastMessageTimestamp"])` |
| `MainActivity.kt` | No logic changes (earlier session-only edit reverted) |
| `baseline-prof.txt` | Compose chat-list + Coil + runtime baseline profile rules |
