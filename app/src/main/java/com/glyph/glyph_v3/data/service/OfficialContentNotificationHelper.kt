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
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.storage.FirebaseStorage
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.OfficialMessage
import com.glyph.glyph_v3.data.models.OfficialStatus
import com.glyph.glyph_v3.ui.chat.OfficialChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts local notifications for newly-published official content.
 *
 * Mirrors [StatusUpdateNotificationHelper] (channel + rounded-thumbnail custom
 * view using [R.layout.notification_status_reply]). Two entry points:
 *  - [postMessage] for an official *message* — tap opens [OfficialChatActivity].
 *  - [postStatus]  for an official *status*  — tap opens [MainActivity] on the
 *    Status section (consistent with status-update notifications).
 *
 * Notification IDs start at 30_000 to avoid colliding with
 * [StatusUpdateNotificationHelper] (20_000) / [ChatNotificationHelper] (0, 1).
 */
object OfficialContentNotificationHelper {

    const val CHANNEL_ID = "glyph_official_updates"
    private const val TAG = "OfficialContentNotif"
    private const val MAX_REMOTE_IMAGE_BYTES = 4L * 1024 * 1024
    private const val REMOTE_TIMEOUT_MS = 15_000

    private val notifIdCounter = AtomicInteger(30_000)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

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
            "Glyph Official",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Announcements and updates from Glyph Official"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            setSound(soundUri, audioAttributes)
        }
        manager.createNotificationChannel(channel)
    }

    suspend fun postMessage(context: Context, message: OfficialMessage) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val title = message.title.ifBlank { "Glyph Official" }
        val body = message.body.ifBlank { "New message from Glyph Official" }

        val thumbnail: Bitmap? = withContext(Dispatchers.IO) {
            message.imageUrl.takeIf { it.isNotBlank() }?.let { loadThumbnail(context, it) }
        }

        val tapIntent = Intent(context, OfficialChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(OfficialChatActivity.EXTRA_OPEN_MESSAGE_ID, message.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            message.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        thumbnail?.let { bmp ->
            val compact = buildRoundedBitmap(bmp, 256, 256, 52f)
            val contentView = buildRemoteViews(context, title, body, compact)
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setCustomHeadsUpContentView(contentView)
        }

        manager.notify(notifIdCounter.getAndIncrement(), builder.build())
        Log.d(TAG, "Posted official message notification id=${message.id}")
    }

    suspend fun postStatus(context: Context, status: OfficialStatus) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val title = "Glyph Official"
        val body = "Posted a new status update"

        val thumbnail: Bitmap? = withContext(Dispatchers.IO) {
            status.mediaUrl.takeIf { it.isNotBlank() }?.let { loadThumbnail(context, it) }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_official_status", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            status.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        thumbnail?.let { bmp ->
            val compact = buildRoundedBitmap(bmp, 256, 256, 52f)
            val contentView = buildRemoteViews(context, title, body, compact)
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setCustomHeadsUpContentView(contentView)
        }

        manager.notify(notifIdCounter.getAndIncrement(), builder.build())
        Log.d(TAG, "Posted official status notification id=${status.id}")
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun buildRemoteViews(
        context: Context,
        sender: String,
        bodyText: String,
        thumb: Bitmap
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_status_reply).apply {
            setTextViewText(R.id.tvStatusSender, sender)
            setTextViewText(R.id.tvStatusBody, bodyText)
            setImageViewBitmap(R.id.ivStatusThumb, thumb)
            setTextColor(R.id.tvStatusSender, Color.WHITE)
            setTextColor(R.id.tvStatusBody, Color.parseColor("#DADCE0"))
            setViewVisibility(R.id.ivStatusThumb, View.VISIBLE)
        }
    }

    private fun buildRoundedBitmap(src: Bitmap, width: Int, height: Int, cornerRadius: Float): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val path = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }
        paint.color = Color.parseColor("#202124")
        canvas.drawPath(path, paint)
        canvas.save()
        canvas.clipPath(path)
        val scale = minOf(width / src.width.toFloat(), height / src.height.toFloat())
        val destWidth = src.width * scale
        val destHeight = src.height * scale
        val left = (width - destWidth) / 2f
        val top = (height - destHeight) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + destWidth, top + destHeight), paint)
        canvas.restore()
        return out
    }

    private suspend fun loadThumbnail(context: Context, url: String): Bitmap? {
        loadBitmapViaFirebaseStorage(url)?.let { return it }
        loadBitmapViaHttp(url)?.let { return it }
        return try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .override(512, 512)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .submit()
                .get()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail for $url", e)
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
        } catch (_: Exception) {
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
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                decodeSampledBitmap(bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ByteArrayInputStream(bytes).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds, 512, 512)
        }
        return ByteArrayInputStream(bytes).use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
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
