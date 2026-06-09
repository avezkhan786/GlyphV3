package com.glyph.glyph_v3.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.storage.FirebaseStorage
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts local notifications for status updates.
 *
 * The notification shows:
 *  - Sender name as title
 *  - "Added a new status update" as body
 *  - A square thumbnail (rounded corners) for IMAGE/VIDEO statuses in the compact,
 *    heads-up, and expanded notification layouts
 */
object StatusUpdateNotificationHelper {

    const val CHANNEL_ID = "glyph_status_updates"
    private const val TAG = "StatusUpdateNotif"
    private const val MAX_REMOTE_IMAGE_BYTES = 4L * 1024 * 1024
    private const val REMOTE_TIMEOUT_MS = 15_000

    // Notification IDs are kept unique using an atomic counter starting
    // away from the IDs used by ChatNotificationHelper (0, 1).
    private val notifIdCounter = AtomicInteger(20_000)

    // ── Channel ────────────────────────────────────────────────────

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Delete the old channel if it was created with IMPORTANCE_DEFAULT (immutable after creation).
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_HIGH) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Status Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when contacts post new status updates"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            setSound(soundUri, audioAttributes)
        }
        manager.createNotificationChannel(channel)
    }

    // ── Posting ────────────────────────────────────────────────────

    /**
     * Build and post a notification for a single newly seen [status].
     * Must be called from an IO coroutine context (image loading is blocking).
     */
    suspend fun post(
        context: Context,
        ownerName: String,
        status: Status
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val thumbnail: Bitmap? = withContext(Dispatchers.IO) {
            loadThumbnail(context, status)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_status_user_id", status.userId)
            putExtra("open_status_id", status.id)
            putExtra("open_status_owner_name", ownerName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            status.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ownerName)
            .setContentText("Added a new status update")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        if (thumbnail != null) {
            val compactRounded = buildRoundedBitmap(
                src = thumbnail,
                width = 256,
                height = 256,
                cornerRadius = 52f
            )
            val expandedRounded = buildAspectRatioRoundedBitmap(
                src = thumbnail,
                maxWidth = 1200,
                maxHeight = 1200,
                cornerRadius = 68f
            )
            val contentView = buildCompactStatusUpdateRemoteViews(
                packageName = context.packageName,
                senderName = ownerName,
                bodyText = "Added a new status update",
                statusThumbnail = compactRounded
            )
            val expandedView = buildExpandedStatusUpdateRemoteViews(
                packageName = context.packageName,
                senderName = ownerName,
                bodyText = "Added a new status update",
                statusThumbnail = expandedRounded
            )
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(expandedView)
                .setCustomHeadsUpContentView(contentView)
        }

        manager.notify(notifIdCounter.getAndIncrement(), builder.build())
        Log.d(TAG, "Posted status notification for user=${status.userId} status=${status.id}")
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Returns a rounded bitmap that preserves the full image without cropping. */
    private fun buildRoundedBitmap(
        src: Bitmap,
        width: Int,
        height: Int,
        cornerRadius: Float
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val path = Path().apply {
            addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        paint.color = Color.parseColor("#202124")
        canvas.drawPath(path, paint)
        canvas.save()
        canvas.clipPath(path)

        val srcWidth = src.width.toFloat()
        val srcHeight = src.height.toFloat()
        val scale = minOf(width / srcWidth, height / srcHeight)
        val destWidth = srcWidth * scale
        val destHeight = srcHeight * scale
        val left = (width - destWidth) / 2f
        val top = (height - destHeight) / 2f
        val destRect = RectF(left, top, left + destWidth, top + destHeight)
        canvas.drawBitmap(src, null, destRect, paint)
        canvas.restore()
        return out
    }

    private fun buildAspectRatioRoundedBitmap(
        src: Bitmap,
        maxWidth: Int,
        maxHeight: Int,
        cornerRadius: Float
    ): Bitmap {
        val srcWidth = src.width.coerceAtLeast(1)
        val srcHeight = src.height.coerceAtLeast(1)
        val scale = minOf(maxWidth / srcWidth.toFloat(), maxHeight / srcHeight.toFloat())
        val targetWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
        return buildRoundedBitmap(
            src = src,
            width = targetWidth,
            height = targetHeight,
            cornerRadius = cornerRadius.coerceAtMost(minOf(targetWidth, targetHeight) / 3f)
        )
    }

    private fun buildCompactStatusUpdateRemoteViews(
        packageName: String,
        senderName: String,
        bodyText: String,
        statusThumbnail: Bitmap
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_status_reply).apply {
            setTextViewText(R.id.tvStatusSender, senderName)
            setTextViewText(R.id.tvStatusBody, bodyText)
            setImageViewBitmap(R.id.ivStatusThumb, statusThumbnail)
            setTextColor(R.id.tvStatusSender, android.graphics.Color.WHITE)
            setTextColor(R.id.tvStatusBody, android.graphics.Color.parseColor("#DADCE0"))
            setViewVisibility(R.id.ivStatusThumb, android.view.View.VISIBLE)
        }
    }

    private fun buildExpandedStatusUpdateRemoteViews(
        packageName: String,
        senderName: String,
        bodyText: String,
        statusThumbnail: Bitmap
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_status_update_expanded).apply {
            setTextViewText(R.id.tvStatusSender, senderName)
            setTextViewText(R.id.tvStatusBody, bodyText)
            setImageViewBitmap(R.id.ivStatusThumbExpanded, statusThumbnail)
            setTextColor(R.id.tvStatusSender, android.graphics.Color.WHITE)
            setTextColor(R.id.tvStatusBody, android.graphics.Color.parseColor("#DADCE0"))
            setViewVisibility(R.id.ivStatusThumbExpanded, android.view.View.VISIBLE)
        }
    }

    /**
     * Loads a thumbnail bitmap for [status]:
     *  - IMAGE  → fetches mediaUrl
     *  - VIDEO  → fetches thumbnailUrl (falls back to mediaUrl)
     *  - others → null (no image in notification)
     */
    private suspend fun loadThumbnail(context: Context, status: Status): Bitmap? {
        return when (status.type) {
            StatusType.IMAGE -> {
                val imageUrl = status.mediaUrl.ifEmpty { null } ?: return null
                loadBitmapForNotification(context, imageUrl, status.id)
            }
            StatusType.VIDEO -> {
                val thumbnailBitmap = status.thumbnailUrl
                    .ifEmpty { null }
                    ?.let { loadBitmapForNotification(context, it, status.id) }

                thumbnailBitmap
                    ?: status.mediaUrl.ifEmpty { null }?.let { loadVideoFrameForNotification(context, it, status.id) }
            }
            else -> null
        }
    }

    private suspend fun loadBitmapForNotification(
        context: Context,
        url: String,
        statusId: String
    ): Bitmap? {
        loadBitmapViaFirebaseStorage(url)?.let {
            Log.d(TAG, "Loaded status thumbnail via Firebase Storage for status=$statusId")
            return it
        }

        loadBitmapViaHttp(url)?.let {
            Log.d(TAG, "Loaded status thumbnail via HTTP for status=$statusId")
            return it
        }

        return try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .override(512, 512)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .submit()
                .get()
                ?.also {
                    Log.d(TAG, "Loaded status thumbnail via Glide for status=$statusId")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail bitmap for status=$statusId url=$url", e)
            null
        }
    }

    private suspend fun loadBitmapViaFirebaseStorage(url: String): Bitmap? {
        return try {
            val bytes = FirebaseStorage.getInstance()
                .getReferenceFromUrl(url)
                .getBytes(MAX_REMOTE_IMAGE_BYTES)
                .await()
            decodeSampledBitmap(bytes)
        } catch (e: Exception) {
            Log.d(TAG, "Firebase Storage thumbnail load failed for $url: ${e.message}")
            null
        }
    }

    private fun loadBitmapViaHttp(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = REMOTE_TIMEOUT_MS
            connection.readTimeout = REMOTE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.d(TAG, "HTTP thumbnail load returned ${connection.responseCode} for $url")
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                decodeSampledBitmap(bytes)
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP thumbnail load failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadVideoFrameForNotification(
        context: Context,
        mediaUrl: String,
        statusId: String
    ): Bitmap? {
        val localFrame = tryExtractVideoFrameFromUri(context, Uri.parse(mediaUrl))
        if (localFrame != null) {
            Log.d(TAG, "Loaded video frame from uri for status=$statusId")
            return localFrame
        }

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(mediaUrl, emptyMap())
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video frame for status=$statusId url=$mediaUrl", e)
            null
        }
    }

    private fun tryExtractVideoFrameFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        ByteArrayInputStream(bytes).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds, 512, 512)
        }
        return ByteArrayInputStream(bytes).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }
}
