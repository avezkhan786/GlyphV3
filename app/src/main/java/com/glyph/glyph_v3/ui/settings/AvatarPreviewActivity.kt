package com.glyph.glyph_v3.ui.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.chrisbanes.photoview.PhotoView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.cache.UserProfileCache
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.utils.ThemeManager
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AvatarPreviewActivity : AppCompatActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var scrim: View
    private lateinit var appBar: View
    private var avatarUrl: String? = null
    private var avatarFullUrl: String? = null
    private var userId: String? = null
    private val repository = FirebaseRepository()

    // Screen rect of the source avatar (passed from SettingsFragment)
    private var srcLeft = 0
    private var srcTop = 0
    private var srcWidth = 0
    private var srcHeight = 0

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { startCrop(it) } }

    private val cropImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null && tempOriginalUri != null) uploadAvatar(tempOriginalUri!!, resultUri)
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(this, "Crop error: ${UCrop.getError(result.data!!)?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var tempOriginalUri: Uri? = null
    private var tempCameraUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success && tempCameraUri != null) startCrop(tempCameraUri!!) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar_preview)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val typedValue = TypedValue()
        theme.resolveAttribute(R.attr.glyphToolbarIcon, typedValue, true)
        toolbar.navigationIcon?.setTint(typedValue.data)

        photoView = findViewById(R.id.avatar_full_view)
        scrim     = findViewById(R.id.scrim)
        appBar    = findViewById(R.id.appBar)

        avatarUrl    = intent.getStringExtra("EXTRA_AVATAR_URL")
        avatarFullUrl = intent.getStringExtra("EXTRA_AVATAR_FULL_URL")
        userId        = intent.getStringExtra("EXTRA_USER_ID")
        srcLeft   = intent.getIntExtra("EXTRA_SRC_LEFT", 0)
        srcTop    = intent.getIntExtra("EXTRA_SRC_TOP", 0)
        srcWidth  = intent.getIntExtra("EXTRA_SRC_WIDTH", 0)
        srcHeight = intent.getIntExtra("EXTRA_SRC_HEIGHT", 0)

        // Load the image synchronously from local cache — sets the bitmap in photoView
        // before any frame is drawn so the expand animation starts with the correct content.
        loadAvatarSync()

        // Wait until photoView has been measured and laid out, then kick off the expand.
        // loadAvatarFullRes() is called from inside animateExpand()'s onAnimationEnd so the
        // Glide re-fit never races against the expand anim.
        photoView.post { animateExpand() }
    }

    // ─── Image loading ─────────────────────────────────────────────────────────

    private fun loadAvatarSync() {
        val localPath = userId?.let { AvatarCacheManager.getLocalAvatarPath(it) }
        if (!localPath.isNullOrEmpty()) {
            val file = File(localPath)
            if (file.exists() && file.length() > 0) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    photoView.setImageBitmap(bmp)
                    return
                }
            }
        }
        // Fallback: load from URL synchronously via Glide memory/disk cache.
        val url = if (!avatarFullUrl.isNullOrEmpty()) avatarFullUrl else avatarUrl
        if (!url.isNullOrEmpty()) {
            Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(R.drawable.ic_default_avatar)
                .into(photoView)
        } else {
            photoView.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    private fun loadAvatarFullRes() {
        val url = if (!avatarFullUrl.isNullOrEmpty()) avatarFullUrl else avatarUrl ?: return
        lifecycleScope.launch {
            delay(50) // small settle delay; called only after expand finishes
            if (!isDestroyed) {
                Glide.with(this@AvatarPreviewActivity)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(photoView)
            }
        }
    }

    /** Plain Glide reload used after edit/remove operations. */
    private fun loadAvatar() {
        val url = if (!avatarFullUrl.isNullOrEmpty()) avatarFullUrl else avatarUrl
        if (!url.isNullOrEmpty()) {
            Glide.with(this).load(url).diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(R.drawable.ic_default_avatar).into(photoView)
        } else {
            photoView.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    // ─── Expand / collapse animation ──────────────────────────────────────────

    /**
     * Expands the avatar from its exact on-screen source position to fill the screen.
     * Works by translating + scaling photoView from [src rect] to [full screen], while
     * fading the black scrim and toolbar from 0 → 1.
     */
    private fun animateExpand() {
        val screenW = photoView.width.toFloat()
        val screenH = photoView.height.toFloat()
        if (screenW == 0f || srcWidth == 0) return

        val startScale = minOf(srcWidth / screenW, srcHeight / screenH)

        // Centre of the source thumb in window coordinates
        val srcCx = srcLeft + srcWidth / 2f
        val srcCy = srcTop + srcHeight / 2f
        val dstCx = screenW / 2f
        val dstCy = screenH / 2f

        photoView.apply {
            scaleX = startScale
            scaleY = startScale
            translationX = srcCx - dstCx
            translationY = srcCy - dstCy
            alpha = 1f
        }

        // Material "emphasized decelerate" curve — feels natural and not mechanical.
        val interp = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

        AnimatorSet().apply {
            duration = 500L
            interpolator = interp
            playTogether(
                ObjectAnimator.ofFloat(photoView, View.SCALE_X, startScale, 1f),
                ObjectAnimator.ofFloat(photoView, View.SCALE_Y, startScale, 1f),
                ObjectAnimator.ofFloat(photoView, View.TRANSLATION_X, srcCx - dstCx, 0f),
                ObjectAnimator.ofFloat(photoView, View.TRANSLATION_Y, srcCy - dstCy, 0f),
                ObjectAnimator.ofFloat(scrim, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(appBar, View.ALPHA, 0f, 1f)
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Load the full-res image only after the expand is complete so
                    // PhotoView's internal re-fit doesn't happen during the animation.
                    loadAvatarFullRes()
                }
            })
            start()
        }
    }

    /**
     * Collapses back to the source position, then finishes the activity.
     */
    private fun animateCollapse() {
        val screenW = photoView.width.toFloat()
        val screenH = photoView.height.toFloat()
        if (screenW == 0f || srcWidth == 0) {
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // If user zoomed in PhotoView, snap back to scale 1 first
        photoView.setScale(1f, true)

        val scaleX = srcWidth / screenW
        val scaleY = srcHeight / screenH
        val endScale = minOf(scaleX, scaleY)

        val srcCx = srcLeft + srcWidth / 2f
        val srcCy = srcTop + srcHeight / 2f
        val dstCx = screenW / 2f
        val dstCy = screenH / 2f

        val accel = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)

        AnimatorSet().also { set ->
            set.duration = 380L
            set.interpolator = accel
            set.playTogether(
                ObjectAnimator.ofFloat(photoView, View.SCALE_X, 1f, endScale),
                ObjectAnimator.ofFloat(photoView, View.SCALE_Y, 1f, endScale),
                ObjectAnimator.ofFloat(photoView, View.TRANSLATION_X, 0f, srcCx - dstCx),
                ObjectAnimator.ofFloat(photoView, View.TRANSLATION_Y, 0f, srcCy - dstCy),
                ObjectAnimator.ofFloat(scrim, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(appBar, View.ALPHA, 1f, 0f)
            )
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finish()
                    overridePendingTransition(0, 0)
                }
            })
            set.start()
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() { animateCollapse() }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.avatar_preview_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { animateCollapse(); true }
            R.id.action_edit  -> { showEditOptions(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Edit options ─────────────────────────────────────────────────────────

    private fun showEditOptions() {
        val options = mutableListOf("Take Photo", "Choose from Gallery")
        if (!avatarUrl.isNullOrEmpty()) options.add("Remove Avatar")
        AlertDialog.Builder(this)
            .setTitle("Profile Picture")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Take Photo"          -> startCamera()
                    "Choose from Gallery" -> pickImageLauncher.launch("image/*")
                    "Remove Avatar"       -> removeAvatar()
                }
            }.show()
    }

    private fun startCamera() {
        try {
            val photoFile = File(cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photoFile)
            tempCameraUri = photoUri
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(uri: Uri) {
        tempOriginalUri = uri
        val destinationUri = Uri.fromFile(File(cacheDir, "cropped_avatar_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true); setShowCropGrid(false); setCompressionQuality(90)
            setHideBottomControls(false); setFreeStyleCropEnabled(true)
            setToolbarColor(ContextCompat.getColor(this@AvatarPreviewActivity, R.color.black))
            setStatusBarColor(ContextCompat.getColor(this@AvatarPreviewActivity, R.color.black))
            setToolbarWidgetColor(ContextCompat.getColor(this@AvatarPreviewActivity, R.color.white))
        }
        UCrop.of(uri, destinationUri).withAspectRatio(1f, 1f).withMaxResultSize(1080, 1080)
            .withOptions(options).getIntent(this).also { cropImageLauncher.launch(it) }
    }

    private fun removeAvatar() {
        AlertDialog.Builder(this)
            .setTitle("Remove Profile Picture")
            .setMessage("Are you sure you want to remove your profile picture?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.removeProfileImage()
                            userId?.let { AvatarCacheManager.clearAvatarCache(it) }
                        }
                        avatarUrl = null; avatarFullUrl = null
                        if (userId != null) UserProfileCache.update(
                            this@AvatarPreviewActivity, userId, avatarUrl = "", avatarFullUrl = "")
                        loadAvatar()
                        Toast.makeText(this@AvatarPreviewActivity, "Profile picture removed successfully!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AvatarPreviewActivity, "Failed to remove avatar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun uploadAvatar(fullUri: Uri, croppedUri: Uri) {
        val progressDialog = AlertDialog.Builder(this).setMessage("Uploading...").setCancelable(false).create()
        progressDialog.show()
        lifecycleScope.launch {
            try {
                val (thumbUrl, fullUrl) = withContext(Dispatchers.IO) {
                    repository.uploadProfileImages(fullUri, croppedUri) {}
                }
                avatarUrl = thumbUrl; avatarFullUrl = fullUrl
                userId?.let { uid ->
                    UserProfileCache.update(this@AvatarPreviewActivity, uid, avatarUrl = thumbUrl, avatarFullUrl = fullUrl)
                    withContext(Dispatchers.IO) { AvatarCacheManager.cacheAvatar(uid, thumbUrl, this@AvatarPreviewActivity) }
                }
                loadAvatar()
                Toast.makeText(this@AvatarPreviewActivity, "Profile picture updated successfully!", Toast.LENGTH_SHORT).show()
                try { File(croppedUri.path ?: "").takeIf { it.exists() }?.delete() } catch (_: Exception) {}
            } catch (e: Exception) {
                Toast.makeText(this@AvatarPreviewActivity, "Failed to upload avatar: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressDialog.dismiss()
            }
        }
    }
}
