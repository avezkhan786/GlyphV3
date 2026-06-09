package com.glyph.glyph_v3.data.models

import androidx.compose.runtime.Immutable

@Immutable
data class User(
    val id: String = "",
    val phoneNumber: String = "",
    val username: String = "",
    val bio: String = "",
    val profileImageUrl: String = "",
    val profileImageFullUrl: String = "",
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val fcmToken: String = ""
)
