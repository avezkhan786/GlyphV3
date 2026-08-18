# SplashActivity "Extra Logo Screen" Between Splash and Chat List

## Symptom

On cold start, after the system splash dismisses there is a brief intermediate "screen" that
shows only the centered Glyph app logo on a solid background, *before* the chat list
finally appears. The user sees three frames instead of two:

1. **System splash** — Android 12+ default (launcher icon on app background)
2. **"Extra logo" screen** — centered `ic_splash` logo over `@color/splash_background`
3. **Chat list** — MainActivity content

The third step should arrive immediately after the first. The second step is not
intentional — it is the system splash activity's branded window-background becoming
visible because `SplashActivity` is staying alive longer than expected.

## Root Cause

`SplashActivity` uses the theme `Theme.GlyphV3.SplashBranded` (declared in
`app/src/main/res/values/themes.xml` and applied via `AndroidManifest.xml`). Its key
attribute is:

```xml
<item name="android:windowBackground">@drawable/splash_background</item>
```

Where `splash_background.xml` is a layer-list that paints `@drawable/ic_splash` centered
on `@color/splash_background` (`#000000`). So while `SplashActivity` is in the foreground
but not yet finished, the *window background* is what the user actually sees — and that
background *is* a logo.

The activity is normally fast enough (it just launches `MainActivity` or `WelcomeActivity`
from `onCreate`) that the OS dismisses it before its window background is ever observable.
**Anything** that keeps the activity resident longer — a network round-trip, a disk read,
or any suspending coroutine on its `lifecycleScope` — exposes the logo.

### First incident (commit `c9e39a6`) — addressed by commit `3329872`

The auth-flow refactor introduced a blocking token refresh on `SplashActivity`'s
`lifecycleScope`:

```kotlin
if (currentUser != null) {
    lifecycleScope.launch {
        try {
            currentUser.getIdToken(true).await()   // ← BLOCKS the splash
        } catch (e: Exception) { ... }
        checkForBackupAndRoute()
    }
}
```

`lifecycleScope.launch { … .await() … }` suspends on `SplashActivity`'s coroutine scope.
While suspended, the activity is not finished — so its branded window background (the
centered app icon) stays on screen for the entire Firebase Auth round-trip. That was the
original "extra logo screen."

Commit `3329872` removed that `lifecycleScope.launch` wrapper, restoring a synchronous
`checkForBackupAndRoute()` call. The blocking `await` was eliminated because token refresh
already runs non-blocking in two other places:

1. `GlyphApplication.onCreate()` — `currentUser.getIdToken(true).addOnSuccessListener { }`
2. `MainActivity.ensureAuthenticated()` — same non-blocking pattern

### Second incident — the backup-check coroutine (remaining after `3329872`)

Even after removing the token-refresh `await`, the `checkForBackupAndRoute()` method
launched a **`CoroutineScope(Dispatchers.IO).launch { … }`** that still held
`SplashActivity` alive while doing potentially-slow work:

1. `BackupPreferences.shouldShowRestoreOffer()` — a DataStore `data.first()` read
   (disk I/O, first access on cold start can take 100-200 ms).
2. `GoogleSignInRepository.silentSignIn()` — a Google Sign-In network round-trip.
3. `DriveRepository.listBackups()` — a Google Drive REST API call (hundreds of ms to
   seconds on a cold network).

During all of this I/O, `SplashActivity` was not finished, so its branded window
background (the centered logo) remained visible. For a new user (never marked the restore
offer as seen), the silent sign-in + Drive API call could keep the logo on screen for
several seconds.

**Additionally**, `goToMain()` (and the `startActivity(RestoreOfferActivity)` path) was
called from within the IO coroutine — i.e. on a background thread. `overrideTransition()`
calls `overridePendingTransition()`, which requires the main thread and would throw
`CalledFromWrongThreadException`. The `catch (_: Exception)` swallowed the first failure,
but the second `goToMain()` (outside the try block) could crash the coroutine or leave
`SplashActivity` unfinishef (if `startActivity` succeeded but `overridePendingTransition`
threw before `finish()`).

The doc's original "general rule" claimed `CoroutineScope(Dispatchers.IO).launch` was
safe because "the callback launch already returned before the IO starts." That reasoning
was **incorrect** — the activity is only released after `finish()` is called *inside* the
coroutine, so the IO work still delays the transition.

## Fix Applied

### SplashActivity (`SplashActivity.kt`)

Removed `checkForBackupAndRoute()` entirely (and its now-unused imports). For authenticated
users, `continueToApp()` now calls `goToMain()` directly — no coroutine, no DataStore read,
no network call. `SplashActivity` finishes in `onCreate` and the system splash
transitions straight to the chat list.

```kotlin
if (currentUser != null) {
    // Route immediately to MainActivity. Token refresh for Firestore
    // (PERMISSION_DENIED protection) runs non-blocking in
    // GlyphApplication.onCreate() and MainActivity.ensureAuthenticated().
    // The backup/restore offer check is handled by MainActivity after its
    // first frame renders, so SplashActivity's branded window background
    // (centered app icon) is never visible as an extra intermediate frame.
    goToMain()
}
```

### MainActivity (`MainActivity.kt`)

Added `checkForBackupRestore()` — the backup/restore offer check now runs on
`lifecycleScope` (`Dispatchers.IO`), **after** the chat list's first frame is released by
the cold-start first-draw gate. If a restore-worthy backup is found, `RestoreOfferActivity`
is started (from the main thread via `withContext(Dispatchers.Main)`) on top of the
already-visible chat list.

The first-frame gate release point triggers the check:
- **Cold start**: the `ViewTreeObserver.OnPreDrawListener` calls `checkForBackupRestore()`
  when the gate is released (either via `onChatListFirstFrameReady` or the 500 ms safety
  timeout).
- **Rotation / process-restore**: `binding.root.post { checkForBackupRestore() }` runs
  after the first layout pass.

Key properties of the new method:
- **Idempotent**: guarded by `backupCheckStarted` flag and the `currentUser != null` check.
- **Lifecycle-safe**: uses `lifecycleScope`, cancelled when the activity is destroyed.
- **Main-thread navigation**: `withContext(Dispatchers.Main)` before `startActivity`,
  fixing the `CalledFromWrongThreadException` that `overrideTransition` would have thrown
  from the IO thread.

### RestoreOfferActivity (`RestoreOfferActivity.kt`)

Updated `goToMain()` to use `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`:

```kotlin
private fun goToMain() {
    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
    finish()
}
```

- **Started from MainActivity**: `CLEAR_TOP` finds the existing `MainActivity` in the task
  stack and brings it forward, clearing `RestoreOfferActivity`. `SINGLE_TOP` delivers the
  intent via `onNewIntent` instead of recreating the activity — preserving the already-loaded
  chat list (no re-initialization, no shimmer).
- **Started from SplashActivity** (fallback): `MainActivity` is not in the stack, so a new
  instance is created — same as before.

## Verification

```
./gradlew :app:compileDebugKotlin   # BUILD SUCCESSFUL
```

## How to Avoid This in the Future

> **`SplashActivity` must finish as quickly as possible. Never `await` it. Never perform
> disk I/O or network calls from it.**

Concretely:

- **No suspending work in `SplashActivity.onCreate`** — no `lifecycleScope.launch`,
  no `.await(...)`, no `runBlocking`, no `Thread.sleep`. The activity should only
  inspect auth state (synchronous `FirebaseAuth.currentUser`) and `startActivity` +
  `finish()`.
- **Move multi-step routing logic to the destination activity.** If a check (e.g. backup
  restore, deep-link resolution) is needed, perform it in `MainActivity` after the first
  frame — not in the splash.
- **Always call `startActivity` / `overridePendingTransition` / `finish` on the main
  thread.** If work runs on `Dispatchers.IO`, switch with `withContext(Dispatchers.Main)`
  or `runOnUiThread { }` before any Android navigation call.
- **If `SplashActivity`'s launch path ever needs to grow beyond a single quick
  `startActivity() + finish()`**, change the theme to `Theme.GlyphV3` (no branded splash)
  before adding the work, so even if the activity lingers the user does not see an extra
  center-icon frame.

## How to Diagnose If a Similar Bug Reappears

If you ever see "an extra blank screen", "an extra logo screen", or "the splash takes
too long" between app launch and `MainActivity`:

1. **`SplashActivity.onCreate` / `continueToApp`** — search for any `lifecycleScope.launch`,
   `.await(...)`, `runBlocking`, `Thread.sleep`, DataStore `.first()`, or repository calls.
   Any of these hold the activity alive across that work.
2. **The route to `MainActivity`** — confirm `SplashActivity` calls `goToMain()` directly
   with no coroutine wrapper. The backup/restore check now lives in
   `MainActivity.checkForBackupRestore()`.
3. **`Theme.GlyphV3.SplashBranded`** — confirm this is still the theme applied to
   `.SplashActivity` in `AndroidManifest.xml`. If yes, the activity's window background
   will paint a centered `ic_splash` logo for as long as the activity lives — so the
   activity must finish before any visible work starts.
4. **Threading** — verify any `startActivity` / `finish` / `overridePendingTransition`
   call is on the main thread. Background-thread calls to these methods throw
   `CalledFromWrongThreadException`.

## Related Files

- `app/src/main/java/com/glyph/glyph_v3/SplashActivity.kt` — now routes directly to
  MainActivity; no backup check
- `app/src/main/java/com/glyph/glyph_v3/MainActivity.kt` — added `checkForBackupRestore()`
  (runs after first frame, main-thread navigation)
- `app/src/main/java/com/glyph/glyph_v3/ui/onboarding/RestoreOfferActivity.kt` —
  `goToMain()` uses `CLEAR_TOP | SINGLE_TOP` to reuse MainActivity
- `app/src/main/res/values/themes.xml` — defines `Theme.GlyphV3.SplashBranded`
- `app/src/main/res/drawable/splash_background.xml` — the centered-logo layer-list
- `app/src/main/AndroidManifest.xml` — applies the splash theme to `.SplashActivity`
- `app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt` — non-blocking token refresh
  in `onCreate()`
