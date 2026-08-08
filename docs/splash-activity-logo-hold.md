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
on `@color/splash_background`. So while `SplashActivity` is in the foreground but not yet
finished, the *window background* is what the user actually sees — and that background
*is* a logo.

The activity is normally fast enough (it just launches `MainActivity` or
`WelcomeActivity` from `onCreate`) that the OS dismisses it before its window
background is ever observable.

**However**, `SplashActivity` had this block introduced during the auth-flow refactor
(commit `c9e39a6`):

```kotlin
if (currentUser != null) {
    lifecycleScope.launch {
        try {
            currentUser.getIdToken(true).await()   // ← BLOCKS the splash
        } catch (e: Exception) {
            Log.w("SplashActivity", "Token refresh failed, proceeding anyway", e)
        }
        checkForBackupAndRoute()
    }
}
```

The `lifecycleScope.launch { … .await() … }` suspends on `SplashActivity`'s coroutine
scope. While suspended, the activity is not finished — so its branded window background
(introduced by `Theme.GlyphV3.SplashBranded`) stays on screen for the entire Firebase
Auth round-trip. **That is the "extra logo screen" the user observes.**

Every async path through `SplashActivity` is in some sense at risk of this — anything
that calls `lifecycleScope.launch { /* awaiting */ }` from `onCreate` will keep the
activity resident long enough for the logo background to show.

## Why the Token Refresh in SplashActivity Was Redundant

Token refresh for Firestore/Permission-Denied protection already runs in two other
places, **non-blocking**, **before** the chat list's first frame:

1. `GlyphApplication.onCreate()`, around line 191 — the first thing executed in the
   process, on `addOnSuccessListener`:
   ```kotlin
   if (currentUser != null) {
       currentUser.getIdToken(true)
           .addOnSuccessListener { ... }
           .addOnFailureListener { ... }
   }
   ```
2. `MainActivity.ensureAuthenticated()`, around line 647 — the activity does not block
   first frame on it:
   ```kotlin
   auth.currentUser?.getIdToken(true)
       ?.addOnSuccessListener { }
       ?.addOnFailureListener { ... }
   ```

Both sites were explicitly commented that they exist to prevent PERMISSION_DENIED on
Firestore listeners. So awaiting the refresh in `SplashActivity` adds nothing — it
only adds visible delay.

## Fix Applied

In `app/src/main/java/com/glyph/glyph_v3/SplashActivity.kt`:

1. Removed the `lifecycleScope.launch { currentUser.getIdToken(true).await(); … }`
   wrapper around `checkForBackupAndRoute()`, restoring the synchronous call shape
   that existed before commit `c9e39a6`.
2. Removed the now-unused imports:
   - `androidx.lifecycle.lifecycleScope`
   - `kotlinx.coroutines.tasks.await`
3. Replaced the misleading "CRITICAL: Refresh the auth token BEFORE launching
   MainActivity" comment with one explaining why we *don't* await here.

```kotlin
if (currentUser != null) {
    // Firestore token refresh runs non-blocking in GlyphApplication.onCreate()
    // and MainActivity.ensureAuthenticated(); both refresh in the background
    // without holding the first frame of the chat list. Awaiting it here would
    // keep SplashActivity's branded window background (centered app icon) on
    // screen for the entire network round-trip — creating an extra visible
    // "logo screen" between the system splash and the chat list. So we just
    // route.
    checkForBackupAndRoute()
} else {
    startActivity(Intent(this, WelcomeActivity::class.java))
    overrideTransition()
    finish()
}
```

## Verification

```bash
./gradlew :app:compileDebugKotlin      # BUILD SUCCESSFUL
```

## How to Avoid This in the Future

The general rule:

> **`SplashActivity` must finish as quickly as possible. Never `await` it.**

Concretely:

- Any Firebase / network call issued from `SplashActivity.onCreate` MUST be
  fire-and-forget — use `addOnSuccessListener` / `addOnFailureListener`, not `await`.
- If work *must* be awaited before launching the next activity, issue it inside
  `CoroutineScope(Dispatchers.IO).launch { … }` (as `checkForBackupAndRoute` does),
  not inside `lifecycleScope.launch { … }`. The `Dispatchers.IO` callback launch
  already returned before the IO starts, so the activity is only held by Android's
  activity-finish plumbing, not by an in-flight suspending coroutine visible to the
  user.
- If `SplashActivity`'s launch path ever needs to grow beyond a single quick
  `startActivity() + finish()`, **change the theme to `Theme.GlyphV3` (no branded
  splash)** before adding the work, so even if the activity lingers the user does
  not see an extra center-icon frame.

## How to Diagnose If a Similar Bug Reappears

If you ever see "an extra blank screen", "an extra logo screen", or "the splash
takes too long" between app launch and `MainActivity`, check the following in order:

1. **`SplashActivity.onCreate`** — search it for any `lifecycleScope.launch`,
   `.await(...)`, `runBlocking`, or `Thread.sleep`. Each of these holds the activity
   alive across that work.
2. **The route to `MainActivity`** — find the call site and confirm route completion
   uses an IO-launched coroutine (`CoroutineScope(Dispatchers.IO).launch { … }`),
   not `lifecycleScope`.
3. **`Theme.GlyphV3.SplashBranded`** — confirm this is still the theme applied to
   `SplashActivity` in `AndroidManifest.xml`. If yes, the activity's window background
   will paint a centered `ic_splash` logo for as long as the activity lives.
4. **Compare with `MainActivity.ensureAuthenticated`** — token refresh there is done
   non-blocking on `addOnSuccessListener` for the same reason; any future direct
   caller of `getIdToken(true).await()` from a launcher activity is suspect.

## Related Files

- `app/src/main/java/com/glyph/glyph_v3/SplashActivity.kt` — the file that was fixed
- `app/src/main/res/values/themes.xml` — defines `Theme.GlyphV3.SplashBranded`
- `app/src/main/res/drawable/splash_background.xml` — the centered-logo layer-list
- `app/src/main/AndroidManifest.xml` — applies the splash theme to `.SplashActivity`
- `app/src/main/java/com/glyph/glyph_v3/MainActivity.kt` — alreadycribes `ensureAuthenticated()`
  for non-blocking token refresh
- `app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt` — does the same in
  `Application.onCreate()`
