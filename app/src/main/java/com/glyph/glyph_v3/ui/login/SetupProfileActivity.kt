package com.glyph.glyph_v3.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.databinding.ActivitySetupProfileBinding
import com.glyph.glyph_v3.utils.ThemeManager

class SetupProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupProfileBinding
    private val repository = FirebaseRepository()
    private var imageUri: Uri? = null
    private var phoneNumber: String? = null

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri
            Glide.with(this)
                .load(uri)
                .circleCrop()
                .into(binding.ivProfile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySetupProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        phoneNumber = intent.getStringExtra("phone_number")
        if (phoneNumber == null) {
            Toast.makeText(this, "Error: Phone number not provided.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupClickListeners()
    }

    private fun setupClickListeners() {
        // Both profile image and camera button open image picker
        binding.ivProfile.setOnClickListener {
            getContent.launch("image/*")
        }
        
        binding.btnCamera.setOnClickListener {
            getContent.launch("image/*")
        }

        binding.btnNext.setOnClickListener {
            validateAndSave()
        }
    }

    private fun validateAndSave() {
        val username = binding.etUsername.text.toString().trim()
        val bio = binding.etBio.text.toString().trim()
        
        if (username.isEmpty()) {
            binding.etUsername.error = "Please enter your name"
            binding.etUsername.requestFocus()
            return
        }
        
        if (username.length < 2) {
            binding.etUsername.error = "Name must be at least 2 characters"
            binding.etUsername.requestFocus()
            return
        }

        saveUserProfile(username, bio, phoneNumber!!)
    }

    private fun saveUserProfile(username: String, bio: String, phone: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnNext.isEnabled = false
        

        val finalBio = bio.ifEmpty { "Hey there! I am using Glyph." }

        repository.saveUserProfile(username, phone, finalBio, imageUri,
            onSuccess = {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Profile saved successfully!", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                }
            },
            onFailure = { e ->
                Log.e("ProfileSave", "Failed to save profile", e)
                Handler(Looper.getMainLooper()).post {
                    binding.progressBar.visibility = View.GONE
                    binding.btnNext.isEnabled = true
                    Toast.makeText(this, "Failed to save profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
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
}
