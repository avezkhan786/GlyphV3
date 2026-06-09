# Message Row Optimization Analysis

## Executive Summary

After analyzing the message list implementation in [ChatScreen.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatScreen.kt), I've identified **significant optimization opportunities** to make message rows as lightweight as possible. The current implementation is already highly optimized with techniques like `TextLayoutCache`, `drawWithCache`, and flattened node hierarchies. However, there are still areas where we can extract heavy logic and reduce per-row overhead.

---

## Current Architecture Analysis

### What's Already Optimized ✅

1. **TextLayoutCache** (lines 2530-2650)
   - Global LRU cache for text measurements
   - Eliminates redundant text layout calculations during scroll
   - 1000-entry cache with access-order eviction
   - Pre-warming support for initial batch

2. **Ultra-Flattened Rendering** (TextMessageBubble, lines 3215+)
   - Single `Spacer` node with `drawWithCache`
   - Reduces node count from ~8 to 1 per text message
   - All drawing happens on canvas (no Box/Column overhead)

3. **Media Message Canvas Drawing** (MediaMessageBubble, lines 2958+)
   - Overlays (timestamp, file size) drawn directly on canvas
   - Saves ~10 nodes per media message

4. **Conditional Animation State** (MessageBubbleContent, lines 1988+)
   - Edit animation state only allocated when `needsEditAnimation = true`
   - Highlight animation only created when `isHighlighted = true`
   - `graphicsLayer` only applied if animations exist (saves overhead for 99% of messages)

5. **Static Helper Functions**
   - `getBubbleShape()` - no remember overhead
   - `getArrowShape()` - for edit animations
   - Pure functions outside composition

---

## Heavy Operations Still Inside Message Rows ⚠️

### 1. **Theme Color Resolution** (HIGH IMPACT)

**Location:** Lines 2050-2055 in MessageBubbleContent

```kotlin
val gradient = remember(isSelf) { if (isSelf) theme.gradientBubbleOutgoing else theme.gradientBubbleIncoming }
val solidColor = remember(isSelf) { if (isSelf) theme.bubbleOutgoingBackground else theme.bubbleIncomingBackground }
val textColor = remember(isSelf) { if (isSelf) theme.bubbleOutgoingText else theme.bubbleIncomingText }
val timestampColor = remember { theme.bubbleTimestamp }
```

**Issue:**
- Every message row accesses theme object and creates 4 color/gradient variables
- `remember(isSelf)` still runs on initial composition for each visible row
- When scrolling fast, dozens of new rows are composed simultaneously
- Theme object lookup happens for every single message

**Solution:** Extract to pre-computed theme sets at list level

```kotlin
// At MessageList level - compute ONCE
@Immutable
data class BubbleThemeSet(
    val outgoingGradient: Brush?,
    val outgoingSolid: Color,
    val outgoingText: Color,
    val incomingGradient: Brush?,
    val incomingSolid: Color,
    val incomingText: Color,
    val timestamp: Color,
    val border: Color
)

// In MessageList composable
val bubbleTheme = remember(theme) {
    BubbleThemeSet(
        outgoingGradient = theme.gradientBubbleOutgoing,
        outgoingSolid = theme.bubbleOutgoingBackground,
        outgoingText = theme.bubbleOutgoingText,
        incomingGradient = theme.gradientBubbleIncoming,
        incomingSolid = theme.bubbleIncomingBackground,
        incomingText = theme.bubbleIncomingText,
        timestamp = theme.bubbleTimestamp,
        border = theme.bubbleBorder
    )
}

// Pass to each row - now it's just direct field access, no theme lookup
```

**Impact:** Eliminates 4 theme lookups and 3 conditional checks per message row

---

### 2. **Reply Preview Text Measurement** (CRITICAL IMPACT)

**Location:** Lines 2290-2325 in MessageBubbleContent

```kotlin
val replyPreviewMinWidth = remember(
    senderTextLocal,
    messageTextLocal,
    senderStyleLocal,
    messageStyleLocal,
    replyPreviewInnerMaxWidth,
    maxWidth
) {
    // Complex text measurement logic with multiple textMeasurer.measure calls
    val senderLayout = textMeasurer.measure(...)
    val messageLayout = textMeasurer.measure(...)
    // Width calculations...
}
```

**Issue:**
- For every message WITH A REPLY, this performs **2 text measurements** inside the row
- Text measurement is one of the heaviest operations in Compose
- The `remember` key includes 6 dependencies, making invalidation likely
- Measurements happen on main thread during scroll

**Solution:** Move to TextLayoutCache or pre-compute during message preparation

```kotlin
// Option A: Extend TextLayoutCache to support reply previews
val replyPreviewCache = TextLayoutCache.getOrMeasure(
    messageId = "${message.id}_reply_sender",
    text = senderTextLocal,
    maxWidthPx = availableWidthInt,
    styleKey = senderStyleKey
) {
    textMeasurer.measure(senderTextLocal, senderStyleLocal, constraints)
}

// Option B: Pre-compute in Message data class extension
data class Message {
    // ... existing fields
    
    // Computed once when message is created
    val replyPreviewLayout: ReplyLayoutCache? by lazy {
        if (replyToMessageId != null) {
            ReplyLayoutCache.compute(this, textMeasurer, density)
        } else null
    }
}
```

**Impact:** Eliminates 2 heavy text measurements per message with reply (~20-30% of messages typically have replies)

---

### 3. **Audio Message Playback State Derivation** (MEDIUM IMPACT)

**Location:** Lines 2213-2227 in MessageBubbleContent

```kotlin
val isPlaying by remember(playbackState, message.id) {
    derivedStateOf {
        playbackState?.playingMessageId == message.id && playbackState.isPlaying
    }
}
val audioProgress by remember(playbackState, message.id) {
    derivedStateOf {
        if (playbackState?.playingMessageId == message.id) playbackState.progress else 0f
    }
}

val currentPositionFormatted = remember(isPlaying, audioProgress, message.audioDuration, message.id, playbackState?.playingMessageId) {
    if (isPlaying || (playbackState?.playingMessageId == message.id && audioProgress > 0)) {
        val posSec = (audioProgress * message.audioDuration / 1000).toLong()
        String.format("%d:%02d", posSec / 60, posSec % 60)
    } else "0:00"
}
```

**Issue:**
- Every audio message creates 2 `derivedStateOf` instances
- Time formatting with `String.format` happens inside row composition
- Complex key dependencies in `remember` (5 keys!)
- State derivation happens for ALL audio messages even when none are playing

**Solution:** Centralize playback state at list level, pass down only what's needed

```kotlin
// At list level
@Immutable
data class AudioPlaybackInfo(
    val messageId: String,
    val isPlaying: Boolean,
    val progress: Float,
    val formattedPosition: String
)

val currentAudioInfo = remember(playbackState) {
    derivedStateOf {
        if (playbackState != null && playbackState.isPlaying) {
            val posSec = (playbackState.progress * playbackState.audioDuration / 1000).toLong()
            AudioPlaybackInfo(
                messageId = playbackState.playingMessageId,
                isPlaying = true,
                progress = playbackState.progress,
                formattedPosition = String.format("%d:%02d", posSec / 60, posSec % 60)
            )
        } else null
    }
}.value

// In row - simple comparison
val myAudioInfo = if (currentAudioInfo?.messageId == message.id) currentAudioInfo else null
```

**Impact:** Reduces state derivation from O(n messages) to O(1), eliminates string formatting from rows

---

### 4. **BorderStroke Allocation** (LOW IMPACT but EASY FIX)

**Location:** Line 2058 in MessageBubbleContent

```kotlin
val borderStroke = BorderStroke(1.dp, theme.bubbleBorder)
```

**Issue:**
- Creates new `BorderStroke` object for every message
- Could be part of pre-computed theme set

**Solution:** Include in `BubbleThemeSet`

```kotlin
data class BubbleThemeSet(
    // ... existing fields
    val borderStroke: BorderStroke
)

val bubbleTheme = remember(theme) {
    BubbleThemeSet(
        // ... existing fields
        borderStroke = BorderStroke(1.dp, theme.bubbleBorder)
    )
}
```

**Impact:** Eliminates 1 object allocation per message

---

### 5. **Shape Calculation** (LOW IMPACT - Already Optimized)

**Location:** Line 2056 in MessageBubbleContent

```kotlin
val shape = getBubbleShape(isSelf, groupPosition, theme.cornerRadiusMedium)
```

**Issue:**
- Currently calls static helper, which is good
- But still creates new `RoundedCornerShape` instance per message
- Only 16 possible shapes exist (2 sides × 4 positions × 2 radius variants for edit animation)

**Solution:** Pre-compute all possible shapes

```kotlin
@Immutable
object BubbleShapeCache {
    private val shapes = mutableMapOf<Triple<Boolean, BubbleGroupPosition, Float>, RoundedCornerShape>()
    
    fun getShape(isSelf: Boolean, position: BubbleGroupPosition, radiusDp: Float): RoundedCornerShape {
        val key = Triple(isSelf, position, radiusDp)
        return shapes.getOrPut(key) {
            when (position) {
                BubbleGroupPosition.SINGLE -> RoundedCornerShape(radiusDp.dp)
                BubbleGroupPosition.TOP -> if (isSelf) {
                    RoundedCornerShape(topStart = radiusDp.dp, topEnd = radiusDp.dp, bottomStart = radiusDp.dp, bottomEnd = 4.dp)
                } else {
                    RoundedCornerShape(topStart = radiusDp.dp, topEnd = radiusDp.dp, bottomStart = 4.dp, bottomEnd = radiusDp.dp)
                }
                // ... other positions
            }
        }
    }
}
```

**Impact:** Eliminates shape object creation per message

---

### 6. **LocalHapticFeedback and LocalView Lookups** (MICRO IMPACT)

**Location:** Lines 2003-2004 in MessageBubbleContent

```kotlin
val haptic = LocalHapticFeedback.current
val view = LocalView.current
```

**Issue:**
- Every message reads from CompositionLocal
- These are used only for click/long-click handlers

**Solution:** Pass as parameters from list level OR use CompositionLocalProvider at list level

```kotlin
// At list level
val haptic = LocalHapticFeedback.current
val view = LocalView.current

// Pass to rows
items(messages) { message ->
    MessageRow(
        message = message,
        haptic = haptic,
        view = view,
        // ... other params
    )
}
```

**Impact:** Eliminates 2 CompositionLocal lookups per message

---

### 7. **Layout Modifier Lambda Allocation** (MICRO IMPACT)

**Location:** Lines 2085-2093 in MessageBubbleContent

```kotlin
val layoutModifier = Modifier
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, placeable.height) {
            val x = if (isSelf) constraints.maxWidth - placeable.width else 0
            placeable.placeRelative(x, 0)
        }
    }
```

**Issue:**
- Lambda allocated per message
- Could be pre-computed for isSelf=true/false variants

**Solution:** Define once as extension functions

```kotlin
private val LayoutModifierOutgoing = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(constraints.maxWidth - placeable.width, 0)
    }
}

private val LayoutModifierIncoming = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

// In message row
val layoutModifier = if (isSelf) LayoutModifierOutgoing else LayoutModifierIncoming
```

**Impact:** Eliminates lambda allocation per message

---

## Recommended Extraction Strategy

### Phase 1: Theme Pre-computation (HIGHEST ROI)

**Files to Create:**
- `BubbleThemeSet.kt` - Immutable theme data class
- Update `OptimizedMessageList.kt` to compute theme set once

**Expected Impact:**
- **Reduce theme lookups:** From 4 per message to 0
- **Reduce conditional checks:** From 3 per message to 0
- **Estimated scroll performance gain:** 5-10% smoother

---

### Phase 2: Reply Preview Cache Integration (CRITICAL for Messages with Replies)

**Files to Modify:**
- `TextLayoutCache` - Add reply-specific cache methods
- `MessageBubbleContent` - Replace inline measurement with cache lookup

**Expected Impact:**
- **Eliminate measurements:** 2 heavy text measurements per message with reply
- **Reduce scroll jank:** Especially in conversations with many replies
- **Estimated performance gain:** 15-25% for messages with replies

---

### Phase 3: Audio State Centralization (MEDIUM Impact)

**Files to Modify:**
- `OptimizedMessageList.kt` - Create centralized audio state
- `MessageBubbleContent` - Simplify audio message rendering

**Expected Impact:**
- **Reduce state derivations:** From O(n) to O(1)
- **Eliminate string formatting:** Move out of hot path
- **Estimated performance gain:** 5-10% for chats with audio messages

---

### Phase 4: Micro-optimizations (LOW Impact but Easy Wins)

1. BorderStroke in theme set
2. Shape cache object
3. Layout modifier reuse
4. CompositionLocal parameter passing

**Expected Impact:**
- **Cumulative:** 2-5% improvement
- **Implementation time:** < 30 minutes total

---

## Comparison: Before vs After Optimization

### Before (Current State)
```
Message Row Composition:
├─ Theme lookups (4)
├─ Conditional checks (3)
├─ BorderStroke allocation (1)
├─ Shape creation (1)
├─ Reply text measurement (0-2) ← HEAVY
├─ Audio state derivation (0-2) ← HEAVY
├─ String formatting (0-1)
├─ CompositionLocal reads (2)
└─ Layout lambda allocation (1)

Total operations per message: 8-16 operations
Heavy operations during scroll: 0-4 per message with replies/audio
```

### After Optimization
```
Message Row Composition:
├─ Theme field access (0 lookups, just reads from data class)
├─ Shape cache lookup (O(1) map access)
├─ Reply cache lookup (O(1) if cached, otherwise cached for next scroll)
├─ Audio info comparison (simple == check)
└─ Pre-computed modifiers

Total operations per message: 2-4 operations
Heavy operations during scroll: 0
```

---

## Performance Testing Checklist

After implementing optimizations, test with:

1. **Large message list (1000+ messages)**
   - Scroll from top to bottom at various speeds
   - Monitor frame times with GPU Profiler
   - Target: 60 FPS steady, no dropped frames

2. **Mixed content types**
   - Text, media, audio, replies all mixed
   - Measure specific bubble type performance

3. **Theme switching**
   - Ensure BubbleThemeSet updates correctly
   - No flickering or stale colors

4. **Memory usage**
   - TextLayoutCache should stay under 1000 entries
   - No memory leaks from cached layouts

5. **Cache hit rates**
   - Use TextLayoutCache.logStats()
   - Target: >80% hit rate after initial load

---

## Implementation Priority

**Do First (Highest Impact):**
1. BubbleThemeSet extraction → Eliminates 7 operations per message
2. Reply preview cache integration → Eliminates 2 heavy measurements per reply message

**Do Next (Medium Impact):**
3. Audio state centralization → Cleaner architecture + better performance
4. Shape cache object → Easy win

**Do Last (Polish):**
5. BorderStroke in theme set
6. Layout modifier reuse
7. CompositionLocal parameter passing

---

## Code Quality Notes

The current implementation is **exceptionally well-optimized** for a Compose-based chat:

✅ **Excellent practices already in place:**
- TextLayoutCache with LRU eviction
- Ultra-flat node hierarchies (1 node per text message!)
- Conditional animation state allocation
- Canvas-based overlay rendering
- Static helper functions
- Performance metrics and logging

✅ **Areas of refinement identified:**
- Theme data can be pre-computed (not a design flaw, just an optimization opportunity)
- Reply measurements can be cached (TextLayoutCache exists, just needs extension)
- Audio state can be centralized (better architecture + performance)

This is **not a rewrite** - it's about **extracting the last 10-20% of performance** by moving computations out of the hot scroll path.

---

## Questions for Further Optimization

1. **Message.displayModel**: How is this computed? Is it cached at the data layer?
2. **Message.aspectRatio**: Is this pre-calculated or computed on-demand?
3. **GroupedMediaMessageBubble**: Does it have similar optimization opportunities?
4. **VoiceMessageBubble**: Is waveform visualization cached or computed per frame?
5. **DeletedForAllTwoPhaseContent**: Animation state - can it be pooled?

Let me know if you want me to dive deeper into any of these areas! 🚀
