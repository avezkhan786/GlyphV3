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
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        recyclerView.apply {
            isClickable = true
            isFocusable = true
            setItemViewCacheSize(OFFSCREEN_VIEW_CACHE_SIZE)
            setHasFixedSize(true)
            itemAnimator = null
            isNestedScrollingEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutManager = BufferedLinearLayoutManager(context).apply {
                stackFromEnd = true
                isItemPrefetchEnabled = true
                initialPrefetchItemCount = OPENING_PREFETCH_ITEM_COUNT
            }
            this.adapter = this@RecyclerCoordinator.adapter
        }
    }

    private class BufferedLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = 0
            extraLayoutSpace[1] = 0
        }
    }

    fun enableOffscreenBuffer() { /* no-op */ }

    fun warmRecycledViewPool(isFinishingProvider: () -> Boolean) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)
        if (isFinishingProvider()) return
        warmCriticalViewHolders(pool, isFinishingProvider)
    }

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
        pool.setMaxRecycledViews(1, 30)
        pool.setMaxRecycledViews(2, 30)
        pool.setMaxRecycledViews(3, 16)
        pool.setMaxRecycledViews(4, 16)
        pool.setMaxRecycledViews(7, 6)
        pool.setMaxRecycledViews(8, 6)
        pool.setMaxRecycledViews(9, 8)
        pool.setMaxRecycledViews(10, 8)
        pool.setMaxRecycledViews(11, 4)
        pool.setMaxRecycledViews(12, 4)
        pool.setMaxRecycledViews(13, 6)
        pool.setMaxRecycledViews(17, 4)
        pool.setMaxRecycledViews(18, 4)
        pool.setMaxRecycledViews(19, 6)
        pool.setMaxRecycledViews(20, 6)
    }

    private fun warmCriticalViewHolders(pool: RecyclerView.RecycledViewPool, isFinishingProvider: () -> Boolean) {
        try {
            for (viewType in CRITICAL_WARM_VIEW_TYPES) {
                if (isFinishingProvider()) return
                pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
            }
        } catch (exception: Exception) {
            Log.w("ChatActivity", "Critical VH pool pre-inflate failed", exception)
        }
    }

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

    suspend fun warmCommonViewHoldersAdaptiveAsync(
        messages: List<com.glyph.glyph_v3.data.models.Message>,
        isFinishingProvider: () -> Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)
        val warmList = buildAdaptiveWarmList(messages)
        try {
            for (viewType in warmList) {
                currentCoroutineContext().ensureActive()
                if (isFinishingProvider()) return@withContext
                pool.putRecycledView(adapter.createViewHolder(recyclerView, viewType))
                yield()
            }
        } catch (exception: Exception) {
            Log.w("ChatActivity", "Adaptive VH pool warm async failed", exception)
        }
    }

    var currentWarmStage: WarmStage = WarmStage.STAGE1_CRITICAL
        private set

    suspend fun preInflateViewHoldersNow(
        isFinishingProvider: () -> Boolean,
        shouldPauseProvider: () -> Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        val pool = recyclerView.recycledViewPool
        configureRecycledViewPool(pool)
        currentWarmStage = WarmStage.entries.firstOrNull { stage ->
            stage.targetMap.any { (viewType, target) ->
                pool.getRecycledViewCount(viewType) < target
            }
        } ?: WarmStage.STAGE3_FULL
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
        currentWarmStage = WarmStage.STAGE3_FULL
    }

    fun isPoolBelowFullTargets(): Boolean {
        val pool = recyclerView.recycledViewPool
        return WarmStage.STAGE3_FULL.targetMap.any { (viewType, target) ->
            pool.getRecycledViewCount(viewType) < target
        }
    }

    internal companion object {
        const val OPENING_PREFETCH_ITEM_COUNT = 10
        const val OFFSCREEN_VIEW_CACHE_SIZE = 4
        val CRITICAL_WARM_VIEW_TYPES = intArrayOf(1, 2, 3, 4, 9, 10, 13)
        private const val MIN_ADAPTIVE_MESSAGES = 20
        private const val ADAPTIVE_WARM_TOTAL = 24
        private const val ADAPTIVE_WARM_MIN_PER_PAIR = 1

        val FIRST_FLING_WARM_VIEW_TYPES = intArrayOf(
            1, 2, 1, 2, 1, 2, 1, 2, 1, 2,
            1, 2, 1, 2, 1, 2, 1, 2, 1, 2,
            1, 1, 1, 1, 1,
            3, 4, 3, 4,
            13, 13,
            9, 10,
            19, 20
        )

        val FULL_POOL_TARGETS = linkedMapOf(
            1 to 20, 2 to 20,
            3 to 8, 4 to 8,
            13 to 6,
            9 to 3, 10 to 3,
            19 to 3, 20 to 3
        )

        fun buildAdaptiveWarmList(messages: List<com.glyph.glyph_v3.data.models.Message>): IntArray {
            if (messages.size < MIN_ADAPTIVE_MESSAGES) return FIRST_FLING_WARM_VIEW_TYPES
            data class TypePair(val incomingType: Int, val outgoingType: Int, val label: String)
            val pairs = listOf(
                TypePair(1, 2, "text"), TypePair(3, 4, "media"), TypePair(9, 10, "collage"),
                TypePair(19, 20, "document"), TypePair(17, 18, "video_note"),
                TypePair(7, 8, "audio"), TypePair(11, 12, "contact")
            )
            val counts = mutableMapOf<TypePair, Int>()
            for (msg in messages) {
                val viewType = messageToViewType(msg)
                val pair = pairs.firstOrNull { it.incomingType == viewType || it.outgoingType == viewType }
                if (pair != null) counts[pair] = (counts[pair] ?: 0) + 1
            }
            val dateHeaderPair = TypePair(13, 13, "date_header")
            val totalMessages = counts.values.sum()
            if (totalMessages == 0) return FIRST_FLING_WARM_VIEW_TYPES
            val allocations = linkedMapOf<TypePair, Int>()
            var allocated = 0
            for (pair in pairs) {
                if (counts.containsKey(pair)) { allocations[pair] = ADAPTIVE_WARM_MIN_PER_PAIR; allocated += ADAPTIVE_WARM_MIN_PER_PAIR }
            }
            val remainingBudget = ADAPTIVE_WARM_TOTAL - allocated - 2
            if (remainingBudget > 0 && totalMessages > 0) {
                for (pair in pairs) {
                    val count = counts[pair] ?: 0
                    if (count == 0) continue
                    allocations[pair] = (allocations[pair] ?: 0) + ((count.toFloat() / totalMessages) * remainingBudget).toInt()
                }
            }
            allocations[dateHeaderPair] = 2
            val result = mutableListOf<Int>()
            for ((pair, count) in allocations) {
                val halfUp = (count + 1) / 2
                val halfDown = count / 2
                for (i in 0 until halfUp) result.add(pair.incomingType)
                for (i in 0 until halfDown) result.add(pair.outgoingType)
            }
            while (result.size < ADAPTIVE_WARM_TOTAL) { result.add(1); result.add(2) }
            return result.take(ADAPTIVE_WARM_TOTAL + 2).toIntArray()
        }

        private fun messageToViewType(msg: com.glyph.glyph_v3.data.models.Message): Int {
            val incoming = msg.isIncoming
            return when (msg.type) {
                com.glyph.glyph_v3.data.models.MessageType.TEXT, com.glyph.glyph_v3.data.models.MessageType.STATUS_REPLY, com.glyph.glyph_v3.data.models.MessageType.SYSTEM -> if (incoming) 1 else 2
                com.glyph.glyph_v3.data.models.MessageType.IMAGE, com.glyph.glyph_v3.data.models.MessageType.GIF, com.glyph.glyph_v3.data.models.MessageType.MEME, com.glyph.glyph_v3.data.models.MessageType.STICKER, com.glyph.glyph_v3.data.models.MessageType.KLIPY_EMOJI -> if (incoming) 3 else 4
                com.glyph.glyph_v3.data.models.MessageType.VIDEO -> if (msg.isVideoNote) { if (incoming) 17 else 18 } else { if (incoming) 3 else 4 }
                com.glyph.glyph_v3.data.models.MessageType.AUDIO -> if (incoming) 7 else 8
                com.glyph.glyph_v3.data.models.MessageType.MEDIA_GROUP -> if (incoming) 9 else 10
                com.glyph.glyph_v3.data.models.MessageType.CONTACT -> if (incoming) 11 else 12
                com.glyph.glyph_v3.data.models.MessageType.DOCUMENT -> if (incoming) 19 else 20
            }
        }

        fun scaleTargetsForDevice(context: Context, baseTargets: Map<Int, Int>): Map<Int, Int> {
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
            return baseTargets.mapValues { (_, target) -> (target * scaleFactor).toInt().coerceAtLeast(1) }
        }
    }

    enum class WarmStage(val targetMap: Map<Int, Int>) {
        STAGE1_CRITICAL(mapOf(1 to 15, 2 to 15, 3 to 4, 4 to 4, 13 to 4, 9 to 2, 10 to 2)),
        STAGE2_EXPANDED(mapOf(1 to 16, 2 to 16, 3 to 6, 4 to 6, 13 to 5, 9 to 3, 10 to 3, 19 to 2, 20 to 2)),
        STAGE3_FULL(FULL_POOL_TARGETS)
    }
}

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
