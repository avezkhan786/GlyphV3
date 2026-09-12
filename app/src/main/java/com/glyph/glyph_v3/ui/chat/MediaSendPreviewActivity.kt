package com.glyph.glyph_v3.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.glyph.glyph_v3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WhatsApp-style full-screen preview shown after the user picks photo(s)/video(s).
 * The user can page through the selected items and type a caption that will be
 * displayed below the media inside the message bubble. Tapping send returns the
 * caption to [com.glyph.glyph_v3.ui.chat.ChatActivity], which continues into the
 * existing compression + send flow.
 */
class MediaSendPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_MIME_TYPES = "extra_mime_types"
        const val EXTRA_INITIAL_CAPTION = "extra_initial_caption"
        const val EXTRA_RECIPIENT_NAME = "extra_recipient_name"
        const val RESULT_CAPTION = "result_caption"

        fun newIntent(
            context: android.content.Context,
            uris: List<Uri>,
            mimeTypes: List<String>,
            initialCaption: String,
            recipientName: String
        ): Intent {
            return Intent(context, MediaSendPreviewActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                putStringArrayListExtra(EXTRA_MIME_TYPES, ArrayList(mimeTypes))
                putExtra(EXTRA_INITIAL_CAPTION, initialCaption)
                putExtra(EXTRA_RECIPIENT_NAME, recipientName)
            }
        }
    }

    private data class PreviewItem(val uri: Uri, val isVideo: Boolean)

    private lateinit var etCaption: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var tvSubtitle: TextView

    private val previewItems = mutableListOf<PreviewItem>()

    // Decoded video frames keyed by page position — rebinds during paging
    // must not re-decode the frame.
    private val videoFrameCache = mutableMapOf<Int, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_send_preview)

        val uriStrings = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val mimeTypes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES).orEmpty()
        val initialCaption = intent.getStringExtra(EXTRA_INITIAL_CAPTION).orEmpty()
        val recipientName = intent.getStringExtra(EXTRA_RECIPIENT_NAME).orEmpty()

        uriStrings.forEachIndexed { index, uriString ->
            val mime = mimeTypes.getOrNull(index) ?: "image/*"
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@forEachIndexed
            previewItems.add(PreviewItem(uri, mime.startsWith("video")))
        }
        if (previewItems.isEmpty()) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }

        findViewById<TextView>(R.id.tvTitle).text =
            if (recipientName.isBlank()) getString(R.string.media_preview_title_default) else "Send to $recipientName"
        tvSubtitle = findViewById(R.id.tvSubtitle)
        if (previewItems.size > 1) {
            tvSubtitle.text = resources.getQuantityString(
                R.plurals.media_preview_item_count, previewItems.size, previewItems.size
            )
            tvSubtitle.visibility = View.VISIBLE
        }

        etCaption = findViewById(R.id.etCaption)
        etCaption.setText(initialCaption)
        etCaption.setSelection(initialCaption.length)

        btnSend = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val caption = etCaption.text.toString().trim()
            setResult(RESULT_OK, Intent().apply { putExtra(RESULT_CAPTION, caption) })
            finish()
        }

        val rvPages = findViewById<RecyclerView>(R.id.rvPreviewPages)
        rvPages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvPages.adapter = PreviewPageAdapter()
        PagerSnapHelper().attachToRecyclerView(rvPages)
    }

    private inner class PreviewPageAdapter : RecyclerView.Adapter<PreviewPageViewHolder>() {

        override fun getItemCount(): Int = previewItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewPageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_send_preview_page, parent, false)
            return PreviewPageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PreviewPageViewHolder, position: Int) {
            holder.bind(previewItems[position], position)
        }
    }

    private inner class PreviewPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPreview: ImageView = itemView.findViewById(R.id.ivPreview)
        private val progressLoading: ProgressBar = itemView.findViewById(R.id.progressLoading)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val ivPlayBadge: ImageView = itemView.findViewById(R.id.ivPlayBadge)

        private var boundPosition: Int = -1

        fun bind(item: PreviewItem, position: Int) {
            boundPosition = position
            ivPreview.setImageDrawable(null)
            ivPlayBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            tvDuration.visibility = View.GONE
            progressLoading.visibility = View.VISIBLE

            if (item.isVideo) {
                loadVideoFrame(item, position)
            } else {
                Glide.with(this@MediaSendPreviewActivity)
                    .load(item.uri)
                    .transform(CenterCrop())
                    .dontAnimate()
                    .into(ivPreview)
                progressLoading.visibility = View.GONE
            }
        }

        private fun loadVideoFrame(item: PreviewItem, position: Int) {
            val cached = videoFrameCache[position]
            if (cached != null && boundPosition == position) {
                ivPreview.setImageBitmap(cached)
                progressLoading.visibility = View.GONE
                return
            }

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val thumb = com.glyph.glyph_v3.util.VideoThumbnailUtil
                            .generateThumbnailBytes(applicationContext, item.uri)
                        val meta = com.glyph.glyph_v3.util.VideoThumbnailUtil
                            .getVideoMetadata(applicationContext, item.uri)
                        val bitmap = thumb?.let { (bytes, _) ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        bitmap to (meta?.duration ?: 0L)
                    }.getOrNull()
                }

                if (boundPosition != position) return@launch // page recycled mid-decode
                progressLoading.visibility = View.GONE

                val bitmap = result?.first
                if (bitmap != null) {
                    videoFrameCache[position] = bitmap
                    ivPreview.setImageBitmap(bitmap)
                }
                val durationMs = result?.second ?: 0L
                if (durationMs > 0) {
                    tvDuration.text = formatDuration(durationMs)
                    tvDuration.visibility = View.VISIBLE
                }
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val seconds = durationMs / 1000
            return String.format("%d:%02d", seconds / 60, seconds % 60)
        }
    }
}