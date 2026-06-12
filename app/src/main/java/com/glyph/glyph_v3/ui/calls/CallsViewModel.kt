package com.glyph.glyph_v3.ui.calls

import android.app.Application
import android.text.format.DateUtils
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.local.entity.LocalCallLog
import com.glyph.glyph_v3.data.repo.CallLogRepository
import com.glyph.glyph_v3.data.models.CallData
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CallsUiState(
    val isLoading: Boolean = true,
    val items: List<CallHistoryUiModel> = emptyList()
)

data class CallHistoryUiModel(
    val groupKey: String,
    val peerId: String,
    val displayName: String,
    val avatarUrl: String,
    val callType: CallType,
    val direction: CallHistoryDirection,
    val timestamp: Long,
    val timeLabel: String,
    val count: Int
)

enum class CallHistoryDirection {
    OUTGOING,
    INCOMING,
    MISSED,
    MISSED_OUTGOING
}

class CallsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private var currentUserId = auth.currentUser?.uid.orEmpty()

    private val _uiState = MutableStateFlow(CallsUiState())
    val uiState: StateFlow<CallsUiState> = _uiState.asStateFlow()

    private var groupedCallIdsByKey: Map<String, List<String>> = emptyMap()
    private var callLogsJob: Job? = null
    private var remoteImportJob: Job? = null
    private var rebuildJob: Job? = null
    /** Separate from rebuildJob so observeContactCacheVersion's cancel does not kill the Firestore fetch. */
    private var phoneWarmupJob: Job? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var isObservingCalls = false

    // Latest snapshot of call logs + userId for cache-version-triggered rebuilds
    private var latestCallLogs: List<LocalCallLog>? = null
    private var latestUserId: String = ""
    private var contactCacheObserverJob: Job? = null

    init {
        startAuthObservation()
    }

    /**
     * React to device contact cache changes. When the user adds/renames/deletes
     * a contact (or when the cache finishes its initial async load after process
     * start), [ContactDisplayNameResolver.cacheVersion] increments. We rebuild
     * the call history so display names reflect the latest contacts immediately.
     */
    private fun observeContactCacheVersion() {
        contactCacheObserverJob?.cancel()
        contactCacheObserverJob = viewModelScope.launch {
            ContactDisplayNameResolver.cacheVersion
                .filter { version -> version > 0L && latestCallLogs != null && latestUserId.isNotBlank() }
                .collect { version ->
                    Log.d(TAG, "Contact cache version changed to $version; rebuilding call history names")
                    val callLogs = latestCallLogs ?: return@collect
                    rebuildJob?.cancel()
                    rebuildJob = launch(Dispatchers.Default) {
                        // Re-seed userPhoneCache from stored phone numbers so that
                        // even if the main rebuildState job hasn't run yet (or ran
                        // before contacts were loaded), names resolve correctly here.
                        for (log in callLogs) {
                            if (log.callerPhone.isNotBlank()) {
                                ContactDisplayNameResolver.cacheUserPhone(log.callerId, log.callerPhone)
                            }
                            if (log.receiverPhone.isNotBlank()) {
                                ContactDisplayNameResolver.cacheUserPhone(log.receiverId, log.receiverPhone)
                            }
                        }
                        val result = buildRebuildResult(callLogs, latestUserId)
                        withContext(Dispatchers.Main.immediate) {
                            groupedCallIdsByKey = result.groupedCallIdsByKey
                            _uiState.value = CallsUiState(
                                isLoading = false,
                                items = result.items
                            )
                        }
                    }
                }
        }
    }

    fun refresh() {
        val liveUserId = auth.currentUser?.uid.orEmpty()
        if (liveUserId.isBlank()) {
            startAuthObservation()
            return
        }

        currentUserId = liveUserId
        startLocalObservation(liveUserId)
    }

    fun ensureObservationActive() {
        val liveUserId = auth.currentUser?.uid.orEmpty()
        if (liveUserId.isBlank()) {
            if (!isObservingCalls) {
                startAuthObservation()
            }
            return
        }

        if (liveUserId != currentUserId || !isObservingCalls) {
            currentUserId = liveUserId
            startLocalObservation(liveUserId)
        }
    }

    fun deleteGroups(groupKeys: Set<String>) {
        if (groupKeys.isEmpty()) return

        val callIds = groupKeys
            .flatMap { groupKey -> groupedCallIdsByKey[groupKey].orEmpty() }
            .distinct()
        if (callIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            CallLogRepository.deleteCallLogs(appContext, callIds)
        }
    }

    override fun onCleared() {
        super.onCleared()
        callLogsJob?.cancel()
        remoteImportJob?.cancel()
        rebuildJob?.cancel()
        phoneWarmupJob?.cancel()
        contactCacheObserverJob?.cancel()
        authStateListener?.let(auth::removeAuthStateListener)
        authStateListener = null
    }

    private fun startAuthObservation() {
        authStateListener?.let(auth::removeAuthStateListener)
        authStateListener = FirebaseAuth.AuthStateListener { authState ->
            val nextUserId = authState.currentUser?.uid.orEmpty()
            when {
                nextUserId.isBlank() -> {
                    currentUserId = ""
                    resetObservedCalls(stopLoading = false)
                }

                nextUserId != currentUserId || !isObservingCalls -> {
                    currentUserId = nextUserId
                    startLocalObservation(nextUserId)
                }
            }
        }
        authStateListener?.let(auth::addAuthStateListener)

        // Firebase Auth caches the signed-in user locally and makes it available
        // synchronously via auth.currentUser. Check it NOW rather than relying
        // solely on the async AuthStateListener, which can fire 1-2 s after
        // ViewModel creation on cold start (observed: ~1929 ms delay in trace).
        // Using auth.currentUser directly eliminates that delay.
        val liveUserId = auth.currentUser?.uid.orEmpty()
        if (liveUserId.isNotBlank() && !isObservingCalls) {
            currentUserId = liveUserId
            startLocalObservation(liveUserId)
        }
    }

    private fun startLocalObservation(userId: String) {
        resetObservedCalls(stopLoading = true)
        isObservingCalls = true
        Log.d(TAG, "[PerfTrace] startLocalObservation userId=$userId cacheVersion=${ContactDisplayNameResolver.cacheVersion.value} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()} ms")
        callLogsJob = viewModelScope.launch {
            CallLogRepository.observeCallLogs(appContext, userId).collect { callLogs ->
                Log.d(TAG, "[PerfTrace] Room emit: ${callLogs.size} logs, cacheVersion=${ContactDisplayNameResolver.cacheVersion.value}, elapsedRealtime=${android.os.SystemClock.elapsedRealtime()} ms")
                // Snapshot for contact-cache-version-triggered rebuilds
                latestCallLogs = callLogs.toList()
                latestUserId = userId
                rebuildState(callLogs, userId)
                maybeBackfillRemoteHistoryIfNeeded(userId, callLogs)
            }
        }
    }

    private fun resetObservedCalls(stopLoading: Boolean) {
        remoteImportJob?.cancel()
        remoteImportJob = null
        callLogsJob?.cancel()
        callLogsJob = null
        rebuildJob?.cancel()
        rebuildJob = null
        phoneWarmupJob?.cancel()
        phoneWarmupJob = null
        groupedCallIdsByKey = emptyMap()
        isObservingCalls = false

        _uiState.value = CallsUiState(
            isLoading = stopLoading && currentUserId.isNotBlank(),
            items = emptyList()
        )
    }

    private fun maybeBackfillRemoteHistoryIfNeeded(userId: String, callLogs: List<LocalCallLog>) {
        if (callLogs.isNotEmpty()) {
            Log.d(TAG, "Skipping remote backfill for userId=$userId because local rows already exist: ${callLogs.size}")
            return
        }
        if (remoteImportJob?.isActive == true) {
            Log.d(TAG, "Skipping remote backfill for userId=$userId because import is already running")
            return
        }
        if (CallLogRepository.wasRemoteHistoryImported(appContext, userId)) {
            Log.d(TAG, "Skipping remote backfill for userId=$userId because import flag is already set")
            return
        }

        Log.d(TAG, "Local call log empty for userId=$userId; attempting remote backfill")
        _uiState.value = _uiState.value.copy(isLoading = true)

        remoteImportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val remoteHistory = fetchRemoteHistory(userId)
                Log.d(TAG, "Remote call history fetch for userId=$userId returned ${remoteHistory.size} rows")
                if (remoteHistory.isNotEmpty()) {
                    CallLogRepository.upsertCallLogs(appContext, remoteHistory)
                    CallLogRepository.markRemoteHistoryImported(appContext, userId)
                    CallLogRepository.logRecentCallLogs(appContext, userId, reason = "after_remote_backfill")
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        if (currentUserId == userId && _uiState.value.items.isEmpty()) {
                            _uiState.value = CallsUiState(isLoading = false, items = emptyList())
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to backfill remote call history", error)
                withContext(Dispatchers.Main.immediate) {
                    if (_uiState.value.items.isEmpty()) {
                        _uiState.value = CallsUiState(isLoading = false, items = emptyList())
                    }
                }
            }
        }
    }

    private suspend fun fetchRemoteHistory(userId: String): List<CallData> {
        val outgoing = firestore.collection(CALLS_COLLECTION)
            .whereEqualTo(FIELD_CALLER_ID, userId)
            .get()
            .await()

        val incoming = firestore.collection(CALLS_COLLECTION)
            .whereEqualTo(FIELD_RECEIVER_ID, userId)
            .get()
            .await()

        return (outgoing.documents + incoming.documents)
            .associateBy { document -> document.id }
            .values
            .mapNotNull { document ->
                document.toObject(CallData::class.java)?.copy(
                    callId = document.getString(FIELD_CALL_ID).orEmpty().ifBlank { document.id }
                )
            }
            .filter(::isHistoricalCall)
    }

    private fun isHistoricalCall(callData: CallData): Boolean {
        return callData.endedAt > 0L || callData.callState() in HISTORICAL_STATES
    }

    private fun rebuildState(callLogs: List<LocalCallLog>, userId: String) {
        val callLogSnapshot = callLogs.toList()
        Log.d(TAG, "[PerfTrace] rebuildState called: logs=${callLogs.size}, cacheVersion=${ContactDisplayNameResolver.cacheVersion.value}, elapsedRealtime=${android.os.SystemClock.elapsedRealtime()} ms")

        rebuildJob?.cancel()
        phoneWarmupJob?.cancel()
        rebuildJob = viewModelScope.launch(Dispatchers.Default) {
            // ── Seed userPhoneCache from locally-stored phone numbers ──────
            // For call-log entries that already have a phone number (new calls
            // after the migration), cache the mapping immediately. This makes
            // the 2-param getDisplayName() fallback work for legacy entries
            // that share the same peer.
            for (log in callLogSnapshot) {
                if (log.callerPhone.isNotBlank()) {
                    ContactDisplayNameResolver.cacheUserPhone(log.callerId, log.callerPhone)
                }
                if (log.receiverPhone.isNotBlank()) {
                    ContactDisplayNameResolver.cacheUserPhone(log.receiverId, log.receiverPhone)
                }
            }

            // ── Wait for device contact cache ─────────────────────────────
            // Keep isLoading=true while we wait. Contacts are loaded once in
            // GlyphApplication.onCreate() on Dispatchers.IO; by the time the
            // user reaches the Calls tab they are nearly always ready. We wait
            // up to 1500 ms so the first paint always shows saved contact names
            // rather than profile names that then flip — matching WhatsApp's
            // behaviour. For warm reopens cacheVersion > 0 already, so this
            // block is skipped entirely and there is no delay.
            val waitStartMs = android.os.SystemClock.elapsedRealtime()
            if (ContactDisplayNameResolver.cacheVersion.value == 0L) {
                Log.d(TAG, "[PerfTrace] rebuildState: cacheVersion=0, waiting for contacts at elapsedRealtime=$waitStartMs ms")
                try {
                    withTimeout(1500L) {
                        ContactDisplayNameResolver.cacheVersion.first { it > 0L }
                    }
                    Log.d(TAG, "[PerfTrace] rebuildState: contacts ready after ${android.os.SystemClock.elapsedRealtime() - waitStartMs} ms wait")
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "[PerfTrace] rebuildState: contact cache TIMEOUT after ${android.os.SystemClock.elapsedRealtime() - waitStartMs} ms — painting with profile names")
                }
            } else {
                Log.d(TAG, "[PerfTrace] rebuildState: cacheVersion already ${ContactDisplayNameResolver.cacheVersion.value} — no wait needed")
            }

            // ── Paint immediately ────────────────────────────────────────
            val buildStartMs = android.os.SystemClock.elapsedRealtime()
            val result = buildRebuildResult(callLogSnapshot, userId)
            Log.d(TAG, "[PerfTrace] rebuildState: buildRebuildResult took ${android.os.SystemClock.elapsedRealtime() - buildStartMs} ms for ${result.items.size} items")
            withContext(Dispatchers.Main.immediate) {
                groupedCallIdsByKey = result.groupedCallIdsByKey
                _uiState.value = CallsUiState(
                    isLoading = false,
                    items = result.items
                )
                Log.d(TAG, "[PerfTrace] rebuildState: first paint committed at elapsedRealtime=${android.os.SystemClock.elapsedRealtime()} ms")
                if (contactCacheObserverJob?.isActive != true) {
                    observeContactCacheVersion()
                }
            }
        }

        // ── Background: fetch ALL user phones for legacy entries ──────────
        // Run in a SEPARATE job so observeContactCacheVersion cancelling
        // rebuildJob does not kill this Firestore fetch. Legacy entries (those
        // with blank callerPhone/receiverPhone) depend on this to resolve
        // saved contact names.
        phoneWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("users")
                    .get()
                    .await()
                var cached = 0
                for (doc in snapshot.documents) {
                    val phone = listOf("phoneNumber", "phone", "mobile")
                        .asSequence()
                        .mapNotNull { key -> doc.getString(key) }
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        ?: continue
                    ContactDisplayNameResolver.cacheUserPhone(doc.id, phone)
                    cached++
                }
                if (cached > 0) {
                    Log.d(TAG, "Preloaded $cached user phone numbers for call-log resolution")
                    // Persist to local storage so the next cold start restores these
                    // mappings synchronously — eliminating the Firestore round-trip delay
                    // for legacy entries with blank callerPhone/receiverPhone.
                    ContactDisplayNameResolver.persistUserPhones(appContext)
                    // ── Repaint now that userPhoneCache is warm ──────────────────
                    val refreshed = withContext(Dispatchers.Default) {
                        buildRebuildResult(callLogSnapshot, userId)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (latestCallLogs === callLogSnapshot || latestCallLogs?.toList() == callLogSnapshot) {
                            groupedCallIdsByKey = refreshed.groupedCallIdsByKey
                            _uiState.value = CallsUiState(
                                isLoading = false,
                                items = refreshed.items
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload user phones: ${e.message}")
            }
        }
    }

    private fun buildRebuildResult(
        callLogs: List<LocalCallLog>,
        userId: String
    ): RebuildResult {
        val allCalls = callLogs.sortedByDescending(::anchorTimestamp)
        val locale = Locale.getDefault()
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("h:mm a", locale)
        val weekdayFormat = SimpleDateFormat("EEE h:mm a", locale)
        val dateFormat = SimpleDateFormat("dd MMM h:mm a", locale)

        val flattenedItems = allCalls.mapNotNull { callLog ->
            toFlatHistoryItem(callLog, userId, calendar)
        }

        val groupedItems = flattenedItems.groupConsecutiveCalls()
        val groupedCallIdsByKey = groupedItems.associate { grouped ->
            grouped.groupKey to grouped.callIds
        }

        val items = groupedItems.map { grouped ->
            CallHistoryUiModel(
                groupKey = grouped.groupKey,
                peerId = grouped.peerId,
                displayName = grouped.displayName,
                avatarUrl = grouped.avatarUrl,
                callType = grouped.callType,
                direction = grouped.direction,
                timestamp = grouped.timestamp,
                timeLabel = formatRelativeTime(
                    timestamp = grouped.timestamp,
                    now = now,
                    timeFormat = timeFormat,
                    weekdayFormat = weekdayFormat,
                    dateFormat = dateFormat
                ),
                count = grouped.count
            )
        }

        return RebuildResult(
            items = items,
            groupedCallIdsByKey = groupedCallIdsByKey
        )
    }

    private fun toFlatHistoryItem(
        callLog: LocalCallLog,
        userId: String,
        calendar: Calendar
    ): FlatCallHistoryItem? {
        val isOutgoing = callLog.callerId == userId
        val peerId = if (isOutgoing) callLog.receiverId else callLog.callerId
        if (peerId.isBlank()) {
            Log.w(
                TAG,
                "Dropping call log from UI because peerId is blank for userId=$userId callId=${callLog.callId} caller=${callLog.callerId} receiver=${callLog.receiverId} status=${callLog.status}"
            )
            return null
        }

        val timestamp = anchorTimestamp(callLog)
        val rawDisplayName = if (isOutgoing) callLog.receiverName else callLog.callerName
        // Pass the phone number stored in the call log directly so the contact
        // lookup goes straight through contactNameCache without needing
        // userPhoneCache to be warm first. Falls back to userPhoneCache[peerId]
        // (seeded below) for legacy entries that have no stored phone, then to
        // remoteProfileName, then to fallback — matching WhatsApp's priority.
        val peerPhone = (if (isOutgoing) callLog.receiverPhone else callLog.callerPhone)
            .takeIf { it.isNotBlank() }
        val displayName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = peerId,
            remoteProfileName = rawDisplayName,
            remotePhoneNumber = peerPhone,
            fallback = UNKNOWN_CONTACT
        )
        val avatarUrl = if (isOutgoing) callLog.receiverAvatar else callLog.callerAvatar

        return FlatCallHistoryItem(
            callId = callLog.callId,
            peerId = peerId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            callType = callLog.callType(),
            direction = callLog.toDirection(isOutgoing),
            timestamp = timestamp,
            dayKey = dayKey(timestamp, calendar)
        )
    }

    private fun List<FlatCallHistoryItem>.groupConsecutiveCalls(): List<GroupedCallHistoryItem> {
        if (isEmpty()) return emptyList()

        val grouped = mutableListOf<GroupedCallHistoryItem>()
        for (item in this) {
            val previous = grouped.lastOrNull()
            if (previous != null && previous.canMerge(item)) {
                grouped[grouped.lastIndex] = previous.copy(
                    count = previous.count + 1,
                    callIds = previous.callIds + item.callId
                )
            } else {
                grouped += GroupedCallHistoryItem(
                    groupKey = item.groupKey(),
                    callIds = listOf(item.callId),
                    peerId = item.peerId,
                    displayName = item.displayName,
                    avatarUrl = item.avatarUrl,
                    callType = item.callType,
                    direction = item.direction,
                    timestamp = item.timestamp,
                    dayKey = item.dayKey,
                    count = 1
                )
            }
        }
        return grouped
    }

    private fun GroupedCallHistoryItem.canMerge(item: FlatCallHistoryItem): Boolean {
        return peerId == item.peerId &&
            callType == item.callType &&
            direction == item.direction &&
            dayKey == item.dayKey
    }

    private fun LocalCallLog.toDirection(isOutgoing: Boolean): CallHistoryDirection {
        val state = callState()
        val isMissed = state == CallState.MISSED ||
            state == CallState.NO_ANSWER ||
            state == CallState.BUSY ||
            state == CallState.DECLINED

        return when {
            isMissed && isOutgoing -> CallHistoryDirection.MISSED_OUTGOING
            isMissed -> CallHistoryDirection.MISSED
            isOutgoing -> CallHistoryDirection.OUTGOING
            else -> CallHistoryDirection.INCOMING
        }
    }

    private fun anchorTimestamp(callLog: LocalCallLog): Long {
        return when {
            callLog.endedAt > 0L -> callLog.endedAt
            callLog.answeredAt > 0L -> callLog.answeredAt
            else -> callLog.createdAt
        }
    }

    private fun formatRelativeTime(
        timestamp: Long,
        now: Long,
        timeFormat: SimpleDateFormat,
        weekdayFormat: SimpleDateFormat,
        dateFormat: SimpleDateFormat
    ): String {
        val diff = now - timestamp
        if (diff < DateUtils.MINUTE_IN_MILLIS) {
            return JUST_NOW
        }
        if (diff < DateUtils.HOUR_IN_MILLIS) {
            val minutes = (diff / DateUtils.MINUTE_IN_MILLIS).coerceAtLeast(1)
            return if (minutes == 1L) ONE_MINUTE_AGO else "$minutes minutes ago"
        }

        return when {
            DateUtils.isToday(timestamp) -> "Today ${timeFormat.format(Date(timestamp))}"
            DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> "Yesterday ${timeFormat.format(Date(timestamp))}"
            diff < DateUtils.WEEK_IN_MILLIS -> weekdayFormat.format(Date(timestamp))
            else -> dateFormat.format(Date(timestamp))
        }
    }

    private fun FlatCallHistoryItem.groupKey(): String {
        return buildString {
            append(peerId)
            append('|')
            append(callType.name)
            append('|')
            append(direction.name)
            append('|')
            append(dayKey)
        }
    }

    private fun dayKey(timestamp: Long, calendar: Calendar): String {
        calendar.timeInMillis = timestamp
        return String.format(
            Locale.US,
            "%04d%02d%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun LocalCallLog.callType(): CallType = if (type == "VIDEO") CallType.VIDEO else CallType.VOICE

    private fun LocalCallLog.callState(): CallState = when (status) {
        "initiating" -> CallState.INITIATING
        "ringing" -> CallState.RINGING
        "accepted" -> CallState.ACCEPTED
        "connected" -> CallState.CONNECTED
        "ended" -> CallState.ENDED
        "declined" -> CallState.DECLINED
        "missed" -> CallState.MISSED
        "busy" -> CallState.BUSY
        "no_answer" -> CallState.NO_ANSWER
        else -> CallState.ENDED
    }

    private data class FlatCallHistoryItem(
        val callId: String,
        val peerId: String,
        val displayName: String,
        val avatarUrl: String,
        val callType: CallType,
        val direction: CallHistoryDirection,
        val timestamp: Long,
        val dayKey: String
    )

    private data class GroupedCallHistoryItem(
        val groupKey: String,
        val callIds: List<String>,
        val peerId: String,
        val displayName: String,
        val avatarUrl: String,
        val callType: CallType,
        val direction: CallHistoryDirection,
        val timestamp: Long,
        val dayKey: String,
        val count: Int
    )

    private data class RebuildResult(
        val items: List<CallHistoryUiModel>,
        val groupedCallIdsByKey: Map<String, List<String>>
    )

    private companion object {
        const val TAG = "CallsViewModel"
        const val CALLS_COLLECTION = "calls"
        const val FIELD_CALLER_ID = "callerId"
        const val FIELD_RECEIVER_ID = "receiverId"
        const val FIELD_CALL_ID = "callId"
        const val UNKNOWN_CONTACT = "Unknown"
        const val JUST_NOW = "Just now"
        const val ONE_MINUTE_AGO = "1 minute ago"

        val HISTORICAL_STATES = setOf(
            CallState.ENDED,
            CallState.DECLINED,
            CallState.MISSED,
            CallState.NO_ANSWER,
            CallState.BUSY
        )
    }
}