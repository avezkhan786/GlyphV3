---
name: chat-list-scroll-bind-optimization
description: Scroll-jank hot paths in ChatListAdapter.bind() — caches, no per-bind allocations, Glide tag-skip
metadata:
  type: project
---

The chat list bind path went through a pass to eliminate scroll jank. Key takeaways:

1. **Theme colors** (MaterialColors.getColor) — must be resolved ONCE (lazy on first context available), not per bind. Cache as `Int` fields on the adapter.
2. **Style drawables** (ic_default_avatar, ic_double_check, bg_online_indicator, ic_group) — resolve via ContextCompat once, mutate, store as `lateinit var Drawable`.
3. **Letter-avatar GradientDrawable** — build ONE per palette color index (10 total, see `avatarColors`). Reuse across binds and viewholders. Same shape, same color — fully safe to share.
4. **SimpleDateFormat** — cache per-adapter instance (not thread-safe but bind path is main thread only). Re-resolves Locale.getDefault() when adapter is recreated.
5. **Color.parseColor("#…")** in bind — replace with compile-time `0xFF....toInt()` constants.
6. **Glide repeated calls** — store avatar key (e.g. `"L:$localPath"` or `"U:$url"`) in `setTag(<unique int>, key)` and only call Glide when key changes. Skips redundant binds during recycle/rebind.
7. **Glide.skipMemoryCache(true)** for local files is wrong: it forces re-decode every bind. Use `.signature(ObjectKey(file.lastModified()))` instead — invalidates on file change, hits memcache on rebind.
8. **Avatar mode transitions** (image ↔ letter) — keep a separate `MODE` tag on ImageView; reset drawable/background only when mode changes (cheaper than checking background identity).
9. **RecyclerView** — `initialPrefetchItemCount = 4` on LinearLayoutManager for adjacent prefetch during fling. `itemAnimator = null` to eliminate DefaultItemAnimator jank from DiffUtil changes (presence/text/badge updates).
10. **Click handler invocations** — guard with `bindingAdapterPosition != RecyclerView.NO_POSITION` to avoid `getItem(-1)`.

## Round 2 — when the bind cache wasn't enough

When bind-path allocations are zero'd out and scroll is still stuttery, the next places to look:

1. **AvatarCacheManager hot-path syscalls** — `File.exists() && File.length()` per cache hit is two `stat()` syscalls. During scroll over 100 chats, that's ~1200 main-thread syscalls/sec. Trust the in-memory index; Glide's load failure tile handles stale entries.
2. **`View.text?.toString() != str`** — `CharSequence.toString()` *allocates a new String* per check. For per-frame text guards across 4 fields, that's ~2.4k String allocs/sec on a 60FPS scroll. Use a fast-path helper that compares via `===` first, then `is String` fallback (no allocation in the common unchanged case).
3. **Presence-driven Chat rebuilds** — `combine(presenceStateFlow)` re-triggers `localChats.map { Chat(...) }` on *every* presence tick (potentially many/sec). Memoize Chat instances by id: keep the prior `Chat` and reuse it when contents are structurally equal. Also coalesce the presence flow with `debounce(120).distinctUntilChanged()` so bursty ticks collapse into one rebuild.
4. **submitList thrash** — even with structural identical lists, `ListAdapter.submitList()` enqueues a DiffUtil diff task on the background executor each call. Guard the call: `if (lastSubmitted === null || !lastSubmitted.contentEquals(chats)) chatAdapter.submitList(chats)` skips the redundant diff dispatch.
5. **`Flow.combine` is fragile** — any upstream emission re-fires each downstream `combine` block even if its inputs didn't change. `distinctUntilChanged()` on each upstream flow helps isolate the changes that actually matter.

The pattern: **the bind path is rarely the full story on a janky list — the per-bind getters (avatar paths, lookups) and the upstream flow chain are usually where the actual ms go.**

## Round 3 — mirror the privacy/locked screen pattern

When caches, syscall stripping, and debouncing still don't fix it, look at how a sibling screen that's *known to scroll smoothly* is implemented and mirror its mechanics.

In Glyph, the smooth list is the Compose `StatusPrivacyScreen` / `LockedChatsScreen`. Mechanics that translate directly into RecyclerView:

1. **Stable keys** — `items(state.contacts, key = { it.id })` → equivalent in RecyclerView is `DiffUtil.areItemsTheSame` keyed on `chat.id`. Confirmed already.
2. **`remember(key1, key2) { … }` for derived values** — Compose only recomputes derived values when inputs change. RecyclerView equivalent is **payload-based partial binds**: DiffUtil returns a `getChangePayload(old, new)` describing which fields changed, and `onBindViewHolder(holder, pos, payloads)` calls a `bindPartial()` that touches only those views. Presence flips no longer cause a full rebind of the row.
3. **`AsyncImage(model = ImageRequest.Builder(...).memoryCacheKey(...).diskCacheKey(...).build())`** — explicit cache keys on the image request. Glide equivalent: signature-based load (`ObjectKey(file.lastModified())`), `placeholders` from cached `Drawable`, plus an ImageView tag guard so repeat binds don't re-issue the load.
4. **Hoisted shared state** — sender-name map outside the row composable so it doesn't re-create per cell. RecyclerView equivalent: a per-adapter field (private map) so derived strings don't recompute per row.
5. **No per-frame animation** — Compose's `key`-based recomposition is the natural scroll-stable. RecyclerView equivalent: `itemAnimator = null` (already set).
6. **`produceState` / `StateFlow.collectAsState()`** — single source of truth for the list state, fed by the upstream Flow. RecyclerView equivalent: `ListAdapter.submitList(state)` once per state change, with a guard so structural-equal lists never submit.

The full bind now consists of:
- `bind(chat)` — full row rebind (only fires on item-position change or full payload)
- `bindPartial(chat, payloads)` — touches only the diff-flagged fields (unread, lastMessage, online, timestamp), avoids `requestLayout()` on the rest
- A payload-cache check: `payloads.contains(PAYLOAD_REBIND_ALL)` falls through to a full rebind
- Text-result cache keyed on `chat.id` (displayName) and `date.time` (timestamp) with bounded LRU eviction via `LinkedHashMap.removeEldestEntry`

Why this matters for jitter: even with no allocations and tag-skipping, a *full* bind still touches ~7 view setters per row per scroll-back. A presence-only diff used to do that for every visible row. Payload-based dispatch routes that to a single visibility toggle.

Why: [[chat-prewarm-flow-parity-invariant]] shares the same hot-path plumbing and is sensitive to scrolls; same cache discipline applies to chat-row prefetch which is built from this adapter.
