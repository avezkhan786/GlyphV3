# Scroll Smoothness Optimization — Handoff Document

## Date
2026-06-22

## Summary

Comprehensive scroll performance optimization for the GlyphV3 chat screen. The initial scroll had massive jank (111 dropped frames), chat opening took 2.3+ seconds, and pagination caused the scroll to stop while loading messages. After optimization: larger initial window, lightweight first-frame binds, frame-budget-protected warming during scroll settling, and seamless background pagination.

## Problem Statement

The chat screen had three critical problems for a commercial chat app:

1. **Chat opening delay**: 2.3+ seconds from tap to first visible messages
2. **Initial scroll jank**: 111 dropped frames during the first scroll (frames up to 877ms)
3. **Pagination scroll stops**: Small initial batch + cooldown caused the scroll to hit the wall and stop while waiting for more messages

## Root Cause Analysis

### 1. Chat Opening Delay (2.3s)
The gap between `submitList` completion and `OnPreDraw` was ~430ms. During this gap, 8-10 visible ViewHolders ran FULL binds that included: link preview inflation, reply preview inflation, reaction chip layout, translation label setup, emoji detection, media reference binding, and group sender name resolution. Each bind took ~40-50ms.

### 2. Initial Scroll Jank (111 dropped frames)
- Frame 1: 292ms (12 full + 11 partial binds)
- Frame 2: 877ms (10 full binds — external main-thread stall, not bind-related)
- Frame 3: 259ms (2 full binds)
- Plus pagination list commits of 238-262ms each during scroll

The 877ms stall with zero inflate/bind work indicates an external main-thread blocker (likely Firebase RTDB callback or GC pause).

### 3. Pagination Wall
With only 115 items loaded initially and pagination triggering at 120 rows from the boundary, the user scrolled through 115 items in ~1 second and hit the wall. Pagination fires every 300-500ms (cooldown), creating gaps where the scroll had no content.

## Changes Made

### ChatAdapter.kt — `isFirstLayout` lightweight binds

Added `isFirstLayout: Boolean` flag. When true, ViewHolder binds use the existing lightweight scroll path (text + timestamp + basic styling, ~2ms) instead of the full bind path (link previews, reply previews, reactions, translation labels, media references, ~40-50ms).

- `IncomingTextViewHolder.bind()`: checks `isScrolling || isFirstLayout`
- `OutgoingTextViewHolder.bind()`: checks `isScrolling || isFirstLayout`
- Set `true` before `submitList` in `commitPrefillList`
- Cleared in `rv.post` after `OnPreDraw` fires
- No explicit upgrade — visible items get full binds on their next natural rebind

### ChatViewModel.kt — Larger initial window

- `INITIAL_WINDOW`: 100 → **300** messages (~350 list items)
- Gives 3-4 screens of scroll buffer before pagination needed

### ChatOpenPrefetcher.kt — Larger prefetch cache

- `LIGHT_PREFETCH_MESSAGE_LIMIT`: 100 → **300** messages
- `PREFETCH_MESSAGE_LIMIT`: 60 → **180** messages
- Cache now holds enough messages for a comfortable first-scroll buffer

### ChatActivity.kt — Pagination & scroll optimizations

**Pagination constants:**
- `OLDER_MESSAGE_PAGE_SIZE`: 120 → **60** (smaller, more frequent pages)
- `MAX_OLDER_PAGES_PER_LOAD`: 3 → **2** (max 120 messages per fire)
- `PAGINATION_COOLDOWN_MS`: removed → **0** (no artificial delay)
- `OLDER_PREFETCH_KEEP_AHEAD_ROWS`: 120 → **300** (trigger 5 pages ahead)
- `LOAD_OLDER_THRESHOLD_MAX`: 350 → **400** (wider scroll trigger)
- `olderLoadInFlight` bypass: kept (pagination commits immediately during scroll)

**Scroll listener:**
- `DRAGGING`: pauses idle queue, starts frame budget guard
- `SETTLING`: does NOT pause idle queue, starts frame budget guard for warming
- `IDLE`: stops frame budget guard, resumes idle queue, applies deferred emissions

**First-frame optimization:**
- `chatAdapter.isFirstLayout = true` set before `submitList`
- Cleared in `rv.post` after first frame draws
- No explicit upgrade — prevents partial binds landing on first scroll frame

### RecyclerCoordinator.kt — Progressive warming with frame-budget protection

- `WarmStage` enum: STAGE1_CRITICAL (~30 VHs) → STAGE2_EXPANDED (~50 VHs) → STAGE3_FULL (~74 VHs)
- `FrameBudgetGuard`: monitors frame times via Choreographer, pauses warming if any frame exceeds 18ms
- `buildAdaptiveWarmList()`: analyzes message type distribution for proportional ViewHolder allocation
- `warmCommonViewHoldersAdaptive()`: synchronous warm using adaptive list
- `isPoolBelowFullTargets()`: returns true if any type below target
- `preInflateViewHoldersNow()`: progressed through stages, reschedules if interrupted
- Companion object made `internal` to expose `FULL_POOL_TARGETS`
- Dead view types 5, 6, 15, 16 marked (never returned by `getItemViewType()`)

### ChatScrollController.kt — Diagnostics

- `FIRST_FLING_METRICS` structured log: inflates, pool hits/misses, hit rate, inflate time, warm progress, dropped frames, per-type inflation breakdown
- `buildPoolMissByTypeSuffix()`: per-view-type inflate counts

### ChatAdapter.kt — Pool telemetry

- `dbgCreateCountByType`: per-view-type pool miss counter
- Extended `ScrollWorkSnapshot` with `createCountByType` map
- `onCreateViewHolder` instrumented with per-type tracking

### activity_chat.xml — Scrollbar

- `android:scrollbars`: "none" → **"vertical"**
- Added thin semi-transparent thumb drawable

### drawable/scrollbar_thumb.xml — New file

- WhatsApp-style thin scrollbar thumb: 3dp, rounded, 25% white

## Key Metrics (Before → After)

| Metric | Before (cold start) | After (expected) |
|--------|---------------------|-------------------|
| First frame time | 2292ms | < 1000ms |
| Initial items loaded | 115 | ~350 |
| First fling inflations | 1 (99% hit rate) | 1 (99% hit rate) |
| First fling dropped frames | 111 | < 15 |
| Pagination fires per scroll | 7-14 | 2-4 |
| Pagination cooldown | 100-500ms | 0ms (no delay) |
| Pool warm complete | 4060ms | < 1500ms |

## Architecture

```
Chat open:
  setupRecyclerView → warmRecycledViewPool (7 VHs) + warmCommonViewHolders (24 VHs)
  prefillRecentMessagesSync → commitPrefillList
    → isFirstLayout=true → submitList → lightweight first-frame binds (~2ms each)
    → OnPreDraw → make visible → FIRST CONTENT FRAME (~500ms instead of ~2300ms)
    → rv.post → isFirstLayout=false, async warming continues

Scroll:
  DRAGGING → pauseIdleTaskQueue (maximizes main-thread headroom)
  SETTLING → FrameBudgetGuard monitors, warming continues via yield()
  IDLE → resumeIdleTaskQueue, applyDeferredLargeFlowEmission

Pagination:
  300 message initial window → 5 pages of buffer at 60 rows/page
  Trigger at 400 rows from boundary → loads before user reaches wall
  No cooldown → pages flow as fast as DB can deliver
  olderLoadInFlight bypass → commits immediately during scroll
  DiffUtil on background → main thread blocked ~10-20ms per commit
```

## Remaining Issues

1. **External main-thread stalls**: 877ms, 292ms, 259ms frames with zero inflate/bind work. Likely Firebase RTDB callbacks or GC pauses. Needs systrace/perfetto profiling.
2. **Chat opening baseline**: Activity `onCreate` setup takes ~1200ms before data is available. Could be optimized by deferring non-essential init.
3. **First-batch jank pattern**: Scrolling through the initial loaded messages is still jankier than subsequent scrolls. Needs deeper investigation into what changes after the first batch passes.

## Files Modified

| File | Key Changes |
|------|------------|
| `ChatAdapter.kt` | `isFirstLayout` flag, pool telemetry, dead view type cleanup |
| `RecyclerCoordinator.kt` | `WarmStage` enum, `FrameBudgetGuard`, adaptive warming, progressive stages |
| `ChatScrollController.kt` | `FIRST_FLING_METRICS`, `logPaginationFire`, `buildPoolMissByTypeSuffix` |
| `ChatActivity.kt` | `isFirstLayout` wiring, pagination constants, scroll listener split, `logPoolHealth`, `frameBudgetGuard`, idle queue fixes, `PREFILL_COMMIT` trace |
| `ChatViewModel.kt` | `INITIAL_WINDOW` 100 → 300 |
| `ChatOpenPrefetcher.kt` | `LIGHT_PREFETCH_MESSAGE_LIMIT` 100 → 300, `PREFETCH_MESSAGE_LIMIT` 60 → 180 |
| `activity_chat.xml` | Scrollbar enabled with WhatsApp-style styling |
| `drawable/scrollbar_thumb.xml` | New scrollbar thumb drawable |
| `GlyphApplication.kt` | Debug-only imports commented (from handoff) |
| `RealtimeMessageRepository.kt` | `waitForConnection=false` always (from handoff) |
| `MessageCacheManager.kt` | `MAX_RENDER_SNAPSHOT_MESSAGES` 150 (from handoff) |

## Testing

```bash
# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clear app data for fresh cache (picks up new 300-message limit)
adb shell pm clear com.glyph.glyph_v3

# Monitor
adb logcat -c && adb logcat -s GlyphPerf StartupTrace ChatOpenTrace
```

Look for:
- `PREFILL_COMMIT duration=Xms items=N` — N should be ~350 (was 115)
- `FIRST CONTENT FRAME Xms after open` — should be < 1000ms (was 2292ms)
- `FIRST_FLING_METRICS` — `droppedFrames` should be < 15 (was 111)
- `pagination fire:` — fewer total fires per scroll session
- `POOL WARM ... stage=STAGE3_FULL` — should complete in < 1500ms
