package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent disk cache for status reply thumbnails shown in chat bubble reply previews.
 *
 * Thumbnails are stored in filesDir/status_thumbs/{statusId}.jpg so they survive
 * after a status expires or is deleted — allowing the reply preview to always display
 * something (blurred in the UI for expired statuses).
 *
 * Type handling:
 *   IMAGE / VIDEO  → downloads from statusThumbnailUrl
 *   TEXT           → Canvas render: solid background colour + truncated text
 *   VOICE          → Canvas render: stylised waveform bars
 *
 * Thread safety: per-statusId Mutex prevents duplicate I/O for concurrent bind calls.
 */
object StatusThumbnailCache {

    private const val TAG = "StatusThumbnailCache"
    private const val DIR = "status_thumbs"
    private const val JPEG_QUALITY = 85

    // A status lives 24 hours; 26 h gives a 2-hour grace window for the blur overlay.
    private const val STATUS_EXPIRY_MS = 26L * 60 * 60 * 1000

    // Fixed pixel dimensions matching 56 dp × 80 dp at 2× density.
    internal const val THUMB_W_PX = 112
    internal const val THUMB_H_PX = 160

    private lateinit var thumbDir: File
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        thumbDir = File(context.applicationContext.filesDir, DIR).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /** Returns true when the status referenced by this message has definitely expired (>26 h). */
    fun isExpired(message: Message): Boolean =
        System.currentTimeMillis() - message.timestamp > STATUS_EXPIRY_MS

    /**
     * Synchronous cache hit — returns the local File only if it already exists on disk.
     * Safe to call on the main thread.
     */
    fun getLocalFileSync(statusId: String): File? {
        if (!::thumbDir.isInitialized) return null
        val f = File(thumbDir, "$statusId.jpg")
        return if (f.exists()) f else null
    }

    /**
     * Ensures the thumbnail file exists on disk, generating or downloading it if needed.
     * Returns the local File on success, null on any failure.
     * Safe to call concurrently for the same statusId — only one operation runs at a time.
     */
    suspend fun ensureThumb(context: Context, message: Message): File? {
        val statusId = message.statusId ?: return null
        if (!::thumbDir.isInitialized) init(context)
        val dest = File(thumbDir, "$statusId.jpg")
        if (dest.exists()) return dest

        val lock = locks.getOrPut(statusId) { Mutex() }
        return lock.withLock {
            // Double-check after lock acquisition in case another coroutine beat us here.
            if (dest.exists()) return@withLock dest

            when (message.statusType) {
                "IMAGE", "VIDEO" -> {
                    val url = message.statusThumbnailUrl
                    if (!url.isNullOrBlank()) downloadToFile(url, dest) else null
                }
                "TEXT" -> {
                    val bmp = generateTextBitmap(
                        text    = message.statusText ?: "",
                        bgColor = message.statusBgColor
                            ?: android.graphics.Color.parseColor("#1B5E20")
                    )
                    if (saveBitmap(bmp, dest)) dest else null
                }
                "VOICE" -> {
                    val bmp = generateVoiceBitmap()
                    if (saveBitmap(bmp, dest)) dest else null
                }
                else -> null
            }
        }
    }

    /**
     * Fire-and-forget batch preload for a list of messages.
     * Skips messages that are already cached.  Safe to call on any thread.
     */
    fun preload(context: Context, messages: List<Message>) {
        val pending = messages.filter {
            it.type == MessageType.STATUS_REPLY &&
                it.statusId != null &&
                getLocalFileSync(it.statusId!!) == null
        }
        for (msg in pending) {
            ioScope.launch {
                try { ensureThumb(context, msg) }
                catch (e: Exception) { Log.w(TAG, "preload failed for ${msg.statusId}", e) }
            }
        }
    }

    // ── Canvas generators ────────────────────────────────────────────────────

    private fun generateTextBitmap(text: String, bgColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(THUMB_W_PX, THUMB_H_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)

        if (text.isNotBlank()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color     = android.graphics.Color.WHITE
                textSize  = 16f
                textAlign = Paint.Align.CENTER
                typeface  = Typeface.DEFAULT_BOLD
            }
            val cx      = THUMB_W_PX / 2f
            val display = text.trim().take(60)

            // Soft word-wrap: max 12 chars per line, max 4 lines
            val words = display.split(" ")
            val lines = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (candidate.length <= 12) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) lines.add(current)
                    current = word.take(12)
                }
            }
            if (current.isNotEmpty()) lines.add(current)

            val visible = lines.take(4)
            val lineH   = 20f
            var y = (THUMB_H_PX - visible.size * lineH) / 2f + lineH - 4f
            for (line in visible) {
                canvas.drawText(line, cx, y, paint)
                y += lineH
            }
        }
        return bmp
    }

    private fun generateVoiceBitmap(): Bitmap {
        val bmp    = Bitmap.createBitmap(THUMB_W_PX, THUMB_H_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#1A1A2E"))

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = android.graphics.Color.argb(210, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
        }
        // Symmetric waveform heights
        val heights = intArrayOf(14, 26, 42, 58, 68, 74, 68, 58, 42, 26, 14)
        val barW    = 6f
        val gap     = (THUMB_W_PX - heights.size * barW) / (heights.size + 1)
        val cy      = THUMB_H_PX / 2f

        for (i in heights.indices) {
            val x     = gap + i * (barW + gap) + barW / 2f
            val halfH = heights[i] / 2f
            barPaint.strokeWidth = barW
            canvas.drawLine(x, cy - halfH, x, cy + halfH, barPaint)
        }

        // Subtle mic-hint circle near the bottom
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(80, 255, 255, 255)
        }
        canvas.drawCircle(THUMB_W_PX / 2f, THUMB_H_PX * 0.82f, 10f, dotPaint)
        return bmp
    }

    // ── I/O helpers ──────────────────────────────────────────────────────────

    private fun saveBitmap(bmp: Bitmap, dest: File): Boolean {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        return try {
            tmp.outputStream().buffered().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    private suspend fun downloadToFile(url: String, dest: File): File? =
        withContext(Dispatchers.IO) {
            val tmp = File(dest.parent, "${dest.name}.tmp")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout    = 15_000
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                if (tmp.renameTo(dest)) dest else null
            } catch (e: Exception) {
                Log.w(TAG, "downloadToFile failed for $url: ${e.message}")
                tmp.delete()
                null
            }
        }
}
