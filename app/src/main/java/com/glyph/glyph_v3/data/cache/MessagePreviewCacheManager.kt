package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.glyph.glyph_v3.data.media.ThumbnailGenerator
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.ui.share.LinkPreviewResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object MessagePreviewCacheManager {
    private const val TAG = "MessagePreviewCache"
    private const val ROOT_DIR = "message_preview_cache"
    private const val LINK_DIR = "links"
    private const val DOCUMENT_DIR = "documents"
    private const val MEDIA_DIR = "media"
    private const val COLLAGE_DIR = "collages"
    private const val META_SUFFIX = ".meta"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    // Remember URLs that returned HTTP 403 (revoked/expired tokens) so we don't keep retrying
    // them on every chat open. Cleared on app restart, which forces a fresh attempt.
    private val failed403Urls = ConcurrentHashMap.newKeySet<String>()
    private val collageThumbnailCache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val mediaPreviewBitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile
    private var rootDir: File? = null

    @Volatile
    private var linkDir: File? = null

    @Volatile
    private var documentDir: File? = null

    @Volatile
    private var mediaDir: File? = null

    @Volatile
    private var collageDir: File? = null

    fun init(context: Context) {
        ensureInitialized(context.applicationContext)
    }

    fun resolveLinkPreviewModel(previewUrl: String?, remoteThumbnailUrl: String?): Any? {
        val cachedPath = getCachedLinkThumbnailPath(previewUrl)
        return cachedPath ?: remoteThumbnailUrl
    }

    fun resolveDocumentPreviewModel(messageId: String, remoteThumbnailUrl: String?): Any? {
        val cachedPath = getCachedDocumentThumbnailPath(messageId)
        return cachedPath ?: remoteThumbnailUrl
    }

    fun resolveMediaPreviewModel(message: Message, fallbackModel: Any?): Any? {
        val cachedPath = getCachedMediaPreviewPath(message.id)
        return cachedPath ?: fallbackModel
    }

    fun resolveMediaGroupPreviewModel(messageId: String, index: Int, fallbackModel: Any?): Any? {
        val cachedPath = getCachedMediaPreviewPath("${messageId}_$index")
        return cachedPath ?: fallbackModel
    }

    fun decodeCollageThumbnail(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val cacheKey = sha256(base64)
        synchronized(collageThumbnailCache) {
            collageThumbnailCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        // Check disk cache before expensive base64 decode
        val diskFile = collageFile(cacheKey)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 0L) {
            val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (diskBitmap != null) {
                synchronized(collageThumbnailCache) {
                    collageThumbnailCache.put(cacheKey, diskBitmap)
                }
                return diskBitmap
            }
        }

        val decoded = ThumbnailGenerator.decodeBase64Thumbnail(base64) ?: return null
        synchronized(collageThumbnailCache) {
            collageThumbnailCache.put(cacheKey, decoded)
        }

        // Persist to disk asynchronously for cold-start resilience
        if (diskFile != null) {
            scope.launch {
                runCatching {
                    diskFile.parentFile?.mkdirs()
                    val tempFile = File(diskFile.parentFile, "${diskFile.name}.tmp")
                    tempFile.outputStream().use { out ->
                        decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (!tempFile.renameTo(diskFile)) {
                        tempFile.copyTo(diskFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            }
        }
        return decoded
    }

    fun peekCollageThumbnail(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val cacheKey = sha256(base64)
        return synchronized(collageThumbnailCache) {
            collageThumbnailCache.get(cacheKey)?.takeIf { !it.isRecycled }
        }
    }

    fun warmCollageThumbnailAsync(base64: String?) {
        if (base64.isNullOrBlank()) return
        val cacheKey = "single_b64:${sha256(base64)}"
        if (!inFlightKeys.add(cacheKey)) return

        scope.launch {
            try {
                decodeCollageThumbnail(base64)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to warm single collage thumbnail", error)
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    fun getCachedLinkThumbnailPath(previewUrl: String?): String? {
        if (previewUrl.isNullOrBlank()) return null
        val file = linkFile(previewUrl) ?: return null
        return file.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    fun getCachedDocumentThumbnailPath(messageId: String): String? {
        val file = documentFile(messageId) ?: return null
        return file.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    fun getCachedMediaPreviewPath(messageId: String): String? {
        val file = mediaFile(messageId) ?: return null
        return file.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    fun peekCachedMediaPreviewBitmap(messageId: String): Bitmap? {
        return synchronized(mediaPreviewBitmapCache) {
            mediaPreviewBitmapCache.get(messageId)?.takeIf { !it.isRecycled }
        }
    }

    fun warmMessagesAsync(
        context: Context,
        messages: Collection<Message>,
        previewModelOverrides: Map<String, Any?> = emptyMap()
    ) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        messages.forEach { message ->
            when (message.type) {
                MessageType.TEXT -> {
                    val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)
                    warmLinkPreviewAsync(appContext, previewUrl, message.thumbnailUrl)
                }

                MessageType.DOCUMENT -> {
                    warmDocumentThumbnailAsync(appContext, message.id, message.thumbnailUrl)
                }

                MessageType.IMAGE,
                MessageType.VIDEO,
                MessageType.GIF,
                MessageType.MEME,
                MessageType.STICKER,
                MessageType.KLIPY_EMOJI -> {
                    warmMediaPreviewAsync(appContext, message, previewModelOverrides[message.id])
                }

                MessageType.MEDIA_GROUP -> {
                    warmMediaGroupPreviewAsync(appContext, message)
                    warmCollageThumbsAsync(message)
                }

                else -> Unit
            }
        }
    }

    suspend fun warmMessagesBlocking(context: Context, messages: Collection<Message>) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        messages.forEach { message ->
            runCatching {
                warmMessageBlocking(appContext, message)
            }.onFailure { error ->
                Log.w(TAG, "Failed to warm preview for message ${message.id}", error)
            }
        }
    }

    private suspend fun warmMessageBlocking(context: Context, message: Message) {
        when (message.type) {
            MessageType.TEXT -> {
                val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)
                warmLinkPreviewBlocking(context, previewUrl, message.thumbnailUrl)
            }

            MessageType.DOCUMENT -> {
                warmDocumentThumbnailBlocking(context, message.id, message.thumbnailUrl)
            }

            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> {
                warmMediaPreviewBlocking(context, message)
            }

            MessageType.MEDIA_GROUP -> {
                warmMediaGroupPreviewBlocking(context, message)
                warmCollageThumbsBlocking(message)
            }

            else -> Unit
        }
    }

    fun warmMediaPreviewAsync(context: Context, message: Message, previewModelOverride: Any? = null) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val cacheKey = "media:${message.id}"
        if (!inFlightKeys.add(cacheKey)) return

        scope.launch {
            try {
                warmMediaPreviewBlocking(appContext, message, previewModelOverride)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to warm media preview for ${message.id}", error)
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    private suspend fun warmMediaPreviewBlocking(
        context: Context,
        message: Message,
        previewModelOverride: Any? = null
    ) {
        val targetFile = mediaFile(message.id) ?: return
        val remotePreviewCandidates = remoteMediaPreviewCandidates(message)
        if (remotePreviewCandidates.isNotEmpty()) {
            val remoteResult = cacheFirstAvailableRemoteAsset(
                context = context,
                sourceUrls = remotePreviewCandidates,
                targetFile = targetFile,
                logLabel = "media:${message.id}"
            )
            if (!remoteResult.isNullOrBlank()) {
                warmCachedMediaPreviewBitmap(message.id, targetFile)
                return
            }
        }

        val localPreviewModel = resolveLocalMediaPreviewModel(message, previewModelOverride) ?: return
        val localResult = cacheLocalPreviewAsset(
            context = context,
            sourceModel = localPreviewModel,
            targetFile = targetFile,
            expectedSource = "local:${localPreviewModel}"
        )
        if (!localResult.isNullOrBlank()) {
            warmCachedMediaPreviewBitmap(message.id, targetFile)
        }
    }

    fun warmMediaGroupPreviewAsync(context: Context, message: Message) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        message.mediaItemsList.take(4).forEachIndexed { index, item ->
            val sourceUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: item.url.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val cacheKey = "group:${message.id}:$index"
            if (!inFlightKeys.add(cacheKey)) return@forEachIndexed

            scope.launch {
                try {
                    cacheRemoteAsset(
                        context = appContext,
                        sourceUrl = sourceUrl,
                        targetFile = mediaFile("${message.id}_$index") ?: return@launch,
                        expectedSource = sourceUrl
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to warm media group preview for ${message.id}#$index", error)
                } finally {
                    inFlightKeys.remove(cacheKey)
                }
            }
        }
    }

    private suspend fun warmMediaGroupPreviewBlocking(context: Context, message: Message) {
        message.mediaItemsList.take(4).forEachIndexed { index, item ->
            val sourceUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: item.url.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed

            runCatching {
                cacheRemoteAsset(
                    context = context,
                    sourceUrl = sourceUrl,
                    targetFile = mediaFile("${message.id}_$index") ?: return@forEachIndexed,
                    expectedSource = sourceUrl
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to warm media group preview for ${message.id}#$index", error)
            }
        }
    }

    private fun warmCollageThumbsAsync(message: Message) {
        val thumbnails = message.mediaItemsList
            .asSequence()
            .mapNotNull { it.thumbnailBase64?.takeIf { encoded -> encoded.isNotBlank() } }
            .take(4)
            .toList()
        if (thumbnails.isEmpty()) return

        val cacheKey = "group_b64:${message.id}"
        if (!inFlightKeys.add(cacheKey)) return

        scope.launch {
            try {
                thumbnails.forEach { encoded ->
                    decodeCollageThumbnail(encoded)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to warm collage thumbnails for ${message.id}", error)
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    private fun warmCollageThumbsBlocking(message: Message) {
        message.mediaItemsList
            .asSequence()
            .mapNotNull { it.thumbnailBase64?.takeIf { encoded -> encoded.isNotBlank() } }
            .take(4)
            .forEach { encoded ->
                decodeCollageThumbnail(encoded)
            }
    }

    fun warmLinkPreviewAsync(context: Context, previewUrl: String?, remoteThumbnailUrl: String?) {
        if (previewUrl.isNullOrBlank() || remoteThumbnailUrl.isNullOrBlank()) return
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val cacheKey = "link:$previewUrl"
        if (!inFlightKeys.add(cacheKey)) return

        scope.launch {
            try {
                cacheRemoteAsset(
                    context = appContext,
                    sourceUrl = remoteThumbnailUrl,
                    targetFile = linkFile(previewUrl) ?: return@launch,
                    expectedSource = remoteThumbnailUrl
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to warm link preview for $previewUrl", error)
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    private suspend fun warmLinkPreviewBlocking(context: Context, previewUrl: String?, remoteThumbnailUrl: String?) {
        if (previewUrl.isNullOrBlank() || remoteThumbnailUrl.isNullOrBlank()) return

        cacheRemoteAsset(
            context = context,
            sourceUrl = remoteThumbnailUrl,
            targetFile = linkFile(previewUrl) ?: return,
            expectedSource = remoteThumbnailUrl
        )
    }

    fun warmDocumentThumbnailAsync(context: Context, messageId: String, remoteThumbnailUrl: String?) {
        if (remoteThumbnailUrl.isNullOrBlank()) return
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val cacheKey = "doc:$messageId"
        if (!inFlightKeys.add(cacheKey)) return

        scope.launch {
            try {
                cacheRemoteAsset(
                    context = appContext,
                    sourceUrl = remoteThumbnailUrl,
                    targetFile = documentFile(messageId) ?: return@launch,
                    expectedSource = remoteThumbnailUrl
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to warm document preview for $messageId", error)
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    private suspend fun warmDocumentThumbnailBlocking(context: Context, messageId: String, remoteThumbnailUrl: String?) {
        if (remoteThumbnailUrl.isNullOrBlank()) return

        cacheRemoteAsset(
            context = context,
            sourceUrl = remoteThumbnailUrl,
            targetFile = documentFile(messageId) ?: return,
            expectedSource = remoteThumbnailUrl
        )
    }

    suspend fun cacheDocumentThumbnailBytes(context: Context, messageId: String, thumbnailBytes: ByteArray): String? {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        return withContext(Dispatchers.IO) {
            runCatching {
                val targetFile = documentFile(messageId) ?: return@runCatching null
                targetFile.parentFile?.mkdirs()
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                tempFile.writeBytes(thumbnailBytes)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                metaFile(targetFile)?.writeText("bytes:$messageId")
                targetFile.absolutePath
            }.getOrElse { error ->
                Log.w(TAG, "Failed to persist local document thumbnail for $messageId", error)
                null
            }
        }
    }

    fun evictMessagePreviews(message: Message) {
        when (message.type) {
            MessageType.TEXT -> {
                val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)
                evictLinkPreview(previewUrl)
            }

            MessageType.DOCUMENT -> {
                evictDocumentPreview(message.id)
            }

            else -> Unit
        }
    }

    fun evictLinkPreview(previewUrl: String?) {
        if (previewUrl.isNullOrBlank()) return
        val file = linkFile(previewUrl)
        file?.delete()
        metaFile(file)?.delete()
    }

    fun evictDocumentPreview(messageId: String) {
        val file = documentFile(messageId)
        file?.delete()
        metaFile(file)?.delete()
    }

    private suspend fun cacheRemoteAsset(
        context: Context,
        sourceUrl: String,
        targetFile: File,
        expectedSource: String
    ): String? = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        val metaFile = metaFile(targetFile)
        val savedSource = metaFile?.takeIf { it.exists() }?.readText()
        if (targetFile.exists() && targetFile.length() > 0L && savedSource == expectedSource) {
            Glide.with(context)
                .load(targetFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload()
            return@withContext targetFile.absolutePath
        }

        // Skip URLs we already know return 403 (revoked Firebase tokens) to avoid
        // hammering the network on every chat open with requests that will always fail.
        if (failed403Urls.contains(sourceUrl)) {
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@withContext targetFile.absolutePath
            }
            return@withContext null
        }

        val downloadedFile = runCatching {
            Glide.with(context)
                .asFile()
                .load(sourceUrl)
                // DiskCacheStrategy.DATA caches the raw source bytes so future Bitmap/Drawable
                // loads hit disk, but does NOT try to encode the File resource itself.
                // DiskCacheStrategy.ALL would cause NoResultEncoderAvailableException because
                // Glide has no registered Encoder<File> for the decoded resource step.
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .submit()
                .get()
        }.getOrElse { error ->
            // Detect HTTP 403 and blacklist the URL to prevent repeated retries.
            val errorText = error.message ?: error.cause?.message ?: ""
            if (errorText.contains("403") || errorText.contains("status code: 403")) {
                failed403Urls.add(sourceUrl)
            }
            if (targetFile.exists() && targetFile.length() > 0L) {
                Glide.with(context)
                    .load(targetFile)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
                Log.w(TAG, "Using existing cached preview after remote fetch failed for $sourceUrl", error)
                return@withContext targetFile.absolutePath
            }

            Log.w(TAG, "Failed to cache remote asset for $sourceUrl", error)
            return@withContext null
        }

        // Guard: Glide's temp file might not exist if Glide evicted its cache entry
        // between .get() returning and us opening the stream, or if a concurrent
        // cacheRemoteAsset call already renamed/moved it.
        if (!downloadedFile.exists() || downloadedFile.length() == 0L) {
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@withContext targetFile.absolutePath
            }
            return@withContext null
        }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        downloadedFile.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            // renameTo can fail cross-filesystem; fall back to copyTo.
            // Guard: a concurrent cacheRemoteAsset call on the same key might have
            // already renamed tempFile away, so only proceed if it still exists.
            if (tempFile.exists() && tempFile.length() > 0L) {
                runCatching { tempFile.copyTo(targetFile, overwrite = true) }
                tempFile.delete()
            }
        }

        metaFile?.writeText(expectedSource)

        Glide.with(context)
            .load(targetFile)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .preload()

        targetFile.absolutePath
    }

    private suspend fun cacheLocalPreviewAsset(
        context: Context,
        sourceModel: Any,
        targetFile: File,
        expectedSource: String
    ): String? = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        val metaFile = metaFile(targetFile)
        val savedSource = metaFile?.takeIf { it.exists() }?.readText()
        if (targetFile.exists() && targetFile.length() > 0L && savedSource == expectedSource) {
            Glide.with(context)
                .load(targetFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload()
            return@withContext targetFile.absolutePath
        }

        val previewBitmap = runCatching {
            Glide.with(context)
                .asBitmap()
                .load(sourceModel)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .submit(160, 160)
                .get()
        }.getOrElse { error ->
            if (targetFile.exists() && targetFile.length() > 0L) {
                Glide.with(context)
                    .load(targetFile)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
                Log.w(TAG, "Using existing cached local preview after decode failed for $expectedSource", error)
                return@withContext targetFile.absolutePath
            }

            Log.w(TAG, "Failed to cache local preview for $expectedSource", error)
            return@withContext null
        }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tempFile.outputStream().use { output ->
            previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            if (tempFile.exists() && tempFile.length() > 0L) {
                runCatching { tempFile.copyTo(targetFile, overwrite = true) }
                tempFile.delete()
            }
        }

        metaFile?.writeText(expectedSource)

        Glide.with(context)
            .load(targetFile)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .preload()

        targetFile.absolutePath
    }

    private suspend fun cacheFirstAvailableRemoteAsset(
        context: Context,
        sourceUrls: List<String>,
        targetFile: File,
        logLabel: String
    ): String? {
        sourceUrls.distinct().forEach { sourceUrl ->
            val result = cacheRemoteAsset(
                context = context,
                sourceUrl = sourceUrl,
                targetFile = targetFile,
                expectedSource = sourceUrl
            )
            if (!result.isNullOrBlank()) {
                return result
            }
        }

        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile.absolutePath
        }

        Log.w(TAG, "No remote preview candidates succeeded for $logLabel")
        return null
    }

    private fun warmCachedMediaPreviewBitmap(messageId: String, previewFile: File) {
        if (!previewFile.exists() || previewFile.length() <= 0L) return
        synchronized(mediaPreviewBitmapCache) {
            mediaPreviewBitmapCache.get(messageId)?.takeIf { !it.isRecycled }?.let { return }
        }
        val decoded = BitmapFactory.decodeFile(previewFile.absolutePath) ?: return
        synchronized(mediaPreviewBitmapCache) {
            mediaPreviewBitmapCache.put(messageId, decoded)
        }
    }

    private fun ensureInitialized(context: Context) {
        if (rootDir != null && linkDir != null && documentDir != null && mediaDir != null && collageDir != null) return

        synchronized(this) {
            if (rootDir != null && linkDir != null && documentDir != null && mediaDir != null && collageDir != null) return

            val root = File(context.filesDir, ROOT_DIR)
            val links = File(root, LINK_DIR)
            val documents = File(root, DOCUMENT_DIR)
            val media = File(root, MEDIA_DIR)
            val collages = File(root, COLLAGE_DIR)
            root.mkdirs()
            links.mkdirs()
            documents.mkdirs()
            media.mkdirs()
            collages.mkdirs()
            rootDir = root
            linkDir = links
            documentDir = documents
            mediaDir = media
            collageDir = collages
        }
    }

    private fun linkFile(previewUrl: String): File? {
        val safeDir = linkDir ?: return null
        return File(safeDir, "${sha256(previewUrl)}.img")
    }

    private fun documentFile(messageId: String): File? {
        val safeDir = documentDir ?: return null
        return File(safeDir, "$messageId.jpg")
    }

    private fun mediaFile(messageId: String): File? {
        val safeDir = mediaDir ?: return null
        return File(safeDir, "$messageId.cache")
    }

    private fun collageFile(cacheKey: String): File? {
        val safeDir = collageDir ?: return null
        return File(safeDir, "$cacheKey.png")
    }

    private fun metaFile(file: File?): File? {
        if (file == null) return null
        return File(file.parentFile, "${file.name}$META_SUFFIX")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun resolveLocalMediaPreviewModel(message: Message, previewModelOverride: Any?): Any? {
        val explicit = previewModelOverride?.takeIf { isLocalPreviewModel(it) }
        if (explicit != null) return explicit

        val localUriValue = message.localUri?.takeIf { it.isNotBlank() } ?: return null
        return when {
            localUriValue.startsWith("/") -> localUriValue.takeIf { File(it).exists() }
            else -> runCatching { Uri.parse(localUriValue) }.getOrNull()
                ?.takeIf { uri ->
                    when (uri.scheme?.lowercase()) {
                        "file" -> uri.path?.let { File(it).exists() } == true
                        "content" -> true
                        else -> false
                    }
                }
                ?: localUriValue.takeIf { isLocalPreviewModel(it) }
        }
    }

    private fun isLocalPreviewModel(model: Any): Boolean {
        return when (model) {
            is File -> model.exists()
            is Uri -> when (model.scheme?.lowercase()) {
                "file" -> model.path?.let { File(it).exists() } == true
                "content" -> true
                else -> false
            }
            is String -> when {
                model.startsWith("/") -> File(model).exists()
                model.startsWith("file://") -> Uri.parse(model).path?.let { File(it).exists() } == true
                model.startsWith("content://") -> true
                else -> false
            }
            else -> false
        }
    }

    private fun remoteMediaPreviewCandidates(message: Message): List<String> {
        return when (message.type) {
            MessageType.IMAGE -> listOfNotNull(
                message.thumbnailUrl?.takeIf { it.isNotBlank() },
                message.imageUrl?.takeIf { it.isNotBlank() }
            )

            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> listOfNotNull(
                message.imageUrl?.takeIf { it.isNotBlank() },
                message.thumbnailUrl?.takeIf { it.isNotBlank() }
            )

            MessageType.VIDEO -> listOfNotNull(
                message.thumbnailUrl?.takeIf { it.isNotBlank() },
                message.videoUrl?.takeIf { it.isNotBlank() }
            )

            else -> emptyList()
        }
    }
}