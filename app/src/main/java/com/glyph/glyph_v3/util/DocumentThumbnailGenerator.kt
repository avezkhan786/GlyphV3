package com.glyph.glyph_v3.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Generates thumbnail images from the first page of PDF documents.
 * Uses Android's built-in PdfRenderer (API 21+).
 */
object DocumentThumbnailGenerator {

    private const val TAG = "DocThumbnailGen"

    /** Max width in pixels for the generated thumbnail */
    private const val MAX_THUMB_WIDTH = 540

    /** JPEG compression quality (0-100) */
    private const val JPEG_QUALITY = 75

    /**
     * Renders the first page of a PDF to JPEG bytes.
     * Returns null if the file is not a PDF, or if rendering fails.
     *
     * Must be called from a background thread / IO dispatcher.
     */
    fun generatePdfThumbnailBytes(context: Context, uri: Uri, fileName: String): ByteArray? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext != "pdf") return null

        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount == 0) {
                    renderer.close()
                    return null
                }

                val page = renderer.openPage(0)
                try {
                    // Scale down proportionally so the thumbnail is at most MAX_THUMB_WIDTH wide
                    val srcWidth = page.width.coerceAtLeast(1)
                    val srcHeight = page.height.coerceAtLeast(1)
                    val scale = if (srcWidth > MAX_THUMB_WIDTH) MAX_THUMB_WIDTH.toFloat() / srcWidth else 1f
                    val bmpWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
                    val bmpHeight = (srcHeight * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)

                    // Fill white background (PDF pages are transparent by default)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // Compress to JPEG
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    bitmap.recycle()

                    out.toByteArray()
                } finally {
                    page.close()
                    renderer.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF thumbnail for '$fileName'", e)
            null
        }
    }
}
