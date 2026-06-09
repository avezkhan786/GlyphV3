package com.glyph.glyph_v3.ui.settings

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
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.databinding.ActivityEditProfileBinding

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private val repository = FirebaseRepository()
    private var imageUri: Uri? = null
    private var currentImageUrl: String = ""
    private var currentPhone: String = ""

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
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply pastel gradient for Pastel-Sky
        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this)
        if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
        }

        setupToolbar()
        loadCurrentProfile()
        setupClickListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadCurrentProfile() {
        binding.progressBar.visibility = View.VISIBLE
        
        repository.getUser { user ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                
                if (user != null) {
                    binding.etUsername.setText(user.username)
                    binding.etBio.setText(user.bio)
                    binding.tvPhone.text = user.phoneNumber
                    currentPhone = user.phoneNumber
                    currentImageUrl = user.profileImageUrl
                    
                    if (user.profileImageUrl.isNotEmpty()) {
                        Glide.with(this)
                            .load(user.profileImageUrl)
                            .circleCrop()
                            .placeholder(R.drawable.ic_default_avatar)
                            .into(binding.ivProfile)
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.ivProfile.setOnClickListener {
            getContent.launch("image/*")
        }
        
        binding.btnCamera.setOnClickListener {
            getContent.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
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

        saveProfile(username, bio)
    }

    private fun saveProfile(username: String, bio: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false
        
        val finalBio = bio.ifEmpty { "Hey there! I am using Glyph." }


        repository.saveUserProfile(username, currentPhone, finalBio, imageUri,
            onSuccess = {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            },
            onFailure = { e ->
                Log.e("EditProfile", "Failed to update profile", e)
                Handler(Looper.getMainLooper()).post {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}
