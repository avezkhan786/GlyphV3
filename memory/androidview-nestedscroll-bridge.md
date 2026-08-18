---
name: androidview-nestedscroll-bridge
description: AndroidView needs nestedScroll modifier on itself to bridge Android → Compose nested scrolling
metadata:
  type: reference
---

When embedding a RecyclerView (or any scrolling Android View) via `AndroidView` inside a Compose `Box` that has `Modifier.nestedScroll(connection)` on the parent, the parent's `NestedScrollConnection.onPreScroll` is NOT called for the embedded View's scroll events.

**Fix:** Apply `Modifier.nestedScroll(revealConnection)` directly to the `AndroidView` composable. This explicitly wires the bridge between the Android View's `dispatchNestedPreScroll` (NestedScrollingChild) and the Compose `NestedScrollConnection`.

Also: use `recyclerView.canScrollVertically(-1)` to check if the list is at the top — it's robust against `setPadding`/`clipToPadding = false` offsets, unlike checking `findFirstVisibleItemPosition() == 0 && itemView.top == 0` (which fails because `itemView.top` equals the padding value when at the true top).
