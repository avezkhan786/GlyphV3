package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.utils.MessageCacheManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.roundToInt

object ChatOpenPrefetcher {
    const val CACHE_DEBUG = true
    const val VERBOSE_MEDIA_BIND_DEBUG = false

    private const val PREFETCH_MESSAGE_LIMIT = 180
    // 300 messages (~350 list items) gives 3-4 screens of scroll buffer.
    // With lightweight first-frame binds, the main-thread cost is minimal.
    private const val LIGHT_PREFETCH_MESSAGE_LIMIT = 300
    private const val MEDIA_WARM_LIMIT = 8
    private const val MEMORY_WARM_LIMIT = 4
    private const val DURABLE_PREVIEW_WARM_LIMIT = 160
    private const val DURABLE_MEDIA_DISK_WARM_LIMIT = 48
    private const val DURABLE_WARMUP_COOLDOWN_MS = 90_000L
    private const val DURABLE_WARMUP_ACTIVE_CHAT_DELAY_MS = 1_800L
    private const val FIRST_PAINT_RETAINED_MESSAGE_LIMIT = 16
    private const val FIRST_PAINT_RETAINED_SPEC_LIMIT = 24
    private const val FIRST_PAINT_VISIBLE_WINDOW_ITEMS = 10
    private const val RETAIN_WARMUP_COOLDOWN_MS = 15000L
    private const val OPENING_RETAINED_SUPPRESSION_MS = 1500L
    private const val CHAT_AVATAR_SIZE_DP = 40f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightChatIds = ConcurrentHashMap.newKeySet<String>()
    private val retainedPreloadInFlightChatIds = ConcurrentHashMap.newKeySet<String>()
    private val durableWarmupInFlightChatIds = ConcurrentHashMap.newKeySet<String>()
    private val groupSenderInfoInFlightUserIds = ConcurrentHashMap.newKeySet<String>()
    private val lastRetainedWarmupAtMsByChatId = ConcurrentHashMap<String, Long>()
    private val lastDurableWarmupAtMsByChatId = ConcurrentHashMap<String, Long>()
    @Volatile private var activeOpeningChatId: String? = null
    @Volatile private var suppressPredictiveRetainedUntilMs: Long = 0L
    data class GroupSenderRenderInfo(
        val userId: String,
        val displayName: String,
        val avatarUrl: String
    )

    data class RetainedMediaPreloadSpec(
        val model: Any,
        val widthPx: Int,
        val heightPx: Int,
        val type: MessageType,
        val messageId: String = ""
    ) {
        fun cacheKey(): String = "${model}|${widthPx}x${heightPx}|${type}"
    }

    data class RetainedMediaPreload(
        val targets: List<FutureTarget<Drawable>>,
        val specs: List<RetainedMediaPreloadSpec>,
        val specKeys: Set<String> = specs.mapTo(linkedSetOf()) { it.cacheKey() }
    )

    data class RetainedAvatarPreload(
        val target: FutureTarget<Drawable>,
        val sourceKey: String
    )

    private data class HeaderAvatarWarmSpec(
        val model: Any,
        val sourceKey: String,
        val cacheUserId: String?,
        val cacheAvatarUrl: String?,
        val isGroupAvatar: Boolean
    )

    private val retainedMediaPreloadsByChatId =
        ConcurrentHashMap<String, RetainedMediaPreload>()
    private val retainedAvatarPreloadsByChatId =
        ConcurrentHashMap<String, RetainedAvatarPreload>()
    private val groupSenderInfoByUserId =
        ConcurrentHashMap<String, GroupSenderRenderInfo>()

    fun getGroupSenderRenderInfo(userId: String): GroupSenderRenderInfo? {
        return groupSenderInfoByUserId[userId.trim()]
    }

    suspend fun awaitGroupSenderRenderInfo(
        userIds: Collection<String>,
        timeoutMs: Long
    ): List<GroupSenderRenderInfo> {
        val normalizedUserIds = userIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedUserIds.isEmpty()) return emptyList()

        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            val warmed = normalizedUserIds.mapNotNull { groupSenderInfoByUserId[it] }
            if (warmed.size == normalizedUserIds.size) return warmed
            delay(16L)
        }
        return normalizedUserIds.mapNotNull { groupSenderInfoByUserId[it] }
    }

    fun noteChatOpenStarting(chatId: String) {
        val trimmedChatId = chatId.trim()
        if (trimmedChatId.isEmpty()) return
        activeOpeningChatId = trimmedChatId
        suppressPredictiveRetainedUntilMs = android.os.SystemClock.elapsedRealtime() + OPENING_RETAINED_SUPPRESSION_MS
        ChatOpenTrace.event(trimmedChatId, "predictive_retained_suppression_start", "duration=${OPENING_RETAINED_SUPPRESSION_MS}ms")
    }

    fun takeRetainedMediaPreload(chatId: String): RetainedMediaPreload? {
        return retainedMediaPreloadsByChatId.remove(chatId.trim())
    }

    fun takeRetainedAvatarPreload(chatId: String): RetainedAvatarPreload? {
        return retainedAvatarPreloadsByChatId.remove(chatId.trim())
    }

    fun warmChatsAsync(
        context: Context,
        repository: RealtimeMessageRepository,
        chatIds: Collection<String>,
        source: String
    ) {
        val appContext = context.applicationContext
        chatIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { chatId ->
                val cachedSnapshot = MessageCacheManager.getRenderableSnapshot(chatId)
                if (cachedSnapshot != null && cachedSnapshot.recentMessages.isNotEmpty()) {
                    queuePredictiveRetainedPreloads(
                        context = appContext,
                        chatId = chatId,
                        source = source,
                        messages = cachedSnapshot.recentMessages,
                        listItems = cachedSnapshot.listItems
                    )
                    queueDurableWarmup(
                        context = appContext,
                        chatId = chatId,
                        source = source,
                        messages = cachedSnapshot.recentMessages
                    )
                    return@forEach
                }
                if (MessageCacheManager.hasFreshMemorySnapshot(chatId)) return@forEach
                if (!inFlightChatIds.add(chatId)) return@forEach
                ChatOpenTrace.event(chatId, "predictive_prefetch_queue", "source=$source")

                scope.launch {
                    try {
                        warmChatInternal(
                            context = appContext,
                            repository = repository,
                            chatId = chatId,
                            source = source,
                            allowHeavyWarm = shouldRunHeavyPredictiveWarm(source)
                        )
                    } finally {
                        inFlightChatIds.remove(chatId)
                    }
                }
            }
    }

    suspend fun primeChatOpen(
        context: Context,
        repository: RealtimeMessageRepository,
        chatId: String,
        source: String,
        peerUserId: String = "",
        peerAvatarUrl: String = ""
    ) {
        val trimmedChatId = chatId.trim()
        if (trimmedChatId.isEmpty()) return
        noteChatOpenStarting(trimmedChatId)
        val appContext = context.applicationContext
        ChatOpenTrace.event(trimmedChatId, "prime_prefetch_enter", "source=$source")

        warmChatHeaderAvatar(
            context = appContext,
            chatId = trimmedChatId,
            peerUserId = peerUserId,
            peerAvatarUrl = peerAvatarUrl,
            source = source
        )

        var snapshot = MessageCacheManager.getRenderableSnapshot(trimmedChatId)
        ChatOpenTrace.event(
            trimmedChatId,
            "prime_prefetch_snapshot_check",
            "hit=${snapshot != null} messages=${snapshot?.recentMessages?.size ?: 0} items=${snapshot?.listItems?.size ?: 0} source=${snapshot?.source ?: "none"}"
        )
        if (snapshot == null || snapshot.recentMessages.isEmpty()) {
            if (inFlightChatIds.add(trimmedChatId)) {
                try {
                    warmChatInternal(
                        context = appContext,
                        repository = repository,
                        chatId = trimmedChatId,
                        source = source,
                        allowHeavyWarm = false
                    )
                } finally {
                    inFlightChatIds.remove(trimmedChatId)
                }
            } else {
                ChatOpenTrace.event(trimmedChatId, "prime_prefetch_join_inflight", "source=$source")
                snapshot = awaitRenderableSnapshot(trimmedChatId, 120L)
                if (snapshot == null || snapshot.recentMessages.isEmpty()) {
                    warmChatInternal(
                        context = appContext,
                        repository = repository,
                        chatId = trimmedChatId,
                        source = source,
                        allowHeavyWarm = false
                    )
                }
            }

            snapshot = MessageCacheManager.getSnapshot(trimmedChatId)
            ChatOpenTrace.event(
                trimmedChatId,
                "prime_prefetch_snapshot_after_warm",
                "hit=${snapshot != null} messages=${snapshot?.recentMessages?.size ?: 0}"
            )
        }

        // Even when the transcript snapshot is already cached, ChatActivity needs retained
        // media futures to seed first-paint binds without blocking RecyclerView submit.
        snapshot = snapshot ?: return
        warmGroupSenderMetadata(
            context = appContext,
            chatId = trimmedChatId,
            messages = snapshot.recentMessages,
            source = source
        )
        val recentMedia = firstPaintRetainedMessages(snapshot.recentMessages, snapshot.listItems)
        if (recentMedia.isNotEmpty()) {
            val hasPredictiveRetained = retainedMediaPreloadsByChatId[trimmedChatId]?.targets?.isNotEmpty() == true
            if (hasPredictiveRetained) {
                ChatOpenTrace.event(trimmedChatId, "prime_prefetch_retained_reuse", "media=${recentMedia.size}")
            } else {
                ChatOpenTrace.event(trimmedChatId, "prime_prefetch_retained_start", "media=${recentMedia.size}")
                submitRetainedMediaPreloads(
                    context = appContext,
                    messages = recentMedia,
                    retainTargetsForChatId = trimmedChatId,
                    priority = Priority.IMMEDIATE,
                    maxMessages = FIRST_PAINT_RETAINED_MESSAGE_LIMIT,
                    maxSpecs = FIRST_PAINT_RETAINED_SPEC_LIMIT,
                    preserveOrder = true,
                    replaceExisting = true
                )
            }
            if (shouldRunRetainedWarmup(trimmedChatId)) {
                scope.launch {
                    runCatching {
                        warmMediaCaches(
                            context = appContext,
                            messages = recentMedia
                        )
                    }
                }
            }
        }
        ChatOpenTrace.event(trimmedChatId, "prime_prefetch_done", "media=${recentMedia.size}")
    }

    private fun queuePredictiveRetainedPreloads(
        context: Context,
        chatId: String,
        source: String,
        messages: List<Message>,
        listItems: List<ChatListItem>
    ) {
        if (!shouldPrepareRetainedPredictiveMedia(source)) return
        if (isPredictiveRetainedSuppressedFor(chatId)) {
            ChatOpenTrace.event(chatId, "predictive_retained_preload_suppressed", "source=$source active=${activeOpeningChatId?.take(8) ?: "none"}")
            return
        }
        if (retainedMediaPreloadsByChatId[chatId]?.targets?.isNotEmpty() == true) {
            ChatOpenTrace.event(chatId, "predictive_retained_preload_reuse", "source=$source")
            return
        }
        if (!retainedPreloadInFlightChatIds.add(chatId)) {
            ChatOpenTrace.event(chatId, "predictive_retained_preload_join", "source=$source")
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            try {
                val retainedMessages = firstPaintRetainedMessages(messages, listItems)
                if (retainedMessages.isEmpty()) return@launch
                if (isPredictiveRetainedSuppressedFor(chatId)) {
                    ChatOpenTrace.event(chatId, "predictive_retained_preload_suppressed_late", "source=$source active=${activeOpeningChatId?.take(8) ?: "none"}")
                    return@launch
                }
                if (retainedMediaPreloadsByChatId[chatId]?.targets?.isNotEmpty() == true) {
                    ChatOpenTrace.event(chatId, "predictive_retained_preload_reuse_late", "source=$source")
                    return@launch
                }
                ChatOpenTrace.event(
                    chatId,
                    "predictive_retained_preload_start",
                    "source=$source media=${retainedMessages.size}"
                )
                submitRetainedMediaPreloads(
                    context = appContext,
                    messages = retainedMessages,
                    retainTargetsForChatId = chatId,
                    priority = Priority.HIGH,
                    maxMessages = FIRST_PAINT_RETAINED_MESSAGE_LIMIT,
                    maxSpecs = FIRST_PAINT_RETAINED_SPEC_LIMIT,
                    preserveOrder = true,
                    replaceExisting = false
                )
            } finally {
                retainedPreloadInFlightChatIds.remove(chatId)
            }
        }
    }

    private fun isPredictiveRetainedSuppressedFor(chatId: String): Boolean {
        val activeChatId = activeOpeningChatId ?: return false
        if (activeChatId == chatId) return false
        return android.os.SystemClock.elapsedRealtime() < suppressPredictiveRetainedUntilMs
    }

    private fun shouldRunHeavyPredictiveWarm(source: String): Boolean {
        return source != "chat_list_visible" &&
            source != "chat_list_compose_visible" &&
            source != "chat_list_compose_archived"
    }

    private suspend fun awaitRenderableSnapshot(
        chatId: String,
        timeoutMs: Long
    ): MessageCacheManager.ChatRenderSnapshot? {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            MessageCacheManager.getRenderableSnapshot(chatId)
                ?.takeIf { it.recentMessages.isNotEmpty() }
                ?.let { return it }
            delay(16L)
        }
        return MessageCacheManager.getRenderableSnapshot(chatId)
            ?.takeIf { it.recentMessages.isNotEmpty() }
    }

    private fun shouldRunRetainedWarmup(chatId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastRetainedWarmupAtMsByChatId.put(chatId, now)
        return previous == null || now - previous >= RETAIN_WARMUP_COOLDOWN_MS
    }

    private fun shouldRunDurableWarmup(chatId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastDurableWarmupAtMsByChatId.put(chatId, now)
        return previous == null || now - previous >= DURABLE_WARMUP_COOLDOWN_MS
    }

    private fun queueDurableWarmup(
        context: Context,
        chatId: String,
        source: String,
        messages: List<Message>
    ) {
        if (messages.isEmpty()) return
        if (!shouldRunDurableWarmup(chatId)) {
            ChatOpenTrace.event(chatId, "durable_warm_skip_cooldown", "source=$source")
            return
        }
        if (!durableWarmupInFlightChatIds.add(chatId)) {
            ChatOpenTrace.event(chatId, "durable_warm_join", "source=$source")
            return
        }

        val appContext = context.applicationContext
        val delayMs = if (activeOpeningChatId == chatId) {
            DURABLE_WARMUP_ACTIVE_CHAT_DELAY_MS
        } else {
            0L
        }
        scope.launch {
            try {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                val durableWindow = messages.takeLast(DURABLE_PREVIEW_WARM_LIMIT)
                if (durableWindow.isEmpty()) return@launch

                ChatOpenTrace.event(
                    chatId,
                    "durable_warm_start",
                    "source=$source messages=${durableWindow.size}"
                )
                MessagePreviewCacheManager.warmMessagesAsync(appContext, durableWindow)
                warmPersistentMediaDiskCache(appContext, durableWindow)
                ChatOpenTrace.event(
                    chatId,
                    "durable_warm_done",
                    "source=$source messages=${durableWindow.size}"
                )
            } finally {
                durableWarmupInFlightChatIds.remove(chatId)
            }
        }
    }

    private suspend fun warmPersistentMediaDiskCache(
        context: Context,
        messages: List<Message>
    ) {
        if (messages.isEmpty()) return

        val diskModels = withContext(Dispatchers.Default) {
            messages
                .asSequence()
                .flatMap { message -> mediaWarmModels(message).asSequence() }
                .filter(::isRemoteDiskWarmModel)
                .distinctBy { it.toString() }
                .take(DURABLE_MEDIA_DISK_WARM_LIMIT)
                .toList()
        }
        if (diskModels.isEmpty()) return

        withContext(Dispatchers.IO) {
            diskModels.forEach { model ->
                runCatching {
                    Glide.with(context)
                        .downloadOnly()
                        .load(model)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.LOW)
                        .submit()
                        .get(1_200L, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun isRemoteDiskWarmModel(model: Any): Boolean {
        return when (model) {
            is String -> model.startsWith("http://") || model.startsWith("https://")
            is Uri -> model.scheme.equals("http", ignoreCase = true) || model.scheme.equals("https", ignoreCase = true)
            else -> false
        }
    }

    private suspend fun submitRetainedMediaPreloads(
        context: Context,
        messages: List<Message>,
        retainTargetsForChatId: String,
        priority: Priority = Priority.HIGH,
        maxMessages: Int = MEMORY_WARM_LIMIT,
        maxSpecs: Int = MEMORY_WARM_LIMIT,
        preserveOrder: Boolean = false,
        replaceExisting: Boolean = true
    ) {
        val selectedMessages = if (preserveOrder) {
            messages.take(maxMessages)
        } else {
            messages.takeLast(maxMessages)
        }
        val preloadSpecs = withContext(Dispatchers.Default) {
            buildMediaPreloadSpecs(context, selectedMessages).take(maxSpecs)
        }
        ChatOpenTrace.event(retainTargetsForChatId, "retained_preload_specs", "count=${preloadSpecs.size}")
        if (preloadSpecs.isEmpty()) return

        val futures = withContext(Dispatchers.Main.immediate) {
            preloadSpecs.map { spec ->
                Glide.with(context)
                    .load(spec.model)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .priority(priority)
                    .fitCenter()
                    .override(spec.widthPx, spec.heightPx)
                    .submit(spec.widthPx, spec.heightPx)
            }
        }

        val existing = retainedMediaPreloadsByChatId[retainTargetsForChatId]
        if (existing?.targets?.isNotEmpty() == true && !replaceExisting) {
            futures.forEach { stale -> runCatching { Glide.with(context).clear(stale) } }
            ChatOpenTrace.event(retainTargetsForChatId, "retained_preload_preserved", "futures=${existing.targets.size}")
            return
        }

        retainedMediaPreloadsByChatId.remove(retainTargetsForChatId)?.targets?.forEach { stale ->
            runCatching { Glide.with(context).clear(stale) }
        }
        retainedMediaPreloadsByChatId[retainTargetsForChatId] = RetainedMediaPreload(
            targets = CopyOnWriteArrayList(futures),
            specs = preloadSpecs.map { it.toRetainedSpec() }
        )
        ChatOpenTrace.event(retainTargetsForChatId, "retained_preload_submitted", "futures=${futures.size}")
    }

    private suspend fun warmChatInternal(
        context: Context,
        repository: RealtimeMessageRepository,
        chatId: String,
        source: String,
        allowHeavyWarm: Boolean = true
    ) {
        StartupTrace.logStage("chat_prefetch_start", "chatId=$chatId source=$source")
        val queryLimit = if (allowHeavyWarm) PREFETCH_MESSAGE_LIMIT else LIGHT_PREFETCH_MESSAGE_LIMIT
        ChatOpenTrace.event(chatId, "prefetch_room_recent_start", "source=$source limit=$queryLimit heavy=$allowHeavyWarm")

        val recentMessages = withContext(Dispatchers.IO) {
            repository.getRecentMessages(chatId, queryLimit)
        }
        ChatOpenTrace.event(chatId, "prefetch_room_recent_end", "count=${recentMessages.size}")
        if (recentMessages.isEmpty()) {
            MessageCacheManager.putSnapshot(chatId, emptyList(), emptyList(), source)
            StartupTrace.logStage("chat_prefetch_end", "chatId=$chatId source=$source count=0")
            ChatOpenTrace.event(chatId, "prefetch_done", "source=$source count=0")
            return
        }

        val sortedMessages = recentMessages.sortedWith(ChatViewModel.MESSAGE_ORDER_COMPARATOR)
        ChatOpenTrace.event(chatId, "prefetch_snapshot_build_start", "messages=${sortedMessages.size}")
        val listItems = withContext(Dispatchers.Default) {
            sortedMessages.forEach { it.warmUpForUi() }
            ChatTranscriptSnapshotBuilder.build(sortedMessages)
        }
        ChatOpenTrace.event(chatId, "prefetch_snapshot_build_end", "items=${listItems.size}")

        MessageCacheManager.putSnapshot(chatId, sortedMessages, listItems, source)
        warmGroupSenderMetadata(
            context = context,
            chatId = chatId,
            messages = sortedMessages,
            source = source
        )
        queueDurableWarmup(
            context = context,
            chatId = chatId,
            source = source,
            messages = sortedMessages
        )
        if (allowHeavyWarm) {
            ChatOpenTrace.event(chatId, "prefetch_media_warm_queue", "messages=${sortedMessages.takeLast(MEDIA_WARM_LIMIT).size}")
            warmMediaCaches(context, sortedMessages.takeLast(MEDIA_WARM_LIMIT))
        } else {
            queuePredictiveRetainedPreloads(
                context = context,
                chatId = chatId,
                source = source,
                messages = sortedMessages,
                listItems = listItems
            )
        }

        StartupTrace.logStage(
            "chat_prefetch_end",
            "chatId=$chatId source=$source count=${sortedMessages.size} items=${listItems.size}"
        )
        ChatOpenTrace.event(chatId, "prefetch_done", "source=$source count=${sortedMessages.size} items=${listItems.size}")
    }

    suspend fun warmGroupSenderMetadata(
        context: Context,
        chatId: String,
        messages: List<Message>,
        source: String
    ) {
        if (!chatId.startsWith("group_") || messages.isEmpty()) return

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val candidateSenderIds = messages
            .asReversed()
            .asSequence()
            .filter { message ->
                message.isIncoming &&
                    message.senderId.isNotBlank() &&
                    message.senderId != currentUserId &&
                    message.type != MessageType.SYSTEM
            }
            .map { it.senderId }
            .distinct()
            .take(FIRST_PAINT_VISIBLE_WINDOW_ITEMS)
            .toList()
        if (candidateSenderIds.isEmpty()) return

        val missingSenderIds = candidateSenderIds.filter { senderId ->
            !groupSenderInfoByUserId.containsKey(senderId) && groupSenderInfoInFlightUserIds.add(senderId)
        }
        if (missingSenderIds.isEmpty()) return

        ChatOpenTrace.event(
            chatId,
            "group_sender_warm_start",
            "source=$source senders=${missingSenderIds.size}"
        )

        val appContext = context.applicationContext
        val avatarRequestSizePx = avatarRequestSizePx()
        try {
            withContext(Dispatchers.IO) {
                missingSenderIds.map { senderId ->
                    async {
                        fetchGroupSenderRenderInfo(senderId)?.also { info ->
                            val localAvatarPath = AvatarCacheManager.getLocalAvatarPath(senderId)
                                ?.takeIf { it.isNotBlank() }
                            val avatarModel: Any? = localAvatarPath?.let(::File)
                                ?: info.avatarUrl.takeIf { it.isNotBlank() }
                            val cachedAvatarSource = localAvatarPath ?: info.avatarUrl
                            if (cachedAvatarSource.isNotBlank()) {
                                groupSenderInfoByUserId[senderId] = info.copy(avatarUrl = cachedAvatarSource)
                            } else {
                                groupSenderInfoByUserId[senderId] = info
                            }
                            if (avatarModel != null) {
                                runCatching {
                                    Glide.with(appContext)
                                        .load(avatarModel)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .circleCrop()
                                        .override(avatarRequestSizePx, avatarRequestSizePx)
                                        .preload(avatarRequestSizePx, avatarRequestSizePx)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            missingSenderIds.forEach(groupSenderInfoInFlightUserIds::remove)
        }

        ChatOpenTrace.event(
            chatId,
            "group_sender_warm_done",
            "source=$source cached=${candidateSenderIds.count { groupSenderInfoByUserId.containsKey(it) }}"
        )
    }

    private suspend fun warmChatHeaderAvatar(
        context: Context,
        chatId: String,
        peerUserId: String,
        peerAvatarUrl: String,
        source: String
    ) {
        val avatarRequestSizePx = avatarRequestSizePx()
        val spec = buildHeaderAvatarWarmSpec(
            chatId = chatId,
            peerUserId = peerUserId,
            peerAvatarUrl = peerAvatarUrl,
            sizePx = avatarRequestSizePx
        ) ?: return

        val existing = retainedAvatarPreloadsByChatId[chatId]
        if (existing?.sourceKey == spec.sourceKey) {
            ChatOpenTrace.event(chatId, "header_avatar_warm_reuse", "source=$source")
            return
        }

        retainedAvatarPreloadsByChatId.remove(chatId)?.target?.let { stale ->
            runCatching { Glide.with(context).clear(stale) }
        }

        val target = withContext(Dispatchers.Main.immediate) {
            val request = Glide.with(context)
                .load(spec.model)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.IMMEDIATE)
                .circleCrop()
                .override(avatarRequestSizePx, avatarRequestSizePx)

            if (spec.model is File) {
                request.signature(ObjectKey(spec.model.lastModified()))
            }

            request.submit(avatarRequestSizePx, avatarRequestSizePx)
        }

        retainedAvatarPreloadsByChatId[chatId] = RetainedAvatarPreload(
            target = target,
            sourceKey = spec.sourceKey
        )

        if (!spec.cacheUserId.isNullOrBlank() && !spec.cacheAvatarUrl.isNullOrBlank()) {
            scope.launch {
                runCatching {
                    if (spec.isGroupAvatar) {
                        AvatarCacheManager.cacheGroupAvatar(spec.cacheUserId, spec.cacheAvatarUrl, context)
                    } else {
                        AvatarCacheManager.cacheAvatar(spec.cacheUserId, spec.cacheAvatarUrl, context)
                    }
                }
            }
        }

        withContext(Dispatchers.IO) {
            runCatching { target.get(500L, TimeUnit.MILLISECONDS) }
        }
        ChatOpenTrace.event(chatId, "header_avatar_warm_submitted", "source=$source local=${spec.model is File}")
    }

    private fun buildHeaderAvatarWarmSpec(
        chatId: String,
        peerUserId: String,
        peerAvatarUrl: String,
        sizePx: Int
    ): HeaderAvatarWarmSpec? {
        val remoteUrl = peerAvatarUrl.trim().takeIf { it.isNotEmpty() }
        val isGroupChat = chatId.startsWith("group_")

        if (isGroupChat) {
            val localPath = AvatarCacheManager.getLocalGroupAvatarPath(chatId)?.takeIf { it.isNotBlank() }
            val localFile = localPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
            if (localFile != null) {
                return HeaderAvatarWarmSpec(
                    model = localFile,
                    sourceKey = "group-file:${chatId}:${localFile.absolutePath}:${localFile.lastModified()}:$sizePx",
                    cacheUserId = null,
                    cacheAvatarUrl = null,
                    isGroupAvatar = true
                )
            }
            if (remoteUrl != null) {
                return HeaderAvatarWarmSpec(
                    model = remoteUrl,
                    sourceKey = "group-url:${chatId}:$remoteUrl:$sizePx",
                    cacheUserId = chatId,
                    cacheAvatarUrl = remoteUrl,
                    isGroupAvatar = true
                )
            }
            return null
        }

        val userId = peerUserId.trim()
        if (userId.isEmpty() || BlockRepository.getBlockStatus(userId).isBlocked) return null

        val localPath = AvatarCacheManager.getLocalAvatarPath(userId)?.takeIf { it.isNotBlank() }
        val localFile = localPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        val visibilityState = AvatarVisibilityRepository.getCachedProfilePhotoVisibility(userId)
        val canOptimisticallyShowLocalAvatar = visibilityState?.isResolved != true && localFile != null
        val canShowAvatar = visibilityState?.isVisible == true || canOptimisticallyShowLocalAvatar
        if (!canShowAvatar) return null

        if (localFile != null) {
            return HeaderAvatarWarmSpec(
                model = localFile,
                sourceKey = "user-file:${userId}:${localFile.absolutePath}:${localFile.lastModified()}:$sizePx",
                cacheUserId = null,
                cacheAvatarUrl = null,
                isGroupAvatar = false
            )
        }

        if (visibilityState?.isVisible == true && remoteUrl != null) {
            return HeaderAvatarWarmSpec(
                model = remoteUrl,
                sourceKey = "user-url:${userId}:$remoteUrl:$sizePx",
                cacheUserId = userId,
                cacheAvatarUrl = remoteUrl,
                isGroupAvatar = false
            )
        }

        return null
    }

    private fun avatarRequestSizePx(): Int {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        return (CHAT_AVATAR_SIZE_DP * density).roundToInt().coerceAtLeast(64)
    }

    private suspend fun fetchGroupSenderRenderInfo(userId: String): GroupSenderRenderInfo? {
        val snapshot = FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .get()
            .await()

        val displayName = snapshot.getString("username")?.takeIf { it.isNotBlank() }
            ?: snapshot.getString("displayName")?.takeIf { it.isNotBlank() }
            ?: snapshot.getString("phoneNumber")?.takeIf { it.isNotBlank() }
            ?: return null
        val avatarUrl = snapshot.getString("profileImageUrl")?.takeIf { it.isNotBlank() }
            ?: snapshot.getString("profileImageFullUrl")?.takeIf { it.isNotBlank() }
            ?: ""

        return GroupSenderRenderInfo(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl
        )
    }

    private suspend fun warmMediaCaches(
        context: Context,
        messages: List<Message>,
        retainTargetsForChatId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            messages.forEach { message ->
                mediaWarmModels(message).forEach { model ->
                    runCatching {
                        Glide.with(context)
                            .downloadOnly()
                            .load(model)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit()
                            .get()
                    }
                }
            }
        }

        val preloadSpecs = withContext(Dispatchers.Default) {
            buildMediaPreloadSpecs(context, messages.takeLast(MEMORY_WARM_LIMIT))
        }
        if (preloadSpecs.isEmpty()) return

        // Submit all decode requests in parallel on the main thread, then await them on IO.
        // Using submit().get() (with no cancel on timeout) instead of preload() gives us
        // two advantages: (1) requests run in parallel so total wait ≈ max(decode times), not
        // their sum; (2) we can gate the warm-complete signal used by primeChatOpen so the
        // coroutine only returns once GIFs are actually in memory cache.
        // Do NOT cancel timed-out futures — cancel() aborts the decode so the GifDrawable
        // never reaches memory cache. Let them continue; they'll populate it for the next open.
        val futures = withContext(Dispatchers.Main.immediate) {
            preloadSpecs.map { spec ->
                Glide.with(context)
                    .load(spec.model)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .fitCenter()
                    .override(spec.widthPx, spec.heightPx)
                    .submit(spec.widthPx, spec.heightPx)
            }
        }
        retainTargetsForChatId?.let { chatId ->
            retainedMediaPreloadsByChatId.remove(chatId)?.targets?.forEach { stale ->
                    runCatching { Glide.with(context).clear(stale) }
            }
            retainedMediaPreloadsByChatId[chatId] = RetainedMediaPreload(
                targets = CopyOnWriteArrayList(futures),
                specs = preloadSpecs.map { it.toRetainedSpec() }
            )
        }
        withContext(Dispatchers.IO) {
            futures.forEach { future ->
                runCatching { future.get(1_000L, TimeUnit.MILLISECONDS) }
            }
        }
    }

    private data class MediaPreloadSpec(
        val model: Any,
        val widthPx: Int,
        val heightPx: Int,
        val type: MessageType,
        val messageId: String = ""
    ) {
        fun cacheKey(): String = "${model}|${widthPx}x${heightPx}|${type}"

        fun toRetainedSpec(): RetainedMediaPreloadSpec = RetainedMediaPreloadSpec(
            model = model,
            widthPx = widthPx,
            heightPx = heightPx,
            type = type,
            messageId = messageId
        )
    }

    private fun buildMediaPreloadSpecs(context: Context, messages: List<Message>): List<MediaPreloadSpec> {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val viewportWidthPx = android.content.res.Resources.getSystem().displayMetrics.widthPixels
        return messages.flatMap { message ->
            if (message.type == MessageType.MEDIA_GROUP && message.mediaItemsList.size > 1) {
                val groupTileSizePx = ChatMediaLayoutSizing.mediaGroupTilePreloadSizePx(density, viewportWidthPx)
                return@flatMap message.mediaItemsList.take(4).mapIndexedNotNull { index, item ->
                    val rawModel = resolveExistingLocalModel(item.localUri)
                        ?: item.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: item.localUri?.takeIf { it.isNotBlank() }
                        ?: item.displayUrl?.takeIf { it.isNotBlank() }
                        ?: item.url?.takeIf { it.isNotBlank() }
                    val model = MessagePreviewCacheManager
                        .resolveMediaGroupPreviewModel(message.id, index, rawModel)
                        ?.takeIf { it.toString().isNotBlank() }
                        ?: return@mapIndexedNotNull null
                    MediaPreloadSpec(
                        model = model,
                        widthPx = groupTileSizePx,
                        heightPx = groupTileSizePx,
                        type = MessageType.MEDIA_GROUP,
                        messageId = message.id
                    )
                }
            }

            val model = mediaWarmModel(message) ?: return@flatMap emptyList()
            val targetSize = ChatMediaLayoutSizing.singleMediaSizePx(
                type = message.type,
                aspect = message.aspectRatio.coerceAtLeast(0.1f),
                density = density,
                viewportWidthPx = viewportWidthPx,
                isVideoNote = message.type == MessageType.VIDEO && message.isVideoNote
            )
            listOf(MediaPreloadSpec(
                model = model,
                widthPx = targetSize.widthPx,
                heightPx = targetSize.heightPx,
                type = message.type,
                messageId = message.id
            ))
        }
    }

    private fun mediaWarmModels(message: Message): List<Any> {
        if (message.type != MessageType.MEDIA_GROUP) {
            return listOfNotNull(mediaWarmModel(message))
        }

        return message.mediaItemsList.take(4).mapIndexedNotNull { index, item ->
            val rawModel = resolveExistingLocalModel(item.localUri)
                ?: item.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: item.localUri?.takeIf { it.isNotBlank() }
                ?: item.displayUrl?.takeIf { it.isNotBlank() }
                ?: item.url?.takeIf { it.isNotBlank() }
            MessagePreviewCacheManager
                .resolveMediaGroupPreviewModel(message.id, index, rawModel)
                ?.takeIf { it.toString().isNotBlank() }
        }
    }

    private fun mediaWarmModel(message: Message): Any? {
        return when (message.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI -> resolveChatBubbleMediaModel(message)

            MessageType.VIDEO -> if (message.isVideoNote) {
                MessagePreviewCacheManager.resolveMediaPreviewModel(
                    message,
                    message.localUri ?: message.thumbnailUrl ?: message.videoUrl ?: ""
                )
            } else {
                resolveChatBubbleMediaModel(message)
            }

            MessageType.MEDIA_GROUP -> message.mediaItemsList.firstOrNull()?.let { item ->
                val rawModel = resolveExistingLocalModel(item.localUri)
                    ?: item.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: item.localUri?.takeIf { it.isNotBlank() }
                    ?: item.url?.takeIf { it.isNotBlank() }
                MessagePreviewCacheManager.resolveMediaGroupPreviewModel(message.id, 0, rawModel)
            }

            else -> null
        }
    }

    private fun shouldPrepareRetainedPredictiveMedia(source: String): Boolean {
        return source == "chat_list_visible" ||
            source == "chat_list_compose_visible" ||
            source == "chat_list_compose_archived"
    }

    private fun shouldRetainFirstPaintMedia(message: Message): Boolean {
        return when (message.type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEDIA_GROUP -> true
            else -> false
        }
    }

    private fun firstPaintRetainedMessages(
        messages: List<Message>,
        listItems: List<ChatListItem>
    ): List<Message> {
        if (messages.isEmpty()) return emptyList()

        val submittedMessages = listItems
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message }
            .toList()
        val submittedTailWindow = submittedMessages.takeLast(FIRST_PAINT_VISIBLE_WINDOW_ITEMS)
        val visibleTailWindow = messages.takeLast(FIRST_PAINT_VISIBLE_WINDOW_ITEMS)
        val recentMediaWindow = messages.asReversed()
            .asSequence()
            .filter(::shouldRetainFirstPaintMedia)
            .take(FIRST_PAINT_RETAINED_MESSAGE_LIMIT)
            .toList()
            .asReversed()

        return (submittedTailWindow.asReversed() + visibleTailWindow.asReversed() + recentMediaWindow.asReversed())
            .filter(::shouldRetainFirstPaintMedia)
            .distinctBy { it.id }
            .take(FIRST_PAINT_RETAINED_MESSAGE_LIMIT)
    }

    private fun resolveExistingLocalModel(localUri: String?): String? {
        val candidate = localUri?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val parsed = Uri.parse(candidate)
            when {
                parsed.scheme == "content" -> candidate
                parsed.scheme == "file" || candidate.startsWith("/") -> {
                    val path = parsed.path ?: candidate
                    candidate.takeIf { File(path).exists() && File(path).length() > 0L }
                }
                parsed.scheme != null -> candidate
                else -> candidate.takeIf { File(candidate).exists() && File(candidate).length() > 0L }
            }
        }.getOrNull()
    }
}