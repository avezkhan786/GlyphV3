# Walkie-Talkie Cross-Network Setup (Metered TURN)

This guide documents the full setup and runtime behavior required to make walkie-talkie calls connect quickly and reliably across different networks/locations.

## 1. What Is Implemented

The app now uses a layered ICE strategy:

1. Dynamic Metered credential fetch (primary)
2. Static Metered TURN credentials (fallback)
3. STUN + ICE history optimization + relay-only prediction for pairs that repeatedly need TURN

Code locations:

- TURN config and property loading: app/build.gradle.kts
- ICE server composition and dynamic fetch: app/src/main/java/com/glyph/glyph_v3/data/webrtc/WebRtcIceConfig.kt
- Walkie-talkie connect flow and TURN readiness handling: app/src/main/java/com/glyph/glyph_v3/data/service/WalkieTalkieManager.kt
- Peer relay-only mode and relay outcome tracking: app/src/main/java/com/glyph/glyph_v3/data/webrtc/WalkieTalkiePeerClient.kt
- Connection history cache: app/src/main/java/com/glyph/glyph_v3/data/cache/IceCandidateHistoryCache.kt

## 2. Required Metered Account Setup

In dashboard.metered.ca:

1. Create/sign in to your Metered app
2. Go to TURN Server
3. Create a TURN credential
4. Copy:
   - app name
   - credential apiKey (for dynamic endpoint)
   - username/password (for static fallback)

## 3. Local Configuration

Edit local.properties (not committed to VCS). Use either option below.

### Option A (recommended): Dynamic credentials

Set:

- METERED_APP_NAME=<your app name>
- METERED_TURN_API_KEY=<credential apiKey>

This derives and uses:

- https://<appname>.metered.live/api/v1/turn/credentials?apiKey=<apiKey>

### Option B: Static credentials

Set:

- METERED_TURN_USERNAME=<metered username>
- METERED_TURN_PASSWORD=<metered password>

When set, static TURN fallback slots are populated with Metered global endpoints:

- turn:global.relay.metered.ca:80
- turn:global.relay.metered.ca:80?transport=tcp
- turn:global.relay.metered.ca:443
- turn:global.relay.metered.ca:443?transport=tcp
- turns:global.relay.metered.ca:443?transport=tcp

## 4. Build and Deploy

Run:

- .\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
- .\gradlew.bat :app:assembleDebug --no-daemon --console=plain

Install the fresh debug APK on both devices.

## 5. Runtime Connection Optimizations Included

### TURN readiness without long blocking

WalkieTalkieManager performs short TURN readiness waits:

- short wait when relay is already configured
- longer wait only when no relay exists
- background refresh continues even if the short wait times out

This avoids long pre-call stalls while still warming TURN.

### Faster ICE gathering

WebRtcIceConfig includes:

- Metered STUN first: stun:stun.relay.metered.ca:80
- Increased ICE candidate pool size for faster availability of candidate sets
- Dynamic credential fetch retry with delay before giving up

### Smart relay prediction per user pair

IceCandidateHistoryCache stores recent outcomes for a pair and enables relay-first mode when history shows relay is usually required.

Result:

- First call discovers path
- Next calls between same pair can skip wasted host/srflx checks and connect faster on hard NAT/cross-carrier routes

## 6. Validation Checklist

Run these tests after install:

1. Same Wi-Fi network
2. Wi-Fi to mobile data
3. Mobile data to mobile data (different carriers if possible)
4. Both devices idle for 30+ minutes, then initiate WT
5. Device wake from sleep + immediate WT initiate

Expected behavior:

- Incoming notification appears quickly
- Session rings quickly
- ICE reaches CONNECTED/COMPLETED without minute-long stalls
- Metered dashboard shows usage for relay calls

## 7. Key Logs to Watch

Success indicators:

- "Responder: answer written for revision=..."
- ICE state transitions to CONNECTED or COMPLETED

Signals to investigate:

- "TURN credentials not ready before peer setup"
- "ICE gathering completed without relay candidates"
- repeated "Starting WT ICE restart negotiation" followed by disconnect

## 8. Troubleshooting Quick Actions

If cross-network still fails or is too slow:

1. Verify local.properties values are present on BOTH builds
2. Confirm Metered credential is active (not disabled/expired)
3. Prefer dynamic apiKey mode + static username/password fallback simultaneously
4. Rebuild and reinstall after any property change
5. Capture both caller and receiver logs for the same sessionId
6. If a bad network history was cached, clear app data and retest to rebuild ICE history cleanly

## 9. Security Notes

- Never commit real TURN secrets to tracked files
- Keep credentials in local.properties or secure CI secrets
- Rotate Metered credentials periodically

## 10. Operational Recommendation

For production reliability and startup speed:

- Keep both dynamic credential API and static Metered fallback configured
- Keep history cache enabled (default)
- Monitor Metered usage and failed-session log rates after each release
