package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MessageType
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * WhatsApp-style collage view for displaying 2-4 images in a single message bubble.
 * 
 * Performance optimizations:
 * - Uses thumbnail-sized images during scroll
 * - Caches layout calculations
 * - Avoids overdraw with clipping
 * - Reuses child views
 */
class CollageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_VISIBLE_ITEMS = 4
        private const val GRID_GAP_PX = 4

        fun requiredHeightForItemCount(widthPx: Int, totalItemCount: Int): Int {
            if (widthPx <= 0 || totalItemCount <= 0) return 0
            val visibleCount = totalItemCount.coerceAtMost(MAX_VISIBLE_ITEMS)
            val cellWidth = (widthPx - GRID_GAP_PX).coerceAtLeast(0) / 2
            val rows = if (visibleCount <= 2) 1 else 2
            return (rows * cellWidth) + ((rows - 1) * GRID_GAP_PX)
        }
    }

    private var mediaItems: List<MediaItem> = emptyList()
    private var totalMediaItemCount: Int = 0
    private var lastLoadedSignature: String? = null
    private var lastMeasureTraceSignature: String? = null
    private var boundMessageId: String? = null
    private var isScrolling = false
    private var showingScrollPreview = false
    private var onImageClickListener: ((index: Int) -> Unit)? = null
    private var onImageLongClickListener: ((index: Int) -> Boolean)? = null
    
    // Full-resolution upgrade tracking
    private var pendingFullResUpgrade = false
    private val fullResKeys = arrayOfNulls<String>(4)
    private val previewTargets = arrayOfNulls<CustomTarget<Bitmap>>(4)
    private val fullResTargets = arrayOfNulls<CustomTarget<Drawable>>(4)
    private var loadGeneration = 0
    private val clearRequestManager by lazy(LazyThreadSafetyMode.NONE) {
        Glide.with(context.applicationContext)
    }

    // Hooked by ChatAdapter to reuse chat-open preloaded drawables for MEDIA_GROUP cells.
    var preloadedMediaDrawableProvider: ((Any, Int, Int, MessageType) -> Drawable?)? = null
    
    // Store corner radii to reapply after setupImageViews
    private var storedTopLeft = 0f
    private var storedTopRight = 0f
    private var storedBottomRight = 0f
    private var storedBottomLeft = 0f
    private val cornerShapeCache = HashMap<Long, List<ShapeAppearanceModel>>(12)
    
    private val imageViews = mutableListOf<ShapeableImageView>()
    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#80000000") // 50% black overlay
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 72f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    
    private val gap = GRID_GAP_PX // px; must match requiredHeightForItemCount
    private var activeVisibleImageCount = 0

    private fun trace(event: String) {
        if (!ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) return
        Log.d("GlyphCacheDebug", "[CollageTrace] msg=${boundMessageId?.take(8) ?: "unknown"} $event")
    }
    
    init {
        setWillNotDraw(false)
        setupOutlineProvider()
    }
    
    private fun setupOutlineProvider() {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                // Use stored corner radii for the outline
                if (storedTopLeft == storedTopRight && storedTopRight == storedBottomRight && storedBottomRight == storedBottomLeft) {
                    // All corners equal - use simple rounded rect
                    outline.setRoundRect(0, 0, view.width, view.height, storedTopLeft)
                } else {
                    // Different corners - use path (requires API 30+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val path = android.graphics.Path()
                        val radii = floatArrayOf(
                            storedTopLeft, storedTopLeft,
                            storedTopRight, storedTopRight,
                            storedBottomRight, storedBottomRight,
                            storedBottomLeft, storedBottomLeft
                        )
                        path.addRoundRect(
                            0f, 0f, view.width.toFloat(), view.height.toFloat(),
                            radii,
                            android.graphics.Path.Direction.CW
                        )
                        outline.setPath(path)
                    } else {
                        // Fallback for older APIs - use average radius
                        val avgRadius = (storedTopLeft + storedTopRight + storedBottomRight + storedBottomLeft) / 4f
                        outline.setRoundRect(0, 0, view.width, view.height, avgRadius)
                    }
                }
            }
        }
    }

    private fun clearImage(imageView: ShapeableImageView) {
        clearRequestManager.clear(imageView)
        clearPreviewBlur(imageView)
        imageView.setImageDrawable(null)
        imageView.setBackgroundColor(0xFF2A2A2A.toInt())
    }

    private fun setPreviewBlurEnabled(enabled: Boolean) {
        showingScrollPreview = enabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val effect = if (enabled) {
            RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP)
        } else {
            null
        }
        imageViews.forEach { imageView ->
            imageView.setRenderEffect(effect)
        }
    }

    private fun clearPreviewBlur(imageView: ShapeableImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imageView.setRenderEffect(null)
        }
    }

    private fun cloneDrawable(drawable: Drawable): Drawable? {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return null
            if (bitmap.isRecycled) return null
            val safeConfig = safeBitmapCopyConfig(bitmap)
            val bitmapCopy = runCatching { bitmap.copy(safeConfig, false) }.getOrNull() ?: return null
            if (bitmapCopy.isRecycled) return null
            return BitmapDrawable(resources, bitmapCopy)
        }

        return drawable.constantState?.newDrawable(resources)?.mutate()
    }

    private fun safeBitmapCopyConfig(bitmap: Bitmap): Bitmap.Config {
        val config = bitmap.config ?: return Bitmap.Config.ARGB_8888
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
            Bitmap.Config.ARGB_8888
        } else {
            config
        }
    }

    private fun clearPreviewTarget(target: CustomTarget<Bitmap>) {
        clearRequestManager.clear(target)
    }
    
    /**
     * Bind media items to the collage view.
     * @param items List of MediaItems to display (minimum 2, shows first 4 with +N overlay if more)
     * @param scrolling True if RecyclerView is currently scrolling (use low-res thumbnails)
     * @param onClick Callback when an image is tapped, receives the image index
     * @param onLongClick Callback when an image is long-pressed, receives the image index and should return true if handled
     */
    fun bind(
        items: List<MediaItem>, 
        messageId: String? = null,
        scrolling: Boolean = false, 
        onClick: ((Int) -> Unit)? = null,
        onLongClick: ((Int) -> Boolean)? = null
    ) {
        if (items.size < 2) {
            throw IllegalArgumentException("CollageImageView requires at least 2 images, got ${items.size}")
        }

        val visibleItems = items.take(MAX_VISIBLE_ITEMS)
        val newSignature = buildItemsSignature(visibleItems, items.size, messageId)
        val sameMessage = boundMessageId != null && boundMessageId == messageId
        val imageCountMismatch = activeVisibleImageCount != visibleItems.size
        val itemsChanged = newSignature != lastLoadedSignature || imageCountMismatch
        
        val wasScrolling = isScrolling
        
        mediaItems = visibleItems
        totalMediaItemCount = items.size
        boundMessageId = messageId
        isScrolling = scrolling
        onImageClickListener = onClick
        onImageLongClickListener = onLongClick
        
        // Only reload if items changed
        val needsFullResUpgrade = !scrolling && hasMissingFullRes(visibleItems)

        trace(
            "bind scrolling=$scrolling wasScrolling=$wasScrolling items=${items.size} " +
                "visible=${visibleItems.size} views=${imageViews.size} active=$activeVisibleImageCount changed=$itemsChanged needsUpgrade=$needsFullResUpgrade"
        )

        // Keep existing tiles when the same message is rebound during an active scroll.
        // This avoids redundant setup/reload work on fling-time rebind churn.
        if (sameMessage && wasScrolling && scrolling && !imageCountMismatch && activeVisibleImageCount == visibleItems.size) {
            trace("bind keepScrolling")
            return
        }

        if (itemsChanged) {
            lastLoadedSignature = newSignature
            setupImageViews(visibleItems.size)
            // Reapply stored corner radii after setupImageViews resets them
            if (storedTopLeft != 0f || storedTopRight != 0f || storedBottomRight != 0f || storedBottomLeft != 0f) {
                applyCornerRadiusInternal(storedTopLeft, storedTopRight, storedBottomRight, storedBottomLeft)
            }
            trace("reload itemsChanged")
            loadImages(preserveCurrentDrawables = sameMessage && !imageCountMismatch)
            invalidate() // Redraw to show +N overlay if needed
        } else if ((wasScrolling && !scrolling) || (!scrolling && pendingFullResUpgrade) || needsFullResUpgrade) {
            // Scrolling stopped or pending upgrade - ensure full-res
            trace("reload upgradeToFullRes")
            loadFullResolutionIntoViews(visibleItems)
        }
    }

    private fun buildItemsSignature(items: List<MediaItem>, totalCount: Int, messageId: String?): String {
        if (items.isEmpty()) return "0"
        return buildString(items.size * 96) {
            append(totalCount)
            append('|')
            append(items.size)
            items.forEachIndexed { index, item ->
                val fullResKey = getFullResKey(item)
                val previewFallback = item.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: item.displayUrl.takeIf { it.isNotBlank() }
                    ?: item.url.takeIf { it.isNotBlank() }

                append('|')
                append(item.type.name)
                append(':')
                append(fullResKey)
                append(':')
                append(previewFallback?.toString().orEmpty())
                append(':')
                append(item.thumbnailBase64?.length ?: 0)
            }
        }
    }

    private fun hasMissingFullRes(items: List<MediaItem>): Boolean {
        items.forEachIndexed { index, item ->
            val key = getFullResKey(item)
            if (key.isNotEmpty() && fullResKeys.getOrNull(index) != key) {
                return true
            }
        }
        return false
    }
    
    private fun setupImageViews(visibleCount: Int) {
        // Add missing views if we have fewer than needed
        while (imageViews.size < visibleCount) {
            val imageView = ShapeableImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                // No corner radius here - parent handles clipping with clipToOutline
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(0f)
                    .build()
            }
            imageViews.add(imageView)
            addView(imageView)
        }
        activeVisibleImageCount = visibleCount
        
        // Set click listeners
        imageViews.forEachIndexed { index, imageView ->
            val isVisibleTile = index < visibleCount
            imageView.visibility = if (isVisibleTile) View.VISIBLE else View.GONE
            if (isVisibleTile) {
                imageView.setOnClickListener {
                    onImageClickListener?.invoke(index)
                }
                // Set long-click listener to pass event to parent if not handled
                imageView.setOnLongClickListener {
                    onImageLongClickListener?.invoke(index) ?: false
                }
            } else {
                imageView.setOnClickListener(null)
                imageView.setOnLongClickListener(null)
            }
        }
        trace("setupImageViews visible=$visibleCount children=${imageViews.size} active=$activeVisibleImageCount")
    }
    
    private fun loadImages(preserveCurrentDrawables: Boolean = false) {
        loadGeneration += 1

        // Reset upgrade tracking
        pendingFullResUpgrade = false
        for (i in fullResKeys.indices) {
            fullResKeys[i] = null
        }
        clearAllPreviewTargets()
        clearAllFullResTargets()
        
        if (!preserveCurrentDrawables) {
            imageViews.forEach { imageView ->
                clearImage(imageView)
            }
        } else {
            imageViews.forEach { imageView ->
                imageView.setBackgroundColor(0xFF2A2A2A.toInt())
            }
        }
        
        val itemsToDisplay = mediaItems

        trace("previewStart scrolling=$isScrolling items=${itemsToDisplay.size}")

        if (tryApplySeededGroupDrawables(itemsToDisplay)) {
            trace("previewSeeded items=${itemsToDisplay.size}")
            setPreviewBlurEnabled(false)
            pendingFullResUpgrade = false
            return
        }
        
        // Phase 1: Load ALL thumbnails first (instant display)
        val thumbBitmaps = arrayOfNulls<Bitmap>(itemsToDisplay.size)
        var missingThumbs = 0

        itemsToDisplay.forEachIndexed { index, item ->
            if (!item.thumbnailBase64.isNullOrEmpty()) {
                val thumbnailBitmap = MessagePreviewCacheManager.peekCollageThumbnail(item.thumbnailBase64)
                if (thumbnailBitmap != null) {
                    thumbBitmaps[index] = thumbnailBitmap
                } else {
                    MessagePreviewCacheManager.warmCollageThumbnailAsync(item.thumbnailBase64)
                    missingThumbs++
                }
            } else {
                missingThumbs++
            }
        }

        if (missingThumbs == 0) {
            // All Base64 thumbnails available - apply atomically (do not override full-res)
            for (i in thumbBitmaps.indices) {
                val key = getFullResKey(itemsToDisplay[i])
                if (key.isNotEmpty() && fullResKeys.getOrNull(i) == key) continue
                thumbBitmaps[i]?.let { imageViews[i].setImageBitmap(it) }
            }
            trace("previewBase64Ready items=${itemsToDisplay.size}")
        } else {
            // Fallback: load tiny remote thumbs and display atomically (fills missing slots)
            trace("previewFallback missing=$missingThumbs items=${itemsToDisplay.size}")
            loadFallbackThumbnails(itemsToDisplay, thumbBitmaps)
        }
        setPreviewBlurEnabled(isScrolling)
        
        pendingFullResUpgrade = true
    }

    private fun resolveSeedModels(index: Int, item: MediaItem): List<Any> {
        val models = LinkedHashSet<Any>()

        if (!isScrolling) {
            resolveExistingLocalUri(item)?.let { models.add(it) }
        }

        val previewFallback = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: item.displayUrl?.takeIf { it.isNotBlank() }
            ?: item.url?.takeIf { it.isNotBlank() }

        val previewModel = boundMessageId?.let { messageId ->
            MessagePreviewCacheManager.resolveMediaGroupPreviewModel(messageId, index, previewFallback)
        } ?: previewFallback
        if (previewModel != null && previewModel.toString().isNotBlank()) {
            models.add(previewModel)
        }

        if (!isScrolling) {
            getFullResSource(item)?.let { models.add(it) }
        }

        return models.toList()
    }

    private fun nonBlankModel(model: Any?): Any? {
        return model?.takeIf { it.toString().isNotBlank() }
    }

    private fun targetDecodeSizePx(): Int {
        val measuredTileSizePx = if (width > 0) {
            ((width - gap).coerceAtLeast(1) / 2).coerceAtLeast(1)
        } else {
            0
        }
        if (measuredTileSizePx > 0) return measuredTileSizePx

        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
        return ChatMediaLayoutSizing.mediaGroupTilePreloadSizePx(
            density = displayMetrics.density,
            viewportWidthPx = displayMetrics.widthPixels
        )
    }

    private fun tryApplySeededGroupDrawables(items: List<MediaItem>): Boolean {
        val provider = preloadedMediaDrawableProvider ?: return false
        if (items.isEmpty()) return false
        val decodeSizePx = targetDecodeSizePx()

        val seeded = ArrayList<Drawable>(items.size)
        val hitModels = ArrayList<String>(items.size)
        for (index in items.indices) {
            val seedModels = resolveSeedModels(index, items[index])
            if (seedModels.isEmpty()) return false

            var chosenModel: Any? = null
            val drawable = seedModels.asSequence()
                .mapNotNull { model ->
                    provider.invoke(model, decodeSizePx, decodeSizePx, MessageType.MEDIA_GROUP)?.let {
                        chosenModel = model
                        cloneDrawable(it)
                    }
                }
                .firstOrNull()
                ?: run {
                    trace("seedMiss idx=$index candidates=${seedModels.size}")
                    return false
                }
            seeded.add(drawable)
            hitModels.add(chosenModel?.toString()?.takeLast(72) ?: "unknown")
        }

        seeded.forEachIndexed { index, drawable ->
            val imageView = imageViews.getOrNull(index) ?: return@forEachIndexed
            imageView.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
            imageView.post { (imageView.drawable as? Animatable)?.start() }
            fullResKeys[index] = getFullResKey(items[index])
        }
        trace("seedHit count=${seeded.size} models=${hitModels.joinToString(";")}")
        return true
    }
    
    private fun loadFullResolutionIntoViews(items: List<MediaItem>) {
        pendingFullResUpgrade = false
        if (items.isEmpty()) return
        val loadStartMs = SystemClock.elapsedRealtime()
        trace("fullResStart count=${items.size}")

        // Fast path for chat-open preloaded MEDIA_GROUP specs.
        if (tryApplySeededGroupDrawables(items)) {
            setPreviewBlurEnabled(false)
            trace("fullResSeeded count=${items.size} elapsed=${SystemClock.elapsedRealtime() - loadStartMs}ms")
            return
        }

        // New generation prevents in-flight thumbnail callbacks from overriding
        // full-resolution content after scrolling stops.
        loadGeneration += 1
        val requestGeneration = loadGeneration
        clearAllPreviewTargets()
        clearAllFullResTargets()
        val decodeSizePx = targetDecodeSizePx()

        val stagedDrawables = arrayOfNulls<Drawable>(items.size)
        val stagedKeys = arrayOfNulls<String>(items.size)
        var completed = 0

        fun completeOne() {
            if (requestGeneration != loadGeneration) return
            completed += 1
            if (completed != items.size) return

            // Atomic commit so collage tiles appear as one cohesive unit.
            for (i in items.indices) {
                val drawable = stagedDrawables[i] ?: continue
                val imageView = imageViews.getOrNull(i) ?: continue
                imageView.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
                imageView.post { (imageView.drawable as? Animatable)?.start() }
                fullResKeys[i] = stagedKeys[i]
            }
            setPreviewBlurEnabled(false)
            trace("fullResCommit count=$completed elapsed=${SystemClock.elapsedRealtime() - loadStartMs}ms")
        }

        items.forEachIndexed { index, item ->
            val imageView = imageViews.getOrNull(index)
            if (imageView == null) {
                completeOne()
                return@forEachIndexed
            }

            val key = getFullResKey(item)
            stagedKeys[index] = key

            if (key.isNotEmpty() && fullResKeys.getOrNull(index) == key && imageView.drawable != null) {
                val existingDrawable = cloneDrawable(imageView.drawable)
                if (existingDrawable != null) {
                    stagedDrawables[index] = existingDrawable
                    completeOne()
                    return@forEachIndexed
                }
            }

            val primarySource = getFullResSource(item)
            if (primarySource == null) {
                trace("fullResMissingSource idx=$index")
                completeOne()
                return@forEachIndexed
            }

            val fallbackSource = getFallbackSource(item, primarySource)
            clearFullResTarget(index)
            val target = object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    clearTrackedTarget()
                    if (requestGeneration != loadGeneration) return
                    stagedDrawables[index] = cloneDrawable(resource)
                    trace("fullResReady idx=$index elapsed=${SystemClock.elapsedRealtime() - loadStartMs}ms")
                    completeOne()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    clearTrackedTarget()
                    if (requestGeneration != loadGeneration) return
                    trace("fullResFail idx=$index elapsed=${SystemClock.elapsedRealtime() - loadStartMs}ms")
                    completeOne()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    clearTrackedTarget()
                }

                private fun clearTrackedTarget() {
                    if (fullResTargets.getOrNull(index) === this) {
                        fullResTargets[index] = null
                    }
                }
            }
            fullResTargets[index] = target

            val request = clearRequestManager
                .load(primarySource)
                .override(decodeSizePx, decodeSizePx)
                .centerCrop()

            val requestWithFallback = if (fallbackSource != null) {
                request.error(
                    clearRequestManager
                        .load(fallbackSource)
                        .override(decodeSizePx, decodeSizePx)
                        .centerCrop()
                )
            } else {
                request
            }

            requestWithFallback.into(target)
        }
    }

    private fun getFullResKey(item: MediaItem): String {
        return resolveExistingLocalUri(item)
            ?: item.displayUrl.takeIf { it.isNotBlank() }
            ?: item.url.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun getFullResSource(item: MediaItem): Any? {
        return resolveExistingLocalUri(item)
            ?: item.displayUrl.takeIf { it.isNotBlank() }
            ?: item.url.takeIf { it.isNotBlank() }
    }

    private fun getFallbackSource(item: MediaItem, primary: Any?): Any? {
        val fallback = item.displayUrl.takeIf { it.isNotBlank() }
            ?: item.url.takeIf { it.isNotBlank() }
        return if (fallback != primary) fallback else null
    }

    private fun resolveExistingLocalUri(item: MediaItem): String? {
        val candidate = item.localUri?.takeIf { it.isNotBlank() } ?: return null
        return if (isUsableLocalUri(candidate)) candidate else null
    }

    private fun isUsableLocalUri(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        return runCatching {
            val parsed = android.net.Uri.parse(candidate)
            when {
                parsed.scheme == "content" -> true
                parsed.scheme == "file" || candidate.startsWith("/") -> {
                    val path = parsed.path ?: candidate
                    val file = java.io.File(path)
                    file.exists() && file.length() > 0L
                }
                parsed.scheme != null -> true
                else -> {
                    val file = java.io.File(candidate)
                    file.exists() && file.length() > 0L
                }
            }
        }.getOrDefault(false)
    }

    private fun getPreviewSource(index: Int, item: MediaItem): Any? {
        if (!isScrolling) {
            resolveExistingLocalUri(item)?.let { return it }
        }

        val fallback = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: item.displayUrl.takeIf { it.isNotBlank() }
            ?: item.url.takeIf { it.isNotBlank() }
        val messageId = boundMessageId ?: return fallback
        return nonBlankModel(MessagePreviewCacheManager.resolveMediaGroupPreviewModel(messageId, index, fallback))
    }

    private fun clearPreviewTarget(index: Int) {
        previewTargets.getOrNull(index)?.let { target ->
            clearPreviewTarget(target)
        }
        if (index in previewTargets.indices) {
            previewTargets[index] = null
        }
    }

    private fun clearFullResTarget(index: Int) {
        fullResTargets.getOrNull(index)?.let { target ->
            clearRequestManager.clear(target)
        }
        if (index in fullResTargets.indices) {
            fullResTargets[index] = null
        }
    }

    private fun clearAllPreviewTargets() {
        previewTargets.indices.forEach(::clearPreviewTarget)
    }

    private fun clearAllFullResTargets() {
        fullResTargets.indices.forEach(::clearFullResTarget)
    }

    private fun copyBitmapForView(resource: Bitmap): Bitmap {
        val safeConfig = safeBitmapCopyConfig(resource)
        return resource.copy(safeConfig, false)
    }

    /**
     * Pre-creates the maximum number of child ImageViews during ViewHolder construction
     * (i.e. at pool-warmup time). This moves the ShapeableImageView allocation + addView
     * cost off the scroll-time bind path. Safe to call on an unattached view; click
     * listeners are set (or reset) by the normal bind() → setupImageViews() path.
     */
    fun preWarmImageViews() {
        while (imageViews.size < MAX_VISIBLE_ITEMS) {
            val imageView = ShapeableImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(0f)
                    .build()
            }
            imageViews.add(imageView)
            addView(imageView)
        }
    }

    fun clearForRecycle() {
        loadGeneration += 1
        pendingFullResUpgrade = false
        setPreviewBlurEnabled(false)
        boundMessageId = null
        mediaItems = emptyList()
        totalMediaItemCount = 0
        lastLoadedSignature = null
        clearAllPreviewTargets()
        clearAllFullResTargets()
        for (i in fullResKeys.indices) {
            fullResKeys[i] = null
        }
        imageViews.forEach { imageView ->
            clearImage(imageView)
            imageView.visibility = View.GONE
            imageView.setOnClickListener(null)
            imageView.setOnLongClickListener(null)
        }
        activeVisibleImageCount = 0
        lastMeasureTraceSignature = null
    }

    private fun loadFallbackThumbnails(items: List<MediaItem>, base64Bitmaps: Array<Bitmap?>) {
        val totalItems = items.size
        val bitmaps = base64Bitmaps
        val requestGeneration = loadGeneration
        var completed = 0

        if (isScrolling) {
            for (i in 0 until totalItems) {
                val imageView = imageViews.getOrNull(i) ?: continue
                imageView.setBackgroundColor(0xFF2A2A2A.toInt())
                bitmaps[i]?.let { imageView.setImageBitmap(it) }
            }
            trace("previewLoadFallbackDuringScroll missing=${bitmaps.count { it == null }} items=$totalItems")
        }

        fun commitIfComplete() {
            if (completed != totalItems) return
            for (i in 0 until totalItems) {
                val key = getFullResKey(items[i])
                if (key.isNotEmpty() && fullResKeys.getOrNull(i) == key) continue
                val bmp = bitmaps[i]
                if (bmp != null) {
                    imageViews[i].setImageBitmap(bmp)
                }
            }
            trace("previewCommitFallback completed=$completed items=$totalItems")
        }

        items.forEachIndexed { index, item ->
            val imageView = imageViews[index]
            imageView.setBackgroundColor(0xFF2A2A2A.toInt())

            // Skip if we already have a Base64 thumbnail for this slot
            if (bitmaps[index] != null) {
                completed++
                commitIfComplete()
                return@forEachIndexed
            }

            val previewSource = getPreviewSource(index, item)
            if (previewSource == null) {
                completed++
                commitIfComplete()
                return@forEachIndexed
            }

            clearPreviewTarget(index)
            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    clearTrackedTarget()
                    if (requestGeneration != loadGeneration) {
                        return
                    }
                    bitmaps[index] = copyBitmapForView(resource)
                    completeOne()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    clearTrackedTarget()
                    if (requestGeneration != loadGeneration) {
                        return
                    }
                    completeOne()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    clearTrackedTarget()
                }

                private fun clearTrackedTarget() {
                    if (previewTargets.getOrNull(index) === this) {
                        previewTargets[index] = null
                    }
                }

                private fun completeOne() {
                    if (requestGeneration != loadGeneration) {
                        return
                    }
                    completed++
                    commitIfComplete()
                }
            }
            previewTargets[index] = target
            val previewSize = if (isScrolling) 50 else targetDecodeSizePx()
            val request = Glide.with(context)
                .asBitmap()
                .load(previewSource)
                .override(previewSize, previewSize)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)

            if (isScrolling && shouldUseCacheOnlyForPreview(previewSource)) {
                request.onlyRetrieveFromCache(true).into(target)
            } else {
                request.into(target)
            }
        }

        commitIfComplete()
    }

    private fun shouldUseCacheOnlyForPreview(model: Any?): Boolean {
        return when (model) {
            null -> true
            is java.io.File -> false
            is android.net.Uri -> when (model.scheme?.lowercase()) {
                "content", "file" -> false
                else -> true
            }
            is String -> when {
                model.contains("/message_preview_cache/") -> false
                model.startsWith("/") -> false
                model.startsWith("file://") -> false
                model.startsWith("content://") -> false
                else -> true
            }
            else -> true
        }
    }
    
    // Per-image full-res upgrade handles transition deterministically.
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        
        // Each cell is half the width minus half the gap
        val cellWidth = (width - gap) / 2
        val cellHeight = cellWidth // Square cells
        
        val count = if (mediaItems.isNotEmpty()) {
            mediaItems.size
        } else {
            activeVisibleImageCount.coerceAtMost(MAX_VISIBLE_ITEMS)
        }
        val rows = when {
            count <= 0 -> 0
            count <= 2 -> 1
            else -> 2
        }
        
        val totalHeight = if (rows == 0) 0 else (rows * cellHeight) + ((rows - 1) * gap)
        
        setMeasuredDimension(width, totalHeight)
        val measureSignature = "$width:$count:$rows:$totalHeight:${imageViews.size}:$activeVisibleImageCount"
        if (measureSignature != lastMeasureTraceSignature) {
            lastMeasureTraceSignature = measureSignature
            trace("measure width=$width count=$count views=${imageViews.size} rows=$rows height=$totalHeight")
        }
        
        // Measure children
        val cellSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        imageViews.take(count).forEach { imageView ->
            imageView.measure(cellSpec, cellSpec)
        }
    }
    
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = (width - gap) / 2
        val cellHeight = cellWidth
        
        val count = if (mediaItems.isNotEmpty()) {
            mediaItems.size
        } else {
            activeVisibleImageCount.coerceAtMost(MAX_VISIBLE_ITEMS)
        }
        
        when (count) {
            2 -> {
                // 2 images: side by side
                imageViews.getOrNull(0)?.layout(0, 0, cellWidth, cellHeight)
                imageViews.getOrNull(1)?.layout(cellWidth + gap, 0, width, cellHeight)
            }
            3 -> {
                // 3 images: 2 on top, 1 on bottom (centered or full width)
                imageViews.getOrNull(0)?.layout(0, 0, cellWidth, cellHeight)
                imageViews.getOrNull(1)?.layout(cellWidth + gap, 0, width, cellHeight)
                imageViews.getOrNull(2)?.layout(0, cellHeight + gap, width, cellHeight * 2 + gap)
            }
            4 -> {
                // 4 images: 2x2 grid
                imageViews.getOrNull(0)?.layout(0, 0, cellWidth, cellHeight)
                imageViews.getOrNull(1)?.layout(cellWidth + gap, 0, width, cellHeight)
                imageViews.getOrNull(2)?.layout(0, cellHeight + gap, cellWidth, cellHeight * 2 + gap)
                imageViews.getOrNull(3)?.layout(cellWidth + gap, cellHeight + gap, width, cellHeight * 2 + gap)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }
    
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        
        // Draw "+N" overlay on last image if there are more than 4 images
        // This is called AFTER children are drawn, so overlay appears on top
        if (totalMediaItemCount > MAX_VISIBLE_ITEMS && activeVisibleImageCount >= MAX_VISIBLE_ITEMS && imageViews.size >= MAX_VISIBLE_ITEMS) {
            val lastImageView = imageViews[3]
            val overlayText = "+${totalMediaItemCount - MAX_VISIBLE_ITEMS}"
            
            // Draw semi-transparent overlay
            canvas.drawRect(
                lastImageView.left.toFloat(),
                lastImageView.top.toFloat(),
                lastImageView.right.toFloat(),
                lastImageView.bottom.toFloat(),
                overlayPaint
            )
            
            // Draw text centered on last image
            val centerX = (lastImageView.left + lastImageView.right) / 2f
            val centerY = (lastImageView.top + lastImageView.bottom) / 2f
            val textY = centerY - ((textPaint.descent() + textPaint.ascent()) / 2)
            
            canvas.drawText(overlayText, centerX, textY, textPaint)
        }
    }
    
    /**
     * Update scroll state to switch between thumbnail and full-res images
     */
    fun updateScrollState(scrolling: Boolean) {
        if (isScrolling != scrolling) {
            trace("scrollState -> $scrolling")
            isScrolling = scrolling
            if (scrolling) {
                if (mediaItems.isNotEmpty()) {
                    pendingFullResUpgrade = true
                    setPreviewBlurEnabled(true)
                }
            } else {
                loadFullResolutionIntoViews(mediaItems)
            }
        }
    }
    
    /**
     * Apply corner radius to outer images for grouped bubble design.
     * This syncs the collage image corners with the parent bubble corners.
     */
    fun applyCornerRadius(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        // Store the values for reapplication after bind() calls
        storedTopLeft = topLeft
        storedTopRight = topRight
        storedBottomRight = bottomRight
        storedBottomLeft = bottomLeft
        
        // Invalidate the outline to use new corner radii
        invalidateOutline()
        
        applyCornerRadiusInternal(topLeft, topRight, bottomRight, bottomLeft)
    }
    
    private fun applyCornerRadiusInternal(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        val count = activeVisibleImageCount.coerceAtMost(imageViews.size)
        if (count <= 1) return

        val cacheKey = collageCornerCacheKey(count, topLeft, topRight, bottomRight, bottomLeft)
        val models = cornerShapeCache.getOrPut(cacheKey) {
            buildCornerShapeModels(count, topLeft, topRight, bottomRight, bottomLeft)
        }
        for (index in 0 until count) {
            val model = models.getOrNull(index) ?: continue
            val imageView = imageViews.getOrNull(index) ?: continue
            if (imageView.shapeAppearanceModel != model) {
                imageView.shapeAppearanceModel = model
            }
        }
    }

    private fun collageCornerCacheKey(
        count: Int,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): Long {
        var result = count
        result = 31 * result + java.lang.Float.floatToIntBits(topLeft)
        result = 31 * result + java.lang.Float.floatToIntBits(topRight)
        result = 31 * result + java.lang.Float.floatToIntBits(bottomRight)
        result = 31 * result + java.lang.Float.floatToIntBits(bottomLeft)
        return result.toLong()
    }

    private fun buildCornerShapeModels(
        count: Int,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): List<ShapeAppearanceModel> {
        return when (count) {
            2 -> listOf(
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(topLeft)
                    .setBottomLeftCornerSize(bottomLeft)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(topRight)
                    .setBottomRightCornerSize(bottomRight)
                    .build()
            )
            3 -> listOf(
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(topLeft)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(topRight)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(bottomLeft)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(bottomRight)
                    .build()
            )
            else -> listOf(
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(topLeft)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(topRight)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(bottomLeft)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(0f)
                    .build(),
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f)
                    .setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(bottomRight)
                    .build()
            )
        }
    }
}
