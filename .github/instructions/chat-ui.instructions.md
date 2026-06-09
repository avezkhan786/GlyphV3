---
description: 'Use when editing, creating, or reviewing any file under ui/chat/ — ChatActivity, ChatAdapter, ChatViewModel, ChatFragment, CollageImageView, ChatOpenPrefetcher, or any chat bubble/media/input component. Covers ChatActivity open pattern, message ingestion, adapter bind rules, media preload conventions, forwarding, reply, and RecyclerView pitfalls.'
applyTo: "app/src/main/java/com/glyph/glyph_v3/ui/chat/**"
---

# Chat UI Conventions

## Opening a Chat

Always use the factory method — `EXTRA_*` constants are `private`:
```kotlin
ChatActivity.newIntent(context, chatId, otherUserId, otherUsername, otherUserAvatar)
// Groups: otherUserId = "", otherUsername = groupName
```

Call `ChatOpenPrefetcher.primeChatOpen(context, chatId, messages)` **before** `startActivity` (bounded 150 ms timeout) so retained Glide futures are ready at first bind.

## Message Ingestion — Dual Path

Every field on `LocalMessage` must be extracted in **both** `processIncomingMessage()` (RTDB) and `syncMessages()` (Firestore) in `RealtimeMessageRepository.kt`. Omitting a field from one path makes it vanish after app restart/history sync.

## Identity

`Message.senderId` is a Firebase UID — always compare with `FirebaseAuth.getInstance().currentUser?.uid`, never a phone number.

## ChatAdapter Bind Rules

- Use `Resources.getSystem().displayMetrics.density` for all pixel size calculations — not `resources.displayMetrics.density`.
- Try seeded drawable reuse (`retainedMediaPreloadFutures`) before any Glide `.load()`.
- Never attach a retained `BitmapDrawable` directly to an `ImageView` — copy the bitmap first via `copyDrawableForSeed()`.
- `bindReactionsChip`: ConstraintLayout roots use the stable constraint path; FrameLayout roots use a child-view fallback that mutates padding. Prefer ConstraintLayout for all media row roots.

## CollageImageView

- **Never call `clearForRecycle()` from `onViewDetachedFromWindow`** (normal scroll detach). Only `onViewRecycled` may clear state.
- Scroll-start must preserve existing tiles (blur/preserve), not call `loadImages()` and clear cells.
- Cap rendered tiles at 4; show `+X` overlay via a separate count field.
- Guard every Glide load against blank models (`load("")` logs `Load failed for []`).

## RecyclerView Clip

Set `clipChildren="false"` on both the RecyclerView host view and item root containers when using scale/translate animations that exceed 1.0, or bubbles will clip at the row boundary.

## Forwarding

Use `ForwardMessageCache` to cache messages by token; launch `ForwardSelectionActivity`. Never put large `LocalMessage` objects in Intents directly.

## Reply

- Scroll-to-reply: if the target index is −1, show a Toast ("Original message not available") instead of silently doing nothing.
- Use `scrollToItem` + short delay (not `animateScrollToItem`) for reliable positioning before highlight.

## First-Scroll Suppression

Defer `schedulePreInflateViewHolders` and `queueScrollMediaWarmupIfNeeded` until `firstScrollTrackingActive == false`. Running them during the first scroll causes frame drops and log spam.

## Logging

- Gate verbose bind/collage logs behind `ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG = false`.
- First-scroll + IME timing: `adb shell setprop log.tag.ChatPerfDebug DEBUG`.
