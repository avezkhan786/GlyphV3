package com.glyph.glyph_v3.ui.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.Gravity
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import android.util.Log
import android.util.Patterns
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.BuildConfig
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.glyph.glyph_v3.data.cache.StatusThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.databinding.ItemDateHeaderBinding
import com.glyph.glyph_v3.databinding.ItemGroupIntroCardBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingTextBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingTextBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingMediaBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingMediaBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingCollageBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingCollageBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingMediaGroupBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingMediaGroupBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingVideoNoteBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingVideoNoteBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingDocumentBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingDocumentBinding
import com.glyph.glyph_v3.databinding.ItemTypingIndicatorBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingContactBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingContactBinding
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.ui.share.LinkPreviewResolver
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.ui.chat.forward.ForwardMessageCache
import com.glyph.glyph_v3.ui.chat.forward.ForwardSelectionActivity
import com.glyph.glyph_v3.ui.media.MediaViewerActivity
import com.glyph.glyph_v3.ui.media.VideoPlayerActivity
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class ChatAdapter(
    private val selectionManager: SelectionManager,
    private val mediaDownloadListener: MediaDownloadListener? = null,
    private val mediaLocalFileResolver: MediaLocalFileResolver? = null
) : ListAdapter<ChatListItem, ChatAdapter.BaseViewHolder>(ChatListItemDiffCallback()) {

    private val avatarRequestSizePx: Int = (
        40f * android.content.res.Resources.getSystem().displayMetrics.density
    ).roundToInt().coerceAtLeast(64)

    var onGroupIntroDescriptionClick: (() -> Unit)? = null
    var onGroupIntroAddMembersClick: (() -> Unit)? = null
    var onGroupIntroInviteClick: (() -> Unit)? = null

    /** Called when the user taps the retry button on a FAILED outgoing media message. */
    var onRetryUpload: ((Message) -> Unit)? = null

    var preloadedMediaDrawableProvider: ((Any, Int, Int, MessageType) -> Drawable?)? = null
    // Secondary provider keyed by messageId — fallback when model string diverges (e.g. race
    // where local file wasn't present at preload time but exists at bind time).
    var preloadedMediaByMsgIdDrawableProvider: ((String, Int, Int, MessageType) -> Drawable?)? = null

    // Coroutine scope for async thumbnail loading. Recreated on re-attach so that
    // cancellation (on detach) never leaves a dead scope behind.
    private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val groupedGapPx: Int by lazy(LazyThreadSafetyMode.NONE) { dpToPx(1f) }
    private val senderChangeGapPx: Int by lazy(LazyThreadSafetyMode.NONE) { dpToPx(8f) }

    private var isPastelTheme: Boolean = false

    private val normalMessageTextSizeSp = 15f
    private val emojiOnlyTextSizeSp = 24f

    private fun preloadedMediaPlaceholder(
        model: Any?,
        widthPx: Int,
        heightPx: Int,
        type: MessageType,
        messageId: String = ""
    ): Drawable? {
        if (model == null || widthPx <= 0 || heightPx <= 0) return null
        val primary = preloadedMediaDrawableProvider?.invoke(model, widthPx, heightPx, type)
        val raw = primary
            ?: if (messageId.isNotEmpty()) preloadedMediaByMsgIdDrawableProvider?.invoke(messageId, widthPx, heightPx, type)
               else null
        val drawable = raw ?: return null
        return drawable.constantState?.newDrawable()?.mutate() ?: drawable
    }

    private fun tryBindSeededMediaWhileScrolling(
        imageView: ShapeableImageView,
        message: Message,
        model: Any?,
        directionLabel: String
    ): Boolean {
        val imageLayoutParams = imageView.layoutParams
        val widthPx = imageLayoutParams.width.takeIf { it > 0 } ?: return false
        val heightPx = imageLayoutParams.height.takeIf { it > 0 } ?: return false
        val seededDrawable = preloadedMediaPlaceholder(model, widthPx, heightPx, message.type, message.id) ?: return false

        imageView.setImageDrawable(seededDrawable)
        when (message.type) {
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> Unit
            else -> maybeResolveMediaDimensionsFromDrawable(imageView, message, seededDrawable)
        }

        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
            Log.d(
                "GlyphCacheDebug",
                "[BIND-REUSE] $directionLabel SCROLL msgId=${message.id.take(8)} " +
                    "iv=${widthPx}x${heightPx} model=${model.toString().take(60)}"
            )
        }
        return true
    }

    private fun tryBindPreloadedMediaNow(
        imageView: ShapeableImageView,
        message: Message,
        model: Any?,
        directionLabel: String,
        mediaLabel: String
    ): Boolean {
        val imageLayoutParams = imageView.layoutParams
        val widthPx = imageLayoutParams.width.takeIf { it > 0 }
        val heightPx = imageLayoutParams.height.takeIf { it > 0 }
        if (widthPx == null || heightPx == null) {
            if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                Log.w("GlyphCacheDebug", "[TryBind] $directionLabel $mediaLabel msgId=${message.id.take(8)} SKIP reason=layout_not_ready lp=${imageLayoutParams.width}x${imageLayoutParams.height}")
            }
            return false
        }
        val seededDrawable = preloadedMediaPlaceholder(model, widthPx, heightPx, message.type, message.id)
        if (seededDrawable == null) {
            if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                Log.w("GlyphCacheDebug", "[TryBind] $directionLabel $mediaLabel msgId=${message.id.take(8)} MISS w=${widthPx} h=${heightPx} model=${model.toString().take(60)}")
            }
            return false
        }

        imageView.setImageDrawable(seededDrawable)
        applyBlur(imageView, false)
        when (message.type) {
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> {
                (seededDrawable as? android.graphics.drawable.Animatable)?.start()
                imageView.post { (imageView.drawable as? android.graphics.drawable.Animatable)?.start() }
            }
            else -> maybeResolveMediaDimensionsFromDrawable(imageView, message, seededDrawable)
        }

        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
            Log.d(
                "GlyphCacheDebug",
                "[BIND-REUSE] $directionLabel $mediaLabel msgId=${message.id.take(8)} " +
                    "iv=${widthPx}x${heightPx} model=${model.toString().take(60)}"
            )
        }
        return true
    }

    private fun tryBindPreloadedVideoNoteThumbnail(
        imageView: ImageView,
        message: Message,
        model: Any?,
        directionLabel: String
    ): Boolean {
        val imageLayoutParams = imageView.layoutParams
        val widthPx = imageView.width.takeIf { it > 0 }
            ?: imageLayoutParams.width.takeIf { it > 0 }
            ?: dpToPx(240f)
        val heightPx = imageView.height.takeIf { it > 0 }
            ?: imageLayoutParams.height.takeIf { it > 0 }
            ?: dpToPx(240f)
        val seededDrawable = preloadedMediaPlaceholder(model, widthPx, heightPx, MessageType.VIDEO, message.id)
        if (seededDrawable == null) {
            ChatOpenTrace.event(
                message.chatId,
                "video_note_seed_miss",
                "dir=$directionLabel msg=${message.id.take(8)} size=${widthPx}x${heightPx} model=${model.toString().take(60)}"
            )
            return false
        }

        imageView.setImageDrawable(seededDrawable)
        ChatOpenTrace.event(
            message.chatId,
            "video_note_seed_hit",
            "dir=$directionLabel msg=${message.id.take(8)} size=${widthPx}x${heightPx} model=${model.toString().take(60)}"
        )
        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
            Log.d(
                "GlyphCacheDebug",
                "[BIND-REUSE] $directionLabel VNOTE msgId=${message.id.take(8)} iv=${widthPx}x${heightPx} model=${model.toString().take(60)}"
            )
        }
        return true
    }

    // Pending highlight: set by scrollToMessage, consumed by onViewAttachedToWindow
    @Volatile
    private var pendingHighlightMessageId: String? = null

    // Callback for reply clicks
    var onReplyClick: ((String) -> Unit)? = null

    // Callback for message bubble tap (translation toolbar)
    // Provides the Message and the View (anchor for popup positioning)
    var onMessageBubbleClick: ((Message, View) -> Unit)? = null

    // Callback to open the WhatsApp-style reaction popup. Provides the message, the
    // bubble view to use as the layout anchor, and the screen-Y of the touch point so
    // the popup appears near where the user pressed on tall bubbles.
    var onShowReactionPopup: ((Message, View, Float) -> Unit)? = null

    // Callback to request that any active reaction popup be dismissed. Fired when a long
    // press results in a selection state where the reaction bar should not be visible
    // (e.g. multi-select, or the long-pressed bubble was deselected).
    var onDismissReactionPopup: (() -> Unit)? = null

    // Callback when an existing reaction chip on a bubble is tapped. Default behavior in
    // ChatActivity should remove the current user's reaction if they own one.
    var onReactionChipClick: ((Message) -> Unit)? = null

    var onOpenMediaViewer: ((Intent) -> Unit)? = null

    /**
     * Last raw screen-Y recorded by the touch listener attached to each bubble. Updated
     * on ACTION_DOWN / ACTION_MOVE so long-press can report where exactly the user touched.
     */
    internal var lastTouchYScreen: Float = -1f

    /**
     * Resolves the preferred anchor view inside a bubble row (the visible card / bubble
     * background). Falls back to the row itself when none of the known bubble container
     * ids are present.
     */
    private fun anchorForBubble(itemView: View): View {
        return itemView.findViewById<View>(R.id.cardMessage)
            ?: itemView.findViewById<View>(R.id.messageBubble)
            ?: itemView.findViewById<View>(R.id.videoNoteContainer)
            ?: itemView
    }

    /**
     * Centralised long-press handling for any bubble type so that selection mode and the
     * WhatsApp-style reaction bar always stay in sync. Always toggles selection; then if
     * the user now has exactly one selected message (the one they pressed) the reaction
     * popup is shown anchored to its bubble. In every other case (multi-select, or the
     * press deselected the only message) the reaction popup is dismissed.
     */
    internal fun handleBubbleLongPress(messageId: String, itemView: View) {
        val item = currentList.asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .firstOrNull { it.message.id == messageId } ?: return
        val anchor = anchorForBubble(itemView)
        selectionManager.toggleSelection(messageId)
        if (selectionManager.getSelectedCount() == 1 && selectionManager.isSelected(messageId)) {
            onShowReactionPopup?.invoke(item.message, anchor, lastTouchYScreen)
        } else {
            onDismissReactionPopup?.invoke()
        }
    }

    /**
     * Locates the bubble anchor view for the given message id among currently-attached
     * view holders. Returns null if the row is not currently visible. Used by
     * ChatActivity to re-show the reaction popup when selection size returns to one via
     * tap-deselect.
     */
    fun findAnchorForMessage(messageId: String, recyclerView: RecyclerView): View? {
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return null
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return null
        for (pos in first..last) {
            if (pos !in 0 until itemCount) continue
            val li = getItem(pos) as? ChatListItem.MessageItem ?: continue
            if (li.message.id != messageId) continue
            val vh = recyclerView.findViewHolderForAdapterPosition(pos) ?: return null
            return anchorForBubble(vh.itemView)
        }
        return null
    }

    // Map of normalized-last-10-digits phone -> User for registered contact detection
    var registeredUsersMap: Map<String, User> = emptyMap()
        set(value) {
            field = value
            // Only notify visible contact-type items instead of full dataset invalidation
            notifyVisibleItemsChanged("AVATAR_UPDATE")
        }

    // Optional override callback when contact card is tapped; if null, opens ContactViewActivity directly
    var onContactCardClick: ((Message) -> Unit)? = null

    // Emits measured typing-indicator height so ChatActivity can coordinate list push animation.
    var onTypingIndicatorMeasured: ((Int) -> Unit)? = null
    var onMediaDimensionsResolved: ((String, Int, Int) -> Unit)? = null
    var animateTypingPayloadUpdates: Boolean = true
    private val lightweightIncomingEntryMessageIds = mutableSetOf<String>()

    fun armLightweightIncomingEntryAnimation(messageIds: Set<String>) {
        lightweightIncomingEntryMessageIds.clear()
        lightweightIncomingEntryMessageIds.addAll(messageIds)
    }

    var currentUserId: String? = com.google.firebase.auth.FirebaseAuth.getInstance().uid
    var currentUserPhone: String? = null
    var otherUsername: String? = null
        set(value) {
            if (field == value) return
            field = value
            // Targeted update instead of full dataset invalidation to prevent flicker
            notifyVisibleItemsChanged("AVATAR_UPDATE")
        }

    /**
     * Phase 6 (groups): when true, every incoming bubble shows a small sender attribution
     * line above the content. Resolved via [senderNameResolver]; bubbles fall back to
     * hidden when the resolver returns null/blank.
     */
    var isGroupChat: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyVisibleItemsChanged("GROUP_SENDER")
        }

    /** Synchronous uid -> display-name lookup. Should hit a cache; never block. */
    var senderNameResolver: ((String) -> String?)? = null

    /** Synchronous uid -> avatar-url lookup for group-specific incoming rows. */
    var senderAvatarResolver: ((String) -> String?)? = null

    /** Refresh sender-name TextViews after the resolver cache changes. */
    fun refreshGroupSenderNames() {
        if (!isGroupChat) return
        notifyVisibleItemsChanged("GROUP_SENDER")
    }

    private var replyLookupMap: Map<String, Message> = emptyMap()

    var replyLookupMessages: List<Message> = emptyList()
        set(value) {
            field = value
            replyLookupMap = value.associateBy { it.id }
        }

    fun setPastelTheme(isPastel: Boolean) {
        this.isPastelTheme = isPastel
    }

    // Cache theme colors to avoid repeated lookups during bind
    private var colorOtherBubble: Int = 0
    private var colorOtherText: Int = 0
    private var colorOwnBubble: Int = 0
    private var colorOwnText: Int = 0
    private var colorsInitialized = false

    // Cached ColorStateLists for bubble tints — avoids allocation + drawable invalidation per bind
    private var tintOtherBubble: android.content.res.ColorStateList? = null
    private var tintOwnBubble: android.content.res.ColorStateList? = null
    private var colorPrimaryCached: Int = 0

    // Cached Typeface instances to avoid allocation per bind
    private val typefaceNormal: android.graphics.Typeface = android.graphics.Typeface.DEFAULT
    private val typefaceItalic: android.graphics.Typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)

    // Cache last known media sizes to keep bubble size stable during scroll.
    private val mediaSizes = mutableMapOf<String, ChatMediaLayoutSizing.MediaSize>()
    private val mediaGroupTotalSizes = mutableMapOf<String, Long>()
    private val persistedResolvedMediaIds = mutableSetOf<String>()

    /**
     * Cache of resolved local file paths per "${chatId}:${messageId}:${type}" so we don't
     * hit `File.exists()` + `File.length()` syscalls on every rebind during scroll. The
     * cached value is `Optional<String?>` (null marker = "no local file"). Invalidated by
     * MEDIA_UPDATED payload so download completion still flips the path through.
     */
    private val resolvedLocalPathCache = HashMap<String, String?>(64)
    private val resolvedLocalPathCacheMissed = HashSet<String>(64)

    internal fun cachedLocalFilePath(chatId: String, messageId: String, type: MessageType): String? {
        val resolver = mediaLocalFileResolver ?: return null
        val key = "$chatId:$messageId:${type.name}"
        if (resolvedLocalPathCacheMissed.contains(key)) return null
        val cached = resolvedLocalPathCache[key]
        if (cached != null) return cached
        if (resolvedLocalPathCache.containsKey(key)) return null
        val resolved = resolver.getLocalFilePath(chatId, messageId, type)
        if (resolved == null) {
            resolvedLocalPathCacheMissed.add(key)
        } else {
            resolvedLocalPathCache[key] = resolved
            resolvedLocalPathCacheMissed.remove(key)
        }
        return resolved
    }

    internal fun invalidateLocalFilePathCache(chatId: String, messageId: String, type: MessageType) {
        val key = "$chatId:$messageId:${type.name}"
        resolvedLocalPathCache.remove(key)
        resolvedLocalPathCacheMissed.remove(key)
    }

    /**
     * Pre-calculate and cache media heights for a batch of messages so that the very first
     * onBind for each media item already has dimensionally stable layout params.
     * Call this from the Activity before submitting the prefill list.
     */
    fun preCacheMediaHeights(messages: List<Message>) {
        // Use system display metrics directly so density is always correct regardless of
        // whether the RecyclerView has been attached yet. This must match dpToPx() which
        // also uses Resources.getSystem().displayMetrics.density.
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        for (msg in messages) {
            if (mediaSizes.containsKey(msg.id)) continue
            when (msg.type) {
                MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF,
                MessageType.MEME, MessageType.STICKER, MessageType.KLIPY_EMOJI -> {
                    val aspect = msg.aspectRatio.coerceAtLeast(0.1f)
                    mediaSizes[msg.id] = ChatMediaLayoutSizing.singleMediaSizePx(
                        type = msg.type,
                        aspect = aspect,
                        density = density,
                        viewportWidthPx = android.content.res.Resources.getSystem().displayMetrics.widthPixels,
                        isVideoNote = msg.type == MessageType.VIDEO && msg.isVideoNote
                    )
                }
                else -> { /* No media dimensions to cache */ }
            }
        }
    }

    private fun initializeColors(context: android.content.Context) {
        if (colorsInitialized) return
        colorOtherBubble = getThemeColor(context, R.attr.glyphBubbleOther)
        colorOtherText = getThemeColor(context, R.attr.glyphBubbleOtherText)
        colorOwnBubble = getThemeColor(context, R.attr.glyphBubbleOwn)
        colorOwnText = getThemeColor(context, R.attr.glyphBubbleOwnText)
        tintOtherBubble = android.content.res.ColorStateList.valueOf(colorOtherBubble)
        tintOwnBubble = android.content.res.ColorStateList.valueOf(colorOwnBubble)
        colorPrimaryCached = getThemeColor(context, com.google.android.material.R.attr.colorPrimary)
        colorsInitialized = true
    }

    private fun applyColorAlpha(color: Int, alpha: Float): Int {
        val colorAlpha = (android.graphics.Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (colorAlpha shl 24)
    }

    var isScrolling: Boolean = false
    // True during the initial layout (before the first frame draws).
    // ViewHolders use the lightweight bind path, same as during scroll,
    // so the first frame paints in ~50ms instead of ~500ms.
    var isFirstLayout: Boolean = false

    // Unified fast-path flag: true when scrolling OR during first layout.
    // All ViewHolder bind methods check this to skip Glide loads, link
    // previews, translation labels, and reactions.
    private val isFastBind: Boolean get() = isScrolling || isFirstLayout

    // --- Scroll work instrumentation (DEBUG only) -----------------------------------------
    // Counts the expensive RecyclerView work that happens while a finger/fling is moving the
    // list. A smooth fling should show ~0 inflations and very few full binds (the off-screen
    // cache + warmed pool should absorb the demand). High inflate/full-bind counts during a
    // session pinpoint whether the stutter is "pool exhausted -> inflating mid-fling" vs
    // "binds too heavy". Read these in the chat_scroll_perf_end log line.
    @Volatile var dbgCreateCount: Int = 0
    @Volatile var dbgCreateTimeNs: Long = 0L
    @Volatile var dbgFullBindCount: Int = 0
    @Volatile var dbgFullBindTimeNs: Long = 0L
    @Volatile var dbgPartialBindCount: Int = 0
    // Per-view-type pool miss counts. Key = viewType int, value = inflate count.
    // Incremented in onCreateViewHolder whenever a new ViewHolder is created (pool miss).
    // Pool hits = fullBindDelta - createDelta for a given scroll session.
    @Volatile var dbgCreateCountByType: MutableMap<Int, Int> = mutableMapOf()

    /** Immutable snapshot of the cumulative scroll-work counters (DEBUG instrumentation). */
    data class ScrollWorkSnapshot(
        val createCount: Int,
        val createTimeNs: Long,
        val fullBindCount: Int,
        val fullBindTimeNs: Long,
        val partialBindCount: Int,
        val createCountByType: Map<Int, Int> = emptyMap()  // per-view-type pool misses
    )

    fun snapshotScrollWork(): ScrollWorkSnapshot = ScrollWorkSnapshot(
        createCount = dbgCreateCount,
        createTimeNs = dbgCreateTimeNs,
        fullBindCount = dbgFullBindCount,
        fullBindTimeNs = dbgFullBindTimeNs,
        partialBindCount = dbgPartialBindCount,
        createCountByType = dbgCreateCountByType.toMap()
    )

    // Strongest sender-side status observed for each outgoing message. Room can briefly
    // emit stale SENDING snapshots during rapid sends; visible ticks must stay monotonic.
    private val strongestOutgoingStatusByMessageId = HashMap<String, MessageStatus>()

    private fun statusRank(status: MessageStatus): Int {
        return when (status) {
            MessageStatus.FAILED -> -1
            MessageStatus.SENDING -> 0
            MessageStatus.SENT -> 1
            MessageStatus.DELIVERED -> 2
            MessageStatus.READ -> 3
            MessageStatus.PLAYED -> 4
            else -> 0
        }
    }

    private fun shouldReplaceKnownStatus(current: MessageStatus, candidate: MessageStatus): Boolean {
        if (candidate == current) return true
        if (candidate == MessageStatus.FAILED) {
            return statusRank(current) <= statusRank(MessageStatus.SENDING)
        }
        return statusRank(candidate) >= statusRank(current)
    }

    private fun rememberStrongestOutgoingStatus(message: Message): MessageStatus {
        if (message.isIncoming) return message.status
        val known = strongestOutgoingStatusByMessageId[message.id]
        if (known == null || shouldReplaceKnownStatus(known, message.status)) {
            strongestOutgoingStatusByMessageId[message.id] = message.status
            return message.status
        }
        return known
    }

    private fun withStrongestOutgoingStatus(item: ChatListItem): ChatListItem {
        if (item !is ChatListItem.MessageItem) return item
        val strongestStatus = rememberStrongestOutgoingStatus(item.message)
        return if (strongestStatus == item.message.status) {
            item
        } else {
            item.copy(message = item.message.copy(status = strongestStatus))
        }
    }

    private fun rememberStrongestOutgoingStatuses(items: List<ChatListItem>) {
        items.forEach { item ->
            if (item is ChatListItem.MessageItem) {
                rememberStrongestOutgoingStatus(item.message)
            }
        }
    }

    private var currentAudioState: AudioPlaybackState? = null
    private var incomingAvatarUrl: String? = null
    private var outgoingAvatarUrl: String? = null
    private var recyclerView: RecyclerView? = null
    private val animatedEntryIds = mutableSetOf<String>()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
        // Recreate so any future re-attach gets a live scope.
        adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.recyclerView = null
    }

    override fun onViewDetachedFromWindow(holder: BaseViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.onViewDetached()
    }

    override fun onViewAttachedToWindow(holder: BaseViewHolder) {
        super.onViewAttachedToWindow(holder)
        // Catch-all so reactions ALWAYS render as part of the bubble whenever a row becomes
        // visible — regardless of which bind path ran (prefill, diff-skip, mid-fling bind, or
        // recycle reuse). Idempotent + cheap no-op for messages without reactions.
        val attachPos = holder.bindingAdapterPosition
        if (attachPos != RecyclerView.NO_POSITION) {
            (getItem(attachPos) as? ChatListItem.MessageItem)?.let { messageItem ->
                holder.ensureReactionsChip(messageItem.message)
            }
        }
        val targetId = pendingHighlightMessageId ?: return
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val item = getItem(pos) as? ChatListItem.MessageItem ?: return
        if (item.message.id == targetId) {
            // Consume immediately so it only fires once
            pendingHighlightMessageId = null
            // Post to let the ViewHolder finish its layout pass before animating
            holder.itemView.post { holder.highlight() }
        }
    }

    fun updateAudioState(state: AudioPlaybackState?) {
        this.currentAudioState = state
        
        // Optimize: Only update visible items
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        
        // We iterate all visible items because if playing stops, we need to update the item that WAS playing too.
        // And if a new item starts playing, we need to update it.
        // Also progress updates need to be reflected.
        for (i in first..last) {
            val holder = rv.findViewHolderForAdapterPosition(i) ?: continue
            if (holder is IncomingViewHolder) {
                holder.refreshVoiceState()
            } else if (holder is OutgoingViewHolder) {
                holder.refreshVoiceState()
            }
        }
    }

    fun setUserAvatars(incomingAvatarUrl: String?, outgoingAvatarUrl: String?) {
        val changed = this.incomingAvatarUrl != incomingAvatarUrl || this.outgoingAvatarUrl != outgoingAvatarUrl
        this.incomingAvatarUrl = incomingAvatarUrl
        this.outgoingAvatarUrl = outgoingAvatarUrl
        if (changed && currentList.isNotEmpty()) {
            // Targeted update instead of full dataset invalidation to prevent flicker
            notifyVisibleItemsChanged("AVATAR_UPDATE")
        }
    }

    /**
     * Notify only visible items with a lightweight payload to avoid full-list flicker.
     * This replaces notifyDataSetChanged() for metadata-only updates (avatars, username, etc.).
     */
    internal fun notifyVisibleItemsChanged(payload: String) {
        val rv = recyclerView ?: run {
            // Fallback: If RecyclerView not attached yet, items will pick up state on next bind
            return
        }
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        // Include a buffer of items around visible range for smooth scrolling
        val start = (first - 2).coerceAtLeast(0)
        val end = (last + 2).coerceAtMost(itemCount - 1)
        notifyItemRangeChanged(start, end - start + 1, payload)
    }

    private fun shouldUpgradeMediaAfterScroll(item: ChatListItem?): Boolean {
        val message = (item as? ChatListItem.MessageItem)?.message ?: return false
        return when (message.type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.MEDIA_GROUP,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> true
            else -> false
        }
    }

    fun notifyVisibleScrollStoppedMedia(recyclerView: RecyclerView) {
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION || last < first) return

        for (position in first..last) {
            val item = currentList.getOrNull(position)
            val holder = recyclerView.findViewHolderForAdapterPosition(position) as? BaseViewHolder
            if (holder != null && item != null) {
                holder.bindSelection(item, animate = false)
            }
            if (!shouldUpgradeMediaAfterScroll(item)) continue

            if (holder != null) {
                holder.refreshMedia(withStrongestOutgoingStatus(item!!))
            } else {
                notifyItemChanged(position, "SCROLL_STOPPED")
            }
        }
    }

    private fun resolveAvatarUrlForSender(userId: String?): String? {
        if (userId.isNullOrBlank()) return incomingAvatarUrl
        if (userId == currentUserId) return outgoingAvatarUrl
        return if (isGroupChat) {
            senderAvatarResolver?.invoke(userId) ?: incomingAvatarUrl
        } else {
            incomingAvatarUrl
        }
    }

    /**
     * Listener for media download actions
     */
    interface MediaDownloadListener {
        fun onDownloadClicked(message: Message)
        fun onCancelDownloadClicked(messageId: String)
    }
    
    /**
     * Interface to resolve local file paths for media.
     */
    interface MediaLocalFileResolver {
        fun getLocalFilePath(chatId: String, messageId: String, mediaType: MessageType): String?
        fun getPlaybackUri(message: Message): String?
        fun isReadyForPlayback(message: Message): Boolean
    }

    private fun formatTime(timestamp: Long): String {
        return timeFormat.format(Date(timestamp))
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        return String.format("%d:%02d", seconds / 60, seconds % 60)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun hasExistingLocalUri(localUri: String?): Boolean {
        val candidate = localUri?.takeIf { it.isNotBlank() } ?: return false
        val path = runCatching { android.net.Uri.parse(candidate).path ?: candidate }.getOrDefault(candidate)
        return runCatching { java.io.File(path).exists() }.getOrDefault(false)
    }

    private fun resolveVideoNoteThumbnailModel(message: Message): Any? {
        val localVideoPath = mediaLocalFileResolver
            ?.getLocalFilePath(message.chatId, message.id, message.type)
            ?.takeIf { it.isNotBlank() && runCatching { java.io.File(it).exists() }.getOrDefault(false) }

        val localUri = message.localUri?.takeIf { candidate ->
            candidate.isNotBlank() && runCatching {
                val uri = Uri.parse(candidate)
                when (uri.scheme) {
                    "content" -> true
                    "file" -> java.io.File(uri.path ?: candidate).exists()
                    null -> java.io.File(candidate).exists()
                    else -> !candidate.startsWith("/")
                }
            }.getOrDefault(false)
        }

        val fallbackModel = localVideoPath
            ?: localUri
            ?: message.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: message.videoUrl?.takeIf { it.isNotBlank() }

        return MessagePreviewCacheManager.resolveMediaPreviewModel(message, fallbackModel)
    }

    private fun hasRenderableMediaSource(item: MediaItem): Boolean {
        if (hasExistingLocalUri(item.localUri)) return true
        if (item.url.isNotBlank()) return true
        if (!item.thumbnailUrl.isNullOrBlank()) return true
        return false
    }

    private fun countDownloadedMediaItems(items: List<MediaItem>): Int {
        return items.count { hasExistingLocalUri(it.localUri) }
    }

    private fun resolveGroupDownloadPercent(
        mediaItems: List<MediaItem>,
        progress: MediaProgressManager.MediaProgress?
    ): Int {
        if (mediaItems.isEmpty()) return 0
        val localPercent = ((countDownloadedMediaItems(mediaItems) * 100f) / mediaItems.size.toFloat()).toInt()
        val trackedPercent = progress
            ?.takeIf { !it.isUploading && !it.isIndeterminate }
            ?.progress
            ?.toInt()
            ?: 0
        return maxOf(localPercent, trackedPercent).coerceIn(0, 99)
    }

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    init {
        setHasStableIds(true)
        selectionManager.onSelectionChanged = { id ->
            val position = currentList.indexOfFirst { it is ChatListItem.MessageItem && it.message.id == id }
            if (position != -1) {
                notifyItemChanged(position, "SELECTION_CHANGED")
            }
        }
    }

    override fun onCurrentListChanged(previousList: MutableList<ChatListItem>, currentList: MutableList<ChatListItem>) {
        super.onCurrentListChanged(previousList, currentList)
        rememberStrongestOutgoingStatuses(currentList)
        if (previousList.isEmpty() || currentList.isEmpty()) return
        if (currentList.size <= previousList.size) return

        val insertedCount = currentList.size - previousList.size

        fun signatureMatches(offset: Int): Boolean {
            if (previousList.isEmpty()) return false
            val lastPrev = previousList.size - 1
            val lastCurrIndex = lastPrev + offset
            if (lastCurrIndex !in currentList.indices) return false

            val midPrev = lastPrev / 2
            return previousList[0].id == currentList[offset + 0].id &&
                previousList[midPrev].id == currentList[offset + midPrev].id &&
                previousList[lastPrev].id == currentList[offset + lastPrev].id
        }

        if (signatureMatches(offset = 0)) {
            val boundaryIndex = previousList.size - 1
            if (boundaryIndex in 0 until itemCount) notifyItemChanged(boundaryIndex, "GROUPING_UPDATE")
            return
        }

        if (signatureMatches(offset = insertedCount)) {
            val boundaryIndex = insertedCount
            if (boundaryIndex in 0 until itemCount) notifyItemChanged(boundaryIndex, "GROUPING_UPDATE")
        }
    }

    companion object {
        private const val MEDIA_DEBUG_ENABLED = false
        private const val MEDIA_DEBUG_TAG = "GlyphDebug"
        private val DEFAULT_MEDIA_LABELS = setOf("Photo", "Video", "GIF", "Sticker", "Meme", "photo", "video", "gif", "sticker", "meme")

        private const val VIEW_TYPE_INCOMING_TEXT = 1
        private const val VIEW_TYPE_OUTGOING_TEXT = 2
        private const val VIEW_TYPE_INCOMING_IMAGE = 3
        private const val VIEW_TYPE_OUTGOING_IMAGE = 4
        // Dead (5-6): legacy — getItemViewType() maps VIDEO to IMAGE types above.
        // Kept to avoid renumbering; never returned or inflating.
        private const val VIEW_TYPE_INCOMING_VIDEO = 5
        private const val VIEW_TYPE_OUTGOING_VIDEO = 6
        private const val VIEW_TYPE_INCOMING_AUDIO = 7
        private const val VIEW_TYPE_OUTGOING_AUDIO = 8
        private const val VIEW_TYPE_INCOMING_MEDIA_GROUP = 9
        private const val VIEW_TYPE_OUTGOING_MEDIA_GROUP = 10
        private const val VIEW_TYPE_INCOMING_CONTACT = 11
        private const val VIEW_TYPE_OUTGOING_CONTACT = 12
        private const val VIEW_TYPE_DATE_HEADER = 13
        private const val VIEW_TYPE_TYPING_INDICATOR = 14
        // Dead (15-16): legacy — getItemViewType() maps STICKER/KLIPY_EMOJI to IMAGE types above.
        // Kept to avoid renumbering; never returned or inflating.
        private const val VIEW_TYPE_INCOMING_STICKER = 15
        private const val VIEW_TYPE_OUTGOING_STICKER = 16
        private const val VIEW_TYPE_INCOMING_VIDEO_NOTE = 17
        private const val VIEW_TYPE_OUTGOING_VIDEO_NOTE = 18
        private const val VIEW_TYPE_INCOMING_DOCUMENT = 19
        private const val VIEW_TYPE_OUTGOING_DOCUMENT = 20
        private const val VIEW_TYPE_GROUP_INTRO = 21
        
        fun getThemeColor(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ChatListItem.MessageItem -> {
                val msg = item.message
                
                // CRITICAL: Treat deleted-for-all messages as TEXT messages to show placeholder
                if (msg.isDeletedForAll) {
                    return if (msg.isIncoming) VIEW_TYPE_INCOMING_TEXT else VIEW_TYPE_OUTGOING_TEXT
                }

                if (msg.isIncoming) {
                    when (msg.type) {
                        MessageType.TEXT, MessageType.STATUS_REPLY, MessageType.SYSTEM -> VIEW_TYPE_INCOMING_TEXT
                        MessageType.IMAGE -> VIEW_TYPE_INCOMING_IMAGE
                        MessageType.VIDEO -> if (msg.isVideoNote) VIEW_TYPE_INCOMING_VIDEO_NOTE else VIEW_TYPE_INCOMING_IMAGE
                        MessageType.AUDIO -> VIEW_TYPE_INCOMING_AUDIO
                        MessageType.CONTACT -> VIEW_TYPE_INCOMING_CONTACT
                        MessageType.MEDIA_GROUP -> VIEW_TYPE_INCOMING_MEDIA_GROUP
                        MessageType.GIF, MessageType.MEME -> VIEW_TYPE_INCOMING_IMAGE
                        MessageType.STICKER, MessageType.KLIPY_EMOJI -> VIEW_TYPE_INCOMING_IMAGE
                        MessageType.DOCUMENT -> VIEW_TYPE_INCOMING_DOCUMENT
                    }
                } else {
                    when (msg.type) {
                        MessageType.TEXT, MessageType.STATUS_REPLY, MessageType.SYSTEM -> VIEW_TYPE_OUTGOING_TEXT
                        MessageType.IMAGE -> VIEW_TYPE_OUTGOING_IMAGE
                        MessageType.VIDEO -> if (msg.isVideoNote) VIEW_TYPE_OUTGOING_VIDEO_NOTE else VIEW_TYPE_OUTGOING_IMAGE
                        MessageType.AUDIO -> VIEW_TYPE_OUTGOING_AUDIO
                        MessageType.CONTACT -> VIEW_TYPE_OUTGOING_CONTACT
                        MessageType.MEDIA_GROUP -> VIEW_TYPE_OUTGOING_MEDIA_GROUP
                        MessageType.GIF, MessageType.MEME -> VIEW_TYPE_OUTGOING_IMAGE
                        MessageType.STICKER, MessageType.KLIPY_EMOJI -> VIEW_TYPE_OUTGOING_IMAGE
                        MessageType.DOCUMENT -> VIEW_TYPE_OUTGOING_DOCUMENT
                    }
                }
            }
            is ChatListItem.GroupIntroItem -> VIEW_TYPE_GROUP_INTRO
            is ChatListItem.DateHeader -> VIEW_TYPE_DATE_HEADER
            is ChatListItem.TypingIndicator -> VIEW_TYPE_TYPING_INDICATOR
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    // ── ViewHolder pre-inflation cache ──────────────────────────────────────────
    // Telegram's ChatMessageCell is created programmatically (new ChatMessageCell(ctx)),
    // avoiding XML inflation entirely. We can't do that without a rewrite, but we CAN
    // pre-inflate ViewHolders into a cache so onCreateViewHolder returns them instantly.
    // This is more reliable than RecycledViewPool.putRecycledView() which may silently
    // fail if the pool caps aren't configured or the VH isn't in a valid state.
    private val preWarmedViewHolders = android.util.SparseArray<ArrayDeque<BaseViewHolder>>()

    /**
     * Pre-inflate [count] ViewHolders of [viewType] into the internal cache. These are
     * returned instantly by [onCreateViewHolder], bypassing XML inflation entirely.
     * Must be called on the main thread before [submitList].
     */
    fun preInflateViewHolders(parent: RecyclerView, viewType: Int, count: Int) {
        val deque = preWarmedViewHolders.get(viewType) ?: run {
            val d = ArrayDeque<BaseViewHolder>(count)
            preWarmedViewHolders.put(viewType, d)
            d
        }
        repeat(count) {
            try {
                val holder = createViewHolderInternal(parent, viewType)
                // Detach the itemView so RecyclerView can attach it properly later
                (holder.itemView.parent as? ViewGroup)?.removeView(holder.itemView)
                deque.addLast(holder)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        // Check pre-warmed cache first — instant return, zero XML inflation
        val deque = preWarmedViewHolders.get(viewType)
        if (deque != null && deque.isNotEmpty()) {
            val cached = deque.removeFirst()
            if (BuildConfig.DEBUG) {
                dbgCreateCount += 1  // still counts as a "create" but was instant
                dbgCreateCountByType[viewType] = (dbgCreateCountByType[viewType] ?: 0) + 1
            }
            return cached
        }
        val createStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val holder = createViewHolderInternal(parent, viewType)
        if (BuildConfig.DEBUG) {
            dbgCreateCount += 1
            dbgCreateTimeNs += System.nanoTime() - createStartNs
            dbgCreateCountByType[viewType] = (dbgCreateCountByType[viewType] ?: 0) + 1
            if (isFastBind) {
                Log.w(
                    "ChatPerfDebug",
                    "INFLATE during scroll viewType=$viewType (cache/pool exhausted) totalCreates=$dbgCreateCount"
                )
            }
        }
        return holder
    }

    /** Returns the number of pre-warmed ViewHolders remaining for [viewType]. */
    fun preWarmedCount(viewType: Int): Int = preWarmedViewHolders.get(viewType)?.size ?: 0

    /** Peeks at a pre-warmed ViewHolder without removing it from the deque. */
    fun preWarmedViewHolder(viewType: Int): BaseViewHolder? =
        preWarmedViewHolders.get(viewType)?.firstOrNull()

    private fun createViewHolderInternal(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_INCOMING_TEXT -> {
                val binding = ItemMessageIncomingTextBinding.inflate(inflater, parent, false)
                IncomingTextViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_TEXT -> {
                val binding = ItemMessageOutgoingTextBinding.inflate(inflater, parent, false)
                OutgoingTextViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_IMAGE -> {
                val binding = ItemMessageIncomingMediaBinding.inflate(inflater, parent, false)
                IncomingMediaViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_IMAGE -> {
                val binding = ItemMessageOutgoingMediaBinding.inflate(inflater, parent, false)
                OutgoingMediaViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_AUDIO -> {
                val binding = ItemMessageIncomingBinding.inflate(inflater, parent, false)
                IncomingViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_AUDIO -> {
                val binding = ItemMessageOutgoingBinding.inflate(inflater, parent, false)
                OutgoingViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_CONTACT -> {
                val binding = ItemMessageIncomingContactBinding.inflate(inflater, parent, false)
                IncomingContactViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_CONTACT -> {
                val binding = ItemMessageOutgoingContactBinding.inflate(inflater, parent, false)
                OutgoingContactViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_MEDIA_GROUP -> {
                val binding = ItemMessageIncomingCollageBinding.inflate(inflater, parent, false)
                IncomingCollageViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_MEDIA_GROUP -> {
                val binding = ItemMessageOutgoingCollageBinding.inflate(inflater, parent, false)
                OutgoingCollageViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_VIDEO_NOTE -> {
                val binding = ItemMessageIncomingVideoNoteBinding.inflate(inflater, parent, false)
                IncomingVideoNoteViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_VIDEO_NOTE -> {
                val binding = ItemMessageOutgoingVideoNoteBinding.inflate(inflater, parent, false)
                OutgoingVideoNoteViewHolder(binding)
            }
            VIEW_TYPE_INCOMING_DOCUMENT -> {
                val binding = ItemMessageIncomingDocumentBinding.inflate(inflater, parent, false)
                IncomingDocumentViewHolder(binding)
            }
            VIEW_TYPE_OUTGOING_DOCUMENT -> {
                val binding = ItemMessageOutgoingDocumentBinding.inflate(inflater, parent, false)
                OutgoingDocumentViewHolder(binding)
            }
            VIEW_TYPE_DATE_HEADER -> {
                val binding = ItemDateHeaderBinding.inflate(inflater, parent, false)
                DateHeaderViewHolder(binding)
            }
            VIEW_TYPE_GROUP_INTRO -> {
                val binding = ItemGroupIntroCardBinding.inflate(inflater, parent, false)
                GroupIntroViewHolder(binding)
            }
            VIEW_TYPE_TYPING_INDICATOR -> {
                val binding = ItemTypingIndicatorBinding.inflate(inflater, parent, false)
                TypingIndicatorViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val bindStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        resetSwipeState(holder)
        val item = withStrongestOutgoingStatus(getItem(position))
        holder.bind(item, position)
        applyGroupSenderName(holder.itemView, item)
        if (BuildConfig.DEBUG) {
            dbgFullBindCount += 1
            dbgFullBindTimeNs += System.nanoTime() - bindStartNs
        }
        if (dbgImeAnimating) Log.d("ImeFlashTrace", "FULL bind pos=$position type=${item::class.simpleName} DURING ime anim")
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int, payloads: MutableList<Any>) {
        if (BuildConfig.DEBUG && payloads.isNotEmpty()) dbgPartialBindCount += 1
        resetSwipeState(holder)
        if (payloads.isNotEmpty()) {
            val shouldHighlight = payloads.contains("HIGHLIGHT")
            val hasOtherPayloads = payloads.any { it != "HIGHLIGHT" }

            if (hasOtherPayloads) {
                if (payloads.contains("AVATAR_UPDATE")) {
                    holder.refreshAvatar(getItem(position))
                    if (shouldHighlight) holder.itemView.post { holder.highlight() }
                    return
                } else if (payloads.contains("STATUS_OVERRIDE")) {
                    // Direct status update — bind with override status, bypassing DiffUtil timing.
                    holder.bind(withStrongestOutgoingStatus(getItem(position)), position, skipImageLoad = true)
                } else if (payloads.contains("NO_IMAGE_RELOAD")) {
                    holder.bind(withStrongestOutgoingStatus(getItem(position)), position, skipImageLoad = true)
                } else if (payloads.contains("GROUPING_UPDATE")) {
                    holder.updateGrouping(position)
                } else if (payloads.contains("SELECTION_CHANGED")) {
                    holder.bindSelection(getItem(position), animate = true)
                } else if (payloads.contains("PROGRESS_UPDATE")) {
                    holder.updateProgress(getItem(position))
                } else if (payloads.contains("SCROLL_STOPPED")) {
                    val item = withStrongestOutgoingStatus(getItem(position))
                    when (holder) {
                        is IncomingMediaViewHolder,
                        is OutgoingMediaViewHolder,
                        is IncomingCollageViewHolder,
                        is OutgoingCollageViewHolder,
                        is IncomingVideoNoteViewHolder,
                        is OutgoingVideoNoteViewHolder -> holder.refreshMedia(item)
                        else -> Unit
                    }
                } else if (payloads.contains("MEDIA_UPDATED")) {
                    // Local file may have been downloaded/changed — drop cached path lookup.
                    val item = getItem(position)
                    if (item is ChatListItem.MessageItem) {
                        invalidateLocalFilePathCache(item.message.chatId, item.message.id, item.message.type)
                    }
                    holder.refreshMedia(item)
                } else if (payloads.contains("TRANSLATION_CHANGED")) {
                    holder.updateTranslation(getItem(position))
                } else if (payloads.contains("TYPING_UPDATE")) {
                    if (holder is TypingIndicatorViewHolder) {
                        if (animateTypingPayloadUpdates) {
                            holder.bindLiveUpdate(getItem(position), position, skipImageLoad = true)
                        } else {
                            holder.bind(getItem(position), position, skipImageLoad = true)
                        }
                    } else {
                        holder.bind(withStrongestOutgoingStatus(getItem(position)), position, skipImageLoad = true)
                    }
                } else if (payloads.contains("REACTIONS_UPDATE")) {
                    // Reactions changed — re-bind only the chip + click listeners (cheap).
                    holder.bindSelection(getItem(position), animate = false)
                } else if (payloads.contains("GROUP_SENDER")) {
                    holder.refreshAvatar(getItem(position))
                    applyGroupSenderName(holder.itemView, getItem(position))
                } else if (payloads.contains("TEXT_HEIGHT_UPDATE")) {
                    // Background precomputation finished — apply fixed heights to
                    // visible text bubbles so subsequent scroll binds skip measurement.
                    holder.applyTextHeight(getItem(position))
                } else {
                    super.onBindViewHolder(holder, position, payloads)
                    applyGroupSenderName(holder.itemView, getItem(position))
                }
            }

            // Always apply highlight if requested, post to ensure view is ready after other processing
            if (shouldHighlight) {
                if (hasOtherPayloads) {
                    holder.itemView.post { holder.highlight() }
                } else {
                    holder.highlight()
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
            applyGroupSenderName(holder.itemView, getItem(position))
        }
        if (dbgImeAnimating) Log.d("ImeFlashTrace", "PARTIAL bind pos=$position payloads=$payloads DURING ime anim")
    }

    // TEMP DIAGNOSTIC (ime flicker): set true by ChatActivity for the IME animation window.
    @Volatile var dbgImeAnimating: Boolean = false

    override fun submitList(list: List<ChatListItem>?) {
        Log.d("ImeFlashTrace", "submitList(size=${list?.size}) imeAnimating=$dbgImeAnimating")
        super.submitList(list)
    }

    override fun submitList(list: List<ChatListItem>?, commitCallback: Runnable?) {
        Log.d("ImeFlashTrace", "submitList(size=${list?.size}, cb) imeAnimating=$dbgImeAnimating")
        super.submitList(list, commitCallback)
    }

    /**
     * Phase 6: show "Sender Name" attribution above incoming bubbles when [isGroupChat]
     * is true and the resolver returns a non-blank name. Outgoing bubbles + non-message
     * rows (date headers, typing indicator) hide the line. Safe to call against any
     * itemView — the lookup is nullable.
     */
    private fun applyGroupSenderName(itemView: View, item: ChatListItem) {
        // 1:1 chats never show the sender line — skip the per-bind findViewById entirely.
        if (!isGroupChat) return
        val tv = itemView.findViewById<android.widget.TextView?>(R.id.tvGroupSenderName) ?: return
        if (item !is ChatListItem.MessageItem) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            return
        }
        val msg = item.message
        if (!msg.isIncoming || msg.type == MessageType.SYSTEM) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            return
        }
        val name = senderNameResolver?.invoke(msg.senderId)
        if (name.isNullOrBlank()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            return
        }
        if (tv.text != name) tv.text = name
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }
    
    fun highlightMessage(messageId: String) {
        val position = currentList.indexOfFirst { 
            it is ChatListItem.MessageItem && it.message.id == messageId 
        }
        if (position != -1) {
            notifyItemChanged(position, "HIGHLIGHT")
        }
    }

    /**
     * Schedule a highlight that fires when the target ViewHolder is attached to the window.
     * Call this BEFORE initiating a scroll — the highlight triggers automatically once the
     * message is on-screen, regardless of scroll type or timing.
     */
    fun schedulePendingHighlight(messageId: String) {
        pendingHighlightMessageId = messageId
    }

    fun notifyProgressChanged(messageId: String, recyclerView: RecyclerView? = null) {
        // Optimization: Only scan visible items to avoid O(N) list traversal on every progress tick
        if (recyclerView != null) {
            val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            if (lm != null) {
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                
                if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                    // Only iterate visible range
                    for (i in first..last) {
                        if (i in 0 until itemCount) {
                            val item = getItem(i)
                            if (item is ChatListItem.MessageItem && item.message.id == messageId) {
                                notifyItemChanged(i, "PROGRESS_UPDATE")
                                return
                            }
                        }
                    }
                }
                // If not visible, no need to notify. onBind will pick up state when scrolled into view.
                return
            }
        }

        // Fallback (slow O(N))
        val position = currentList.indexOfFirst { 
            it is ChatListItem.MessageItem && it.message.id == messageId 
        }
        if (position != -1) {
            notifyItemChanged(position, "PROGRESS_UPDATE")
        }
    }

    /**
     * Directly update the status icon for [messageId] without waiting for the Room→DiffUtil
     * pipeline. Called from ChatActivity when the repository emits a status update event.
     */
    fun notifyStatusUpdate(messageId: String, newStatus: MessageStatus) {
        val pos = currentList.indexOfFirst { it is ChatListItem.MessageItem && it.message.id == messageId }
        val listStatus = (currentList.getOrNull(pos) as? ChatListItem.MessageItem)?.message?.status
        val currentStatus = strongestOutgoingStatusByMessageId[messageId] ?: listStatus
        if (currentStatus != null && !shouldReplaceKnownStatus(currentStatus, newStatus)) {
            return
        }

        strongestOutgoingStatusByMessageId[messageId] = newStatus
        if (pos != -1) notifyItemChanged(pos, "STATUS_OVERRIDE")
    }

    override fun onViewRecycled(holder: BaseViewHolder) {
        super.onViewRecycled(holder)
        resetSwipeState(holder)
        when (holder) {
            is IncomingViewHolder -> holder.clearMediaState()
            is OutgoingViewHolder -> holder.clearMediaState()
            is IncomingMediaViewHolder -> holder.clearMediaState()
            is OutgoingMediaViewHolder -> holder.clearMediaState()
            is IncomingCollageViewHolder -> holder.clearMediaState()
            is OutgoingCollageViewHolder -> holder.clearMediaState()
            is OutgoingVideoNoteViewHolder -> holder.onViewDetached()
            is IncomingVideoNoteViewHolder -> holder.onViewDetached()
            is TypingIndicatorViewHolder -> holder.onViewDetached()
        }
    }

    private fun resetSwipeState(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        if (view.translationX == 0f && view.alpha == 1f) return
        view.animate().cancel()
        view.translationX = 0f
        view.alpha = 1f
    }

    abstract inner class BaseViewHolder(binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastSelectionVisualState: Boolean? = null

        private val forwardedLabelContainer: View? by lazy(LazyThreadSafetyMode.NONE) {
            itemView.findViewById<View>(R.id.forwardedLabelContainer)
        }
        private val forwardedLabelIcon: ImageView? by lazy(LazyThreadSafetyMode.NONE) {
            itemView.findViewById<ImageView>(R.id.ivForwardedLabel)
        }
        private val forwardedLabelText: TextView? by lazy(LazyThreadSafetyMode.NONE) {
            itemView.findViewById<TextView>(R.id.tvForwardedLabel)
        }
        private val mediaForwardButton: View? by lazy(LazyThreadSafetyMode.NONE) {
            itemView.findViewById<View>(R.id.btnForwardMessage)
        }

        abstract fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean = false)

        open fun refreshMedia(item: ChatListItem) {}

        /**
         * Lightweight avatar-only refresh. Default is no-op for non-message holders (headers, typing).
         * Message view holders override this to re-load only the avatar ImageView.
         */
        open fun refreshAvatar(item: ChatListItem) {}

        open fun onViewDetached() {}

        /**
         * Applies precomputed text height to the message TextView if available.
         * Overridden by text ViewHolders; no-op for media/audio/contact/etc.
         */
        open fun applyTextHeight(item: ChatListItem) {}

        open fun highlight() {
            val bubbleView = itemView.findViewById<View>(R.id.cardMessage) 
                ?: itemView.findViewById<View>(R.id.messageBubble)
                ?: itemView

            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val msg = item.message

            // Ensure colors are initialised (highlight can fire before first bind for recycled holders)
            initializeColors(bubbleView.context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bubbleView.background != null) {
                // High-contrast opposite-color calculation
                val baseColor = if (msg.isIncoming) colorOtherBubble else colorOwnBubble
                val r = 255 - android.graphics.Color.red(baseColor)
                val g = 255 - android.graphics.Color.green(baseColor)
                val b = 255 - android.graphics.Color.blue(baseColor)
                val oppositeColor = android.graphics.Color.rgb(r, g, b)
                val highlightColor = (oppositeColor and 0x00FFFFFF) or 0xB3000000.toInt()
                
                // Build a shaped foreground drawable that matches the bubble's corners
                // so we don't need clipToOutline (fragile across FrameLayout / LinearLayout).
                val overlayDrawable = android.graphics.drawable.GradientDrawable()
                overlayDrawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                val bg = bubbleView.background
                if (bg is android.graphics.drawable.GradientDrawable) {
                    val radii = bg.cornerRadii
                    if (radii != null) {
                        overlayDrawable.cornerRadii = radii.clone()
                    } else {
                        overlayDrawable.cornerRadius = bg.cornerRadius
                    }
                }
                overlayDrawable.setColor(android.graphics.Color.TRANSPARENT)
                bubbleView.foreground = overlayDrawable
                
                // Triple flash: 3 pulses = 6 segments, 250 ms each = 1.5 s
                val animator = android.animation.ValueAnimator.ofObject(
                    android.animation.ArgbEvaluator(), 
                    android.graphics.Color.TRANSPARENT,
                    highlightColor
                )
                animator.duration = 250
                animator.repeatCount = 5 
                animator.repeatMode = android.animation.ValueAnimator.REVERSE
                animator.interpolator = android.view.animation.LinearInterpolator()
                
                animator.addUpdateListener { 
                    overlayDrawable.setColor(it.animatedValue as Int)
                }
                
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        bubbleView.foreground = null
                    }
                })
                animator.start()
            } else {
                // Fallback: Triple scale pulse
                 bubbleView.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(250)
                    .withEndAction {
                         bubbleView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(250)
                            .withEndAction {
                                // Repeat for 2nd time
                                bubbleView.animate()
                                    .scaleX(1.1f)
                                    .scaleY(1.1f)
                                    .setDuration(250)
                                    .withEndAction {
                                        bubbleView.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(250)
                                            .start()
                                    }
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }

        protected fun setVisibility(view: View, visibility: Int) {
            if (view.visibility != visibility) {
                view.visibility = visibility
            }
        }

        protected fun bindForwardedLabel(message: Message, isIncoming: Boolean) {
            val container = forwardedLabelContainer ?: return
            if (!message.isForwarded) {
                setVisibility(container, View.GONE)
                return
            }

            val labelColor = if (isIncoming) {
                0xFF667781.toInt()
            } else {
                applyColorAlpha(colorOwnText, 0.70f)
            }
            forwardedLabelText?.apply {
                text = "Forwarded"
                setTextColor(labelColor)
            }
            forwardedLabelIcon?.setColorFilter(labelColor)
            setVisibility(container, View.VISIBLE)
        }

        protected fun bindMediaForwardButton(message: Message) {
            val button = mediaForwardButton ?: return
            if (message.isDeletedForAll || message.chatId.isBlank()) {
                setVisibility(button, View.GONE)
                button.setOnClickListener(null)
                return
            }

            setVisibility(button, View.VISIBLE)
            button.setOnClickListener {
                val context = itemView.context
                val payloadToken = ForwardMessageCache.put(message.chatId, listOf(message))
                context.startActivity(
                    ForwardSelectionActivity.createIntent(
                        context = context,
                        sourceChatId = message.chatId,
                        messageIds = arrayListOf(message.id),
                        payloadToken = payloadToken
                    )
                )
            }
        }

        open fun updateGrouping(position: Int) {}
        
        open fun updateProgress(item: ChatListItem) {}

        open fun updateTranslation(item: ChatListItem) {}
        
        internal fun bindSelection(item: ChatListItem, animate: Boolean = false) {
            if (item is ChatListItem.MessageItem) {
                if (isFastBind) {
                    // Reaction chips must render reliably even when a row is bound mid-fling.
                    // They are part of the already-loaded message model (no fetch) and are rare
                    // + lightweight (a single TextView), so always create/lay them out — deferring
                    // them to a post-scroll pass made them flicker or vanish until the next rebind.
                    bindReactionsChip(item.message, allowCreateAndLayout = true)

                    val isSelected = selectionManager.isSelected(item.message.id)
                    updateSelectionVisuals(isSelected, animate = false)

                    itemView.setOnLongClickListener {
                        handleBubbleLongPress(item.message.id, itemView)
                        true
                    }
                    itemView.setOnClickListener {
                        if (selectionManager.hasSelection()) {
                            selectionManager.toggleSelection(item.message.id)
                        } else if (!item.message.isDeletedForAll) {
                            onMessageClick(item)
                        }
                    }
                    return
                }

                // Render / refresh the reactions chip for this message (no-op if there are none).
                bindReactionsChip(item.message)

                // Disabled interactions for deleted messages
                if (item.message.isDeletedForAll) {
                    // Allow selection toggle on long click
                    itemView.setOnLongClickListener {
                        selectionManager.toggleSelection(item.message.id)
                        true
                    }
                    // Allow selection toggle on click ONLY if selection mode is active
                    itemView.setOnClickListener {
                        if (selectionManager.hasSelection()) {
                            selectionManager.toggleSelection(item.message.id)
                        } 
                    }
                    
                    val isSelected = selectionManager.isSelected(item.message.id)
                    updateSelectionVisuals(isSelected, animate = animate)
                    return
                }

                val isSelected = selectionManager.isSelected(item.message.id)
                updateSelectionVisuals(isSelected, animate = animate)

                // Record where the finger landed so handleBubbleLongPress can pass the
                // exact screen-Y to the popup and show it near the touch point.
                // We set this on BOTH itemView AND the anchor bubble view because child
                // views (messageBubble, cardMessage, videoNoteContainer, etc.) are
                // clickable and consume ACTION_DOWN, which prevents itemView's touch
                // listener from ever firing.
                val touchRecorder = View.OnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_MOVE) {
                        lastTouchYScreen = event.rawY
                    }
                    // Return false so click / long-click listeners still fire normally.
                    false
                }
                itemView.setOnTouchListener(touchRecorder)
                val bubbleView = anchorForBubble(itemView)
                if (bubbleView !== itemView) {
                    bubbleView.setOnTouchListener(touchRecorder)
                }

                itemView.setOnLongClickListener {
                    // WhatsApp-style: long press always toggles selection AND, when the
                    // resulting selection is exactly one message, surfaces the reaction
                    // bar above that bubble. Multi-select hides the reaction bar.
                    handleBubbleLongPress(item.message.id, itemView)
                    true
                }
                
                itemView.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(item.message.id)
                    } else {
                        onMessageClick(item)
                    }
                }
            } else {
                clearReactionsChipIfPresent()
                clearTouchRecorder()
                itemView.setOnLongClickListener(null)
                itemView.setOnClickListener(null)
                updateSelectionVisuals(false, animate = false)
            }
        }

        private fun clearTouchRecorder() {
            itemView.setOnTouchListener(null)
            val bubbleView = anchorForBubble(itemView)
            if (bubbleView !== itemView) {
                bubbleView.setOnTouchListener(null)
            }
        }

        private fun clearReactionsChipIfPresent() {
            val root = itemView as? android.view.ViewGroup ?: return
            val existing = root.findViewById<TextView>(R.id.glyph_reaction_chip) ?: return
            root.removeView(existing)
            // Clear any extra bottom padding that was reserved for the chip.
            if (root.paddingBottom > 0) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            }
        }

        /**
         * Robustly (re)creates this row's reaction chip as part of the bubble. Safe to call from
         * any path (bind, attach, recycle reuse) — idempotent: reuses an existing chip and is a
         * cheap no-op when the message has no reactions. This is the catch-all that guarantees
         * reactions render reliably every time a row becomes visible.
         */
        internal fun ensureReactionsChip(message: Message) {
            bindReactionsChip(message, allowCreateAndLayout = true)
        }

        private fun bindReactionsChip(message: Message, allowCreateAndLayout: Boolean = true) {
            // Accept any ViewGroup root — ConstraintLayout (text/audio/document/contact)
            // AND FrameLayout (media, video notes, collage). The previous code cast to
            // ConstraintLayout and returned early, silently skipping every FrameLayout row.
            val root = itemView as? android.view.ViewGroup ?: return

            // Find the bubble anchor in priority order:
            //   cardMessage  → text, audio, document, contact holders
            //   messageBubble → media (image/gif/sticker/video) holders
            //   videoNoteContainer → circular video note holders
            val anchor = root.findViewById<View>(R.id.cardMessage)
                ?: root.findViewById<View>(R.id.messageBubble)
                ?: root.findViewById<View>(R.id.videoNoteContainer)
                ?: return

            // Ensure every parent in the chain can paint the chip outside its own bounds.
            root.clipChildren = false
            root.clipToPadding = false
            (anchor as? android.view.ViewGroup)?.clipChildren = false
            (anchor as? android.view.ViewGroup)?.clipToPadding = false

            val existing = root.findViewById<TextView>(R.id.glyph_reaction_chip)
            if (message.reactions.isEmpty()) {
                if (existing != null) root.removeView(existing)
                // Remove any extra bottom padding that was reserved for the chip so the row
                // shrinks back to its natural bubble-to-bubble spacing.
                if (root.paddingBottom > 0) {
                    root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
                }
                return
            }

            if (!allowCreateAndLayout && existing == null) {
                return
            }

            val chip = existing ?: TextView(root.context).apply {
                id = R.id.glyph_reaction_chip
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                elevation = dpToPx(3f).toFloat()
            }
            chip.setOnClickListener { onReactionChipClick?.invoke(message) }

            // Aggregate emojis: order of first appearance, capped at 3 distinct with total count.
            val grouped = LinkedHashMap<String, Int>()
            for ((_, emoji) in message.reactions) {
                grouped[emoji] = (grouped[emoji] ?: 0) + 1
            }
            val total = message.reactions.size
            val isSingle = grouped.size == 1 && total == 1
            val emojiSep = "\u2009"   // THIN SPACE between adjacent emojis
            val countSep = "  "
            val display = if (grouped.size <= 3) {
                if (total > 1) grouped.keys.joinToString(emojiSep) + countSep + total
                else grouped.keys.first()
            } else {
                grouped.keys.take(3).joinToString(emojiSep) + countSep + total
            }
            chip.text = display

            val bg = if (isFastBind && chip.background is android.graphics.drawable.GradientDrawable) {
                // During scroll, mutate the existing drawable instead of allocating a new one.
                // A new GradientDrawable allocation + native paint setup costs ~2-4ms per bind;
                // reusing the existing background cuts that to near-zero. The animation gap
                // (old color → new color) is invisible mid-fling because rows blur past.
                (chip.background as android.graphics.drawable.GradientDrawable).apply {
                    shape = if (isSingle) android.graphics.drawable.GradientDrawable.OVAL
                            else android.graphics.drawable.GradientDrawable.RECTANGLE
                    if (!isSingle) setCornerRadius(dpToPx(16f).toFloat())
                    setColor(android.graphics.Color.parseColor("#2A2A2A"))
                    setStroke(dpToPx(1f).coerceAtLeast(1), android.graphics.Color.parseColor("#33FFFFFF"))
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    shape = if (isSingle) android.graphics.drawable.GradientDrawable.OVAL
                            else android.graphics.drawable.GradientDrawable.RECTANGLE
                    if (!isSingle) cornerRadius = dpToPx(16f).toFloat()
                    setColor(android.graphics.Color.parseColor("#2A2A2A"))
                    setStroke(dpToPx(1f).coerceAtLeast(1), android.graphics.Color.parseColor("#33FFFFFF"))
                }
            }
            chip.background = bg
            if (isSingle) {
                chip.setPadding(0, 0, 0, 0)
            } else {
                chip.setPadding(dpToPx(10f), dpToPx(3f), dpToPx(10f), dpToPx(3f))
            }

            val isCL = root is androidx.constraintlayout.widget.ConstraintLayout

            // Clear any extra bottom padding — the chip extends the row naturally via
            // its ConstraintLayout constraints (or translation for FrameLayout).
            if (root.paddingBottom > 0) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            }

            if (existing == null) {
                if (isCL) {
                    // ConstraintLayout: chip tucks under the bubble with a 4dp overlap.
                    // No bottom constraint — ConstraintLayout wrap_content naturally
                    // extends to the lowest child edge, which is the chip's bottom.
                    val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
                        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topToBottom = anchor.id
                        topMargin = -dpToPx(4f)
                        if (message.isIncoming) {
                            startToStart = anchor.id
                            marginStart = dpToPx(10f)
                        } else {
                            endToEnd = anchor.id
                            marginEnd = dpToPx(10f)
                        }
                    }
                    root.addView(chip, lp)
                } else {
                    // FrameLayout / other roots: add chip at top-start (0,0) then translate it
                    // into the correct position once the anchor is measured and laid out.
                    val lp = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    root.addView(chip, lp)
                    placeChipBelowAnchor(chip, root, anchor, message.isIncoming)
                }
            } else if (!isCL) {
                // Existing chip in a non-CL root — re-position in case the row was recycled
                // to a different message or the anchor dimensions changed.
                placeChipBelowAnchor(chip, root, anchor, message.isIncoming)
            } else {
                // Existing chip in a ConstraintLayout — ensure constraints are correct
                // in case the row was recycled from a different layout or the XML-defined
                // margins need updating.
                val lp = chip.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                if (lp != null) {
                    var updated = false
                    val targetTopMargin = -dpToPx(4f)
                    if (lp.topMargin != targetTopMargin) {
                        lp.topMargin = targetTopMargin
                        updated = true
                    }
                    // Remove any bottom constraint that could create a gap.
                    if (lp.bottomToBottom != -1) {
                        lp.bottomToBottom = -1
                        lp.bottomMargin = 0
                        updated = true
                    }
                    if (message.isIncoming && lp.marginStart != dpToPx(10f)) {
                        lp.marginStart = dpToPx(10f)
                        updated = true
                    } else if (!message.isIncoming && lp.marginEnd != dpToPx(10f)) {
                        lp.marginEnd = dpToPx(10f)
                        updated = true
                    }
                    if (updated) chip.layoutParams = lp
                }
            }

            // Enforce the correct chip size (strict 1:1 circle vs. pill).
            if (isCL) {
                val lp = chip.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
                val targetSize = if (isSingle) dpToPx(32f)
                                 else androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
                if (lp.width != targetSize || lp.height != targetSize) {
                    lp.width = targetSize
                    lp.height = targetSize
                    chip.layoutParams = lp
                }
            } else {
                if (isSingle) {
                    val size = dpToPx(32f)
                    val lp = chip.layoutParams
                    if (lp.width != size || lp.height != size) {
                        lp.width = size
                        lp.height = size
                        chip.layoutParams = lp
                    }
                }
            }
        }

        /**
         * Positions a reaction chip (already added to [root]) directly below [anchor]
         * using translationX/Y.  Must be called after [root] has been laid out so that
         * [anchor]'s coordinates relative to [root] are stable.  Safe to re-call on
         * subsequent rebinds for the same chip view.
         */
        private fun placeChipBelowAnchor(
            chip: TextView,
            root: android.view.ViewGroup,
            anchor: View,
            isIncoming: Boolean
        ) {
            // Use a pre-draw listener so positioning runs once the anchor is measured and
            // laid out, regardless of whether we're in the initial bind or a rebind.
            val vto = root.viewTreeObserver
            vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (chip.parent == null) {
                        // Chip was removed before layout completed — clean up listener.
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        return true
                    }
                    // Compute anchor bounds relative to root.
                    val rect = android.graphics.Rect(0, 0, anchor.width, anchor.height)
                    try {
                        root.offsetDescendantRectToMyCoords(anchor, rect)
                    } catch (_: Exception) {
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        return true
                    }
                    // Chip tucks under the bottom edge of the anchor by 4dp.
                    chip.translationY = (rect.bottom - dpToPx(4f)).toFloat()
                    // Reserve just enough bottom padding to contain the chip.
                    val neededPadding = (chip.measuredHeight - dpToPx(4f)).coerceAtLeast(0)
                    if (root.paddingBottom != neededPadding) {
                        root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, neededPadding)
                    }
                    if (isIncoming) {
                        chip.translationX = (rect.left + dpToPx(10f)).toFloat()
                    } else {
                        chip.translationX = (rect.right - dpToPx(10f) - chip.width).toFloat()
                    }
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })
        }

        private fun updateSelectionVisuals(isSelected: Boolean, animate: Boolean) {
            if (!animate && lastSelectionVisualState == isSelected && itemView.scaleX == 1.0f && itemView.scaleY == 1.0f) {
                return
            }

            val color = if (isSelected) {
                ContextCompat.getColor(itemView.context, R.color.message_selected_background)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            lastSelectionVisualState = isSelected
            itemView.setBackgroundColor(color)
            
            val scale = if (isSelected) 0.98f else 1.0f
            if (animate) {
                itemView.animate().cancel()
                itemView.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            } else {
                itemView.animate().cancel()
                itemView.scaleX = scale
                itemView.scaleY = scale
            }
        }

        open fun onMessageClick(item: ChatListItem.MessageItem) {
            // Default: show translation toolbar if message has text
            if (item.message.text.isNotBlank() && !item.message.isDeletedForAll) {
                onMessageBubbleClick?.invoke(item.message, itemView)
            }
        }

        protected fun formatTimestampWithEdited(msg: Message, timeText: String): String {
            return if (msg.isEdited) {
                itemView.context.getString(R.string.edited_timestamp, timeText)
            } else {
                timeText
            }
        }

        protected fun playAudio(url: String, context: android.content.Context) {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
            }
            try {
                mediaPlayer.setDataSource(url)
                mediaPlayer.prepareAsync()
                mediaPlayer.setOnPreparedListener { 
                    it.start() 
                    Toast.makeText(context, "Playing...", Toast.LENGTH_SHORT).show()
                }
                mediaPlayer.setOnCompletionListener { 
                    it.release() 
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to play audio", Toast.LENGTH_SHORT).show()
            }
        }
        
        protected fun openImageViewer(message: Message, initialPosition: Int) {
            val context = itemView.context
            val intent = MediaViewerActivity.newIntentWithMultipleMedia(
                context, 
                message.mediaItemsList, 
                initialPosition,
                message.timestamp,
                message.id,
                message.chatId
            )
            onOpenMediaViewer?.invoke(intent) ?: context.startActivity(intent)
        }
        
        protected fun loadAvatar(imageView: com.google.android.material.imageview.ShapeableImageView, userId: String) {
            val fallbackDrawable = imageView.drawable
                ?: ContextCompat.getDrawable(imageView.context, com.glyph.glyph_v3.R.drawable.ic_default_avatar)
            val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager
                .getLocalAvatarPath(userId)
                ?.takeIf { it.isNotBlank() }
            if (!localAvatarPath.isNullOrBlank()) {
                val localFile = java.io.File(localAvatarPath)
                Glide.with(imageView.context)
                    .load(localFile)
                    .signature(com.bumptech.glide.signature.ObjectKey(localFile.lastModified()))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .circleCrop()
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                    .placeholder(fallbackDrawable)
                    .dontAnimate()
                    .into(imageView)
                return
            }

            val url = resolveAvatarUrlForSender(userId)
            if (!url.isNullOrEmpty()) {
                Glide.with(imageView.context)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                    .placeholder(fallbackDrawable)
                    .dontAnimate()
                    .into(imageView)
            } else {
                imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_default_avatar)
            }
        }
        
        protected fun updateMessageStatus(imageView: android.widget.ImageView, status: com.glyph.glyph_v3.data.models.MessageStatus) {
            imageView.clearColorFilter()
            imageView.imageTintList = null
            when (status) {
                com.glyph.glyph_v3.data.models.MessageStatus.SENDING -> {
                    imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_clock)
                    imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    imageView.alpha = 0.5f
                }
                com.glyph.glyph_v3.data.models.MessageStatus.SENT -> {
                    imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_check)
                    imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    imageView.alpha = 0.5f
                }
                com.glyph.glyph_v3.data.models.MessageStatus.DELIVERED -> {
                    imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_double_check)
                    imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    imageView.alpha = 0.5f
                }
                com.glyph.glyph_v3.data.models.MessageStatus.READ,
                com.glyph.glyph_v3.data.models.MessageStatus.PLAYED -> {
                    imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_double_check_blue)
                    imageView.imageTintList = null
                    imageView.alpha = 1.0f
                }
                else -> {
                    imageView.setImageResource(com.glyph.glyph_v3.R.drawable.ic_close)
                    imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    imageView.alpha = 1.0f
                }
            }
        }
    }

    private fun applyItemTopMargin(root: View, topMarginPx: Int) {
        val lp = (root.layoutParams as? RecyclerView.LayoutParams)
            ?: RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

        if (lp.topMargin != topMarginPx || lp.bottomMargin != 0) {
            lp.topMargin = topMarginPx
            lp.bottomMargin = 0
            root.layoutParams = lp
        }
    }

    // Cache for corner shapes to prevent allocation on every bind
    private val shapeCache = java.util.concurrent.ConcurrentHashMap<Long, com.google.android.material.shape.ShapeAppearanceModel>()

    private data class BubbleCornerSizes(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float
    )

    private fun resolveBubbleCornerSizes(view: View, groupPosition: BubbleGroupPosition, isIncoming: Boolean): BubbleCornerSizes {
        val density = view.context.resources.displayMetrics.density
        val fullPx = 16f * density
        val reducedPx = 6f * density

        var topLeft = fullPx
        var topRight = fullPx
        var bottomRight = fullPx
        var bottomLeft = fullPx

        when (groupPosition) {
            BubbleGroupPosition.SINGLE -> Unit
            BubbleGroupPosition.TOP -> {
                if (isIncoming) bottomLeft = reducedPx else bottomRight = reducedPx
            }
            BubbleGroupPosition.MIDDLE -> {
                if (isIncoming) {
                    topLeft = reducedPx
                    bottomLeft = reducedPx
                } else {
                    topRight = reducedPx
                    bottomRight = reducedPx
                }
            }
            BubbleGroupPosition.BOTTOM -> {
                if (isIncoming) topLeft = reducedPx else topRight = reducedPx
            }
        }

        return BubbleCornerSizes(
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft
        )
    }

    private fun applyBubbleCorners(
        view: View,
        groupPosition: BubbleGroupPosition,
        isIncoming: Boolean,
        fullRadiusDp: Float,
        imageView: View? = null
    ) {
        val corners = resolveBubbleCornerSizes(view, groupPosition, isIncoming)
        val topLeft = corners.topLeft
        val topRight = corners.topRight
        val bottomRight = corners.bottomRight
        val bottomLeft = corners.bottomLeft
        val cornerKey = (groupPosition.ordinal.toLong() shl 50) or
            (if (isIncoming) 1L shl 48 else 0L) or
            (java.lang.Float.floatToIntBits(fullRadiusDp).toLong() and 0xFFFFFFFFL)

        if (view is MaterialCardView) {
            // Optimization: Cache shape models
            var model = shapeCache[cornerKey]
            if (model == null) {
                model = view.shapeAppearanceModel.toBuilder()
                    .setTopLeftCornerSize(topLeft)
                    .setTopRightCornerSize(topRight)
                    .setBottomRightCornerSize(bottomRight)
                    .setBottomLeftCornerSize(bottomLeft)
                    .build()
                shapeCache[cornerKey] = model!!
            }

            if (view.shapeAppearanceModel != model) {
                view.shapeAppearanceModel = model
            }
        } else {
            if (view.getTag(R.id.tag_bubble_corner_key) != cornerKey) {
                view.setTag(R.id.tag_bubble_corner_key, cornerKey)
                val background = view.background
                if (background is android.graphics.drawable.GradientDrawable) {
                     val mutable = background.mutate() as android.graphics.drawable.GradientDrawable
                     mutable.cornerRadii = floatArrayOf(
                        topLeft, topLeft,
                        topRight, topRight,
                        bottomRight, bottomRight,
                        bottomLeft, bottomLeft
                    )
                } else if (background != null) {
                     background.mutate()
                }
            }
        }

        // Sync image corners if provided
        if (imageView is ShapeableImageView) {
            // Image is inside bubble with 0.5dp padding
            val paddingPx = dpToPxF(0.5f)
            val imgTopLeft = (topLeft - paddingPx).coerceAtLeast(0f)
            val imgTopRight = (topRight - paddingPx).coerceAtLeast(0f)
            val imgBottomRight = (bottomRight - paddingPx).coerceAtLeast(0f)
            val imgBottomLeft = (bottomLeft - paddingPx).coerceAtLeast(0f)

            val imgKey = (groupPosition.ordinal.toLong() shl 50) or 
                         (if (isIncoming) 1L shl 48 else 0L) or 
                         (java.lang.Float.floatToIntBits(fullRadiusDp).toLong() and 0xFFFFFFFFL) or 
                         (1L shl 49) // Bit for "isImage"

            var model = shapeCache[imgKey]
            if (model == null) {
                model = imageView.shapeAppearanceModel.toBuilder()
                    .setTopLeftCornerSize(imgTopLeft)
                    .setTopRightCornerSize(imgTopRight)
                    .setBottomRightCornerSize(imgBottomRight)
                    .setBottomLeftCornerSize(imgBottomLeft)
                    .build()
                shapeCache[imgKey] = model!!
            }
            
            if (imageView.shapeAppearanceModel != model) {
                imageView.shapeAppearanceModel = model
            }
        } else if (imageView is CollageImageView) {
            // CollageImageView is inside bubble with 0.5dp padding
            val paddingPx = dpToPxF(0.5f)
            val imgTopLeft = (topLeft - paddingPx).coerceAtLeast(0f)
            val imgTopRight = (topRight - paddingPx).coerceAtLeast(0f)
            val imgBottomRight = (bottomRight - paddingPx).coerceAtLeast(0f)
            val imgBottomLeft = (bottomLeft - paddingPx).coerceAtLeast(0f)
            
            imageView.applyCornerRadius(imgTopLeft, imgTopRight, imgBottomRight, imgBottomLeft)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * android.content.res.Resources.getSystem().displayMetrics.density).roundToInt()
    }

    private fun dpToPxF(dp: Float): Float {
        return dp * android.content.res.Resources.getSystem().displayMetrics.density
    }

    private fun applyMediaBubbleBackground(
        messageBubble: View,
        imageView: ShapeableImageView,
        isIncoming: Boolean,
        isSticker: Boolean
    ) {
        val mode = when {
            isSticker -> if (isIncoming) 10 else 11
            isPastelTheme -> if (isIncoming) 20 else 21
            else -> if (isIncoming) 30 else 31
        }
        if (messageBubble.getTag(R.id.tag_media_background_mode) == mode) return
        messageBubble.setTag(R.id.tag_media_background_mode, mode)

        if (isSticker) {
            messageBubble.background = null
            imageView.background = null
            imageView.setTag(R.id.tag_bubble_corner_key, null)
            return
        }

        if (isPastelTheme) {
            messageBubble.setBackgroundResource(
                if (isIncoming) R.drawable.bg_pastel_bubble_incoming else R.drawable.bg_pastel_bubble_outgoing
            )
            messageBubble.backgroundTintList = null
        } else {
            messageBubble.setBackgroundResource(
                if (isIncoming) R.drawable.bg_message_incoming else R.drawable.bg_message_outgoing
            )
            messageBubble.backgroundTintList = if (isIncoming) tintOtherBubble else tintOwnBubble
        }
        imageView.setBackgroundResource(R.drawable.bg_rounded_image)
        messageBubble.setTag(R.id.tag_bubble_corner_key, null)
        imageView.setTag(R.id.tag_bubble_corner_key, null)
    }

    private fun syncLinkPreviewThumbnailCorners(
        previewCard: MaterialCardView?,
        thumbnailView: ShapeableImageView?,
        groupPosition: BubbleGroupPosition,
        isIncoming: Boolean,
        hasTopSibling: Boolean
    ) {
        if (thumbnailView == null) return

        val corners = resolveBubbleCornerSizes(thumbnailView, groupPosition, isIncoming)
        val topLeft = if (hasTopSibling) 0f else corners.topLeft
        val topRight = if (hasTopSibling) 0f else corners.topRight

        val previewShape = previewCard?.shapeAppearanceModel?.toBuilder()
            ?.setTopLeftCornerSize(topLeft)
            ?.setTopRightCornerSize(topRight)
            ?.setBottomLeftCornerSize(0f)
            ?.setBottomRightCornerSize(0f)
            ?.build()
        if (previewCard != null && previewShape != null && previewCard.shapeAppearanceModel != previewShape) {
            previewCard.shapeAppearanceModel = previewShape
        }

        val imageShape = thumbnailView.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(topLeft)
            .setTopRightCornerSize(topRight)
            .setBottomLeftCornerSize(0f)
            .setBottomRightCornerSize(0f)
            .build()

        thumbnailView.scaleType = ImageView.ScaleType.CENTER_CROP
        thumbnailView.clipToOutline = true

        if (thumbnailView.shapeAppearanceModel != imageShape) {
            thumbnailView.shapeAppearanceModel = imageShape
        }
    }



    private fun applyEmojiOnlyStyle(
        messageContainer: View,
        messageView: TextView,
        isEmojiOnly: Boolean,
        isIncoming: Boolean
    ) {
        messageView.setTag(R.id.tag_emoji_only, isEmojiOnly)
        updateBubblePaddingForEmoji(messageView, isEmojiOnly, isIncoming)
        val targetSizeSp = if (isEmojiOnly) emojiOnlyTextSizeSp else normalMessageTextSizeSp
        val targetPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            targetSizeSp,
            messageView.resources.displayMetrics
        )
        if (kotlin.math.abs(messageView.textSize - targetPx) > 0.5f) {
            messageView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, targetSizeSp)
        }

        val params = messageView.layoutParams
        if (isEmojiOnly) {
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                messageView.layoutParams = params
            }
            val targetGravity = if (isIncoming) Gravity.START else Gravity.END
            if (messageView.gravity != targetGravity) {
                messageView.gravity = targetGravity
            }
        } else {
            if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                messageView.layoutParams = params
            }
            if (messageView.gravity != Gravity.START) {
                messageView.gravity = Gravity.START
            }
        }

        if (isEmojiOnly) {
            messageContainer.background = null
        }
    }

    private fun updateBubblePaddingForEmoji(messageView: TextView, isEmojiOnly: Boolean, isIncoming: Boolean = true) {
        val bubbleLayout = findMessageBubbleLayout(messageView) ?: return
        val density = messageView.resources.displayMetrics.density
        if (isEmojiOnly) {
            val ep = (6 * density).toInt()
            if (bubbleLayout.paddingLeft != ep || bubbleLayout.paddingRight != ep ||
                bubbleLayout.paddingTop != ep || bubbleLayout.paddingBottom != ep) {
                bubbleLayout.setPaddingRelative(ep, ep, ep, ep)
            }
        } else {
            val startPx = ((if (isIncoming) 9 else 8) * density).toInt()
            val endPx   = ((if (isIncoming) 8 else 9) * density).toInt()
            val topPx    = (3 * density).toInt()
            val bottomPx = (2 * density).toInt()
            if (bubbleLayout.paddingStart != startPx || bubbleLayout.paddingEnd != endPx ||
                bubbleLayout.paddingTop != topPx || bubbleLayout.paddingBottom != bottomPx) {
                bubbleLayout.setPaddingRelative(startPx, topPx, endPx, bottomPx)
            }
        }
    }

    private fun findMessageBubbleLayout(messageView: TextView): MessageBubbleLayout? {
        var parent = messageView.parent
        while (parent is View) {
            if (parent is MessageBubbleLayout) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Binds inline translation state for text-based message bubbles.
     * Shows/hides translated text and the toggle label.
     * Performance: only touches views whose state actually changed.
     */
    private fun bindTranslationState(
        item: ChatListItem.MessageItem,
        tvMessage: TextView,
        tvTranslationLabel: TextView
    ) {
        val msg = item.message
        // Always hide the label — all toggle control is via the floating toolbar
        tvTranslationLabel.visibility = View.GONE
        tvTranslationLabel.setOnClickListener(null)

        if (msg.isDeletedForAll || msg.text.isBlank()) {
            return
        }

        when {
            item.isTranslating -> {
                // Keep original text while translating
                tvMessage.text = msg.text
            }
            item.translatedText != null && item.isShowingTranslation -> {
                // Show translated text inside the bubble
                tvMessage.text = item.translatedText
            }
            else -> {
                // Show original text
                tvMessage.text = msg.text
            }
        }
    }

    private fun bindLinkPreview(
        root: View,
        message: Message,
        tvMessage: TextView,
        allowThumbnailLoad: Boolean = true
    ): Boolean {
        // The link-preview card lives in a ViewStub and is only inflated the first time a message
        // actually needs it. Decide from the message data alone (no view work) whether a preview
        // is possible; if not, never touch the stub so the heavy MaterialCardView/ShapeableImageView
        // subtree is never constructed for the vast majority of plain text bubbles.
        val canShowPreview = !message.isDeletedForAll && message.type == MessageType.TEXT && (
            !message.linkPreviewTitle.isNullOrBlank() ||
                !message.linkPreviewDomain.isNullOrBlank() ||
                !message.thumbnailUrl.isNullOrBlank()
            )
        if (canShowPreview) {
            (root.findViewById<View?>(R.id.linkPreviewStub) as? ViewStub)?.inflate()
        }

        val previewCard = root.findViewById<View>(R.id.linkPreviewCard)
        val thumbnailView = root.findViewById<ShapeableImageView>(R.id.ivLinkPreviewThumbnail)

        fun hidePreview() {
            previewCard?.let {
                if (it.visibility != View.GONE) it.visibility = View.GONE
                it.setOnClickListener(null)
                it.setOnLongClickListener(null)
            }
            thumbnailView?.let {
                Glide.with(it).clear(it)
                it.setTag(R.id.tag_link_preview_thumbnail_url, null)
                it.visibility = View.GONE
            }
            tvMessage.visibility = View.VISIBLE
        }

        if (message.isDeletedForAll || message.type != MessageType.TEXT) {
            hidePreview()
            return false
        }

        if (previewCard == null) return false

        val hasPreviewData = !message.linkPreviewTitle.isNullOrBlank() ||
            !message.linkPreviewDomain.isNullOrBlank() ||
            !message.thumbnailUrl.isNullOrBlank()
        if (!hasPreviewData) {
            hidePreview()
            return false
        }

        val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)

        if (previewUrl.isNullOrBlank()) {
            hidePreview()
            return false
        }

        // Only perform remaining findViewByIds when we actually have a preview to show
        val titleView = root.findViewById<TextView>(R.id.tvLinkPreviewTitle)
        val descriptionView = root.findViewById<TextView>(R.id.tvLinkPreviewDescription)
        val domainView = root.findViewById<TextView>(R.id.tvLinkPreviewDomain)
        val urlView = root.findViewById<TextView>(R.id.tvLinkPreviewUrl)

        previewCard.visibility = View.VISIBLE
        previewCard.setOnClickListener {
            if (selectionManager.hasSelection()) {
                selectionManager.toggleSelection(message.id)
            } else {
                openLinkPreview(root.context, previewUrl)
            }
        }
        previewCard.setOnLongClickListener {
            handleBubbleLongPress(message.id, root)
            true
        }

        titleView?.text = normalizePreviewText(message.linkPreviewTitle) ?: previewUrl
        
        if (descriptionView != null) {
            val description = normalizePreviewText(message.linkPreviewDescription)
            if (description.isNullOrBlank()) {
                descriptionView.visibility = View.GONE
            } else {
                descriptionView.visibility = View.VISIBLE
                descriptionView.text = description
            }
        }

        val domain = normalizePreviewText(message.linkPreviewDomain) ?: Uri.parse(previewUrl).host.orEmpty()
        val siteName = normalizePreviewText(message.linkPreviewSiteName)
        domainView?.text = if (!siteName.isNullOrBlank()) {
            "$siteName • $domain"
        } else {
            domain
        }
        
        urlView?.visibility = View.GONE

        if (thumbnailView != null) {
            val existingPreviewUrl = thumbnailView.getTag(R.id.tag_link_preview_thumbnail_url) as? String
            val hasMatchingDrawable = existingPreviewUrl == previewUrl && thumbnailView.drawable != null
            val thumbnailModel = if (allowThumbnailLoad) {
                MessagePreviewCacheManager.resolveLinkPreviewModel(previewUrl, message.thumbnailUrl)
            } else {
                MessagePreviewCacheManager.getCachedLinkThumbnailPath(previewUrl)
            }

            when {
                !allowThumbnailLoad && hasMatchingDrawable -> {
                    thumbnailView.visibility = View.VISIBLE
                }
                thumbnailModel != null -> {
                    thumbnailView.visibility = View.VISIBLE
                    thumbnailView.setTag(R.id.tag_link_preview_thumbnail_url, previewUrl)
                    Glide.with(thumbnailView)
                        .load(thumbnailModel)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .centerCrop()
                        .dontAnimate()
                        .into(thumbnailView)
                }
                else -> {
                    Glide.with(thumbnailView).clear(thumbnailView)
                    thumbnailView.setTag(R.id.tag_link_preview_thumbnail_url, null)
                    thumbnailView.visibility = View.GONE
                }
            }
        }

        val messageText = tvMessage.text?.toString().orEmpty()
        val visibleMessageText = stripFirstUrl(messageText)
        if (visibleMessageText.isBlank()) {
            tvMessage.visibility = View.GONE
        } else {
            tvMessage.text = visibleMessageText
            tvMessage.visibility = View.VISIBLE
        }

        return true
    }

    private fun stripFirstUrl(text: String): String {
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return text.trim()
        val stripped = buildString {
            append(text.substring(0, matcher.start()))
            append(text.substring(matcher.end()))
        }
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizePreviewText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text
            .replace(Regex("^[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]+"), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun getLinkPreviewUrl(message: Message): String? {
        if (message.isDeletedForAll || message.type != MessageType.TEXT) return null
        val hasPreviewData = !message.linkPreviewTitle.isNullOrBlank() ||
            !message.linkPreviewDomain.isNullOrBlank() ||
            !message.thumbnailUrl.isNullOrBlank()
        if (!hasPreviewData) return null

        val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)
        return previewUrl?.takeIf { hasPreviewData }
    }

    private fun findMessageById(messageId: String?): Message? {
        if (messageId.isNullOrBlank()) return null
        return replyLookupMap[messageId]
            ?: currentList
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message }
            .firstOrNull { it.id == messageId }
    }

    private fun resolveExistingLocalUri(localUri: String?): String? {
        if (localUri.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(localUri)
            when {
                uri.scheme == "file" || localUri.startsWith("/") -> {
                    val path = uri.path ?: localUri
                    localUri.takeIf { java.io.File(path).exists() }
                }
                uri.scheme == "content" -> localUri
                else -> null
            }
        }.getOrNull()
    }

    private fun resolveReplyPreviewModel(repliedMessage: Message): Any? {
        val rawModel: Any? = when (repliedMessage.type) {
            MessageType.IMAGE -> resolveExistingLocalUri(repliedMessage.localUri) ?: repliedMessage.imageUrl
            MessageType.VIDEO -> resolveExistingLocalUri(repliedMessage.localUri) ?: repliedMessage.videoUrl ?: repliedMessage.thumbnailUrl
            MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.GIF, MessageType.MEME ->
                resolveExistingLocalUri(repliedMessage.localUri) ?: repliedMessage.imageUrl ?: repliedMessage.thumbnailUrl
            MessageType.DOCUMENT -> repliedMessage.thumbnailUrl
            MessageType.MEDIA_GROUP -> repliedMessage.mediaItemsList.firstOrNull()?.let { item ->
                resolveExistingLocalUri(item.localUri) ?: item.displayUrl.takeIf { it.isNotBlank() }
            }
            else -> null
        }
        return when (repliedMessage.type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.GIF,
            MessageType.MEME -> MessagePreviewCacheManager.resolveMediaPreviewModel(repliedMessage, rawModel)
            else -> rawModel
        }
    }

    private fun buildReplyPreviewSummary(message: Message): String {
        val previewUrl = getLinkPreviewUrl(message)
        if (previewUrl != null) {
            val title = message.linkPreviewTitle?.takeIf { it.isNotBlank() }
                ?: LinkPreviewResolver.fallback(previewUrl).title
            val domain = message.linkPreviewDomain?.takeIf { it.isNotBlank() }
                ?: Uri.parse(previewUrl).host.orEmpty().removePrefix("www.")
            return "$title\n$domain"
        }

        return when (message.type) {
            MessageType.IMAGE -> "Photo"
            MessageType.VIDEO -> "Video"
            MessageType.AUDIO -> "Audio"
            MessageType.CONTACT -> "${message.contactName ?: "Contact"}: ${message.contactPhone ?: ""}"
            MessageType.MEDIA_GROUP -> "${message.mediaItemsList.size} media items"
            MessageType.GIF -> "GIF"
            MessageType.STICKER -> "Sticker"
            MessageType.KLIPY_EMOJI -> "Emoji"
            MessageType.MEME -> "Meme"
            MessageType.DOCUMENT -> "Document"
            MessageType.TEXT -> message.text.ifBlank { "Message" }
            MessageType.STATUS_REPLY -> "Replied to status"
            MessageType.SYSTEM -> message.text.ifBlank { "System message" }
        }
    }

    private fun openLinkPreview(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeBounceIncoming(item: ChatListItem.MessageItem, bubbleView: View) {
        if (!item.shouldAnimateEntry) return
        
        // Failsafe: Only animate if the message is actually recent (last 30 seconds)
        // This prevents old messages from re-animating during scrolling or re-binds
        val now = System.currentTimeMillis()
        if (now - item.message.timestamp > 30000) return

        val id = item.message.id
        if (!animatedEntryIds.add(id)) return
        val animationStartMs = System.currentTimeMillis()
        lightweightIncomingEntryMessageIds.remove(id)
        if (BuildConfig.DEBUG || Log.isLoggable("ChatPerfDebug", Log.DEBUG)) {
            Log.d(
                "ChatPerfDebug",
                "[IncomingEntry] start id=${id.takeLast(6)} type=${item.message.type} lightweight=true"
            )
        }

        bubbleView.animate().cancel()
        bubbleView.scaleX = 1f
        bubbleView.scaleY = 1f
        bubbleView.translationY = dpToPxF(3f)
        bubbleView.alpha = 1f
        bubbleView.animate()
            .translationY(0f)
            .setDuration(90)
            .setInterpolator(LinearOutSlowInInterpolator())
            .withLayer()
            .withEndAction {
                if (BuildConfig.DEBUG || Log.isLoggable("ChatPerfDebug", Log.DEBUG)) {
                    Log.d(
                        "ChatPerfDebug",
                        "[IncomingEntry] end id=${id.takeLast(6)} mode=lightweight elapsed=${System.currentTimeMillis() - animationStartMs}ms"
                    )
                }
            }
            .start()
    }

    private fun applyBlur(imageView: ShapeableImageView, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imageView.setRenderEffect(
                if (enabled) RenderEffect.createBlurEffect(12f, 12f, Shader.TileMode.CLAMP) else null
            )
        }
    }

    private fun preserveVisibleDrawablePlaceholder(imageView: ShapeableImageView): Drawable? {
        val currentDrawable = imageView.drawable ?: return null
        if (currentDrawable is BitmapDrawable) {
            return BitmapDrawable(imageView.resources, currentDrawable.bitmap).mutate()
        }
        return currentDrawable.constantState?.newDrawable(imageView.resources)?.mutate()
            ?: currentDrawable.constantState?.newDrawable()?.mutate()
    }

    private fun isAnimatedInlineMedia(message: Message): Boolean {
        return when (message.type) {
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> true
            else -> false
        }
    }

    private fun resolveSingleMediaPreviewSource(message: Message, resolvedFullSource: Any?): Any? {
        val thumbnailFallback = message.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedFullSource
        return MessagePreviewCacheManager
            .resolveMediaPreviewModel(message, thumbnailFallback)
            ?.takeIf { it.toString().isNotBlank() }
    }

    private fun isSafeScrollingPreviewModel(
        message: Message,
        previewModel: Any?,
        resolvedFullSource: Any?
    ): Boolean {
        if (previewModel == null) return false
        val modelString = previewModel.toString()
        if (modelString.isBlank()) return false

        val fromPreviewCache = modelString.contains("/message_preview_cache/")
        if (fromPreviewCache) return true

        val explicitThumbnail = !message.thumbnailUrl.isNullOrBlank() && modelString == message.thumbnailUrl
        if (explicitThumbnail) return true

        // While scrolling, never use the exact full-source model as a "preview" fallback.
        // This avoids decoding full-size media on the critical fling path.
        return resolvedFullSource == null || previewModel != resolvedFullSource
    }

    private fun bindCachedScrollPreviewImmediately(
        imageView: ShapeableImageView,
        message: Message,
        directionLabel: String,
        previewSizePx: Int
    ): Boolean {
        val previewBitmap = MessagePreviewCacheManager.peekCachedMediaPreviewBitmap(message.id)
            ?: return false

        imageView.setImageBitmap(previewBitmap)
        applyBlur(imageView, true)
        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
            Log.d(
                "GlyphCacheDebug",
                "[BIND-SCROLL-PREVIEW-SYNC] $directionLabel msgId=${message.id.take(8)} " +
                    "size=${previewSizePx}px model=memory:${message.id.take(8)}"
            )
        }
        return true
    }

    private fun isLocalOrCachedPreviewModel(model: Any?): Boolean {
        return when (model) {
            null -> false
            is java.io.File -> model.exists() && model.length() > 0L
            is Uri -> when (model.scheme?.lowercase()) {
                "content" -> true
                "file" -> model.path?.let { java.io.File(it).exists() } == true
                else -> false
            }
            is String -> when {
                model.contains("/message_preview_cache/") -> true
                model.startsWith("/") -> java.io.File(model).exists()
                model.startsWith("file://") -> Uri.parse(model).path?.let { java.io.File(it).exists() } == true
                model.startsWith("content://") -> true
                else -> false
            }
            else -> false
        }
    }

    private fun shouldUseCacheOnlyForScrollPreview(model: Any?): Boolean {
        return !isLocalOrCachedPreviewModel(model)
    }

    private fun loadStaticMediaWithPreview(
        context: Context,
        imageView: ShapeableImageView,
        message: Message,
        resolvedImageSource: Any?,
        widthPx: Int,
        heightPx: Int,
        directionLabel: String,
        skipBlurredPreview: Boolean = false
    ) {
        val previewSource = resolveSingleMediaPreviewSource(message, resolvedImageSource)
        val existingPlaceholder = preserveVisibleDrawablePlaceholder(imageView)
        val mainRequest = Glide.with(context)
            .load(resolvedImageSource)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .fitCenter()
            .override(widthPx, heightPx)
            .dontAnimate()
            .placeholder(existingPlaceholder ?: androidx.core.content.ContextCompat.getDrawable(context, android.R.color.transparent))
            .error(R.drawable.ic_image_error)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    applyBlur(imageView, false)
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    applyBlur(imageView, false)
                    if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                        val level = if (dataSource == DataSource.MEMORY_CACHE) "BIND-HIT " else "BIND-MISS"
                        Log.d(
                            "GlyphCacheDebug",
                            "[$level] $directionLabel IMG  msgId=${message.id.take(8)} ds=$dataSource " +
                                "iv=${widthPx}x${heightPx} model=${model.toString().take(60)}"
                        )
                    }
                    maybeResolveMediaDimensionsFromDrawable(imageView, message, resource)
                    return false
                }
            })

        // When skipBlurredPreview is true (first layout / chat open), skip the low-res
        // blurred thumbnail and go straight to the full image. The blurred 96px preview
        // is useful during scroll (motion hides the blur) but looks broken on a static
        // first frame where the user expects clear images immediately.
        val requestWithPreview = if (previewSource != null && !skipBlurredPreview) {
            applyBlur(imageView, true)
            mainRequest.thumbnail(
                Glide.with(context)
                    .load(previewSource)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .fitCenter()
                    .override(96, 96)
                    .placeholder(existingPlaceholder)
                    .dontAnimate()
            )
        } else {
            applyBlur(imageView, false)
            mainRequest
        }

        requestWithPreview.into(imageView)
    }

    private fun loadScrollingMediaPreview(
        context: Context,
        imageView: ShapeableImageView,
        message: Message,
        resolvedImageSource: Any?,
        directionLabel: String,
        previewSizePx: Int
    ) {
        val placeholder = scrollPreviewPlaceholderDrawable(context, message)
        val resolvedPreviewModel = resolveSingleMediaPreviewSource(message, resolvedImageSource)
        val previewWarmSource = resolvedPreviewModel ?: resolvedImageSource
        val previewSource = resolvedPreviewModel?.takeIf {
            isSafeScrollingPreviewModel(message, it, resolvedImageSource)
        }

        Glide.with(context).clear(imageView)

        if (bindCachedScrollPreviewImmediately(imageView, message, directionLabel, previewSizePx)) {
            if (previewWarmSource != null) {
                MessagePreviewCacheManager.warmMediaPreviewAsync(
                    context.applicationContext,
                    message,
                    previewModelOverride = previewWarmSource
                )
            }
            return
        }

        if (previewSource != null) {
            applyBlur(imageView, true)
            val request = Glide.with(context)
                .load(previewSource)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .fitCenter()
                .override(previewSizePx, previewSizePx)
                .format(DecodeFormat.PREFER_RGB_565)
                .dontAnimate()
                .placeholder(placeholder)
                .error(placeholder)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        applyBlur(imageView, false)
                        return false
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        applyBlur(imageView, true)
                        return false
                    }
                })

            if (shouldUseCacheOnlyForScrollPreview(previewSource)) {
                request.onlyRetrieveFromCache(true).into(imageView)
            } else {
                request.into(imageView)
            }

            if (previewWarmSource != null) {
                MessagePreviewCacheManager.warmMediaPreviewAsync(
                    context.applicationContext,
                    message,
                    previewModelOverride = previewWarmSource
                )
            }
            if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                Log.d(
                    "GlyphCacheDebug",
                    "[BIND-SCROLL-PREVIEW-LOAD] $directionLabel msgId=${message.id.take(8)} " +
                        "cacheOnly=${shouldUseCacheOnlyForScrollPreview(previewSource)} preview=${previewSource.toString().take(60)}"
                )
            }
            return
        }

        applyBlur(imageView, false)
        imageView.setImageDrawable(placeholder)
        val warmSource = previewSource ?: previewWarmSource
        if (warmSource != null) {
            MessagePreviewCacheManager.warmMediaPreviewAsync(
                context.applicationContext,
                message,
                previewModelOverride = warmSource
            )
        }
        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
            Log.d(
                "GlyphCacheDebug",
                "[BIND-SCROLL-PREVIEW-DEFER] $directionLabel msgId=${message.id.take(8)} " +
                    "safePreview=${previewSource != null} warm=${warmSource != null}"
            )
        }
    }

    private fun cachedMediaGroupTotalSize(message: Message, mediaItems: List<MediaItem>): Long {
        mediaGroupTotalSizes[message.id]?.takeIf { it > 0L }?.let { return it }
        val totalSize = mediaItems.sumOf { it.fileSize ?: 0L }
        if (totalSize > 0L) {
            mediaGroupTotalSizes[message.id] = totalSize
        }
        return totalSize
    }

    private fun scrollPreviewPlaceholderDrawable(context: Context, message: Message): Drawable? {
        val placeholderRes = if (message.type == MessageType.VIDEO) {
            R.drawable.ic_video_placeholder
        } else {
            R.drawable.ic_image_placeholder
        }
        return ContextCompat.getDrawable(context, placeholderRes)
    }

    private fun applyStableMediaHeight(imageView: ShapeableImageView, message: Message) {
        val aspect = message.aspectRatio.coerceAtLeast(0.1f)
        val isSticker = message.type == MessageType.STICKER || message.type == MessageType.KLIPY_EMOJI
        val targetSize = resolveSingleMediaSizePx(message, imageView)
        val targetWidth = targetSize.widthPx
        
        // Use cached height if available to prevent jumps during re-binds.
        // If not cached, calculate and store immediately to eliminate the deferred-post race
        // where a recycled ViewHolder's layout params could overwrite the correct value.
        val cachedSize = mediaSizes[message.id]
        val mediaSize = if (cachedSize != null && cachedSize.widthPx == targetWidth && cachedSize.heightPx > 0) {
            cachedSize
        } else {
            mediaSizes[message.id] = targetSize
            targetSize
        }
        val targetHeight = mediaSize.heightPx
        
        if (MEDIA_DEBUG_ENABLED) {
            Log.w(
                MEDIA_DEBUG_TAG,
                "applyStableMediaHeight id=${message.id.take(8)} cached=${cachedSize != null} " +
                    "aspect=${"%.4f".format(aspect)} targetW=${targetWidth}px targetH=${targetHeight}px"
            )
        }

        val params = imageView.layoutParams
        if (params.height != targetHeight || params.width != targetWidth) {
            params.height = targetHeight
            params.width = targetWidth
            imageView.layoutParams = params
        }
        
        // Ensure scaleType is always correct
        val targetScaleType = if (isSticker) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
        if (imageView.scaleType != targetScaleType) {
            imageView.scaleType = targetScaleType
        }
    }

    private fun resolveSingleMediaSizePx(message: Message, anchor: View? = null): ChatMediaLayoutSizing.MediaSize {
        return ChatMediaLayoutSizing.singleMediaSizePx(
            type = message.type,
            aspect = message.aspectRatio.coerceAtLeast(0.1f),
            density = android.content.res.Resources.getSystem().displayMetrics.density,
            viewportWidthPx = resolveViewportWidthPx(anchor),
            isVideoNote = message.type == MessageType.VIDEO && message.isVideoNote,
            rootHorizontalPaddingPx = resolveRootHorizontalPaddingPx(anchor),
            forwardSideSpacePx = resolveForwardSideSpacePx(anchor),
            bubbleHorizontalPaddingPx = resolveBubbleHorizontalPaddingPx(anchor)
        )
    }

    private fun applyStableCollageWidth(collageView: CollageImageView) {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val targetWidth = ChatMediaLayoutSizing.cappedLandscapeMediaWidthPx(
            density = density,
            viewportWidthPx = resolveViewportWidthPx(collageView),
            rootHorizontalPaddingPx = resolveRootHorizontalPaddingPx(collageView),
            forwardSideSpacePx = resolveForwardSideSpacePx(collageView),
            bubbleHorizontalPaddingPx = resolveBubbleHorizontalPaddingPx(collageView)
        )
        val params = collageView.layoutParams
        if (params.width != targetWidth) {
            params.width = targetWidth
            collageView.layoutParams = params
        }
    }

    private fun resolveMediaItemRoot(anchor: View?): View? {
        var current = anchor
        while (current != null) {
            if (current.findViewById<View>(R.id.btnForwardMessage) != null) return current
            current = current.parent as? View
        }
        return null
    }

    private fun resolveViewportWidthPx(anchor: View?): Int {
        val root = resolveMediaItemRoot(anchor)
        val parent = root?.parent as? View
        return when {
            root != null && root.width > 0 -> root.width
            parent != null && parent.width > 0 -> parent.width
            else -> android.content.res.Resources.getSystem().displayMetrics.widthPixels
        }
    }

    private fun resolveRootHorizontalPaddingPx(anchor: View?): Int {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val root = resolveMediaItemRoot(anchor)
        return root?.let { it.paddingLeft + it.paddingRight }
            ?: ChatMediaLayoutSizing.defaultRootHorizontalPaddingPx(density)
    }

    private fun resolveForwardSideSpacePx(anchor: View?): Int {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val button = resolveMediaItemRoot(anchor)?.findViewById<View>(R.id.btnForwardMessage)
            ?: return ChatMediaLayoutSizing.defaultForwardSideSpacePx(density)
        val params = button.layoutParams
        val buttonWidth = when {
            button.width > 0 -> button.width
            params != null && params.width > 0 -> params.width
            else -> ChatMediaLayoutSizing.dpToPx(36f, density)
        }
        val margins = (params as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
        return buttonWidth + margins
    }

    private fun resolveBubbleHorizontalPaddingPx(anchor: View?): Int {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val bubble = resolveMediaItemRoot(anchor)?.findViewById<View>(R.id.messageBubble)
        return bubble?.let { it.paddingLeft + it.paddingRight }
            ?: ChatMediaLayoutSizing.defaultBubbleHorizontalPaddingPx(density)
    }

    private fun maybeResolveMediaDimensionsFromDrawable(
        imageView: ShapeableImageView,
        message: Message,
        drawable: Drawable?
    ) {
        if (drawable == null) return
        if (message.mediaWidth > 0 && message.mediaHeight > 0) return
        if (!persistedResolvedMediaIds.add(message.id)) return

        val resolvedWidth = drawable.intrinsicWidth
        val resolvedHeight = drawable.intrinsicHeight
        if (resolvedWidth <= 0 || resolvedHeight <= 0) {
            persistedResolvedMediaIds.remove(message.id)
            return
        }

        val resolvedAspect = (resolvedWidth.toFloat() / resolvedHeight.toFloat()).coerceAtLeast(0.1f)
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val targetSize = ChatMediaLayoutSizing.singleMediaSizePx(
            type = message.type,
            aspect = resolvedAspect,
            density = density,
            viewportWidthPx = resolveViewportWidthPx(imageView),
            isVideoNote = message.type == MessageType.VIDEO && message.isVideoNote,
            rootHorizontalPaddingPx = resolveRootHorizontalPaddingPx(imageView),
            forwardSideSpacePx = resolveForwardSideSpacePx(imageView),
            bubbleHorizontalPaddingPx = resolveBubbleHorizontalPaddingPx(imageView)
        )
        val targetWidth = targetSize.widthPx
        val targetHeight = targetSize.heightPx

        mediaSizes[message.id] = targetSize

        val params = imageView.layoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            imageView.layoutParams = params
        }

        onMediaDimensionsResolved?.invoke(message.id, resolvedWidth, resolvedHeight)
    }

    /**
     * Load a locally-cached status thumbnail file into the reply-preview ImageView.
     * Applies a pixel-blur overlay when the status has expired (>26 h old).
     */
    private fun loadStatusThumbFromFile(
        context: android.content.Context,
        file: java.io.File,
        message: Message,
        iv: ShapeableImageView,
        targetW: Int,
        targetH: Int
    ) {
        iv.background = null
        val base = Glide.with(context)
            .load(file)
            .override(targetW, targetH)
            .dontAnimate()
            .skipMemoryCache(false)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
        if (StatusThumbnailCache.isExpired(message)) {
            base.transform(CenterCrop(), PixelBlurTransformation()).into(iv)
        } else {
            base.centerCrop().into(iv)
        }
    }

    private fun buildColorSwatchDrawable(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
        }

    private fun clearReplyPreviewVisuals(
        context: android.content.Context,
        replyContainer: View
    ) {
        replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)?.let { iv ->
            Glide.with(context).clear(iv)
            iv.tag = null
            iv.background = null
            iv.visibility = View.GONE

            val lp = iv.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (lp != null && lp.height == 0) {
                val density = android.content.res.Resources.getSystem().displayMetrics.density
                lp.height = (36 * density).toInt()
                lp.width = (36 * density).toInt()
                lp.marginEnd = (4 * density).toInt()
                iv.layoutParams = lp
                iv.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(4f * density)
                    .build()
            }
        }

        replyContainer.findViewById<View>(R.id.replyCollageContainer)?.visibility = View.GONE
        listOfNotNull(
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage1),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage2),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage3),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage4)
        ).forEach { imageView ->
            Glide.with(context).clear(imageView)
            imageView.visibility = View.GONE
        }
    }

    private fun hideReplyPreviewMediaViewsLightweight(replyContainer: View) {
        replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)?.let { iv ->
            iv.tag = null
            iv.background = null
            iv.setImageDrawable(null)
            if (iv.visibility != View.GONE) {
                iv.visibility = View.GONE
            }

            val lp = iv.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (lp != null && lp.height == 0) {
                val density = android.content.res.Resources.getSystem().displayMetrics.density
                lp.height = (36 * density).toInt()
                lp.width = (36 * density).toInt()
                lp.marginEnd = (4 * density).toInt()
                iv.layoutParams = lp
                iv.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(4f * density)
                    .build()
            }
        }

        replyContainer.findViewById<View>(R.id.replyCollageContainer)?.let { collageContainer ->
            if (collageContainer.visibility != View.GONE) {
                collageContainer.visibility = View.GONE
            }
        }
        listOfNotNull(
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage1),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage2),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage3),
            replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage4)
        ).forEach { imageView ->
            imageView.setImageDrawable(null)
            imageView.background = null
            if (imageView.visibility != View.GONE) {
                imageView.visibility = View.GONE
            }
        }
    }

    private fun bindReplyPreviewLightweight(
        replyContainer: View,
        message: Message
    ): Boolean {
        val tvContact = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContact)
        val tvContent = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContent)
        val bar = replyContainer.findViewById<View>(R.id.replyBar)

        if (message.type == MessageType.STATUS_REPLY) {
            replyContainer.visibility = View.VISIBLE
            replyContainer.setOnClickListener(null)
            hideReplyPreviewMediaViewsLightweight(replyContainer)

            tvContact.text = if (message.isIncoming) {
                "You • Status"
            } else {
                (otherUsername ?: "Someone") + " • Status"
            }
            tvContent.text = when (message.statusType) {
                "IMAGE" -> "Photo"
                "VIDEO" -> "Video"
                "VOICE" -> "Voice"
                else -> message.statusText?.take(80) ?: "Status"
            }
            tvContact.setTextColor(android.graphics.Color.parseColor("#128C7E"))
            bar.setBackgroundColor(android.graphics.Color.parseColor("#128C7E"))
            return true
        }

        if (message.replyToMessageId == null) {
            if (replyContainer.visibility != View.GONE) {
                hideReplyPreviewMediaViewsLightweight(replyContainer)
                replyContainer.visibility = View.GONE
            }
            replyContainer.setOnClickListener(null)
            return true
        }

        replyContainer.visibility = View.VISIBLE
        replyContainer.setOnClickListener(null)
        hideReplyPreviewMediaViewsLightweight(replyContainer)

        tvContact.text = if (message.replyToSenderId == currentUserId) {
            "You"
        } else {
            otherUsername ?: "Sender"
        }
        tvContent.text = message.replyToText?.takeIf { it.isNotBlank() } ?: "Message"

        if (isPastelTheme) {
            tvContact.setTextColor(android.graphics.Color.parseColor("#4A3D6D"))
        }
        bar.setBackgroundColor(colorPrimaryCached)
        return true
    }

    private fun bindReplyPreview(
        context: android.content.Context,
        root: View,
        message: Message
    ) {
        val replyContainer = root.findViewById<View>(R.id.includeReplyPreview) ?: return

        if (isFastBind && bindReplyPreviewLightweight(replyContainer, message)) {
            return
        }

        // ── STATUS_REPLY: show a status preview header using the same reply layout ──
        if (message.type == MessageType.STATUS_REPLY) {
            replyContainer.visibility = View.VISIBLE
            replyContainer.setOnClickListener(null)

            val tvContact = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContact)
            val tvContent = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContent)
            val bar      = replyContainer.findViewById<View>(R.id.replyBar)
            val ivReplyImage  = replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)
            val collageContainer = replyContainer.findViewById<View>(R.id.replyCollageContainer)
            collageContainer?.visibility = View.GONE

            val density = context.resources.displayMetrics.density

            // CORRECTED: incoming = they replied to MY status → "You • Status"
            //            outgoing = I replied to THEIR status → "{Name} • Status"
            val senderLabel = if (message.isIncoming)
                "You \u2022 Status"
            else
                (otherUsername ?: "Someone") + " \u2022 Status"

            tvContact.text = senderLabel
            tvContact.setTextColor(android.graphics.Color.parseColor("#128C7E"))

            // Sub-line: content summary
            tvContent.text = when (message.statusType) {
                "IMAGE" -> "\uD83D\uDCF7 Photo"
                "VIDEO" -> "\uD83C\uDFA5 Video"
                "VOICE" -> "\uD83C\uDF99 Voice"
                else    -> message.statusText?.take(80) ?: "Status"
            }

            // Accent bar — WhatsApp teal
            bar.setBackgroundColor(android.graphics.Color.parseColor("#128C7E"))

            // Thumbnail — full height of container, 56dp wide, right corners only (WhatsApp style)
            val thumbTargetW = (56 * density).toInt()
            val thumbTargetH = (80 * density).toInt()
            ivReplyImage.visibility = View.VISIBLE

            // Only mutate LayoutParams when dimensions actually differ (avoids layout pass).
            val lp = ivReplyImage.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (lp != null && (lp.height != 0 || lp.width != thumbTargetW || lp.marginEnd != 0)) {
                lp.height    = 0   // MATCH_CONSTRAINT — top/bottom constraints define height
                lp.width     = thumbTargetW
                lp.marginEnd = 0
                ivReplyImage.layoutParams = lp
            }

            val cornerPx = 8f * density
            ivReplyImage.shapeAppearanceModel =
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setTopRightCornerSize(cornerPx)
                    .setBottomRightCornerSize(cornerPx)
                    .setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f)
                    .build()

            val statusId = message.statusId
            val statusType = message.statusType ?: ""
            ivReplyImage.tag = statusId ?: ""

            when (statusType) {
                "IMAGE", "VIDEO" -> {
                    // ── IMAGE / VIDEO ────────────────────────────────────────────────────────
                    // Strategy: if we already saved a local thumbnail (e.g. from a prior visit)
                    // load from disk with optional blur.  Otherwise fall back to loading via
                    // Glide directly from the remote URL — identical to the pre-refactor path
                    // which is proven to work on both sender and receiver.  Fire a background
                    // save to StatusThumbnailCache concurrently so future visits are instant
                    // and expired-status blurring works even after the URL is gone.
                    val localFile = if (statusId != null) StatusThumbnailCache.getLocalFileSync(statusId) else null
                    val thumbUrl  = message.statusThumbnailUrl

                    when {
                        localFile != null -> {
                            // Local file on disk — load with optional expired-blur.
                            loadStatusThumbFromFile(context, localFile, message, ivReplyImage, thumbTargetW, thumbTargetH)
                        }
                        !thumbUrl.isNullOrBlank() -> {
                            // No local file yet — load via Glide directly (network or Glide cache).
                            // This is the same path as the original code and works reliably on
                            // both sender and receiver without auth/URLConnection issues.
                            ivReplyImage.background = null
                            val isExpired = StatusThumbnailCache.isExpired(message)
                            if (isExpired) {
                                Glide.with(context).load(thumbUrl)
                                    .transform(CenterCrop(), PixelBlurTransformation())
                                    .override(thumbTargetW, thumbTargetH).dontAnimate()
                                    .into(ivReplyImage)
                            } else {
                                Glide.with(context).load(thumbUrl)
                                    .centerCrop()
                                    .override(thumbTargetW, thumbTargetH).dontAnimate()
                                    .into(ivReplyImage)
                            }
                            // Concurrently persist to StatusThumbnailCache so the next visit
                            // skips the network entirely.
                            if (statusId != null) {
                                adapterScope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { StatusThumbnailCache.ensureThumb(context, message) }
                                    }
                                }
                            }
                        }
                        else -> {
                            // No URL at all — hide the thumbnail slot.
                            Glide.with(context).clear(ivReplyImage)
                            ivReplyImage.visibility = View.GONE
                        }
                    }
                }

                "TEXT" -> {
                    // ── TEXT ─────────────────────────────────────────────────────────────────
                    // Immediately show the bg-colour swatch, then upgrade to the Canvas-rendered
                    // thumbnail (bg colour + word-wrapped text) once cached on disk.
                    val localFile = if (statusId != null) StatusThumbnailCache.getLocalFileSync(statusId) else null
                    if (localFile != null) {
                        loadStatusThumbFromFile(context, localFile, message, ivReplyImage, thumbTargetW, thumbTargetH)
                    } else {
                        val bgColor = message.statusBgColor ?: 0xFF1B5E20.toInt()
                        Glide.with(context).clear(ivReplyImage)
                        ivReplyImage.setImageDrawable(null)
                        ivReplyImage.background = buildColorSwatchDrawable(bgColor)
                        if (statusId != null) {
                            adapterScope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    runCatching { StatusThumbnailCache.ensureThumb(context, message) }.getOrNull()
                                }
                                if (ivReplyImage.tag == statusId && file != null) {
                                    loadStatusThumbFromFile(context, file, message, ivReplyImage, thumbTargetW, thumbTargetH)
                                }
                            }
                        }
                    }
                }

                "VOICE" -> {
                    // ── VOICE ─────────────────────────────────────────────────────────────────
                    // Immediately show a dark placeholder, then swap to the waveform thumbnail.
                    val localFile = if (statusId != null) StatusThumbnailCache.getLocalFileSync(statusId) else null
                    if (localFile != null) {
                        loadStatusThumbFromFile(context, localFile, message, ivReplyImage, thumbTargetW, thumbTargetH)
                    } else {
                        Glide.with(context).clear(ivReplyImage)
                        ivReplyImage.setImageDrawable(null)
                        ivReplyImage.background = buildColorSwatchDrawable(android.graphics.Color.parseColor("#1A1A2E"))
                        if (statusId != null) {
                            adapterScope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    runCatching { StatusThumbnailCache.ensureThumb(context, message) }.getOrNull()
                                }
                                if (ivReplyImage.tag == statusId && file != null) {
                                    loadStatusThumbFromFile(context, file, message, ivReplyImage, thumbTargetW, thumbTargetH)
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Unknown status type — hide the thumbnail slot entirely.
                    Glide.with(context).clear(ivReplyImage)
                    ivReplyImage.visibility = View.GONE
                }
            }
            return
        }

        if (message.replyToMessageId != null) {
            replyContainer.visibility = View.VISIBLE
            // Reset ivReplyImage size if this ViewHolder was previously used for a STATUS_REPLY
            // (which sets height=MATCH_CONSTRAINT/0 — regular replies need the original 36×36dp)
            replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)?.let { iv ->
                val lp = iv.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                if (lp != null && lp.height == 0) {
                    val d = context.resources.displayMetrics.density
                    lp.height    = (36 * d).toInt()
                    lp.width     = (36 * d).toInt()
                    lp.marginEnd = (4 * d).toInt()
                    iv.layoutParams = lp
                    iv.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                        .setAllCornerSizes(4f * d)
                        .build()
                }
                iv.tag = null
                iv.background = null
            }
            replyContainer.setOnClickListener {
                onReplyClick?.invoke(message.replyToMessageId)
            }

            val tvContact = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContact)
            val tvContent = replyContainer.findViewById<android.widget.TextView>(R.id.tvReplyContent)
            val bar = replyContainer.findViewById<View>(R.id.replyBar)
            val ivReplyImage = replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)
            val collageContainer = replyContainer.findViewById<View>(R.id.replyCollageContainer)
            val collageViews = listOfNotNull(
                replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage1),
                replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage2),
                replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage3),
                replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage4)
            )
            val repliedMessage = findMessageById(message.replyToMessageId)
            
            tvContact.text = if(message.replyToSenderId == currentUserId) "You" else (otherUsername ?: "Sender") 
            tvContent.text = repliedMessage?.let(::buildReplyPreviewSummary) ?: (message.replyToText ?: "Message")

            // Handle media group (collage) vs single thumbnail
            val isCollage = repliedMessage?.type == MessageType.MEDIA_GROUP && repliedMessage.mediaItemsList.size > 1
            
            if (isCollage && collageContainer != null) {
                // Show stacked collage thumbnails
                ivReplyImage.visibility = View.GONE
                Glide.with(context).clear(ivReplyImage)
                collageContainer.visibility = View.VISIBLE
                
                val items = repliedMessage!!.mediaItemsList.take(4)
                collageViews.forEachIndexed { index, iv ->
                    if (index < items.size) {
                        iv.visibility = View.VISIBLE
                        val scale = 1f - (index * 0.02f)
                        iv.scaleX = scale
                        iv.scaleY = scale
                        val url = resolveExistingLocalUri(items[index].localUri) ?: items[index].displayUrl
                        if (url.isNotEmpty()) {
                            Glide.with(context).load(url).centerCrop().dontAnimate().into(iv)
                        }
                    } else {
                        iv.visibility = View.GONE
                        Glide.with(context).clear(iv)
                    }
                }
            } else {
                // Hide collage container, show single thumbnail
                collageContainer?.visibility = View.GONE
                collageViews.forEach { Glide.with(context).clear(it); it.visibility = View.GONE }
                
                val replyThumbnailModel = repliedMessage?.let(::resolveReplyPreviewModel)
                if (replyThumbnailModel != null && (replyThumbnailModel !is String || replyThumbnailModel.isNotBlank())) {
                    ivReplyImage.visibility = View.VISIBLE
                    Glide.with(context)
                        .load(replyThumbnailModel)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .dontAnimate()
                        .into(ivReplyImage)
                } else {
                    Glide.with(context).clear(ivReplyImage)
                    ivReplyImage.visibility = View.GONE
                }
            }
            
             // Ensure sender name color is visible (darker in light themes)
             if (isPastelTheme) {
                 tvContact.setTextColor(android.graphics.Color.parseColor("#4A3D6D")) // Dark Indigo from theme
             }

             bar.setBackgroundColor(colorPrimaryCached)
        } else {
            // Fast path: skip deep clearing if already hidden from a previous bind
            if (replyContainer.visibility != View.GONE) {
                replyContainer.visibility = View.GONE
                replyContainer.setOnClickListener(null)
                replyContainer.findViewById<ShapeableImageView>(R.id.ivReplyImage)?.let { iv ->
                    Glide.with(context).clear(iv)
                    iv.tag = null
                    iv.background = null
                    iv.visibility = View.GONE
                    // Restore original 36×36 dp size if a STATUS_REPLY bind had set MATCH_CONSTRAINT
                    val lp = iv.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    if (lp != null && lp.height == 0) {
                        val d = context.resources.displayMetrics.density
                        lp.height    = (36 * d).toInt()
                        lp.width     = (36 * d).toInt()
                        lp.marginEnd = (4 * d).toInt()
                        iv.layoutParams = lp
                        iv.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(4f * d)
                            .build()
                    }
                }
                replyContainer.findViewById<View>(R.id.replyCollageContainer)?.visibility = View.GONE
                listOfNotNull(
                    replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage1),
                    replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage2),
                    replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage3),
                    replyContainer.findViewById<ShapeableImageView>(R.id.ivCollage4)
                ).forEach { Glide.with(context).clear(it); it.visibility = View.GONE }
            }
        }
    }

    inner class IncomingMediaViewHolder(private val binding: ItemMessageIncomingMediaBinding) : BaseViewHolder(binding) {
        // Track the currently loaded image source to avoid redundant Glide reloads
        private var boundImageSource: Any? = null
        private var boundWasScrolling: Boolean = false
        private var boundMessageId: String? = null

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val previouslyBoundMessageId = boundMessageId
                // FAST-REBIND PATH: during active scroll, when rebinding the same message and
                // we already have a drawable, skip every per-bind allocation/disk-syscall.
                if (isFastBind && previouslyBoundMessageId == msg.id && binding.ivImage.drawable != null) {
                    return
                }
                boundMessageId = msg.id
                val context = binding.root.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = true)
                bindMediaForwardButton(msg)
                
                val isSticker = msg.type == MessageType.STICKER || msg.type == MessageType.KLIPY_EMOJI
                
                applyMediaBubbleBackground(
                    messageBubble = binding.messageBubble,
                    imageView = binding.ivImage,
                    isIncoming = true,
                    isSticker = isSticker
                )
                
                binding.ivImage.minimumHeight = ChatMediaLayoutSizing.minSingleMediaHeightPx(
                    msg.type,
                    android.content.res.Resources.getSystem().displayMetrics.density
                )
                
                val localImagePath = cachedLocalFilePath(msg.chatId, msg.id, msg.type)
                val hasLocalMedia = !localImagePath.isNullOrEmpty()
                
                applyStableMediaHeight(binding.ivImage, msg)

                val imageSource = when {
                    hasLocalMedia -> {
                        // For VIDEO messages, prefer the JPEG thumbnail over the raw .mp4 file.
                        // Glide uses MediaMetadataRetriever to extract a frame from .mp4 files,
                        // which fails when the moov atom is at the end or the codec is unusual.
                        // thumbnailUrl may be a local JPEG path (after download) or a Firebase URL.
                        if (msg.type == MessageType.VIDEO && !msg.thumbnailUrl.isNullOrEmpty()) {
                            msg.thumbnailUrl
                        } else {
                            localImagePath
                        }
                    }
                    !msg.localUri.isNullOrEmpty() -> {
                        val uri = Uri.parse(msg.localUri)
                        if (uri.scheme == "file" || msg.localUri!!.startsWith("/")) {
                            val path = uri.path ?: msg.localUri!!
                            if (java.io.File(path).exists()) msg.localUri
                            else if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                        } else {
                            // Trust content:// URIs or others
                            msg.localUri
                        }
                    }
                    else -> if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                }
                val resolvedImageSource = if (hasLocalMedia || isAnimatedInlineMedia(msg)) {
                    imageSource
                } else {
                    MessagePreviewCacheManager.resolveMediaPreviewModel(msg, imageSource)
                }
                
                // FLICKER GUARD: Skip Glide entirely if the same source is already loaded
                // and the drawable is present. This prevents flash on DiffUtil rebinds.
                val drawablePresent = binding.ivImage.drawable != null
                val sourceUnchanged =
                    resolvedImageSource == boundImageSource &&
                        previouslyBoundMessageId == msg.id &&
                        drawablePresent
                val shouldLoad = when {
                    skipImageLoad && drawablePresent -> false
                    sourceUnchanged && !isFastBind&& !boundWasScrolling -> false
                    sourceUnchanged && isScrolling && boundWasScrolling -> false
                    else -> true
                }

                if (shouldLoad) {
                    val isKlipyMedia = isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME
                    val isGif = msg.type == MessageType.GIF || msg.type == MessageType.MEME

                    if (isScrolling) {
                        // During actual scroll: low-res preview. During first layout: fall through to full quality.
                        if (!tryBindSeededMediaWhileScrolling(binding.ivImage, msg, resolvedImageSource, "IN ")) {
                            loadScrollingMediaPreview(
                                context = context,
                                imageView = binding.ivImage,
                                message = msg,
                                resolvedImageSource = resolvedImageSource,
                                directionLabel = "IN ",
                                previewSizePx = if (isKlipyMedia) 200 else 120
                            )
                        }
                        boundWasScrolling = true
                    } else if (isGif || isSticker) {
                        // GIF, MEME, STICKER, KLIPY_EMOJI: generic Drawable load (handles GIF, WebP, MP4).
                        // asGif() is NOT used because Klipy may return WebP or MP4 which asGif() rejects.
                        // Glide auto-detects animated GIF/WebP and exposes them as Animatable drawables.
                        //
                        // CRITICAL: .override() with the layoutParams dimensions set by applyStableMediaHeight
                        // produces an exact memory-cache-key match with prefillFromSnapshot preloads
                        // (which also use override(w,h) at identical pixel values). Glide then delivers
                        // the already-decoded GifDrawable synchronously from memory cache — no blank frame.
                        val ivLpIn = binding.ivImage.layoutParams
                        val glideWIn = ivLpIn.width.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        val glideHIn = ivLpIn.height.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        if (!tryBindPreloadedMediaNow(binding.ivImage, msg, resolvedImageSource, "IN ", "GIF/STK")) {
                            val currentPlaceholder = preserveVisibleDrawablePlaceholder(binding.ivImage)
                            Glide.with(context)
                                .load(resolvedImageSource)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .fitCenter()
                                .override(glideWIn, glideHIn)
                                .transition(DrawableTransitionOptions().dontTransition()) // suppress crossfade; dontAnimate() also sets GifOptions.DISABLE_ANIMATION which kills GIF playback
                                .placeholder(currentPlaceholder ?: androidx.core.content.ContextCompat.getDrawable(context, android.R.color.transparent))
                                .error(R.drawable.ic_image_error)
                                .listener(object : RequestListener<Drawable> {
                                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                        applyBlur(binding.ivImage, false)
                                        return false
                                    }
                                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                        applyBlur(binding.ivImage, false)
                                        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                                            val level = if (dataSource == DataSource.MEMORY_CACHE) "BIND-HIT " else "BIND-MISS"
                                            Log.d("GlyphCacheDebug", "[$level] IN GIF/STK msgId=${msg.id.take(8)} ds=$dataSource iv=${glideWIn}x${glideHIn} model=${model.toString().take(60)}")
                                        }
                                        (resource as? android.graphics.drawable.Animatable)?.start()
                                        binding.ivImage.post {
                                            (binding.ivImage.drawable as? android.graphics.drawable.Animatable)?.start()
                                        }
                                        return false
                                    }
                                })
                                .into(binding.ivImage)
                        }
                        boundWasScrolling = false
                    } else {
                        // Non-scrolling static image (photo, video thumbnail)
                        // If exact prefetch reuse misses, show a tiny blurred preview first so
                        // the row never sits transparent while the full resource is decoded.
                        val ivLpIn = binding.ivImage.layoutParams
                        val glideWIn = ivLpIn.width.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        val glideHIn = ivLpIn.height.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        if (!tryBindPreloadedMediaNow(binding.ivImage, msg, resolvedImageSource, "IN ", "IMG ")) {
                            loadStaticMediaWithPreview(
                                context = context,
                                imageView = binding.ivImage,
                                message = msg,
                                resolvedImageSource = resolvedImageSource,
                                widthPx = glideWIn,
                                heightPx = glideHIn,
                                directionLabel = "IN",
                                skipBlurredPreview = isFirstLayout
                            )
                        }
                        boundWasScrolling = false
                    }
                    boundImageSource = resolvedImageSource
                }

                if (isFastBind) {
                    bindScrollingChrome(msg, isSticker, position)
                    bindSelection(item, animate = false)
                    // During first layout (chat just opened), re-attach click listeners
                    // that bindScrollingChrome preserved. Freshly-inflated ViewHolders need
                    // them set up to be tappable. During scroll, they stay null.
                    if (isFirstLayout) {
                        binding.ivDownloadButton.setOnClickListener { mediaDownloadListener?.onDownloadClicked(msg) }
                        val hasLocal = !cachedLocalFilePath(msg.chatId, msg.id, msg.type).isNullOrEmpty()
                        binding.messageBubble.setOnClickListener {
                            if (selectionManager.hasSelection()) {
                                selectionManager.toggleSelection(msg.id)
                            } else if (hasLocal || mediaLocalFileResolver?.isReadyForPlayback(msg) == true) {
                                if (msg.type == MessageType.VIDEO) {
                                    val intent = VideoPlayerActivity.newIntent(context, mediaLocalFileResolver?.getPlaybackUri(msg) ?: msg.videoUrl ?: "", "Video")
                                    context.startActivity(intent)
                                } else {
                                    val intent = MediaViewerActivity.newIntent(
                                        context,
                                        mediaLocalFileResolver?.getPlaybackUri(msg) ?: msg.imageUrl ?: "",
                                        msg.timestamp, msg.id, msg.chatId
                                    )
                                    onOpenMediaViewer?.invoke(intent) ?: context.startActivity(intent)
                                }
                            }
                        }
                        binding.messageBubble.setOnLongClickListener {
                            handleBubbleLongPress(msg.id, itemView)
                            true
                        }
                    }
                    return
                }

                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) {
                    binding.tvFileSize.visibility = View.GONE
                } else {
                    binding.tvFileSize.text = formatFileSize(msg.fileSize ?: 0)
                    binding.tvFileSize.visibility = View.VISIBLE
                }
                
                // Display caption if present (only real user captions, not default placeholders)
                if (!msg.text.isNullOrBlank() && msg.text !in DEFAULT_MEDIA_LABELS && msg.type != MessageType.KLIPY_EMOJI) {
                    binding.tvCaption.text = msg.text
                    binding.tvCaption.visibility = View.VISIBLE
                } else {
                    binding.tvCaption.visibility = View.GONE
                }

                if (isSticker) {
                    binding.tvTimestamp.visibility = View.GONE
                    binding.tvTimestampBelow.visibility = View.VISIBLE
                    binding.tvTimestampBelow.text = binding.tvTimestamp.text
                } else {
                    binding.tvTimestamp.visibility = View.VISIBLE
                    binding.tvTimestampBelow.visibility = View.GONE
                    binding.tvTimestamp.setBackgroundResource(R.drawable.bg_timestamp_overlay)
                    val px6 = dpToPx(6f)
                    val px2 = dpToPx(2f)
                    binding.tvTimestamp.setPadding(px6, px2, px6, px2)
                }
                
                if (msg.type == MessageType.VIDEO) {
                    binding.ivPlayButton.visibility = View.VISIBLE
                    binding.tvVideoDuration.visibility = View.VISIBLE
                    binding.tvVideoDuration.text = formatDuration(msg.videoDuration ?: 0)
                } else {
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvVideoDuration.visibility = View.GONE
                }
                
                bindReplyPreview(binding.root.context, binding.root, msg)

                val downloadProgress = MediaProgressManager.getProgress(msg.id)
                val isDownloading = downloadProgress != null && !downloadProgress.isUploading && !downloadProgress.isComplete
                val isReady = hasLocalMedia || (mediaLocalFileResolver?.isReadyForPlayback(msg) == true)
                
                when {
                    isReady -> {
                        binding.ivDownloadButton.visibility = View.GONE
                        binding.progressIndicator.visibility = View.GONE
                    }
                    isDownloading -> {
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.ivDownloadButton.visibility = View.GONE
                        if (downloadProgress!!.isIndeterminate) {
                            binding.progressIndicator.startIndeterminate()
                        } else {
                            binding.progressIndicator.setProgress(downloadProgress.progress, animate = false)
                        }
                    }
                    else -> {
                        binding.ivDownloadButton.visibility = View.VISIBLE
                        binding.progressIndicator.visibility = View.GONE
                    }
                }
                
                binding.ivDownloadButton.setOnClickListener { mediaDownloadListener?.onDownloadClicked(msg) }
                
                binding.messageBubble.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else if (isReady) {
                        if (msg.type == MessageType.VIDEO) {
                            val intent = VideoPlayerActivity.newIntent(context, mediaLocalFileResolver?.getPlaybackUri(msg) ?: msg.videoUrl ?: "", "Video")
                            context.startActivity(intent)
                        } else {
                            val intent = MediaViewerActivity.newIntent(
                                context, 
                                mediaLocalFileResolver?.getPlaybackUri(msg) ?: msg.imageUrl ?: "", 
                                msg.timestamp,
                                msg.id,
                                msg.chatId
                            )
                            onOpenMediaViewer?.invoke(intent) ?: context.startActivity(intent)
                        }
                    }
                }
                
                binding.messageBubble.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                
                updateGrouping(position)
                maybeBounceIncoming(item, binding.messageBubble)
                bindSelection(item, animate = false)
            }
        }

        private fun bindScrollingChrome(msg: Message, isSticker: Boolean, position: Int) {
            binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
            if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) {
                binding.tvFileSize.visibility = View.GONE
            } else {
                binding.tvFileSize.text = formatFileSize(msg.fileSize ?: 0)
                binding.tvFileSize.visibility = View.VISIBLE
            }

            if (!msg.text.isNullOrBlank() && msg.text !in DEFAULT_MEDIA_LABELS && msg.type != MessageType.KLIPY_EMOJI) {
                binding.tvCaption.text = msg.text
                binding.tvCaption.visibility = View.VISIBLE
            } else {
                binding.tvCaption.visibility = View.GONE
            }

            if (isSticker) {
                binding.tvTimestamp.visibility = View.GONE
                binding.tvTimestampBelow.visibility = View.VISIBLE
                binding.tvTimestampBelow.text = binding.tvTimestamp.text
            } else {
                binding.tvTimestamp.visibility = View.VISIBLE
                binding.tvTimestampBelow.visibility = View.GONE
            }

            if (msg.type == MessageType.VIDEO) {
                binding.ivPlayButton.visibility = View.VISIBLE
                binding.tvVideoDuration.visibility = View.VISIBLE
                binding.tvVideoDuration.text = formatDuration(msg.videoDuration ?: 0)
            } else {
                binding.ivPlayButton.visibility = View.GONE
                binding.tvVideoDuration.visibility = View.GONE
            }

            bindReplyPreview(binding.root.context, binding.root, msg)
            binding.ivDownloadButton.visibility = View.GONE
            binding.progressIndicator.visibility = View.GONE
            // During actual scroll: null click listeners prevent accidental taps.
            // During first layout: preserve listeners so visible media is tappable.
            if (!isFirstLayout) {
                binding.ivDownloadButton.setOnClickListener(null)
                binding.messageBubble.setOnClickListener(null)
                binding.messageBubble.setOnLongClickListener(null)
            }
            updateGrouping(position)
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.messageBubble, groupPos, isIncoming = true, fullRadiusDp = 12f, imageView = binding.ivImage)
        }

        override fun updateProgress(item: ChatListItem) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            val localImagePath = cachedLocalFilePath(msg.chatId, msg.id, msg.type)
            val hasLocalMedia = !localImagePath.isNullOrEmpty()
            val downloadProgress = MediaProgressManager.getProgress(msg.id)
            val isDownloading = downloadProgress != null && !downloadProgress.isUploading && !downloadProgress.isComplete
            val isReady = hasLocalMedia || (mediaLocalFileResolver?.isReadyForPlayback(msg) == true)
            when {
                isReady -> {
                    binding.ivDownloadButton.visibility = View.GONE
                    binding.progressIndicator.visibility = View.GONE
                }
                isDownloading -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.ivDownloadButton.visibility = View.GONE
                    if (downloadProgress!!.isIndeterminate) {
                        binding.progressIndicator.startIndeterminate()
                    } else {
                        binding.progressIndicator.setProgress(downloadProgress.progress, animate = true)
                    }
                }
                else -> {
                    binding.ivDownloadButton.visibility = View.VISIBLE
                    binding.progressIndicator.visibility = View.GONE
                }
            }
        }

        override fun refreshMedia(item: ChatListItem) {
            if (isFastBind) return
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                // Compute the current best imageSource the same way bind() does
                val localImagePath = cachedLocalFilePath(msg.chatId, msg.id, msg.type)
                val currentSource: Any? = when {
                    !localImagePath.isNullOrEmpty() -> {
                        // For VIDEO messages, prefer the JPEG thumbnail over the raw .mp4 file.
                        if (msg.type == MessageType.VIDEO && !msg.thumbnailUrl.isNullOrEmpty()) {
                            msg.thumbnailUrl
                        } else {
                            localImagePath
                        }
                    }
                    !msg.localUri.isNullOrEmpty() -> {
                        val uri = Uri.parse(msg.localUri)
                        if (uri.scheme == "file" || msg.localUri!!.startsWith("/")) {
                            val path = uri.path ?: msg.localUri!!
                            if (java.io.File(path).exists()) msg.localUri
                            else if (isAnimatedInlineMedia(msg)) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                        } else {
                            msg.localUri
                        }
                    }
                    else -> if (isAnimatedInlineMedia(msg)) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                }
                val resolvedSource = if (!localImagePath.isNullOrEmpty() || isAnimatedInlineMedia(msg)) {
                    currentSource
                } else {
                    MessagePreviewCacheManager.resolveMediaPreviewModel(msg, currentSource)
                }
                // Skip only if source is unchanged AND already at full quality AND drawable present
                // (source change means e.g. download completed → must switch from remote URL to local file)
                val sourceUnchanged = resolvedSource == boundImageSource && boundMessageId == msg.id
                if (sourceUnchanged && !boundWasScrolling && binding.ivImage.drawable != null) return

                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    bind(item, pos, skipImageLoad = false)
                }
            }
        }

        fun clearMediaState() {
            // Only clear Glide request, preserve drawable for potential rebind to same message
            Glide.with(binding.ivImage).clear(binding.ivImage)
            applyBlur(binding.ivImage, false)
            boundImageSource = null
            boundWasScrolling = false
            boundMessageId = null
        }
    }

    inner class OutgoingMediaViewHolder(private val binding: ItemMessageOutgoingMediaBinding) : BaseViewHolder(binding) {
        private var lastStatus: MessageStatus? = null
        // Track the currently loaded image source to avoid redundant Glide reloads
        private var boundImageSource: Any? = null
        private var boundWasScrolling: Boolean = false
        private var boundMessageId: String? = null

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val displayStatus = rememberStrongestOutgoingStatus(msg)
                val previouslyBoundMessageId = boundMessageId
                // FAST-REBIND PATH: during active scroll, when rebinding the same message and
                // we already have a drawable, skip every per-bind allocation/disk-syscall.
                if (isFastBind && previouslyBoundMessageId == msg.id && binding.ivImage.drawable != null) {
                    // Still update the status icon — it's cheap and must never be stale.
                    setStatus(displayStatus)
                    return
                }
                boundMessageId = msg.id
                val context = binding.root.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = false)
                bindMediaForwardButton(msg)
                
                val isSticker = msg.type == MessageType.STICKER || msg.type == MessageType.KLIPY_EMOJI
                
                applyMediaBubbleBackground(
                    messageBubble = binding.messageBubble,
                    imageView = binding.ivImage,
                    isIncoming = false,
                    isSticker = isSticker
                )
                
                binding.ivImage.minimumHeight = ChatMediaLayoutSizing.minSingleMediaHeightPx(
                    msg.type,
                    android.content.res.Resources.getSystem().displayMetrics.density
                )
                
                applyStableMediaHeight(binding.ivImage, msg)

                val localImagePath = cachedLocalFilePath(msg.chatId, msg.id, msg.type)
                val imageSource = when {
                    !localImagePath.isNullOrEmpty() -> {
                        // For VIDEO messages, prefer the JPEG thumbnail over the raw .mp4 file.
                        // Glide uses MediaMetadataRetriever to extract a frame from .mp4 files,
                        // which fails when the moov atom is at the end or the codec is unusual.
                        if (msg.type == MessageType.VIDEO && !msg.thumbnailUrl.isNullOrEmpty()) {
                            msg.thumbnailUrl
                        } else {
                            localImagePath
                        }
                    }
                    !msg.localUri.isNullOrEmpty() -> {
                        val uri = Uri.parse(msg.localUri)
                        if (uri.scheme == "file" || msg.localUri!!.startsWith("/")) {
                            val path = uri.path ?: msg.localUri!!
                            if (java.io.File(path).exists()) msg.localUri
                            else if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                        } else {
                            msg.localUri
                        }
                    }
                    else -> if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                }
                val resolvedImageSource = if (!localImagePath.isNullOrEmpty() || isAnimatedInlineMedia(msg)) {
                    imageSource
                } else {
                    MessagePreviewCacheManager.resolveMediaPreviewModel(msg, imageSource)
                }

                // FLICKER GUARD: Skip Glide entirely if same source is already loaded
                val drawablePresent = binding.ivImage.drawable != null
                val sourceUnchanged =
                    resolvedImageSource == boundImageSource &&
                        previouslyBoundMessageId == msg.id &&
                        drawablePresent
                val shouldLoad = when {
                    skipImageLoad && drawablePresent -> false
                    sourceUnchanged && !isFastBind&& !boundWasScrolling -> false
                    sourceUnchanged && isScrolling && boundWasScrolling -> false
                    else -> true
                }

                if (shouldLoad) {
                    val isKlipyMedia = isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME
                    val isGif = msg.type == MessageType.GIF || msg.type == MessageType.MEME

                    if (isScrolling) {
                        // During actual scroll: low-res preview. During first layout: fall through to full quality.
                        if (!tryBindSeededMediaWhileScrolling(binding.ivImage, msg, resolvedImageSource, "OUT")) {
                            loadScrollingMediaPreview(
                                context = context,
                                imageView = binding.ivImage,
                                message = msg,
                                resolvedImageSource = resolvedImageSource,
                                directionLabel = "OUT",
                                previewSizePx = if (isKlipyMedia) 200 else 120
                            )
                        }
                        boundWasScrolling = true
                    } else if (isGif || isSticker) {
                        // GIF, MEME, STICKER, KLIPY_EMOJI: generic Drawable load (handles GIF, WebP, MP4).
                        // asGif() is NOT used because Klipy may return WebP or MP4 which asGif() rejects.
                        // Glide auto-detects animated GIF/WebP and exposes them as Animatable drawables.
                        //
                        // CRITICAL: .override() with the layoutParams dimensions set by applyStableMediaHeight
                        // produces an exact memory-cache-key match with prefillFromSnapshot preloads
                        // (which also use override(w,h) at identical pixel values). Glide then delivers
                        // the already-decoded GifDrawable synchronously from memory cache — no blank frame.
                        val ivLpOut = binding.ivImage.layoutParams
                        val glideWOut = ivLpOut.width.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        val glideHOut = ivLpOut.height.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        if (!tryBindPreloadedMediaNow(binding.ivImage, msg, resolvedImageSource, "OUT", "GIF/STK")) {
                            val currentPlaceholder = preserveVisibleDrawablePlaceholder(binding.ivImage)
                            Glide.with(context)
                                .load(resolvedImageSource)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .fitCenter()
                                .override(glideWOut, glideHOut)
                                .transition(DrawableTransitionOptions().dontTransition()) // suppress crossfade; dontAnimate() also sets GifOptions.DISABLE_ANIMATION which kills GIF playback
                                .placeholder(currentPlaceholder ?: androidx.core.content.ContextCompat.getDrawable(context, android.R.color.transparent))
                                .error(R.drawable.ic_image_error)
                                .listener(object : RequestListener<Drawable> {
                                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                        applyBlur(binding.ivImage, false)
                                        return false
                                    }
                                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                        applyBlur(binding.ivImage, false)
                                        if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                                            val level = if (dataSource == DataSource.MEMORY_CACHE) "BIND-HIT " else "BIND-MISS"
                                            Log.d("GlyphCacheDebug", "[$level] OUT GIF/STK msgId=${msg.id.take(8)} ds=$dataSource iv=${glideWOut}x${glideHOut} model=${model.toString().take(60)}")
                                        }
                                        (resource as? android.graphics.drawable.Animatable)?.start()
                                        binding.ivImage.post {
                                            (binding.ivImage.drawable as? android.graphics.drawable.Animatable)?.start()
                                        }
                                        return false
                                    }
                                })
                                .into(binding.ivImage)
                        }
                        boundWasScrolling = false
                    } else {
                        // Non-scrolling static image (photo, video thumbnail)
                        // If exact prefetch reuse misses, show a tiny blurred preview first so
                        // the row never sits transparent while the full resource is decoded.
                        val ivLpOut = binding.ivImage.layoutParams
                        val glideWOut = ivLpOut.width.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        val glideHOut = ivLpOut.height.takeIf { it > 0 } ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
                        if (!tryBindPreloadedMediaNow(binding.ivImage, msg, resolvedImageSource, "OUT", "IMG ")) {
                            loadStaticMediaWithPreview(
                                context = context,
                                imageView = binding.ivImage,
                                message = msg,
                                resolvedImageSource = resolvedImageSource,
                                widthPx = glideWOut,
                                heightPx = glideHOut,
                                directionLabel = "OUT",
                                skipBlurredPreview = isFirstLayout
                            )
                        }
                        boundWasScrolling = false
                    }
                    boundImageSource = resolvedImageSource
                }

                if (isFastBind) {
                    bindScrollingChrome(msg, isSticker, displayStatus, position)
                    bindSelection(item, animate = false)
                    // During first layout: re-attach click listeners preserved by bindScrollingChrome.
                    if (isFirstLayout) {
                        binding.messageBubble.setOnClickListener {
                            if (selectionManager.hasSelection()) {
                                selectionManager.toggleSelection(msg.id)
                            } else {
                                val uploadProg = MediaProgressManager.getProgress(msg.id)
                                val isUploadActive = (uploadProg != null && uploadProg.isUploading && !uploadProg.isComplete)
                                    || displayStatus == MessageStatus.SENDING
                                if (isUploadActive) return@setOnClickListener
                                val playbackUri = mediaLocalFileResolver?.getPlaybackUri(msg)
                                if (msg.type == MessageType.VIDEO) {
                                    val uri = playbackUri ?: msg.localUri ?: msg.videoUrl ?: ""
                                    if (uri.isNotEmpty()) {
                                        val intent = VideoPlayerActivity.newIntent(context, uri, "Video")
                                        context.startActivity(intent)
                                    }
                                } else {
                                    val uri = playbackUri ?: msg.localUri ?: msg.imageUrl ?: ""
                                    if (uri.isNotEmpty()) {
                                        val intent = MediaViewerActivity.newIntent(context, uri, msg.timestamp, msg.id, msg.chatId)
                                        onOpenMediaViewer?.invoke(intent) ?: context.startActivity(intent)
                                    }
                                }
                            }
                        }
                        binding.messageBubble.setOnLongClickListener {
                            handleBubbleLongPress(msg.id, itemView)
                            true
                        }
                    }
                    return
                }

                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) {
                    binding.tvFileSize.visibility = View.GONE
                } else {
                    binding.tvFileSize.text = formatFileSize(msg.fileSize ?: 0)
                    binding.tvFileSize.visibility = View.VISIBLE
                }
                
                // Display caption if present (only real user captions, not default placeholders)
                if (!msg.text.isNullOrBlank() && msg.text !in DEFAULT_MEDIA_LABELS && msg.type != MessageType.KLIPY_EMOJI) {
                    binding.tvCaption.text = msg.text
                    binding.tvCaption.visibility = View.VISIBLE
                } else {
                    binding.tvCaption.visibility = View.GONE
                }

                if (isSticker) {
                    binding.layoutTimestamp.visibility = View.GONE
                    binding.layoutTimestampBelow.visibility = View.VISIBLE
                    binding.tvTimestampBelow.text = binding.tvTimestamp.text
                } else {
                    binding.layoutTimestamp.visibility = View.VISIBLE
                    binding.layoutTimestampBelow.visibility = View.GONE
                    binding.layoutTimestamp.setBackgroundResource(R.drawable.bg_timestamp_overlay)
                    val px6 = dpToPx(6f)
                    val px2 = dpToPx(2f)
                    binding.layoutTimestamp.setPadding(px6, px2, px6, px2)
                }
                
                // Upload state governs whether to show progress, retry, or play button for VIDEO
                if (msg.type == MessageType.VIDEO) {
                    val uploadProg = MediaProgressManager.getProgress(msg.id)
                    val isUploadActive = (uploadProg != null && uploadProg.isUploading && !uploadProg.isComplete)
                        || displayStatus == MessageStatus.SENDING
                    val isUploadFailed = displayStatus == MessageStatus.FAILED

                    when {
                        isUploadActive -> {
                            binding.progressIndicator.visibility = View.VISIBLE
                            binding.ivStatusAction.visibility = View.GONE
                            binding.ivPlayButton.visibility = View.GONE
                            binding.tvVideoDuration.visibility = View.GONE
                            if (uploadProg != null && !uploadProg.isIndeterminate) {
                                binding.progressIndicator.setProgress(uploadProg.progress, animate = false)
                            } else {
                                binding.progressIndicator.startIndeterminate()
                            }
                        }
                        isUploadFailed -> {
                            binding.progressIndicator.visibility = View.GONE
                            binding.ivStatusAction.visibility = View.VISIBLE
                            binding.ivPlayButton.visibility = View.GONE
                            binding.tvVideoDuration.visibility = View.GONE
                            binding.ivStatusAction.setOnClickListener {
                                onRetryUpload?.invoke(msg)
                            }
                        }
                        else -> {
                            binding.progressIndicator.visibility = View.GONE
                            binding.ivStatusAction.visibility = View.GONE
                            binding.ivPlayButton.visibility = View.VISIBLE
                            binding.tvVideoDuration.visibility = View.VISIBLE
                            binding.tvVideoDuration.text = formatDuration(msg.videoDuration ?: 0)
                        }
                    }
                } else {
                    binding.progressIndicator.visibility = View.GONE
                    binding.ivStatusAction.visibility = View.GONE
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvVideoDuration.visibility = View.GONE
                }
                
                bindReplyPreview(binding.root.context, binding.root, msg)
                
                setStatus(displayStatus)
                
                binding.messageBubble.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        val uploadProg = MediaProgressManager.getProgress(msg.id)
                        val isUploadActive = (uploadProg != null && uploadProg.isUploading && !uploadProg.isComplete)
                            || displayStatus == MessageStatus.SENDING
                        if (isUploadActive) return@setOnClickListener
                        // Resolve playback URI: prefer persistent local storage, then localUri, then remote URL
                        val playbackUri = mediaLocalFileResolver?.getPlaybackUri(msg)
                        if (msg.type == MessageType.VIDEO) {
                            val uri = playbackUri ?: msg.localUri ?: msg.videoUrl ?: ""
                            if (uri.isNotEmpty()) {
                                val intent = VideoPlayerActivity.newIntent(context, uri, "Video")
                                context.startActivity(intent)
                            }
                        } else {
                            val uri = playbackUri ?: msg.localUri ?: msg.imageUrl ?: ""
                            if (uri.isNotEmpty()) {
                                val intent = MediaViewerActivity.newIntent(
                                    context,
                                    uri,
                                    msg.timestamp,
                                    msg.id,
                                    msg.chatId
                                )
                                onOpenMediaViewer?.invoke(intent) ?: context.startActivity(intent)
                            }
                        }
                    }
                }
                
                binding.messageBubble.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                
                updateGrouping(position)
                bindSelection(item, animate = false)
            }
        }

        private fun bindScrollingChrome(msg: Message, isSticker: Boolean, displayStatus: MessageStatus, position: Int) {
            binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
            if (isSticker || msg.type == MessageType.GIF || msg.type == MessageType.MEME) {
                binding.tvFileSize.visibility = View.GONE
            } else {
                binding.tvFileSize.text = formatFileSize(msg.fileSize ?: 0)
                binding.tvFileSize.visibility = View.VISIBLE
            }

            if (!msg.text.isNullOrBlank() && msg.text !in DEFAULT_MEDIA_LABELS && msg.type != MessageType.KLIPY_EMOJI) {
                binding.tvCaption.text = msg.text
                binding.tvCaption.visibility = View.VISIBLE
            } else {
                binding.tvCaption.visibility = View.GONE
            }

            if (isSticker) {
                binding.layoutTimestamp.visibility = View.GONE
                binding.layoutTimestampBelow.visibility = View.VISIBLE
                binding.tvTimestampBelow.text = binding.tvTimestamp.text
            } else {
                binding.layoutTimestamp.visibility = View.VISIBLE
                binding.layoutTimestampBelow.visibility = View.GONE
            }

            if (msg.type == MessageType.VIDEO) {
                binding.ivPlayButton.visibility = View.VISIBLE
                binding.tvVideoDuration.visibility = View.VISIBLE
                binding.tvVideoDuration.text = formatDuration(msg.videoDuration ?: 0)
            } else {
                binding.ivPlayButton.visibility = View.GONE
                binding.tvVideoDuration.visibility = View.GONE
            }

            bindReplyPreview(binding.root.context, binding.root, msg)
            setStatus(displayStatus)
            // During actual scroll: null click listeners prevent accidental taps.
            // During first layout: preserve listeners so visible media is tappable.
            if (!isFirstLayout) {
                binding.messageBubble.setOnClickListener(null)
                binding.messageBubble.setOnLongClickListener(null)
            }
            updateGrouping(position)
        }

        private fun setStatus(status: MessageStatus) {
            if (lastStatus == status) return
            binding.ivStatus.alpha = 1.0f
            val shouldAnimate = lastStatus != null && lastStatus != MessageStatus.READ && status == MessageStatus.READ
            
            // Clear tint for READ status to show blue color, otherwise use white for media
            if (status == MessageStatus.READ) {
                binding.ivStatus.imageTintList = null
                binding.ivStatusBelow.imageTintList = null
            } else {
                binding.ivStatus.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                binding.ivStatusBelow.imageTintList = null
            }

            when (status) {
                MessageStatus.SENDING -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_clock)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_clock)
                }
                MessageStatus.SENT -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_check)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_check)
                }
                MessageStatus.DELIVERED -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_double_check)
                }
                MessageStatus.READ -> {
                    if (shouldAnimate) animateStatusToRead(binding.ivStatus, R.drawable.ic_double_check_blue)
                    else binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_double_check_blue)
                }
                MessageStatus.FAILED -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_error_outline)
                }
                else -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_check)
                    binding.ivStatusBelow.setImageResource(R.drawable.ic_check)
                }
            }
            lastStatus = status
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.messageBubble, groupPos, isIncoming = false, fullRadiusDp = 12f, imageView = binding.ivImage)
        }

        override fun refreshMedia(item: ChatListItem) {
            if (isFastBind) return
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                // Compute the current best imageSource the same way bind() does
                val localImagePath = cachedLocalFilePath(msg.chatId, msg.id, msg.type)
                val currentSource: Any? = when {
                    !localImagePath.isNullOrEmpty() -> {
                        // For VIDEO messages, prefer the JPEG thumbnail over the raw .mp4 file.
                        if (msg.type == MessageType.VIDEO && !msg.thumbnailUrl.isNullOrEmpty()) {
                            msg.thumbnailUrl
                        } else {
                            localImagePath
                        }
                    }
                    !msg.localUri.isNullOrEmpty() -> {
                        val uri = Uri.parse(msg.localUri)
                        if (uri.scheme == "file" || msg.localUri!!.startsWith("/")) {
                            val path = uri.path ?: msg.localUri!!
                            if (java.io.File(path).exists()) msg.localUri
                            else if (isAnimatedInlineMedia(msg)) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                        } else {
                            msg.localUri
                        }
                    }
                    else -> if (isAnimatedInlineMedia(msg)) msg.imageUrl ?: msg.thumbnailUrl
                            else msg.thumbnailUrl ?: msg.imageUrl
                }
                val resolvedSource = if (!localImagePath.isNullOrEmpty() || isAnimatedInlineMedia(msg)) {
                    currentSource
                } else {
                    MessagePreviewCacheManager.resolveMediaPreviewModel(msg, currentSource)
                }
                // Skip only if source is unchanged AND already at full quality AND drawable present
                val sourceUnchanged = resolvedSource == boundImageSource && boundMessageId == msg.id
                if (sourceUnchanged && !boundWasScrolling && binding.ivImage.drawable != null) return

                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    bind(item, pos, skipImageLoad = false)
                }
            }
        }

        override fun updateProgress(item: ChatListItem) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            if (msg.type != MessageType.VIDEO) return
            val uploadProg = MediaProgressManager.getProgress(msg.id)
            val status = rememberStrongestOutgoingStatus(msg)
            val isUploadActive = (uploadProg != null && uploadProg.isUploading && !uploadProg.isComplete)
                || status == MessageStatus.SENDING
            val isUploadFailed = status == MessageStatus.FAILED

            when {
                isUploadActive -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.ivStatusAction.visibility = View.GONE
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvVideoDuration.visibility = View.GONE
                    if (uploadProg != null && !uploadProg.isIndeterminate) {
                        binding.progressIndicator.setProgress(uploadProg.progress, animate = true)
                    } else {
                        binding.progressIndicator.startIndeterminate()
                    }
                }
                isUploadFailed -> {
                    binding.progressIndicator.visibility = View.GONE
                    binding.ivStatusAction.visibility = View.VISIBLE
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvVideoDuration.visibility = View.GONE
                    binding.ivStatusAction.setOnClickListener { onRetryUpload?.invoke(msg) }
                }
                else -> {
                    binding.progressIndicator.visibility = View.GONE
                    binding.ivStatusAction.visibility = View.GONE
                    binding.ivPlayButton.visibility = View.VISIBLE
                    binding.tvVideoDuration.visibility = View.VISIBLE
                    binding.tvVideoDuration.text = formatDuration(msg.videoDuration ?: 0)
                }
            }
        }

        fun clearMediaState() {
            // Only clear Glide request, preserve drawable for potential rebind to same message
            Glide.with(binding.ivImage).clear(binding.ivImage)
            applyBlur(binding.ivImage, false)
            boundImageSource = null
            boundWasScrolling = false
            boundMessageId = null
        }

        private fun animateStatusToRead(iconView: android.widget.ImageView, @androidx.annotation.DrawableRes readIcon: Int) {
            iconView.animate().cancel()
            iconView.animate()
                .rotationY(90f)
                .setDuration(100)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .withEndAction {
                    iconView.setImageResource(readIcon)
                    iconView.rotationY = -90f
                    iconView.animate()
                        .rotationY(0f)
                        .setDuration(100)
                        .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .start()
                }
                .start()
        }
    }

    // ======================== VIDEO NOTE VIEW HOLDERS ========================

    inner class OutgoingVideoNoteViewHolder(private val binding: ItemMessageOutgoingVideoNoteBinding) : BaseViewHolder(binding) {
        private var lastStatus: MessageStatus? = null
        private var player: androidx.media3.exoplayer.ExoPlayer? = null
        private var isPlaying = false
        private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var progressRunnable: Runnable? = null
        private var boundMessageId: String? = null
        // Track loaded thumbnail to avoid redundant Glide reloads
        private var boundThumbnailModel: Any? = null
        private var boundThumbnailWasScrolling: Boolean = false
        private var boundChromeWasScrolling: Boolean = false
        private val TAG = "OutgoingVideoNote"

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val context = itemView.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = false)


                // If rebound to a different message, clean up old player
                if (boundMessageId != null && boundMessageId != msg.id) {
                    releasePlayer()
                    isPlaying = false
                    // Hide playerView immediately — it may still be visible from previous playback
                    binding.playerView.visibility = View.GONE
                    boundThumbnailModel = null
                    boundThumbnailWasScrolling = false
                }
                boundMessageId = msg.id

                // Load circular thumbnail — prefer localUri for video notes to avoid
                // orientation issues with server-generated thumbnails
                val thumbnailModel = resolveVideoNoteThumbnailModel(msg)
                // FLICKER GUARD: Skip thumbnail reload if same URL is already loaded at full quality
                val thumbnailAlreadyLoaded = thumbnailModel == boundThumbnailModel && binding.ivThumbnail.drawable != null && !boundThumbnailWasScrolling
                if (!skipImageLoad && thumbnailModel != null && thumbnailModel.toString().isNotEmpty() && !thumbnailAlreadyLoaded) {
                    if (tryBindPreloadedVideoNoteThumbnail(binding.ivThumbnail, msg, thumbnailModel, "OUT")) {
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = false
                    } else if (isFastBind) {
                        // Low-res blurred thumbnail only during scroll; SCROLL_STOPPED triggers refreshMedia → full quality.
                        Glide.with(context)
                            .asBitmap()
                            .load(thumbnailModel)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(96, 96)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .disallowHardwareConfig()
                            .centerCrop()
                            .circleCrop()
                            .placeholder(androidx.core.content.ContextCompat.getDrawable(context, android.R.color.black))
                            .into(binding.ivThumbnail)
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = true
                    } else {
                        ChatOpenTrace.event(msg.chatId, "video_note_glide_start", "dir=OUT msg=${msg.id.take(8)} model=${thumbnailModel.toString().take(60)}")
                        Glide.with(context)
                            .asBitmap()
                            .load(thumbnailModel)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .circleCrop()
                            .placeholder(androidx.core.content.ContextCompat.getDrawable(context, android.R.color.black))
                            .error(android.R.color.black)
                            .listener(object : RequestListener<android.graphics.Bitmap> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.Bitmap>, isFirstResource: Boolean): Boolean {
                                    ChatOpenTrace.event(msg.chatId, "video_note_glide_failed", "dir=OUT msg=${msg.id.take(8)} error=${e?.rootCauses?.firstOrNull()?.javaClass?.simpleName ?: e?.javaClass?.simpleName ?: "unknown"}")
                                    return false
                                }

                                override fun onResourceReady(resource: android.graphics.Bitmap, model: Any, target: Target<android.graphics.Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                    ChatOpenTrace.event(msg.chatId, "video_note_glide_ready", "dir=OUT msg=${msg.id.take(8)} ds=$dataSource size=${resource.width}x${resource.height}")
                                    return false
                                }
                            })
                            .into(binding.ivThumbnail)
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = false
                    }
                } else if (!skipImageLoad && (thumbnailModel == null || thumbnailModel.toString().isEmpty())) {
                    binding.ivThumbnail.setImageResource(android.R.color.black)
                    boundThumbnailModel = null
                    boundThumbnailWasScrolling = false
                }

                if (isFastBind) {
                    bindScrollingChrome(msg)
                    bindSelection(item, animate = false)
                    return
                }
                boundChromeWasScrolling = false

                // Upload progress check
                val uploadProgress = MediaProgressManager.getProgress(msg.id)
                val isUploading = uploadProgress != null && uploadProgress.isUploading && !uploadProgress.isComplete
                val isSending = rememberStrongestOutgoingStatus(msg) == MessageStatus.SENDING

                if (isUploading) {
                    // Active upload progress — show determinate or indeterminate
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvDuration.visibility = View.GONE
                    if (uploadProgress!!.isIndeterminate) {
                        binding.progressIndicator.startIndeterminate()
                    } else {
                        binding.progressIndicator.setProgress(uploadProgress.progress, animate = false)
                    }
                } else if (isSending && uploadProgress == null) {
                    // Sending but no progress entry yet — show indeterminate spinner
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.progressIndicator.startIndeterminate()
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvDuration.visibility = View.GONE
                } else {
                    // No upload in progress — show normal state
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
                    binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
                    binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
                }

                // Playback ring hidden unless playing
                if (!isPlaying) {
                    binding.playbackRing.visibility = View.GONE
                    binding.borderRing.visibility = View.VISIBLE
                }

                // Timestamp + status
                binding.tvTimestamp.text = msg.formattedTime
                setStatus(rememberStrongestOutgoingStatus(msg))

                // Click to play/pause inline
                binding.videoNoteContainer.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                        return@setOnClickListener
                    }
                    // Don't allow playback while uploading
                    if (isUploading || isSending) {
                        return@setOnClickListener
                    }
                    if (isPlaying) {
                        stopPlayback()
                    } else {
                        // For video notes, prioritize localUri (already on device) over Firebase URL
                        val videoUrl = msg.localUri ?: msg.videoUrl
                        if (videoUrl == null) {
                            android.util.Log.w(TAG, "Click ignored: no video URL available")
                            return@setOnClickListener
                        }
                        val urlType = if (msg.localUri != null) "localUri" else "firebaseUrl"
                        startPlayback(context, videoUrl)
                    }
                }

                // Long click for selection
                binding.videoNoteContainer.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }

                // Handle selection visual
                val isSelected = selectionManager.isSelected(msg.id)
                itemView.setBackgroundColor(if (isSelected) 0x30FFFFFF else 0x00000000)
            }
        }

        private fun bindScrollingChrome(msg: Message) {
            binding.progressIndicator.visibility = View.GONE
            binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
            binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
            binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
            if (!isPlaying) {
                binding.playbackRing.visibility = View.GONE
                binding.borderRing.visibility = View.VISIBLE
            }
            binding.tvTimestamp.text = msg.formattedTime
            setStatus(rememberStrongestOutgoingStatus(msg))
            bindReplyPreview(binding.root.context, binding.root, msg)
            binding.videoNoteContainer.setOnClickListener(null)
            binding.videoNoteContainer.setOnLongClickListener(null)
            boundChromeWasScrolling = true
        }

        private fun startPlayback(context: android.content.Context, videoUrl: String) {
            
            // Clean up any lingering player state first
            releasePlayer()

            isPlaying = true
            binding.ivPlayButton.visibility = View.GONE
            binding.tvDuration.visibility = View.GONE

            // Show playback ring, hide static border
            binding.playbackRing.visibility = View.VISIBLE
            binding.playbackRing.setPlaybackProgress(0f)
            binding.borderRing.visibility = View.GONE

            // Make player visible and circular
            binding.playerView.visibility = View.VISIBLE
            binding.playerView.clipToOutline = true
            binding.playerView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            

            // Animate container to expanded size
            // Container is 252dp, inner content is 240dp (12dp gap for ring)
            val containerSize = binding.videoNoteContainer.width
            val expandedContainer = (312 * itemView.resources.displayMetrics.density).toInt()
            val gap = (12 * itemView.resources.displayMetrics.density).toInt()
            val animator = android.animation.ValueAnimator.ofInt(containerSize, expandedContainer)
            animator.duration = 250
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                val innerSize = size - gap
                val params = binding.videoNoteContainer.layoutParams
                params.width = size
                params.height = size
                binding.videoNoteContainer.layoutParams = params

                // Inner children (video, thumbnail, border) stay smaller
                listOf(binding.ivThumbnail, binding.playerView, binding.borderRing).forEach { v ->
                    val vp = v.layoutParams
                    vp.width = innerSize
                    vp.height = innerSize
                    v.layoutParams = vp
                }
                // Ring tracks full container size
                val rp = binding.playbackRing.layoutParams
                rp.width = size
                rp.height = size
                binding.playbackRing.layoutParams = rp
            }
            animator.start()

            val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
            val mediaItem = androidx.media3.common.MediaItem.fromUri(videoUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateStr = when (playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                }
                
                override fun onIsPlayingChanged(playing: Boolean) {
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e(TAG, "Playback error: ${error.errorCode}, msg=${error.message}", error)
                    stopPlayback()
                }
            })
            binding.playerView.player = exoPlayer
            
            exoPlayer.prepare()
            
            exoPlayer.playWhenReady = true
            
            player = exoPlayer

            // Start frame-accurate playback progress polling
            startProgressPolling(exoPlayer)
        }

        private fun startProgressPolling(exoPlayer: androidx.media3.exoplayer.ExoPlayer) {
            stopProgressPolling()
            val runnable = object : Runnable {
                override fun run() {
                    val p = player ?: return
                    val duration = p.duration
                    val position = p.currentPosition
                    if (duration > 0) {
                        val progress = position.toFloat() / duration.toFloat()
                        binding.playbackRing.setPlaybackProgress(progress.coerceIn(0f, 1f))
                    }
                    progressHandler.postDelayed(this, 16L) // ~60fps
                }
            }
            progressRunnable = runnable
            progressHandler.post(runnable)
        }

        private fun stopProgressPolling() {
            progressRunnable?.let { progressHandler.removeCallbacks(it) }
            progressRunnable = null
        }

        /**
         * Release player resources safely — detach from PlayerView BEFORE releasing.
         */
        private fun releasePlayer() {
            stopProgressPolling()
            // CRITICAL: detach from view FIRST, then release.
            // Releasing while still attached corrupts the PlayerView's Surface,
            // causing blank playback on subsequent plays.
            binding.playerView.player = null
            player?.release()
            player = null
        }

        private fun stopPlayback() {
            isPlaying = false
            releasePlayer()
            binding.playerView.visibility = View.GONE
            binding.ivPlayButton.visibility = View.VISIBLE
            binding.tvDuration.visibility = View.VISIBLE

            // Hide playback ring, restore static border
            binding.playbackRing.visibility = View.GONE
            binding.playbackRing.setPlaybackProgress(0f)
            binding.borderRing.visibility = View.VISIBLE

            // Animate back to original size
            val containerSize = binding.videoNoteContainer.width
            val normalContainer = (252 * itemView.resources.displayMetrics.density).toInt()
            val gap = (12 * itemView.resources.displayMetrics.density).toInt()
            val animator = android.animation.ValueAnimator.ofInt(containerSize, normalContainer)
            animator.duration = 200
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                val innerSize = size - gap
                val params = binding.videoNoteContainer.layoutParams
                params.width = size
                params.height = size
                binding.videoNoteContainer.layoutParams = params

                listOf(binding.ivThumbnail, binding.playerView, binding.borderRing).forEach { v ->
                    val vp = v.layoutParams
                    vp.width = innerSize
                    vp.height = innerSize
                    v.layoutParams = vp
                }
                val rp = binding.playbackRing.layoutParams
                rp.width = size
                rp.height = size
                binding.playbackRing.layoutParams = rp
            }
            animator.start()
        }

        private fun setStatus(status: MessageStatus) {
            if (lastStatus == status) return
            if (status == MessageStatus.READ) {
                binding.ivStatus.imageTintList = null
            } else {
                binding.ivStatus.imageTintList = android.content.res.ColorStateList.valueOf(0x80FFFFFF.toInt())
            }
            when (status) {
                MessageStatus.SENDING -> binding.ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT -> binding.ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ -> binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                MessageStatus.FAILED -> binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                else -> binding.ivStatus.setImageResource(R.drawable.ic_check)
            }
            lastStatus = status
        }

        override fun updateProgress(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val uploadProgress = MediaProgressManager.getProgress(msg.id)
                val isUploading = uploadProgress != null && uploadProgress.isUploading && !uploadProgress.isComplete
                val isSending = rememberStrongestOutgoingStatus(msg) == MessageStatus.SENDING

                if (isUploading) {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvDuration.visibility = View.GONE
                    if (uploadProgress!!.isIndeterminate) {
                        binding.progressIndicator.startIndeterminate()
                    } else {
                        binding.progressIndicator.setProgress(uploadProgress.progress, animate = true)
                    }
                } else if (isSending && uploadProgress == null) {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.progressIndicator.startIndeterminate()
                    binding.ivPlayButton.visibility = View.GONE
                    binding.tvDuration.visibility = View.GONE
                } else {
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
                    binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
                    binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
                }
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
        }

        override fun refreshMedia(item: ChatListItem) {
            if (isFastBind) return
            // Only rebind if the thumbnail was loaded at low-res while scrolling.
            if (!boundThumbnailWasScrolling && !boundChromeWasScrolling) return
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                bind(item, pos, skipImageLoad = false)
            }
        }

        override fun onViewDetached() {
            releasePlayer()
            isPlaying = false
            // Ensure playerView is hidden so the recycled ViewHolder starts in a clean state
            binding.playerView.visibility = View.GONE
        }
    }

    inner class IncomingVideoNoteViewHolder(private val binding: ItemMessageIncomingVideoNoteBinding) : BaseViewHolder(binding) {
        private var player: androidx.media3.exoplayer.ExoPlayer? = null
        private var isPlaying = false
        private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var progressRunnable: Runnable? = null
        private var boundMessageId: String? = null
        // Track loaded thumbnail to avoid redundant Glide reloads
        private var boundThumbnailModel: Any? = null
        private var boundThumbnailWasScrolling: Boolean = false
        private var boundChromeWasScrolling: Boolean = false
        private val TAG = "IncomingVideoNote"

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val context = itemView.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = true)


                // If rebound to a different message, clean up old player
                if (boundMessageId != null && boundMessageId != msg.id) {
                    releasePlayer()
                    isPlaying = false
                    // Hide playerView immediately — it may still be visible from previous playback
                    binding.playerView.visibility = View.GONE
                    boundThumbnailModel = null
                    boundThumbnailWasScrolling = false
                }
                boundMessageId = msg.id

                // Load circular thumbnail — prefer localUri for video notes to avoid
                // orientation issues with server-generated thumbnails
                val thumbnailModel = resolveVideoNoteThumbnailModel(msg)
                // FLICKER GUARD: Skip thumbnail reload if same URL is already loaded at full quality
                val thumbnailAlreadyLoaded = thumbnailModel == boundThumbnailModel && binding.ivThumbnail.drawable != null && !boundThumbnailWasScrolling
                if (!skipImageLoad && thumbnailModel != null && thumbnailModel.toString().isNotEmpty() && !thumbnailAlreadyLoaded) {
                    if (tryBindPreloadedVideoNoteThumbnail(binding.ivThumbnail, msg, thumbnailModel, "IN")) {
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = false
                    } else if (isFastBind) {
                        // Low-res blurred thumbnail only during scroll; SCROLL_STOPPED triggers refreshMedia → full quality.
                        Glide.with(context)
                            .asBitmap()
                            .load(thumbnailModel)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(96, 96)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .disallowHardwareConfig()
                            .centerCrop()
                            .circleCrop()
                            .placeholder(androidx.core.content.ContextCompat.getDrawable(context, android.R.color.black))
                            .into(binding.ivThumbnail)
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = true
                    } else {
                        ChatOpenTrace.event(msg.chatId, "video_note_glide_start", "dir=IN msg=${msg.id.take(8)} model=${thumbnailModel.toString().take(60)}")
                        Glide.with(context)
                            .asBitmap()
                            .load(thumbnailModel)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .circleCrop()
                            .placeholder(androidx.core.content.ContextCompat.getDrawable(context, android.R.color.black))
                            .error(android.R.color.black)
                            .listener(object : RequestListener<android.graphics.Bitmap> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.Bitmap>, isFirstResource: Boolean): Boolean {
                                    ChatOpenTrace.event(msg.chatId, "video_note_glide_failed", "dir=IN msg=${msg.id.take(8)} error=${e?.rootCauses?.firstOrNull()?.javaClass?.simpleName ?: e?.javaClass?.simpleName ?: "unknown"}")
                                    return false
                                }

                                override fun onResourceReady(resource: android.graphics.Bitmap, model: Any, target: Target<android.graphics.Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                    ChatOpenTrace.event(msg.chatId, "video_note_glide_ready", "dir=IN msg=${msg.id.take(8)} ds=$dataSource size=${resource.width}x${resource.height}")
                                    return false
                                }
                            })
                            .into(binding.ivThumbnail)
                        boundThumbnailModel = thumbnailModel
                        boundThumbnailWasScrolling = false
                    }
                } else if (!skipImageLoad && (thumbnailModel == null || thumbnailModel.toString().isEmpty())) {
                    binding.ivThumbnail.setImageResource(android.R.color.black)
                    boundThumbnailModel = null
                    boundThumbnailWasScrolling = false
                }

                if (isFastBind) {
                    bindScrollingChrome(msg)
                    bindSelection(item, animate = false)
                    return
                }
                boundChromeWasScrolling = false

                // Download progress check
                val downloadProgress = MediaProgressManager.getProgress(msg.id)
                val isDownloading = downloadProgress != null && !downloadProgress.isUploading && !downloadProgress.isComplete
                val hasLocalMedia = !msg.localUri.isNullOrEmpty() || (mediaLocalFileResolver?.isReadyForPlayback(msg) == true)

                when {
                    hasLocalMedia -> {
                        binding.ivDownloadButton.visibility = View.GONE
                        binding.progressIndicator.visibility = View.GONE
                        binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
                        binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
                        binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
                    }
                    isDownloading -> {
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.ivDownloadButton.visibility = View.GONE
                        binding.ivPlayButton.visibility = View.GONE
                        binding.tvDuration.visibility = View.GONE
                        if (downloadProgress!!.isIndeterminate) {
                            binding.progressIndicator.startIndeterminate()
                        } else {
                            binding.progressIndicator.setProgress(downloadProgress.progress, animate = false)
                        }
                    }
                    else -> {
                        binding.ivDownloadButton.visibility = View.VISIBLE
                        binding.progressIndicator.visibility = View.GONE
                        binding.ivPlayButton.visibility = View.GONE
                        binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
                        binding.tvDuration.visibility = View.VISIBLE
                    }
                }

                // Download button action
                binding.ivDownloadButton.setOnClickListener { mediaDownloadListener?.onDownloadClicked(msg) }

                // Playback ring hidden unless playing
                if (!isPlaying) {
                    binding.playbackRing.visibility = View.GONE
                    binding.borderRing.visibility = View.VISIBLE
                }

                // Timestamp
                binding.tvTimestamp.text = msg.formattedTime

                // Click to play/pause inline
                binding.videoNoteContainer.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                        return@setOnClickListener
                    }
                    // Don't allow playback while downloading
                    if (isDownloading) {
                        return@setOnClickListener
                    }
                    if (!hasLocalMedia && msg.videoUrl.isNullOrEmpty()) {
                        android.util.Log.w(TAG, "Click ignored: no local media and no video URL")
                        return@setOnClickListener
                    }
                    if (isPlaying) {
                        stopPlayback()
                    } else {
                        val videoUrl = mediaLocalFileResolver?.getPlaybackUri(msg) ?: msg.localUri ?: msg.videoUrl
                        if (videoUrl == null) {
                            android.util.Log.w(TAG, "Click ignored: could not resolve video URL")
                            return@setOnClickListener
                        }
                        val urlSource = when {
                            mediaLocalFileResolver?.getPlaybackUri(msg) != null -> "mediaLocalFileResolver"
                            msg.localUri != null -> "localUri"
                            else -> "firebaseUrl"
                        }
                        startPlayback(context, videoUrl)
                    }
                }

                binding.videoNoteContainer.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }

                val isSelected = selectionManager.isSelected(msg.id)
                itemView.setBackgroundColor(if (isSelected) 0x30FFFFFF else 0x00000000)
            }
        }

        private fun bindScrollingChrome(msg: Message) {
            binding.ivDownloadButton.visibility = View.GONE
            binding.progressIndicator.visibility = View.GONE
            binding.tvDuration.text = formatDuration(msg.videoDuration ?: 0)
            binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
            binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
            if (!isPlaying) {
                binding.playbackRing.visibility = View.GONE
                binding.borderRing.visibility = View.VISIBLE
            }
            binding.tvTimestamp.text = msg.formattedTime
            bindReplyPreview(binding.root.context, binding.root, msg)
            binding.ivDownloadButton.setOnClickListener(null)
            binding.videoNoteContainer.setOnClickListener(null)
            binding.videoNoteContainer.setOnLongClickListener(null)
            boundChromeWasScrolling = true
        }

        private fun startPlayback(context: android.content.Context, videoUrl: String) {
            
            // Clean up any lingering player state first
            releasePlayer()

            isPlaying = true
            binding.ivPlayButton.visibility = View.GONE
            binding.tvDuration.visibility = View.GONE

            // Show playback ring, hide static border
            binding.playbackRing.visibility = View.VISIBLE
            binding.playbackRing.setPlaybackProgress(0f)
            binding.borderRing.visibility = View.GONE

            binding.playerView.visibility = View.VISIBLE
            binding.playerView.clipToOutline = true
            binding.playerView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            

            // Animate container to expanded size
            // Container is 252dp, inner content is 240dp (12dp gap for ring)
            val containerSize = binding.videoNoteContainer.width
            val expandedContainer = (312 * itemView.resources.displayMetrics.density).toInt()
            val gap = (12 * itemView.resources.displayMetrics.density).toInt()
            val animator = android.animation.ValueAnimator.ofInt(containerSize, expandedContainer)
            animator.duration = 250
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                val innerSize = size - gap
                val params = binding.videoNoteContainer.layoutParams
                params.width = size
                params.height = size
                binding.videoNoteContainer.layoutParams = params

                listOf(binding.ivThumbnail, binding.playerView, binding.borderRing).forEach { v ->
                    val vp = v.layoutParams
                    vp.width = innerSize
                    vp.height = innerSize
                    v.layoutParams = vp
                }
                val rp = binding.playbackRing.layoutParams
                rp.width = size
                rp.height = size
                binding.playbackRing.layoutParams = rp
            }
            animator.start()

            val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
            val mediaItem = androidx.media3.common.MediaItem.fromUri(videoUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateStr = when (playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                }
                
                override fun onIsPlayingChanged(playing: Boolean) {
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e(TAG, "Playback error: ${error.errorCode}, msg=${error.message}", error)
                    stopPlayback()
                }
            })
            binding.playerView.player = exoPlayer
            
            exoPlayer.prepare()
            
            exoPlayer.playWhenReady = true
            
            player = exoPlayer

            // Start frame-accurate playback progress polling
            startProgressPolling(exoPlayer)
        }

        private fun startProgressPolling(exoPlayer: androidx.media3.exoplayer.ExoPlayer) {
            stopProgressPolling()
            val runnable = object : Runnable {
                override fun run() {
                    val p = player ?: return
                    val duration = p.duration
                    val position = p.currentPosition
                    if (duration > 0) {
                        val progress = position.toFloat() / duration.toFloat()
                        binding.playbackRing.setPlaybackProgress(progress.coerceIn(0f, 1f))
                    }
                    progressHandler.postDelayed(this, 16L) // ~60fps
                }
            }
            progressRunnable = runnable
            progressHandler.post(runnable)
        }

        private fun stopProgressPolling() {
            progressRunnable?.let { progressHandler.removeCallbacks(it) }
            progressRunnable = null
        }

        /**
         * Release player resources safely — detach from PlayerView BEFORE releasing.
         */
        private fun releasePlayer() {
            stopProgressPolling()
            // CRITICAL: detach from view FIRST, then release.
            // Releasing while still attached corrupts the PlayerView's Surface,
            // causing blank playback on subsequent plays.
            binding.playerView.player = null
            player?.release()
            player = null
        }

        private fun stopPlayback() {
            isPlaying = false
            releasePlayer()
            binding.playerView.visibility = View.GONE
            binding.ivPlayButton.visibility = View.VISIBLE
            binding.tvDuration.visibility = View.VISIBLE

            // Hide playback ring, restore static border
            binding.playbackRing.visibility = View.GONE
            binding.playbackRing.setPlaybackProgress(0f)
            binding.borderRing.visibility = View.VISIBLE

            val containerSize = binding.videoNoteContainer.width
            val normalContainer = (252 * itemView.resources.displayMetrics.density).toInt()
            val gap = (12 * itemView.resources.displayMetrics.density).toInt()
            val animator = android.animation.ValueAnimator.ofInt(containerSize, normalContainer)
            animator.duration = 200
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                val innerSize = size - gap
                val params = binding.videoNoteContainer.layoutParams
                params.width = size
                params.height = size
                binding.videoNoteContainer.layoutParams = params

                listOf(binding.ivThumbnail, binding.playerView, binding.borderRing).forEach { v ->
                    val vp = v.layoutParams
                    vp.width = innerSize
                    vp.height = innerSize
                    v.layoutParams = vp
                }
                val rp = binding.playbackRing.layoutParams
                rp.width = size
                rp.height = size
                binding.playbackRing.layoutParams = rp
            }
            animator.start()
        }

        override fun updateProgress(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val downloadProgress = MediaProgressManager.getProgress(msg.id)
                val isDownloading = downloadProgress != null && !downloadProgress.isUploading && !downloadProgress.isComplete
                val hasLocalMedia = !msg.localUri.isNullOrEmpty() || (mediaLocalFileResolver?.isReadyForPlayback(msg) == true)

                when {
                    hasLocalMedia -> {
                        binding.ivDownloadButton.visibility = View.GONE
                        binding.progressIndicator.visibility = View.GONE
                        binding.ivPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
                        binding.tvDuration.visibility = if (isPlaying) View.GONE else View.VISIBLE
                    }
                    isDownloading -> {
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.ivDownloadButton.visibility = View.GONE
                        binding.ivPlayButton.visibility = View.GONE
                        binding.tvDuration.visibility = View.GONE
                        if (downloadProgress!!.isIndeterminate || downloadProgress.progress <= 0f) {
                            binding.progressIndicator.startIndeterminate()
                        } else {
                            binding.progressIndicator.setProgress(downloadProgress.progress, animate = true)
                        }
                    }
                    else -> {
                        binding.ivDownloadButton.visibility = View.VISIBLE
                        binding.progressIndicator.visibility = View.GONE
                        binding.ivPlayButton.visibility = View.GONE
                    }
                }
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
        }

        override fun refreshMedia(item: ChatListItem) {
            if (isFastBind) return
            // Only rebind if the thumbnail was loaded at low-res while scrolling.
            if (!boundThumbnailWasScrolling && !boundChromeWasScrolling) return
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                bind(item, pos, skipImageLoad = false)
            }
        }

        override fun onViewDetached() {
            releasePlayer()
            isPlaying = false
            // Ensure playerView is hidden so the recycled ViewHolder starts in a clean state
            binding.playerView.visibility = View.GONE
        }
    }

    inner class IncomingCollageViewHolder(private val binding: ItemMessageIncomingCollageBinding) : BaseViewHolder(binding) {
        private var boundChromeWasScrolling: Boolean = false

        init {
            binding.collageView.preWarmImageViews()
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                initializeColors(itemView.context)
                bindForwardedLabel(msg, isIncoming = true)
                if (!isScrolling) bindMediaForwardButton(msg)
                
                val mediaItems = msg.mediaItemsList
                
                if (mediaItems.size < 2) {
                    // Fallback for invalid collage state
                    setVisibility(binding.root, View.GONE)
                    return
                }
                setVisibility(binding.root, View.VISIBLE)
                
                binding.collageView.preloadedMediaDrawableProvider = preloadedMediaDrawableProvider
                applyStableCollageWidth(binding.collageView)

                if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                    Log.d(
                        "GlyphCacheDebug",
                        "[CollageBind] IN msg=${msg.id.take(8)} total=${mediaItems.size} " +
                            "visible=${mediaItems.size.coerceAtMost(4)} scrolling=$isScrolling"
                    )
                }

                // Bind collage
                binding.collageView.bind(
                    items = mediaItems,
                    messageId = msg.id,
                    scrolling = isScrolling,
                    onClick = if (isFastBind) null else { index ->
                        openImageViewer(msg, index)
                    },
                    onLongClick = if (isFastBind) null else { _ ->
                        handleBubbleLongPress(msg.id, itemView)
                        true
                    }
                )
                
                // Calculate combined file size
                val totalSizeBytes = cachedMediaGroupTotalSize(msg, mediaItems)
                if (totalSizeBytes > 0) {
                    binding.tvFileSize.text = formatFileSize(totalSizeBytes)
                    binding.tvFileSize.visibility = View.VISIBLE
                } else {
                    binding.tvFileSize.visibility = View.GONE
                }
                
                // Timestamp
                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                
                binding.ivAvatar.visibility = View.GONE

                if (isFastBind) {
                    bindReplyPreview(binding.root.context, binding.root, msg)
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvProgressText.visibility = View.GONE
                    binding.messageBubble.setOnClickListener(null)
                    binding.messageBubble.setOnLongClickListener(null)
                    updateGrouping(position)
                    boundChromeWasScrolling = true
                    bindSelection(item, animate = false)
                    return
                }
                boundChromeWasScrolling = false
                
                // Click and long click handlers
                binding.messageBubble.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        // Open image viewer on click
                        openImageViewer(msg, 0)
                    }
                }
                
                binding.messageBubble.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                
                // Reply preview
                bindReplyPreview(binding.root.context, binding.root, msg)

                // Progress indicator (downloads)
                updateProgressUi(msg, mediaItems)
                
                // Apply grouping and selection
                updateGrouping(position)
                maybeBounceIncoming(item, binding.messageBubble)
                bindSelection(item, animate = false)
            }
        }
        
        override fun refreshMedia(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val mediaItems = item.message.mediaItemsList
                if (boundChromeWasScrolling) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        bind(item, pos, skipImageLoad = false)
                    }
                    return
                }
                // Only bind if we have at least 2 images for collage
                if (mediaItems.size >= 2) {
                    if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                        Log.d(
                            "GlyphCacheDebug",
                            "[CollageBind] IN refresh msg=${item.message.id.take(8)} total=${mediaItems.size} " +
                                "visible=${mediaItems.size.coerceAtMost(4)} scrolling=false"
                        )
                    }
                    applyStableCollageWidth(binding.collageView)
                    binding.collageView.bind(
                        items = mediaItems,
                        messageId = item.message.id,
                        scrolling = false,
                        onClick = { index ->
                            if (selectionManager.hasSelection()) {
                                selectionManager.toggleSelection(item.message.id)
                            } else {
                                openImageViewer(item.message, index)
                            }
                        },
                        onLongClick = { _ ->
                            handleBubbleLongPress(item.message.id, itemView)
                            true
                        }
                    )
                }
                updateProgressUi(item.message, mediaItems)
            }
        }

        override fun updateProgress(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val mediaItems = item.message.mediaItemsList
                if (mediaItems.size >= 2) {
                    updateProgressUi(item.message, mediaItems)
                }
            }
        }

        private fun updateProgressUi(msg: Message, mediaItems: List<MediaItem>) {
            val progress = MediaProgressManager.getProgress(msg.id)
            val isUploading = progress != null && progress.isUploading && !progress.isComplete
            val hasRemoteItems = mediaItems.isNotEmpty() && mediaItems.all { it.url.isNotBlank() }
            val isSentState = when (msg.status) {
                MessageStatus.SENT,
                MessageStatus.DELIVERED,
                MessageStatus.READ,
                MessageStatus.PLAYED -> true
                else -> false
            }

            when {
                !isUploading && (hasRemoteItems || isSentState) -> {
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvProgressText.visibility = View.GONE
                }
                progress != null -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.tvProgressText.visibility = View.VISIBLE
                    if (progress.isIndeterminate) {
                        binding.progressIndicator.startIndeterminate()
                        binding.tvProgressText.visibility = View.GONE
                    } else {
                        val percent = progress.progress.toInt().coerceIn(0, 99)
                        binding.progressIndicator.setProgress(percent.toFloat(), animate = false)
                        binding.tvProgressText.text = "$percent%"
                    }
                }
                else -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.tvProgressText.visibility = View.GONE
                    binding.progressIndicator.startIndeterminate()
                }
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.messageBubble, groupPos, isIncoming = true, fullRadiusDp = 12f, imageView = binding.collageView)
        }

        override fun onViewDetached() {
            // A detached collage is often just one scroll away from returning. Keep the
            // rendered four-tile state intact so it does not re-enter as an empty 2-cell row.
        }

        fun clearMediaState() {
            binding.collageView.clearForRecycle()
        }
    }

    inner class OutgoingCollageViewHolder(private val binding: ItemMessageOutgoingCollageBinding) : BaseViewHolder(binding) {
        private var boundChromeWasScrolling: Boolean = false

        init {
            binding.collageView.preWarmImageViews()
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                initializeColors(itemView.context)
                bindForwardedLabel(msg, isIncoming = false)
                if (!isScrolling) bindMediaForwardButton(msg)
                
                val mediaItems = msg.mediaItemsList
                
                if (mediaItems.size < 2) {
                    setVisibility(binding.root, View.GONE)
                    return
                }
                setVisibility(binding.root, View.VISIBLE)
                
                binding.collageView.preloadedMediaDrawableProvider = preloadedMediaDrawableProvider
                applyStableCollageWidth(binding.collageView)

                if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                    Log.d(
                        "GlyphCacheDebug",
                        "[CollageBind] OUT msg=${msg.id.take(8)} total=${mediaItems.size} " +
                            "visible=${mediaItems.size.coerceAtMost(4)} scrolling=$isScrolling"
                    )
                }

                // Bind collage
                binding.collageView.bind(
                    items = mediaItems,
                    messageId = msg.id,
                    scrolling = isScrolling,
                    onClick = if (isFastBind) null else { index ->
                        if (selectionManager.hasSelection()) {
                            selectionManager.toggleSelection(msg.id)
                        } else {
                            openImageViewer(msg, index)
                        }
                    },
                    onLongClick = if (isFastBind) null else { _ ->
                        handleBubbleLongPress(msg.id, itemView)
                        true
                    }
                )
                
                // Calculate combined file size
                val totalSizeBytes = cachedMediaGroupTotalSize(msg, mediaItems)
                if (totalSizeBytes > 0) {
                    binding.tvFileSize.text = formatFileSize(totalSizeBytes)
                    binding.tvFileSize.visibility = View.VISIBLE
                } else {
                    binding.tvFileSize.visibility = View.GONE
                }
                
                // Timestamp and status
                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                updateMessageStatus(binding.ivStatus, rememberStrongestOutgoingStatus(msg))

                if (isFastBind) {
                    bindReplyPreview(binding.root.context, binding.root, msg)
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvProgressText.visibility = View.GONE
                    binding.messageBubble.setOnClickListener(null)
                    binding.messageBubble.setOnLongClickListener(null)
                    updateGrouping(position)
                    boundChromeWasScrolling = true
                    bindSelection(item, animate = false)
                    return
                }
                boundChromeWasScrolling = false
                
                // Click and long click handlers
                binding.messageBubble.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        // Open image viewer on click
                        openImageViewer(msg, 0)
                    }
                }
                
                binding.messageBubble.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                
                // Reply preview
                bindReplyPreview(binding.root.context, binding.root, msg)

                // Progress indicator (uploads)
                updateProgressUi(msg, mediaItems)
                
                // Apply grouping and selection
                updateGrouping(position)
                bindSelection(item, animate = false)
            }
        }
        
        override fun refreshMedia(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val mediaItems = item.message.mediaItemsList
                if (boundChromeWasScrolling) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        bind(item, pos, skipImageLoad = false)
                    }
                    return
                }
                // Only bind if we have at least 2 images for collage
                if (mediaItems.size >= 2) {
                    if (ChatOpenPrefetcher.VERBOSE_MEDIA_BIND_DEBUG) {
                        Log.d(
                            "GlyphCacheDebug",
                            "[CollageBind] OUT refresh msg=${item.message.id.take(8)} total=${mediaItems.size} " +
                                "visible=${mediaItems.size.coerceAtMost(4)} scrolling=false"
                        )
                    }
                    applyStableCollageWidth(binding.collageView)
                    binding.collageView.bind(
                        items = mediaItems,
                        messageId = item.message.id,
                        scrolling = false,
                        onClick = { index ->
                            if (selectionManager.hasSelection()) {
                                selectionManager.toggleSelection(item.message.id)
                            } else {
                                openImageViewer(item.message, index)
                            }
                        },
                        onLongClick = { _ ->
                            handleBubbleLongPress(item.message.id, itemView)
                            true
                        }
                    )
                }
                updateProgressUi(item.message, mediaItems)
            }
        }

        override fun updateProgress(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                val mediaItems = item.message.mediaItemsList
                if (mediaItems.size >= 2) {
                    updateProgressUi(item.message, mediaItems)
                }
            }
        }

        private fun updateProgressUi(msg: Message, mediaItems: List<MediaItem>) {
            val progress = MediaProgressManager.getProgress(msg.id)
            val isUploading = progress != null && progress.isUploading && !progress.isComplete
            val hasRemoteItems = mediaItems.isNotEmpty() && mediaItems.all { it.url.isNotBlank() }
            val isSentState = when (rememberStrongestOutgoingStatus(msg)) {
                MessageStatus.SENT,
                MessageStatus.DELIVERED,
                MessageStatus.READ,
                MessageStatus.PLAYED -> true
                else -> false
            }

            when {
                !isUploading && (hasRemoteItems || isSentState) -> {
                    binding.progressIndicator.visibility = View.GONE
                    binding.tvProgressText.visibility = View.GONE
                }
                progress != null -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.tvProgressText.visibility = View.VISIBLE
                    if (progress.isIndeterminate) {
                        binding.progressIndicator.startIndeterminate()
                        binding.tvProgressText.visibility = View.GONE
                    } else {
                        val percent = progress.progress.toInt().coerceIn(0, 99)
                        binding.progressIndicator.setProgress(percent.toFloat(), animate = false)
                        binding.tvProgressText.text = "$percent%"
                    }
                }
                else -> {
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.tvProgressText.visibility = View.GONE
                    binding.progressIndicator.startIndeterminate()
                }
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.messageBubble, groupPos, isIncoming = false, fullRadiusDp = 12f, imageView = binding.collageView)
        }

        override fun onViewDetached() {
            // A detached collage is often just one scroll away from returning. Keep the
            // rendered four-tile state intact so it does not re-enter as an empty 2-cell row.
        }

        fun clearMediaState() {
            binding.collageView.clearForRecycle()
        }
    }

    inner class TypingIndicatorViewHolder(private val binding: ItemTypingIndicatorBinding) : BaseViewHolder(binding) {
        // Animation: slide in/out from bottom — no horizontal movement, no scale changes.
        private val visibilityDurationMs = 280L
        // Minimum travel distance when the bubble slides in from / out to the bottom.
        private val minHiddenSlideYDp = 56f
        // Minimum time the indicator must remain visible before it can be hidden.
        private val minVisibleMs = 600L
        // Timestamp of the most recent show animation start (for min-visible guard).
        private var lastShownAtMs = 0L
        // Pending Runnable that fires a deferred hide after min-visible time elapses.
        private var pendingHideRunnable: Runnable? = null
        // One-frame deferred hide used when the RecyclerView tail is being pushed in
        // the same transition so both motions begin together.
        private var pendingSynchronizedHideStart: Runnable? = null
        // Material Design standard ease-in-out (cubic-bezier 0.4, 0, 0.2, 1).
        private val easeInterpolator = android.view.animation.PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)

        private var dotAnimatorSet: AnimatorSet? = null
        private var isExpressiveMode = false
        private var isLabelDotsMode = false
        private var lastLiveText = ""
        private var lastSentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType =
            com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL
        private var visibilityAnimator: android.view.ViewPropertyAnimator? = null
        private var bubbleSlideAnimator: android.view.ViewPropertyAnimator? = null
        private var lastIndicatorVisible: Boolean? = null

        // Cached dots-only width so we can animate back to it
        private var dotsNaturalWidth = 0
        private var widthAnimator: ValueAnimator? = null

        // Typewriter animation state
        private var typewriterAnimator: ValueAnimator? = null
        private var revealedLength = 0

        // Sentiment emoji emit interval tracking
        private var lastEmojiEmitTime = 0L
        private val EMOJI_EMIT_INTERVAL_MS = 2000L

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            bindTypingIndicator(item, position, skipImageLoad, animateExpressive = false)
        }

        fun bindLiveUpdate(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            bindTypingIndicator(item, position, skipImageLoad, animateExpressive = true)
        }

        private fun bindTypingIndicator(
            item: ChatListItem,
            position: Int,
            skipImageLoad: Boolean,
            animateExpressive: Boolean
        ) {
            if (item !is ChatListItem.TypingIndicator) return
            initializeColors(binding.root.context)

            // Keep latest measured height available to ChatActivity for synchronized push animation.
            binding.root.post {
                val measured = binding.root.height
                if (measured > 0) {
                    onTypingIndicatorMeasured?.invoke(measured)
                }
            }

            val wasVisible = lastIndicatorVisible
            val isCurrentlyRenderedVisible = binding.root.visibility == View.VISIBLE && binding.root.alpha > 0.01f
            val shouldAnimateIn = item.isVisible && wasVisible != true
            val shouldAnimateOut = !item.isVisible && (wasVisible == true || isCurrentlyRenderedVisible)

            if (!item.isVisible) {
                if (shouldAnimateOut) {
                    animateIndicatorVisibility(
                        show = false,
                        synchronizeWithListMotion = item.synchronizeHideWithListMotion
                    )
                } else {
                    // Fast-path: snap to hidden state without animation.
                    // Cancel any pending deferred hide so it doesn't race with this reset.
                    binding.root.removeCallbacks(pendingHideRunnable)
                    pendingHideRunnable = null
                    binding.root.removeCallbacks(pendingSynchronizedHideStart)
                    pendingSynchronizedHideStart = null
                    visibilityAnimator?.cancel()
                    bubbleSlideAnimator?.cancel()
                    val slideY = resolveHiddenSlideY()
                    binding.root.visibility = View.INVISIBLE
                    binding.root.alpha = 0f
                    binding.bubbleLayout.translationX = 0f
                    binding.bubbleLayout.translationY = slideY
                    binding.bubbleLayout.scaleX = 1f
                    binding.bubbleLayout.scaleY = 1f
                }
                stopBouncingDots()
                cancelTypewriter()
                hideExpressiveOverlays()
                lastIndicatorVisible = false
                return
            }

            binding.root.visibility = View.VISIBLE
            if (shouldAnimateIn) {
                animateIndicatorVisibility(show = true)
            } else {
                // Already visible — ensure transforms are at the shown position.
                binding.root.removeCallbacks(pendingHideRunnable)
                pendingHideRunnable = null
                binding.root.removeCallbacks(pendingSynchronizedHideStart)
                pendingSynchronizedHideStart = null
                visibilityAnimator?.cancel()
                bubbleSlideAnimator?.cancel()
                binding.root.alpha = 1f
                binding.bubbleLayout.translationX = 0f
                binding.bubbleLayout.translationY = 0f
                binding.bubbleLayout.scaleX = 1f
                binding.bubbleLayout.scaleY = 1f
            }
            lastIndicatorVisible = true

            // Apply theme-specific bubble background
            if (isPastelTheme) {
                binding.bubbleLayout.setBackgroundResource(R.drawable.bg_pastel_bubble_incoming)
                binding.bubbleLayout.backgroundTintList = null
            } else {
                binding.bubbleLayout.setBackgroundResource(R.drawable.bg_typing_indicator)
                binding.bubbleLayout.background?.setTint(colorOtherBubble)
            }

            // Dot tint (always applied so they're correct if we switch back)
            binding.dot1.setColorFilter(colorOtherText)
            binding.dot2.setColorFilter(colorOtherText)
            binding.dot3.setColorFilter(colorOtherText)

            // Text color follows theme
            binding.tvLiveText.setTextColor(colorOtherText)

            // Expressive 1:1 typing uses text-only mode. Group typing keeps the classic
            // animated dots visible and adds a label beside them.
            if (item.liveText.isNotBlank() && item.isExpressive) {
                switchToExpressive(
                    text = item.liveText,
                    sentiment = item.sentiment,
                    animateChange = animateExpressive,
                    animateTyping = animateExpressive,
                    animateSentiment = animateExpressive
                )
            } else if (item.liveText.isNotBlank()) {
                switchToLabeledDots(item.liveText)
            } else {
                switchToDots()
            }
        }

        private fun showExpressiveOverlays(sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType) {
            if (sentiment == com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL) {
                hideExpressiveOverlays()
                return
            }

            // Special effect for ROMANTIC sentiment
            if (sentiment == com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC) {
                // Ensure we don't spam it - check time if needed, but updateSentiment
                // handles state changes, so this triggers once per sentiment entry.
                triggerHeartbeatEffect()
            }

            // Activate gradient
            binding.gradientView.visibility = View.VISIBLE
            binding.gradientView.alpha = 0.25f
            binding.gradientView.animateToSentiment(sentiment)

            // Activate emoji overlay
            binding.floatingEmojiView.visibility = View.VISIBLE

            // Rate-limited emoji emission
            val now = System.currentTimeMillis()
            if (now - lastEmojiEmitTime >= EMOJI_EMIT_INTERVAL_MS) {
                lastEmojiEmitTime = now
                binding.floatingEmojiView.emitEmojis(sentiment)
            }
        }

        private fun hideExpressiveOverlays() {
            binding.gradientView.animate().alpha(0f).setDuration(200).withEndAction {
                binding.gradientView.visibility = View.GONE
                binding.gradientView.reset()
            }.start()
            binding.floatingEmojiView.animate().alpha(0f).setDuration(200).withEndAction {
                binding.floatingEmojiView.visibility = View.GONE
                binding.floatingEmojiView.reset()
            }.start()
        }

        // =====================================================================
        //  DOTS MODE (Classic Three-Dot Typing Indicator)
        // =====================================================================
        // State machine rules:
        // - Dots VISIBLE, Live text INVISIBLE
        // - Bouncing animation active
        // - Width = WRAP_CONTENT (compact, original behavior)
        // - No gradient, no emoji overlays
        // - Mutually exclusive with LIVE mode

        private fun switchToDots() {
            if (!isExpressiveMode && !isLabelDotsMode && dotAnimatorSet != null) return // already in dots mode

            val wasExpressive = isExpressiveMode
            isExpressiveMode = false
            isLabelDotsMode = false
            lastLiveText = ""
            lastSentiment = com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL

            // ===== ATOMIC STATE CHANGE: Ensure mutual exclusivity =====
            // IMMEDIATELY hide live text mode (no delays, no animations for visibility)
            cancelTypewriter()
            binding.tvLiveText.clearAnimation() // Cancel any pending alpha animations
            binding.tvLiveText.visibility = View.INVISIBLE
            binding.tvLiveText.alpha = 0f
            binding.tvLiveText.text = ""

            // IMMEDIATELY show dots mode
            binding.dotsContainer.clearAnimation()
            binding.dotsContainer.visibility = View.VISIBLE
            binding.dotsContainer.alpha = if (wasExpressive) 0f else 1f

            // Hide expressive overlays immediately
            binding.gradientView.visibility = View.GONE
            binding.gradientView.reset()
            binding.floatingEmojiView.visibility = View.GONE
            binding.floatingEmojiView.reset()

            // CRITICAL: Cancel any width animation and reset to WRAP_CONTENT immediately
            widthAnimator?.cancel()
            widthAnimator = null
            resetBubbleWidthToWrapContent()

            // Start dot animation immediately (no delay)
            startBouncingDots()

            // Optional smooth fade-in if transitioning from expressive
            if (wasExpressive) {
                binding.dotsContainer.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .setStartDelay(0) // No delay - we already set visibility immediately
                    .start()
            }

            // Measure natural dots width on first layout (only once)
            if (dotsNaturalWidth == 0) {
                binding.bubbleLayout.post {
                    if (binding.bubbleLayout.width > 0 && !isExpressiveMode && !isLabelDotsMode) {
                        dotsNaturalWidth = binding.bubbleLayout.width
                    }
                }
            }
        }

        private fun switchToLabeledDots(text: String) {
            val displayText = text
                .removeSuffix("...")
                .removeSuffix("…")
                .trimEnd()
            val previousText = lastLiveText
            isExpressiveMode = false
            isLabelDotsMode = true
            lastSentiment = com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL

            cancelTypewriter()
            hideExpressiveOverlays()

            widthAnimator?.cancel()
            widthAnimator = null
            resetBubbleWidthToWrapContent()

            binding.tvLiveText.clearAnimation()
            binding.tvLiveText.visibility = View.VISIBLE
            binding.tvLiveText.alpha = 1f
            if (displayText != previousText) {
                binding.tvLiveText.text = displayText
                lastLiveText = displayText
            }

            binding.dotsContainer.clearAnimation()
            binding.dotsContainer.visibility = View.VISIBLE
            binding.dotsContainer.alpha = 1f
            startBouncingDots()

            if (dotsNaturalWidth == 0) {
                binding.bubbleLayout.post {
                    if (binding.bubbleLayout.width > 0 && isLabelDotsMode) {
                        dotsNaturalWidth = binding.bubbleLayout.width
                    }
                }
            }
        }

        // =====================================================================
        //  EXPRESSIVE MODE (Live Typing Preview with Sentiment)
        // =====================================================================
        // State machine rules:
        // - Live text VISIBLE, Dots INVISIBLE
        // - Typewriter character animation active
        // - Width animates dynamically to fit text
        // - Gradient background + floating emojis based on sentiment
        // - Mutually exclusive with DOTS mode

        private fun switchToExpressive(
            text: String,
            sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType,
            animateChange: Boolean = true,
            animateTyping: Boolean = animateChange,
            animateSentiment: Boolean = animateChange
        ) {
            val wasDots = !isExpressiveMode
            isExpressiveMode = true
            isLabelDotsMode = false

            // ===== ATOMIC STATE CHANGE: Ensure mutual exclusivity =====
            // IMMEDIATELY hide dots mode (no delays, no animations for visibility)
            stopBouncingDots()
            binding.dotsContainer.clearAnimation() // Cancel any pending alpha animations
            binding.dotsContainer.visibility = View.INVISIBLE
            binding.dotsContainer.alpha = 0f

            // IMMEDIATELY show live text mode
            binding.tvLiveText.clearAnimation()
            binding.tvLiveText.visibility = View.VISIBLE
            binding.tvLiveText.alpha = 1f

            // Capture dots width before we transition (for width animation reference)
            if (dotsNaturalWidth == 0 && binding.bubbleLayout.width > 0) {
                dotsNaturalWidth = binding.bubbleLayout.width
            }

            if (wasDots) {
                // Measure text width and animate bubble to it BEFORE starting typewriter
                measureAndSetWidth(text, animate = animateChange)

                if (animateTyping) {
                    // Start with empty text, then typewriter-reveal
                    binding.tvLiveText.text = ""
                    revealedLength = 0
                    lastLiveText = text

                    animateTypewriter(text, fromIndex = 0)
                } else {
                    cancelTypewriter()
                    binding.tvLiveText.text = text
                    revealedLength = text.length
                    lastLiveText = text
                }
            } else {
                // Already in expressive — update text with animation
                if (animateTyping) {
                    updateLiveText(text)
                } else {
                    cancelTypewriter()
                    measureAndSetWidth(text, animate = false)
                    binding.tvLiveText.text = text
                    revealedLength = text.length
                    lastLiveText = text
                }
            }

            // Handle sentiment changes
            updateSentiment(sentiment, animateEffects = animateSentiment)
        }

        /**
         * Smooth typewriter animation: reveals characters one by one with a soft
         * fade-in effect. Each new character appears with slight alpha transition.
         */
        private fun animateTypewriter(fullText: String, fromIndex: Int) {
            cancelTypewriter()
            if (fromIndex >= fullText.length) {
                binding.tvLiveText.text = fullText
                revealedLength = fullText.length
                return
            }

            val charsToReveal = fullText.length - fromIndex
            // ~30ms per character for smooth typewriter, capped to avoid long delays
            val totalDuration = (charsToReveal * 30L).coerceAtMost(600L)

            typewriterAnimator = ValueAnimator.ofInt(fromIndex, fullText.length).apply {
                duration = totalDuration
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener { anim ->
                    val currentEnd = anim.animatedValue as Int
                    if (currentEnd != revealedLength && currentEnd <= fullText.length) {
                        revealedLength = currentEnd
                        // Use SpannableString to fade in the latest character
                        val spannable = android.text.SpannableStringBuilder(fullText.substring(0, currentEnd))
                        if (currentEnd > 0 && currentEnd <= fullText.length) {
                            // Subtle alpha on the last revealed character
                            val fraction = anim.animatedFraction
                            val charFraction = ((fraction * charsToReveal) % 1.0f)
                            val alpha = (0.4f + 0.6f * charFraction).coerceIn(0.4f, 1.0f)
                            if (currentEnd > 0) {
                                spannable.setSpan(
                                    android.text.style.ForegroundColorSpan(
                                        adjustAlpha(binding.tvLiveText.currentTextColor, alpha)
                                    ),
                                    currentEnd - 1,
                                    currentEnd,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                        }
                        binding.tvLiveText.text = spannable
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Ensure final text is clean (no spans)
                        if (revealedLength >= fullText.length) {
                            binding.tvLiveText.text = fullText
                        }
                    }
                })
                start()
            }
        }

        private fun adjustAlpha(color: Int, alpha: Float): Int {
            val a = (android.graphics.Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        private fun cancelTypewriter() {
            typewriterAnimator?.cancel()
            typewriterAnimator = null
        }

        /**
         * Update live text with smooth typewriter for appended characters,
         * or subtle crossfade for large text changes.
         */
        private fun updateLiveText(newText: String) {
            if (newText == lastLiveText) return
            
            // CRITICAL: Measure and start width animation FIRST.
            // When expanding, the bubble width must lead the typewriter reveal
            // to prevent the text from ever wrapping prematurely.
            measureAndSetWidth(newText, animate = true)

            val tv = binding.tvLiveText
            if (newText.startsWith(lastLiveText)) {
                // Incremental append — typewriter the new characters
                val prevLen = lastLiveText.length
                lastLiveText = newText
                animateTypewriter(newText, fromIndex = prevLen)
            } else if (lastLiveText.startsWith(newText)) {
                // Deletion — just set directly, no animation needed
                cancelTypewriter()
                tv.text = newText
                revealedLength = newText.length
                lastLiveText = newText
            } else {
                // Big change — quick crossfade
                cancelTypewriter()
                tv.animate().alpha(0.0f).setDuration(80).withEndAction {
                    tv.text = ""
                    revealedLength = 0
                    lastLiveText = newText
                    tv.alpha = 1f
                    animateTypewriter(newText, fromIndex = 0)
                }.start()
            }
        }

        private fun updateSentiment(
            sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType,
            animateEffects: Boolean
        ) {
            if (sentiment == lastSentiment) {
                // Even if sentiment hasn't changed, still try emitting emojis (rate-limited)
                if (animateEffects && sentiment != com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmojiEmitTime >= EMOJI_EMIT_INTERVAL_MS) {
                        lastEmojiEmitTime = now
                        binding.floatingEmojiView.emitEmojis(sentiment)
                    }
                }
                return
            }
            lastSentiment = sentiment
            if (animateEffects) {
                showExpressiveOverlays(sentiment)
            } else {
                renderExpressiveOverlays(sentiment)
            }
        }

        private fun renderExpressiveOverlays(sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType) {
            if (sentiment == com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL) {
                hideExpressiveOverlays()
                return
            }

            binding.gradientView.visibility = View.VISIBLE
            binding.gradientView.alpha = 0.25f
            binding.gradientView.setSentimentImmediate(sentiment)

            binding.floatingEmojiView.visibility = View.VISIBLE
            binding.floatingEmojiView.reset()
        }

        private fun triggerHeartbeatEffect() {
            try {
                // Cancel any ongoing animation first
                binding.bubbleLayout.animate().cancel()
                binding.bubbleLayout.scaleX = 1f
                binding.bubbleLayout.scaleY = 1f

                val context = binding.root.context
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                // 1. Visual Heartbeat Animation ("Lub-dub" looping for ~3s)
                val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.08f, 0.95f, 1.04f, 1.0f)
                val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.08f, 0.95f, 1.04f, 1.0f)
                
                ObjectAnimator.ofPropertyValuesHolder(binding.bubbleLayout, scaleX, scaleY).apply {
                    duration = 800 // Slower pace for the full cycle
                    repeatCount = 3 // Approx 3.2 seconds total (800ms * 4 pulses)
                    interpolator = FastOutSlowInInterpolator()
                    start()
                }

                // 2. Haptic Heartbeat Pattern (Looping)
                if (vibrator.hasVibrator()) {
                    // Lub-dub timing:
                    // 0ms: Lub (Strong), 100ms: dub (Medium), 800ms: Cycle Start
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Timings: [Wait, Lub, Gap, dub, Intra-heartbeat Pause]
                        val timings = longArrayOf(0, 60, 100, 60, 580)
                        val amplitudes = intArrayOf(0, 255, 0, 150, 0)
                        
                        // Repeat index 0 for ~3 seconds. We'll stop it manually or let it run.
                        // To play exactly 4 times (3.2s) we use repeatIndex = -1 and a different logic
                        // but setting repeat = 0 and then cancelling is most reliable.
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 60, 100, 60, 580), 0)
                    }

                    // Stop vibration after 3 seconds
                    binding.root.postDelayed({
                        vibrator.cancel()
                    }, 3000)
                }
            } catch (e: Exception) {
                // Safely ignore vibration errors
            }
        }

        /**
         * Measure the text's required width and smoothly animate bubble width.
         * @param animate If false, width snaps instantly (useful for initial layout/restore).
         */
        private fun measureAndSetWidth(text: String, animate: Boolean) {
            val tv = binding.tvLiveText
            val density = tv.resources.displayMetrics.density

            // 1. Calculate constraint width (70% of screen)
            val screenWidth = tv.resources.displayMetrics.widthPixels
            val maxW = (screenWidth * 0.70f).toInt()
            
            // hPadding (16dp each side) + 6dp extra safety buffer to prevent ALL wrap jitter
            // A larger buffer is better than a jittery transition.
            val hPadding = (38 * density).toInt() 

            // 2. Determine "True" width including multi-line wrap logic
            val availableWidthForText = maxW - hPadding
            val textPaint = tv.paint
            
            // Measure actual width needed for this text
            val widthForSingleLine = textPaint.measureText(text).toInt()
            
            val targetWidth: Int
            if (widthForSingleLine <= availableWidthForText) {
                // Fits in one line
                targetWidth = (widthForSingleLine + hPadding).coerceIn(dotsNaturalWidth, maxW)
            } else {
                // Wraps to multiple lines - use maxW
                targetWidth = maxW
            }

            setBubbleWidth(targetWidth, animate)
        }

        private fun setBubbleWidth(targetWidth: Int, animate: Boolean) {
            val bubble = binding.bubbleLayout
            val currentWidth = bubble.width
            
            // CRITICAL STABILITY FIX: 
            // When expanding width (typing), update INSTANTLY to ensure the bubble 
            // is always wide enough for the new text before it's rendered.
            // This prevents momentary text wrapping which causes vertical height jitter.
            // Only animate smoothly when shrinking (backspace) or when the change is minimal.
            val isExpanding = targetWidth > currentWidth
            
            // Check if expansion is significant enough to cause wrap
            // If expanding, disable animation to ensure stability
            val shouldAnimate = animate && !isExpanding && currentWidth > 0

            // If already at target, not laid out, or expanding (to prevent wrap), set directly
            if (!shouldAnimate || currentWidth == targetWidth) {
                widthAnimator?.cancel()
                if (bubble.layoutParams.width != targetWidth) {
                    bubble.layoutParams.width = targetWidth
                    bubble.requestLayout()
                }
                return
            }

            // Optimization: Apply hardware layer during width animation for smoothness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                bubble.setHasTransientState(true)
            }
            
            widthAnimator?.cancel()
            widthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth).apply {
                // Slower contraction for smooth feel
                duration = 200L
                interpolator = LinearOutSlowInInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    if (bubble.layoutParams.width != value) {
                        bubble.layoutParams.width = value
                        bubble.requestLayout()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            bubble.setHasTransientState(false)
                        }
                    }
                })
                start()
            }
        }

        /**
         * Immediately reset bubble width to WRAP_CONTENT (original dots behavior).
         * Called when switching from expressive back to dots mode.
         */
        private fun resetBubbleWidthToWrapContent() {
            val bubble = binding.bubbleLayout
            bubble.layoutParams = bubble.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            bubble.requestLayout()
        }

        // =====================================================================
        //  DOT ANIMATION
        // =====================================================================

        override fun onViewDetached() {
            super.onViewDetached()
            // Cancel any deferred-hide runnable so it doesn't fire after the holder is recycled
            // and potentially bound to a different chat thread.
            binding.root.removeCallbacks(pendingHideRunnable)
            pendingHideRunnable = null
            binding.root.removeCallbacks(pendingSynchronizedHideStart)
            pendingSynchronizedHideStart = null
            lastShownAtMs = 0L
            visibilityAnimator?.cancel()
            visibilityAnimator = null
            bubbleSlideAnimator?.cancel()
            bubbleSlideAnimator = null
            lastIndicatorVisible = null
            isExpressiveMode = false
            isLabelDotsMode = false
            stopBouncingDots()
            cancelTypewriter()
            widthAnimator?.cancel()
            widthAnimator = null
            binding.gradientView.cleanup()
            binding.floatingEmojiView.reset()
            // Ensure width is reset to original state when detached
            resetBubbleWidthToWrapContent()
        }

        private fun startBouncingDots() {
            if (dotAnimatorSet != null) return

            val loopDuration = 1200L
            val startDelay = 220L

            val anim1 = createDotAnimator(binding.dot1, 0, loopDuration)
            val anim2 = createDotAnimator(binding.dot2, startDelay, loopDuration)
            val anim3 = createDotAnimator(binding.dot3, startDelay * 2, loopDuration)

            dotAnimatorSet = AnimatorSet().apply {
                playTogether(anim1, anim2, anim3)
                start()
            }
        }

        private fun stopBouncingDots() {
            dotAnimatorSet?.cancel()
            dotAnimatorSet = null

            listOf(binding.dot1, binding.dot2, binding.dot3).forEach { dot ->
                dot.translationY = 0f
                dot.scaleX = 1f
                dot.scaleY = 1f
                dot.alpha = 1f
            }
        }

        private fun animateIndicatorVisibility(
            show: Boolean,
            synchronizeWithListMotion: Boolean = false
        ) {
            val root = binding.root
            val bubble = binding.bubbleLayout
            val slideY = resolveHiddenSlideY()

            // Cancel any deferred-hide runnable that might fire after this call.
            root.removeCallbacks(pendingHideRunnable)
            pendingHideRunnable = null
            root.removeCallbacks(pendingSynchronizedHideStart)
            pendingSynchronizedHideStart = null
            visibilityAnimator?.cancel()
            bubbleSlideAnimator?.cancel()

            if (show) {
                // Record when the indicator became visible for the min-visible guard.
                lastShownAtMs = System.currentTimeMillis()
                root.visibility = View.VISIBLE
                // Snap to the hidden (below-screen) position so the slide-in always
                // starts from the same place regardless of prior animation state.
                root.alpha = 0f
                bubble.translationX = 0f
                bubble.translationY = slideY
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                // Fade in the row
                visibilityAnimator = root.animate()
                    .alpha(1f)
                    .setDuration(visibilityDurationMs)
                    .setInterpolator(easeInterpolator)
                    .setListener(null)
                visibilityAnimator?.start()
                // Slide bubble up from below
                bubbleSlideAnimator = bubble.animate()
                    .translationY(0f)
                    .setDuration(visibilityDurationMs)
                    .setInterpolator(easeInterpolator)
                    .setListener(null)
                bubbleSlideAnimator?.start()
            } else {
                // Minimum-visible guard: if the indicator was shown very recently, defer
                // the hide animation so it doesn't flicker on rapid typing-state changes.
                val elapsed = System.currentTimeMillis() - lastShownAtMs
                val deferMs = (minVisibleMs - elapsed).coerceAtLeast(0L)
                if (deferMs > 0L && !synchronizeWithListMotion) {
                    val r = Runnable {
                        // Only proceed if the state hasn't changed back to visible.
                        if (lastIndicatorVisible == false) {
                            performHideAnimation(root, bubble, slideY)
                        }
                    }
                    pendingHideRunnable = r
                    root.postDelayed(r, deferMs)
                    return
                }
                if (synchronizeWithListMotion) {
                    scheduleSynchronizedHideAnimation(root, bubble, slideY)
                } else {
                    performHideAnimation(root, bubble, slideY)
                }
            }
        }

        private fun scheduleSynchronizedHideAnimation(root: View, bubble: View, slideY: Float) {
            val hideRunnable = Runnable {
                pendingSynchronizedHideStart = null
                if (lastIndicatorVisible == false) {
                    performHideAnimation(root, bubble, slideY)
                }
            }
            pendingSynchronizedHideStart = hideRunnable
            if (root.isAttachedToWindow) {
                root.postOnAnimation(hideRunnable)
            } else {
                hideRunnable.run()
            }
        }

        private fun resolveHiddenSlideY(): Float {
            val root = binding.root
            val bubble = binding.bubbleLayout
            val minSlide = minHiddenSlideYDp * root.resources.displayMetrics.density
            val measuredTravel = if (root.height > 0 && bubble.height > 0) {
                (root.height - bubble.top + root.paddingBottom).toFloat()
            } else {
                0f
            }
            return maxOf(minSlide, measuredTravel)
        }

        private fun performHideAnimation(root: View, bubble: View, slideY: Float) {
            if (root.alpha <= 0.01f) {
                // Already invisible — skip animation and snap to hidden state.
                root.visibility = View.INVISIBLE
                bubble.translationY = slideY
                return
            }
            visibilityAnimator?.cancel()
            bubbleSlideAnimator?.cancel()
            // Fade out the row
            visibilityAnimator = root.animate()
                .alpha(0f)
                .setDuration(visibilityDurationMs)
                .setInterpolator(easeInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        root.visibility = View.INVISIBLE
                        root.alpha = 0f
                        bubble.translationY = slideY
                    }
                })
            visibilityAnimator?.start()
            // Slide bubble down out of view
            bubbleSlideAnimator = bubble.animate()
                .translationY(slideY)
                .setDuration(visibilityDurationMs)
                .setInterpolator(easeInterpolator)
                .setListener(null)
            bubbleSlideAnimator?.start()
        }

        private fun createDotAnimator(view: View, delay: Long, loopDuration: Long): Animator {
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.88f, 1.16f, 0.88f).apply {
                repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            }
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.88f, 1.16f, 0.88f).apply {
                repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            }
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0.56f, 1.0f, 0.56f).apply {
                repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            }
            val density = view.resources.displayMetrics.density
            val translationY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -1.25f * density, 0f).apply {
                repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            }

            return AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha, translationY)
                duration = loopDuration
                startDelay = delay
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
    }

    inner class IncomingTextViewHolder(private val binding: ItemMessageIncomingTextBinding) : BaseViewHolder(binding) {
        override fun applyTextHeight(item: ChatListItem) {
            if (item is ChatListItem.MessageItem && !item.isEmojiContent) {
                TextLayoutPrecomputer.applyToTextView(binding.tvMessage, item)
            }
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                initializeColors(binding.root.context)
                bindForwardedLabel(msg, isIncoming = true)

                if (isPastelTheme) {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_incoming)
                    binding.cardMessage.backgroundTintList = null
                } else {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_message_incoming)
                    binding.cardMessage.backgroundTintList = tintOtherBubble
                }
                // Reset the corner cache tag so applyBubbleCorners always runs fresh
                // (prevents stale grouped corners from surviving ViewHolder recycling)
                binding.cardMessage.setTag(R.id.tag_bubble_corner_key, null)
                binding.tvMessage.setTextColor(colorOtherText)

                // Capture text layout params once for background-thread precomputation.
                // Set correct text size first: XML default is 17sp but normal messages
                // use 15sp. Without this the precomputed height is 13% too tall.
                if (!TextLayoutPrecomputer.isReady()) {
                    binding.tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, normalMessageTextSizeSp)
                    TextLayoutPrecomputer.captureParams(binding.tvMessage)
                }

                // Apply pre-measured height BEFORE setting text so onMeasure skips
                // the internal StaticLayout creation pass.
                // Always reset minHeight before applying premeasured height.
                // If applyToTextView returns false (no premeasured height available),
                // a stale minHeight from a previous recycled ViewHolder would persist
                // and leave large empty space below the text.
                binding.tvMessage.minHeight = 0
                binding.tvMessage.maxHeight = Int.MAX_VALUE
                if (!item.isEmojiContent) {
                    TextLayoutPrecomputer.applyToTextView(binding.tvMessage, item)
                }

                if (msg.isDeletedForAll) {
                    binding.tvMessage.text = " This message was deleted "
                    binding.tvMessage.typeface = typefaceItalic
                    // Ensure opacity is visually distinct
                    binding.tvMessage.alpha = 0.6f
                } else {
                    binding.tvMessage.text = msg.text.ifBlank { "Message" }
                    binding.tvMessage.typeface = typefaceNormal
                    binding.tvMessage.alpha = 1.0f
                }

                if (isFastBind || isFirstLayout) {
                    binding.tvTranslationLabel.visibility = View.GONE
                    val linkPreviewVisible = bindLinkPreview(
                        binding.root,
                        msg,
                        binding.tvMessage,
                        allowThumbnailLoad = false
                    )
                    bindReplyPreview(binding.root.context, binding.root, msg)
                    binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                    val replyVisible = binding.includeReplyPreview.root.visibility == View.VISIBLE
                    val emojiOnly = !msg.isForwarded && !msg.isDeletedForAll && !replyVisible && !linkPreviewVisible && item.isEmojiContent
                    applyEmojiOnlyStyle(binding.cardMessage, binding.tvMessage, emojiOnly, isIncoming = true)
                    updateGrouping(position)
                    bindSelection(item, animate = false)
                    return
                }

                // Inline translation
                bindTranslationState(item, binding.tvMessage, binding.tvTranslationLabel)
                val linkPreviewVisible = bindLinkPreview(binding.root, msg, binding.tvMessage)

                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)

                bindReplyPreview(binding.root.context, binding.root, msg)

                val replyVisible = binding.root.findViewById<View>(R.id.includeReplyPreview)?.visibility == View.VISIBLE
                val emojiOnly = !msg.isForwarded && !msg.isDeletedForAll && !replyVisible && !linkPreviewVisible && item.isEmojiContent
                applyEmojiOnlyStyle(binding.cardMessage, binding.tvMessage, emojiOnly, isIncoming = true)

                updateGrouping(position)
                maybeBounceIncoming(item, binding.cardMessage)
                bindSelection(item, animate = false)
            }
        }

        override fun updateTranslation(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                bindTranslationState(item, binding.tvMessage, binding.tvTranslationLabel)
                bindLinkPreview(binding.root, item.message, binding.tvMessage)
            }
        }

        override fun onMessageClick(item: ChatListItem.MessageItem) {
            val previewUrl = getLinkPreviewUrl(item.message)
            if (previewUrl != null) {
                openLinkPreview(itemView.context, previewUrl)
            } else {
                super.onMessageClick(item)
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = true, fullRadiusDp = 8f)
            val hasTopSibling = binding.includeReplyPreview.root.visibility == View.VISIBLE
            // Link-preview card lives in a ViewStub; only present once inflated for an actual preview.
            val previewCard = binding.root.findViewById<MaterialCardView?>(R.id.linkPreviewCard)
            val previewThumb = binding.root.findViewById<ShapeableImageView?>(R.id.ivLinkPreviewThumbnail)
            syncLinkPreviewThumbnailCorners(previewCard, previewThumb, groupPos, isIncoming = true, hasTopSibling = hasTopSibling)
        }
    }

    inner class OutgoingTextViewHolder(private val binding: ItemMessageOutgoingTextBinding) : BaseViewHolder(binding) {
        private var lastStatus: MessageStatus? = null

        override fun applyTextHeight(item: ChatListItem) {
            if (item is ChatListItem.MessageItem && !item.isEmojiContent) {
                TextLayoutPrecomputer.applyToTextView(binding.tvMessage, item)
            }
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                initializeColors(binding.root.context)
                bindForwardedLabel(msg, isIncoming = false)
                
                if (isPastelTheme) {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_outgoing)
                    binding.cardMessage.backgroundTintList = null
                } else {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_message_outgoing)
                    binding.cardMessage.backgroundTintList = tintOwnBubble
                }
                // Reset the corner cache tag so applyBubbleCorners always runs fresh
                // (prevents stale grouped corners from surviving ViewHolder recycling)
                binding.cardMessage.setTag(R.id.tag_bubble_corner_key, null)
                
                binding.tvMessage.setTextColor(colorOwnText)

                // Capture text layout params once for background-thread precomputation.
                // Set correct text size first (see IncomingTextViewHolder comment).
                if (!TextLayoutPrecomputer.isReady()) {
                    binding.tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, normalMessageTextSizeSp)
                    TextLayoutPrecomputer.captureParams(binding.tvMessage)
                }

                // Always reset minHeight before applying premeasured height.
                // If applyToTextView returns false (no premeasured height available),
                // a stale minHeight from a previous recycled ViewHolder would persist
                // and leave large empty space below the text.
                binding.tvMessage.minHeight = 0
                binding.tvMessage.maxHeight = Int.MAX_VALUE
                if (!item.isEmojiContent) {
                    TextLayoutPrecomputer.applyToTextView(binding.tvMessage, item)
                }

                if (msg.isDeletedForAll) {
                    binding.tvMessage.text = " This message was deleted "
                    binding.tvMessage.typeface = typefaceItalic
                    binding.tvMessage.alpha = 0.6f
                } else {
                    binding.tvMessage.text = msg.text.ifBlank { "Message" }
                    binding.tvMessage.typeface = typefaceNormal
                    binding.tvMessage.alpha = 1.0f
                }

                if (isFastBind || isFirstLayout) {
                    binding.tvTranslationLabel.visibility = View.GONE
                    val linkPreviewVisible = bindLinkPreview(
                        binding.root,
                        msg,
                        binding.tvMessage,
                        allowThumbnailLoad = false
                    )
                    bindReplyPreview(binding.root.context, binding.root, msg)
                    binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                    val replyVisible = binding.includeReplyPreview.root.visibility == View.VISIBLE
                    val emojiOnly = !msg.isForwarded && !msg.isDeletedForAll && !replyVisible && !linkPreviewVisible && item.isEmojiContent
                    applyEmojiOnlyStyle(binding.cardMessage, binding.tvMessage, emojiOnly, isIncoming = false)
                    if (!msg.isDeletedForAll) {
                        binding.ivStatus.visibility = View.VISIBLE
                        setStatus(rememberStrongestOutgoingStatus(msg))
                    } else {
                        binding.ivStatus.visibility = View.GONE
                    }
                    updateGrouping(position)
                    bindSelection(item, animate = false)
                    return
                }

                // Inline translation
                bindTranslationState(item, binding.tvMessage, binding.tvTranslationLabel)
                val linkPreviewVisible = bindLinkPreview(binding.root, msg, binding.tvMessage)

                binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)

                bindReplyPreview(binding.root.context, binding.root, msg)

                val replyVisible = binding.root.findViewById<View>(R.id.includeReplyPreview)?.visibility == View.VISIBLE
                val emojiOnly = !msg.isForwarded && !msg.isDeletedForAll && !replyVisible && !linkPreviewVisible && item.isEmojiContent
                applyEmojiOnlyStyle(binding.cardMessage, binding.tvMessage, emojiOnly, isIncoming = false)

                if (!msg.isDeletedForAll) {
                    binding.ivStatus.visibility = View.VISIBLE
                    binding.ivStatus.alpha = 1.0f
                    setStatus(rememberStrongestOutgoingStatus(msg))
                } else {
                    binding.ivStatus.visibility = View.GONE
                }

                updateGrouping(position)
                maybeBounceIncoming(item, binding.cardMessage)
                bindSelection(item, animate = false)
            }
        }

        override fun updateTranslation(item: ChatListItem) {
            if (item is ChatListItem.MessageItem) {
                bindTranslationState(item, binding.tvMessage, binding.tvTranslationLabel)
                bindLinkPreview(binding.root, item.message, binding.tvMessage)
            }
        }

        override fun onMessageClick(item: ChatListItem.MessageItem) {
            val previewUrl = getLinkPreviewUrl(item.message)
            if (previewUrl != null) {
                openLinkPreview(itemView.context, previewUrl)
            } else {
                super.onMessageClick(item)
            }
        }

        private fun setStatus(status: MessageStatus) {
            if (lastStatus == status) return
            binding.ivStatus.alpha = 1.0f
            val shouldAnimate = lastStatus != null && lastStatus != MessageStatus.READ && status == MessageStatus.READ
            
            // Match voice message bubble status color logic explicitly using setColorFilter
            if (status == MessageStatus.READ) {
                binding.ivStatus.setColorFilter(android.graphics.Color.parseColor("#4FC3F7"))
                binding.ivStatus.imageTintList = null
            } else {
                val color = (colorOwnText and 0x00FFFFFF) or 0x99000000.toInt()
                binding.ivStatus.setColorFilter(color)
                binding.ivStatus.imageTintList = null
            }

            when (status) {
                MessageStatus.SENDING -> binding.ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT -> binding.ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ -> {
                    if (shouldAnimate) animateStatusToRead(binding.ivStatus, R.drawable.ic_double_check_blue)
                    else binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                }
                MessageStatus.FAILED -> binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                else -> binding.ivStatus.setImageResource(R.drawable.ic_check)
            }
            lastStatus = status
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = false, fullRadiusDp = 8f)
            val hasTopSibling = binding.includeReplyPreview.root.visibility == View.VISIBLE
            // Link-preview card lives in a ViewStub; only present once inflated for an actual preview.
            val previewCard = binding.root.findViewById<MaterialCardView?>(R.id.linkPreviewCard)
            val previewThumb = binding.root.findViewById<ShapeableImageView?>(R.id.ivLinkPreviewThumbnail)
            syncLinkPreviewThumbnailCorners(previewCard, previewThumb, groupPos, isIncoming = false, hasTopSibling = hasTopSibling)
        }

        private fun animateStatusToRead(iconView: android.widget.ImageView, @androidx.annotation.DrawableRes readIcon: Int) {
            iconView.animate().cancel()
            iconView.animate()
                .rotationY(90f)
                .setDuration(100)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .withEndAction {
                    iconView.setImageResource(readIcon)
                    iconView.rotationY = -90f
                    iconView.animate()
                        .rotationY(0f)
                        .setDuration(100)
                        .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .start()
                }
                .start()
        }
    }

    inner class IncomingViewHolder(private val binding: ItemMessageIncomingBinding) : BaseViewHolder(binding) {
        
        fun refreshVoiceState() {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val item = getItem(pos) as? ChatListItem.MessageItem ?: return
            if (item.message.type == MessageType.AUDIO) {
                 bindVoiceMessage(item.message)
            }
        }

        private fun bindVoiceMessage(msg: Message) {
             val audioState = currentAudioState
             val isPlaying = audioState?.playingMessageId == msg.id && audioState.isPlaying
             val progress = if (audioState?.playingMessageId == msg.id) audioState.progress ?: 0f else 0f
             val duration = msg.audioDuration
             
             binding.voiceMessageView.bind(
                isSelf = false,
                isPlaying = isPlaying,
                progress = progress,
                currentPosition = formatDuration((progress * duration).toLong()),
                totalDuration = formatDuration(duration),
                onPlayPause = { audioState?.onPlay?.invoke(msg) },
                onSeek = { pos -> audioState?.onSeek?.invoke(msg, pos) },
                durationMs = duration,
                timestamp = msg.formattedTime,
                status = msg.status,
                avatarUrl = resolveAvatarUrlForSender(msg.senderId),
                contentColor = colorOtherText
             )
        }

        override fun refreshAvatar(item: ChatListItem) {
            val messageItem = item as? ChatListItem.MessageItem ?: return
            if (messageItem.message.type == MessageType.AUDIO) {
                bindVoiceMessage(messageItem.message)
            }
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val context = binding.root.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = true)
                
                if (isPastelTheme) {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_incoming)
                    binding.cardMessage.backgroundTintList = null
                } else {
                    binding.cardMessage.setCardBackgroundColor(colorOtherBubble)
                }
                // Reset corner tag so applyBubbleCorners runs fresh after recycling
                binding.cardMessage.setTag(R.id.tag_bubble_corner_key, null)
                binding.tvMessage.setTextColor(colorOtherText)

                setVisibility(binding.cardMessage, View.VISIBLE)

                when (msg.type) {
                    MessageType.AUDIO -> {
                        setVisibility(binding.tvMessage, View.GONE)
                        setVisibility(binding.llMetadata, View.GONE)
                        binding.voiceMessageView.visibility = View.VISIBLE
                        
                        bindVoiceMessage(msg)
                    }
                    else -> {
                        binding.voiceMessageView.visibility = View.GONE
                        setVisibility(binding.tvMessage, View.VISIBLE)
                        setVisibility(binding.llMetadata, View.VISIBLE)
                        setVisibility(binding.tvTimestamp, View.VISIBLE)
                        binding.tvMessage.text = msg.text
                        binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                    }
                }
                // Selection / reaction long-press: itemView's listener (set in bindSelection)
                // can be swallowed by the interactive voice waveform, so attach the unified
                // helper directly to the bubble + voice view as well.
                binding.cardMessage.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                binding.cardMessage.setOnClickListener {
                    if (selectionManager.hasSelection()) selectionManager.toggleSelection(msg.id)
                }
                binding.voiceMessageView.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                updateGrouping(position)
                maybeBounceIncoming(item, binding.cardMessage)
                bindSelection(item, animate = false)
            }
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = true, fullRadiusDp = 8f)
        }

        fun clearMediaState() {}
    }

    inner class OutgoingViewHolder(private val binding: ItemMessageOutgoingBinding) : BaseViewHolder(binding) {
        private var lastStatus: MessageStatus? = null
        
        fun refreshVoiceState() {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val item = getItem(pos) as? ChatListItem.MessageItem ?: return
            if (item.message.type == MessageType.AUDIO) {
                 bindVoiceMessage(item.message)
            }
        }
        
        private fun bindVoiceMessage(msg: Message) {
             val audioState = currentAudioState
             val isPlaying = audioState?.playingMessageId == msg.id && audioState.isPlaying
             val progress = if (audioState?.playingMessageId == msg.id) audioState.progress ?: 0f else 0f
             val duration = msg.audioDuration
             
             binding.voiceMessageView.bind(
                isSelf = true,
                isPlaying = isPlaying,
                progress = progress,
                currentPosition = formatDuration((progress * duration).toLong()),
                totalDuration = formatDuration(duration),
                onPlayPause = { audioState?.onPlay?.invoke(msg) },
                onSeek = { pos -> audioState?.onSeek?.invoke(msg, pos) },
                durationMs = duration,
                timestamp = msg.formattedTime,
                status = msg.status,
                avatarUrl = outgoingAvatarUrl,
                contentColor = colorOwnText
             )
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.MessageItem) {
                val msg = item.message
                val context = binding.root.context
                initializeColors(context)
                bindForwardedLabel(msg, isIncoming = false)
                
                if (isPastelTheme) {
                    binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_outgoing)
                    binding.cardMessage.backgroundTintList = null
                } else {
                    binding.cardMessage.setCardBackgroundColor(colorOwnBubble)
                }
                // Reset corner tag so applyBubbleCorners runs fresh after recycling
                binding.cardMessage.setTag(R.id.tag_bubble_corner_key, null)
                binding.tvMessage.setTextColor(colorOwnText)

                setVisibility(binding.cardMessage, View.VISIBLE)

                when (msg.type) {
                    MessageType.AUDIO -> {
                        setVisibility(binding.tvMessage, View.GONE)
                        setVisibility(binding.llMetadata, View.GONE)
                        binding.voiceMessageView.visibility = View.VISIBLE

                        bindVoiceMessage(msg)
                    }
                    else -> {
                        binding.voiceMessageView.visibility = View.GONE
                        setVisibility(binding.tvMessage, View.VISIBLE)
                        setVisibility(binding.llMetadata, View.VISIBLE)
                        setVisibility(binding.tvTimestamp, View.VISIBLE)
                        setVisibility(binding.ivStatus, View.VISIBLE)
                        binding.tvMessage.text = msg.text
                        binding.tvTimestamp.text = formatTimestampWithEdited(msg, msg.formattedTime)
                        setStatus(rememberStrongestOutgoingStatus(msg))
                    }
                }
                // Selection / reaction long-press: itemView's listener (set in bindSelection)
                // can be swallowed by the interactive voice waveform, so attach the unified
                // helper directly to the bubble + voice view as well.
                binding.cardMessage.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                binding.cardMessage.setOnClickListener {
                    if (selectionManager.hasSelection()) selectionManager.toggleSelection(msg.id)
                }
                binding.voiceMessageView.setOnLongClickListener {
                    handleBubbleLongPress(msg.id, itemView)
                    true
                }
                updateGrouping(position)
                maybeBounceIncoming(item, binding.cardMessage)
                bindSelection(item, animate = false)
            }
        }

        private fun setStatus(status: MessageStatus) {
            if (lastStatus == status) return
            binding.ivStatus.alpha = 1.0f
            val shouldAnimate = lastStatus != null && lastStatus != MessageStatus.READ && status == MessageStatus.READ
            
            // Match voice message bubble status color logic explicitly using setColorFilter
            if (status == MessageStatus.READ) {
                binding.ivStatus.setColorFilter(android.graphics.Color.parseColor("#4FC3F7"))
                binding.ivStatus.imageTintList = null
            } else {
                val color = (colorOwnText and 0x00FFFFFF) or 0x99000000.toInt()
                binding.ivStatus.setColorFilter(color)
                binding.ivStatus.imageTintList = null
            }

            when (status) {
                MessageStatus.SENDING -> binding.ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT -> binding.ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ -> {
                    if (shouldAnimate) animateStatusToRead(binding.ivStatus, R.drawable.ic_double_check_blue)
                    else binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                }
                MessageStatus.FAILED -> binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                else -> binding.ivStatus.setImageResource(R.drawable.ic_check)
            }
            lastStatus = status
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = false, fullRadiusDp = 8f)
        }

        fun clearMediaState() {}

        private fun animateStatusToRead(iconView: android.widget.ImageView, @androidx.annotation.DrawableRes readIcon: Int) {
            iconView.animate().cancel()
            iconView.animate()
                .rotationY(90f)
                .setDuration(100)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .withEndAction {
                    iconView.setImageResource(readIcon)
                    iconView.rotationY = -90f
                    iconView.animate()
                        .rotationY(0f)
                        .setDuration(100)
                        .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .start()
                }
                .start()
        }
    }

    // ==============================================================================================
    // CONTACT CARD BUBBLE VIEW HOLDERS
    // ==============================================================================================

    inner class IncomingContactViewHolder(
        private val binding: ItemMessageIncomingContactBinding
    ) : BaseViewHolder(binding) {

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            val context = binding.root.context
            initializeColors(context)
            bindForwardedLabel(msg, isIncoming = true)

            binding.tvContactName.text = msg.contactName ?: "Contact"
            binding.tvContactPhone.text = msg.contactPhone ?: ""
            binding.tvTimestamp.text = msg.formattedTime

            // Lookup registered user by last-10-digit phone normalization
            val normalizedPhone = PhoneNumberUtil.normalizeToLast10Digits(msg.contactPhone ?: "")
            val registeredUser = registeredUsersMap[normalizedPhone]

            val currentUid = currentUserId
            val myPhoneNormalized = if (!currentUserPhone.isNullOrEmpty()) PhoneNumberUtil.normalizeToLast10Digits(currentUserPhone!!) else null
            val isMe = (registeredUser?.id != null && registeredUser.id == currentUid) ||
                       (normalizedPhone.isNotEmpty() && normalizedPhone == myPhoneNormalized)

            // Avatar
            val name = msg.contactName ?: "Contact"
            val avatarColor = ContactViewActivity.avatarColorForName(name)
            binding.viewAvatarBg.backgroundTintList = android.content.res.ColorStateList.valueOf(avatarColor)
            binding.tvAvatarInitials.setAlpha(1.0f)
            binding.tvAvatarInitials.text = ContactViewActivity.initialsFor(name)

            if (registeredUser != null || isMe) {
                binding.llOnGlyphBadge.visibility = View.VISIBLE
                val profileUrl = if (isMe) (outgoingAvatarUrl ?: registeredUser?.profileImageUrl) else registeredUser?.profileImageUrl
                
                if (!profileUrl.isNullOrBlank()) {
                    binding.ivContactAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitials.visibility = View.INVISIBLE
                    Glide.with(context)
                        .load(profileUrl)
                        .placeholder(R.drawable.ic_default_avatar)
                        .circleCrop()
                        .into(binding.ivContactAvatar)
                } else {
                    binding.ivContactAvatar.visibility = View.GONE
                    binding.tvAvatarInitials.visibility = View.VISIBLE
                }
            } else {
                binding.ivContactAvatar.visibility = View.GONE
                binding.tvAvatarInitials.visibility = View.VISIBLE
                binding.llOnGlyphBadge.visibility = View.GONE
            }

            val openDetail = {
                if (selectionManager.hasSelection()) {
                    selectionManager.toggleSelection(msg.id)
                } else {
                    val intent = ContactViewActivity.newIntent(
                        context,
                        msg.contactName ?: "Contact",
                        msg.contactPhone ?: "",
                        registeredUser?.id,
                        registeredUser?.profileImageUrl ?: if(isMe) outgoingAvatarUrl else null,
                        registeredUser?.username,
                        currentUid,
                        currentUserPhone
                    )
                    context.startActivity(intent)
                }
            }
            binding.cardMessage.setOnClickListener { openDetail() }
            binding.cardMessage.setOnLongClickListener {
                handleBubbleLongPress(msg.id, itemView)
                true
            }

            // Action buttons
            if (isMe) {
                binding.btnMessage.visibility = View.GONE
                binding.btnInvite.visibility = View.GONE
            } else if (registeredUser != null) {
                binding.btnMessage.visibility = View.VISIBLE
                binding.btnInvite.visibility = View.GONE
                binding.btnMessage.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        val uid = currentUid ?: return@setOnClickListener
                        val registeredId = registeredUser.id
                        val chatId = if (uid < registeredId) "${uid}_$registeredId" else "${registeredId}_$uid"
                        val chatIntent = ChatActivity.newIntent(
                            context, chatId, registeredId,
                            ContactDisplayNameResolver.getDisplayName(
                                otherUserId = registeredUser.id,
                                remoteProfileName = registeredUser.username.ifBlank { msg.contactName ?: "Contact" },
                                remotePhoneNumber = msg.contactPhone
                            ),
                            registeredUser.profileImageUrl
                        )
                        context.startActivity(chatIntent)
                    }
                }
            } else {
                binding.btnMessage.visibility = View.GONE
                binding.btnInvite.visibility = View.VISIBLE
                binding.btnInvite.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        showBubbleInviteDialog(context, msg.contactName ?: "Contact", msg.contactPhone ?: "")
                    }
                }
            }

            updateGrouping(position)
            bindSelection(item, animate = false)
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
        }
    }

    inner class OutgoingContactViewHolder(
        private val binding: ItemMessageOutgoingContactBinding
    ) : BaseViewHolder(binding) {

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            val context = binding.root.context
            initializeColors(context)
            bindForwardedLabel(msg, isIncoming = false)

            binding.tvContactName.text = msg.contactName ?: "Contact"
            binding.tvContactPhone.text = msg.contactPhone ?: ""
            binding.tvTimestamp.text = msg.formattedTime

            // Status icon
            when (msg.status) {
                MessageStatus.SENDING   -> binding.ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT      -> binding.ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ      -> binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                MessageStatus.FAILED    -> binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                else                    -> binding.ivStatus.setImageResource(R.drawable.ic_check)
            }

            // Lookup registered user
            val normalizedPhone = PhoneNumberUtil.normalizeToLast10Digits(msg.contactPhone ?: "")
            val registeredUser = registeredUsersMap[normalizedPhone]

            val currentUid = currentUserId
            val myPhoneNormalized = if (!currentUserPhone.isNullOrEmpty()) PhoneNumberUtil.normalizeToLast10Digits(currentUserPhone!!) else null
            val isMe = (registeredUser?.id != null && registeredUser.id == currentUid) ||
                       (normalizedPhone.isNotEmpty() && normalizedPhone == myPhoneNormalized)

            // Avatar
            val name = msg.contactName ?: "Contact"
            val avatarColor = ContactViewActivity.avatarColorForName(name)
            binding.viewAvatarBg.backgroundTintList = android.content.res.ColorStateList.valueOf(avatarColor)
            binding.tvAvatarInitials.setAlpha(1.0f)
            binding.tvAvatarInitials.text = ContactViewActivity.initialsFor(name)

            if (registeredUser != null || isMe) {
                binding.llOnGlyphBadge.visibility = View.VISIBLE
                val profileUrl = if (isMe) (outgoingAvatarUrl ?: registeredUser?.profileImageUrl) else registeredUser?.profileImageUrl

                if (!profileUrl.isNullOrBlank()) {
                    binding.ivContactAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitials.visibility = View.INVISIBLE
                    Glide.with(context)
                        .load(profileUrl)
                        .placeholder(R.drawable.ic_default_avatar)
                        .circleCrop()
                        .into(binding.ivContactAvatar)
                } else {
                    binding.ivContactAvatar.visibility = View.GONE
                    binding.tvAvatarInitials.visibility = View.VISIBLE
                }
            } else {
                binding.ivContactAvatar.visibility = View.GONE
                binding.tvAvatarInitials.visibility = View.VISIBLE
                binding.llOnGlyphBadge.visibility = View.GONE
            }

            val openDetail = {
                if (selectionManager.hasSelection()) {
                    selectionManager.toggleSelection(msg.id)
                } else {
                    val intent = ContactViewActivity.newIntent(
                        context,
                        msg.contactName ?: "Contact",
                        msg.contactPhone ?: "",
                        registeredUser?.id,
                        registeredUser?.profileImageUrl ?: if(isMe) outgoingAvatarUrl else null,
                        registeredUser?.username,
                        currentUid,
                        currentUserPhone
                    )
                    context.startActivity(intent)
                }
            }
            binding.cardMessage.setOnClickListener { openDetail() }
            binding.cardMessage.setOnLongClickListener {
                handleBubbleLongPress(msg.id, itemView)
                true
            }

            // Action buttons
            if (isMe) {
                binding.btnMessage.visibility = View.GONE
                binding.btnInvite.visibility = View.GONE
            } else if (registeredUser != null) {
                binding.btnMessage.visibility = View.VISIBLE
                binding.btnInvite.visibility = View.GONE
                binding.btnMessage.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        val uid = currentUid ?: return@setOnClickListener
                        val registeredId = registeredUser.id
                        val chatId = if (uid < registeredId) "${uid}_$registeredId" else "${registeredId}_$uid"
                        val chatIntent = ChatActivity.newIntent(
                            context, chatId, registeredId,
                            ContactDisplayNameResolver.getDisplayName(
                                otherUserId = registeredUser.id,
                                remoteProfileName = registeredUser.username.ifBlank { msg.contactName ?: "Contact" },
                                remotePhoneNumber = msg.contactPhone
                            ),
                            registeredUser.profileImageUrl
                        )
                        context.startActivity(chatIntent)
                    }
                }
            } else {
                binding.btnMessage.visibility = View.GONE
                binding.btnInvite.visibility = View.VISIBLE
                binding.btnInvite.setOnClickListener {
                    if (selectionManager.hasSelection()) {
                        selectionManager.toggleSelection(msg.id)
                    } else {
                        showBubbleInviteDialog(context, msg.contactName ?: "Contact", msg.contactPhone ?: "")
                    }
                }
            }

            updateGrouping(position)
            bindSelection(item, animate = false)
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
        }
    }

    inner class DateHeaderViewHolder(private val binding: ItemDateHeaderBinding) : BaseViewHolder(binding) {
        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item is ChatListItem.DateHeader) {
                binding.tvDate.text = item.dateString
            }
        }
    }

    // ==============================================================================================
    // DOCUMENT BUBBLE VIEW HOLDERS
    // ==============================================================================================

    private fun showBubbleInviteDialog(context: android.content.Context, name: String, phoneNumber: String) {
        val activity = context as? androidx.appcompat.app.AppCompatActivity ?: return
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_invite, null)
        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.invite_title)
        val subtitleView = dialogView.findViewById<android.widget.TextView>(R.id.invite_subtitle)
        val previewView = dialogView.findViewById<android.widget.EditText>(R.id.invite_message_preview)
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_send_sms)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share)

        titleView.text = context.getString(R.string.invite_dialog_title, name)
        subtitleView.text = context.getString(R.string.invite_dialog_subtitle)
        val playStoreUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
        previewView.setText(context.getString(R.string.invite_message, playStoreUrl))

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setNegativeButton(context.getString(R.string.action_not_now), null)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        // Fix "Not Now" button visibility in light/pastel themes
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(
            getThemeColor(context, R.attr.glyphToolbarIcon)
        )

        btnSend.setOnClickListener {
            val msg = previewView.text.toString()
            dialog.dismiss()
            val smsUri = android.net.Uri.parse("smsto:${android.net.Uri.encode(phoneNumber)}")
            val smsIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO, smsUri).apply { putExtra("sms_body", msg) }
            runCatching { activity.startActivity(smsIntent) }
        }
        btnShare.setOnClickListener {
            val msg = previewView.text.toString()
            dialog.dismiss()
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                putExtra(android.content.Intent.EXTRA_TEXT, msg)
                type = "text/plain"
            }
            activity.startActivity(android.content.Intent.createChooser(shareIntent, context.getString(R.string.invite_share)))
        }
    }

    private fun getDocumentIconBg(fileName: String): Int {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf"                     -> R.drawable.bg_attachment_icon_red
            "doc", "docx", "odt"      -> R.drawable.bg_attachment_icon_blue
            "xls", "xlsx", "ods", "csv" -> R.drawable.bg_attachment_icon_green
            "ppt", "pptx", "odp"      -> R.drawable.bg_attachment_icon_orange
            "zip", "rar", "7z", "tar", "gz" -> R.drawable.bg_attachment_icon_teal
            "txt", "md", "rtf"        -> R.drawable.bg_attachment_icon_purple
            else                      -> R.drawable.bg_attachment_icon_indigo
        }
    }

    private fun getDocumentTypeLabel(fileName: String): String {
        return fileName.substringAfterLast('.', "").uppercase().ifEmpty { "FILE" }
    }

    private fun openDocumentUrl(url: String?, fileName: String, context: android.content.Context) {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "File not available", Toast.LENGTH_SHORT).show()
            return
        }
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val ext = fileName.substringAfterLast('.', "bin").lowercase()
        // Use a stable cache filename so we don't re-download the same file each time
        val cacheFile = java.io.File(context.cacheDir, "doc_${url.hashCode()}.$ext")
        Toast.makeText(context, "Opening document…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    java.net.URL(url).openStream().use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", cacheFile
                )
                val mime = getDocMimeType(ext)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                mainHandler.post {
                    try {
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        // Fall back to */* so the system chooser appears
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(contentUri, "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { context.startActivity(fallback) }
                        catch (e2: Exception) {
                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "Failed to open document", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun getDocMimeType(ext: String): String = when (ext) {
        "pdf"  -> "application/pdf"
        "doc"  -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls"  -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt"  -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt"  -> "text/plain"
        "csv"  -> "text/csv"
        "rtf"  -> "application/rtf"
        "zip"  -> "application/zip"
        "rar"  -> "application/x-rar-compressed"
        "7z"   -> "application/x-7z-compressed"
        else   -> "*/*"
    }

    inner class IncomingDocumentViewHolder(
        private val binding: ItemMessageIncomingDocumentBinding
    ) : BaseViewHolder(binding) {

        private var boundThumbModel: Any? = null

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            initializeColors(binding.root.context)
            bindForwardedLabel(msg, isIncoming = true)

            // Bubble background
            if (isPastelTheme) {
                binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_incoming)
                binding.cardMessage.backgroundTintList = null
            } else {
                binding.cardMessage.setBackgroundResource(R.drawable.bg_message_incoming)
                binding.cardMessage.backgroundTintList = tintOtherBubble
            }

            // Filename (stored in msg.text)
            binding.tvFileName.text = msg.text.ifBlank { "Document" }
            binding.tvFileName.setTextColor(colorOtherText)

            // File info: size + type
            val typeLabel = getDocumentTypeLabel(msg.text)
            val sizeText = if ((msg.fileSize ?: 0L) > 0L) " · ${formatFileSize(msg.fileSize!!)}" else ""
            binding.tvFileInfo.text = "$typeLabel$sizeText"
            binding.tvFileInfo.setTextColor(colorOtherText)

            // Icon background color based on file type
            binding.docIconBg.setBackgroundResource(getDocumentIconBg(msg.text))

            // PDF preview thumbnail
            val thumbModel = MessagePreviewCacheManager.resolveDocumentPreviewModel(msg.id, msg.thumbnailUrl)
            if (thumbModel != null) {
                binding.ivDocPreview.visibility = View.VISIBLE
                if (!(skipImageLoad && binding.ivDocPreview.drawable != null) && !(thumbModel == boundThumbModel && binding.ivDocPreview.drawable != null)) {
                    com.bumptech.glide.Glide.with(binding.root.context)
                        .load(thumbModel)
                        .transform(TopCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .dontAnimate()
                        .placeholder(ContextCompat.getDrawable(binding.root.context, android.R.color.white))
                        .into(binding.ivDocPreview)
                    boundThumbModel = thumbModel
                }
            } else {
                binding.ivDocPreview.visibility = View.GONE
                boundThumbModel = null
            }

            // Timestamp
            binding.tvTimestamp.text = msg.formattedTime

            // Caption (optional)
            val incomingCaption = msg.documentCaption
            if (!incomingCaption.isNullOrBlank()) {
                binding.tvCaption.text = incomingCaption
                binding.tvCaption.setTextColor(colorOtherText)
                binding.tvCaption.visibility = View.VISIBLE
            } else {
                binding.tvCaption.visibility = View.GONE
            }

            // Click to open
            binding.cardMessage.setOnClickListener {
                if (selectionManager.hasSelection()) {
                    selectionManager.toggleSelection(msg.id)
                } else {
                    openDocumentUrl(msg.imageUrl, msg.text, binding.root.context)
                }
            }
            binding.cardMessage.setOnLongClickListener {
                handleBubbleLongPress(msg.id, itemView)
                true
            }

            updateGrouping(position)
            maybeBounceIncoming(item, binding.cardMessage)
            bindSelection(item, animate = false)
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = true, fullRadiusDp = 8f)
        }
    }

    inner class OutgoingDocumentViewHolder(
        private val binding: ItemMessageOutgoingDocumentBinding
    ) : BaseViewHolder(binding) {

        private var lastStatus: MessageStatus? = null
        private var boundThumbModel: Any? = null

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item !is ChatListItem.MessageItem) return
            val msg = item.message
            initializeColors(binding.root.context)
            bindForwardedLabel(msg, isIncoming = false)

            // Bubble background
            if (isPastelTheme) {
                binding.cardMessage.setBackgroundResource(R.drawable.bg_pastel_bubble_outgoing)
                binding.cardMessage.backgroundTintList = null
            } else {
                binding.cardMessage.setBackgroundResource(R.drawable.bg_message_outgoing)
                binding.cardMessage.backgroundTintList = tintOwnBubble
            }

            // Filename (stored in msg.text)
            binding.tvFileName.text = msg.text.ifBlank { "Document" }
            binding.tvFileName.setTextColor(colorOwnText)

            // File info: size + type
            val typeLabel = getDocumentTypeLabel(msg.text)
            val sizeText = if ((msg.fileSize ?: 0L) > 0L) " · ${formatFileSize(msg.fileSize!!)}" else ""
            binding.tvFileInfo.text = "$typeLabel$sizeText"
            binding.tvFileInfo.setTextColor(colorOwnText)

            // Icon background color based on file type
            binding.docIconBg.setBackgroundResource(getDocumentIconBg(msg.text))

            // PDF preview thumbnail
            val thumbModel = MessagePreviewCacheManager.resolveDocumentPreviewModel(msg.id, msg.thumbnailUrl)
            if (thumbModel != null) {
                binding.ivDocPreview.visibility = View.VISIBLE
                if (!(skipImageLoad && binding.ivDocPreview.drawable != null) && !(thumbModel == boundThumbModel && binding.ivDocPreview.drawable != null)) {
                    com.bumptech.glide.Glide.with(binding.root.context)
                        .load(thumbModel)
                        .transform(TopCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .dontAnimate()
                        .placeholder(ContextCompat.getDrawable(binding.root.context, android.R.color.white))
                        .into(binding.ivDocPreview)
                    boundThumbModel = thumbModel
                }
            } else {
                binding.ivDocPreview.visibility = View.GONE
                boundThumbModel = null
            }

            // Timestamp
            binding.tvTimestamp.text = msg.formattedTime

            // Caption (optional)
            val outgoingCaption = msg.documentCaption
            if (!outgoingCaption.isNullOrBlank()) {
                binding.tvCaption.text = outgoingCaption
                binding.tvCaption.setTextColor(colorOwnText)
                binding.tvCaption.visibility = View.VISIBLE
            } else {
                binding.tvCaption.visibility = View.GONE
            }

            // Status icon
            setStatus(rememberStrongestOutgoingStatus(msg))

            // Click to open
            binding.cardMessage.setOnClickListener {
                if (selectionManager.hasSelection()) {
                    selectionManager.toggleSelection(msg.id)
                } else {
                    openDocumentUrl(msg.imageUrl, msg.text, binding.root.context)
                }
            }
            binding.cardMessage.setOnLongClickListener {
                handleBubbleLongPress(msg.id, itemView)
                true
            }

            updateGrouping(position)
            bindSelection(item, animate = false)
        }

        private fun setStatus(status: MessageStatus) {
            if (lastStatus == status) return
            binding.ivStatus.alpha = 1.0f
            if (status == MessageStatus.READ) {
                binding.ivStatus.setColorFilter(android.graphics.Color.parseColor("#4FC3F7"))
                binding.ivStatus.imageTintList = null
            } else {
                val color = (colorOwnText and 0x00FFFFFF) or 0x99000000.toInt()
                binding.ivStatus.setColorFilter(color)
                binding.ivStatus.imageTintList = null
            }
            when (status) {
                MessageStatus.SENDING   -> binding.ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT      -> binding.ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> binding.ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ      -> binding.ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                MessageStatus.FAILED    -> binding.ivStatus.setImageResource(R.drawable.ic_error_outline)
                else                    -> binding.ivStatus.setImageResource(R.drawable.ic_check)
            }
            lastStatus = status
        }

        override fun updateGrouping(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = getItem(position) as? ChatListItem.MessageItem ?: return
            val groupPos = item.groupPosition
            val topMargin = if (groupPos == BubbleGroupPosition.TOP || groupPos == BubbleGroupPosition.SINGLE) senderChangeGapPx else groupedGapPx
            applyItemTopMargin(binding.root, topMargin)
            applyBubbleCorners(binding.cardMessage, groupPos, isIncoming = false, fullRadiusDp = 8f)
        }
    }

    inner class GroupIntroViewHolder(
        private val binding: ItemGroupIntroCardBinding
    ) : BaseViewHolder(binding) {

        init {
            binding.composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
        }

        override fun bind(item: ChatListItem, position: Int, skipImageLoad: Boolean) {
            if (item !is ChatListItem.GroupIntroItem) return
            applyItemTopMargin(binding.root, senderChangeGapPx)
            binding.composeView.setContent {
                GlyphThemeProvider(isDeepDark = true) {
                    GroupIntroMessageCard(
                        groupName = item.groupName,
                        groupAvatarUrl = item.groupAvatarUrl,
                        description = item.description,
                        memberCount = item.memberCount,
                        onDescriptionClick = { onGroupIntroDescriptionClick?.invoke() },
                        onAddMembersClick = { onGroupIntroAddMembersClick?.invoke() },
                        onInviteClick = { onGroupIntroInviteClick?.invoke() }
                    )
                }
            }
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
        }
    }

    class ChatListItemDiffCallback : DiffUtil.ItemCallback<ChatListItem>() {
        override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ChatListItem, newItem: ChatListItem): Any? {
            if (oldItem is ChatListItem.MessageItem && newItem is ChatListItem.MessageItem) {
                val oldMsg = oldItem.message
                val newMsg = newItem.message
                if (oldMsg.id == newMsg.id) {
                    // Reactions changed — small payload to refresh only the chip.
                    if (oldMsg.reactions != newMsg.reactions) return "REACTIONS_UPDATE"

                    val mediaUrlsUnchanged = when (newMsg.type) {
                        MessageType.IMAGE, MessageType.GIF, MessageType.MEME,
                        MessageType.STICKER, MessageType.KLIPY_EMOJI -> (oldMsg.localUri ?: oldMsg.imageUrl) == (newMsg.localUri ?: newMsg.imageUrl)
                        MessageType.VIDEO -> {
                            if (newMsg.isVideoNote) {
                                // Video notes prefer localUri for thumbnail display,
                                // so only trigger image reload when localUri changes
                                (oldMsg.localUri ?: oldMsg.thumbnailUrl ?: oldMsg.videoUrl) == (newMsg.localUri ?: newMsg.thumbnailUrl ?: newMsg.videoUrl)
                            } else {
                                (oldMsg.thumbnailUrl ?: oldMsg.localUri ?: oldMsg.videoUrl) == (newMsg.thumbnailUrl ?: newMsg.localUri ?: newMsg.videoUrl)
                            }
                        }
                        MessageType.MEDIA_GROUP -> {
                            // For collages, compare mediaItems JSON to detect download updates
                            oldMsg.mediaItems == newMsg.mediaItems
                        }
                        else -> true
                    }

                    // Check if inline translation state changed
                    val translationChanged = oldItem.translatedText != newItem.translatedText ||
                        oldItem.isShowingTranslation != newItem.isShowingTranslation ||
                        oldItem.isTranslating != newItem.isTranslating
                    if (translationChanged && mediaUrlsUnchanged) return "TRANSLATION_CHANGED"

                    if (mediaUrlsUnchanged) return "NO_IMAGE_RELOAD"
                    
                    // If MEDIA_GROUP mediaItems changed, return payload to refresh media
                    if (newMsg.type == MessageType.MEDIA_GROUP && oldMsg.mediaItems != newMsg.mediaItems) {
                        return "MEDIA_UPDATED"
                    }
                }
            }
            // Typing indicator: always return a payload to prevent full rebind flicker
            if (oldItem is ChatListItem.TypingIndicator && newItem is ChatListItem.TypingIndicator) {
                return "TYPING_UPDATE"
            }
            return null
        }
    }
}

/**
 * Pixel-blur Glide transformation used to visually mark expired status thumbnails.
 * Achieves a fast blur via 8× scale-down + bilinear scale-up — no additional library needed.
 */
private class PixelBlurTransformation : BitmapTransformation() {
    override fun transform(
        pool: BitmapPool,
        toTransform: android.graphics.Bitmap,
        outWidth: Int,
        outHeight: Int
    ): android.graphics.Bitmap {
        val small = android.graphics.Bitmap.createScaledBitmap(
            toTransform, (outWidth / 8).coerceAtLeast(1), (outHeight / 8).coerceAtLeast(1), true
        )
        return android.graphics.Bitmap.createScaledBitmap(small, outWidth, outHeight, true)
    }
    override fun updateDiskCacheKey(md: java.security.MessageDigest) {
        md.update("PixelBlurTransformation".toByteArray(Charsets.UTF_8))
    }
    override fun equals(other: Any?) = other is PixelBlurTransformation
    override fun hashCode() = "PixelBlurTransformation".hashCode()
}

/**
 * Glide BitmapTransformation that scales the image to fill the view width
 * and anchors to the top, cropping the bottom — so the document header is always visible.
 */
private class TopCropTransform : BitmapTransformation() {
    override fun transform(
        pool: BitmapPool,
        toTransform: android.graphics.Bitmap,
        outWidth: Int,
        outHeight: Int
    ): android.graphics.Bitmap {
        if (toTransform.width == outWidth && toTransform.height == outHeight) return toTransform
        val scale = outWidth.toFloat() / toTransform.width.toFloat()
        val result = pool.get(outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
        canvas.scale(scale, scale)
        canvas.drawBitmap(toTransform, 0f, 0f, paint)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: java.security.MessageDigest) {
        messageDigest.update("TopCropTransform".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?) = other is TopCropTransform
    override fun hashCode() = TopCropTransform::class.java.name.hashCode()
}
