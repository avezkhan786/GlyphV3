package com.glyph.glyph_v3.ui.users

/**
 * Represents a contact from the user's device, indicating whether they are registered on Glyph.
 */
data class ContactListItem(
    val name: String,
    val phoneNumber: String, // The raw number from the device
    val isRegistered: Boolean,
    val registeredUser: com.glyph.glyph_v3.data.models.User? = null // Firebase user data if registered
)
