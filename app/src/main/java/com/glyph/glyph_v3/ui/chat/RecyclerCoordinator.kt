package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal class RecyclerCoordinator(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val adapter: ChatAdapter
) {
    fun configureRecyclerView() {
        recyclerView.apply {
            isClickable = true
            isFocusable = true
            // Small off-screen bound-view cache. This holds views that JUST scrolled off, ready
            // to return WITHOUT a rebind on a small scroll reversal. It must stay small: during a
            // fast fling positions never repeat, so cached views are never reused and a large
            // cache only DELAYS those views' return to the shared RecycledViewPool (by its size),
            // starving the leading edge and forcing expensive re-inflation. 4 is the WhatsApp-like
            // sweet spot — enough for tiny jitter, small enough that the pool refills immediately.
            setItemViewCacheSize(OFFSCREEN_VIEW_CACHE_SIZE)
            setHasFixedSize(true)
            itemAnimator = null
            layoutManager = BufferedLinearLayoutManager(context).apply {
                stackFromEnd = true
                isItemPrefetchEnabled = true
                initialPrefetchItemCount = OPENING_PREFETCH_ITEM_COUNT
            }
            this.adapter = this@RecyclerCoordinator.adapter
        }
    }

    /**
     * LinearLayoutManager subclass kept only so [enableOffscreenBuffer] has a stable type to call.
     *
     * IMPORTANT: it no longer inflates the laid-out band. An earlier version laid out a FULL
     * viewport height of extra rows above AND below the visible area. On a fast fling that holds a
     * whole screen of trailing views attached one extra screen longer before they recycle, so the
     * leading edge runs out of pooled ViewHolders and has to inflate brand-new bubbles mid-frame
     * (~17ms each on mid-range CPUs) — exactly the sustained "INFLATE during scroll" churn seen in
     * profiling (200+ inflations in a single scroll session). Tight, default-style recycling (no
     * extra band) lets every trailing view return to the pool immediately and feed the leading
     * edge, so a fling reuses views instead of inflating them.
     */
    private class BufferedLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            // No extra band — keep the working set at ~one viewport so recycling stays tight and
            // the pool is never starved during fast flings.
            extraLayoutSpace[0] = 0
            extraLayoutSpace[1] = 0
        }
    }

    /**
     * Retained as a no-op for existing call sites. The off-screen layout band was removed because
     * it starved the recycled-view pool during fast flings (see [BufferedLinearLayoutManager]).
     */
    fun enableOffscreenBuffer() {
        // Intentionally does nothing now. Tight recycling (no extra layout band) is what keeps the
        // pool fed; pre-laying an off-screen band caused the sustained mid-fling inflation churn.
    }

    fun warmRecycledViewPool(isFinishingProvider: () -> Boolean) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)

        if (isFinishingProvider()) return
        warmCriticalViewHolders(pool, isFinishingProvider)
    }

    /**
     * Synchronously inflate a bounded set of the most common bubble types into the recycled-view
     * pool. Intended to run right after pool config (including during the data-loading gap)
     * so the pool holds spare text/media bubbles BEFORE the user's first fling.
     * Cheap and idempotent — extra copies simply top up the pool up to its per-type cap.
     */
    fun warmCommonViewHoldersNow(isFinishingProvider: () -> Boolean) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)
        try {
            for (viewType in FIRST_FLING_WARM_VIEW_TYPES) {
                if (isFinishingProvider()) return
                pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
            }
        } catch (exception: Exception) {
            Log.w("ChatActivity", "First-fling VH pool warm failed", exception)
        }
    }

    private fun configureRecycledViewPool(pool: RecyclerView.RecycledViewPool) {
        // When the off-screen cache overflows during a fling, RV pulls from this pool (rebind,
        // cheap) rather than inflating new bubbles (expensive). Caps are raised so a fast
        // media-heavy fling recycles existing bubbles instead of inflating mid-frame.
        // NOTE: types 5, 6 are dead (getItemViewType() maps VIDEO to IMAGE types 3, 4).
        pool.setMaxRecycledViews(1, 30)
        pool.setMaxRecycledViews(2, 30)
        pool.setMaxRecycledViews(3, 16)
        pool.setMaxRecycledViews(4, 16)
        pool.setMaxRecycledViews(9, 8)
        pool.setMaxRecycledViews(10, 8)
        pool.setMaxRecycledViews(13, 6)
        pool.setMaxRecycledViews(17, 4)
        pool.setMaxRecycledViews(18, 4)
        pool.setMaxRecycledViews(19, 6)
        pool.setMaxRecycledViews(20, 6)
    }

    private fun warmCriticalViewHolders(
        pool: RecyclerView.RecycledViewPool,
        isFinishingProvider: () -> Boolean
    ) {
        try {
            for (viewType in CRITICAL_WARM_VIEW_TYPES) {
                if (isFinishingProvider()) return
                pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
            }
        } catch (exception: Exception) {
            Log.w("ChatActivity", "Critical VH pool pre-inflate failed", exception)
        }
    }

    /**
     * Synchronous warm using an adaptively-built list based on actual message type
     * distribution. Analyzes [messages] to determine which view types dominate and
     * allocates warm ViewHolders proportionally. Falls back to the static
     * [FIRST_FLING_WARM_VIEW_TYPES] if fewer than MIN_ADAPTIVE_MESSAGES are available.
     */
    fun warmCommonViewHoldersAdaptive(
        messages: List<com.glyph.glyph_v3.data.models.Message>,
        isFinishingProvider: () -> Boolean
    ) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)
        val warmList = buildAdaptiveWarmList(messages)
        try {
            for (viewType in warmList) {
                if (isFinishingProvider()) return
                pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
            }
        } catch (exception: Exception) {
            Log.w("ChatActivity", "Adaptive VH pool warm failed", exception)
        }
    }

    /**
     * Current warm stage. Starts at STAGE1_CRITICAL and progresses through STAGE3_FULL. Starts at STAGE1_CRITICAL and progresses through STAGE3_FULL.
     * Resets to STAGE1_CRITICAL on each new call to [preInflateViewHoldersNow] if the pool
     * was cleared or the activity was recreated.
     */
    var currentWarmStage: WarmStage = WarmStage.STAGE1_CRITICAL
        private set

    /**
     * Async pool warming with progressive stages and frame-budget protection.
     *
     * Progresses through three stages:
     * - STAGE1_CRITICAL: minimum for a smooth first fling (~30 VHs)
     * - STAGE2_EXPANDED: comfortable buffer for sustained scrolling (~50 VHs)
     * - STAGE3_FULL: all targets reached (~74 VHs)
     *
     * Each stage is a round-robin fill: inflate one of each under-target type per pass,
     * skipping types that already meet that stage's target. yield() after each inflate
     * hands the main thread back to the Choreographer so a frame can render.
     *
     * @param isFinishingProvider returns true if the activity is finishing/destroyed
     * @param shouldPauseProvider returns true if warming should pause (idle queue paused,
     *        frame budget exceeded, etc.)
     */
    suspend fun preInflateViewHoldersNow(
        isFinishingProvider: () -> Boolean,
        shouldPauseProvider: () -> Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)

        // Determine which stage to start at based on current pool state.
        // If all STAGE1 targets are met, jump to STAGE2; etc.
        currentWarmStage = WarmStage.entries.firstOrNull { stage ->
            stage.targetMap.any { (viewType, target) ->
                pool.getRecycledViewCount(viewType) < target
            }
        } ?: WarmStage.STAGE3_FULL

        // Progress through remaining stages
        for (stage in WarmStage.entries.dropWhile { it != currentWarmStage }) {
            currentWarmStage = stage
            var madeProgress = true
            while (madeProgress) {
                madeProgress = false
                for ((viewType, target) in stage.targetMap) {
                    currentCoroutineContext().ensureActive()
                    if (isFinishingProvider() || shouldPauseProvider()) return@withContext
                    if (pool.getRecycledViewCount(viewType) >= target) continue
                    runCatching {
                        pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
                    }.onFailure { exception ->
                        Log.w("ChatActivity", "Deferred VH pool pre-inflate failed type=$viewType", exception)
                    }
                    madeProgress = true
                    yield()
                }
            }
        }
        // All stages complete
        currentWarmStage = WarmStage.STAGE3_FULL
    }

    /**
     * Returns true if the pool has not reached all STAGE3_FULL targets.
     */
    fun isPoolBelowFullTargets(): Boolean {
        val pool = recyclerView.recycledViewPool
        return WarmStage.STAGE3_FULL.targetMap.any { (viewType, target) ->
            pool.getRecycledViewCount(viewType) < target
        }
    }

    // ── Companion: constants, targets, stages ────────────────────────────────────────

    internal companion object {
        const val OPENING_PREFETCH_ITEM_COUNT = 6

        // Off-screen ready-bound view cache (RecyclerView.setItemViewCacheSize). Kept small on
        // purpose: a large cache delays views' return to the shared RecycledViewPool during a fast
        // fling (cached views are position-bound and never reused while flinging), which starves
        // the leading edge and forces re-inflation. 4 covers small scroll reversals without
        // throttling pool refill.
        const val OFFSCREEN_VIEW_CACHE_SIZE = 4

        val CRITICAL_WARM_VIEW_TYPES = intArrayOf(1, 2, 3, 4, 9, 10, 13)

        // Minimum message count for adaptive warming. Below this, the static fallback list is used.
        private const val MIN_ADAPTIVE_MESSAGES = 20

        // Total ViewHolders to allocate in the adaptive sync warm batch.
        private const val ADAPTIVE_WARM_TOTAL = 24

        // Minimum count per view-type pair in the adaptive warm (ensures every type gets at least 1).
        private const val ADAPTIVE_WARM_MIN_PER_PAIR = 1

        // SYNCHRONOUS safety batch inflated before the first scroll. Expanded to 24 VHs
        // covering all active view types (including video notes 19/20 and collage 9/10).
        // Used as a fallback when adaptive warming doesn't have enough message history.
        val FIRST_FLING_WARM_VIEW_TYPES = intArrayOf(
            1, 2, 1, 2, 1, 2, 1, 2,      // 8 text (4 in + 4 out)
            1, 2, 1, 2,                   // +4 text (2 in + 2 out)
            3, 4, 3, 4,                   // 4 media (2 in + 2 out)
            13, 13,                       // 2 date headers
            9, 10,                        // 2 collage (1 in + 1 out)
            19, 20                        // 2 video notes (1 in + 1 out)
        )

        // Full target pool occupancy per view type — STAGE3_FULL.
        // Sized for a 100-message initial window + smooth paging.
        val FULL_POOL_TARGETS = linkedMapOf(
            1 to 20, 2 to 20,   // incoming / outgoing text
            3 to 8, 4 to 8,     // incoming / outgoing media bubbles
            13 to 6,            // date headers / system rows
            9 to 3, 10 to 3,    // incoming / outgoing collage
            19 to 3, 20 to 3    // incoming / outgoing video notes
        )

        // ── Adaptive Warm ─────────────────────────────────────────────────────────

        /**
         * Builds an adaptive ViewHolder warm list based on the actual message type
         * distribution in [messages]. Falls back to [FIRST_FLING_WARM_VIEW_TYPES] if
         * fewer than [MIN_ADAPTIVE_MESSAGES] messages are available.
         *
         * Algorithm:
         * 1. Count view type occurrences across the message list (incoming + outgoing per type).
         * 2. Compute the proportion of each view-type pair relative to total messages.
         * 3. Allocate [ADAPTIVE_WARM_TOTAL] ViewHolders proportionally, ensuring every
         *    active pair gets at least [ADAPTIVE_WARM_MIN_PER_PAIR].
         * 4. Interleave incoming/outgoing within each pair for balanced coverage.
         */
        fun buildAdaptiveWarmList(
            messages: List<com.glyph.glyph_v3.data.models.Message>
        ): IntArray {
            if (messages.size < MIN_ADAPTIVE_MESSAGES) {
                return FIRST_FLING_WARM_VIEW_TYPES
            }

            // Count view types. Each message maps to an (incomingType, outgoingType) pair.
            // Key = canonical "pair key" (e.g. "text", "media"), value = count of messages.
            data class TypePair(val incomingType: Int, val outgoingType: Int, val label: String)
            val pairs = listOf(
                TypePair(1, 2, "text"),
                TypePair(3, 4, "media"),
                TypePair(9, 10, "collage"),
                TypePair(19, 20, "document"),
                TypePair(17, 18, "video_note"),
                TypePair(7, 8, "audio"),
                TypePair(11, 12, "contact")
            )

            // Count messages per pair
            val counts = mutableMapOf<TypePair, Int>()
            for (msg in messages) {
                val viewType = messageToViewType(msg)
                val pair = pairs.firstOrNull { it.incomingType == viewType || it.outgoingType == viewType }
                if (pair != null) {
                    counts[pair] = (counts[pair] ?: 0) + 1
                }
            }

            // Add date headers separately (they appear between date boundaries, not per-message)
            val dateHeaderPair = TypePair(13, 13, "date_header")

            val totalMessages = counts.values.sum()
            if (totalMessages == 0) return FIRST_FLING_WARM_VIEW_TYPES

            // Allocate ViewHolders proportionally
            val allocations = linkedMapOf<TypePair, Int>()
            var allocated = 0

            // First pass: give every pair at least the minimum
            val remainingPairs = pairs.toMutableList()
            for (pair in remainingPairs) {
                if (counts.containsKey(pair)) {
                    allocations[pair] = ADAPTIVE_WARM_MIN_PER_PAIR
                    allocated += ADAPTIVE_WARM_MIN_PER_PAIR
                }
            }

            // Second pass: distribute remaining budget proportionally
            val remainingBudget = ADAPTIVE_WARM_TOTAL - allocated - 2 // reserve 2 for date headers
            if (remainingBudget > 0 && totalMessages > 0) {
                for (pair in pairs) {
                    val count = counts[pair] ?: 0
                    if (count == 0) continue
                    val proportion = count.toFloat() / totalMessages
                    val extra = (proportion * remainingBudget).toInt()
                    allocations[pair] = (allocations[pair] ?: 0) + extra
                }
            }

            // Always include 2 date headers
            allocations[dateHeaderPair] = 2

            // Build the flat intArray, interleaving incoming/outgoing
            val result = mutableListOf<Int>()
            for ((pair, count) in allocations) {
                val halfUp = (count + 1) / 2  // ceiling division for incoming
                val halfDown = count / 2       // floor division for outgoing
                for (i in 0 until halfUp) result.add(pair.incomingType)
                for (i in 0 until halfDown) result.add(pair.outgoingType)
            }

            // Safety: if we somehow built fewer than expected, top up with text types
            while (result.size < ADAPTIVE_WARM_TOTAL) {
                result.add(1)
                result.add(2)
            }

            return result.take(ADAPTIVE_WARM_TOTAL + 2).toIntArray() // +2 for date headers
        }

        /**
         * Maps a [Message] to its RecyclerView view type. This duplicates the logic from
         * ChatAdapter.getItemViewType() for offline analysis.
         */
        private fun messageToViewType(
            msg: com.glyph.glyph_v3.data.models.Message
        ): Int {
            val incoming = msg.isIncoming
            return when (msg.type) {
                com.glyph.glyph_v3.data.models.MessageType.TEXT,
                com.glyph.glyph_v3.data.models.MessageType.STATUS_REPLY,
                com.glyph.glyph_v3.data.models.MessageType.SYSTEM ->
                    if (incoming) 1 else 2

                com.glyph.glyph_v3.data.models.MessageType.IMAGE,
                com.glyph.glyph_v3.data.models.MessageType.GIF,
                com.glyph.glyph_v3.data.models.MessageType.MEME,
                com.glyph.glyph_v3.data.models.MessageType.STICKER,
                com.glyph.glyph_v3.data.models.MessageType.KLIPY_EMOJI ->
                    if (incoming) 3 else 4

                com.glyph.glyph_v3.data.models.MessageType.VIDEO ->
                    if (msg.isVideoNote) {
                        if (incoming) 17 else 18
                    } else {
                        if (incoming) 3 else 4
                    }

                com.glyph.glyph_v3.data.models.MessageType.AUDIO ->
                    if (incoming) 7 else 8

                com.glyph.glyph_v3.data.models.MessageType.MEDIA_GROUP ->
                    if (incoming) 9 else 10

                com.glyph.glyph_v3.data.models.MessageType.CONTACT ->
                    if (incoming) 11 else 12

                com.glyph.glyph_v3.data.models.MessageType.DOCUMENT ->
                    if (incoming) 19 else 20
            }
        }

        // ── Device-Aware Scaling ──────────────────────────────────────────────────

        /**
         * Scales warm targets based on device memory class.
         * - Low RAM (< 256 MB): 40% of base targets (skip STAGE3 entirely)
         * - Medium RAM (256-512 MB): 70% of base targets
         * - High RAM (> 512 MB): full targets (no scaling)
         */
        fun scaleTargetsForDevice(
            context: Context,
            baseTargets: Map<Int, Int>
        ): Map<Int, Int> {
            val memoryClass = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.memoryClass ?: 256
            }.getOrDefault(256)

            val scaleFactor = when {
                memoryClass < 128 -> 0.4f
                memoryClass < 256 -> 0.7f
                else -> 1.0f
            }

            if (scaleFactor >= 1.0f) return baseTargets

            return baseTargets.mapValues { (_, target) ->
                (target * scaleFactor).toInt().coerceAtLeast(1)
            }
        }
    }

    /**
     * Progressive warming stages. Each stage defines per-view-type pool targets.
     * Stages are completed in order; warming only advances to the next stage when
     * all targets in the current stage are met.
     */
    enum class WarmStage(val targetMap: Map<Int, Int>) {
        /** Minimum for a smooth first fling. ~30 VHs, mostly text. */
        STAGE1_CRITICAL(mapOf(
            1 to 10, 2 to 10,   // text: 10 each (covers ~15-message fling)
            3 to 4, 4 to 4,     // media: 4 each
            13 to 4,            // date headers
            9 to 2, 10 to 2     // collage
        )),
        /** Comfortable buffer for sustained scrolling. ~50 VHs. */
        STAGE2_EXPANDED(mapOf(
            1 to 16, 2 to 16,   // text: 16 each
            3 to 6, 4 to 6,     // media: 6 each
            13 to 5,            // date headers
            9 to 3, 10 to 3,    // collage
            19 to 2, 20 to 2    // video notes
        )),
        /** All targets reached. ~74 VHs. */
        STAGE3_FULL(FULL_POOL_TARGETS)
    }
}

/**
 * Monitors frame times via [Choreographer]. While active, tracks whether the most
 * recent frame exceeded [maxFrameMs]. Used as a safety net so pool warming during
 * scroll settling never creates jank — if frames go over budget, warming pauses
 * until they recover.
 */
internal class FrameBudgetGuard(private val maxFrameMs: Float = 18.0f) {
    @Volatile var lastFrameOverBudget: Boolean = false
        private set

    private var lastFrameTimeNs: Long = 0L
    private var callback: Choreographer.FrameCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTimeNs > 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameTimeNs) / 1_000_000f
                    lastFrameOverBudget = deltaMs > maxFrameMs
                }
                lastFrameTimeNs = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        callback = cb
        lastFrameTimeNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop() {
        callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        callback = null
        lastFrameOverBudget = false
    }
}
