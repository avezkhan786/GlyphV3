package com.glyph.glyph_v3.ui.auth

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared animation specifications for the auth flow.
 *
 * All durations and easing curves are defined here so every screen
 * and component uses consistent motion timing.
 */
object AuthAnimationUtils {

    // --- Cross-activity transitions ---

    /** Duration for forward (enter) activity transitions. */
    const val ACTIVITY_ENTER_MS = 300L

    /** Duration for backward (exit) activity transitions. */
    const val ACTIVITY_EXIT_MS = 250L

    // --- Compose animation specs ---

    /** Standard content fade-in duration. */
    const val FADE_IN_MS = 400

    /** Stagger delay between staggered-fade children. */
    const val STAGGER_DELAY_MS = 80

    /** Button press scale target (96%). */
    const val PRESS_SCALE_TARGET = 0.96f

    /** Button press animation duration. */
    const val PRESS_ANIMATION_MS = 200

    /** OTP digit entry scale animation duration. */
    const val OTP_DIGIT_ENTRY_MS = 200

    /** OTP border color transition duration. */
    const val BORDER_TRANSITION_MS = 200

    /** Shake animation: duration of one half-cycle (4 cycles total). */
    const val SHAKE_HALF_CYCLE_MS = 50

    /** Shake animation: horizontal offset in dp. */
    val SHAKE_OFFSET_DP: Dp = 10.dp

    /** Checkmark circle scale-in duration. */
    const val CHECKMARK_CIRCLE_MS = 250

    /** Checkmark stroke draw duration. */
    const val CHECKMARK_STROKE_MS = 350

    /** Success hold duration before navigating away. */
    const val SUCCESS_HOLD_MS = 600L

    /** Loading overlay fade duration. */
    const val OVERLAY_FADE_MS = 300

    /** OTP countdown duration in seconds. */
    const val OTP_COUNTDOWN_SECONDS = 60

    // --- Compose animation specs (reusable tween/spring instances) ---

    /** Gentle spring for button press scale. */
    val pressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    /** Entry tween for staggered content fade. */
    val fadeInTween = tween<Float>(FADE_IN_MS)

    // --- Activity transition helpers ---

    /**
     * Applies a forward (enter) activity transition.
     * Call immediately after [Activity.startActivity] or before [Activity.finish].
     */
    fun forward(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.animator.fade_in,
                0,
                com.glyph.glyph_v3.R.color.glyph_primary
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                com.glyph.glyph_v3.R.anim.auth_slide_in_right,
                com.glyph.glyph_v3.R.anim.auth_slide_out_left
            )
        }
    }

    /**
     * Applies a backward (return) activity transition.
     * Call immediately before [Activity.finish] or after starting a backward activity.
     */
    fun back(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                0,
                android.R.animator.fade_out,
                com.glyph.glyph_v3.R.color.glyph_primary
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                com.glyph.glyph_v3.R.anim.auth_slide_in_left,
                com.glyph.glyph_v3.R.anim.auth_slide_out_right
            )
        }
    }
}
