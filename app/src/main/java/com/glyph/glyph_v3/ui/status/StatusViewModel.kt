package com.glyph.glyph_v3.ui.status

import android.app.Application
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusPrivacyMode
import com.glyph.glyph_v3.data.models.StatusPrivacySetting
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.models.UserStatusGroup
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import android.webkit.MimeTypeMap
import java.util.UUID

/** Human-readable upload stage for UI display. */
enum class UploadStage {
    IDLE, PREPARING, COMPRESSING, UPLOADING, PROCESSING, RETRYING, DONE;

    val label: String get() = when (this) {
        IDLE        -> ""
        PREPARING   -> "Preparing…"
        COMPRESSING -> "Compressing video…"
        UPLOADING   -> "Uploading…"
        PROCESSING  -> "Processing…"
        RETRYING    -> "Retrying upload…"
        DONE        -> "Done"
    }
}

data class StatusUiState(
    val myStatuses: List<Status> = emptyList(),
    val contactStatusGroups: List<UserStatusGroup> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadStage: UploadStage = UploadStage.IDLE,
    val uploadIndex: Int = 0,
    val uploadTotal: Int = 0,
    val isReplying: Boolean = false,
    val replyStatusId: String? = null,
    val error: String? = null,
    val myUsername: String = "",
    val myAvatarUrl: String = ""
)

data class StatusPrivacyUiState(
    val mode: StatusPrivacyMode = StatusPrivacyMode.MY_CONTACTS,
    val excludedContacts: List<String> = emptyList(),
    val includedContacts: List<String> = emptyList(),
    val allContacts: List<User> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false
)

data class MediaItem(
    val uri: Uri,
    val type: StatusType,
    val caption: String = ""
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private val _privacyState = MutableStateFlow(StatusPrivacyUiState())
    val privacyState: StateFlow<StatusPrivacyUiState> = _privacyState.asStateFlow()

    private var uploadObserverJob: Job? = null

    init {
        startListening()
        loadMyProfile()
    }

    private fun startListening() {
        StatusRepository.startListeningMyStatuses()
        StatusRepository.startListeningContactStatuses()

        viewModelScope.launch {
            StatusRepository.myStatuses.collect { statuses ->
                updateUiState { current ->
                    if (current.myStatuses == statuses) current else current.copy(myStatuses = statuses)
                }
            }
        }
        viewModelScope.launch {
            StatusRepository.contactStatuses.collect { groups ->
                updateUiState { current ->
                    if (current.contactStatusGroups == groups) current
                    else current.copy(contactStatusGroups = groups)
                }
            }
        }
    }

    private fun loadMyProfile() {
        val uid = StatusRepository.currentUserId ?: return
        viewModelScope.launch {
            try {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val doc = firestore.collection("users").document(uid).get().await()
                val username = doc.getString("username") ?: ""
                val avatarUrl = doc.getString("profileImageUrl") ?: ""
                updateUiState { current ->
                    if (current.myUsername == username && current.myAvatarUrl == avatarUrl) current
                    else current.copy(myUsername = username, myAvatarUrl = avatarUrl)
                }
            } catch (_: Exception) {}
        }
    }

    fun uploadTextStatus(text: String, backgroundColor: Int, fontStyle: String = "default") {
        if (text.isBlank()) return
        viewModelScope.launch {
            updateUiState { it.copy(isUploading = true, error = null) }
            val result = StatusRepository.uploadTextStatus(text, backgroundColor, fontStyle)
            updateUiState {
                it.copy(
                    isUploading = false,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun uploadMediaStatus(uri: Uri, type: StatusType, caption: String = "") {
        uploadMultipleMediaStatuses(listOf(MediaItem(uri, type, caption)))
    }

    fun uploadMultipleMediaStatuses(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            updateUiState {
                it.copy(
                isUploading = true,
                uploadStage = UploadStage.PREPARING,
                uploadProgress = 0f,
                uploadIndex = 0,
                uploadTotal = items.size,
                error = null
                )
            }

            val app = getApplication<Application>()
            val workIds = mutableListOf<UUID>()

            for ((index, item) in items.withIndex()) {
                updateUiState {
                    it.copy(
                        uploadIndex = index + 1,
                        uploadProgress = 0f,
                        uploadStage = UploadStage.PREPARING
                    )
                }

                // Copy media to internal cache so it survives process death.
                val tempFile = withContext(Dispatchers.IO) {
                    copyUriToCache(app, item.uri, item.type)
                }
                if (tempFile == null) {
                    updateUiState {
                        it.copy(
                            isUploading = false,
                            uploadStage = UploadStage.IDLE,
                            error = "Failed to prepare item ${index + 1} for upload"
                        )
                    }
                    return@launch
                }

                val statusId = UUID.randomUUID().toString()
                val visibleTo = try { StatusRepository.resolveVisibleTo() }
                                catch (_: Exception) { emptyList() }

                val workId = StatusUploadWorker.enqueue(
                    context    = app,
                    localFile  = tempFile,
                    type       = item.type,
                    caption    = item.caption,
                    statusId   = statusId,
                    durationMs = 0L,
                    visibleTo  = visibleTo
                )
                workIds.add(workId)
            }

            // Observe the last-enqueued job for progress feedback on the preview screen.
            observeUploadWork(workIds)
        }
    }

    fun uploadVoiceStatus(audioFile: File) {
        viewModelScope.launch {
            updateUiState {
                it.copy(
                    isUploading = true,
                    uploadStage = UploadStage.PREPARING,
                    uploadProgress = 0f,
                    uploadIndex = 1,
                    uploadTotal = 1,
                    error = null
                )
            }
            val durationMs = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(audioFile.absolutePath)
                val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                d?.toLongOrNull() ?: 0L
            } catch (_: Exception) { 0L }

            val app = getApplication<Application>()
            val statusId = UUID.randomUUID().toString()
            val visibleTo = try { StatusRepository.resolveVisibleTo() }
                            catch (_: Exception) { emptyList() }

            val workId = StatusUploadWorker.enqueue(
                context    = app,
                localFile  = audioFile,
                type       = StatusType.VOICE,
                caption    = "🎙 Voice status",
                statusId   = statusId,
                durationMs = durationMs,
                visibleTo  = visibleTo
            )
            observeUploadWork(listOf(workId))
        }
    }

    /** Observe WorkManager progress for the given work IDs and mirror into [_uiState]. */
    private fun observeUploadWork(workIds: List<UUID>) {
        if (workIds.isEmpty()) return

        val app = getApplication<Application>()
        val wm = WorkManager.getInstance(app)
        val total = workIds.size

        uploadObserverJob?.cancel()
        uploadObserverJob = viewModelScope.launch {
            combine(workIds.map { wm.getWorkInfoByIdFlow(it) }) { infos -> infos.toList() }
                .takeWhile { rawInfos ->
                    val infos = rawInfos.filterNotNull()
                    if (infos.isEmpty()) {
                        return@takeWhile true
                    }

                    val failedInfo = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                    if (failedInfo != null) {
                        val errMsg = failedInfo.outputData.getString("error") ?: "Upload failed"
                        updateUiState {
                            it.copy(
                                isUploading = false,
                                uploadStage = UploadStage.IDLE,
                                uploadProgress = 0f,
                                uploadIndex = 0,
                                uploadTotal = 0,
                                error = errMsg
                            )
                        }
                        return@takeWhile false
                    }

                    val allFinished = infos.all {
                        it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.CANCELLED
                    }
                    if (allFinished) {
                        updateUiState {
                            it.copy(
                                isUploading = false,
                                uploadStage = UploadStage.IDLE,
                                uploadProgress = 0f,
                                uploadIndex = 0,
                                uploadTotal = 0
                            )
                        }
                        return@takeWhile false
                    }

                    val activeIndex = infos.indexOfFirst {
                        it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.CANCELLED
                    }.coerceAtLeast(0)
                    val activeInfo = infos[activeIndex]
                    val completedCount = infos.count { it.state == WorkInfo.State.SUCCEEDED }
                    val aggregateProgress = infos.sumOf { info ->
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> 1.0
                            WorkInfo.State.RUNNING,
                            WorkInfo.State.ENQUEUED,
                            WorkInfo.State.BLOCKED -> info.progress
                                .getFloat(
                                    StatusUploadWorker.KEY_PROGRESS_FRACTION,
                                    info.progress
                                        .getInt(StatusUploadWorker.KEY_PROGRESS, 0)
                                        .coerceIn(0, 100) / 100f
                                )
                                .coerceIn(0f, 1f)
                                .toDouble()
                            else -> 0.0
                        }
                    }.toFloat() / total.coerceAtLeast(1)

                    val stage = activeInfo.progress.getString(StatusUploadWorker.KEY_STAGE)
                        ?.let { runCatching { UploadStage.valueOf(it) }.getOrNull() }
                        ?: when (activeInfo.state) {
                            WorkInfo.State.ENQUEUED,
                            WorkInfo.State.BLOCKED -> UploadStage.PREPARING
                            else -> UploadStage.UPLOADING
                        }

                    updateUiState {
                        it.copy(
                            isUploading = true,
                            uploadStage = stage,
                            uploadProgress = aggregateProgress.coerceIn(0f, 1f),
                            uploadIndex = (completedCount + 1).coerceAtMost(total),
                            uploadTotal = total
                        )
                    }

                    true
                }
                .collect { rawInfos ->
                    // Collection body intentionally empty; work is handled in takeWhile for cleanup.
                }
            uploadObserverJob = null
            }
        }

    private fun updateUiState(transform: (StatusUiState) -> StatusUiState) {
        val current = _uiState.value
        val updated = transform(current)
        if (updated != current) {
            _uiState.value = updated
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Copy a content URI into the app's internal cache directory so it persists
     * across process death (content URIs can become invalid after the source app
     * revokes access or the system clears the grant).
     */
    /**
     * Copy a content URI to a temp file in the app's internal cache.
     *
     * Strategy (most reliable first):
     *  1. Query [MediaStore.MediaColumns.DATA] for the real filesystem path and copy
     *     the file directly — completely bypasses OEM ContentProvider interception.
     *  2. [ContentResolver.openFileDescriptor] raw FD fallback.
     *  3. [ContentResolver.openInputStream] last resort.
     */
    private fun copyUriToCache(context: android.content.Context, uri: Uri, type: StatusType): File? {
        return try {
            val ext = inferMediaExtension(context, uri, type)
            val dest = File(context.cacheDir, "status_pending_${UUID.randomUUID()}.$ext")

            // 1. Resolve the actual file path from MediaStore (works even on API 34).
            //    Direct File I/O is immune to Samsung's thumbnail-substitution quirk.
            val actualPath: String? = try {
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null }

            var copied = false
            if (!actualPath.isNullOrBlank()) {
                val src = File(actualPath)
                if (src.exists() && src.length() > 0) {
                    src.inputStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied = true
                } else {
                    Log.w("StatusViewModel", "copyUriToCache: actualPath=$actualPath exists=${src.exists()} length=${src.length()} — unusable")
                }
            } else {
                Log.w("StatusViewModel", "copyUriToCache: MediaStore.DATA returned null for uri=$uri")
            }

            if (!copied) {
                // 2. openFileDescriptor — raw fd, less susceptible to OEM filtering.
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    pfd.use { fd ->
                        java.io.FileInputStream(fd.fileDescriptor).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    copied = dest.length() > 0
                }
            }

            if (!copied) {
                // 3. Last resort: openInputStream.
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val result = if (dest.exists() && dest.length() > 0) dest else null
            result
        } catch (e: Exception) {
            Log.e("StatusViewModel", "Failed to copy URI to cache", e)
            null
        }
    }

    private fun inferMediaExtension(context: android.content.Context, uri: Uri, type: StatusType): String {
        val reportedMime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.substringBefore(';')
            ?.lowercase()
        val urlExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        return when {
            type == StatusType.VOICE -> urlExtension ?: "m4a"
            reportedMime == "image/gif" || urlExtension == "gif" -> "gif"
            reportedMime == "image/webp" || urlExtension == "webp" -> "webp"
            reportedMime == "image/png" || urlExtension == "png" -> "png"
            reportedMime == "image/jpeg" || reportedMime == "image/jpg" || urlExtension in setOf("jpg", "jpeg") -> "jpg"
            reportedMime == "video/webm" || urlExtension == "webm" -> "webm"
            reportedMime == "video/quicktime" || urlExtension == "mov" -> "mov"
            type == StatusType.VIDEO -> urlExtension ?: "mp4"
            else -> urlExtension ?: if (type == StatusType.IMAGE) "jpg" else "mp4"
        }
    }

    fun deleteStatus(statusId: String) {
        viewModelScope.launch {
            val result = StatusRepository.deleteStatus(statusId)
            if (result.isFailure) {
                updateUiState {
                    it.copy(error = result.exceptionOrNull()?.message ?: "Failed to delete status")
                }
            }
        }
    }

    fun markViewed(statusId: String) {
        viewModelScope.launch {
            StatusRepository.markStatusViewed(statusId)
        }
    }

    fun clearError() {
        updateUiState { it.copy(error = null) }
    }

    // ── Privacy ──

    fun loadPrivacySettings() {
        viewModelScope.launch {
            _privacyState.value = _privacyState.value.copy(isLoading = true)
            StatusRepository.loadPrivacySetting()
            val setting = StatusRepository.privacySetting.value
            val contacts = StatusRepository.getAllContacts()
            _privacyState.value = StatusPrivacyUiState(
                mode = setting.mode,
                excludedContacts = setting.excludedContacts,
                includedContacts = setting.includedContacts,
                allContacts = contacts,
                isLoading = false
            )
        }
    }

    fun updatePrivacyMode(mode: StatusPrivacyMode) {
        _privacyState.value = _privacyState.value.copy(mode = mode, savedSuccessfully = false)
    }

    fun toggleExcludedContact(userId: String) {
        val current = _privacyState.value.excludedContacts.toMutableList()
        if (userId in current) current.remove(userId) else current.add(userId)
        _privacyState.value = _privacyState.value.copy(excludedContacts = current, savedSuccessfully = false)
    }

    fun toggleIncludedContact(userId: String) {
        val current = _privacyState.value.includedContacts.toMutableList()
        if (userId in current) current.remove(userId) else current.add(userId)
        _privacyState.value = _privacyState.value.copy(includedContacts = current, savedSuccessfully = false)
    }

    fun savePrivacySettings() {
        viewModelScope.launch {
            _privacyState.value = _privacyState.value.copy(isSaving = true)
            val state = _privacyState.value
            val setting = StatusPrivacySetting(
                mode = state.mode,
                excludedContacts = state.excludedContacts,
                includedContacts = state.includedContacts
            )
            val result = StatusRepository.savePrivacySetting(setting)
            _privacyState.value = _privacyState.value.copy(
                isSaving = false,
                savedSuccessfully = result.isSuccess
            )
        }
    }

    // ── Status Reply ──

    fun sendStatusReply(status: Status, replyText: String) {
        val currentUserId = StatusRepository.currentUserId ?: return
        if (replyText.isBlank() || status.userId == currentUserId) return

        viewModelScope.launch {
            try {
                updateUiState { it.copy(isReplying = true, replyStatusId = status.id) }
                val app = getApplication<Application>()
                val db = AppDatabase.getDatabase(app)
                val repo = RealtimeMessageRepository(
                    db.messageDao(), db.chatDao(), db.deletedMessageDao(), app
                )

                // Resolve other user info from Firestore
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val otherDoc = firestore.collection("users").document(status.userId).get().await()
                val otherUsername = otherDoc.getString("username") ?: ""
                val otherAvatar = otherDoc.getString("profileImageUrl") ?: ""

                // Construct deterministic chatId
                val chatId = listOf(currentUserId, status.userId).sorted().joinToString("_")

                // Determine status thumbnail URL (prefer dedicated thumbnail for VIDEO,
                // fall back to mediaUrl for IMAGE and VIDEO when thumbnailUrl is absent).
                val thumbnailUrl = when (status.type) {
                    StatusType.IMAGE -> status.thumbnailUrl.ifEmpty { status.mediaUrl }
                    StatusType.VIDEO -> status.thumbnailUrl.ifEmpty { status.mediaUrl }
                    else -> null
                }

                withContext(Dispatchers.IO) {
                    repo.sendStatusReply(
                        chatId = chatId,
                        text = replyText,
                        otherUserId = status.userId,
                        otherUsername = otherUsername,
                        otherUserAvatar = otherAvatar,
                        statusId = status.id,
                        statusOwnerId = status.userId,
                        statusThumbnailUrl = thumbnailUrl,
                        statusType = status.type.name,
                        statusText = if (status.type == StatusType.TEXT) status.text else status.caption,
                        statusBgColor = if (status.type == StatusType.TEXT) status.backgroundColor else null
                    )
                }
                updateUiState { it.copy(isReplying = false, replyStatusId = null) }
            } catch (e: Exception) {
                android.util.Log.e("StatusViewModel", "Failed to send status reply", e)
                updateUiState { it.copy(isReplying = false, replyStatusId = null, error = "Failed to send reply") }
            }
        }
    }

    fun sendStatusLike(status: Status) {
        val currentUserId = StatusRepository.currentUserId ?: return
        if (status.userId == currentUserId || status.likedByIds.contains(currentUserId)) return

        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val db = AppDatabase.getDatabase(app)
                val repo = RealtimeMessageRepository(
                    db.messageDao(), db.chatDao(), db.deletedMessageDao(), app
                )

                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val otherDoc = firestore.collection("users").document(status.userId).get().await()
                val otherUsername = otherDoc.getString("username") ?: ""
                val otherAvatar = otherDoc.getString("profileImageUrl") ?: ""
                val chatId = listOf(currentUserId, status.userId).sorted().joinToString("_")
                val thumbnailUrl = when (status.type) {
                    StatusType.IMAGE -> status.thumbnailUrl.ifEmpty { status.mediaUrl }
                    StatusType.VIDEO -> status.thumbnailUrl.ifEmpty { status.mediaUrl }
                    else -> null
                }

                withContext(Dispatchers.IO) {
                    repo.sendStatusReply(
                        chatId = chatId,
                        text = "❤️",
                        otherUserId = status.userId,
                        otherUsername = otherUsername,
                        otherUserAvatar = otherAvatar,
                        statusId = status.id,
                        statusOwnerId = status.userId,
                        statusThumbnailUrl = thumbnailUrl,
                        statusType = status.type.name,
                        statusText = if (status.type == StatusType.TEXT) status.text else status.caption,
                        statusBgColor = if (status.type == StatusType.TEXT) status.backgroundColor else null
                    )
                    StatusRepository.likeStatus(status.id)
                }
            } catch (e: Exception) {
                android.util.Log.e("StatusViewModel", "Failed to send status like", e)
                updateUiState { it.copy(error = "Failed to like status") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        uploadObserverJob?.cancel()
    }
}
