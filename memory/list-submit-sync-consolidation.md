---
name: list-submit-sync-consolidation
description: Consolidate all submitListSync calls into AndroidView.update callback via avatarStateTrigger
metadata:
  type: reference
---

## Problem

After fixing the "Glyph Official" cold-start delay (by calling `OfficialContentRepository.startListening()` from `ChatListComposeFragment.onCreate()`), user rows began "subtly flashing like they are re-rendering."

## Root Cause: Dual submitListSync ping-pong

Two independent code paths were calling `submitListSync` with **different lists** that each contained **stale avatar state** relative to the other:

1. **`AndroidView.update` callback** — calls `submitListSync(items)` where `items` comes from `remember(filteredChats, …)`. The `avatarStateVersion` fields in `items` reflect the avatar state at the last `remember` recompute (which only triggers on data/key changes, NOT avatar downloads).

2. **`Avatar LaunchedEffect`** — calls `submitListSync(avItems)` where `avItems` is built fresh via `buildChatListItems(...)` which reads the **latest** `AvatarStateManager.peek()` values (post-download).

When avatars download during cold start, the sequence was:
- LaunchedEffect fires → submits list with **new** avatars → rows show downloaded avatars
- A recomposition triggers the `update` callback → submits list with **stale** avatars → rows revert to placeholder → **flash**
- If another avatar state change fires → LaunchedEffect fires again → rows show downloaded avatars again

## Solution

Eliminate the dual-call conflict by making the `LaunchedEffect` only **trigger recomposition** (via a `var avatarStateTrigger by remember { mutableStateOf(0) }` counter) instead of calling `submitListSync` directly.

- `avatarStateTrigger` is added as a key to the `items` `remember` block
- When avatar states change, `avatarStateTrigger++` causes `items` to recompute via `buildChatListItems` (which reads fresh `AvatarStateManager.peek()` values)
- The `AndroidView.update` callback then calls `submitListSync` with the updated list — **the single source of truth**
- `DiffUtil` correctly identifies only the rows whose `avatarStateVersion` changed, and `ChatRowViewHolder.bindPayloads` applies only the `[Avatar]` payload via Glide's tag-skip pattern (no unnecessary image reloads)
