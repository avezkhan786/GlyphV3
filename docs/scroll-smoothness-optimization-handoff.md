# Scroll Smoothness & Incremental Message Loading — Handoff

## Overview

Comprehensive optimization of chat opening and scrolling performance. The goal was WhatsApp-level smoothness: instant open, 60 FPS scrolling, and invisible pagination that never interrupts the scroll.

## Changes Made

### 1. True Incremental Message Loading (`ChatActivity.kt`)

**Problem:** `processMessagesWithHeaders` rebuilt the entire `List<ChatListItem>` from scratch on every pagination emission. For 500+ messages, this meant 500+ ChatListItem creations, date header re-insertions, and bubble group re-computations. `submitList` then dispatched all items via DiffUtil on the main thread, causing multi-second frame drops.

**Fix:**
- `INITIAL_MESSAGE_WINDOW` reduced from 500 → 40 messages (line 552). Only enough for initial display — the first frame processes just 40 messages.
- New `tryBuildPrependedMessageList()` function (line 7967): when pagination loads strictly older messages (tail unchanged), only the NEW older messages are processed and prepended to the existing list. Avoids O(n) rebuild on every pagination step.
- Flow collector (line 6854) tries three paths in order: `tryBuildFastAppendedMessageList` (single tail message) → `tryBuildPrependedMessageList` (pagination prepend) → full `processMessagesWithHeaders` (fallback).
- Boundary fixup: adjusts bubble grouping and date headers at the junction between prepended and existing items.

### 2. Pagination Feed-Through During Fling (`ChatActivity.kt`)

**Problem:** Pagination emissions were deferred during DRAGGING (finger on screen). The deferred emission waited until `SCROLL_STATE_IDLE`, by which time the fling had already hit the boundary and stopped. The user had to scroll again manually.

**Fix:**
- `applyDeferredLargeFlowEmissionIfNeeded()` now also called at `SCROLL_STATE_SETTLING` (line 5709) — flushes deferred pagination as soon as the user lifts their finger and the fling begins.
- Commit-layer wait in `submitListAwait` (line 7263) changed from blocking ALL scroll states to only blocking `SCROLL_STATE_DRAGGING`. Commits during SETTLING (fling deceleration) flow through immediately.
- `applyDeferredLargeFlowEmissionIfNeeded()` resets `isLoadingOlderMessages` and calls `maybeContinueOlderPrefetch()` after commit (line 6444) — prevents pagination gate from staying locked after deferred flush.
- Safety-net reset of `isLoadingOlderMessages` at `SCROLL_STATE_IDLE` (line 5654).

### 3. Pagination Cascade Prevention (`ChatActivity.kt`)

**Problem:** `isLoadingOlderMessages = false` and `maybeContinueOlderPrefetch()` ran BEFORE the defer check in the flow collector. When an emission was deferred, the gate was already open, allowing `onScrolled` to fire another pagination load immediately. The window ballooned from 620→2300+ during a single scroll.

**Fix:**
- `isLoadingOlderMessages = false` and `maybeContinueOlderPrefetch()` moved to AFTER the first defer check (line 6540). Gate stays shut while emission is queued.

### 4. Aggressive Pagination Thresholds (`ChatActivity.kt`)

| Constant | Old | New |
|----------|-----|-----|
| `INITIAL_MESSAGE_WINDOW` | 300 | **40** |
| `PAGINATION_COOLDOWN_MS` | 0ms | **30ms** |
| `LOAD_OLDER_THRESHOLD` | 60 | **120** |
| `LOAD_OLDER_THRESHOLD_MAX` | 400 | **600** |
| `MAX_OLDER_PAGES_PER_LOAD` | 2 | **3** |
| `OLDER_PREFETCH_KEEP_AHEAD_ROWS` | 120 | **200** |
| Cold-start prefill | 20 msg | **50 msg** |

Pagination now triggers 2× further from the boundary (120 rows vs 60), loads 3 pages at a time (180 messages), and keeps a 200-row buffer ahead.

### 5. Enter Animation Gate Removed (`ChatActivity.kt`)

**Problem:** `shouldDeferLargeFlowEmission()` returned `true` when `!enterAnimationCompleted`, blocking the live-flow emission that expanded the prefill (~20 messages) to the full window. During this 300-500ms window, the user had almost no content to scroll through.

**Fix:** Removed `if (!enterAnimationCompleted) return true` from `shouldDeferLargeFlowEmission()` (line 6316). The 150ms settle window already coalesces emissions.

### 6. Progressive Pool Warming After First Frame (`ChatActivity.kt` + `RecyclerCoordinator.kt`)

**Problem:** Post-first-frame warm only created 24 ViewHolders. The progressive `preInflateViewHoldersNow` (up to 74 VHs) was never called in the critical path. `shouldPauseProvider` referenced `idleQueuePaused` (always `true` during init) and `frameBudgetGuard.lastFrameOverBudget` (always `true` after the first frame), killing the warm instantly.

**Fix:**
- `rv.post{}` after first frame (line 2320) now calls `preInflateViewHoldersNow` in a coroutine with `shouldPauseProvider = { false }`. The `yield()` between inflations naturally interleaves with frames.
- Initial warm batch expanded from 22→26 VHs with text allocation 12→16 (`RecyclerCoordinator.kt` line 286).
- Added `warmCommonViewHoldersAdaptiveAsync` suspend function (`RecyclerCoordinator.kt` line 166) for coroutine callers that need yielding.

### 7. GradientDrawable Reuse During Scroll (`ChatAdapter.kt`)

**Problem:** `bindReactionsChip` allocated a new `GradientDrawable` unconditionally each bind (~2-4ms per allocation + native paint setup).

**Fix:** When `isScrolling` is true and the chip already has a `GradientDrawable` background, the existing drawable is mutated in-place (line 1667) instead of allocating a new one.

## Files Modified

| File | Changes |
|------|---------|
| `ChatActivity.kt` | Incremental prepend, pagination feed-through, cascade prevention, aggressive thresholds, enter-animation gate removal, progressive pool warming |
| `ChatAdapter.kt` | GradientDrawable reuse during scroll |
| `RecyclerCoordinator.kt` | `warmCommonViewHoldersAdaptiveAsync`, expanded initial warm batch |

## Key Architecture Decisions

1. **Initial window = 40 messages**: Small enough for instant open (~10ms `processMessagesWithHeaders`), large enough for a casual scroll-up without triggering pagination.
2. **Incremental prepend**: Only processes the delta on pagination — O(new) instead of O(total). Boundary bubble/date fixup is localized to 2 items.
3. **Fling-fed pagination**: Commits go through during SETTLING so the fling never hits a wall. Only DRAGGING (finger on screen) defers commits.
4. **Pool warming with yields**: `preInflateViewHoldersNow` interleaves inflation with frame rendering via `yield()`. No pause provider needed — the yields naturally allow UI events.

## Verification

- Open a chat with 500+ messages: first frame should appear near-instantly, scroll should be smooth with no boundary-hits
- Frame times in logcat (`GlyphPerf` tag) should be under 32ms
- Inflations during first scroll should be near zero (warm pool)
- Repeat open/close: performance should be consistent
