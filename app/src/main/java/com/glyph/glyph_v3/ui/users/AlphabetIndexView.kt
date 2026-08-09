package com.glyph.glyph_v3.ui.users

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Right-edge alphabetical index strip for the Contact Selection list.
 *
 * - Renders A..Z stacked vertically with a trailing "#" group at the bottom.
 * - Tap or drag anywhere in the strip dispatches the letter currently under the
 *   finger. CALLBACK fires only when the letter changes (no spam from a held tap).
 * - Sets [pressed] while the user is interacting, so the host can show a
 *   centred floating bubble calling out the current section.
 *
 * Implementation notes:
 *  - No third-party deps, no RecyclerView, no scrolling framework. This view is
 *    intentionally trivial so it adds zero layout cost.
 *  - Letters that are grayed-out (cfg: not present in the current list) are
 *    still valid input — the host maps them to the nearest section.
 *  - Touch slop is absorbed in [onTouchEvent]'s hit-test. The view owns its
 *    own gesture: it does not consume events from the underlying RecyclerView
 *    unless the user lands inside this strip's bounds.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Set of letters (uppercase, A-Z, "#") the host has flagged as present in the list.
     *  Letters outside this set are drawn faintly so the strip still feels uniform. */
    var presentLetters: Set<String> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Letter whose section is currently displayed by the underlying list.
     * Distinct from [activeLetter] (which only animates during a touch on the
     * strip) — this one tracks the visible scroll position so the user has a
     * persistent "I am here" marker even when they're reading the list and
     * not touching the strip. The strip draws a green filled-circle behind
     * the matching letter while this is set.
     */
    var currentSection: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Fires when the active letter changes due to tap or drag. */
    var onLetterChanged: ((String) -> Unit)? = null

    /** Fires on ACTION_UP / ACTION_CANCEL so the host can hide its drag affordance. */
    var onPressEnded: (() -> Unit)? = null

    /** True while a finger is down inside this view. */
    var isActive: Boolean = false
        private set

    /** Letter currently under the user's finger (or null when not pressed). */
    var activeLetter: String? = null
        private set

    private val letters: List<String> = buildList {
        for (c in 'A'..'Z') add(c.toString())
        add("#")
    }

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        isFakeBoldText = false
        letterSpacing = 0.04f
    }

    /** Paint for the filled circle that marks the letter whose section the
     *  list is currently positioned on. Distinct from the row's filled rear
     *  (the rounded rect on the strip itself) so the indicator colour can be
     *  tuned independently. */
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rowCount: Int get() = letters.size

    // Touch handling: we use a simple MotionEvent.ACTION_* switch — no gesture
    // detector — so the response is instant and predictable for both
    // single-tap and drag.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always ignore events that don't start inside this view.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
            if (!inside) return false
        } else if (!isActive) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                if (!isActive) {
                    isActive = true
                }
                val newLetter = letterAt(event.y) ?: return true
                if (newLetter != activeLetter) {
                    activeLetter = newLetter
                    fireSectionTickHaptic()
                    onLetterChanged?.invoke(newLetter)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isActive = false
                activeLetter = null
                invalidate()
                onPressEnded?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun letterAt(y: Float): String? {
        if (height <= 0) return null
        val rowH = height.toFloat() / rowCount
        val raw = (y / rowH).toInt()
        val idx = raw.coerceIn(0, rowCount - 1)
        return letters[idx]
    }

    override fun onDraw(canvas: Canvas) {
        if (height <= 0) return
        val rowH = height.toFloat() / rowCount
        val cx = width / 2f
        // Letters stay readable on devices of any density: scale with row height,
        // capped at 13sp and at 64% of row height so the glyph always has clear
        // top/bottom headroom even on handsets where 27 rows crunch to ~16dp each.
        val capPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics,
        )
        val textSizePx = (rowH * 0.64f).coerceAtMost(capPx)
        letterPaint.textSize = textSizePx
        letterPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        // `(ascent + descent)/2` is negative for any sane font (~ −0.25 × textSize).
        // The glyph centre sits at `baseline + (ascent + descent)/2`, so to centre
        // the glyph on `rowH * (i + 0.5)` we *subtract* that offset. An earlier
        // version used `+`, which pushed the top letter about half a glyph
        // *above* the view top — that's why "A" was clipped on the strip.
        val verticalOffset =
            (letterPaint.fontMetrics.ascent + letterPaint.fontMetrics.descent) / 2f
        // Filled circle for the current-section indicator. Capped at 13dp so it
        // sits a little larger than the bold letter inside it without spilling
        // past the strip's 36dp width on dense screens.
        val circleCapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 13f, resources.displayMetrics,
        )
        val circleRadius = (rowH * 0.46f).coerceAtMost(circleCapPx)
        val primaryColor = activeColor()

        for ((i, letter) in letters.withIndex()) {
            val rowCenterY = rowH * (i + 0.5f)
            val baseline = rowCenterY - verticalOffset
            val isActive = letter == activeLetter
            val isCurrentSection = letter == currentSection
            val isPresent = letter in presentLetters
            // Step 1: paint the filled circle (when this letter is the list's
            // current section). Drawn first so the letter sits on top.
            if (isCurrentSection) {
                sectionPaint.color = primaryColor
                canvas.drawCircle(cx, rowCenterY, circleRadius, sectionPaint)
            }
            // Step 2: choose letter colour for legibility against its bg.
            letterPaint.color = when {
                isCurrentSection -> Color.WHITE
                isActive -> primaryColor
                isPresent -> textColor()
                else -> mutedColor()
            }
            letterPaint.typeface =
                if (isActive || isCurrentSection)
                    Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else
                    Typeface.DEFAULT
            canvas.drawText(letter, cx, baseline, letterPaint)
        }
    }

    /**
     * Haptic tick on every alphabet step — gives the user a tactile
     * confirmation that "yes, the index picked up your finger on a new
     * letter" so they don't have to keep their eyes on the strip while
     * dragging. KEYBOARD_TAP is a noticeable-but-short pulse — a step up
     * from CLOCK_TICK (which felt too faint on slow drags) without being
     * so heavy it becomes annoying on fast glides through multiple letters.
     * We deliberately *don't* pass FLAG_IGNORE_GLOBAL_SETTING so a user who
     * has system haptics disabled in the OS won't feel anything —
     * respecting their platform preference outranks our design choice.
     *
     * `KEYBOARD_TAP` has been around since API 8; minSdk = 26 so we just
     * call it directly with no SDK guard.
     */
    private fun fireSectionTickHaptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun textColor(): Int = resolveThemeColor(
        android.R.attr.textColorSecondary,
        fallback = Color.argb(0xCC, 0x1F, 0x2C, 0x34),
    )

    private fun activeColor(): Int = resolveThemeColor(
        com.glyph.glyph_v3.R.attr.glyphPrimary,
        fallback = Color.argb(0xFF, 0x12, 0x8C, 0x7E),
    )

    private fun mutedColor(): Int = Color.argb(0x55, 0x60, 0x60, 0x60)

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        } else fallback
    }
}
