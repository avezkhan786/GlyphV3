package com.glyph.glyph_v3.ui.chatlist

import android.util.Log

/**
 * Debug-only instrumentation for chat-list scroll performance.
 *
 * Enable from a debug device with:
 *   adb shell setprop log.tag.ChatListPerf VERBOSE
 * Then tail the tag:
 *   adb logcat -s ChatListPerf
 * And scroll the chat list. After each scroll comes to rest a multi-line summary is logged.
 *
 * The monitor is fully opt-in: every counter is gated by [enabled] which is read
 * once at process startup. When the tag is not VERBOSE the methods short-circuit,
 * so the per-call cost in production is one volatile load + one branch.
 */
internal object ChatListPerfMonitor {
    private const val TAG = "ChatListPerf"

    @Volatile var enabled: Boolean = false
        private set

    /** Read by [com.glyph.glyph_v3.GlyphApplication.onCreate] once per process. */
    fun checkEnabled() {
        enabled = Log.isLoggable(TAG, Log.VERBOSE)
    }

    // ── Bind cost ──
    var binds: Long = 0; private set
    var partialBinds: Long = 0; private set
    var bindNanos: Long = 0; private set
    var maxBindNanos: Long = 0; private set
    var firstBindNanos: Long = 0

    // ── Image work ──
    var glideCalls: Long = 0; private set
    var avatarLookups: Long = 0; private set
    var avatarCacheMisses: Long = 0; private set

    // ── Text work ──
    var textResolved: Long = 0; private set
    var textCacheHits: Long = 0; private set

    // ── Flow ──
    var presenceEmissions: Long = 0; private set
    var listRebuilds: Long = 0; private set
    var chatsAllocated: Long = 0; private set

    // ── Adapter ──
    var submitListCalls: Long = 0; private set
    var submitListSkipped: Long = 0; private set
    var payloadsFired: Long = 0; private set

    fun bindStartNanos(): Long {
        if (!enabled) return 0L
        val t = System.nanoTime()
        if (firstBindNanos == 0L) firstBindNanos = t
        return t
    }

    fun bindEnd(startNanos: Long) {
        if (!enabled) return
        if (startNanos == 0L) return
        val d = System.nanoTime() - startNanos
        binds++
        bindNanos += d
        if (d > maxBindNanos) maxBindNanos = d
    }

    fun onPartialBind() {
        if (!enabled) return
        partialBinds++
    }

    fun onPayloadFired() {
        if (!enabled) return
        payloadsFired++
    }

    fun onGlideCall() {
        if (!enabled) return
        glideCalls++
    }

    fun onAvatarLookup(cacheHit: Boolean) {
        if (!enabled) return
        avatarLookups++
        if (!cacheHit) avatarCacheMisses++
    }

    fun onTextResolved(cacheHit: Boolean) {
        if (!enabled) return
        textResolved++
        if (cacheHit) textCacheHits++
    }

    fun onPresenceEmission() {
        if (!enabled) return
        presenceEmissions++
    }

    fun onChatListRebuild(count: Int) {
        if (!enabled) return
        listRebuilds++
        chatsAllocated += count
    }

    fun onSubmitList(submitted: Boolean) {
        if (!enabled) return
        submitListCalls++
        if (!submitted) submitListSkipped++
    }

    fun reset() {
        if (!enabled) return
        binds = 0; partialBinds = 0; bindNanos = 0; maxBindNanos = 0
        glideCalls = 0; avatarLookups = 0; avatarCacheMisses = 0
        textResolved = 0; textCacheHits = 0
        presenceEmissions = 0; listRebuilds = 0; chatsAllocated = 0
        submitListCalls = 0; submitListSkipped = 0; payloadsFired = 0
        firstBindNanos = 0
    }

    fun flush(label: String) {
        if (!enabled) return
        val elapsedMs = if (firstBindNanos > 0L)
            (System.nanoTime() - firstBindNanos) / 1_000_000.0 else 0.0
        val avgUs = if (binds > 0L) (bindNanos.toDouble() / binds) / 1_000.0 else 0.0
        val maxMs = maxBindNanos / 1_000_000.0
        val totalMs = bindNanos / 1_000_000.0
        val cacheHitPct =
            if (textResolved > 0L) (100 * textCacheHits / textResolved).toInt() else 0
        val submitSkipPct =
            if (submitListCalls > 0L) (100 * submitListSkipped / submitListCalls).toInt() else 0

        Log.v(TAG, "[$label] binds=$binds partial=$partialBinds payloads=$payloadsFired elapsedMs=${"%.0f".format(elapsedMs)}")
        Log.v(TAG, "[$label] perBind avg=${"%.1f".format(avgUs)}us p100=${"%.2f".format(maxMs)}ms total=${"%.1f".format(totalMs)}ms")
        Log.v(TAG, "[$label] glide=$glideCalls avatarLookups=$avatarLookups miss=$avatarCacheMisses (${if (avatarLookups > 0L) (100 * avatarCacheMisses / avatarLookups).toInt() else 0}% miss)")
        Log.v(TAG, "[$label] textResolved=$textResolved hits=$textCacheHits ($cacheHitPct% hit)")
        Log.v(TAG, "[$label] presenceEmissions=$presenceEmissions listRebuilds=$listRebuilds chatsAllocated=$chatsAllocated")
        Log.v(TAG, "[$label] submitList=$submitListCalls skipped=$submitListSkipped ($submitSkipPct% skipped)")
        reset()
    }
}
