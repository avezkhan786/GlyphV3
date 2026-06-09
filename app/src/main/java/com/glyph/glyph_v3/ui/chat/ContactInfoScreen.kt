package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.ui.media.MediaViewerActivity
import com.glyph.glyph_v3.ui.media.VideoPlayerActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.chat.contactinfo.AdvancedChatPrivacyActivity
import com.glyph.glyph_v3.ui.chat.contactinfo.ChatNotificationsActivity
import com.glyph.glyph_v3.ui.chat.contactinfo.DisappearingMessagesActivity
import com.glyph.glyph_v3.ui.chat.contactinfo.ManageStorageActivity
import com.glyph.glyph_v3.ui.settings.GlyphSettingsSwitch
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.glyph.glyph_v3.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

// Lightweight representation of a fetchable media thumbnail in this chat
internal data class ChatMediaItem(val thumbnailUrl: String, val isVideo: Boolean)

/**
 * Process-level in-memory cache so ContactInfoScreen shows media instantly on first open.
 * ChatActivity calls [preload] as soon as the chat is ready; subsequent opens hit the cache
 * with zero latency. Entries expire after 5 minutes so stale data is refreshed automatically.
 */
internal object ContactInfoMediaCache {
    private data class Entry(val items: List<ChatMediaItem>, val fetchedAt: Long = System.currentTimeMillis())
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5L * 60 * 1000 // 5 minutes

    /** Returns fresh cached items, or null if missing / stale. */
    fun get(chatId: String): List<ChatMediaItem>? {
        val entry = cache[chatId] ?: return null
        return if (System.currentTimeMillis() - entry.fetchedAt < TTL_MS) entry.items else null
    }

    fun put(chatId: String, items: List<ChatMediaItem>) {
        cache[chatId] = Entry(items)
    }

    /**
     * Fetches media for [chatId] (if not already cached), stores the result, and
     * pre-warms Coil's memory + disk cache for every thumbnail URL so images render
     * without a network round-trip when the Contact Info screen opens.
     * Safe to call on any dispatcher.
     */
    suspend fun preload(chatId: String, context: android.content.Context) {
        if (chatId.isEmpty()) return
        val existing = get(chatId)
        val items = existing ?: loadChatMediaItems(chatId, context).also { put(chatId, it) }
        prewarmCoil(context, items)
    }

    private fun prewarmCoil(context: android.content.Context, items: List<ChatMediaItem>) {
        if (items.isEmpty()) return
        val loader = context.imageLoader
        items.forEach { item ->
            val data: Any = when {
                item.thumbnailUrl.startsWith("/") -> java.io.File(item.thumbnailUrl)
                item.thumbnailUrl.startsWith("file://") ->
                    java.io.File(android.net.Uri.parse(item.thumbnailUrl).path ?: item.thumbnailUrl)
                item.thumbnailUrl.startsWith("content://") ->
                    android.net.Uri.parse(item.thumbnailUrl)
                else -> item.thumbnailUrl
            }
            loader.enqueue(
                coil.request.ImageRequest.Builder(context)
                    .data(data)
                    .memoryCacheKey(item.thumbnailUrl)
                    .diskCacheKey(item.thumbnailUrl)
                    .build()
            )
        }
    }
}

/**
 * Mirrors ChatAdapter's resolution priority:
 *   1. localUri that is a file:// or absolute path AND file exists on disk
 *   2. localUri that is a content:// URI (trusted as-is)
 *   3. remoteUrl fallback
 */
private fun resolveMediaUrl(localUri: String?, remoteUrl: String?): String? {
    val TAG = "ContactInfo.Media"
    if (!localUri.isNullOrEmpty()) {
        val uri = android.net.Uri.parse(localUri)
        return if (uri.scheme == "file" || localUri.startsWith("/")) {
            val path = uri.path ?: localUri
            val exists = java.io.File(path).exists()
            if (exists) localUri else remoteUrl?.takeIf { it.isNotEmpty() }
        } else {
            localUri // content:// URIs are trusted as-is
        }
    }
    return remoteUrl?.takeIf { it.isNotEmpty() }
}

/** Fetches up to [limit] newest image/video thumbnails for [chatId].
 *
 * Strategy:
 *   1. Room local DB (ground truth — works offline, survives Firestore relay deletion)
 *   2. Firestore fallback (only if Room returns nothing, e.g. very first install / unsynced)
 */
private suspend fun loadChatMediaItems(
    chatId: String,
    context: android.content.Context,
    limit: Long = 24
): List<ChatMediaItem> {
    val TAG = "ContactInfo.Media"
    if (chatId.isEmpty()) {
        android.util.Log.w(TAG, "loadChatMediaItems: chatId is EMPTY — skipping query")
        return emptyList()
    }
    return try {
        // ── 1. Primary: local Room DB ─────────────────────────────────────────
        // All sent/received messages are persisted here. Since Firestore now acts
        // as a relay (deleted after delivery), Room is the only reliable source.
        val localMessages = com.glyph.glyph_v3.data.local.AppDatabase
            .getDatabase(context)
            .messageDao()
            .getMediaMessages(chatId, limit.toInt())

        if (localMessages.isNotEmpty()) {
            return localMessages.flatMap { msg ->
                when (msg.type.name) {
                    "VIDEO" -> {
                        // Pass the video source directly — Coil's VideoFrameDecoder will
                        // extract the first frame. Prefer local file (instant, offline),
                        // fall back to remote URL (network stream).
                        val url = msg.localUri?.takeIf { it.isNotEmpty() && java.io.File(it).exists() }
                            ?: msg.videoUrl?.takeIf { it.isNotEmpty() }
                        listOfNotNull(url?.let { ChatMediaItem(it, isVideo = true) })
                    }
                    "MEDIA_GROUP" -> {
                        val itemsJson = msg.mediaItems
                        if (!itemsJson.isNullOrEmpty()) {
                            try {
                                val arr = com.google.gson.Gson().fromJson(
                                    itemsJson,
                                    Array<com.glyph.glyph_v3.data.models.MediaItem>::class.java
                                )
                                arr.mapNotNull { item ->
                                    val remoteUrl = if (item.isVideo) (item.thumbnailUrl ?: item.url) else item.url
                                    val url = resolveMediaUrl(item.localUri, remoteUrl)
                                    url?.let { ChatMediaItem(it, isVideo = item.isVideo) }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "  [Room] MEDIA_GROUP JSON parse error: ${e.message}")
                                emptyList()
                            }
                        } else {
                            val url = resolveMediaUrl(msg.localUri, msg.imageUrl)
                            listOfNotNull(url?.let { ChatMediaItem(it, isVideo = false) })
                        }
                    }
                    else -> {
                        // IMAGE, GIF, MEME, STICKER, KLIPY_EMOJI
                        val url = resolveMediaUrl(msg.localUri, msg.imageUrl)
                        listOfNotNull(url?.let { ChatMediaItem(it, isVideo = false) })
                    }
                }
            }
        }

        // ── 2. Firestore fallback ────────────────────────────────────────────
        // Only reached when Room is empty (fresh install, messages not yet delivered locally).
        val mediaTypes = listOf("IMAGE", "VIDEO", "MEDIA_GROUP", "GIF", "MEME", "STICKER", "KLIPY_EMOJI")

        // Step 1: try with orderBy (requires composite index in Firestore)
        val snapshot = try {
            FirebaseFirestore.getInstance()
                .collection("chats").document(chatId)
                .collection("messages")
                .whereIn("type", mediaTypes)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
        } catch (indexEx: Exception) {
            android.util.Log.e(TAG, "Ordered query FAILED (missing composite index?). Retrying without orderBy. Error: ${indexEx.message}")
            // Step 2: fallback — no orderBy, sort client-side later
            FirebaseFirestore.getInstance()
                .collection("chats").document(chatId)
                .collection("messages")
                .whereIn("type", mediaTypes)
                .limit(limit)
                .get().await()
        }


        // flatMap so MEDIA_GROUP expands into one entry per item in the group
        val result = snapshot.documents.flatMap { doc ->
            val type = doc.getString("type") ?: run {
                android.util.Log.w(TAG, "  doc ${doc.id}: missing 'type' field — skipping")
                return@flatMap emptyList()
            }
            val localUri  = doc.getString("localUri")
            val imageUrl  = doc.getString("imageUrl")
            val thumbUrl  = doc.getString("thumbnailUrl")
            val videoUrl  = doc.getString("videoUrl")

            when (type) {
                "VIDEO" -> {
                    // Pass the video source directly — Coil's VideoFrameDecoder extracts
                    // the first frame. Prefer local file, fall back to remote video URL.
                    val url = localUri?.takeIf { it.isNotEmpty() && java.io.File(it).exists() }
                        ?: videoUrl?.takeIf { it.isNotEmpty() }
                    listOfNotNull(url?.let { ChatMediaItem(it, isVideo = true) })
                }
                "MEDIA_GROUP" -> {
                    val itemsJson = doc.getString("mediaItems")
                    if (!itemsJson.isNullOrEmpty()) {
                        try {
                            val arr = com.google.gson.Gson().fromJson(
                                itemsJson,
                                Array<com.glyph.glyph_v3.data.models.MediaItem>::class.java
                            )
                            arr.mapNotNull { item ->
                                val remoteUrl = if (item.isVideo) (item.thumbnailUrl ?: item.url) else item.url
                                val url = resolveMediaUrl(item.localUri, remoteUrl)
                                url?.let { ChatMediaItem(it, isVideo = item.isVideo) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "    MEDIA_GROUP JSON parse error: ${e.message}")
                            emptyList()
                        }
                    } else {
                        val url = resolveMediaUrl(localUri, imageUrl)
                        listOfNotNull(url?.let { ChatMediaItem(it, isVideo = false) })
                    }
                }
                "STICKER", "KLIPY_EMOJI", "MEME" -> {
                    val url = resolveMediaUrl(localUri, imageUrl)
                    listOfNotNull(url?.let { ChatMediaItem(it, isVideo = false) })
                }
                else -> {
                    val url = resolveMediaUrl(localUri, imageUrl)
                    listOfNotNull(url?.let { ChatMediaItem(it, isVideo = false) })
                }
            }
        }

        result
    } catch (e: Exception) {
        android.util.Log.e(TAG, "loadChatMediaItems FAILED with exception", e)
        emptyList()
    }
}

// ──────────────────────────────────────────────────────
// WhatsApp-style Contact Info Screen (2026 dark UI)
// ──────────────────────────────────────────────────────

private val WhatsAppDarkDivider = Color(0xFF222D34)
private val WhatsAppGreen = Color(0xFF00A884)
private val WhatsAppRed = Color(0xFFEF5350)
private val WhatsAppTextPrimary = Color(0xFFE9EDEF)
private val WhatsAppTextSecondary = Color(0xFF8696A0)
private val WhatsAppIconGrey = Color(0xFF8696A0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(
    contactName: String,
    contactPhone: String,
    contactAvatar: String,
    contactUserId: String,
    chatId: String,
    lastSeen: String,
    onBackClick: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val density = LocalDensity.current

    // Pinned top bar + scroll-driven floating avatar animation
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollState = rememberScrollState()

    // Collapse over first 160dp of scroll (0 = hero visible, 1 = fully collapsed)
    val heroTriggerPx = with(density) { 160.dp.toPx() }
    val scrollFraction by remember {
        derivedStateOf { min(1f, scrollState.value / heroTriggerPx) }
    }
    // Eased fraction gives organic feel (slow start, fast middle, slow end)
    val easedFraction by remember {
        derivedStateOf { FastOutSlowInEasing.transform(scrollFraction) }
    }
    val collapsedFraction = scrollFraction
    // Hero texts (name/phone/lastSeen) fade out as avatar travels up
    val heroAlpha by remember { derivedStateOf { 1f - scrollFraction } }

    // Avatar sizes
    val largeAvatarDp = 150.dp
    val smallAvatarDp = 36.dp

    // Back arrow right edge + center Y (static, captured on first layout)
    var backArrowRightPx by remember { mutableFloatStateOf(0f) }
    var backArrowCenterYPx by remember { mutableFloatStateOf(0f) }

    // Hero placeholder center in root coords (captured once at scroll=0)
    var heroPlaceholderCenterXPx by remember { mutableFloatStateOf(0f) }
    var heroPlaceholderBaseYPx by remember { mutableFloatStateOf(0f) }
    var heroMeasured by remember { mutableStateOf(false) }

    // Surface colors matching navigation bar - single consistent color throughout
    val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(context)
    val surfaceBg = when (currentTheme) {
        com.glyph.glyph_v3.utils.ThemeManager.THEME_DARK -> colorResource(id = R.color.dark_surface)
        com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY -> colorResource(id = R.color.pastel_background)
        else -> colorResource(id = R.color.light_surface)
    }
    val dividerColor = if (theme.isDark) WhatsAppDarkDivider else theme.divider
    val textPrimary = if (theme.isDark) WhatsAppTextPrimary else theme.textPrimary
    val textSecondary = if (theme.isDark) WhatsAppTextSecondary else theme.textSecondary
    val iconGrey = if (theme.isDark) WhatsAppIconGrey else theme.iconSecondary
    val accentGreen = if (theme.isDark) WhatsAppGreen else theme.actionPrimary
    val destructiveRed = if (theme.isDark) WhatsAppRed else theme.actionDestructive

    // Load real media thumbnails — serve from cache instantly, refresh in background if stale
    val mediaItems by produceState<List<ChatMediaItem>>(
        initialValue = ContactInfoMediaCache.get(chatId) ?: emptyList(),
        chatId
    ) {
        val cached = ContactInfoMediaCache.get(chatId)
        if (cached != null) {
            value = cached
            return@produceState // cache is fresh — no need to fetch
        }
        val fresh = loadChatMediaItems(chatId, context)
        ContactInfoMediaCache.put(chatId, fresh)
        value = fresh
    }

    // ── Privacy gate: check if other user allows us to see their profile photo ──
    val privacyGatedAvatar by produceState(initialValue = "", contactUserId) {
        try {
            if (contactUserId.isEmpty()) {
                value = ""
                return@produceState
            }
            val canSee = AvatarVisibilityRepository.refreshProfilePhotoVisibility(contactUserId).isVisible
            value = if (canSee) contactAvatar else ""
        } catch (_: Exception) {
            value = ""
        }
    }

    // Root Box lets the floating avatar render over the top bar without clipping
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = surfaceBg,
        topBar = {
            ContactInfoTopBar(
                contactName = contactName,
                collapsedFraction = collapsedFraction,
                scrollBehavior = scrollBehavior,
                surfaceBg = surfaceBg,
                textPrimary = textPrimary,
                onBackClick = onBackClick,
                onBackArrowPositioned = { rightPx, centerYPx ->
                    backArrowRightPx = rightPx
                    backArrowCenterYPx = centerYPx
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding()
                )
                .verticalScroll(scrollState)
        ) {
            // ── 1. Hero / Identity Section ──
            HeroSection(
                contactName = contactName,
                contactPhone = contactPhone,
                lastSeen = lastSeen,
                surfaceBg = surfaceBg,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                heroAlpha = heroAlpha,
                largeAvatarDp = largeAvatarDp,
                onAvatarPlaceholderPositioned = { centerXPx, centerYPx ->
                    if (!heroMeasured) {
                        heroPlaceholderCenterXPx = centerXPx
                        heroPlaceholderBaseYPx = centerYPx
                        heroMeasured = true
                    }
                }
            )

            SectionSpacer(surfaceBg)

            // ── 2. Action Buttons Row ──
            ActionButtonsRow(
                accentGreen = accentGreen,
                textSecondary = textSecondary,
                surfaceBg = surfaceBg,
                dividerColor = dividerColor
            )

            SectionSpacer(surfaceBg)

            // ── 3. Media Preview Section ──
            MediaPreviewSection(
                mediaItems = mediaItems,
                accentGreen = accentGreen,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                iconGrey = iconGrey,
                surfaceBg = surfaceBg,
                chatId = chatId,
                contactName = contactName
            )

            SectionSpacer(surfaceBg)

            // ── 4. Storage Section ──
            val totalMediaBytes by produceState(initialValue = -1L, chatId) {
                value = withContext(Dispatchers.IO) {
                    try {
                        AppDatabase.getDatabase(context)
                            .messageDao()
                            .getMediaMessages(chatId, 500)
                            .sumOf { it.fileSize ?: 0L }
                    } catch (e: Exception) { 0L }
                }
            }
            val storageSizeLabel = remember(totalMediaBytes) {
                when {
                    totalMediaBytes < 0L -> "…"
                    totalMediaBytes == 0L -> "0 B"
                    totalMediaBytes < 1_024L -> "${totalMediaBytes} B"
                    totalMediaBytes < 1_048_576L -> "${"%,.1f".format(totalMediaBytes / 1_024.0)} KB"
                    totalMediaBytes < 1_073_741_824L -> "${"%,.1f".format(totalMediaBytes / 1_048_576.0)} MB"
                    else -> "${"%,.2f".format(totalMediaBytes / 1_073_741_824.0)} GB"
                }
            }
            SettingsRow(
                icon = R.drawable.ic_storage,
                title = "Manage Storage",
                subtitle = storageSizeLabel,
                iconTint = iconGrey,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                surfaceBg = surfaceBg,
                onClick = {
                    context.startActivity(
                        ManageStorageActivity.newIntent(
                            context = context,
                            chatId = chatId,
                            contactName = contactName,
                            contactAvatar = privacyGatedAvatar
                        )
                    )
                }
            )

            SectionSpacer(surfaceBg)

            // ── 5. Settings Section ──
            SettingsSection(
                contactName = contactName,
                chatId = chatId,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                iconGrey = iconGrey,
                surfaceBg = surfaceBg,
                dividerColor = dividerColor
            )

            SectionSpacer(surfaceBg)

            // ── 6. Social & Chat Actions ──
            SocialActionsSection(
                contactName = contactName,
                chatId = chatId,
                textPrimary = textPrimary,
                iconGrey = iconGrey,
                accentGreen = accentGreen,
                surfaceBg = surfaceBg,
                dividerColor = dividerColor
            )

            SectionSpacer(surfaceBg)

            // ── 7. Destructive Actions ──
            DestructiveActionsSection(
                contactName = contactName,
                destructiveRed = destructiveRed,
                surfaceBg = surfaceBg
            )

            // Bottom padding for nav gesture area
            Spacer(modifier = Modifier.height(32.dp))
        }
    } // end Scaffold

    // ── Floating avatar overlay ──
    // Positioned in root coordinates so it can travel into the top bar
    if (heroMeasured && backArrowCenterYPx > 0f) {
        val largeAvatarPx = with(density) { largeAvatarDp.toPx() }
        val smallAvatarPx = with(density) { smallAvatarDp.toPx() }
        // Hero center moves upward as content scrolls
        val naturalHeroCenterY = heroPlaceholderBaseYPx - scrollState.value
        // Target: just right of back arrow, vertically centered in toolbar
        val gapPx = with(density) { 0.dp.toPx() }
        val targetCenterX = backArrowRightPx + gapPx + smallAvatarPx / 2f
        val targetCenterY = backArrowCenterYPx
        // Interpolated size and center position
        val currentSizePx = largeAvatarPx + (smallAvatarPx - largeAvatarPx) * easedFraction
        val currentCenterX = heroPlaceholderCenterXPx +
            (targetCenterX - heroPlaceholderCenterXPx) * easedFraction
        val currentCenterY = naturalHeroCenterY +
            (targetCenterY - naturalHeroCenterY) * easedFraction
        val currentSizeDp = with(density) { currentSizePx.toDp() }
        FloatingAvatarOverlay(
            contactAvatar = privacyGatedAvatar,
            contactUserId = contactUserId,
            contactName = contactName,
            sizeDp = currentSizeDp,
            centerXPx = currentCenterX,
            centerYPx = currentCenterY
        )
    }
    } // end root Box
}

// ──────────────────────────────────────────────────────
// Top App Bar with collapsing behavior
// ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactInfoTopBar(
    contactName: String,
    collapsedFraction: Float,
    scrollBehavior: TopAppBarScrollBehavior,
    surfaceBg: Color,
    textPrimary: Color,
    onBackClick: () -> Unit,
    onBackArrowPositioned: (rightPx: Float, centerYPx: Float) -> Unit = { _, _ -> }
) {
    TopAppBar(
        title = {
            // Name fades in beside the settled avatar.
            // paddingStart = smallAvatar(36dp) so text clears the floating avatar.
            Text(
                text = contactName,
                color = textPrimary.copy(alpha = collapsedFraction),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 36.dp)
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // Capture right edge + vertical center so floating avatar knows its landing zone
                    val rightPx = coords.positionInRoot().x + coords.size.width.toFloat()
                    val centerYPx = coords.positionInRoot().y + coords.size.height / 2f
                    onBackArrowPositioned(rightPx, centerYPx)
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        actions = {
            IconButton(onClick = { /* overflow menu */ }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more_vert),
                    contentDescription = "Menu",
                    tint = textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = surfaceBg,
            scrolledContainerColor = surfaceBg,
            navigationIconContentColor = textPrimary,
            titleContentColor = textPrimary,
            actionIconContentColor = textPrimary
        )
    )
}

// ──────────────────────────────────────────────────────
// Hero / Identity Section
// ──────────────────────────────────────────────────────
@Composable
private fun HeroSection(
    contactName: String,
    contactPhone: String,
    lastSeen: String,
    surfaceBg: Color,
    textPrimary: Color,
    textSecondary: Color,
    heroAlpha: Float = 1f,
    largeAvatarDp: Dp = 120.dp,
    onAvatarPlaceholderPositioned: (centerXPx: Float, centerYPx: Float) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .padding(top = 0.dp, bottom = 0.dp)
            .offset(y = (-35).dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Invisible Spacer – holds layout space for the floating avatar overlay
        Spacer(
            modifier = Modifier
                .size(largeAvatarDp)
                .onGloballyPositioned { coords ->
                    val centerX = coords.positionInRoot().x + coords.size.width / 2f
                    val centerY = coords.positionInRoot().y + coords.size.height / 2f
                    onAvatarPlaceholderPositioned(centerX, centerY)
                }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Name + phone + lastSeen fade out together with the avatar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(heroAlpha),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Contact Name
            Text(
                text = contactName,
                color = textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            
            // Phone Number
            if (contactPhone.isNotEmpty()) {
                val formattedPhone = remember(contactPhone) {
                    PhoneNumberUtil.formatForDisplay(contactPhone)
                }
                Text(
                    text = formattedPhone,
                    color = textSecondary,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (lastSeen.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                // Show "Online" status in same green as video icon, other statuses in secondary color
                val statusColor = if (lastSeen.contains("Online", ignoreCase = true)) Color(0xFF21C063) else textSecondary
                Text(
                    text = lastSeen,
                    color = statusColor,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────
// Action Buttons Row (Audio · Video · Pay · Search)
// ──────────────────────────────────────────────────────
@Composable
private fun ActionButtonsRow(
    accentGreen: Color,
    textSecondary: Color,
    surfaceBg: Color,
    dividerColor: Color
) {
    val customIconColor = Color(0xFF21C063)
    val customBorderColor = Color(0xFF202529)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionButton(
            icon = R.drawable.ic_call,
            label = "Audio",
            accentColor = customIconColor,
            textColor = textSecondary,
            borderColor = customBorderColor,
            modifier = Modifier.weight(1f),
            onClick = {}
        )
        ActionButton(
            icon = R.drawable.ic_videocam,
            label = "Video",
            accentColor = customIconColor,
            textColor = textSecondary,
            borderColor = customBorderColor,
            modifier = Modifier.weight(1f),
            onClick = {}
        )
        ActionButton(
            icon = R.drawable.ic_rupee_new,
            label = "Pay",
            accentColor = customIconColor,
            textColor = textSecondary,
            borderColor = customBorderColor,
            modifier = Modifier.weight(1f),
            onClick = {}
        )
        ActionButton(
            icon = R.drawable.ic_search,
            label = "Search",
            accentColor = customIconColor,
            textColor = textSecondary,
            borderColor = customBorderColor,
            modifier = Modifier.weight(1f),
            onClick = {}
        )
    }
}

@Composable
private fun ActionButton(
    icon: Int,
    label: String,
    accentColor: Color,
    textColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(68.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

// ──────────────────────────────────────────────────────
// Media, links, and docs section
// ──────────────────────────────────────────────────────
@Composable
private fun MediaPreviewSection(
    mediaItems: List<ChatMediaItem>,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    iconGrey: Color,
    surfaceBg: Color,
    chatId: String = "",
    contactName: String = ""
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
    ) {
        // Title Row: "Media, links, and docs" + count + chevron
        val ctx = LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    ctx.startActivity(
                        MediaDocsLinksActivity.newIntent(
                            context = ctx,
                            contactName = contactName,
                            chatId = chatId
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Media, links, and docs",
                color = textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (mediaItems.isNotEmpty()) {
                Text(
                    text = "${mediaItems.size}",
                    color = textSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = iconGrey,
                modifier = Modifier.size(20.dp)
            )
        }

        if (mediaItems.isEmpty()) {
            // Show subtle empty state instead of broken placeholders
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No media yet",
                    color = textSecondary.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        } else {
            // Horizontal scrollable real thumbnails
            // Pre-build the MediaItem list once so the lambda captures it by reference
            val context = LocalContext.current
            val displayedItems = remember(mediaItems) { mediaItems.take(12) }
            val hasMoreItems = remember(mediaItems) { mediaItems.size > 12 }
            val viewerItems = remember(mediaItems) {
                mediaItems.map { item ->
                    MediaItem(
                        url = item.thumbnailUrl,
                        type = if (item.isVideo) MediaType.VIDEO else MediaType.IMAGE,
                        thumbnailUrl = if (item.isVideo) item.thumbnailUrl else null
                    )
                }
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(displayedItems) { index, item ->
                    MediaThumbnail(item) {
                        if (item.isVideo) {
                            context.startActivity(
                                VideoPlayerActivity.newIntent(context, item.thumbnailUrl)
                            )
                        } else {
                            context.startActivity(
                                MediaViewerActivity.newIntentWithMultipleMedia(
                                    context = context,
                                    mediaItems = viewerItems,
                                    startIndex = index
                                )
                            )
                        }
                    }
                }
                
                // Forward arrow after 12 items if there are more
                if (hasMoreItems) {
                    item {
                        Box(
                            modifier = Modifier
                                .size(108.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Transparent)
                                .clickable {
                                    context.startActivity(
                                        MediaDocsLinksActivity.newIntent(
                                            context = context,
                                            contactName = contactName,
                                            chatId = chatId
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_right),
                                contentDescription = "View all media",
                                tint = Color(0xFFF7F8FA),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(item: ChatMediaItem, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(108.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A2328))
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        // Resolve to the correct Coil-compatible data type based on URI scheme
        val imageData: Any = when {
            item.thumbnailUrl.startsWith("/") -> java.io.File(item.thumbnailUrl)
            item.thumbnailUrl.startsWith("file://") ->
                java.io.File(android.net.Uri.parse(item.thumbnailUrl).path ?: item.thumbnailUrl)
            item.thumbnailUrl.startsWith("content://") ->
                android.net.Uri.parse(item.thumbnailUrl)
            else -> item.thumbnailUrl
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageData)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // Video play badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────
// Settings Section
// ──────────────────────────────────────────────────────
@Composable
private fun SettingsSection(
    contactName: String,
    chatId: String,
    textPrimary: Color,
    textSecondary: Color,
    iconGrey: Color,
    surfaceBg: Color,
    dividerColor: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Read persisted chat-level settings synchronously for instant render
    var disappearingTimer by remember {
        mutableStateOf(ChatSettingsDataStore.getDisappearingTimer(context, chatId))
    }
    var chatLockEnabled by remember {
        mutableStateOf(ChatSettingsDataStore.isChatLocked(context, chatId))
    }
    var advancedPrivacy by remember {
        mutableStateOf(ChatSettingsDataStore.isAdvancedPrivacyEnabled(context, chatId))
    }
    var translateEnabled by remember {
        mutableStateOf(ChatSettingsDataStore.isTranslateEnabled(context, chatId))
    }
    var mediaVisible by remember {
        mutableStateOf(ChatSettingsDataStore.isMediaVisible(context, chatId))
    }

    // Keep states fresh when returning from sub-screens
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                disappearingTimer = ChatSettingsDataStore.getDisappearingTimer(context, chatId)
                advancedPrivacy = ChatSettingsDataStore.isAdvancedPrivacyEnabled(context, chatId)
                chatLockEnabled = ChatSettingsDataStore.isChatLocked(context, chatId)
                translateEnabled = ChatSettingsDataStore.isTranslateEnabled(context, chatId)
                mediaVisible = ChatSettingsDataStore.isMediaVisible(context, chatId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
    ) {
        // Notifications
        SettingsRowInline(
            icon = R.drawable.ic_notifications,
            title = "Notifications",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            onClick = {
                context.startActivity(
                    ChatNotificationsActivity.newIntent(context, chatId, contactName)
                )
            }
        )

        SettingsDivider(dividerColor)

        // Media visibility – toggle
        SettingsRowWithToggle(
            icon = R.drawable.ic_media_visibility,
            title = "Media visibility",
            isChecked = mediaVisible,
            onCheckedChange = { newVal ->
                mediaVisible = newVal
                scope.launch { ChatSettingsDataStore.setMediaVisible(context, chatId, newVal) }
            },
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        SettingsDivider(dividerColor)

        // Encryption
        SettingsRowWithSubtitle(
            icon = R.drawable.ic_encryption,
            title = "Encryption",
            subtitle = "Messages and calls are end-to-end encrypted. Tap to verify.",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onClick = {}
        )

        SettingsDivider(dividerColor)

        // Disappearing messages – reads live timer value
        SettingsRowWithSubtitle(
            icon = R.drawable.ic_disappearing,
            title = "Disappearing messages",
            subtitle = ChatSettingsDataStore.disappearingTimerLabel(disappearingTimer),
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onClick = {
                context.startActivity(
                    DisappearingMessagesActivity.newIntent(context, chatId, contactName)
                )
            }
        )

        SettingsDivider(dividerColor)

        // Chat lock – bottom sheet + biometric when enabling
        var showChatLockSheet by remember { mutableStateOf(false) }
        SettingsRowWithToggle(
            icon = R.drawable.ic_chat_lock,
            title = "Chat lock",
            subtitle = "Lock and hide this chat on this device.",
            isChecked = chatLockEnabled,
            onCheckedChange = { newVal ->
                if (newVal && !chatLockEnabled) {
                    showChatLockSheet = true   // enable path: show bottom sheet
                } else if (!newVal && chatLockEnabled) {
                    chatLockEnabled = false
                    scope.launch { ChatSettingsDataStore.updateLockedSet(context, chatId, false) }
                }
            },
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )
        if (showChatLockSheet) {
            ChatLockBottomSheet(
                chatId = chatId,
                onDismiss = { showChatLockSheet = false },
                onLocked = {
                    chatLockEnabled = true
                    showChatLockSheet = false
                    scope.launch { ChatSettingsDataStore.updateLockedSet(context, chatId, true) }
                }
            )
        }

        SettingsDivider(dividerColor)

        // Advanced chat privacy – reads live value
        SettingsRowInline(
            icon = R.drawable.ic_advanced_privacy,
            title = "Advanced chat privacy",
            subtitle = if (advancedPrivacy) "On" else "Off",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onClick = {
                context.startActivity(
                    AdvancedChatPrivacyActivity.newIntent(context, chatId)
                )
            }
        )

        SettingsDivider(dividerColor)

        // Translate messages – toggle
        SettingsRowWithToggle(
            icon = R.drawable.ic_translate_glyph,
            title = "Translate messages",
            isChecked = translateEnabled,
            onCheckedChange = { newVal ->
                translateEnabled = newVal
                scope.launch { ChatSettingsDataStore.setTranslateEnabled(context, chatId, newVal) }
            },
            iconTint = iconGrey,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )
    }
}

// ──────────────────────────────────────────────────────
// Social & Chat Actions section
// ──────────────────────────────────────────────────────
@Composable
private fun SocialActionsSection(
    contactName: String,
    chatId: String,
    textPrimary: Color,
    iconGrey: Color,
    accentGreen: Color,
    surfaceBg: Color,
    dividerColor: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Clear chat UX state
    var showClearChatDialog by remember { mutableStateOf(false) }
    var isClearingChat by remember { mutableStateOf(false) }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { if (!isClearingChat) showClearChatDialog = false },
            title = { Text("Clear this chat?", color = textPrimary) },
            text = {
                Text(
                    "All messages will be permanently deleted from this chat. This action cannot be undone.",
                    color = textPrimary.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isClearingChat,
                    onClick = {
                        // Guard against double execution: ignore taps while a clear is running.
                        if (isClearingChat) return@TextButton
                        isClearingChat = true
                        scope.launch {
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    (context.applicationContext as com.glyph.glyph_v3.GlyphApplication)
                                        .getOrCreateRealtimeRepository()
                                        .clearChatMessages(chatId)
                                }
                                true
                            } catch (e: Exception) {
                                Log.e("ContactInfoScreen", "Failed to clear chat", e)
                                false
                            }
                            isClearingChat = false
                            showClearChatDialog = false
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Chat cleared" else "Failed to clear chat",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    if (isClearingChat) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = WhatsAppRed
                        )
                    } else {
                        Text("Clear", color = WhatsAppRed)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isClearingChat,
                    onClick = { showClearChatDialog = false }
                ) {
                    Text("Cancel", color = textPrimary)
                }
            },
            containerColor = surfaceBg
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
    ) {
        // No groups in common – label
        Text(
            text = "No groups in common",
            color = Color(0xFF8696A0),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 72.dp, top = 14.dp, bottom = 6.dp)
        )

        SettingsDivider(dividerColor)

        // Create group with contact
        SettingsRowInline(
            icon = R.drawable.ic_group,
            title = "Create group with $contactName",
            iconTint = accentGreen,
            textPrimary = textPrimary,
            onClick = {}
        )

        SettingsDivider(dividerColor)

        // Add to Favourites
        SettingsRowInline(
            icon = R.drawable.ic_favourite,
            title = "Add to Favourites",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            onClick = {}
        )

        SettingsDivider(dividerColor)

        // Add to list
        SettingsRowInline(
            icon = R.drawable.ic_list,
            title = "Add to list",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            onClick = {}
        )

        SettingsDivider(dividerColor)

        // Clear chat
        SettingsRowInline(
            icon = R.drawable.ic_clear_chat,
            title = "Clear chat",
            iconTint = iconGrey,
            textPrimary = textPrimary,
            onClick = { if (!isClearingChat) showClearChatDialog = true }
        )
    }
}

// ──────────────────────────────────────────────────────
// Destructive Actions (Red)
// ──────────────────────────────────────────────────────
@Composable
private fun DestructiveActionsSection(
    contactName: String,
    destructiveRed: Color,
    surfaceBg: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
    ) {
        // Block
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { /* block */ }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_block_glyph),
                contentDescription = null,
                tint = destructiveRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = "Block $contactName",
                color = destructiveRed,
                fontSize = 16.sp
            )
        }

        // Report
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { /* report */ }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_report),
                contentDescription = null,
                tint = destructiveRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = "Report $contactName",
                color = destructiveRed,
                fontSize = 16.sp
            )
        }
    }
}

// ──────────────────────────────────────────────────────
// Reusable Row Components
// ──────────────────────────────────────────────────────

/** Single row with icon, title, optional subtitle – full width clickable */
@Composable
private fun SettingsRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    iconTint: Color,
    textPrimary: Color,
    textSecondary: Color,
    surfaceBg: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = 16.sp
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = textSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** Inline row used within a settings section (no independent background) */
@Composable
private fun SettingsRowInline(
    icon: Int,
    title: String,
    subtitle: String? = null,
    iconTint: Color,
    textPrimary: Color,
    textSecondary: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = 16.sp
            )
            if (subtitle != null && textSecondary != Color.Unspecified) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = textSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** Row with subtitle text */
@Composable
private fun SettingsRowWithSubtitle(
    icon: Int,
    title: String,
    subtitle: String,
    iconTint: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = textSecondary,
                fontSize = 14.sp,
                lineHeight = 18.sp
            )
        }
    }
}

/** Row with a Material switch toggle */
@Composable
private fun SettingsRowWithToggle(
    icon: Int,
    title: String,
    subtitle: String? = null,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconTint: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = 16.sp
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        GlyphSettingsSwitch(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** Thin divider with left indent matching icon + spacing */
@Composable
private fun SettingsDivider(color: Color) {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        thickness = 0.5.dp,
        color = color
    )
}

// ──────────────────────────────────────────────────────
// Chat Lock Bottom Sheet
// ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatLockBottomSheet(
    chatId: String,
    onDismiss: () -> Unit,
    onLocked: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accentGreen = Color(0xFF00A884)
    val bgColor = Color(0xFF0B141A)
    val textPrimary = Color.White
    val textSecondary = Color(0xFF8D9598)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1F2C34),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF8D9598).copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lock illustration
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 20.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(accentGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chat_lock),
                    contentDescription = null,
                    tint = accentGreen,
                    modifier = Modifier.size(40.dp)
                )
            }

            // Header
            Text(
                text = "Keep this chat locked and hidden",
                color = textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Description
            Text(
                text = "This chat will move to a locked folder that can only be accessed with your fingerprint, face, or device PIN.",
                color = textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Continue button → biometric
            Button(
                onClick = {
                    val activity = context as? FragmentActivity ?: return@Button
                    val executor = ContextCompat.getMainExecutor(activity)
                    val biometricPrompt = BiometricPrompt(
                        activity,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult
                            ) {
                                onLocked()
                            }
                        }
                    )
                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Lock chat")
                        .setSubtitle("Verify your identity to lock this chat")
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build()
                    biometricPrompt.authenticate(promptInfo)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
            ) {
                Text(
                    text = "Continue",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel link
            TextButton(onClick = onDismiss) {
                Text(text = "Not now", color = textSecondary, fontSize = 15.sp)
            }
        }
    }
}

/** 8dp-tall spacer using the dark surface background between sections */
@Composable
private fun SectionSpacer(surfaceBg: Color) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(surfaceBg.copy(alpha = 0.2f))
    )
}

// ──────────────────────────────────────────────────────
// Floating avatar overlay
// Renders at absolute screen coordinates (outside scroll hierarchy)
// so it can travel smoothly from hero position into the top bar
// ──────────────────────────────────────────────────────
@Composable
private fun FloatingAvatarOverlay(
    contactAvatar: String,
    contactUserId: String,
    contactName: String,
    sizeDp: Dp,
    centerXPx: Float,
    centerYPx: Float
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }
    val topLeftX = (centerXPx - sizePx / 2f).toInt()
    val topLeftY = (centerYPx - sizePx / 2f).toInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(topLeftX, topLeftY) }
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(0xFF2A3942)),
        contentAlignment = Alignment.Center
    ) {
        if (contactAvatar.isNotEmpty()) {
            val localPath = remember(contactUserId) {
                try {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager
                        .getLocalAvatarPath(contactUserId)
                } catch (_: Exception) { null }
            }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(localPath?.let { java.io.File(it) } ?: contactAvatar)
                    .crossfade(false)
                    .build(),
                contentDescription = "Profile picture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Initial letter – font proportional to avatar size
            val fontSizeSp = with(density) { (sizePx * 0.38f).toSp() }
            Text(
                text = contactName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = fontSizeSp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
