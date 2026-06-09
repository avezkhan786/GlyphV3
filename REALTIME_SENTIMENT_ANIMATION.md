# Real-Time Sentiment Animation System

## Overview
This document explains the implementation of the real-time sentiment analysis feature that displays animated flying emojis based on the detected emotional tone of messages being typed.

## Architecture

### Core Components

1. **SentimentType.kt** - Sentiment categories and visual mappings
   - 8 sentiment types: POSITIVE, NEGATIVE, NEUTRAL, EXCITED, ANGRY, ROMANTIC, SAD, FUNNY
   - Each with 6-10 matching emojis and gradient colors
   
2. **FlyingEmojiAnimation.kt** - Emoji particle animation system
   - Continuous spawning loop (500ms intervals)
   - Max 8 concurrent emojis
   - Smooth fade-in/out with smoothstep interpolation
   - Natural floating motion (ease-out quad vertical, linear horizontal)
   - 4-second animation duration per emoji

3. **SentimentGradientBackground.kt** - Animated gradient overlay (currently disabled)
   - Radial gradient with animated transitions
   - Alpha forced to 0f for emoji-only mode
   - Preserved for future customization

4. **SentimentOverlay.kt** - Composite UI component
   - Combines gradient + emoji layers
   - Injected into ChatActivity at elevation 1f (behind messages, in front of wallpaper)

5. **ExpressiveTypingManager.kt** - Central orchestrator
   - Coordinates LiveTypingRepository + SentimentAnalysisService
   - Implements 2.5s sentiment linger period after typing stops
   - Emits ExpressiveState via StateFlow

6. **ChatActivity.kt** - UI integration layer
   - **CRITICAL**: State management for Compose observation

## Critical Implementation Details

### Compose State Observation Fix

**Problem**: Animations only triggered after typing indicator disappeared and reappeared, not in real-time during continuous typing.

**Root Cause**: The initial implementation used `by mutableStateOf()` delegation:
```kotlin
// BROKEN - Compose can't observe this
private var expressiveSentiment by mutableStateOf(SentimentType.NEUTRAL)
```

This creates a snapshot state object, but when accessed outside a `@Composable` context (like in `setContent` lambda), Compose doesn't track reads and therefore doesn't know to recompose when values change.

**Solution**: Explicit State<T> objects with delegate accessors:
```kotlin
// WORKING - Compose observes .value reads
private val _expressiveSentiment = mutableStateOf(SentimentType.NEUTRAL)
private var expressiveSentiment: SentimentType
    get() = _expressiveSentiment.value
    set(value) { _expressiveSentiment.value = value }
```

Then in `setupSentimentOverlay()`:
```kotlin
setContent {
    // Reading .value inside @Composable enables observation
    val sentiment = _expressiveSentiment.value
    val active = _isExpressiveActive.value
    
    SentimentOverlay(sentiment = sentiment, isActive = active)
}
```

**Why This Works**:
1. Reading `_expressiveSentiment.value` inside `setContent` creates a Compose snapshot
2. Compose's snapshot system tracks this read and marks the composition for invalidation
3. When `ExpressiveTypingManager` updates sentiment → ChatActivity sets new value → Compose detects change → SentimentOverlay recomposes immediately
4. Recomposition triggers `FlyingEmojiAnimation`'s `LaunchedEffect` to restart with new sentiment

### Continuous Spawning Loop

```kotlin
LaunchedEffect(sentiment, isActive) {
    if (!isActive) return@LaunchedEffect
    
    while (activeEmojis.size < maxConcurrentEmojis && isActive) {
        // Spawn emoji
        activeEmojis.add(createEmoji())
        delay(500L) // Spawn interval
    }
}
```

This ensures:
- New emojis appear continuously while typing (not just on sentiment change)
- Spawning stops gracefully when inactive
- Restarts when sentiment changes or reactivates

### Sentiment Linger Period

```kotlin
private fun handleTextChange(newText: String?) {
    if (newText.isNullOrBlank()) {
        val shouldLinger = _expressiveState.value.sentiment != SentimentType.NEUTRAL
        if (shouldLinger) {
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(SENTIMENT_LINGER_MS) // 2500ms
                clearExpressiveState()
            }
        }
    } else {
        lingerJob?.cancel()
        analyzeAndEmit(newText)
    }
}
```

This prevents animations from disappearing before users see them.

## Animation Specifications

### Emoji Vertical Movement
- **Duration**: 4000ms
- **Easing**: Ease-out quad (`y * (2 - y)`)
- **Range**: 0% → 100% of container height
- **Behavior**: Fast start, gradual slow-down mimicking natural deceleration

### Emoji Horizontal Drift
- **Duration**: 4000ms  
- **Easing**: Linear
- **Range**: Random ±15% of start position
- **Behavior**: Gentle side-to-side floating

### Emoji Fade Transitions
- **Fade-in**: First 20% of animation (0-800ms)
- **Hold**: Middle 55% (800-3000ms) at full opacity
- **Fade-out**: Last 25% (3000-4000ms)
- **Interpolation**: Smoothstep (`t² * (3 - 2t)`) for S-curve transitions

## Performance Characteristics

- **Max Concurrent Emojis**: 8
- **Spawn Interval**: 500ms
- **Emoji Lifetime**: 4 seconds
- **GPU Acceleration**: All animations use `graphicsLayer` modifiers
- **Throttling**: Sentiment analysis throttled to 600ms
- **Debouncing**: Live typing updates debounced to 150ms

## Testing Checklist

- [ ] Build and install APK on physical device
- [ ] Verify emojis spawn immediately when typing starts
- [ ] Confirm sentiment changes trigger new emoji types in real-time
- [ ] Validate 2.5s linger after typing stops
- [ ] Test rapid sentiment changes (e.g., "happy sad angry")
- [ ] Check performance on mid-range device (no dropped frames)
- [ ] Verify emoji cleanup (no memory leaks)
- [ ] Test edge case: very long typing sessions (>1 minute)

## Known Limitations

1. **Gradient Disabled**: Currently alpha forced to 0f; re-enable by removing alpha override in `SentimentGradientBackground.kt`
2. **Network Dependency**: Sentiment analysis requires internet connection to Cloud Functions
3. **API Costs**: Google Natural Language API has usage costs; implement client-side fallback for production

## Future Enhancements

- [ ] Client-side sentiment analysis (ML Kit or Hugging Face)
- [ ] Customizable spawn rate/max emojis in settings
- [ ] Sound effects matching sentiment
- [ ] Haptic feedback on sentiment changes
- [ ] User-customizable emoji sets per sentiment
- [ ] A/B test gradient re-enablement with different opacities

## Troubleshooting

### Animations don't start in real-time
**Check**: Ensure `setupSentimentOverlay()` reads from `State<T>.value` inside `setContent` block, not from delegate accessors.

### Emojis appear jerky or jump positions
**Check**: Verify `animateFloatAsState` is not using `infiniteRepeatable`. Should be one-shot animations with `tween` spec.

### Sentiment linger too short/long
**Adjust**: `SENTIMENT_LINGER_MS` in `ExpressiveTypingManager.kt` (currently 2500ms).

### Too many/few emojis
**Adjust**: `maxConcurrentEmojis` in `FlyingEmojiAnimation.kt` (currently 8) or spawn interval (currently 500ms).

## Code References

- State management: [ChatActivity.kt:235-250](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt#L235-L250)
- Compose injection: [ChatActivity.kt:3910-3950](app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt#L3910-L3950)
- Animation loop: [FlyingEmojiAnimation.kt](app/src/main/java/com/glyph/glyph_v3/ui/chat/expressive/FlyingEmojiAnimation.kt)
- Linger logic: [ExpressiveTypingManager.kt](app/src/main/java/com/glyph/glyph_v3/features/expressive/ExpressiveTypingManager.kt)
