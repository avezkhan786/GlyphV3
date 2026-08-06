# Authentication Flow Redesign — Implementation Document

**Version:** 1.1.0  
**Date:** 2026-08-06  
**Branch:** master

---

## 1. Motivation

The previous authentication flow used a single `LoginActivity` (ViewBinding) with a bare `EditText` for phone number input and another for OTP entry. There was no welcome/onboarding screen, no country code picker, no proper OTP input UI, and no animations. The goal was to replace it with a premium, modern onboarding experience comparable to top-tier messaging applications (WhatsApp, Telegram, Signal) while preserving all existing Firebase Authentication logic and supporting the app's three theme variants (Light, Dark, Pastel‑Sky).

### Design Principles

- **Minimalist & premium** — large whitespace, balanced spacing, clean typography, no visual clutter
- **Material 3** — edge-to-edge layouts, proper WindowInsets handling, semantic theming
- **60 FPS animations** — pure Compose animations (no Lottie for auth screens) with button press‑scale, OTP digit entry, shake-on-error, and animated success checkmark
- **Accessibility** — 48 dp minimum touch targets, TalkBack content descriptions, dynamic font scaling
- **No Firebase logic changes** — all auth API calls, callbacks, and routing preserved verbatim

---

## 2. High-Level Architecture

```
                        PhoneAuthCoordinator (singleton)
┌──────────────────────────────────────────────────────────────────┐
│  startVerification()   verifyCode()        routeAfterSignIn()    │
│  resendCode()          (manual entry)      (profile check → nav) │
│        │                    │                      │             │
│  PhoneAuthOptions    getCredential()    FirebaseRepository.getUser()
│  .verifyPhoneNumber  signInWithCredential  navigateToMain()
│        │                    │              navigateToSetupProfile()
└────────┼────────────────────┼──────────────────────────────────┘
         │                    │
    [PhoneNumberActivity]  [OtpVerificationActivity]
         │                    │
    WelcomeActivity            │
         │                    │
    ┌────▼────────────────────▼─────────────────────────────────┐
    │                    AuthFlowSession (singleton)              │
    │  verificationId : String?      resendToken : ForceResend?  │
    │  phoneNumber : String?         dialCode : String?          │
    │  nationalDigits : String?      hasRouted : Boolean         │
    └────────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

| Decision | Rationale |
|---|---|
| **Jetpack Compose** for all screens | Already a hybrid ViewBinding+Compose project; Compose gives fluid animations, declarative edge‑to‑edge, and less code than XML |
| **Four separate Activities** (Welcome, Phone, OTP, Setup) | Each screen is an independent Activity with `setContent {}` — follows the existing `RestoreOfferActivity` pattern; Intent‑based routing consistent with the rest of the app (no Jetpack Navigation) |
| **`PhoneAuthCoordinator` as Kotlin `object`** | Singleton ensures both `PhoneNumberActivity` and `OtpVerificationActivity` share the same Firebase callback state, preventing double‑sign‑in races |
| **`AuthFlowSession` singleton** for cross‑Activity state | Mirrors the old `LoginActivity`'s instance fields (`verificationId`, `resendToken`); process‑death behavior unchanged (user re‑enters number) |
| **`glyphTheme` semantic tokens** for all styling | Every color, elevation, and spacing comes from `GlyphThemeTokens` — dark mode and Pastel‑Sky work automatically via `GlyphThemeProvider` |

---

## 3. File Inventory

### 3.1 New Files

| File | Package | Purpose |
|---|---|---|
| `WelcomeActivity.kt` | `ui.auth` | Screen 1: Brand + "Get Started" CTA |
| `PhoneNumberActivity.kt` | `ui.auth` | Screen 2: Country picker + phone input |
| `OtpVerificationActivity.kt` | `ui.auth` | Screen 3: 6‑digit OTP + verify + resend |
| `SetupProfileActivity.kt` | `ui.auth` | Screen 4: Avatar + display name |
| `PhoneAuthCoordinator.kt` | `ui.auth` | Firebase phone‑auth state machine (singleton) |
| `AuthFlowSession.kt` | `ui.auth` | Cross‑Activity state holder (singleton) |
| `AuthAnimationUtils.kt` | `ui.auth` | Shared animation specs and Activity transition helpers |
| `Country.kt` | `ui.auth.models` | Country data class |
| `CountryData.kt` | `ui.auth.data` | 240‑country list with flag emoji |
| `AuthScaffold.kt` | `ui.auth.components` | Edge‑to‑edge screen wrapper |
| `GlyphButton.kt` | `ui.auth.components` | Press‑scale animated button |
| `LoadingOverlay.kt` | `ui.auth.components` | Scrim + card + spinner overlay |
| `ConfirmationDialog.kt` | `ui.auth.components` | Themed confirmation dialog |
| `CountryPickerSheet.kt` | `ui.auth.components` | Modal bottom sheet with search |
| `OtpInputField.kt` | `ui.auth.components` | 6‑digit OTP boxes with animations |
| `PhoneInputField.kt` | `ui.auth.components` | Phone number input with dial‑code prefix |
| `AvatarPicker.kt` | `ui.auth.components` | Circular avatar with camera badge |
| `auth_slide_in_right.xml` | `res/anim` | Forward enter (slide + fade, 300 ms) |
| `auth_slide_out_left.xml` | `res/anim` | Forward exit (slide + fade, 300 ms) |
| `auth_slide_in_left.xml` | `res/anim` | Back enter (slide + fade, 250 ms) |
| `auth_slide_out_right.xml` | `res/anim` | Back exit (slide + fade, 250 ms) |

### 3.2 Modified Files

| File | Change |
|---|---|
| `SplashActivity.kt` | Routes unauthenticated users to `WelcomeActivity` (was `LoginActivity`) |
| `MainActivity.kt` | Updated `LoginActivity` → `WelcomeActivity` and `SetupProfileActivity` references to new `ui.auth` package |
| `AccountSettingsActivity.kt` | Updated logout/deletion paths to `WelcomeActivity`; added `AuthFlowSession.clear()` and `FirebaseFirestore.clearPersistence()` |
| `ShareTargetActivity.kt` | Updated unauthenticated routing to `WelcomeActivity` |
| `AndroidManifest.xml` | Registered 4 new activities with `adjustResize` for keyboard screens; kept old `ui.login` entries for backward compatibility |
| `GlyphApplication.kt` | No functional changes (handler reverted to original) |

### 3.3 Preserved (Unchanged) Files

| File | Status |
|---|---|
| `ui/login/LoginActivity.kt` | Kept for reference; no longer the primary auth path |
| `ui/login/SetupProfileActivity.kt` | Kept for reference; no longer the primary auth path |
| `res/layout/activity_login.xml` | Unchanged |
| `res/layout/activity_setup_profile.xml` | Unchanged |

### 3.4 Dependencies

No new dependencies were added. The implementation uses existing libraries:
- Jetpack Compose (Material 3, BOM 2024.09.00)
- Firebase Auth / Firestore / Storage (BoM 32.7.0)
- Coil (for avatar loading in `AvatarPicker`)
- Material Icons Extended

---

## 4. Screen‑by‑Screen Implementation

### 4.1 WelcomeActivity — "Get Started"

**Composable:** `WelcomeScreen`

- App logo (140 dp, centred) with staggered fade‑in animation
- App name "Glyph" in `displaySmall` bold
- Tagline: "Private, fast and secure conversations."
- Terms & Privacy text with clickable links opening browser intents
- "Get Started" button (56 dp, 28 dp radius, full‑width, bottom‑anchored)
- Transitions to `PhoneNumberActivity` with `AuthAnimationUtils.forward()`

**Key behaviours:**
- No back button (immersive first screen)
- Button press scales to 96% via `animateFloatAsState` spring
- Content fades in with 80 ms stagger between children

### 4.2 PhoneNumberActivity — Phone Input

**Composable:** `PhoneNumberScreen`

- Country selector row (flag emoji, name, calling code, chevron) — opens `CountryPickerSheet`
- `CountryPickerSheet`: Material 3 `ModalBottomSheet` with search field and `LazyColumn`
- Country auto‑detected from SIM (`TelephonyManager.simCountryIso`) or `Locale.getDefault().country`; falls back to India (+91)
- `PhoneInputField`: country code prefix | vertical divider | numeric text field (large font, auto‑spacing every 5 digits)
- Privacy note: "Carrier charges may apply."
- Floating Next button (circular, 56 dp, bottom‑end, `ArrowForward` icon) — disabled until 7–12 digits entered
- On tap: `ConfirmationDialog` → "Continue" → `PhoneAuthCoordinator.startVerification()`
- Loading overlay while Firebase sends OTP

**Key behaviours:**
- Input border animates colour and width on focus
- Invalid input shows subtle error text (no aggressive red)
- Back button returns to `WelcomeActivity` with reverse transition

### 4.3 OtpVerificationActivity — OTP Verification

**Composable:** `OtpVerificationScreen`

- `OtpInputField`: 6 boxes (56 dp height, 12 dp corner radius, 1.5 dp border), single hidden `BasicTextField` for keyboard/paste/backspace
- Auto‑advance on digit entry, backspace navigates backward, paste fills all 6
- Clipboard auto‑fill on composition (for SMS Retriever and manual paste)
- Countdown timer (60 s) with "Resend Code" `TextButton` enabled after expiry
- "Change phone number" navigates back to `PhoneNumberActivity`
- Verify button: full‑width, disabled until 6 digits, inline `CircularProgressIndicator` while verifying
- On success: animated checkmark (Canvas drawArc + drawPath trimPath) → `PhoneAuthCoordinator.routeAfterSignIn()`
- On failure: shake animation (horizontal translation, 4 cycles) + error text

**Key behaviours:**
- OTP box border transitions: `borderInput` → `borderFocus` (focused) → `borderPrimary` (filled)
- Digit entry: `AnimatedContent` with scale + fade
- `getIdToken(true)` force‑refreshes auth token before routing
- `signInInProgress` flag prevents parallel sign‑in from auto‑verify + manual entry

### 4.4 SetupProfileActivity — Profile Setup

**Composable:** `SetupProfileScreen`

- `AvatarPicker`: 120 dp circle with camera badge overlay (44 dp), Coil `AsyncImage`
- Image picker via `rememberLauncherForActivityResult(GetContent)`
- Display name `OutlinedTextField` with 30‑char counter, emoji support
- Optional bio field with 150‑char counter
- Continue button (full‑width, 56 dp) — disabled until name ≥ 2 characters
- Saves via `FirebaseRepository.saveUserProfile()` (unchanged call path)

---

## 5. Navigation Flow

```
                          ┌─────────────────┐
                          │  SplashActivity  │
                          │ (launcher, auth  │
                          │  state check)     │
                          └────────┬────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     │ authenticated?              │
                     ▼ Yes                     ▼ No
              ┌──────────┐            ┌─────────────────┐
              │MainActivity│            │ WelcomeActivity  │
              └──────────┘            │  (Get Started)    │
                                      └────────┬────────┘
                                               │ tap "Get Started"
                                               ▼
                                      ┌─────────────────┐
                                      │PhoneNumberActivity│
                                      │ (country + phone  │
                                      │  input)           │
                                      └────────┬────────┘
                                               │ SMS sent (onCodeSent)
                                               ▼
                                      ┌─────────────────┐
                                      │OtpVerificationAct│
                                      │ (6-digit OTP +    │
                                      │  countdown)       │
                                      └────────┬────────┘
                                               │ verify success
                                               ▼
                              ┌────────────────────────────┐
                              │ PhoneAuthCoordinator       │
                              │ .routeAfterSignIn()        │
                              │   → FirebaseRepository     │
                              │     .getUser()             │
                              └────────────┬───────────────┘
                                           │
                         ┌─────────────────┴─────────────────┐
                         │ user exists & has username?         │
                         ▼ Yes                             ▼ No
                  ┌──────────┐                  ┌──────────────────┐
                  │MainActivity│                  │SetupProfileActivity│
                  └──────────┘                  │ (avatar + name)    │
                                                └────────┬─────────┘
                                                         │ save success
                                                         ▼
                                                  ┌──────────┐
                                                  │MainActivity│
                                                  └──────────┘

All transitions use CLEAR_TASK so the auth activities are removed from
the back stack after successful login.
```

---

## 6. Authentication Lifecycle

### 6.1 Phone Verification

1. User enters phone number and taps Next
2. `ConfirmationDialog` appears — "Is this the correct number?"
3. User taps "Continue" → `PhoneAuthCoordinator.startVerification()`
4. `PhoneAuthOptions.newBuilder(auth).setPhoneNumber(e164).setTimeout(60L).setActivity(this).setCallbacks(...)` → `PhoneAuthProvider.verifyPhoneNumber(options)`
5. Firebase sends SMS (or auto‑verifies the number)

### 6.2 OTP Code Handling

**Auto‑verification path:**
- `onVerificationCompleted(credential)` fires
- `signInAndRoute()` → `performSignInAndRoute()` → `signInWithCredential()` → `getIdToken(true)` → `routeAfterSignIn()`
- User is navigated to `MainActivity` or `SetupProfileActivity` without manually entering a code

**Manual code entry path:**
- `onCodeSent(verificationId, token)` fires
- User enters 6‑digit code → `PhoneAuthCoordinator.verifyCode()`
- `PhoneAuthProvider.getCredential(verificationId, code)` → `signInWithCredential()`
- On success → `routeAfterSignIn()`

**Race‑condition guard:**
- `signInInProgress` flag prevents parallel `signInWithCredential` calls
- `AuthFlowSession.hasRouted` prevents double‑navigation from concurrent paths
- `getIdToken(true)` force‑refreshes the auth token before `repository.getUser()` to ensure Firestore gets a fresh token

### 6.3 Post‑Sign‑In Routing

```
routeAfterSignIn()
  → repository.getUser(uid)
    → user != null && banned/blocked
      → showAccountRestrictedDialog() → Sign Out → WelcomeActivity
    → user != null && username.isNotEmpty()
      → navigateToMain() (CLEAR_TASK)
    → else (new user)
      → navigateToSetupProfile() (passes phone_number via Intent extra)
```

### 6.4 Sign‑Out Lifecycle

All three sign‑out paths execute the same cleanup sequence:
1. `PresenceManager.goOffline()` or `ContactDisplayNameResolver.shutdown()`
2. `FirebaseAuth.getInstance().signOut()`
3. `AuthFlowSession.clear()`
4. `FirebaseFirestore.getInstance().clearPersistence()` — drops cached watch targets
5. Launch `WelcomeActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`

---

## 7. Firebase Authentication Integration

### 7.1 Preserved Logic

All Firebase auth logic from the original `LoginActivity` is preserved in `PhoneAuthCoordinator`:

| Original (LoginActivity) | New (PhoneAuthCoordinator) |
|---|---|
| `PhoneAuthOptions.newBuilder(auth).setPhoneNumber(...).setTimeout(60L).setActivity(this).setCallbacks(...)` | Identical |
| `PhoneAuthProvider.getCredential(verificationId, code)` | Identical |
| `auth.signInWithCredential(credential).addOnCompleteListener(activity) { ... }` | Identical |
| `FirebaseRepository().getUser { user -> ... }` | Identical |
| `navigateToMain()` / `navigateToSetupProfile()` with `CLEAR_TASK` | Identical Intent construction |
| `showAccountRestrictedDialog()` with `MaterialAlertDialogBuilder` | Identical |

### 7.2 Token Refresh

```kotlin
// Added: force-refresh the auth token before Firestore listeners start
auth.currentUser?.getIdToken(true)?.addOnCompleteListener {
    routeAfterSignIn(activity) {}
}
```

This ensures Firestore's gRPC connection picks up the fresh token before `MainActivity` registers any snapshot listeners.

---

## 8. Error Handling & Edge Cases

| Scenario | Handling |
|---|---|
| Phone input has < 7 or > 12 digits | Next button stays disabled; subtle error text on invalid |
| Phone verification fails (FirebaseException) | Error text shown inline; loading overlay dismissed |
| Auto‑verification and manual entry race | `signInInProgress` + `hasRouted` guards prevent double‑sign‑in |
| OTP code invalid | Shake animation + error text below OTP boxes; button re‑enabled |
| OTP countdown expires | "Resend Code" button enabled; resend calls `PhoneAuthProvider.verifyPhoneNumber()` with `ForceResendingToken` |
| `getUser()` returns null (network error) | User routed to `SetupProfileActivity` (same as old flow) |
| Account banned/blocked | `MaterialAlertDialogBuilder` "Account Restricted" with Sign Out |
| `AuthFlowSession` data missing on OTP screen | Activity calls `finish()` immediately, returning to previous screen |
| Process death mid‑flow | `verificationId` lost, user re‑enters number (same robustness as old flow) |

---

## 9. State Management

### 9.1 AuthFlowSession (singleton)

Kotlin `object` holding nullable fields:
- `verificationId: String?` — returned by Firebase after SMS sent
- `resendToken: ForceResendingToken?` — for OTP resend
- `phoneNumber: String?` — full E.164 number
- `dialCode: String?` — selected country code (e.g. "91")
- `nationalDigits: String?` — digits without dial code
- `hasRouted: Boolean` — prevents double‑navigation

Cleared on: sign‑out, successful navigation to Main/Setup.

### 9.2 PhoneAuthCoordinator (singleton)

Kotlin `object` — all Firebase auth state and callbacks in one place. Both `PhoneNumberActivity` and `OtpVerificationActivity` use the same instance, eliminating the need for multiple coordinator instances and the associated race conditions.

### 9.3 Compose State

Each screen manages its own UI state via `remember { mutableStateOf(...) }`:
- `PhoneNumberScreen`: `selectedCountry`, `phoneValue` (TextFieldValue), `isLoading`, `errorMessage`
- `OtpVerificationScreen`: `otpCode`, `isLoading`, `isVerifying`, `showSuccess`, `errorMessage`, `countdown`, `canResend`, `otpError`
- `SetupProfileScreen`: `imageUri`, `displayName`, `bio`, `isLoading`, `nameError`

No ViewModel is used for the auth screens — complexity is low enough that Compose state + `PhoneAuthCoordinator` singleton is sufficient.

---

## 10. Theming

All composables use `glyphTheme` (from `GlyphThemeTokens`) — no hardcoded colours:

| Element | Token |
|---|---|
| Screen background | `backgroundPrimary` (or `backgroundGradient` for Pastel‑Sky) |
| Primary button | `actionPrimary` |
| Text | `textPrimary` / `textSecondary` / `textTertiary` |
| Input fields | `surfaceInput` container, `borderInput` / `borderFocus` stroke |
| Error | `actionError` |
| Success checkmark | `actionSuccess` |
| Loading scrim | `surfaceOverlay` |
| Divider | `divider` |

Each Activity wraps content in `GlyphThemeProvider {}`, which auto‑detects the current theme from `ThemeManager` (Light, Dark, Pastel‑Sky).

---

## 11. Animations

All animations are pure Compose — **no Lottie dependency for auth**.

| Animation | Implementation |
|---|---|
| Button press scale | `animateFloatAsState` spring, target 0.96 via `collectIsPressedAsState()` |
| Content stagger fade | `AnimatedVisibility` + `fadeIn` with staggered `tween` delays |
| Input border focus | `animateDpAsState` (stroke 1→2 dp) + `animateColorAsState` (border colour) |
| OTP digit entry | `AnimatedContent` with `scaleIn` + `fadeIn` transition |
| OTP shake (invalid) | `Animatable` horizontal offset, 4 cycles, 50 ms each |
| Success checkmark | `Canvas` drawArc trimPath (circle) + drawPath trimPath (check), both `Animatable` |
| Loading overlay | `AnimatedVisibility` fadeIn/fadeOut, 300 ms |
| Activity transitions | Shared‑axis slide+fade via `overridePendingTransition` / `overrideActivityTransition` |

---

## 12. Accessibility

- Minimum touch targets: 48 dp (buttons 56 dp, OTP boxes 56 dp)
- OTP boxes: `contentDescription = "Digit ${index + 1} of 6"`
- Country selector: `contentDescription = "Select country, currently ${country.name}"`
- All buttons/images have content descriptions
- Text sizes in `sp` (respects system font scaling)
- `KeyboardType.Phone` / `KeyboardType.Number` for appropriate input fields

---

## 13. Backward Compatibility

- Old `ui.login.LoginActivity` and `ui.login.SetupProfileActivity` are **preserved** in the codebase and manifest but are no longer the primary routing targets
- Any external code that references the old classes by fully‑qualified name will still compile and run
- The old ViewBinding layouts (`activity_login.xml`, `activity_setup_profile.xml`) are unchanged
- `FirebaseRepository`, `GoogleSignInRepository`, and all service classes are untouched

---

## 14. Performance

- Screens use Compose with strong skipping enabled (existing `stability_config.conf`)
- Country list is a static `val` — no disk or network I/O
- `OtpInputField` focus management uses `FocusRequester` with `LaunchedEffect` — no unnecessary recompositions
- `remember` + `derivedStateOf` for computed values
- Activity transitions: 250–300 ms, translate+fade only
- No nested layout hierarchies — Compose declarative layout is flat
- `AuthScaffold` paints background once, children are simple `Column`/`Row` compositions

---

## 15. Testing

| Test Case | Expected Behaviour |
|---|---|
| Fresh install → cold start | Splash → Welcome (fade in) |
| Welcome → Get Started tap | Slide to PhoneNumber screen |
| Country picker | Bottom sheet opens, search filters, selection updates prefix |
| Phone validation | 7–12 digits enables Next; invalid shows error |
| Confirmation dialog → Continue | Loading overlay → SMS sent → navigate to OTP |
| OTP auto‑fill (clipboard) | Digits populate all 6 boxes on composition |
| OTP manual entry | Auto‑advance, backspace, paste work correctly |
| OTP resend | Countdown 60→0 → Resend enabled → new code sent |
| Invalid OTP | Shake animation, error text, button re‑enabled |
| Valid OTP | Checkmark animation → route to Main or Setup |
| Profile setup | Avatar picker, name validation, save → MainActivity |
| Dark mode | All screens render with DarkThemeTokens |
| Pastel‑Sky | Gradient background, correct colours |
| Back navigation | OTP back → Phone, Phone back → Welcome |
| Sign out | `clearPersistence()` → WelcomeActivity |
| TalkBack | All elements announced correctly |

---

## 16. Future Improvements

1. **SMS Retriever API** — register a `BroadcastReceiver` for `SmsRetriever.SMS_RETRIEVED_ACTION` to auto‑read OTP from SMS without clipboard. Requires adding `play-services-auth-api-phone` dependency.
2. **Unit tests** for `PhoneAuthCoordinator` and `CountryData`
3. **UI tests** via Compose testing framework for each screen
4. **Consolidate old auth files** — remove `ui.login.LoginActivity` and `ui.login.SetupProfileActivity` once all references are migrated
5. **Add "skip" option** on `SetupProfileActivity` — allow users to set up profile later
6. **Profile photo cropping** — integrate uCrop (already a dependency) for square avatar cropping
