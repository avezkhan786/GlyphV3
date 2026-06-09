package com.glyph.glyph_v3.ui.chat.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Produces Google Maps–style avatar pin markers that match the classic
 * "photo inside a teardrop" look (as seen in Google Maps contact sharing).
 *
 * Shape breakdown:
 * ```
 *       ╭──────────╮
 *      /            \
 *     |    avatar    |   ← circle head, thick coloured border ring
 *      \            /
 *       ╰───┬────╯       ← wide base, smooth bezier taper
 *           │
 *           ▼            ← sharp GPS tip (bitmap bottom-centre)
 * ```
 *
 * The shape is ONE continuous path: arcTo (280 ° over the top) + two
 * quadratic bezier curves down to the tip.  No kinks, no separate triangle.
 *
 * Drop shadow via [BlurMaskFilter] (works on software-rendered bitmaps).
 * Cache key = url + sizeDp + borderColor, so two users with the same photo
 * still get distinct markers if their border colours differ.
 */
object AvatarMarkerFactory {

    /** LRU bitmap cache keyed by (url, sizeDp, borderColor). */
    private val cache = LruCache<String, Bitmap>(24)

    /** Default head diameter in dp — matches proportions of reference image. */
    const val MARKER_SIZE_DP = 54

    // ── Geometry tuning ──────────────────────────────────────────────────────
    //
    // BREAK_DEG: degrees measured from the 6-o'clock position of the circle
    // where the teardrop tail detaches.  40 ° gives the wide, authentic
    // Google Maps base (the attachment points sit roughly at the equator).
    private const val BREAK_DEG = 40.0

    // Tail height as a fraction of the circle radius.
    // 0.72 ≈ the tail in the reference image (slightly less than the radius).
    private const val TAIL_RATIO = 0.72f

    // Border ring width in dp.  4 dp matches the thick prominent ring visible
    // in the reference image.
    private const val BORDER_DP = 4.0f

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Asynchronously build a [BitmapDescriptor] pin for [avatarUrl].
     *
     * @param avatarUrl   Remote URL or local path; null/blank → placeholder.
     * @param sizeDp      Head circle diameter in dp.
     * @param borderColor ARGB colour of the outer ring and tail fill.
     * @param onReady     Callback with the finished descriptor (called on main thread).
     */
    fun create(
        context: Context,
        avatarUrl: String?,
        sizeDp: Int = MARKER_SIZE_DP,
        borderColor: Int = 0xFFFFFFFF.toInt(),
        onReady: (BitmapDescriptor) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val circlePx = (sizeDp * density).toInt()
        val cacheKey = "${avatarUrl}_${sizeDp}_${borderColor}"

        cache.get(cacheKey)?.let {
            onReady(BitmapDescriptorFactory.fromBitmap(it))
            return
        }

        if (avatarUrl.isNullOrBlank()) {
            val bmp = buildMarker(null, circlePx, borderColor, density)
            cache.put(cacheKey, bmp)
            onReady(BitmapDescriptorFactory.fromBitmap(bmp))
            return
        }

        Glide.with(context.applicationContext)
            .asBitmap()
            .load(avatarUrl)
            .circleCrop()
            .override(circlePx, circlePx)
            .into(object : CustomTarget<Bitmap>(circlePx, circlePx) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val bmp = buildMarker(resource, circlePx, borderColor, density)
                    cache.put(cacheKey, bmp)
                    onReady(BitmapDescriptorFactory.fromBitmap(bmp))
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    val bmp = buildMarker(null, circlePx, borderColor, density)
                    cache.put(cacheKey, bmp)
                    onReady(BitmapDescriptorFactory.fromBitmap(bmp))
                }
            })
    }

    // ── Bitmap builder ───────────────────────────────────────────────────────

    /**
     * Renders the full pin into a new [Bitmap].
     *
     * Coordinate system (Y-down):
     * ```
     *  |← pad →|←── circlePx ──→|← pad →|
     *  ┌───────────────────────────────────┐  ─ pad (shadow bleed top)
     *  │        ╭─────────────╮            │  ─┐
     *  │       /   avatar /   \           │   │ circlePx
     *  │      |   placeholder  |          │   │
     *  │       \              /           │  ─┤
     *  │        ╰──────┬──────╯           │   │ tailPx
     *  │               ▼  ← GPS tip here  │  ─┘
     *  └───────────────────────────────────┘
     * ```
     * GPS anchor must be `Offset(0.5f, 1.0f)` in the Marker call.
     */
    private fun buildMarker(
        avatar: Bitmap?,
        circlePx: Int,
        borderColor: Int,
        density: Float
    ): Bitmap {
        val r = circlePx / 2f                               // circle radius
        val tailPx = (r * TAIL_RATIO).toInt()               // tail height in pixels
        val borderPx = BORDER_DP * density                  // border ring thickness

        // Shadow padding: on all sides except the bottom (tip is exactly at bottom edge).
        val pad = (6 * density).toInt()

        val bmpW = circlePx + 2 * pad
        val bmpH = circlePx + tailPx + pad   // pad on top only; tip at bottom

        val out = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Centre of the circle head in bitmap coordinates
        val cx = bmpW / 2f
        val cy = pad + r

        // ── Step 1: Compute the teardrop path ─────────────────────────────
        //
        // The two "attachment" points are where the tail meets the circle,
        // measured BREAK_DEG either side of the bottom (6-o'clock) position.
        //
        //   In Android's coordinate system angles (0°=3-o'clock, CW positive):
        //     6-o'clock = 90°
        //     right attachment = 90° − BREAK_DEG
        //     left  attachment = 90° + BREAK_DEG
        //
        val br   = Math.toRadians(BREAK_DEG)
        val sinB = sin(br).toFloat()
        val cosB = cos(br).toFloat()

        // Offsets from the circle centre to each attachment point
        val ax = sinB * r    // horizontal distance (same for both sides)
        val ay = cosB * r    // vertical   distance below cy

        // Attachment point coordinates in bitmap space
        val rAttX = cx + ax;  val rAttY = cy + ay   // right attachment
        val lAttX = cx - ax;  val lAttY = cy + ay   // left  attachment

        // GPS tip — exactly at the bitmap's bottom-centre
        val tipX = cx
        val tipY = bmpH.toFloat()

        // Arc angles in Android's convention:
        //   arcStartAngle = angle to right attachment point
        //   arcSweep      = −(360 − 2*BREAK_DEG), counterclockwise, sweeping over the top
        val arcStartAngle = atan2(ay, ax).let { Math.toDegrees(it.toDouble()).toFloat() } // ≈50°
        val arcSweep = -(360f - 2f * BREAK_DEG.toFloat())   // ≈ −280°

        // Bezier control points:
        // Horizontally they converge toward cx to taper the tail, but not too much (wider neck).
        // Vertically placed at 45% of the tail height below the attachment row.
        val ctrlY  = cy + ay + (tipY - (cy + ay)) * 0.45f
        val ctrlXR = cx + ax * 0.42f    // wider neck (increased from 0.35)
        val ctrlXL = cx - ax * 0.42f

        // For a subtle rounded tip instead of sharp point, we'll converge smoothly
        // with a slight rounding at the very bottom
        val tipConvergeY = tipY - (3f * density)      // point where curves converge (above the tip)
        val tipConvergeX = 3.2f * density             // wider tail (+2dp from 1.2dp)

        // ── Step 1b: Build the teardrop path with subtle rounded tip ───────
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        val tearPath = Path().apply {
            moveTo(rAttX, rAttY)                                 // start: right attachment
            arcTo(oval, arcStartAngle, arcSweep)                 // 280° CCW arc over the top → left attachment
            quadTo(ctrlXL, ctrlY, cx - tipConvergeX, tipConvergeY)  // left curve → left-convergence
            quadTo(cx, tipY, cx + tipConvergeX, tipConvergeY)       // rounded tip connect
            quadTo(ctrlXR, ctrlY, rAttX, rAttY)                  // right curve → right attachment
            close()
        }

        // ── Step 2: Drop shadow ────────────────────────────────────────────
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000.toInt()          // ~33 % opaque black
            maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.translate(0f, 2f * density)      // shadow offset: 2 dp downward
        canvas.drawPath(tearPath, shadowPaint)
        canvas.restore()

        // ── Step 3: Teardrop body (solid border colour — flat, like Google Maps) ──
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(tearPath, bodyPaint)

        // ── Step 4: Avatar (or placeholder) inside the circle ─────────────
        //
        // The avatar is clipped to a circle that is inset by the border width.
        // The tail area stays filled with the border colour — matching the
        // reference image where the tail has no separate fill, just the body colour.
        //
        val innerR = r - borderPx

        val clipPath = Path().apply {
            addCircle(cx, cy, innerR, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        if (avatar != null) {
            val src = Rect(0, 0, avatar.width, avatar.height)
            val dst = RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
            canvas.drawBitmap(avatar, src, dst, Paint(Paint.ANTI_ALIAS_FLAG))
        } else {
            // Placeholder: white circle + centred "?" glyph (no avatar loaded)
            canvas.drawColor(0xFFFFFFFF.toInt())
            val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                textSize = innerR * 1.0f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val textY = cy - (txtPaint.descent() + txtPaint.ascent()) / 2f
            canvas.drawText("?", cx, textY, txtPaint)
        }
        canvas.restore()

        return out
    }

    /** Evict everything from the cache (call on low-memory events). */
    fun clearCache() = cache.evictAll()
}
