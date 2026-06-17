package com.glyph.glyph_v3.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Manages Google Sign-In for Drive backup/restore.
 *
 * Google Drive AppDataFolder requires a Google account. This repository
 * adds Google Sign-In as an additional linked identity alongside (not replacing)
 * Firebase Phone Auth. Silent sign-in is attempted on app start — if the user
 * has a Google account on their device it links silently.
 */
class GoogleSignInRepository(private val context: Context) {

    companion object {
        private const val TAG = "GoogleSignInRepo"

        @Volatile
        private var instance: GoogleSignInRepository? = null

        fun getInstance(context: Context): GoogleSignInRepository {
            return instance ?: synchronized(this) {
                instance ?: GoogleSignInRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val googleSignInClient: GoogleSignInClient

    private val _signedInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val signedInAccount: StateFlow<GoogleSignInAccount?> = _signedInAccount.asStateFlow()

    init {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(context, signInOptions)
    }

    /**
     * Attempt silent sign-in (no UI) on app start.
     * Best-effort: if no Google account is on the device, this silently fails.
     */
    suspend fun silentSignIn(): GoogleSignInAccount? {
        return withContext(Dispatchers.IO) {
            try {
                val account = googleSignInClient.silentSignIn().await()
                _signedInAccount.value = account
                if (account != null) {
                    Log.d(TAG, "Silent sign-in succeeded: ${account.email}")
                }
                account
            } catch (e: Exception) {
                Log.w(TAG, "Silent sign-in failed (expected if no Google account)", e)
                null
            }
        }
    }

    /**
     * Interactive sign-in with account picker UI.
     * Call this when the user explicitly enables backup from settings.
     */
    suspend fun signIn(activity: Activity): GoogleSignInAccount? {
        return withContext(Dispatchers.IO) {
            try {
                val intent = googleSignInClient.signInIntent
                // We need to launch the intent from the main thread and await the result.
                // The activity handles this via startActivityForResult pattern.
                // Callers should use the signInIntent + onActivityResult pattern, or:
                // For Compose: use rememberLauncherForActivityResult
                // For now we throw — callers should use getSignInIntent()
                throw IllegalStateException("Use getSignInIntent() + handleSignInResult() pattern")
            } catch (e: Exception) {
                Log.e(TAG, "Interactive sign-in failed", e)
                null
            }
        }
    }

    /**
     * Returns the sign-in Intent for use with ActivityResultLauncher.
     */
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Process the result from the sign-in intent.
     */
    suspend fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        return withContext(Dispatchers.IO) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                _signedInAccount.value = account
                Log.d(TAG, "Interactive sign-in succeeded: ${account?.email}")
                account
            } catch (e: ApiException) {
                Log.e(TAG, "Sign-in failed with status: ${e.statusCode}", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in failed", e)
                null
            }
        }
    }

    /**
     * Sign out of the Google account (removes backup access).
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                googleSignInClient.signOut().await()
                _signedInAccount.value = null
                Log.d(TAG, "Signed out of Google account")
            } catch (e: Exception) {
                Log.e(TAG, "Sign-out failed", e)
            }
        }
    }

    /**
     * Returns a GoogleAccountCredential suitable for Drive API usage.
     * The credential handles automatic token refresh.
     */
    fun getDriveCredential(account: GoogleSignInAccount): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf("https://www.googleapis.com/auth/drive.appdata")
        )
        credential.selectedAccount = account.account
        return credential
    }
}
