---
name: chat-open-perf
description: 'Diagnose and fix chat-open jank, placeholder flashes, scroll lag, and media preload misses in GlyphV3. Use when optimizing first-frame latency, investigating recyclerView/Glide issues, fixing retained preload regressions, or improving chat cold/warm open performance. Covers commitPrefillList flow, Glide density matching, retained FutureTargets, CollageImageView rules, first-scroll suppression, and all performance logging gates.'
argument-hint: 'Describe the symptom (e.g. "placeholder flash on chat open", "first-scroll jank", "media bubble flicker")'
---

# Chat-Open Performance Skill

See [CHAT_OPEN_INSTANT_PLAYBOOK.md](../../../CHAT_OPEN_INSTANT_PLAYBOOK.md) for the full strategic playbook.

## Key Files

| File | Role |
|------|------|
| `ui/chat/ChatActivity.kt` | `commitPrefillList()`, open-source classification, retained handoff |
| `ui/chat/ChatOpenPrefetcher.kt` | `primeChatOpen()`, `warmChatsAsync()`, preload spec building |
| `ui/chat/ChatAdapter.kt` | `bindSingleMedia()`, `bindReactionsChip()`, seeded drawable reuse |
| `ui/chat/CollageImageView.kt` | Grouped-media tile loading and recycle lifecycle |
| `utils/MessageCacheManager.kt` | Snapshot compaction, `live_flow` / `app_cold_start` / cached-reopen sources |

## Open-Source Classification

`commitPrefillList()` classifies every open by its `source` string and picks a fast-path or blocking strategy:

| Source | Strategy |
|--------|----------|
| `chat_list_compose_visible` / `chat_list_compose_tap` / `chat_list_tap` | **Lightweight** — 3–6 critical specs, no INVISIBLE gate |
| `live_flow` (cached reopen) | **Cached** — 4 specs, 320 ms first-paint await, deferred MEDIA_GROUP |
| `app_cold_start` | **Warm-start fast path** — small retained spec set, skip blocking await |
| All others | Blocking first-paint await with full spec window |

All chat-list sources (visible + tap) must use the lightweight path regardless of message count.

## Glide Density — Critical

Always use **`Resources.getSystem().displayMetrics.density`** when computing override dimensions for Glide preloads or bind-time size calculations. Using `resources.displayMetrics.density` (activity-scoped) is affected by Display Size settings and produces a different `EngineKey`, causing a placeholder→image flash even when the resource is fully decoded in memory.

```kotlin
// CORRECT
val density = Resources.getSystem().displayMetrics.density
val w = (bubbleWidthDp * density).toInt()

// WRONG — different key on high Display-Size devices
val density = resources.displayMetrics.density
```

## Retained FutureTargets — Rules

- Store completed preload `FutureTarget`s in `retainedMediaPreloadFutures` (a `CopyOnWriteArrayList`) for the lifetime of the chat.
- **Never call `future.cancel(false)` on completed futures.** Glide's `ActiveResources` pins resources via the Target's strong reference; releasing the Target demotes the resource to `LruResourceCache` where it can be evicted before the first bind.
- Release all retained targets in `onDestroy()` and at the start of each `commitPrefillList()`.
- For retained Activity-side image/video preloads, use `disallowHardwareConfig()` so `copyDrawableForSeed()` can reliably clone the warmed bitmap.
- Never attach a retained `Drawable` directly to an `ImageView` — `BitmapDrawable.constantState.newDrawable()` shares the same bitmap; clearing the retained target can make the view draw a recycled bitmap. Copy the bitmap first.

## commitPrefillList() Timing Rules

- Guard against duplicate initial commits with an in-flight/committed flag; a second call clears retained preload resources and forces later binds to `RESOURCE_DISK_CACHE`.
- Derive first-paint preload windows from the actual `ChatListItem` list being submitted, not only `recentMessages.takeLast(...)`.
- For the lightweight path, only block on the last 6 specs (visible viewport) with a 350 ms per-future deadline wrapped in `withTimeoutOrNull(450L)`.
- Deferred MEDIA_GROUP / DOCUMENT warmups should be posted one frame after the visible window is committed.

## CollageImageView Rules

- **Never call `clearForRecycle()` from `onViewDetachedFromWindow`.** Normal scroll detach must preserve `mediaItems`, `lastLoadedSignature`, child views, and drawables.
- Only `onViewRecycled` may call `clearForRecycle()`.
- Scroll-start should blur/preserve existing tiles; calling `loadImages()` and clearing cells during scroll causes blank half-height / two-cell layouts on re-entry.
- Keep state to the first 4 visible items plus a separate total count for the `+X` overlay.

## First-Scroll Suppression

Both `schedulePreInflateViewHolders` and `queueScrollMediaWarmupIfNeeded` must be deferred while `firstScrollTrackingActive == true`. Running them during first scroll causes repeated `[ScrollWarm] queue reason=first_scroll` log bursts and elevated dropped frames.

## Snapshot Compaction

`MessageCacheManager.putSnapshot` must compact in-memory snapshots to the same recent render slice used for persisted snapshots. A mismatch between the dynamic-height transcript and the media-preload window stretches first-layout/bind work and increases the chance that warmed media is not aligned with the visible slice.

## Predictive Retained Preloads

`warmChatsAsync()` must queue retained first-paint preloads even when `MessageCacheManager` already has a fresh render snapshot, or bind will see `mapSize=0` and futures become ready only after `[SeedMiss]`. Synchronously harvest already-done handoff futures before `submitList`.

## Live History Expansion

When the first live Room emission expands the adapter from prefill items to the full history (~950 items), detect the tail-append case, trace `live_list_first_commit mode=staged_tail`, and delay `live_full_expansion_*` until after the open settles. Suppress heavy per-candidate `[SeedMiss] mapKeys` logging.

## Logging Gates

| Tag / Flag | What it controls |
|---|---|
| `ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG = false` | Verbose media-bind and collage logs |
| `adb shell setprop log.tag.ChatPerfDebug DEBUG` | First-scroll + IME timing summaries |
| `[ScrollWarm]` | Scroll warmup queue/ready/fail/trim in ChatActivity |
| `[BIND-REUSE]` / `[BIND-MISS]` / `[BIND-SCROLL-FALLBACK]` | Seeded drawable reuse paths in ChatAdapter |
| `[CollageTrace]` | CollageImageView tile load paths |
| `[PrefillTiming]` | commitPrefillList source, await, and submit events |

## Common Symptom → Root Cause Map

| Symptom | Likely Cause |
|---------|-------------|
| Placeholder flash despite memory cache hit | System vs activity density mismatch → `EngineKey` differs |
| Retained preloads evicted before first bind | `future.cancel(false)` on completed futures, or Target GC-eligible |
| Blank/two-cell collage after scroll | `clearForRecycle()` called from `onViewDetachedFromWindow` |
| First-scroll jank burst | `schedulePreInflateViewHolders` or scroll warmup not suppressed while `firstScrollTrackingActive` |
| Cached reopen still blocking | Snapshot source not normalized to cached-open source, falling into heavy await path |
| Cold-start open slower than warm | `app_cold_start` not treated as `warmStartFastPath` in `commitPrefillList` |
| Media seeding healthy but full history replaces prefill at +1.25s | `live_full_expansion` not staged; first live Room emission replaces prefill list |
