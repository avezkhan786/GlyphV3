package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.util.Log
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
     * pool. Intended to run once, right after the first content frame is drawn (open animation
     * done, RecyclerView already visible), so the pool holds spare text/media bubbles BEFORE the
     * user's first fling. This eliminates the "smooth only after several scrolls" warm-up without
     * adding to cold-open latency, because it executes in the orientation gap after first paint.
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
        // cheap) rather than inflating new bubbles (expensive). Media/collage caps are raised so
        // a fast media-heavy fling recycles existing bubbles instead of inflating mid-frame.
        pool.setMaxRecycledViews(1, 30)
        pool.setMaxRecycledViews(2, 30)
        pool.setMaxRecycledViews(3, 16)
        pool.setMaxRecycledViews(4, 16)
        pool.setMaxRecycledViews(5, 6)
        pool.setMaxRecycledViews(6, 6)
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

    suspend fun preInflateViewHoldersNow(
        isFinishingProvider: () -> Boolean,
        shouldPauseProvider: () -> Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)

        // Round-robin fill: inflate one of each under-target type per pass, skipping types that
        // already hold enough spares. This front-loads coverage of ALL hot types (so an early
        // fling finds both incoming + outgoing text ready) and is fully idempotent — when a
        // scroll pauses warming and it later resumes, already-filled types are skipped so we
        // never re-pay the ~17ms inflation cost. yield() after each inflate hands the main
        // thread back to the Choreographer so a frame can render between inflations (no hitch).
        var madeProgress = true
        while (madeProgress) {
            madeProgress = false
            for ((viewType, target) in WARM_POOL_TARGETS) {
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

    private companion object {
        const val OPENING_PREFETCH_ITEM_COUNT = 6

        // Off-screen ready-bound view cache (RecyclerView.setItemViewCacheSize). Kept small on
        // purpose: a large cache delays views' return to the shared RecycledViewPool during a fast
        // fling (cached views are position-bound and never reused while flinging), which starves
        // the leading edge and forces re-inflation. 4 covers small scroll reversals without
        // throttling pool refill.
        const val OFFSCREEN_VIEW_CACHE_SIZE = 4

        val CRITICAL_WARM_VIEW_TYPES = intArrayOf(1, 2, 3, 4, 9, 10, 13)

        // Small SYNCHRONOUS safety batch inflated in the first-frame post — just enough to cover
        // a fling that happens within the first ~100ms (before the async warm's initial delay).
        // Kept tiny (6 light text bubbles ≈ 100ms) so it never adds a visible open hitch; the
        // bulk of pool warming is done asynchronously by preInflateViewHoldersNow.
        val FIRST_FLING_WARM_VIEW_TYPES = intArrayOf(1, 2, 1, 2, 1, 2)

        // Target pool occupancy per view type after asynchronous warming. Device profiling showed
        // a cold pool forces ~58 ViewHolder inflations during the first aggressive fling at
        // ~17ms each (≈1s of dropped frames), while a warm pool flings with zero inflations and
        // <26ms worst frame. These targets seed enough spares to absorb a full fast fling so the
        // scroll never inflates mid-frame. Dominated by text (types 1/2); media stays cheaper
        // because image decode is separately deferred to idle. Must be <= the per-type pool caps
        // in configureRecycledViewPool or putRecycledView silently drops the extra inflation.
        val WARM_POOL_TARGETS = linkedMapOf(
            1 to 22, 2 to 22,   // incoming / outgoing text — the dominant fling demand
            3 to 6, 4 to 6,     // incoming / outgoing media bubbles
            13 to 4,            // date headers / system rows
            9 to 2, 10 to 2,    // incoming / outgoing collage
            19 to 2, 20 to 2    // incoming / outgoing video notes
        )
    }
}
