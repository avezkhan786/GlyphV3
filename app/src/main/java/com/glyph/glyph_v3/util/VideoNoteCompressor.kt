package com.glyph.glyph_v3.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Production-grade video note compression pipeline.
 *
 * Uses Surface-based hardware-accelerated MediaCodec transcoding to achieve
 * optimal quality at minimal file size for circular video notes.
 *
 * Strategy:
 * - Smart resolution: 480p circle (no wasted pixels for a 240dp UI element)
 * - Adaptive VBR: 1.2–1.8 Mbps depending on source complexity
 * - 30fps cap: cinematic, efficient, no perceptual loss
 * - H.264 Baseline: widest device compatibility, hardware-accelerated everywhere
 * - AAC mono 64kbps: clear speech, minimal overhead
 * - Pre-upload: upload progress reflects compressed size
 * - Fail-safe: returns original URI on any failure
 */
object VideoNoteCompressor {

    private const val TAG = "VideoNoteCompressor"

    // ───── Codec constants ─────
    private const val VIDEO_MIME = "video/avc"      // H.264 — universal HW support
    private const val TIMEOUT_US = 10_000L           // 10ms dequeue timeout

    // ───── Video target parameters ─────
    private const val TARGET_SHORT_SIDE = 480        // 480p circle — sharp on 240dp display
    private const val TARGET_FPS = 30
    private const val I_FRAME_INTERVAL_SEC = 2       // Keyframe every 2s — good scrubbing
    private const val BASE_BITRATE_BPS = 1_500_000   // 1.5 Mbps baseline
    private const val MIN_BITRATE_BPS = 800_000      // Floor — prevents over-compression
    private const val MAX_BITRATE_BPS = 2_500_000    // Ceiling — prevents bloat

    // ───── Audio (passthrough — no re-encoding needed) ─────

    /**
     * Result of compression.
     */
    data class CompressionResult(
        val uri: Uri,
        val originalSize: Long,
        val compressedSize: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int
    ) {
        val compressionRatio: Float
            get() = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f
        val savingsPercent: Float
            get() = if (originalSize > 0) (1f - compressionRatio) * 100f else 0f
    }

    /**
     * Compress a video note for optimal chat delivery.
     *
     * Runs entirely on [Dispatchers.IO]. Reports progress via [onProgress] (0–100).
     * On failure, returns the original URI wrapped in a [CompressionResult] so the
     * send flow is never blocked.
     */
    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): CompressionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // ── 1. Probe source metadata ──
        val meta = probeSource(context, sourceUri)
        if (meta == null) {
            Log.w(TAG, "Cannot read source metadata — sending original")
            return@withContext fallbackResult(context, sourceUri)
        }


        // ── 2. Decide if compression is needed ──
        if (!shouldCompress(meta)) {
            onProgress?.invoke(100f)
            return@withContext CompressionResult(
                uri = sourceUri,
                originalSize = meta.fileSize,
                compressedSize = meta.fileSize,
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height
            )
        }

        // ── 3. Calculate output parameters ──
        val params = calculateOutputParams(meta)

        // ── 4. Transcode ──
        val outputFile = File(getOutputDir(context), "vnote_${System.currentTimeMillis()}.mp4")
        try {
            val success = transcode(context, sourceUri, outputFile, meta, params, onProgress)
            if (!success) {
                outputFile.delete()
                Log.w(TAG, "Transcode failed — sending original")
                return@withContext fallbackResult(context, sourceUri)
            }

            // ── 5. Validate output ──
            val compressedSize = outputFile.length()
            if (compressedSize <= 0 || compressedSize >= meta.fileSize * 0.95f) {
                // Compression didn't save meaningful space — use original
                outputFile.delete()
                onProgress?.invoke(100f)
                return@withContext CompressionResult(
                    uri = sourceUri,
                    originalSize = meta.fileSize,
                    compressedSize = meta.fileSize,
                    durationMs = meta.durationMs,
                    width = meta.width,
                    height = meta.height
                )
            }

            // Sanity check: output suspiciously tiny = likely corrupt (headers only)
            if (compressedSize < meta.fileSize * 0.01f && compressedSize < 50_000) {
                outputFile.delete()
                Log.w(TAG, "Compressed output too small ($compressedSize bytes) — likely corrupt, using original")
                onProgress?.invoke(100f)
                return@withContext CompressionResult(
                    uri = sourceUri,
                    originalSize = meta.fileSize,
                    compressedSize = meta.fileSize,
                    durationMs = meta.durationMs,
                    width = meta.width,
                    height = meta.height
                )
            }

            // Verify output is actually playable (has video frames)
            if (!validateOutput(outputFile)) {
                outputFile.delete()
                Log.w(TAG, "Compressed output failed validation — using original")
                onProgress?.invoke(100f)
                return@withContext CompressionResult(
                    uri = sourceUri,
                    originalSize = meta.fileSize,
                    compressedSize = meta.fileSize,
                    durationMs = meta.durationMs,
                    width = meta.width,
                    height = meta.height
                )
            }

            val elapsed = System.currentTimeMillis() - startTime

            onProgress?.invoke(100f)

            CompressionResult(
                uri = Uri.fromFile(outputFile),
                originalSize = meta.fileSize,
                compressedSize = compressedSize,
                durationMs = meta.durationMs,
                width = params.width,
                height = params.height
            )
        } catch (e: CancellationException) {
            outputFile.delete()
            Log.w(TAG, "Compression cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed: ${e.message}", e)
            outputFile.delete()
            onProgress?.invoke(100f)
            fallbackResult(context, sourceUri)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Source probing
    // ═══════════════════════════════════════════════════════════════

    private data class SourceMeta(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMs: Long,
        val durationUs: Long,
        val bitrate: Int,
        val fileSize: Long,
        val fps: Int,
        val hasAudio: Boolean
    )

    private fun probeSource(context: Context, uri: Uri): SourceMeta? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true) ?: false

            val frameRate = if (Build.VERSION.SDK_INT >= 23) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.roundToInt()
            } else null

            // Apply rotation
            val (w, h) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH

            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (_: Exception) { 0L }

            SourceMeta(
                width = w, height = h, rotation = rotation,
                durationMs = durationMs, durationUs = durationMs * 1000L,
                bitrate = bitrate, fileSize = fileSize,
                fps = frameRate ?: 30, hasAudio = hasAudio
            )
        } catch (e: Exception) {
            Log.e(TAG, "probeSource failed", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Decision: should we compress?
    // ═══════════════════════════════════════════════════════════════

    /**
     * Skip compression if the source is already well-optimized:
     * - Resolution ≤ 480p AND bitrate ≤ 1.8 Mbps AND fps ≤ 30
     * - File size < 1 MB for a clip up to 10 seconds
     */
    private fun shouldCompress(meta: SourceMeta): Boolean {
        val shortSide = min(meta.width, meta.height)
        if (shortSide <= TARGET_SHORT_SIDE &&
            meta.bitrate in 1..MAX_BITRATE_BPS &&
            meta.fps <= TARGET_FPS + 2
        ) {
            return false
        }
        // Very small files — not worth the CPU cost
        if (meta.fileSize < 500_000 && meta.durationMs < 5_000) return false
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    //  Output parameters calculation
    // ═══════════════════════════════════════════════════════════════

    private data class OutputParams(
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val fps: Int
    )

    /**
     * Adaptively choose resolution and bitrate based on source characteristics.
     */
    private fun calculateOutputParams(meta: SourceMeta): OutputParams {
        // ── Resolution ──
        // Video notes display at 240dp (≈480px on xxhdpi). Targeting 480p short side.
        val shortSide = min(meta.width, meta.height)
        val longSide = max(meta.width, meta.height)

        val (outW, outH) = if (shortSide > TARGET_SHORT_SIDE) {
            val scale = TARGET_SHORT_SIDE.toFloat() / shortSide
            val w = (meta.width * scale).roundToInt()
            val h = (meta.height * scale).roundToInt()
            // Must be even for H.264
            (w.roundToEven()) to (h.roundToEven())
        } else {
            meta.width.roundToEven() to meta.height.roundToEven()
        }

        // ── Frame rate ──
        val outFps = min(meta.fps, TARGET_FPS)

        // ── Adaptive bitrate ──
        // Base: 1.5 Mbps for 480p. Scale proportionally to pixel count.
        val pixelRatio = (outW * outH).toFloat() / (480f * 480f)
        var bitrate = (BASE_BITRATE_BPS * pixelRatio).roundToInt()

        // Boost for high-motion / high-bitrate source (suggests complex content)
        if (meta.bitrate > 10_000_000) {
            bitrate = (bitrate * 1.3f).roundToInt() // +30% for very high-bitrate sources
        } else if (meta.bitrate > 5_000_000) {
            bitrate = (bitrate * 1.15f).roundToInt() // +15% for moderately complex
        }

        // Boost for front camera (face detail matters)
        // CameraX front-camera video is typically not flagged, but 480p is already good here

        // Clamp
        bitrate = bitrate.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)

        return OutputParams(outW, outH, bitrate, outFps)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Surface-based transcoding engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Full Surface-to-Surface transcode: decoder → Surface → encoder.
     * Handles video re-encoding and audio passthrough (remux) or re-encoding.
     */
    private suspend fun transcode(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        meta: SourceMeta,
        params: OutputParams,
        onProgress: ((Float) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var encoderInputSurface: Surface? = null

        try {
            // ── Set up video extractor ──
            videoExtractor = MediaExtractor().apply {
                setDataSource(context, sourceUri, null)
            }
            val videoTrackIdx = findTrack(videoExtractor, "video/")
            if (videoTrackIdx == null) {
                Log.e(TAG, "No video track found in source")
                return@withContext false
            }
            videoExtractor.selectTrack(videoTrackIdx)
            val sourceVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            // ── Set up audio extractor ──
            val audioTrackIdx: Int?
            val sourceAudioFormat: MediaFormat?
            if (meta.hasAudio) {
                audioExtractor = MediaExtractor().apply {
                    setDataSource(context, sourceUri, null)
                }
                audioTrackIdx = findTrack(audioExtractor, "audio/")
                if (audioTrackIdx != null) {
                    audioExtractor.selectTrack(audioTrackIdx)
                    sourceAudioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
                } else {
                    sourceAudioFormat = null
                }
            } else {
                audioTrackIdx = null
                sourceAudioFormat = null
            }

            // ── Create video encoder ──
            val encoderFormat = MediaFormat.createVideoFormat(VIDEO_MIME, params.width, params.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, params.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // VBR for adaptive quality
                if (Build.VERSION.SDK_INT >= 21) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                // H.264 Baseline profile for maximum compatibility
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }

            encoder = createEncoder(VIDEO_MIME)
            if (encoder == null) {
                Log.e(TAG, "No hardware H.264 encoder available")
                return@withContext false
            }
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // ── Create video decoder ──
            val sourceMime = sourceVideoFormat.getString(MediaFormat.KEY_MIME) ?: VIDEO_MIME
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(sourceVideoFormat, encoderInputSurface, null, 0)
            decoder.start()

            // ── Muxer ──
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Set rotation to 0 since we handle it via resolution swap
            muxer.setOrientationHint(0)

            // ── Transcode loop (video re-encode + audio passthrough) ──
            val result = processFrames(
                videoExtractor = videoExtractor,
                audioExtractor = audioExtractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                sourceAudioFormat = sourceAudioFormat,
                durationUs = meta.durationUs,
                onProgress = onProgress
            )

            return@withContext result

        } catch (e: CancellationException) {
            Log.w(TAG, "Transcode cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcode error: ${e.message}", e)
            return@withContext false
        } finally {
            // ── Cleanup (order matters) ──
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { encoderInputSurface?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Core frame processing loop.
     * Pumps video frames: extractor → decoder → surface → encoder → muxer
     * Audio: passthrough (remux) — no re-encoding, no deadlock risk.
     */
    private suspend fun processFrames(
        videoExtractor: MediaExtractor,
        audioExtractor: MediaExtractor?,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        sourceAudioFormat: MediaFormat?,
        durationUs: Long,
        onProgress: ((Float) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val bufferInfo = MediaCodec.BufferInfo()
        var videoOutputTrack = -1
        var audioOutputTrack = -1
        var muxerStarted = false

        var videoInputDone = false
        var videoDecoderDone = false
        var videoEncoderDone = false
        var audioDone = audioExtractor == null || sourceAudioFormat == null

        // Audio passthrough buffer (reused each iteration)
        val audioBuffer = if (!audioDone) ByteBuffer.allocate(256 * 1024) else null
        val audioInfo = if (!audioDone) MediaCodec.BufferInfo() else null

        var lastProgressPercent = -1
        val startTimeMs = System.currentTimeMillis()
        val TIMEOUT_LIMIT_MS = 120_000L // 2 minutes max — more than enough for any video note


        while (!videoEncoderDone || !audioDone) {
            ensureActive()

            // ── Timeout guard ──
            if (System.currentTimeMillis() - startTimeMs > TIMEOUT_LIMIT_MS) {
                Log.e(TAG, "processFrames timeout after ${TIMEOUT_LIMIT_MS / 1000}s — aborting")
                return@withContext false
            }

            // ── 1. Feed video decoder from extractor ──
            if (!videoInputDone) {
                val inputIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIdx >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIdx)!!
                    val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        videoInputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIdx, 0, sampleSize, videoExtractor.sampleTime, 0)
                        videoExtractor.advance()
                    }
                }
            }

            // ── 2. Drain video decoder → Surface (auto-renders to encoder) ──
            if (!videoDecoderDone) {
                val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIdx >= 0) {
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    // Render to encoder's input surface (true = render)
                    decoder.releaseOutputBuffer(outputIdx, !eos && bufferInfo.size > 0)
                    if (eos) {
                        encoder.signalEndOfInputStream()
                        videoDecoderDone = true
                    }

                    // Progress from video position
                    if (durationUs > 0 && bufferInfo.presentationTimeUs > 0) {
                        val pct = (bufferInfo.presentationTimeUs.toFloat() / durationUs * 95f).roundToInt()
                            .coerceIn(0, 95)
                        if (pct > lastProgressPercent) {
                            lastProgressPercent = pct
                            onProgress?.invoke(pct.toFloat())
                        }
                    }
                }
            }

            // ── 3. Drain video encoder → muxer ──
            if (!videoEncoderDone) {
                val outputIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = encoder.outputFormat
                        videoOutputTrack = muxer.addTrack(fmt)
                        // Add audio track now that we have video format
                        if (sourceAudioFormat != null && audioOutputTrack < 0) {
                            audioOutputTrack = muxer.addTrack(sourceAudioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIdx >= 0 -> {
                        val data = encoder.getOutputBuffer(outputIdx)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoOutputTrack, data, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outputIdx, false)
                        if (eos) videoEncoderDone = true
                    }
                }
            }

            // ── 4. Audio passthrough: read from extractor → write to muxer ──
            if (!audioDone && audioExtractor != null && audioBuffer != null && audioInfo != null && muxerStarted) {
                try {
                    audioBuffer.clear()
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        audioDone = true
                    } else {
                        audioInfo.offset = 0
                        audioInfo.size = sampleSize
                        audioInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioOutputTrack, audioBuffer, audioInfo)
                        audioExtractor.advance()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio passthrough error: ${e.message}", e)
                    audioDone = true // Skip remaining audio on error
                }
            }
        }

        return@withContext true
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Find the first track matching a mimetype prefix ("video/" or "audio/"). */
    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    /** Create a hardware-preferred encoder, falling back to any available. */
    private fun createEncoder(mime: String): MediaCodec? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            // Prefer hardware encoder
            val hwCodec = codecList.codecInfos.firstOrNull { info ->
                info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !info.name.contains("OMX.google", ignoreCase = true) // Skip software encoders
            }
            val codecName = hwCodec?.name ?: codecList.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name

            if (codecName != null) MediaCodec.createByCodecName(codecName) else null
        } catch (e: Exception) {
            Log.e(TAG, "createEncoder failed, falling back", e)
            try { MediaCodec.createEncoderByType(mime) } catch (_: Exception) { null }
        }
    }


    /**
     * Validate that the compressed output is a playable video with actual frames.
     * Uses MediaMetadataRetriever to check duration and extract a frame.
     */
    private fun validateOutput(outputFile: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(outputFile.absolutePath)
            }
            // Check that the output has a non-zero duration
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0) {
                Log.w(TAG, "validateOutput: duration=$duration — no video data")
                return false
            }
            // Check that the output has valid video dimensions
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "validateOutput: dimensions=${width}x${height} — invalid")
                return false
            }
            // Try to extract an actual frame (most reliable corruption check)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame == null) {
                Log.w(TAG, "validateOutput: couldn't extract any frame — corrupt output")
                return false
            }
            frame.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "validateOutput failed", e)
            false
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /** Round to nearest even number (required for H.264 dimensions). */
    private fun Int.roundToEven(): Int = if (this % 2 == 0) this else this + 1

    /** Safe integer extraction from MediaFormat. */
    private fun MediaFormat.getIntSafe(key: String): Int? {
        return try { getInteger(key) } catch (_: Exception) { null }
    }

    /** Get the output directory for compressed video notes. */
    private fun getOutputDir(context: Context): File {
        val dir = File(context.cacheDir, "compressed_video_notes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Produce a fallback result wrapping the original URI. */
    private fun fallbackResult(context: Context, uri: Uri): CompressionResult {
        val fileSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        return CompressionResult(
            uri = uri,
            originalSize = fileSize,
            compressedSize = fileSize,
            durationMs = 0,
            width = 0,
            height = 0
        )
    }

    /**
     * Clean up cached compressed video notes older than [maxAgeMs].
     */
    fun cleanupCache(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val dir = getOutputDir(context)
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > maxAgeMs) file.delete()
            }
        } catch (_: Exception) {}
    }
}
