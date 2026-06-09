package com.glyph.glyph_v3.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ThumbnailGenerator {
    private const val TAG = "ThumbnailGenerator"
    private const val THUMBNAIL_SIZE = 50 // Very small for fast load
    private const val THUMBNAIL_QUALITY = 40 // Low quality for tiny size
    private const val BLUR_RADIUS = 10f
    
    /**
     * Generate a tiny blurred base64 thumbnail for instant display
     */
    suspend fun generateBase64Thumbnail(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Decode with severe downsampling for tiny size
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            
            // Calculate sample size to get ~50px thumbnail
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, THUMBNAIL_SIZE)
            
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565 // Smaller memory footprint
                }.let { opts ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            } ?: return@withContext null
            
            // Further scale down if needed
            val scaledBitmap = if (bitmap.width > THUMBNAIL_SIZE || bitmap.height > THUMBNAIL_SIZE) {
                val scale = min(
                    THUMBNAIL_SIZE.toFloat() / bitmap.width,
                    THUMBNAIL_SIZE.toFloat() / bitmap.height
                )
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            
            // Apply subtle blur (already small, so blur is fast)
            val blurredBitmap = applyFastBlur(scaledBitmap)
            if (blurredBitmap != scaledBitmap) {
                scaledBitmap.recycle()
            }
            
            // Compress to JPEG base64
            val base64 = ByteArrayOutputStream().use { outputStream ->
                blurredBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, outputStream)
                blurredBitmap.recycle()
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            }
            
            base64
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
            null
        }
    }
    
    /**
     * Decode base64 thumbnail to bitmap
     */
    fun decodeBase64Thumbnail(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode thumbnail", e)
            null
        }
    }

    /**
     * Public blur helper for tiny bitmaps.
     */
    fun blurBitmap(bitmap: Bitmap): Bitmap {
        return applyFastBlur(bitmap)
    }
    
    private fun calculateSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }
        return sampleSize
    }
    
    /**
     * Fast box blur for tiny images
     */
    private fun applyFastBlur(bitmap: Bitmap): Bitmap {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Simple box blur (fast for small images)
            val radius = 2
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                val pixel = pixels[ny * width + nx]
                                r += (pixel shr 16) and 0xFF
                                g += (pixel shr 8) and 0xFF
                                b += pixel and 0xFF
                                count++
                            }
                        }
                    }
                    
                    val avgR = r / count
                    val avgG = g / count
                    val avgB = b / count
                    pixels[y * width + x] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                }
            }
            
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            Log.e(TAG, "Blur failed, returning original", e)
            return bitmap
        }
    }
}
