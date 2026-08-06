package com.glyph.glyph_v3.ui.auth

import com.google.firebase.auth.PhoneAuthProvider

/**
 * In-memory session holder for the phone auth flow.
 *
 * Stores verification state that must be shared between [PhoneNumberActivity]
 * and [OtpVerificationActivity]. This mirrors the previous behavior where
 * [com.glyph.glyph_v3.ui.login.LoginActivity] held these as instance fields —
 * process death loses the state (same as before), requiring the user to re-enter
 * their number.
 */
object AuthFlowSession {

    /** The verification ID returned by Firebase after sending the OTP. */
    var verificationId: String? = null

    /** Token for resending the OTP, returned by Firebase on first [onCodeSent]. */
    var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    /** The full E.164 phone number being verified. */
    var phoneNumber: String? = null

    /** The national digits only (without dial code), for display purposes. */
    var nationalDigits: String? = null

    /** The selected dial code (e.g. "91"). */
    var dialCode: String? = null

    /** Whether the coordinator has already handled routing (prevents double-navigation). */
    var hasRouted: Boolean = false

    /**
     * Clears all stored state. Call when the auth flow completes or is abandoned.
     */
    fun clear() {
        verificationId = null
        resendToken = null
        phoneNumber = null
        nationalDigits = null
        dialCode = null
        hasRouted = false
    }
}
