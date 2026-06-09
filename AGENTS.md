# GlyphV3 — Agent Instructions

GlyphV3 is a WhatsApp-like Android chat app written in Kotlin, backed by Firebase.

## Build Commands

```powershell
# Compile only (fast iteration)
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain

# Full debug build
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain

# With stacktrace on failures
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --stacktrace
```

Always build after code changes to verify correctness. Fix all errors before proposing the solution as complete.

## Project Structure

```
app/src/main/java/com/glyph/glyph_v3/
├── GlyphApplication.kt       # App singleton; repository factory methods
├── data/
│   ├── local/                # Room DB (AppDatabase v35), DAOs, entities
│   ├── models/               # Data classes (LocalMessage, LocalChat, …)
│   ├── repo/                 # Repositories (one per domain)
│   ├── service/              # Foreground services, WorkManager workers
│   └── cache/                # In-memory + disk caches (Avatar, Status, MessagePreview…)
└── ui/
    ├── chat/                 # ChatActivity + ChatFragment + ChatAdapter + ChatViewModel
    ├── chatlist/             # Chat list (Compose)
    ├── groups/               # CreateGroupActivity, GroupInfoActivity (Compose)
    ├── status/               # Status viewer/recorder
    ├── calls/ walkietalkie/  # Voice call + walkie-talkie UI
    ├── settings/             # Settings screens
    ├── theme/                # Theme tokens + providers
    └── …
```

## Architecture

- **MVVM + Repository**: ViewModels own UI state; Repositories own data access.
- **Firebase**: Firestore (message history), RTDB (real-time delivery + typing), Storage (media), Auth, FCM (push), Functions (fan-out).
- **Room** (v35): Local cache; always write explicit `addMigrations()` when bumping the version.
- **WorkManager**: Required for durable background work (delivery receipts, upload retries). Detached coroutines will lose writes when the process is reclaimed.
- **Dual ingestion path**: Every field that exists on a `LocalMessage` must be extracted in **both** `processIncomingMessage()` (RTDB) and `syncMessages()` (Firestore) in `RealtimeMessageRepository.kt`. Omitting a field in one path causes it to vanish after app restart / history sync.

## Theme System

All Activities and Compose screens must follow this pattern:

```kotlin
// In every Activity — BEFORE super.onCreate()
ThemeManager.applyTheme(this)
super.onCreate(savedInstanceState)
```

```kotlin
// Wrap every Compose screen
GlyphThemeProvider {
    YourScreen()
}
// Access tokens via glyphTheme.*:
//   glyphTheme.backgroundPrimary / backgroundSecondary / backgroundElevated
//   glyphTheme.textPrimary / textSecondary / textTertiary
//   glyphTheme.actionPrimary / actionDestructive
//   glyphTheme.surfaceHeader / surfaceNavigation
```

- **Do NOT** call `GlyphTheme(tokens)` directly.
- For XML layouts use `?attr/glyphTextPrimary`, `?attr/glyphAccent` (not `glyphActionPrimary`).

## Key Patterns

### ChatActivity
Open a chat via the factory — EXTRA_* constants are `private`:
```kotlin
ChatActivity.newIntent(context, chatId, otherUserId, otherUsername, otherUserAvatar)
// Groups: otherUserId = "", otherUsername = groupName
```

### Group Chats
- Group IDs are prefixed `group_<uuid>`; 1:1 IDs remain `uid1_uid2` (sorted).
- `LocalChat` carries: `isGroup`, `groupName`, `groupIconUrl`, `groupDescription`, `participantsJson`, `adminsJson`.
- `GroupChatRepository` is the singleton; access via `GlyphApplication.getOrCreateGroupChatRepository()`.
- `MessageType.SYSTEM` covers join/leave/admin events — all `when` exhaustive sites must handle it.

### Identity
- `Message.senderId` is a **Firebase UID**, not a phone number. Always compare with `FirebaseAuth.getInstance().currentUser?.uid`.

### Forwarding
- Cache via `ForwardMessageCache`, launch `ForwardSelectionActivity`. Never pass large message objects through Intents.

### Singleton Repositories
Access via `GlyphApplication.getOrCreate*()` helpers (e.g. `getOrCreateRealtimeRepository()`). Resolve on `Dispatchers.IO`, not on the main thread.

## Drawable & Resource Pitfalls

Drawables that **exist**: `ic_arrow_back`, `ic_check`, `ic_close`, `ic_search`, `ic_camera`, `ic_chevron_right`, `ic_group`, `ic_edit`.  
Drawables that **do NOT exist**: `ic_arrow_forward`, `ic_group_add` — use `ic_chevron_right` / `ic_group` instead.  
Circular avatar background: `@drawable/bg_avatar_circle`.

## Compose Pitfalls

- `togetherWith` infix for animated content transitions: keep it and the right operand on the **same line**, or assign to `val` first. Multi-line infix parses as two statements.
- Import `androidx.compose.animation.togetherWith` explicitly (or use wildcard `androidx.compose.animation.*`).
- Do not assign `mutableState` directly inside `withContext(Dispatchers.IO)` — update on main thread.

## Chat Performance — Critical Rules

See [CHAT_OPEN_INSTANT_PLAYBOOK.md](CHAT_OPEN_INSTANT_PLAYBOOK.md) for the full playbook.

- **Glide density must match exactly**: use `Resources.getSystem().displayMetrics.density` everywhere in preload sizing — not `resources.displayMetrics.density` — or Glide produces different `EngineKey` values and causes placeholder flashes.
- **Retained FutureTargets**: never call `future.cancel(false)` on completed preload futures; Glide's `ActiveResources` pins resources via strong refs on the Target — releasing prematurely evicts them before the first bind.
- **CollageImageView**: never call `clearForRecycle()` from `onViewDetachedFromWindow` (normal scroll detach). Only clear in `onViewRecycled`.
- **RecyclerView clip**: set `clipChildren="false"` on both the RecyclerView host and item root containers when using scale/translate animations above 1.0.
- **First-scroll suppression**: defer `schedulePreInflateViewHolders` and `queueScrollMediaWarmupIfNeeded` while `firstScrollTrackingActive == true`.
- **Logging gates**: verbose media-bind logs behind `ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG = false`; first-scroll/IME summaries under `adb shell setprop log.tag.ChatPerfDebug DEBUG`.

## Firebase Security Rules

- `firestore.rules` — Firestore access rules (chat participants, `create` vs `update/delete` split for message spoofing prevention).
- `storage.rules` — Storage access gated on `isChatParticipant(chatId)` helper.
- `database.rules.json` — RTDB rules.
- Deploy: `firebase deploy --only firestore:rules,storage`
- Tokenized download URLs (`?alt=media&token=…`) bypass Storage rules — existing recipients fetching via Glide are unaffected.

## Useful Documentation

| File | Topic |
|------|-------|
| [APP_FEATURES_OVERVIEW.md](APP_FEATURES_OVERVIEW.md) | Full feature inventory |
| [ARCHITECTURE_DIAGRAMS.md](ARCHITECTURE_DIAGRAMS.md) | Component & data-flow diagrams |
| [CHAT_OPEN_INSTANT_PLAYBOOK.md](CHAT_OPEN_INSTANT_PLAYBOOK.md) | Chat-open performance strategy |
| [MESSAGE_DELIVERY_LATENCY_TROUBLESHOOTING.md](MESSAGE_DELIVERY_LATENCY_TROUBLESHOOTING.md) | Delivery latency debug guide |
| [MESSAGE_ROW_OPTIMIZATIONS_COMPLETE.md](MESSAGE_ROW_OPTIMIZATIONS_COMPLETE.md) | Message row perf decisions |
| [AVATAR_CACHING_IMPLEMENTATION.md](AVATAR_CACHING_IMPLEMENTATION.md) | Avatar cache architecture |
| [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) | Firebase deploy steps |
