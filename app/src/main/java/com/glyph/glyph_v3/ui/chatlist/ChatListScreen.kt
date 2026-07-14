package com.glyph.glyph_v3.ui.chatlist

import android.graphics.Rect
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.ui.aiagent.AiAgentConstants
import com.glyph.glyph_v3.ui.theme.LocalGlyphTheme
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ChatStatusRingState {
    NONE,
    SEEN,
    UNSEEN
}

// Cache FontFamily at file scope to avoid re-creating it on every recomposition.
// Font loading is expensive (disk I/O + parsing) and the font doesn't change.
private val bbhBartleFontFamily by lazy {
    FontFamily(Font(R.font.bbh_bartle_regular))
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onUndoDelete: () -> Unit = {}
) {
    // FontFamily is now cached at file scope (bbhBartleFontFamily) — no per-composition cost.
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

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

    val filteredChats = remember(chats, searchQuery, pendingDeleteChatIds, isArchivedMode) {
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

    val context = LocalContext.current
    val density = LocalDensity.current
    val currentTheme = ThemeManager.getCurrentTheme(context)
    val surfaceBackgroundColor = glyphTheme.backgroundPrimary
    val showSecretLockedRow = isLockedChatsHidden && secretCodeMatch && searchQuery.isNotEmpty()
    val showLockedSection = showSecretLockedRow || (lockedChatsCount > 0 && !isLockedChatsHidden)
    val showArchivedSection = archivedChatsCount > 0
    val chatListState = rememberLazyListState()
    val showHeaderSections = !isSelectionMode && !isArchivedMode && (showLockedSection || showArchivedSection)
    val hiddenSectionsRowCount = (if (showLockedSection) 1 else 0) + (if (showArchivedSection) 1 else 0)
    val hiddenSectionsHeight = (hiddenSectionsRowCount * 50).dp
    val hiddenSectionsHeightPx = with(density) { hiddenSectionsHeight.roundToPx().toFloat() }
    val hiddenSectionsRevealKey = remember(
        showSecretLockedRow,
        showLockedSection,
        showArchivedSection,
        lockedChatsCount,
        archivedChatsCount,
        isLockedChatsHidden
    ) {
        listOf(
            showSecretLockedRow,
            showLockedSection,
            showArchivedSection,
            lockedChatsCount,
            archivedChatsCount,
            isLockedChatsHidden
        ).joinToString("|")
    }
    var revealOffsetPx by remember { mutableFloatStateOf(0f) }
    var revealInteractionNonce by remember { mutableIntStateOf(0) }
    val revealConnection = remember(showHeaderSections, isArchivedMode, hiddenSectionsHeightPx, chatListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!showHeaderSections || isArchivedMode || hiddenSectionsHeightPx <= 0f) return Offset.Zero

                val isPushingUp = available.y < 0f
                val isPullingDown = available.y > 0f
                val listAtTop = chatListState.firstVisibleItemIndex == 0 &&
                    chatListState.firstVisibleItemScrollOffset == 0

                return when {
                    isPullingDown && listAtTop -> {
                        val nextOffset = (revealOffsetPx + (available.y * 0.75f)).coerceIn(0f, hiddenSectionsHeightPx)
                        val consumedY = nextOffset - revealOffsetPx
                        if (consumedY != 0f) {
                            revealOffsetPx = nextOffset
                            revealInteractionNonce += 1
                        }
                        Offset(0f, consumedY)
                    }

                    isPushingUp && revealOffsetPx > 0f -> {
                        val nextOffset = (revealOffsetPx + available.y).coerceIn(0f, hiddenSectionsHeightPx)
                        val consumedY = nextOffset - revealOffsetPx
                        if (consumedY != 0f) {
                            revealOffsetPx = nextOffset
                            revealInteractionNonce += 1
                        }
                        Offset(0f, consumedY)
                    }

                    else -> Offset.Zero
                }
            }
        }
    }

    LaunchedEffect(hiddenSectionsRevealKey, showHeaderSections, isArchivedMode, hiddenSectionsHeightPx) {
        // Always start with hidden sections collapsed on first appearance.
        // The user reveals them via pull-down gesture (NestedScrollConnection).
        revealOffsetPx = 0f
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
                                fontFamily = bbhBartleFontFamily,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(revealConnection)
                .clipToBounds()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = revealOffsetPx }
            ) {
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
                                selectionBackgroundColor = selectionBackgroundColor
                            )
                        }

                        if (filteredChats.isEmpty()) {
                            item(key = "empty_state") { EmptyChatListState() }
                        }
                    }
                }
            }

            if (showHeaderSections && hiddenSectionsHeightPx > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = contentPadding.calculateTopPadding())
                        .height(hiddenSectionsHeight)
                        .clipToBounds()
                        .zIndex(1f)
                ) {
                    HiddenChatsSections(
                        archivedChatsCount = archivedChatsCount,
                        onClickArchived = onArchivedFolderClick,
                        hasUnreadArchivedMessages = hasUnreadArchivedMessages,
                        lockedChatsCount = lockedChatsCount,
                        hasUnreadLockedMessages = hasUnreadLockedMessages,
                        onClickLocked = onLockedChatsClick,
                        isLockedChatsHidden = isLockedChatsHidden,
                        secretCodeMatch = secretCodeMatch,
                        searchQuery = searchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(hiddenSectionsHeight)
                            .graphicsLayer {
                                translationY = revealOffsetPx - hiddenSectionsHeightPx
                            }
                    )
                }
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
        // AI avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(aiAccentColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ai_agent),
                contentDescription = "Glyph AI",
                tint = Color.Unspecified,
                modifier = Modifier.size(48.dp)
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

@Composable
private fun HiddenChatsSections(
    archivedChatsCount: Int,
    onClickArchived: () -> Unit,
    hasUnreadArchivedMessages: Boolean,
    lockedChatsCount: Int = 0,
    hasUnreadLockedMessages: Boolean = false,
    onClickLocked: () -> Unit = {},
    isLockedChatsHidden: Boolean = false,
    secretCodeMatch: Boolean = false,
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentTheme = ThemeManager.getCurrentTheme(context)

    val archiveBadgeColor = if (hasUnreadArchivedMessages) {
        glyphTheme.indicatorUnreadBackground
    } else {
        when (currentTheme) {
            ThemeManager.THEME_DARK -> Color(0xFF374151) // Dark grey
            ThemeManager.THEME_LIGHT -> Color(0xFFE0E0E0) // Light grey
            ThemeManager.THEME_PASTEL_SKY -> Color(0xFFB0C0CF).copy(alpha = 0.5f) // Muted blue-grey
            else -> Color.Gray
        }
    }

    val archiveBadgeTextColor = if (hasUnreadArchivedMessages) {
         glyphTheme.indicatorUnreadText
    } else {
        // Muted text color for the neutral badge
         when (currentTheme) {
            ThemeManager.THEME_DARK -> Color(0xFF9CA3AF)
            ThemeManager.THEME_LIGHT -> Color(0xFF757575)
            else -> Color.White
        }
    }

    val lockedBadgeColor = if (hasUnreadLockedMessages) {
        glyphTheme.indicatorUnreadBackground
    } else {
        when (currentTheme) {
            ThemeManager.THEME_DARK -> Color(0xFF374151)
            ThemeManager.THEME_LIGHT -> Color(0xFFE0E0E0)
            ThemeManager.THEME_PASTEL_SKY -> Color(0xFFB0C0CF).copy(alpha = 0.5f)
            else -> Color.Gray
        }
    }
    val lockedBadgeTextColor = if (hasUnreadLockedMessages) {
        glyphTheme.indicatorUnreadText
    } else {
        when (currentTheme) {
            ThemeManager.THEME_DARK -> Color(0xFF9CA3AF)
            ThemeManager.THEME_LIGHT -> Color(0xFF757575)
            else -> Color.White
        }
    }

    val showSecretLockedRow = isLockedChatsHidden && secretCodeMatch && searchQuery.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // Secret-code search reveal: only when "Hide locked chats" is ENABLED and the PIN matches.
        // When not hidden the normal row already shows, so no injection needed.
        if (showSecretLockedRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickLocked)
                    .height(50.dp)
                    .padding(start = 16.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chat_lock),
                    contentDescription = "Locked chats",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Locked chats",
                    color = glyphTheme.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Normal locked-chats row: only show when section is NOT hidden by secret code
        if (lockedChatsCount > 0 && !isLockedChatsHidden) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickLocked)
                    .height(50.dp)
                    .padding(start = 16.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chat_lock),
                    contentDescription = "Locked chats",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Locked chats",
                    color = glyphTheme.textSecondary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(lockedBadgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lockedChatsCount.toString(),
                        color = lockedBadgeTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(x = (-0.4).dp, y = (-1.5).dp)
                    )
                }
            }
        }

        if (archivedChatsCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickArchived)
                    .height(50.dp)
                    .padding(start = 16.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_archive),
                    contentDescription = "Archived",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Archived",
                    color = glyphTheme.textSecondary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(archiveBadgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = archivedChatsCount.toString(),
                        color = archiveBadgeTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(x = (-0.4).dp, y = (-1.5).dp)
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
    selectionBackgroundColor: Color = Color.Transparent
) {
    var avatarTopLeft by remember { mutableStateOf(Offset.Zero) }
    var avatarSize by remember { mutableStateOf(IntSize.Zero) }
    var isPositioned by remember { mutableStateOf(false) }
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
                        avatarTopLeft = coordinates.positionInWindow()
                        avatarSize = coordinates.size
                        isPositioned = true
                    }
                    .clickable(enabled = isPositioned) {
                        if (isPositioned && avatarSize.width > 0 && avatarSize.height > 0) {
                            val avatarBoundsInWindow = Rect(
                                avatarTopLeft.x.roundToInt(),
                                avatarTopLeft.y.roundToInt(),
                                (avatarTopLeft.x + avatarSize.width).roundToInt(),
                                (avatarTopLeft.y + avatarSize.height).roundToInt()
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
                    isSelected = isSelected
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
                        text = chatDisplayName(chat, currentUserId),
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
                    val draftText = chat.draft.trim()
                    val hasDraft = draftText.isNotEmpty()

                    if (chat.isOtherUserTyping) {
                        // Show typing indicator wrapped in weight(1f) to keep UnreadBadge at the end
                        Box(modifier = Modifier.weight(1f)) {
                            TypingIndicator(label = chat.typingText)
                        }
                    } else if (hasDraft) {
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
                                append(draftText)
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

                        Text(
                            text = buildChatListSubtitle(chat, currentUserId, groupSenderNamesByUserId),
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
    isSelected: Boolean = false
) {
    val context = LocalContext.current
    val isGroupChat = chat.isGroup
    val avatarUrl = chatDisplayAvatarUrl(chat)
    val otherUserId = remember(chat.participants, currentUserId) {
        chat.participants.firstOrNull { it != currentUserId && it.isNotEmpty() } ?: ""
    }
    // Avatar visibility is gated ONLY on live block status. The persisted
    // AvatarVisibilityRepository cache reports isVisible=false during a block
    // and stays stale after unblock — using it here means the avatar never
    // reappears. Block status is the single authoritative gate.
    val blockedUsers by remember { com.glyph.glyph_v3.data.repo.BlockRepository.myBlockedUsers }
        .collectAsState()
    val isBlocked = otherUserId.isNotEmpty() && otherUserId in blockedUsers
    val canShowAvatar = if (isGroupChat) true else !isBlocked

    // Single source of truth for the local avatar path — reactive, so it
    // updates the moment AvatarStateManager re-downloads after unblock.
    val avatarState by remember(otherUserId, avatarUrl) {
        if (otherUserId.isNotEmpty() && !isGroupChat) {
            com.glyph.glyph_v3.data.cache.AvatarStateManager.observe(otherUserId, avatarUrl)
        } else {
            kotlinx.coroutines.flow.MutableStateFlow(
                com.glyph.glyph_v3.data.cache.AvatarStateManager.AvatarState(
                    localPath = null, remoteUrl = "", isDownloaded = false, version = 0L
                )
            )
        }
    }.collectAsState()

    val localAvatarPath = remember(avatarState.version, canShowAvatar, isGroupChat, chat.id) {
        if (!canShowAvatar) {
            null
        } else if (isGroupChat) {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chat.id)
        } else {
            avatarState.localPath
        }
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
            val idx = (hashCode and 0x7FFFFFFF) % letterAvatarColors.size
            letterAvatarColors[idx]
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

        if (canShowAvatar && (imageFile != null || visibleAvatarUrl.isNotEmpty())) {
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

    // Only pay for an infinite transition when the user is actively in-chat
    val pulseScale = if (isOnline && isInChat) {
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
    // Single shared phase drives all three dots — 3× cheaper than 3 InfiniteTransitions
    val typingTransition = rememberInfiniteTransition(label = "TypingPhase")
    val typingPhase by typingTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Phase"
    )

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

private val letterAvatarColors: List<Color> = listOf(
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

private fun formatTimestampWhatsApp(date: Date): String {
    val now = Calendar.getInstance()
    val messageTime = Calendar.getInstance().apply { time = date }

    val isToday = now.get(Calendar.DATE) == messageTime.get(Calendar.DATE) &&
        now.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
        now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)

    val isYesterday = run {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
        yesterday.get(Calendar.DATE) == messageTime.get(Calendar.DATE) &&
            yesterday.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
            yesterday.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
    }

    val isThisWeek = run {
        val weekAgo = Calendar.getInstance().apply { add(Calendar.DATE, -7) }
        messageTime.after(weekAgo) && !isToday && !isYesterday
    }

    return when {
        isToday -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        isYesterday -> "Yesterday"
        isThisWeek -> SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(date)
    }
}
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
