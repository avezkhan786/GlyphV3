package com.glyph.glyph_v3.ui.viewer

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.databinding.ActivityImageViewerBinding

/**
 * Full-screen image viewer with swipe-to-dismiss and zoom capabilities.
 * Displays a pager of images from a media group message.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var adapter: ImagePagerAdapter
    
    private var mediaItems: List<MediaItem> = emptyList()
    private var initialPosition: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full-screen immersive mode using modern WindowInsetsController API
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get media items from intent
        mediaItems = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("MEDIA_ITEMS", MediaItem::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<MediaItem>("MEDIA_ITEMS") ?: emptyList()
        }
        initialPosition = intent.getIntExtra("INITIAL_POSITION", 0)
        
        if (mediaItems.isEmpty()) {
            finish()
            return
        }
        
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        
        setupViewPager()
        setupUI()
    }
    
    private fun setupViewPager() {
        adapter = ImagePagerAdapter(mediaItems) {
            // Toggle UI visibility on tap
            toggleUI()
        }
        
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(initialPosition, false)
        
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })
    }
    
    private fun setupUI() {
        updateCounter(initialPosition)
        
        binding.btnClose.setOnClickListener {
            finish()
        }
        
        // Optional: Add share button
        binding.btnShare.setOnClickListener {
            // TODO: Implement share functionality
        }
    }
    
    private fun updateCounter(position: Int) {
        binding.tvCounter.text = "${position + 1}/${mediaItems.size}"
    }
    
    private fun toggleUI() {
        if (binding.topBar.visibility == View.VISIBLE) {
            binding.topBar.visibility = View.GONE
        } else {
            binding.topBar.visibility = View.VISIBLE
        }
    }
}
