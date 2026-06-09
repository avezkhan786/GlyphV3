package com.glyph.glyph_v3.util

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.glyph.glyph_v3.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ChatOpenTrace {
    private const val TAG = "ChatOpenTrace"

    private val processStartMs = SystemClock.elapsedRealtime()
    private val openStartMsByChatId = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong(0L)

    fun start(chatId: String?, source: String, details: String? = null) {
        if (!BuildConfig.DEBUG) return
        val normalizedChatId = normalizeChatId(chatId)
        val now = SystemClock.elapsedRealtime()
        if (normalizedChatId != null) {
            openStartMsByChatId[normalizedChatId] = now
        }
        event(
            chatId = normalizedChatId,
            stage = "navigation_start",
            details = joinDetails("source=$source", details),
            nowMs = now
        )
    }

    fun event(chatId: String?, stage: String, details: String? = null) {
        if (!BuildConfig.DEBUG) return
        event(normalizeChatId(chatId), stage, details, SystemClock.elapsedRealtime())
    }

    fun <T> measure(chatId: String?, stage: String, details: String? = null, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val startMs = SystemClock.elapsedRealtime()
        event(chatId, "${stage}_start", details)
        return try {
            block().also {
                event(chatId, "${stage}_end", joinDetails(details, "elapsed=${SystemClock.elapsedRealtime() - startMs}ms"))
            }
        } catch (error: Throwable) {
            event(
                chatId,
                "${stage}_fail",
                joinDetails(details, "elapsed=${SystemClock.elapsedRealtime() - startMs}ms error=${error::class.java.simpleName}")
            )
            throw error
        }
    }

    private fun event(chatId: String?, stage: String, details: String?, nowMs: Long) {
        val thread = Thread.currentThread()
        val openDelta = chatId?.let { id ->
            openStartMsByChatId[id]?.let { start -> "+${nowMs - start}ms" }
        } ?: "n/a"
        val mainThread = Looper.myLooper() == Looper.getMainLooper()
        val suffix = details?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()

        Log.d(
            TAG,
            "seq=${sequence.incrementAndGet()} " +
                "wall=${System.currentTimeMillis()} " +
                "process=+${nowMs - processStartMs}ms " +
                "open=$openDelta " +
                "thread=${thread.name}#${thread.id} " +
                "main=$mainThread " +
                "chat=${chatId?.take(12) ?: "unknown"} " +
                "stage=$stage" +
                suffix
        )
    }

    private fun normalizeChatId(chatId: String?): String? = chatId?.trim()?.takeIf { it.isNotEmpty() }

    private fun joinDetails(first: String?, second: String?): String? {
        return listOfNotNull(
            first?.takeIf { it.isNotBlank() },
            second?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() }
    }
}