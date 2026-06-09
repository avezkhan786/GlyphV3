package com.glyph.glyph_v3.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.glyph.glyph_v3.R

/**
 * Document caption screen shown after the user picks a document.
 * Renders the first page of PDFs; shows a typed icon for other formats.
 * User can type a caption then tap Send to confirm.
 */
class DocumentCaptionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_URI   = "extra_document_uri"
        const val EXTRA_FILENAME       = "extra_filename"
        const val EXTRA_INITIAL_CAPTION = "extra_initial_caption"
        const val EXTRA_OTHER_USERNAME = "extra_other_username"
        const val RESULT_DOCUMENT_URI  = "result_document_uri"
        const val RESULT_CAPTION       = "result_caption"
    }

    private lateinit var ivDocPreview: ImageView
    private lateinit var llDocFallback: View
    private lateinit var tvDocFallbackName: TextView
    private lateinit var docIconBg: View
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvRecipient: TextView
    private lateinit var etCaption: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnBack: ImageButton

    private var documentUri: Uri? = null
    private var fileName: String = "Document"

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_caption)

        // Match status bar color to toolbar (glyphToolbarBackground) so they appear seamless
        val tvToolbarBg = TypedValue()
        theme.resolveAttribute(R.attr.glyphToolbarBackground, tvToolbarBg, true)
        window.statusBarColor = tvToolbarBg.data

        val uriString = intent.getStringExtra(EXTRA_DOCUMENT_URI) ?: run { finish(); return }
        documentUri = Uri.parse(uriString)
        fileName = intent.getStringExtra(EXTRA_FILENAME) ?: "Document"
        val otherUsername = intent.getStringExtra(EXTRA_OTHER_USERNAME) ?: "User"
        val initialCaption = intent.getStringExtra(EXTRA_INITIAL_CAPTION).orEmpty()

        bindViews()
        applyInputStyle()
        tvTitle.text = fileName
        tvRecipient.text = otherUsername
        etCaption.setText(initialCaption)
        etCaption.setSelection(initialCaption.length)

        btnBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }

        btnSend.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val caption = etCaption.text.toString().trim()
            setResult(RESULT_OK, Intent().apply {
                putExtra(RESULT_DOCUMENT_URI, uriString)
                putExtra(RESULT_CAPTION, caption)
            })
            finish()
        }

        renderDocumentPreview()
    }

    private fun bindViews() {
        ivDocPreview      = findViewById(R.id.ivDocPreview)
        llDocFallback     = findViewById(R.id.llDocFallback)
        tvDocFallbackName = findViewById(R.id.tvDocFallbackName)
        docIconBg         = findViewById(R.id.docIconBg)
        progressLoading   = findViewById(R.id.progressLoading)
        tvTitle           = findViewById(R.id.tvTitle)
        tvRecipient       = findViewById(R.id.tvRecipient)
        etCaption         = findViewById(R.id.etCaption)
        btnSend           = findViewById(R.id.btnSend)
        btnBack           = findViewById(R.id.btnBack)
    }

    /** Applies a pill-shaped background to the caption input using theme palette colors. */
    private fun applyInputStyle() {
        val cornerPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
        )
        val tvSurface  = TypedValue()
        val tvBorder   = TypedValue()
        theme.resolveAttribute(R.attr.glyphSurfaceVariant, tvSurface, true)
        theme.resolveAttribute(R.attr.glyphBorder, tvBorder, true)

        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(tvSurface.data)
            setStroke(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.25f, resources.displayMetrics).toInt(),
                tvBorder.data
            )
        }
        etCaption.background = pill
    }

    private fun renderDocumentPreview() {
        val uri = documentUri ?: return
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "pdf") {
            renderPdfPreview(uri)
        } else {
            showFallback(ext)
        }
    }

    private fun renderPdfPreview(uri: Uri) {
        Thread {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) { runOnUiThread { showFallback("pdf") }; return@Thread }

                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(0)

                val dm = resources.displayMetrics
                val padding = (16 * dm.density * 2).toInt()
                val bitmapWidth = (dm.widthPixels - padding).coerceAtLeast(300)
                val bitmapHeight = (bitmapWidth.toFloat() * page.height / page.width).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                page.close()
                renderer.close()

                runOnUiThread {
                    progressLoading.visibility = View.GONE
                    ivDocPreview.visibility = View.VISIBLE
                    Glide.with(this@DocumentCaptionActivity).load(bitmap).into(ivDocPreview)
                }
            } catch (e: Exception) {
                android.util.Log.e("DocCaptionActivity", "PDF render failed", e)
                runOnUiThread { showFallback("pdf") }
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun showFallback(ext: String) {
        progressLoading.visibility = View.GONE
        ivDocPreview.visibility = View.GONE
        llDocFallback.visibility = View.VISIBLE
        tvDocFallbackName.text = fileName
        docIconBg.setBackgroundResource(
            when (ext) {
                "pdf"                            -> R.drawable.bg_attachment_icon_red
                "doc", "docx", "odt"             -> R.drawable.bg_attachment_icon_blue
                "xls", "xlsx", "ods", "csv"      -> R.drawable.bg_attachment_icon_green
                "ppt", "pptx", "odp"             -> R.drawable.bg_attachment_icon_orange
                "zip", "rar", "7z", "tar", "gz"  -> R.drawable.bg_attachment_icon_teal
                "txt", "md", "rtf"               -> R.drawable.bg_attachment_icon_purple
                else                             -> R.drawable.bg_attachment_icon_indigo
            }
        )
    }
}
