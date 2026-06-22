package com.glyph.glyph_v3.ui.chat

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.util.StartupTrace
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

internal class ChatScrollController(
    private val chatIdProvider: () -> String?,
    private val onFirstScrollFinished: (String) -> Unit,
    private val scrollWorkProvider: () -> ChatAdapter.ScrollWorkSnapshot? = { null },
    // Diagnostics: recycled-view-pool occupancy per viewType (e.g. "1=22,2=21,3=4").
    private val poolSummaryProvider: () -> String = { "" },
    // Diagnostics: currently loaded message-window size (adapter item count).
    private val windowSizeProvider: () -> Int = { 0 },
    // Diagnostics: whether deferred startup work has been unlocked (first-frame done).
    private val startupUnlockedProvider: () -> Boolean = { true },
    // Diagnostics: whether the idle task queue is currently paused (background work gated).
    private val idleQueuePausedProvider: () -> Boolean = { false },
    // Diagnostics: elapsedRealtime ms at chat open (for "X ms after open" scroll timing).
    private val openElapsedMsProvider: () -> Long = { 0L }
) {
    var firstScrollMetricsLogged: Boolean = false
        private set
    var firstScrollTrackingActive: Boolean = false
        private set
    var scrollPerfTrackingActive: Boolean = false
        private set

    // Scroll readiness tracking: tracks whether the system is ready for smooth scrolling
    var isScrollReady: Boolean = false
        private set

    private var firstScrollFrameCount = 0
    private var firstScrollDroppedFrames = 0
    private var firstScrollSlowFrames = 0
    private var firstScrollLastFrameNs = 0L
    private var firstScrollStartElapsedMs = 0L
    // Work baseline captured at first-scroll start, used to report per-slow-frame deltas.
    private var firstScrollWorkBaseline: ChatAdapter.ScrollWorkSnapshot? = null
    private var firstScrollLastLoggedWork: ChatAdapter.ScrollWorkSnapshot? = null

    private var scrollPerfSessionCounter = 0
    private var scrollPerfCurrentSessionId = 0
    private var scrollPerfFrameCount = 0
    private var scrollPerfDroppedFrames = 0
    private var scrollPerfSlowFrames = 0
    private var scrollPerfWorstFrameMs = 0.0
    private var scrollPerfLastFrameNs = 0L
    private var scrollPerfStartElapsedMs = 0L
    private var scrollPerfAccumulatedDy = 0
    private var scrollPerfVisibleFirst = RecyclerView.NO_POSITION
    private var scrollPerfVisibleLast = RecyclerView.NO_POSITION
    private var scrollPerfStartReason = ""
    private var scrollPerfLastState = RecyclerView.SCROLL_STATE_IDLE
    private var scrollPerfWorkAtStart: ChatAdapter.ScrollWorkSnapshot? = null

    private val scrollPerfFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scrollPerfTrackingActive) return

            if (scrollPerfLastFrameNs != 0L) {
                val frameDeltaMs = (frameTimeNanos - scrollPerfLastFrameNs) / 1_000_000.0
                if (frameDeltaMs > 24.0) {
                    scrollPerfSlowFrames += 1
                }
                val dropped = floor(frameDeltaMs / 16.6667).toInt() - 1
                if (dropped > 0) {
                    scrollPerfDroppedFrames += dropped
                }
                scrollPerfWorstFrameMs = max(scrollPerfWorstFrameMs, frameDeltaMs)

                if (BuildConfig.DEBUG && (frameDeltaMs >= SCROLL_PERF_JANK_FRAME_MS || dropped > 0)) {
                    StartupTrace.logStage(
                        "chat_scroll_perf_jank",
                        "chatId=${chatId()} session=$scrollPerfCurrentSessionId frame=${String.format(Locale.US, "%.1f", frameDeltaMs)}ms dropped=${dropped.coerceAtLeast(0)} state=$scrollPerfLastState first=$scrollPerfVisibleFirst last=$scrollPerfVisibleLast dy=$scrollPerfAccumulatedDy"
                    )
                }
            }

            scrollPerfFrameCount += 1
            scrollPerfLastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val firstScrollFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!firstScrollTrackingActive) return

            if (firstScrollLastFrameNs != 0L) {
                val frameDeltaMs = (frameTimeNanos - firstScrollLastFrameNs) / 1_000_000.0
                if (frameDeltaMs > 24.0) {
                    firstScrollSlowFrames += 1
                }
                val dropped = floor(frameDeltaMs / 16.6667).toInt() - 1
                if (dropped > 0) {
                    firstScrollDroppedFrames += dropped
                }

                // Per-slow-frame diagnostics: log the frame time plus the cumulative
                // inflation/bind work that happened since the last logged frame. A slow frame
                // paired with inflates=+N points at a cold recycled-view pool (mid-fling
                // ViewHolder creation). A slow frame with inflates=0 but binds=+N points at
                // expensive binding or background CPU contention. This is the signal that
                // distinguishes "pool not warm" from "binds too heavy" from "background starve".
                if (BuildConfig.DEBUG && frameDeltaMs >= FIRST_SCROLL_LOG_FRAME_MS) {
                    val current = scrollWorkProvider()
                    val last = firstScrollLastLoggedWork ?: firstScrollWorkBaseline
                    val inflateDelta = (current?.createCount ?: 0) - (last?.createCount ?: 0)
                    val bindDelta = (current?.fullBindCount ?: 0) - (last?.fullBindCount ?: 0)
                    val partialDelta = (current?.partialBindCount ?: 0) - (last?.partialBindCount ?: 0)
                    firstScrollLastLoggedWork = current
                    GlyphPerf.log(
                        "firstScroll slow frame: ${String.format(Locale.US, "%.1f", frameDeltaMs)}ms " +
                            "dropped=${dropped.coerceAtLeast(0)} frame#=$firstScrollFrameCount " +
                            "inflates=+$inflateDelta fullBinds=+$bindDelta partialBinds=+$partialDelta " +
                            "window=${windowSizeProvider()}"
                    )
                }
            }

            firstScrollFrameCount += 1
            firstScrollLastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun onScrollStateChanged(newState: Int) {
        scrollPerfLastState = newState
    }

    fun recordScrolledDelta(dy: Int) {
        if (scrollPerfTrackingActive && dy != 0) {
            scrollPerfAccumulatedDy += dy
        }
    }

    fun startFirstScrollPerfTrackingIfNeeded(reason: String) {
        if (firstScrollMetricsLogged || firstScrollTrackingActive) return

        // Don't track first-scroll metrics until the first content frame is drawn + pool seeded.
        // This is about METRICS accuracy, not about blocking the scroll itself — the scroll always
        // runs; we only skip recording it as the "first scroll" if the system hasn't painted yet.
        // NOTE: we deliberately do NOT time-gate scrolling. Blocking/rejecting early scrolls is what
        // makes the open feel heavy/non-responsive (the opposite of WhatsApp). The scroll always works.
        if (!isScrollReady) {
            GlyphPerf.log("first-scroll metrics skipped: first frame not drawn yet (scroll still works)")
            return
        }

        firstScrollTrackingActive = true
        firstScrollFrameCount = 0
        firstScrollDroppedFrames = 0
        firstScrollSlowFrames = 0
        firstScrollLastFrameNs = 0L
        firstScrollStartElapsedMs = SystemClock.elapsedRealtime()
        firstScrollWorkBaseline = scrollWorkProvider()
        firstScrollLastLoggedWork = firstScrollWorkBaseline
        val scrollStartSinceOpenMs = firstScrollStartElapsedMs - openElapsedMsProvider()
        GlyphPerf.log(
            "FIRST SCROLL START reason=$reason ${scrollStartSinceOpenMs}ms after open " +
                "window=${windowSizeProvider()} startupUnlocked=${startupUnlockedProvider()} " +
                "idleQueuePaused=${idleQueuePausedProvider()} pool=[${poolSummaryProvider()}] " +
                "baselineInflates=${firstScrollWorkBaseline?.createCount ?: 0} " +
                "baselineBinds=${firstScrollWorkBaseline?.fullBindCount ?: 0}"
        )
        StartupTrace.logStage(
            "chat_first_scroll_start",
            "chatId=${chatId()} reason=$reason"
        )
        Choreographer.getInstance().postFrameCallback(firstScrollFrameCallback)
    }

    fun startScrollPerfTrackingIfNeeded(
        reason: String,
        layoutManager: LinearLayoutManager?,
        recyclerScrollState: Int
    ) {
        if (!BuildConfig.DEBUG || scrollPerfTrackingActive) return
        scrollPerfTrackingActive = true
        scrollPerfCurrentSessionId = ++scrollPerfSessionCounter
        scrollPerfFrameCount = 0
        scrollPerfDroppedFrames = 0
        scrollPerfSlowFrames = 0
        scrollPerfWorstFrameMs = 0.0
        scrollPerfLastFrameNs = 0L
        scrollPerfStartElapsedMs = SystemClock.elapsedRealtime()
        scrollPerfAccumulatedDy = 0
        scrollPerfStartReason = reason
        scrollPerfLastState = if (layoutManager != null) recyclerScrollState else RecyclerView.SCROLL_STATE_IDLE
        scrollPerfWorkAtStart = scrollWorkProvider()
        updateVisibleRange(layoutManager)
        StartupTrace.logStage(
            "chat_scroll_perf_start",
            "chatId=${chatId()} session=$scrollPerfCurrentSessionId reason=$reason first=$scrollPerfVisibleFirst last=$scrollPerfVisibleLast"
        )
        Choreographer.getInstance().postFrameCallback(scrollPerfFrameCallback)
    }

    fun finishScrollPerfTrackingIfNeeded(reason: String, layoutManager: LinearLayoutManager?) {
        if (!scrollPerfTrackingActive) return
        scrollPerfTrackingActive = false
        updateVisibleRange(layoutManager)
        val elapsedMs = SystemClock.elapsedRealtime() - scrollPerfStartElapsedMs
        val workDelta = buildWorkDeltaSuffix()
        StartupTrace.logStage(
            "chat_scroll_perf_end",
            "chatId=${chatId()} session=$scrollPerfCurrentSessionId start=$scrollPerfStartReason end=$reason elapsed=${elapsedMs}ms frames=$scrollPerfFrameCount slow=$scrollPerfSlowFrames dropped=$scrollPerfDroppedFrames worst=${String.format(Locale.US, "%.1f", scrollPerfWorstFrameMs)}ms dy=$scrollPerfAccumulatedDy first=$scrollPerfVisibleFirst last=$scrollPerfVisibleLast$workDelta"
        )
        scrollPerfWorkAtStart = null
    }

    /**
     * Build a " inflates=.. fullBinds=.. (avgBindMs) partialBinds=.." suffix describing the
     * expensive RecyclerView work performed during this scroll session. inflates>0 means the
     * pool was exhausted and ViewHolders were created mid-fling (the worst kind of jank);
     * high fullBinds / avgBindMs means binds themselves are the bottleneck. Empty when the
     * work provider is unavailable (release builds).
     */
    private fun buildWorkDeltaSuffix(): String {
        val start = scrollPerfWorkAtStart ?: return ""
        val end = scrollWorkProvider() ?: return ""
        val inflates = end.createCount - start.createCount
        val fullBinds = end.fullBindCount - start.fullBindCount
        val partialBinds = end.partialBindCount - start.partialBindCount
        val createMs = (end.createTimeNs - start.createTimeNs) / 1_000_000.0
        val bindMs = (end.fullBindTimeNs - start.fullBindTimeNs) / 1_000_000.0
        val avgBindMs = if (fullBinds > 0) bindMs / fullBinds else 0.0
        val avgCreateMs = if (inflates > 0) createMs / inflates else 0.0
        return " inflates=$inflates(${String.format(Locale.US, "%.1f", createMs)}ms avg=${String.format(Locale.US, "%.2f", avgCreateMs)}ms)" +
            " fullBinds=$fullBinds(${String.format(Locale.US, "%.1f", bindMs)}ms avg=${String.format(Locale.US, "%.2f", avgBindMs)}ms)" +
            " partialBinds=$partialBinds"
    }

    fun updateVisibleRange(layoutManager: LinearLayoutManager?) {
        scrollPerfVisibleFirst = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        scrollPerfVisibleLast = layoutManager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
    }

    fun finishFirstScrollPerfTrackingIfNeeded(reason: String) {
        if (!firstScrollTrackingActive) return
        firstScrollTrackingActive = false
        firstScrollMetricsLogged = true
        val elapsedMs = SystemClock.elapsedRealtime() - firstScrollStartElapsedMs
        val baseline = firstScrollWorkBaseline
        val end = scrollWorkProvider()
        val inflateDelta = (end?.createCount ?: 0) - (baseline?.createCount ?: 0)
        val bindDelta = (end?.fullBindCount ?: 0) - (baseline?.fullBindCount ?: 0)
        val poolHits = (bindDelta - inflateDelta).coerceAtLeast(0)
        val inflateTimeMs = ((end?.createTimeNs ?: 0L) - (baseline?.createTimeNs ?: 0L)) / 1_000_000.0
        val hitRate = if (bindDelta > 0) (poolHits * 100) / bindDelta else 0

        // Structured telemetry for before/after comparison
        if (BuildConfig.DEBUG) {
            val perType = buildPoolMissByTypeSuffix(baseline, end)
            GlyphPerf.log(
                "FIRST_FLING_METRICS: " +
                    "inflates=$inflateDelta " +
                    "poolHits=$poolHits " +
                    "poolMisses=$inflateDelta " +
                    "hitRate=${hitRate}% " +
                    "inflateTime=${String.format(Locale.US, "%.1f", inflateTimeMs)}ms " +
                    "warmProgress=[${poolSummaryProvider()}] " +
                    "droppedFrames=$firstScrollDroppedFrames " +
                    "slowFrames=$firstScrollSlowFrames " +
                    "elapsed=${elapsedMs}ms " +
                    "window=${windowSizeProvider()}" +
                    "$perType"
            )
        }

        GlyphPerf.log(
            "FIRST SCROLL END reason=$reason elapsed=${elapsedMs}ms frames=$firstScrollFrameCount " +
                "slow=$firstScrollSlowFrames dropped=$firstScrollDroppedFrames " +
                "totalInflates=$inflateDelta totalFullBinds=$bindDelta"
        )
        StartupTrace.logStage(
            "chat_first_scroll_end",
            "chatId=${chatId()} reason=$reason elapsed=${elapsedMs}ms frames=$firstScrollFrameCount slow=$firstScrollSlowFrames dropped=$firstScrollDroppedFrames"
        )
        onFirstScrollFinished("first_scroll_${reason}")
    }

    /**
     * Builds a " perType={1=3,2=5,...}" suffix showing pool misses (inflates) per view type
     * during this first scroll. Empty when work snapshots are unavailable (release builds).
     */
    private fun buildPoolMissByTypeSuffix(
        baseline: ChatAdapter.ScrollWorkSnapshot?,
        end: ChatAdapter.ScrollWorkSnapshot?
    ): String {
        if (baseline == null || end == null) return ""
        val baselineByType = baseline.createCountByType
        val endByType = end.createCountByType
        val changed = endByType.entries.mapNotNull { (type, count) ->
            val delta = count - (baselineByType[type] ?: 0)
            if (delta > 0) "$type=$delta" else null
        }
        if (changed.isEmpty()) return " perType={none}"
        return " perType={${changed.joinToString(",")}}"
    }

    // ── Pagination diagnostics ───────────────────────────────────────────────────────
    // These fire on the main thread when older history is paged in and when the resulting
    // list emission commits. Seeing a pagination fire land inside the first-scroll window
    // (and how long its commit takes) reveals whether mid-fling list commits are the jank
    // source vs. pool/binding.

    fun logPaginationFire(pages: Int, windowAfter: Int) {
        if (!BuildConfig.DEBUG) return
        GlyphPerf.log(
            "pagination fire: pages=$pages windowAfter=$windowAfter " +
                "firstScrollActive=$firstScrollTrackingActive idleQueuePaused=${idleQueuePausedProvider()}"
        )
    }

    fun logEmissionCommit(window: Int, durationMs: Long) {
        if (!BuildConfig.DEBUG) return
        GlyphPerf.log(
            "list emission commit: window=$window duration=${durationMs}ms " +
                "firstScrollActive=$firstScrollTrackingActive"
        )
    }

    /**
     * Marks the scroll system as ready for performance tracking.
     * Should be called when:
     * - Pool is sufficiently warmed
     * - Initial window is loaded
     * - First frame has been drawn
     * This ensures first scroll metrics only measure when the system is actually prepared.
     */
    fun markScrollReady() {
        if (!isScrollReady) {
            isScrollReady = true
            GlyphPerf.log("scroll system ready for performance tracking")
        }
    }

    private fun chatId(): String = chatIdProvider() ?: "unknown"

    private companion object {
        const val SCROLL_PERF_JANK_FRAME_MS = 32.0
        // First-scroll per-frame logging threshold: log every frame slower than this so the
        // open→scroll stutter is fully visible in logcat (not just a summary at the end).
        const val FIRST_SCROLL_LOG_FRAME_MS = 24.0
    }
}

/**
 * Diagnostics logger for the chat open sequence and scroll performance. All output goes to
 * logcat under the "GlyphPerf" tag so it can be isolated with:
 *
 *   adb logcat -s GlyphPerf StartupTrace
 *
 * Gated to DEBUG builds so release ships none of it.
 */
internal object GlyphPerf {
    private const val TAG = "GlyphPerf"

    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}
