package com.glyph.glyph_v3.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.databinding.ActivityLoginBinding
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()
    private val repository = FirebaseRepository()
    
    private var verificationId: String? = null
    private var userPhoneNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Apply pastel gradient for Pastel-Sky
        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this)
        if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = androidx.core.content.ContextCompat.getDrawable(this, com.glyph.glyph_v3.R.drawable.bg_pastel_gradient)
        }
        
        // The SplashActivity now handles the check, so we don't need it here.
        
        binding.btnSendCode.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text.toString().trim()
            if (phoneNumber.isNotEmpty()) {
                userPhoneNumber = phoneNumber // Store the number
                startPhoneNumberVerification(phoneNumber)
            } else {
                binding.etPhoneNumber.error = "Enter phone number"
            }
        }
        
        binding.btnVerify.setOnClickListener {
            val code = binding.etOtp.text.toString().trim()
            if (code.isNotEmpty() && verificationId != null) {
                verifyPhoneNumberWithCode(verificationId!!, code)
            }
        }
    }
    
    private fun startPhoneNumberVerification(phoneNumber: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }
    
    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            signInWithPhoneAuthCredential(credential)
        }

        override fun onVerificationFailed(e: FirebaseException) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this@LoginActivity, "Verification Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
            binding.progressBar.visibility = View.GONE
            this@LoginActivity.verificationId = verificationId
            
            binding.layoutPhoneInput.visibility = View.GONE
            binding.layoutOtpInput.visibility = View.VISIBLE
            binding.tvSentTo.text = "Code sent to $userPhoneNumber"
        }
    }
    
    private fun verifyPhoneNumberWithCode(verificationId: String, code: String) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithPhoneAuthCredential(credential)
    }
    
    private fun signInWithPhoneAuthCredential(credential: PhoneAuthCredential) {
        binding.progressBar.visibility = View.VISIBLE
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // User is signed in, now check if they have a profile.
                    checkUserProfile()
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Sign In Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }
    
    private fun checkUserProfile() {
        repository.getUser { user ->
            if (user != null && user.username.isNotEmpty()) {
                navigateToMain()
            } else {
                // New user, pass the phone number to the setup screen
                navigateToSetupProfile()
            }
        }
    }
    
    private fun navigateToMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(mainIntent)
        finish()
    }
    
    private fun navigateToSetupProfile() {
        // Use Firebase Auth's phone number (E.164 format) for consistency
        val firebasePhoneNumber = auth.currentUser?.phoneNumber ?: userPhoneNumber
        val setupIntent = Intent(this, SetupProfileActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            putExtra("phone_number", firebasePhoneNumber)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(setupIntent)
        finish()
    }
}
