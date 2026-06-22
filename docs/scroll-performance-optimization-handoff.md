# Scroll Performance Optimization — Handoff Document

## Date
2026-06-20

## Context

The chat screen scrolling experience had significant performance issues creating visible
jank and stuttering. The goal was WhatsApp-like smoothness: fast opening, zero jank even
when cold-starting and immediately scrolling, and smooth pagination during scroll.

## Key Findings from Log Analysis

All investigation was done by capturing live logcat on Samsung SM-M346B (mid-range device)
with filters:

```bash
adb -t <id> logcat -s GlyphPerf StartupTrace ChatOpenTrace
```

### Before optimization:
- Frame times: **100ms+** (target: 16.6ms for 60fps)
- Dropped frames: **2–5 per slow frame**
- ViewHolder inflations during scroll: **+4 to +8** (pool exhaustion)
- Initial window: **135 items** (100 messages + headers)
- Chat opening to first visible: **843ms**
- Pagination during first scroll caused massive list commits (135 → 1620 items)

### After optimization (best observed results):
- Frame times: **~69ms** (~30% improvement)
- Inflates during scroll: **+0 to +1** (87% reduction)
- Pool coverage: **[1=11,2=6,3=4,4=4,9=1,10=1,13=1]** (83% improvement)
- Scaling a large 500-message initial window back to 100–150 fixed opening latency
  (500 took 2289ms; 80–100 takes ~558ms)
- Pagination deferral (first attempt) **blocked the fling** — removed

## Files Modified

| File | Changes | Purpose |
|------|---------|---------|
| `RecyclerCoordinator.kt` | Expanded `FIRST_FLING_WARM_VIEW_TYPES` from 6→14, increased `WARM_POOL_TARGETS` | Better pool coverage, fewer mid-scroll inflations |
| `ChatScrollController.kt` | Added `isScrollReady` flag, `markScrollReady()`, removed time-gate | Scroll tracking only after first frame; scroll always works |
| `ChatViewModel.kt` | Adjusted `INITIAL_WINDOW`: 100→300→500→100 (settled at 100) | Fast opening; pagination feeds scrolling |
| `ChatActivity.kt` | Lowered `ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA` (24→10→1), removed pagination-deferral, removed `paginationQueuedAfterFirstScroll`, wired `markScrollReady()`, removed 2-second scroll gate | Responsive scrolling, flings don't stop |
| `ChatOpenPrefetcher.kt` | Adjusted `LIGHT_PREFETCH_MESSAGE_LIMIT`: 120→300→500→100 (settled at 100) | Fast opening; not loading hundreds of messages upfront |
| `MessageCacheManager.kt` | Adjusted `MAX_RENDER_SNAPSHOT_MESSAGES`: 240→500→150 (settled at 150) | Quick cache serialization; decent scroll buffer |
| `GlyphApplication.kt` | Fixed release build: commented out debug-only `DebugAppCheckProviderFactory` import | Release APK now compiles |
| `RealtimeMessageRepository.kt` | Changed `primeRealtimeTransportForForeground` to always use `waitForConnection=false` | Firebase operations non-blocking during opening |

## What Worked — Keep These

1. **Pool warming expansions** — `FIRST_FLING_WARM_VIEW_TYPES` (14 VHs) and `WARM_POOL_TARGETS`
   (20/20 text, 8/8 media) dramatically reduced mid-scroll ViewHolder creation.

2. **Lower defer thresholds** — `ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA=10`,
   `ACTIVE_SCROLL_LIST_COMMIT_DEFER_MESSAGE_DELTA=6` ensure pagination commits are
   deferred during scroll when appropriate.

3. **Scroll readiness tracking** — `markScrollReady()` called after first frame + pool warm
   prevents false "first-scroll jank" metrics when user scrolls before paint.

4. **Non-blocking Firebase** — `waitForConnection=false` always, so the UI never waits for
   RTDB connections.

5. **`olderLoadInFlight` mechanism** — Critical for fling-pagination. When pagination loads
   during a fling, `olderLoadInFlight=true` makes `shouldDeferLargeFlowEmission()`
   return `false`, so the commit goes through immediately and feeds the fling.

## What Was Tried and Reverted — Avoid These

1. **500-message initial window** — Made opening take **2289ms** (vs ~558ms for 100).
   WhatsApp opens with a small set and pages the rest. Don't try to eliminate pagination
   by front-loading messages — it kills opening speed.

2. **Pagination deferral during first scroll** — The pagination queue (`paginationQueuedAfterFirstScroll`)
   blocked content from loading during flings. When the user scrolled past the 100 loaded
   messages, the fling **hit a wall and stopped dead**. The original `olderLoadInFlight`
   mechanism (immediate-commit for fling pagination) is correct and necessary.

3. **2-second scroll time-gate** — Blocking scroll in the first 2 seconds made the app
   feel heavy/non-responsive, the opposite of WhatsApp. Removed.

4. **30-ViewHolder sync pool warm** — Excessive; 14 is enough for the first fling and
   doesn't add visible overhead.

## Remaining Issues

1. **Frame jank (~100ms) with light bind work** — Some 100ms frames show `inflates=+0,
   fullBinds=+1, partialBinds=+3`. This points to something ELSE blocking the main
   thread during scroll (Firebase callbacks, presence updates, DB operations on main
   thread). Needs profiling with systrace/perfetto.

2. **Inconsistent media pre-rendering** — The media warm depends on `source`:
   `source == "activity_prefill"` awaits preloads (pre-rendered first paint) but other
   sources (e.g. `chat_list_compose_visible`, `live_flow`) do async warm (media pops in
   after opening). Some chats open fully rendered, others show placeholders that fill
   in later. The fix is in `commitPrefillList()` at the `shouldAwaitInitialMediaPreloads`
   check — it gates on `source == "activity_prefill"`.

3. **Activity `onCreate` setup baseline** — Even with 5–6 messages, opening takes
   ~575–700ms. The `chat_onCreate_start` to `chat_prefill_start` gap is 300–400ms —
   this is purely activity setup. Could be optimized by deferring non-essential init.

4. **Chat opening from chat list tap** — The `chat_list_compose_visible` path shows 20
   messages from cache initially, then the live flow (`source=live_flow count=80`)
   replaces it ~300ms later. This "double paint" may cause perceived flicker/heaviness.

## Testing Commands

```bash
# Install and launch
adb -t <id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -t <id> shell monkey -p com.glyph.glyph_v3 -c android.intent.category.LAUNCHER 1

# Monitor performance
adb -t <id> logcat -c
adb -t <id> logcat -s GlyphPerf StartupTrace ChatOpenTrace

# Build release
./gradlew assembleRelease
```

## Key Constants (Current Values)

```
RecyclerCoordinator:
  OFFSCREEN_VIEW_CACHE_SIZE = 4
  FIRST_FLING_WARM_VIEW_TYPES = [1,2,1,2,1,2,1,2, 3,4,3,4, 13,13]  // 14 VHs
  WARM_POOL_TARGETS = {1→20, 2→20, 3→8, 4→8, 13→6, 9→3, 10→3, 19→3, 20→3}

ChatViewModel:
  INITIAL_WINDOW = 100
  OLDER_PAGE_SIZE = 60

ChatActivity:
  OLDER_MESSAGE_PAGE_SIZE = 120
  MAX_OLDER_PAGES_PER_LOAD = 3
  ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA = 10
  ACTIVE_SCROLL_LIST_COMMIT_DEFER_MESSAGE_DELTA = 6
  ACTIVE_SCROLL_FLOW_DEFER_MESSAGE_DELTA = 120

ChatOpenPrefetcher:
  PREFETCH_MESSAGE_LIMIT = 60
  LIGHT_PREFETCH_MESSAGE_LIMIT = 100

MessageCacheManager:
  MAX_CHAT_SNAPSHOTS = 12
  MAX_RENDER_SNAPSHOT_MESSAGES = 150
```

## Build Status

- **Debug APK**: ✅ builds and installs (113MB)
- **Release APK**: ✅ builds (84MB, unsigned) — requires signing for install
