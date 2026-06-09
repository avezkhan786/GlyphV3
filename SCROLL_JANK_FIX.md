# Scroll Jank Fix & Optimization

## Problem
Users reported "janky" scrolling, especially during the first scroll after opening a chat on mid-range devices. This is typically caused by:
1.  **Object Allocation:** Creating thousands of `RoundedCornerShape` objects during the initial layout/scroll.
2.  **State Overhead:** `animateFloatAsState` creating subscriptions for every list item, even when not animating.
3.  **Main Thread Work:** Prefetch logic running on the main thread during the critical initial composition.

## Solution

### 1. Shape Caching (`BubbleShapeCache`)
Implemented a static cache for message bubble shapes.
- **Before:** Every message bubble created a new `RoundedCornerShape` object on every recomposition.
- **After:** Shapes are cached based on `(isSelf, groupPosition, radius)`. Since there are only ~8 possible combinations, this reduces allocation to near zero after the first few frames.

### 2. Optimized Swipe Animation
Refactored `SwipeableMessageBubble` to remove unnecessary state objects.
- **Before:**
  ```kotlin
  val iconAlpha by animateFloatAsState(...) // Created for EVERY message
  val iconScale by animateFloatAsState(...) // Created for EVERY message
  ```
- **After:**
  ```kotlin
  // Derived directly from offsetX (no extra state/animation overhead)
  val iconAlpha = if (offsetX > 0) ... else 0f
  ```
  This removes ~200 state subscriptions for a list of 100 messages.

### 3. Background Prefetching
Moved the initial image prefetch logic off the main thread.
- **Before:** `messages.asSequence()...` ran on the UI thread during the first frame.
- **After:** Wrapped in `LaunchedEffect(messages) { withContext(Dispatchers.Default) { ... } }` to process the list in the background.

## Verification
- **First Scroll:** Should be significantly smoother as the GPU doesn't wait for shape allocation and the UI thread isn't blocked by prefetch logic.
- **Memory:** Reduced garbage collection pressure due to fewer object allocations.
