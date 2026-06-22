package com.glyph.glyph_v3.utils

import android.content.Context
import android.os.SystemClock
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.ui.chat.ChatListItem
import com.glyph.glyph_v3.ui.chat.BubbleGroupPosition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * Manages in-memory caching of chat messages to enable instant display
 * when opening a chat screen, eliminating the "pop-in" delay.
 */
object MessageCacheManager {

    private const val MAX_CHAT_SNAPSHOTS = 12
    // Fast opening: Keep cache snapshot small so serialization/restoration on open is cheap.
    // 500-message snapshots made opening heavy (2289ms). 150 covers the initial view + a
    // small scroll buffer; pagination handles anything beyond smoothly during scroll.
    private const val MAX_RENDER_SNAPSHOT_MESSAGES = 150
    private const val MEMORY_SNAPSHOT_TTL_MS = 10 * 60 * 1000L
    private const val DISK_SNAPSHOT_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val ROOT_DIR = "chat_render_snapshots"

    private val gson = Gson()
    private val diskIoExecutor = Executors.newSingleThreadExecutor()

    data class ChatRenderSnapshot(
        val recentMessages: List<Message>,
        val listItems: List<ChatListItem>,
        val createdAtEpochMs: Long,
        val createdAtElapsedMs: Long?,
        val source: String
    ) {
        val ageMs: Long
            get() = when (val elapsedAt = createdAtElapsedMs) {
                null -> (System.currentTimeMillis() - createdAtEpochMs).coerceAtLeast(0L)
                else -> (SystemClock.elapsedRealtime() - elapsedAt).coerceAtLeast(0L)
            }
    }

    private data class PersistedRenderSnapshot(
        val recentMessages: List<Message>,
        val items: List<PersistedRenderItem>,
        val createdAtEpochMs: Long,
        val source: String
    )

    private data class PersistedRenderItem(
        val kind: String,
        val messageId: String? = null,
        val dateString: String? = null,
        val groupPosition: String? = null,
        val isEmojiContent: Boolean = false
    )

    private val lock = Any()
    private val snapshots = object : LinkedHashMap<String, ChatRenderSnapshot>(MAX_CHAT_SNAPSHOTS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChatRenderSnapshot>?): Boolean {
            return size > MAX_CHAT_SNAPSHOTS
        }
    }

    @Volatile
    private var rootDir: File? = null

    fun init(context: Context) {
        ensureInitialized(context.applicationContext)
    }

    fun getSnapshot(chatId: String): ChatRenderSnapshot? = synchronized(lock) {
        val snapshot = snapshots[chatId] ?: return@synchronized null
        if (snapshot.ageMs > MEMORY_SNAPSHOT_TTL_MS) {
            snapshots.remove(chatId)
            return@synchronized null
        }
        snapshot
    }

    fun getRenderableSnapshot(chatId: String): ChatRenderSnapshot? {
        getSnapshot(chatId)?.let { return it }
        val diskSnapshot = loadSnapshotFromDisk(chatId) ?: return null
        synchronized(lock) {
            snapshots[chatId] = diskSnapshot
        }
        return diskSnapshot
    }

    fun putSnapshot(
        chatId: String,
        recentMessages: List<Message>,
        listItems: List<ChatListItem>,
        source: String
    ) {
        val nowEpochMs = System.currentTimeMillis()
        val existingMessages = synchronized(lock) { snapshots[chatId]?.recentMessages }.orEmpty()
        val effectiveRecentMessages = (if (recentMessages.isNotEmpty()) recentMessages else existingMessages)
            .takeLast(MAX_RENDER_SNAPSHOT_MESSAGES)
        val effectiveListItems = if (effectiveRecentMessages.isNotEmpty()) {
            restoreRenderItems(
                compactPersistedItems(listItems, effectiveRecentMessages),
                effectiveRecentMessages
            )
        } else {
            compactListOnlyItems(listItems)
        }
        val snapshot = ChatRenderSnapshot(
            recentMessages = effectiveRecentMessages,
            listItems = effectiveListItems,
            createdAtEpochMs = nowEpochMs,
            createdAtElapsedMs = SystemClock.elapsedRealtime(),
            source = source
        )
        synchronized(lock) {
            snapshots[chatId] = snapshot
        }

        persistSnapshotAsync(chatId, snapshot)
    }

    fun getMessages(chatId: String): List<ChatListItem>? {
        return getRenderableSnapshot(chatId)?.listItems
    }

    fun putMessages(chatId: String, messages: List<ChatListItem>) {
        val recentMessages = messages
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message }
            .toList()
            .takeLast(MAX_RENDER_SNAPSHOT_MESSAGES)

        putSnapshot(
            chatId = chatId,
            recentMessages = recentMessages,
            listItems = messages,
            source = "legacy_list_only"
        )
    }

    fun clear(chatId: String) {
        synchronized(lock) {
            snapshots.remove(chatId)
        }
        snapshotFile(chatId)?.delete()
    }

    fun clearAll() {
        synchronized(lock) {
            snapshots.clear()
        }
        rootDir?.listFiles()?.forEach { it.delete() }
    }

    /**
     * Check if we have cached messages for this chat
     */
    fun hasMessages(chatId: String): Boolean {
        return !getRenderableSnapshot(chatId)?.listItems.isNullOrEmpty()
    }

    fun hasFreshSnapshot(chatId: String): Boolean {
        return getRenderableSnapshot(chatId) != null
    }

    fun hasFreshMemorySnapshot(chatId: String): Boolean {
        return getSnapshot(chatId) != null
    }

    private fun persistSnapshotAsync(chatId: String, snapshot: ChatRenderSnapshot) {
        val snapshotDir = rootDir ?: return
        val persisted = buildPersistedSnapshot(snapshot)
        diskIoExecutor.execute {
            runCatching {
                val targetFile = File(snapshotDir, snapshotFileName(chatId))
                val tempFile = File(snapshotDir, "${targetFile.name}.tmp")
                tempFile.writeText(gson.toJson(persisted))
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
        }
    }

    private fun buildPersistedSnapshot(snapshot: ChatRenderSnapshot): PersistedRenderSnapshot {
        val persistedItems = compactPersistedItems(snapshot.listItems, snapshot.recentMessages)
        return PersistedRenderSnapshot(
            recentMessages = snapshot.recentMessages,
            items = persistedItems,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            source = snapshot.source
        )
    }

    private fun compactPersistedItems(
        listItems: List<ChatListItem>,
        recentMessages: List<Message>
    ): List<PersistedRenderItem> {
        if (recentMessages.isEmpty()) return emptyList()

        val recentIds = recentMessages.asSequence().map { it.id }.toHashSet()
        val persisted = ArrayList<PersistedRenderItem>(recentMessages.size + 8)
        var pendingHeader: String? = null

        listItems.forEach { item ->
            when (item) {
                is ChatListItem.DateHeader -> pendingHeader = item.dateString
                is ChatListItem.GroupIntroItem -> Unit
                is ChatListItem.MessageItem -> {
                    if (!recentIds.contains(item.message.id)) return@forEach
                    val header = pendingHeader
                    if (!header.isNullOrEmpty() && persisted.lastOrNull()?.dateString != header) {
                        persisted += PersistedRenderItem(
                            kind = "header",
                            dateString = header
                        )
                    }
                    persisted += PersistedRenderItem(
                        kind = "message",
                        messageId = item.message.id,
                        dateString = item.dateString,
                        groupPosition = item.groupPosition.name,
                        isEmojiContent = item.isEmojiContent
                    )
                }
                is ChatListItem.TypingIndicator -> Unit
            }
        }

        if (persisted.isNotEmpty()) return persisted

        return recentMessages.map { message ->
            PersistedRenderItem(
                kind = "message",
                messageId = message.id,
                isEmojiContent = false,
                groupPosition = BubbleGroupPosition.SINGLE.name
            )
        }
    }

    private fun compactListOnlyItems(listItems: List<ChatListItem>): List<ChatListItem> {
        if (listItems.isEmpty()) return emptyList()

        val tailMessageIds = listItems
            .asSequence()
            .filterIsInstance<ChatListItem.MessageItem>()
            .map { it.message.id }
            .toList()
            .takeLast(MAX_RENDER_SNAPSHOT_MESSAGES)
            .toHashSet()
        if (tailMessageIds.isEmpty()) return emptyList()

        val compacted = ArrayList<ChatListItem>(tailMessageIds.size + 8)
        var pendingHeader: ChatListItem.DateHeader? = null
        listItems.forEach { item ->
            when (item) {
                is ChatListItem.DateHeader -> pendingHeader = item
                is ChatListItem.MessageItem -> {
                    if (!tailMessageIds.contains(item.message.id)) return@forEach
                    pendingHeader?.let { header ->
                        if (compacted.lastOrNull() != header) compacted += header
                    }
                    pendingHeader = null
                    compacted += item
                }
                is ChatListItem.GroupIntroItem,
                is ChatListItem.TypingIndicator -> Unit
            }
        }

        return compacted
    }

    private fun restoreRenderItems(
        persistedItems: List<PersistedRenderItem>,
        recentMessages: List<Message>
    ): List<ChatListItem> {
        if (persistedItems.isEmpty() || recentMessages.isEmpty()) return emptyList()

        val messagesById = recentMessages.associateBy { it.id }
        val restoredItems = ArrayList<ChatListItem>(persistedItems.size)
        persistedItems.forEach { item ->
            when (item.kind) {
                "header" -> {
                    val dateString = item.dateString ?: return@forEach
                    restoredItems += ChatListItem.DateHeader(dateString)
                }
                "message" -> {
                    val message = messagesById[item.messageId] ?: return@forEach
                    restoredItems += ChatListItem.MessageItem(
                        message = message,
                        groupPosition = item.groupPosition
                            ?.let { runCatching { BubbleGroupPosition.valueOf(it) }.getOrNull() }
                            ?: BubbleGroupPosition.SINGLE,
                        dateString = item.dateString.orEmpty(),
                        isEmojiContent = item.isEmojiContent
                    )
                }
            }
        }

        if (restoredItems.isNotEmpty()) return restoredItems

        return recentMessages.map { message ->
            ChatListItem.MessageItem(
                message = message,
                groupPosition = BubbleGroupPosition.SINGLE,
                dateString = "",
                isEmojiContent = false
            )
        }
    }

    private fun loadSnapshotFromDisk(chatId: String): ChatRenderSnapshot? {
        val file = snapshotFile(chatId) ?: return null
        if (!file.exists() || file.length() <= 0L) return null

        return runCatching {
            val json = file.readText()
            val type = object : TypeToken<PersistedRenderSnapshot>() {}.type
            val persisted = gson.fromJson<PersistedRenderSnapshot>(json, type) ?: return@runCatching null
            if ((System.currentTimeMillis() - persisted.createdAtEpochMs).coerceAtLeast(0L) > DISK_SNAPSHOT_TTL_MS) {
                file.delete()
                return@runCatching null
            }

            val boundedRecentMessages = persisted.recentMessages.takeLast(MAX_RENDER_SNAPSHOT_MESSAGES)
            val restoredItems = restoreRenderItems(persisted.items, boundedRecentMessages)

            if (restoredItems.isEmpty() && boundedRecentMessages.isEmpty()) {
                null
            } else {
                ChatRenderSnapshot(
                    recentMessages = boundedRecentMessages,
                    listItems = restoredItems,
                    createdAtEpochMs = persisted.createdAtEpochMs,
                    createdAtElapsedMs = null,
                    source = "${persisted.source}_disk"
                )
            }
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    private fun ensureInitialized(context: Context) {
        if (rootDir != null) return
        synchronized(this) {
            if (rootDir != null) return
            rootDir = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
        }
    }

    private fun snapshotFile(chatId: String): File? {
        val dir = rootDir ?: return null
        return File(dir, snapshotFileName(chatId))
    }

    private fun snapshotFileName(chatId: String): String {
        return buildString(chatId.length + 5) {
            chatId.forEach { ch ->
                append(if (ch.isLetterOrDigit()) ch else '_')
            }
            append(".json")
        }
    }
}
