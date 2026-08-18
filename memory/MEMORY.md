# Memory Index

- [Chat prewarm/flow parity invariant](chat-prewarm-flow-parity-invariant.md) — cold-start reflow guard: prewarm snapshot must field-match the Room flow mapping
- [Chat list scroll-bind optimization](chat-list-scroll-bind-optimization.md) — caches, no per-bind allocations, Glide tag-skip pattern used in ChatListAdapter
- [Chat list active screen — Compose vs RecyclerView](chat-list-active-screen.md) — ChatListComposeFragment hosts the live LazyColumn; optimize it, not the legacy ChatListFragment
- [AndroidView nestedScroll bridge](androidview-nestedscroll-bridge.md) — AndroidView needs nestedScroll modifier on itself to bridge Android → Compose nested scrolling
- [submitListSync: synchronous diff bypass for RecyclerView](submitlistsync-synchronous-diff.md) — bypasses AsyncListDiffer's background-thread diff to eliminate one-frame delay on reactive list updates
- [Single source of truth for submitListSync](list-submit-sync-consolidation.md) — avatarStateTrigger consolidates all submitListSync calls into AndroidView.update callback, eliminating dual-call ping-pong that caused user-row flashing on cold start
