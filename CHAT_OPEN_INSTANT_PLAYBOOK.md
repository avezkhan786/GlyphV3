# Chat Open Instant Performance Playbook (No Flash)

## Goal
Make chat open feel instant on mid-range devices, with no visible placeholder flash.

## Scope
This playbook documents the final working approach used for heavy chat opens (notably chat id patterns like 39tzjKLx), where media-rich threads caused open delays and occasional visual flash.

## Symptoms Observed
- Long delay between prefill start and first visible content.
- Repeated prefill cycles for the same chat during live updates.
- Heavy prefetch batches (up to 12 specs) running repeatedly.
- MEDIA_GROUP preview failures adding noise and pressure.
- Flash risk when adapter bind happened before stable seeded resource handoff.

## Root Causes
1. UI path doing too much work before first paint
- Large preload fan-out and waits before reveal increased first-frame latency.

2. Redundant prefill during live updates
- live_flow updates re-entered expensive prefill behavior, causing recurring stalls.

3. Heavy warmups competing with first paint
- Background retained warmups could run too aggressively and repeatedly.

4. Cache-key sensitivity and bind-time races
- Small option/model/dimension mismatches and missing seeded drawable handoff caused occasional non-instant binds.

## Final Working Strategy
### 1. Instant path for heavy opens in ChatActivity
- File: [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
- Constants tuned for light open:
  - LIGHT_OPEN_MIN_ITEMS = 40
  - LIGHT_OPEN_PRELOAD_SPECS = 3
- Behavior:
  - For chat_list_compose_visible with large item count, use lightweight preloads only.
  - Submit list immediately and keep reveal path non-blocking.
  - Run tiny background await window only (short timeout and small spec set).

### 2. Hard fast-path for live_flow
- File: [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
- live_flow never enters hide/await prefill path.
- RecyclerView stays visible and list is submitted directly.
- This removes recurring open-time stalls when realtime updates arrive.

### 3. Critical await restricted to above-the-fold and non-collage
- File: [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
- awaitChatMediaPreloads blocks only on a limited critical subset.
- MEDIA_GROUP and DOCUMENT specs are excluded from critical blocking.
- Remaining requests continue in background.

### 4. Preload fan-out bounded for first open
- File: [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
- buildInitialChatMediaPreloadSpecs now:
  - deduplicates by cache key,
  - deprioritizes MEDIA_GROUP and DOCUMENT,
  - caps initial spec count to avoid large open-time bursts.

### 5. Drawables pinned and reused to avoid flash
- Files:
  - [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
  - [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatAdapter.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatAdapter.kt)
- Retained FutureTargets keep warmed resources from being evicted too early.
- Adapter seeded placeholder path reuses preloaded drawables.
- Fallback also reuses currently bound drawable when pre-seed is unavailable.

### 6. Prefetcher throttled and limited
- File: [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatOpenPrefetcher.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatOpenPrefetcher.kt)
- Reduced limits and added cooldown:
  - MEDIA_WARM_LIMIT = 8
  - MEMORY_WARM_LIMIT = 4
  - RETAIN_WARMUP_COOLDOWN_MS = 15000
- Goal is to prevent repeated retained warmups from competing with first render.

## Log Signals That Confirm Success
Look for these patterns in GlyphCacheDebug:

1. Lightweight open path active
- [PrefillTiming] lightweightOpen ... specs=3

2. live_flow bypass active
- [PrefillTiming] liveFlowFastPath ...

3. Small critical wait only
- [Await] blocking on small subset (not large full-window waits)

4. No user-visible flash path
- [BIND-REUSE] for visible media rows
- [BIND-HIT] MEMORY_CACHE on visible first rows

5. Non-blocking failures tolerated
- [AwaitReady-FAIL] MEDIA_GROUP ... may appear, but should not block first paint.

## Tuning Guide (If Regression Returns)
Adjust in [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt):

1. For even faster first paint
- Lower LIGHT_OPEN_PRELOAD_SPECS from 3 to 2.
- Lower lightweight await timeout budget.

2. If first image quality readiness drops too much
- Increase LIGHT_OPEN_PRELOAD_SPECS from 3 to 4.
- Keep live_flow fast-path unchanged.

3. If background contention returns
- Reduce ChatOpenPrefetcher MEDIA_WARM_LIMIT and MEMORY_WARM_LIMIT further.
- Increase RETAIN_WARMUP_COOLDOWN_MS.

## Debug Checklist for Future Incidents
1. Confirm path selection
- Verify whether lightweightOpen or liveFlowFastPath is active in logs.

2. Check blocking window size
- Verify [Await] blocking count and elapsed times.

3. Confirm bind behavior
- Verify [BIND-REUSE] or [BIND-HIT] on first visible media.

4. Identify repeated heavy warmups
- Watch [Prefetcher] retained N specs and repeated long future timings.

5. Check media-group noise
- MEDIA_GROUP failures are acceptable if first paint remains instant.

## Files Involved
- [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt)
- [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatOpenPrefetcher.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatOpenPrefetcher.kt)
- [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatAdapter.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatAdapter.kt)
- [app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatMediaModelResolver.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatMediaModelResolver.kt)

## Outcome
With the above combination, open became fast and stable with no visible flashing on the previously problematic heavy chat scenario.
