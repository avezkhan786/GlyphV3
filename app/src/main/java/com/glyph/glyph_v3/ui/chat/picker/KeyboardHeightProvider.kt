package com.glyph.glyph_v3.ui.chat.picker

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Dynamically measures and persists the soft keyboard height.
 *
 * HOW IT WORKS:
 * 1. Attach to the root view's global layout listener.
 * 2. When the keyboard opens, compute the IME inset height minus nav bar.
 * 3. Persist that height in SharedPreferences so it survives rotations
 *    and app restarts.
 * 4. The picker panel uses this measured height as its own height so
 *    switching between keyboard and picker is seamless (no layout jump).
 */
class KeyboardHeightProvider(private val activity: Activity) {

    companion object {
        private const val TAG = "KbdHeight"
        private const val PREFS_NAME = "keyboard_prefs"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        /** Reasonable fallback if we've never measured the keyboard. */
        const val DEFAULT_KEYBOARD_HEIGHT_DP = 270
    }

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val rootView: View = activity.window.decorView.rootView

    /** Called when a new keyboard height is measured. */
    var onHeightChanged: ((Int) -> Unit)? = null

    /** Called when keyboard visibility changes. */
    var onKeyboardVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Last measured keyboard height in pixels. */
    private var measuredHeight: Int = loadSavedHeight()

    /** True when the soft keyboard is currently visible. */
    var isKeyboardVisible: Boolean = false
        private set

    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ── Public API ────────────────────────────────────────────────────

    /** Get the keyboard height (measured or default fallback). */
    fun getKeyboardHeight(): Int {
        return if (measuredHeight > 0) measuredHeight
        else dpToPx(DEFAULT_KEYBOARD_HEIGHT_DP)
    }

    /** Start listening for keyboard visibility changes. */
    fun start() {
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            detectKeyboard()
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** Stop listening. */
    fun stop() {
        listener?.let { rootView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        listener = null
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun detectKeyboard() {
        val insets = ViewCompat.getRootWindowInsets(rootView) ?: return
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val imeHeight = imeInsets.bottom - navInsets.bottom

        val wasVisible = isKeyboardVisible
        isKeyboardVisible = imeHeight > dpToPx(60) // threshold to filter noise

        if (isKeyboardVisible && imeHeight > 0 && imeHeight != measuredHeight) {
            measuredHeight = imeHeight
            saveHeight(imeHeight)
            onHeightChanged?.invoke(imeHeight)
        }

        if (wasVisible != isKeyboardVisible) {
            onKeyboardVisibilityChanged?.invoke(isKeyboardVisible)
        }
    }

    private fun loadSavedHeight(): Int {
        val saved = prefs.getInt(KEY_KEYBOARD_HEIGHT, 0)
        return if (saved > 0) saved else dpToPx(DEFAULT_KEYBOARD_HEIGHT_DP)
    }

    private fun saveHeight(height: Int) {
        prefs.edit().putInt(KEY_KEYBOARD_HEIGHT, height).apply()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }
}
