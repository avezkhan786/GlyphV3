package com.glyph.glyph_v3.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Per-chat settings persistence using SharedPreferences (fast, synchronous reads).
 * Each chat has its own namespaced key prefix so settings are isolated.
 *
 * Settings stored:
 *  - Mute notifications (message, call, status)
 *  - Notification tone name
 *  - Vibrate mode (Default / Off / Short / Long)
 *  - Disappearing messages timer (off / 24h / 7d / 90d)
 *  - Advanced chat privacy toggle
 *  - Chat lock toggle
 *  - Translate messages toggle
 *  - Media visibility toggle
 */
object ChatSettingsDataStore {

    private const val PREFS_NAME = "chat_settings"
    private val lockedChatVersion = MutableStateFlow(0)

    // ── Keys (prefixed per chat) ───────────────────────────────────
    private const val KEY_MUTE_MESSAGES = "mute_messages"
    private const val KEY_NOTIFICATION_TONE = "notification_tone"
    private const val KEY_MSG_VIBRATE = "msg_vibrate"
    private const val KEY_MUTE_CALLS = "mute_calls"
    private const val KEY_CALL_RINGTONE = "call_ringtone"
    private const val KEY_CALL_VIBRATE = "call_vibrate"
    private const val KEY_MUTE_STATUS = "mute_status"
    private const val KEY_DISAPPEARING_TIMER = "disappearing_timer"
    private const val KEY_ADVANCED_PRIVACY = "advanced_privacy"
    private const val KEY_CHAT_LOCK = "chat_lock"
    private const val KEY_TRANSLATE = "translate"
    private const val KEY_MEDIA_VISIBILITY = "media_visibility"
    private const val KEY_GROUP_PERMISSION_EDIT_SETTINGS = "group_permission_edit_settings"
    private const val KEY_GROUP_PERMISSION_SEND_MESSAGES = "group_permission_send_messages"
    private const val KEY_GROUP_PERMISSION_ADD_MEMBERS = "group_permission_add_members"
    private const val KEY_GROUP_PERMISSION_INVITE_LINK = "group_permission_invite_link"
    private const val KEY_GROUP_PERMISSION_APPROVE_MEMBERS = "group_permission_approve_members"

    // Disappearing message timer values
    const val DISAPPEARING_OFF = "off"
    const val DISAPPEARING_24H = "24h"
    const val DISAPPEARING_7D = "7d"
    const val DISAPPEARING_90D = "90d"

    // Vibrate modes
    const val VIBRATE_DEFAULT = "Default"
    const val VIBRATE_OFF = "Off"
    const val VIBRATE_SHORT = "Short"
    const val VIBRATE_LONG = "Long"

    data class GroupPermissions(
        val editSettings: Boolean = true,
        val sendMessages: Boolean = true,
        val addMembers: Boolean = true,
        val inviteViaLink: Boolean = false,
        val approveMembers: Boolean = false
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(chatId: String, field: String): String = "${chatId}_$field"

    fun observeLockedChatVersion(): StateFlow<Int> = lockedChatVersion.asStateFlow()

    private fun notifyLockedChatsChanged() {
        lockedChatVersion.update { it + 1 }
    }

    // ── Message Notification Mute ──────────────────────────────────
    fun isMessagesMuted(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_MUTE_MESSAGES), false)

    suspend fun setMessagesMuted(context: Context, chatId: String, muted: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_MUTE_MESSAGES), muted).apply()
        }
    }

    // ── Notification Tone ──────────────────────────────────────────
    fun getNotificationTone(context: Context, chatId: String): String =
        prefs(context).getString(key(chatId, KEY_NOTIFICATION_TONE), "Default (New World)") ?: "Default (New World)"

    suspend fun setNotificationTone(context: Context, chatId: String, tone: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(key(chatId, KEY_NOTIFICATION_TONE), tone).apply()
        }
    }

    // ── Message Vibrate ────────────────────────────────────────────
    fun getMessageVibrate(context: Context, chatId: String): String =
        prefs(context).getString(key(chatId, KEY_MSG_VIBRATE), VIBRATE_DEFAULT) ?: VIBRATE_DEFAULT

    suspend fun setMessageVibrate(context: Context, chatId: String, mode: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(key(chatId, KEY_MSG_VIBRATE), mode).apply()
        }
    }

    // ── Call Mute ──────────────────────────────────────────────────
    fun isCallsMuted(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_MUTE_CALLS), false)

    suspend fun setCallsMuted(context: Context, chatId: String, muted: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_MUTE_CALLS), muted).apply()
        }
    }

    // ── Call Ringtone ──────────────────────────────────────────────
    fun getCallRingtone(context: Context, chatId: String): String =
        prefs(context).getString(key(chatId, KEY_CALL_RINGTONE), "Default (ReelAudio-55672)") ?: "Default (ReelAudio-55672)"

    suspend fun setCallRingtone(context: Context, chatId: String, tone: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(key(chatId, KEY_CALL_RINGTONE), tone).apply()
        }
    }

    // ── Call Vibrate ───────────────────────────────────────────────
    fun getCallVibrate(context: Context, chatId: String): String =
        prefs(context).getString(key(chatId, KEY_CALL_VIBRATE), VIBRATE_DEFAULT) ?: VIBRATE_DEFAULT

    suspend fun setCallVibrate(context: Context, chatId: String, mode: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(key(chatId, KEY_CALL_VIBRATE), mode).apply()
        }
    }

    // ── Status Mute ────────────────────────────────────────────────
    fun isStatusMuted(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_MUTE_STATUS), false)

    suspend fun setStatusMuted(context: Context, chatId: String, muted: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_MUTE_STATUS), muted).apply()
        }
    }

    // ── Disappearing Messages ──────────────────────────────────────
    fun getDisappearingTimer(context: Context, chatId: String): String =
        prefs(context).getString(key(chatId, KEY_DISAPPEARING_TIMER), DISAPPEARING_OFF) ?: DISAPPEARING_OFF

    suspend fun setDisappearingTimer(context: Context, chatId: String, timer: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(key(chatId, KEY_DISAPPEARING_TIMER), timer).apply()
        }
    }

    // ── Advanced Chat Privacy ──────────────────────────────────────
    fun isAdvancedPrivacyEnabled(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_ADVANCED_PRIVACY), false)

    suspend fun setAdvancedPrivacyEnabled(context: Context, chatId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_ADVANCED_PRIVACY), enabled).apply()
        }
    }

    // ── Chat Lock ──────────────────────────────────────────────────
    fun isChatLocked(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_CHAT_LOCK), false)

    suspend fun setChatLocked(context: Context, chatId: String, locked: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_CHAT_LOCK), locked).apply()
            notifyLockedChatsChanged()
        }
    }

    // ── Translate ──────────────────────────────────────────────────
    fun isTranslateEnabled(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_TRANSLATE), false)

    suspend fun setTranslateEnabled(context: Context, chatId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_TRANSLATE), enabled).apply()
        }
    }

    // ── Media Visibility ───────────────────────────────────────────
    fun isMediaVisible(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(key(chatId, KEY_MEDIA_VISIBILITY), true)

    suspend fun setMediaVisible(context: Context, chatId: String, visible: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(key(chatId, KEY_MEDIA_VISIBILITY), visible).apply()
        }
    }

    // ── Group Permissions ────────────────────────────────────────
    fun getGroupPermissions(context: Context, chatId: String): GroupPermissions {
        val prefs = prefs(context)
        return GroupPermissions(
            editSettings = prefs.getBoolean(key(chatId, KEY_GROUP_PERMISSION_EDIT_SETTINGS), true),
            sendMessages = prefs.getBoolean(key(chatId, KEY_GROUP_PERMISSION_SEND_MESSAGES), true),
            addMembers = prefs.getBoolean(key(chatId, KEY_GROUP_PERMISSION_ADD_MEMBERS), true),
            inviteViaLink = prefs.getBoolean(key(chatId, KEY_GROUP_PERMISSION_INVITE_LINK), false),
            approveMembers = prefs.getBoolean(key(chatId, KEY_GROUP_PERMISSION_APPROVE_MEMBERS), false)
        )
    }

    suspend fun setGroupPermissions(context: Context, chatId: String, permissions: GroupPermissions) {
        withContext(Dispatchers.IO) {
            prefs(context).edit()
                .putBoolean(key(chatId, KEY_GROUP_PERMISSION_EDIT_SETTINGS), permissions.editSettings)
                .putBoolean(key(chatId, KEY_GROUP_PERMISSION_SEND_MESSAGES), permissions.sendMessages)
                .putBoolean(key(chatId, KEY_GROUP_PERMISSION_ADD_MEMBERS), permissions.addMembers)
                .putBoolean(key(chatId, KEY_GROUP_PERMISSION_INVITE_LINK), permissions.inviteViaLink)
                .putBoolean(key(chatId, KEY_GROUP_PERMISSION_APPROVE_MEMBERS), permissions.approveMembers)
                .apply()
        }
    }

    fun groupPermissionsSummary(permissions: GroupPermissions): String {
        val memberActions = buildList {
            if (permissions.editSettings) add("edit settings")
            if (permissions.sendMessages) add("send messages")
            if (permissions.addMembers) add("add members")
        }
        val memberSummary = when {
            memberActions.isEmpty() -> "Only admins can manage the group"
            memberActions.size == 1 -> "Members can ${memberActions.first()}"
            memberActions.size == 2 -> "Members can ${memberActions[0]} and ${memberActions[1]}"
            else -> "Members can ${memberActions.dropLast(1).joinToString(", ")} and ${memberActions.last()}"
        }
        return if (permissions.approveMembers) {
            "$memberSummary; admins approve new members"
        } else {
            memberSummary
        }
    }

    // ── Locked Chat IDs set ────────────────────────────────────────
    // Maintained in addition to per-chat keys for efficient enumeration.
    private const val KEY_LOCKED_IDS_SET = "locked_chat_ids_set"

    /** All currently locked chat IDs (instant, synchronous). */
    fun getLockedChatIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_IDS_SET, emptySet())?.toSet() ?: emptySet()

    /** Adds or removes the given chatId from the locked-chat set and the per-chat key. */
    suspend fun updateLockedSet(context: Context, chatId: String, locked: Boolean) {
        withContext(Dispatchers.IO) {
            val current = prefs(context)
                .getStringSet(KEY_LOCKED_IDS_SET, emptySet())!!.toMutableSet()
            if (locked) current.add(chatId) else current.remove(chatId)
            prefs(context).edit()
                .putStringSet(KEY_LOCKED_IDS_SET, current)
                .putBoolean(key(chatId, KEY_CHAT_LOCK), locked)
                .apply()
            notifyLockedChatsChanged()
        }
    }

    /** Helper to read disappearing timer as a human-readable label */
    fun disappearingTimerLabel(timer: String): String = when (timer) {
        DISAPPEARING_24H -> "24 hours"
        DISAPPEARING_7D -> "7 days"
        DISAPPEARING_90D -> "90 days"
        else -> "Off"
    }

    // ── Secret Code & Hide Locked Chats ────────────────────────────
    private const val KEY_HIDE_LOCKED_CHATS = "hide_locked_chats_enabled"
    private const val KEY_SECRET_CODE_HASH = "secret_code_hash"
    private const val KEY_LOCKED_BANNER_DISMISSED = "locked_banner_dismissed"

    fun isHideLockedChatsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_LOCKED_CHATS, false)

    suspend fun setHideLockedChatsEnabled(context: Context, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(KEY_HIDE_LOCKED_CHATS, enabled).apply()
        }
    }

    fun isSecretCodeSet(context: Context): Boolean =
        prefs(context).getString(KEY_SECRET_CODE_HASH, null) != null

    suspend fun setSecretCode(context: Context, code: String) {
        withContext(Dispatchers.IO) {
            val hash = sha256(code)
            prefs(context).edit()
                .putString(KEY_SECRET_CODE_HASH, hash)
                .putBoolean(KEY_HIDE_LOCKED_CHATS, true)
                .apply()
        }
    }

    fun verifySecretCode(context: Context, code: String): Boolean {
        val storedHash = prefs(context).getString(KEY_SECRET_CODE_HASH, null) ?: return false
        return sha256(code) == storedHash
    }

    suspend fun clearSecretCode(context: Context) {
        withContext(Dispatchers.IO) {
            prefs(context).edit()
                .remove(KEY_SECRET_CODE_HASH)
                .putBoolean(KEY_HIDE_LOCKED_CHATS, false)
                .apply()
        }
    }

    fun isLockedBannerDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCKED_BANNER_DISMISSED, false)

    suspend fun setLockedBannerDismissed(context: Context, dismissed: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit().putBoolean(KEY_LOCKED_BANNER_DISMISSED, dismissed).apply()
        }
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
