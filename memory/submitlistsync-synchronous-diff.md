---
name: submitlistsync-synchronous-diff
description: submitListSync bypasses AsyncListDiffer's background-thread diff to eliminate one-frame delay on reactive list updates
metadata:
  type: reference
  originSessionId: 22bb8bb1-3b51-4fd5-bcd9-84aba952f256
  modified: 2026-08-16T00:00:00.000Z
---

## Problem

`ListAdapter.submitList()` routes through `AsyncListDiffer`, which runs `DiffUtil` on a
background thread and posts results back to the main thread. This background-thread hop
+ main-thread post adds a one-frame (~16ms) delay. Compose's `LazyColumn` does not have
this delay — it computes its diff synchronously during recomposition.

This delay was visible as "Glyph Official chat displays after a slight delay" on cold
start: the cache seed emits from `OfficialContentRepository.seedMessagesFromCache()`,
recomposition happens, `submitList` dispatches into `AsyncListDiffer`, one frame passes
before the diff result lands on the main thread.

## Solution

Add a `submitListSync` method to the adapter that:

1. Calculates `DiffUtil.calculateDiff` synchronously on the calling thread (main thread).
   For a chat list (< ~100 items) the CPU cost is measured in microseconds, far below
   the 16ms frame budget.
2. Updates an internal `mSyncList` field.
3. Dispatches results via `diffResult.dispatchUpdatesTo(this)` — fully synchronous.

The adapter also overrides `getItemCount()` and `getItem()` to read from `mSyncList`
when in sync mode, completely bypassing `AsyncListDiffer`.

## Usage

Replace **all** `adapter.submitList(list)` calls in the `AndroidView` path with
`adapter.submitListSync(list)`. This must be done in:
- The `remember {}` block (adapter pre-population)
- The `AndroidView`'s `update` callback
- Any `LaunchedEffect` that re-submits the list (e.g. avatar state changes)

`submitListSync` is on `ChatListScreenAdapter` (the new adapter created for the
RecyclerView path), not on the legacy `ChatListAdapter`.
