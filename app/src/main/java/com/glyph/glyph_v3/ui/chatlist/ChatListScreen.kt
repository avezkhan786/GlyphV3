package com.glyph.glyph_v3.ui.chatlist

import android.graphics.Rect
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.cache.AvatarStateManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.ui.aiagent.AiAgentConstants
import com.glyph.glyph_v3.ui.theme.LocalGlyphTheme
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.ui.chat.OfficialGlyphAvatar
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ChatStatusRingState {
    NONE,
    SEEN,
    UNSEEN
}

private const val TAG = "ChatListScroll"

/** Process-level guard: auto-reveal hidden sections only once per cold start. */
private var sColdStartRevealDone = false

/**
 * Screen-level scroll-active signal consumed deep in the row tree by
 * [PresenceIndicator] (presence pulse) and [TypingIndicator] (typing dots) to
 * suspend their infinite animations while the list is flinging.
 *
 * Carries a State<Boolean> — not a plain Boolean — so only the leaf composables
 * that read `.value` recompose on the idle↔scroll flip; ChatRow / Avatar / Text
 * stay untouched, and the provider itself never recomposes. The source is
 * [rememberLazyListState] [.isScrollInProgress] wrapped in [derivedStateOf],
 * which collapses the frequent per-frame scroll-position writes into a single
 * boolean change so continuous scrolling triggers zero recomposition from this
 * provider. Reclaims UI-thread frame budget for composing newly-visible rows
 * during fling — the bottleneck confirmed by gfxinfo (GPU 2-5ms, "Slow UI
 * thread" frames, tail to 19-34ms).
 */
private val LocalListScrolling = compositionLocalOf<State<Boolean>> {
    error("LocalListScrolling must be provided by ChatListScreen")
}

/**
 * Public-API prefetch strategy that widens the sibling beyond-viewport
 * precomposition window to [aheadCount] rows ahead of the current scroll
 * direction, instead of Compose's default single ahead row.
 *
 * WHY: gfxinfo on this list shows "Slow UI thread" frames during fling while the
 * GPU sits at 2-5ms — the bottleneck is composing newly-entering rows on the UI
 * thread. Compose's internal [androidx.compose.foundation.lazy.DefaultLazyListPrefetchStrategy]
 * precomposes exactly ONE ahead row; widening the window means more rows are
 * already composed when fling reaches them. This class is the Config C test:
 * does a wider window actually move composition OFF the fling frame, or does the
 * extra prefetch land on fling frames and make jank worse? Only keep it if the
 * A/B/C/D medians (Phase 7) demonstrate the former.
 *
 * FIDELITY — mirrors the default's algorithm exactly (decoded from the 1.9.5
 * bytecode): direction from the scroll-delta sign, the ahead anchor from the
 * first/last visible item, the "skip rescheduling when the anchor hasn't moved"
 * guard, and the urgency test that runs the NEAREST ahead row's composition this
 * frame when this frame's delta will reach it (`markAsUrgent()`). It only
 * generalises the 1-row window to N rows, keeping a small ordered map of
 * [LazyLayoutPrefetchState.PrefetchHandle]s so a sliding anchor diff/cancels
 * just the one handle that left the window (O(1), zero reschedule work while the
 * anchor is steady mid-row — the common fling case).
 *
 * ONLY PUBLIC APIs are touched: [LazyListPrefetchStrategy],
 * [LazyListPrefetchScope]'s schedulePrefetch, [LazyLayoutPrefetchState.PrefetchHandle]
 * .cancel / markAsUrgent, [LazyListLayoutInfo], [LazyListItemInfo]. No internal
 * Compose classes, no `$foundation_release` accessors, no per-frame
 * LaunchedEffect / snapshotFlow — the Compose prefetch scheduler drives these
 * callbacks; we never poll scroll state ourselves (Phase 5 satisfied by design).
 */
@OptIn(ExperimentalFoundationApi::class)
private class AheadCacheWindowPrefetchStrategy(
    private val aheadCount: Int
) : LazyListPrefetchStrategy {

    // Ordered map of currently-scheduled ahead indices -> their prefetch handles.
    private val handles = linkedMapOf<Int, LazyLayoutPrefetchState.PrefetchHandle>()
    private var wasScrollingForward = true
    // Nearest ahead index we prefetch toward; -1 means nothing scheduled yet
    // (mirrors the default's indexToPrefetch = -1 sentinel).
    private var currentAnchor = -1

    // The interface declares these as member-extension functions
    // (fun LazyListPrefetchScope.onScroll(...)), so the prefetch scope is the
    // RECEIVER (`this`), not an explicit parameter. Schedule calls below resolve
    // on that receiver.
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val forward = delta < 0f
        val anchor = if (forward) visible.last().index + 1 else visible.first().index - 1
        if (anchor < 0 || anchor >= layoutInfo.totalItemsCount) {
            // At the very top/bottom — nothing valid to prefetch toward.
            if (handles.isNotEmpty()) cancelAndClear()
            currentAnchor = -1
            return
        }
        // Reschedule only when the nearest-ahead index or direction changed —
        // identical guard to the default, so steady mid-row scrolling does ZERO
        // scheduling work (the common fling case).
        if (anchor != currentAnchor || forward != wasScrollingForward) {
            reschedule(anchor, forward, layoutInfo.totalItemsCount)
        }
        // Urgency: run the NEAREST ahead row's composition this frame if the
        // scroll delta will reach it. Same test as the default; further-ahead
        // rows are never reached this frame and stay non-urgent.
        val nearest = handles[anchor]
        if (nearest != null) {
            val urgent = if (forward) {
                val lastVisible = visible.last()
                val distance = lastVisible.offset + lastVisible.size + layoutInfo.mainAxisItemSpacing - layoutInfo.viewportEndOffset
                distance.toFloat() < -delta
            } else {
                val firstVisible = visible.first()
                val distance = layoutInfo.viewportStartOffset - firstVisible.offset
                distance.toFloat() < delta
            }
            if (urgent) nearest.markAsUrgent()
        }
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        // Visible items changed without a scroll delta (Room emitted new/updated
        // chats while idle, or a programmatic scroll). Re-derive the ahead set
        // from current visible items + last direction so we prefetch toward the
        // right rows. Mirrors the default, generalised to the window.
        if (currentAnchor == -1) return
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val anchor = if (wasScrollingForward) visible.last().index + 1 else visible.first().index - 1
        if (anchor < 0 || anchor >= layoutInfo.totalItemsCount) {
            if (handles.isNotEmpty()) cancelAndClear()
            currentAnchor = -1
            return
        }
        reschedule(anchor, wasScrollingForward, layoutInfo.totalItemsCount)
        // No urgency here: there is no scroll delta at this moment.
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        // Only fires when THIS list is nested inside another lazy layout. Ours is
        // a top-level screen list (never nested), so this callback never runs in
        // practice — leaving it empty is a true no-op for us. We intentionally do
        // NOT call the default's [schedulePrefetch] here: that member is
        // @Deprecated("use schedulePrecomposition(index) instead") in 1.9.5, and
        // (per the user's steer away from the nested-prefetch knob) nested prefetch
        // is not what targets our chat-row composition. Sibling-ahead precomposition
        // — the actual lever — is driven by [LazyListPrefetchScope] in onScroll.
        return
    }

    // Member extension on the prefetch scope so it shares the receiver from the
    // overrides above; schedulePrefetch(...) resolves on that receiver.
    private fun LazyListPrefetchScope.reschedule(
        anchor: Int,
        forward: Boolean,
        totalItemsCount: Int
    ) {
        // Desired window is the contiguous run anchor, anchor+dir, ..., clamped
        // to bounds. Compute its inclusive min/max for O(1) membership testing
        // without allocating a set (Phase 5: no per-anchor-change heap churn).
        val dir = if (forward) 1 else -1
        val desiredMin: Int
        val desiredMax: Int
        if (forward) {
            desiredMin = anchor
            desiredMax = minOf(anchor + (aheadCount - 1) * dir, totalItemsCount - 1)
        } else {
            desiredMax = anchor
            desiredMin = maxOf(anchor + (aheadCount - 1) * dir, 0)
        }
        // Cancel handles that slid out of the window.
        val iterator = handles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < desiredMin || entry.key > desiredMax) {
                entry.value.cancel()
                iterator.remove()
            }
        }
        // Schedule handles for indices that entered the window.
        var idx = desiredMin
        while (idx <= desiredMax) {
            if (idx !in handles) {
                schedulePrefetch(idx)?.let { handle -> handles[idx] = handle }
            }
            idx++
        }
        currentAnchor = anchor
        wasScrollingForward = forward
    }

    private fun cancelAndClear() {
        for (handle in handles.values) handle.cancel()
        handles.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    title: String,
    chats: List<Chat>,
    groupSenderNamesByUserId: Map<String, String> = emptyMap(),
    statusRingStatesByUserId: Map<String, ChatStatusRingState> = emptyMap(),
    selectedChatIds: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    showDeleteConfirmation: Boolean = false,
    isInitialLoading: Boolean,
    currentUserId: String?,
    onNewChatClick: () -> Unit,
    onChatClick: (Chat) -> Unit,
    onChatLongClick: (Chat) -> Unit,
    onClearSelection: () -> Unit,
    onPinChats: () -> Unit,
    onDeleteChats: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onMuteChats: () -> Unit,
    onArchiveChats: () -> Unit,
    onAvatarClick: (Chat, Rect) -> Unit,
    modifier: Modifier = Modifier,
    isArchivedMode: Boolean = false,
    archivedChatsCount: Int = 0,
    hasUnreadArchivedMessages: Boolean = false,
    lockedChatsCount: Int = 0,
    hasUnreadLockedMessages: Boolean = false,
    onLockedChatsClick: () -> Unit = {},
    isLockedChatsHidden: Boolean = false,
    secretCodeMatch: Boolean = false,
    onSearchQueryChanged: (String) -> Unit = {},
    clearSearchTrigger: Int = 0,
    onUnarchiveChats: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onArchivedFolderClick: () -> Unit = {},
    // ── Undo delete ──
    pendingDeleteChatIds: Set<String> = emptySet(),
    pendingDeleteCount: Int = 0,
    showUndoBar: Boolean = false,
    undoProgress: Float = 0f,
    onUndoDelete: () -> Unit = {},
    blockedUserIds: Set<String> = emptySet(),
    useRecyclerView: Boolean = false
) {
    // FontFamily for BBH Bartle. Add the font file(s) under `app/src/main/res/font/`:
    // e.g. res/font/bbh_bartle_regular.ttf and reference as R.font.bbh_bartle_regular
    val bbhBartle = remember { FontFamily(Font(R.font.bbh_bartle_regular)) }
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Clear the search bar whenever the trigger increments (e.g. returning from locked chats)
    LaunchedEffect(clearSearchTrigger) {
        if (clearSearchTrigger > 0) searchQuery = ""
    }
    
    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete ${selectedChatIds.size} chats?") },
            text = { Text("Messages in these chats will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text("Cancel")
                }
            }
        )
    }

    val filteredChats by remember(chats, searchQuery, pendingDeleteChatIds, isArchivedMode) {
        derivedStateOf {
            val base = if (pendingDeleteChatIds.isNotEmpty()) {
                chats.filter { it.id !in pendingDeleteChatIds }
            } else {
                chats
            }
            // In the main list, exclude locked chats (they live in the Locked Chats section).
            // In archived mode, show all archived chats — even ones that are also locked.
            val visible = if (isArchivedMode) base else base.filter { !it.isLocked }
            if (searchQuery.isBlank()) {
                visible
            } else {
                val queryLower = searchQuery.lowercase(Locale.getDefault())
                visible.filter { chat ->
                    chatDisplayName(chat).lowercase(Locale.getDefault()).contains(queryLower)
                }
            }
        }
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val currentTheme = ThemeManager.getCurrentTheme(context)
    val surfaceBackgroundColor = glyphTheme.backgroundPrimary
    val showSecretLockedRow = isLockedChatsHidden && secretCodeMatch && searchQuery.isNotEmpty()
    val showLockedSection = showSecretLockedRow || (lockedChatsCount > 0 && !isLockedChatsHidden)
    val showArchivedSection = archivedChatsCount > 0
    // Prefetch strategy that precomposes 2 rows ahead of the scroll direction
    // (Compose's default precomposes 1). Widening the window is the Config C test:
    // does having more rows already-composed reduce on-fling composition, or does
    // the extra prefetch itself land on fling frames and make jank worse?
    // remember'd so its identity (and in-flight handle map) is stable across
    // recompositions; re-created fresh on process death, which is correct.
    val prefetchStrategy = remember { AheadCacheWindowPrefetchStrategy(aheadCount = 2) }
    val chatListState = rememberLazyListState(0, 0, prefetchStrategy)

    // Shared scroll-position signal consumed by the NestedScrollConnection below.
    // For the Compose LazyColumn: updated from chatListState via LaunchedEffect.
    // For the RecyclerView (AndroidView): updated from a RecyclerView.OnScrollListener.
    // This lets the same reveal/hide gesture work identically for both backends.
    val listAtTopState = remember { mutableStateOf(true) }

    // Observe LazyListState → listAtTop (Compose path only; no-op when useRecyclerView).
    if (!useRecyclerView) {
        LaunchedEffect(chatListState) {
            snapshotFlow {
                chatListState.firstVisibleItemIndex == 0 &&
                    chatListState.firstVisibleItemScrollOffset == 0
            }.distinctUntilChanged().collect { atTop ->
                listAtTopState.value = atTop
            }
        }
    }

    // Smallest useful scroll-aware signal: true only while the list is actively
    // scrolled/dragged/flung. derivedStateOf collapses the per-frame scroll-
    // position writes into a single idle↔active boolean, so continuous scrolling
    // triggers zero recomposition. Provided to the row tree via LocalListScrolling
    // so only the infinite-animation leaves (presence pulse, typing dots) subscribe.
    val scrollActive = remember(chatListState) {
        derivedStateOf { chatListState.isScrollInProgress }
    }
    val showHeaderSections = !isSelectionMode && !isArchivedMode && (showLockedSection || showArchivedSection)
    val hiddenSectionsRowCount = (if (showLockedSection) 1 else 0) + (if (showArchivedSection) 1 else 0)
    val hiddenSectionsHeight = (hiddenSectionsRowCount * 50).dp
    val hiddenSectionsHeightPx = with(density) { hiddenSectionsHeight.roundToPx().toFloat() }

    // ── Badge colors for the XML-based hidden sections overlay ────────────────
    // These mirror the color logic that was previously in the HiddenChatsSections
    // Composable, computed once per theme change so the AndroidView update callback
    // doesn't recompute on every recomposition.
    val unreadBadgeColor = context.resolveColor(R.attr.glyphUnreadBadge)
    val unreadBadgeTextColor = android.graphics.Color.BLACK  // matches indicatorUnreadText
    val neutralBadgeColor = when (currentTheme) {
        ThemeManager.THEME_DARK -> 0xFF374151.toInt()
        ThemeManager.THEME_LIGHT -> 0xFFE0E0E0.toInt()
        ThemeManager.THEME_PASTEL_SKY -> 0xB0B0C0CF.toInt()
        else -> android.graphics.Color.GRAY
    }
    val neutralBadgeTextColor = when (currentTheme) {
        ThemeManager.THEME_DARK -> 0xFF9CA3AF.toInt()
        ThemeManager.THEME_LIGHT -> 0xFF757575.toInt()
        else -> android.graphics.Color.WHITE
    }
    val lockedClick = rememberUpdatedState(onLockedChatsClick)
    val archivedClick = rememberUpdatedState(onArchivedFolderClick)

    var revealOffsetPx by remember { mutableFloatStateOf(0f) }
    var revealInteractionNonce by remember { mutableIntStateOf(0) }
    // Tracks whether the user was pushing up (closing the section) or pulling
    // down (opening it) during the last scroll gesture.  Used by onPreFling to
    // snap in the correct direction.
    var userIsClosingSection by remember { mutableStateOf(false) }
    // Rate-limit scroll debug logs: log at most once every ~200ms
    val lastScrollLogMs = remember { mutableStateOf(0L) }
    val revealConnection = remember(showHeaderSections, isArchivedMode, hiddenSectionsHeightPx, chatListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val canLog = System.currentTimeMillis() - lastScrollLogMs.value > 200L
                if (!showHeaderSections || isArchivedMode || hiddenSectionsHeightPx <= 0f) {
                    if (canLog) {
                        Log.d(TAG, "onPreScroll SKIP: showHdr=$showHeaderSections archMode=$isArchivedMode hPx=$hiddenSectionsHeightPx")
                        lastScrollLogMs.value = System.currentTimeMillis()
                    }
                    return Offset.Zero
                }

                val isPushingUp = available.y < 0f
                val isPullingDown = available.y > 0f
                val listAtTop = listAtTopState.value

                val result = when {
                    isPullingDown && listAtTop -> {
                        // 1:1 finger tracking — no dampening so the section
                        // reveals smoothly in a single gesture.
                        userIsClosingSection = false
                        val nextOffset = (revealOffsetPx + available.y).coerceIn(0f, hiddenSectionsHeightPx)
                        val consumedY = nextOffset - revealOffsetPx
                        if (consumedY != 0f) {
                            revealOffsetPx = nextOffset
                            revealInteractionNonce += 1
                        }
                        if (canLog) {
                            Log.d(TAG, "onPreScroll PULL-DOWN: avail=${available.y} offset=$revealOffsetPx→$nextOffset consumed=$consumedY nonce=$revealInteractionNonce")
                            lastScrollLogMs.value = System.currentTimeMillis()
                        }
                        Offset(0f, consumedY)
                    }

                    isPushingUp && revealOffsetPx > 0.5f -> {
                        userIsClosingSection = true
                        val nextOffset = (revealOffsetPx + available.y).coerceIn(0f, hiddenSectionsHeightPx)
                        val consumedY = nextOffset - revealOffsetPx
                        if (consumedY != 0f) {
                            revealOffsetPx = nextOffset
                            revealInteractionNonce += 1
                        }
                        // Snap to zero when very close to avoid tiny residuals
                        // that would consume scroll events on the next gesture.
                        if (revealOffsetPx < 1f) revealOffsetPx = 0f
                        if (canLog) {
                            Log.d(TAG, "onPreScroll PUSH-UP(BLOCKED): avail=${available.y} offset=${revealOffsetPx}→$nextOffset consumed=$consumedY nonce=$revealInteractionNonce listAtTop=$listAtTop")
                            lastScrollLogMs.value = System.currentTimeMillis()
                        }
                        Offset(0f, consumedY)
                    }

                    else -> {
                        if (canLog) {
                            val reason = when {
                                isPushingUp -> "offset=$revealOffsetPx ≤0.5f → PASS-THROUGH"
                                isPullingDown -> "!listAtTop (idx=${chatListState.firstVisibleItemIndex} off=${chatListState.firstVisibleItemScrollOffset}) → PASS-THROUGH"
                                else -> "idle → PASS-THROUGH"
                            }
                            Log.d(TAG, "onPreScroll $reason avail.y=${available.y}")
                            lastScrollLogMs.value = System.currentTimeMillis()
                        }
                        Offset.Zero
                    }
                }
                return result
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!showHeaderSections || isArchivedMode || hiddenSectionsHeightPx <= 0f) {
                    return Velocity.Zero
                }
                // Snap the reveal offset to fully open or fully closed when the
                // user lifts their finger after a partial reveal.
                if (revealOffsetPx > 0.5f && revealOffsetPx < hiddenSectionsHeightPx - 0.5f) {
                    // If the user was pushing up they want to close the section;
                    // always snap to 0 regardless of how far they pushed.
                    // If pulling down, snap open only if past the threshold.
                    val target = if (userIsClosingSection) {
                        0f
                    } else {
                        val threshold = hiddenSectionsHeightPx * 0.35f
                        if (revealOffsetPx > threshold) hiddenSectionsHeightPx else 0f
                    }
                    Log.d(TAG, "onPreFling: snap offset=$revealOffsetPx→$target (closing=$userIsClosingSection)")
                    animate(
                        initialValue = revealOffsetPx,
                        targetValue = target,
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { value, _ -> revealOffsetPx = value }
                    if (revealOffsetPx < 1f) revealOffsetPx = 0f
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(isArchivedMode, showHeaderSections) {
        // Reset hidden sections offset when entering/leaving archived mode
        // or when header sections visibility changes structurally.
        Log.d(TAG, "LaunchedEffect(RESET): archMode=$isArchivedMode showHdr=$showHeaderSections → revealOffsetPx=0")
        revealOffsetPx = 0f
    }

    // Auto-reveal hidden sections on cold start (once per process) and when
    // a chat is newly archived, then auto-hide after a short delay.
    var prevArchivedCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(archivedChatsCount) {
        val isColdStartReveal = !sColdStartRevealDone && !isArchivedMode && showHeaderSections &&
            archivedChatsCount > 0
        val isNewArchive = sColdStartRevealDone && !isArchivedMode && showHeaderSections &&
            archivedChatsCount > prevArchivedCount && prevArchivedCount > 0

        Log.d(TAG, "LaunchedEffect(AUTO): count=$archivedChatsCount prev=$prevArchivedCount " +
            "coldStart=$isColdStartReveal newArchive=$isNewArchive")

        if (isColdStartReveal || isNewArchive) {
            if (isColdStartReveal) {
                sColdStartRevealDone = true
                delay(1_500L) // brief pause before reveal so the user sees the list first
            }
            val interactionSnap = revealInteractionNonce
            Log.d(TAG, "LaunchedEffect(AUTO): TRIGGERED — revealing sections. interactionSnap=$interactionSnap startOffset=$revealOffsetPx target=$hiddenSectionsHeightPx")
            try {
                // Reveal the sections
                animate(
                    initialValue = revealOffsetPx,
                    targetValue = hiddenSectionsHeightPx,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) { value, _ -> revealOffsetPx = value }
                Log.d(TAG, "LaunchedEffect(AUTO): reveal animation done. offset=$revealOffsetPx. Waiting 2.5s…")

                // Keep visible briefly so the user can see the change
                delay(2_500L)
                Log.d(TAG, "LaunchedEffect(AUTO): delay complete. interactionNonce=${revealInteractionNonce} (snap=$interactionSnap)")

                // Auto-hide only if user hasn't manually interacted
                if (revealInteractionNonce == interactionSnap) {
                    Log.d(TAG, "LaunchedEffect(AUTO): auto-hiding — animating offset to 0")
                    animate(
                        initialValue = revealOffsetPx,
                        targetValue = 0f,
                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                    ) { value, _ -> revealOffsetPx = value }
                    Log.d(TAG, "LaunchedEffect(AUTO): auto-hide animation done. offset=$revealOffsetPx")
                } else {
                    Log.d(TAG, "LaunchedEffect(AUTO): user interacted — skipping auto-hide")
                }
            } finally {
                // Always snap revealOffsetPx to 0 when this effect finishes
                // or is cancelled, unless the user has manually interacted.
                if (revealInteractionNonce == interactionSnap) {
                    Log.d(TAG, "LaunchedEffect(AUTO): finally — snapping revealOffsetPx=$revealOffsetPx→0")
                    revealOffsetPx = 0f
                } else {
                    Log.d(TAG, "LaunchedEffect(AUTO): finally — user interacted, NOT snapping (nonce=${revealInteractionNonce} vs snap=$interactionSnap)")
                }
            }
        } else {
            Log.d(TAG, "LaunchedEffect(AUTO): SKIP — condition not met")
        }
        prevArchivedCount = archivedChatsCount
    }

    // Reset revealOffsetPx when the screen restarts (e.g., returning from
    // ArchivedChatsActivity) so that any residual offset from a previous
    // manual pull-down doesn't cause the NestedScrollConnection to consume
    // scroll events intended for the LazyColumn.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                Log.d(TAG, "Lifecycle ON_START → reset revealOffsetPx (was $revealOffsetPx)")
                revealOffsetPx = 0f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Determine Status Bar color based on selection mode
    // Note: Since we are in edge-to-edge, we might need a way to set status bar color if not handled by activity
    
    val selectionBackgroundColor = if (currentTheme == ThemeManager.THEME_DARK) {
         Color(0xFF1F2C34) // WhatsApp Dark Selection
    } else {
         Color(0xFFE9EDEF) // WhatsApp Light Selection
    }
    
    // Bottom Bar for Encryption Footer on Archived Screen
    val bottomBarContent: @Composable () -> Unit = {
        if (isArchivedMode) {
            Box(
                 modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (glyphTheme.gradientPrimary != null) Color.Transparent else surfaceBackgroundColor
                    )
                    .navigationBarsPadding()
            ) {
                 EncryptionFooter()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (glyphTheme.gradientPrimary != null) {
            Color.Transparent  
        } else {
            surfaceBackgroundColor  
        },
        bottomBar = bottomBarContent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceBackgroundColor)
            ) {
                if (isSelectionMode) {
                     SelectionTopAppBar(
                         selectionCount = selectedChatIds.size,
                         onBackClick = onClearSelection,
                         onDeleteClick = onDeleteChats,
                         onMuteClick = onMuteChats,
                         onArchiveClick = {
                             haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                             if (isArchivedMode) onUnarchiveChats() else onArchiveChats()
                         },
                         containerColor = surfaceBackgroundColor,
                         contentColor = glyphTheme.textPrimary,
                         iconColor = glyphTheme.iconPrimary,
                         isArchivedMode = isArchivedMode
                     )
                } else if (isArchivedMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Archived",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = glyphTheme.textPrimary
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_back),
                                    contentDescription = "Back",
                                    tint = glyphTheme.iconPrimary
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* More options */ }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_more_vert),
                                    contentDescription = "More",
                                    tint = glyphTheme.iconPrimary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = surfaceBackgroundColor,
                            titleContentColor = glyphTheme.textPrimary,
                            actionIconContentColor = glyphTheme.iconPrimary
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = bbhBartle,
                                color = glyphTheme.textPrimary
                            )
                        },
                        actions = {
                            IconButton(onClick = { /* visual-only */ }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_camera_glyph),
                                    contentDescription = "Camera",
                                    tint = glyphTheme.iconPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { /* visual-only */ }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_search),
                                    contentDescription = "Search",
                                    tint = glyphTheme.iconPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { /* visual-only */ }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_more_vert),
                                    contentDescription = "More",
                                    tint = glyphTheme.iconPrimary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = surfaceBackgroundColor,
                            titleContentColor = glyphTheme.textPrimary,
                            actionIconContentColor = glyphTheme.iconPrimary
                        ),
                        modifier = Modifier
                    )
                }

                // Search bar is always rendered (outside the mode conditional)
                // to maintain consistent layout height and prevent content shift
                // when entering selection or archived mode.
                ChatListSearchBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = {
                        searchQuery = it
                        onSearchQueryChanged(it)
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode && !isArchivedMode) {
                FloatingActionButton(
                    onClick = onNewChatClick,
                    containerColor = glyphTheme.actionPrimary,
                    contentColor = glyphTheme.textInverse
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_new_chat),
                        contentDescription = "New Chat"
                    )
                }
            }
        },

        floatingActionButtonPosition = FabPosition.End
    ) { contentPadding ->
        CompositionLocalProvider(LocalListScrolling provides scrollActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (useRecyclerView) Modifier else Modifier.nestedScroll(revealConnection))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                // Only clip for the Compose LazyColumn path. For the RecyclerView
                // (AndroidView), the native EdgeEffect stretch extends beyond the
                // RecyclerView bounds during overscroll; clipToBounds() on this
                // outer Box would clip that stretch, making the last visible row
                // appear to "disappear" when the user overscrolls at the bottom.
                // The hidden-sections overlay has its own clipToBounds(), and the
                // undo snackbar stays within bounds, so removing it here is safe.
                .then(if (useRecyclerView) Modifier else Modifier.clipToBounds())
                .then(
                    if (glyphTheme.gradientPrimary != null) {
                        Modifier.background(glyphTheme.gradientPrimary!!)
                    } else {
                        Modifier.background(surfaceBackgroundColor)
                    }
                )
        ) {
            // Direct conditional rendering — no Crossfade animation so the entire
            // screen (top bar, search bar, content) appears at once on open.
            val listPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + if (isArchivedMode) 16.dp else 0.dp
            )
            // Extra bottom buffer for the RecyclerView path: the EdgeEffect stretch
            // during overscroll can cause the last row to be clipped if it sits at
            // the very edge of the content. This buffer mirrors LazyColumn's
            // contentPadding — an empty zone at the bottom of the scroll content
            // that absorbs the overscroll stretch so all visible rows stay stable.
            //
            // The 24dp buffer is implemented as RecyclerView.setPadding (with
            // clipToPadding=true) rather than as extra parent-Box padding. To keep
            // the total bottom space identical to the LazyColumn path, the parent
            // Box's bottom padding is reduced by 24dp via recyclerViewListPadding.
            val recyclerViewBottomBufferPx = with(density) { 24.dp.roundToPx() }
            // Parent-Box padding for the RecyclerView path — same as listPadding
            // but with the 24dp buffer subtracted (it lives in RecyclerView padding instead).
            val recyclerViewListPadding = PaddingValues(
                top = listPadding.calculateTopPadding(),
                bottom = (listPadding.calculateBottomPadding() - 24.dp).coerceAtLeast(0.dp)
            )

            // ─── RecyclerView (AndroidView) Integration ─────────────────────────
            // When useRecyclerView is true, the LazyColumn is replaced by a RecyclerView
            // embedded via AndroidView. The surrounding Compose UI (top bar, search bar,
            // FAB, hidden sections, undo snackbar) remains Compose. Legacy ChatListFragment
            // and ChatListAdapter are untouched.
            val scrollSuspensionCoordinator = remember { ScrollSuspensionCoordinator() }

            // Track isSelectionMode without re-creating the lambda on every recomposition.
            val isSelectionModeState = rememberUpdatedState(isSelectionMode)

            // AI agent click handler: checks isSelectionMode dynamically at click time.
            val aiAgentClickCallback = remember(onChatClick, currentUserId) {
                {
                    if (!isSelectionModeState.value) {
                        val aiChat = Chat(
                            id = AiAgentConstants.AI_AGENT_CHAT_ID,
                            participants = listOf(currentUserId ?: "", AiAgentConstants.AI_AGENT_USER_ID),
                            otherUsername = AiAgentConstants.AI_AGENT_USERNAME
                        )
                        onChatClick(aiChat)
                    }
                }
            }

            // selectionBackgroundColor as Compose Color has a .toArgb() extension;
            // the adapter expects a plain Int ARGB.
            val selectionBgInt = selectionBackgroundColor.toArgb()

            // Wrap callbacks in rememberUpdatedState so the adapter doesn't get
            // re-created when the parent Composable passes new lambda instances.
            val onClickState = rememberUpdatedState(onChatClick)
            val onLongClickState = rememberUpdatedState(onChatLongClick)
            val onAvatarClickState = rememberUpdatedState(onAvatarClick)

            // Avatar-download version trigger — incremented when any avatar state changes
            // (download completes, unblock resolves, etc.). Adding it as a key to the
            // `items` remember below ensures buildChatListItems re-runs with fresh
            // AvatarStateManager.peek() values, so the update callback's submitListSync
            // always has the latest avatar state. This eliminates the ping-pong between
            // the LaunchedEffect (which previously called submitListSync directly with
            // updated avatar versions) and the AndroidView.update callback (which would
            // then re-submit the stale items list from remember, reverting the avatar
            // state — visible as a subtle flash on cold start when avatars download).
            var avatarStateTrigger by remember { mutableStateOf(0) }

            // Build the list items once per data change (mirrors LazyColumn's items()).
            // Memoized so the AndroidView.update callback can reference a stable list
            // and only calls submitList when the actual data changes (reference check).
            // This must come BEFORE recyclerViewAdapter so the pre-population below
            // has the initial list available during adapter creation.
            val items = remember(
                filteredChats,
                avatarStateTrigger,
                selectedChatIds,
                isSelectionMode,
                isInitialLoading,
                isArchivedMode,
                groupSenderNamesByUserId,
                statusRingStatesByUserId,
                blockedUserIds,
                currentUserId
            ) {
                buildChatListItems(
                    filteredChats = filteredChats,
                    groupSenderNamesByUserId = groupSenderNamesByUserId,
                    statusRingStatesByUserId = statusRingStatesByUserId,
                    selectedChatIds = selectedChatIds,
                    isSelectionMode = isSelectionMode,
                    isInitialLoading = isInitialLoading,
                    isArchivedMode = isArchivedMode,
                    currentUserId = currentUserId,
                    blockedUserIds = blockedUserIds
                )
            }

            val recyclerViewAdapter = remember(
                useRecyclerView, currentUserId, groupSenderNamesByUserId, blockedUserIds, selectionBgInt
            ) {
                if (useRecyclerView) {
                    val adapter = ChatListScreenAdapter(
                        currentUserId = currentUserId,
                        groupSenderNamesByUserId = groupSenderNamesByUserId,
                        blockedUserIds = blockedUserIds,
                        onClick = { chat -> onClickState.value(chat) },
                        onLongClick = { chat -> onLongClickState.value(chat) },
                        onAvatarClick = { chat, rect -> onAvatarClickState.value(chat, rect) },
                        onAiAgentClick = aiAgentClickCallback,
                        selectionBackgroundColor = selectionBgInt,
                        scrollSuspensionCoordinator = scrollSuspensionCoordinator
                    )
                    // Pre-submit the initial list during creation so the RecyclerView
                    // is never shown empty on the first layout pass. This is critical
                    // for eliminating the "Glyph Official" delay: the AsyncListDiffer
                    // diff for an empty→populated transition completes before the
                    // first frame is drawn, making the initial list appear instantly.
                    adapter.submitListSync(items)
                    adapter
                } else {
                    null
                }
            }

            // ── Observe avatar state changes (downloads, unblocks) ──────────────
            // Compose ChatRow uses AvatarStateManager.observe() + collectAsState() to react
            // to avatar downloads. For the AndroidView approach, we observe avatar state
            // via combine() and trigger recomposition when any avatar version changes.
            //
            // The recomposition causes `items` (remembered above with avatarStateTrigger
            // as a key) to recompute via buildChatListItems — which reads fresh
            // AvatarStateManager.peek() values — and the AndroidView.update callback
            // then calls submitListSync with the updated list.
            //
            // The first emission from combine() is SKIPPED because it is redundant with
            // the update callback's initial submitListSync, which already ran during
            // adapter creation with the current avatar states. Without this skip, the
            // first combine() emission would trigger a recomposition and a second
            // submitListSync — a no-op diff but wasted work. Only genuine avatar-download
            // state changes (after the initial emission) trigger the trigger increment.
            val avatarSubscriptionKey = filteredChats.map { it.id } to currentUserId to blockedUserIds
            LaunchedEffect(avatarSubscriptionKey) {
                if (recyclerViewAdapter == null) return@LaunchedEffect
                val avatarFlows = filteredChats.map { chat ->
                    val otherUserId = resolveOtherUserId(chat, currentUserId)
                    val avatarUrl = chatDisplayAvatarUrl(chat)
                    val cacheId = if (chat.isGroup) {
                        AvatarCacheManager.groupIconCacheIdPublic(chat.id)
                    } else if (otherUserId.isNotEmpty()) {
                        otherUserId
                    } else {
                        ""
                    }
                    val isBlocked = !chat.isGroup && otherUserId.isNotEmpty() &&
                        otherUserId in blockedUserIds
                    val canShowAvatar = if (chat.isGroup) true else !isBlocked
                    if (cacheId.isNotEmpty() && canShowAvatar) {
                        AvatarStateManager.observe(cacheId, avatarUrl)
                    } else {
                        flowOf(
                            AvatarStateManager.AvatarState(
                                localPath = null,
                                remoteUrl = avatarUrl,
                                isDownloaded = false,
                                version = 0L
                            )
                        )
                    }
                }
                if (avatarFlows.isNotEmpty()) {
                    var isFirstEmission = true
                    combine(avatarFlows) { _ ->
                        if (isFirstEmission) {
                            isFirstEmission = false
                        } else {
                            // Trigger recomposition so the `items` remember recomputes
                            // via buildChatListItems (which reads fresh
                            // AvatarStateManager.peek() values). The AndroidView.update
                            // callback then calls submitListSync with the updated list.
                            // This consolidates all submitListSync calls into the update
                            // callback, preventing the dual-call ping-pong that caused
                            // user-row flashing on cold start.
                            avatarStateTrigger++
                        }
                    }.collect { }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (useRecyclerView) Modifier.padding(recyclerViewListPadding) else Modifier)
                    .graphicsLayer { translationY = revealOffsetPx }
            ) {
                if (useRecyclerView) {
                    // ═══════════════════════════════════════════════════════════
                    // RecyclerView path (AndroidView) — replaces LazyColumn
                    // ═══════════════════════════════════════════════════════════
                    val rvAdapter = recyclerViewAdapter
                    // Track the last submitted list reference to skip redundant
                    // submitListSync calls on every recomposition. submitListSync
                    // does a reference check internally, but we also guard here
                    // so ChatListPerfMonitor only logs genuine updates.
                    val prevItemsRef = remember { object { var value: List<ChatListScreenItem>? = null } }
                    AndroidView(
                        factory = { context ->
                            RecyclerView(context).apply {
                                layoutManager = LinearLayoutManager(context)
                                adapter = rvAdapter
                                setItemViewCacheSize(20)
                                setHasFixedSize(true)
                                // Disable item animations. The DefaultItemAnimator runs fade-in
                                // (on insert) and fade-out+fine-in (on change) animations that
                                // overlay the row content during the ~300ms animation. On cold
                                // start, presence/status updates cause notifyItemChanged on all
                                // visible rows, and the simultaneous fade animations make every
                                // row "dim and instantly become normal" — perceived as a full
                                // reload. Since we use submitListSync (synchronous DiffUtil on
                                // the main thread), there is zero one-frame delay to justify
                                // animations; rows should update in place without any transition.
                                itemAnimator = null
                                // Parent Box uses recyclerViewListPadding (listPadding minus 24dp
                                // bottom) to position the RecyclerView. The 24dp difference is
                                // re-absorbed here as content padding via setPadding below — this
                                // keeps the total bottom space identical to the LazyColumn path
                                // while creating an empty buffer zone that absorbs the EdgeEffect
                                // overscroll stretch so the last row never gets clipped or
                                // disappears. Mirrors LazyColumn's contentPadding.
                                setPadding(0, 0, 0, recyclerViewBottomBufferPx)
                                clipToPadding = true
                                // Mirror Compose's LocalListScrolling for infinite animations.
                                scrollSuspensionCoordinator.attach(this)
                                // Mirror chatListState.firstVisibleItemIndex/ScrollOffset for
                                // the NestedScrollConnection's reveal/hide gesture.
                                val listAtTopListener = object : RecyclerView.OnScrollListener() {
                                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                        // computeVerticalScrollOffset() is O(1) — reads the
                                        // LinearLayoutManager's cached mScrollOffset field directly.
                                        // Equivalent to !canScrollVertically(-1) but avoids a
                                        // view-lookup layout traversal on every scroll frame.
                                        val isAtTop = recyclerView.computeVerticalScrollOffset() == 0
                                        if (isAtTop != listAtTopState.value) {
                                            listAtTopState.value = isAtTop
                                        }
                                    }

                                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                            val isAtTop = recyclerView.computeVerticalScrollOffset() == 0
                                            if (isAtTop != listAtTopState.value) {
                                                listAtTopState.value = isAtTop
                                            }
                                        }
                                    }
                                }
                                addOnScrollListener(listAtTopListener)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(revealConnection),
                        // The update callback runs synchronously during recomposition
                        // commit (before layout), which is faster than LaunchedEffect
                        // (which dispatches to a coroutine after the frame). This
                        // eliminates the one-or-two-frame delay that caused the
                        // "Glyph Official" chat to appear late.
                        //
                        // submitListSync calculates DiffUtil on the main thread and
                        // dispatches results synchronously — matching Compose's
                        // LazyColumn behaviour with zero extra frames. AsyncListDiffer
                        // would post to the main thread (one frame delay).
                        // The `items` variable is memoized via remember() above, so
                        // it only changes reference when actual data changes.
                        update = { recyclerView ->
                            val adapter = rvAdapter
                            if (adapter != null && recyclerView.adapter !== adapter) {
                                recyclerView.adapter = adapter
                                prevItemsRef.value = null
                            }
                            if (adapter != null && items !== prevItemsRef.value) {
                                prevItemsRef.value = items
                                adapter.submitListSync(items)
                                ChatListPerfMonitor.onSubmitList(submitted = true)
                            }
                        },
                        onRelease = { _ ->
                            // Clean up scroll listener when the RecyclerView is destroyed.
                            scrollSuspensionCoordinator.detach()
                        }
                    )
                } else {
                    // ═══════════════════════════════════════════════════════════
                    // Compose LazyColumn path — kept for verification
                    // ═══════════════════════════════════════════════════════════
                    if (isInitialLoading) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding
                        ) {
                            if (isArchivedMode) {
                                item(key = "header") { ArchivedInfoBanner() }
                            }
                            items(8, key = { "placeholder_$it" }, contentType = { "placeholder" }) {
                                ChatRowPlaceholder()
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = chatListState,
                            contentPadding = listPadding
                        ) {
                            if (isArchivedMode) {
                                item(key = "header") { ArchivedInfoBanner() }
                            }
                            // ── Pinned Glyph AI entry ────────────────────
                            // Always rendered (even in selection mode) to prevent list shifting.
                            // Click is a no-op during selection mode since Glyph AI cannot be selected.
                            if (!isArchivedMode) {
                                item(key = AiAgentConstants.AI_AGENT_CHAT_ID, contentType = "ai_agent") {
                                    AiAgentRow(
                                        onClick = {
                                            if (!isSelectionMode) {
                                                val aiChat = Chat(
                                                    id = AiAgentConstants.AI_AGENT_CHAT_ID,
                                                    participants = listOf(currentUserId ?: "", AiAgentConstants.AI_AGENT_USER_ID),
                                                    otherUsername = AiAgentConstants.AI_AGENT_USERNAME
                                                )
                                                onChatClick(aiChat)
                                            }
                                        }
                                    )
                                }
                            }

                            items(filteredChats, key = { it.id }, contentType = { "chat" }) { chat ->
                                val isSelected = selectedChatIds.contains(chat.id)
                                val otherUserId = remember(chat.participants, currentUserId) {
                                    resolveOtherUserId(chat, currentUserId)
                                }
                                ChatRow(
                                    chat = chat,
                                    currentUserId = currentUserId,
                                    groupSenderNamesByUserId = groupSenderNamesByUserId,
                                    statusRingState = statusRingStatesByUserId[otherUserId] ?: ChatStatusRingState.NONE,
                                    isSelected = isSelected,
                                    isInSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onChatLongClick(chat)
                                        } else {
                                            ChatOpenTrace.start(
                                                chatId = chat.id,
                                                source = "chat_list_screen_tap",
                                                details = "unread=${chat.unreadCount} archived=$isArchivedMode locked=${chat.isLocked}"
                                            )
                                            onChatClick(chat)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onChatLongClick(chat)
                                    },
                                    onAvatarClick = { bounds ->
                                        if (isSelectionMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onChatLongClick(chat)
                                        } else {
                                            onAvatarClick(chat, bounds)
                                        }
                                    },
                                    selectionBackgroundColor = selectionBackgroundColor,
                                    blockedUserIds = blockedUserIds
                                )
                            }

                            if (filteredChats.isEmpty()) {
                                item(key = "empty_state") { EmptyChatListState() }
                            }
                        }
                    }
                }
            }

            if (showHeaderSections && hiddenSectionsHeightPx > 0f) {
                AndroidView(
                    factory = { ctx ->
                        val view = LayoutInflater.from(ctx)
                            .inflate(R.layout.item_chat_list_hidden_sections, null) as LinearLayout
                        view.findViewById<LinearLayout>(R.id.lockedChatsRow).setOnClickListener {
                            lockedClick.value()
                        }
                        view.findViewById<LinearLayout>(R.id.archivedRow).setOnClickListener {
                            archivedClick.value()
                        }
                        view
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = contentPadding.calculateTopPadding())
                        .height(hiddenSectionsHeight)
                        .clipToBounds()
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = revealOffsetPx - hiddenSectionsHeightPx
                        },
                    update = { container ->
                        val lockedRow = container.findViewById<LinearLayout>(R.id.lockedChatsRow)
                        val archivedRow = container.findViewById<LinearLayout>(R.id.archivedRow)
                        val lockedBadge = container.findViewById<TextView>(R.id.tvLockedBadge)
                        val archivedBadge = container.findViewById<TextView>(R.id.tvArchiveBadge)

                        // Locked row: visible when locked chats count > 0 (normal)
                        // or when the secret-code search reveals the hidden row.
                        val showLockedRow = showLockedSection
                        lockedRow.visibility = if (showLockedRow) View.VISIBLE else View.GONE
                        lockedBadge.visibility = if (lockedChatsCount > 0) View.VISIBLE else View.GONE
                        if (lockedChatsCount > 0) {
                            lockedBadge.text = if (lockedChatsCount > 99) "99+" else lockedChatsCount.toString()
                        }

                        // Archived row: always visible when there are archived chats.
                        archivedRow.visibility = if (showArchivedSection) View.VISIBLE else View.GONE
                        if (archivedChatsCount > 0) {
                            archivedBadge.visibility = View.VISIBLE
                            archivedBadge.text = if (archivedChatsCount > 99) "99+" else archivedChatsCount.toString()
                        }

                        // Badge colors — unread uses the theme green badge;
                        // neutral uses a per-theme gray.
                        val lockedUnreadBg = if (hasUnreadLockedMessages) unreadBadgeColor else neutralBadgeColor
                        val lockedUnreadText = if (hasUnreadLockedMessages) unreadBadgeTextColor else neutralBadgeTextColor
                        lockedBadge.setBackgroundResource(R.drawable.bg_neutral_badge)
                        lockedBadge.setBackgroundColor(lockedUnreadBg)
                        lockedBadge.setTextColor(lockedUnreadText)

                        val archivedUnreadBg = if (hasUnreadArchivedMessages) unreadBadgeColor else neutralBadgeColor
                        val archivedUnreadText = if (hasUnreadArchivedMessages) unreadBadgeTextColor else neutralBadgeTextColor
                        archivedBadge.setBackgroundResource(R.drawable.bg_neutral_badge)
                        archivedBadge.setBackgroundColor(archivedUnreadBg)
                        archivedBadge.setTextColor(archivedUnreadText)
                    }
                )
            }

            // ── Undo Delete Snackbar overlay ──
            AnimatedVisibility(
                visible = showUndoBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (isArchivedMode) 60.dp else 96.dp)
            ) {
                UndoDeleteSnackbar(
                    deletedCount = pendingDeleteCount,
                    progress = undoProgress,
                    onUndo = onUndoDelete
                )
            }
        }
        }
    }
}

// ─── Undo Delete Snackbar ───────────────────────────────────────────────────

/**
 * WhatsApp-style undo bar shown after chat deletion.
 * Displays a message, a countdown progress bar, and an "UNDO" button.
 */
@Composable
private fun UndoDeleteSnackbar(
    deletedCount: Int,
    progress: Float,
    onUndo: () -> Unit
) {
    val context = LocalContext.current
    val currentTheme = ThemeManager.getCurrentTheme(context)

    val barBackground = when (currentTheme) {
        ThemeManager.THEME_DARK -> Color(0xFF2A2F32)
        ThemeManager.THEME_PASTEL_SKY -> Color(0xFF3D3255)
        else -> Color(0xFF323232)
    }
    val barTextColor = Color.White
    val undoColor = when (currentTheme) {
        ThemeManager.THEME_PASTEL_SKY -> Color(0xFFC8AAFF)
        else -> Color(0xFF83D8AE)
    }
    val progressTrackColor = Color.White.copy(alpha = 0.15f)
    val progressIndicatorColor = undoColor

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = barBackground)
    ) {
        Column {
            // Progress bar at the very top of the card
            LinearProgressIndicator(
                progress = { 1f - progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = progressIndicatorColor,
                trackColor = progressTrackColor,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val label = if (deletedCount == 1) "Chat deleted" else "$deletedCount chats deleted"
                Text(
                    text = label,
                    color = barTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onUndo) {
                    Text(
                        text = "UNDO",
                        color = undoColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Pinned "Glyph AI" entry at the top of the chat list.
 * Styled like a regular ChatRow but with a distinctive AI avatar and sparkle accent.
 */
@Composable
private fun AiAgentRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val currentTheme = ThemeManager.getCurrentTheme(context)

    val aiAccentColor = when (currentTheme) {
        ThemeManager.THEME_DARK -> Color(0xFF9E7CFF)
        ThemeManager.THEME_PASTEL_SKY -> Color(0xFF9B7EDE)
        else -> Color(0xFF7C4DFF)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // AI avatar — 54dp outer wrapper matches ChatRow's status-ring container
        // so the circular avatar aligns horizontally with all other chat avatars
        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_ai_agent),
                contentDescription = "Glyph AI",
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AiAgentConstants.AI_AGENT_USERNAME,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = glyphTheme.textPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AiAgentConstants.AI_AGENT_LAST_MESSAGE,
                fontSize = 14.sp,
                color = glyphTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Sparkle badge
        Icon(
            painter = painterResource(id = R.drawable.ic_sparkles),
            contentDescription = null,
            tint = aiAccentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ChatRowPlaceholder() {
    val shimmer = rememberInfiniteTransition(label = "PlaceholderShimmer")
    val shimmerFraction by shimmer.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShimmerFraction"
    )
    val baseAlpha = if (glyphTheme.hasGradients) 0.13f else 0.07f
    val lineAlpha = if (glyphTheme.hasGradients) 0.094f else 0.059f
    val shimmerBase = (if (glyphTheme.hasGradients) Color.White else Color.Black)
        .copy(alpha = baseAlpha * shimmerFraction)
    val shimmerLine = (if (glyphTheme.hasGradients) Color.White else Color.Black)
        .copy(alpha = lineAlpha * shimmerFraction)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(shimmerBase)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .fillMaxWidth(0.6f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBase)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerLine)
            )
        }
    }
}

@Composable
private fun EmptyChatListState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No chats yet",
            fontSize = 16.sp,
            color = glyphTheme.textSecondary,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Start a new chat to see it here",
            fontSize = 14.sp,
            color = glyphTheme.textSecondary
        )
    }
}


@Composable
private fun ChatListSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val context = LocalContext.current
    val currentTheme = ThemeManager.getCurrentTheme(context)
    val searchBarColor = if (currentTheme == ThemeManager.THEME_DARK) {
        Color(0xFF23282C)
    } else {
        glyphTheme.surfaceInput
    }

    val searchIconColor = when (currentTheme) {
        ThemeManager.THEME_LIGHT -> Color(0xFF9E9E9E)
        ThemeManager.THEME_PASTEL_SKY -> glyphTheme.textSecondary
        else -> Color(0xFF8D9598)
    }
    val searchPlaceholderColor = searchIconColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 0.dp)
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 0.dp, bottom = 4.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = searchBarColor),
            border = androidx.compose.foundation.BorderStroke(
                width = 0.5.dp,
                color = glyphTheme.borderInput
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = searchIconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = glyphTheme.textPrimary,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(glyphTheme.cursorColor),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Ask Glyph AI or Search",
                                        color = searchPlaceholderColor,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: Chat,
    currentUserId: String?,
    groupSenderNamesByUserId: Map<String, String>,
    statusRingState: ChatStatusRingState = ChatStatusRingState.NONE,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAvatarClick: (Rect) -> Unit,
    selectionBackgroundColor: Color = Color.Transparent,
    blockedUserIds: Set<String> = emptySet()
) {
    val displayName = remember(chat.groupName, chat.otherUsername, chat.isGroup, chat.participants, currentUserId) {
        chatDisplayName(chat, currentUserId)
    }
    // Read the latest draft once per chat-id binding. DraftMessageStore keeps
    // drafts in an in-memory map (loaded at init), so getDraft is an O(1)
    // HashMap read — no IO dispatcher hop needed. The previous LaunchedEffect +
    // withContext(Dispatchers.IO) launched a coroutine for *every* row that
    // scrolled into view and then recomposed it a second time once the draft
    // resolved, roughly doubling per-row composition work during fling. Keying
    // on chat.id gives the same re-read semantics (a recycled slot bound to a
    // new chat re-reads) with a single synchronous composition per appearance.
    val draft = remember(chat.id) {
        com.glyph.glyph_v3.data.service.DraftMessageStore.getDraft(chat.id)
    }
    // Avatar tap-target bounds, written by onGloballyPositioned on every layout
    // pass and only read when the user taps the avatar. Held in plain (non-State)
    // fields so the writes don't trigger recomposition — critical during scroll,
    // where each visible row's window position changes every frame. The old code
    // stored these in mutableStateOf and recomposed every visible row per frame.
    val avatarBounds = remember { AvatarBounds() }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) selectionBackgroundColor else Color.Transparent)
            .combinedClickable(
                onClick = { currentOnClick() },
                onLongClick = { currentOnLongClick() }
            )
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInWindow()
                        val size = coordinates.size
                        avatarBounds.x = topLeft.x
                        avatarBounds.y = topLeft.y
                        avatarBounds.width = size.width
                        avatarBounds.height = size.height
                    }
                    .clickable {
                        if (avatarBounds.width > 0 && avatarBounds.height > 0) {
                            val avatarBoundsInWindow = Rect(
                                avatarBounds.x.roundToInt(),
                                avatarBounds.y.roundToInt(),
                                (avatarBounds.x + avatarBounds.width).roundToInt(),
                                (avatarBounds.y + avatarBounds.height).roundToInt()
                            )
                            onAvatarClick(avatarBoundsInWindow)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Avatar(
                    chat = chat,
                    currentUserId = currentUserId,
                    statusRingState = statusRingState,
                    isOnline = chat.isOtherUserOnline,
                    isInChat = chat.isOtherUserInChat,
                    isSelected = isSelected,
                    blockedUserIds = blockedUserIds
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = glyphTheme.textPrimary
                    )

                    val timestamp = chat.lastMessageTimestamp?.let { formatTimestampWhatsApp(it) }.orEmpty()
                    if (timestamp.isNotEmpty()) {
                        Text(
                            text = timestamp,
                            fontSize = 12.sp,
                            color = if (chat.unreadCount > 0) {
                                glyphTheme.indicatorUnreadBackground
                            } else {
                                glyphTheme.textSecondary
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val draftText = draft.trim()
                    val hasDraft = draftText.isNotEmpty()

                    if (chat.isOtherUserTyping) {
                        // Show typing indicator wrapped in weight(1f) to keep UnreadBadge at the end
                        Box(modifier = Modifier.weight(1f)) {
                            TypingIndicator(label = chat.typingText)
                        }
                    } else if (hasDraft) {
                        // MEMOIZE: buildAnnotatedString allocates a new AnnotatedString object
                        // on every recomposition. Wrapping in remember(chat.id) prevents
                        // unnecessary re-creation when the chat identity hasn't changed.
                        val draftTextMemo = remember(chat.id) {
                            draft.trim()
                        }
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) {
                                    append("Draft: ")
                                }
                                append(draftTextMemo)
                            },
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = glyphTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        val isOwnMessage = chat.lastMessageSenderId == currentUserId
                        val isOwnReactionSummary = isOwnMessage && chat.lastMessage.startsWith("You reacted ")
                        if (isOwnMessage && chat.lastMessage.isNotEmpty() && !isOwnReactionSummary) {
                            // Show status icon based on message status
                            val statusIconRes = when (chat.lastMessageStatus) {
                                "SENDING" -> R.drawable.ic_clock
                                "SENT" -> R.drawable.ic_check
                                "DELIVERED" -> R.drawable.ic_double_check
                                "READ" -> R.drawable.ic_double_check_blue
                                "FAILED" -> R.drawable.ic_error_outline
                                else -> R.drawable.ic_check // Default to single check
                            }
                            // Replaced hardcoded color with theme's tertiary (accent) color
                            val statusTint = if (chat.lastMessageStatus == "READ") {
                                // Match ChatScreen: use the message-status indicator color from theme
                                glyphTheme.indicatorMessageStatus
                            } else {
                                glyphTheme.textSecondary
                            }

                            Icon(
                                painter = painterResource(id = statusIconRes),
                                contentDescription = "Message status: ${chat.lastMessageStatus}",
                                tint = statusTint,
                                modifier = Modifier
                                    .size(19.dp)
                                    .padding(end = 4.dp)
                            )
                        }

                        // MEMOIZE: buildChatListSubtitle is pure text computation; wrapping
                        // in remember(chat.id, chat.lastMessage) prevents per-frame re-allocation
 // of the returned String and the map lookup into groupSenderNamesByUserId.
                        val subtitle = remember(chat.id, chat.lastMessage) {
                            buildChatListSubtitle(chat, currentUserId, groupSenderNamesByUserId)
                        }
                        Text(
                            text = subtitle,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = glyphTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadBadge(count = chat.unreadCount)
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }

    }
}

@Composable
private fun Avatar(
    chat: Chat,
    currentUserId: String?,
    statusRingState: ChatStatusRingState,
    isOnline: Boolean,
    isInChat: Boolean,
    isSelected: Boolean = false,
    blockedUserIds: Set<String> = emptySet()
) {
    val context = LocalContext.current
    val isGroupChat = chat.isGroup
    val avatarUrl = chatDisplayAvatarUrl(chat)
    val otherUserId = remember(chat.participants, currentUserId) {
        chat.participants.firstOrNull { it != currentUserId && it.isNotEmpty() } ?: ""
    }
    val isBlocked = otherUserId.isNotEmpty() && otherUserId in blockedUserIds
    val canShowAvatar = if (isGroupChat) true else !isBlocked

    // Single source of truth for the local avatar path — reactive, so it
    // updates the moment AvatarStateManager re-downloads after unblock.
    //
    // Both 1:1 AND group avatars use AvatarStateManager.observe / observeGroup,
    // which seed localPath SYNCHRONOUSLY from disk on first access. This is
    // CRITICAL for eliminating the group-avatar white flash on cold start: the
    // previous group path used an async LaunchedEffect that always started with
    // localPath=null on the first frame, so the cached group icon only appeared
    // after the effect completed → flash. With a synchronous disk seed the cached
    // icon is present on the very first composition.
    val avatarState by remember(otherUserId, avatarUrl, chat.id, isGroupChat) {
        when {
            !isGroupChat && otherUserId.isNotEmpty() ->
                com.glyph.glyph_v3.data.cache.AvatarStateManager.observe(otherUserId, avatarUrl)
            isGroupChat ->
                com.glyph.glyph_v3.data.cache.AvatarStateManager.observeGroup(chat.id, avatarUrl)
            else -> MutableStateFlow(
                com.glyph.glyph_v3.data.cache.AvatarStateManager.AvatarState(
                    localPath = null, remoteUrl = "", isDownloaded = false, version = 0L
                )
            )
        }
    }.collectAsState()

    val localAvatarPath = remember(avatarState.version, canShowAvatar) {
        if (!canShowAvatar) null else avatarState.localPath
    }
    val visibleAvatarUrl = remember(canShowAvatar, avatarUrl) {
        avatarUrl.takeIf { canShowAvatar && it.isNotBlank() }.orEmpty()
    }
    val avatarCacheKey = remember(chat.id, otherUserId, localAvatarPath, visibleAvatarUrl, isGroupChat) {
        if (isGroupChat) {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.buildGroupAvatarCacheKey(
                chatId = chat.id,
                localAvatarPath = localAvatarPath,
                avatarUrl = visibleAvatarUrl
            )
        } else {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.buildAvatarCacheKey(
                userId = otherUserId,
                localAvatarPath = localAvatarPath,
                avatarUrl = visibleAvatarUrl
            )
        }
    }
    val displayName = remember(chat.groupName, chat.otherUsername, isGroupChat) {
        chatDisplayName(chat)
    }
    val initial = remember(displayName) {
        displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    }
    val bgColor = remember(displayName, isGroupChat) {
        if (isGroupChat) {
            Color(0xFF3A2B1C)
        } else {
            val hashCode = displayName.hashCode()
            val idx = (hashCode and 0x7FFFFFFF) % letterAvatarColorSwatch.size
            letterAvatarColorSwatch[idx]
        }
    }
    val showStatusRing = !isGroupChat && statusRingState == ChatStatusRingState.UNSEEN
    val ringColor = if (showStatusRing) {
        glyphTheme.indicatorUnreadBackground.copy(alpha = 0.95f)
    } else {
        Color.Transparent
    }
    val ringTransition = updateTransition(targetState = statusRingState, label = "chatStatusRing")
    val ringAlpha by ringTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 180, easing = FastOutSlowInEasing) },
        label = "chatStatusRingAlpha"
    ) { state -> if (state == ChatStatusRingState.UNSEEN) 1f else 0f }
    val ringScale by ringTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 220, easing = FastOutSlowInEasing) },
        label = "chatStatusRingScale"
    ) { state -> if (state == ChatStatusRingState.UNSEEN) 1f else 0.92f }
    val avatarSize = 46.dp
    val ringStroke = 1.5.dp
    
    Box(
        modifier = Modifier.size(54.dp),
        contentAlignment = Alignment.Center
    ) {
        if (showStatusRing || ringAlpha > 0f) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = ringAlpha
                        scaleX = ringScale
                        scaleY = ringScale
                    }
            ) {
                val strokePx = ringStroke.toPx()
                drawCircle(
                    color = ringColor,
                    radius = (size.minDimension - strokePx) / 2f,
                    style = Stroke(width = strokePx)
                )
            }
        }

        if (chat.isOfficial) {
            // Brand mark: the launcher app icon (foreground on the launcher
            // background), identical to OfficialChatActivity's header avatar.
            OfficialGlyphAvatar(modifier = Modifier.size(avatarSize))
        } else {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                if (isGroupChat) {
                    Icon(
                        painter = painterResource(R.drawable.ic_group),
                        contentDescription = null,
                        tint = Color(0xFFFFD166),
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = initial,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        val imageFile = remember(localAvatarPath) { localAvatarPath?.let { java.io.File(it) } }
        val imageRequest = remember(localAvatarPath, visibleAvatarUrl, avatarCacheKey, context) {
            val src = imageFile ?: visibleAvatarUrl
            coil.request.ImageRequest.Builder(context)
                .data(src)
                .memoryCacheKey(avatarCacheKey)
                .diskCacheKey(avatarCacheKey)
                .crossfade(false)
                .build()
        }

        if (canShowAvatar && imageFile != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        if (isSelected) {
            val glyphTheme = LocalGlyphTheme.current
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .shadow(3.dp, CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(glyphTheme.actionPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        } else {
            if (!isGroupChat) {
                PresenceIndicator(isOnline, isInChat, Modifier.size(54.dp))
            } else if (chat.groupOnlineCount > 0) {
                // Show how many group members are currently online
                GroupOnlineCountBadge(
                    count = chat.groupOnlineCount,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}

/**
 * Mutable holder for an avatar's tap-target bounds in window coordinates, updated
 * by [onGloballyPositioned] on every layout pass and read only on tap. Plain
 * (non-State) fields so writes during scroll don't trigger recomposition — see
 * the ChatRow comment for why this matters.
 */
private class AvatarBounds {
    var x: Float = 0f
    var y: Float = 0f
    var width: Int = 0
    var height: Int = 0
}

private fun resolveOtherUserId(chat: Chat, currentUserId: String?): String {
    if (chat.isGroup) return ""
    return chat.participants.firstOrNull { participantId ->
        participantId != currentUserId && participantId.isNotEmpty()
    }.orEmpty()
}

private fun chatDisplayName(chat: Chat, currentUserId: String? = null): String {
    return if (chat.isGroup) {
        chat.groupName.ifBlank { "Group" }
    } else {
        val otherUserId = currentUserId?.let { uid ->
            chat.participants.firstOrNull { it != uid && it.isNotBlank() }
        } ?: ""
        ContactDisplayNameResolver.getDisplayName(
            otherUserId = otherUserId,
            remoteProfileName = chat.otherUsername
        )
    }
}

internal fun buildChatListSubtitle(
    chat: Chat,
    currentUserId: String?,
    groupSenderNamesByUserId: Map<String, String>
): String {
    val rawMessage = chat.lastMessage.trim()
    if (rawMessage.isBlank()) return rawMessage
    if (!chat.isGroup) return rawMessage
    if (isLikelyGroupSystemSummary(rawMessage)) return rawMessage

    val senderId = chat.lastMessageSenderId
    if (senderId.isBlank()) return rawMessage

    val senderLabel = if (senderId == currentUserId) {
        "You"
    } else {
        groupSenderNamesByUserId[senderId]?.takeIf { it.isNotBlank() }
    } ?: return rawMessage

    return if (rawMessage.startsWith("$senderLabel:")) rawMessage else "$senderLabel: $rawMessage"
}

private fun isLikelyGroupSystemSummary(message: String): Boolean {
    val lower = message.lowercase(Locale.getDefault())
    return lower.startsWith("group ") ||
        " was created" in lower ||
        " added " in lower ||
        " removed " in lower ||
        " left" in lower ||
        " joined" in lower ||
        " promoted " in lower ||
        " demoted " in lower ||
        " changed the group" in lower
}

private fun chatDisplayAvatarUrl(chat: Chat): String {
    return if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
}

/**
 * Small circular badge displayed at the bottom-end of a group avatar when at
 * least one member is online. Shows the online count (e.g. "3") or "99+" for
 * very large groups. Animates in/out smoothly so the chat list never flickers.
 */
@Composable
private fun GroupOnlineCountBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    val badgeScale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "GroupBadgeScale"
    )
    if (badgeScale <= 0f) return

    val label = if (count > 99) "99+" else "$count"

    // Outer white ring provides contrast against the avatar background.
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = badgeScale; scaleY = badgeScale }
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .background(Color(0xFF25D366)), // WhatsApp green
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp,
                letterSpacing = 0.sp
            )
        }
    }
}

@Composable
private fun PresenceIndicator(
    isOnline: Boolean,
    isInChat: Boolean,
    modifier: Modifier = Modifier
) {
    val dotAlpha by animateFloatAsState(
        targetValue = if (isOnline) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "DotAlpha"
    )
    val dotScale by animateFloatAsState(
        targetValue = if (isOnline) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "DotScale"
    )

    // Only pay for an infinite transition when the user is actively in-chat.
    // Suspend the 1.5s presence pulse while the list is flinging: the pulse
    // recomposes this Canvas every ~16ms while ticking, so during scroll it
    // competes for the UI-thread budget needed to compose newly-visible rows
    // (the fling bottleneck per gfxinfo). The green online dot itself stays
    // (dotAlpha/dotScale are at rest unless presence changes) — only the subtle
    // scale pulse freezes, then resumes on idle.
    //
    // Reading LocalListScrolling.current.value *inside* the isOnline && isInChat
    // branch means rows that never pulse never subscribe to the scroll State,
    // so only online-in-chat rows recompose on the idle↔scroll flip (twice per
    // gesture) — the rest of the row tree is untouched.
    val pulseScale = if (isOnline && isInChat) {
        val isListScrolling = LocalListScrolling.current.value
        if (!isListScrolling) {
            val pulseTransition = rememberInfiniteTransition(label = "DotPulse")
            val ps by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Pulse"
            )
            ps
        } else {
            1f
        }
    } else {
        1f
    }

    if (dotAlpha <= 0f && dotScale <= 0f) return

    val color = glyphTheme.indicatorOnline

    Canvas(modifier = modifier) {
        val dotRadius = 6.dp.toPx()
        val borderWidth = 1.dp.toPx()
        val finalRadius = dotRadius * dotScale * pulseScale
        val centerOffset = androidx.compose.ui.geometry.Offset(
            x = size.width * 0.82f,
            y = size.height * 0.82f
        )
        drawCircle(
            color = Color.White,
            radius = finalRadius + borderWidth,
            center = centerOffset,
            alpha = dotAlpha
        )
        drawCircle(
            color = color,
            radius = finalRadius,
            center = centerOffset,
            alpha = dotAlpha
        )
    }
}
@Composable
private fun TypingIndicator(label: String = "") {
    // Single shared phase drives all three dots — 3× cheaper than 3 InfiniteTransitions.
    // Suspend the bounce while the list is flinging: the infinite animate ticks this
    // subtree every frame (~16ms) and re-evaluates the 3-dot offsets, competing for
    // UI-thread budget needed to compose newly-visible rows during fling (the gfxinfo-
    // confirmed bottleneck). Reading LocalListScrolling shows the static "typing…"
    // label while scrolling and freezes the dot bounce, resuming on idle. Only rows
    // that actually render this indicator subscribe to the scroll State.
    val isListScrolling = LocalListScrolling.current.value
    val typingPhase = if (!isListScrolling) {
        val typingTransition = rememberInfiniteTransition(label = "TypingPhase")
        typingTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "Phase"
        ).value
    } else {
        0f
    }

    val displayLabel = label.trim()
        .ifBlank { "typing..." }
        .removeSuffix("...")
        .trimEnd()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = displayLabel,
            fontSize = 14.sp,
            color = glyphTheme.actionPrimary
        )

        Row(
            modifier = Modifier.padding(start = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
        ) {
            for (index in 0 until 3) {
                // Phase-shift each dot by 1/3 of the cycle, then map to a -4..0 bounce
                val dotPhase = (typingPhase + index / 3f) % 1f
                val offsetY = if (dotPhase < 0.5f) -4f * (dotPhase / 0.5f) else -4f * ((1f - dotPhase) / 0.5f)

                Text(
                    text = ".",
                    fontSize = 18.sp,
                    color = glyphTheme.actionPrimary,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.offset(y = offsetY.dp)
                )
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(glyphTheme.indicatorUnreadBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = glyphTheme.indicatorUnreadText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(x = (-0.4).dp, y = (-1.5).dp)
        )
    }
}

private val letterAvatarColorSwatch: List<Color> = listOf(
    Color(0xFF25D366),
    Color(0xFF128C7E),
    Color(0xFF075E54),
    Color(0xFF34B7F1),
    Color(0xFF00A884),
    Color(0xFFD4AC0D),
    Color(0xFFE74C3C),
    Color(0xFF9B59B6),
    Color(0xFF3498DB),
    Color(0xFFE67E22)
)

// formatTimestampWhatsApp, todayFormatter, dowFormatter, shortDateFormatter,
// timestampStringCache, TIMESTAMP_CACHE_CAPACITY, and letterAvatarColors
// are all defined in ChatListScreenAdapter.kt (internal) and shared between
// the Compose LazyColumn and the RecyclerView adapter paths.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopAppBar(
    selectionCount: Int,
    onBackClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMuteClick: () -> Unit,
    onArchiveClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    iconColor: Color,
    isArchivedMode: Boolean = false
) {
    TopAppBar(
        title = {
            Text(
                text = "$selectionCount",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = iconColor
                )
            }
        },
        actions = {
            IconButton(onClick = onDeleteClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = "Delete",
                    tint = iconColor
                )
            }
            if (!isArchivedMode) {
                IconButton(onClick = onMuteClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_notifications),
                        contentDescription = "Mute",
                        tint = iconColor
                    )
                }
            }
            IconButton(onClick = onArchiveClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_archive),
                    contentDescription = if (isArchivedMode) "Unarchive" else "Archive",
                    tint = iconColor,
                    modifier = if (isArchivedMode) Modifier.rotate(180f) else Modifier
                )
            }
             IconButton(onClick = { /* More options */ }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more_vert),
                    contentDescription = "More",
                    tint = iconColor
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            titleContentColor = contentColor,
            actionIconContentColor = iconColor,
            navigationIconContentColor = iconColor
        )
    )
}

@Composable
private fun ArchivedInfoBanner() {
    val glyphTheme = LocalGlyphTheme.current
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        HorizontalDivider(color = glyphTheme.bubbleBorder)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, top = 10.dp, end = 32.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "These chats stay archived when new messages are received. Tap to change",
                style = TextStyle(
                    color = glyphTheme.textSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                modifier = Modifier.clickable { /* TODO: Settings */ }
            )
        }
        HorizontalDivider(color = glyphTheme.bubbleBorder)
    }
}

@Composable
private fun EncryptionFooter() {
    val glyphTheme = LocalGlyphTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_lock),
            contentDescription = "Encrypted",
            tint = glyphTheme.textSecondary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Your personal messages are end-to-end encrypted",
            style = TextStyle(
                color = glyphTheme.textSecondary,
                fontSize = 12.sp
            )
        )
    }
}

// ─── RecyclerView list-item builder ───────────────────────────────────────────
// Transforms the same List<Chat> used by the LazyColumn into List<ChatListScreenItem>
// for the RecyclerView adapter. Must produce the same ordering and visibility as
// the Compose LazyColumn: archived banner → AI agent → chats → empty state.

internal fun buildChatListItems(
    filteredChats: List<Chat>,
    groupSenderNamesByUserId: Map<String, String>,
    statusRingStatesByUserId: Map<String, ChatStatusRingState>,
    selectedChatIds: Set<String>,
    isSelectionMode: Boolean,
    isInitialLoading: Boolean,
    isArchivedMode: Boolean,
    currentUserId: String?,
    blockedUserIds: Set<String>
): List<ChatListScreenItem> {
    val items = mutableListOf<ChatListScreenItem>()

    if (isArchivedMode) {
        items.add(ChatListScreenItem.ArchivedBanner())
    }

    if (isInitialLoading) {
        repeat(8) { index ->
            items.add(ChatListScreenItem.Placeholder("placeholder_$index"))
        }
    } else {
        // ── Pinned "Glyph AI" entry ───────────────────────────────
        if (!isArchivedMode) {
            items.add(ChatListScreenItem.AiAgent())
        }

        // ── Chat rows ────────────────────────────────────────────
        for (chat in filteredChats) {
            val otherUserId = resolveOtherUserId(chat, currentUserId)
            val displayName = chatDisplayName(chat, currentUserId)
            val avatarUrl = chatDisplayAvatarUrl(chat)
            val statusRingState = statusRingStatesByUserId[otherUserId] ?: ChatStatusRingState.NONE
            val isSelected = selectedChatIds.contains(chat.id)
            val avatarCacheKey = if (chat.isGroup) {
                AvatarCacheManager.groupIconCacheIdPublic(chat.id)
            } else if (otherUserId.isNotEmpty()) {
                otherUserId
            } else {
                ""
            }
            // Synchronous avatar state read for initial display.
            // Single peek() call to avoid inconsistency between localPath and version
            // reads (the synchronized block in peek guarantees atomicity, but two
            // separate calls could still race with a concurrent refresh()).
            val avatarStateOpt = AvatarStateManager.peek(avatarCacheKey)
            val avatarLocalPath = avatarStateOpt?.localPath
            val avatarStateVersion = avatarStateOpt?.version ?: 0L
            val isBlocked = !chat.isGroup && otherUserId.isNotEmpty() && otherUserId in blockedUserIds
            val canShowAvatar = if (chat.isGroup) true else !isBlocked

            items.add(ChatListScreenItem.Chat(
                chat = chat,
                displayName = displayName,
                otherUserId = otherUserId,
                statusRingState = statusRingState,
                isSelected = isSelected,
                isInSelectionMode = isSelectionMode,
                avatarUrl = if (canShowAvatar) avatarUrl else "",
                isGroupChat = chat.isGroup,
                avatarLocalPath = if (canShowAvatar && (chat.isGroup || avatarLocalPath != null)) avatarLocalPath else null,
                initialLetter = initialLetter(displayName),
                avatarBgColor = avatarBackgroundColor(displayName, chat.isGroup),
                isOfficial = chat.isOfficial,
                avatarCacheKey = if (canShowAvatar) avatarCacheKey else "",
                avatarStateVersion = avatarStateVersion
            ))
        }

        // ── Empty state ─────────────────────────────────────────
        if (filteredChats.isEmpty()) {
            items.add(ChatListScreenItem.Empty())
        }
    }

    return items
}

/**
 * Returns the uppercase initial letter of the display name, or "G" as fallback.
 * Mirrors the Compose ChatRow's `initial` computation.
 */
internal fun initialLetter(displayName: String): String {
    return displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
}

/**
 * Returns the ARGB Int avatar background color for the letter avatar.
 * Mirrors the Compose ChatRow's `bgColor` computation using letterAvatarColors.
 * Uses the adapter's `letterAvatarColors: List<Int>` (same package) to avoid a
 * naming conflict with the Compose `letterAvatarColorSwatch: List<Color>`.
 */
internal fun avatarBackgroundColor(displayName: String, isGroupChat: Boolean): Int {
    return if (isGroupChat) {
        0xFF3A2B1C.toInt()
    } else {
        val hashCode = displayName.hashCode()
        val idx = (hashCode and 0x7FFFFFFF) % letterAvatarColors.size
        letterAvatarColors[idx]
    }
}
