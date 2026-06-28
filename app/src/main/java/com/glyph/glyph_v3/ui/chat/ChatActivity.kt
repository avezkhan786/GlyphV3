package com.glyph.glyph_v3.ui.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.TextUtils
import android.transition.TransitionManager
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.core.animation.doOnEnd
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import android.view.HapticFeedbackConstants
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.util.BuzzManager
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.ItemTouchHelper
import android.util.DisplayMetrics
import androidx.core.app.NotificationManagerCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.media.MediaTransferManager
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.ProfilePhotoVisibilityState
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.databinding.ActivityChatBinding
import com.glyph.glyph_v3.databinding.LayoutSelectionToolbarBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingTextBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingTextBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingMediaBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingMediaBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingMediaGroupBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingMediaGroupBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingDocumentBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingDocumentBinding
import com.glyph.glyph_v3.databinding.ItemMessageIncomingCollageBinding
import com.glyph.glyph_v3.databinding.ItemMessageOutgoingCollageBinding
import com.glyph.glyph_v3.ui.chat.forward.ForwardSelectionActivity
import com.glyph.glyph_v3.ui.share.LinkPreviewData
import com.glyph.glyph_v3.ui.share.LinkPreviewResolver
import com.glyph.glyph_v3.ui.media.MediaViewerActivity
import com.glyph.glyph_v3.ui.media.VideoPlayerActivity
import com.glyph.glyph_v3.util.MediaCompressor
import com.glyph.glyph_v3.util.VideoNoteCompressor
import com.glyph.glyph_v3.data.service.ChatNotificationHelper
import com.glyph.glyph_v3.data.service.DraftMessageStore
import com.glyph.glyph_v3.data.service.UnreadMessageStore
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.utils.DebugUtils
import com.glyph.glyph_v3.utils.MessageCacheManager
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import androidx.activity.viewModels
import android.view.LayoutInflater
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import com.glyph.glyph_v3.utils.ChatWallpaperManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import kotlin.sequences.filterIsInstance
import kotlin.sequences.find
import androidx.compose.runtime.mutableStateOf
import android.content.res.ColorStateList
import androidx.compose.ui.graphics.toArgb
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.ui.theme.PastelSkyThemeTokens
import com.glyph.glyph_v3.ui.chat.state.RecorderState
import com.glyph.glyph_v3.util.AudioRecorder
import com.glyph.glyph_v3.util.AudioPlayer
import com.glyph.glyph_v3.ui.chat.AudioPlaybackState
import com.glyph.glyph_v3.ui.chat.composer.AiComposerUiState
import com.glyph.glyph_v3.ui.chat.overlay.VoiceRecorderOverlay
import com.glyph.glyph_v3.ui.chat.overlay.VoiceRecordingOptionsSheet
import com.glyph.glyph_v3.ui.chat.overlay.VoiceOptionState
import com.glyph.glyph_v3.data.repo.SpeechToTextRepository
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.chat.expressive.RomanticAnimationMode
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver

@Suppress("DEPRECATION")
class ChatActivity : AppCompatActivity(), 
    ChatAdapter.MediaDownloadListener,
    ChatAdapter.MediaLocalFileResolver,
    BuzzManager.BuzzUiListener {

    private enum class HeaderPresenceKind {
        ONLINE,
        ONLINE_IN_CHAT,
        LAST_SEEN,
        OFFLINE,
        HIDDEN
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var selectionToolbarBinding: LayoutSelectionToolbarBinding

    private val selectionManager = SelectionManager()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var repository: RealtimeMessageRepository
    private lateinit var mediaTransferManager: MediaTransferManager
    private lateinit var mediaController: MediaController
    private lateinit var scrollController: ChatScrollController
    private lateinit var recyclerCoordinator: RecyclerCoordinator
    private lateinit var keyboardController: KeyboardController
    private val firebaseRepository = FirebaseRepository()

    // Group chat support (Phase 5). isGroupChat is derived cheaply from chatId prefix
    // (`group_<uuid>`) right after intent extras are parsed, so all downstream branches
    // can read it synchronously without a Room round-trip. groupRepository is lazy so
    // 1:1 chats never construct it.
    private var isGroupChat: Boolean = false
    private val groupRepository: com.glyph.glyph_v3.data.repo.GroupChatRepository by lazy {
        (applicationContext as GlyphApplication).getOrCreateGroupChatRepository()
    }
    /**
     * Phase 6: uid -> display-name cache used by [ChatAdapter.senderNameResolver] to
     * render the small "sender name" line above incoming bubbles in group chats.
     * Synchronously read on the bind path; Firestore lookups happen off-thread and
     * notify the adapter via [ChatAdapter.refreshGroupSenderNames] when populated.
     */
    private val groupSenderNamesCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val groupSenderAvatarCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val groupSenderAvatarCachingPending: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )
    private val groupSenderNamesPending: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )
    private val groupParticipantNamesCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val groupParticipantNamesPending: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )
    private val groupTypingNamesPending: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )
    private var activeGroupTypingUserIds: Set<String> = emptySet()
    private var groupTypingIndicatorText: String = ""
    private var groupParticipantIdsForTyping: List<String> = emptyList()
    private var groupIntroName: String = "Group"
    private var groupIntroAvatarUrl: String = ""
    private var groupIntroDescription: String = ""
    private var groupIntroMemberCount: Int = 0
    private val compressionViewModel: CompressionViewModel by viewModels()
    private val registeredUsersCache = linkedMapOf<String, com.glyph.glyph_v3.data.models.User>()

    // Tracks the currently-visible WhatsApp-style reaction bar so we can replace,
    // dismiss, or reposition it as the user's selection changes. Only one popup may be
    // visible at any time; the message id is kept separately so we can detect when the
    // selection moves to a different message.
    private var currentReactionPopup: com.glyph.glyph_v3.ui.chat.reactions.ReactionPopupOverlay? = null
    private var currentReactionPopupMessageId: String? = null
    private var currentReactionPopupTouchYScreen: Float = -1f
    
    private var chatId: String? = null
    private var otherUserId: String? = null
    private var otherUsername: String = ""
    private var otherUserPhone: String = ""
    private var otherUserAvatar: String = ""

    private var currentUserPhone: String = ""
    private var currentUserUsername: String = ""
    private var currentUserAvatar: String = ""
    
    // Compose states for Pastel theme
    private val lastSeenState = mutableStateOf("")
    private val composeHeaderLastSeenState = mutableStateOf("")
    private val composeHeaderLastSeenMarqueeEnabledState = mutableStateOf(false)
    private var startupLastSeenRevealCompleted = false
    private var startupLastSeenRevealJob: Job? = null
    private var presenceEmissionCount = 0
    private val messageTextState = mutableStateOf("")
    private val userNameState = mutableStateOf("")
    private val userAvatarState = mutableStateOf("")
    private val selectionModeState = mutableStateOf(false)
    private val isChatEmptyState = mutableStateOf(true)

    // Guard against concurrent/double "Clear chat" execution.
    @Volatile
    private var clearChatInProgress = false

    // Walkie-Talkie
    private val walkieTalkieState = mutableStateOf(com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.IDLE)
    private val walkieTalkieButtonVisible = mutableStateOf(true) // always show WT button for the peer
    private var walkieTalkieTopSafeAreaInsetPx by mutableIntStateOf(0)
    private var walkieTalkieBottomSafeAreaInsetPx by mutableIntStateOf(0)

    // Pinned-message banner state (driven by Room Flow)
    private val pinnedMessagesState = mutableStateOf<List<com.glyph.glyph_v3.data.local.entity.LocalMessage>>(emptyList())
    private val pinnedBannerIndexState = mutableStateOf(0)
    private val pinnedBannerDismissed = mutableStateOf(false)
    private var pinnedBannerObserverJob: Job? = null

    // Voice Recording & Playback
    private lateinit var recorderState: com.glyph.glyph_v3.ui.chat.state.RecorderState
    private lateinit var audioRecorder: com.glyph.glyph_v3.util.AudioRecorder
    private lateinit var audioPlayer: com.glyph.glyph_v3.util.AudioPlayer
    private var voiceRuntimeInitialized = false
    private var pendingVoicePlaybackRequestId: String? = null
    private val audioPlaybackState = mutableStateOf<AudioPlaybackState?>(null)

    // Speech-to-Text
    private val speechToTextRepository by lazy { SpeechToTextRepository.getInstance(this) }
    private val voiceOptionState = mutableStateOf<VoiceOptionState>(VoiceOptionState.Hidden)
    private var pendingVoiceFile: java.io.File? = null
    private var pendingVoiceDurationMs: Long = 0L
    private var sttJob: Job? = null
    private val selectedSttLanguage = mutableStateOf(
        com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage.DEFAULT
    )
    
    // Translation feature
    private lateinit var translationManager: com.glyph.glyph_v3.ui.chat.translation.TranslationManager
    private var translationToolbar: com.glyph.glyph_v3.ui.chat.translation.TranslationToolbarPopup? = null
    private var translationWarmupStarted = false

    // AI Composer feature
    private lateinit var aiComposerManager: com.glyph.glyph_v3.ui.chat.composer.AiComposerManager
    private var aiComposerWarmupStarted = false
    private var deferredFeatureWarmupJob: Job? = null
    private var deferredOffscreenPreviewWarmupJob: Job? = null
    private var deferredMediaHeightWarmupJob: Job? = null
    private val retainedHeaderAvatarPreloads = CopyOnWriteArrayList<ChatOpenPrefetcher.RetainedAvatarPreload>()
    private var idleTaskRunnerJob: Job? = null
    private var deferredStartupUnlockJob: Job? = null
    private var postRenderObserversStarted = false
    private var attachmentMenuInitialized = false
    private var emojiPickerInitialized = false
    private var idleQueuePaused = true
    private var deferredStartupWorkUnlocked = false
    private val idleTaskMutex = Mutex()
    // Frame-budget guard: monitors frame times during SETTLING warming.
    // If a frame exceeds 18ms, warming pauses until frame times recover.
    private val frameBudgetGuard = FrameBudgetGuard(maxFrameMs = 18.0f)
    private val idleTaskQueue = linkedMapOf<String, IdleScheduledTask>()

    // Live Expressive Typing feature
    private var expressiveTypingManager: com.glyph.glyph_v3.ui.chat.expressive.ExpressiveTypingManager? = null
    private var expressiveTypingViewController: com.glyph.glyph_v3.ui.chat.expressive.ExpressiveTypingViewController? = null
    private val expressivePrefs by lazy { com.glyph.glyph_v3.ui.chat.expressive.ExpressiveTypingPreferences.getInstance(this) }

    // Inline translation state: messageId → TranslationState
    private data class InlineTranslationState(
        val translatedText: String? = null,
        val isShowingTranslation: Boolean = false,
        val isTranslating: Boolean = false
    )
    private data class DeferredLargeFlowEmission(
        val messages: List<Message>,
        val isTypingRaw: Boolean
    )

    private val translationStateMap = java.util.concurrent.ConcurrentHashMap<String, InlineTranslationState>()
    private val translationPrefs by lazy {
        com.glyph.glyph_v3.ui.chat.translation.TranslationPreferences.getInstance(this)
    }
    
    private var presenceJob: Job? = null
    private var groupPresenceJob: Job? = null
    private var pendingGroupPresenceFallbackJob: Job? = null
    private var activeGroupOnlineCount: Int = 0
    /** The static member-list subtitle set by observeGroupMetadata(); used as fallback. */
    private var groupMemberSubtitle: String = ""
    /** Tracks the member list previously used to start group presence observation. */
    private var lastObservedGroupMembers: List<String> = emptyList()
    private var typingJob: Job? = null
    private var eagerTypingObserverJob: Job? = null
    private var pendingTransitionPlaySound: Boolean = false
    private var pendingLargeFlowEmission: DeferredLargeFlowEmission? = null
    private var pendingLargeFlowApplyJob: Job? = null
    private var displayedMessageIds: MutableSet<String> = mutableSetOf()
    // ── Computation caches ───────────────────────────────────────────────────
    // Emoji-only text analysis is ~0.5ms per message (Unicode codepoint scan).
    // Caching avoids re-scanning the same text on every flow emission.
    private val messageEmojiCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // Timestamp→LocalDate conversion uses java.time which allocates Instant,
    // ZonedDateTime, and LocalDate objects per call. Caching avoids this on
    // every list rebuild for messages whose timestamp hasn't changed.
    private val messageDateCache = java.util.concurrent.ConcurrentHashMap<String, LocalDate>()
    private var lastTypingIndicatorVisible: Boolean = false
    private var isTyping = false
    private var isOtherUserTyping = false
    // Compose-observable state for sentiment overlay
    private val _isExpressiveActive = mutableStateOf(false)
    private val _isExpressiveMutuallyEnabled = mutableStateOf(false)
    private val _expressiveLiveText = mutableStateOf("")
    private val _expressiveSentiment = mutableStateOf(com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL)
    private val _romanticAnimationMode = mutableStateOf(RomanticAnimationMode.EMOJI_PARTICLES)
    private val _isMessageRomanticEffectActive = mutableStateOf(false)
    private val _messageRomanticSentiment = mutableStateOf(com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL)
    private val _messageRomanticAnimationMode = mutableStateOf(RomanticAnimationMode.EMOJI_PARTICLES)
    private val _expressiveComposerTopPx = mutableStateOf(0)
    
    // Delegate accessors for non-Compose code
    private var isExpressiveActive: Boolean
        get() = _isExpressiveActive.value
        set(value) { _isExpressiveActive.value = value }
    private var isExpressiveMutuallyEnabled: Boolean
        get() = _isExpressiveMutuallyEnabled.value
        set(value) { _isExpressiveMutuallyEnabled.value = value }
    private var expressiveLiveText: String
        get() = _expressiveLiveText.value
        set(value) { _expressiveLiveText.value = value }
    private var expressiveSentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
        get() = _expressiveSentiment.value
        set(value) { _expressiveSentiment.value = value }
    private var romanticAnimationMode: RomanticAnimationMode
        get() = _romanticAnimationMode.value
        set(value) { _romanticAnimationMode.value = value }
    private var isMessageRomanticEffectActive: Boolean
        get() = _isMessageRomanticEffectActive.value
        set(value) { _isMessageRomanticEffectActive.value = value }
    private var messageRomanticSentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
        get() = _messageRomanticSentiment.value
        set(value) { _messageRomanticSentiment.value = value }
    private var messageRomanticAnimationMode: RomanticAnimationMode
        get() = _messageRomanticAnimationMode.value
        set(value) { _messageRomanticAnimationMode.value = value }
    private var expressiveComposerTopPx: Int
        get() = _expressiveComposerTopPx.value
        set(value) { _expressiveComposerTopPx.value = value }
    private var expressiveInputLayoutChangeListener: View.OnLayoutChangeListener? = null
    private var messageRomanticEffectJob: Job? = null
    private var expressiveObserveSessionStartedAtMs: Long = 0L
    private var lastAppliedExpressiveSourceTimestampMs: Long = 0L
    private var expressiveInlineAnimationArmed: Boolean = false
    
    private var lastObservedOtherUserTypingRaw: Boolean = false
    private var lastPresenceStatus: PresenceManager.PresenceStatus? = null
    private var lastHeaderPresenceKind: HeaderPresenceKind? = null
    private var otherUserPrivacySettings: PrivacySettingsRepository.PrivacySettings? = null
    private var otherUserPrivacyAmIContact: Boolean = false
    private var privacySettingsJob: Job? = null
    private var avatarVisibilityJob: Job? = null

    // ── Walkie-Talkie ─────────────────────────────────────────────────
    private val walkieTalkieManager by lazy {
        com.glyph.glyph_v3.data.service.WalkieTalkieManager.getInstance(this)
    }
    private var walkieTalkieStateJob: Job? = null
    private var walkieTalkieFloorDeniedJob: Job? = null

    private var messageSyncListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var readReceiptListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var chatOpenStartElapsedMs: Long = 0L
    // True once the Activity's window-enter animation is complete (or the 500ms safety net fires).
    // Used to gate the first large live-flow tail expansion so DiffUtil commits don't compete
    // with the slide-in animation for the main-thread frame budget.
    @Volatile private var enterAnimationCompleted = false
    private var firstContentDrawLogged = false
    private var initialVisibleWarmupLogged = false
    private var lastVisibleWarmupSignature: String? = null
    private var lastVisibleWarmupAtMs: Long = 0L
    private var scrollIdleMediaUpgradeJob: Job? = null
    private val firstScrollMetricsLogged: Boolean
        get() = ::scrollController.isInitialized && scrollController.firstScrollMetricsLogged
    private val firstScrollTrackingActive: Boolean
        get() = ::scrollController.isInitialized && scrollController.firstScrollTrackingActive

    companion object {
        private const val PHASE2_PERF_TAG = "ChatPhase2Perf"
        private const val PHASE3_PERF_TAG = "ChatPhase3Perf"
        private const val PHASE4_PERF_TAG = "ChatPhase4Perf"
        private const val INITIAL_VISIBLE_WARMUP_COUNT = 8
        private const val INITIAL_SCROLL_UP_WARMUP_MULTIPLIER = 3
        private const val VISIBLE_PREVIEW_BUFFER_ITEMS = 2
        private const val MEDIA_PREFETCH_BUFFER_ITEMS = 2
        private const val VISIBLE_WARMUP_DEBOUNCE_MS = 300L
        private const val OFFSCREEN_WARMUP_IDLE_DELAY_MS = 500L
        private const val DEFERRED_MEDIA_HEIGHT_IDLE_DELAY_MS = 200L
        private const val IDLE_QUEUE_RESUME_DELAY_MS = 100L
        private const val SCROLL_IDLE_MEDIA_UPGRADE_DELAY_MS = 140L
        // Backoff when pool warm makes no progress (duration < 5ms). Prevents the tight
        // reschedule loop where hundreds of 0ms warm cycles flood the main thread.
        private const val POOL_WARM_NO_PROGRESS_BACKOFF_MS = 500L
        private const val KEYBOARD_LIST_COMMIT_GATE_TIMEOUT_MS = 900L
        private const val SCROLL_PERF_JANK_FRAME_MS = 32.0
        // WhatsApp-like smoothness: Zero-tolerance for list commits during first scroll.
        // Defer ANY list commit if first scroll is active, regardless of size. This prevents
        // even small commits from interfering with the critical first-scroll experience.
        private const val ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA = 1
        private const val ACTIVE_SCROLL_LIST_COMMIT_DEFER_MESSAGE_DELTA = 1
        private const val ACTIVE_SCROLL_FLOW_DEFER_MESSAGE_DELTA = 120
        private const val DEFERRED_STARTUP_UNLOCK_DELAY_MS = 1200L
        private const val STARTUP_LAST_SEEN_MARQUEE_DELAY_MS = 2000
        private const val GROUP_PRESENCE_EMPTY_FALLBACK_GRACE_MS = 1500L
        private const val CHAT_AVATAR_SIZE_DP = 40f
        private const val MEDIA_DEBUG_TAG = "GlyphDebug"
        private const val MESSAGE_TRACE_TAG = "MessageTraceUi"
        private const val REQ_LIVE_AUDIO_NOTIFICATION = 2001
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_OTHER_USER_ID = "other_user_id"
        private const val EXTRA_OTHER_USERNAME = "other_username"
        private const val EXTRA_OTHER_USER_AVATAR = "other_user_avatar"
        private const val EXTRA_FORCE_SCROLL_TO_BOTTOM = "force_scroll_to_bottom"
        private const val EXTRA_WT_SESSION_ID = "wt_session_id"
        private const val EXTRA_WT_INITIATOR_NAME = "wt_initiator_name"
        const val EXTRA_START_RECORDING = "start_recording"
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_SHARED_URIS = "shared_uris"
        const val EXTRA_SHARED_MIME_TYPE = "shared_mime_type"
        const val EXTRA_SHARED_LINK_PREVIEW_TITLE = "shared_link_preview_title"
        const val EXTRA_SHARED_LINK_PREVIEW_DOMAIN = "shared_link_preview_domain"
        const val EXTRA_SHARED_LINK_PREVIEW_THUMBNAIL_URL = "shared_link_preview_thumbnail_url"
        /** When true, open the map video camera immediately (tapped 'Yes' on camera-invite notification). */
        const val EXTRA_ENABLE_CAMERA = "enable_camera"
        /** requestId of the camera invite so ChatActivity can write the RTDB acceptance signal. */
        const val EXTRA_CAMERA_REQUEST_ID = "camera_request_id"
        private const val TYPING_INDICATOR_TRANSITION_MS = 280L
        private const val TYPING_INDICATOR_FALLBACK_HEIGHT_DP = 42f
        private const val PREFILL_SETTLE_WINDOW_MS = 150L

        // Telegram loads 25 messages initially; we load 40 for a slightly larger buffer
        // while keeping the initial Room query fast. Pagination handles older loads.
        // Reduced from 200 to 40 — the live flow starts in onStart and the prefill
        // cache provides instant content, so the initial window only needs to cover
        // the first screenful plus a small scroll buffer.
        private const val INITIAL_MESSAGE_WINDOW = 40
        private const val OLDER_MESSAGE_PAGE_SIZE = 50
        // Minimum interval between messageWindowLimit growths. loadOlderMessages() is invoked once
        // per scroll frame while the user is near a boundary, so without throttling it would grow the
        // window ~60x/s and flatMapLatest would cancel+re-run the Room query just as often (query
        // thrash). 60ms bounds that to ~16 growths/s — fast enough to keep a fast fling fed
        // (each growth adds a full page of older rows) while letting each Room query + DiffUtil run
        // to completion. The very first growth of a session is always allowed (see loadOlderMessages).
        // Reduced from 80ms to 60ms for faster response during rapid scrolls.
        private const val PAGINATION_COOLDOWN_MS = 60L
        // Trigger distance: prefetch older history when the first visible item is within
        // this many rows of the very start (newest messages). Raised from 20 to 40 so
        // pagination fires well before the user reaches the boundary — prevents the
        // scroll-stopping "top wall" effect where the user scrolls through all loaded
        // items before the expanded Room query returns.
        private const val LOAD_OLDER_THRESHOLD = 40
        // Maximum trigger distance for fast-fling scenarios: when the last visible item
        // is within this many rows of the oldest loaded message. Raised from 80 to 120
        // so fast flings trigger pagination much earlier, keeping the buffer full.
        private const val LOAD_OLDER_THRESHOLD_MAX = 120
        // Load more pages per pagination trigger. Raised from 2 to 3 so each trigger
        // brings in 150 older messages (3 × 50) instead of 100, reducing the number
        // of flatMapLatest cancellations and keeping the buffer deeper.
        private const val MAX_OLDER_PAGES_PER_LOAD = 3
        // After each page load, keep at least this many rows ahead of the user's scroll
        // position. Raised from 50 to 80 so continuous prefetch maintains a deeper buffer
        // — a fast fling at 1000px/s (typical) would exhaust 50 rows in ~2s but 80 rows
        // buys ~3.2s, comfortably covering the Room query + DiffUtil window.
        private const val OLDER_PREFETCH_KEEP_AHEAD_ROWS = 80

        fun newIntent(
            context: Context,
            chatId: String,
            otherUserId: String,
            otherUsername: String = "",
            otherUserAvatar: String = "",
            startRecording: Boolean = false,
            forceScrollToBottom: Boolean = false,
            wtSessionId: String? = null,
            wtInitiatorName: String? = null,
            enableCamera: Boolean = false,
            cameraRequestId: String? = null
        ): Intent {
            return Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_OTHER_USER_ID, otherUserId)
                putExtra(EXTRA_OTHER_USERNAME, otherUsername)
                putExtra(EXTRA_OTHER_USER_AVATAR, otherUserAvatar)
                if (startRecording) putExtra(EXTRA_START_RECORDING, true)
                if (forceScrollToBottom) putExtra(EXTRA_FORCE_SCROLL_TO_BOTTOM, true)
                if (wtSessionId != null) putExtra(EXTRA_WT_SESSION_ID, wtSessionId)
                if (wtInitiatorName != null) putExtra(EXTRA_WT_INITIATOR_NAME, wtInitiatorName)
                if (enableCamera) putExtra(EXTRA_ENABLE_CAMERA, true)
                if (cameraRequestId != null) putExtra(EXTRA_CAMERA_REQUEST_ID, cameraRequestId)
            }
        }
    }

    private enum class IdleTaskPriority {
        HIGH,
        MEDIUM,
        LOW
    }

    private data class IdleScheduledTask(
        val key: String,
        val priority: IdleTaskPriority,
        val delayMs: Long,
        val enqueuedAtMs: Long,
        val action: suspend () -> Unit
    )
    
    // Block state - observable from Compose
    private val _blockStatus = mutableStateOf(com.glyph.glyph_v3.data.repo.BlockStatus.NOT_BLOCKED)
    private var blockStatusJob: Job? = null
    private var didPrimeInitialBlockUi = false

    // ── Google Maps background feature ─────────────────────
    private var mapBackgroundManager: com.glyph.glyph_v3.ui.chat.map.MapBackgroundManager? = null
    private var interactiveMapButtonBaseMarginPx: Int = -1
    private var mapVideoSessionManager: com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager? = null
    private var isUsingSharedMapVideoManager: Boolean = false
    private var pendingMapVideoEnableAfterPermissions: Boolean = false
    private var pendingMapVideoEnableShouldSendInvite: Boolean = true
    private var isInteractiveQuickReplyOverlayActive: Boolean = false
    private var recyclerOverlayDefaultsCaptured = false
    private var recyclerDefaultPaddingStartPx: Int = 0
    private var recyclerDefaultPaddingTopPx: Int = 0
    private var recyclerDefaultPaddingEndPx: Int = 0
    private var recyclerDefaultPaddingBottomPx: Int = 0
    private var quickReplyOverlayGestureRoutedToRecycler: Boolean = false
    private var interactiveMapVisibleDockRect: Rect? = null

    /**
     * Receives the local broadcast sent by [RoutingNotificationBroadcastReceiver] when the
     * "Stop Navigation" button in the notification is tapped. Propagates the stop to the
     * in-app [MapBackgroundManager] so that [RoutingManager.uiState.isNavigating] is cleared
     * and the foreground service is not accidentally restarted on the next location tick.
     */
    private val stopNavigationLocalReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != com.glyph.glyph_v3.ui.chat.map.routing.RoutingNotificationManager.ACTION_STOP_NAVIGATION_LOCAL) return
            val receivedChatId = intent.getStringExtra(
                com.glyph.glyph_v3.ui.chat.map.routing.RoutingNotificationManager.EXTRA_CHAT_ID
            )
            // Only react if the broadcast is for the chat currently open in this Activity.
            if (receivedChatId == null || receivedChatId != chatId) return
            mapBackgroundManager?.stopNavigation()
        }
    }

    /** Clears the in-map camera invite prompt when the user taps "No" in the notification. */
    private val cameraInviteDismissReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != com.glyph.glyph_v3.data.service.NotificationActionReceiver.ACTION_DISMISS_CAMERA_INVITE_LOCAL) return
            val receivedChatId = intent.getStringExtra(
                com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_CHAT_ID
            )
            if (receivedChatId != null && receivedChatId == chatId) {
                mapVideoSessionManager?.dismissIncomingCameraInvite()
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            ensureMapBackgroundManagerInitialized(reason = "location_permission_granted")?.enableMapBackground()
            setupMapComposeViews()
        } else {
            Toast.makeText(this, "Location permission required for map background", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Live Location — permission + GPS settings launchers ────────────────
    /** True when the user was directed to Location Settings to turn GPS on for live sharing. */
    private var pendingLiveLocationAfterGps = false
    private var pendingLiveLocationCameraDurationMs: Long? = null

    /** Permission launcher dedicated to the live location flow. */
    private val liveLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            proceedWithLiveLocationCheck()
        } else {
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                showLocationPermissionRationaleDialog()
            } else {
                showLocationPermissionDeniedDialog()
            }
        }
    }

    /** Opens the system Location Settings or App Settings and waits for the user to return. */
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result handled in onResume */ }

    private val liveLocationCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val durationMs = pendingLiveLocationCameraDurationMs
        pendingLiveLocationCameraDurationMs = null
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && micGranted && durationMs != null) {
            startLiveLocationSharing(durationMs, shareCamera = true)
        } else if (!cameraGranted && !micGranted) {
            Toast.makeText(this, "Camera and microphone permissions are required to share your live camera feed", Toast.LENGTH_SHORT).show()
        } else if (!cameraGranted) {
            Toast.makeText(this, "Camera permission required to share your live camera feed", Toast.LENGTH_SHORT).show()
        } else if (!micGranted) {
            Toast.makeText(this, "Microphone permission required to share live camera audio", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Monitors changes to the device's location providers while live sharing is active.
     * When GPS/network location is turned off, stops live sharing and alerts the user.
     */
    private val locationProviderChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.location.LocationManager.PROVIDERS_CHANGED_ACTION) return
            val mgr = mapBackgroundManager ?: return
            if (!mgr.isLiveSharing) return

            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val isGpsEnabled =
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled) {
                mgr.stopLiveSharing()
                updateLiveBannerVisibility()
                showGpsDisabledDuringLiveSharingDialog()
            }
        }
    }
    
    private var cameraPhotoUri: Uri? = null
    private var cameraVideoUri: Uri? = null
    private var recordingStartTime = 0L
    
    private lateinit var soundPool: android.media.SoundPool
    private var soundSentId: Int = 0
    private var soundReceivedId: Int = 0
    private var soundSwipeId: Int = 0
    private var isSoundPoolReleased: Boolean = false
    private var isSendState = false
    private var enterToSendEnabled = false
    private var lastMessageId: String? = null
    private var replyToMessage: Message? = null
        set(value) {
            field = value
            replyMessageState.value = value
        }
    private val replyMessageState = mutableStateOf<Message?>(null)
    private var editingMessage: Message? = null
        set(value) {
            field = value
            editingMessageState.value = value
        }
    private val editingMessageState = mutableStateOf<Message?>(null)
    private var pendingSharedLinkPreview: LinkPreviewData? = null
    private var linkPreviewResolveJob: Job? = null
    private var draftTextBeforeEdit: String? = null
    private var draftSaveJob: Job? = null
    private var lastSavedDraft: String = ""

    private var chatWallpaperTarget: CustomTarget<Drawable>? = null
    private var appliedWallpaperAssetPath: String? = null

    // Deterministic scroll requests: set intent, execute only after list commit + layout.
    private var pendingScrollToBottomOnNextListCommit: Boolean = false
    private var scrollToBottomRequestToken: Int = 0
    private lateinit var scrollFabController: ScrollToBottomFabController
    
    private var avatarSyncJob: Job? = null

    private var interactiveOverlayFadeApplied: Boolean = false
    // Tracks the newest (tail) message id last seen by the scroll-FAB unread counter. The FAB
    // badge must count ONLY genuinely new messages appended at the tail — never older history
    // paged in at the top by windowed pagination (which also grows the list size).
    private var lastFabNewestMessageId: String? = null

    // Prevent show/hide flicker when user taps the FAB and a smooth scroll triggers callbacks.
    private var isKeyboardAnimating = false
    // Compose-observable mirror of isKeyboardAnimating for ChatInputArea.suppressAnimations.
    // Updated synchronously in onKeyboardAnimationChanged so Compose sees the same value.
    private val isKeyboardAnimatingState = mutableStateOf(false)
    private var keyboardListCommitGateActive = false
    private var keyboardListCommitSettledSignal = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var suppressScrollFabUntilAtBottom: Boolean
        get() = if (::scrollFabController.isInitialized) scrollFabController.suppressUntilAtBottom else false
        set(value) {
            if (::scrollFabController.isInitialized) {
                scrollFabController.suppressUntilAtBottom = value
            }
        }
    private val unreadCount: Int
        get() = if (::scrollFabController.isInitialized) scrollFabController.unreadCount else 0

    // Tracks whether the user has intentionally scrolled upward to read history.
    // While true, programmatic auto-scroll to bottom is suppressed so older messages
    // remain visible. Reset when the user scrolls back near the bottom or sends a message.
    private var userHasScrolledUp: Boolean = false

    private var typingIndicatorHeightPx: Int = 0

    private var isAccessoryIconsCollapsed: Boolean = false

    // Emoji/GIF/Sticker picker
    private lateinit var keyboardHeightProvider: KeyboardHeightProvider
    private var isPickerMode: Boolean = false

    private val zoneId: ZoneId by lazy(LazyThreadSafetyMode.NONE) { ZoneId.systemDefault() }

    private val dateFormatter: DateTimeFormatter by lazy(LazyThreadSafetyMode.NONE) {
        DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.getDefault())
    }

    private val deleteForAllWindowMs: Long = TimeUnit.HOURS.toMillis(48)
    private val editWindowMs: Long = TimeUnit.HOURS.toMillis(1)

    // Gallery picker for multiple photos and videos
    private val pickMultipleMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleMultipleMediaUris(uris)
        }
    }

    // Single media picker (fallback)
    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleMediaUri(uri)
        }
    }

    // Camera photo capture
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraPhotoUri != null) {
            sendImage(cameraPhotoUri!!)
        }
    }

    // Camera video capture
    private val takeVideoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && cameraVideoUri != null) {
            sendVideo(cameraVideoUri!!)
        }
    }

    // Permission request for camera
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
             Toast.makeText(this, "Permission granted. Hold to record.", Toast.LENGTH_SHORT).show()
        } else {
             Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val mapVideoPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (pendingMapVideoEnableAfterPermissions) {
            pendingMapVideoEnableAfterPermissions = false
            if (cameraGranted && micGranted) {
                val manager = refreshVideoSessionManagerBinding()
                manager?.setEnabled(true)
                if (pendingMapVideoEnableShouldSendInvite) {
                    val senderName = currentUserUsername.takeIf { it.isNotBlank() }
                        ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                            ?.takeIf { it.isNotBlank() }
                        ?: "Someone"
                    manager?.sendCameraInvite(senderName)
                }
                pendingMapVideoEnableShouldSendInvite = true
                setupMapComposeViews()
            } else if (!cameraGranted && !micGranted) {
                pendingMapVideoEnableShouldSendInvite = true
                Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_SHORT).show()
            } else if (!cameraGranted) {
                pendingMapVideoEnableShouldSendInvite = true
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            } else {
                pendingMapVideoEnableShouldSendInvite = true
                Toast.makeText(this, "Microphone permission required for map audio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchInAppCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // In-app camera launcher
    private val inAppCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uriString = data?.getStringExtra(com.glyph.glyph_v3.ui.camera.CameraActivity.RESULT_MEDIA_URI)
            val mediaType = data?.getStringExtra(com.glyph.glyph_v3.ui.camera.CameraActivity.RESULT_MEDIA_TYPE) ?: "image/*"
            val caption = data?.getStringExtra(com.glyph.glyph_v3.ui.camera.CameraActivity.RESULT_CAPTION) ?: ""
            val isVideoNote = data?.getBooleanExtra(com.glyph.glyph_v3.ui.camera.CameraActivity.RESULT_IS_VIDEO_NOTE, false) ?: false
            
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                if (mediaType.startsWith("video/")) {
                    if (isVideoNote) {
                        sendVideoNoteDirectly(uri)
                    } else {
                        sendVideo(uri, caption)
                    }
                } else {
                    sendImage(uri, caption)
                }
            }
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            // Take persistent read permission so we can access the file during upload
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* not always grantable */ }
            // Resolve filename to show in the caption preview screen
            var fileName = "Document"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                }
            } catch (_: Exception) {}
            // Open the caption / preview screen before sending
            val intent = Intent(this, DocumentCaptionActivity::class.java).apply {
                putExtra(DocumentCaptionActivity.EXTRA_DOCUMENT_URI, uri.toString())
                putExtra(DocumentCaptionActivity.EXTRA_FILENAME, fileName)
                putExtra(DocumentCaptionActivity.EXTRA_OTHER_USERNAME, otherUsername.ifEmpty { "User" })
            }
            documentCaptionLauncher.launch(intent)
        }
    }

    /** Receives the confirmed URI + caption from [DocumentCaptionActivity] and sends the document. */
    private val documentCaptionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (isBlockedForSending()) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uriString = data.getStringExtra(DocumentCaptionActivity.RESULT_DOCUMENT_URI)
                ?: return@registerForActivityResult
            val caption = data.getStringExtra(DocumentCaptionActivity.RESULT_CAPTION) ?: ""
            val uri = Uri.parse(uriString)
            val id = chatId ?: return@registerForActivityResult

            if (isGroupChat) {
                lifecycleScope.launch {
                    groupRepository.sendGroupDocumentMessage(
                        chatId = id,
                        documentUri = uri,
                        caption = caption
                    )
                }
                launchNextPendingSharedDocument()
                return@registerForActivityResult
            }

            val otherId = otherUserId ?: return@registerForActivityResult
            lifecycleScope.launch {
                repository.sendDocumentMessage(
                    chatId = id,
                    documentUri = uri,
                    otherUserId = otherId,
                    otherUsername = otherUsername.ifEmpty { "Unknown" },
                    otherUserAvatar = otherUserAvatar,
                    caption = caption
                )
            }
            launchNextPendingSharedDocument()
        } else {
            pendingSharedDocuments.clear()
        }
    }

    private data class SharedDocumentPayload(
        val uri: Uri,
        val fileName: String,
        val initialCaption: String
    )

    private val pendingSharedDocuments = ArrayDeque<SharedDocumentPayload>()

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            Toast.makeText(this, "Audio selected: ${uri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            // TODO: Implement audio sending
        }
    }

    // Contact picker
    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        if (uri != null) {
            handleContactUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this, deepDark = true)
        StartupTrace.logStage("chat_onCreate_start")
        chatOpenStartElapsedMs = SystemClock.elapsedRealtime()
        
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Telegram-style instant settle: replace the platform default activity
        // slide (100% travel / 280ms) with a short 15% / 180ms decelerate tween.
        // The chat list beneath gets no exit anim, so it stays visible under the
        // incoming screen — matching Telegram's "old view remains" feel. The
        // close direction is mirrored in finish().
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.chat_open_enter, 0)
        selectionToolbarBinding = binding.selectionToolbar
        scrollController = ChatScrollController(
            chatIdProvider = { chatId },
            onFirstScrollFinished = { reason ->
                deferredStartupUnlockJob?.cancel()
                unlockDeferredStartupWork(reason = reason)
            },
            scrollWorkProvider = {
                if (::chatAdapter.isInitialized) chatAdapter.snapshotScrollWork() else null
            },
            poolSummaryProvider = { poolOccupancySummary() },
            windowSizeProvider = { if (::chatAdapter.isInitialized) chatAdapter.itemCount else 0 },
            startupUnlockedProvider = { deferredStartupWorkUnlocked },
            idleQueuePausedProvider = { idleQueuePaused },
            openElapsedMsProvider = { chatOpenStartElapsedMs }
        )
        keyboardController = KeyboardController(
            binding = binding,
            dpToPx = ::dpToPx,
            keyboardHeightProvider = { if (::keyboardHeightProvider.isInitialized) keyboardHeightProvider else null },
            isPickerModeProvider = { isPickerMode },
            isAiComposerVisibleProvider = {
                ::aiComposerManager.isInitialized && aiComposerManager.uiState.value !is AiComposerUiState.Hidden
            },
            isAtBottomProvider = { isRecyclerAtBottom() },
            itemCountProvider = { if (::chatAdapter.isInitialized) chatAdapter.itemCount else 0 },
            onKeyboardAnimationChanged = { animating ->
                isKeyboardAnimating = animating
                isKeyboardAnimatingState.value = animating // Sync Compose-observable state
                if (animating && ::chatAdapter.isInitialized) chatAdapter.dbgImeAnimating = true
                if (animating) {
                    markKeyboardListCommitGateActive(reason = "keyboard_animating")
                    pauseIdleTaskQueue(reason = "keyboard_animating")
                    cancelPendingScrollIdleMediaUpgrade()
                    // Hold visible GIFs/stickers/animated media on their current frame for the
                    // ~250ms IME slide. Each animated drawable otherwise decodes + uploads a new
                    // frame to the RenderThread every vsync, which competes with the per-frame
                    // chatContentContainer.translationY for the frame budget and produces the
                    // occasional flicker/stutter that scales with the number of on-screen GIFs.
                    // stop() holds the current frame (no reset), so resume is seamless.
                    setVisibleAnimatedMediaRunning(running = false)
                    // Pause the Activity-scoped Glide RequestManager for the duration of the
                    // slide. When the keyboard is opened IMMEDIATELY after chat open, the chat is
                    // still settling: recent-message image/GIF/sticker loads are in flight, and
                    // each onResourceReady fires a placeholder->drawable swap + bitmap upload +
                    // invalidate on the main thread. Those deliveries land on the first 2-3 IME
                    // animation frames and blow the frame budget, so translationY lurches (the
                    // logged 0 -> -93 -> -363 jump) instead of easing — the visible "flash" that
                    // scales with the number of GIFs/stickers and disappears once the chat has
                    // settled (~1s). pauseRequests() holds not-yet-completed loads without
                    // touching already-painted images, freeing the budget for a clean slide.
                    pauseChatImageRequestsForIme()
                } else {
                    // GIF/sticker restart is intentionally deferred to onAnimatedMediaReady,
                    // which fires from the PreDraw listener AFTER translationY=0f is committed.
                    // Restarting here would let GIF frame invalidations race against the
                    // pending translationY reset, producing one visible wrong frame on the
                    // first keyboard open (and any open where the layout commits mid-frame).
                    resumeIdleTaskQueue(reason = "keyboard_idle")
                }
            },
            onAnimatedMediaReady = {
                // Belt-and-suspenders guard: the epoch check in KeyboardController already
                // discards stale PreDraw listeners (rapid open/close), but if somehow
                // isKeyboardAnimating is still true here a new animation has started and
                // restarting GIFs now would cause them to run during the animation slide.
                if (!isKeyboardAnimating) {
                    setVisibleAnimatedMediaRunning(running = true)
                } else {
                    Log.d(KeyboardController.FLASH_TAG, "onAnimatedMediaReady: GIF restart skipped — new animation already in progress")
                }
                // Resume the Glide RequestManager paused for the slide. This fires from the
                // PreDraw listener AFTER translationY=0f is committed, so held loads deliver
                // into a settled layout — no wrong frame. If a new animation is already in
                // progress its own onPrepare re-paused requests, so resuming here is harmless
                // (idempotent) and the next onAnimatedMediaReady resumes again for the latest.
                resumeChatImageRequestsForIme()
                markKeyboardListCommitGateSettled(reason = "keyboard_settled_predraw")
                if (::chatAdapter.isInitialized) chatAdapter.dbgImeAnimating = false
            },
            onRequestBottomAnchor = { requestScrollToBottomAfterNextPreDraw() },
            onFinalizeScroll = { count ->
                // Called from rv.post {} inside PreDraw-A \u2014 after the keyboard-end traversal's
                // draw is committed. Runs ensureLastItemFullyVisibleAboveBottomUi and updates
                // the scroll FAB without touching rv.requestLayout/invalidate, so no extra
                // traversal or detach-scrap cycle is introduced.
                if (count > 0) ensureLastItemFullyVisibleAboveBottomUi(count - 1)
                scheduleScrollFabUpdate()
            },
            onHideEmojiPicker = { hideEmojiPicker() },
            onInteractiveMapInsetChanged = { navBottom -> updateInteractiveMapButtonBottomMargin(navBottomOverride = navBottom) },
            isChatInputFocused = { binding.etMessageInput.hasFocus() }
        )
        
        keyboardHeightProvider = KeyboardHeightProvider(this)
        setupWindowInsets()

        // Apply chat wallpaper early so it is visible immediately.
        applyChatWallpaperIfNeeded(force = true)

        // Sync accessory icons with the current input state once layout is ready.
        binding.layoutInput.doOnNextLayout {
            updateAccessoryIcons(isTyping = binding.etMessageInput.text.isNullOrEmpty().not(), animate = false)
        }
        
        // Handle back press to clear selection
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = binding.emojiPickerPanel
                
                // Priority 1: If expanded picker with keyboard visible → hide keyboard only
                if (panel.isPickerVisible && panel.panelMode == EmojiPickerPanel.PanelMode.EXPANDED) {
                    val insets = ViewCompat.getRootWindowInsets(binding.root)
                    val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    
                    if (imeBottom > 0) {
                        // Keyboard is visible → hide it, keep panel expanded
                        panel.clearAllSearchFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(panel.windowToken, 0)
                    } else {
                        // Keyboard hidden → close panel and restore keyboard
                        hideEmojiPicker()
                        binding.etMessageInput.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                } else if (panel.isPickerVisible) {
                    // Priority 2: Compact picker visible → hide it
                    hideEmojiPicker()
                } else if (editingMessage != null) {
                    cancelEditMode(restoreDraft = true)
                } else if (selectionManager.hasSelection()) {
                    selectionManager.clearSelection()
                } else {
                    // Priority 3: Default system back
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Get intent extras
        chatId = intent.getStringExtra(EXTRA_CHAT_ID)
        otherUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID)?.takeIf { it.isNotBlank() }
        otherUsername = intent.getStringExtra(EXTRA_OTHER_USERNAME) ?: ""
        otherUserAvatar = intent.getStringExtra(EXTRA_OTHER_USER_AVATAR) ?: ""
        // Phase 5: cheap chatId-prefix check identifies group chats. Must happen here so
        // setupHeader/sendMessage/typing branches see the correct value.
        isGroupChat = com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId ?: "")
        userNameState.value = otherUsername
        if (isGroupChat) {
            val localGroupAvatar = chatId?.let {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(it)
            }
            if (!localGroupAvatar.isNullOrBlank()) {
                otherUserAvatar = localGroupAvatar
            }
            userAvatarState.value = otherUserAvatar.takeIf { it.isNotBlank() }.orEmpty()
        } else {
            userAvatarState.value = ""
        }
        primeInitialBlockUiState()
        if (intent.getBooleanExtra(EXTRA_FORCE_SCROLL_TO_BOTTOM, false)) {
            userHasScrolledUp = false
            pendingScrollToBottomOnNextListCommit = true
            intent.removeExtra(EXTRA_FORCE_SCROLL_TO_BOTTOM)
        }

        // OPTIMIZATION: Try to get current user info synchronous from Auth immediately
        // This ensures outgoing avatar is visible immediately without waiting for DB fetch
        try {
            val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (authUser != null) {
                currentUserPhone = authUser.phoneNumber ?: ""
                currentUserAvatar = authUser.photoUrl?.toString() ?: ""
                
                // Try to use locally cached avatar for instant load
                try {
                    val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(authUser.uid)
                    if (localPath != null) {
                        currentUserAvatar = localPath
                    }
                } catch (e: Exception) {
                    Log.w("ChatActivity", "Failed to check local avatar cache", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Failed to get auth user info synchronously", e)
        }

        // OPTIMIZATION: Resolve other user's locally cached avatar synchronously so the
        // adapter has a real image source before the first ViewHolder bind, eliminating
        // the placeholder→avatar flash that occurs while fetchOtherUserInfo() runs.
        try {
            val otherId = otherUserId
            if (!otherId.isNullOrEmpty()) {
                val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherId)
                if (localPath != null) {
                    otherUserAvatar = localPath
                    userAvatarState.value = localPath
                    applyOtherUserAvatarVisibility()
                }
            }
        } catch (e: Exception) {
            Log.w("ChatActivity", "Failed to check other user local avatar cache", e)
        }

        chatId?.let(::consumePrefetcherRetainedHeaderAvatar)
        primeInitialHeaderIdentity()

        DraftMessageStore.init(applicationContext)

        val app = applicationContext as GlyphApplication
        app.ensureSharedRepositoryStartup(reason = "chat_activity_open")

        // Initialize repository
        val db = app.getOrCreateAppDatabase()
        repository = app.getOrCreateRealtimeRepository()
        
        // Initialize media transfer manager
        mediaTransferManager = MediaTransferManager.getInstance(applicationContext, db.messageDao())
        mediaController = MediaController(this, mediaTransferManager)

        setupHeader()
        setupSelectionToolbar()
        binding.btnCancelEdit.setOnClickListener {
            cancelEditMode(restoreDraft = true)
        }
        binding.btnDismissShareLinkPreview.setOnClickListener {
            clearPendingSharedLinkPreview()
        }

        // Pre-warm Glide memory cache for both avatars before any ViewHolder binds.
        // Compute eligibility synchronously (cheap cache/flag reads), then defer the actual
        // Glide RequestBuilder construction + decode submission to the first posted frame so
        // the RequestBuilder allocations do not pad the critical path before setupRecyclerView.
        val appCtx = applicationContext
        val avatarRequestSizePx = chatAvatarRequestSizePx()
        val cachedVisibility = otherUserId?.let { AvatarVisibilityRepository.getCachedProfilePhotoVisibility(it) }
        val shouldPreloadPeerAvatar = if (isGroupChat) {
            otherUserAvatar.isNotBlank()
        } else {
            cachedVisibility?.isVisible == true && !_blockStatus.value.isBlocked && otherUserAvatar.isNotBlank()
        }
        // Snapshot avatar paths for the post lambda (avoids capturing mutable vars directly).
        val peerAvatarSnapshot = otherUserAvatar
        val currentAvatarSnapshot = currentUserAvatar

        setupRecyclerView()

        // Defer avatar Glide preloads to the next frame. The decodes happen on Glide's
        // background pool regardless, but starting them one frame later removes ~2–4 ms of
        // RequestBuilder allocation work from the critical path that runs before first layout.
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            if (shouldPreloadPeerAvatar && peerAvatarSnapshot.isNotBlank()) {
                val peerAvatarModel: Any = java.io.File(peerAvatarSnapshot)
                    .takeIf { it.exists() && it.length() > 0 }
                    ?: peerAvatarSnapshot
                var peerRequest = Glide.with(appCtx)
                    .load(peerAvatarModel)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop()
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                if (peerAvatarModel is java.io.File) {
                    peerRequest = peerRequest.signature(
                        com.bumptech.glide.signature.ObjectKey(peerAvatarModel.lastModified())
                    )
                }
                peerRequest.preload(avatarRequestSizePx, avatarRequestSizePx)
            }
            if (currentAvatarSnapshot.isNotBlank()) {
                val currentAvatarModel: Any = java.io.File(currentAvatarSnapshot)
                    .takeIf { it.exists() && it.length() > 0 }
                    ?: currentAvatarSnapshot
                var currentRequest = Glide.with(appCtx)
                    .load(currentAvatarModel)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop()
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                if (currentAvatarModel is java.io.File) {
                    currentRequest = currentRequest.signature(
                        com.bumptech.glide.signature.ObjectKey(currentAvatarModel.lastModified())
                    )
                }
                currentRequest.preload(avatarRequestSizePx, avatarRequestSizePx)
            }
        }

        // Telegram pattern: content must be ready BEFORE the first RecyclerView layout
        // so the transition animation reveals already-rendered messages — no blank flash.
        //
        // We check the memory cache synchronously (HashMap lookup, ~1ms) and submit
        // immediately if hit. The cache snapshot already has precomputed text heights
        // (preserved in PersistedRenderItem), so the bind is O(1) per bubble.
        //
        // On cache miss, a coroutine loads from disk/Room. The RecyclerView may show
        // empty briefly — equivalent to Telegram's skeleton UI.
        restoreDraftForChat()
        setupTypingIndicator()
        setupScrollToBottomFab()
        setupComposeHeader()
        applyPastelSkyTheme()
        prefillRecentMessagesAsync()

        // Auto-start voice recording when launched from the unanswered call screen
        if (intent.getBooleanExtra(EXTRA_START_RECORDING, false)) {
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) startRecording()
            }, 400)
        }
        // Accept camera invite tapped from notification
        if (intent.getBooleanExtra(EXTRA_ENABLE_CAMERA, false)) {
            // Consume immediately so activity recreation (e.g. rotation) doesn't double-fire.
            intent.removeExtra(EXTRA_ENABLE_CAMERA)
            val cId = chatId ?: ""
            val senderUid = otherUserId ?: ""
            val reqId = intent.getStringExtra(EXTRA_CAMERA_REQUEST_ID) ?: ""
            intent.removeExtra(EXTRA_CAMERA_REQUEST_ID)
            // Dismiss the camera invite notification
            if (cId.isNotEmpty()) {
                val notifTag = "camera_invite_$cId"
                (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                    ?.cancel(notifTag, notifTag.hashCode())
            }
            // Write RTDB acceptance so the inviter's device receives the response
            if (cId.isNotEmpty() && senderUid.isNotEmpty() && reqId.isNotEmpty()) {
                val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val myName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    ?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
                if (myUid.isNotEmpty()) {
                    com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
                        .sendCameraInviteAcceptedCommand(
                            chatId = cId,
                            targetUserId = senderUid,
                            requestId = reqId,
                            responderUserId = myUid,
                            responderName = myName
                        )
                }
            }
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    // Enable map background first if not already on
                    val mapMgr = ensureMapBackgroundManagerInitialized(reason = "notification_camera_accept")
                    if (mapMgr != null && !mapMgr.isMapEnabled && mapMgr.hasLocationPermission) {
                        mapMgr.enableMapBackground()
                        setupMapComposeViews()
                        binding.ivChatWallpaper.animate().alpha(0f).setDuration(400).start()
                    }
                    enableMapVideoModeWithPermissions(sendInviteToOtherUser = false)
                }
            }, 700)
        }
        // Handle any content shared from the system share sheet (via ShareTargetActivity).
        // Runs after the first layout pass so the input field and fragment manager are ready.
        binding.root.post {
            if (!isFinishing && !isDestroyed) handlePendingSharedContent()
        }
        
        // DEFERRED: Initialize non-critical UI features after the first frame is drawn
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            
            setupAttachmentMenuOverlay()
            updateRegisteredUsersCache()
            
            scheduleDeferredFeatureWarmups(reason = "post_first_frame")
        }

        // If we don't have complete user info, fetch it.
        // Notification deep links commonly include username but omit avatar URL,
        // so we must fetch when either field is missing. Also fetch when phone
        // number is missing so ContactInfoScreen can display it.
        if (isGroupChat) {
            fetchOtherUserInfo()
        } else if (!otherUserId.isNullOrEmpty() && (otherUsername.isEmpty() || otherUserAvatar.isEmpty() || otherUserPhone.isEmpty())) {
            fetchOtherUserInfo()
        } else {
            updateHeaderInfo()
            ensureLocalChatExists()
        }

        observeContactCacheUpdates()

        fetchCurrentUserInfo()

        if (shouldRestoreMapBackgroundOnOpen()) {
            ensureMapBackgroundManagerInitialized(reason = "visible_restore")
        }

        binding.btnSend.setOnClickListener {
            if (isSendState) {
                sendMessage()
            }
            // Voice recording handled by OnTouchListener
        }

        binding.btnAdd.setOnClickListener {
            toggleAttachmentMenu()
        }

        binding.btnEmoji.setOnClickListener {
            toggleEmojiPicker()
        }

        // AI Composer button — opens AI enhancement sheet
        binding.btnAiComposer.setOnClickListener {
            ensureAiComposerWarmUpStarted(reason = "ai_button")
            val currentText = binding.etMessageInput.text?.toString() ?: ""
            if (currentText.isNotBlank() && ::aiComposerManager.isInitialized) {
                // 1. Professional dismissal of keyboard with modern API before showing sheet
                val controller = WindowCompat.getInsetsController(window, binding.root)
                controller.hide(WindowInsetsCompat.Type.ime())
                
                // 2. Clear focus from input to ensure keyboard doesn't pop back up
                binding.etMessageInput.clearFocus()

                // 3. Wait for keyboard to dismiss for a cleaner transition
                // Non-blocking wait using lifecycleScope for the premium UX requested
                lifecycleScope.launch {
                    val insets = ViewCompat.getRootWindowInsets(binding.root)
                    val isImeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    
                    if (isImeVisible) {
                        // Max wait 320ms (standard Android keyboard dismissal)
                        var timer = 0
                        while (timer < 20 && ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                            delay(16)
                            timer++
                        }
                    }
                    
                    // Show AI Composer after keyboard is hidden (or timeout)
                    aiComposerManager.openSheet(currentText)
                    showAiComposerOverlay()
                }
            }
        }

        // Camera icon in the input pill → launch in-app camera
        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchInAppCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Enter-to-send: observe setting and wire key listener.
        //
        // Apply the default (enter-to-send OFF) configuration EAGERLY and
        // SYNCHRONOUSLY here, before the user can tap the input. The setting is
        // read from DataStore asynchronously (disk I/O) and emits a few hundred
        // ms after the chat opens. Reassigning EditText.inputType tears down and
        // recreates the active InputConnection via InputMethodManager.restartInput().
        // If that async emission lands while the user is performing their first
        // tap, it swallows the pending showSoftInput and the keyboard fails to
        // appear on the first tap. By moving the field to its stable default
        // state up front, the async emission of the default value becomes a
        // no-op (see applyEnterToSendConfig's idempotency guard) and never
        // disturbs the IME connection.
        applyEnterToSendConfig(enterToSendEnabled)
        lifecycleScope.launch {
            com.glyph.glyph_v3.data.preferences.SettingsDataStore
                .enterToSendFlow(this@ChatActivity)
                .collect { enabled ->
                    enterToSendEnabled = enabled
                    applyEnterToSendConfig(enabled)
                }
        }

        binding.etMessageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND && enterToSendEnabled) {
                if (isSendState) sendMessage()
                true
            } else false
        }

        binding.etMessageInput.setOnKeyListener { _, keyCode, event ->
            if (enterToSendEnabled &&
                keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            ) {
                if (isSendState) sendMessage()
                true
            } else false
        }

        binding.etMessageInput.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val insets = ViewCompat.getRootWindowInsets(binding.root)
                val isImeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val isInteractiveQuickReply = mapBackgroundManager?.isInteractiveMode == true &&
                    binding.layoutInput.visibility == View.VISIBLE

                if (isInteractiveQuickReply && !isImeVisible) {
                    binding.etMessageInput.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    binding.etMessageInput.post {
                        imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            false
        }

        // On first focus (e.g. initial tap after chat opens while the IME connection is still
        // being established), explicitly request the keyboard. The show MUST be issued after
        // the current focus pass completes — at the instant onFocusChange fires, the EditText's
        // InputConnection (startInput) has not yet been bound, so a show() requested in the same
        // frame is silently dropped (focus appears, keyboard never opens). Posting defers the
        // request to the next message-loop iteration, by which point the connection is live, so
        // the keyboard opens reliably on the very first tap. This is event ordering, not a timed
        // delay — there is no fixed wait.
        binding.etMessageInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // A keyboard SHOW animation is now imminent (the post below requests it). Pause
                // the idle task queue and cancel pending media upgrades NOW — not at onPrepare,
                // which is ~100-250ms later when the IME animation has already started. The
                // expensive one-time work that runs on the first chat open (pre-inflate of
                // RecyclerView ViewHolders) is chunked through the idle queue; if a single
                // ViewHolder inflation (a complex MessageItem layout costs ~15ms) is in flight
                // when the first animation begins, it blocks one vsync — the logged 18ms frame
                // that makes translationY lurch (-169 -> -363) and produces the one-time
                // first-open flicker. Pausing here lets the in-flight chunk drain during the IME
                // startup latency (before any animation frame must draw) and prevents new chunks
                // from starting, so the first slide runs on a clean main thread. Subsequent opens
                // were already smooth because this startup work had finished. Resume is handled by
                // onKeyboardAnimationChanged(false) after the animation settles.
                markKeyboardListCommitGateActive(reason = "keyboard_imminent")
                pauseIdleTaskQueue(reason = "keyboard_imminent")
                cancelPendingScrollIdleMediaUpgrade()
                view.postDelayed({
                    if (keyboardListCommitGateActive && !isKeyboardAnimating) {
                        markKeyboardListCommitGateSettled(reason = "keyboard_imminent_timeout")
                    }
                }, KEYBOARD_LIST_COMMIT_GATE_TIMEOUT_MS)
                view.post {
                    if (!view.hasFocus()) return@post
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
                }
            }
        }

        // Keep deferred startup work parked until first-scroll settles or a short timeout elapses.
        scheduleDeferredStartupUnlock(reason = "chat_open")
        scheduleIdleTask(
            key = "preinflate_view_holders",
            priority = IdleTaskPriority.HIGH,
            delayMs = 0L,
            reason = "startup_prefetch"
        ) {
            preInflateViewHoldersNow()
        }
        StartupTrace.logStage("chat_onCreate_end", "chatId=${chatId ?: "unknown"}")
    }

    /**
     * Applies the enter-to-send IME configuration to the message input.
     *
     * Idempotent by design: each property is only reassigned when it actually
     * differs from the field's current value. This matters because assigning
     * [EditText.setInputType] — even to the identical value — tears down and
     * recreates the active InputConnection (via [InputMethodManager.restartInput]).
     * That teardown is exactly what swallowed the user's first-tap keyboard
     * show right after opening the chat. Guarding the assignments lets the
     * asynchronous DataStore emission of the default value run as a pure no-op,
     * leaving any live IME connection untouched.
     *
     * When a genuine IME-contract change *is* required while the field is
     * focused, the connection is re-established cleanly with restartInput() so
     * the keyboard stays attached instead of being left half-restarted.
     */
    private fun applyEnterToSendConfig(enabled: Boolean) {
        val et = binding.etMessageInput
        val desiredInputType = if (enabled) {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val desiredImeOptions = if (enabled) {
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        } else {
            android.view.inputmethod.EditorInfo.IME_ACTION_NONE
        }

        val inputTypeChanged = et.inputType != desiredInputType
        val imeOptionsChanged = et.imeOptions != desiredImeOptions
        val maxLinesChanged = et.maxLines != 4

        // Nothing to change — never touch the InputConnection.
        if (!inputTypeChanged && !imeOptionsChanged && !maxLinesChanged) return

        if (inputTypeChanged) et.inputType = desiredInputType
        if (imeOptionsChanged) et.imeOptions = desiredImeOptions
        if (maxLinesChanged) et.maxLines = 4

        // Only an inputType/imeOptions change actually restarts the connection;
        // if that happened while focused, re-establish it so a pending or active
        // keyboard request survives instead of being dropped mid-restart.
        if ((inputTypeChanged || imeOptionsChanged) && et.hasFocus()) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.restartInput(et)
        }
    }

    private fun markKeyboardListCommitGateActive(reason: String) {
        if (!keyboardListCommitGateActive) {
            keyboardListCommitGateActive = true
            if (keyboardListCommitSettledSignal.isCompleted) {
                keyboardListCommitSettledSignal = CompletableDeferred()
            }
            StartupTrace.logStage(
                "chat_keyboard_commit_gate_active",
                "chatId=${chatId ?: "unknown"} reason=$reason"
            )
        }
    }

    private fun markKeyboardListCommitGateSettled(reason: String) {
        if (!keyboardListCommitGateActive && keyboardListCommitSettledSignal.isCompleted) return
        keyboardListCommitGateActive = false
        if (!keyboardListCommitSettledSignal.isCompleted) {
            keyboardListCommitSettledSignal.complete(Unit)
        }
        StartupTrace.logStage(
            "chat_keyboard_commit_gate_settled",
            "chatId=${chatId ?: "unknown"} reason=$reason"
        )
    }

    private suspend fun awaitKeyboardSettledBeforeLargeListCommitIfNeeded(
        targetList: List<ChatListItem>,
        source: String,
        itemDelta: Int,
        messageDelta: Int
    ) {
        if (!keyboardListCommitGateActive && !isKeyboardAnimating) return

        val rv = binding.recyclerViewMessages
        val signal = keyboardListCommitSettledSignal
        val lm = rv.layoutManager as? LinearLayoutManager
        StartupTrace.logStage(
            "chat_list_commit_keyboard_deferred",
            "chatId=${chatId ?: "unknown"} source=$source targetItems=${targetList.size} itemDelta=$itemDelta messageDelta=$messageDelta first=${lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION} animating=$isKeyboardAnimating"
        )

        withTimeoutOrNull(KEYBOARD_LIST_COMMIT_GATE_TIMEOUT_MS) {
            signal.await()
        }

        val resumedLm = rv.layoutManager as? LinearLayoutManager
        StartupTrace.logStage(
            "chat_list_commit_keyboard_resumed",
            "chatId=${chatId ?: "unknown"} source=$source targetItems=${targetList.size} first=${resumedLm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${resumedLm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION} animating=$isKeyboardAnimating gate=$keyboardListCommitGateActive"
        )
    }

    private suspend fun preInflateViewHoldersNow() {
        if (!::recyclerCoordinator.isInitialized) return
        val startNs = System.nanoTime()
        recyclerCoordinator.preInflateViewHoldersNow(
            isFinishingProvider = { isFinishing || isDestroyed },
            shouldPauseProvider = { idleQueuePaused || frameBudgetGuard.lastFrameOverBudget || chatAdapter.isScrolling }
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        GlyphPerf.log(
            "POOL WARM duration=${elapsedMs}ms " +
                "${SystemClock.elapsedRealtime() - chatOpenStartElapsedMs}ms after open " +
                "stage=${recyclerCoordinator.currentWarmStage.name} " +
                "pool=[${poolOccupancySummary()}]"
        )
        logPoolHealth("after_warm_${elapsedMs}ms")

        // If warming was interrupted before STAGE3_FULL completion, reschedule so it
        // can continue when the queue resumes (after scroll ends or frame budget recovers).
        // BUT: if no progress was made (duration < 5ms), add a backoff delay to prevent
        // the tight reschedule loop observed in logs (hundreds of 0ms warm cycles flooding
        // the main thread with no actual VH creation).
        if (!isFinishing && !isDestroyed && recyclerCoordinator.isPoolBelowFullTargets()) {
            val backoffMs = if (elapsedMs < 5) POOL_WARM_NO_PROGRESS_BACKOFF_MS else 0L
            if (backoffMs > 0) {
                GlyphPerf.log(
                    "POOL WARM backing off ${backoffMs}ms — no progress made " +
                        "stage=${recyclerCoordinator.currentWarmStage.name}"
                )
            }
            scheduleIdleTask(
                key = "preinflate_view_holders",
                priority = IdleTaskPriority.LOW, // Downgrade priority when rescheduling
                delayMs = backoffMs,
                reason = "warm_resume"
            ) {
                preInflateViewHoldersNow()
            }
        }
    }

    private fun scheduleDeferredStartupUnlock(reason: String) {
        deferredStartupUnlockJob?.cancel()
        deferredStartupUnlockJob = lifecycleScope.launch {
            delay(DEFERRED_STARTUP_UNLOCK_DELAY_MS)
            if (!firstScrollTrackingActive && !firstScrollMetricsLogged) {
                unlockDeferredStartupWork(reason = "${reason}_timeout")
            }
        }
    }

    private var secondaryFeaturesBootstrapped = false

    private fun clearChatNotificationsAfterLaunch() {
        val openedChatId = chatId ?: return
        if (isFinishing || isDestroyed) return
        try {
            UnreadMessageStore.init(applicationContext)
            UnreadMessageStore.clearMessages(openedChatId)

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val tag = ChatNotificationHelper.notificationTagForChat(openedChatId)
            nm.cancel(tag, 0)
            nm.cancel(tag, 1)
        } catch (e: Exception) {
            Log.w("ChatActivity", "Failed to clear chat notifications", e)
        }
    }

    private fun ensureSoundPoolInitialized() {
        if (::soundPool.isInitialized || isSoundPoolReleased || isFinishing || isDestroyed) return
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_GAME)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = android.media.SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        soundSentId = soundPool.load(this, R.raw.message_sent, 1)
        soundReceivedId = soundPool.load(this, R.raw.message_received, 1)
        soundSwipeId = soundPool.load(this, R.raw.bubble_slide, 1)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        // Mark the window enter animation as complete. Secondary features (network
        // listeners, message sync, typing observer) are deferred until this point so
        // their startup work doesn't compete with the slide-in transition.
        enterAnimationCompleted = true
        StartupTrace.logStage(
            "chat_window_first_draw",
            "chatId=${chatId ?: "unknown"} elapsed=${SystemClock.elapsedRealtime() - chatOpenStartElapsedMs}ms"
        )
        ChatOpenTrace.event(chatId, "chat_transition_window_drawn")
        // Flush any scroll-deferred emission that was waiting for idle state.
        applyDeferredLargeFlowEmissionIfNeeded()
        triggerSecondaryFeaturesNow(reason = "enter_animation_complete")
    }

    override fun finish() {
        super.finish()
        // Mirror the open transition on the way out: short settle toward the
        // right with a fade, so the chat list beneath is revealed cleanly instead
        // of the platform's long default slide.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.chat_open_exit)
    }

    private fun triggerSecondaryFeaturesNow(reason: String) {
        if (secondaryFeaturesBootstrapped || isFinishing || isDestroyed) return
        secondaryFeaturesBootstrapped = true
        StartupTrace.logStage("chat_secondary_features_start", "reason=$reason")

        // Start network and database state listeners
        clearChatNotificationsAfterLaunch()
        startBlockStatusObservation()
        startOtherUserAvatarObservation()
        ensureExpressiveTypingInitialized(reason = "secondary_features_start")
        startEagerTypingObserver()
        repository.startIncomingMessageSync()
        loadMessages()
        startPresenceObservation()
        ensureSoundPoolInitialized()

        // Asynchronously prime network connection transports
        PresenceManager.primeTransport("chat_open")
        walkieTalkieManager.primeTransport("chat_open")
        CallSignalingRepository.primeTransport("chat_open")
        BuzzManager.primeTransport()

        setupTranslationFeature()
        setupBottomAnchoring()
        setupVoiceFeatures()

        val db = (applicationContext as com.glyph.glyph_v3.GlyphApplication).getOrCreateAppDatabase()
        startPinnedMessageBannerObservation(db)
        setupPinnedMessageBanner(db)
        startWalkieTalkieObservation()
        setupWalkieTalkieOverlay()

        // Trigger first sync/read check (idempotent helper)
        chatId?.let { cid ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { repository.markChatAsRead(cid) }
            }
        }

        StartupTrace.logStage("chat_secondary_features_end", "reason=$reason")

        // Safety net: if onEnterAnimationComplete is never called (no transition animation,
        // test environments, or very fast devices), ensure secondary features are unblocked.
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && !enterAnimationCompleted) {
                enterAnimationCompleted = true
                applyDeferredLargeFlowEmissionIfNeeded()
            }
        }, 500L)
    }

    private fun unlockDeferredStartupWork(reason: String) {
        if (deferredStartupWorkUnlocked) return
        deferredStartupWorkUnlocked = true
        GlyphPerf.log(
            "STARTUP UNLOCKED reason=$reason " +
                "${SystemClock.elapsedRealtime() - chatOpenStartElapsedMs}ms after open " +
                "pool=[${poolOccupancySummary()}]"
        )
        resumeIdleTaskQueue(reason = reason)
        maybeScheduleInitialLastSeenReveal(_reason = "startup_unlock_$reason")
        triggerSecondaryFeaturesNow(reason = "startup_unlock_$reason")
    }

    private fun applyChatWallpaperIfNeeded(force: Boolean) {
        // 1. Try immediate application from memory cache for zero-latency display
        val cachedPath = com.glyph.glyph_v3.utils.ChatWallpaperManager.getCachedWallpaperPath()
        val cachedDimming = com.glyph.glyph_v3.utils.ChatWallpaperManager.getCachedWallpaperDimming()

        if (cachedPath != null) {
            binding.wallpaperDimmingOverlay.alpha = cachedDimming
            if (force || cachedPath != appliedWallpaperAssetPath) {
                appliedWallpaperAssetPath = cachedPath
                binding.ivChatWallpaper.visibility = View.VISIBLE
                binding.chatContentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val cachedDrawable = com.glyph.glyph_v3.utils.ChatWallpaperManager
                    .getCachedWallpaperDrawable(resources, cachedPath)
                if (cachedDrawable != null) {
                    binding.ivChatWallpaper.setImageDrawable(cachedDrawable)
                } else {
                    val uri = com.glyph.glyph_v3.utils.ChatWallpaperManager.assetUri(cachedPath)
                    Glide.with(this)
                        .load(uri)
                        .centerCrop()
                        .into(binding.ivChatWallpaper)
                }
            }
        }

        // 2. Launch async check to ensure we have the latest from DataStore
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            val folder = ChatWallpaperManager.getEffectiveThemeFolder(this@ChatActivity)
            val resolvedAssetPath = ChatWallpaperManager.resolveWallpaperToApply(this@ChatActivity, folder)
            val dimming = ChatWallpaperManager.getWallpaperDimming(this@ChatActivity, folder)


            // Apply dimming
            binding.wallpaperDimmingOverlay.alpha = dimming

            if (isFinishing || isDestroyed) return@launch

            if (!force && resolvedAssetPath == appliedWallpaperAssetPath) return@launch
            appliedWallpaperAssetPath = resolvedAssetPath

            // Clear any previous pending load.
            chatWallpaperTarget?.let { Glide.with(this@ChatActivity).clear(it) }
            chatWallpaperTarget = null

            if (resolvedAssetPath.isNullOrBlank()) {
                binding.ivChatWallpaper.visibility = View.GONE
                // Fall back to the default gradient background if no wallpapers exist.
                val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this@ChatActivity)
                if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
                    binding.chatContentContainer.setBackgroundResource(R.drawable.bg_pastel_gradient)
                } else {
                    binding.chatContentContainer.setBackgroundResource(R.drawable.bg_glyph_gradient)
                }
                return@launch
            }

            // Show wallpaper image view
            binding.ivChatWallpaper.visibility = View.VISIBLE
            binding.chatContentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            val cachedDrawable = ChatWallpaperManager.getCachedWallpaperDrawable(resources, resolvedAssetPath)
            if (cachedDrawable != null) {
                binding.ivChatWallpaper.setImageDrawable(cachedDrawable)
                return@launch
            }

            val uri = ChatWallpaperManager.assetUri(resolvedAssetPath)
            
            val target = object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (isFinishing || isDestroyed) return
                    binding.ivChatWallpaper.setImageDrawable(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    binding.ivChatWallpaper.setImageDrawable(null)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    android.util.Log.e("ChatWallpaper", "Failed to load wallpaper: $uri")
                }
            }

            chatWallpaperTarget = target
            Glide.with(this@ChatActivity)
                .load(uri)
                .centerCrop()
                .into(target)
        }
    }

    /**
     * Grows the live message window by one page so older history is paged in from the
     * local DB. Called when the user scrolls near the top of the list. No-ops when a page
     * is already loading or there is nothing older left to load.
     */
    /**
     * Grows the live message window so older history is paged in from the local DB. Called
     * when the user scrolls near the top of the list. [pageCount] scales with scroll velocity
     * so a fast fling pulls a bigger chunk at once and stays ahead of the scroll. No-ops when a
     * page is already loading or there is nothing older left to load.
     */
    private var skipNextDefer = false

    /**
     * Loads older messages during scroll. Uses a TWO-PRONGED approach:
     *
     * 1. DIRECT PATH (immediate): queries Room on IO, processes on Default, prepends to
     *    the adapter on Main. This fills the gap within ~50ms so the user never hits a wall.
     *
     * 2. FLOW SYNC (eventual): grows messageWindowLimit so the live flow's next emission
     *    includes all loaded messages. DiffUtil sees identical items and no-ops.
     *
     * Without the direct path, flatMapLatest cancels the current Room query when the limit
     * changes, creating a 100-300ms gap where the adapter has no new items — the user hits
     * the end of loaded content and the scroll stops dead ("pagination wall").
     */
    private fun loadOlderMessages(pageCount: Int = 1) {
        if (!hasMoreOlderMessages) return

        val now = SystemClock.elapsedRealtime()
        if (lastPaginationFireElapsedMs != 0L &&
            now - lastPaginationFireElapsedMs < PAGINATION_COOLDOWN_MS
        ) {
            return
        }
        lastPaginationFireElapsedMs = now

        val isActivelyScrolling = chatAdapter.isScrolling ||
                                   binding.recyclerViewMessages.scrollState != RecyclerView.SCROLL_STATE_IDLE

        if (!isActivelyScrolling && isLoadingOlderMessages) return

        isLoadingOlderMessages = true
        olderLoadInFlight = true
        val pages = pageCount.coerceIn(1, MAX_OLDER_PAGES_PER_LOAD)
        val windowAfter = messageWindowLimit.value + OLDER_MESSAGE_PAGE_SIZE * pages

        // ── DIRECT PATH: load + prepend immediately, bypassing flow cancellation ──
        val id = chatId ?: return
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            try {
                val newMessages = withContext(Dispatchers.IO) {
                    repository.getRecentMessages(id, windowAfter)
                }
                if (newMessages.size <= currentMessages.size) return@launch

                // Only process the NEW (older) messages — the tail is unchanged
                val previousIds = currentMessages.mapTo(mutableSetOf()) { it.id }
                val onlyNew = newMessages.filter { it.id !in previousIds }
                if (onlyNew.isEmpty()) return@launch

                // Process new messages on background threads
                val newItems = withContext(Dispatchers.Default) {
                    onlyNew.forEach { it.warmUpForUi() }
                    val raw = processMessagesWithHeaders(onlyNew)
                    if (TextLayoutPrecomputer.isReady()) {
                        TextLayoutPrecomputer.precomputeForItems(raw)
                    } else raw
                }

                // Prepend to the adapter's current list on main thread
                val currentList = chatAdapter.currentList.toMutableList()
                // Remove typing indicator if present (it stays at the tail)
                val typingIdx = currentList.indexOfLast { it is ChatListItem.TypingIndicator }
                if (typingIdx >= 0) currentList.removeAt(typingIdx)

                // Prepend new (older) items at the beginning
                currentList.addAll(0, newItems)

                // Restore typing indicator at the tail
                if (typingIdx >= 0) {
                    currentList.add(ChatListItem.TypingIndicator())
                }

                // Update tracking state
                currentMessages = newMessages
                renderedMessageOrder = newMessages.map { it.id }
                chatAdapter.replyLookupMessages = newMessages
                updateDisplayedMessageIds(currentList)
                hasMoreOlderMessages = newMessages.size >= windowAfter

                // Submit the merged list — DiffUtil finds items unchanged, only new ones added
                chatAdapter.submitList(currentList) {
                    // Keep scroll position stable at the same content
                }
            } catch (_: Exception) {
                // Direct load failed — the flow sync path will eventually catch up
            } finally {
                isLoadingOlderMessages = false
                olderLoadInFlight = false
            }
        }

        // ── FLOW SYNC PATH: grow the limit so future emissions include all messages ──
        messageWindowLimit.value = windowAfter
        if (::scrollController.isInitialized) {
            scrollController.logPaginationFire(pages = pages, windowAfter = windowAfter)
        }
    }

    /**
     * One-time, idle pre-extension of the message window. Fired shortly after the first stable
     * render while the user is still pinned at the bottom. Growing the window here pre-pages a
     * cushion of older history into the buffer so the very first scroll-up reads from memory
     * instead of triggering a cold Room round-trip at the initial window boundary (which is what
     * caused the brief pause once the initial set was exhausted). The prepend is invisible: the
     * layout manager keeps the bottom anchor, so no scroll movement is seen.
     */
    private fun prefetchOlderWindowProactively() {
        // Pagination disabled — INITIAL_MESSAGE_WINDOW=500 loads full chat upfront
    }

    /**
     * Continuous keep-ahead prefetch. Called after every window emission settles. While the user
     * is scrolling up, if the first visible row is still within [OLDER_PREFETCH_KEEP_AHEAD_ROWS]
     * of the bottom (near position 0, i.e. just started scrolling up) OR the last visible row
     * is within that same distance of the absolute top of the loaded list, immediately page in
     * more history. The bottom-proximity gate handles the initial scroll-up prefetch; the
     * top-proximity gate ensures that a sustained fast fling is fed back-to-back and never hits
     * the top wall / stalls even after the user scrolls past position ~180-220. No-op when
     * the user is at/near the bottom (never scrolled up) so normal use does no extra work.
     */
    private fun maybeContinueOlderPrefetch() {
        if (!::binding.isInitialized || !::chatAdapter.isInitialized) return
        if (isFinishing || isDestroyed) return
        if (!hasMoreOlderMessages) return

        val rv = binding.recyclerViewMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return

        // Early exit: only prefetch when user has scrolled up from bottom
        if (!userHasScrolledUp) return

        val total = chatAdapter.itemCount
        if (total == 0) return

        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()

        // Distance from bottom (position 0 = newest/bottom)
        val distanceFromBottom = firstVisible

        // Distance from top (oldest loaded message)
        val rowsFromEnd = max(0, total - 1 - lastVisible)

        // Dual-proximity gate:
        // 1. Near bottom (just started scrolling up) → prefetch for smooth initial scroll-up
        // 2. Near top (approaching oldest loaded) → load more older history
        val nearBottom = distanceFromBottom <= OLDER_PREFETCH_KEEP_AHEAD_ROWS
        val nearTop = rowsFromEnd <= OLDER_PREFETCH_KEEP_AHEAD_ROWS

        if (nearBottom || nearTop) {
            loadOlderMessages(pageCount = 1)
        }
    }

    /**
     * Telegram-style async prefill. Called via recyclerView.post {} so it runs AFTER
     * onCreate returns and the activity transition animation has started. This means
     * the user sees the action bar + wallpaper + input bar immediately (like Telegram's
     * skeleton UI), and content paints a frame or two later.
     *
     * Key differences from the old sync approach:
     * - No ViewHolder pre-inflation (RecyclerView's pool handles this)
     * - No runBlocking text precomputation (async via coroutine after submit)
     * - Memory cache hit: submit instantly, precompute heights in background
     * - No cache: load from disk/Room on background threads
     */
    /**
     * Telegram-style prefill: submits cached content to the adapter BEFORE the
     * RecyclerView's first layout pass, so the transition animation reveals
     * already-rendered messages — no blank flash, no pop-in.
     *
     * Memory cache hit: submit synchronously (~5ms). Items already have precomputed
     * text heights (preserved in PersistedRenderItem), so the first bind is O(1).
     *
     * Cache miss: launch coroutine for disk/Room. The RecyclerView shows empty
     * briefly (equivalent to Telegram's skeleton UI) until content arrives.
     */
    /**
     * Telegram-style prefill with fade-in. Submits content BEFORE the RecyclerView
     * layout pass so the first frame reveals fully-rendered messages. On cache miss
     * (cold start), the RecyclerView fades in when content arrives — no empty flash.
     */
    private fun prefillRecentMessagesAsync() {
        val id = chatId ?: return
        if (isFinishing || isDestroyed) return
        StartupTrace.logStage("chat_prefill_start", "chatId=$id")

        // ── MEMORY CACHE (warm reopen) ──────────────────────────────────
        // HashMap lookup ~1ms. Submit synchronously so the RecyclerView has
        // data before its first layout. Snapshot items already have precomputed
        // text heights (PersistedRenderItem.premeasuredTextHeightPx).
        val memorySnapshot = MessageCacheManager.getSnapshot(id)
        if (memorySnapshot != null && memorySnapshot.listItems.isNotEmpty()) {
            StartupTrace.logStage("chat_prefill_source",
                "chatId=$id source=${memorySnapshot.source}_memory count=${memorySnapshot.recentMessages.size}")

            if (isGroupChat) primeGroupSenderCachesForMessages(memorySnapshot.recentMessages)
            val normalized = normalizePrefillListItems(memorySnapshot.listItems)
            chatAdapter.preCacheMediaHeights(memorySnapshot.recentMessages)
            if (::mediaController.isInitialized) {
                mediaController.tryFinalizeFirstPaintPreloads(memorySnapshot.recentMessages, timeoutMs = 30L)
            }
            submitPrefillAndShow(id, normalized, memorySnapshot.recentMessages,
                "${memorySnapshot.source}_memory")
            lifecycleScope.launch(Dispatchers.Default) {
                memorySnapshot.recentMessages.forEach { it.warmUpForUi() }
            }
            primeTextLayoutPrecomputerIfNeeded()
            return
        }

        // ── DISK CACHE — synchronous small-file read ─────────────────────
        // On cold start the memory cache is empty, but a disk snapshot from a
        // previous session is almost always present. File.readText() on a
        // ~50KB JSON file takes <5ms — cheap enough to run synchronously so
        // the RecyclerView's first layout has data (no empty flash).
        val diskSnapshot = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            MessageCacheManager.loadSnapshotFromDiskForPrefill(id)
        }
        if (diskSnapshot != null && diskSnapshot.listItems.isNotEmpty()) {
            StartupTrace.logStage("chat_prefill_source",
                "chatId=$id source=${diskSnapshot.source} count=${diskSnapshot.recentMessages.size}")
            if (isGroupChat) primeGroupSenderCachesForMessages(diskSnapshot.recentMessages)
            diskSnapshot.recentMessages.forEach { it.warmUpForUi() }
            chatAdapter.preCacheMediaHeights(diskSnapshot.recentMessages)
            submitPrefillAndShow(id, diskSnapshot.listItems, diskSnapshot.recentMessages,
                diskSnapshot.source)
            return
        }

        // ── COLD START (no cache at all) — Room DB query ─────────────────
        // The RecyclerView stays at alpha=0 until content arrives. The user
        // sees the action bar + wallpaper + input bar immediately, and the
        // message list fades in when Room returns. This is equivalent to
        // Telegram's skeleton UI.
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val recent = withContext(Dispatchers.IO) { repository.getRecentMessages(id, INITIAL_MESSAGE_WINDOW) }
            StartupTrace.logStage("chat_prefill_source", "chatId=$id source=room_recent count=${recent.size}")
            if (recent.isEmpty()) {
                binding.recyclerViewMessages.alpha = 1f
                return@launch
            }
            if (isGroupChat) primeGroupSenderCachesForMessages(recent)
            val listItems = withContext(Dispatchers.Default) {
                recent.forEach { it.warmUpForUi() }
                chatAdapter.preCacheMediaHeights(recent)
                val rawList = processMessagesWithHeaders(recent)
                if (TextLayoutPrecomputer.isReady()) TextLayoutPrecomputer.precomputeForItems(rawList)
                else rawList
            }
            submitPrefillAndShow(id, listItems, recent, "activity_prefill")
            MessagePreviewCacheManager.warmMessagesAsync(applicationContext,
                recent.takeLast(INITIAL_VISIBLE_WARMUP_COUNT * INITIAL_SCROLL_UP_WARMUP_MULTIPLIER))
        }
    }

    /** Submits the prefill list and fades the RecyclerView in (100ms). */
    private fun submitPrefillAndShow(
        id: String, items: List<ChatListItem>, messages: List<Message>, source: String
    ) {
        chatAdapter.isFirstLayout = true
        processedListItems = items
        currentMessages = messages
        chatAdapter.replyLookupMessages = messages
        renderedMessageOrder = messages.map { it.id }
        updateDisplayedMessageIds(items)
        chatAdapter.submitList(items) { scrollToBottomAfterNextLayout() }
        MessageCacheManager.putSnapshot(id, messages, items, source)
        messageWindowLimit.value = INITIAL_MESSAGE_WINDOW
        prefillTimestampMs = System.currentTimeMillis()

        // Consume retained media preloads that primeChatOpen submitted before
        // startActivity. These are already decoded — the first bind hits instantly.
        if (::mediaController.isInitialized) {
            mediaController.clearRetainedMediaPreloadFutures()
            mediaController.consumePrefetcherRetainedMediaPreloads(id)
        }

        // Telegram's needDelayOpenAnimation principle: keep the list invisible
        // (so it pre-renders — laid out + bound — while not drawn) and reveal it
        // only once the prefilled items are actually bound + laid out, so the
        // first visible frame already shows fully-rendered messages (no fade, no
        // pop-in). A 200ms fallback covers edge cases where a layout pass doesn't
        // fire promptly.
        scheduleContentReveal(source = source)
        binding.recyclerViewMessages.post {
            warmVisibleContentWindows(reason = "prefill_${source}")
            unlockDeferredStartupWork(reason = "cache_hit_first_frame")
            if (::scrollController.isInitialized) scrollController.markScrollReady()
        }
        primeTextLayoutPrecomputerIfNeeded()
    }

    // Guards the content reveal so it is scheduled and fired exactly once per open.
    private var contentRevealScheduled = false

    private fun scheduleContentReveal(source: String) {
        val rv = binding.recyclerViewMessages
        if (rv.alpha >= 0.99f) return // already visible (e.g. warm re-entry path)
        if (contentRevealScheduled) return
        contentRevealScheduled = true
        ChatOpenTrace.event(chatId, "chat_content_gate_ready", "source=$source")
        // Fire on the first layout AFTER the prefill commit, so bound content is
        // on screen before the reveal (no reveal-of-empty-then-pop).
        rv.doOnNextLayout {
            if (!isFinishing && !isDestroyed) revealMessageList("$source layout")
        }
        // Safety net: never let the list stay invisible past 200ms.
        rv.postDelayed({
            if (!isFinishing && !isDestroyed &&
                binding.recyclerViewMessages.alpha < 0.99f
            ) {
                revealMessageList("$source timeout")
            }
        }, 200L)
    }

    private fun revealMessageList(reason: String) {
        if (!contentRevealScheduled) return
        contentRevealScheduled = false
        val rv = binding.recyclerViewMessages
        if (rv.alpha >= 0.99f && rv.visibility == View.VISIBLE) return
        val elapsedAtReveal = SystemClock.elapsedRealtime() - chatOpenStartElapsedMs
        ChatOpenTrace.event(chatId, "chat_reveal_start", "reason=$reason elapsed=${elapsedAtReveal}ms")
        // Telegram principle: the list pre-rendered (laid out + bound) while it
        // was invisible, so reveal it INSTANTLY — no alpha fade. A fade would
        // reintroduce the "message list displays after a delay" feel; the first
        // visible frame here already shows fully-rendered messages.
        rv.animate().cancel()
        rv.visibility = View.VISIBLE
        rv.alpha = 1f
        ChatOpenTrace.event(
            chatId, "chat_reveal_end",
            "reason=$reason elapsed=${SystemClock.elapsedRealtime() - chatOpenStartElapsedMs}ms"
        )
    }

    /** Ensures TextLayoutPrecomputer has params captured for subsequent scrolls. */
    private fun primeTextLayoutPrecomputerIfNeeded() {
        if (TextLayoutPrecomputer.isReady()) return
        lifecycleScope.launch(Dispatchers.Main) {
            val sampleVh = chatAdapter.preWarmedViewHolder(1)
                ?: chatAdapter.preWarmedViewHolder(2)
            if (sampleVh != null) {
                val tv = sampleVh.itemView.findViewById<android.widget.TextView>(R.id.tvMessage)
                if (tv != null) {
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    TextLayoutPrecomputer.captureParams(tv)
                }
            }
        }
    }

    private fun toggleAttachmentMenu() {
        ensureAttachmentMenuInitialized(reason = "button_tap")
        if (binding.layoutAttachmentMenu.root.visibility == View.VISIBLE) {
            hideAttachmentMenu()
        } else {
            showAttachmentMenu()
        }
    }

    /**
     * Shows the AI Composer overlay for the XML-based layout path (non-Pastel themes).
     * Creates a temporary ComposeView dialog anchored to the activity.
     */
    private fun showAiComposerOverlay() {
        if (!::aiComposerManager.isInitialized) return

        val rootContainer = binding.root as? android.view.ViewGroup ?: return
        if (rootContainer.findViewWithTag<android.view.View>("AiComposerOverlay") != null) return

        val overlayView = androidx.compose.ui.platform.ComposeView(this).apply {
            tag = "AiComposerOverlay"
            setContent {
                com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                    val aiState by aiComposerManager.uiState.collectAsState()
                    val currentText = binding.etMessageInput.text?.toString() ?: ""

                    com.glyph.glyph_v3.ui.chat.composer.AiComposerSheet(
                        uiState = aiState,
                        currentText = currentText,
                        onActionSelected = { action ->
                            aiComposerManager.selectAction(action, currentText)
                        },
                        onLanguageSelected = { language ->
                            aiComposerManager.selectLanguageAndTranslate(language, currentText)
                        },
                        onToneSelected = { tone ->
                            aiComposerManager.selectToneAndAdjust(tone, currentText)
                        },
                        onReplaceText = { newText ->
                            binding.etMessageInput.setText(newText)
                            binding.etMessageInput.setSelection(newText.length)
                            aiComposerManager.dismiss()
                        },
                        onRetry = { aiComposerManager.retry(currentText) },
                        onBack = { aiComposerManager.navigateBack() },
                        onDismiss = { aiComposerManager.dismiss() }
                    )

                    androidx.compose.runtime.LaunchedEffect(aiState) {
                        if (aiState is com.glyph.glyph_v3.ui.chat.composer.AiComposerUiState.Hidden) {
                            (this@apply.parent as? android.view.ViewGroup)?.removeView(this@apply)
                        }
                    }
                }
            }
        }

        rootContainer.addView(
            overlayView,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun commitPrefillList(
        chatId: String,
        listItems: List<ChatListItem>,
        recentMessages: List<Message>,
        source: String
    ) {
        val normalizedListItems = normalizePrefillListItems(listItems)
        // Keep the INITIAL_MESSAGE_WINDOW (500) as the flow limit. The prefill shows a fast
        // cache snapshot; the live flow loads the full window in its first emission BEFORE
        // the user can scroll. Shrinking the limit to the prefill count would cause the flow
        // to emit more messages later, potentially during the first scroll (156ms commit observed
        // in logs with firstScrollActive=true). A 500-message window means pagination rarely
        // fires during initial scroll for any chat.
        val prefillShownCount = normalizedListItems.count { it is ChatListItem.MessageItem }
        if (prefillShownCount > messageWindowLimit.value) {
            messageWindowLimit.value = prefillShownCount
        }
        // Ensure the flow window stays at INITIAL_MESSAGE_WINDOW so all available messages
        // are loaded in the first flow emission, not via scroll-triggered pagination.
        if (messageWindowLimit.value < INITIAL_MESSAGE_WINDOW) {
            messageWindowLimit.value = INITIAL_MESSAGE_WINDOW
        }
        mediaController.clearRetainedMediaPreloadFutures()
        mediaController.consumePrefetcherRetainedMediaPreloads(chatId)
        MessageCacheManager.putSnapshot(chatId, recentMessages, normalizedListItems, source)

        prefillTimestampMs = System.currentTimeMillis()
        processedListItems = normalizedListItems
        currentMessages = recentMessages
        chatAdapter.replyLookupMessages = recentMessages
        // Seed the position-lock so the first live flow emission doesn't reorder what the user sees.
        renderedMessageOrder = recentMessages.map { it.id }

        // Pre-inflate ViewHolders BEFORE the guard check so the deque is populated
        // regardless of which path is taken. The prefill_skipped path (taken when the
        // live flow already filled the adapter) previously skipped pre-inflation,
        // leaving the deque empty and causing 32+ inflations during the first scroll.
        preInflateAllViewHolders()

        val liveMessageCount = chatAdapter.currentList.count { it is ChatListItem.MessageItem }
        if (liveMessageCount > recentMessages.size) {
            processedListItems = chatAdapter.currentList
            updateDisplayedMessageIds(chatAdapter.currentList)
            binding.recyclerViewMessages.visibility = View.VISIBLE
            StartupTrace.logStage(
                "chat_prefill_skipped",
                "chatId=$chatId liveCount=$liveMessageCount prefillCount=${recentMessages.size}"
            )
            // Live flow already rendered content — still warm the pool + unlock deferred work
            // promptly so the first fling is smooth on this path too.
            binding.recyclerViewMessages.post {
                if (::recyclerCoordinator.isInitialized) {
                    recyclerCoordinator.warmCommonViewHoldersNow(
                        isFinishingProvider = { isFinishing || isDestroyed }
                    )
                    recyclerCoordinator.enableOffscreenBuffer()
                }
                unlockDeferredStartupWork(reason = "prefill_skipped")
                // Mark scroll as ready since pool is now warm and content is already visible
                if (::scrollController.isInitialized) {
                    scrollController.markScrollReady()
                }
            }
            return
        }

        val rv = binding.recyclerViewMessages

        // Keep RecyclerView VISIBLE during loading so the user sees content or empty state
        // on the first frame. Setting INVISIBLE here creates the 2-second "blank screen" delay
        // that Telegram avoids by showing skeleton shimmers immediately. Without skeletons,
        // the best we can do is show whatever cached content or empty state we have right now.
        // The PreDraw listener below still fires to mark the first content frame for metrics.
        rv.visibility = View.VISIBLE

        mediaController.warmPrefillMediaAsync(
            scope = lifecycleScope,
            chatId = chatId,
            messages = recentMessages,
            preferExpandedWarmup = source != "activity_prefill"
        )
        scheduleDeferredMediaHeightWarmup(recentMessages, reason = "prefill_commit")
        scheduleDeferredOffscreenPreviewWarmup(recentMessages, reason = "prefill_commit")

            // Warm the actual first-frame tail window plus nearby media rows, not just the
            // literal last few messages. Some chats end with short text bursts that push the
            // visible media bubble slightly above the fixed tail slice.
        val mediaPreloadSpecs = mediaController.buildInitialChatMediaPreloadSpecs(chatId, recentMessages)
        val shouldAwaitInitialMediaPreloads = source == "activity_prefill"
        if (!shouldAwaitInitialMediaPreloads) {
            mediaController.retainChatMediaPreloadsAsync(
                scope = lifecycleScope,
                specs = mediaPreloadSpecs
            )
        }

        lifecycleScope.launch {
            // Media preloading: send requests to Glide's background decoder pool so
            // thumbnails are warm by the time the user scrolls to them. We do NOT await
            // them — the RecyclerView is already VISIBLE and showing content. Images
            // appear progressively as they decode, exactly like Telegram.
            if (shouldAwaitInitialMediaPreloads) {
                mediaController.retainChatMediaPreloadsAsync(
                    scope = lifecycleScope,
                    specs = mediaPreloadSpecs
                )
            }

            // Guard: if the live messages flow already committed newer content while we
            // were setting up, don't overwrite it with the older prefill snapshot.
            if (chatAdapter.currentList.count { it is ChatListItem.MessageItem } > recentMessages.size) {
                return@launch
            }

            // Lightweight binds for initial layout. isFirstLayout makes ViewHolders skip
            // heavy work (link thumbnails, reactions, translation labels) — same as the
            // isScrolling fast path. Cuts bind time from ~15ms to ~2ms per item.
            // Pool warming is deferred to post{} in setupRecyclerView so it doesn't block
            // the first frame.
            chatAdapter.isFirstLayout = true

            val prefillCommitStartNs = System.nanoTime()
            chatAdapter.submitList(normalizedListItems) {
                updateDisplayedMessageIds(normalizedListItems)
                logFirstVisibleContentIfNeeded(source = source, itemCount = normalizedListItems.size)
                scrollToBottomAfterNextLayout()
                val commitDurationMs = (System.nanoTime() - prefillCommitStartNs) / 1_000_000
                GlyphPerf.log("PREFILL_COMMIT duration=${commitDurationMs}ms items=${normalizedListItems.size}")
                StartupTrace.logStage("chat_prefill_end", "chatId=$chatId items=${normalizedListItems.size}")

                rv.viewTreeObserver.addOnPreDrawListener(
                    object : android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (rv.viewTreeObserver.isAlive) {
                                rv.viewTreeObserver.removeOnPreDrawListener(this)
                            }
                            GlyphPerf.log(
                                "FIRST CONTENT FRAME ${SystemClock.elapsedRealtime() - chatOpenStartElapsedMs}ms after open " +
                                    "items=${normalizedListItems.size} pool=[${poolOccupancySummary()}]"
                            )
                            rv.post {
                                warmVisibleContentWindows(reason = "prefill_commit")
                                if (::recyclerCoordinator.isInitialized) {
                                    recyclerCoordinator.enableOffscreenBuffer()
                                }
                                unlockDeferredStartupWork(reason = "first_frame_drawn")
                                if (::scrollController.isInitialized) {
                                    scrollController.markScrollReady()
                                }
                            }
                            return true
                        }
                    }
                )
            }
        }
    }

    /**
     * Pre-inflate only the MINIMUM ViewHolders needed for a smooth first frame.
     * Text bubbles (types 1+2) dominate the visible area; media (3+4) cover the rest.
     * Full pool warming continues asynchronously after the first frame via the
     * idle task queue (STAGE1→STAGE2→STAGE3).
     *
     * Old behavior: inflated 100+ VHs synchronously (30 text in, 30 text out, 20 media,
     * 12 headers, etc.) → blocked main thread for 1-1.5 seconds on cold start.
     * New: 40 text + 8 media = ~400ms, enough for 2+ screenfuls of content.
     */
    private fun preInflateAllViewHolders() {
        if (!::chatAdapter.isInitialized || !::binding.isInitialized) return
        val rv = binding.recyclerViewMessages
        val pool = rv.recycledViewPool

        // Skip if pool already warm from a previous session within this process.
        if (pool.getRecycledViewCount(1) >= 15 && pool.getRecycledViewCount(2) >= 15) return

        // Set caps first (cheap)
        pool.setMaxRecycledViews(1, 30); pool.setMaxRecycledViews(2, 30)
        pool.setMaxRecycledViews(3, 16); pool.setMaxRecycledViews(4, 16)
        pool.setMaxRecycledViews(13, 6)

        // Only inflate the types that dominate the first visible screen:
        // 20 incoming text + 20 outgoing text + 4 incoming media + 4 outgoing media
        val criticalCounts = mapOf(1 to 20, 2 to 20, 3 to 4, 4 to 4)
        criticalCounts.forEach { (type, count) ->
            repeat(count) {
                try { pool.putRecycledView(chatAdapter.createViewHolder(rv, type)) }
                catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    private fun putVHsIntoPool(pool: RecyclerView.RecycledViewPool, rv: RecyclerView, type: Int, count: Int) {
        repeat(count) {
            try { pool.putRecycledView(chatAdapter.createViewHolder(rv, type)) }
            catch (_: Exception) { /* best-effort */ }
        }
    }

    private fun normalizePrefillListItems(listItems: List<ChatListItem>): List<ChatListItem> {
        if (listItems.isEmpty()) return listItems

        var changed = false
        val normalized = listItems.map { item ->
            val messageItem = item as? ChatListItem.MessageItem ?: return@map item
            // Use cached emoji check to avoid O(n) Unicode scan per item
            val recalculatedEmojiOnly = messageEmojiCache.getOrPut(messageItem.message.id) {
                EmojiUtils.isEmojiOnlyMessage(messageItem.message.text)
            }
            if (messageItem.isEmojiContent == recalculatedEmojiOnly) {
                messageItem
            } else {
                changed = true
                messageItem.copy(isEmojiContent = recalculatedEmojiOnly)
            }
        }
        return if (changed) normalized else listItems
    }

    private fun showAttachmentMenu() {
        val menuRoot = binding.layoutAttachmentMenu.root
        val overlay = binding.attachmentMenuOverlay
        if (menuRoot.visibility == View.VISIBLE) return

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180L).start()

        menuRoot.visibility = View.VISIBLE
        animateAttachmentMenuIn()
    }

    private fun hideAttachmentMenu(onEnd: (() -> Unit)? = null) {
        val menuRoot = binding.layoutAttachmentMenu.root
        if (menuRoot.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }

        binding.attachmentMenuOverlay.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                binding.attachmentMenuOverlay.visibility = View.GONE
            }
            .start()

        animateAttachmentMenuOut {
            menuRoot.visibility = View.GONE
            onEnd?.invoke()
        }
    }

    private fun animateAttachmentMenuIn() {
        val menuBinding = binding.layoutAttachmentMenu
        val gridItems = listOf(
            menuBinding.optionGallery,
            menuBinding.optionCamera,
            menuBinding.optionDocument,
            menuBinding.optionAudio,
            menuBinding.optionLocation,
            menuBinding.optionContact,
            menuBinding.optionPoll,
            menuBinding.optionPayment,
            menuBinding.optionEvent,
            menuBinding.optionAiImages
        )

        menuBinding.root.alpha = 0f
        menuBinding.root.translationY = 100f
        gridItems.forEach { item ->
            item.alpha = 0f
            item.scaleX = 0.92f
            item.scaleY = 0.92f
        }

        menuBinding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        gridItems.forEachIndexed { index, item ->
            item.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 30).toLong())
                .setDuration(300L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun animateAttachmentMenuOut(onEnd: () -> Unit) {
        binding.layoutAttachmentMenu.root.animate()
            .alpha(0f)
            .translationY(100f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    private fun setupAttachmentMenu() {
        with(binding.layoutAttachmentMenu) {
            optionGallery.setOnClickListener {
                hideAttachmentMenu {
                    pickMultipleMediaLauncher.launch("*/*")
                }
            }
            optionCamera.setOnClickListener {
                hideAttachmentMenu {
                    if (ContextCompat.checkSelfPermission(this@ChatActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showCameraOptions()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
            optionDocument.setOnClickListener {
                hideAttachmentMenu {
                    pickDocumentLauncher.launch("*/*")
                }
            }
            optionAudio.setOnClickListener {
                hideAttachmentMenu {
                    pickAudioLauncher.launch("audio/*")
                }
            }
            optionLocation.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@ChatActivity, "Location sharing coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionContact.setOnClickListener {
                hideAttachmentMenu {
                    pickContactLauncher.launch(null)
                }
            }
            optionPoll.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@ChatActivity, "Poll creation coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionPayment.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@ChatActivity, "Payment coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionEvent.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@ChatActivity, "Event creation coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionAiImages.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@ChatActivity, "AI Image generation coming soon", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureAttachmentMenuInitialized(reason: String) {
        if (attachmentMenuInitialized) return
        attachmentMenuInitialized = true
        setupAttachmentMenu()
    }

    private fun setupAttachmentMenuOverlay() {
        val overlay = binding.attachmentMenuOverlay
        val input = binding.layoutInput

        val applyPadding = {
            overlay.setPadding(
                overlay.paddingLeft,
                overlay.paddingTop,
                overlay.paddingRight,
                input.height
            )
        }

        input.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPadding()
        }

        input.post {
            applyPadding()
        }
    }

    private fun setupHeader() {
        // Back button
        binding.btnBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }

        // Phase 5: tapping the user name / avatar opens GroupInfoActivity for groups.
        // 1:1 chats already open ContactInfoActivity from elsewhere, so we don't override that.
        if (isGroupChat) {
            val openGroupInfo = View.OnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val cid = chatId ?: return@OnClickListener
                startActivity(
                    com.glyph.glyph_v3.ui.groups.GroupInfoActivity.newIntent(this, cid)
                )
            }
            binding.tvUserName.setOnClickListener(openGroupInfo)
            binding.ivProfilePicture.setOnClickListener(openGroupInfo)
        }

        // Header action buttons
        binding.btnVideoCall.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            initiateCall(com.glyph.glyph_v3.data.models.CallType.VIDEO)
        }

        binding.btnVoiceCall.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            initiateCall(com.glyph.glyph_v3.data.models.CallType.VOICE)
        }

        binding.btnLightning.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // Send Buzz
            chatId?.let { cid ->
                otherUserId?.let { uid ->
                     BuzzManager.sendBuzz(cid, uid,
                        onSuccess = { 
                            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            Toast.makeText(this, "BUZZ sent! ⚡", Toast.LENGTH_SHORT).show() 
                        },
                        onError = { error ->
                            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        }
                     )
                }
            } ?: run {
                Toast.makeText(this, "Chat not ready", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMenu.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showChatMenu(view)
        }
    }

    private fun initiateCall(callType: com.glyph.glyph_v3.data.models.CallType) {
        // Phase 5: 1:1 voice/video calls don't apply to group chats yet.
        if (isGroupChat) {
            Toast.makeText(this, "Group calls are not yet supported", Toast.LENGTH_SHORT).show()
            return
        }
        val receiverId = otherUserId
        if (receiverId.isNullOrEmpty()) {
            Toast.makeText(this, "Cannot call – user info unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.CALL, showFeedback = true)) {
            return
        }
        val callManager = com.glyph.glyph_v3.data.webrtc.CallManager
        callManager.startOutgoingCall(
            context = this,
            receiverId = receiverId,
            receiverName = otherUsername,
            receiverAvatar = otherUserAvatar,
            callType = callType
        )
        val callData = callManager.callData.value ?: return
        startActivity(
            com.glyph.glyph_v3.ui.calls.ActiveCallActivity.createIntent(
                context = this,
                callId = callData.callId,
                callType = callType,
                contactName = otherUsername,
                contactAvatar = otherUserAvatar
            )
        )
    }

    private fun showChatMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        }
        val menu = popup.menu
        
        chatId?.let { cid ->
            val isMuted = BuzzManager.isBuzzMuted(this, cid)
            // Title is always "Mute Buzz", icon is always the Bolt
            menu.add(0, 1, 0, "Mute Buzz").apply {
                setIcon(R.drawable.ic_bolt)
                isCheckable = true
                isChecked = isMuted
            }
        }
        
        // Auto-translate toggle
        menu.add(0, 4, 1, R.string.auto_translate).apply {
            setIcon(R.drawable.ic_translate_glyph)
            isCheckable = true
            isChecked = translationPrefs.isAutoTranslateEnabled
        }

        if (!isGroupChat) {
            // Add other placeholders if needed
            // Live Expressive Typing toggle
            menu.add(0, 5, 2, "Live Expressive Typing").apply {
                setIcon(R.drawable.ic_rtt)
                isCheckable = true
                isChecked = expressivePrefs.isEnabled.value
            }
        }

        menu.add(0, 2, 3, "Clear Chat").setIcon(R.drawable.ic_bin_glyph)

        // Map Background toggle
        chatId?.let { cid ->
            val mapEnabled = isMapBackgroundEnabledForMenu()
            menu.add(0, 6, 4, "Map Background").apply {
                setIcon(R.drawable.ic_map)
                isCheckable = true
                isChecked = mapEnabled
            }

            // Live Location (only shown if map background is enabled)
            if (mapEnabled) {
                val liveSharing = mapBackgroundManager?.isLiveSharing == true
                menu.add(0, 7, 5, "Live Location").apply {
                    setIcon(R.drawable.ic_my_location)
                    isCheckable = true
                    isChecked = liveSharing
                }

                // Navigate to other user (only shown if map background is enabled)
                val isNavigating = mapBackgroundManager?.routingState?.isNavigating == true
                menu.add(0, 8, 6, if (isNavigating) "Stop Navigation" else "Navigate").apply {
                    setIcon(R.drawable.ic_explore)
                    isCheckable = true
                    isChecked = isNavigating
                }
            }
        }

        val isBlockedByMe = _blockStatus.value.iBlockedThem
        menu.add(0, 3, 7, if (isBlockedByMe) "Unblock User" else "Block User").setIcon(R.drawable.ic_block_glyph)

        popup.setOnMenuItemClickListener { item ->
            anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            when (item.itemId) {
                1 -> { // Toggle Mute
                    chatId?.let { cid ->
                        val currentMuted = BuzzManager.isBuzzMuted(this, cid)
                        BuzzManager.setBuzzMuted(this, cid, !currentMuted)
                        val msg = if (!currentMuted) "Buzz muted for this chat" else "Buzz unmuted"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                4 -> { // Auto-translate
                    if (translationPrefs.isAutoTranslateEnabled) {
                        // Toggle off
                        translationPrefs.isAutoTranslateEnabled = false
                        Toast.makeText(this, R.string.auto_translate_disabled, Toast.LENGTH_SHORT).show()
                    } else {
                        // Show language selector, then enable
                        showAutoTranslateLanguageSelector()
                    }
                    true
                }
                5 -> { // Live Expressive Typing toggle
                    val current = expressivePrefs.isEnabled.value
                    expressivePrefs.setEnabled(!current)
                    val msg = if (!current) "Live Expressive Typing Enabled" else "Live Expressive Typing Disabled"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (current) {
                        // Just disabled — stop sending live text and clear UI
                        expressiveTypingManager?.stopObserving()
                    } else {
                        // Just enabled — restart observing
                        ensureExpressiveTypingInitialized(reason = "menu_enable")
                        startExpressiveObservingSession()
                    }
                    true
                }
                2, 3 -> {
                    if (item.itemId == 2) {
                        showClearChatConfirmation()
                    } else {
                        if (_blockStatus.value.iBlockedThem) {
                            showUnblockConfirmation()
                        } else {
                            showBlockConfirmation()
                        }
                    }
                    true
                }
                6 -> { // Map Background toggle
                    handleMapBackgroundToggle()
                    true
                }
                7 -> { // Live Location toggle
                    handleLiveLocationToggle()
                    true
                }
                8 -> { // Navigate toggle
                    handleNavigateToggle()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Shows a WhatsApp-style confirmation dialog for clearing chat history.
     * On confirmation, permanently deletes all messages from local DB, Firestore,
     * and local media files. Tombstones prevent Firestore sync from re-inserting.
     */
    private fun showClearChatConfirmation() {
        val cid = chatId ?: return
        // Guard: do nothing if chat is already empty
        if (isChatEmptyState.value) return
        // Guard: do nothing if a clear is already running
        if (clearChatInProgress) return

        androidx.appcompat.app.AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Clear this chat?")
            .setMessage("All messages will be permanently deleted from this chat. This action cannot be undone.")
            .setPositiveButton("Clear") { dialog, _ ->
                dialog.dismiss()
                if (clearChatInProgress) return@setPositiveButton
                clearChatInProgress = true
                lifecycleScope.launch {
                    try {
                        repository.clearChatMessages(cid)
                        // Clear the adapter / UI
                        chatAdapter.submitList(emptyList())
                        Toast.makeText(this@ChatActivity, "Chat cleared", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Failed to clear chat", e)
                        Toast.makeText(this@ChatActivity, "Failed to clear chat", Toast.LENGTH_SHORT).show()
                    } finally {
                        clearChatInProgress = false
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════
    // GOOGLE MAPS BACKGROUND — handlers & setup
    // ══════════════════════════════════════════════════════════

    private fun isMapBackgroundEnabledForMenu(): Boolean {
        val manager = mapBackgroundManager
        if (manager != null) return manager.isMapEnabled
        return shouldRestoreMapBackgroundOnOpen()
    }

    /** Called from the overflow menu to toggle map background on/off. */
    private fun handleMapBackgroundToggle() {
        val mgr = ensureMapBackgroundManagerInitialized(reason = "menu_toggle") ?: run {
            Log.w(PHASE3_PERF_TAG, "map_background_toggle_skipped manager_unavailable")
            return
        }
        if (mgr.isMapEnabled) {
            // Disable map → restore wallpaper
            mapVideoSessionManager?.setEnabled(false)
            mgr.disableMapBackground()
            teardownMapComposeViews()
            // Reset chat content in case interactive mode was active
            resetChatContentFromInteractiveMode()
            // Crossfade back to wallpaper
            binding.ivChatWallpaper.animate().alpha(1f).setDuration(400).start()
            applyChatWallpaperIfNeeded(force = true)
            Toast.makeText(this, "Map background disabled", Toast.LENGTH_SHORT).show()
        } else {
            // Check permission before enabling
            if (mgr.hasLocationPermission) {
                mgr.enableMapBackground()
                setupMapComposeViews()
                // Crossfade — hide wallpaper
                binding.ivChatWallpaper.animate().alpha(0f).setDuration(400).start()
                Toast.makeText(this, "Map background enabled", Toast.LENGTH_SHORT).show()
            } else {
                locationPermissionLauncher.launch(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }

        // Re-apply block guard after map state transitions that may touch bottom UI.
        enforceBlockedInputUiState()
    }

    /** Called from the overflow menu to toggle live location sharing. */
    private fun handleLiveLocationToggle() {
        val mgr = ensureMapBackgroundManagerInitialized(reason = "live_location_toggle") ?: return
        if (!mgr.isLiveSharing && isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.LIVE_LOCATION, showFeedback = true)) {
            return
        }
        if (mgr.isLiveSharing) {
            showStopLiveLocationConfirmation()
        } else {
            checkLocationAndStartSharing()
        }
    }

    private fun showStopLiveLocationConfirmation() {
        val mgr = mapBackgroundManager ?: return
        val view = layoutInflater.inflate(R.layout.dialog_live_location_stop, null)
        val isCameraShareActive = isMyAutoCameraShareActive()

        view.findViewById<android.widget.TextView>(R.id.text_stop_live_location_subtitle)?.text =
            if (isCameraShareActive) {
                "Your live location and camera sharing will end immediately"
            } else {
                "Your live sharing session will end immediately"
            }

        view.findViewById<android.widget.TextView>(R.id.text_stop_live_location_message)?.text =
            if (isCameraShareActive) {
                "The other user will stop seeing both your live location and your live camera feed."
            } else {
                "The other user will no longer be able to see your live location."
            }

        view.findViewById<android.widget.TextView>(R.id.text_stop_live_location_note)?.text =
            if (isCameraShareActive) {
                "Your foreground camera sharing notification will also disappear once sharing stops."
            } else {
                "You can start sharing again any time from the Live Location menu."
            }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            ?.setOnClickListener { dialog.dismiss() }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stop_live_location)
            ?.setOnClickListener {
                dialog.dismiss()
                mgr.stopLiveSharing()
                updateLiveBannerVisibility()
                Toast.makeText(this, "Live location sharing stopped", Toast.LENGTH_SHORT).show()
            }

        dialog.show()
    }

    /** Called from the overflow menu to toggle navigation/routing to the other user. */
    private fun handleNavigateToggle() {
        val mgr = ensureMapBackgroundManagerInitialized(reason = "navigate_toggle") ?: return
        
        if (mgr.routingState.isNavigating) {
            mgr.stopNavigation()
            Toast.makeText(this, "Navigation stopped", Toast.LENGTH_SHORT).show()
        } else {
            mgr.startNavigation()
            Toast.makeText(this, "Navigating to ${otherUsername.ifEmpty { "User" }}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Entry-point for enabling live location.
     * Checks location permission first, then verifies GPS/network location services are on.
     */
    private fun checkLocationAndStartSharing() {
        if (isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.LIVE_LOCATION, showFeedback = true)) {
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            proceedWithLiveLocationCheck()
        } else {
            liveLocationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Called once location permission is confirmed.
     * Checks whether the device location service (GPS / network) is enabled.
     * If off → prompts the user to turn it on.
     * If on  → shows the duration picker.
     */
    private fun proceedWithLiveLocationCheck() {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled =
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (isGpsEnabled) {
            pendingLiveLocationAfterGps = false
            showLiveSharingDurationPicker()
        } else {
            pendingLiveLocationAfterGps = true
            showLocationServicesOffDialog()
        }
    }

    /** Alert explaining that GPS is off, with a "Turn On" shortcut to system settings. */
    private fun showLocationServicesOffDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_location_gps_off, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            .setOnClickListener {
                dialog.dismiss()
                pendingLiveLocationAfterGps = false
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_turn_on)
            .setOnClickListener {
                dialog.dismiss()
                locationSettingsLauncher.launch(
                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                )
            }
        dialog.show()
    }

    /** Rationale dialog shown when the permission was denied once and can be re-requested. */
    private fun showLocationPermissionRationaleDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_location_permission, null)
        view.findViewById<android.widget.TextView>(R.id.dialog_title).text =
            "Location permission required"
        view.findViewById<android.widget.TextView>(R.id.dialog_subtitle).text =
            "Needed for live location sharing"
        view.findViewById<android.widget.TextView>(R.id.dialog_message).text =
            "Live location sharing requires access to your device location. " +
            "Please grant the location permission to continue."
        val btnPositive =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_positive)
        btnPositive.text = "Grant"
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            .setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            dialog.dismiss()
            liveLocationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        dialog.show()
    }

    /** Dialog shown when location permission is permanently denied — directs user to App Settings. */
    private fun showLocationPermissionDeniedDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_location_permission, null)
        view.findViewById<android.widget.TextView>(R.id.dialog_title).text =
            "Location permission denied"
        view.findViewById<android.widget.TextView>(R.id.dialog_subtitle).text =
            "Open App Settings to allow location"
        view.findViewById<android.widget.TextView>(R.id.dialog_message).text =
            "Live location sharing requires location permission. " +
            "Please enable it in App Settings → Permissions → Location."
        val btnPositive =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_positive)
        btnPositive.text = "Open Settings"
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            .setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            dialog.dismiss()
            locationSettingsLauncher.launch(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
        dialog.show()
    }

    /**
     * Shown when the device's location service is switched off while live sharing is running.
     * Reuses the existing dialog_location_gps_off layout but with a tailored message and
     * a "Turn On" shortcut instead of the usual flow.
     */
    private fun showGpsDisabledDuringLiveSharingDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_location_gps_off, null)

        // Find the body TextView (index 1 in root LinearLayout) and update text
        val root = view as? android.widget.LinearLayout
        val bodyText = root?.getChildAt(1) as? android.widget.TextView
        bodyText?.text = "Location services were turned off, so live location sharing has been stopped automatically. Turn them back on to share again."

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            .setOnClickListener { dialog.dismiss() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_turn_on)
            .apply {
                text = "Turn On"
                setOnClickListener {
                    dialog.dismiss()
                    pendingLiveLocationAfterGps = true
                    locationSettingsLauncher.launch(
                        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    )
                }
            }
        dialog.show()
    }

    /** Shows a custom dialog to choose live sharing duration (15m / 1h / 8h). */
    private fun showLiveSharingDurationPicker() {
        val durationMs = longArrayOf(
            15 * 60 * 1000L,       // 15 min
            60 * 60 * 1000L,       // 1 hr
            8 * 60 * 60 * 1000L    // 8 hr
        )
        val view = layoutInflater.inflate(R.layout.dialog_location_duration, null)
        val cameraShareCheckbox = view.findViewById<android.widget.CheckBox>(R.id.checkbox_camera_share)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        fun pick(which: Int) {
            dialog.dismiss()
            val selectedDuration = durationMs[which]
            if (cameraShareCheckbox?.isChecked == true) {
                val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted && micGranted) {
                    startLiveLocationSharing(selectedDuration, shareCamera = true)
                } else {
                    pendingLiveLocationCameraDurationMs = selectedDuration
                    liveLocationCameraPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }
            } else {
                startLiveLocationSharing(selectedDuration, shareCamera = false)
            }
        }
        view.findViewById<android.view.View>(R.id.option_15min).setOnClickListener { pick(0) }
        view.findViewById<android.view.View>(R.id.option_1hour).setOnClickListener { pick(1) }
        view.findViewById<android.view.View>(R.id.option_8hours).setOnClickListener { pick(2) }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun startLiveLocationSharing(durationMs: Long, shareCamera: Boolean) {
        val manager = mapBackgroundManager ?: return
        val currentChatId = chatId ?: return
        val currentOtherUserId = otherUserId ?: return
        if (isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.LIVE_LOCATION, showFeedback = true)) {
            return
        }

        if (shareCamera) {
            mapVideoSessionManager?.setEnabled(false)
        } else {
            com.glyph.glyph_v3.data.service.MapCameraShareForegroundService.stop(this, currentOtherUserId)
        }

        manager.startLiveSharing(durationMs, otherUsername.ifEmpty { "User" })

        if (shareCamera) {
            com.glyph.glyph_v3.data.service.MapCameraShareForegroundService.start(
                context = this,
                chatId = currentChatId,
                targetUserId = currentOtherUserId,
                otherUserName = otherUsername.ifEmpty { "User" },
                otherUserAvatar = otherUserAvatar
            )
            binding.root.post {
                refreshVideoSessionManagerBinding()
                setupMapComposeViews()
            }
        }

        updateLiveBannerVisibility()
        Toast.makeText(
            this,
            if (shareCamera) "Live location and camera sharing started" else "Live location sharing started",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun isMyAutoCameraShareActive(): Boolean {
        return com.glyph.glyph_v3.data.service.ActiveMapCameraShareStore.get(this)
            ?.matches(chatId, otherUserId) == true
    }

    private fun createLocalMapVideoSessionManager(
        chatId: String,
        myUserId: String,
        otherUserId: String
    ): com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager {
        return com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager(
            context = this,
            scope = lifecycleScope,
            chatId = chatId,
            myUserId = myUserId,
            otherUserId = otherUserId
        )
    }

    private fun enableMapVideoModeWithPermissions(sendInviteToOtherUser: Boolean = true) {
        val manager = refreshVideoSessionManagerBinding() ?: return
        if (isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.LIVE_CAMERA, showFeedback = true)) {
            manager.setEnabled(false)
            return
        }
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            manager.setEnabled(true)
            if (sendInviteToOtherUser) {
                val senderName = currentUserUsername.takeIf { it.isNotBlank() }
                    ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                        ?.takeIf { it.isNotBlank() }
                    ?: "Someone"
                manager.sendCameraInvite(senderName)
            }
            setupMapComposeViews()
        } else {
            pendingMapVideoEnableAfterPermissions = true
            pendingMapVideoEnableShouldSendInvite = sendInviteToOtherUser
            mapVideoPermissionsLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    private fun refreshVideoSessionManagerBinding(): com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager? {
        val currentChatId = chatId ?: return mapVideoSessionManager
        val currentOtherUserId = otherUserId ?: return mapVideoSessionManager
        val myUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return mapVideoSessionManager

        val sharedManager = com.glyph.glyph_v3.data.service.MapVideoSessionRegistry.get(
            chatId = currentChatId,
            myUserId = myUserId,
            otherUserId = currentOtherUserId
        )

        return if (sharedManager != null) {
            if (mapVideoSessionManager !== sharedManager && !isUsingSharedMapVideoManager) {
                mapVideoSessionManager?.onDestroy()
            }
            mapVideoSessionManager = sharedManager
            isUsingSharedMapVideoManager = true
            sharedManager
        } else {
            if (isUsingSharedMapVideoManager || mapVideoSessionManager == null) {
                mapVideoSessionManager = createLocalMapVideoSessionManager(
                    chatId = currentChatId,
                    myUserId = myUserId,
                    otherUserId = currentOtherUserId
                )
            }
            isUsingSharedMapVideoManager = false
            mapVideoSessionManager
        }
    }

    /**
     * Initialize the MapBackgroundManager. Called once from onCreate after we have
     * chatId, otherUserId, and current user info.
     */
    private fun initMapBackgroundManager() {
        val cid = chatId ?: return
        val otherId = otherUserId ?: return
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return

        mapBackgroundManager = com.glyph.glyph_v3.ui.chat.map.MapBackgroundManager(
            context = this,
            scope = lifecycleScope,
            chatId = cid,
            myUserId = myUid,
            otherUserId = otherId,
            initialMyAvatarUrl = currentUserAvatar,
            initialOtherAvatarUrl = otherUserAvatar,
            otherUserName = otherUsername.ifEmpty { "User" }
        )
        refreshVideoSessionManagerBinding()
        refreshMapBackgroundAvatars()

        // If map was previously enabled, set it up immediately
        if (mapBackgroundManager?.isMapEnabled == true && mapBackgroundManager?.hasLocationPermission == true) {
            setupMapComposeViews()
            // Hide wallpaper
            binding.ivChatWallpaper.alpha = 0f
        }
    }

    private fun shouldRestoreMapBackgroundOnOpen(): Boolean {
        val cid = chatId ?: return false
        val prefs = com.glyph.glyph_v3.ui.chat.map.MapBackgroundPreferences.getInstance(this)
        return prefs.isMapBackgroundEnabled(cid) ||
            prefs.isLiveSharingEnabled(cid) ||
            prefs.isNavigationEnabled(cid) ||
            prefs.isInteractiveMode(cid)
    }

    private fun ensureMapBackgroundManagerInitialized(reason: String): com.glyph.glyph_v3.ui.chat.map.MapBackgroundManager? {
        if (mapBackgroundManager != null) return mapBackgroundManager
        initMapBackgroundManager()
        return mapBackgroundManager
    }

    /**
     * Set the ComposeView content for map background, live banner, and interactive button.
     * Called when map is first enabled or restored on resume.
     */
    private fun setupMapComposeViews() {
        val mgr = ensureMapBackgroundManagerInitialized(reason = "setup_compose_views") ?: return
        val videoMgr = refreshVideoSessionManagerBinding()

        // Map background
        binding.mapBackgroundView.visibility = View.VISIBLE
        binding.mapBackgroundView.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                val routingState = mgr.routingState
                com.glyph.glyph_v3.ui.chat.map.ChatMapBackground(
                    myUserId = mgr.myUserId,
                    otherUserId = mgr.otherUserId,
                    otherUserName = otherUsername.ifEmpty { "User" },
                    myAvatarUrl = mgr.myAvatarUrl,
                    otherAvatarUrl = mgr.otherAvatarUrl,
                    videoSessionManager = videoMgr,
                    autoReceiveVideoEnabled = mgr.otherUserCameraSharing,
                    onAcceptIncomingCameraInvite = {
                        val myName = com.google.firebase.auth.FirebaseAuth.getInstance()
                            .currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: currentUserPhone.takeIf { it.isNotBlank() }
                            ?: "Someone"
                        videoMgr?.acceptIncomingCameraInvite(myName)
                        enableMapVideoModeWithPermissions(sendInviteToOtherUser = false)
                        // Cancel the camera invite notification if it's still showing
                        chatId?.let { id ->
                            val notifTag = "camera_invite_$id"
                            val nm = getSystemService(android.app.NotificationManager::class.java)
                            nm?.cancel(notifTag, notifTag.hashCode())
                        }
                    },
                    onDismissIncomingCameraInvite = {
                        val myName = com.google.firebase.auth.FirebaseAuth.getInstance()
                            .currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: currentUserPhone.takeIf { it.isNotBlank() }
                            ?: "Someone"
                        videoMgr?.declineIncomingCameraInvite(myName)
                    },
                    isInteractive = mgr.isInteractiveMode,
                    isVisible = mgr.isMapEnabled,
                    routePoints = routingState.routePoints,
                    isNavigating = routingState.isNavigating,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                )
            }
        }

        // Live location banner — always visible while the map is on
        binding.liveLocationBannerView.visibility = View.VISIBLE
        binding.liveLocationBannerView.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                com.glyph.glyph_v3.ui.chat.map.LiveLocationBanner(
                    isVisible = mgr.isMapEnabled,
                    myIsLiveSharing = mgr.isLiveSharing,
                    startedAtMs = mgr.liveSharingStartedAt,
                    durationMs = mgr.liveSharingDurationMs,
                    accuracyMeters = mgr.myAccuracyMeters,
                    otherUserId = mgr.otherUserId,
                    otherUserName = otherUsername.ifEmpty { "User" }
                )
            }
        }

        // Interactive map button (above input)
        binding.interactiveMapButton.visibility = View.VISIBLE
        binding.interactiveMapButton.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                com.glyph.glyph_v3.ui.chat.map.InteractiveMapButton(
                    isInteractive = mgr.isInteractiveMode,
                    isMapEnabled = mgr.isMapEnabled,
                    isVideoModeEnabled = videoMgr?.isVideoModeEnabled == true,
                    isAudioMuted = videoMgr?.isAudioMuted == true,
                    isQuickReplyEnabled = !_blockStatus.value.iBlockedThem,
                    isQuickReplyOverlayActive = isInteractiveQuickReplyOverlayActive,
                    myAvatarUrl = mgr.myAvatarUrl,
                    videoManager = videoMgr,
                    onDockBoundsChanged = { rect ->
                        interactiveMapVisibleDockRect = rect
                    },
                    onToggle = {
                        mgr.toggleInteractiveMode()
                        animateChatContentForInteractiveMode(mgr.isInteractiveMode)
                    },
                    onQuickReply = {
                        toggleQuickReplyInputInInteractiveMode()
                    },
                    onToggleVideoMode = {
                        val manager = refreshVideoSessionManagerBinding() ?: return@InteractiveMapButton
                        if (isMyAutoCameraShareActive()) {
                            if (
                                manager.currentMode == com.glyph.glyph_v3.ui.chat.map.video.MapVideoMode.AUTO_RECEIVE_ONLY ||
                                manager.currentMode == com.glyph.glyph_v3.ui.chat.map.video.MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE
                            ) {
                                Toast.makeText(
                                    this@ChatActivity,
                                    "The other user's live camera feed is being shown automatically",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                manager.setManualReceiveEnabled(!manager.isManualRemoteViewEnabled)
                            }
                            setupMapComposeViews()
                        } else if (manager.currentMode == com.glyph.glyph_v3.ui.chat.map.video.MapVideoMode.AUTO_RECEIVE_ONLY) {
                            enableMapVideoModeWithPermissions()
                        } else if (manager.isVideoModeEnabled) {
                            manager.setEnabled(false)
                            setupMapComposeViews()
                        } else {
                            enableMapVideoModeWithPermissions()
                        }
                    },
                    onToggleAudioMute = {
                        mapVideoSessionManager?.toggleAudioMuted()
                    }
                )
            }
        }

        // Routing info banner — shows distance/ETA/duration when navigating
        binding.routingInfoBannerView.visibility = View.VISIBLE
        binding.routingInfoBannerView.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                val isDark = com.glyph.glyph_v3.ui.theme.glyphTheme.isDark
                com.glyph.glyph_v3.ui.chat.map.routing.RoutingInfoBanner(
                    state = mgr.routingState,
                    otherUserName = otherUsername.ifEmpty { "User" },
                    isDark = isDark,
                    onStop = { mgr.stopNavigation() },
                    onToggleMode = { mgr.toggleTravelMode() },
                    onRouteUpdated = { state -> showNavigationNotification(state) }
                )
            }
        }
        installInteractiveMapTouchDebugLogging()
        binding.interactiveMapButton.post {
            ensureInteractiveMapButtonAboveTransientOverlays()
            updateScrollFabMapOverlayAvoidance()
            updateInteractiveMapGestureExclusion()
            logInteractiveMapTouchSnapshot("setupMapComposeViews_post")
        }
    }

    private fun ensureInteractiveMapButtonAboveTransientOverlays() {
        if (!::binding.isInitialized) return
        if (binding.interactiveMapButton.visibility != View.VISIBLE) return

        binding.interactiveMapButton.elevation = dpToPx(36f).toFloat()
        binding.interactiveMapButton.translationZ = dpToPx(36f).toFloat()
        binding.interactiveMapButton.bringToFront()
    }

    private fun updateScrollFabMapOverlayAvoidance() {
        if (!::binding.isInitialized) return

        val fab = binding.fabScrollToBottom
        val mapButton = binding.interactiveMapButton

        if (
            fab.visibility != View.VISIBLE ||
            fab.alpha <= 0f ||
            fab.width <= 0 ||
            fab.height <= 0 ||
            mapButton.visibility != View.VISIBLE ||
            mapButton.width <= 0 ||
            mapButton.height <= 0
        ) {
            if (fab.translationY != 0f) fab.translationY = 0f
            return
        }

        val fabRect = getViewBoundsOnScreen(fab)
        val mapRect = getViewBoundsOnScreen(mapButton)
        if (fabRect == null || mapRect == null) {
            if (fab.translationY != 0f) fab.translationY = 0f
            return
        }

        if (!Rect.intersects(fabRect, mapRect)) {
            if (fab.translationY != 0f) fab.translationY = 0f
            return
        }

        val overlapHeight = (fabRect.bottom - mapRect.top).coerceAtLeast(0)
        val targetTranslationY = -(overlapHeight + dpToPx(12)).toFloat()
        if (fab.translationY != targetTranslationY) {
            fab.translationY = targetTranslationY
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                "MapTouchDebug",
                "scroll_fab_avoidance fab=${formatDebugRect(fabRect)} map=${formatDebugRect(mapRect)} translationY=${fab.translationY}"
            )
        }
    }

    private fun updateInteractiveMapGestureExclusion() {
        if (!::binding.isInitialized) return

        val mapButtonHost = binding.interactiveMapButton
        if (mapButtonHost.visibility != View.VISIBLE || mapButtonHost.width <= 0 || mapButtonHost.height <= 0) {
            mapButtonHost.systemGestureExclusionRects = emptyList()
            return
        }

        val exclusionWidth = minOf(dpToPx(96), mapButtonHost.width)
        val exclusionRect = android.graphics.Rect(
            (mapButtonHost.width - exclusionWidth).coerceAtLeast(0),
            0,
            mapButtonHost.width,
            mapButtonHost.height
        )
        mapButtonHost.systemGestureExclusionRects = listOf(exclusionRect)

        if (BuildConfig.DEBUG) {
            Log.d(
                "MapTouchDebug",
                "gesture_exclusion rect=[${exclusionRect.left},${exclusionRect.top},${exclusionRect.right},${exclusionRect.bottom}] hostSize=${mapButtonHost.width}x${mapButtonHost.height}"
            )
        }
    }

    /** Ensure blocked-by-me state always wins over transient UI visibility changes. */
    private fun enforceBlockedInputUiState() {
        updateInputBarForBlockStatus(_blockStatus.value, animate = false)
    }

    /** Show input only when not blocked by me; otherwise keep blocked banner/input policy. */
    private fun showInputBarIfAllowed() {
        if (_blockStatus.value.iBlockedThem) {
            enforceBlockedInputUiState()
        } else {
            binding.layoutInput.visibility = View.VISIBLE
        }
    }

    private fun captureRecyclerOverlayDefaultsIfNeeded() {
        if (recyclerOverlayDefaultsCaptured) return
        recyclerOverlayDefaultsCaptured = true
        binding.recyclerViewMessages.let { recycler ->
            recyclerDefaultPaddingStartPx = recycler.paddingStart
            recyclerDefaultPaddingTopPx = recycler.paddingTop
            recyclerDefaultPaddingEndPx = recycler.paddingEnd
            recyclerDefaultPaddingBottomPx = recycler.paddingBottom
        }
    }

    private fun hasVisibleChatMessages(): Boolean {
        return ::chatAdapter.isInitialized && chatAdapter.currentList.any {
            it is ChatListItem.MessageItem || it is ChatListItem.GroupIntroItem
        }
    }

    private fun interactiveOverlayHeightPx(): Int {
        val screenBound = (resources.displayMetrics.heightPixels * 0.17f).toInt()
        return minOf(dpToPx(140), screenBound).coerceAtLeast(dpToPx(112))
    }

    private fun applyInteractiveOverlayMessageFade() {
        if (!::binding.isInitialized) return
        val recycler = binding.recyclerViewMessages
        if (!isInteractiveQuickReplyOverlayActive || recycler.visibility != View.VISIBLE) {
            if (!interactiveOverlayFadeApplied) return
            for (index in 0 until recycler.childCount) {
                recycler.getChildAt(index)?.let { child ->
                    if (child.alpha != 1f) child.alpha = 1f
                }
            }
            interactiveOverlayFadeApplied = false
            return
        }

        interactiveOverlayFadeApplied = true
        val fadeHeightPx = dpToPx(48)
        val contentTop = recycler.paddingTop
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            val relativeTop = child.top - contentTop
            val relativeBottom = child.bottom - contentTop
            val alpha = when {
                relativeBottom <= 0 -> 0f
                relativeTop >= fadeHeightPx -> 1f
                else -> (relativeBottom.toFloat() / fadeHeightPx.toFloat()).coerceIn(0f, 1f)
            }
            if (child.alpha != alpha) child.alpha = alpha
        }
    }

    private fun snapInteractiveOverlayToLatest() {
        if (!isInteractiveQuickReplyOverlayActive || !::chatAdapter.isInitialized) return
        val recycler = binding.recyclerViewMessages
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val target = chatAdapter.itemCount - 1
        if (target < 0) return
        recycler.post {
            layoutManager.scrollToPositionWithOffset(target, 0)
            applyInteractiveOverlayMessageFade()
        }
    }

    private fun syncInteractiveOverlayChrome() {
        if (!::binding.isInitialized) return
        binding.messageOverlayFade.visibility = View.GONE
        if (isInteractiveQuickReplyOverlayActive) {
            binding.emptyChatOverlay.visibility = View.GONE
        }
        updateQuickReplyTouchShieldVisibility()
        ensureInteractiveMapButtonAboveTransientOverlays()
        binding.recyclerViewMessages.post { applyInteractiveOverlayMessageFade() }
    }

    private fun updateQuickReplyTouchShieldVisibility() {
        if (!::binding.isInitialized) return
        val recycler = binding.recyclerViewMessages
        val shield = binding.quickReplyTouchShield
        val shouldShowShield = isInteractiveQuickReplyOverlayActive && recycler.visibility == View.VISIBLE
        shield.visibility = if (shouldShowShield) View.VISIBLE else View.GONE
        if (shouldShowShield) {
            updateQuickReplyTouchShieldBounds()
            recycler.bringToFront()
            shield.bringToFront()
            binding.messageOverlayFade.bringToFront()
            ensureInteractiveMapButtonAboveTransientOverlays()
        } else {
            val shieldParams = shield.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (shieldParams != null && shieldParams.bottomToTop != androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET) {
                shieldParams.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                shieldParams.bottomToBottom = R.id.recyclerViewMessages
                shield.layoutParams = shieldParams
            }
        }
    }

    private fun updateQuickReplyTouchShieldBounds() {
        if (!::binding.isInitialized) return

        val shield = binding.quickReplyTouchShield
        val params = shield.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        val mapButtonVisible = binding.interactiveMapButton.visibility == View.VISIBLE

        if (mapButtonVisible) {
            if (params.bottomToTop != R.id.interactiveMapButton ||
                params.bottomToBottom != androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET) {
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToTop = R.id.interactiveMapButton
                shield.layoutParams = params
            }
        } else {
            if (params.bottomToBottom != R.id.recyclerViewMessages ||
                params.bottomToTop != androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET) {
                params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToBottom = R.id.recyclerViewMessages
                shield.layoutParams = params
            }
        }
    }

    private fun isPointInsideQuickReplyOverlay(rawX: Float, rawY: Float): Boolean {
        if (!::binding.isInitialized || !isInteractiveQuickReplyOverlayActive) return false

        val visibleDockRect = interactiveMapVisibleDockRect
        if (visibleDockRect?.contains(rawX.toInt(), rawY.toInt()) == true) {
            return false
        }

        val recycler = binding.recyclerViewMessages
        if (recycler.visibility != View.VISIBLE || recycler.width <= 0 || recycler.height <= 0) return false

        val recyclerLocation = IntArray(2)
        recycler.getLocationOnScreen(recyclerLocation)
        val left = recyclerLocation[0].toFloat()
        val top = recyclerLocation[1].toFloat()
        val right = left + recycler.width
        val bottom = top + recycler.height
        return rawX in left..right && rawY in top..bottom
    }

    private fun installInteractiveMapTouchDebugLogging() {
        if (!BuildConfig.DEBUG || !::binding.isInitialized) return

        fun attachTouchLogger(name: String, view: View) {
            view.setOnTouchListener { touchedView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        logInteractiveMapTouchEvent(
                            source = name,
                            touchedView = touchedView,
                            event = event
                        )
                    }
                }
                false
            }
        }

        attachTouchLogger("interactiveMapButtonView", binding.interactiveMapButton)
        attachTouchLogger("layoutInput", binding.layoutInput)
        attachTouchLogger("composeInput", binding.composeInput)
        attachTouchLogger("inputPill", binding.inputPill)
        logInteractiveMapTouchSnapshot("install_listeners")
    }

    private fun logInteractiveMapTouchSnapshot(stage: String) {
        if (!BuildConfig.DEBUG || !::binding.isInitialized) return

        val mapRect = interactiveMapVisibleDockRect ?: getViewBoundsOnScreen(binding.interactiveMapButton)
        val inputRect = getViewBoundsOnScreen(binding.layoutInput)
        val composeInputRect = getViewBoundsOnScreen(binding.composeInput)
        val pillRect = getViewBoundsOnScreen(binding.inputPill)
        val overlapWithInput = mapRect != null && inputRect != null && Rect.intersects(mapRect, inputRect)
        val overlapWithComposeInput = mapRect != null && composeInputRect != null && Rect.intersects(mapRect, composeInputRect)

        Log.d(
            "MapTouchDebug",
            "snapshot stage=$stage map=${formatDebugRect(mapRect)} input=${formatDebugRect(inputRect)} " +
                "composeInput=${formatDebugRect(composeInputRect)} pill=${formatDebugRect(pillRect)} " +
                "mapVis=${binding.interactiveMapButton.visibility} inputVis=${binding.layoutInput.visibility} " +
                "composeInputVis=${binding.composeInput.visibility} pillVis=${binding.inputPill.visibility} " +
                "mapZ=${binding.interactiveMapButton.z} inputZ=${binding.layoutInput.z} composeZ=${binding.composeInput.z} " +
                "mapTxY=${binding.interactiveMapButton.translationY} inputTxY=${binding.layoutInput.translationY} " +
                "overlapInput=$overlapWithInput overlapCompose=$overlapWithComposeInput"
        )
    }

    private fun logInteractiveMapTouchEvent(source: String, touchedView: View, event: MotionEvent) {
        if (!BuildConfig.DEBUG || !::binding.isInitialized) return

        val rawX = event.rawX
        val rawY = event.rawY
        val mapRect = interactiveMapVisibleDockRect ?: getViewBoundsOnScreen(binding.interactiveMapButton)
        val inputRect = getViewBoundsOnScreen(binding.layoutInput)
        val composeInputRect = getViewBoundsOnScreen(binding.composeInput)
        val pillRect = getViewBoundsOnScreen(binding.inputPill)
        val sourceRect = getViewBoundsOnScreen(touchedView)

        Log.d(
            "MapTouchDebug",
            "touch source=$source action=${motionActionName(event.actionMasked)} raw=(${rawX.toInt()},${rawY.toInt()}) " +
                "local=(${event.x.toInt()},${event.y.toInt()}) src=${formatDebugRect(sourceRect)} " +
                "insideSrc=${sourceRect?.contains(rawX.toInt(), rawY.toInt()) == true} " +
                "insideMap=${mapRect?.contains(rawX.toInt(), rawY.toInt()) == true} " +
                "insideInput=${inputRect?.contains(rawX.toInt(), rawY.toInt()) == true} " +
                "insideCompose=${composeInputRect?.contains(rawX.toInt(), rawY.toInt()) == true} " +
                "insidePill=${pillRect?.contains(rawX.toInt(), rawY.toInt()) == true} " +
                "map=${formatDebugRect(mapRect)} input=${formatDebugRect(inputRect)} compose=${formatDebugRect(composeInputRect)}"
        )
    }

    private fun getViewBoundsOnScreen(view: View): Rect? {
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
    }

    private fun formatDebugRect(rect: Rect?): String {
        if (rect == null) return "null"
        return "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
    }

    private fun motionActionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return rawX in left..right && rawY in top..bottom
    }

    private fun dispatchQuickReplyTouchToRecycler(event: MotionEvent): Boolean {
        if (!::binding.isInitialized) return false
        val recycler = binding.recyclerViewMessages
        if (!isInteractiveQuickReplyOverlayActive || recycler.visibility != View.VISIBLE) return false

        val rootLocation = IntArray(2)
        val recyclerLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        recycler.getLocationOnScreen(recyclerLocation)

        val forwardedEvent = MotionEvent.obtain(event)
        forwardedEvent.offsetLocation(
            (rootLocation[0] - recyclerLocation[0]).toFloat(),
            (rootLocation[1] - recyclerLocation[1]).toFloat()
        )

        val handled = recycler.dispatchTouchEvent(forwardedEvent)
        forwardedEvent.recycle()
        return handled
    }

    private fun requestParentInterceptDisallow(view: View, disallow: Boolean) {
        var parent = view.parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }

    private fun resetOverlayRowTransform(view: View?) {
        view ?: return
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun normalizeInteractiveOverlayVisibleRows() {
        if (!::binding.isInitialized) return
        val recycler = binding.recyclerViewMessages
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            resetOverlayRowTransform(child)
            resetOverlayRowTransform(child.findViewById(R.id.cardMessage))
            resetOverlayRowTransform(child.findViewById(R.id.messageBubble))
        }
    }

    private fun stabilizeInteractiveOverlayLayout(animate: Boolean) {
        if (!::binding.isInitialized) return
        val recycler = binding.recyclerViewMessages
        recycler.stopScroll()
        recycler.requestLayout()
        recycler.doOnNextLayout {
            if (!isInteractiveQuickReplyOverlayActive) return@doOnNextLayout

            normalizeInteractiveOverlayVisibleRows()

            val layoutManager = recycler.layoutManager as? LinearLayoutManager
            val target = if (::chatAdapter.isInitialized) chatAdapter.itemCount - 1 else -1
            if (layoutManager != null && target >= 0) {
                layoutManager.scrollToPositionWithOffset(target, 0)
            }

            recycler.postOnAnimation {
                if (!isInteractiveQuickReplyOverlayActive) return@postOnAnimation

                normalizeInteractiveOverlayVisibleRows()
                applyInteractiveOverlayMessageFade()

                if (animate) {
                    recycler.animate().cancel()
                    recycler.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .start()
                } else {
                    recycler.alpha = 1f
                    recycler.translationY = 0f
                }
            }
        }
    }

    private fun setInteractiveQuickReplyOverlayEnabled(enabled: Boolean, animate: Boolean) {
        if (!::binding.isInitialized) return
        captureRecyclerOverlayDefaultsIfNeeded()
        isInteractiveQuickReplyOverlayActive = enabled
        if (binding.mapBackgroundView.visibility == View.VISIBLE || binding.interactiveMapButton.visibility == View.VISIBLE) {
            setupMapComposeViews()
        }

        val recycler = binding.recyclerViewMessages
        val layoutParams = recycler.layoutParams
            as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return

        if (enabled) {
            layoutParams.height = interactiveOverlayHeightPx()
            layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToTop = R.id.typingBarrier
            layoutParams.marginStart = 0
            layoutParams.marginEnd = 0
            layoutParams.bottomMargin = 0
            recycler.layoutParams = layoutParams
            recycler.setPadding(
                recyclerDefaultPaddingStartPx,
                recyclerDefaultPaddingTopPx,
                recyclerDefaultPaddingEndPx,
                recyclerDefaultPaddingBottomPx
            )
            recycler.isClickable = true
            recycler.isFocusable = true
            recycler.isNestedScrollingEnabled = false
            recycler.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            recycler.elevation = 0f
            recycler.translationZ = 0f
            recycler.visibility = if (hasVisibleChatMessages()) View.VISIBLE else View.INVISIBLE
            updateQuickReplyTouchShieldVisibility()
            binding.interactiveMapButton.elevation = dpToPx(20).toFloat()
            binding.interactiveMapButton.translationZ = dpToPx(20).toFloat()
            binding.interactiveMapButton.bringToFront()
            binding.layoutInput.elevation = dpToPx(24).toFloat()
            binding.layoutInput.translationZ = dpToPx(24).toFloat()
            binding.layoutInput.bringToFront()
            recycler.animate().cancel()
            recycler.alpha = if (animate) 0f else 1f
            recycler.translationY = if (animate) dpToPx(18).toFloat() else 0f
            syncInteractiveOverlayChrome()
            hideScrollFabImmediately()
            stabilizeInteractiveOverlayLayout(animate = animate)
            return
        }

        layoutParams.height = 0
        layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        layoutParams.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        layoutParams.bottomToTop = R.id.typingBarrier
        layoutParams.marginStart = 0
        layoutParams.marginEnd = 0
        layoutParams.bottomMargin = 0
        recycler.layoutParams = layoutParams
        recycler.setPadding(
            recyclerDefaultPaddingStartPx,
            recyclerDefaultPaddingTopPx,
            recyclerDefaultPaddingEndPx,
            recyclerDefaultPaddingBottomPx
        )
        recycler.isNestedScrollingEnabled = false
        recycler.background = null
        recycler.elevation = 0f
        recycler.translationZ = 0f
        updateQuickReplyTouchShieldVisibility()
        binding.interactiveMapButton.elevation = dpToPx(20).toFloat()
        binding.interactiveMapButton.translationZ = 0f
        binding.layoutInput.elevation = 0f
        binding.layoutInput.translationZ = 0f
        recycler.alpha = 1f
        recycler.translationY = 0f
        applyInteractiveOverlayMessageFade()
    }

    private fun toggleQuickReplyInputInInteractiveMode() {
        if (_blockStatus.value.iBlockedThem) {
            enforceBlockedInputUiState()
            return
        }

        val inputBar = binding.layoutInput
        val mapButton = binding.interactiveMapButton
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val inputHeight = inputBar.height.takeIf { it > 0 }?.toFloat() ?: 200f

        val isQuickReplyVisible = inputBar.visibility == View.VISIBLE &&
            inputBar.alpha > 0.5f &&
            kotlin.math.abs(inputBar.translationY) < 1f

        if (isQuickReplyVisible) {
            val recycler = binding.recyclerViewMessages
            val buttonDeltaY: Float = run {
                val btnH = mapButton.height
                val pill = binding.inputPill
                val pillH = pill.height
                if (btnH == 0 || pillH == 0) return@run 0f
                val btnLoc = IntArray(2).also { mapButton.getLocationOnScreen(it) }
                val pillLoc = IntArray(2).also { pill.getLocationOnScreen(it) }
                (pillLoc[1] + pillH.toFloat()) - (btnLoc[1] + btnH.toFloat())
            }

            binding.etMessageInput.clearFocus()
            imm.hideSoftInputFromWindow(binding.etMessageInput.windowToken, 0)

            mapButton.animate().cancel()
            mapButton.animate()
                .translationY(buttonDeltaY)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()

            inputBar.animate().cancel()
            inputBar.animate()
                .translationY(inputHeight)
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .withEndAction {
                    inputBar.visibility = View.INVISIBLE
                }
                .start()

            recycler.animate().cancel()
            recycler.visibility = if (hasVisibleChatMessages()) View.VISIBLE else View.INVISIBLE
            recycler.alpha = 1f
            recycler.translationY = 0f
            recycler.animate()
                .translationY(inputHeight)
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .withEndAction {
                    recycler.visibility = View.INVISIBLE
                    setInteractiveQuickReplyOverlayEnabled(enabled = false, animate = false)
                }
                .start()
            return
        }

        showInputBarIfAllowed()
        setInteractiveQuickReplyOverlayEnabled(enabled = true, animate = true)

        mapButton.animate().cancel()
        mapButton.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .start()

        inputBar.animate().cancel()
        inputBar.visibility = View.VISIBLE
        inputBar.translationY = inputHeight
        inputBar.alpha = 0f
        inputBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator(2.1f))
            .start()

        binding.etMessageInput.clearFocus()
    }

    /**
     * Animate chat bubbles, input bar, and the interactive-map button when toggling
     * interactive map mode.
     *
     * Enter interactive mode (isInteractive = true):
     *   - Incoming bubbles slide out to the LEFT, outgoing to the RIGHT (staggered).
     *   - Input bar slides DOWN + fades out.
     *   - "Back to Chat" button slides DOWN in perfect sync, decelerating to settle
     *     exactly where the input bar was — creating a seamless position-swap morph.
     *
     * Return from interactive mode (isInteractive = false):
     *   - Button accelerates UP back to its home position.
     *   - Input bar slides UP + fades in, timed to arrive just as bubbles re-enter.
     *   - Incoming bubbles slide in from the LEFT, outgoing from the RIGHT (staggered).
     *
     * No scale transforms on the container — avoids layout recalculations and jank.
     * All animations target 60 fps via hardware-accelerated translationX/Y + alpha.
     */
    private fun animateChatContentForInteractiveMode(isInteractive: Boolean) {
        val recycler  = binding.recyclerViewMessages
        val inputBar  = binding.layoutInput
        val mapButton = binding.interactiveMapButton
        val blockedByMe = _blockStatus.value.iBlockedThem

        if (!isInteractive) {
            setInteractiveQuickReplyOverlayEnabled(enabled = false, animate = false)
        }

        // Keep the container at neutral transforms so only individual children animate.
        binding.chatContentContainer.apply {
            animate().cancel()
            alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f
        }

        // Map view types to bubble direction.
        // Incoming: odd types 1,3,5,7,9,11,15,17,19  |  Outgoing: even types 2,4,6,8,10,12,16,18,20
        val incomingTypes = setOf(1, 3, 5, 7, 9, 11, 15, 17, 19)
        val outgoingTypes = setOf(2, 4, 6, 8, 10, 12, 16, 18, 20)

        val screenWidth = recycler.width.takeIf { it > 0 }?.toFloat() ?: 1080f
        val slideDistance = screenWidth * 0.55f
        
        // Play the swipe sound effect
        ensureSoundPoolInitialized()
        if (!isSoundPoolReleased && ::soundPool.isInitialized && soundSwipeId != 0) {
            soundPool.play(soundSwipeId, 0.5f, 0.5f, 1, 0, 1.0f)
        }

        // ── Shared timing constants ─────────────────────────────────────────────────
        // The input bar and the map button use identical delay + duration so they move
        // as one coordinated unit (the "position swap" morph).
        val SWAP_DURATION = 240L
        val SWAP_DELAY    = 30L

        if (isInteractive) {
            // ── EXIT: bubbles slide off-screen ────────────────────────────────────
            val childCount = recycler.childCount
            for (i in 0 until childCount) {
                val child = recycler.getChildAt(i) ?: continue
                val adapterPos = recycler.getChildAdapterPosition(child)
                val viewType = if (adapterPos != RecyclerView.NO_POSITION) {
                    chatAdapter.getItemViewType(adapterPos)
                } else -1

                val targetX = when (viewType) {
                    in incomingTypes -> -slideDistance   // incoming → slide left
                    in outgoingTypes ->  slideDistance   // outgoing → slide right
                    else             ->  0f              // date headers, etc.: fade only
                }

                // Stagger top-to-bottom so the exit cascade is clearly visible.
                val delay = (i * 22L).coerceAtMost(110L)
                child.animate().cancel()
                child.animate()
                    .translationX(targetX)
                    .alpha(0f)
                    .setDuration(260)
                    .setStartDelay(delay)
                    .setInterpolator(AccelerateInterpolator(1.8f))
                    .start()
            }

            // ── EXIT: input bar slides down ───────────────────────────────────────
            val inputHeight = inputBar.height.takeIf { it > 0 }?.toFloat() ?: 200f
            inputBar.animate().cancel()
            inputBar.animate()
                .translationY(inputHeight)
                .alpha(0f)
                .setDuration(SWAP_DURATION)
                .setStartDelay(SWAP_DELAY)
                .setInterpolator(AccelerateInterpolator(1.5f))
                .withEndAction {
                    recycler.visibility = View.INVISIBLE
                    inputBar.visibility = View.INVISIBLE
                    // Reset every child so RecyclerView recycling doesn't carry over transforms.
                    for (j in 0 until recycler.childCount) {
                        recycler.getChildAt(j)?.apply { translationX = 0f; alpha = 1f }
                    }
                }
                .start()

            // ── EXIT: button slides down to the exact bottom-safe position of the input pill ───
            // We measure against `inputPill` (the visible rounded pill) rather than
            // `layoutInput` (which includes a transparent paddingBottom = navBars.bottom).
            // The dock can now be taller than the old centered pill, so align bottom edges
            // instead of centers to preserve the original safe-area clearance above the nav bar.
            if (blockedByMe) {
                // Blocked state has no input bar; keep the map button anchored above the blocked banner.
                mapButton.animate().cancel()
                mapButton.translationY = 0f
                updateInteractiveMapButtonBottomMargin()
            } else {
                val buttonDeltaY: Float = run {
                    val btnH  = mapButton.height
                    val pill  = binding.inputPill
                    val pillH = pill.height
                    if (btnH == 0 || pillH == 0) return@run 0f
                    val btnLoc  = IntArray(2).also { mapButton.getLocationOnScreen(it) }
                    val pillLoc = IntArray(2).also { pill.getLocationOnScreen(it) }
                    // Bottom-to-bottom delta (positive = button must move down)
                    (pillLoc[1] + pillH.toFloat()) - (btnLoc[1] + btnH.toFloat())
                }
                mapButton.animate().cancel()
                mapButton.animate()
                    .translationY(buttonDeltaY)
                    .setDuration(SWAP_DURATION)
                    // 100ms after the input starts sliding — gives the input bar a visible
                    // head-start so the button visually "follows" rather than moving simultaneously.
                    .setStartDelay(SWAP_DELAY + 100L)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .withStartAction {
                        // INTENSE Modern "Resistance" feel:
                        // Using multiple haptic pulses to heighten the sensation of breaking static friction.
                        mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        mapButton.postDelayed({
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.TOGGLE_ON)
                            }
                        }, 50)
                    }
                    .withEndAction {
                        // INTENSE Modern "Thump" feel:
                        // Combining a heavy click with a deep gesture-end pulse for a "double-thump" landing.
                        mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        mapButton.postDelayed({
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                            } else {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_RELEASE)
                            }
                        }, 40)
                    }
                    .start()
            }

        } else {
            // ── RETURN: button springs back up first ──────────────────────────────
            mapButton.animate().cancel()
            if (blockedByMe) {
                mapButton.translationY = 0f
                updateInteractiveMapButtonBottomMargin()
            } else {
                mapButton.animate()
                    .translationY(0f)
                    .setDuration(260)
                    .setStartDelay(0L)
                    // AccelerateInterpolator mirrors the decelerate used on entry so the
                    // motion feels like the same arc reversed.
                    .setInterpolator(AccelerateInterpolator(1.8f))
                    .withStartAction {
                        // INTENSE Modern "Resistance" feel for return trigger:
                        mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        mapButton.postDelayed({
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.TOGGLE_ON)
                            }
                        }, 50)
                    }
                    .withEndAction {
                        // INTENSE Modern "Thump" feel for return landing:
                        mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        mapButton.postDelayed({
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                            } else {
                                mapButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_RELEASE)
                            }
                        }, 40)
                    }
                    .start()
            }

            // ── RETURN: input bar slides up and fades in ──────────────────────────
            recycler.visibility = View.VISIBLE
            if (_blockStatus.value.iBlockedThem) {
                inputBar.animate().cancel()
                inputBar.translationY = 0f
                inputBar.alpha = 1f
                inputBar.visibility = View.GONE
                enforceBlockedInputUiState()
            } else {
                inputBar.visibility = View.VISIBLE

                val inputHeight = inputBar.height.takeIf { it > 0 }?.toFloat() ?: 200f
                inputBar.animate().cancel()
                inputBar.translationY = inputHeight
                inputBar.alpha = 0f
                inputBar.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(340)
                    .setStartDelay(60L)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .start()
            }

            // ── RETURN: bubbles slide in from their sides ─────────────────────────
            // Pre-position each visible bubble at its off-screen starting point.
            val childCount = recycler.childCount
            for (i in 0 until childCount) {
                val child = recycler.getChildAt(i) ?: continue
                val adapterPos = recycler.getChildAdapterPosition(child)
                val viewType = if (adapterPos != RecyclerView.NO_POSITION) {
                    chatAdapter.getItemViewType(adapterPos)
                } else -1

                val startX = when (viewType) {
                    in incomingTypes -> -slideDistance
                    in outgoingTypes ->  slideDistance
                    else             ->  0f
                }
                child.animate().cancel()
                child.translationX = startX
                child.alpha = 0f
            }

            // Slide bubbles in bottom-to-top with a gentle stagger.
            for (i in childCount - 1 downTo 0) {
                val child = recycler.getChildAt(i) ?: continue
                val delay = ((childCount - 1 - i) * 26L).coerceAtMost(140L)
                child.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(370)
                    .setStartDelay(delay)
                    .setInterpolator(DecelerateInterpolator(2.5f))
                    .start()
            }
        }
    }

    /** Instantly reset all chat content visibility and transforms (e.g. when disabling map). */
    private fun resetChatContentFromInteractiveMode() {
        val container = binding.chatContentContainer
        val recycler  = binding.recyclerViewMessages
        val inputBar  = binding.layoutInput
        val mapButton = binding.interactiveMapButton
        setInteractiveQuickReplyOverlayEnabled(enabled = false, animate = false)

        // Cancel any in-flight animations.
        container.animate().cancel()
        inputBar.animate().cancel()
        mapButton.animate().cancel()
        for (i in 0 until recycler.childCount) {
            recycler.getChildAt(i)?.animate()?.cancel()
        }

        // Restore container to neutral transforms.
        container.alpha = 1f; container.translationY = 0f
        container.scaleX = 1f; container.scaleY = 1f

        // Restore every RecyclerView child.
        for (i in 0 until recycler.childCount) {
            recycler.getChildAt(i)?.apply { translationX = 0f; alpha = 1f }
        }

        // Restore input bar and map button.
        inputBar.translationY = 0f
        inputBar.alpha = 1f
        mapButton.translationY = 0f

        // Ensure both are visible.
        recycler.visibility = View.VISIBLE
        showInputBarIfAllowed()
    }

    /** Remove Compose content and hide the map views. */
    private fun teardownMapComposeViews() {
        mapBackgroundManager?.stopNavigation()
        setInteractiveQuickReplyOverlayEnabled(enabled = false, animate = false)
        interactiveMapVisibleDockRect = null
        binding.interactiveMapButton.systemGestureExclusionRects = emptyList()
        binding.fabScrollToBottom.translationY = 0f
        binding.mapBackgroundView.visibility = View.GONE
        binding.liveLocationBannerView.visibility = View.GONE
        binding.interactiveMapButton.visibility = View.GONE
        binding.routingInfoBannerView.visibility = View.GONE
    }

    /** Update live banner visibility based on current state. */
    private fun updateLiveBannerVisibility() {
        val mgr = mapBackgroundManager ?: return
        // Show the banner whenever the map is enabled — the composable itself
        // handles which content to display based on live-sharing state.
        binding.liveLocationBannerView.visibility =
            if (mgr.isMapEnabled) View.VISIBLE else View.GONE
    }

    /**
     * Shows a language selector bottom sheet for auto-translate.
     * When a language is selected, auto-translate is enabled with that language.
     */
    private fun showAutoTranslateLanguageSelector() {
        val currentLang = translationPrefs.targetLanguageCode
        val sheet = com.glyph.glyph_v3.ui.chat.translation.LanguageSelectorSheet.newInstance(
            selectedCode = currentLang
        ) { selectedLanguage ->
            translationPrefs.targetLanguageCode = selectedLanguage.code
            translationPrefs.isAutoTranslateEnabled = true
            Toast.makeText(
                this,
                getString(R.string.auto_translate_enabled, selectedLanguage.displayName),
                Toast.LENGTH_SHORT
            ).show()
            // Only future incoming messages will be auto-translated.
            // Existing messages are not affected to avoid performance overhead.
        }
        sheet.show(supportFragmentManager, "auto_translate_language")
    }

    private fun updateHeaderInfo() {
        val gatedAvatarSource = userAvatarState.value
        val headerInitial = otherUsername.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"

        // Set user name
        binding.tvUserName.text = otherUsername.ifEmpty { "Unknown" }
        binding.tvProfilePictureInitial.text = headerInitial

        // Load profile picture - try local cache first for instant display
        if (gatedAvatarSource.isNotEmpty() && !_blockStatus.value.isBlocked && !otherUserId.isNullOrEmpty()) {
            binding.tvProfilePictureInitial.visibility = View.GONE
            val avatarRequestSizePx = chatAvatarRequestSizePx()
            val localAvatarPath = if (isGroupChat) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chatId!!)
            } else {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId!!)
            }
            
            if (localAvatarPath != null) {
                // Load from local storage - INSTANT
                // Use signature() with file timestamp to force Glide to reload when file changes
                val file = java.io.File(localAvatarPath)
                Glide.with(this)
                    .load(file)
                    .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .transform(CircleCrop())
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                    .dontAnimate()
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(binding.ivProfilePicture)
                
                // Note: Background avatar sync is handled by startAvatarAutoSync()
                // which runs after 10 seconds to check for updates
            } else {
                // Fallback to URL (first time load)
                Glide.with(this)
                    .load(gatedAvatarSource)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .transform(CircleCrop())
                    .override(avatarRequestSizePx, avatarRequestSizePx)
                    .dontAnimate()
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(binding.ivProfilePicture)
                
                // Cache in background for next time
                lifecycleScope.launch(Dispatchers.IO) {
                    val cached = if (isGroupChat) {
                        com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheGroupAvatar(
                            chatId!!,
                            gatedAvatarSource,
                            this@ChatActivity
                        )
                    } else {
                        com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(
                            otherUserId!!,
                            gatedAvatarSource,
                            this@ChatActivity
                        )
                    }
                    if (cached) {
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                refreshMapBackgroundAvatars()
                            }
                        }
                    }
                }
            }
        } else {
            Glide.with(this).clear(binding.ivProfilePicture)
            binding.ivProfilePicture.setImageDrawable(null)
            binding.tvProfilePictureInitial.visibility = View.VISIBLE
        }
    }

    private fun primeInitialHeaderIdentity() {
        userNameState.value = otherUsername
        val headerInitial = otherUsername.firstOrNull { !it.isWhitespace() }
            ?.uppercaseChar()
            ?.toString()
            ?: "?"

        binding.tvUserName.text = otherUsername.ifEmpty { "Unknown" }
        binding.tvProfilePictureInitial.text = headerInitial

        val visibleAvatar = resolveVisibleHeaderAvatarSource()
        userAvatarState.value = visibleAvatar
        if (visibleAvatar.isBlank() || (!isGroupChat && otherUserId.isNullOrEmpty())) {
            Glide.with(this).clear(binding.ivProfilePicture)
            binding.ivProfilePicture.setImageDrawable(null)
            binding.tvProfilePictureInitial.visibility = View.VISIBLE
            return
        }

        binding.tvProfilePictureInitial.visibility = View.GONE
        if (seedHeaderAvatarFromRetained(binding.ivProfilePicture)) return

        val avatarRequestSizePx = chatAvatarRequestSizePx()
        var localPath = if (isGroupChat) {
            chatId?.let { com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(it) }
        } else {
            otherUserId?.let { com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(it) }
        }
        if (localPath == null && visibleAvatar.isNotEmpty() && !visibleAvatar.startsWith("http") && java.io.File(visibleAvatar).exists()) {
            localPath = visibleAvatar
        }
        if (localPath != null) {
            val drawable = loadLocalAvatarSynchronously(localPath, avatarRequestSizePx)
            if (drawable != null) {
                binding.ivProfilePicture.setImageDrawable(drawable)
                return
            }
        }
        val localFile = localPath?.let { java.io.File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val request = Glide.with(this)
            .load(localFile ?: visibleAvatar)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
            .transform(CircleCrop())
            .override(avatarRequestSizePx, avatarRequestSizePx)
            .dontAnimate()

        if (localFile != null) {
            request.signature(com.bumptech.glide.signature.ObjectKey(localFile.lastModified()))
        }

        request.into(binding.ivProfilePicture)
    }

    private fun resolveVisibleHeaderAvatarSource(
        visibilityState: ProfilePhotoVisibilityState? = otherUserId?.let {
            AvatarVisibilityRepository.getCachedProfilePhotoVisibility(it)
        }
    ): String {
        if (isGroupChat) return otherUserAvatar.takeIf { it.isNotBlank() }.orEmpty()

        val localPeerAvatarAvailable = otherUserId?.let {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(it)
        } != null
        val canOptimisticallyShowLocalAvatar =
            visibilityState?.isResolved != true && localPeerAvatarAvailable

        return otherUserAvatar.takeIf {
            !_blockStatus.value.isBlocked &&
                it.isNotBlank() &&
                (visibilityState?.isVisible == true || canOptimisticallyShowLocalAvatar)
        }.orEmpty()
    }

    private fun fetchOtherUserInfo() {
        // Phase 5: groups have no "other user" — hydrate header from local group metadata instead.
        if (isGroupChat) {
            observeGroupMetadata()
            return
        }
        val otherId = otherUserId ?: return
        firebaseRepository.getUser(otherId) { user ->
            if (user != null) {
                otherUserPhone = user.phoneNumber
                otherUserAvatar = user.profileImageUrl
                updateRegisteredUsersCache()
                // Cache phone number and resolve display name with device contact priority
                if (user.phoneNumber.isNotBlank()) {
                    ContactDisplayNameResolver.cacheUserPhone(user.id, user.phoneNumber)
                }
                otherUsername = ContactDisplayNameResolver.getDisplayName(
                    otherUserId = otherId,
                    remoteProfileName = user.username,
                    remotePhoneNumber = user.phoneNumber
                )
                // Update header with fetched info
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    userNameState.value = otherUsername
                    chatAdapter.otherUsername = otherUsername
                    applyOtherUserAvatarVisibility()
                }
            }
            ensureLocalChatExists()
        }
    }

    private fun observeContactCacheUpdates() {
        lifecycleScope.launch {
            ContactDisplayNameResolver.cacheVersion
                .drop(1) // Skip initial emission (0L) to avoid racing with fetchOtherUserInfo
                .collect { _ ->
                // When device contacts change, re-resolve the header display name
                if (isFinishing || isDestroyed) return@collect
                if (!isGroupChat && otherUserId != null) {
                    val resolvedName = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = otherUserId,
                        remoteProfileName = otherUsername,
                        remotePhoneNumber = otherUserPhone
                    )
                    if (resolvedName != otherUsername && resolvedName.isNotBlank()) {
                        otherUsername = resolvedName
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            userNameState.value = otherUsername
                            binding.tvUserName.text = otherUsername
                            chatAdapter.otherUsername = otherUsername
                        }
                    }
                }
            }
        }
    }

    private fun ensureLocalChatExists() {
        val id = chatId ?: return
        // Phase 5: group LocalChat row is created by GroupChatRepository.createGroup
        // (or hydrated by message receive). Don't run the 1:1 fallback for groups.
        if (isGroupChat) {
            lifecycleScope.launch { repository.clearUnreadCount(id) }
            return
        }
        val otherId = otherUserId ?: return

        lifecycleScope.launch {
            repository.getOrCreateLocalChat(id, otherId, otherUsername.ifEmpty { "Unknown" }, otherUserAvatar)
            repository.clearUnreadCount(id)
        }
    }

    /**
     * Phase 5: observe the local group chat row and reflect its name / icon / member count
     * in the header. Mirrors the role of fetchOtherUserInfo() for 1:1 chats.
     */
    private fun observeGroupMetadata() {
        val id = chatId ?: return
        val chatDao = (applicationContext as GlyphApplication).getOrCreateAppDatabase().chatDao()
        // Phase 6: enable per-bubble sender attribution for groups.
        chatAdapter.isGroupChat = true
        chatAdapter.senderNameResolver = { uid -> groupSenderNamesCache[uid] }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatDao.observeChatById(id).collect { chat ->
                    if (chat == null || !chat.isGroup) return@collect
                    if (isFinishing || isDestroyed) return@collect
                    val name = chat.groupName.ifBlank { "Group" }
                    val localGroupAvatar = com.glyph.glyph_v3.data.cache.AvatarCacheManager
                        .getLocalGroupAvatarPath(id)
                    val resolvedGroupAvatar = localGroupAvatar ?: chat.groupIconUrl
                    otherUsername = name
                    otherUserAvatar = resolvedGroupAvatar
                    userNameState.value = name
                    userAvatarState.value = resolvedGroupAvatar
                    chatAdapter.otherUsername = name
                    binding.tvUserName.text = name
                    val participants = com.glyph.glyph_v3.data.repo.GroupChatRepository
                        .decodeStringList(chat.participantsJson)
                    groupParticipantIdsForTyping = participants
                    val memberCount = participants.size
                    groupIntroName = name
                    groupIntroAvatarUrl = resolvedGroupAvatar
                    groupIntroDescription = chat.groupDescription
                    groupIntroMemberCount = memberCount
                    refreshGroupIntroListIfNeeded()
                    val subtitle = buildGroupSubtitle(participants, memberCount)
                    groupMemberSubtitle = subtitle
                    applyGroupMemberSubtitleIfNoPresence(subtitle)
                    resolveGroupSubtitleNames(participants, memberCount)
                    // Start (or restart) real-time group presence observation whenever
                    // the participant list changes (members added / removed).
                    startGroupPresenceObservation(participants)
                    if (resolvedGroupAvatar.isNotBlank()) {
                        try {
                            Glide.with(this@ChatActivity)
                                .load(resolvedGroupAvatar)
                                .transform(CircleCrop())
                                .override(chatAvatarRequestSizePx(), chatAvatarRequestSizePx())
                                .dontAnimate()
                                .placeholder(R.drawable.ic_default_avatar)
                                .error(R.drawable.ic_default_avatar)
                                .into(binding.ivProfilePicture)
                            binding.tvProfilePictureInitial.visibility = View.GONE
                        } catch (e: Exception) {
                            Log.w("ChatActivity", "Failed to load group icon", e)
                        }
                    } else {
                        binding.ivProfilePicture.setImageDrawable(null)
                        binding.tvProfilePictureInitial.text = name.firstOrNull()
                            ?.uppercaseChar()?.toString() ?: "G"
                        binding.tvProfilePictureInitial.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun buildGroupSubtitle(participants: List<String>, memberCount: Int): String {
        if (participants.isEmpty()) return "Group"
        if (participants.size > 4) return "$memberCount members"
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val orderedParticipants = participants.filter { it != currentUid } + participants.filter { it == currentUid }
        val names = orderedParticipants.map { uid ->
            when {
                uid == currentUid -> "You"
                groupParticipantNamesCache[uid]?.isNotBlank() == true -> groupParticipantNamesCache[uid].orEmpty()
                else -> "Member"
            }
        }
        return names.joinToString(", ").ifBlank { "$memberCount members" }
    }

    private fun resolveGroupSubtitleNames(participants: List<String>, memberCount: Int) {
        if (participants.isEmpty() || participants.size > 4) return
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        var anyResolved = false

        participants.forEach { uid ->
            if (uid == currentUid || !groupParticipantNamesCache[uid].isNullOrBlank()) return@forEach
            val cachedName = registeredUsersCache[uid]?.username?.takeIf { it.isNotBlank() }
            if (!cachedName.isNullOrBlank()) {
                groupParticipantNamesCache[uid] = cachedName
                groupSenderNamesCache[uid] = cachedName
                anyResolved = true
            }
        }

        if (anyResolved) {
            val subtitle = buildGroupSubtitle(participants, memberCount)
            groupMemberSubtitle = subtitle
            applyGroupMemberSubtitleIfNoPresence(subtitle)
            chatAdapter.refreshGroupSenderNames()
        }

        val missing = participants.filter { uid ->
            uid != currentUid && groupParticipantNamesCache[uid].isNullOrBlank() && groupParticipantNamesPending.add(uid)
        }
        if (missing.isEmpty()) {
            val subtitle = buildGroupSubtitle(participants, memberCount)
            groupMemberSubtitle = subtitle
            applyGroupMemberSubtitleIfNoPresence(subtitle)
            return
        }

        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        missing.forEach { uid ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val name = doc.getString("username")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("phoneNumber")?.takeIf { it.isNotBlank() }
                    val avatarUrl = doc.getString("profileImageUrl")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("profileImageFullUrl")?.takeIf { it.isNotBlank() }
                    if (!name.isNullOrBlank()) {
                        groupParticipantNamesCache[uid] = name
                        groupSenderNamesCache[uid] = name
                    }
                    if (!avatarUrl.isNullOrBlank()) {
                        groupSenderAvatarCache[uid] = avatarUrl
                    }
                }
                .addOnCompleteListener {
                    groupParticipantNamesPending.remove(uid)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val subtitle = buildGroupSubtitle(participants, memberCount)
                        groupMemberSubtitle = subtitle
                        applyGroupMemberSubtitleIfNoPresence(subtitle)
                        chatAdapter.refreshGroupSenderNames()
                    }
                }
        }
    }

    /**
     * Updates the header subtitle to [subtitle] only if group presence is NOT
     * currently showing online users. This prevents name-resolution Firestore
     * callbacks from overwriting the real-time "N online" / "Alice online" text.
     */
    private fun applyGroupMemberSubtitleIfNoPresence(subtitle: String) {
        if (activeGroupOnlineCount > 0) return
        binding.tvLastSeen.text = subtitle
        binding.tvLastSeen.visibility = if (subtitle.isNotBlank()) View.VISIBLE else View.GONE
        applyComposeHeaderStatus(subtitle, source = "group_subtitle_refresh")
    }

    private fun updateGroupTypingUsers(typingUserIds: Set<String>) {
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val normalized = typingUserIds
            .asSequence()
            .filter { it.isNotBlank() && it != selfUid }
            .toSet()

        if (normalized == activeGroupTypingUserIds) return

        activeGroupTypingUserIds = normalized
        ensureGroupTypingNamesLoaded(normalized)
        groupTypingIndicatorText = formatGroupTypingIndicatorText(normalized)
    }

    private fun ensureGroupTypingNamesLoaded(typingUserIds: Set<String>) {
        if (typingUserIds.isEmpty()) return
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        val missing = typingUserIds.filter { uid ->
            uid != selfUid &&
                groupParticipantNamesCache[uid].isNullOrBlank() &&
                groupSenderNamesCache[uid].isNullOrBlank() &&
                registeredUsersCache[uid]?.username.isNullOrBlank() &&
                groupTypingNamesPending.add(uid)
        }
        if (missing.isEmpty()) return

        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        missing.forEach { uid ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val name = doc.getString("username")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("phoneNumber")?.takeIf { it.isNotBlank() }
                    val avatarUrl = doc.getString("profileImageUrl")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("profileImageFullUrl")?.takeIf { it.isNotBlank() }
                    if (!name.isNullOrBlank()) {
                        groupParticipantNamesCache[uid] = name
                        groupSenderNamesCache[uid] = name
                    }
                    if (!avatarUrl.isNullOrBlank()) {
                        groupSenderAvatarCache[uid] = avatarUrl
                    }
                }
                .addOnCompleteListener {
                    groupTypingNamesPending.remove(uid)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val updated = formatGroupTypingIndicatorText(activeGroupTypingUserIds)
                        if (updated != groupTypingIndicatorText) {
                            groupTypingIndicatorText = updated
                            refreshTypingIndicatorListIfNeeded()
                        }
                    }
                }
        }
    }

    private fun resolveGroupTypingDisplayName(uid: String): String? {
        return groupParticipantNamesCache[uid]?.takeIf { it.isNotBlank() }
            ?: groupSenderNamesCache[uid]?.takeIf { it.isNotBlank() }
            ?: registeredUsersCache[uid]?.username?.takeIf { it.isNotBlank() }
            ?: registeredUsersCache[uid]?.phoneNumber?.takeIf { it.isNotBlank() }
    }

    private fun formatGroupTypingIndicatorText(typingUserIds: Set<String>): String {
        if (typingUserIds.isEmpty()) return ""

        val names = linkedSetOf<String>()
        typingUserIds.forEach { uid ->
            resolveGroupTypingDisplayName(uid)?.trim()?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }

        val knownNames = names.toList()
        val unknownCount = (typingUserIds.size - knownNames.size).coerceAtLeast(0)

        return when {
            knownNames.isEmpty() -> {
                if (typingUserIds.size == 1) "Someone is typing..." else "${typingUserIds.size} people are typing..."
            }
            knownNames.size == 1 && unknownCount == 0 -> {
                "${knownNames[0]} is typing..."
            }
            knownNames.size >= 2 && unknownCount == 0 && typingUserIds.size == 2 -> {
                "${knownNames[0]} and ${knownNames[1]} are typing..."
            }
            knownNames.size >= 2 && unknownCount == 0 -> {
                val others = (typingUserIds.size - 2).coerceAtLeast(0)
                if (others == 0) {
                    "${knownNames[0]} and ${knownNames[1]} are typing..."
                } else {
                    "${knownNames[0]}, ${knownNames[1]}, and $others others are typing..."
                }
            }
            knownNames.size == 1 && unknownCount == 1 -> {
                "${knownNames[0]} and 1 other are typing..."
            }
            knownNames.size == 1 -> {
                "${knownNames[0]} and $unknownCount others are typing..."
            }
            else -> {
                "${knownNames[0]}, ${knownNames[1]}, and $unknownCount others are typing..."
            }
        }
    }

    private fun currentTypingIndicatorText(isTypingActive: Boolean): String {
        if (!isTypingActive) return ""
        return if (isGroupChat) groupTypingIndicatorText else expressiveLiveText
    }

    private fun shouldUseExpressiveTypingIndicator(isTypingActive: Boolean): Boolean {
        return if (isGroupChat) {
            false
        } else {
            isExpressiveMutuallyEnabled || (isTypingActive && expressivePrefs.isEnabled.value)
        }
    }

    private fun shouldShowTypingIndicator(): Boolean {
        if (isGroupChat) {
            return isOtherUserTyping
        }

        // In 1:1 chats, once Live Expressive Typing is enabled, suppress the legacy
        // three-dot fallback and only surface the expressive indicator when live text
        // is actually present.
        val expressiveModeEnabled = expressivePrefs.isEnabled.value || isExpressiveMutuallyEnabled
        return if (expressiveModeEnabled) {
            isExpressiveActive
        } else {
            isOtherUserTyping
        }
    }

    private fun buildTypingAdjustedList(baseList: List<ChatListItem>): List<ChatListItem> {
        val showTypingIndicator = shouldShowTypingIndicator()
        val trimmedBase = baseList.dropLastWhile { it is ChatListItem.TypingIndicator }
        if (!showTypingIndicator) return trimmedBase

        val useExpressive = shouldUseExpressiveTypingIndicator(showTypingIndicator)
        val indicatorText = currentTypingIndicatorText(showTypingIndicator)
        return trimmedBase + ChatListItem.TypingIndicator(
            isVisible = true,
            isExpressive = useExpressive,
            liveText = indicatorText,
            sentiment = expressiveSentiment
        )
    }

    private fun refreshTypingIndicatorListIfNeeded() {
        if (!::chatAdapter.isInitialized) return
        val showTypingIndicator = shouldShowTypingIndicator()
        if (!showTypingIndicator && processedListItems.none { it is ChatListItem.TypingIndicator }) return

        val base = if (chatAdapter.currentList.isNotEmpty()) chatAdapter.currentList else processedListItems
        val updatedList = buildTypingAdjustedList(base)
        processedListItems = updatedList
        chatAdapter.submitList(updatedList)
        updateDisplayedMessageIds(updatedList)
    }

    private fun openGroupInfo(openAddMembers: Boolean = false) {
        val currentChatId = chatId ?: return
        startActivity(
            com.glyph.glyph_v3.ui.groups.GroupInfoActivity.newIntent(
                context = this,
                chatId = currentChatId,
                openAddMembers = openAddMembers
            )
        )
    }

    private fun showGroupInviteUnavailable() {
        Toast.makeText(this, "Invite links are not available yet", Toast.LENGTH_SHORT).show()
    }

    private fun shouldShowGroupIntroInList(messages: List<Message>): Boolean {
        if (!isGroupChat) return false
        return messages.isEmpty() || messages.none { it.type != MessageType.SYSTEM }
    }

    private fun buildGroupIntroItem(): ChatListItem.GroupIntroItem {
        return ChatListItem.GroupIntroItem(
            chatId = chatId.orEmpty(),
            groupName = groupIntroName,
            groupAvatarUrl = groupIntroAvatarUrl,
            description = groupIntroDescription,
            memberCount = groupIntroMemberCount
        )
    }

    private fun refreshGroupIntroListIfNeeded() {
        if (!isGroupChat || !::chatAdapter.isInitialized) return
        val hasIntroItem = processedListItems.any { it is ChatListItem.GroupIntroItem }
        if (!hasIntroItem && !shouldShowGroupIntroInList(currentMessages)) return
        if (currentMessages.isEmpty() && processedListItems.isEmpty()) return

        lifecycleScope.launch {
            val showTypingIndicator = isOtherUserTyping || isExpressiveActive
            val useExpressive = shouldUseExpressiveTypingIndicator(showTypingIndicator)
            val indicatorText = currentTypingIndicatorText(showTypingIndicator)
            val updatedList = withContext(Dispatchers.Default) {
                val rawList = processMessagesWithHeaders(
                    currentMessages,
                    showTypingIndicator = showTypingIndicator,
                    newIncomingIds = emptySet(),
                    expressiveActive = useExpressive,
                    expressiveText = indicatorText,
                    expressiveSent = expressiveSentiment
                )
                TextLayoutPrecomputer.precomputeForItems(rawList)
            }
            processedListItems = updatedList
            chatAdapter.submitList(updatedList)
            updateDisplayedMessageIds(updatedList)
        }
    }

    /**
     * Phase 6: ensure every unique incoming sender id in [messages] has a display name
     * resolved into [groupSenderNamesCache]. Lookups go to the cached registered-users
     * map first, then to Firestore `/users/{uid}.username`. When any name resolves we
     * call [ChatAdapter.refreshGroupSenderNames] on the main thread so visible bubbles
     * pick it up. Outgoing senders (current user) and SYSTEM messages are skipped.
     */
    private fun ensureGroupSenderNamesLoadedFor(messages: List<Message>) {
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val needed = messages.asSequence()
            .filter { it.isIncoming && it.senderId.isNotBlank() && it.senderId != selfUid }
            .map { it.senderId }
            .distinct()
            .filter { uid -> uid !in groupSenderNamesCache && groupSenderNamesPending.add(uid) }
            .toList()
        if (needed.isEmpty()) return

        // Fast path: pull from in-process registered users cache.
        var anyResolved = false
        val resolvedNow = mutableListOf<String>()
        needed.forEach { uid ->
            val prefetched = ChatOpenPrefetcher.getGroupSenderRenderInfo(uid)
            val prefetchedName = prefetched?.displayName?.takeIf { it.isNotBlank() }
            val cachedName = registeredUsersCache[uid]?.username?.takeIf { it.isNotBlank() }
            val name = prefetchedName ?: cachedName
            if (!name.isNullOrBlank()) {
                groupSenderNamesCache[uid] = name
                groupParticipantNamesCache.putIfAbsent(uid, name)
                anyResolved = true
                resolvedNow += uid
            }
            val avatarUrl = prefetched?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: registeredUsersCache[uid]?.profileImageUrl?.takeIf { it.isNotBlank() }
            if (!avatarUrl.isNullOrBlank()) {
                groupSenderAvatarCache[uid] = avatarUrl
            }
        }
        resolvedNow.forEach(groupSenderNamesPending::remove)
        if (anyResolved) chatAdapter.refreshGroupSenderNames()

        val stillMissing = needed.filter { it !in groupSenderNamesCache }
        if (stillMissing.isEmpty()) return

        // Fall back to Firestore. Each lookup is fire-and-forget; the resolver stays nullable
        // until the name lands in the cache.
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        stillMissing.forEach { uid ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val remoteName = doc.getString("username")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("displayName")?.takeIf { it.isNotBlank() }
                    val phoneNumber = doc.getString("phoneNumber")
                    // Cache phone number for future contact name resolution
                    if (!phoneNumber.isNullOrBlank()) {
                        ContactDisplayNameResolver.cacheUserPhone(uid, phoneNumber)
                    }
                    // Resolve with device contact priority: Device contact → Remote name → Phone number
                    val resolvedName = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = uid,
                        remoteProfileName = remoteName,
                        remotePhoneNumber = phoneNumber
                    )
                    val avatarUrl = doc.getString("profileImageUrl")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("profileImageFullUrl")?.takeIf { it.isNotBlank() }
                    if (resolvedName.isNotBlank()) {
                        groupSenderNamesCache[uid] = resolvedName
                        groupParticipantNamesCache.putIfAbsent(uid, resolvedName)
                        runOnUiThread { chatAdapter.refreshGroupSenderNames() }
                    }
                    if (!avatarUrl.isNullOrBlank()) {
                        groupSenderAvatarCache[uid] = avatarUrl
                    }
                }
                .addOnCompleteListener { groupSenderNamesPending.remove(uid) }
        }
    }

    private fun primeGroupSenderCachesForMessages(messages: List<Message>) {
        if (!isGroupChat || messages.isEmpty()) return
        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        var anyResolved = false

        messages.asSequence()
            .filter { it.isIncoming && it.senderId.isNotBlank() && it.senderId != selfUid && it.type != MessageType.SYSTEM }
            .map { it.senderId }
            .distinct()
            .forEach { uid ->
                val prefetched = ChatOpenPrefetcher.getGroupSenderRenderInfo(uid)
                val prefetchedName = prefetched?.displayName?.takeIf { it.isNotBlank() }
                val fallbackName = registeredUsersCache[uid]?.username?.takeIf { it.isNotBlank() }
                val name = prefetchedName ?: fallbackName
                if (!name.isNullOrBlank()) {
                    if (groupSenderNamesCache[uid] != name) {
                        groupSenderNamesCache[uid] = name
                        anyResolved = true
                    }
                    groupParticipantNamesCache.putIfAbsent(uid, name)
                }

                val avatarUrl = prefetched?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: registeredUsersCache[uid]?.profileImageUrl?.takeIf { it.isNotBlank() }
                if (!avatarUrl.isNullOrBlank()) {
                    groupSenderAvatarCache[uid] = avatarUrl
                }
            }

        if (anyResolved && ::chatAdapter.isInitialized) {
            chatAdapter.refreshGroupSenderNames()
        }
    }

    private fun fetchCurrentUserInfo() {
        firebaseRepository.getUser { user ->
            if (user != null) {
                currentUserPhone = user.phoneNumber
                currentUserUsername = user.username
                currentUserAvatar = user.profileImageUrl
            } else {
                 val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                 currentUserPhone = authUser?.phoneNumber ?: ""
                 currentUserAvatar = authUser?.photoUrl?.toString() ?: ""
            }
            
            // Preserve local cache if available to prevent avatar flash/reload
            try {
                val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (authUser != null) {
                    val userId = authUser.uid
                    val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
                    if (localPath != null) {
                        currentUserAvatar = localPath
                    } else if (currentUserAvatar.isNotEmpty() && currentUserAvatar.startsWith("http")) {
                        // Cache is missing for current user - cache it now for next time
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val cached = com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(userId, currentUserAvatar, this@ChatActivity)
                                if (cached) {
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed) {
                                            refreshMapBackgroundAvatars()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ChatActivity", "Failed to cache current user avatar", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore cache check errors
            }

            updateRegisteredUsersCache()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                chatAdapter.setUserAvatars(
                    otherUserAvatar.takeIf { it.isNotBlank() },
                    currentUserAvatar.takeIf { it.isNotBlank() }
                )
                refreshMapBackgroundAvatars()
            }
        }
    }

    private fun refreshMapBackgroundAvatars() {
        val mgr = mapBackgroundManager ?: return

        val resolvedMyAvatar = currentUserIdOrNull()?.let { userId ->
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
        } ?: currentUserAvatar

        val resolvedOtherAvatar = otherUserId?.let { userId ->
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
        } ?: otherUserAvatar

        mgr.updateAvatarUrls(
            myAvatarUrl = resolvedMyAvatar,
            otherAvatarUrl = resolvedOtherAvatar
        )
    }

    private fun currentUserIdOrNull(): String? =
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

    /**
     * Start automatic avatar sync after 10 seconds.
     * Checks for avatar updates and automatically re-renders the header if a new avatar is found.
     */
    private fun startAvatarAutoSync() {
        val userId = otherUserId ?: return
        val avatarUrl = otherUserAvatar
        
        if (avatarUrl.isEmpty()) {
            return
        }
        
        avatarSyncJob?.cancel()
                avatarSyncJob = lifecycleScope.launch {
                    scheduleIdleTask(
                        key = "avatar_auto_sync",
                        priority = IdleTaskPriority.LOW,
                        delayMs = 10_000L,
                        reason = "chat_open"
                    ) {
                        try {
                            val updated = withContext(Dispatchers.IO) {
                                com.glyph.glyph_v3.data.cache.AvatarCacheManager.updateAvatarIfNeeded(
                                    userId,
                                    avatarUrl,
                                    this@ChatActivity
                                )
                            }

                            if (updated) {
                                withContext(Dispatchers.Main) {
                                    reloadAvatarImage(userId)
                                    refreshMapBackgroundAvatars()
                                }
                            } else {
                            }
                        } catch (e: Exception) {
                            Log.e("ChatActivity", "Error in avatar auto-sync for user $userId", e)
                        }
                    }
                }
    }
    
    /**
     * Reload avatar image from cache. Call this when a new avatar has been downloaded.
     */
    private fun loadLocalAvatarSynchronously(localPath: String, targetSizePx: Int): Drawable? {
        return try {
            val file = java.io.File(localPath)
            if (!file.exists() || file.length() <= 0) return null

            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(localPath, options)

            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null

            var inSampleSize = 1
            if (srcWidth > targetSizePx || srcHeight > targetSizePx) {
                val halfWidth = srcWidth / 2
                val halfHeight = srcHeight / 2
                while (halfWidth / inSampleSize >= targetSizePx && halfHeight / inSampleSize >= targetSizePx) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(localPath, options) ?: return null
            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                isCircular = true
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Failed to decode local avatar synchronously", e)
            null
        }
    }

    private fun reloadAvatarImage(userId: String) {
        val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
        
        if (localPath != null) {
            val avatarRequestSizePx = chatAvatarRequestSizePx()
            val drawable = loadLocalAvatarSynchronously(localPath, avatarRequestSizePx)
            if (drawable != null) {
                binding.ivProfilePicture.setImageDrawable(drawable)
                refreshMapBackgroundAvatars()
                return
            }
            val file = java.io.File(localPath)
            Glide.with(this)
                .load(file)
                .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .transform(CircleCrop())
                .override(avatarRequestSizePx, avatarRequestSizePx)
                .dontAnimate()
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivProfilePicture)
            refreshMapBackgroundAvatars()
        } else {
            Log.w("ChatActivity", "Cannot reload avatar: no cached file for user $userId")
        }
    }

    private fun chatAvatarRequestSizePx(): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (CHAT_AVATAR_SIZE_DP * density).roundToInt().coerceAtLeast(64)
    }

    private fun consumePrefetcherRetainedHeaderAvatar(chatId: String) {
        val retained = ChatOpenPrefetcher.takeRetainedAvatarPreload(chatId) ?: return
        retainedHeaderAvatarPreloads.add(retained)
    }

    private fun clearRetainedHeaderAvatarPreloadFutures() {
        if (retainedHeaderAvatarPreloads.isEmpty()) return
        val toClear = retainedHeaderAvatarPreloads.toList()
        retainedHeaderAvatarPreloads.clear()
        toClear.forEach { preload ->
            runCatching { Glide.with(applicationContext).clear(preload.target) }
        }
    }

    private fun seedHeaderAvatarFromRetained(imageView: ImageView): Boolean {
        val drawable = retainedHeaderAvatarPreloads
            .asSequence()
            .mapNotNull { preload ->
                val target = preload.target
                if (!target.isDone || target.isCancelled) return@mapNotNull null
                runCatching { target.get() }.getOrNull()
            }
            .lastOrNull()
            ?: return false

        val copied = copyHeaderAvatarDrawable(drawable) ?: return false
        imageView.setImageDrawable(copied)
        return true
    }

    private fun copyHeaderAvatarDrawable(drawable: Drawable): Drawable? {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return null
            if (bitmap.isRecycled) return null
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            return runCatching {
                BitmapDrawable(resources, bitmap.copy(config, false)).mutate()
            }.getOrNull()
        }
        return drawable.constantState?.newDrawable(resources)?.mutate()
            ?: drawable.constantState?.newDrawable()?.mutate()
    }

    private fun resolveGroupSenderAvatarForBind(userId: String): String? {
        if (userId.isBlank()) return null

        val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager
            .getLocalAvatarPath(userId)
            ?.takeIf { it.isNotBlank() }
        if (!localPath.isNullOrBlank()) {
            groupSenderAvatarCache[userId] = localPath
            return localPath
        }

        val remoteAvatar = groupSenderAvatarCache[userId]?.takeIf { it.isNotBlank() } ?: return null
        if (remoteAvatar.startsWith("http") && groupSenderAvatarCachingPending.add(userId)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cached = com.glyph.glyph_v3.data.cache.AvatarCacheManager
                        .cacheAvatar(userId, remoteAvatar, applicationContext)
                    if (cached) {
                        val refreshedLocalPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager
                            .getLocalAvatarPath(userId)
                            ?.takeIf { it.isNotBlank() }
                        if (!refreshedLocalPath.isNullOrBlank()) {
                            groupSenderAvatarCache[userId] = refreshedLocalPath
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed && ::chatAdapter.isInitialized) {
                                    chatAdapter.refreshGroupSenderNames()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatActivity", "Failed to warm group sender avatar cache for $userId", e)
                } finally {
                    groupSenderAvatarCachingPending.remove(userId)
                }
            }
        }
        return remoteAvatar
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(selectionManager, this, this)
        chatAdapter.isGroupChat = isGroupChat
        chatAdapter.senderNameResolver = { uid -> groupSenderNamesCache[uid] }
        chatAdapter.senderAvatarResolver = { uid -> resolveGroupSenderAvatarForBind(uid) }
        chatAdapter.preloadedMediaDrawableProvider = { model, widthPx, heightPx, type ->
            mediaController.getRetainedPreloadedDrawable(model, widthPx, heightPx, type)
        }
        chatAdapter.preloadedMediaByMsgIdDrawableProvider = { messageId, widthPx, heightPx, type ->
            mediaController.getRetainedPreloadedDrawableByMessageId(messageId, widthPx, heightPx, type)
        }
        chatAdapter.currentUserPhone = currentUserPhone
        chatAdapter.otherUsername = otherUsername
        chatAdapter.registeredUsersMap = registeredUsersCache.toMap()
        chatAdapter.onGroupIntroDescriptionClick = { openGroupInfo() }
        chatAdapter.onGroupIntroAddMembersClick = { openGroupInfo(openAddMembers = true) }
        chatAdapter.onGroupIntroInviteClick = { showGroupInviteUnavailable() }
        chatAdapter.onTypingIndicatorMeasured = { measured ->
            if (measured > 0) typingIndicatorHeightPx = measured
        }
        chatAdapter.onMediaDimensionsResolved = { messageId, mediaWidth, mediaHeight ->
            repository.persistMediaDimensions(messageId, mediaWidth, mediaHeight)
        }
        chatAdapter.setHasStableIds(true)
        chatAdapter.onReplyClick = { messageId ->
            scrollToMessage(messageId)
        }
        // WhatsApp-style reactions: long-press shows popup; chip tap removes user's own reaction.
        chatAdapter.onShowReactionPopup = { message, anchor, touchYScreen ->
            showReactionPopupFor(message, anchor, touchYScreen)
        }
        chatAdapter.onDismissReactionPopup = {
            dismissReactionPopup(animate = true)
        }
        chatAdapter.onReactionChipClick = chip@{ message ->
            val cid = chatId ?: return@chip
            val uid = chatAdapter.currentUserId
            // Open the WhatsApp-style reaction details sheet so the user can browse who
            // reacted, switch filter pills, add a new reaction, or remove their own.
            com.glyph.glyph_v3.ui.chat.reactions.ReactionDetailsSheet.show(
                fm = supportFragmentManager,
                reactions = message.reactions,
                currentUserId = uid,
                otherUsername = otherUsername,
                currentUserAvatar = currentUserAvatar.takeIf { it.isNotBlank() },
                otherUserAvatar = userAvatarState.value.takeIf { it.isNotBlank() },
                isGroupChat = isGroupChat,
                onRemoveOwn = {
                    if (uid != null && message.reactions.containsKey(uid)) {
                        lifecycleScope.launch {
                            repository.toggleReaction(cid, message.id, null)
                        }
                    }
                },
                onAddNew = {
                    com.glyph.glyph_v3.ui.chat.reactions.ReactionEmojiPickerSheet.show(
                        supportFragmentManager
                    ) { picked ->
                        lifecycleScope.launch {
                            repository.toggleReaction(cid, message.id, picked)
                        }
                    }
                },
                onChangeOwn = {
                    com.glyph.glyph_v3.ui.chat.reactions.ReactionEmojiPickerSheet.show(
                        supportFragmentManager
                    ) { picked ->
                        lifecycleScope.launch {
                            repository.toggleReaction(cid, message.id, picked)
                        }
                    }
                }
            )
        }
        chatAdapter.setUserAvatars(
            userAvatarState.value.takeIf { it.isNotBlank() },
            currentUserAvatar.takeIf { it.isNotBlank() }
        )
        chatAdapter.onRetryUpload = { message ->
            retryMediaUpload(message)
        }
        recyclerCoordinator = RecyclerCoordinator(this, binding.recyclerViewMessages, chatAdapter)
        
        binding.recyclerViewMessages.apply {
            recyclerCoordinator.configureRecyclerView()
            // Start invisible — prefillRecentMessagesAsync fades in when content is ready.
            // This prevents the "empty RecyclerView flash → content pop-in" on cold start.
            alpha = 0f

            binding.quickReplyTouchShield.setOnTouchListener(object : View.OnTouchListener {
                private val overlayTouchSlop = ViewConfiguration.get(this@ChatActivity).scaledTouchSlop
                private var overlayDownX = 0f
                private var overlayDownY = 0f
                private var overlayVerticalDragActive = false
                private val shieldLocation = IntArray(2)
                private val recyclerLocation = IntArray(2)

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    if (!isInteractiveQuickReplyOverlayActive) return false

                    val visibleDockRect = interactiveMapVisibleDockRect
                    if (visibleDockRect?.contains(event.rawX.toInt(), event.rawY.toInt()) == true) {
                        return false
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            overlayDownX = event.x
                            overlayDownY = event.y
                            overlayVerticalDragActive = false
                            if (this@apply.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                                this@apply.stopScroll()
                            }
                            requestParentInterceptDisallow(view, true)
                            requestParentInterceptDisallow(this@apply, true)
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.x - overlayDownX
                            val deltaY = event.y - overlayDownY
                            val verticalDistance = kotlin.math.abs(deltaY)
                            val horizontalDistance = kotlin.math.abs(deltaX)

                            if (!overlayVerticalDragActive &&
                                verticalDistance > overlayTouchSlop &&
                                verticalDistance > horizontalDistance
                            ) {
                                overlayVerticalDragActive = true
                            }

                            if (overlayVerticalDragActive) {
                                requestParentInterceptDisallow(view, true)
                                requestParentInterceptDisallow(this@apply, true)
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            overlayVerticalDragActive = false
                            requestParentInterceptDisallow(view, false)
                            requestParentInterceptDisallow(this@apply, false)
                        }
                    }

                    view.getLocationOnScreen(shieldLocation)
                    this@apply.getLocationOnScreen(recyclerLocation)
                    val forwardedEvent = MotionEvent.obtain(event)
                    forwardedEvent.offsetLocation(
                        (shieldLocation[0] - recyclerLocation[0]).toFloat(),
                        (shieldLocation[1] - recyclerLocation[1]).toFloat()
                    )
                    this@apply.dispatchTouchEvent(forwardedEvent)
                    forwardedEvent.recycle()
                    return true
                }
            })

            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                private val overlayTouchSlop = ViewConfiguration.get(this@ChatActivity).scaledTouchSlop
                private var overlayDownX = 0f
                private var overlayDownY = 0f
                private var overlayVerticalDragActive = false

                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (!isInteractiveQuickReplyOverlayActive) return false

                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            overlayDownX = e.x
                            overlayDownY = e.y
                            overlayVerticalDragActive = false
                            if (rv.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                                rv.stopScroll()
                            }
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = e.x - overlayDownX
                            val deltaY = e.y - overlayDownY
                            val verticalDistance = kotlin.math.abs(deltaY)
                            val horizontalDistance = kotlin.math.abs(deltaX)

                            if (!overlayVerticalDragActive &&
                                verticalDistance > overlayTouchSlop &&
                                verticalDistance > horizontalDistance
                            ) {
                                overlayVerticalDragActive = true
                                requestParentInterceptDisallow(rv, true)
                            }

                            if (overlayVerticalDragActive) {
                                requestParentInterceptDisallow(rv, true)
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            overlayVerticalDragActive = false
                        }
                    }
                    return false
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                    if (!disallowIntercept) {
                        overlayVerticalDragActive = false
                    }
                }
            })

            // Show/hide empty chat overlay based on adapter item count
            chatAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                private var wasEmpty = true
                private fun updateEmptyState() {
                    val hasMessages = chatAdapter.currentList.any {
                        it is ChatListItem.MessageItem || it is ChatListItem.GroupIntroItem
                    }
                    if (!isInteractiveQuickReplyOverlayActive) {
                        binding.emptyChatOverlay.visibility = if (hasMessages) View.GONE else View.VISIBLE
                    }
                    // Only toggle RecyclerView visibility when transitioning between empty/non-empty
                    // to prevent flicker from metadata updates (avatars, usernames, etc.)
                    // During prefill, the RecyclerView starts INVISIBLE and is revealed by
                    // prefillRecentMessagesSync() after layout completes. Do NOT override that here.
                    val isEmpty = !hasMessages
                    if (isEmpty != wasEmpty) {
                        if (!isEmpty && prefillTimestampMs > 0L) {
                            // Prefill is in progress — let it handle the reveal after layout
                        } else {
                            binding.recyclerViewMessages.visibility = if (hasMessages) View.VISIBLE else View.INVISIBLE
                            updateQuickReplyTouchShieldVisibility()
                        }
                        wasEmpty = isEmpty
                    }
                    syncInteractiveOverlayChrome()
                    isChatEmptyState.value = !hasMessages
                }
                override fun onChanged() { updateEmptyState() }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    updateEmptyState()
                    if (isInteractiveQuickReplyOverlayActive) {
                        snapInteractiveOverlayToLatest()
                    }
                }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateEmptyState() }
            })
            
            // Swipe to reply
            val swipeCallback = SwipeToReplyCallback(
                this@ChatActivity,
                canSwipe = { position ->
                    if (isInteractiveQuickReplyOverlayActive) return@SwipeToReplyCallback false

                    val item = chatAdapter.currentList.getOrNull(position)
                    if (item is ChatListItem.MessageItem) {
                        // Disable swipe for deleted messages
                        if (item.message.isDeletedForAll) return@SwipeToReplyCallback false

                        if (item.message.type == MessageType.AUDIO) {
                            val isPlaying = audioPlaybackState.value?.playingMessageId == item.message.id
                            !isPlaying
                        } else {
                            true
                        }
                    } else {
                        // Disable for headers
                        false
                    }
                }
            ) { position ->
                if (position != RecyclerView.NO_POSITION) {
                    val message = chatAdapter.currentList.getOrNull(position)
                    if (message is ChatListItem.MessageItem) {
                        onSwipeToReply(message.message)
                    }
                }
            }
            ItemTouchHelper(swipeCallback).attachToRecyclerView(this)
            
            // Optimize scroll performance by skipping media loads during scroll
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val lm = layoutManager as LinearLayoutManager
                    updateScrollPerfVisibleRange(lm)
                    scrollController.onScrollStateChanged(newState)
                    StartupTrace.logStage(
                        "chat_scroll_state",
                        "chatId=${chatId ?: "unknown"} state=$newState first=${lm.findFirstVisibleItemPosition()} last=${lm.findLastVisibleItemPosition()}"
                    )
                    when (newState) {
                        androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                            chatAdapter.isScrolling = false
                            // Don't clear isFirstLayout immediately — doing so causes the
                            // next scroll frame to switch from lightweight (2ms) to full
                            // (15ms) binds, which blocks the fling and causes the mid-scroll
                            // stop. Instead, clear it after a 500ms settle delay so rapid
                            // scroll sessions keep using fast binds throughout.
                            scheduleFirstLayoutClear()
                            // Stop frame budget monitoring — the scroll has fully settled.
                            frameBudgetGuard.stop()
                            // The fling is over — older-page loads no longer need to bypass the
                            // settle-defer (any straggler emission now commits via the normal path).
                            olderLoadInFlight = false
                            // Safety net: reset the pagination gate so subsequent scrolls can
                            // trigger loads even if a deferred emission never reset it.
                            isLoadingOlderMessages = false
                            finishScrollPerfTrackingIfNeeded(reason = "idle", layoutManager = lm)
                            resumeIdleTaskQueue(reason = "scroll_idle")
                            finishFirstScrollPerfTrackingIfNeeded(reason = "idle")
                            scheduleScrollIdleMediaUpgrade()
                            maybeScheduleInitialLastSeenReveal(_reason = "scroll_idle")
                            // Safety net: after every scroll session settles, re-check whether
                            // the user is still near the top of the loaded range and page in
                            // more older history if available. This catches cases where the
                            // flow-emission chain (maybeContinueOlderPrefetch) was interrupted
                            // by a cancellation while the adapter hadn't yet reflected the new
                            // window size.
                            maybeContinueOlderPrefetch()

                            // User scrolled back to the strict bottom — re-enable auto-scroll.
                            // Near-bottom hysteresis can fluctuate during small drags and must
                            // not clear the intent latch, or the FAB will briefly hide/flicker.
                            if (isRecyclerAtBottom()) {
                                userHasScrolledUp = false
                            }

                            // DEFER heavy work to after the RecyclerView renders the settled frame.
                            // applyDeferredLargeFlowEmissionIfNeeded commits a full new list
                            // (DiffUtil + layout), and warmVisibleContentWindows triggers image
                            // cache warming — both must NOT compete with the last scroll frame.
                            binding.recyclerViewMessages.post {
                                if (!::chatAdapter.isInitialized || chatAdapter.isScrolling) return@post
                                applyDeferredLargeFlowEmissionIfNeeded()
                                warmVisibleContentWindows(reason = "scroll_idle")
                            }
                        }
                        androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                            // Dismiss the WhatsApp-style reaction bar as soon as the user
                            // starts scrolling so the floating overlay doesn't drift away
                            // from the (now-moving) bubble it was anchored to.
                            if (currentReactionPopup != null) dismissReactionPopup(animate = true)
                            startFirstScrollPerfTrackingIfNeeded(reason = "drag")
                            startScrollPerfTrackingIfNeeded(reason = "drag", layoutManager = lm)
                            cancelPendingScrollIdleMediaUpgrade()
                            firstLayoutClearJob?.cancel() // Reset settle timer on new scroll
                            chatAdapter.isScrolling = true
                            // CRITICAL FIX: Do NOT pause idle queue during drag - this blocks Room DB queries
                            // including pagination! Pagination needs to run immediately during scroll to
                            // prevent the user from hitting the "top wall" and stalling. The idle queue was designed
                            // for background tasks, but pagination is user-critical and must not be blocked.
                            // Pool warming and image prefetch are already throttled by other mechanisms.
                            // pauseIdleTaskQueue(reason = "scrolling")  // REMOVED to enable pagination during scroll
                            // Dismiss translation toolbar on scroll to prevent stale positioning
                            translationToolbar?.dismiss()
                        }
                        androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING -> {
                            // Dismiss the reaction bar as the fling begins.
                            if (currentReactionPopup != null) dismissReactionPopup(animate = true)
                            startFirstScrollPerfTrackingIfNeeded(reason = "settling")
                            startScrollPerfTrackingIfNeeded(reason = "settling", layoutManager = lm)
                            cancelPendingScrollIdleMediaUpgrade()
                            chatAdapter.isScrolling = true
                            // Intentionally do NOT pause the idle queue during SETTLING.
                            // The fling decelerates naturally and the main thread has slack
                            // between frames. Pool warming continues with yield() between
                            // inflations, and the frameBudgetGuard pauses warming if any
                            // frame exceeds the budget.
                            frameBudgetGuard.start()
                            translationToolbar?.dismiss()
                            // CRITICAL: Do NOT call applyDeferredLargeFlowEmissionIfNeeded() here.
                            // Committing a full new list (DiffUtil + layout pass) while the fling
                            // is still decelerating is the #1 cause of mid-fling jank. Deferred
                            // emissions are flushed on IDLE instead, when the scroll has settled.
                        }
                    }
                }

                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val lm = recyclerView.layoutManager as? LinearLayoutManager
                    updateScrollPerfVisibleRange(lm)
                    scrollController.recordScrolledDelta(dy)
                    // dy < 0 means the list is moving downward = user is scrolling toward older messages.
                    // Only mark as intentional when it is a touch-driven drag (not a programmatic snap).
                    if (dy < 0 && recyclerView.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                        if (!userHasScrolledUp) {
                            // Start of an intentional upward scroll session. Re-baseline the FAB
                            // unread counter to "now" and clear any stale count so the badge only
                            // ever shows messages that arrive AFTER the user starts scrolling up.
                            lastFabNewestMessageId = currentMessages.lastOrNull()?.id
                            setUnreadCountInternal(0, animate = false)
                        }
                        userHasScrolledUp = true
                    }
                    // WhatsApp-style pagination: page in older history BEFORE the user reaches
                    // the top. The trigger distance and the number of pages pulled both scale
                    // with scroll velocity (|dy| per frame), so a fast fling starts loading much
                    // earlier and pulls a bigger chunk — the buffer is refilled ahead of the
                    // scroll instead of stalling at the top. loadOlderMessages() self-guards
                    // against overlapping loads.
                    //
                    // Dual-proximity gate: with stackFromEnd=true, position 0 is the newest
                    // message (bottom of screen) and high positions are older messages (top of
                    // screen). The firstVisible condition catches scroll-up near the bottom
                    // (prefetch). The end-distance condition catches the user approaching the
                    // oldest loaded message — without it, once the user scrolls past position
                    // ~180-220 the trigger stops firing and any remaining older history in the
                    // local DB is never loaded.
                    // Pagination trigger: load older messages when near top or near bottom, regardless of scroll direction.
                    if (lm != null) {
                        val total = chatAdapter.itemCount
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        val rowsFromEnd = max(0, total - 1 - last)

                        // Dual-proximity pagination trigger
                        val distanceFromBottom = first
                        val nearBottom = distanceFromBottom <= LOAD_OLDER_THRESHOLD
                        val nearTop = rowsFromEnd <= LOAD_OLDER_THRESHOLD_MAX

                        // Allow pagination during scroll even if a load is already in flight.
                        // The cooldown prevents rapid cascades, but we want responsive loading
                        // while the user is actively scrolling.
                        if (hasMoreOlderMessages && userHasScrolledUp) {
                            if (nearBottom || nearTop) {
                                // Determine page count based on scroll speed – faster flings load more pages.
                                val speed = kotlin.math.abs(dy)
                                // Rough heuristic: each 300px of scroll velocity adds one extra page.
                                val extraPages = (speed / 300).coerceAtLeast(0)
                                val pageCount = 1 + extraPages
                                loadOlderMessages(pageCount = pageCount)
                            }
                        }

                        // Log boundary proximity for debugging
                        if (first <= 0 || rowsFromEnd <= 5 || total < 100) {
                            Log.d("ScrollBoundary",
                                "NEAR_TOP first=$first last=$last total=$total " +
                                "rowsFromEnd=$rowsFromEnd window=${chatAdapter.itemCount} " +
                                "msgCount=${currentMessages.size} liveFlowCommitted=${pendingLargeFlowEmission == null}"
                            )
                        }
                    }
                }
            })

            // ── Pool warming: configure caps immediately, warm after first frame ──
            // Pool caps must be set before any ViewHolders are created so the visible
            // items already benefit from raised limits.
            val pool = binding.recyclerViewMessages.recycledViewPool
            pool.setMaxRecycledViews(1, 30)   // incoming text
            pool.setMaxRecycledViews(2, 30)   // outgoing text
            pool.setMaxRecycledViews(3, 20)   // incoming media
            pool.setMaxRecycledViews(4, 16)   // outgoing media
        }

    }

    /**
     * Initializes the translation feature (translate + TTS).
     * Lightweight: no blocking work, just wires callbacks.
     */
    private fun setupTranslationFeature() {
        // Initialize TranslationManager (lifecycle-scoped to this activity)
        translationManager = com.glyph.glyph_v3.ui.chat.translation.TranslationManager(
            context = this,
            scope = lifecycleScope
        )
        // Load the persisted target language
        translationManager.setLanguage(translationPrefs.targetLanguage)

        // Initialize AI Composer Manager (lifecycle-scoped)
        aiComposerManager = com.glyph.glyph_v3.ui.chat.composer.AiComposerManager(
            context = this,
            scope = lifecycleScope
        )

        // Observe AI Composer state to update input padding (restrict upward move)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiComposerManager.uiState.collect {
                    refreshInputPadding()
                }
            }
        }

        // Initialize the floating toolbar
        translationToolbar = com.glyph.glyph_v3.ui.chat.translation.TranslationToolbarPopup(
            recyclerView = binding.recyclerViewMessages,
            translationManager = translationManager
        )
        // Initial sync of toolbar label
        translationToolbar?.updateLanguageLabel(translationPrefs.targetLanguage)

        // Wire language change to bottom sheet
        translationToolbar?.onLanguageChangeRequested = {
            val savedMessage = translationManager.activeMessage
            val currentLang = translationPrefs.targetLanguageCode
            val sheet = com.glyph.glyph_v3.ui.chat.translation.LanguageSelectorSheet.newInstance(
                selectedCode = currentLang
            ) { selectedLanguage ->
                // Update both the prefs and the manager
                translationPrefs.targetLanguageCode = selectedLanguage.code
                translationManager.setLanguage(selectedLanguage)
                translationToolbar?.updateLanguageLabel(selectedLanguage)
                translationManager.resetStateForLanguageChange()

                // If there was an active message, update toolbar but don't re-translate immediately
                val msg = savedMessage ?: translationManager.activeMessage
                if (msg != null) {
                    // Reset toolbar button to "Translate" so user can request the new language
                    translationToolbar?.setTranslateButtonState(false)

                    // Revert bubble to original text since current translation is for old language
                    translationStateMap.remove(msg.id)
                    updateTranslationForMessage(msg.id)
                }
            }
            sheet.show(supportFragmentManager, "language_selector")
        }

        // Wire toolbar Translate button → inline translation
        translationToolbar?.onTranslateRequested = { message ->
            ensureTranslationWarmUpStarted(reason = "translate_request")
            translateMessageInline(message)
            translationToolbar?.setTranslateButtonState(true)
        }

        // Wire toolbar Original button → revert to original text
        translationToolbar?.onOriginalRequested = { message ->
            val state = translationStateMap[message.id]
            if (state != null) {
                translationStateMap[message.id] = state.copy(isShowingTranslation = false)
                updateTranslationForMessage(message.id)
                translationToolbar?.setTranslateButtonState(false)
            }
        }

        // Wire adapter message click: always show floating toolbar
        chatAdapter.onMessageBubbleClick = { message, anchorView ->
            if (message.text.isNotBlank() && !message.isDeletedForAll) {
                ensureTranslationWarmUpStarted(reason = "toolbar_open")
                // Always show the toolbar — it handles translate/original toggle
                val existingState = translationStateMap[message.id]
                translationToolbar?.show(anchorView, message, isTranslated = existingState?.translatedText != null && existingState.isShowingTranslation)
            }
        }

    }

    /**
     * Translate a single message inline and update its bubble.
     */
    private fun translateMessageInline(message: com.glyph.glyph_v3.data.models.Message) {
        if (message.text.isBlank()) return
        ensureTranslationWarmUpStarted(reason = "inline_translate")
        val lang = translationPrefs.targetLanguage

        // Mark as translating
        translationStateMap[message.id] = InlineTranslationState(isTranslating = true)
        updateTranslationForMessage(message.id)

        lifecycleScope.launch {
            try {
                val repo = com.glyph.glyph_v3.data.repo.TranslationRepository.getInstance(this@ChatActivity)
                
                // Use progressive flow instead of blocking call
                repo.translateFlow(
                    messageId = message.id,
                    text = message.text,
                    targetLanguage = lang.code
                )
                .collect { result ->
                     translationStateMap[message.id] = InlineTranslationState(
                        translatedText = result.translatedText,
                        isShowingTranslation = true,
                        isTranslating = false
                    )
                    // Sync toolbar if it's still showing this message
                    if (translationToolbar?.activeMessage?.id == message.id) {
                        translationToolbar?.setTranslateButtonState(true)
                    }
                    updateTranslationForMessage(message.id)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Inline translation failed for ${message.id}", e)
                translationStateMap.remove(message.id)
                // Reset toolbar if it's still showing this message
                if (translationToolbar?.activeMessage?.id == message.id) {
                    translationToolbar?.setTranslateButtonState(false)
                }
                updateTranslationForMessage(message.id)
            }
        }
    }

    /**
     * Auto-translate a batch of incoming messages (called when new messages arrive
     * and auto-translate is enabled).
     */
    private fun autoTranslateMessages(messages: List<com.glyph.glyph_v3.data.models.Message>) {
        ensureTranslationWarmUpStarted(reason = "auto_translate")
        val lang = translationPrefs.targetLanguage
        val repo = com.glyph.glyph_v3.data.repo.TranslationRepository.getInstance(this)

        for (msg in messages) {
            if (msg.text.isBlank()) continue
            if (translationStateMap.containsKey(msg.id)) continue // Already translated or in-progress

            translationStateMap[msg.id] = InlineTranslationState(isTranslating = true)
            updateTranslationForMessage(msg.id)

            lifecycleScope.launch {
                try {
                    repo.translateFlow(
                        messageId = msg.id,
                        text = msg.text,
                        targetLanguage = lang.code
                    ).collect { result ->
                        translationStateMap[msg.id] = InlineTranslationState(
                            translatedText = result.translatedText,
                            isShowingTranslation = true,
                            isTranslating = false
                        )
                        updateTranslationForMessage(msg.id)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatActivity", "Auto-translate failed for ${msg.id}", e)
                    translationStateMap.remove(msg.id)
                    updateTranslationForMessage(msg.id)
                }
            }
        }
    }

    /**
     * Efficiently update a single message's translation state in the adapter
     * using the TRANSLATION_CHANGED payload (no full list rebuild).
     */
    private fun updateTranslationForMessage(messageId: String) {
        val currentList = chatAdapter.currentList
        val idx = currentList.indexOfFirst {
            it is ChatListItem.MessageItem && it.message.id == messageId
        }
        if (idx == -1) return

        val item = currentList[idx] as ChatListItem.MessageItem
        val txState = translationStateMap[messageId]
        val updatedItem = item.copy(
            translatedText = txState?.translatedText,
            isShowingTranslation = txState?.isShowingTranslation ?: false,
            isTranslating = txState?.isTranslating ?: false
        )

        // Create a new list with the updated item and submit it
        val newList = currentList.toMutableList()
        newList[idx] = updatedItem
        processedListItems = newList
        chatAdapter.submitList(newList)
    }

    /**
     * Ensures that when the user is reading the latest messages (near-bottom), changes in
     * the bottom UI (input growth, attachment panel, typing indicator, IME insets) never
     * cause the last message to be clipped under the input.
     */
    private fun setupBottomAnchoring() {
        val bottomViews = listOf(
            binding.layoutInput,
            binding.layoutTypingIndicator
        )

        val listener = View.OnLayoutChangeListener { changedView, l, t, r, b, ol, ot, or_, ob ->
            if (chatAdapter.itemCount <= 0) return@OnLayoutChangeListener
            val translY = binding.chatContentContainer.translationY
            Log.d(KeyboardController.FLASH_TAG, "BottomAnchorListener(${changedView.javaClass.simpleName}): isKeyboardAnimating=$isKeyboardAnimating translationY=$translY atBottom=${isRecyclerAtBottom()} layout=[$l,$t,$r,$b] was=[$ol,$ot,$or_,$ob]")
            if (isKeyboardAnimating) return@OnLayoutChangeListener
            // During the onEnd layout-commit traversal, KeyboardController has already
            // registered a PreDraw listener that calls requestScrollToBottomAfterNextPreDraw
            // (via onRequestBottomAnchor). If we also fire here we get a second
            // rv.requestLayout/invalidate → an extra traversal → GIF first-frame races in and
            // produces the visible flash. Suppress until the PreDraw clears the flag.
            if (keyboardController.isInEndLayoutCommit) return@OnLayoutChangeListener
            if (!isRecyclerAtBottom()) return@OnLayoutChangeListener
            Log.d(KeyboardController.FLASH_TAG, "  → calling requestScrollToBottomAfterNextPreDraw (from BottomAnchorListener)")
            requestScrollToBottomAfterNextPreDraw()
        }

        bottomViews.forEach { it.addOnLayoutChangeListener(listener) }
    }

    /**
     * Pauses or resumes every animated drawable (GIF, animated WebP, animated sticker) currently
     * attached to the RecyclerView, for the duration of the IME open/close animation.
     *
     * While [chatContentContainer] is translated frame-by-frame to follow the keyboard, each
     * self-invalidating animated drawable also decodes + uploads a new frame to the RenderThread
     * every vsync. That work competes with the translation for the frame budget — the cost scales
     * with the number of on-screen GIFs/stickers, which is exactly why the flicker is far more
     * visible in media-heavy chats. Holding the drawables on their current frame for the brief
     * slide frees the RenderThread so the translation stays smooth.
     *
     * [android.graphics.drawable.Animatable.stop] on Glide's GifDrawable/WebpDrawable holds the
     * current frame (it does not reset to frame 0), so resume is seamless with no visible jump.
     * Only attached children are touched, so the cost is bounded by the visible window (~6-10 rows)
     * and runs just twice per animation (start + end).
     */
    private fun setVisibleAnimatedMediaRunning(running: Boolean) {
        val translY = binding.chatContentContainer.translationY
        Log.d(KeyboardController.FLASH_TAG, "setVisibleAnimatedMediaRunning($running): translationY=$translY isKeyboardAnimating=$isKeyboardAnimating")
        val rv = binding.recyclerViewMessages
        for (i in 0 until rv.childCount) {
            toggleAnimatableDrawables(rv.getChildAt(i), running)
        }
    }

    // True while the Activity-scoped Glide RequestManager is paused for an IME animation.
    // Guards against unbalanced resume calls and lets us avoid touching Glide when nothing
    // was paused (e.g. a resume firing before any pause on an unusual lifecycle order).
    private var chatImageRequestsPausedForIme = false

    /**
     * Pauses the Activity-scoped Glide [com.bumptech.glide.RequestManager] (the same one all chat
     * image/GIF/sticker loads use via `Glide.with(context)`) for the duration of an IME slide.
     *
     * On an immediate keyboard open right after chat open, recent-message loads are still in
     * flight. Their onResourceReady callbacks (placeholder->drawable swap + bitmap upload +
     * invalidate) land on the first few IME animation frames and blow the frame budget, making
     * the content-container translationY lurch instead of ease. Pausing holds not-yet-completed
     * requests without disturbing already-painted images, so the first frames stay within budget.
     */
    private fun pauseChatImageRequestsForIme() {
        if (chatImageRequestsPausedForIme) return
        if (isFinishing || isDestroyed) return
        try {
            Glide.with(this).pauseRequests()
            chatImageRequestsPausedForIme = true
        } catch (_: Exception) {
            // Glide.with can throw if the Activity is past its valid lifecycle window; ignore.
        }
    }

    /** Resumes the Glide RequestManager paused by [pauseChatImageRequestsForIme]. Idempotent. */
    private fun resumeChatImageRequestsForIme() {
        if (!chatImageRequestsPausedForIme) return
        chatImageRequestsPausedForIme = false
        if (isFinishing || isDestroyed) return
        try {
            Glide.with(this).resumeRequests()
        } catch (_: Exception) {
            // Ignore; held requests resume naturally when the manager next becomes active.
        }
    }

    private fun toggleAnimatableDrawables(view: View, running: Boolean) {
        if (view is ImageView) {
            val drawable = view.drawable
            if (drawable is android.graphics.drawable.Animatable) {
                if (running) {
                    if (!drawable.isRunning) drawable.start()
                } else if (drawable.isRunning) {
                    drawable.stop()
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                toggleAnimatableDrawables(view.getChildAt(i), running)
            }
        }
    }


    private fun setupScrollToBottomFab() {
        scrollFabController = ScrollToBottomFabController(
            binding = binding,
            recyclerView = binding.recyclerViewMessages,
            dpToPx = ::dpToPx,
            isTypingTransitionRunningProvider = { isTypingTransitionRunning },
            isInteractiveQuickReplyOverlayActiveProvider = { isInteractiveQuickReplyOverlayActive },
            isGroupChatProvider = { isGroupChat },
            isOtherUserTypingProvider = { isOtherUserTyping },
            hasUserScrolledUpProvider = { userHasScrolledUp },
            resolveTypingIndicatorShiftPx = ::resolveTypingIndicatorShiftPx,
            onScrollToBottomClick = { scrollToBottomSmooth() },
            onRecyclerScrolled = {
                if (isInteractiveQuickReplyOverlayActive) {
                    applyInteractiveOverlayMessageFade()
                }
            },
            onRecyclerScrollIdle = {
                if (isInteractiveQuickReplyOverlayActive) {
                    applyInteractiveOverlayMessageFade()
                }
            },
            onFabLayoutChanged = {
                updateScrollFabMapOverlayAvoidance()
                ensureInteractiveMapButtonAboveTransientOverlays()
            }
        )
        scrollFabController.attach()
    }

    private fun scheduleScrollFabUpdate() {
        if (::scrollFabController.isInitialized) {
            scrollFabController.scheduleUpdate()
        }
    }

    private fun hideScrollFabImmediately() {
        if (::scrollFabController.isInitialized) {
            scrollFabController.hideImmediately()
        }
    }

    private fun scrollToBottomSmooth() {
        val rv = binding.recyclerViewMessages
        val count = chatAdapter.itemCount
        if (count <= 0) return

        val target = count - 1
        rv.stopScroll()

        val lm = rv.layoutManager as? LinearLayoutManager
            ?: run {
                rv.scrollToPosition(target)
                rv.post {
                    ensureLastItemFullyVisibleAboveBottomUi(target)
                    scheduleScrollFabUpdate()
                }
                return
            }

        val lastVisible = lm.findLastVisibleItemPosition().coerceAtLeast(0)
        val farJumpThresholdItems = 20

        if (lastVisible < target - farJumpThresholdItems) {
            // Far from tail: hard jump instantly to avoid visible long travel.
            lm.scrollToPositionWithOffset(target, 0)
            rv.post {
                ensureLastItemFullyVisibleAboveBottomUi(target)
                scheduleScrollFabUpdate()
            }
            return
        }

        // Near the tail: keep a short settle animation for premium feel.
        val scroller = object : LinearSmoothScroller(this@ChatActivity) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_END

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 18f / displayMetrics.densityDpi
            }

            override fun calculateTimeForScrolling(dx: Int): Int {
                return super.calculateTimeForScrolling(dx).coerceAtMost(110)
            }

            override fun calculateTimeForDeceleration(dx: Int): Int {
                return super.calculateTimeForDeceleration(dx).coerceAtMost(150)
            }
        }

        scroller.targetPosition = target
        lm.startSmoothScroll(scroller)

        rv.postOnAnimation {
            ensureLastItemFullyVisibleAboveBottomUi(target)
            scheduleScrollFabUpdate()
        }
    }

    private fun isRecyclerNearBottom(): Boolean {
        val rv = binding.recyclerViewMessages
        
        // If not laid out or no items, we are effectively "at bottom" for UI purposes.
        if (rv.width == 0 || rv.height == 0 || chatAdapter.itemCount == 0) return true
        
        // If a transition animation is running, maintain the "at bottom" illusion
        // to prevent the FAB from flickering while the list is sliding.
        if (isTypingTransitionRunning) return true

        // 1. Check if the very last item is visible on screen.
        // This is the most reliable "at bottom" check regardless of pixel offsets,
        // padding, or temporary layout states.
        val lm = rv.layoutManager as? LinearLayoutManager
        if (lm != null) {
            val lastVisibleItem = lm.findLastVisibleItemPosition()
            val totalItems = chatAdapter.itemCount
            // Tolerance of 1 item allows for partial visibility or footer item (typing indicator)
            if (lastVisibleItem >= totalItems - 2) {
                return true
            }
        }

        // 2. Fallback to pixel-based math logic for redundancy or if LM is unavailable.
        // WhatsApp-style logic: show the button as soon as the user starts scrolling up.
        // We check vertically: if we can scroll down more than ~100dp, we are NOT at the bottom.
        val verticalOffset = rv.computeVerticalScrollOffset()
        val verticalExtent = rv.computeVerticalScrollExtent()
        val verticalRange = rv.computeVerticalScrollRange()

        // Dist from bottom in pixels
        val distanceFromBottom = verticalRange - verticalExtent - verticalOffset
        
        // If the entire list fits on screen, we are always at bottom.
        if (verticalRange <= verticalExtent) return true

        // Use a dynamic threshold so minor animated offsets from typing transitions
        // don't incorrectly mark the user as "away from bottom".
        val density = resources.displayMetrics.density
        val baseThresholdPx = (50 * density).toInt()
        val typingAwareThresholdPx = resolveTypingIndicatorShiftPx() + (24 * density).toInt()
        val thresholdPx = max(baseThresholdPx, typingAwareThresholdPx)

        return distanceFromBottom < thresholdPx
    }

    private fun isRecyclerIdleAndNearBottomForIncoming(): Boolean {
        if (!::chatAdapter.isInitialized) return false
        val rv = binding.recyclerViewMessages
        if (chatAdapter.isScrolling || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return false
        }
        return isRecyclerNearBottom() || isRecyclerAtBottom()
    }

    private fun setUnreadCountInternal(newCount: Int, animate: Boolean) {
        if (::scrollFabController.isInitialized) {
            scrollFabController.setUnreadCount(newCount, animate)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    // Cached messages list for mixing with typing indicator
    private var currentMessages: List<Message> = emptyList()
    private var previousMessages: List<Message> = emptyList()
    // Raw (unstabilized) reference for identity-based skip optimization
    private var previousRawMessages: List<Message> = emptyList()
    private var processedListItems: List<ChatListItem> = emptyList()
    private var sequenceJob: Job? = null
    private var lastMessageFlowTraceAtMs: Long = 0L
    // Position-lock cache: once a message is rendered at a position it never moves.
    // New messages are inserted at their correct canonical position.
    private var renderedMessageOrder: List<String> = emptyList()
    // Timestamp when prefill was submitted. Live flow emissions are coalesced for a short
    // window after this to let Firestore sync + Room DB settle before rendering the full list.
    // This prevents the rapid flash caused by multiple intermediate Room emissions during
    // initial Firestore snapshot processing.
    private var prefillTimestampMs: Long = 0L

    // WhatsApp-style pagination state. The RecyclerView's live flow is bounded by this
    // limit; growing it re-subscribes Room with a larger LIMIT, prepending older history.
    private val messageWindowLimit = kotlinx.coroutines.flow.MutableStateFlow(INITIAL_MESSAGE_WINDOW)
    @Volatile
    private var isLoadingOlderMessages = false
    @Volatile
    private var hasMoreOlderMessages = false
    // True while the user is actively scrolling and an older-page load is feeding the fling.
    // Older-page growth must commit immediately (never deferred) so the fling stays fed with
    // content instead of hitting the top wall and stopping. Reset on SCROLL_STATE_IDLE.
    @Volatile
    private var olderLoadInFlight = false
    // Pagination cooldown: prevents back-to-back cascade where maybeContinueOlderPrefetch
    // → loadOlderMessages → flow emission → maybeContinueOlderPrefetch chains infinitely.
    // Throttled to PAGINATION_COOLDOWN_MS between consecutive fires.
    private var lastPaginationFireElapsedMs: Long = 0L
    // One-time guard: after the first stable render we pre-extend the window once so the very
    // first scroll-up never stalls on a cold Room round-trip at the initial window boundary.
    private var proactiveOlderPrefetchScheduled = false

    private fun traceUi(stage: String, details: String) {
        if (BuildConfig.DEBUG) {
            Log.d("ChatUiTrace", "$stage | $details")
        }
    }

    private fun summarizeMessage(message: Message?): String {
        if (message == null) return "none"
        return "id=${message.id.take(8)} incoming=${message.isIncoming} status=${message.status} type=${message.type} ts=${message.timestamp}"
    }

    private fun shouldDeferLargeFlowEmission(messages: List<Message>): Boolean {
        if (!::chatAdapter.isInitialized || !::binding.isInitialized) return false
        if (pendingScrollToBottomOnNextListCommit) return false

        val currentCount = maxOf(
            chatAdapter.currentList.count { it is ChatListItem.MessageItem },
            currentMessages.size,
            previousMessages.size
        )
        if (currentCount <= 0) return false

        // CRITICAL FIX: Never defer during active scrolling or when pagination is in flight.
        // This ensures that pagination queries show results immediately even during fast scroll.
        // Deferring during scroll causes the "stop and wait" behavior users are experiencing.
        val isActivelyScrolling = chatAdapter.isScrolling ||
                               binding.recyclerViewMessages.scrollState != RecyclerView.SCROLL_STATE_IDLE
        if (isActivelyScrolling) return false

        // Never defer when the pool is warm. With 100% hit rate, even a full list
        // commit causes zero inflations. Deferring during scroll is what causes the
        // "pause at cache boundary" — the live flow's emission waits for idle while
        // the user scrolls past the cached items into empty space.
        if (!deferredStartupWorkUnlocked) return false

        return false
    }

    private fun deferLargeFlowEmissionIfNeeded(
        chatIdSnapshot: String,
        messages: List<Message>,
        isTypingRaw: Boolean,
        stage: String
    ): Boolean {
        if (!shouldDeferLargeFlowEmission(messages)) return false

        pendingLargeFlowEmission = DeferredLargeFlowEmission(
            messages = messages,
            isTypingRaw = isTypingRaw
        )

        val lm = binding.recyclerViewMessages.layoutManager as? LinearLayoutManager
        StartupTrace.logStage(
            "chat_flow_emit_deferred",
            "chatId=$chatIdSnapshot current=${chatAdapter.currentList.count { it is ChatListItem.MessageItem }} target=${messages.size} state=${binding.recyclerViewMessages.scrollState} stage=$stage first=${lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION}"
        )
        return true
    }

    private fun restoreAnchorAfterDeferredFlowCommit(anchorMessageId: String?, anchorOffset: Int) {
        if (anchorMessageId.isNullOrBlank()) return
        // Anchor restoration is only meaningful when the user has deliberately scrolled UP to
        // read history. When the user is at the bottom (e.g. the flush triggered by
        // onEnterAnimationComplete right after chat open), LinearLayoutManager's stackFromEnd
        // already keeps the tail row visible. Calling scrollToPositionWithOffset here would
        // snap the viewport to the anchor row's index in the OLD (smaller) list, which maps to
        // a different visual position in the newly expanded list — the visible downward shift.
        if (!userHasScrolledUp) return
        val layoutManager = binding.recyclerViewMessages.layoutManager as? LinearLayoutManager ?: return
        val anchorPosition = chatAdapter.currentList.indexOfFirst {
            it is ChatListItem.MessageItem && it.message.id == anchorMessageId
        }
        if (anchorPosition == -1) return
        binding.recyclerViewMessages.post {
            layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset)
        }
    }

    private fun applyDeferredLargeFlowEmissionIfNeeded() {
        val deferred = pendingLargeFlowEmission ?: return
        if (pendingLargeFlowApplyJob?.isActive == true) return
        // Don't flush during active scroll or first layout — wait for idle.
        // This prevents 43-inflation frames when onEnterAnimationComplete or the
        // 500ms safety timer fire while the user is scrolling.
        if (::chatAdapter.isInitialized && chatAdapter.isFirstLayout) return
        if (::chatAdapter.isInitialized && chatAdapter.isScrolling && !olderLoadInFlight) return
        // Always attempt to apply deferred large flow emission regardless of scroll state
        val activeChatId = chatId ?: return
        val layoutManager = binding.recyclerViewMessages.layoutManager as? LinearLayoutManager
        val anchorPosition = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val anchorMessageId = (chatAdapter.currentList.getOrNull(anchorPosition) as? ChatListItem.MessageItem)?.message?.id
        val anchorOffset = layoutManager?.findViewByPosition(anchorPosition)?.top ?: 0

        pendingLargeFlowApplyJob = lifecycleScope.launch {
            pendingLargeFlowEmission = null
            val effectiveTyping = if (_blockStatus.value.iBlockedThem) false else deferred.isTypingRaw
            val previousOrderSnapshot = renderedMessageOrder
            val stableMessages = withContext(Dispatchers.Default) {
                computeStableMessageOrder(deferred.messages, previousOrderSnapshot)
            }
            renderedMessageOrder = stableMessages.map { it.id }

            currentMessages = stableMessages
            chatAdapter.replyLookupMessages = stableMessages
            isOtherUserTyping = effectiveTyping
            lastObservedOtherUserTypingRaw = effectiveTyping
            scheduleDeferredOffscreenPreviewWarmup(stableMessages, reason = "deferred_flow_idle")
            scheduleDeferredMediaHeightWarmup(stableMessages, reason = "deferred_flow_idle")
            updateHeaderStatus()

            val showIndicator = effectiveTyping || (!isGroupChat && isExpressiveActive)
            val expActive = shouldUseExpressiveTypingIndicator(effectiveTyping)
            val indicatorText = currentTypingIndicatorText(effectiveTyping)
            val listItems = withContext(Dispatchers.Default) {
                val rawList = processMessagesWithHeaders(
                    stableMessages,
                    showTypingIndicator = showIndicator,
                    newIncomingIds = emptySet(),
                    expressiveActive = expActive,
                    expressiveText = indicatorText,
                    expressiveSent = expressiveSentiment
                )
                TextLayoutPrecomputer.precomputeForItems(rawList)
            }

            submitListAwait(listItems)
            // Pagination bookkeeping: reset the gate so the next fire can proceed.
            // The original flow-collector path resets isLoadingOlderMessages before
            // the commit, but when we flush a deferred emission through this path,
            // the collector was short-circuited — reset it here too.
            isLoadingOlderMessages = false
            maybeContinueOlderPrefetch()
            processedListItems = listItems
            updateDisplayedMessageIds(listItems)
            restoreAnchorAfterDeferredFlowCommit(anchorMessageId, anchorOffset)
            MessageCacheManager.putSnapshot(
                chatId = activeChatId,
                recentMessages = stableMessages.takeLast(80),
                listItems = processedListItems,
                source = "live_flow"
            )
            previousRawMessages = deferred.messages
            previousMessages = stableMessages
            pendingScrollToBottomOnNextListCommit = false
        }
    }


    private fun loadMessages() {
        val id = chatId ?: return
        // Phase 5: groups have no otherId; only the 1:1 receipt-sync branch needs it.
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)
        StartupTrace.logStage("chat_live_sync_start", "chatId=$id otherId=$otherId group=$isGroupChat")
        
        lifecycleScope.launch {
            val latestLocalTimestamp = withContext(Dispatchers.IO) {
                repository.getLatestLocalMessageTimestamp(id)
            }
            messageSyncListener?.remove()
            messageSyncListener = repository.syncMessages(id, latestLocalTimestamp)
        }
        
        // Start syncing read receipts (1:1 only — group reads use the per-message readBy array)
        readReceiptListener?.remove()
        if (!isGroupChat) {
            readReceiptListener = repository.syncReadReceipts(id, otherId)
        }
        
        // Start observing message status updates (DELIVERED/READ)
        repository.observeMessageStatusUpdates(id)
        // Delivery receipts are now synced globally from GlyphApplication.
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combine messages and typing status into one atomic list flow.
                // WhatsApp-style pagination: the message flow is bounded by messageWindowLimit
                // and grows as the user scrolls up, so the whole history is never loaded at once.
                //
                // Growth is rate-limited INSIDE loadOlderMessages() (PAGINATION_COOLDOWN_MS), never
                // by debouncing this flow. An earlier version wrapped this in debounce(30): during a
                // fast fling the scroll listener grows messageWindowLimit once per frame (~16ms), and
                // since that is faster than the 30ms debounce window the debounce never settled — it
                // withheld EVERY emission until the scroll stopped. No older page ever loaded mid-fling,
                // the list hit the top wall, the fling stalled, and the page only loaded on settle — the
                // exact "scroll stops, then loads" behavior. With growth throttled to ~12x/s and a bare
                // flatMapLatest, each limit change cancels the stale (smaller) query and re-runs with the
                // larger window, so older rows stream in continuously and stay ahead of the fling.
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val messagesFlow = messageWindowLimit.flatMapLatest { limit ->
                    repository.getRecentMessagesFlow(id, limit)
                }
                val typingUserIdsFlow = buildTypingUserIdsFlow(id, otherId)
                
                messagesFlow.combine(typingUserIdsFlow) { msgs, typingUserIds -> msgs to typingUserIds }
                    .conflate()
                    .collectLatest { (messages, typingUserIdsRaw) ->
                        eagerTypingObserverJob?.cancel()
                        eagerTypingObserverJob = null
                        // Pagination bookkeeping: a full window means older history likely
                        // remains in the local DB. Note: isLoadingOlderMessages and
                        // maybeContinueOlderPrefetch are deferred until AFTER the first
                        // defer check below — resetting them early would allow a pagination
                        // cascade while the current emission is waiting in the defer queue.
                        // Only report "has more" when the DB actually returned the full limit,
                        // meaning there may be additional older messages beyond what was requested.
                        // Using > previousMessages.size falsely reports true on the first load
                        // (previousMessages is empty), causing unnecessary pagination that triggers
                        // flatMapLatest flow cancellation on the main thread — a 100ms+ frame drop.
                        hasMoreOlderMessages = messages.size >= messageWindowLimit.value
                        if (isGroupChat) ensureGroupSenderNamesLoadedFor(messages)
                        val nowElapsed = SystemClock.elapsedRealtime()
                        val deltaMs = if (lastMessageFlowTraceAtMs == 0L) 0L else nowElapsed - lastMessageFlowTraceAtMs
                        lastMessageFlowTraceAtMs = nowElapsed
                        if (isGroupChat) {
                            updateGroupTypingUsers(typingUserIdsRaw)
                        } else if (activeGroupTypingUserIds.isNotEmpty() || groupTypingIndicatorText.isNotEmpty()) {
                            activeGroupTypingUserIds = emptySet()
                            groupTypingIndicatorText = ""
                        }
                        val isTypingRaw = if (isGroupChat) {
                            activeGroupTypingUserIds.isNotEmpty()
                        } else {
                            typingUserIdsRaw.isNotEmpty()
                        }
                        traceUi(
                            stage = "flow_emit_raw",
                            details = "chatId=$id count=${messages.size} delta=${deltaMs}ms latest=${summarizeMessage(messages.lastOrNull())} typing=$isTypingRaw typers=${if (isGroupChat) activeGroupTypingUserIds.size else typingUserIdsRaw.size}"
                        )
                        val blockStatusSnapshot = _blockStatus.value

                        // Blocking stops live interaction, but the existing transcript stays visible.
                        val isTyping = if (blockStatusSnapshot.iBlockedThem) false else isTypingRaw
                        val indicatorText = currentTypingIndicatorText(isTyping)
                        traceUi(
                            stage = "flow_emit_filtered",
                            details = "chatId=$id count=${messages.size} latest=${summarizeMessage(messages.lastOrNull())} typing=$isTyping"
                        )
                        if (deferLargeFlowEmissionIfNeeded(
                                chatIdSnapshot = id,
                                messages = messages,
                                isTypingRaw = isTypingRaw,
                                stage = "collector_entry"
                            )) {
                            return@collectLatest
                        }
                        // The emission passed the first defer gate — the list will actually
                        // grow. Now it's safe to reset the pagination gate and check whether
                        // more prefetch is needed. Doing this BEFORE the defer check caused a
                        // cascade where each deferred fire immediately allowed another fire,
                        // ballooning the window during the drag.
                        isLoadingOlderMessages = false
                        maybeContinueOlderPrefetch()
                        warmVisibleContentWindows(messages = messages, reason = "flow_emit")

                        // FLICKER GUARD — Settling Window
                        // After the prefill shows the last 20 messages, the Firestore snapshot
                        // listener fires and inserts all messages into Room DB. Each insert
                        // triggers a new Flow emission. Rendering every intermediate state causes
                        // rapid flashing as items are inserted and DiffUtil recalculates.
                        // Instead, we suppress emissions for a short window after prefill to let
                        // Room DB settle, then render once with the full dataset.
                        if (prefillTimestampMs > 0L) {
                            val elapsed = System.currentTimeMillis() - prefillTimestampMs
                            if (elapsed < PREFILL_SETTLE_WINDOW_MS) {
                                // Still within the settling window — coalesce by delaying.
                                // collectLatest will cancel this coroutine if a newer emission
                                // arrives before the delay completes, automatically coalescing.
                                delay(PREFILL_SETTLE_WINDOW_MS - elapsed)
                            }
                            // Window has elapsed — clear the timestamp and proceed with a single
                            // complete render of all messages.
                            prefillTimestampMs = 0L
                            warmVisibleContentWindows(messages = messages, reason = "prefill_settle")
                        }

                        // Re-check deferral after settle-window coalescing. Emissions can begin when
                        // idle and become active-scroll by the time we reach full list processing.
                        if (deferLargeFlowEmissionIfNeeded(
                                chatIdSnapshot = id,
                                messages = messages,
                                isTypingRaw = isTypingRaw,
                                stage = "pre_stabilize"
                            )) {
                            return@collectLatest
                        }

                        // ANTI-FLICKER: Stabilize message order so that already-rendered
                        // messages never visually change position when serverTimestamp backfills
                        // arrive from Firestore. New messages are inserted at the correct position.
                        val stableMessages = stabilizeMessageOrder(messages)

                        // Detect and log any position changes vs previous emission.
                        // With the renderedMessageOrder lock, existing messages should NEVER
                        // change position. Only new messages (appended to the tail) cause
                        // prevIds != newIds. Any [MOVED] log now indicates a bug in the lock.
                        // Use: adb logcat -s MsgOrder  to filter.
                        if (previousMessages.isNotEmpty()) {
                            val prevIds = previousMessages.map { it.id }
                            val newIds = stableMessages.map { it.id }
                            if (prevIds != newIds) {
                                val prevTail = prevIds.takeLast(6).map { it.takeLast(4) }
                                val newTail = newIds.takeLast(6).map { it.takeLast(4) }
                                val prevSet = prevIds.toSet()
                                val newSet = newIds.toSet()
                                val addedIds = newSet - prevSet
                                val removedIds = prevSet - newSet
                                val movedIds = prevIds.filter { it in newSet && newIds.indexOf(it) != prevIds.indexOf(it) }
                                if (movedIds.isNotEmpty()) {
                                    // An already-rendered message moved — this should NOT happen with the lock!
                                    android.util.Log.e("MsgOrder", "[LOCK_VIOLATION!] prevTail=$prevTail newTail=$newTail added=${addedIds.map{it.takeLast(4)}} removed=${removedIds.map{it.takeLast(4)}}")
                                    for (mid in movedIds.takeLast(3)) {
                                        val msg = stableMessages.find { it.id == mid }
                                        android.util.Log.e("MsgOrder", "[MOVED] id=${mid.takeLast(6)} fsTs=${msg?.serverTimestamp} cTs=${msg?.timestamp} oldPos=${prevIds.indexOf(mid)} newPos=${newIds.indexOf(mid)}")
                                    }
                                } else {
                                    // Only new messages added/removed — this is expected normal behavior.
                                    android.util.Log.d("MsgOrder", "[NEW_MSG] added=${addedIds.size} removed=${removedIds.size} newTail=$newTail")
                                }
                            }
                        }

                        val wasNearBottomBeforeUpdate = isRecyclerIdleAndNearBottomForIncoming()

                        val incomingNotDisplayedIds = stableMessages
                            .asSequence()
                            .filter { it.isIncoming && !displayedMessageIds.contains(it.id) }
                            .map { it.id }
                            .toSet()
                        val newIncomingIds = if (displayedMessageIds.isEmpty()) {
                            emptySet()
                        } else {
                            incomingNotDisplayedIds
                        }
                        val newIncomingTailAppendedIds = computeTailAppendedIncomingIds(
                            stableMessages = stableMessages,
                            previousStableMessages = previousMessages,
                            incomingCandidateIds = newIncomingIds
                        )
                        val outgoingNotDisplayedIds = stableMessages
                            .asSequence()
                            .filter { !it.isIncoming && !displayedMessageIds.contains(it.id) }
                            .map { it.id }
                            .toSet()
                        val newOutgoingIds = if (displayedMessageIds.isEmpty()) {
                            emptySet()
                        } else {
                            outgoingNotDisplayedIds
                        }

                        // Trigger auto-translate for new incoming messages if enabled
                        if (newIncomingIds.isNotEmpty() && translationPrefs.isAutoTranslateEnabled) {
                            val newMsgs = stableMessages.filter { newIncomingIds.contains(it.id) }
                            autoTranslateMessages(newMsgs)
                        }

                        val typingIndicatorDisplayed = processedListItems.any { it is ChatListItem.TypingIndicator }
                        lastTypingIndicatorVisible = lastTypingIndicatorVisible || typingIndicatorDisplayed || isTyping

                        // Play sound for new incoming message (may be deferred to bubble reveal)
                        var shouldDeferIncomingDisplay = false
                        var shouldDeferOutgoingDisplay = false
                        var deferredRevealIds: Set<String> = emptySet()
                        if (stableMessages.isNotEmpty()) {
                            val latestMessage = stableMessages.last()
                            // Use newIncomingIds (not incomingNotDisplayedIds) so that pre-existing
                            // messages present when the chat screen first opens do NOT trigger a sound.
                            // newIncomingIds is already set to emptySet() when displayedMessageIds is
                            // empty (i.e. on the very first emission), which is the correct guard.
                            val isNewIncoming = latestMessage.isIncoming && newIncomingIds.contains(latestMessage.id)
                            
                            if (isNewIncoming) {
                                 var playSoundNow = true
                                 // Defer display when typing was recently visible and a new message arrived.
                                 if (newIncomingIds.isNotEmpty() && (typingIndicatorDisplayed || lastTypingIndicatorVisible || isOtherUserTyping || lastObservedOtherUserTypingRaw)) {
                                     shouldDeferIncomingDisplay = true
                                     playSoundNow = false
                                     pendingTransitionPlaySound = true
                                 }
                                 if (playSoundNow && !isSoundPoolReleased && !isFinishing && !isDestroyed) {
                                     ensureSoundPoolInitialized()
                                 }
                                 if (playSoundNow && !isSoundPoolReleased && ::soundPool.isInitialized && soundReceivedId != 0 && !isFinishing && !isDestroyed) {
                                     soundPool.play(soundReceivedId, 1.0f, 1.0f, 0, 0, 1.0f)
                                 }
                            }
                            val isNewOutgoing = !latestMessage.isIncoming && newOutgoingIds.contains(latestMessage.id)
                            if (isNewOutgoing &&
                                pendingScrollToBottomOnNextListCommit &&
                                (typingIndicatorDisplayed || lastTypingIndicatorVisible || isOtherUserTyping || lastObservedOtherUserTypingRaw)
                            ) {
                                shouldDeferOutgoingDisplay = true
                                deferredRevealIds = newOutgoingIds
                            }
                            lastMessageId = latestMessage.id
                        }
                        val shouldDeferTailReveal = shouldDeferIncomingDisplay || shouldDeferOutgoingDisplay
                        
                        
                        // Sync unread status
                        // Optimization: Search from the end as unread messages are likely recent
                        val hasUnreadIncoming = stableMessages.asReversed().any { it.isIncoming && it.status != MessageStatus.READ }
                        if (hasUnreadIncoming) {
                            repository.markChatAsRead(id)
                        }
                        
                        // Scroll FAB Counter Logic
                        // Count ONLY genuinely new messages appended at the tail (newest). With
                        // windowed pagination the list also grows when older history is paged in
                        // at the TOP — those must never inflate the badge. We anchor on the
                        // previously seen newest message id and count incoming messages that come
                        // strictly after it; older-page prepends leave the tail unchanged and add 0.
                        val previousNewestId = lastFabNewestMessageId
                        if (previousNewestId == null) {
                            lastFabNewestMessageId = stableMessages.lastOrNull()?.id
                        } else {
                            val prevIdx = stableMessages.indexOfLast { it.id == previousNewestId }
                            if (prevIdx in 0 until stableMessages.size - 1) {
                                var newIncoming = 0
                                for (i in prevIdx + 1 until stableMessages.size) {
                                    if (stableMessages[i].isIncoming) newIncoming++
                                }
                                if (newIncoming > 0) {
                                    if (isRecyclerIdleAndNearBottomForIncoming()) {
                                        setUnreadCountInternal(0, animate = false)
                                    } else {
                                        setUnreadCountInternal(unreadCount + newIncoming, animate = true)
                                    }
                                }
                            }
                            lastFabNewestMessageId = stableMessages.lastOrNull()?.id
                        }
                        
                        // Transition Step 1: Decide on strategy
                        // Only auto-scroll when the user has not intentionally scrolled up to read history.
                        // pendingScrollToBottomOnNextListCommit bypasses the guard (user just sent a message).
                        // chatAdapter.itemCount == 0 handles the very first emission (chat open).
                        val shouldAutoScrollToBottom = 
                            pendingScrollToBottomOnNextListCommit ||
                            (!userHasScrolledUp && wasNearBottomBeforeUpdate) ||
                            chatAdapter.itemCount == 0
                        val hadVisibleTypingBeforeUpdate = processedListItems.any {
                            it is ChatListItem.TypingIndicator && it.isVisible
                        }
                        var skipAutoScrollForThisEmission = false
                        
                        isOtherUserTyping = isTyping
                        lastObservedOtherUserTypingRaw = isTyping
                        currentMessages = stableMessages
                        chatAdapter.replyLookupMessages = stableMessages
                        scheduleDeferredOffscreenPreviewWarmup(stableMessages, reason = "flow_emit")
                        scheduleDeferredMediaHeightWarmup(stableMessages, reason = "flow_emit")
                        updateHeaderStatus()

                        // Skip deferred-tail-reveal when the adapter is showing stale cached
                        // content (93-220 items) and the live flow has 500 items ready. The
                        // reveal path only submits a partial list and returns early, preventing
                        // the full 521-item commit from reaching the adapter. This is the root
                        // cause of "scroll stops at boundary" — currentMessages=500 but
                        // chatAdapter.itemCount still shows 93.
                        val liveFlowHasMoreThanAdapter = stableMessages.size > chatAdapter.itemCount
                        if (shouldDeferTailReveal && !liveFlowHasMoreThanAdapter) {
                            val shouldAnchorTypingToMessageTransition =
                                shouldAutoScrollToBottom || wasNearBottomBeforeUpdate || chatAdapter.itemCount == 0
                            val withheldMessageIds = if (shouldDeferIncomingDisplay) {
                                incomingNotDisplayedIds
                            } else {
                                deferredRevealIds
                            }

                            val revealList = withContext(Dispatchers.Default) {
                                val rawList = processMessagesWithHeaders(
                                    stableMessages,
                                    showTypingIndicator = false,
                                    useInvisibleTypingPlaceholder = false,
                                    newIncomingIds = emptySet()
                                )
                                TextLayoutPrecomputer.precomputeForItems(rawList)
                            }
                            if (pendingTransitionPlaySound && !isSoundPoolReleased && !isFinishing && !isDestroyed) {
                                ensureSoundPoolInitialized()
                            }
                            if (pendingTransitionPlaySound && !isSoundPoolReleased && ::soundPool.isInitialized && soundReceivedId != 0 && !isFinishing && !isDestroyed) {
                                soundPool.play(soundReceivedId, 1.0f, 1.0f, 0, 0, 1.0f)
                            }
                            submitTypingMessageRevealListAwait(
                                list = revealList,
                                anchorToBottom = shouldAnchorTypingToMessageTransition,
                                revealMessageIds = withheldMessageIds
                            )
                            traceUi(
                                stage = "list_commit_typing_handoff_single",
                                details = "chatId=$id items=${revealList.size} latest=${summarizeMessage(stableMessages.lastOrNull())} anchor=$shouldAnchorTypingToMessageTransition reveal=${withheldMessageIds.size}"
                            )
                            processedListItems = revealList
                            updateDisplayedMessageIds(revealList)
                            maybeTriggerRomanticMessageEffect(
                                messages = stableMessages,
                                candidateMessageIds = if (shouldDeferIncomingDisplay) newIncomingTailAppendedIds else emptySet()
                            )

                            lastTypingIndicatorVisible = false
                            pendingTransitionPlaySound = false
                            pendingScrollToBottomOnNextListCommit = false
                            previousRawMessages = messages
                            previousMessages = stableMessages
                            return@collectLatest
                        }

                        // OPTIMIZATION: If messages haven't changed (only typing status),
                        // avoid full list rebuild and reuse existing ChatListItems.
                        if (messages === previousRawMessages && processedListItems.isNotEmpty()
                            && processedListItems.size >= stableMessages.size) {
                            // Base list is everything except trailing typing indicators
                            val baseList = processedListItems.dropLastWhile { it is ChatListItem.TypingIndicator }
                            
                            // Show typing indicator with expressive data if available OR if we're optimistic
                            val showIndicator = isTyping || (!isGroupChat && isExpressiveActive)
                            val useExpressive = shouldUseExpressiveTypingIndicator(isTyping)

                            val newList = if (showIndicator) {
                                baseList + ChatListItem.TypingIndicator(
                                    isVisible = true,
                                    isExpressive = useExpressive,
                                    liveText = indicatorText,
                                    sentiment = expressiveSentiment
                                )
                            } else {
                                baseList
                            }
                            
                            val transition = detectTypingIndicatorTransition(hadVisibleTypingBeforeUpdate, showIndicator)
                            val committedList = submitListWithTypingTransition(
                                targetList = newList,
                                transition = transition,
                                anchorToBottom = shouldAutoScrollToBottom
                            )
                            traceUi(
                                stage = "list_commit_typing_only",
                                details = "chatId=$id items=${committedList.size} latest=${summarizeMessage(stableMessages.lastOrNull())} transition=$transition"
                            )
                            if (transition != TypingIndicatorTransition.NONE && shouldAutoScrollToBottom) {
                                skipAutoScrollForThisEmission = true
                            }
                            processedListItems = committedList
                            updateDisplayedMessageIds(committedList)
                            
                        } else {
                            val showIndicator = isTyping || (!isGroupChat && isExpressiveActive)
                            // Optimistic expressive mode: show expressive bubble instead of dots 
                            // as long as the local user has the feature enabled.
                            val expActive = shouldUseExpressiveTypingIndicator(isTyping)
                            val expText = indicatorText
                            val expSent = expressiveSentiment

                            if (deferLargeFlowEmissionIfNeeded(
                                    chatIdSnapshot = id,
                                    messages = messages,
                                    isTypingRaw = isTypingRaw,
                                    stage = "pre_build_full"
                                )) {
                                return@collectLatest
                            }

                            // Try incremental paths before falling back to a full rebuild.
                            // Fast-append: exactly one new message at the tail (live incoming).
                            // Prepend: pagination loaded strictly older messages at the head.
                            val listItems = tryBuildFastAppendedMessageList(
                                previousMessages = previousMessages,
                                messages = stableMessages,
                                showTypingIndicator = showIndicator,
                                newIncomingIds = newIncomingIds,
                                expressiveActive = expActive,
                                expressiveText = expText,
                                expressiveSent = expSent
                            ) ?: tryBuildPrependedMessageList(
                                previousMessages = previousMessages,
                                messages = stableMessages,
                                showTypingIndicator = showIndicator,
                                newIncomingIds = newIncomingIds,
                                expressiveActive = expActive,
                                expressiveText = expText,
                                expressiveSent = expSent
                            ) ?: withContext(Dispatchers.Default) {
                                val rawList = processMessagesWithHeaders(
                                    stableMessages,
                                    showTypingIndicator = showIndicator,
                                    newIncomingIds = newIncomingIds,
                                    expressiveActive = expActive,
                                    expressiveText = expText,
                                    expressiveSent = expSent
                                )
                                // Precompute text heights on this background thread so
                                // ViewHolder.bind applies fixed min/max height before
                                // setText(), skipping the onMeasure layout pass.
                                TextLayoutPrecomputer.precomputeForItems(rawList)
                            }
                            if (deferLargeFlowEmissionIfNeeded(
                                    chatIdSnapshot = id,
                                    messages = messages,
                                    isTypingRaw = isTypingRaw,
                                    stage = "pre_commit_full"
                                )) {
                                return@collectLatest
                            }
                            val transition = detectTypingIndicatorTransition(hadVisibleTypingBeforeUpdate, showIndicator)
                            val committedList = submitListWithTypingTransition(
                                targetList = listItems,
                                transition = transition,
                                anchorToBottom = shouldAutoScrollToBottom
                            )
                            traceUi(
                                stage = "list_commit_full",
                                details = "chatId=$id items=${committedList.size} latest=${summarizeMessage(stableMessages.lastOrNull())} newIncoming=${newIncomingIds.size} transition=$transition autoScroll=$shouldAutoScrollToBottom"
                            )
                            if (transition != TypingIndicatorTransition.NONE && shouldAutoScrollToBottom) {
                                skipAutoScrollForThisEmission = true
                            }
                            processedListItems = committedList
                            updateDisplayedMessageIds(committedList)
                            maybeTriggerRomanticMessageEffect(
                                messages = stableMessages,
                                candidateMessageIds = newIncomingTailAppendedIds
                            )

                            // Auto-translate new incoming text messages if enabled
                            if (translationPrefs.isAutoTranslateEnabled && newIncomingIds.isNotEmpty()) {
                                val newIncomingTextMessages = stableMessages.filter {
                                    it.isIncoming && newIncomingIds.contains(it.id) &&
                                    it.text.isNotBlank() && !it.isDeletedForAll
                                }
                                if (newIncomingTextMessages.isNotEmpty()) {
                                    autoTranslateMessages(newIncomingTextMessages)
                                }
                            }
                        }
                        
                        if (shouldAutoScrollToBottom && !skipAutoScrollForThisEmission) {
                            requestScrollToBottomAfterNextPreDraw(
                                forceScroll = pendingScrollToBottomOnNextListCommit,
                                anchorToBottom = shouldAutoScrollToBottom
                            )
                        } else {
                            binding.recyclerViewMessages.doOnNextLayout { scheduleScrollFabUpdate() }
                        }

                        MessageCacheManager.putSnapshot(
                            chatId = id,
                            recentMessages = stableMessages.takeLast(80),
                            listItems = processedListItems,
                            source = "live_flow"
                        )
                        
                        pendingScrollToBottomOnNextListCommit = false
                        previousRawMessages = messages
                        previousMessages = stableMessages

                        // One-time proactive buffer: once the first real window has rendered, pre-page
                        // a cushion of older history so the first scroll-up never stalls on a cold
                        // Room query at the initial boundary. Guarded so it runs exactly once and only
                        // when older history actually exists.
                        if (!proactiveOlderPrefetchScheduled &&
                            stableMessages.isNotEmpty() &&
                            hasMoreOlderMessages &&
                            !isLoadingOlderMessages
                        ) {
                            proactiveOlderPrefetchScheduled = true
                            // Fire the proactive older-history cushion sooner so the very first
                            // scroll-up never stalls on a cold Room query at the initial boundary.
                            binding.recyclerViewMessages.postDelayed({
                                prefetchOlderWindowProactively()
                            }, 100L)
                        }
                    }
            }
        }

        // Observe upload/download progress for media messages
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaProgressManager.progressUpdates.collectLatest { (messageId, _) ->
                    // Pass RecyclerView to restrict updates to visible items
                    chatAdapter.notifyProgressChanged(messageId, binding.recyclerViewMessages)
                }
            }
        }
    }

    private fun buildTypingUserIdsFlow(
        chatId: String,
        otherId: String
    ) = (if (isGroupChat) {
        groupRepository.observeGroupTyping(chatId)
            .distinctUntilChanged()
    } else {
        PresenceManager
            .observeTypingStatus(chatId, otherId)
            .map { typing ->
                if (typing && otherId.isNotBlank()) setOf(otherId) else emptySet()
            }
            .distinctUntilChanged()
    })
        .onStart { emit(emptySet()) }
        .catch { error ->
            Log.w("ChatActivity", "Typing flow failed for chatId=$chatId", error)
            emit(emptySet())
        }

    private fun startEagerTypingObserver() {
        val id = chatId ?: return
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)

        eagerTypingObserverJob?.cancel()
        eagerTypingObserverJob = lifecycleScope.launch {
            buildTypingUserIdsFlow(id, otherId).collectLatest { typingUserIdsRaw ->
                if (isGroupChat) {
                    updateGroupTypingUsers(typingUserIdsRaw)
                } else if (activeGroupTypingUserIds.isNotEmpty() || groupTypingIndicatorText.isNotEmpty()) {
                    activeGroupTypingUserIds = emptySet()
                    groupTypingIndicatorText = ""
                }

                val isTypingRaw = if (isGroupChat) {
                    activeGroupTypingUserIds.isNotEmpty()
                } else {
                    typingUserIdsRaw.isNotEmpty()
                }
                val effectiveTyping = if (_blockStatus.value.iBlockedThem) false else isTypingRaw
                val previousTyping = isOtherUserTyping
                isOtherUserTyping = effectiveTyping
                lastObservedOtherUserTypingRaw = effectiveTyping

                if (previousTyping != effectiveTyping || (isGroupChat && typingUserIdsRaw.isNotEmpty())) {
                    refreshTypingIndicatorListIfNeeded()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun submitListAwait(list: List<ChatListItem>) {
        if (::chatAdapter.isInitialized && ::binding.isInitialized) {
            val currentList = chatAdapter.currentList
            if (currentList.isNotEmpty()) {
                val currentMessageCount = currentList.count { it is ChatListItem.MessageItem }
                val targetMessageCount = list.count { it is ChatListItem.MessageItem }
                val itemDelta = kotlin.math.abs(list.size - currentList.size)
                val messageDelta = kotlin.math.abs(targetMessageCount - currentMessageCount)
                if (itemDelta >= ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA ||
                    messageDelta >= ACTIVE_SCROLL_LIST_COMMIT_DEFER_MESSAGE_DELTA
                ) {
                    awaitKeyboardSettledBeforeLargeListCommitIfNeeded(
                        targetList = list,
                        source = "submit_list_await",
                        itemDelta = itemDelta,
                        messageDelta = messageDelta
                    )
                }
            }
        }

        // CRITICAL GATE: Normally defer list commits during the first scroll session to avoid
        // 593-769ms layout passes that drop 47+ frames.
        //
        // EXCEPTION (olderLoadInFlight): pagination prepends MUST commit immediately during
        // scroll. Without this, the user scrolls past the cached window into empty space and
        // hits a "top wall" — nothing loads until they lift their finger. Prepending older
        // history during scroll is exactly what WhatsApp/Telegram do; the prepended items land
        // above the viewport, so with stable IDs + anchor preservation the visible content stays
        // put and the layout cost is small (DiffUtil only inserts the new head rows).
        val inFirstScroll = ::scrollController.isInitialized && scrollController.firstScrollTrackingActive
        val scrolling = ::chatAdapter.isInitialized && chatAdapter.isScrolling
        val isPaginationPrepend = olderLoadInFlight
        val shouldBlockCommit = (inFirstScroll || scrolling) && !isPaginationPrepend
        if ((inFirstScroll || scrolling) && isPaginationPrepend) {
            // Pagination commit allowed during scroll — log it distinctly from the deferred path.
            if (::scrollController.isInitialized) {
                scrollController.logEmissionCommit(window = list.size, durationMs = -2)
            }
        } else if (inFirstScroll || scrolling) {
            if (::scrollController.isInitialized) {
                scrollController.logEmissionCommit(window = list.size, durationMs = -1)
            }
        }
        while (shouldBlockCommit &&
                ::chatAdapter.isInitialized && (chatAdapter.isScrolling ||
                (::scrollController.isInitialized && scrollController.firstScrollTrackingActive))
                && !isFinishing && !isDestroyed) {
            kotlinx.coroutines.delay(16)
            // Re-evaluate: if a pagination prepend arrives while we're deferring a normal commit,
            // let it through (the caller's olderLoadInFlight may have flipped).
        }
        if (isFinishing || isDestroyed) return

        suspendCancellableCoroutine<Unit> { cont ->
            val commitStartNs = System.nanoTime()
            val windowSize = list.size
            chatAdapter.submitList(list) {
                if (::scrollController.isInitialized) {
                    val durationMs = (System.nanoTime() - commitStartNs) / 1_000_000.0
                    scrollController.logEmissionCommit(window = windowSize, durationMs = durationMs.toLong())
                }
                if (cont.isActive) {
                    cont.resume(Unit) { }
                }
            }
        }
    }

    private suspend fun submitTypingMessageRevealListAwait(
        list: List<ChatListItem>,
        anchorToBottom: Boolean,
        revealMessageIds: Set<String>
    ) {
        val rv = binding.recyclerViewMessages
        val previousItemAnimator = rv.itemAnimator
        val shouldSuppressFabForInternalMotion = anchorToBottom && !userHasScrolledUp && unreadCount == 0
        val previousVisibleTops = captureVisibleItemTops(rv)

        rv.itemAnimator = null
        if (shouldSuppressFabForInternalMotion) {
            suppressScrollFabUntilAtBottom = true
            hideScrollFabImmediately()
        }

        try {
            // Ensure this handoff completes atomically even if collectLatest receives
            // another emission mid-transition; interrupted reveals can retrigger flicker.
            withContext(NonCancellable) {
                submitListAwait(list)
                awaitTypingMessageRevealBeforeNextDraw(
                    anchorToBottom = anchorToBottom,
                    previousVisibleTops = previousVisibleTops,
                    revealMessageIds = revealMessageIds
                )
            }
        } finally {
            if (rv.itemAnimator == null && previousItemAnimator != null) {
                rv.itemAnimator = previousItemAnimator
            }
            if (shouldSuppressFabForInternalMotion) {
                suppressScrollFabUntilAtBottom = false
            }
            rv.postOnAnimation { scheduleScrollFabUpdate() }
        }
    }

    private fun captureVisibleItemTops(rv: RecyclerView): Map<String, Int> {
        if (rv.childCount == 0) return emptyMap()
        val result = LinkedHashMap<String, Int>(rv.childCount)
        for (index in 0 until rv.childCount) {
            val child = rv.getChildAt(index) ?: continue
            val adapterPosition = rv.getChildAdapterPosition(child)
            if (adapterPosition == RecyclerView.NO_POSITION) continue
            val itemId = chatAdapter.currentList.getOrNull(adapterPosition)?.id ?: continue
            result[itemId] = child.top
        }
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitTypingMessageRevealBeforeNextDraw(
        anchorToBottom: Boolean,
        previousVisibleTops: Map<String, Int>,
        revealMessageIds: Set<String>
    ) = suspendCancellableCoroutine { cont ->
        val rv = binding.recyclerViewMessages
        val lm = rv.layoutManager as? LinearLayoutManager
        if (lm == null || chatAdapter.itemCount <= 0) {
            cont.resume(Unit) { }
            return@suspendCancellableCoroutine
        }

        var attempts = 0
        var didResume = false
        lateinit var listener: android.view.ViewTreeObserver.OnPreDrawListener
        lateinit var fallback: Runnable

        fun resumeOnce() {
            if (didResume) return
            didResume = true
            if (cont.isActive) cont.resume(Unit) { }
        }

        fun removeListenerIfAlive() {
            if (rv.viewTreeObserver.isAlive) {
                rv.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            rv.removeCallbacks(fallback)
        }

        fallback = Runnable {
            removeListenerIfAlive()
            resumeOnce()
        }

        listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val target = chatAdapter.itemCount - 1
                if (target < 0) {
                    removeListenerIfAlive()
                    resumeOnce()
                    return true
                }

                val targetView = lm.findViewByPosition(target)
                if (anchorToBottom && targetView == null && attempts < 2) {
                    attempts += 1
                    rv.scrollToPosition(target)
                    rv.requestLayout()
                    rv.invalidate()
                    return false
                }

                if (anchorToBottom && targetView != null) {
                    ensureLastItemFullyVisibleAboveBottomUi(target)
                }

                val animatedChildren = prepareTypingRevealChildTranslations(
                    rv = rv,
                    previousVisibleTops = previousVisibleTops,
                    revealMessageIds = revealMessageIds
                )
                removeListenerIfAlive()
                if (animatedChildren.isEmpty()) {
                    resumeOnce()
                } else {
                    rv.postOnAnimation {
                        animateTypingRevealChildTranslations(animatedChildren) {
                            resumeOnce()
                        }
                    }
                }
                return true
            }
        }

        rv.viewTreeObserver.addOnPreDrawListener(listener)
        cont.invokeOnCancellation { removeListenerIfAlive() }
        rv.postDelayed(fallback, 120L)
        rv.requestLayout()
        rv.invalidate()
    }

    private fun prepareTypingRevealChildTranslations(
        rv: RecyclerView,
        previousVisibleTops: Map<String, Int>,
        revealMessageIds: Set<String>
    ): List<View> {
        if (rv.childCount == 0) return emptyList()
        val revealItemIds = revealMessageIds.asSequence()
            .map { "msg_$it" }
            .toHashSet()
        val isExplicitReveal = revealItemIds.isNotEmpty()
        val animatedChildren = ArrayList<View>(rv.childCount)

        for (index in 0 until rv.childCount) {
            val child = rv.getChildAt(index) ?: continue
            val adapterPosition = rv.getChildAdapterPosition(child)
            if (adapterPosition == RecyclerView.NO_POSITION) continue
            val itemId = chatAdapter.currentList.getOrNull(adapterPosition)?.id ?: continue
            val previousTop = previousVisibleTops[itemId]
            when {
                previousTop != null && !isExplicitReveal -> {
                    val delta = (previousTop - child.top).toFloat()
                    if (kotlin.math.abs(delta) > 0.5f) {
                        child.animate().cancel()
                        child.translationY = delta
                        child.setHasTransientState(true)
                        animatedChildren.add(child)
                    }
                }
                itemId in revealItemIds -> {
                    // Keep new rows stable during typing-to-message handoff; alpha pulses
                    // here can stack with rapid emissions and look like multi-flicker.
                    child.animate().cancel()
                    child.translationY = 0f
                    child.alpha = 1f
                    child.setHasTransientState(false)
                }
            }
        }
        return animatedChildren
    }

    private fun animateTypingRevealChildTranslations(
        children: List<View>,
        onComplete: () -> Unit
    ) {
        if (children.isEmpty()) {
            onComplete()
            return
        }
        var remaining = children.size
        children.forEach { child ->
            child.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    child.translationY = 0f
                    child.alpha = 1f
                    child.setHasTransientState(false)
                    remaining -= 1
                    if (remaining == 0) onComplete()
                }
                .start()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitIdleBeforeLargeListCommitIfNeeded(
        targetList: List<ChatListItem>,
        source: String
    ) {
        if (!::chatAdapter.isInitialized || !::binding.isInitialized) return

        val rv = binding.recyclerViewMessages
        val currentList = chatAdapter.currentList
        if (currentList.isEmpty()) return

        val currentMessageCount = currentList.count { it is ChatListItem.MessageItem }
        val targetMessageCount = targetList.count { it is ChatListItem.MessageItem }
        val itemDelta = kotlin.math.abs(targetList.size - currentList.size)
        val messageDelta = kotlin.math.abs(targetMessageCount - currentMessageCount)
        if (itemDelta < ACTIVE_SCROLL_LIST_COMMIT_DEFER_ITEM_DELTA && messageDelta < ACTIVE_SCROLL_LIST_COMMIT_DEFER_MESSAGE_DELTA) {
            return
        }

        awaitKeyboardSettledBeforeLargeListCommitIfNeeded(
            targetList = targetList,
            source = source,
            itemDelta = itemDelta,
            messageDelta = messageDelta
        )

        // Always defer if first scroll is active, even if deltas are small.
        // This prevents jank from any list commits during the critical first scroll interaction.
        val firstScrollActive = ::scrollController.isInitialized && scrollController.firstScrollTrackingActive
        // During SETTLING (fling deceleration), pagination commits go through immediately
        // so the fling is continuously fed with rows and never hits a wall. The fling
        // already has natural slack between deceleration frames for the commit to land.
        // During DRAGGING (finger on screen), we defer to maximize main-thread headroom.
        val scrollState = rv.scrollState
        val activelyScrolling = scrollState == RecyclerView.SCROLL_STATE_DRAGGING || firstScrollActive
        if (!activelyScrolling) return

        val lm = rv.layoutManager as? LinearLayoutManager
        StartupTrace.logStage(
            "chat_list_commit_deferred",
            "chatId=${chatId ?: "unknown"} source=$source currentItems=${currentList.size} targetItems=${targetList.size} currentMessages=$currentMessageCount targetMessages=$targetMessageCount state=${rv.scrollState} first=${lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION}"
        )

        suspendCancellableCoroutine<Unit> { cont ->
            lateinit var listener: RecyclerView.OnScrollListener

            fun resumeIfIdle() {
                if (!cont.isActive) return
                if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    rv.removeOnScrollListener(listener)
                    cont.resume(Unit) { }
                }
            }

            listener = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        resumeIfIdle()
                    }
                }
            }

            rv.addOnScrollListener(listener)
            rv.post { resumeIfIdle() }
            cont.invokeOnCancellation { rv.removeOnScrollListener(listener) }
        }

        val resumedLm = rv.layoutManager as? LinearLayoutManager
        StartupTrace.logStage(
            "chat_list_commit_resumed",
            "chatId=${chatId ?: "unknown"} source=$source targetItems=${targetList.size} targetMessages=$targetMessageCount first=${resumedLm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${resumedLm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION}"
        )
    }

    private enum class TypingIndicatorTransition {
        NONE,
        APPEAR,
        DISAPPEAR
    }

    private fun detectTypingIndicatorTransition(
        wasVisible: Boolean,
        willBeVisible: Boolean
    ): TypingIndicatorTransition {
        return when {
            !wasVisible && willBeVisible -> TypingIndicatorTransition.APPEAR
            wasVisible && !willBeVisible -> TypingIndicatorTransition.DISAPPEAR
            else -> TypingIndicatorTransition.NONE
        }
    }



    private var isTypingTransitionRunning = false
    private var typingRecyclerPushAnimator: ValueAnimator? = null

    private suspend fun submitListWithTypingTransition(
        targetList: List<ChatListItem>,
        transition: TypingIndicatorTransition,
        anchorToBottom: Boolean
    ): List<ChatListItem> {
        awaitIdleBeforeLargeListCommitIfNeeded(
            targetList = targetList,
            source = "typing_transition_$transition"
        )

        // Broaden detection: if we are at bottom OR the indicator is currently visible on screen, we animate.
        val indicatorIndexBefore = chatAdapter.currentList.indexOfLast { it is ChatListItem.TypingIndicator }
        val isIndicatorVisibleOnScreen = if (indicatorIndexBefore != -1) {
            val vh = binding.recyclerViewMessages.findViewHolderForAdapterPosition(indicatorIndexBefore)
            vh?.itemView != null && vh.itemView.isAttachedToWindow && vh.itemView.visibility == View.VISIBLE && vh.itemView.alpha > 0f
        } else false

        val finalShouldAnimatePush = anchorToBottom || isRecyclerNearBottom() || isRecyclerAtBottom() || isIndicatorVisibleOnScreen
        val shouldSuppressFabForInternalMotion = finalShouldAnimatePush && !userHasScrolledUp && unreadCount == 0
        val rv = binding.recyclerViewMessages

        if (transition == TypingIndicatorTransition.NONE) {
            submitListAwait(targetList)
            return targetList
        }

        val previousItemAnimator = rv.itemAnimator
        rv.itemAnimator = null
        isTypingTransitionRunning = true
        var typingFollowTranslatedChildren: List<View> = emptyList()
        if (shouldSuppressFabForInternalMotion) {
            suppressScrollFabUntilAtBottom = true
            hideScrollFabImmediately()
        }

        try {
            when (transition) {
                TypingIndicatorTransition.APPEAR -> {
                    submitListAwait(targetList)
                    if (finalShouldAnimatePush) {
                        // Wait for layout to ensure the new item is measured and scroll range updated.
                        rv.doOnLayoutAwait()

                        val pushDistance = resolveTypingIndicatorShiftPx()
                        if (pushDistance > 0) {
                            animateRecyclerTypingPush(showing = true, distancePx = pushDistance)
                            ensureLastItemFullyVisibleAboveBottomUi((chatAdapter.itemCount - 1).coerceAtLeast(0))
                        }
                    }
                }

                TypingIndicatorTransition.DISAPPEAR -> {
                    // To animate 'out' (slide down), we first make the indicator invisible but KEEP it in the list.
                    // This keeps layout stable while visible rows follow it with sub-pixel translation.
                    val existingIndicator = chatAdapter.currentList.lastOrNull { it is ChatListItem.TypingIndicator } as? ChatListItem.TypingIndicator
                    val pushDistance = if (finalShouldAnimatePush) {
                        resolveTypingIndicatorShiftPx()
                    } else {
                        0
                    }

                    val basePlaceholder = existingIndicator ?: ChatListItem.TypingIndicator(
                        isVisible = false,
                        isExpressive = false,
                        liveText = "",
                        sentiment = com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL
                    )
                    // Just toggle visibility off, keep everything else exactly same
                    val placeholderList = targetList + basePlaceholder.copy(
                        isVisible = false,
                        synchronizeHideWithListMotion = true
                    )

                    submitListAwait(placeholderList)

                    if (finalShouldAnimatePush) {
                        // Start reverse list motion immediately after the placeholder commit so
                        // RecyclerView movement shares the same frame window as the row hide.
                        if (pushDistance > 0) {
                            typingFollowTranslatedChildren = animateRecyclerTypingHideFollow(distancePx = pushDistance)
                        } else {
                            delay(TYPING_INDICATOR_TRANSITION_MS)
                        }
                    } else {
                        delay(TYPING_INDICATOR_TRANSITION_MS)
                    }

                    // Finally, remove the item from the list entirely while the transition gate is still active.
                    submitListAwait(targetList)
                    if (typingFollowTranslatedChildren.isNotEmpty()) {
                        clearTypingFollowTranslationsOnNextPreDraw(typingFollowTranslatedChildren)
                        typingFollowTranslatedChildren = emptyList()
                    }
                }

                TypingIndicatorTransition.NONE -> Unit
            }
            return targetList
        } finally {
            typingRecyclerPushAnimator?.cancel()
            typingRecyclerPushAnimator = null
            if (typingFollowTranslatedChildren.isNotEmpty()) {
                clearTypingFollowTranslations(typingFollowTranslatedChildren)
            }
            isTypingTransitionRunning = false
            if (shouldSuppressFabForInternalMotion) {
                suppressScrollFabUntilAtBottom = false
            }
            if (rv.itemAnimator == null && previousItemAnimator != null) {
                rv.itemAnimator = previousItemAnimator
            }
            rv.postOnAnimation { scheduleScrollFabUpdate() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun View.doOnLayoutAwait() = suspendCancellableCoroutine { cont ->
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                v?.removeOnLayoutChangeListener(this)
                if (cont.isActive) cont.resume(Unit) {}
            }
        }
        addOnLayoutChangeListener(listener)
        // If the view is already invoking layout, we might miss it if we don't check implementation details,
        // but typically a requestLayout + post is safer. 
        // For robustness, if layout doesn't happen quickly, resume anyway to avoid ANR-like hangs in coroutine
        postDelayed({
             if (cont.isActive) {
                 removeOnLayoutChangeListener(listener)
                 cont.resume(Unit) {}
             }
        }, 100)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun animateRecyclerTypingPush(showing: Boolean, distancePx: Int) {
        if (distancePx <= 0) return
        val rv = binding.recyclerViewMessages
        val bottomSafeAnchorPosition = resolveBottomSafeTailAdapterPosition()
        typingRecyclerPushAnimator?.cancel()
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            rv.stopScroll()
        }

        suspendCancellableCoroutine<Unit> { cont ->
            var appliedPx = 0
            val animator = ValueAnimator.ofFloat(0f, distancePx.toFloat()).apply {
                duration = TYPING_INDICATOR_TRANSITION_MS
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { valueAnimator ->
                    val value = valueAnimator.animatedValue as Float
                    val targetPx = (if (showing) value else -value).roundToInt()
                    val delta = targetPx - appliedPx
                    if (delta != 0) {
                        rv.scrollBy(0, delta)
                        if (!showing && bottomSafeAnchorPosition != RecyclerView.NO_POSITION) {
                            ensureLastItemFullyVisibleAboveBottomUi(bottomSafeAnchorPosition)
                        }
                        appliedPx += delta
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (typingRecyclerPushAnimator == animation) {
                            typingRecyclerPushAnimator = null
                        }
                        if (cont.isActive) cont.resume(Unit) { }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (typingRecyclerPushAnimator == animation) {
                            typingRecyclerPushAnimator = null
                        }
                        if (cont.isActive) cont.resume(Unit) { }
                    }
                })
            }

            cont.invokeOnCancellation { animator.cancel() }
            typingRecyclerPushAnimator = animator
            animator.start()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun animateRecyclerTypingHideFollow(distancePx: Int): List<View> {
        if (distancePx <= 0) return emptyList()
        val rv = binding.recyclerViewMessages
        typingRecyclerPushAnimator?.cancel()
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            rv.stopScroll()
        }

        val translatedChildren = captureTypingFollowChildren(rv)
        if (translatedChildren.isEmpty()) {
            delay(TYPING_INDICATOR_TRANSITION_MS)
            return emptyList()
        }

        translatedChildren.forEach { child ->
            child.animate().cancel()
            child.translationY = 0f
            child.setHasTransientState(true)
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val animator = ValueAnimator.ofFloat(0f, distancePx.toFloat()).apply {
                duration = TYPING_INDICATOR_TRANSITION_MS
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { valueAnimator ->
                    val value = valueAnimator.animatedValue as Float
                    translatedChildren.forEach { child ->
                        child.translationY = value
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (typingRecyclerPushAnimator == animation) {
                            typingRecyclerPushAnimator = null
                        }
                        if (cont.isActive) cont.resume(Unit) { }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (typingRecyclerPushAnimator == animation) {
                            typingRecyclerPushAnimator = null
                        }
                        clearTypingFollowTranslations(translatedChildren)
                        if (cont.isActive) cont.resume(Unit) { }
                    }
                })
            }

            cont.invokeOnCancellation {
                animator.cancel()
                clearTypingFollowTranslations(translatedChildren)
            }
            typingRecyclerPushAnimator = animator
            animator.start()
        }

        return translatedChildren
    }

    private fun captureTypingFollowChildren(rv: RecyclerView): List<View> {
        val children = ArrayList<View>(rv.childCount)
        for (index in 0 until rv.childCount) {
            val child = rv.getChildAt(index) ?: continue
            val adapterPosition = rv.getChildAdapterPosition(child)
            if (adapterPosition == RecyclerView.NO_POSITION) continue
            if (chatAdapter.currentList.getOrNull(adapterPosition) is ChatListItem.TypingIndicator) continue
            children.add(child)
        }
        return children
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun clearTypingFollowTranslationsOnNextPreDraw(children: List<View>) {
        if (children.isEmpty()) return
        val rv = binding.recyclerViewMessages
        suspendCancellableCoroutine<Unit> { cont ->
            val observer = rv.viewTreeObserver
            val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (rv.viewTreeObserver.isAlive) {
                        rv.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    clearTypingFollowTranslations(children)
                    if (cont.isActive) cont.resume(Unit) { }
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
            cont.invokeOnCancellation {
                if (rv.viewTreeObserver.isAlive) {
                    rv.viewTreeObserver.removeOnPreDrawListener(listener)
                }
                clearTypingFollowTranslations(children)
            }
            rv.requestLayout()
            rv.invalidate()
        }
    }

    private fun clearTypingFollowTranslations(children: List<View>) {
        children.forEach { child ->
            child.translationY = 0f
            child.setHasTransientState(false)
        }
    }

    private fun resolveTypingIndicatorShiftPx(): Int {
        // Try to find the actual view holder first as it's the source of truth after layout
        val rv = binding.recyclerViewMessages
        val indicatorPos = chatAdapter.currentList.indexOfLast { it is ChatListItem.TypingIndicator }
        if (indicatorPos != -1) {
            val vh = rv.findViewHolderForAdapterPosition(indicatorPos)
            val measured = vh?.itemView?.height ?: 0
            if (measured > 0) {
                typingIndicatorHeightPx = measured
                return measured
            }
        }

        // Use a more dynamic fallback based on density if we don't have a measurement.
        // A typical chat bubble height is ~44-48dp.
        if (typingIndicatorHeightPx > 0) {
            return typingIndicatorHeightPx
        }

        val density = resources.displayMetrics.density
        return (TYPING_INDICATOR_FALLBACK_HEIGHT_DP * density).toInt()
    }

    private fun resolveBottomSafeTailAdapterPosition(): Int {
        for (index in chatAdapter.currentList.indices.reversed()) {
            if (chatAdapter.currentList[index] is ChatListItem.MessageItem) {
                return index
            }
        }
        return (chatAdapter.itemCount - 1).coerceAtLeast(RecyclerView.NO_POSITION)
    }

    private fun updateDisplayedMessageIds(list: List<ChatListItem>) {
        displayedMessageIds = list.asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message.id }
            .toMutableSet()
    }

    /**
     * Returns only truly new incoming messages appended after the previously rendered tail.
     *
     * This excludes history hydration/prepend deltas (prefill -> first full live flow), which
     * can otherwise look "new" by ID-diff and incorrectly retrigger romantic effects on chat open.
     */
    private fun computeTailAppendedIncomingIds(
        stableMessages: List<Message>,
        previousStableMessages: List<Message>,
        incomingCandidateIds: Set<String>
    ): Set<String> {
        if (incomingCandidateIds.isEmpty()) return emptySet()
        if (previousStableMessages.isEmpty()) return emptySet()

        val previousTailId = previousStableMessages.lastOrNull()?.id ?: return emptySet()
        val previousTailIndex = stableMessages.indexOfLast { it.id == previousTailId }
        if (previousTailIndex == -1) return emptySet()

        return stableMessages
            .asSequence()
            .drop(previousTailIndex + 1)
            .filter { it.isIncoming && incomingCandidateIds.contains(it.id) }
            .map { it.id }
            .toSet()
    }

    /**
     * Stable message ordering with position lock.
     *
     * PROBLEM: COALESCE(serverTimestamp, clientTimestamp) causes visible position swaps when:
     *   1. Both devices send simultaneously (A at t=490, B at t=663)
     *   2. A settles first with fsTs=830 — now A's key (830) > B's pending key (663)
     *   3. B jumps before A → visible swap. B settles at fsTs=851 → swaps back.
     *
     * SOLUTION: once a message has been rendered at a position, it NEVER moves.
     * New messages are inserted at the correct COALESCE position relative to already-rendered
     * messages. This matches how WhatsApp/Telegram handle concurrent sends — each device shows
     * a stable view, accepting that near-simultaneous messages may appear in different order
     * on each device for their brief overlap window.
     */
    private fun computeStableMessageOrder(messages: List<Message>, previousOrder: List<String>): List<Message> {
        if (previousOrder.isEmpty()) {
            return messages.sortedWith(compareBy({ it.serverTimestamp ?: it.timestamp }, { it.id }))
        }

        val msgById = messages.associateBy { it.id }
        val prevSet = previousOrder.toHashSet()
        val stable = previousOrder.mapNotNull { msgById[it] }
        val newMsgs = messages.filter { it.id !in prevSet }
            .sortedWith(compareBy({ it.serverTimestamp ?: it.timestamp }, { it.id }))

        val merged = stable.toMutableList()
        for (newMsg in newMsgs) {
            val key = newMsg.serverTimestamp ?: newMsg.timestamp
            val insertAt = merged.indexOfFirst { existing ->
                val existKey = existing.serverTimestamp ?: existing.timestamp
                existKey > key || (existKey == key && existing.id > newMsg.id)
            }
            if (insertAt == -1) merged.add(newMsg) else merged.add(insertAt, newMsg)
        }
        return merged
    }

    private fun stabilizeMessageOrder(messages: List<Message>): List<Message> {
        val settled = messages.count { it.serverTimestamp != null }
        val pending = messages.size - settled

        val result = computeStableMessageOrder(messages, renderedMessageOrder)

        // Update the lock for this emission.
        renderedMessageOrder = result.map { it.id }

        // Debug logging — filter logcat with: adb logcat -s MsgOrder
        android.util.Log.d("MsgOrder", "[SORT] total=${result.size} settled=$settled pending=$pending" +
            " last5=${result.takeLast(5).joinToString("|") { m ->
                val sid = m.id.takeLast(4)
                val sTs = m.serverTimestamp
                val cTs = m.timestamp
                if (sTs != null) "$sid/fs=$sTs" else "$sid/PENDING/c=$cTs"
            }}")
        return result
    }

    private fun processMessagesWithHeaders(
        messages: List<Message>, 
        showTypingIndicator: Boolean = false,
        useInvisibleTypingPlaceholder: Boolean = false,
        newIncomingIds: Set<String> = emptySet(),
        expressiveActive: Boolean = false,
        expressiveText: String = "",
        expressiveSent: com.glyph.glyph_v3.ui.chat.expressive.SentimentType =
            com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL
    ): List<ChatListItem> {
        val tempResult = ArrayList<ChatListItem>(messages.size + 20)
        var lastDate: LocalDate? = null
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        var currentHeaderText = ""
        val showGroupIntro = shouldShowGroupIntroInList(messages)
        var groupIntroInserted = false

        if (showGroupIntro && messages.isEmpty()) {
            tempResult.add(buildGroupIntroItem())
            groupIntroInserted = true
        }

        // Pass 1: Create items and compute heavy properties (Emoji)
        for (message in messages) {
            message.warmUpForUi()

            // Use cached date to avoid java.time allocations on every rebuild
            val messageDate = messageDateCache.getOrPut(message.id) {
                Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
            }
            if (messageDate != lastDate) {
                currentHeaderText = when (messageDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateFormatter.format(messageDate)
                }
                tempResult.add(ChatListItem.DateHeader(currentHeaderText))
                lastDate = messageDate
                if (showGroupIntro && !groupIntroInserted) {
                    tempResult.add(buildGroupIntroItem())
                    groupIntroInserted = true
                }
            }

            // Use cached emoji-only check to avoid O(n) Unicode scan on every rebuild
            val isEmoji = messageEmojiCache.getOrPut(message.id) {
                EmojiUtils.isEmojiOnlyMessage(message.text)
            }
            val shouldAnimate = newIncomingIds.contains(message.id)
            val txState = translationStateMap[message.id]
            tempResult.add(
                ChatListItem.MessageItem(
                    message = message,
                    groupPosition = BubbleGroupPosition.SINGLE,
                    dateString = currentHeaderText,
                    isEmojiContent = isEmoji,
                    shouldAnimateEntry = shouldAnimate,
                    translatedText = txState?.translatedText,
                    isShowingTranslation = txState?.isShowingTranslation ?: false,
                    isTranslating = txState?.isTranslating ?: false
                )
            )
        }

        // Pass 2: Compute bubble grouping
        for (i in tempResult.indices) {
            val item = tempResult[i]
            if (item is ChatListItem.MessageItem) {
                val prev = if (i > 0) tempResult[i - 1] else null
                val next = if (i < tempResult.size - 1) tempResult[i + 1] else null
                
                val hasPrevSame = (prev is ChatListItem.MessageItem) && prev.message.senderId == item.message.senderId
                val hasNextSame = (next is ChatListItem.MessageItem) && next.message.senderId == item.message.senderId
                
                val groupPos = when {
                    hasPrevSame && hasNextSame -> BubbleGroupPosition.MIDDLE
                    hasPrevSame && !hasNextSame -> BubbleGroupPosition.BOTTOM
                    !hasPrevSame && hasNextSame -> BubbleGroupPosition.TOP
                    else -> BubbleGroupPosition.SINGLE
                }
                
                if (groupPos != BubbleGroupPosition.SINGLE) {
                    tempResult[i] = item.copy(groupPosition = groupPos)
                }
            }
        }
        
        // Pass 3: Append Typing Indicator if requested
        if (showTypingIndicator) {
             tempResult.add(ChatListItem.TypingIndicator(
                 isVisible = !useInvisibleTypingPlaceholder,
                 isExpressive = expressiveActive,
                 liveText = expressiveText,
                 sentiment = expressiveSent
             ))
        }
        
        return tempResult
    }

    private fun tryBuildFastAppendedMessageList(
        previousMessages: List<Message>,
        messages: List<Message>,
        showTypingIndicator: Boolean,
        newIncomingIds: Set<String>,
        expressiveActive: Boolean,
        expressiveText: String,
        expressiveSent: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
    ): List<ChatListItem>? {
        if (processedListItems.isEmpty()) return null
        if (messages.size != previousMessages.size + 1) return null
        if (previousMessages.isEmpty()) return null
        if (messages.subList(0, previousMessages.size) != previousMessages) return null
        if (shouldShowGroupIntroInList(previousMessages) || shouldShowGroupIntroInList(messages)) return null

        val appendedMessage = messages.lastOrNull() ?: return null
        val baseList = processedListItems
            .dropLastWhile { it is ChatListItem.TypingIndicator }
            .toMutableList()
        val lastMessageIndex = baseList.indexOfLast { it is ChatListItem.MessageItem }
        val lastMessageItem = baseList.getOrNull(lastMessageIndex) as? ChatListItem.MessageItem ?: return null

        val previousDate = messageDateCache.getOrPut(lastMessageItem.message.id) {
            Instant.ofEpochMilli(lastMessageItem.message.timestamp).atZone(zoneId).toLocalDate()
        }
        val appendedDate = messageDateCache.getOrPut(appendedMessage.id) {
            Instant.ofEpochMilli(appendedMessage.timestamp).atZone(zoneId).toLocalDate()
        }
        val appendedHeaderText = when (appendedDate) {
            LocalDate.now(zoneId) -> "Today"
            LocalDate.now(zoneId).minusDays(1) -> "Yesterday"
            else -> dateFormatter.format(appendedDate)
        }

        var appendedGroupPosition = BubbleGroupPosition.SINGLE
        if (previousDate == appendedDate && lastMessageItem.message.senderId == appendedMessage.senderId) {
            val previousNeighbor = baseList.getOrNull(lastMessageIndex - 1) as? ChatListItem.MessageItem
            val updatedLastPosition = if (previousNeighbor?.message?.senderId == appendedMessage.senderId) {
                BubbleGroupPosition.MIDDLE
            } else {
                BubbleGroupPosition.TOP
            }
            if (lastMessageItem.groupPosition != updatedLastPosition) {
                baseList[lastMessageIndex] = lastMessageItem.copy(groupPosition = updatedLastPosition)
            }
            appendedGroupPosition = BubbleGroupPosition.BOTTOM
        } else if (previousDate != appendedDate) {
            baseList.add(ChatListItem.DateHeader(appendedHeaderText))
        }

        val translationState = translationStateMap[appendedMessage.id]
        baseList.add(
            ChatListItem.MessageItem(
                message = appendedMessage,
                groupPosition = appendedGroupPosition,
                dateString = appendedHeaderText,
                isEmojiContent = messageEmojiCache.getOrPut(appendedMessage.id) {
                    EmojiUtils.isEmojiOnlyMessage(appendedMessage.text)
                },
                shouldAnimateEntry = newIncomingIds.contains(appendedMessage.id),
                translatedText = translationState?.translatedText,
                isShowingTranslation = translationState?.isShowingTranslation ?: false,
                isTranslating = translationState?.isTranslating ?: false
            )
        )

        if (showTypingIndicator) {
            baseList.add(
                ChatListItem.TypingIndicator(
                    isVisible = true,
                    isExpressive = expressiveActive,
                    liveText = expressiveText,
                    sentiment = expressiveSent
                )
            )
        }

        traceUi(
            stage = "list_commit_fast_append_prepare",
            details = "chatId=${chatId ?: "unknown"} messageId=${appendedMessage.id} total=${messages.size}"
        )
        return baseList
    }

    /**
     * Incremental prepend for pagination: when older messages are loaded, only the NEW
     * (older) messages are processed and prepended to the existing [processedListItems].
     * Returns null if prepend is not applicable (tail changed, first load, etc.),
     * causing the caller to fall back to a full [processMessagesWithHeaders] rebuild.
     *
     * This avoids the O(n) cost of re-processing the entire message list on every
     * pagination step — critical for chats with 500+ messages where each Room emission
     * returns the full window. Only the delta is processed.
     */
    private fun tryBuildPrependedMessageList(
        previousMessages: List<Message>,
        messages: List<Message>,
        showTypingIndicator: Boolean,
        newIncomingIds: Set<String>,
        expressiveActive: Boolean,
        expressiveText: String,
        expressiveSent: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
    ): List<ChatListItem>? {
        // Only applicable when pagination loaded strictly older messages — the tail
        // (newest messages) must be identical to the previous emission.
        val newCount = messages.size - previousMessages.size
        if (newCount <= 0) return null
        if (processedListItems.isEmpty()) return null
        if (previousMessages.isEmpty()) return null
        if (messages.subList(newCount, messages.size) != previousMessages) return null
        if (shouldShowGroupIntroInList(previousMessages)) return null

        val newOlderMessages = messages.subList(0, newCount)
        val newItems = ArrayList<ChatListItem>(newCount + 8)

        // ── Process new (older) messages: date headers + items ─────────────────
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val firstExistingMsg = previousMessages.firstOrNull() ?: return null
        val firstExistingDate = Instant.ofEpochMilli(firstExistingMsg.timestamp).atZone(zoneId).toLocalDate()
        var lastDate: LocalDate? = null

        for (message in newOlderMessages) {
            message.warmUpForUi()
            val messageDate = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
            if (messageDate != lastDate) {
                val headerText = when (messageDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateFormatter.format(messageDate)
                }
                newItems.add(ChatListItem.DateHeader(headerText))
                lastDate = messageDate
            }
            val isEmoji = EmojiUtils.isEmojiOnlyMessage(message.text)
            val shouldAnimate = newIncomingIds.contains(message.id)
            val txState = translationStateMap[message.id]
            newItems.add(
                ChatListItem.MessageItem(
                    message = message,
                    groupPosition = BubbleGroupPosition.SINGLE,
                    dateString = "",
                    isEmojiContent = isEmoji,
                    shouldAnimateEntry = shouldAnimate,
                    translatedText = txState?.translatedText,
                    isShowingTranslation = txState?.isShowingTranslation ?: false,
                    isTranslating = txState?.isTranslating ?: false
                )
            )
        }

        // ── Compute bubble grouping within the new block ──────────────────────
        for (i in newItems.indices) {
            val item = newItems[i]
            if (item is ChatListItem.MessageItem) {
                val prev = if (i > 0) newItems[i - 1] else null
                val next = if (i < newItems.size - 1) newItems[i + 1] else null
                val hasPrevSame = (prev is ChatListItem.MessageItem) && prev.message.senderId == item.message.senderId
                val hasNextSame = (next is ChatListItem.MessageItem) && next.message.senderId == item.message.senderId
                if (hasPrevSame || hasNextSame) {
                    val groupPos = when {
                        hasPrevSame && hasNextSame -> BubbleGroupPosition.MIDDLE
                        hasPrevSame && !hasNextSame -> BubbleGroupPosition.BOTTOM
                        !hasPrevSame && hasNextSame -> BubbleGroupPosition.TOP
                        else -> BubbleGroupPosition.SINGLE
                    }
                    newItems[i] = item.copy(groupPosition = groupPos)
                }
            }
        }

        // ── Base list: drop typing indicator, strip first DateHeader if needed ─
        val baseList = processedListItems
            .dropLastWhile { it is ChatListItem.TypingIndicator }
            .toMutableList()

        // ── Boundary fixup: date headers ──────────────────────────────────────
        // If the new block's last date matches the base list's first existing
        // date, remove the base list's leading DateHeader — the new block already
        // provides a date header for that date.
        val firstBaseDateHeaderIdx = baseList.indexOfFirst { it is ChatListItem.DateHeader }
        if (firstBaseDateHeaderIdx >= 0 && lastDate == firstExistingDate) {
            baseList.removeAt(firstBaseDateHeaderIdx)
        }

        // ── Boundary fixup: bubble grouping between last-new and first-existing ─
        val lastNewMsgIdx = newItems.indexOfLast { it is ChatListItem.MessageItem }
        val firstBaseMsgIdx = baseList.indexOfFirst { it is ChatListItem.MessageItem }
        if (lastNewMsgIdx >= 0 && firstBaseMsgIdx >= 0) {
            val lastNew = newItems[lastNewMsgIdx] as ChatListItem.MessageItem
            val firstBase = baseList[firstBaseMsgIdx] as ChatListItem.MessageItem

            if (lastNew.message.senderId == firstBase.message.senderId) {
                // The boundary merges two same-sender blocks — adjust both sides.
                val newPrevIdx = if (lastNewMsgIdx > 0) lastNewMsgIdx - 1 else -1
                val newPrev = newItems.getOrNull(newPrevIdx) as? ChatListItem.MessageItem
                val hasPrevSameInNew = newPrev?.message?.senderId == lastNew.message.senderId

                val baseNextIdx = if (firstBaseMsgIdx + 1 < baseList.size) firstBaseMsgIdx + 1 else -1
                val baseNext = baseList.getOrNull(baseNextIdx) as? ChatListItem.MessageItem
                val hasNextSameInBase = baseNext?.message?.senderId == firstBase.message.senderId

                val newLastGroup = if (hasPrevSameInNew) BubbleGroupPosition.MIDDLE
                                   else BubbleGroupPosition.TOP
                newItems[lastNewMsgIdx] = lastNew.copy(groupPosition = newLastGroup)

                val baseFirstGroup = if (hasNextSameInBase) BubbleGroupPosition.MIDDLE
                                     else BubbleGroupPosition.BOTTOM
                if (firstBase.groupPosition != baseFirstGroup) {
                    baseList[firstBaseMsgIdx] = firstBase.copy(groupPosition = baseFirstGroup)
                }
            }
        }

        // ── Assemble: new items + existing base + typing indicator ────────────
        newItems.addAll(baseList)
        if (showTypingIndicator) {
            newItems.add(
                ChatListItem.TypingIndicator(
                    isVisible = true,
                    isExpressive = expressiveActive,
                    liveText = expressiveText,
                    sentiment = expressiveSent
                )
            )
        }

        traceUi(
            stage = "list_commit_prepend_prepare",
            details = "chatId=${chatId ?: "unknown"} prepended=$newCount total=${messages.size}"
        )
        return newItems
    }

    private fun updateSendButtonState(hasText: Boolean) {
        if (isSendState == hasText) return
        isSendState = hasText

        val targetIcon = if (hasText) R.drawable.ic_send_custom else R.drawable.ic_mic_custom
        val contentDescription = if (hasText) "Send" else "Record Voice"
        val iconView = binding.ivSendIcon

        // Fast, responsive animation: quick shrink, swap, and expand back to normal
        iconView.animate().cancel()
        iconView.scaleX = 1f
        iconView.scaleY = 1f

        val shrinkScale = 0.8f
        val shrinkDuration = 40L
        val expandDuration = 80L

        iconView.animate()
            .scaleX(shrinkScale)
            .scaleY(shrinkScale)
            .setDuration(shrinkDuration)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                iconView.setImageResource(targetIcon)
                binding.btnSend.contentDescription = contentDescription

                iconView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(expandDuration)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            .start()
    }

    private fun playSendSound() {
        ensureSoundPoolInitialized()
        if (!isSoundPoolReleased && ::soundPool.isInitialized && soundSentId != 0 && !isFinishing && !isDestroyed) {
            soundPool.play(soundSentId, 1.0f, 1.0f, 0, 0, 1.0f)
        }
    }

    // =========================================================================================
    // EMOJI / GIF / STICKER PICKER (KLIPY)
    // =========================================================================================

    private fun setupEmojiPicker() {
        val panel = binding.emojiPickerPanel
        panel.attachToActivity(this)

        // Set initial height from saved keyboard height + current nav bar
        panel.setPickerHeight(
            keyboardHeightProvider.getKeyboardHeight(),
            getNavBarHeight()
        )

        // Listen for keyboard height changes and update picker panel height
        keyboardHeightProvider.onHeightChanged = { heightPx ->
            panel.setPickerHeight(heightPx, getNavBarHeight())
        }

        // Note: we removed the onKeyboardVisibilityChanged listener from here 
        // to prevent immediate logic reset when the keyboard starts rising.
        // The transition is now handled smoothly in setupWindowInsets via onEnd.

        // Start measuring keyboard height
        keyboardHeightProvider.start()

        // ── Expanded search mode callbacks ────────────────────────────
        panel.onSearchFocusChanged = { hasFocus ->
            if (hasFocus && panel.isPickerVisible) {
                // Search gained focus → show keyboard (the panel will expand once the
                // keyboard animation finishes in onEnd below, using expandForSearch)
                binding.etMessageInput.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(panel, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        panel.onModeChanged = { mode ->
            when (mode) {
                EmojiPickerPanel.PanelMode.EXPANDED -> {
                    // Hide the input bar while the panel is full-screen
                    binding.layoutInput.visibility = View.GONE
                }
                EmojiPickerPanel.PanelMode.HALF -> {
                    // Keep the input bar hidden while the picker stays half-open
                    binding.layoutInput.visibility = View.GONE
                }
                EmojiPickerPanel.PanelMode.COMPACT -> {
                    // Restore the input bar
                    showInputBarIfAllowed()
                    updateContentBottomPadding(lastIme = 0, systemBottom = getNavBarHeight())
                }
            }
        }

        // ── Drag-to-close callback ────────────────────────────────────
        panel.onDragClose = {
            // User dragged the panel down to close it.
            panel.clearAllSearchFocus()
            
            // CRITICAL: Set isPickerMode to false BEFORE triggering any layout changes.
            // This prevents the input bar from "snapping" to the picker height (compact mode)
            // before the keyboard starts rising.
            isPickerMode = false
            binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
            
            // Now collapse and hide the panel. 
            // panel.hide() will trigger the visibility and padding updates via onModeChanged
            // but since isPickerMode is now false, it won't force the height floor.
            panel.collapseToCompact()
            panel.hide()
            
            // Ensure input bar is visible and request focus
            showInputBarIfAllowed()
            binding.etMessageInput.requestFocus()
            
            // Trigger the keyboard to show. The WindowInsetsAnimationCallback will 
            // handle the smooth upward transition of the input bar.
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        panel.onCollapseToCompactRequested = {
            collapseExpandedSearch()
        }

        // Callbacks for media selection
        panel.onSystemEmojiSelected = { emoji ->
            val start = binding.etMessageInput.selectionStart
            val end = binding.etMessageInput.selectionEnd
            val text = binding.etMessageInput.text
            if (text != null) {
                text.replace(start, end, emoji)
            } else {
                binding.etMessageInput.setText(emoji)
                binding.etMessageInput.setSelection(emoji.length)
            }
        }

        panel.onEmojiSelected = { item ->
            sendKlipyMedia(item, MessageType.KLIPY_EMOJI) // Send as specific KLIPY_EMOJI type
            autoCloseExpandedPicker()
        }

        panel.onGifSelected = { item ->
            sendKlipyMedia(item, MessageType.GIF)
            autoCloseExpandedPicker()
        }

        panel.onStickerSelected = { item ->
            sendKlipyMedia(item, MessageType.STICKER)
            autoCloseExpandedPicker()
        }

        panel.onMemeSelected = { item ->
            sendKlipyMedia(item, MessageType.MEME)
            autoCloseExpandedPicker()
        }
    }

    private fun ensureEmojiPickerInitialized(reason: String) {
        if (emojiPickerInitialized) return
        emojiPickerInitialized = true
        setupEmojiPicker()
    }

    /**
     * Automatically closes or collapses the picker after media selection
     * if it's currently in expanded search mode.
     */
    private fun autoCloseExpandedPicker() {
        val panel = binding.emojiPickerPanel
        if (panel.panelMode == EmojiPickerPanel.PanelMode.EXPANDED) {
            // Restore normal chat view so user can see their sent message
            hideEmojiPicker()
        }
    }

    private fun toggleEmojiPicker() {
        ensureEmojiPickerInitialized(reason = "button_tap")
        val panel = binding.emojiPickerPanel
        if (panel.panelMode == EmojiPickerPanel.PanelMode.EXPANDED) {
            // If we're in expanded search mode, collapse back to compact first
            collapseExpandedSearch()
            return
        }
        if (isPickerMode) {
            // Switch back to keyboard. We request focus and show IME, but don't 
            // set isPickerMode=false yet. The WindowInsetsAnimationCallback 
            // will keep the layout stable using pickerHeight as a floor until 
            // the keyboard has fully risen, then it will call hideEmojiPicker().
            binding.etMessageInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            showEmojiPicker()
        }
    }

    private fun showEmojiPicker() {
        val currentIme = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom ?: 0
        val wasKeyboardVisible = currentIme > 0 || keyboardHeightProvider.isKeyboardVisible
        isPickerMode = true
        binding.btnEmoji.setImageResource(R.drawable.ic_keyboard)

        val navBar = getNavBarHeight()
        val pickerHeight = keyboardHeightProvider.getKeyboardHeight()

        if (!wasKeyboardVisible) {
            // Animate only if keyboard isn't already handling the transition
            val transition = androidx.transition.TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup, transition)
        }

        // Make the picker visible first so it is already in place when the IME hides.
        binding.emojiPickerPanel.setPickerHeight(pickerHeight, navBar)
        binding.emojiPickerPanel.show(animate = !wasKeyboardVisible)

        // Keep the input bar anchored while the keyboard animates away.
        updateContentBottomPadding(lastIme = pickerHeight, systemBottom = navBar)

        // Then dismiss the keyboard over the already-visible picker.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessageInput.windowToken, 0)

        // Final sync once the picker is in place.
        updateContentBottomPadding(lastIme = 0, systemBottom = navBar)
    }

    private fun hideEmojiPicker() {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
        val currentIme = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        
        if (currentIme == 0) {
            // Keyboard isn't taking over (e.g. user hit back or emoji button while picker was open)
            // so animate the drop.
            val transition = androidx.transition.TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .setDuration(250)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup, transition)
        }

        isPickerMode = false
        binding.emojiPickerPanel.hide()
        binding.btnEmoji.setImageResource(R.drawable.ic_emoji)

        // Ensure input bar is visible (it may have been hidden during expanded search)
        showInputBarIfAllowed()

        // Restore normal bottom padding based on the actual current IME state.
        // If the keyboard is visible/rising,ime.bottom will be > 0.
        // If not, it will be 0 and the input will drop to the navigation bar.
        updateContentBottomPadding(lastIme = currentIme, systemBottom = getNavBarHeight())
    }

    /**
     * Collapse from expanded search mode back to compact picker mode.
     * Hides the keyboard, clears search focus, and shrinks the panel.
     */
    private fun collapseExpandedSearch() {
        val panel = binding.emojiPickerPanel
        if (panel.panelMode != EmojiPickerPanel.PanelMode.EXPANDED) return

        // 1. Clear focus first (prevents the focus listener from re-triggering)
        panel.clearAllSearchFocus()

        // 2. Hide the keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(panel.windowToken, 0)

        // 3. Collapse the panel back to compact height
        panel.collapseToCompact()
    }

    private fun getNavBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    }

    /**
     * Send a Klipy media item (GIF, sticker, or emoji) as a message.
     * The media URL is stored in [Message.imageUrl] and the dimensions are preserved.
     */
    private fun sendKlipyMedia(item: com.glyph.glyph_v3.data.remote.KlipyMediaItem, type: MessageType) {
        if (isBlockedForSending()) return
        val id = chatId ?: return
        // Phase 8: groups have no single otherUserId. The 1:1 path still requires it.
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)
        val replyMsg = replyToMessage

        // Sending always snaps to the new message, regardless of read-history position.
        userHasScrolledUp = false
        pendingScrollToBottomOnNextListCommit = true

        lifecycleScope.launch {
            if (isGroupChat) {
                groupRepository.sendGroupKlipyMessage(
                    chatId = id,
                    type = type,
                    title = item.title,
                    imageUrl = item.fullUrl,
                    previewUrl = item.previewUrl,
                    mediaWidth = item.fullWidth,
                    mediaHeight = item.fullHeight,
                    replyToMessageId = replyMsg?.id,
                    replyToText = replyMsg?.text,
                    replyToSenderId = replyMsg?.senderId,
                    replyToType = replyMsg?.type
                )
            } else {
                repository.sendKlipyMediaMessage(
                    chatId = id,
                    otherUserId = otherId,
                    otherUsername = otherUsername.ifEmpty { "Unknown" },
                    otherUserAvatar = otherUserAvatar,
                    type = type,
                    title = item.title,
                    imageUrl = item.fullUrl,
                    previewUrl = item.previewUrl,
                    mediaWidth = item.fullWidth,
                    mediaHeight = item.fullHeight,
                    replyToMessageId = replyMsg?.id,
                    replyToText = replyMsg?.text,
                    replyToSenderId = replyMsg?.senderId,
                    replyToType = replyMsg?.type
                )
            }
            playSendSound()
        }

        cancelReply()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text.toString().trim()
        val id = chatId ?: return
        // Phase 5: groups don't have an `otherUserId`. Allow sends without it.
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)
        traceUi(stage = "send_tap", details = "chatId=$id otherUserId=$otherId textLen=${text.length}")
        StartupTrace.logStage("chat_send_tapped", "chatId=$id length=${text.length}")
        val activeSharedPreview = pendingSharedLinkPreview?.takeIf {
            LinkPreviewResolver.extractFirstUrl(text) == it.url
        }

        // Block guard: only stop sending when I initiated the block
        val blockStatus = _blockStatus.value
        if (blockStatus.iBlockedThem) {
            Toast.makeText(this, "Unblock this contact to send messages.", Toast.LENGTH_SHORT).show()
            return
        }

        val editing = editingMessage
        if (editing != null) {
            if (!isMessageEditable(editing)) {
                Toast.makeText(this, "Edit window expired", Toast.LENGTH_SHORT).show()
                cancelEditMode(restoreDraft = false)
                return
            }
            if (text.isBlank()) {
                Toast.makeText(this, "Message can't be empty", Toast.LENGTH_SHORT).show()
                return
            }

            if (text == editing.text) {
                cancelEditMode(restoreDraft = false)
                return
            }

            lifecycleScope.launch {
                repository.editMessage(
                    messageId = editing.id,
                    newText = text,
                    chatId = id,
                    otherUserId = otherId
                )
            }
            binding.etMessageInput.text.clear()
            cancelEditMode(restoreDraft = false)
            clearDraftForChat()
            return
        }

        if (text.isNotEmpty()) {
            // Deterministic: scroll only after the sent message actually appears in the list + layout is committed.
            // Sending a message always snaps to the new message, regardless of read-history position.
            userHasScrolledUp = false
            pendingScrollToBottomOnNextListCommit = true
            val replyMsg = replyToMessage // Capture current reply state
            lifecycleScope.launch {
                traceUi(stage = "send_launch", details = "chatId=$id reply=${replyMsg != null} preview=${activeSharedPreview != null}")
                val rText = if (replyMsg?.type == MessageType.CONTACT) {
                    "${replyMsg.contactName ?: "Contact"}: ${replyMsg.contactPhone ?: ""}"
                } else if (replyMsg?.type == MessageType.DOCUMENT) {
                    "📄 ${replyMsg.text.ifBlank { "Document" }}"
                } else {
                    replyMsg?.text
                }

                if (isGroupChat) {
                    // Phase 5: groups go through GroupChatRepository which writes to the same
                    // Firestore /chats/{chatId}/messages/{messageId} subcollection but skips
                    // the 1:1 pending_messages fan-out. Notifications for groups are handled
                    // separately (see Phase 6).
                    groupRepository.sendGroupTextMessage(
                        chatId = id,
                        text = text,
                        replyToMessageId = replyMsg?.id,
                        replyToText = rText,
                        replyToSenderId = replyMsg?.senderId,
                        replyToType = replyMsg?.type
                    )
                } else {
                    repository.sendMessage(
                        chatId = id,
                        text = text,
                        otherUserId = otherId,
                        otherUsername = otherUsername.ifEmpty { "Unknown" },
                        otherUserAvatar = otherUserAvatar,
                        previewThumbnailUrl = activeSharedPreview?.thumbnailUrl,
                        previewTitle = activeSharedPreview?.title,
                        previewDomain = activeSharedPreview?.domain,
                        replyToMessageId = replyMsg?.id,
                        replyToText = rText,
                        replyToSenderId = replyMsg?.senderId,
                        replyToType = replyMsg?.type
                    )
                }
                maybeTriggerRomanticMessageEffect(text)
                traceUi(stage = "send_returned", details = "chatId=$id otherUserId=$otherId")
                playSendSound()
            }
            binding.etMessageInput.text.clear()
            cancelReply()
            clearPendingSharedLinkPreview()
            clearDraftForChat()

            // Clear live expressive typing preview on message sent
            expressiveTypingManager?.onMessageSent()
        }
    }

    private fun onSwipeToReply(message: Message) {
        replyToMessage = message
        updateSharedLinkPreviewUi()
        
        // 1. Populate data immediately
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().uid
        binding.tvReplyUsername.text = if (message.senderId == currentUid) "You" else otherUsername
        
        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        binding.tvReplyUsername.setTextColor(typedValue.data)

        val isSharedLinkMessage = isSharedLinkMessage(message)
        
        // Show/hide thumbnail based on message type
        val showThumbnail = message.type == MessageType.IMAGE || 
                           message.type == MessageType.VIDEO || 
                           message.type == MessageType.MEDIA_GROUP ||
                           message.type == MessageType.STICKER ||
                           message.type == MessageType.KLIPY_EMOJI ||
                           message.type == MessageType.GIF ||
                           message.type == MessageType.MEME ||
                           message.type == MessageType.DOCUMENT ||
                   message.type == MessageType.CONTACT ||
                   isSharedLinkMessage
        
        if (showThumbnail) {
            binding.replyThumbnailContainer.visibility = View.VISIBLE
            
            if (message.type == MessageType.MEDIA_GROUP && message.mediaItemsList.size > 1) {
                // Show stacked collage thumbnails (up to 4)
                binding.ivReplyThumbnail.visibility = View.GONE
                
                val thumbnailViews = listOf(
                    binding.ivReplyThumbnail1,
                    binding.ivReplyThumbnail2,
                    binding.ivReplyThumbnail3,
                    binding.ivReplyThumbnail4
                )
                
                val itemsToShow = message.mediaItemsList.take(4)
                
                // Calculate container width: base thumbnail size + offset per additional image
                val containerWidth = 36 + (8 * (itemsToShow.size - 1))
                val layoutParams = binding.replyThumbnailContainer.layoutParams
                layoutParams.width = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    containerWidth.toFloat(),
                    resources.displayMetrics
                ).toInt()
                binding.replyThumbnailContainer.layoutParams = layoutParams
                
                itemsToShow.forEachIndexed { index, mediaItem ->
                    val thumbnailView = thumbnailViews[index]
                    thumbnailView.visibility = View.VISIBLE
                    
                    // Apply slight scale for depth effect
                    val scale = 1f - (index * 0.02f)
                    thumbnailView.scaleX = scale
                    thumbnailView.scaleY = scale
                    
                    val imageUrl = mediaItem.localUri ?: mediaItem.displayUrl
                    if (imageUrl.isNotEmpty()) {
                        com.bumptech.glide.Glide.with(this)
                            .load(imageUrl)
                            .centerCrop()
                            .into(thumbnailView)
                    }
                }
                
                // Hide unused thumbnail views
                for (i in itemsToShow.size until thumbnailViews.size) {
                    thumbnailViews[i].visibility = View.GONE
                }
            } else {
                // Show single thumbnail for IMAGE, VIDEO, or single-item MEDIA_GROUP
                binding.ivReplyThumbnail.visibility = View.VISIBLE
                binding.ivReplyThumbnail1.visibility = View.GONE
                binding.ivReplyThumbnail2.visibility = View.GONE
                binding.ivReplyThumbnail3.visibility = View.GONE
                binding.ivReplyThumbnail4.visibility = View.GONE
                
                // Reset container width to single thumbnail
                val layoutParams = binding.replyThumbnailContainer.layoutParams
                layoutParams.width = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    36f,
                    resources.displayMetrics
                ).toInt()
                binding.replyThumbnailContainer.layoutParams = layoutParams
                
                // Clear any leftover background color (used for contact initials)
                binding.ivReplyThumbnail.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // Helper: validate a local URI/path is a real, accessible file
                fun resolveLocalUri(localUri: String?): String? {
                    if (localUri.isNullOrEmpty()) return null
                    return try {
                        val uri = android.net.Uri.parse(localUri)
                        if (uri.scheme == "file" || localUri.startsWith("/")) {
                            val path = uri.path ?: localUri
                            if (java.io.File(path).exists()) localUri else null
                        } else if (uri.scheme == "content") {
                            // content:// URIs can't easily be validated without IO; trust them
                            localUri
                        } else null
                    } catch (e: Exception) { null }
                }

                val rawImageUrl = when (message.type) {
                    MessageType.IMAGE -> resolveLocalUri(message.localUri) ?: message.imageUrl
                    MessageType.VIDEO -> resolveLocalUri(message.localUri) ?: message.videoUrl
                    MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.GIF, MessageType.MEME ->
                        resolveLocalUri(message.localUri) ?: message.imageUrl ?: message.thumbnailUrl
                    MessageType.DOCUMENT -> message.thumbnailUrl
                    MessageType.MEDIA_GROUP -> {
                        val firstItem = message.mediaItemsList.firstOrNull()
                        firstItem?.localUri ?: firstItem?.displayUrl
                    }
                    MessageType.TEXT -> message.thumbnailUrl
                    else -> null
                }

                // For media types, prefer disk-cached thumbnail (same resolution chain as ChatAdapter)
                val imageUrl = when (message.type) {
                    MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.GIF, MessageType.MEME,
                    MessageType.IMAGE, MessageType.VIDEO ->
                        MessagePreviewCacheManager.resolveMediaPreviewModel(message, rawImageUrl)
                    else -> rawImageUrl
                }
                
                if (message.type == MessageType.CONTACT) {
                    val normalizedPhone = PhoneNumberUtil.normalizeToLast10Digits(message.contactPhone ?: "")
                    val registeredUser = chatAdapter.registeredUsersMap[normalizedPhone]
                    val isMe = (registeredUser?.id != null && registeredUser.id == currentUid) ||
                               (normalizedPhone.isNotEmpty() && normalizedPhone == (if(!currentUserPhone.isNullOrEmpty()) PhoneNumberUtil.normalizeToLast10Digits(currentUserPhone) else null))

                    val profileUrl = if (isMe) (currentUserAvatar.ifBlank { registeredUser?.profileImageUrl?.ifBlank { null } }) else registeredUser?.profileImageUrl
                    
                    if (!profileUrl.isNullOrBlank()) {
                        com.bumptech.glide.Glide.with(this)
                            .load(profileUrl)
                            .circleCrop()
                            .into(binding.ivReplyThumbnail)
                    } else {
                        val name = message.contactName ?: "Contact"
                        val avatarColor = ContactViewActivity.avatarColorForName(name)
                        binding.ivReplyThumbnail.setImageResource(R.drawable.ic_default_avatar)
                        binding.ivReplyThumbnail.setBackgroundColor(avatarColor)
                    }
                } else if (imageUrl != null && (imageUrl !is String || (imageUrl as String).isNotEmpty())) {
                    com.bumptech.glide.Glide.with(this)
                        .load(imageUrl)
                        .centerCrop()
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .dontAnimate()
                        .into(binding.ivReplyThumbnail)
                } else if (message.type == MessageType.DOCUMENT) {
                    // No thumbnail generated yet — show document icon as placeholder
                    com.bumptech.glide.Glide.with(this)
                        .load(R.drawable.ic_attachment_document)
                        .centerCrop()
                        .into(binding.ivReplyThumbnail)
                }
            }
        } else {
            binding.replyThumbnailContainer.visibility = View.GONE
        }
        
        binding.tvReplyText.text = when(message.type) {
            MessageType.TEXT -> {
                if (isSharedLinkMessage) buildSharedLinkReplySummary(message) else message.text
            }
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎤 Audio"
            MessageType.CONTACT -> "${message.contactName ?: "Contact"}: ${message.contactPhone ?: ""}"
            MessageType.MEDIA_GROUP -> "📷 ${message.mediaItemsList.size} items"
            MessageType.STICKER -> "Sticker"
            MessageType.KLIPY_EMOJI -> "Emoji"
            MessageType.GIF -> "GIF"
            MessageType.MEME -> "Meme"
            MessageType.DOCUMENT -> "📄 ${message.text?.takeIf { it.isNotBlank() } ?: "Document"}"
            else -> "Message"
        }
        
        // Ensure input focus
        binding.etMessageInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        // 2. Prepare for Animation (only if not already visible)
        val card = binding.replyPreviewCard
        
        // If already visible and expanded, no need to re-animate
        if (card.visibility == View.VISIBLE && card.height > 0 && card.alpha > 0.9f) {
            return
        }
        
        // Cancel any existing animator on the view
        card.animate().cancel()
        
        // Reset properties for the new constrained layout
        card.visibility = View.VISIBLE
        card.alpha = 0f
            
        // Measure target height
        val matchParentMeasureSpec = View.MeasureSpec.makeMeasureSpec((binding.root as View).width, View.MeasureSpec.EXACTLY)
        val wrapContentMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(matchParentMeasureSpec, wrapContentMeasureSpec)
        val targetHeight = card.measuredHeight
        
        // Ensure starting state
        card.layoutParams.height = 0
        card.requestLayout()
            
        // 3. Animate Height with Stronger Bounce
        // Using high overshoot tension for noticeable spring effect
        val heightAnimator = ValueAnimator.ofInt(0, targetHeight)
        heightAnimator.duration = 300 // Slightly longer for the bounce to settle
        heightAnimator.interpolator = OvershootInterpolator(1.5f) // Reduced tension for stability
            
        heightAnimator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            val params = card.layoutParams
            params.height = value
            card.layoutParams = params
            
            // Fade in
            val fraction = animation.animatedFraction
            card.alpha = min(1f, fraction * 2f)
        }
            
        heightAnimator.doOnEnd { 
            card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            card.alpha = 1f
        }
        heightAnimator.start()
        
        binding.btnCancelReply.setOnClickListener {
            cancelReply()
        }
    }

    private fun isSharedLinkMessage(message: Message): Boolean {
        if (message.type != MessageType.TEXT || message.isDeletedForAll) return false
        val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text)
        return previewUrl != null && (
            !message.thumbnailUrl.isNullOrBlank() ||
                !message.linkPreviewTitle.isNullOrBlank() ||
                !message.linkPreviewDomain.isNullOrBlank()
            )
    }

    private fun buildSharedLinkReplySummary(message: Message): String {
        val previewUrl = LinkPreviewResolver.extractFirstUrl(message.text) ?: return message.text
        val fallback = LinkPreviewResolver.fallback(previewUrl)
        val title = message.linkPreviewTitle?.takeIf { it.isNotBlank() } ?: fallback.title
        val domain = message.linkPreviewDomain?.takeIf { it.isNotBlank() } ?: fallback.domain
        return "$title\n$domain"
    }

    private fun scrollToMessage(messageId: String) {
        val position = chatAdapter.currentList.indexOfFirst {
            it is ChatListItem.MessageItem && it.message.id == messageId
        }

        if (position == -1) {
            Toast.makeText(this, "Original message not available", Toast.LENGTH_SHORT).show()
            return
        }

        val rv = binding.recyclerViewMessages
        val lm = rv.layoutManager as LinearLayoutManager

        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()

        // Already fully visible — highlight right away, no scroll needed
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION && position in first..last) {
            chatAdapter.highlightMessage(messageId)
            return
        }

        // Schedule a pending highlight on the adapter — it will fire automatically
        // in onViewAttachedToWindow once the target ViewHolder appears on-screen.
        chatAdapter.schedulePendingHighlight(messageId)

        if (kotlin.math.abs(position - (if (first != RecyclerView.NO_POSITION) first else 0)) > 50) {
            // Instant jump
            lm.scrollToPositionWithOffset(position, rv.height / 3)
        } else {
            // Smooth scroll
            val scroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            scroller.targetPosition = position
            lm.startSmoothScroll(scroller)
        }
    }

    private fun cancelReply() {
        replyToMessage = null
        binding.btnCancelReply.setOnClickListener(null)
        
        val card = binding.replyPreviewCard
        
        // 1. Capture current height
        val initialHeight = card.height
        
        // 2. Animate Height collapse with smooth accelerating dismiss
        val heightAnimator = ValueAnimator.ofInt(initialHeight, 0)
        heightAnimator.duration = 200
        heightAnimator.interpolator = AccelerateInterpolator(2f)
        
        heightAnimator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            val params = card.layoutParams
            params.height = value
            card.layoutParams = params
            
            // Fade alpha faster than height so view is transparent before it collapses fully
            card.alpha = (1f - animation.animatedFraction * 1.5f).coerceAtLeast(0f)
        }
        
        heightAnimator.doOnEnd { 
            card.visibility = View.GONE
            // Reset for next time
            card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            card.alpha = 1f
            resolveTypedLinkPreview()
        }
        heightAnimator.start()
    }

    private fun enterEditMode(message: Message) {
        if (!isMessageEditable(message)) {
            val userId = repository.currentUserId
            val isOwnMessage = userId != null && !message.isIncoming && message.senderId == userId
            val isExpired = isOwnMessage && (System.currentTimeMillis() - message.timestamp) > editWindowMs
            val reasonText = if (isExpired) {
                "Edit window expired"
            } else {
                "You can only edit your own text messages"
            }
            Toast.makeText(this, reasonText, Toast.LENGTH_SHORT).show()
            return
        }

        if (editingMessage?.id == message.id) return

        // Editing takes precedence over reply
        if (replyToMessage != null) {
            cancelReply()
        }

        draftTextBeforeEdit = binding.etMessageInput.text?.toString()
        editingMessage = message

        binding.etMessageInput.setText(message.text)
        binding.etMessageInput.setSelection(binding.etMessageInput.text?.length ?: 0)
        binding.etMessageInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        showEditPreview(message)
        updateSharedLinkPreviewUi()
        updateSendButtonState(binding.etMessageInput.text?.isNotEmpty() == true)
    }

    private fun cancelEditMode(restoreDraft: Boolean) {
        editingMessage = null
        hideEditPreview()

        if (restoreDraft) {
            val draft = draftTextBeforeEdit.orEmpty()
            binding.etMessageInput.setText(draft)
            binding.etMessageInput.setSelection(draft.length)
        }

        draftTextBeforeEdit = null
        resolveTypedLinkPreview()
        updateSendButtonState(binding.etMessageInput.text?.isNotEmpty() == true)
    }

    private fun showEditPreview(message: Message) {
        val card = binding.editPreviewCard
        binding.tvEditText.text = getEditPreviewText(message)
        binding.tvEditLabel.text = "Editing message"

        card.animate().cancel()
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideEditPreview() {
        val card = binding.editPreviewCard
        if (card.visibility != View.VISIBLE) return
        card.animate().cancel()
        card.animate().alpha(0f).setDuration(150).withEndAction {
            card.visibility = View.GONE
            card.alpha = 1f
        }.start()
    }

    private fun getEditPreviewText(message: Message): String {
        val text = message.text
        if (!text.isNullOrBlank()) return text
        return when (message.type) {
            MessageType.IMAGE -> "Photo"
            MessageType.VIDEO -> "Video"
            MessageType.AUDIO -> "Audio"
            MessageType.CONTACT -> "Contact"
            MessageType.MEDIA_GROUP -> "Media"
            MessageType.GIF -> "GIF"
            MessageType.STICKER -> "Sticker"
            MessageType.KLIPY_EMOJI -> "Emoji"
            MessageType.MEME -> "Meme"
            MessageType.TEXT -> "Message"
            MessageType.DOCUMENT -> message.text?.takeIf { it.isNotBlank() } ?: "Document"
            MessageType.STATUS_REPLY -> "Replied to status"
            MessageType.SYSTEM -> message.text.ifBlank { "System message" }
        }
    }

    private fun getSelectedMessages(selectedIds: Set<String>): List<Message> {
        if (selectedIds.isEmpty()) return emptyList()
        val idSet = selectedIds
        return chatAdapter.currentList.asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message }
            .filter { it.id in idSet }
            .toList()
    }

    private fun getSelectedMessage(selectedIds: Set<String>): Message? {
        if (selectedIds.size != 1) return null
        val id = selectedIds.first()
        return chatAdapter.currentList
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .firstOrNull { it.message.id == id }
            ?.message
    }

    /**
     * Shows (or replaces) the WhatsApp-style reaction picker over [message]. Any
     * previously-visible popup is dismissed first so we never stack multiple overlays.
     */
    private fun showReactionPopupFor(message: Message, anchor: View, touchYScreen: Float = -1f) {
        val cid = chatId ?: return
        val sameMessageAlreadyShowing =
            currentReactionPopupMessageId == message.id && currentReactionPopup != null
        // If we're already showing the popup for this exact message, leave it alone so
        // the entry animation isn't restarted on every selection notification.
        if (sameMessageAlreadyShowing) {
            val touchPositionAlreadyApplied =
                touchYScreen >= 0f &&
                    currentReactionPopupTouchYScreen >= 0f &&
                    kotlin.math.abs(currentReactionPopupTouchYScreen - touchYScreen) < 2f
            if (touchYScreen < 0f || touchPositionAlreadyApplied) return
        }
        // Replace any existing popup without animating the close so the swap looks
        // instantaneous when the anchor changes (e.g. user deselects → reselects).
        currentReactionPopup?.dismiss(animate = false)
        currentReactionPopup = null
        currentReactionPopupMessageId = null
        currentReactionPopupTouchYScreen = -1f

        val popup = com.glyph.glyph_v3.ui.chat.reactions.ReactionPopupOverlay.show(
            activity = this@ChatActivity,
            anchor = anchor,
            isOutgoing = !message.isIncoming,
            ownReaction = message.reactions[chatAdapter.currentUserId],
            onEmojiSelected = { emoji ->
                selectionManager.clearSelection()
                lifecycleScope.launch {
                    repository.toggleReaction(cid, message.id, emoji)
                }
            },
            onMoreRequested = {
                com.glyph.glyph_v3.ui.chat.reactions.ReactionEmojiPickerSheet.show(
                    supportFragmentManager
                ) { picked ->
                    selectionManager.clearSelection()
                    lifecycleScope.launch {
                        repository.toggleReaction(cid, message.id, picked)
                    }
                }
            },
            onDismiss = {
                if (currentReactionPopupMessageId == message.id) {
                    currentReactionPopup = null
                    currentReactionPopupMessageId = null
                    currentReactionPopupTouchYScreen = -1f
                }
            },
            touchYScreen = touchYScreen
        )
        currentReactionPopup = popup
        currentReactionPopupMessageId = message.id
        currentReactionPopupTouchYScreen = touchYScreen
    }

    /** Dismisses the active reaction popup, if any. */
    private fun dismissReactionPopup(animate: Boolean = true) {
        currentReactionPopup?.dismiss(animate = animate)
        currentReactionPopup = null
        currentReactionPopupMessageId = null
        currentReactionPopupTouchYScreen = -1f
    }

    /**
     * Keeps the reaction popup in sync with the selection set: visible only when
     * exactly one message is selected, dismissed otherwise. Handles the deselect-back-
     * to-one case by re-anchoring to whichever message remains selected.
     */
    private fun reconcileReactionPopup(selectedIds: Set<String>) {
        if (selectedIds.size != 1) {
            dismissReactionPopup(animate = true)
            return
        }
        val id = selectedIds.first()
        if (currentReactionPopupMessageId == id) return
        val message = getSelectedMessage(selectedIds) ?: run {
            dismissReactionPopup(animate = true)
            return
        }
        val anchor = chatAdapter.findAnchorForMessage(id, binding.recyclerViewMessages)
        if (anchor == null) {
            // Anchor row isn't currently attached (e.g. selection survived a list rebind)
            // — drop the popup rather than show it without a sensible position.
            dismissReactionPopup(animate = true)
            return
        }
        showReactionPopupFor(message, anchor, touchYScreen = -1f)
    }

    private fun isMessageEditable(message: Message): Boolean {
        val userId = repository.currentUserId ?: return false
        if (message.isIncoming || message.isDeletedForAll || message.senderId != userId) return false
        if (message.type != MessageType.TEXT) return false
        return (System.currentTimeMillis() - message.timestamp) <= editWindowMs
    }

    private fun isEligibleForDeleteForAll(message: Message): Boolean {
        val userId = repository.currentUserId ?: return false
        val now = System.currentTimeMillis()
        return !message.isIncoming &&
            !message.isDeletedForAll &&
            message.senderId == userId &&
            (now - message.timestamp) <= deleteForAllWindowMs
    }

    private fun sendImage(uri: Uri, caption: String = "") {
        // Show compression bottom sheet for single image
        showCompressionBottomSheet(listOf(uri to "image/*"), caption)
    }

    private fun sendVideo(uri: Uri, caption: String = "") {
        // Automatically compress videos at MEDIUM quality (WhatsApp-style — no dialog needed).
        sendMediaWithCompression(listOf(uri to "video/*"), CompressionQuality.MEDIUM, emptyMap(), caption)
    }
    
    /**
     * Internal method to send an image directly (after compression).
     */
    private fun sendImageInternal(uri: Uri, caption: String = "") {
        if (isBlockedForSending()) return
        val id = chatId ?: return
        val replyMsg = replyToMessage

        if (isGroupChat) {
            lifecycleScope.launch {
                groupRepository.sendGroupImageMessage(
                    chatId = id,
                    imageUri = uri,
                    caption = caption,
                    replyToMessageId = replyMsg?.id,
                    replyToText = replyMsg?.text,
                    replyToSenderId = replyMsg?.senderId,
                    replyToType = replyMsg?.type
                )
            }
            cancelReply()
            return
        }

        val otherId = otherUserId ?: return
        lifecycleScope.launch {
            repository.sendImageMessage(
                chatId = id,
                imageUri = uri,
                otherUserId = otherId,
                otherUsername = otherUsername.ifEmpty { "Unknown" },
                otherUserAvatar = otherUserAvatar,
                caption = caption,
                replyToMessageId = replyMsg?.id,
                replyToText = replyMsg?.text,
                replyToSenderId = replyMsg?.senderId,
                replyToType = replyMsg?.type
            )
        }
        cancelReply()
    }
    
    /**
     * Internal method to send a video directly (after compression).
     */
    private fun sendVideoInternal(uri: Uri, caption: String = "") {
        if (isBlockedForSending()) return
        val id = chatId ?: return
        val replyMsg = replyToMessage

        if (isGroupChat) {
            lifecycleScope.launch {
                groupRepository.sendGroupVideoMessage(
                    chatId = id,
                    videoUri = uri,
                    caption = caption,
                    isVideoNote = false,
                    replyToMessageId = replyMsg?.id,
                    replyToText = replyMsg?.text,
                    replyToSenderId = replyMsg?.senderId,
                    replyToType = replyMsg?.type
                )
            }
            cancelReply()
            return
        }

        val otherId = otherUserId ?: return
        lifecycleScope.launch {
            repository.sendVideoMessage(
                chatId = id,
                videoUri = uri,
                otherUserId = otherId,
                otherUsername = otherUsername.ifEmpty { "Unknown" },
                otherUserAvatar = otherUserAvatar,
                caption = caption,
                replyToMessageId = replyMsg?.id,
                replyToText = replyMsg?.text,
                replyToSenderId = replyMsg?.senderId,
                replyToType = replyMsg?.type
            )
        }
        cancelReply()
    }

    // Labels that are auto-generated placeholders, not real user captions.
    private val retryMediaCaptionExclusions = setOf(
        "Photo", "Video", "GIF", "Sticker", "Meme", "photo", "video", "gif", "sticker", "meme"
    )

    /**
     * Retry a failed media upload. Re-uses the message's localUri so we don't need
     * to re-pick the file. Works for both 1:1 and group chats.
     */
    private fun retryMediaUpload(message: com.glyph.glyph_v3.data.models.Message) {
        val localUriStr = message.localUri ?: return
        val uri = android.net.Uri.parse(localUriStr)
        val id = chatId ?: return

        // Reset status to SENDING so the progress indicator shows immediately
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dao = (application as com.glyph.glyph_v3.GlyphApplication)
                .getOrCreateAppDatabase().messageDao()
            dao.updateMessageStatus(message.id, com.glyph.glyph_v3.data.models.MessageStatus.SENDING)
        }

        val caption = message.text?.takeIf { it !in retryMediaCaptionExclusions } ?: ""
        when (message.type) {
            com.glyph.glyph_v3.data.models.MessageType.VIDEO -> {
                if (isGroupChat) {
                    lifecycleScope.launch {
                        groupRepository.sendGroupVideoMessage(
                            chatId = id,
                            videoUri = uri,
                            caption = caption,
                            isVideoNote = message.isVideoNote
                        )
                    }
                } else {
                    val otherId = otherUserId ?: return
                    lifecycleScope.launch {
                        repository.sendVideoMessage(
                            chatId = id,
                            videoUri = uri,
                            otherUserId = otherId,
                            otherUsername = otherUsername.ifEmpty { "Unknown" },
                            otherUserAvatar = otherUserAvatar,
                            caption = caption
                        )
                    }
                }
            }
            com.glyph.glyph_v3.data.models.MessageType.IMAGE -> {
                sendImageInternal(uri, caption)
            }
            else -> { /* Other types not retryable via this path */ }
        }
    }

    /**
     * On activity resume, find any VIDEO/IMAGE messages that were left in SENDING state
     * (process was killed mid-upload) and automatically re-queue them so the user sees
     * uploads resume without having to tap retry.
     */
    private fun resumeStalledUploads() {
        val cId = chatId ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dao = (application as com.glyph.glyph_v3.GlyphApplication)
                .getOrCreateAppDatabase().messageDao()
            val stalled = dao.getOutgoingMessagesByStatuses(
                listOf(com.glyph.glyph_v3.data.models.MessageStatus.SENDING), 50
            ).filter { msg ->
                msg.chatId == cId
                    && !msg.localUri.isNullOrEmpty()
                    && (msg.type == com.glyph.glyph_v3.data.models.MessageType.VIDEO
                        || msg.type == com.glyph.glyph_v3.data.models.MessageType.IMAGE)
                    && !com.glyph.glyph_v3.data.repo.MediaProgressManager.isActive(msg.id)
            }
            if (stalled.isEmpty()) return@launch
            // Mark them FAILED so the UI shows the retry button, then immediately retry
            stalled.forEach { msg ->
                dao.updateMessageStatus(msg.id, com.glyph.glyph_v3.data.models.MessageStatus.FAILED)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                stalled.forEach { msg ->
                    retryMediaUpload(
                        com.glyph.glyph_v3.data.models.Message(
                            id = msg.id,
                            chatId = msg.chatId,
                            text = msg.text,
                            senderId = msg.senderId,
                            timestamp = msg.timestamp,
                            status = com.glyph.glyph_v3.data.models.MessageStatus.FAILED,
                            isIncoming = false,
                            type = msg.type,
                            localUri = msg.localUri,
                            isVideoNote = msg.isVideoNote
                        )
                    )
                }
            }
        }
    }

    /**
     * Send a video note directly without compression.
     * CameraX 720p output is already suitable for a 240dp circular display.
     */
    private fun sendVideoNoteDirectly(uri: Uri) {
        if (isBlockedForSending()) return
        val id = chatId ?: return

        if (isGroupChat) {
            lifecycleScope.launch {
                groupRepository.sendGroupVideoMessage(
                    chatId = id,
                    videoUri = uri,
                    caption = "",
                    isVideoNote = true
                )
            }
            return
        }

        val otherId = otherUserId ?: return
        lifecycleScope.launch {
            repository.sendVideoMessage(
                chatId = id,
                videoUri = uri,
                otherUserId = otherId,
                otherUsername = otherUsername.ifEmpty { "Unknown" },
                otherUserAvatar = otherUserAvatar,
                caption = "",
                isVideoNote = true
            )
        }
    }

    private fun handleMediaUri(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "image/*"
        if (mimeType.startsWith("video")) {
            // Auto-compress videos at MEDIUM quality — no dialog needed.
            sendMediaWithCompression(listOf(uri to mimeType), CompressionQuality.MEDIUM, emptyMap())
        } else {
            showCompressionBottomSheet(listOf(uri to mimeType))
        }
    }

    /**
     * Called once after the activity is fully laid out to handle content that was forwarded
     * from the system share sheet via ShareTargetActivity.
     *
     * - text/plain  -- pre-fills the message input so the user can review before sending.
     * - image/video -- shows the compression quality sheet (same as picking from gallery).
     * - document    -- opens DocumentCaptionActivity so the user can add a caption.
     *
     * The method is idempotent: the extras are consumed on first call so a configuration
     * change (rotation) that calls it again is harmless.
     */
    private fun handlePendingSharedContent() {
        val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT)?.takeUnless { it.isBlank() }
        val sharedUriStrings = intent.getStringArrayListExtra(EXTRA_SHARED_URIS)
        val sharedMimeType = intent.getStringExtra(EXTRA_SHARED_MIME_TYPE) ?: "*/*"
        val sharedPreviewTitle = intent.getStringExtra(EXTRA_SHARED_LINK_PREVIEW_TITLE)
        val sharedPreviewDomain = intent.getStringExtra(EXTRA_SHARED_LINK_PREVIEW_DOMAIN)
        val sharedPreviewThumbnailUrl = intent.getStringExtra(EXTRA_SHARED_LINK_PREVIEW_THUMBNAIL_URL)

        // Remove so rotate/re-deliver doesn't trigger twice
        intent.removeExtra(EXTRA_SHARED_TEXT)
        intent.removeExtra(EXTRA_SHARED_URIS)
        intent.removeExtra(EXTRA_SHARED_MIME_TYPE)
        intent.removeExtra(EXTRA_SHARED_LINK_PREVIEW_TITLE)
        intent.removeExtra(EXTRA_SHARED_LINK_PREVIEW_DOMAIN)
        intent.removeExtra(EXTRA_SHARED_LINK_PREVIEW_THUMBNAIL_URL)

        if (!sharedUriStrings.isNullOrEmpty()) {
            val uris = sharedUriStrings.mapNotNull { uriStr ->
                try { Uri.parse(uriStr) } catch (_: Exception) { null }
            }
            if (uris.isEmpty()) return

            val isMediaMime = sharedMimeType.startsWith("image/") || sharedMimeType.startsWith("video/")

            if (isMediaMime) {
                // Route through the standard media compression flow
                handleMultipleMediaUris(uris, sharedText.orEmpty())
            } else {
                enqueueSharedDocuments(uris, sharedText.orEmpty())
            }
            return
        }

        if (!sharedText.isNullOrEmpty()) {
            // Pre-fill the compose input; user can edit then tap Send.
            binding.etMessageInput.setText(sharedText)
            binding.etMessageInput.setSelection(sharedText.length)
            binding.etMessageInput.requestFocus()

            val sharedUrl = LinkPreviewResolver.extractFirstUrl(sharedText)
            pendingSharedLinkPreview = sharedUrl?.let { url ->
                LinkPreviewData(
                    url = url,
                    title = sharedPreviewTitle?.takeIf { it.isNotBlank() }
                        ?: LinkPreviewResolver.fallback(url).title,
                    domain = sharedPreviewDomain?.takeIf { it.isNotBlank() }
                        ?: LinkPreviewResolver.fallback(url).domain,
                    thumbnailUrl = sharedPreviewThumbnailUrl?.takeIf { it.isNotBlank() }
                )
            }
            updateSharedLinkPreviewUi()
        }
    }

    private fun clearPendingSharedLinkPreview() {
        pendingSharedLinkPreview = null
        linkPreviewResolveJob?.cancel()
        linkPreviewResolveJob = null
        updateSharedLinkPreviewUi()
    }
    
    private fun resolveTypedLinkPreview() {
        // Don't resolve if in reply or edit mode
        if (replyToMessage != null || editingMessage != null) {
            return
        }
        
        val currentText = binding.etMessageInput.text?.toString().orEmpty()
        val currentUrl = LinkPreviewResolver.extractFirstUrl(currentText)
        
        // Clear preview if no URL in text
        if (currentUrl == null) {
            if (pendingSharedLinkPreview != null) {
                clearPendingSharedLinkPreview()
            }
            return
        }
        
        // Already have preview for this URL
        if (pendingSharedLinkPreview?.url == currentUrl) {
            return
        }
        
        // Cancel any pending resolution
        linkPreviewResolveJob?.cancel()
        
        // Start resolving the new URL
        linkPreviewResolveJob = lifecycleScope.launch {
            // Debounce: wait a bit to ensure user finished typing
            delay(800)
            
            try {
                val resolved = LinkPreviewResolver.resolve(currentUrl)
                
                // Double-check the URL still matches (user might have changed it)
                val latestText = binding.etMessageInput.text?.toString().orEmpty()
                val latestUrl = LinkPreviewResolver.extractFirstUrl(latestText)
                
                if (latestUrl == currentUrl) {
                    pendingSharedLinkPreview = resolved
                    updateSharedLinkPreviewUi()
                }
            } catch (e: Exception) {
                // Failed to resolve, use fallback
                val latestText = binding.etMessageInput.text?.toString().orEmpty()
                val latestUrl = LinkPreviewResolver.extractFirstUrl(latestText)
                
                if (latestUrl == currentUrl) {
                    pendingSharedLinkPreview = LinkPreviewResolver.fallback(currentUrl)
                    updateSharedLinkPreviewUi()
                }
            }
        }
    }

    private fun updateSharedLinkPreviewUi() {
        if (isDestroyed || isFinishing) return

        val preview = pendingSharedLinkPreview
        val currentText = binding.etMessageInput.text?.toString().orEmpty()
        val currentUrl = LinkPreviewResolver.extractFirstUrl(currentText)
        val shouldShow = preview != null && currentUrl == preview.url && replyToMessage == null && editingMessage == null

        if (!shouldShow) {
            binding.shareLinkPreviewCard.visibility = View.GONE
            Glide.with(this).clear(binding.ivShareLinkPreviewThumbnail)
            binding.ivShareLinkPreviewThumbnail.visibility = View.GONE
            return
        }

        binding.shareLinkPreviewCard.visibility = View.VISIBLE
        binding.tvShareLinkPreviewTitle.text = preview.title
        binding.tvShareLinkPreviewDomain.text = preview.domain
        binding.tvShareLinkPreviewUrl.visibility = View.GONE

        val thumbnailUrl = preview.thumbnailUrl
        val thumbnailModel = MessagePreviewCacheManager.resolveLinkPreviewModel(preview.url, thumbnailUrl)
        if (thumbnailModel == null) {
            Glide.with(this).clear(binding.ivShareLinkPreviewThumbnail)
            binding.ivShareLinkPreviewThumbnail.visibility = View.GONE
        } else {
            binding.ivShareLinkPreviewThumbnail.visibility = View.VISIBLE
            MessagePreviewCacheManager.warmLinkPreviewAsync(applicationContext, preview.url, thumbnailUrl)
            Glide.with(this)
                .load(thumbnailModel)
                .dontAnimate()
                .into(binding.ivShareLinkPreviewThumbnail)
        }
    }

    private fun handleMultipleMediaUris(uris: List<Uri>, caption: String = "") {
        if (uris.isEmpty()) return
        
        // Build list of URIs with their mime types
        val mediaWithTypes = uris.map { uri ->
            val mimeType = contentResolver.getType(uri) ?: run {
                // Try to determine type from URI
                val uriString = uri.toString().lowercase()
                when {
                    uriString.contains("image") || uriString.endsWith(".jpg") || 
                    uriString.endsWith(".jpeg") || uriString.endsWith(".png") -> "image/*"
                    uriString.contains("video") || uriString.endsWith(".mp4") || 
                    uriString.endsWith(".mov") -> "video/*"
                    else -> "image/*"
                }
            }
            uri to mimeType
        }
        
        // If all items are videos, skip the dialog and auto-compress at MEDIUM quality.
        val allVideos = mediaWithTypes.all { (_, mimeType) -> mimeType.startsWith("video") }
        if (allVideos) {
            sendMediaWithCompression(mediaWithTypes, CompressionQuality.MEDIUM, emptyMap(), caption)
        } else {
            showCompressionBottomSheet(mediaWithTypes, caption)
        }
    }

    private fun enqueueSharedDocuments(uris: List<Uri>, initialCaption: String) {
        pendingSharedDocuments.clear()
        uris.forEachIndexed { index, uri ->
            pendingSharedDocuments.addLast(
                SharedDocumentPayload(
                    uri = uri,
                    fileName = resolveSharedDocumentName(uri),
                    initialCaption = if (index == 0) initialCaption else ""
                )
            )
        }
        launchNextPendingSharedDocument()
    }

    private fun launchNextPendingSharedDocument() {
        val nextDocument = pendingSharedDocuments.removeFirstOrNull() ?: return
        val docIntent = Intent(this, DocumentCaptionActivity::class.java).apply {
            putExtra(DocumentCaptionActivity.EXTRA_DOCUMENT_URI, nextDocument.uri.toString())
            putExtra(DocumentCaptionActivity.EXTRA_FILENAME, nextDocument.fileName)
            putExtra(DocumentCaptionActivity.EXTRA_OTHER_USERNAME, otherUsername.ifEmpty { "User" })
            putExtra(DocumentCaptionActivity.EXTRA_INITIAL_CAPTION, nextDocument.initialCaption)
        }
        documentCaptionLauncher.launch(docIntent)
    }

    private fun resolveSharedDocumentName(uri: Uri): String {
        var fileName = "Document"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    fileName = cursor.getString(nameIdx) ?: fileName
                }
            }
        } catch (_: Exception) {}
        return fileName
    }
    
    /**
     * Show the compression quality selection bottom sheet before uploading media.
     */
    /**
     * Returns true if sending is blocked by the local user. Shows a toast only in that case.
     * Blocked recipients should not see UI hints when they have been blocked by others.
     */
    private fun isBlockedForSending(): Boolean {
        val blockStatus = _blockStatus.value
        if (blockStatus.iBlockedThem) {
            Toast.makeText(this, "Unblock this contact to send messages.", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private enum class RealtimeBlockFeature {
        CALL,
        TYPING,
        LIVE_LOCATION,
        LIVE_CAMERA
    }

    private fun isRealtimeInteractionBlocked(
        feature: RealtimeBlockFeature,
        showFeedback: Boolean = false
    ): Boolean {
        val status = _blockStatus.value
        if (!status.isBlocked) {
            return false
        }
        if (!showFeedback) {
            return true
        }

        when (feature) {
            RealtimeBlockFeature.LIVE_LOCATION,
            RealtimeBlockFeature.LIVE_CAMERA -> showLiveSharingBlockedAlert()
            RealtimeBlockFeature.CALL -> {
                Toast.makeText(
                    this,
                    "Voice and video calls are unavailable with ${otherUsername.ifEmpty { "this contact" }}.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            RealtimeBlockFeature.TYPING -> Unit
        }
        return true
    }

    private fun showLiveSharingBlockedAlert() {
        val displayName = otherUsername.ifEmpty { "this contact" }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage("You cannot share your live location or live camera feed with $displayName.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun handleRealtimeBlockStateChanged(status: com.glyph.glyph_v3.data.repo.BlockStatus) {
        if (!status.isBlocked) {
            return
        }

        val currentChatId = chatId
        val currentOtherUserId = otherUserId
        if (currentChatId != null && currentOtherUserId != null) {
            PresenceManager.setTypingStatus(currentChatId, currentOtherUserId, false)
        }

        expressiveTypingManager?.onMessageSent()
        mapVideoSessionManager?.setEnabled(false)

        if (isMyAutoCameraShareActive()) {
            currentOtherUserId?.let {
                com.glyph.glyph_v3.data.service.MapCameraShareForegroundService.stop(this, it)
            }
        }

        if (mapBackgroundManager?.isLiveSharing == true) {
            mapBackgroundManager?.stopLiveSharing()
            updateLiveBannerVisibility()
        }
    }

    private fun showCompressionBottomSheet(mediaItems: List<Pair<Uri, String>>, caption: String = "") {
        if (isBlockedForSending()) return
        // Initialize the ViewModel with media items
        compressionViewModel.initializeWithMedia(mediaItems)
        
        val bottomSheet = MediaCompressionBottomSheetV2.newInstance().apply {
            onConfirm = { quality, overrides ->
                sendMediaWithCompression(mediaItems, quality, overrides, caption)
            }
            onCancel = {
                // User cancelled, do nothing
            }
        }
        bottomSheet.show(supportFragmentManager, MediaCompressionBottomSheetV2.TAG)
    }
    
    /**
     * Send media with the selected compression quality.
     */
    private fun sendMediaWithCompression(
        mediaItems: List<Pair<Uri, String>>, 
        quality: CompressionQuality,
        overrides: Map<Uri, CompressionQuality> = emptyMap(),
        caption: String = ""
    ) {
        if (isBlockedForSending()) return
        val id = chatId ?: return
        // Phase 11: groups now also support MEDIA_GROUP collage. Single-item path
        // still routes to sendImageInternal/sendVideoInternal which are
        // group-aware. 1:1 multi-item still uses RealtimeMessageRepository's
        // sendGroupedMediaMessage.
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)
        
        lifecycleScope.launch {
            // Compress media if needed (off main thread)
            val compressedItems = if (quality == CompressionQuality.ORIGINAL && overrides.isEmpty()) {
                mediaItems
            } else {
                val cacheDir = MediaCompressor.getCompressionCacheDir(this@ChatActivity)
                mediaItems.map { (uri, mimeType) ->
                    // Use per-item override if available, otherwise use global quality
                    val itemQuality = overrides[uri] ?: quality
                    
                    // Skip compression for ORIGINAL quality
                    if (itemQuality == CompressionQuality.ORIGINAL) {
                        return@map uri to mimeType
                    }
                    
                    // Extract metadata for compression
                    val metadata = withContext(Dispatchers.IO) {
                        com.glyph.glyph_v3.util.MediaEstimationUtil.extractMediaMetadata(
                            this@ChatActivity, uri, mimeType
                        )
                    }
                    
                    // Compress the media
                    val compressedUri = MediaCompressor.compress(
                        context = this@ChatActivity,
                        item = metadata,
                        quality = itemQuality,
                        outputDir = cacheDir
                    ) ?: uri
                    
                    compressedUri to mimeType
                }
            }
            
            // Send the media
            if (compressedItems.size == 1) {
                // Single media — already group-aware via sendImageInternal / sendVideoInternal.
                val (uri, mimeType) = compressedItems.first()
                if (mimeType.startsWith("video/")) {
                    sendVideoInternal(uri, caption)
                } else {
                    sendImageInternal(uri, caption)
                }
            } else if (isGroupChat) {
                // Group multi-item: send as a single MEDIA_GROUP collage so the
                // receivers render it in one bubble (Phase 11). Caption rides on
                // the message text just like 1:1.
                val mediaUris = compressedItems.map { it.first }
                groupRepository.sendGroupedMediaMessage(
                    chatId = id,
                    uris = mediaUris,
                    quality = quality,
                    overrides = overrides,
                    caption = caption
                )
            } else {
                // 1:1 multi-media (2-4 items): send as MEDIA_GROUP collage.
                val mediaUris = compressedItems.map { it.first }
                
                repository.sendGroupedMediaMessage(
                    chatId = id,
                    uris = mediaUris,
                    otherUserId = otherId,
                    otherUsername = otherUsername.ifEmpty { "Unknown" },
                    otherUserAvatar = otherUserAvatar,
                    quality = quality,
                    overrides = overrides
                )
            }
        }
    }

    private fun showCameraOptions() {
        // Legacy fallback — now redirects to in-app camera
        launchInAppCamera()
    }

    /** Launch the in-app WhatsApp-style camera experience. */
    private fun launchInAppCamera() {
        val intent = com.glyph.glyph_v3.ui.camera.CameraActivity.newIntent(
            context = this,
            chatId = chatId ?: "",
            otherUserId = otherUserId ?: "",
            otherUsername = otherUsername,
            otherUserAvatar = otherUserAvatar
        )
        inAppCameraLauncher.launch(intent)
    }

    private fun launchCamera() {
        val photoFile = File.createTempFile(
            "photo_${System.currentTimeMillis()}",
            ".jpg",
            cacheDir
        )
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(cameraPhotoUri)
    }

    private fun launchVideoRecorder() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        cameraVideoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        cameraVideoUri?.let { takeVideoLauncher.launch(it) }
    }

    private fun handleContactUri(contactUri: Uri) {
        var contactName = ""
        var contactPhone = ""

        // Get contact name
        val nameCursor: Cursor? = contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts._ID),
            null,
            null,
            null
        )
        nameCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                contactName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))

                // Get phone number
                val phoneCursor: Cursor? = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                phoneCursor?.use { pCursor ->
                    if (pCursor.moveToFirst()) {
                        contactPhone = pCursor.getString(pCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    }
                }
            }
        }

        if (contactName.isNotEmpty()) {
            sendContactCard(contactName, contactPhone)
        } else {
            Toast.makeText(this, "Could not read contact info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendContactCard(contactName: String, contactPhone: String) {
        if (isBlockedForSending()) return
        val id = chatId ?: return
        // Phase 8: branch on group; 1:1 still requires otherUserId.
        val otherId = if (isGroupChat) "" else (otherUserId ?: return)

        lifecycleScope.launch {
            if (isGroupChat) {
                groupRepository.sendGroupContactMessage(
                    chatId = id,
                    contactName = contactName,
                    contactPhone = contactPhone
                )
            } else {
                repository.sendContactMessage(
                    chatId = id,
                    contactName = contactName,
                    contactPhone = contactPhone,
                    otherUserId = otherId,
                    otherUsername = otherUsername.ifEmpty { "Unknown" },
                    otherUserAvatar = otherUserAvatar
                )
            }
        }
    }

    fun openMediaViewer(imageUrl: String, timestamp: Long) {
        startActivity(MediaViewerActivity.newIntent(this, imageUrl, timestamp))
    }
    
    fun openMultiMediaViewer(mediaItems: List<com.glyph.glyph_v3.data.models.MediaItem>, startIndex: Int = 0, timestamp: Long = 0) {
        startActivity(MediaViewerActivity.newIntentWithMultipleMedia(this, mediaItems, startIndex, timestamp))
    }

    fun openVideoPlayer(videoUrl: String) {
        startActivity(VideoPlayerActivity.newIntent(this, videoUrl, "Video"))
    }

    private fun restoreDraftForChat() {
        val id = chatId ?: return
        val draft = DraftMessageStore.getDraft(id)
        lastSavedDraft = draft

        if (draft.isEmpty()) {
            updateSendButtonState(false)
            messageTextState.value = ""
            return
        }

        binding.etMessageInput.setText(draft)
        binding.etMessageInput.setSelection(draft.length)
        messageTextState.value = draft
        updateSendButtonState(true)
        // Ensure emoji icon is positioned to hide buzz button (no animation on restore)
        updateAccessoryIcons(isTyping = true, animate = false)
    }

    private fun updateRegisteredUsersCache() {
        val updated = linkedMapOf<String, com.glyph.glyph_v3.data.models.User>()

        val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val myPhone = currentUserPhone.ifBlank { authUser?.phoneNumber.orEmpty() }
        val myId = authUser?.uid.orEmpty()
        val myAvatar = currentUserAvatar.ifBlank { authUser?.photoUrl?.toString().orEmpty() }
        val normalizedMyPhone = PhoneNumberUtil.normalizeToLast10Digits(myPhone)
        if (normalizedMyPhone.isNotEmpty() && myId.isNotEmpty()) {
            updated[normalizedMyPhone] = com.glyph.glyph_v3.data.models.User(
                id = myId,
                phoneNumber = myPhone,
                username = authUser?.displayName.orEmpty(),
                profileImageUrl = myAvatar,
                profileImageFullUrl = myAvatar
            )
        }

        val normalizedOtherPhone = PhoneNumberUtil.normalizeToLast10Digits(otherUserPhone)
        if (normalizedOtherPhone.isNotEmpty() && !otherUserId.isNullOrBlank()) {
            updated[normalizedOtherPhone] = com.glyph.glyph_v3.data.models.User(
                id = otherUserId.orEmpty(),
                phoneNumber = otherUserPhone,
                username = otherUsername,
                profileImageUrl = otherUserAvatar,
                profileImageFullUrl = otherUserAvatar
            )
        }

        if (registeredUsersCache == updated) return
        registeredUsersCache.clear()
        registeredUsersCache.putAll(updated)

        if (::chatAdapter.isInitialized) {
            chatAdapter.registeredUsersMap = registeredUsersCache.toMap()
        }

    }

    private fun getVisibleBufferedMessages(messages: List<Message> = currentMessages): List<Message> {
        if (messages.isEmpty()) return emptyList()

        val lm = if (::chatAdapter.isInitialized) {
            binding.recyclerViewMessages.layoutManager as? LinearLayoutManager
        } else {
            null
        }
        val rvLaidOut = ::binding.isInitialized && binding.recyclerViewMessages.isLaidOut
        if (lm == null || !rvLaidOut) {
            return messages.takeLast(INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS)
        }

        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) {
            return messages.takeLast(INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS)
        }

        val currentList = chatAdapter.currentList
        if (currentList.isEmpty()) {
            return messages.takeLast(INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS)
        }

        val start = (firstVisible - VISIBLE_PREVIEW_BUFFER_ITEMS).coerceAtLeast(0)
        val endExclusive = (lastVisible + VISIBLE_PREVIEW_BUFFER_ITEMS + 1).coerceAtMost(currentList.size)
        if (start >= endExclusive) {
            return messages.takeLast(INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS)
        }

        val visibleMessages = currentList.subList(start, endExclusive)
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message }
            .distinctBy { it.id }
            .toList()

        return if (visibleMessages.isNotEmpty()) {
            visibleMessages
        } else {
            messages.takeLast(INITIAL_VISIBLE_WARMUP_COUNT + VISIBLE_PREVIEW_BUFFER_ITEMS)
        }
    }

    private fun prefetchViewportMediaIfNeeded(messages: List<Message> = currentMessages, reason: String) {
        if (!::chatAdapter.isInitialized || messages.isEmpty() || chatAdapter.isScrolling) return
        if (reason != "scroll_idle") {
            return
        }

        val candidates = getVisibleBufferedMessages(messages)
            .filter { it.isIncoming }
            .filter {
                it.type == MessageType.IMAGE ||
                    it.type == MessageType.VIDEO ||
                    it.type == MessageType.AUDIO ||
                    it.type == MessageType.MEDIA_GROUP
            }
            .take(INITIAL_VISIBLE_WARMUP_COUNT + MEDIA_PREFETCH_BUFFER_ITEMS)

        if (candidates.isEmpty()) return

        scheduleIdleTask(
            key = "viewport_media_prefetch",
            priority = IdleTaskPriority.MEDIUM,
            delayMs = 0L,
            reason = reason
        ) {
            if (chatAdapter.isScrolling) return@scheduleIdleTask
            withContext(Dispatchers.IO) {
                var queuedDownloads = 0

                for (message in candidates) {
                    if (isReadyForPlayback(message)) continue
                    if (MediaProgressManager.getProgress(message.id) != null) continue

                    when (message.type) {
                        MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO -> {
                            val remoteUrl = when (message.type) {
                                MessageType.IMAGE -> message.imageUrl
                                MessageType.VIDEO -> message.videoUrl
                                MessageType.AUDIO -> message.audioUrl
                                else -> null
                            } ?: continue

                            mediaTransferManager.startDownload(
                                messageId = message.id,
                                chatId = message.chatId,
                                mediaType = message.type,
                                remoteUrl = remoteUrl,
                                expectedSize = message.fileSize
                            )
                            queuedDownloads += 1
                        }

                        MessageType.MEDIA_GROUP -> {
                            val hasRemoteItems = message.mediaItemsList.any { item ->
                                item.localUri.isNullOrEmpty() && item.url.isNotEmpty()
                            }
                            if (!hasRemoteItems) continue

                            mediaTransferManager.startGroupDownload(message)
                            queuedDownloads += 1
                        }

                        else -> Unit
                    }
                }

                if (queuedDownloads > 0) {
                }
            }
        }
    }

    private fun warmVisibleContentWindows(messages: List<Message> = currentMessages, reason: String) {
        if (!::chatAdapter.isInitialized || messages.isEmpty()) return
        if (chatAdapter.isScrolling && (reason == "flow_emit" || reason == "prefill_settle")) return

        val visibleMessages = getVisibleBufferedMessages(messages)
        if (visibleMessages.isEmpty()) return

        val visibleSignature = visibleMessages.joinToString(separator = "|") { it.id }
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldDebounce = reason == "flow_emit" || reason == "prefill_settle"
        if (
            shouldDebounce &&
            visibleSignature == lastVisibleWarmupSignature &&
            nowElapsed - lastVisibleWarmupAtMs < VISIBLE_WARMUP_DEBOUNCE_MS
        ) {
            return
        }

        lastVisibleWarmupSignature = visibleSignature
        lastVisibleWarmupAtMs = nowElapsed


        chatAdapter.preCacheMediaHeights(visibleMessages)
        val previewModelOverrides = visibleMessages
            .asSequence()
            .filter { message ->
                message.type == MessageType.IMAGE ||
                    message.type == MessageType.VIDEO ||
                    message.type == MessageType.GIF ||
                    message.type == MessageType.MEME ||
                    message.type == MessageType.STICKER ||
                    message.type == MessageType.KLIPY_EMOJI
            }
            .mapNotNull { message ->
                chatAdapter.cachedLocalFilePath(message.chatId, message.id, message.type)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { localPath -> message.id to localPath }
            }
            .toMap()
        MessagePreviewCacheManager.warmMessagesAsync(
            applicationContext,
            visibleMessages,
            previewModelOverrides = previewModelOverrides
        )
        if (reason == "scroll_idle" || reason == "prefill_commit") {
            StartupTrace.logStage(
                "chat_visible_warm",
                "chatId=${chatId ?: "unknown"} reason=$reason visible=${visibleMessages.size}"
            )
        }
        if (reason == "scroll_idle") {
            prefetchViewportMediaIfNeeded(messages = visibleMessages, reason = reason)
        } else {
        }

        if (!initialVisibleWarmupLogged) {
            initialVisibleWarmupLogged = true
        }
    }

    private var firstLayoutClearJob: Job? = null

    private fun scheduleFirstLayoutClear() {
        if (!::chatAdapter.isInitialized || !chatAdapter.isFirstLayout) return
        firstLayoutClearJob?.cancel()
        firstLayoutClearJob = lifecycleScope.launch {
            delay(500L) // Wait for scroll to fully settle
            if (!::chatAdapter.isInitialized || chatAdapter.isScrolling || isFinishing || isDestroyed) {
                return@launch
            }
            chatAdapter.isFirstLayout = false
            // Flush the live flow emission that was deferred while isFirstLayout was true.
            // Without this, the 521-item emission stays deferred forever if the user doesn't
            // trigger another scroll (which would flush it on the next SCROLL_STATE_IDLE).
            applyDeferredLargeFlowEmissionIfNeeded()
        }
    }

    private fun cancelPendingScrollIdleMediaUpgrade() {
        scrollIdleMediaUpgradeJob?.cancel()
        scrollIdleMediaUpgradeJob = null
    }

    private fun scheduleScrollIdleMediaUpgrade() {
        cancelPendingScrollIdleMediaUpgrade()
        scrollIdleMediaUpgradeJob = lifecycleScope.launch {
            delay(SCROLL_IDLE_MEDIA_UPGRADE_DELAY_MS)
            if (!::chatAdapter.isInitialized || chatAdapter.isScrolling || isFinishing || isDestroyed) {
                return@launch
            }

            val lm = binding.recyclerViewMessages.layoutManager as? LinearLayoutManager ?: return@launch
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION || last < first) {
                return@launch
            }

            if (BuildConfig.DEBUG) {
                StartupTrace.logStage(
                    "chat_scroll_idle_upgrade",
                    "chatId=${chatId ?: "unknown"} first=$first last=$last visible=${last - first + 1}"
                )
            }

            chatAdapter.notifyVisibleScrollStoppedMedia(binding.recyclerViewMessages)
        }
    }

    private fun scheduleIdleTask(
        key: String,
        priority: IdleTaskPriority,
        delayMs: Long = 0L,
        reason: String,
        action: suspend () -> Unit
    ) {
        lifecycleScope.launch {
            idleTaskMutex.withLock {
                idleTaskQueue[key] = IdleScheduledTask(
                    key = key,
                    priority = priority,
                    delayMs = delayMs,
                    enqueuedAtMs = SystemClock.elapsedRealtime(),
                    action = action
                )
            }
            drainIdleTaskQueue(reason = "enqueue:$key")
        }
    }

    private fun pauseIdleTaskQueue(reason: String) {
        idleQueuePaused = true
        idleTaskRunnerJob?.cancel()
        idleTaskRunnerJob = null
    }

    private fun resumeIdleTaskQueue(reason: String) {
        if (!deferredStartupWorkUnlocked && !firstScrollMetricsLogged) {
            return
        }
        if (isKeyboardAnimating) {
            return
        }
        if (!idleQueuePaused && idleTaskRunnerJob?.isActive == true) return
        idleQueuePaused = false
        drainIdleTaskQueue(reason = reason)
    }

    private fun drainIdleTaskQueue(reason: String) {
        if (idleQueuePaused || isFinishing || isDestroyed || isKeyboardAnimating) return
        if (idleTaskRunnerJob?.isActive == true) return

        idleTaskRunnerJob = lifecycleScope.launch {
            delay(IDLE_QUEUE_RESUME_DELAY_MS)
            while (!idleQueuePaused && !isFinishing && !isDestroyed && !isKeyboardAnimating) {
                // NOTE: the chatAdapter.isScrolling self-kill was removed.
                // The authoritative pause mechanism is pauseIdleTaskQueue(), called by the
                // scroll DRAGGING handler, keyboard animation, and activity pause.
                // Warming during SETTLING is intentional and the frameBudgetGuard ensures
                // frames are not stolen from the decelerating fling.
                if (isKeyboardAnimating) {
                    idleQueuePaused = true
                    return@launch
                }

                val nextTask = idleTaskMutex.withLock {
                    val now = SystemClock.elapsedRealtime()
                    idleTaskQueue.values
                        .filter { now - it.enqueuedAtMs >= it.delayMs }
                        .minWithOrNull(compareBy<IdleScheduledTask>({ it.priority.ordinal }, { it.enqueuedAtMs }))
                        ?.also { idleTaskQueue.remove(it.key) }
                } ?: return@launch

                val startedAt = SystemClock.elapsedRealtime()
                runCatching {
                    nextTask.action.invoke()
                }.onFailure { error ->
                    Log.w(PHASE4_PERF_TAG, "idle_task_failed key=${nextTask.key}", error)
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
            }
        }
    }

    private fun scheduleDeferredOffscreenPreviewWarmup(messages: List<Message>, reason: String) {
        if (messages.isEmpty()) return

        deferredOffscreenPreviewWarmupJob?.cancel()
        deferredOffscreenPreviewWarmupJob = lifecycleScope.launch {
            scheduleIdleTask(
                key = "offscreen_preview_warmup",
                priority = IdleTaskPriority.LOW,
                delayMs = OFFSCREEN_WARMUP_IDLE_DELAY_MS,
                reason = reason
            ) {
                if (isFinishing || isDestroyed || !::chatAdapter.isInitialized || chatAdapter.isScrolling) return@scheduleIdleTask
                val visibleIds = getVisibleBufferedMessages(messages).asSequence().map { it.id }.toSet()
                val offscreenMessages = messages.filterNot { visibleIds.contains(it.id) }
                if (offscreenMessages.isEmpty()) return@scheduleIdleTask

                MessagePreviewCacheManager.warmMessagesAsync(applicationContext, offscreenMessages)
            }
        }
    }

    private fun scheduleDeferredMediaHeightWarmup(messages: List<Message>, reason: String) {
        if (messages.isEmpty() || !::chatAdapter.isInitialized) return

        deferredMediaHeightWarmupJob?.cancel()
        deferredMediaHeightWarmupJob = lifecycleScope.launch {
            scheduleIdleTask(
                key = "offscreen_media_height_warmup",
                priority = IdleTaskPriority.LOW,
                delayMs = DEFERRED_MEDIA_HEIGHT_IDLE_DELAY_MS,
                reason = reason
            ) {
                if (isFinishing || isDestroyed || chatAdapter.isScrolling) return@scheduleIdleTask
                val visibleIds = getVisibleBufferedMessages(messages).asSequence().map { it.id }.toSet()
                val deferredMessages = messages.filterNot { visibleIds.contains(it.id) }
                if (deferredMessages.isEmpty()) return@scheduleIdleTask

                chatAdapter.preCacheMediaHeights(deferredMessages)
            }
        }
    }

    private fun startPostRenderObserversIfNeeded(db: AppDatabase) {
        if (postRenderObserversStarted) return
        postRenderObserversStarted = true
        // startPresenceObservation() is now called directly from onCreate (after loadMessages)
        // so the RTDB listener attaches during the launch animation rather than after first frame.
        startBlockStatusObservation()
        startPinnedMessageBannerObservation(db)
        startWalkieTalkieObservation()
        setupWalkieTalkieOverlay()
    }

    private fun ensureTranslationWarmUpStarted(reason: String) {
        if (translationWarmupStarted || !::translationManager.isInitialized) return
        translationWarmupStarted = true
        translationManager.warmUp()
    }

    private fun ensureAiComposerWarmUpStarted(reason: String) {
        if (aiComposerWarmupStarted || !::aiComposerManager.isInitialized) return
        aiComposerWarmupStarted = true
        aiComposerManager.warmUp()
    }

    private fun scheduleDeferredFeatureWarmups(reason: String) {
        deferredFeatureWarmupJob?.cancel()
        deferredFeatureWarmupJob = lifecycleScope.launch {
            scheduleIdleTask(
                key = "deferred_feature_warmups",
                priority = IdleTaskPriority.LOW,
                delayMs = 2500L,
                reason = reason
            ) {
                if (isFinishing || isDestroyed) return@scheduleIdleTask
                ensureTranslationWarmUpStarted(reason = "idle_delay")
                ensureAiComposerWarmUpStarted(reason = "idle_delay")
                ensureAttachmentMenuInitialized(reason = "idle_delay")
                ensureEmojiPickerInitialized(reason = "idle_delay")
                ensureExpressiveTypingInitialized(reason = "idle_delay")
            }
        }
    }

    private fun logFirstVisibleContentIfNeeded(source: String, itemCount: Int) {
        if (firstContentDrawLogged) return
        firstContentDrawLogged = true
        val elapsedMs = SystemClock.elapsedRealtime() - chatOpenStartElapsedMs
        StartupTrace.logStage(
            "chat_first_visible_content",
            "chatId=${chatId ?: "unknown"} source=$source items=$itemCount elapsed=${elapsedMs}ms"
        )
        // Mirror into the per-chat ChatOpenTrace sequence so first-content appears
        // alongside chat_content_gate_ready / chat_reveal_start for ordering checks.
        ChatOpenTrace.event(
            chatId, "chat_first_visible_content",
            "source=$source items=$itemCount elapsed=${elapsedMs}ms"
        )
    }

    private fun startFirstScrollPerfTrackingIfNeeded(reason: String) {
        scrollController.startFirstScrollPerfTrackingIfNeeded(reason)
    }

    private fun startScrollPerfTrackingIfNeeded(reason: String, layoutManager: LinearLayoutManager?) {
        if (!::scrollController.isInitialized) return
        scrollController.startScrollPerfTrackingIfNeeded(
            reason = reason,
            layoutManager = layoutManager,
            recyclerScrollState = binding.recyclerViewMessages.scrollState
        )
    }

    private fun finishScrollPerfTrackingIfNeeded(reason: String, layoutManager: LinearLayoutManager?) {
        if (!::scrollController.isInitialized) return
        scrollController.finishScrollPerfTrackingIfNeeded(reason, layoutManager)
    }

    private fun updateScrollPerfVisibleRange(layoutManager: LinearLayoutManager?) {
        if (!::scrollController.isInitialized) return
        scrollController.updateVisibleRange(layoutManager)
    }

    /**
     * Diagnostics: a compact "viewType=count" summary of the recycled-view pool occupancy.
     * Read by [ChatScrollController] at first-scroll start so logcat shows whether the pool was
     * warm (counts near the per-type caps) or cold (zeros → mid-fling inflation jank).
     */
    private fun poolOccupancySummary(): String {
        if (!::chatAdapter.isInitialized) return ""
        val rv = binding.recyclerViewMessages
        val pool = rv.recycledViewPool ?: return ""
        // Active view types only (5, 6 are dead — never returned by getItemViewType)
        return buildString {
            for (viewType in intArrayOf(1, 2, 3, 4, 9, 10, 13, 17, 18, 19, 20)) {
                val count = pool.getRecycledViewCount(viewType)
                if (count > 0) {
                    if (isNotEmpty()) append(",")
                    append(viewType).append("=").append(count)
                }
            }
        }
    }

    /**
     * DEBUG-only diagnostic: logs pool occupancy vs targets with utilization percentages.
     * Emits a line like:
     *   POOL_HEALTH after_warm: 1=8/20(40%) 2=7/20(35%) 3=4/8(50%) ...
     */
    private fun logPoolHealth(tag: String) {
        if (!BuildConfig.DEBUG || !::chatAdapter.isInitialized) return
        val pool = binding.recyclerViewMessages.recycledViewPool ?: return
        val targets = RecyclerCoordinator.FULL_POOL_TARGETS
        if (targets.isEmpty()) return
        val sb = StringBuilder("POOL_HEALTH $tag: ")
        var totalCurrent = 0
        var totalTarget = 0
        for ((viewType, target) in targets) {
            val current = pool.getRecycledViewCount(viewType)
            val pct = if (target > 0) (current * 100) / target else 0
            sb.append("$viewType=$current/$target(${pct}%) ")
            totalCurrent += current
            totalTarget += target
        }
        val overallPct = if (totalTarget > 0) (totalCurrent * 100) / totalTarget else 0
        sb.append("total=$totalCurrent/$totalTarget(${overallPct}%)")
        GlyphPerf.log(sb.toString())
    }

    private fun finishFirstScrollPerfTrackingIfNeeded(reason: String) {
        if (!::scrollController.isInitialized) return
        scrollController.finishFirstScrollPerfTrackingIfNeeded(reason)
    }

    private fun scheduleDraftSave(text: String) {
        val id = chatId ?: return
        draftSaveJob?.cancel()

        val snapshot = text
        draftSaveJob = lifecycleScope.launch {
            delay(250)
            persistDraft(id, snapshot)
        }
    }

    private fun persistDraftNow() {
        val id = chatId ?: return
        draftSaveJob?.cancel()
        val current = binding.etMessageInput.text?.toString().orEmpty()
        persistDraft(id, current)
    }

    private fun clearDraftForChat() {
        val id = chatId ?: return
        draftSaveJob?.cancel()
        DraftMessageStore.clearDraft(id)
        lastSavedDraft = ""
    }

    private fun persistDraft(chatId: String, text: String) {
        if (text.isBlank()) {
            DraftMessageStore.clearDraft(chatId)
            lastSavedDraft = ""
            return
        }

        if (text == lastSavedDraft) return

        DraftMessageStore.setDraft(chatId, text)
        lastSavedDraft = text
    }

    private fun setupTypingIndicator() {
        val id = chatId ?: return

        // Listen for text changes to send typing status
        binding.etMessageInput.addTextChangedListener { text ->
            val currentlyTyping = !text.isNullOrEmpty()
            updateSendButtonState(currentlyTyping)
            
            // Resolve link preview for typed URLs
            resolveTypedLinkPreview()
            
            // Update Compose state for Pastel theme
            messageTextState.value = text?.toString() ?: ""

            scheduleDraftSave(text?.toString().orEmpty())

            // WhatsApp-like right accessory behavior: camera collapses, emoji shifts in.
            val shouldAnimateAccessoryIcons = isAccessoryIconsCollapsed != currentlyTyping
            updateAccessoryIcons(isTyping = currentlyTyping, animate = shouldAnimateAccessoryIcons)

            if (currentlyTyping) {
                ensureExpressiveTypingInitialized(reason = "first_text_input")
            }
            
            if (isTyping != currentlyTyping) {
                isTyping = currentlyTyping
                val effectiveTyping = isTyping && !isRealtimeInteractionBlocked(feature = RealtimeBlockFeature.TYPING)
                if (isGroupChat) {
                    groupRepository.setGroupTyping(
                        chatId = id,
                        isTyping = effectiveTyping,
                        participantUids = groupParticipantIdsForTyping
                    )
                } else {
                    otherUserId?.takeIf { it.isNotBlank() }?.let { peerId ->
                        PresenceManager.setTypingStatus(id, peerId, effectiveTyping)
                    }
                }
            }

            // Reset typing status after 3 seconds of inactivity (grace period before hiding)
            typingJob?.cancel()
            if (isTyping) {
                typingJob = lifecycleScope.launch {
                    delay(3000)
                    if (isTyping) {
                        isTyping = false
                        if (isGroupChat) {
                            groupRepository.setGroupTyping(
                                chatId = id,
                                isTyping = false,
                                participantUids = groupParticipantIdsForTyping
                            )
                        } else {
                            otherUserId?.takeIf { it.isNotBlank() }?.let { peerId ->
                                PresenceManager.setTypingStatus(id, peerId, false)
                            }
                        }
                    }
                }
            }

            // Live Expressive Typing: send live text to partner
            expressiveTypingManager?.onLocalTextChanged(text?.toString().orEmpty())
        }
    }

    /**
     * Initialize the Live Expressive Typing feature.
     * Sets up the manager, inflates the preview UI, and starts observing.
     */
    private fun setupExpressiveTyping() {
        if (isGroupChat) return
        val cId = chatId ?: return
        val otherId = otherUserId?.takeIf { it.isNotBlank() } ?: return

        // Initialize manager
        val manager = com.glyph.glyph_v3.ui.chat.expressive.ExpressiveTypingManager(
            chatId = cId,
            otherUserId = otherId,
            preferences = expressivePrefs
        )
        expressiveTypingManager = manager

        // Initialize lightweight state observer (no overlay — all rendering is
        // done inline within the RecyclerView TypingIndicatorViewHolder)
        val viewController = com.glyph.glyph_v3.ui.chat.expressive.ExpressiveTypingViewController()
        expressiveTypingViewController = viewController

        // When expressive state changes, store it and trigger a typing-indicator
        // refresh so the RecyclerView picks up the new expressive data.
        viewController.onExpressiveStateChanged = { active, text, sentiment, mutuallyEnabled, sourceTimestampMs ->
            val changed = (active != isExpressiveActive) ||
                          (text != expressiveLiveText) ||
                          (sentiment != expressiveSentiment) ||
                          (mutuallyEnabled != isExpressiveMutuallyEnabled)

            val shouldAnimateInlineUpdate = expressiveInlineAnimationArmed &&
                active &&
                sourceTimestampMs > expressiveObserveSessionStartedAtMs &&
                sourceTimestampMs > lastAppliedExpressiveSourceTimestampMs

            updateRomanticAnimationMode(active = active, sentiment = sentiment)
            
            isExpressiveActive = active
            isExpressiveMutuallyEnabled = mutuallyEnabled
            expressiveLiveText = text
            expressiveSentiment = sentiment
            if (sourceTimestampMs > 0L) {
                lastAppliedExpressiveSourceTimestampMs = maxOf(
                    lastAppliedExpressiveSourceTimestampMs,
                    sourceTimestampMs
                )
            }

            if (changed) {
                // Force the typing indicator list item to refresh with new expressive data
                refreshTypingIndicatorInline(animatePayload = shouldAnimateInlineUpdate)
            }

            // First expressive callback in a new observing session must never animate.
            // This prevents reopen/rebind/recreate from replaying previously detected sentiment effects.
            expressiveInlineAnimationArmed = true
        }

        viewController.bind(manager, lifecycleScope)

        // Start observing live typing from the other user
        startExpressiveObservingSession()

        // Sync preferences from Firestore on startup
        expressivePrefs.syncFromFirestore()
        
        // Inject the sentiment overlay for animated backgrounds and flying emojis
        setupSentimentLayers()
    }

    private fun ensureExpressiveTypingInitialized(reason: String) {
        if (isGroupChat) return
        if (expressiveTypingManager != null) return
        if (!expressivePrefs.isEnabled.value && reason != "menu_enable") return
        setupExpressiveTyping()
    }

    private fun startExpressiveObservingSession() {
        expressiveObserveSessionStartedAtMs = System.currentTimeMillis()
        expressiveInlineAnimationArmed = false
        expressiveTypingManager?.startObserving()
    }

    /**
     * Injects two Compose-based sentiment overlays into the chat UI for depth effect.
     * 
     * 1. Background Layer: Sits behind messages (index 2).
     *    - Displays gradient backgrounds (if active)
     *    - Displays small, slow, blurry emojis (depth)
     * 
     * 2. Foreground Layer: Sits in front of messages (top index).
     *    - Displays large, fast, sharp emojis (pop)
     * 
     * IMPORTANT: Uses State objects (_expressiveSentiment, _isExpressiveActive) directly
     * to enable real-time Compose observation.
     */
    private fun setupSentimentLayers() {
        val rootContainer = binding.root as? android.view.ViewGroup ?: return
        val chatContentContainer = binding.chatContentContainer
        bindExpressiveComposerAnchor()
        
        // --- 1. Background Layer ---
        if (rootContainer.findViewWithTag<android.view.View>("SentimentOverlayBackground") == null) {
            val backgroundView = androidx.compose.ui.platform.ComposeView(this).apply {
                tag = "SentimentOverlayBackground"
                elevation = 0f // Keep at 0 to respect Z-order behind messages (which are > 0 index)
                setContent {
                    com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                        com.glyph.glyph_v3.ui.chat.expressive.SentimentOverlay(
                            sentiment = if (_isMessageRomanticEffectActive.value) {
                                _messageRomanticSentiment.value
                            } else {
                                _expressiveSentiment.value
                            },
                            isActive = _isMessageRomanticEffectActive.value || _isExpressiveActive.value,
                            romanticAnimationMode = if (_isMessageRomanticEffectActive.value) {
                                _messageRomanticAnimationMode.value
                            } else {
                                _romanticAnimationMode.value
                            },
                            romanticComposerTopPx = _expressiveComposerTopPx.value,
                            layer = com.glyph.glyph_v3.ui.chat.expressive.EmojiLayer.BACKGROUND
                        )
                    }
                }
            }
            // Insert at index 2 (after wallpaper and dimming overlay)
            rootContainer.addView(
                backgroundView,
                2,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        // --- 2. Foreground Layer ---
        val existingForeground = rootContainer.findViewWithTag<android.view.View>("SentimentOverlayForeground")
        if (existingForeground != null && existingForeground.parent !== chatContentContainer) {
            (existingForeground.parent as? android.view.ViewGroup)?.removeView(existingForeground)
        }
        if (chatContentContainer.findViewWithTag<android.view.View>("SentimentOverlayForeground") == null) {
            val foregroundView = androidx.compose.ui.platform.ComposeView(this).apply {
                tag = "SentimentOverlayForeground"
                elevation = 0f
                setContent {
                    com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                        com.glyph.glyph_v3.ui.chat.expressive.SentimentOverlay(
                            sentiment = if (_isMessageRomanticEffectActive.value) {
                                _messageRomanticSentiment.value
                            } else {
                                _expressiveSentiment.value
                            },
                            isActive = _isMessageRomanticEffectActive.value || _isExpressiveActive.value,
                            romanticAnimationMode = if (_isMessageRomanticEffectActive.value) {
                                _messageRomanticAnimationMode.value
                            } else {
                                _romanticAnimationMode.value
                            },
                            romanticComposerTopPx = _expressiveComposerTopPx.value,
                            layer = com.glyph.glyph_v3.ui.chat.expressive.EmojiLayer.FOREGROUND
                        )
                    }
                }
            }
            val overlayLayoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT
            ).apply {
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
            val inputIndex = chatContentContainer.indexOfChild(binding.layoutInput).coerceAtLeast(0)
            chatContentContainer.addView(
                foregroundView,
                inputIndex,
                overlayLayoutParams
            )
        }
        
        // Clean up legacy single overlay if it exists
        val legacyOverlay = rootContainer.findViewWithTag<android.view.View>("SentimentOverlay")
        if (legacyOverlay != null) {
            rootContainer.removeView(legacyOverlay)
        }
    }

    private fun bindExpressiveComposerAnchor() {
        if (expressiveInputLayoutChangeListener != null) return

        val listener = View.OnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            expressiveComposerTopPx = top
        }
        expressiveInputLayoutChangeListener = listener
        binding.layoutInput.addOnLayoutChangeListener(listener)
        binding.layoutInput.post {
            expressiveComposerTopPx = binding.layoutInput.top
        }
    }

    private fun updateRomanticAnimationMode(
        active: Boolean,
        sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
    ) {
        val enteringRomantic = active &&
            sentiment == com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC &&
            (!isExpressiveActive || expressiveSentiment != com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC)

        if (enteringRomantic) {
            romanticAnimationMode = RomanticAnimationMode.random()
        } else if (!active || sentiment != com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC) {
            romanticAnimationMode = RomanticAnimationMode.EMOJI_PARTICLES
        }
    }

    private fun maybeTriggerRomanticMessageEffect(text: String) {
        val sentiment = com.glyph.glyph_v3.ui.chat.expressive.SentimentAnalysisService.classifyTextLocally(text)
        if (sentiment != com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC) {
            return
        }
        triggerRomanticMessageEffect(sentiment)
    }

    private fun maybeTriggerRomanticMessageEffect(
        messages: List<Message>,
        candidateMessageIds: Set<String>
    ) {
        if (candidateMessageIds.isEmpty()) {
            return
        }

        val romanticMessage = messages.asReversed().firstOrNull { message ->
            message.id in candidateMessageIds &&
                message.text.isNotBlank() &&
                !message.isDeletedForAll &&
                com.glyph.glyph_v3.ui.chat.expressive.SentimentAnalysisService.classifyTextLocally(message.text) ==
                    com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC
        } ?: return

        maybeTriggerRomanticMessageEffect(romanticMessage.text)
    }

    private fun triggerRomanticMessageEffect(
        sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType
    ) {
        if (sentiment != com.glyph.glyph_v3.ui.chat.expressive.SentimentType.ROMANTIC) {
            return
        }

        messageRomanticEffectJob?.cancel()
        isMessageRomanticEffectActive = true
        messageRomanticSentiment = sentiment
        messageRomanticAnimationMode = RomanticAnimationMode.random()
        messageRomanticEffectJob = lifecycleScope.launch {
            delay(3000L)
            isMessageRomanticEffectActive = false
            messageRomanticSentiment = com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL
            messageRomanticAnimationMode = RomanticAnimationMode.EMOJI_PARTICLES
        }
    }

    /**
     * Refresh just the typing indicator at the bottom of the RecyclerView
     * with the current expressive state, without rebuilding the whole list.
     */
    private fun refreshTypingIndicatorInline(animatePayload: Boolean = true) {
        if (!::chatAdapter.isInitialized) return
        if (processedListItems.isEmpty() && chatAdapter.currentList.isEmpty()) return
        val wasNearBottom = isRecyclerNearBottom()
        val base = if (chatAdapter.currentList.isNotEmpty()) chatAdapter.currentList else processedListItems
        val updatedList = buildTypingAdjustedList(base)
        processedListItems = updatedList
        chatAdapter.animateTypingPayloadUpdates = animatePayload
        chatAdapter.submitList(updatedList) {
            chatAdapter.animateTypingPayloadUpdates = true
            // After DiffUtil commits, scroll to reveal the typing indicator
            // only if the user was already near the bottom (don't hijack scroll position)
            if (wasNearBottom && updatedList.lastOrNull() is ChatListItem.TypingIndicator) {
                requestScrollToBottomAfterNextPreDraw(anchorToBottom = true)
            }
        }
    }

    private fun updateAccessoryIcons(isTyping: Boolean, animate: Boolean) {
        // isTyping=true  => Buzz collapses; AI shows.
        // isTyping=false => Buzz returns; camera shows.
        val emoji = binding.btnEmoji
        val camera = binding.btnCamera
        val aiBtn = binding.btnAiComposer
        val buzz = binding.btnLightning

        // Interrupt any in-flight transitions so the next state wins immediately.
        listOf(emoji, camera, aiBtn, buzz).forEach { view ->
            view.animate().cancel()
            view.clearAnimation()
        }

        val duration = if (animate) 220L else 0L
        val interpolatorIn = OvershootInterpolator(1.2f)
        val interpolatorOut = android.view.animation.AccelerateInterpolator()

        emoji.translationX = 0f

        if (isTyping) {
            // --- Buzz: scale + fade out, then remove it from layout so text can use the space. ---
            if (buzz.visibility == View.VISIBLE) {
                if (animate) {
                    buzz.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(180L)
                        .setInterpolator(interpolatorOut)
                        .withEndAction { buzz.visibility = View.GONE }
                        .start()
                } else {
                    buzz.visibility = View.GONE
                    buzz.alpha = 0f
                    buzz.scaleX = 0f
                    buzz.scaleY = 0f
                }
            }

            // --- Camera/AI swap ---
            if (animate) {
                if (camera.visibility == View.VISIBLE) {
                    camera.animate()
                        .alpha(0f)
                        .scaleX(0.4f)
                        .scaleY(0.4f)
                        .setDuration(120L)
                        .setInterpolator(interpolatorOut)
                        .withEndAction { camera.visibility = View.INVISIBLE }
                        .start()
                } else {
                    camera.visibility = View.INVISIBLE
                    camera.alpha = 0f
                    camera.scaleX = 0.4f
                    camera.scaleY = 0.4f
                }
            } else {
                camera.visibility = View.INVISIBLE
                camera.alpha = 0f
                camera.scaleX = 0.4f
                camera.scaleY = 0.4f
            }

            aiBtn.visibility = View.VISIBLE
            if (animate) {
                aiBtn.alpha = 0f
                aiBtn.scaleX = 0f
                aiBtn.scaleY = 0f
                aiBtn.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(100L)
                    .setDuration(duration)
                    .setInterpolator(interpolatorIn)
                    .start()
            } else {
                aiBtn.alpha = 1f
                aiBtn.scaleX = 1f
                aiBtn.scaleY = 1f
            }
        } else {
            // --- Buzz: scale + fade back to VISIBLE. ---
            if (buzz.visibility != View.VISIBLE) {
                buzz.visibility = View.VISIBLE
                buzz.alpha = 0f
                buzz.scaleX = 0f
                buzz.scaleY = 0f
                if (animate) {
                    buzz.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(interpolatorIn)
                        .start()
                } else {
                    buzz.alpha = 1f
                    buzz.scaleX = 1f
                    buzz.scaleY = 1f
                }
            }

            // --- Camera/AI swap ---
            if (animate) {
                if (aiBtn.visibility == View.VISIBLE) {
                    aiBtn.animate()
                        .alpha(0f)
                        .scaleX(0.4f)
                        .scaleY(0.4f)
                        .setDuration(120L)
                        .setInterpolator(interpolatorOut)
                        .withEndAction { aiBtn.visibility = View.GONE }
                        .start()
                } else {
                    aiBtn.visibility = View.GONE
                    aiBtn.alpha = 0f
                    aiBtn.scaleX = 0.4f
                    aiBtn.scaleY = 0.4f
                }
            } else {
                aiBtn.visibility = View.GONE
                aiBtn.alpha = 0f
                aiBtn.scaleX = 0.4f
                aiBtn.scaleY = 0.4f
            }

            camera.visibility = View.VISIBLE
            if (animate) {
                camera.alpha = 0f
                camera.scaleX = 0f
                camera.scaleY = 0f
                camera.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(100L)
                    .setDuration(duration)
                    .setInterpolator(interpolatorIn)
                    .start()
            } else {
                camera.alpha = 1f
                camera.scaleX = 1f
                camera.scaleY = 1f
            }
        }

        isAccessoryIconsCollapsed = isTyping
    }

    private fun updateHeaderStatus() {
        if (isGroupChat) {
            val subtitle = lastSeenState.value.ifBlank { binding.tvLastSeen.text?.toString().orEmpty() }
            if (subtitle.isNotBlank()) {
                binding.tvLastSeen.text = subtitle
                binding.tvLastSeen.visibility = View.VISIBLE
                applyComposeHeaderStatus(subtitle, source = "group_update_header")
            }
            return
        }

        // If blocked in either direction, hide presence info entirely
        val blockStatus = _blockStatus.value
        if (blockStatus.isBlocked) {
            binding.tvLastSeen.text = ""
            binding.tvLastSeen.visibility = View.GONE
            applyComposeHeaderStatus("", source = "blocked")
            return
        }

        // Header should only show presence/last-seen.
        // Typing is represented exclusively in the message list indicator item.
        if (isOtherUserTyping) {
            hideTypingIndicator()
            val status = lastPresenceStatus
            if (status != null) {
                updatePresenceUI(status)
            } else {
                binding.tvLastSeen.text = ""
                binding.tvLastSeen.visibility = View.GONE
                applyComposeHeaderStatus("", source = "presence_missing_typing")
            }
        } else {
            hideTypingIndicator()
            val status = lastPresenceStatus
            if (status != null) {
                updatePresenceUI(status)
            } else {
                binding.tvLastSeen.text = ""
                binding.tvLastSeen.visibility = View.GONE
                applyComposeHeaderStatus("", source = "presence_missing")
            }
        }
    }

    private fun showTypingIndicator() {
        // Overlay disabled in favor of RecyclerView item
        binding.layoutTypingIndicator.visibility = View.GONE
        
        // Update toolbar status
        if (binding.tvLastSeen.text != "typing...") {
            android.transition.TransitionManager.beginDelayedTransition(binding.toolbarContainer)
            binding.tvLastSeen.text = "typing..."
            binding.tvLastSeen.setTextColor(ContextCompat.getColor(this, R.color.glyph_online))
            binding.tvLastSeen.visibility = View.VISIBLE
        }
    }

    private fun hideTypingIndicator(animate: Boolean = true) {
        binding.layoutTypingIndicator.visibility = View.GONE
    }

    private fun hideTypingIndicator() = hideTypingIndicator(animate = true)

    private fun isRecyclerAtBottom(): Boolean {
        // Strict bottom (not a threshold): ensures “fully pinned” behavior.
        return !binding.recyclerViewMessages.canScrollVertically(1)
    }

    /**
     * Legacy call-site kept for compatibility.
     *
     * Deterministically scrolls only after the next layout/pre-draw, so the last item has been
     * measured and the bottom constraints (input/typing/attachment/IME) have taken effect.
     */
    private fun scrollToBottomAfterNextLayout() {
        requestScrollToBottomAfterNextPreDraw()
    }

    /**
     * Deterministic scroll-to-bottom that runs only after layout is committed and the last item
     * has had a chance to be measured (pre-draw phase). No timers, no arbitrary delays.
     *
     * Idempotent/safe to call repeatedly; the latest request wins.
     */
    private fun requestScrollToBottomAfterNextPreDraw(
        forceScroll: Boolean = pendingScrollToBottomOnNextListCommit,
        anchorToBottom: Boolean = !userHasScrolledUp && (isRecyclerNearBottom() || isRecyclerAtBottom())
    ) {
        val translY = binding.chatContentContainer.translationY
        Log.d(KeyboardController.FLASH_TAG, "requestScrollToBottom: force=$forceScroll anchor=$anchorToBottom translationY=$translY isKeyboardAnimating=$isKeyboardAnimating")
        val rv = binding.recyclerViewMessages
        if (chatAdapter.itemCount <= 0) {
            suppressScrollFabUntilAtBottom = false
            if (::scrollFabController.isInitialized) {
                scrollFabController.hideImmediately()
            }
            return
        }

        val shouldScrollAfterNextLayout = forceScroll || anchorToBottom
        if (!shouldScrollAfterNextLayout) {
            suppressScrollFabUntilAtBottom = false
            scheduleScrollFabUpdate()
            return
        }

        // Do not forcibly scroll when the user is reading history, UNLESS a send-message
        // action explicitly requested it via pendingScrollToBottomOnNextListCommit.
        if (userHasScrolledUp && !forceScroll) return

        // Suppress any scroll FAB updates until this deterministic scroll finishes.
        suppressScrollFabUntilAtBottom = true

        val token = ++scrollToBottomRequestToken

        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (token != scrollToBottomRequestToken) {
                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }

                val countNow = chatAdapter.itemCount
                if (countNow <= 0) {
                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }

                // Run exactly once for smoothness.
                rv.viewTreeObserver.removeOnPreDrawListener(this)

                // While the IME is animating, the keyboard controller's onEnd already performs
                // an unconditional pre-scroll to the last item before its layout commit. Running
                // scrollToPosition here as well forces a full RecyclerView relayout *during* the
                // slide. With a small chat that relayout is invisible, but a very large history
                // (e.g. 4000+ items) whose DiffUtil happens to finish mid-animation dispatches
                // its commit callback here, and the resulting scrollToPosition churns the list
                // exactly while it slides — the one-time content flicker the user sees only in
                // the large chat (small/GIF chats finish their diff before the keyboard opens).
                // Defer to onEnd's anchor; the FAB stays suppressed until the list settles at the
                // bottom, where the scroll listener clears the suppression.
                if (isKeyboardAnimating) {
                    return true
                }

                val shouldScrollNow = forceScroll || (!userHasScrolledUp && anchorToBottom)
                if (!shouldScrollNow) {
                    suppressScrollFabUntilAtBottom = false
                    StartupTrace.logStage(
                        "chat_scroll_to_bottom_cancelled",
                        "chatId=${chatId ?: "unknown"} state=${rv.scrollState} first=${(rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION} last=${(rv.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION} userScrolledUp=$userHasScrolledUp pending=$pendingScrollToBottomOnNextListCommit force=$forceScroll anchor=$anchorToBottom"
                    )
                    scheduleScrollFabUpdate()
                    return true
                }

                val target = countNow - 1
                rv.scrollToPosition(target)

                // After the scroll has been applied and children are laid out, ensure the last
                // message is not covered by the bottom UI (input/attachment/typing).
                rv.post {
                    if (token != scrollToBottomRequestToken) return@post
                    ensureLastItemFullyVisibleAboveBottomUi(target)
                    scheduleScrollFabUpdate()
                }
                return true
            }
        }

        rv.viewTreeObserver.addOnPreDrawListener(listener)
        // Ensure we actually get a pre-draw callback soon.
        rv.requestLayout()
        rv.invalidate()
    }

    private fun ensureLastItemFullyVisibleAboveBottomUi(lastAdapterPosition: Int) {
        val rv = binding.recyclerViewMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return

        val lastView = lm.findViewByPosition(lastAdapterPosition) ?: return

        // Compute the "safe" bottom edge of the message list as the top of whichever bottom UI
        // element is currently highest on screen.
        val rvLoc = IntArray(2)
        rv.getLocationOnScreen(rvLoc)
        val rvTopOnScreen = rvLoc[1]

        val blockingTopOnScreen = computeBottomBlockingTopOnScreen()
        val safeBottomInRv = (blockingTopOnScreen - rvTopOnScreen).coerceIn(0, rv.height)

        // Keep a little breathing room using the RecyclerView's own bottom padding.
        val targetBottom = (safeBottomInRv - rv.paddingBottom).coerceAtLeast(0)
        val delta = lastView.bottom - targetBottom
        if (delta > 0) {
            rv.scrollBy(0, delta)
        }
    }

    private fun computeBottomBlockingTopOnScreen(): Int {
        // layoutInput is always present; attachment menu / typing indicator are conditional.
        val candidates = ArrayList<View>(3)
        candidates.add(binding.layoutInput)

        if (binding.layoutAttachmentMenu.root.visibility == View.VISIBLE) {
            candidates.add(binding.layoutAttachmentMenu.root)
        }
        if (binding.layoutTypingIndicator.visibility == View.VISIBLE) {
            candidates.add(binding.layoutTypingIndicator)
        }

        var minTop = Int.MAX_VALUE
        val tmp = IntArray(2)
        for (v in candidates) {
            v.getLocationOnScreen(tmp)
            val top = tmp[1]
            if (top < minTop) minTop = top
        }
        return minTop
    }
    
    private var lastSeenRefreshJob: Job? = null

    private fun isLastSeenStatusText(statusText: String): Boolean {
        return statusText.startsWith("last seen", ignoreCase = true)
    }

    private fun applyComposeHeaderStatus(statusText: String, source: String) {
        lastSeenState.value = statusText

        val shouldGateStartupLastSeen =
            !isGroupChat &&
                !startupLastSeenRevealCompleted &&
                isLastSeenStatusText(statusText)

        if (shouldGateStartupLastSeen) {
            composeHeaderLastSeenState.value = ""
            composeHeaderLastSeenMarqueeEnabledState.value = false
            maybeScheduleInitialLastSeenReveal(_reason = source)
            return
        }

        composeHeaderLastSeenState.value = statusText
        composeHeaderLastSeenMarqueeEnabledState.value =
            !isGroupChat && isLastSeenStatusText(statusText)
    }

    private fun isStartupHeaderWorkSettledForLastSeenReveal(): Boolean {
        if (!deferredStartupWorkUnlocked || firstScrollTrackingActive) return false
        if (!::binding.isInitialized) return false
        val recyclerIdle = binding.recyclerViewMessages.scrollState == RecyclerView.SCROLL_STATE_IDLE
        val adapterIdle = !::chatAdapter.isInitialized || !chatAdapter.isScrolling
        return recyclerIdle && adapterIdle
    }

    private fun computeLatestPresenceStatusTextFromCache(): String? {
        val status = lastPresenceStatus ?: return null
        val privacy = otherUserPrivacySettings ?: return ""

        fun isVisible(visibility: String?): Boolean = when (visibility) {
            "nobody" -> false
            "contacts" -> otherUserPrivacyAmIContact
            else -> true
        }

        val showOnline = isVisible(privacy.onlineVisibility)
        val showLastSeen = isVisible(privacy.lastSeenVisibility)

        return if (status.isOnline && showOnline) {
            val isInThisChat = status.viewingChatId != null && status.viewingChatId == chatId
            getString(if (isInThisChat) R.string.status_online_in_chat else R.string.status_online)
        } else if (!status.isOnline && showLastSeen) {
            formatLastSeen(status.lastSeen)
        } else {
            ""
        }
    }

    private fun maybeScheduleInitialLastSeenReveal(_reason: String) {
        if (isGroupChat || startupLastSeenRevealCompleted) return
        if (!isLastSeenStatusText(lastSeenState.value)) return
        if (!isStartupHeaderWorkSettledForLastSeenReveal()) return
        if (startupLastSeenRevealJob?.isActive == true) return

        startupLastSeenRevealJob = lifecycleScope.launch {
            // Wait for at least 2 presence emissions: the first is RTDB's
            // locally-cached (stale) value; the second is server-confirmed.
            // This prevents displaying an outdated "last seen" timestamp.
            val startedWaitingAt = System.currentTimeMillis()
            val maxWaitMs = 3000L
            while (presenceEmissionCount < 2 && (System.currentTimeMillis() - startedWaitingAt) < maxWaitMs) {
                delay(50)
                if (isFinishing || isDestroyed || startupLastSeenRevealCompleted) return@launch
            }
            if (isFinishing || isDestroyed || startupLastSeenRevealCompleted) return@launch
            if (!isStartupHeaderWorkSettledForLastSeenReveal()) return@launch

            val latestStatusText = computeLatestPresenceStatusTextFromCache() ?: lastSeenState.value
            startupLastSeenRevealCompleted = true
            applyComposeHeaderStatus(latestStatusText, source = "startup_last_seen_reveal")
        }
    }

    /**
     * Starts a real-time observation of online members for the current group chat.
     * Shows "Name online", "Name1, Name2 online", or "N online" in the header
     * subtitle, falling back to the static member-list subtitle when nobody is online.
     *
     * Restarts only when [memberIds] changes (avoids churn on metadata-only updates).
     */
    private fun startGroupPresenceObservation(memberIds: List<String>) {
        if (!isGroupChat) return
        if (memberIds == lastObservedGroupMembers) return
        lastObservedGroupMembers = memberIds

        val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        groupPresenceJob?.cancel()
        pendingGroupPresenceFallbackJob?.cancel()
        pendingGroupPresenceFallbackJob = null
        if (memberIds.isEmpty()) return

        val totalMemberCount = memberIds.size
        groupPresenceJob = lifecycleScope.launch {
            try {
                com.glyph.glyph_v3.data.repo.GroupPresenceRepository
                    .observeGroupOnlineUsers(memberIds, selfUid)
                    .collectLatest { onlineUids ->
                        pendingGroupPresenceFallbackJob?.cancel()
                        pendingGroupPresenceFallbackJob = null

                        if (onlineUids.isNotEmpty()) {
                            activeGroupOnlineCount = onlineUids.size
                            val subtitle = "$totalMemberCount members, ${onlineUids.size} online"
                            if (!isFinishing && !isDestroyed) {
                                binding.tvLastSeen.text = subtitle
                                binding.tvLastSeen.visibility =
                                    if (subtitle.isNotBlank()) View.VISIBLE else View.GONE
                                applyComposeHeaderStatus(subtitle, source = "group_presence_online")
                            }
                            return@collectLatest
                        }

                        if (activeGroupOnlineCount == 0) {
                            val fallback = groupMemberSubtitle
                            if (!isFinishing && !isDestroyed) {
                                binding.tvLastSeen.text = fallback
                                binding.tvLastSeen.visibility =
                                    if (fallback.isNotBlank()) View.VISIBLE else View.GONE
                                applyComposeHeaderStatus(fallback, source = "group_presence_fallback")
                            }
                            return@collectLatest
                        }

                        pendingGroupPresenceFallbackJob = launch {
                            delay(GROUP_PRESENCE_EMPTY_FALLBACK_GRACE_MS)
                            if (isFinishing || isDestroyed) return@launch
                            activeGroupOnlineCount = 0
                            val fallback = groupMemberSubtitle
                            binding.tvLastSeen.text = fallback
                            binding.tvLastSeen.visibility =
                                if (fallback.isNotBlank()) View.VISIBLE else View.GONE
                            applyComposeHeaderStatus(fallback, source = "group_presence_fallback_grace")
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.w("ChatActivity", "Group presence observation error", e)
            }
        }
    }

    private fun startPresenceObservation() {
        val userId = otherUserId ?: return

        // Reset privacy state so the null-guard in updatePresenceUI hides presence
        // until Firestore confirms the other user's settings. This prevents any
        // cached/fast RTDB emission from briefly showing a restricted status.
        otherUserPrivacySettings = PrivacySettingsRepository.getCachedPrivacySettingsForUser(userId)
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        otherUserPrivacyAmIContact = PrivacySettingsRepository
            .canViewerSeeCached(userId, myUid, "contacts")
            ?: false
        presenceEmissionCount = 0
        updateHeaderStatus()

        // Observe the other user's privacy settings so we can gate last seen / online
        privacySettingsJob?.cancel()
        privacySettingsJob = lifecycleScope.launch {
            try {
                // One-shot: determine if we are in the other user's contacts (share a chat)
                val amITheirContact = withContext(Dispatchers.IO) {
                    try {
                        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext false
                        PrivacySettingsRepository.canViewerSee(userId, myUid, "contacts")
                    } catch (_: Exception) { false }
                }
                
                PrivacySettingsRepository.privacySettingsFlowForUser(userId).collectLatest { settings ->
                    otherUserPrivacySettings = settings
                    otherUserPrivacyAmIContact = amITheirContact
                    // Re-evaluate header whenever privacy settings change
                    updateHeaderStatus()
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Error observing privacy settings for $userId", e)
            }
        }
        
        presenceJob?.cancel()
        presenceJob = lifecycleScope.launch {
            try {
                PresenceManager.observeUserPresence(userId).collectLatest { presence ->
                    lastPresenceStatus = presence
                    presenceEmissionCount++
                    updateHeaderStatus()
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Error observing presence", e)
                // Show offline status on error
                lastPresenceStatus = PresenceManager.PresenceStatus(false, 0L)
                updateHeaderStatus()
            }
        }
        
        // Periodic refresh: re-format the relative "last seen" text every 60 seconds.
        // Without this, "last seen 5 minutes ago" stays frozen until the next RTDB change,
        // making the display progressively more stale.
        lastSeenRefreshJob?.cancel()
        lastSeenRefreshJob = lifecycleScope.launch {
            // delay() is cancellable — CancellationException propagates when the
            // job is cancelled, naturally exiting the loop.
            while (true) {
                delay(60_000L)
                val status = lastPresenceStatus
                if (status != null && !status.isOnline) {
                    updateHeaderStatus()
                }
            }
        }
    }

    private fun startOtherUserAvatarObservation() {
        val userId = otherUserId ?: return
        avatarVisibilityJob?.cancel()
        avatarVisibilityJob = lifecycleScope.launch {
            AvatarVisibilityRepository.observeProfilePhotoVisibility(userId).collectLatest { state ->
                applyOtherUserAvatarVisibility(state)
            }
        }
    }

    private fun applyOtherUserAvatarVisibility(
        visibilityState: ProfilePhotoVisibilityState? = otherUserId?.let {
            AvatarVisibilityRepository.getCachedProfilePhotoVisibility(it)
        }
    ) {
        val localPeerAvatarAvailable = otherUserId?.let {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(it)
        } != null
        val canOptimisticallyShowLocalAvatar =
            !isGroupChat && visibilityState?.isResolved != true && localPeerAvatarAvailable

        val visibleAvatar = otherUserAvatar.takeIf {
            !_blockStatus.value.isBlocked &&
                it.isNotBlank() &&
                (isGroupChat || visibilityState?.isVisible == true || canOptimisticallyShowLocalAvatar)
        }

        userAvatarState.value = visibleAvatar.orEmpty()

        if (::chatAdapter.isInitialized) {
            chatAdapter.setUserAvatars(
                visibleAvatar,
                currentUserAvatar.takeIf { it.isNotBlank() }
            )
        }

        if (!isFinishing && !isDestroyed) {
            updateHeaderInfo()
            refreshMapBackgroundAvatars()
        }

        if (!_blockStatus.value.isBlocked && visibilityState?.isVisible == true) {
            startAvatarAutoSync()
        } else {
            avatarSyncJob?.cancel()
        }
    }
    
    private fun startBlockStatusObservation() {
        val userId = otherUserId ?: return
        blockStatusJob?.cancel()
        blockStatusJob = lifecycleScope.launch {
            com.glyph.glyph_v3.data.repo.BlockRepository.observeBlockStatus(userId).collectLatest { status ->
                _blockStatus.value = status
                if (status.isBlocked) {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearAvatarCache(userId)
                }
                applyOtherUserAvatarVisibility()
                // Update header presence when block status changes
                updateHeaderStatus()
                // Update input bar visibility based on block state
                updateInputBarForBlockStatus(status, animate = didPrimeInitialBlockUi)
                handleRealtimeBlockStateChanged(status)
                didPrimeInitialBlockUi = true
            }
        }
    }

    private fun primeInitialBlockUiState() {
        val userId = otherUserId ?: return
        val initialStatus = com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(userId)
        _blockStatus.value = initialStatus
        if (initialStatus.isBlocked) {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearAvatarCache(userId)
        }
        applyOtherUserAvatarVisibility()
        updateInputBarForBlockStatus(initialStatus, animate = false)
        handleRealtimeBlockStateChanged(initialStatus)

        lifecycleScope.launch {
            val warmedStatus = withContext(Dispatchers.IO) {
                com.glyph.glyph_v3.data.repo.BlockRepository.warmBlockStatusFromLocalCache(userId)
            }
            _blockStatus.value = warmedStatus
            if (warmedStatus.isBlocked) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearAvatarCache(userId)
            }
            applyOtherUserAvatarVisibility()
            updateHeaderStatus()
            updateInputBarForBlockStatus(warmedStatus, animate = false)
            handleRealtimeBlockStateChanged(warmedStatus)
            didPrimeInitialBlockUi = true
        }
    }

    private fun updateInputBarForBlockStatus(
        status: com.glyph.glyph_v3.data.repo.BlockStatus,
        animate: Boolean = true
    ) {
        runOnUiThread {
            val inputContainer = binding.layoutInput
            val blockBanner = binding.root.findViewById<View>(R.id.layoutBlockedBanner)

            // Only show the banner and hide input when this user initiated the block.
            if (status.iBlockedThem) {
                if (blockBanner?.visibility != View.VISIBLE) {
                    inputContainer.visibility = View.GONE
                    binding.replyPreviewCard.visibility = View.GONE

                    blockBanner?.animate()?.cancel()
                    blockBanner?.alpha = if (animate) 0f else 1f
                    blockBanner?.visibility = View.VISIBLE
                    if (animate) {
                        blockBanner?.animate()?.alpha(1f)?.setDuration(250)?.start()
                    }
                }

                if (blockBanner != null) {
                    val tvTimestamp = blockBanner.findViewById<android.widget.TextView>(R.id.tv_blocked_timestamp)
                    val btnUnblock = blockBanner.findViewById<View>(R.id.btn_unblock)

                    val blockedAt = otherUserId?.let { com.glyph.glyph_v3.data.repo.BlockRepository.getBlockedAt(it) }
                    if (blockedAt != null && blockedAt > 0L) {
                        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", java.util.Locale.getDefault()).format(java.util.Date(blockedAt))
                        tvTimestamp.text = "On $dateStr"
                        tvTimestamp.visibility = View.VISIBLE
                    } else {
                        tvTimestamp.visibility = View.GONE
                    }

                    btnUnblock.setOnClickListener { showUnblockConfirmation() }
                }
            } else {
                // Not blocked by me: show input, remove banner so the blocked user sees no hint.
                if (inputContainer.visibility != View.VISIBLE) {
                    inputContainer.animate().cancel()
                    inputContainer.alpha = if (animate) 0f else 1f
                    inputContainer.visibility = View.VISIBLE
                    if (animate) {
                        inputContainer.animate().alpha(1f).setDuration(250).start()
                    }
                }

                if (blockBanner?.visibility == View.VISIBLE) {
                    blockBanner.animate().cancel()
                    if (animate) {
                        blockBanner.animate().alpha(0f).setDuration(250).withEndAction {
                            blockBanner.visibility = View.GONE
                            binding.root.post { updateInteractiveMapButtonBottomMargin() }
                        }.start()
                    } else {
                        blockBanner.alpha = 1f
                        blockBanner.visibility = View.GONE
                        binding.root.post { updateInteractiveMapButtonBottomMargin() }
                    }
                }
            }

            // Cleanup old legacy dynamic banner
            val oldBanner = binding.root.findViewWithTag<View>("BLOCK_BANNER")
            oldBanner?.let { (it.parent as? ViewGroup)?.removeView(it) }

            // Keep the map-interactive button above whichever bottom bar is active.
            binding.root.post { updateInteractiveMapButtonBottomMargin() }
        }
    }

    /**
     * Keep the interactive map button above bottom overlays.
     * When the blocked banner is visible, lift the button above it.
     */
    private fun updateInteractiveMapButtonBottomMargin(navBottomOverride: Int? = null) {
        val lp = binding.interactiveMapButton.layoutParams
            as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return

        if (interactiveMapButtonBaseMarginPx < 0) {
            interactiveMapButtonBaseMarginPx = lp.bottomMargin
        }

        val blockedBanner = binding.root.findViewById<View>(R.id.layoutBlockedBanner)
        val blockedVisible = blockedBanner?.visibility == View.VISIBLE

        // Anchor above the blocked banner when it is shown; otherwise anchor above input.
        val targetAnchor = if (blockedVisible) R.id.layoutBlockedBanner else R.id.layoutInput
        if (lp.bottomToTop != targetAnchor) {
            lp.bottomToTop = targetAnchor
        }

        // Keep the XML baseline spacing from the anchored surface.
        val targetBottom = interactiveMapButtonBaseMarginPx
        if (lp.bottomMargin != targetBottom) {
            lp.bottomMargin = targetBottom
        }

        // Parameter kept for caller compatibility; no extra nav-bar lift needed because
        // both input and blocked banner already receive bottom inset padding.
        @Suppress("UNUSED_VARIABLE")
        val ignoredNavBottom = navBottomOverride

        binding.interactiveMapButton.layoutParams = lp
        binding.interactiveMapButton.post {
            logInteractiveMapTouchSnapshot("updateBottomMargin")
        }
    }

    private fun showBlockConfirmation() {
        val username = otherUsername.ifEmpty { "this user" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Block $username?")
            .setMessage(
                "Blocked contacts will no longer be able to send you messages or see your online status. " +
                "Existing messages will not be deleted."
            )
            .setPositiveButton("Block") { _, _ ->
                lifecycleScope.launch {
                    try {
                        com.glyph.glyph_v3.data.repo.BlockRepository.blockUser(otherUserId!!)
                        Toast.makeText(this@ChatActivity, "$username blocked", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Failed to block user", e)
                        Toast.makeText(this@ChatActivity, "Failed to block. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnblockConfirmation() {
        val username = otherUsername.ifEmpty { "this user" }
        val view = layoutInflater.inflate(R.layout.dialog_unblock_confirmation, null)
        view.findViewById<android.widget.TextView>(R.id.text_unblock_title)?.text = "Unblock $username?"
        view.findViewById<android.widget.TextView>(R.id.text_unblock_message)?.text =
            "$username will be able to send you messages, call you, and use live sharing features with you again."

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.GlyphConfirmationDialog)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_unblock_cancel)
            ?.setOnClickListener { dialog.dismiss() }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_unblock_confirm)
            ?.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    try {
                        com.glyph.glyph_v3.data.repo.BlockRepository.unblockUser(otherUserId!!)
                        showUnblockSuccessDialog(username)
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Failed to unblock user", e)
                        Toast.makeText(this@ChatActivity, "Failed to unblock. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        dialog.show()
    }

    private fun showUnblockSuccessDialog(username: String) {
        val view = layoutInflater.inflate(R.layout.dialog_unblock_success, null)
        view.findViewById<android.widget.TextView>(R.id.text_unblock_success_title)?.text =
            "$username unblocked"
        view.findViewById<android.widget.TextView>(R.id.text_unblock_success_message)?.text =
            "$username can message, call, and use live sharing with you again."

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.GlyphConfirmationDialog)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_unblock_success_done)
            ?.setOnClickListener { dialog.dismiss() }

        dialog.show()

        lifecycleScope.launch {
            delay(2800)
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun updatePresenceUI(status: PresenceManager.PresenceStatus) {
        val privacy = otherUserPrivacySettings

        // Privacy settings have not been loaded from Firestore yet.
        // Hide all presence info until they arrive to guarantee zero exposure of
        // restricted statuses — even for a single frame. The presence data is
        // already captured in lastPresenceStatus and will be rendered correctly
        // as soon as privacySettingsJob delivers its first emission.
        if (privacy == null) {
            binding.tvLastSeen.text = ""
            binding.tvLastSeen.visibility = View.GONE
            applyComposeHeaderStatus("", source = "privacy_not_loaded")
            return
        }

        val statusText: String
        val colorRes: Int
        val currentKind: HeaderPresenceKind

        // Helper to check visibility for a given setting
        fun isVisible(visibility: String?): Boolean = when (visibility) {
            "nobody" -> false
            "contacts" -> otherUserPrivacyAmIContact
            else -> true // "everyone" or null (default: show)
        }

        // Gate online display based on the other user's onlineVisibility setting
        val showOnline = isVisible(privacy?.onlineVisibility)
        // Gate last seen display based on the other user's lastSeenVisibility setting
        val showLastSeen = isVisible(privacy?.lastSeenVisibility)

        if (status.isOnline && showOnline) {
            val isInThisChat = status.viewingChatId != null && status.viewingChatId == chatId
            statusText = getString(
                if (isInThisChat) R.string.status_online_in_chat else R.string.status_online
            )
            colorRes = R.color.glyph_online
            currentKind = if (isInThisChat) HeaderPresenceKind.ONLINE_IN_CHAT else HeaderPresenceKind.ONLINE
        } else if (!status.isOnline && showLastSeen) {
            statusText = formatLastSeen(status.lastSeen)
            colorRes = R.color.glyph_text_secondary
            currentKind = when {
                statusText.startsWith("last seen", ignoreCase = true) -> HeaderPresenceKind.LAST_SEEN
                statusText.isEmpty() -> HeaderPresenceKind.HIDDEN
                else -> HeaderPresenceKind.OFFLINE
            }
        } else {
            // Privacy restricts showing this info — hide it
            statusText = ""
            colorRes = R.color.glyph_text_secondary
            currentKind = HeaderPresenceKind.HIDDEN
        }

        // Keep XML view in sync (it is hidden, but kept as a source of truth)
        binding.tvLastSeen.text = statusText
        binding.tvLastSeen.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.tvLastSeen.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE

        lastHeaderPresenceKind = currentKind

        // Drive the Compose ChatHeader — it handles its own last-seen scroll animation
        // via Animatable which is immune to RecyclerView draw cascades.
        applyComposeHeaderStatus(statusText, source = "presence_update")
    }
    
    /**
     * Format the last seen timestamp into a human-readable string.
     * Examples: "last seen just now", "last seen 5 minutes ago", "last seen today at 3:45 PM", 
     * "last seen yesterday at 10:30 AM", "last seen Dec 9 at 2:15 PM"
     */
    private fun formatLastSeen(timestamp: Long): String {
        if (timestamp == 0L) {
            return getString(R.string.status_offline)
        }
        
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            // Less than 1 minute ago
            diff < TimeUnit.MINUTES.toMillis(1) -> {
                getString(R.string.last_seen_just_now)
            }
            // Less than 1 hour ago
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
                resources.getQuantityString(R.plurals.last_seen_minutes_ago, minutes, minutes)
            }
            // Today
            isToday(timestamp) -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
                getString(R.string.last_seen_today_at, timeFormat.format(Date(timestamp)))
            }
            // Yesterday
            isYesterday(timestamp) -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
                getString(R.string.last_seen_yesterday_at, timeFormat.format(Date(timestamp)))
            }
            // This week (within 7 days)
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val dateTimeFormat = SimpleDateFormat("EEEE 'at' h:mm a", Locale.US)
                getString(R.string.last_seen_on, dateTimeFormat.format(Date(timestamp)))
            }
            // Older
            else -> {
                val dateTimeFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.US)
                getString(R.string.last_seen_on, dateTimeFormat.format(Date(timestamp)))
            }
        }
    }
    
    private fun isToday(timestamp: Long): Boolean {
        val timestampCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val todayCal = Calendar.getInstance()
        return timestampCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                timestampCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
    }
    
    private fun isYesterday(timestamp: Long): Boolean {
        val timestampCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return timestampCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                timestampCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    }
    
    private fun setupWindowInsets() {
        keyboardController.configureWindowChrome(window)

        // Capture the XML design-time bottom margin of the interactive map button once,
        // before any insets callback overwrites it.  On every insets pass we add navBars.bottom
        // on top of this value so the button always clears the 3-button / gesture nav bar.
        if (interactiveMapButtonBaseMarginPx < 0) {
            interactiveMapButtonBaseMarginPx = (binding.interactiveMapButton.layoutParams
                as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                ?.bottomMargin ?: dpToPx(90)
        }

        keyboardController.installInsetsHandlers(
            isPastelThemeProvider = {
                com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this) ==
                    com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY
            },
            topSafeAreaConsumer = { inset -> walkieTalkieTopSafeAreaInsetPx = inset },
            bottomSafeAreaConsumer = { inset -> walkieTalkieBottomSafeAreaInsetPx = inset },
            dispatchInsetsToDynamicOverlays = { windowInsets ->
                ViewCompat.onApplyWindowInsets(binding.composeHeader, windowInsets)
                ViewCompat.onApplyWindowInsets(binding.composeInput, windowInsets)
                ViewCompat.onApplyWindowInsets(binding.voiceRecorderStub, windowInsets)
                binding.root.findViewWithTag<android.view.View>("AiComposerOverlay")?.let {
                    ViewCompat.onApplyWindowInsets(it, windowInsets)
                }
            },
            updatePastelToolbarPadding = { topPadding ->
                if (binding.toolbarContainer.paddingTop != topPadding) {
                    binding.toolbarContainer.setPadding(0, topPadding, 0, 0)
                }
            }
        )
    }
    
    private var connectionRetryJob: Job? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FORCE_SCROLL_TO_BOTTOM, false)) {
            userHasScrolledUp = false
            pendingScrollToBottomOnNextListCommit = true
            intent.removeExtra(EXTRA_FORCE_SCROLL_TO_BOTTOM)
            binding.recyclerViewMessages.post { requestScrollToBottomAfterNextPreDraw() }
        }
        // Handle "Yes" from camera-invite notification when this activity is already running.
        // With FLAG_ACTIVITY_SINGLE_TOP the OS calls onNewIntent instead of creating a new
        // instance. If the activity is already resumed, onResume won't fire again, so we
        // must handle the extra here directly and clear it so onResume doesn't double-trigger.
        if (intent.getBooleanExtra(EXTRA_ENABLE_CAMERA, false)) {
            intent.removeExtra(EXTRA_ENABLE_CAMERA)
            val cId = chatId ?: ""
            val senderUid = this.intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: otherUserId ?: ""
            val reqId = intent.getStringExtra(EXTRA_CAMERA_REQUEST_ID) ?: ""
            intent.removeExtra(EXTRA_CAMERA_REQUEST_ID)
            // Dismiss notification
            if (cId.isNotEmpty()) {
                val notifTag = "camera_invite_$cId"
                (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                    ?.cancel(notifTag, notifTag.hashCode())
            }
            // Write RTDB acceptance
            if (cId.isNotEmpty() && senderUid.isNotEmpty() && reqId.isNotEmpty()) {
                val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val myName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    ?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
                if (myUid.isNotEmpty()) {
                    com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
                        .sendCameraInviteAcceptedCommand(
                            chatId = cId,
                            targetUserId = senderUid,
                            requestId = reqId,
                            responderUserId = myUid,
                            responderName = myName
                        )
                }
            }
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    val mapMgr = ensureMapBackgroundManagerInitialized(reason = "notification_camera_accept")
                    if (mapMgr != null && !mapMgr.isMapEnabled && mapMgr.hasLocationPermission) {
                        mapMgr.enableMapBackground()
                        setupMapComposeViews()
                        binding.ivChatWallpaper.animate().alpha(0f).setDuration(400).start()
                    }
                    enableMapVideoModeWithPermissions(sendInviteToOtherUser = false)
                }
            }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        // If re-launched via notification "Yes" while already open, enable camera now.
        if (intent.getBooleanExtra(EXTRA_ENABLE_CAMERA, false)) {
            intent.removeExtra(EXTRA_ENABLE_CAMERA)
            val cId = chatId ?: ""
            val senderUid = otherUserId ?: ""
            val reqId = intent.getStringExtra(EXTRA_CAMERA_REQUEST_ID) ?: ""
            intent.removeExtra(EXTRA_CAMERA_REQUEST_ID)
            // Dismiss the camera invite notification
            if (cId.isNotEmpty()) {
                val notifTag = "camera_invite_$cId"
                (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                    ?.cancel(notifTag, notifTag.hashCode())
            }
            // Write RTDB acceptance so the inviter's device receives the response
            if (cId.isNotEmpty() && senderUid.isNotEmpty() && reqId.isNotEmpty()) {
                val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val myName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    ?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
                if (myUid.isNotEmpty()) {
                    com.glyph.glyph_v3.ui.chat.map.video.MapVideoSignalingRepository
                        .sendCameraInviteAcceptedCommand(
                            chatId = cId,
                            targetUserId = senderUid,
                            requestId = reqId,
                            responderUserId = myUid,
                            responderName = myName
                        )
                }
            }
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    val mapMgr = ensureMapBackgroundManagerInitialized(reason = "notification_camera_accept")
                    if (mapMgr != null && !mapMgr.isMapEnabled && mapMgr.hasLocationPermission) {
                        mapMgr.enableMapBackground()
                        setupMapComposeViews()
                        binding.ivChatWallpaper.animate().alpha(0f).setDuration(400).start()
                    }
                    enableMapVideoModeWithPermissions(sendInviteToOtherUser = false)
                }
            }, 400)
        }
        resumeIdleTaskQueue(reason = "activity_resume")
        maybeScheduleInitialLastSeenReveal(_reason = "resume")
        resumeStalledUploads()

        // CRITICAL ORDER: Set activeChatId in PresenceManager BEFORE calling goOnline().
        //
        // goOnline() snapshots activeChatId synchronously and embeds it in the single
        // Firebase updateChildren() write.  If we called goOnline() first (old order),
        // activeChatId was still null at snapshot time, so the write included
        // "viewingChat = null", erasing whatever setViewingChat() had just written —
        // that was the root cause of the "In Chat" flickering bug.
        chatId?.let { cId ->
            com.glyph.glyph_v3.data.service.ActiveChatManager.setActiveChat(cId, this)

            // Sets activeChatId = cId inside PresenceManager so that the goOnline() call
            // below includes it atomically. Also issues its own write as a fast path in
            // case the connection is already established before goOnline() resolves.
            PresenceManager.setViewingChat(cId)

            // Immediately mark chat as read to ensure unread counts are cleared
            // and read receipts are sent, even if messages haven't loaded in UI yet.
            repository.markChatAsRead(cId)
        }

        // Defer Firebase transport reconnect to the next frame so keepSynced()/goOnline()
        // SDK calls do not consume main-thread time during the enter animation.
        // ORDERING is preserved: setViewingChat() above runs BEFORE goOnline() below
        // because both are now in the same post {} block, executing in order.
        // One-frame delay (~16 ms) is imperceptible for presence but eliminates the
        // synchronous PresenceManager/WalkieTalkieRepository keepSynced overhead that was
        // competing with the slide-in animation for the frame budget.
        binding.root.post {
            if (isFinishing || isDestroyed) return@post

            // Go online AFTER setViewingChat() has set activeChatId, so the atomic write
            // in goOnline() correctly includes the current viewingChat.
            PresenceManager.primeTransport("chat_activity_resume", forceTokenRefresh = true)
            PresenceManager.goOnline()
            repository.primeRealtimeTransportForForeground("chat_activity_resume")
            walkieTalkieManager.primeTransport("chat_activity_resume", forceTokenRefresh = true)

            // COLD-START FIX: If the Firebase RTDB connection is not yet established
            // (common on cold start), retry goOnline() once when the connection becomes
            // available. Without this, the initial goOnline() call silently queues the
            // write but the user appears offline until the connection arrives and the
            // queued write completes — which can take 5-15 seconds.
            connectionRetryJob?.cancel()
            if (!PresenceManager.isConnected.value) {
                connectionRetryJob = lifecycleScope.launch {
                    // Wait for RTDB connection to be established (with timeout)
                    withTimeoutOrNull(15_000L) {
                        PresenceManager.isConnected.first { it }
                    }?.let {
                        // Connection is now live — retry goOnline() to ensure presence
                        // is written immediately via the now-active connection.
                        PresenceManager.goOnline()
                    }
                }
            }
        }

        BuzzManager.setUiListener(this)

        // Refresh wallpaper so changes apply immediately after returning from Settings.
        applyChatWallpaperIfNeeded(force = false)

        // Resume expressive typing observation when returning to chat
        startExpressiveObservingSession()

        // Resume map background location observation
        if (shouldRestoreMapBackgroundOnOpen()) {
            ensureMapBackgroundManagerInitialized(reason = "resume_restore")
        }
        mapBackgroundManager?.onResume()
        refreshVideoSessionManagerBinding()
        if (!isUsingSharedMapVideoManager) {
            mapVideoSessionManager?.onResume()
        }

        // Safety: if the "Stop Navigation" button was pressed while the app was in the
        // background, the prefs will have been cleared but the in-memory RoutingManager
        // state will still say isNavigating=true. Re-align them here before potentially
        // re-posting the notification.
        run {
            val mgr = mapBackgroundManager
            if (mgr != null && mgr.routingState.isNavigating) {
                val prefs = com.glyph.glyph_v3.ui.chat.map.MapBackgroundPreferences.getInstance(this)
                if (!prefs.isNavigationEnabled(mgr.chatId)) {
                    mgr.stopNavigation()
                }
            }
        }

        // Register local receiver so that a "Stop Navigation" notification tap immediately
        // propagates to the in-app RoutingManager while this Activity is in the foreground.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            stopNavigationLocalReceiver,
            android.content.IntentFilter(
                com.glyph.glyph_v3.ui.chat.map.routing.RoutingNotificationManager.ACTION_STOP_NAVIGATION_LOCAL
            ),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Register receiver for "No" notification button → clears in-map camera invite prompt.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cameraInviteDismissReceiver,
            android.content.IntentFilter(
                com.glyph.glyph_v3.data.service.NotificationActionReceiver.ACTION_DISMISS_CAMERA_INVITE_LOCAL
            ),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // If navigation was restored, show the notification
        if (mapBackgroundManager?.routingState?.isNavigating == true) {
            showNavigationNotification(mapBackgroundManager!!.routingState)
        }

        // Listen for GPS provider changes so we can stop live sharing if the user turns off location
        registerReceiver(
            locationProviderChangedReceiver,
            android.content.IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        )

        // Auto-proceed with live location if user just returned from Location Settings with GPS on
        if (pendingLiveLocationAfterGps) {
            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val isGpsEnabled =
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (isGpsEnabled) {
                pendingLiveLocationAfterGps = false
                showLiveSharingDurationPicker()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::binding.isInitialized && isInteractiveQuickReplyOverlayActive) {
            val scrollFab = binding.fabScrollToBottom
            if (scrollFab.visibility == View.VISIBLE && isPointInsideView(scrollFab, ev.rawX, ev.rawY)) {
                quickReplyOverlayGestureRoutedToRecycler = false
                return super.dispatchTouchEvent(ev)
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    quickReplyOverlayGestureRoutedToRecycler = isPointInsideQuickReplyOverlay(ev.rawX, ev.rawY)
                    if (quickReplyOverlayGestureRoutedToRecycler) {
                        val recycler = binding.recyclerViewMessages
                        if (recycler.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                            recycler.stopScroll()
                        }
                        requestParentInterceptDisallow(recycler, true)
                        dispatchQuickReplyTouchToRecycler(ev)
                        return true
                    }
                }

                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    if (quickReplyOverlayGestureRoutedToRecycler) {
                        requestParentInterceptDisallow(binding.recyclerViewMessages, true)
                        dispatchQuickReplyTouchToRecycler(ev)
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (quickReplyOverlayGestureRoutedToRecycler) {
                        dispatchQuickReplyTouchToRecycler(ev)
                        requestParentInterceptDisallow(binding.recyclerViewMessages, false)
                        quickReplyOverlayGestureRoutedToRecycler = false
                        return true
                    }
                    quickReplyOverlayGestureRoutedToRecycler = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Called by the system when it needs to reclaim memory. Glide automatically evicts its
     * LruBitmapPool and LruResourceCache on TRIM_MEMORY_UI_HIDDEN (level 20), which is sent
     * when ALL of the app's UI has gone into the background (long idle).
     *
     * Without this hook, the next chat open after idle would trigger fresh disk-IO decodes
     * for every image and the wallpaper, causing a stutter burst. We proactively re-warm
     * the wallpaper bitmap on a background thread so it is already decoded by the time
     * onResume runs and applyChatWallpaperIfNeeded finds a cached drawable.
     *
     * Using the latest Android ComponentCallbacks2 constant (TRIM_MEMORY_UI_HIDDEN = 20).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Re-warm wallpaper bitmap off the main thread so the next onResume can apply it
            // from the in-memory cache instead of waiting for a Glide disk decode.
            com.glyph.glyph_v3.utils.ChatWallpaperManager.warmCurrentWallpaperAsync(applicationContext)
            // After TRIM_MEMORY_UI_HIDDEN Android clears Glide's LruResourceCache and
            // LruBitmapPool. Re-queue avatar decodes now so the bitmaps are back in memory
            // by the time the user returns and the first ViewHolder bind tries to load them.
            // This prevents the placeholder→avatar flash that occurs on long-idle reopens.
            val appCtx = applicationContext
            val sizePx = chatAvatarRequestSizePx()
            val peerAvatar = otherUserAvatar
            if (peerAvatar.isNotBlank()) {
                val model: Any = java.io.File(peerAvatar).takeIf { it.exists() && it.length() > 0 } ?: peerAvatar
                var req = Glide.with(appCtx)
                    .load(model)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop()
                    .override(sizePx, sizePx)
                if (model is java.io.File) {
                    req = req.signature(com.bumptech.glide.signature.ObjectKey(model.lastModified()))
                }
                req.preload(sizePx, sizePx)
            }
            val selfAvatar = currentUserAvatar
            if (selfAvatar.isNotBlank()) {
                val model: Any = java.io.File(selfAvatar).takeIf { it.exists() && it.length() > 0 } ?: selfAvatar
                var req = Glide.with(appCtx)
                    .load(model)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop()
                    .override(sizePx, sizePx)
                if (model is java.io.File) {
                    req = req.signature(com.bumptech.glide.signature.ObjectKey(model.lastModified()))
                }
                req.preload(sizePx, sizePx)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        finishScrollPerfTrackingIfNeeded(
            reason = "pause",
            layoutManager = if (::binding.isInitialized) binding.recyclerViewMessages.layoutManager as? LinearLayoutManager else null
        )
        pauseIdleTaskQueue(reason = "activity_pause")
        cancelPendingScrollIdleMediaUpgrade()
        startupLastSeenRevealJob?.cancel()

        connectionRetryJob?.cancel()
        connectionRetryJob = null
        persistDraftNow()

        BuzzManager.setUiListener(null)

        // Clear active chat when leaving
        com.glyph.glyph_v3.data.service.ActiveChatManager.clearActiveChat()
        
        // CRITICAL: User is no longer viewing any specific chat
        // But they're still in the app (presence will still show as online)
        PresenceManager.setViewingChat(null)

        // Pause expressive typing when leaving chat
        expressiveTypingManager?.stopObserving()

        // Pause map background location observations
        mapBackgroundManager?.onPause()
        if (!isUsingSharedMapVideoManager) {
            mapVideoSessionManager?.onPause()
        }

        // Unregister the local stop-navigation receiver — no longer needed when paused
        try { unregisterReceiver(stopNavigationLocalReceiver) } catch (_: IllegalArgumentException) {}
        try { unregisterReceiver(cameraInviteDismissReceiver) } catch (_: IllegalArgumentException) {}

        // Stop listening for GPS changes when the activity is not in the foreground
        try { unregisterReceiver(locationProviderChangedReceiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }
    
    override fun onDestroy() {
        finishScrollPerfTrackingIfNeeded(
            reason = "destroy",
            layoutManager = if (::binding.isInitialized) binding.recyclerViewMessages.layoutManager as? LinearLayoutManager else null
        )
        if (::mediaController.isInitialized) {
            mediaController.clearRetainedMediaPreloadFutures()
        }
        clearRetainedHeaderAvatarPreloadFutures()
        chatWallpaperTarget?.let {
            // Avoid Glide.with(Activity) during teardown; it can throw when the Activity is already destroyed.
            runCatching { Glide.with(applicationContext).clear(it) }
        }
        chatWallpaperTarget = null

        presenceJob?.cancel()
        groupPresenceJob?.cancel()
        privacySettingsJob?.cancel()
        avatarVisibilityJob?.cancel()
        typingJob?.cancel()
        avatarSyncJob?.cancel()
        sttJob?.cancel()
        lastSeenRefreshJob?.cancel()
        connectionRetryJob?.cancel()
        blockStatusJob?.cancel()
        pinnedBannerObserverJob?.cancel()
        startupLastSeenRevealJob?.cancel()
        messageSyncListener?.remove()
        readReceiptListener?.remove()
        draftSaveJob?.cancel()
        deferredFeatureWarmupJob?.cancel()
        deferredOffscreenPreviewWarmupJob?.cancel()
        deferredMediaHeightWarmupJob?.cancel()
        cancelPendingScrollIdleMediaUpgrade()
        idleTaskRunnerJob?.cancel()
        idleTaskRunnerJob = null
        idleQueuePaused = true
        runBlocking {
            idleTaskMutex.withLock {
                idleTaskQueue.clear()
            }
        }
        
        if (::keyboardHeightProvider.isInitialized) {
            keyboardHeightProvider.stop()
        }
        
        // CRITICAL: Do NOT call PresenceManager.cleanup() here!
        // cleanup() should ONLY be called on logout, not when leaving a chat.
        // The app lifecycle (GlyphApplication) handles goOnline/goOffline.
        
        com.glyph.glyph_v3.data.service.ActiveChatManager.clearActiveChat()

        // Release translation resources
        translationToolbar?.dismiss(force = true)
        translationToolbar = null
        if (::translationManager.isInitialized) {
            translationManager.release()
        }

        // Release AI composer resources
        if (::aiComposerManager.isInitialized) {
            aiComposerManager.release()
        }

        // Release expressive typing resources
        expressiveInputLayoutChangeListener?.let { binding.layoutInput.removeOnLayoutChangeListener(it) }
        expressiveInputLayoutChangeListener = null
        expressiveTypingViewController?.unbind()
        expressiveTypingViewController = null
        expressiveTypingManager?.destroy()
        expressiveTypingManager = null

        // Release map background resources
        mapBackgroundManager?.onDestroy()
        mapBackgroundManager = null
        if (!isUsingSharedMapVideoManager) {
            mapVideoSessionManager?.onDestroy()
        }
        mapVideoSessionManager = null
        isUsingSharedMapVideoManager = false

        // Walkie-Talkie cleanup
        walkieTalkieStateJob?.cancel()
        walkieTalkieFloorDeniedJob?.cancel()

        // Clean up any pending voice file
        pendingVoiceFile?.delete()
        pendingVoiceFile = null
        if (::audioPlayer.isInitialized) {
            audioPlayer.stop(reset = true)
        }

        super.onDestroy()

        // Release after super.onDestroy so lifecycleScope coroutines are cancelled first.
        isSoundPoolReleased = true
        if (::soundPool.isInitialized) {
            soundPool.release()
        }
    }

    // =========================================================================================
    // VOICE MESSAGE INTEGRATION
    // =========================================================================================

    private fun setupVoiceFeatures() {
        recorderState = com.glyph.glyph_v3.ui.chat.state.RecorderState()
        
        // Setup Overlay
        binding.voiceRecorderStub.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                val isRecording by recorderState.isRecording
                
                // Recording timer and amplitude pulse
                LaunchedEffect(isRecording) {
                    if (isRecording) {
                        val startTime = System.currentTimeMillis()
                        while (recorderState.isRecording.value) {
                            val elapsed = System.currentTimeMillis() - startTime
                            val seconds = (elapsed / 1000) % 60
                            val minutes = (elapsed / 1000) / 60
                            recorderState.updateDuration(String.format("%d:%02d", minutes, seconds))
                            recorderState.updateAmplitude(
                                if (::audioRecorder.isInitialized) audioRecorder.getAmplitude() else 0
                            )
                            delay(100)
                        }
                    }
                }

                com.glyph.glyph_v3.ui.chat.overlay.VoiceRecorderOverlay(
                    recorderState = recorderState,
                    onSend = { stopRecordingAndShowOptions() },
                    onCancel = { 
                        val file = recorderState.recordingFile.value
                        recorderState.cancelRecording()
                        if (::audioRecorder.isInitialized) {
                            audioRecorder.cancel()
                        }
                        file?.delete()
                    }
                )

                // Voice Recording Options Sheet (Send Voice / Convert to Text)
                val currentOptionState by voiceOptionState
                VoiceRecordingOptionsSheet(
                    state = currentOptionState,
                    onSendVoiceNote = { sendPendingVoiceNote() },
                    onConvertToText = { startSpeechToText() },
                    onLanguageChanged = { lang -> handleSttLanguageChanged(lang) },
                    onRetranslate = { lang -> retranslateWithLanguage(lang) },
                    onUseTranslatedText = { text -> insertTextAndDismissOptions(text) },
                    onDismiss = { dismissVoiceOptions(deleteFile = true) }
                )
            }
        }

        // Keep the voice UI shell mounted, but defer recorder/player creation until first use.
        updatePlaybackState(isPlaying = false, progress = 0f)
        if (::chatAdapter.isInitialized) {
            chatAdapter.updateAudioState(audioPlaybackState.value)
        }

        // Setup Touch Listener for Recording
        var initialDownX = 0f
        var initialDownY = 0f

        binding.btnSend.setOnTouchListener { v, event ->
            if (isSendState) return@setOnTouchListener false 
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                         recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                         return@setOnTouchListener true
                    }

                    recordingStartTime = System.currentTimeMillis()
                    initialDownX = event.rawX
                    initialDownY = event.rawY
                    startRecording()
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                        val diffX = event.rawX - initialDownX
                        val diffY = event.rawY - initialDownY
                        // Only allow negative offsets (left and up) to match UI
                        recorderState.updateOffsets(diffX.coerceAtMost(0f), diffY.coerceAtMost(0f))
                    }
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                         val duration = System.currentTimeMillis() - recordingStartTime
                         val offX = recorderState.offsetX.value
                         val offY = recorderState.offsetY.value
                         
                         // Thresholds matching VoiceRecorderOverlay.kt
                         if (offX < -180f) { // Slide to Cancel
                             val file = recorderState.recordingFile.value
                             recorderState.cancelRecording()
                             if (::audioRecorder.isInitialized) {
                                 audioRecorder.cancel()
                             }
                             file?.delete()
                             v.performHapticFeedback(HapticFeedbackConstants.REJECT)
                         } else if (offY < -140f) { // Slide to Lock
                             recorderState.lockRecording()
                             v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                         } else if (duration < 500) {
                             // Tap-to-record: Lock recording
                             recorderState.lockRecording()
                         } else {
                             // Press-and-hold: Stop and Show Options
                             stopRecordingAndShowOptions()
                         }
                    }
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_CANCEL -> {
                     if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                         val file = recorderState.recordingFile.value
                         recorderState.cancelRecording()
                         if (::audioRecorder.isInitialized) {
                             audioRecorder.cancel()
                         }
                         file?.delete()
                     }
                     return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun ensureVoiceRuntimeInitialized(reason: String) {
        if (voiceRuntimeInitialized) return
        voiceRuntimeInitialized = true

        audioRecorder = com.glyph.glyph_v3.util.AudioRecorder(this)
        audioPlayer = com.glyph.glyph_v3.util.AudioPlayer(this)

        lifecycleScope.launch {
            audioPlayer.isPlaying.collect { isPlaying ->
                updatePlaybackState(isPlaying = isPlaying)
            }
        }

        lifecycleScope.launch {
            audioPlayer.progress.collect { progress ->
                updatePlaybackState(progress = progress)
            }
        }

        lifecycleScope.launch {
            audioPlayer.completionEvents.collect { uri ->
                val completedId = handleAudioCompletion(uri)
                if (playingMessageId == completedId) {
                    playingMessageId = null
                    updatePlaybackState(isPlaying = false, progress = 0f)
                }
            }
        }
    }
    
    private var playingMessageId: String? = null

    private fun startRecording() {
         if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
             recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
             return
         }

         ensureVoiceRuntimeInitialized(reason = "recording_start")

         val cacheDir = externalCacheDir ?: cacheDir
         val file = java.io.File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
         
         if (audioRecorder.start(file)) {
             recorderState.startRecording(file)
             binding.btnSend.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
         } else {
             Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
         }
    }

    private fun stopRecordingAndShowOptions() {
        if (!::audioRecorder.isInitialized) return
        val file = recorderState.stopRecording()
        val durationMs = audioRecorder.stop()
        
        if (file != null && file.exists()) {
             if (durationMs < 1000) {
                 Toast.makeText(this, "Message too short", Toast.LENGTH_SHORT).show()
                 file.delete()
                 return
             }
             
             // Store pending voice file and show options
             pendingVoiceFile = file
             pendingVoiceDurationMs = durationMs
             
             val seconds = (durationMs / 1000) % 60
             val minutes = (durationMs / 1000) / 60
             val durationText = String.format("%d:%02d", minutes, seconds)
             
             // Initialize STT language from translation preferences
             selectedSttLanguage.value = translationPrefs.targetLanguage
             
             voiceOptionState.value = VoiceOptionState.ShowingOptions(
                 durationText = durationText,
                 selectedLanguage = selectedSttLanguage.value
             )
        } else {
             file?.delete()
        }
    }

    /**
     * Send the pending voice file as a voice note (original behavior).
     * Called when user selects "Send as Voice Note" from the options sheet.
     */
    private fun sendPendingVoiceNote() {
        if (isBlockedForSending()) return
        val file = pendingVoiceFile
        val durationMs = pendingVoiceDurationMs
        
        voiceOptionState.value = VoiceOptionState.Hidden
        
        if (file != null && file.exists()) {
             lifecycleScope.launch(Dispatchers.IO) {
                 try {
                     val id = chatId ?: return@launch
                     if (isGroupChat) {
                         groupRepository.sendGroupVoiceMessage(
                             chatId = id,
                             voiceFile = file,
                             duration = durationMs
                         )
                     } else {
                         repository.sendVoiceMessage(
                             chatId = id,
                             voiceFile = file,
                             duration = durationMs,
                             otherUserId = otherUserId ?: "",
                             otherUsername = otherUsername,
                             otherUserAvatar = otherUserAvatar
                         )
                     }
                     
                     withContext(Dispatchers.Main) {
                        if (replyToMessage != null) cancelReply()
                     }
                 } catch (e: Exception) {
                     Log.e("ChatActivity", "Failed to send voice", e)
                 }
             }
        }
        
        pendingVoiceFile = null
        pendingVoiceDurationMs = 0L
    }

    /**
     * Start speech-to-text conversion on the pending voice file.
     * Called when user selects "Convert to Text & Translate" from the options sheet.
     */
    private fun startSpeechToText() {
        val file = pendingVoiceFile
        if (file == null || !file.exists()) {
            voiceOptionState.value = VoiceOptionState.Error("Recording file not found.")
            return
        }
        
        voiceOptionState.value = VoiceOptionState.Processing
        
        // Get target language from the selected STT language
        val targetLang = selectedSttLanguage.value.code
        
        sttJob?.cancel()
        sttJob = lifecycleScope.launch {
            try {
                val result = speechToTextRepository.recognizeAndTranslate(
                    audioFile = file,
                    targetLanguage = targetLang
                )
                
                if (result.recognizedText.isNullOrBlank()) {
                    voiceOptionState.value = VoiceOptionState.Error("No speech detected in the recording.")
                } else {
                    voiceOptionState.value = VoiceOptionState.ResultPreview(
                        recognizedText = result.recognizedText,
                        translatedText = result.translatedText,
                        targetLanguage = selectedSttLanguage.value.displayName,
                        selectedLanguage = selectedSttLanguage.value
                    )
                }
            } catch (e: SpeechToTextRepository.SpeechToTextError) {
                Log.e("ChatActivity", "STT error: ${e.message}", e)
                voiceOptionState.value = VoiceOptionState.Error(e.message ?: "Speech recognition failed.")
            } catch (e: Exception) {
                Log.e("ChatActivity", "STT unexpected error", e)
                voiceOptionState.value = VoiceOptionState.Error("An unexpected error occurred. Please try again.")
            }
        }
    }

    /**
     * Handle language change from options sheet (before STT is run).
     * Updates the selected language and persists to preferences.
     */
    private fun handleSttLanguageChanged(language: com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage) {
        selectedSttLanguage.value = language
        // Persist to preferences so it's remembered
        translationPrefs.targetLanguageCode = language.code
        translationPrefs.addRecentLanguage(language.code)
        
        // Update the ShowingOptions state with the new language
        val currentState = voiceOptionState.value
        if (currentState is VoiceOptionState.ShowingOptions) {
            voiceOptionState.value = currentState.copy(selectedLanguage = language)
        }
    }

    /**
     * Re-translate already recognized text with a different language.
     * Called from ResultPreview when user changes language.
     */
    private fun retranslateWithLanguage(language: com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage) {
        selectedSttLanguage.value = language
        translationPrefs.targetLanguageCode = language.code
        translationPrefs.addRecentLanguage(language.code)
        
        // Get the recognized text from current state
        val currentState = voiceOptionState.value
        val recognizedText = if (currentState is VoiceOptionState.ResultPreview) {
            currentState.recognizedText
        } else null
        
        if (recognizedText != null) {
            // Re-translate just the text (no need to re-do STT)
            voiceOptionState.value = VoiceOptionState.Processing
            
            sttJob?.cancel()
            sttJob = lifecycleScope.launch {
                try {
                    // Use the translation repository to translate the already-recognized text
                    val translationRepo = com.glyph.glyph_v3.data.repo.TranslationRepository.getInstance(this@ChatActivity)
                    val result = translationRepo.translate(
                        messageId = "stt_retranslate_${System.currentTimeMillis()}",
                        text = recognizedText,
                        targetLanguage = language.code
                    )
                    
                    voiceOptionState.value = VoiceOptionState.ResultPreview(
                        recognizedText = recognizedText,
                        translatedText = result.translatedText,
                        targetLanguage = language.displayName,
                        selectedLanguage = language
                    )
                } catch (e: Exception) {
                    Log.e("ChatActivity", "Re-translation error", e)
                    // Show result with just the original text
                    voiceOptionState.value = VoiceOptionState.ResultPreview(
                        recognizedText = recognizedText,
                        translatedText = null,
                        targetLanguage = language.displayName,
                        selectedLanguage = language
                    )
                    Toast.makeText(this@ChatActivity, "Translation failed: ${e.message?.take(60)}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // No recognized text yet, just restart STT with new language
            startSpeechToText()
        }
    }

    /**
     * Insert recognized/translated text into the message input field.
     * Called when user selects "Use Original" or "Use Translation" from result preview.
     */
    private fun insertTextAndDismissOptions(text: String) {
        voiceOptionState.value = VoiceOptionState.Hidden
        
        // Insert text into the message input
        binding.etMessageInput.setText(text)
        binding.etMessageInput.setSelection(text.length)
        binding.etMessageInput.requestFocus()
        
        // Show keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        
        // Clean up voice file (no longer needed)
        pendingVoiceFile?.delete()
        pendingVoiceFile = null
        pendingVoiceDurationMs = 0L
    }

    /**
     * Dismiss the voice options sheet and optionally delete the pending file.
     */
    private fun dismissVoiceOptions(deleteFile: Boolean = false) {
        sttJob?.cancel()
        voiceOptionState.value = VoiceOptionState.Hidden
        
        if (deleteFile) {
            pendingVoiceFile?.delete()
        }
        pendingVoiceFile = null
        pendingVoiceDurationMs = 0L
    }
    
    private fun updatePlaybackState(isPlaying: Boolean? = null, progress: Float? = null) {
        // Allow state creation even if not playing, to expose Play/Pause accessors
        val currentId = playingMessageId
        val resolvedIsPlaying = isPlaying ?: if (::audioPlayer.isInitialized) audioPlayer.isPlaying.value else false
        val resolvedProgress = progress ?: if (::audioPlayer.isInitialized) audioPlayer.progress.value else 0f

        val newState = AudioPlaybackState(
            isPlaying = resolvedIsPlaying,
            progress = resolvedProgress,
            playingMessageId = currentId,
            audioDuration = null,
            onPlay = { msg -> playVoiceMessage(msg) },
            onPause = { if (::audioPlayer.isInitialized) audioPlayer.pause() },
            onSeek = { msg, pos -> 
                 if (playingMessageId == msg.id && ::audioPlayer.isInitialized) {
                     val duration = msg.audioDuration
                     audioPlayer.seekTo((pos * duration).toInt())
                 } else {
                     // Message is not currently active in player, but user is scrubbing it
                     // We should update the UI state so the progress bar moves immediately
                     val current = audioPlaybackState.value
                     if (current?.playingMessageId == msg.id) {
                         audioPlaybackState.value = current.copy(progress = pos)
                     }
                 }
            }
        )
        audioPlaybackState.value = newState
        if (::chatAdapter.isInitialized) {
            chatAdapter.updateAudioState(newState)
        }
    }

    private fun handleAudioCompletion(uri: android.net.Uri?): String? {
        if (uri == null) return null
        val currentCId = chatId ?: return null
        val uriString = uri.toString()
        val uriPath = uri.path // Extracts /storage/... from file:///storage/...

        // Scan current list to find the matching message
        val currentList = chatAdapter.currentList
        // Find message where localUri matches (robust check against file:// scheme)
        val messageItem = currentList.asSequence()
            .filterIsInstance<com.glyph.glyph_v3.ui.chat.ChatListItem.MessageItem>()
            .find { item -> 
                val msg = item.message
                if (msg.type != com.glyph.glyph_v3.data.models.MessageType.AUDIO) return@find false
                
                // Normal exact match
                if (msg.localUri == uriString || msg.audioUrl == uriString) return@find true
                
                // Path match (ignores file:// prefix)
                if (msg.localUri != null && uriPath != null && msg.localUri!!.endsWith(uriPath)) return@find true
                
                // Fallback: Check if uriString contains the local path (common issue)
                if (msg.localUri != null && uriString.contains(msg.localUri!!)) return@find true
                
                false
            }
            
        if (messageItem != null) {
            val msg = messageItem.message
            // If it's an INCOMING message (from other user) and not yet marked as played
            if (msg.isIncoming && msg.status != com.glyph.glyph_v3.data.models.MessageStatus.PLAYED) {
                 repository.markVoiceMessageAsPlayed(currentCId, msg.id)
            }
            return msg.id
        }
        return null
    }

    private fun resolveVoicePlaybackUri(message: Message): Uri? {
        val candidates = linkedSetOf<String>()

        getPlaybackUri(message)?.takeIf { it.isNotBlank() }?.let(candidates::add)

        chatId
            ?.let { currentChatId -> mediaTransferManager.getLocalFilePath(currentChatId, message.id, MessageType.AUDIO) }
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)

        message.localUri?.takeIf { it.isNotBlank() }?.let(candidates::add)
        message.audioUrl?.takeIf { it.isNotBlank() }?.let(candidates::add)

        return candidates.firstNotNullOfOrNull(::toPlayableAudioUri)
    }

    private fun toPlayableAudioUri(candidate: String): Uri? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null

        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
        return when (parsed?.scheme?.lowercase(Locale.US)) {
            "http", "https", "content", "android.resource" -> parsed
            "file" -> {
                val file = parsed.path?.let(::File) ?: return null
                if (file.exists() && file.length() > 0L) Uri.fromFile(file) else null
            }
            else -> {
                val file = File(trimmed)
                if (file.exists() && file.length() > 0L) Uri.fromFile(file) else null
            }
        }
    }

    private fun playVoiceMessage(message: Message) {
         ensureVoiceRuntimeInitialized(reason = "playback_start")
         if (playingMessageId == message.id && audioPlayer.isPlayerInitialized) {
             if (audioPlayer.isPlaying.value) {
                 audioPlayer.pause()
             } else {
                 audioPlayer.resume() 
             }
             return
        }

        pendingVoicePlaybackRequestId = message.id
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = resolveVoicePlaybackUri(message)

            withContext(Dispatchers.Main) {
                if (pendingVoicePlaybackRequestId != message.id) {
                    return@withContext
                }

                if (uri == null) {
                    pendingVoicePlaybackRequestId = null
                    val unavailableMessage = when (message.status) {
                        MessageStatus.PENDING_DOWNLOAD, MessageStatus.DOWNLOADING -> "Voice note is still downloading"
                        MessageStatus.DOWNLOAD_FAILED -> "Voice note download failed. Tap download and try again"
                        else -> "Voice note is not available for playback"
                    }
                    Toast.makeText(this@ChatActivity, unavailableMessage, Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                pendingVoicePlaybackRequestId = null
                val oldPlayingId = playingMessageId
                playingMessageId = message.id
                if (oldPlayingId != message.id) {
                    updatePlaybackState(isPlaying = false, progress = 0f)
                }
                audioPlayer.play(uri)
            }
        }
    }

    private fun setupSelectionToolbar() {
        selectionManager.selectedIds.observe(this) { selectedIds ->
            updateSelectionToolbar(selectedIds)
            // Keep the WhatsApp-style reaction bar in lock-step with the selection: visible
            // for exactly one selected message, dismissed for multi-select or no selection.
            reconcileReactionPopup(selectedIds)
        }

        selectionToolbarBinding.btnSelectionBack.setOnClickListener {
            selectionManager.clearSelection()
        }

        selectionToolbarBinding.btnEdit.setOnClickListener {
            val selectedIds = selectionManager.getSelectedIds()
            val selectedMessage = getSelectedMessage(selectedIds)
            if (selectedMessage != null && isMessageEditable(selectedMessage)) {
                enterEditMode(selectedMessage)
                selectionManager.clearSelection()
            }
        }
        
        selectionToolbarBinding.btnDelete.setOnClickListener {
            val selectedIds = selectionManager.getSelectedIds()
            if (selectedIds.isNotEmpty()) {
                val selectedMessages = getSelectedMessages(selectedIds)
                val canDeleteForAll = selectedMessages.isNotEmpty() &&
                    selectedMessages.all { isEligibleForDeleteForAll(it) }

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete messages?")
                    .setMessage("Are you sure you want to delete ${selectedIds.size} messages?")
                    .setPositiveButton("Delete for me") { _, _ ->
                        lifecycleScope.launch {
                            chatId?.let { id ->
                                repository.deleteMessages(id, selectedIds.toList())
                                selectionManager.clearSelection()
                            }
                        }
                    }
                    .apply {
                        if (canDeleteForAll) {
                            setNeutralButton("Delete for everyone") { _, _ ->
                                lifecycleScope.launch {
                                    val id = chatId ?: return@launch
                                    val result = repository.deleteMessagesForAll(id, selectedIds.toList())
                                    if (!result.failureMessage.isNullOrBlank()) {
                                        Toast.makeText(this@ChatActivity, result.failureMessage, Toast.LENGTH_SHORT).show()
                                    } else if (result.rejected.isNotEmpty()) {
                                        val reasons = result.rejected.values.distinct().joinToString(", ")
                                        val msg = if (reasons.contains("expired")) {
                                            "Deletion failed: Message is too old (Server limit)."
                                        } else {
                                            "Deletion failed: $reasons"
                                        }
                                        Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_LONG).show()
                                    }
                                    selectionManager.clearSelection()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        selectionToolbarBinding.btnReply.setOnClickListener {
             val selectedIds = selectionManager.getSelectedIds()
             if (selectedIds.size == 1) {
                 // Focus input for reply
                 binding.etMessageInput.requestFocus()
                 val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                 imm.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                 Toast.makeText(this, "Replying...", Toast.LENGTH_SHORT).show()
             }
             selectionManager.clearSelection()
        }
        
        selectionToolbarBinding.btnForward.setOnClickListener {
             val selectedIds = selectionManager.getSelectedIds()
             val sourceChatId = chatId
             if (selectedIds.isEmpty() || sourceChatId.isNullOrBlank()) return@setOnClickListener
             startActivity(
                 ForwardSelectionActivity.createIntent(
                     this,
                     sourceChatId,
                     ArrayList(selectedIds)
                 )
             )
             selectionManager.clearSelection()
        }
        
        selectionToolbarBinding.btnStar.setOnClickListener {
             Toast.makeText(this, "Messages starred", Toast.LENGTH_SHORT).show()
             selectionManager.clearSelection()
        }
        
        selectionToolbarBinding.btnSelectionMenu.setOnClickListener {
            showSelectionMenu(it)
        }
    }

    private fun updateSelectionToolbar(selectedIds: Set<String>) {
        val isPastel = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this) == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY

        // All themes use the Compose header (composeHeader), which respects showContent =
        // !selectionModeState.value to hide its own icons during selection. Update for all themes.
        selectionModeState.value = selectedIds.isNotEmpty()

        if (selectedIds.isNotEmpty()) {
            if (selectionToolbarBinding.root.visibility == View.GONE) {
                // Apply pastel-specific tint and status-bar inset padding
                if (isPastel) {
                    val tint = PastelSkyThemeTokens.textPrimary.toArgb()
                    val tintList = ColorStateList.valueOf(tint)
                    selectionToolbarBinding.btnSelectionBack.imageTintList = tintList
                    selectionToolbarBinding.btnReply.imageTintList = tintList
                    selectionToolbarBinding.btnEdit.imageTintList = tintList
                    selectionToolbarBinding.btnStar.imageTintList = tintList
                    selectionToolbarBinding.btnDelete.imageTintList = tintList
                    selectionToolbarBinding.btnForward.imageTintList = tintList
                    selectionToolbarBinding.btnSelectionMenu.imageTintList = tintList
                    selectionToolbarBinding.tvSelectionCount.setTextColor(tint)

                    // Pastel AppBarLayout is transparent — selection toolbar must account for the
                    // status-bar inset itself since the AppBarLayout won't add it.
                    val topInset = ViewCompat.getRootWindowInsets(binding.root)
                        ?.getInsets(WindowInsetsCompat.Type.systemBars())
                        ?.top ?: 0
                    val typedValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
                    val actionBarHeight = TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)

                    // Preserve the 2dp end padding defined in the XML so the right-side icons
                    // stay aligned with the Compose header's end = 2.dp Row padding.
                    val endPx = (2f * resources.displayMetrics.density + 0.5f).toInt()
                    selectionToolbarBinding.root.setPadding(0, topInset, endPx, 0)
                    selectionToolbarBinding.root.layoutParams = selectionToolbarBinding.root.layoutParams.apply {
                        height = actionBarHeight + topInset
                    }
                }

                // Show selection toolbar overlay (instant — no deferred callbacks that can race)
                selectionToolbarBinding.root.animate().cancel()
                selectionToolbarBinding.root.alpha = 1f
                selectionToolbarBinding.root.visibility = View.VISIBLE
            }
            selectionToolbarBinding.tvSelectionCount.text = selectedIds.size.toString()

            val selectedMessage = if (selectedIds.size == 1) getSelectedMessage(selectedIds) else null
            val showEdit = selectedMessage != null && isMessageEditable(selectedMessage)
            selectionToolbarBinding.btnReply.visibility = if (selectedIds.size == 1) View.VISIBLE else View.GONE
            selectionToolbarBinding.btnEdit.visibility = if (showEdit) View.VISIBLE else View.GONE
            selectionToolbarBinding.btnStar.visibility = View.VISIBLE
        } else {
            if (selectionToolbarBinding.root.visibility == View.VISIBLE) {
                // Hide selection toolbar instantly (mirrors pastel behaviour — no deferred fade)
                selectionToolbarBinding.root.animate().cancel()
                selectionToolbarBinding.root.alpha = 0f
                selectionToolbarBinding.root.visibility = View.GONE

                // Reset pastel-specific padding so the toolbar is clean for next use
                if (isPastel) {
                    val typedValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
                    val actionBarHeight = TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
                    val endPx = (2f * resources.displayMetrics.density + 0.5f).toInt()
                    selectionToolbarBinding.root.setPadding(0, 0, endPx, 0)
                    selectionToolbarBinding.root.layoutParams = selectionToolbarBinding.root.layoutParams.apply {
                        height = actionBarHeight
                    }
                }
                // selectionModeState was already set to false above — the Compose header
                // will automatically restore its content (back arrow, name, call buttons, etc.)
            }
        }
    }
    
    private fun showSelectionMenu(view: View) {
        val popup = android.widget.PopupMenu(this, view)
        popup.menu.add("Message details")
        popup.menu.add("Copy")
        popup.menu.add("Pin")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Copy" -> {
                    val selectedIds = selectionManager.getSelectedIds()
                    if (selectedIds.isNotEmpty()) {
                        // We need to get the message text. Since we don't have direct access to message objects here easily without querying,
                        // we can iterate through the adapter's list or query the repo.
                        // For simplicity, let's query the repo or just iterate the adapter's current list.
                        val messages = chatAdapter.currentList
                            .filterIsInstance<ChatListItem.MessageItem>()
                            .filter { it.message.id in selectedIds }
                            .sortedBy { it.message.timestamp }
                            .joinToString("\n") { 
                                if (it.message.type == com.glyph.glyph_v3.data.models.MessageType.TEXT) it.message.text ?: "" else "[Media]" 
                            }
                        
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Copied Messages", messages)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Messages copied", Toast.LENGTH_SHORT).show()
                    }
                }
                "Message details" -> {
                    val selectedIds = selectionManager.getSelectedIds()
                    if (selectedIds.size == 1) {
                        val message = getSelectedMessage(selectedIds)
                        if (message != null) {
                            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            val myPhone = if (currentUserPhone.isNotEmpty()) currentUserPhone else (currentUser?.phoneNumber ?: "")
                            val myAvatar = if (currentUserAvatar.isNotEmpty()) currentUserAvatar else (currentUser?.photoUrl?.toString() ?: "")
                            
                            startActivity(
                                MessageDetailsActivity.newIntent(
                                    this,
                                    message,
                                    currentUserPhone = myPhone,
                                    currentUserAvatar = myAvatar,
                                    otherUserPhone = otherUserPhone,
                                    otherUsername = otherUsername,
                                    otherUserAvatar = otherUserAvatar
                                )
                            )
                        }
                    } else {
                        Toast.makeText(this, "Please select only one message", Toast.LENGTH_SHORT).show()
                    }
                }
                "Pin" -> Toast.makeText(this, "Message Pinned", Toast.LENGTH_SHORT).show()
            }
            selectionManager.clearSelection()
            true
        }
        popup.show()
    }
    
    // ==================== MediaDownloadListener Implementation ====================
    
    override fun onDownloadClicked(message: Message) {
        val cId = chatId ?: return
        

        if (message.type == MessageType.MEDIA_GROUP) {
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                try {
                    repository.updateMessageStatus(message.id, MessageStatus.DOWNLOADING)
                    mediaTransferManager.startGroupDownload(message)
                } catch (e: Exception) {
                    android.util.Log.e("ChatActivity", "Failed to start group download", e)
                    repository.updateMessageStatus(message.id, MessageStatus.DOWNLOAD_FAILED)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Download failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        
        // Determine the remote URL based on message type
        val remoteUrl = when (message.type) {
            MessageType.IMAGE -> message.imageUrl
            MessageType.VIDEO -> message.videoUrl
            MessageType.AUDIO -> message.audioUrl
            else -> null
        }
        
        
        if (remoteUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No media URL available", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        
        // Update status to DOWNLOADING in DB immediately for UI feedback
        lifecycleScope.launch {
            try {
                val db = com.glyph.glyph_v3.data.local.AppDatabase.getDatabase(this@ChatActivity)
                db.messageDao().updateMessageStatus(message.id, MessageStatus.DOWNLOADING)
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Failed to update status", e)
            }
        }
        
        mediaTransferManager.startDownload(
            messageId = message.id,
            chatId = cId,
            mediaType = message.type,
            remoteUrl = remoteUrl,
            expectedSize = message.fileSize
        ) { success, localPath ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (success) {
                    Toast.makeText(this, "Download complete: $localPath", Toast.LENGTH_LONG).show()
                    // Targeted refresh: only update the specific downloaded message item
                    // instead of full notifyDataSetChanged() which causes flicker
                    val position = chatAdapter.currentList.indexOfFirst {
                        it is ChatListItem.MessageItem && it.message.id == message.id
                    }
                    if (position != -1) {
                        chatAdapter.notifyItemChanged(position, "MEDIA_UPDATED")
                    }
                } else {
                    Toast.makeText(this, "Download failed. Tap to retry.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onCancelDownloadClicked(messageId: String) {
        mediaTransferManager.cancelDownload(messageId)
        Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
    }
    
    // ==================== MediaLocalFileResolver Implementation ====================
    
    override fun getLocalFilePath(chatId: String, messageId: String, mediaType: MessageType): String? {
        return mediaTransferManager.getLocalFilePath(chatId, messageId, mediaType)
    }
    
    override fun getPlaybackUri(message: Message): String? {
        // Convert Message to LocalMessage for MediaTransferManager
        val localMessage = com.glyph.glyph_v3.data.local.entity.LocalMessage(
            id = message.id,
            chatId = message.chatId,
            text = message.text,
            senderId = message.senderId,
            timestamp = message.timestamp,
            status = message.status,
            isIncoming = message.isIncoming,
            type = message.type,
            imageUrl = message.imageUrl,
            videoUrl = message.videoUrl,
            thumbnailUrl = message.thumbnailUrl,
            audioUrl = message.audioUrl,
            localUri = message.localUri,
            fileSize = message.fileSize,
            videoDuration = message.videoDuration,
            audioDuration = message.audioDuration,
            contactName = message.contactName,
            contactPhone = message.contactPhone
        )
        return mediaTransferManager.getPlaybackUri(localMessage)
    }
    
    override fun isReadyForPlayback(message: Message): Boolean {
        // GIFs, Memes and Stickers are always ready via URL
        if (message.type == MessageType.GIF || message.type == MessageType.MEME || 
            message.type == MessageType.STICKER || message.type == MessageType.KLIPY_EMOJI) {
            return true
        }

        // Check if we have a local file for this message
        val cId = message.chatId
        val localPath = mediaTransferManager.getLocalFilePath(cId, message.id, message.type)
        if (!localPath.isNullOrEmpty()) {
            val file = java.io.File(localPath)
            return file.exists() && file.length() > 0
        }
        
        // Also check the localUri field
        if (!message.localUri.isNullOrEmpty()) {
            val file = java.io.File(message.localUri!!)
            if (file.exists() && file.length() > 0) return true
        }

        // For VIDEO: fall back to remote URL so the video can be streamed even if
        // the local copy was never saved or was deleted.
        if (message.type == MessageType.VIDEO && !message.videoUrl.isNullOrEmpty()) {
            return true
        }
        
        return false
    }

    private fun applyPastelSkyTheme() {
        if (com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this) == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            // Force edge-to-edge and transparent system bars with explicit flags
            window.apply {
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = android.graphics.Color.TRANSPARENT
                navigationBarColor = android.graphics.Color.TRANSPARENT
                // Set window background to match the start of the gradient to avoid black bars
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#CFE9F3")))
            }
            
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = true // Dark icons for light background
            insetsController.isAppearanceLightNavigationBars = true
            
            // Remove AppBarLayout background and elevation to let Compose gradient shine
            binding.appBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.appBarLayout.elevation = 0f
            
            // Keep AppBarLayout padding neutral; selection toolbar handles status-bar insets.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
                v.setPadding(0, 0, 0, 0)
                insets
            }
            
            // Remove padding from layoutInput to let Compose control it fully
            binding.layoutInput.setPadding(0, 0, 0, 0)
            
            // Input pill and send button replaced by Compose input (pastel sky only)
            binding.inputPill.visibility = View.GONE
            binding.btnSend.visibility = View.GONE

            // Show Compose input (header is always Compose via setupComposeHeader)
            binding.composeInput.visibility = View.VISIBLE

            binding.composeInput.setContent {
                val keyboardController = LocalSoftwareKeyboardController.current
                val scope = rememberCoroutineScope()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime
                
                com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                    // Sync text state with EditText for compatibility
                    // Note: In a real migration, we would observe the ViewModel or EditText changes
                    
                    // AI Composer state observation — guard against lateinit not yet initialized
                    // (applyPastelSkyTheme is called before setupTranslationFeature)
                    val aiState by if (::aiComposerManager.isInitialized) {
                        aiComposerManager.uiState.collectAsState()
                    } else {
                        androidx.compose.runtime.remember {
                            kotlinx.coroutines.flow.MutableStateFlow(
                                com.glyph.glyph_v3.ui.chat.composer.AiComposerUiState.Hidden
                            )
                        }.collectAsState()
                    }

                    // Observe keyboard animation state to suppress Compose animations
                        // during the IME slide — avoids competing with the View-layer
                        // chatContentContainer.translationY animation for frame budget.
                        val suppressAnimations by isKeyboardAnimatingState

                        ChatInputArea(
                            text = messageTextState.value,
                            onTextChange = { newText ->
                                messageTextState.value = newText
                                binding.etMessageInput.setText(newText)
                                binding.etMessageInput.setSelection(newText.length)
                            },
                            onAttachClick = { binding.btnAdd.performClick() },
                            onEmojiClick = { binding.btnEmoji.performClick() },
                            onCameraClick = { binding.btnCamera.performClick() },
                            onSendClick = { binding.btnSend.performClick() },
                            onBuzzClick = { binding.btnLightning.performClick() },
                            replyToMessage = replyMessageState.value,
                            onCancelReply = { cancelReply() },
                            editingMessage = editingMessageState.value,
                            onCancelEdit = { cancelEditMode(true) },
                            otherUsername = userNameState.value,
                            suppressAnimations = suppressAnimations,
                        onRecordingDown = {
                            if (ContextCompat.checkSelfPermission(this@ChatActivity, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                recordingStartTime = System.currentTimeMillis()
                                startRecording()
                            }
                        },
                        onRecordingDrag = { offX, offY ->
                            if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                                recorderState.updateOffsets(offX.coerceAtMost(0f), offY.coerceAtMost(0f))
                            }
                        },
                        onRecordingUp = { offX, offY ->
                            if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                                 val duration = System.currentTimeMillis() - recordingStartTime
                                 
                                 // Thresholds matching VoiceRecorderOverlay.kt
                                 if (offX < -180f) { // Slide to Cancel
                                     val file = recorderState.recordingFile.value
                                     recorderState.cancelRecording()
                                     if (::audioRecorder.isInitialized) {
                                         audioRecorder.cancel()
                                     }
                                     file?.delete()
                                     binding.btnSend.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                                 } else if (offY < -140f) { // Slide to Lock
                                     recorderState.lockRecording()
                                     binding.btnSend.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                 } else if (duration < 500) {
                                     // Tap-to-record: Lock recording
                                     recorderState.lockRecording()
                                 } else {
                                     // Press-and-hold: Stop and Show Options
                                     stopRecordingAndShowOptions()
                                 }
                            }
                        },
                        onAiClick = {
                            ensureAiComposerWarmUpStarted(reason = "compose_ai_click")
                            val currentText = messageTextState.value
                            if (currentText.isNotBlank()) {
                                // Professional dismissal for Compose input area
                                keyboardController?.hide()
                                
                                scope.launch {
                                    // Wait for keyboard dismissal to finish for a premium feel
                                    var iterations = 0
                                    while (iterations < 20 && imeInsets.getBottom(density) > 0) {
                                        delay(16)
                                        iterations++
                                    }
                                    aiComposerManager.openSheet(currentText)
                                    showAiComposerOverlay()
                                }
                            }
                        }
                    )
                }
            }

            // Pass theme state to adapter
            if (::chatAdapter.isInitialized) {
                chatAdapter.setPastelTheme(true)
            }

            // Style Scroll-to-bottom FAB
            binding.fabScrollToBottom.setCardBackgroundColor(android.graphics.Color.parseColor("#E6DDF2")) // LavenderMist
            binding.ivScrollFabIconCenter.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#5B4B8A")) // DeepIndigo
            binding.ivScrollFabIconStart.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#5B4B8A"))
            binding.tvScrollFabLabel.setTextColor(android.graphics.Color.parseColor("#5B4B8A"))
        }
    }

    /**
     * Sets up the Compose [ChatHeader] for ALL themes (dark, light, pastel-sky).
     *
     * The Compose header uses `remember { Animatable(0f) }` for its last-seen scroll, which
     * is immune to RecyclerView draw cascades — unlike the old XML [scrollX] approach that
     * was silently reset by Android's text-layout engine after every scroll stop.
     *
    * Presence updates flow in via [composeHeaderLastSeenState] and [userNameState]
    * (both MutableState).
     * The Compose runtime re-runs only the affected subtree, so AM/PM is always visible.
     */
    private fun setupComposeHeader() {
        val isPastel = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this) ==
                com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY

        // Initialize Compose states from current values
        applyComposeHeaderStatus(binding.tvLastSeen.text.toString(), source = "compose_header_init")
        userNameState.value = otherUsername
        userAvatarState.value = resolveVisibleHeaderAvatarSource()
        val headerAvatarSizePx = chatAvatarRequestSizePx()

        // Hide the XML toolbar; the Compose header draws its own background.
        binding.toolbarContainer.visibility = View.GONE

        if (isPastel) {
            // Pastel: gradient bleeds behind the status bar — make AppBarLayout transparent
            // and let Compose's statusBarsPadding() manage the inset.
            binding.appBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.appBarLayout.elevation = 0f
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
                v.setPadding(0, 0, 0, 0)
                insets
            }
        }

        if (!isPastel) {
            // For dark/light: the AppBarLayout adds its own top-padding for the status bar, so
            // headerContainer only needs to be ?attr/actionBarSize tall.  Lock every layer to
            // that exact pixel height so that:
            //   • Compose recompositions (full Row ↔ 56 dp Spacer) never trigger a re-measure
            //     that propagates up to AppBarLayout and causes visible height jank.
            //   • selectionToolbar (also ?attr/actionBarSize) always aligns pixel-perfect with
            //     the outgoing Compose icons — they live at exactly the same (x=0, y=0) origin
            //     inside the FrameLayout, so the transition is seamless.
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
            val abHeight = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)

            binding.headerContainer.updateLayoutParams { height = abHeight }
            binding.composeHeader.updateLayoutParams  { height = abHeight }
            selectionToolbarBinding.root.updateLayoutParams { height = abHeight }
        }
        // For pastel: AppBarLayout is transparent with 0 padding; Compose's statusBarsPadding()
        // controls insets and the selectionToolbar height is adjusted in updateSelectionToolbar.

        binding.composeHeader.visibility = View.VISIBLE
        binding.composeHeader.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                var showMenu by remember { mutableStateOf(false) }
                var didSeedHeaderAvatarFromRetained by remember { mutableStateOf(false) }
                
                ChatHeader(
                    userName = userNameState.value.ifEmpty { "Unknown" },
                    lastSeen = composeHeaderLastSeenState.value,
                    applyStatusBarPadding = isPastel,
                    lastSeenMarqueeEnabled = composeHeaderLastSeenMarqueeEnabledState.value,
                    lastSeenMarqueeStartDelayMs = STARTUP_LAST_SEEN_MARQUEE_DELAY_MS,
                    profilePicture = {
                        val userId = otherUserId
                        val avatarUrl = userAvatarState.value
                        val isGroupHeader = isGroupChat
                        val avatarInitial = userNameState.value.firstOrNull { !it.isWhitespace() }
                            ?.uppercaseChar()?.toString() ?: "?"
                        if (!avatarUrl.isNullOrEmpty() && (isGroupHeader || !userId.isNullOrEmpty())) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { context ->
                                    android.widget.ImageView(context).apply {
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                        
                                        // Synchronously load the cached avatar to prevent any flicker during transition
                                        var localPath = when {
                                            isGroupHeader -> chatId?.let {
                                                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(it)
                                            }
                                            !userId.isNullOrEmpty() -> com.glyph.glyph_v3.data.cache.AvatarCacheManager
                                                .getLocalAvatarPath(userId)
                                            else -> null
                                        }
                                        if (localPath == null && !avatarUrl.isNullOrEmpty() && !avatarUrl.startsWith("http") && java.io.File(avatarUrl).exists()) {
                                            localPath = avatarUrl
                                        }
                                        if (localPath != null) {
                                            val drawable = loadLocalAvatarSynchronously(localPath, headerAvatarSizePx)
                                            if (drawable != null) {
                                                setImageDrawable(drawable)
                                            }
                                        }
                                    }
                                },
                                update = updateAvatar@{ view ->
                                    if (!didSeedHeaderAvatarFromRetained && seedHeaderAvatarFromRetained(view)) {
                                        didSeedHeaderAvatarFromRetained = true
                                        return@updateAvatar
                                    }
                                    var localPath = when {
                                        isGroupHeader -> chatId?.let {
                                            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(it)
                                        }
                                        !userId.isNullOrEmpty() -> com.glyph.glyph_v3.data.cache.AvatarCacheManager
                                            .getLocalAvatarPath(userId)
                                        else -> null
                                    }
                                    if (localPath == null && !avatarUrl.isNullOrEmpty() && !avatarUrl.startsWith("http") && java.io.File(avatarUrl).exists()) {
                                        localPath = avatarUrl
                                    }
                                    if (localPath != null) {
                                        val drawable = loadLocalAvatarSynchronously(localPath, headerAvatarSizePx)
                                        if (drawable != null) {
                                            view.setImageDrawable(drawable)
                                        } else {
                                            val file = java.io.File(localPath)
                                            com.bumptech.glide.Glide.with(view)
                                                .load(file)
                                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                                .circleCrop()
                                                .override(headerAvatarSizePx, headerAvatarSizePx)
                                                .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                                                .dontAnimate()
                                                .into(view)
                                        }
                                    } else {
                                        com.bumptech.glide.Glide.with(view)
                                            .load(avatarUrl)
                                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                            .circleCrop()
                                            .override(headerAvatarSizePx, headerAvatarSizePx)
                                            .dontAnimate()
                                            .into(view)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ComposeColor(0xFFD9DDE3), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = avatarInitial)
                            }
                        }
                    },
                    onBackClick = { finish() },
                    onProfileClick = {
                        val currentChatId = chatId ?: ""
                        if (isGroupChat) {
                            startActivity(
                                com.glyph.glyph_v3.ui.groups.GroupInfoActivity.newIntent(
                                    context = this@ChatActivity,
                                    chatId = currentChatId
                                )
                            )
                        } else {
                            startActivity(
                                ContactInfoActivity.newIntent(
                                    context = this@ChatActivity,
                                    contactName = otherUsername,
                                    contactPhone = otherUserPhone,
                                    contactAvatar = otherUserAvatar,
                                    contactUserId = otherUserId ?: "",
                                    chatId = currentChatId,
                                    lastSeen = lastSeenState.value
                                )
                            )
                        }
                    },
                    onVideoCallClick = { binding.btnVideoCall.performClick() },
                    onVoiceCallClick = { binding.btnVoiceCall.performClick() },
                    onGroupVoiceCallClick = { binding.btnVoiceCall.performClick() },
                    onGroupWalkieTalkieClick = {
                        Toast.makeText(
                            this@ChatActivity,
                            "Walkie-Talkie for groups is not yet supported",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    showVideoCall = true,
                    showVoiceCall = !isGroupChat,
                    showGroupDropdown = isGroupChat,
                    walkieTalkieButtonState = when (walkieTalkieState.value) {
                        com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.IDLE -> if (isGroupChat) null else false
                        com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.DISCONNECTING -> null
                        else -> if (isGroupChat) null else true // any active state → show "end" icon
                    },
                    onWalkieTalkieClick = { handleWalkieTalkieButtonClick() },
                    onMenuClick = { 
                        showMenu = true 
                        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    },
                    showMenu = showMenu,
                    onDismissMenu = { showMenu = false },
                    menuContent = {
                        chatId?.let { cid ->
                            val isMuted = BuzzManager.isBuzzMuted(this@ChatActivity, cid)
                            DropdownMenuItem(
                                text = { Text("Mute Buzz") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_bolt), null, modifier = Modifier.size(24.dp)) },
                                trailingIcon = { 
                                    androidx.compose.material3.Checkbox(
                                        checked = isMuted,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val currentMuted = BuzzManager.isBuzzMuted(this@ChatActivity, cid)
                                    BuzzManager.setBuzzMuted(this@ChatActivity, cid, !currentMuted)
                                    val msg = if (!currentMuted) "Buzz muted" else "Buzz unmuted"
                                    Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        
                        val autoTranslate = translationPrefs.isAutoTranslateEnabled
                        DropdownMenuItem(
                            text = { Text(getString(R.string.auto_translate)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_translate_glyph), null, modifier = Modifier.size(24.dp)) },
                            trailingIcon = { 
                                androidx.compose.material3.Checkbox(
                                    checked = autoTranslate,
                                    onCheckedChange = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                if (translationPrefs.isAutoTranslateEnabled) {
                                    translationPrefs.isAutoTranslateEnabled = false
                                    Toast.makeText(this@ChatActivity, R.string.auto_translate_disabled, Toast.LENGTH_SHORT).show()
                                } else {
                                    showAutoTranslateLanguageSelector()
                                }
                            }
                        )

                        if (!isGroupChat) {
                            val expressiveEnabled = expressivePrefs.isEnabled.value
                            DropdownMenuItem(
                                text = { Text("Live Expressive Typing") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_rtt), null, modifier = Modifier.size(24.dp)) },
                                trailingIcon = {
                                    androidx.compose.material3.Checkbox(
                                        checked = expressiveEnabled,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val current = expressivePrefs.isEnabled.value
                                    expressivePrefs.setEnabled(!current)
                                    val msg = if (!current) "Live Expressive Typing Enabled" else "Live Expressive Typing Disabled"
                                    Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                                    if (current) {
                                        expressiveTypingManager?.stopObserving()
                                    } else {
                                        ensureExpressiveTypingInitialized(reason = "menu_enable")
                                        startExpressiveObservingSession()
                                    }
                                }
                            )
                        }

                        val isEmpty = isChatEmptyState.value
                        DropdownMenuItem(
                            text = { Text("Clear Chat") },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_bin_glyph),
                                    null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            enabled = !isEmpty,
                            onClick = {
                                showMenu = false
                                if (!isEmpty) showClearChatConfirmation()
                            }
                        )
                        
                        // ── Map Background toggle ──────────────────
                        chatId?.let { cid ->
                            val mapEnabled = isMapBackgroundEnabledForMenu()
                            DropdownMenuItem(
                                text = { Text("Map Background") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_map), null, modifier = Modifier.size(24.dp)) },
                                trailingIcon = {
                                    androidx.compose.material3.Checkbox(
                                        checked = mapEnabled,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    handleMapBackgroundToggle()
                                }
                            )

                            // Live Location (only when map is on)
                            if (mapEnabled) {
                                val liveSharing = mapBackgroundManager?.isLiveSharing == true
                                DropdownMenuItem(
                                    text = { Text("Live Location") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_my_location), null, modifier = Modifier.size(24.dp)) },
                                    trailingIcon = {
                                        androidx.compose.material3.Checkbox(
                                            checked = liveSharing,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        handleLiveLocationToggle()
                                    }
                                )

                                // Navigate to user (only when map is on)
                                val isNavigating = mapBackgroundManager?.routingState?.isNavigating == true
                                DropdownMenuItem(
                                    text = { Text(if (isNavigating) "Stop Navigation" else "Navigate to ${otherUsername.ifEmpty { "User" }}") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_explore), null, modifier = Modifier.size(24.dp)) },
                                    trailingIcon = {
                                        if (isNavigating) {
                                            androidx.compose.material3.Checkbox(
                                                checked = true,
                                                onCheckedChange = null
                                            )
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        handleNavigateToggle()
                                    }
                                )
                            }
                        }

                        val currentBlockStatus = _blockStatus.value
                        val isBlockedByMe = currentBlockStatus.iBlockedThem
                        DropdownMenuItem(
                            text = { Text(if (isBlockedByMe) "Unblock User" else "Block User") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_block_glyph), null, modifier = Modifier.size(24.dp)) },
                            onClick = {
                                showMenu = false
                                if (isBlockedByMe) {
                                    showUnblockConfirmation()
                                } else {
                                    showBlockConfirmation()
                                }
                            }
                        )
                    },
                    showContent = !selectionModeState.value
                )
            }
        }
    }

    override fun onBuzzReceived(buzzChatId: String, senderName: String) {
        // Ignore buzzes from users I have blocked.
        if (_blockStatus.value.iBlockedThem) return

        val isThisChat = buzzChatId == this.chatId
        
        runOnUiThread {
             shakeScreen()
             if (isThisChat) {
                 showBuzzOverlay()
             } else {
                 showBuzzBanner(buzzChatId, senderName)
             }
        }
    }

    private fun showBuzzBanner(buzzChatId: String, senderName: String) {
        val container = binding.root as? ViewGroup ?: return
        
        // Remove existing banner if any
        val existing = container.findViewWithTag<View>("BUZZ_BANNER")
        if (existing != null) container.removeView(existing)
        
        // Creates a banner at the top
        val banner = android.widget.TextView(this).apply {
             tag = "BUZZ_BANNER"
             text = "⚡ Buzz from $senderName"
             textSize = 16f
             setTextColor(android.graphics.Color.WHITE)
             typeface = android.graphics.Typeface.DEFAULT_BOLD
             gravity = android.view.Gravity.CENTER
             setPadding(32, 16, 32, 16)
             
             // Purple/Indigo background
             background = android.graphics.drawable.GradientDrawable().apply {
                 setColor(android.graphics.Color.parseColor("#5B4B8A"))
                 cornerRadius = 48f
             }
             
             elevation = 50f
             
             // Top Margin with z-indexing
             val lp = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                 androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.WRAP_CONTENT, 
                 androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.WRAP_CONTENT
             ).apply {
                 gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                 topMargin = 180 // Below toolbar
             }
             layoutParams = lp
             
             setOnClickListener {
                 // Navigate to new chat
                 val intent = ChatActivity.newIntent(this@ChatActivity, buzzChatId, "", senderName, "")
                 // Clear stack so back goes to main list? Or keep stack? 
                 // ChatActivity usually is singleTop or similar, but let's standard start
                 startActivity(intent)
             }
        }
        
        container.addView(banner)
        
        // Slide down and fade in
        banner.translationY = -100f
        banner.alpha = 0f
        banner.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
            
        // Auto dismiss after 3s
        banner.postDelayed({
             banner.animate()
                 .translationY(-100f)
                 .alpha(0f)
                 .setDuration(300)
                 .withEndAction { 
                     container.removeView(banner) 
                 }
                 .start()
        }, 3000)
    }

    private fun shakeScreen() {
        val view = binding.chatContentContainer
        
        // Horizontal shake animation (Yahoo style)
        val shake = ObjectAnimator.ofFloat(view, "translationX", 0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f)
        shake.duration = 500
        shake.start()
    }
    
    private fun showBuzzOverlay() {
         val container = binding.root as? ViewGroup ?: return

         container.post {
             val overlayText = android.widget.TextView(this).apply {
                 text = "BUZZ!!! ⚡"
                 textSize = 48f
                 setTextColor(android.graphics.Color.YELLOW)
                 typeface = android.graphics.Typeface.DEFAULT_BOLD
                 gravity = android.view.Gravity.CENTER
                 setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
                 background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#40FFFFFF"))
                 isClickable = false
                 isFocusable = false
             }

             val widthSpec = View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY)
             val heightSpec = View.MeasureSpec.makeMeasureSpec(container.height, View.MeasureSpec.EXACTLY)
             overlayText.measure(widthSpec, heightSpec)
             overlayText.layout(0, 0, container.width, container.height)

             container.overlay.add(overlayText)

             overlayText.animate()
                 .alpha(0f)
                 .scaleX(1.5f)
                 .scaleY(1.5f)
                 .setDuration(1200)
                 .withEndAction {
                     container.overlay.remove(overlayText)
                 }
                 .start()
         }
    }


    // Note: This method was replaced effectively by setOnApplyWindowInsetsListener in setupWindowInsets
    // But we keep it to ensure no double padding for Pastel theme if called elsewhere
    private fun updateContentBottomPadding(lastIme: Int, systemBottom: Int) {
        if (::keyboardController.isInitialized) {
            keyboardController.updateContentBottomPadding(lastIme, systemBottom)
        }
    }

    private fun refreshInputPadding() {
        if (::keyboardController.isInitialized) {
            keyboardController.refreshInputPadding()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * DEBUG: Test network connectivity for translation feature.
     * Call this method when debugging network issues.
     * Logs detailed diagnostics and shows results via Toast.
     */
    private fun debugNetworkConnectivity() {
        Toast.makeText(this, "Testing network for translation... (check logs)", Toast.LENGTH_SHORT).show()
        DebugUtils.runNetworkDiagnostics(this, lifecycleScope)
    }

    private fun showNavigationNotification(state: com.glyph.glyph_v3.ui.chat.map.routing.RoutingUiState) {
        com.glyph.glyph_v3.ui.chat.map.routing.RoutingNotificationManager.showNavigationNotification(
            context = this,
            otherUserName = otherUsername.ifEmpty { "User" },
            state = state
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pinned-message banner (WhatsApp-style, floats below toolbar)
    // ─────────────────────────────────────────────────────────────────────

    private fun setupPinnedMessageBanner(db: AppDatabase) {
        val cid = chatId ?: return

        // Make the ComposeView always visible; Compose's AnimatedVisibility handles
        // the show/hide transition so the view collapses to 0dp when nothing is pinned.
        binding.pinnedMessageBannerView.visibility = android.view.View.VISIBLE

        // Mount the Composable once
        binding.pinnedMessageBannerView.setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                val pins by pinnedMessagesState
                val dismissed by pinnedBannerDismissed
                val index by pinnedBannerIndexState

                PinnedMessageBanner(
                    pinnedMessages = if (dismissed) emptyList() else pins,
                    currentIndex = index,
                    onBannerTap = { msg ->
                        // Scroll to the tapped message first
                        scrollToMessage(msg.id)
                        
                        // Then cycle to the next pin if there are multiple
                        if (pins.size > 1) {
                            pinnedBannerIndexState.value = (index + 1) % pins.size
                        }
                    },
                    onUnpin = { msg ->
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.messageDao().unpinMessages(listOf(msg.id))
                        }
                    }
                )
            }
        }

        lifecycleScope.launch {
            val initialPins = withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.messageDao().getActivePinnedMessages(cid, System.currentTimeMillis())
            }
            val previous = pinnedMessagesState.value
            if (initialPins.size > previous.size) {
                pinnedBannerDismissed.value = false
                pinnedBannerIndexState.value = 0
            }
            pinnedMessagesState.value = initialPins
            if (initialPins.isNotEmpty()) {
                pinnedBannerIndexState.value = pinnedBannerIndexState.value.coerceIn(0, initialPins.lastIndex)
            } else {
                pinnedBannerIndexState.value = 0
            }

        }
    }

    private fun startPinnedMessageBannerObservation(db: AppDatabase) {
        val cid = chatId ?: return
        pinnedBannerObserverJob?.cancel()
        pinnedBannerObserverJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            db.messageDao()
                .observeActivePinnedMessages(cid, System.currentTimeMillis())
                .collect { pins ->
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val previous = pinnedMessagesState.value
                        if (pins.size > previous.size) {
                            // New pin added → reset dismiss flag and show banner
                            pinnedBannerDismissed.value = false
                            pinnedBannerIndexState.value = 0
                        }
                        pinnedMessagesState.value = pins
                        // Clamp index in case a pin was removed
                        if (pins.isNotEmpty()) {
                            pinnedBannerIndexState.value =
                                pinnedBannerIndexState.value.coerceIn(0, pins.lastIndex)
                        } else {
                            pinnedBannerIndexState.value = 0
                        }

                    }
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WALKIE-TALKIE
    // ═══════════════════════════════════════════════════════════════

    private fun startWalkieTalkieObservation() {
        walkieTalkieStateJob?.cancel()
        walkieTalkieStateJob = lifecycleScope.launch {
            walkieTalkieManager.state.collectLatest { state ->
                walkieTalkieState.value = state
            }
        }

        walkieTalkieFloorDeniedJob?.cancel()
        walkieTalkieFloorDeniedJob = lifecycleScope.launch {
            walkieTalkieManager.floorDenied.collect {
                Toast.makeText(this@ChatActivity, "Other user is speaking", Toast.LENGTH_SHORT).show()
            }
        }

        // Start watching for incoming WT requests
        walkieTalkieManager.startWatchingForRequests()

        // If launched from a WT notification, dispatch the pending session
        // Only dispatch if manager is IDLE — if it's already connected (e.g. incoming
        // activity already established the session), the overlay will appear via
        // the state observation above without needing to re-dispatch.
        val pendingWtSessionId = intent.getStringExtra(EXTRA_WT_SESSION_ID)
        val pendingWtInitiatorName = intent.getStringExtra(EXTRA_WT_INITIATOR_NAME)
        if (!pendingWtSessionId.isNullOrEmpty() && !pendingWtInitiatorName.isNullOrEmpty()) {
            if (walkieTalkieManager.state.value == com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.IDLE) {
                walkieTalkieManager.handleIncomingRequestFromFcm(
                    sessionId = pendingWtSessionId,
                    initiatorName = pendingWtInitiatorName
                )
            } else {
            }
            intent.removeExtra(EXTRA_WT_SESSION_ID)
            intent.removeExtra(EXTRA_WT_INITIATOR_NAME)
        }
    }

    private fun setupWalkieTalkieOverlay() {
        val rootContainer = binding.root as? android.view.ViewGroup ?: return
        if (rootContainer.findViewWithTag<android.view.View>("WalkieTalkieOverlay") != null) return

        val overlayView = androidx.compose.ui.platform.ComposeView(this).apply {
            tag = "WalkieTalkieOverlay"
            setContent {
                com.glyph.glyph_v3.ui.theme.GlyphThemeProvider(isDeepDark = true) {
                    val wtState by walkieTalkieManager.state.collectAsState()
                    val peerName = otherUsername ?: "User"

                    com.glyph.glyph_v3.ui.chat.walkietalkie.WalkieTalkieOverlay(
                        state = wtState,
                        peerName = peerName,
                        topSafeAreaInsetPx = walkieTalkieTopSafeAreaInsetPx,
                        bottomSafeAreaInsetPx = walkieTalkieBottomSafeAreaInsetPx,
                        onPttPress = { walkieTalkieManager.pressPtt() },
                        onPttRelease = { walkieTalkieManager.releasePtt() },
                        onDisconnect = {
                            walkieTalkieManager.disconnect()
                            Toast.makeText(this@ChatActivity, "Walkie-Talkie ended", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        rootContainer.addView(
            overlayView,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun handleWalkieTalkieButtonClick() {
        val userId = otherUserId ?: return
        dismissKeyboardBeforeWalkieTalkie()

        val wtState = walkieTalkieManager.state.value

        when (wtState) {
            // Active session — disconnect
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.CONNECTED_IDLE,
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.TRANSMITTING,
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.RECEIVING,
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.CONNECTING,
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.REQUESTING -> {
                walkieTalkieManager.disconnect()
                Toast.makeText(this, "Walkie-Talkie ended", Toast.LENGTH_SHORT).show()
            }
            // Idle — check mic permission then start
            com.glyph.glyph_v3.data.service.WalkieTalkieManager.State.IDLE -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        REQ_LIVE_AUDIO_NOTIFICATION
                    )
                    return
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            REQ_LIVE_AUDIO_NOTIFICATION
                        )
                        return
                    }
                }
                startWalkieTalkieSessionAfterKeyboardDismissal(userId)
            }
            else -> {}
        }
    }

    private fun dismissKeyboardBeforeWalkieTalkie() {
        binding.etMessageInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessageInput.windowToken, 0)
    }

    private fun startWalkieTalkieSessionAfterKeyboardDismissal(userId: String) {
        // Start the session immediately — the manager sets REQUESTING state synchronously
        // so the overlay appears with "LINKING CHANNEL" feedback without waiting for the
        // keyboard animation to finish. The overlay renders full-screen over the keyboard.
        walkieTalkieManager.startSession(userId)
        Toast.makeText(this@ChatActivity, "Starting Walkie-Talkie…", Toast.LENGTH_SHORT).show()

        // Non-blocking keyboard dismissal: keep hiding the keyboard in the background
        // while the session setup runs and the overlay is already visible.
        lifecycleScope.launch {
            var waitedFrames = 0
            while (
                waitedFrames < 20 &&
                ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            ) {
                delay(16L)
                waitedFrames++
            }
        }
    }
}
