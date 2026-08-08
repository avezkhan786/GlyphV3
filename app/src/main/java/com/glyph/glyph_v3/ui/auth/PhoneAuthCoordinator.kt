package com.glyph.glyph_v3.ui.auth

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/**
 * Singleton coordinator that ports Firebase phone authentication logic from
 * [com.glyph.glyph_v3.ui.login.LoginActivity] into a UI-independent object.
 *
 * All Firebase API calls, callback handling, and post-sign-in routing are
 * preserved exactly as they were. This object knows nothing about Compose —
 * it only calls the callbacks it's given.
 *
 * Being a singleton ensures that [PhoneNumberActivity] and [OtpVerificationActivity]
 * share the same Firebase auth callback state, preventing double-sign-in races.
 */
object PhoneAuthCoordinator {

    private const val TAG = "PhoneAuthCoordinator"

    private val auth = FirebaseAuth.getInstance()
    private val repository = FirebaseRepository()

    // Track whether the coordinator is currently executing a sign-in to prevent
    // parallel sign-in attempts from auto-verification + manual code entry.
    private var signInInProgress = false

    /**
     * Initiates phone number verification.
     */
    fun startVerification(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: (verificationId: String, token: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onFailed: (FirebaseException) -> Unit,
        onAutoCompleted: () -> Unit
    ) {
        AuthFlowSession.hasRouted = false
        signInInProgress = false

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInAndRoute(activity, credential, onAutoCompleted, onFailed)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    signInInProgress = false
                    onFailed(e)
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    AuthFlowSession.verificationId = verificationId
                    AuthFlowSession.resendToken = token
                    onCodeSent(verificationId, token)
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    /**
     * Resends the OTP code using the existing [resendToken].
     */
    fun resendCode(
        activity: Activity,
        phoneNumber: String,
        token: PhoneAuthProvider.ForceResendingToken,
        onCodeSent: (verificationId: String, newToken: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onFailed: (FirebaseException) -> Unit
    ) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setForceResendingToken(token)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInAndRoute(activity, credential, {}, onFailed)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    signInInProgress = false
                    onFailed(e)
                }

                override fun onCodeSent(
                    verificationId: String,
                    newToken: PhoneAuthProvider.ForceResendingToken
                ) {
                    AuthFlowSession.verificationId = verificationId
                    AuthFlowSession.resendToken = newToken
                    onCodeSent(verificationId, newToken)
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    /**
     * Verifies the OTP code entered by the user.
     */
    fun verifyCode(
        activity: Activity,
        verificationId: String,
        code: String,
        onSuccess: () -> Unit,
        onFailed: (FirebaseException) -> Unit
    ) {
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, code)
            performSignInAndRoute(activity, credential, onSuccess, onFailed)
        } catch (e: Exception) {
            onFailed(
                if (e is FirebaseException) e
                else FirebaseException(e.message ?: "Verification error")
            )
        }
    }

    /**
     * Common sign-in path used by both auto-verification and manual code entry.
     * Guards against parallel sign-in attempts via [signInInProgress].
     */
    private fun performSignInAndRoute(
        activity: Activity,
        credential: PhoneAuthCredential,
        onSuccess: () -> Unit,
        onFailed: (FirebaseException) -> Unit
    ) {
        if (signInInProgress) {
            Log.w(TAG, "Sign-in already in progress — skipping duplicate")
            return
        }
        signInInProgress = true

        auth.signInWithCredential(credential)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    // Force-refresh the auth token so Firestore gets a fresh token
                    // before any listeners are registered in MainActivity.
                    // CRITICAL: Only call onSuccess (which triggers UI animation) AND
                    // routeAfterSignIn (which navigates) AFTER the token refresh completes.
                    // This prevents a race where MainActivity sets up Firestore listeners
                    // with a stale token, causing PERMISSION_DENIED errors.
                    auth.currentUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        signInInProgress = false
                        if (tokenTask.isSuccessful) {
                            onSuccess()
                            routeAfterSignIn(activity) {}
                        } else {
                            // Token refresh failed - still try to proceed but log warning
                            Log.w(TAG, "Token refresh failed after sign-in, proceeding anyway", tokenTask.exception)
                            onSuccess()
                            routeAfterSignIn(activity) {}
                        }
                    }
                } else {
                    signInInProgress = false
                    val e = task.exception
                    if (e is FirebaseException) onFailed(e)
                    else onFailed(FirebaseException(e?.message ?: "Sign-in failed"))
                }
            }
    }

    /**
     * Routes the user after successful sign-in.
     */
    fun routeAfterSignIn(
        activity: Activity,
        onLoadingChange: (Boolean) -> Unit
    ) {
        if (AuthFlowSession.hasRouted) return
        AuthFlowSession.hasRouted = true

        onLoadingChange(true)

        repository.getUser { user ->
            onLoadingChange(false)

            if (user != null && user.accountStatus == "banned" || user != null && user.accountStatus == "blocked") {
                showAccountRestrictedDialog(activity, user!!.accountStatus)
            } else if (user != null && user.username.isNotEmpty()) {
                navigateToMain(activity)
            } else {
                navigateToSetupProfile(activity)
            }
        }
    }

    // --- Private helpers ---

    private fun signInAndRoute(
        activity: Activity,
        credential: PhoneAuthCredential,
        onSuccess: () -> Unit,
        onFailed: (FirebaseException) -> Unit
    ) {
        if (signInInProgress || AuthFlowSession.hasRouted) {
            Log.w(TAG, "signInAndRoute skipping — inProgress=$signInInProgress hasRouted=${AuthFlowSession.hasRouted}")
            return
        }
        performSignInAndRoute(activity, credential, onSuccess, onFailed)
    }

    private fun showAccountRestrictedDialog(activity: Activity, status: String) {
        signInInProgress = false
        val message = when (status) {
            "banned" -> "Your Glyph account has been permanently banned.\n\nIf you believe this is a mistake, please contact support."
            "blocked" -> "Your Glyph account has been restricted.\n\nPlease contact support for more information."
            else -> "Your Glyph account has been restricted.\n\nPlease contact support for more information."
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Account Restricted")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Sign Out") { _, _ ->
                auth.signOut()
                AuthFlowSession.clear()
                activity.finish()
            }
            .show()
    }

    private fun navigateToMain(activity: Activity) {
        signInInProgress = false
        val intent = activity.intent
        val mainIntent = Intent(activity, MainActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(mainIntent)
        AuthFlowSession.clear()
        activity.finish()
    }

    private fun navigateToSetupProfile(activity: Activity) {
        signInInProgress = false
        val firebasePhoneNumber = auth.currentUser?.phoneNumber ?: AuthFlowSession.phoneNumber
        val intent = activity.intent
        val setupIntent = Intent(activity, SetupProfileActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            putExtra("phone_number", firebasePhoneNumber)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(setupIntent)
        AuthFlowSession.clear()
        activity.finish()
    }
}
