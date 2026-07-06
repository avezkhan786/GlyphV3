package com.glyph.glyph_v3.ui.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 📅 DateHeaderDecoration - Floating Date Chips
 *
 * Draws Telegram-style floating date header chips above messages
 */
class DateHeaderDecoration(
    private val isDark: Boolean = false,
    private val backgroundColor: Int = 0x33000000.toInt(), // Semi-transparent black
    private val textColor: Int = 0xFFFFFFFF.toInt() // White
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    // Date formatter
    private val dateFormatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

    companion object {
        // Telegram date header styling
        private const val PADDING_HORIZONTAL = 12f // dp
        private const val PADDING_VERTICAL = 3f // dp
        private const val CORNER_RADIUS = 12f // dp
        private const val TEXT_SIZE = 12f // sp
        private const val TOP_MARGIN = 8f // dp
        private const val BOTTOM_MARGIN = 4f // dp

        // Standard colors
        const val LIGHT_BG = 0x33000000.toInt() // 20% black
        const val DARK_BG = 0x66FFFFFF.toInt() // 40% white
        const val LIGHT_TEXT = 0xFFFFFFFF.toInt()
        const val DARK_TEXT = 0xFF000000.toInt()

        fun createDefault(isDark: Boolean): DateHeaderDecoration {
            return if (isDark) {
                DateHeaderDecoration(
                    isDark = true,
                    backgroundColor = DARK_BG,
                    textColor = LIGHT_TEXT
                )
            } else {
                DateHeaderDecoration(
                    isDark = false,
                    backgroundColor = LIGHT_BG,
                    textColor = LIGHT_TEXT
                )
            }
        }
    }

    init {
        // Setup background paint
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor

        // Setup text paint
        textPaint.style = Paint.Style.FILL
        textPaint.color = textColor
        textPaint.textSize = TEXT_SIZE.spToPx()
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Draw date headers above items
     */
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.childCount == 0) return

        // Process each visible child
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue

            val dateText = getDateTextForPosition(position)
            if (dateText != null && shouldShowDateHeader(position)) {
                drawDateChip(canvas, parent, dateText, child)
            }
        }
    }

    /**
     * Draw a single date chip
     */
    private fun drawDateChip(canvas: Canvas, parent: RecyclerView, dateText: String, child: View) {
        // Measure text
        val textWidth = textPaint.measureText(dateText)
        val totalWidth = textWidth + PADDING_HORIZONTAL.dpToPx() * 2
        val totalHeight = TEXT_SIZE.spToPx() + PADDING_VERTICAL.dpToPx() * 2

        // Calculate position (centered horizontally)
        val left = (parent.width - totalWidth) / 2f
        val top = child.top - TOP_MARGIN.dpToPx() - totalHeight
        val right = left + totalWidth
        val bottom = top + totalHeight

        // Draw pill background
        rectF.set(left, top, right, bottom)
        canvas.drawRoundRect(
            rectF,
            CORNER_RADIUS.dpToPx(),
            CORNER_RADIUS.dpToPx(),
            paint
        )

        // Draw text (centered in pill)
        val textX = parent.width / 2f
        val textY = top + PADDING_VERTICAL.dpToPx() + TEXT_SIZE.spToPx() - 2f.dpToPx()
        canvas.drawText(dateText, textX, textY, textPaint)
    }

    /**
     * Check if position should show a date header
     */
    private fun shouldShowDateHeader(position: Int): Boolean {
        if (position == 0) return true

        // Get previous position's timestamp
        val currentTimestamp = getTimestampForPosition(position) ?: return false
        val previousTimestamp = getTimestampForPosition(position - 1) ?: return true

        // Check if dates are different
        val cal1 = Calendar.getInstance().apply { timeInMillis = currentTimestamp }
        val cal2 = Calendar.getInstance().apply { timeInMillis = previousTimestamp }

        return cal1.get(Calendar.YEAR) != cal2.get(Calendar.YEAR) ||
                cal1.get(Calendar.DAY_OF_YEAR) != cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Get date text for a position
     */
    private fun getDateTextForPosition(position: Int): String? {
        if (!shouldShowDateHeader(position)) return null

        val timestamp = getTimestampForPosition(position) ?: return null
        return formatDate(timestamp)
    }

    /**
     * Get timestamp for a position (placeholder - adapt to your adapter)
     */
    private fun getTimestampForPosition(position: Int): Long? {
        // TODO: Adapt this to your actual adapter
        // For now, return current time as placeholder
        return System.currentTimeMillis()
    }

    /**
     * Format timestamp as "Today", "Yesterday", or date
     */
    private fun formatDate(timestamp: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)

        return when {
            isSameDay(cal, today) -> "Today"
            isSameDay(cal, yesterday) -> "Yesterday"
            else -> dateFormatter.format(cal.time)
        }
    }

    /**
     * Check if two calendars represent the same day
     */
    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}

private fun Float.dpToPx(): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        android.content.res.Resources.getSystem().displayMetrics
    )
}

private fun Int.dpToPx(): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        android.content.res.Resources.getSystem().displayMetrics
    ).toInt()
}

private fun Float.spToPx(): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        android.content.res.Resources.getSystem().displayMetrics
    )
}
