package com.glyph.glyph_v3.ui.status

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.*
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.util.VideoThumbnailUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * WorkManager CoroutineWorker that handles status media uploads reliably in the background.
 *
 * Pipeline:
 *   1. PREPARING   – validate inputs, file already copied to internal cache before enqueue
 *   2. COMPRESSING – transcode video to H.264 MP4 at ≤720p via Media3 Transformer
 *   3. UPLOADING   – stream file to Firebase Storage with live progress
 *   4. PROCESSING  – generate JPEG thumbnail (video only) and upload it
 *   5. DONE        – write Firestore document; delete temp file
 *
 * Retry: transient network failures trigger [Result.retry] up to 3 attempts with exponential
 * back-off. Permanent failures (auth, missing file) return [Result.failure].
 */
class StatusUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // ──────────────────────────────────────────────────────────────────
    // Foreground info
    // ──────────────────────────────────────────────────────────────────

    override suspend fun getForegroundInfo(): ForegroundInfo =
        makeForegroundInfo("Preparing upload…", 0, indeterminate = true)

    // ──────────────────────────────────────────────────────────────────
    // doWork
    // ──────────────────────────────────────────────────────────────────

    override suspend fun doWork(): Result {
        val filePath  = inputData.getString(KEY_FILE_PATH)  ?: return permanentFailure("Missing file path")
        val typeStr   = inputData.getString(KEY_TYPE)       ?: return permanentFailure("Missing type")
        val caption   = inputData.getString(KEY_CAPTION)    ?: ""
        val statusId  = inputData.getString(KEY_STATUS_ID)  ?: return permanentFailure("Missing statusId")
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val visibleToJson = inputData.getString(KEY_VISIBLE_TO) ?: "[]"

        val type = try { StatusType.valueOf(typeStr) }
                   catch (_: Exception) { return permanentFailure("Unknown type: $typeStr") }

        val srcFile = File(filePath)
        if (!srcFile.exists()) return permanentFailure("Source file not found: $filePath")

        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return permanentFailure("Not authenticated")

        val visibleTo: List<String> = try {
            val arr = JSONArray(visibleToJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }

        setForeground(makeForegroundInfo("Preparing…", 0, indeterminate = true))
        reportProgress(STAGE_PREPARING, 0)

        return try {
            // ── 1. Compress video via Media3 Transformer ───────────────
            val uploadFile: File = if (type == StatusType.VIDEO) {
                setForeground(makeForegroundInfo("Compressing video…", 0, indeterminate = true))
                reportProgress(STAGE_COMPRESSING, 0)
                compressVideoWithTransformer(srcFile) ?: srcFile
            } else {
                srcFile
            }

            // ── 2. Upload media to Firebase Storage ────────────────────
            setForeground(makeForegroundInfo("Uploading… 0%", 0))
            reportProgress(STAGE_UPLOADING, 0)

            val ext = inferStorageExtension(uploadFile, type)
            val storageRef = FirebaseStorage.getInstance().reference
                .child("status_media/$uid/$statusId.$ext")
            val metadata = StorageMetadata.Builder()
                .setContentType(inferMimeType(uploadFile, type))
                .build()

            val uploadUri = uploadFile.toUri()
            val fallbackTotalBytes = uploadFile.length().coerceAtLeast(1L)
            var lastUploadedPercent = -1
            var lastReportedProgressStep = -1
            val uploadTask = storageRef.putFile(uploadUri, metadata)
            uploadTask.addOnProgressListener { snap ->
                val totalBytes = snap.totalByteCount
                    .takeIf { it > 0L }
                    ?: fallbackTotalBytes
                val fraction = (snap.bytesTransferred.toFloat() / totalBytes.toFloat())
                    .coerceIn(0f, 1f)
                val progressStep = (fraction * 1000).roundToInt().coerceIn(0, 1000)
                val pct = (fraction * 100f).roundToInt().coerceIn(0, 100)
                if (progressStep != lastReportedProgressStep) {
                    lastReportedProgressStep = progressStep
                    reportProgressAsync(STAGE_UPLOADING, pct, fraction)
                }
                if (pct != lastUploadedPercent) {
                    lastUploadedPercent = pct
                    setForegroundAsync(makeForegroundInfo("Uploading… $pct%", pct))
                }
            }
            uploadTask.await()
            reportProgress(STAGE_UPLOADING, 100, 1f)
            val mediaUrl = storageRef.downloadUrl.await().toString()

            // ── 3. Thumbnail for video ──────────────────────────────────
            var thumbnailUrl = ""
            var capturedThumbBytes: ByteArray? = null
            if (type == StatusType.VIDEO) {
                setForeground(makeForegroundInfo("Processing…", 100))
                reportProgress(STAGE_PROCESSING, 100, 1f)
                val thumbPair = VideoThumbnailUtil.generateThumbnailBytes(context, uploadFile.toUri())
                if (thumbPair != null) {
                    val (thumbBytes, _) = thumbPair
                    capturedThumbBytes = thumbBytes
                    val thumbRef = FirebaseStorage.getInstance().reference
                        .child("status_media/$uid/${statusId}_thumb.jpg")
                    val thumbMeta = StorageMetadata.Builder()
                        .setContentType("image/jpeg").build()
                    thumbRef.putBytes(thumbBytes, thumbMeta).await()
                    thumbnailUrl = thumbRef.downloadUrl.await().toString()
                } else {
                    Log.w(TAG, "Thumbnail generation failed for $statusId")
                }
            }

            // ── 4. Write Firestore document ─────────────────────────────
            setForeground(makeForegroundInfo("Finishing…", 100))
            reportProgress(STAGE_PROCESSING, 100, 1f)

            val now = System.currentTimeMillis()
            val doc = hashMapOf(
                "id"              to statusId,
                "userId"          to uid,
                "type"            to type.name,
                "text"            to "",
                "backgroundColor" to 0,
                "fontStyle"       to "default",
                "mediaUrl"        to mediaUrl,
                "thumbnailUrl"    to thumbnailUrl,
                "caption"         to caption,
                "timestamp"       to now,
                "expiresAt"       to (now + 24L * 3600_000L),
                "viewerIds"       to emptyList<String>(),
                "visibleTo"       to visibleTo,
                "durationMs"      to durationMs
            )
            FirebaseFirestore.getInstance()
                .collection("statuses")
                .document(statusId)
                .set(doc)
                .await()

            // ── 5. Seed local cache so uploader plays instantly ──────────
            com.glyph.glyph_v3.data.cache.StatusCacheManager.seedFromUpload(
                statusId = statusId,
                userId = uid,
                type = type,
                mediaUrl = mediaUrl,
                localFile = uploadFile,
                thumbBytes = capturedThumbBytes,
                expiresAt = now + 24L * 3600_000L
            )

            // ── 6. Cleanup temp files ───────────────────────────────────
            if (uploadFile != srcFile) uploadFile.delete()
            srcFile.delete()

            reportProgress(STAGE_DONE, 100, 1f)
            Result.success(workDataOf(KEY_MEDIA_URL to mediaUrl, KEY_THUMBNAIL_URL to thumbnailUrl))

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed (attempt ${runAttemptCount + 1})", e)
            val isTransient = e is java.net.UnknownHostException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException
            if (isTransient && runAttemptCount < 3) {
                val retryText = "Connection issue. Retrying…"
                setForeground(makeForegroundInfo(retryText, 0, indeterminate = true))
                reportProgress(STAGE_RETRYING, 0)
                Result.retry()
            } else {
                showTerminalNotification(
                    title = "Status upload failed",
                    text = e.message ?: "Unable to upload status"
                )
                Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Video remuxing (container-copy, ensures MP4/AAC compatibility)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Transcode [input] to a compressed H.264 MP4 using Media3 Transformer.
     * - Re-encodes video to H.264 at up to 720p and a target bitrate of ~2 Mbps.
     * - Audio is passed through unchanged.
     * Returns null on failure (caller falls back to original file).
     */
    private suspend fun compressVideoWithTransformer(input: File): File? {
        val output = File(context.cacheDir, "status_compressed_${System.currentTimeMillis()}.mp4")
        output.delete()

        return suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    val editedMediaItem = EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.fromFile(input))
                    )
                        .setEffects(
                            Effects(
                                emptyList(),
                                listOf(Presentation.createForHeight(720))
                            )
                        )
                        .build()

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult
                            ) {
                                val sizeBefore = input.length()
                                val sizeAfter = output.length()
                                if (cont.isActive) {
                                    cont.resume(
                                        if (output.exists() && sizeAfter > 0) output else null
                                    ) {}
                                }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                Log.e(TAG, "Video compression failed", exportException)
                                output.delete()
                                if (cont.isActive) cont.resume(null) {}
                            }
                        })
                        .build()

                    transformer.start(editedMediaItem, output.absolutePath)

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            try { transformer.cancel() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transformer setup failed", e)
                    output.delete()
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private suspend fun reportProgress(stage: String, percent: Int, fraction: Float = percent.coerceIn(0, 100) / 100f) {
        setProgress(
            workDataOf(
                KEY_STAGE to stage,
                KEY_PROGRESS to percent.coerceIn(0, 100),
                KEY_PROGRESS_FRACTION to fraction.coerceIn(0f, 1f)
            )
        )
    }

    private fun reportProgressAsync(stage: String, percent: Int, fraction: Float = percent.coerceIn(0, 100) / 100f) {
        setProgressAsync(
            workDataOf(
                KEY_STAGE to stage,
                KEY_PROGRESS to percent.coerceIn(0, 100),
                KEY_PROGRESS_FRACTION to fraction.coerceIn(0f, 1f)
            )
        )
    }

    private fun permanentFailure(msg: String): Result {
        Log.e(TAG, msg)
        return Result.failure(workDataOf("error" to msg))
    }

    private fun inferStorageExtension(file: File, type: StatusType): String {
        val fileExtension = file.extension.lowercase().takeIf { it.isNotBlank() }
        return when (type) {
            StatusType.IMAGE -> fileExtension ?: "jpg"
            StatusType.VOICE -> fileExtension ?: "m4a"
            StatusType.VIDEO -> fileExtension ?: "mp4"
            else -> fileExtension ?: "mp4"
        }
    }

    private fun inferMimeType(file: File, type: StatusType): String {
        return when (type) {
            StatusType.IMAGE -> when (file.extension.lowercase()) {
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            StatusType.VOICE -> when (file.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                else -> "audio/mp4"
            }
            StatusType.VIDEO -> when (file.extension.lowercase()) {
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                else -> "video/mp4"
            }
            else -> "application/octet-stream"
        }
    }

    private fun makeForegroundInfo(
        text: String,
        progress: Int,
        indeterminate: Boolean = false
    ): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading status")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        // On API 29+ the 3-arg constructor is available and a type MUST be supplied
        // for targetSdk 34+ (Android 14). The manifest also declares foregroundServiceType.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showTerminalNotification(title: String, text: String) {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "Status Uploads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows upload progress for status updates"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }.also { nm.createNotificationChannel(it) }
    }

    // ──────────────────────────────────────────────────────────────────
    // Companion — constants + enqueue helper
    // ──────────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "StatusUploadWorker"
        const val CHANNEL_ID = "glyph_status_upload"
        const val NOTIFICATION_ID = 4242

        // Input keys
        const val KEY_FILE_PATH   = "file_path"
        const val KEY_TYPE        = "type"
        const val KEY_CAPTION     = "caption"
        const val KEY_STATUS_ID   = "status_id"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_VISIBLE_TO  = "visible_to"

        // Progress keys (set via setProgress / observed via WorkInfo)
        const val KEY_STAGE    = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"

        // Output keys
        const val KEY_MEDIA_URL     = "media_url"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"

        // Stage labels
        const val STAGE_PREPARING   = "PREPARING"
        const val STAGE_COMPRESSING = "COMPRESSING"
        const val STAGE_UPLOADING   = "UPLOADING"
        const val STAGE_PROCESSING  = "PROCESSING"
        const val STAGE_RETRYING    = "RETRYING"
        const val STAGE_DONE        = "DONE"

        /** Work tag used to query all pending/running status uploads. */
        const val WORK_TAG = "status_upload"

        /**
         * Build and enqueue a one-time upload job.
         *
         * @param context        Application context
         * @param localFile      File already copied to internal cache
         * @param type           [StatusType] of the media
         * @param caption        User caption
         * @param statusId       Pre-generated Firestore document ID
         * @param durationMs     Audio/video duration (0 for images)
         * @param visibleTo      List of UIDs allowed to see this status
         * @return               WorkRequest ID (use to observe progress)
         */
        fun enqueue(
            context: Context,
            localFile: File,
            type: StatusType,
            caption: String,
            statusId: String,
            durationMs: Long,
            visibleTo: List<String>
        ): UUID {
            val visibleToJson = JSONArray(visibleTo).toString()
            val data = workDataOf(
                KEY_FILE_PATH   to localFile.absolutePath,
                KEY_TYPE        to type.name,
                KEY_CAPTION     to caption,
                KEY_STATUS_ID   to statusId,
                KEY_DURATION_MS to durationMs,
                KEY_VISIBLE_TO  to visibleToJson
            )
            val request = OneTimeWorkRequestBuilder<StatusUploadWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}
