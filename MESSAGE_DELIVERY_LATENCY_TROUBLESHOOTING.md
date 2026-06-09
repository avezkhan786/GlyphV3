# Message Delivery Latency Troubleshooting

Last updated: 2026-03-14

## Problem Summary

The app showed severe message latency after cold start or after a long idle period.

Observed symptoms:

- Sender-side messages could remain pending for 10 to 30 seconds.
- Receiver-side messages could arrive only after reopening the app or chat.
- Receiver notifications could appear, but the actual chat database/UI stayed stale.
- Calls and other realtime flows could also feel delayed immediately after reopening the app.

Expected behavior:

- Sender local echo should appear immediately.
- Sender should reach `SENT` within a few hundred milliseconds on a healthy network.
- Receiver should persist and display the message without needing the chat to be reopened.
- Long idle should not require a manual reconnect path.

## Root Causes

This issue had more than one contributing cause.

### 1. RTDB/Auth Warm-Up After Idle

After long idle, the first send could be delayed while Firebase Realtime Database and Firebase Auth re-established transport/auth state.

Impact:

- The sender could stay in `SENDING` longer than expected.
- First-write latency was dominated by reconnect/auth warm-up instead of actual message write time.

### 2. Stale Incoming RTDB Listener On Receiver

The app cached incoming sync as if it were already active, but after long idle the underlying RTDB listener could be stale.

Impact:

- The receiver process could appear healthy but stop consuming `pending_messages` in real time.
- Foreground/network recovery paths became no-ops because they reused the cached listener instead of reattaching it.

### 3. FCM Background Path Did Not Persist Messages Into Room

The background FCM flow updated notifications and the unread store, but did not upsert the incoming message into Room immediately.

Impact:

- Receiver notification could arrive before the local chat database was updated.
- Opening the chat later could show stale history until RTDB or Firestore catch-up completed.

## Verified Fixes

### Sender/Transport Hardening

The app now warms Firebase transport more aggressively on foreground and after stale idle.

Key behavior:

- Foreground warm-up can force auth refresh.
- RTDB can be hard-reset on stale reconnect paths.
- First send no longer waits on a lazy reconnect path.

Relevant files:

- [app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt](app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt)
- [app/src/main/java/com/glyph/glyph_v3/data/repo/RealtimeMessageRepository.kt](app/src/main/java/com/glyph/glyph_v3/data/repo/RealtimeMessageRepository.kt)

### Forced Incoming Listener Restart

The incoming RTDB listener is no longer treated as healthy just because a cached ref exists.

Key behavior:

- Added forced restart support for incoming sync.
- Foreground/auth/network recovery now reattaches the RTDB listener when needed.
- Listener cancellation now clears stale state.

Relevant files:

- [app/src/main/java/com/glyph/glyph_v3/data/repo/RealtimeMessageRepository.kt](app/src/main/java/com/glyph/glyph_v3/data/repo/RealtimeMessageRepository.kt)
- [app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt](app/src/main/java/com/glyph/glyph_v3/GlyphApplication.kt)
- [app/src/main/java/com/glyph/glyph_v3/data/service/NetworkConnectivityMonitor.kt](app/src/main/java/com/glyph/glyph_v3/data/service/NetworkConnectivityMonitor.kt)

### Immediate Local Persistence From FCM

Incoming FCM messages are now persisted into Room immediately in the background service.

Key behavior:

- Incoming message is inserted into `messages` table from `MyFirebaseMessagingService`.
- Chat preview/unread state is updated immediately.
- Receiver chat state stays current even if RTDB listener recovery is delayed.

Relevant file:

- [app/src/main/java/com/glyph/glyph_v3/data/service/MyFirebaseMessagingService.kt](app/src/main/java/com/glyph/glyph_v3/data/service/MyFirebaseMessagingService.kt)

## Logging Added For Diagnosis

Two log tags were used during diagnosis.

### `MessageTraceUi`

Used in the chat UI flow.

Important stages:

- `send_tap`
- `send_launch`
- `send_returned`
- `flow_emit_raw`
- `flow_emit_filtered`
- `list_commit_full`
- `list_commit_step1`
- `list_commit_step2`

### `MessageTrace`

Used in repository/service layers.

Important stages:

- `send_begin`
- `send_local_inserted`
- `send_auth_ready`
- `send_rtdb_write_start`
- `send_rtdb_write_ack`
- `incoming_sync_reuse`
- `incoming_sync_force_restart`
- `incoming_sync_cancelled`
- `incoming_snapshot`
- `incoming_local_inserted`
- `firestore_sync_snapshot`
- `fcm_local_persist`

## How To Read The Logs

### Healthy Sender Path

Expected sequence:

1. `MessageTraceUi [send_tap]`
2. `MessageTrace [send_local_inserted]`
3. `MessageTraceUi [flow_emit_raw]` with latest message in `SENDING`
4. `MessageTrace [send_rtdb_write_ack]`
5. `MessageTraceUi [flow_emit_raw]` with latest message in `SENT`

Healthy timing target:

- `SENDING` visible immediately.
- `SENT` typically within about 100 to 300 ms on a healthy connection.

### Healthy Receiver Background Path

Expected sequence:

1. FCM arrives.
2. `MessageTrace [fcm_local_persist]`
3. Receiver opens chat or is already active.
4. `MessageTraceUi [flow_emit_raw]` shows the new incoming message.

Healthy behavior:

- Receiver database should already contain the message before the chat is reopened.
- Opening the chat should not depend on RTDB catch-up for the latest message.

### Healthy Receiver Live RTDB Path

Expected sequence:

1. `MessageTrace [incoming_snapshot]`
2. `MessageTrace [incoming_local_inserted]`
3. `MessageTraceUi [flow_emit_raw]`
4. `MessageTraceUi [list_commit_full]`

## What The Successful Final Runs Looked Like

Verified good patterns:

- Sender `SENT` in about 100 to 142 ms.
- One intermediate run showed `DELIVERED` in about 4.3 seconds.
- Final run showed `DELIVERED` in about 82 ms for the latest message.
- Receiver reached `DELIVERED` and `READ` without requiring chat reopen.

Interpretation:

- Sender transport bottleneck was resolved.
- Receiver listener staleness was resolved.
- Receiver background persistence gap was resolved.

## If The Problem Comes Back

Capture logcat filtered by:

- `MessageTrace`
- `MessageTraceUi`
- `MyFirebaseMsgService`
- `RealtimeMessageRepo`
- `GlyphApplication`

Then check these questions in order.

### 1. Does Sender Local Echo Still Happen Immediately?

Look for:

- `send_tap`
- `send_local_inserted`
- `flow_emit_raw` with `SENDING`

If this is delayed, the issue is local UI, Room, or main-thread contention.

### 2. Is Sender RTDB Ack Slow Again?

Look for the gap between:

- `send_rtdb_write_start`
- `send_rtdb_write_ack`

If this is large, the sender reconnect/auth path regressed.

### 3. Did Receiver Persist From FCM?

Look for:

- `fcm_local_persist`

If missing, the FCM service either did not run, did not get the message payload, or failed to write to Room.

### 4. Did Receiver Restart Incoming Sync Properly?

Look for:

- `incoming_sync_force_restart`
- `incoming_sync_reuse`
- `incoming_sync_cancelled`
- `incoming_snapshot`

If only `incoming_sync_reuse` appears repeatedly and no `incoming_snapshot` arrives, suspect stale listener reuse again.

### 5. Is The Delay Actually FCM Delivery?

If sender reaches `SENT` fast and receiver does not log either:

- `fcm_local_persist`
- `incoming_snapshot`

then the remaining issue is likely outside the client app, for example:

- delayed FCM delivery
- missing/stale FCM token
- OEM battery restrictions / doze
- server-side Cloud Function delay

Relevant server file:

- [functions/index.js](functions/index.js)

## Operational Checks

If regression is reported, verify all of the following.

### Client Checks

- Receiver app has notification permission.
- Receiver device battery optimization is disabled for the app if OEM aggressively kills background work.
- FCM token is present and current in Firestore user document.
- App can receive data-only FCM while backgrounded.

### Server Checks

- Cloud Function is deployed and healthy.
- Function is still sending high-priority data-only FCM.
- Payload still includes `messageId`, `chat_id`, `other_user_id`, `type`, and `timestamp`.

## Recommended Cleanup After Confidence Builds

Once the fix has been stable across enough real-world testing:

- reduce or remove `MessageTrace` debug logs
- keep only warning/error paths for listener cancellation and failed FCM persistence
- keep this document as the regression playbook
