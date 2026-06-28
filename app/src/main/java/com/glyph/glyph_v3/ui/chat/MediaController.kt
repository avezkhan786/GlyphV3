package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.media.MediaTransferManager
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal class MediaController(
    context: Context,
    private val mediaTransferManager: MediaTransferManager
) {
    private val appContext = context.applicationContext

    private data class RetainedPreloadEntry(
        val target: FutureTarget<Drawable>,
        val type: MessageType,
        val messageId: String
    )

    data class ChatMediaPreloadSpec(
        val model: Any,
        val w: Int,
        val h: Int,
        val type: MessageType,
        val messageId: String
    )

    private val retainedMediaPreloadFutures = CopyOnWriteArrayList<FutureTarget<Drawable>>()
    private val retainedPreloadByKey = ConcurrentHashMap<String, RetainedPreloadEntry>()
    private val retainedPreloadByModelType = ConcurrentHashMap<String, RetainedPreloadEntry>()
    private val retainedPreloadByMessageId = ConcurrentHashMap<String, RetainedPreloadEntry>()

    fun warmPrefillMediaAsync(
        scope: CoroutineScope,
        chatId: String,
        messages: List<Message>,
        preferExpandedWarmup: Boolean
    ) {
        if (messages.isEmpty()) return

        MessagePreviewCacheManager.warmMessagesAsync(appContext, messages)

        scope.launch {
            val sources = withContext(Dispatchers.Default) {
                val count = if (preferExpandedWarmup) {
                    INITIAL_VISIBLE_WARMUP_COUNT * INITIAL_SCROLL_UP_WARMUP_MULTIPLIER
                } else {
                    INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS
                }
                resolveAdapterMediaSources(chatId, messages.takeLast(count))
            }
            if (sources.isEmpty()) return@launch

            val remoteSources = sources.filter { source ->
                source is String && (source.startsWith("http://") || source.startsWith("https://"))
            }
            if (remoteSources.isEmpty()) return@launch

            val futures = withContext(Dispatchers.Main.immediate) {
                remoteSources.map { source ->
                    Glide.with(appContext)
                        .downloadOnly()
                        .load(source)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .submit()
                }
            }

            withContext(Dispatchers.IO) {
                futures.forEach { future ->
                    runCatching { future.get(800L, TimeUnit.MILLISECONDS) }
                }
            }
        }
    }

    fun buildInitialChatMediaPreloadSpecs(
        chatId: String,
        messages: List<Message>
    ): List<ChatMediaPreloadSpec> {
        if (messages.isEmpty()) return emptyList()

        val visibleTailWindow = messages.takeLast(
            (INITIAL_VISIBLE_WARMUP_COUNT * INITIAL_SCROLL_UP_WARMUP_MULTIPLIER) + VISIBLE_PREVIEW_BUFFER_ITEMS
        )
        val recentMediaWindow = messages.asReversed()
            .asSequence()
            .filter(::shouldWarmChatMediaOnOpen)
            .take(INITIAL_VISIBLE_WARMUP_COUNT + MEDIA_PREFETCH_BUFFER_ITEMS)
            .toList()
            .asReversed()

        return buildChatMediaPreloadSpecs(
            chatId,
            (visibleTailWindow + recentMediaWindow).distinctBy { it.id }
        )
    }

    suspend fun awaitChatMediaPreloads(
        specs: List<ChatMediaPreloadSpec>,
        totalBudgetMs: Long = 900L
    ) {
        if (specs.isEmpty()) return

        val uniqueSpecs = specs.distinctBy { "${it.model}|${it.w}x${it.h}|${it.type}" }
        val preloadPairs: List<Pair<ChatMediaPreloadSpec, FutureTarget<Drawable>>> = withContext(Dispatchers.Main.immediate) {
            uniqueSpecs.mapNotNull { spec ->
                runCatching {
                    spec to Glide.with(appContext)
                        .load(spec.model)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .disallowHardwareConfig()
                        .fitCenter()
                        .override(spec.w, spec.h)
                        .priority(Priority.IMMEDIATE)
                        .submit(spec.w, spec.h)
                }.getOrNull()
            }
        }
        if (preloadPairs.isEmpty()) return

        withContext(Dispatchers.IO) {
            val deadlineMs = SystemClock.elapsedRealtime() + totalBudgetMs
            preloadPairs.forEach { (_, future) ->
                val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) return@forEach
                runCatching { future.get(remainingMs, TimeUnit.MILLISECONDS) }
            }
        }

        retainedMediaPreloadFutures.addAll(preloadPairs.map { it.second })
        preloadPairs.forEach { (spec, target) ->
            registerRetainedPreload(
                model = spec.model,
                widthPx = spec.w,
                heightPx = spec.h,
                type = spec.type,
                messageId = spec.messageId,
                target = target
            )
        }
    }

    fun retainChatMediaPreloadsAsync(
        scope: CoroutineScope,
        specs: List<ChatMediaPreloadSpec>,
        maxSpecCount: Int = NEAR_SCROLL_RETAINED_PRELOAD_COUNT,
        totalBudgetMs: Long = NEAR_SCROLL_RETAINED_PRELOAD_BUDGET_MS
    ) {
        if (specs.isEmpty() || maxSpecCount <= 0) return

        scope.launch {
            val uniqueSpecs = withContext(Dispatchers.Default) {
                specs.asReversed()
                    .distinctBy { "${it.model}|${it.w}x${it.h}|${it.type}" }
                    .filterNot { spec ->
                        retainedPreloadByKey.containsKey(retainedPreloadCacheKey(spec.model, spec.w, spec.h, spec.type))
                    }
                    .take(maxSpecCount)
            }
            if (uniqueSpecs.isEmpty()) return@launch

            val preloadPairs = withContext(Dispatchers.Main.immediate) {
                uniqueSpecs.mapNotNull { spec ->
                    runCatching {
                        spec to Glide.with(appContext)
                            .load(spec.model)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .disallowHardwareConfig()
                            .fitCenter()
                            .override(spec.w, spec.h)
                            .priority(Priority.HIGH)
                            .submit(spec.w, spec.h)
                    }.getOrNull()
                }
            }
            if (preloadPairs.isEmpty()) return@launch

            retainedMediaPreloadFutures.addAll(preloadPairs.map { it.second })
            preloadPairs.forEach { (spec, target) ->
                registerRetainedPreload(
                    model = spec.model,
                    widthPx = spec.w,
                    heightPx = spec.h,
                    type = spec.type,
                    messageId = spec.messageId,
                    target = target
                )
            }

            withContext(Dispatchers.IO) {
                val deadlineMs = SystemClock.elapsedRealtime() + totalBudgetMs
                preloadPairs.forEach { (_, future) ->
                    val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0L) return@forEach
                    runCatching { future.get(remainingMs, TimeUnit.MILLISECONDS) }
                }
            }
        }
    }

    fun clearRetainedMediaPreloadFutures() {
        retainedPreloadByKey.clear()
        retainedPreloadByModelType.clear()
        retainedPreloadByMessageId.clear()
        if (retainedMediaPreloadFutures.isEmpty()) return
        val toClear = retainedMediaPreloadFutures.toList()
        retainedMediaPreloadFutures.clear()
        toClear.forEach { target ->
            runCatching { Glide.with(appContext).clear(target) }
        }
    }

    fun consumePrefetcherRetainedMediaPreloads(chatId: String) {
        val retained = ChatOpenPrefetcher.takeRetainedMediaPreload(chatId) ?: return
        if (retained.targets.isEmpty()) return

        retainedMediaPreloadFutures.addAll(retained.targets)
        retained.specs.forEachIndexed { index, spec ->
            val target = retained.targets.getOrNull(index) ?: return@forEachIndexed
            registerRetainedPreload(
                model = spec.model,
                widthPx = spec.widthPx,
                heightPx = spec.heightPx,
                type = spec.type,
                messageId = spec.messageId,
                target = target
            )
        }
    }

    fun getRetainedPreloadedDrawable(
        model: Any?,
        widthPx: Int,
        heightPx: Int,
        type: MessageType
    ): Drawable? {
        if (model == null || widthPx <= 0 || heightPx <= 0) return null
        val modelString = model.toString()
        if (modelString.isBlank()) return null

        val entry = retainedPreloadByKey[retainedPreloadCacheKey(model, widthPx, heightPx, type)]
            ?: retainedPreloadByModelType[retainedPreloadModelTypeKey(model, type)]
            ?: return null
        return retainedEntryDrawable(entry)
    }

    fun getRetainedPreloadedDrawableByMessageId(
        messageId: String,
        widthPx: Int,
        heightPx: Int,
        type: MessageType
    ): Drawable? {
        if (messageId.isBlank() || widthPx <= 0 || heightPx <= 0) return null
        val entry = retainedPreloadByMessageId[messageId] ?: return null
        if (entry.type != type || entry.messageId != messageId) return null
        return retainedEntryDrawable(entry)
    }

    private fun resolveAdapterMediaSources(chatId: String, messages: List<Message>): List<Any> {
        val result = ArrayList<Any>(messages.size)
        for (message in messages) {
            val source = resolveAdapterMediaSource(chatId, message)
            if (source != null) result.add(source)
        }
        return result
    }

    private fun resolveAdapterMediaSource(chatId: String, message: Message): Any? {
        val isSticker = message.type == MessageType.STICKER || message.type == MessageType.KLIPY_EMOJI
        val isKlipy = isSticker || message.type == MessageType.GIF || message.type == MessageType.MEME
        when (message.type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> Unit
            else -> return null
        }
        val localImagePath = mediaTransferManager.getLocalFilePath(chatId, message.id, message.type)
        if (!localImagePath.isNullOrEmpty()) return localImagePath
        val imageSource: Any? = when {
            !message.localUri.isNullOrEmpty() -> {
                val uri = Uri.parse(message.localUri)
                if (uri.scheme == "file" || message.localUri!!.startsWith("/")) {
                    val path = uri.path ?: message.localUri!!
                    if (File(path).exists()) message.localUri
                    else if (isKlipy) message.imageUrl ?: message.thumbnailUrl
                    else message.thumbnailUrl ?: message.imageUrl
                } else {
                    message.localUri
                }
            }
            else -> if (isKlipy) message.imageUrl ?: message.thumbnailUrl
                else message.thumbnailUrl ?: message.imageUrl
        }
        return if (isKlipy) imageSource else MessagePreviewCacheManager.resolveMediaPreviewModel(message, imageSource)
    }

    private fun shouldWarmChatMediaOnOpen(message: Message): Boolean {
        return when (message.type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEDIA_GROUP,
            MessageType.DOCUMENT -> true
            else -> false
        }
    }

    private fun buildChatMediaPreloadSpecs(
        chatId: String,
        messages: List<Message>
    ): List<ChatMediaPreloadSpec> {
        val density = Resources.getSystem().displayMetrics.density
        return messages.flatMap { message ->
            if (message.type == MessageType.MEDIA_GROUP && message.mediaItemsList.size > 1) {
                message.mediaItemsList.take(4).mapIndexedNotNull { index, item ->
                    val existingLocal = item.localUri?.takeIf { candidate ->
                        candidate.isNotBlank() && runCatching {
                            val path = Uri.parse(candidate).path ?: candidate
                            File(path).exists()
                        }.getOrDefault(false)
                    }
                    val previewFallback = item.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: item.displayUrl?.takeIf { it.isNotBlank() }
                        ?: item.url?.takeIf { it.isNotBlank() }
                    val previewModel = existingLocal
                        ?: MessagePreviewCacheManager.resolveMediaGroupPreviewModel(message.id, index, previewFallback)
                    previewModel?.takeIf { it.toString().isNotEmpty() }?.let {
                        ChatMediaPreloadSpec(
                            model = it,
                            w = 800,
                            h = 800,
                            type = message.type,
                            messageId = message.id
                        )
                    }
                }
            } else {
                val model = when (message.type) {
                    MessageType.IMAGE,
                    MessageType.VIDEO,
                    MessageType.GIF,
                    MessageType.MEME,
                    MessageType.STICKER,
                    MessageType.KLIPY_EMOJI -> resolveAdapterMediaSource(chatId, message)
                    MessageType.DOCUMENT -> MessagePreviewCacheManager.resolveDocumentPreviewModel(message.id, message.thumbnailUrl)
                    else -> null
                }?.takeIf { it.toString().isNotEmpty() } ?: return@flatMap emptyList<ChatMediaPreloadSpec>()

                val isSticker = message.type == MessageType.STICKER || message.type == MessageType.KLIPY_EMOJI
                val aspect = message.aspectRatio.coerceAtLeast(0.1f)
                val isLandscape = aspect > 1.0f
                val targetWidthDp = when {
                    isSticker -> 180f
                    message.type == MessageType.DOCUMENT -> 220f
                    isLandscape -> 320f
                    else -> 260f
                }

                val targetWidthPx = Math.round(targetWidthDp * density)
                val rawHeightPx = if (message.type == MessageType.DOCUMENT) {
                    Math.round(targetWidthPx / 1.6f)
                } else {
                    Math.round(targetWidthPx / aspect)
                }
                val maxHeightPx = Math.round((if (isSticker) 180f else 800f) * density)
                val minHeightPx = Math.round((if (isSticker) 50f else 100f) * density)

                listOf(
                    ChatMediaPreloadSpec(
                        model = model,
                        w = targetWidthPx,
                        h = rawHeightPx.coerceIn(minHeightPx, maxHeightPx),
                        type = message.type,
                        messageId = message.id
                    )
                )
            }
        }
    }

    private fun registerRetainedPreload(
        model: Any,
        widthPx: Int,
        heightPx: Int,
        type: MessageType,
        messageId: String,
        target: FutureTarget<Drawable>
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        val modelString = model.toString()
        if (modelString.isBlank()) return

        val entry = RetainedPreloadEntry(
            target = target,
            type = type,
            messageId = messageId
        )
        retainedPreloadByKey[retainedPreloadCacheKey(model, widthPx, heightPx, type)] = entry
        retainedPreloadByModelType.putIfAbsent(retainedPreloadModelTypeKey(model, type), entry)
        if (messageId.isNotBlank()) {
            retainedPreloadByMessageId.putIfAbsent(messageId, entry)
        }
    }

    private fun retainedEntryDrawable(entry: RetainedPreloadEntry): Drawable? {
        val target = entry.target
        if (target.isCancelled || !target.isDone) return null
        val drawable = runCatching { target.get() }.getOrNull() ?: return null
        return copyDrawableForSeed(drawable)
    }

    /**
     * Called just before the RecyclerView's first layout pass (from the prefill path).
     * For the first [maxItems] media messages (those most likely visible in the viewport),
     * checks if retained preloads exist. If a preload isn't done yet, waits briefly
     * ([timeoutMs] per item) for Glide to finish decoding.
     *
     * When primeChatOpen has already completed (called before startActivity with a 150ms
     * timeout), all targets are done and this is a no-op (~0ms). On cache miss or slow
     * devices, it adds at most [timeoutMs] * [maxItems] ms to the prefill path.
     *
     * This eliminates the "media pop-in" on the first frame: visible images are already
     * decoded in the Glide memory cache and will be delivered synchronously during bind.
     */
    fun tryFinalizeFirstPaintPreloads(
        messages: List<Message>,
        maxItems: Int = 8,
        timeoutMs: Long = 30L
    ) {
        var waited = 0
        for (msg in messages.asReversed()) { // newest first (visible in viewport)
            if (waited >= maxItems) break
            val key = retainedPreloadByMessageId[msg.id] ?: continue
            if (key.target.isDone || key.target.isCancelled) continue
            try {
                key.target.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                waited++
            } catch (_: Exception) {
                // Timeout or cancellation — preload wasn't ready in time.
                // The bind will fall through to Glide (shows placeholder briefly).
                waited++
            }
        }
    }

    private fun copyDrawableForSeed(drawable: Drawable): Drawable? {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return null
            if (bitmap.isRecycled) return null
            val safeConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                android.graphics.Bitmap.Config.ARGB_8888
            } else {
                bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmapCopy = runCatching { bitmap.copy(safeConfig, false) }.getOrNull() ?: return null
            return BitmapDrawable(appContext.resources, bitmapCopy)
        }
        return drawable.constantState?.newDrawable(appContext.resources)?.mutate()
            ?: drawable.constantState?.newDrawable()?.mutate()
    }

    private fun retainedPreloadCacheKey(model: Any, widthPx: Int, heightPx: Int, type: MessageType): String {
        return "$model|${widthPx}x${heightPx}|$type"
    }

    private fun retainedPreloadModelTypeKey(model: Any, type: MessageType): String {
        return "$model|$type"
    }

    private companion object {
        const val INITIAL_VISIBLE_WARMUP_COUNT = 8
        const val INITIAL_SCROLL_UP_WARMUP_MULTIPLIER = 3
        const val VISIBLE_PREVIEW_BUFFER_ITEMS = 2
        const val MEDIA_PREFETCH_BUFFER_ITEMS = 2
        const val NEAR_SCROLL_RETAINED_PRELOAD_COUNT = 12
        const val NEAR_SCROLL_RETAINED_PRELOAD_BUDGET_MS = 1_000L
    }
}
