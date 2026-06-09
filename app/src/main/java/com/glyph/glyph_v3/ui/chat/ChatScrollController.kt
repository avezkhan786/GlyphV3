package com.glyph.glyph_v3.ui.chat

import android.os.SystemClock
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
    private val scrollWorkProvider: () -> ChatAdapter.ScrollWorkSnapshot? = { null }
) {
    var firstScrollMetricsLogged: Boolean = false
        private set
    var firstScrollTrackingActive: Boolean = false
        private set
    var scrollPerfTrackingActive: Boolean = false
        private set

    private var firstScrollFrameCount = 0
    private var firstScrollDroppedFrames = 0
    private var firstScrollSlowFrames = 0
    private var firstScrollLastFrameNs = 0L
    private var firstScrollStartElapsedMs = 0L

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
        firstScrollTrackingActive = true
        firstScrollFrameCount = 0
        firstScrollDroppedFrames = 0
        firstScrollSlowFrames = 0
        firstScrollLastFrameNs = 0L
        firstScrollStartElapsedMs = SystemClock.elapsedRealtime()
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
        StartupTrace.logStage(
            "chat_first_scroll_end",
            "chatId=${chatId()} reason=$reason elapsed=${elapsedMs}ms frames=$firstScrollFrameCount slow=$firstScrollSlowFrames dropped=$firstScrollDroppedFrames"
        )
        onFirstScrollFinished("first_scroll_${reason}")
    }

    private fun chatId(): String = chatIdProvider() ?: "unknown"

    private companion object {
        const val SCROLL_PERF_JANK_FRAME_MS = 32.0
    }
}
