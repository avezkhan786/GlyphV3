package com.glyph.glyph_v3.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.github.chrisbanes.photoview.PhotoView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.ActivityFullScreenImageBinding

class FullScreenImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullScreenImageBinding
    private lateinit var photoView: PhotoView

    companion object {
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_LOCAL_IMAGE_PATH = "local_image_path"
        private const val EXTRA_LOCAL_IMAGE_LAST_MODIFIED = "local_image_last_modified"
        private const val EXTRA_USER_NAME = "user_name"

        fun newIntent(
            context: Context,
            imageUrl: String,
            userName: String,
            localImagePath: String? = null,
            localImageLastModified: Long? = null
        ): Intent {
            return Intent(context, FullScreenImageActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URL, imageUrl)
                putExtra(EXTRA_USER_NAME, userName)
                if (!localImagePath.isNullOrEmpty()) {
                    putExtra(EXTRA_LOCAL_IMAGE_PATH, localImagePath)
                }
                if (localImageLastModified != null) {
                    putExtra(EXTRA_LOCAL_IMAGE_LAST_MODIFIED, localImageLastModified)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFullScreenImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoView = binding.photoView

        setupFullScreen()
        setupImage()
        setupGestureDetectors()
        setupClickListeners()

        onBackPressedDispatcher.addCallback(this) {
            if (photoView.scale > 1f) {
                photoView.setScale(1f, true)
            } else {
                finish()
            }
        }
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Only hide the status bar so the image is edge-to-edge at the top.
        // Keeping the navigation bar visible means the back gesture always works
        // in a single swipe — hiding it causes the first swipe to reveal the bar
        // (transient) and requires a second swipe to actually navigate back.
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupImage() {
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""
        val localImagePath = intent.getStringExtra(EXTRA_LOCAL_IMAGE_PATH) ?: ""
        val localLastModified = intent.getLongExtra(EXTRA_LOCAL_IMAGE_LAST_MODIFIED, 0L)
        val userName = intent.getStringExtra(EXTRA_USER_NAME) ?: ""

        binding.tvUserName.text = userName

        when {
            localImagePath.isNotEmpty() -> {
                val file = java.io.File(localImagePath)
                val signatureValue = if (localLastModified != 0L) localLastModified else file.lastModified()

                Glide.with(this)
                    .load(file)
                    .signature(ObjectKey(signatureValue))
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(photoView)
            }

            imageUrl.isNotEmpty() -> {
                val request = ImageRequest.Builder(this)
                    .data(imageUrl)
                    .size(Size.ORIGINAL)
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .target(photoView)
                    .build()
                imageLoader.enqueue(request)
            }

            else -> photoView.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    private fun setupGestureDetectors() {
        // PhotoView handles pinch-to-zoom and pan natively.
        // Tap on the image toggles the toolbar overlay.
        photoView.setOnViewTapListener { _, _, _ -> toggleToolbar() }
    }

    private fun toggleToolbar() {
        if (binding.toolbar.visibility == View.VISIBLE) {
            binding.toolbar.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.toolbar.visibility = View.GONE }
                .start()
        } else {
            binding.toolbar.visibility = View.VISIBLE
            binding.toolbar.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
