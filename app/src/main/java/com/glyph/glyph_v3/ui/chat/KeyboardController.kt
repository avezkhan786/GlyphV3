package com.glyph.glyph_v3.ui.chat

import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.ActivityChatBinding
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import kotlin.math.max

internal class KeyboardController(
    private val binding: ActivityChatBinding,
    private val dpToPx: (Int) -> Int,
    private val keyboardHeightProvider: () -> com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider?,
    private val isPickerModeProvider: () -> Boolean,
    private val isAiComposerVisibleProvider: () -> Boolean,
    private val isAtBottomProvider: () -> Boolean,
    private val itemCountProvider: () -> Int,
    private val onKeyboardAnimationChanged: (Boolean) -> Unit,
    private val onAnimatedMediaReady: () -> Unit,
    private val onRequestBottomAnchor: () -> Unit,
    // Called once the keyboard-end traversal's draw is committed (via rv.post from PreDraw-A).
    // Receives the item count captured at onEnd so the caller can run fine-grained adjustments
    // (ensureLastItemFullyVisibleAboveBottomUi + scheduleScrollFabUpdate) WITHOUT triggering
    // an extra rv.requestLayout() + rv.invalidate() — which would create a second traversal
    // containing a detach-scrap cycle, giving in-flight Glide GIF requests a window to
    // deliver drawables to briefly-scrapped views and produce a one-frame placeholder flash.
    private val onFinalizeScroll: (count: Int) -> Unit,
    private val onHideEmojiPicker: () -> Unit,
    private val onInteractiveMapInsetChanged: (Int) -> Unit,
    private val isChatInputFocused: () -> Boolean
) {
    var isAnimating: Boolean = false
        private set

    // True from the moment onEnd calls updateContentBottomPadding (which triggers a
    // requestLayout) until the resulting PreDraw fires and the layout is fully committed.
    // During this window the BottomAnchorListener must not call requestScrollToBottom — it
    // would race against the PreDraw that we already registered for that exact purpose,
    // causing a redundant rv.requestLayout() + rv.invalidate() and an extra traversal that
    // lets a GIF first-frame invalidation sneak in as a visible flash.
    var isInEndLayoutCommit: Boolean = false
        private set

    private var pendingBottomAnchorAfterIme = false
    private var lastAppliedInputPaddingBottom = Int.MIN_VALUE
    private var lastAppliedBlockedPaddingBottom = Int.MIN_VALUE
    // Padding that was in effect when the current IME animation started. onProgress uses this
    // as the baseline so the translation delta exactly cancels what the layout WOULD have
    // moved, keeping visual position continuous across the onEnd layout-commit.
    private var animationStartPaddingBottom = 0

    // Incremented each time a NEW IME animation begins (onPrepare). The onEnd PreDraw
    // listener captures the epoch at registration time and checks it before executing.
    // If a new animation started before the PreDraw fired, the epoch won't match and the
    // stale listener is discarded — preventing it from: (a) overwriting translationY that
    // the new animation's onPrepare already set correctly, and (b) calling onAnimatedMediaReady
    // while isAnimating=true (which restarts GIFs mid-animation and causes the flash).
    private var imeAnimationEpoch = 0

    // Cached once: dpToPx(3) is a constant, recomputing it on every IME animation frame is waste.
    private val gapPx: Int by lazy { dpToPx(3) }

    // The blocked banner is absent for the common (non-blocked) case, so a per-frame
    // findViewById on the whole view tree during the IME animation is pure overhead — on cold
    // (pre-JIT) first animations that tree walk is a measurable slice of every frame. Resolve it
    // ONCE at installInsetsHandlers and reuse the reference for the lifetime of the controller.
    private var blockedBannerView: View? = null
    private var blockedBannerResolved = false

    private fun resolveBlockedBanner(): View? {
        // Eagerly populated in installInsetsHandlers. applyBottomPadding's only job is to
        // return the cached reference; if the view tree is mutated later, the cache will
        // be stale and we re-resolve once.
        if (blockedBannerResolved) return blockedBannerView
        if (isAnimating) return blockedBannerView
        blockedBannerView = binding.root.findViewById(R.id.layoutBlockedBanner)
        blockedBannerResolved = true
        return blockedBannerView
    }

    fun configureWindowChrome(window: android.view.Window) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    fun installInsetsHandlers(
        isPastelThemeProvider: () -> Boolean,
        topSafeAreaConsumer: (Int) -> Unit,
        bottomSafeAreaConsumer: (Int) -> Unit,
        dispatchInsetsToDynamicOverlays: (WindowInsetsCompat) -> Unit,
        updatePastelToolbarPadding: (Int) -> Unit
    ) {
        // AppBarLayout has elevation=0dp (stateListAnimator=null), same as chatContentContainer.
        // CoordinatorLayout draws children in declaration order when elevations are equal, so
        // chatContentContainer (declared after AppBarLayout) would paint on top of the toolbar
        // when translated upward. bringToFront() moves AppBarLayout to draw last, ensuring it
        // always renders on top of the translated content — no toolbar overlap.
        binding.appBarLayout.bringToFront()

        // Eagerly resolve the (optional) blocked banner once. applyBottomPadding will run
        // this findViewById on every IME-related padding change otherwise, which is wasted
        // work for the common (non-blocked) case where the result is always null.
        resolveBlockedBanner()

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        // New animation starting — any PreDraw listener registered by the
                        // previous onEnd is now stale and must be discarded (see imeAnimationEpoch).
                        imeAnimationEpoch++
                        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
                        val navBottom = rootInsets
                            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

                        // The animation base is ALWAYS the keyboard-HIDDEN padding. With the hidden
                        // layout the RecyclerView is at its full height with its top pinned just
                        // under the AppBar, so translating the container vertically can NEVER open
                        // a gap at the top — for either direction. Every frame is then a pure GPU
                        // translationY with no per-frame layout, so SHOW and HIDE both hold a
                        // steady cadence and the first open is as smooth as every open after.
                        val hiddenPadding = computeTargetPadding(lastIme = 0, systemBottom = navBottom)

                        val currentPadding =
                            if (lastAppliedInputPaddingBottom == Int.MIN_VALUE) hiddenPadding
                            else lastAppliedInputPaddingBottom

                        // Direction is inferred from the CURRENTLY-applied padding (not from root
                        // insets, which at onPrepare still reflect the pre-animation state): when
                        // we are already on the shown layout (large padding) the keyboard is going
                        // away → HIDE; otherwise → SHOW.
                        val isShow = currentPadding < SHOW_DETECT_THRESHOLD_PX

                        animationStartPaddingBottom = hiddenPadding

                        if (!isShow && currentPadding != hiddenPadding) {
                            // HIDE: we are on the shown layout (RecyclerView shrunk). Commit the
                            // hidden layout NOW — exactly one layout pass for the whole animation —
                            // then translate the container UP by the amount that layout just moved
                            // the input bar, so the on-screen position is unchanged (no jump). From
                            // here onProgress only eases translationY back to 0: zero per-frame
                            // layout, which removes the HIDE stutter.
                            //
                            // Committing the hidden padding grows the RecyclerView by ~873px. Anchor
                            // the last item to the bottom BEFORE the layout so LinearLayoutManager
                            // does not backfill the newly-exposed space with older messages (which
                            // would flash for one frame). scrollToPosition + setPadding coalesce
                            // into a single traversal.
                            val count = itemCountProvider()
                            if (count > 0 && (pendingBottomAnchorAfterIme || shouldAnchorToBottom())) {
                                binding.recyclerViewMessages.scrollToPosition(count - 1)
                            }
                            updateContentBottomPadding(lastIme = 0, systemBottom = navBottom)
                            binding.chatContentContainer.translationY =
                                -(currentPadding - hiddenPadding).toFloat()
                        } else {
                            // SHOW: already on the hidden layout; start at rest and slide up.
                            binding.chatContentContainer.translationY = 0f
                        }

                        setKeyboardAnimating(true)
                        Log.d(TAG, "IME animation started base=$hiddenPadding current=$currentPadding show=$isShow")
                    }
                    super.onPrepare(animation)
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                    val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    val panel = binding.emojiPickerPanel
                    if (panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(imeInsets.bottom)
                        return insets
                    }

                    // Skip the bottom-anchor + panel-state polling when the emoji picker is
                    // not active. During the common IME show from text input the picker is
                    // closed, so isPickerModeProvider() returns false; deferring these reads
                    // keeps the per-frame work to the absolute minimum: target/delta + one
                    // translationY write. The translationY math below always runs — that is
                    // the whole point of the animation.
                    if (isPickerModeProvider()) {
                        pendingBottomAnchorAfterIme = pendingBottomAnchorAfterIme || shouldAnchorToBottom()
                    }

                    // Unified translation for BOTH directions. The layout is fixed on the hidden
                    // state (base = hiddenPadding, set in onPrepare), so:
                    //   translationY = -(target - base)
                    // glides the whole chat area up as the keyboard rises and eases back to 0 as
                    // it falls. This is a pure GPU transform — no requestLayout, no ConstraintLayout
                    // measure/layout cascade per frame — so the descent (HIDE) is as smooth as the
                    // ascent (SHOW), and neither stutters on the first open.
                    val targetPadding = computeTargetPadding(imeInsets.bottom, navBars.bottom)
                    val delta = targetPadding - animationStartPaddingBottom
                    binding.chatContentContainer.translationY = -delta.toFloat()
                    if (VERBOSE_IME_DEBUG) {
                        val t0 = System.nanoTime()
                        val us = (System.nanoTime() - t0) / 1000
                        Log.d(TAG, "onProgress ime=${imeInsets.bottom} nav=${navBars.bottom} " +
                            "target=$targetPadding base=$animationStartPaddingBottom " +
                            "delta=$delta translationY=${-delta} (${us}µs)")
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return

                    val t0Start = System.nanoTime()
                    setKeyboardAnimating(false)

                    val endInsets = ViewCompat.getRootWindowInsets(binding.root)
                    val imeBottom = endInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    val navBottom = endInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

                    // Commit the final layout state.
                    //   SHOW: the layout was held on the hidden state during the animation; here we
                    //         apply the shown padding (RecyclerView shrinks) for the resting state.
                    //   HIDE: the hidden layout was already committed in onPrepare, so this is a
                    //         no-op (padding unchanged) — no relayout, no flash.
                    //
                    // Anchor BEFORE the layout commit for BOTH SHOW and HIDE.
                    //
                    // Why this matters for SHOW: updateContentBottomPadding shrinks the
                    // RecyclerView by ~1000 px (hidden → shown padding delta). Without a prior
                    // scrollToPosition the list manager has no pending scroll offset — it renders
                    // the last-committed RV scroll position into the narrowed viewport, which
                    // exposes stale/older messages for the one frame between the layout commit and
                    // the next traversal's scroll. That one wrong frame is the visible flash.
                    //
                    // Calling scrollToPosition HERE (before the layout) coalesces the scroll and
                    // the padding change into a single traversal: LinearLayoutManager honours
                    // mPendingScrollPosition during onLayoutChildren, so the last item is anchored
                    // in the same pass that commits the new padding — zero wrong frames.
                    val count = itemCountProvider()
                    val shouldAnchor = count > 0 && (pendingBottomAnchorAfterIme || shouldAnchorToBottom())
                    if (shouldAnchor) {
                        binding.recyclerViewMessages.scrollToPosition(count - 1)
                        if (VERBOSE_IME_DEBUG) {
                            Log.d(FLASH_TAG, "onEnd: pre-scroll scrollToPosition(${count - 1}) before layout commit")
                        }
                    }

                    // Apply the real layout state (schedules requestLayout for next traversal).
                    // Set isInEndLayoutCommit=true first so that the BottomAnchorListener, which
                    // fires from layoutInput's OnLayoutChangeListener during the resulting
                    // traversal's layout phase, sees the flag and skips its own
                    // requestScrollToBottomAfterNextPreDraw call. Without this guard the listener
                    // fires a second requestScrollToBottomAfterNextPreDraw (and a second
                    // rv.requestLayout/invalidate), which schedules an extra traversal — the GIF
                    // first-frame invalidation that arrives in that window produces the flash.
                    isInEndLayoutCommit = true
                    updateContentBottomPadding(lastIme = imeBottom, systemBottom = navBottom)

                    val container = binding.chatContentContainer
                    val priorTranslation = container.translationY
                    val capturedEpoch = imeAnimationEpoch
                    val capturedCount = count
                    if (VERBOSE_IME_DEBUG) {
                        Log.d(FLASH_TAG, "onEnd: registering PreDraw reset. translationY=$priorTranslation imeBottom=$imeBottom epoch=$capturedEpoch")
                    }
                    // PreDraw's only remaining job is to clear isInEndLayoutCommit and reset
                    // translationY=0 (safety net for pre-API-30). All animation-restart work
                    // (GIF start(), Glide resumeRequests, onFinalizeScroll) is deferred to
                    // container.post {} so it runs AFTER the current draw commits — not on the
                    // critical path before the buffer is presented.
                    //
                    // Why this matters: onAnimatedMediaReady() walks the visible RV children
                    // and calls drawable.start() on each Animatable. Each start() schedules a
                    // next-frame decode + RenderThread upload, all happening synchronously
                    // inside the old onPreDraw. With several on-screen GIFs/stickers this is
                    // 6-10+ invalidate()s on the main thread right before the buffer is
                    // committed. On the first IME open after entering the chat, recent message
                    // image/GIF/sticker loads are still settling and the frame budget is
                    // already tight; the synchronous restart lands on the very frame the user
                    // sees the settled layout, producing the visible "worse on first open"
                    // stutter. container.post {} moves the work onto the next main-thread
                    // message after draw, with no invalidates in the critical path.
                    container.viewTreeObserver.addOnPreDrawListener(
                        object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                container.viewTreeObserver.removeOnPreDrawListener(this)
                                // Layout is now committed; BottomAnchorListener can fire freely.
                                isInEndLayoutCommit = false
                                if (capturedEpoch != imeAnimationEpoch) {
                                    if (VERBOSE_IME_DEBUG) {
                                        Log.d(FLASH_TAG, "PreDraw-reset: STALE (captured=$capturedEpoch current=$imeAnimationEpoch), discarding")
                                    }
                                    return true
                                }
                                val beforeReset = container.translationY
                                // The platform (API 30+) resets translationY=0 automatically
                                // during insets-animation cleanup. This line is a safety net for
                                // older API levels or other edge cases.
                                container.translationY = 0f
                                if (VERBOSE_IME_DEBUG) {
                                    Log.d(FLASH_TAG, "PreDraw-reset: translationY $beforeReset → 0 (was expected: $priorTranslation) epoch=$capturedEpoch")
                                }
                                // Defer animated-media resume (GIF start() + Glide resumeRequests)
                                // and the post-layout scroll finalization to AFTER the current
                                // draw commits. container.post {} is the same mechanism already
                                // used below for onFinalizeScroll — it queues work onto the main
                                // thread's message queue after the current draw is presented, so
                                // no invalidate() runs in the critical path.
                                container.post {
                                    if (capturedEpoch == imeAnimationEpoch) {
                                        onAnimatedMediaReady()
                                        // Fine-grained post-layout adjustment runs after the
                                        // draw — no rv.requestLayout/invalidate, so no extra
                                        // traversal, no detach-scrap cycle, no GIF placeholder
                                        // flash window.
                                        if (capturedCount > 0) {
                                            onFinalizeScroll(capturedCount)
                                        }
                                    }
                                }
                                return true
                            }
                        }
                    )

                    if (VERBOSE_IME_DEBUG) {
                        val ms = (System.nanoTime() - t0Start) / 1_000_000f
                        Log.d(TAG, "onEnd settle ${ms}ms finalIme=$imeBottom nav=$navBottom " +
                            "appliedPadding=$lastAppliedInputPaddingBottom")
                    }

                    val panel = binding.emojiPickerPanel
                    if (imeBottom > 0 && isPickerModeProvider() && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(imeBottom)
                    } else if (imeBottom > 0 && isPickerModeProvider() && !isChatInputFocused()) {
                        panel.expandForSearch(imeBottom)
                    } else if (imeBottom > 0 && isPickerModeProvider() && isChatInputFocused()) {
                        onHideEmojiPicker()
                    } else if (imeBottom == 0 && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(0)
                    }

                    // pendingBottomAnchorAfterIme is reset here. We do NOT call
                    // onRequestBottomAnchor() — the scrollToPosition above already committed
                    // the anchor in the same traversal as the padding change. Calling
                    // onRequestBottomAnchor would route through requestScrollToBottomAfterNextPreDraw
                    // which issues rv.requestLayout() + rv.invalidate() from a PreDraw listener,
                    // creating Traversal N+1 with a detach-scrap cycle. In-flight Glide GIF loads
                    // can deliver drawables into that window and produce a one-frame placeholder
                    // flash on the first keyboard open. The fine-grained ensureLastItemFullyVisible
                    // + scheduleScrollFabUpdate is handled by onFinalizeScroll (rv.post in PreDraw-A).
                    if (pendingBottomAnchorAfterIme) {
                        pendingBottomAnchorAfterIme = false
                    }

                    // Wake any coroutines awaiting the IME-hide transition. This is the
                    // proper signal for the AI-composer flow (and any other code that needs
                    // to chain off a keyboard dismiss): a one-shot complete() instead of
                    // a 16ms-polling isVisible() loop. Only fires for HIDE (imeBottom == 0)
                    // — a SHOW completion does not match the dismiss-awaiter contract.
                    if (imeBottom == 0) {
                        notifyImeHidden()
                    }
                }
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val displayCutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            topSafeAreaConsumer(max(systemBars.top, displayCutout.top))
            bottomSafeAreaConsumer(navBars.bottom)
            onInteractiveMapInsetChanged(navBars.bottom)

            val topInset = if (isPastelThemeProvider()) 0 else systemBars.top
            if (binding.appBarLayout.paddingTop != topInset) {
                binding.appBarLayout.setPadding(0, topInset, 0, 0)
            }

            dispatchInsetsToDynamicOverlays(windowInsets)

            if (isPastelThemeProvider()) {
                updatePastelToolbarPadding(systemBars.top + dpToPx(20))
            }

            val translationYNow = binding.chatContentContainer.translationY
            if (VERBOSE_IME_DEBUG) {
                Log.d(FLASH_TAG, "onApplyWindowInsets: ime=${ime.bottom} nav=${navBars.bottom} isAnimating=$isAnimating translationY=$translationYNow pending=$pendingBottomAnchorAfterIme")
            }
            if (!isAnimating) {
                val willAnchor = shouldAnchorToBottom()
                val newPadding = computeTargetPadding(ime.bottom, navBars.bottom)
                if (VERBOSE_IME_DEBUG) {
                    Log.d(FLASH_TAG, "  → NOT animating: willAnchor=$willAnchor newPadding=$newPadding lastApplied=$lastAppliedInputPaddingBottom layoutPadding=${binding.layoutInput.paddingBottom}")
                }
                if (willAnchor) {
                    onRequestBottomAnchor()
                }
                updateContentBottomPadding(lastIme = ime.bottom, systemBottom = navBars.bottom)
            } else if (shouldAnchorToBottom()) {
                pendingBottomAnchorAfterIme = true
            }

            WindowInsetsCompat.CONSUMED
        }
    }

    fun updateContentBottomPadding(lastIme: Int, systemBottom: Int) {
        applyBottomPadding(computeTargetPadding(lastIme, systemBottom))
    }

    // Extracted so both onProgress (translation delta) and updateContentBottomPadding
    // (real layout commit) use the identical formula — guaranteeing zero discontinuity.
    private fun computeTargetPadding(lastIme: Int, systemBottom: Int): Int {
        val provider = keyboardHeightProvider()
        return if (provider == null) {
            if (isAiComposerVisibleProvider()) systemBottom + gapPx else max(lastIme, systemBottom) + gapPx
        } else {
            val pickerHeight = provider.getKeyboardHeight() + systemBottom
            val effectiveIme = when {
                isAiComposerVisibleProvider() -> 0
                isPickerModeProvider() -> max(lastIme, pickerHeight)
                else -> lastIme
            }
            max(effectiveIme, systemBottom) + gapPx
        }
    }

    fun refreshInputPadding() {
        val insets = ViewCompat.getRootWindowInsets(binding.root) ?: return
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        updateContentBottomPadding(imeBottom, navBars)
    }

    // One-shot deferreds awaiting the next IME-hide animation's onEnd. Drained on each
    // successful hide so multiple concurrent awaiters all resolve at once.
    private val pendingImeHideAwaiters = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()

    /**
     * Suspends until the next IME-hide animation completes (i.e. the keyboard finishes
     * dismissing), or until [timeoutMs] elapses. Returns true if the signal fired, false
     * if the timeout hit.
     *
     * This is the proper replacement for a `while (isVisible(ime())) delay(16)` polling
     * loop. The polling loop allocates a WindowInsetsCompat on every iteration and walks
     * the view tree; this one-shot signal has zero per-frame work and resolves exactly
     * once per dismiss.
     */
    suspend fun awaitImeHidden(timeoutMs: Long): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        pendingImeHideAwaiters.add(deferred)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            deferred.await()
            true
        } ?: false.also {
            // Timed out — drop our reference so the list does not grow unbounded.
            pendingImeHideAwaiters.remove(deferred)
        }
    }

    private fun notifyImeHidden() {
        // Drain under no lock: pendingImeHideAwaiters is only mutated on the main thread
        // (awaitImeHidden runs in lifecycleScope.launch on Main; this fires from onEnd
        // which is also on Main). The list is small (≤ a few concurrent awaiters).
        if (pendingImeHideAwaiters.isEmpty()) return
        val toComplete = pendingImeHideAwaiters.toList()
        pendingImeHideAwaiters.clear()
        toComplete.forEach { it.complete(Unit) }
    }

    private fun applyBottomPadding(targetPadding: Int) {
        val changed = lastAppliedInputPaddingBottom != targetPadding || binding.layoutInput.paddingBottom != targetPadding
        if (VERBOSE_IME_DEBUG) {
            Log.d(FLASH_TAG, "applyBottomPadding: target=$targetPadding last=$lastAppliedInputPaddingBottom layoutCurrent=${binding.layoutInput.paddingBottom} changed=$changed isAnimating=$isAnimating translationY=${binding.chatContentContainer.translationY}")
        }
        if (changed) {
            lastAppliedInputPaddingBottom = targetPadding
            binding.layoutInput.setPadding(
                binding.layoutInput.paddingLeft,
                binding.layoutInput.paddingTop,
                binding.layoutInput.paddingRight,
                targetPadding
            )
        }

        val layoutBlockedBanner = resolveBlockedBanner()
        if (layoutBlockedBanner != null &&
            (lastAppliedBlockedPaddingBottom != targetPadding || layoutBlockedBanner.paddingBottom != targetPadding)
        ) {
            lastAppliedBlockedPaddingBottom = targetPadding
            layoutBlockedBanner.setPadding(
                layoutBlockedBanner.paddingLeft,
                layoutBlockedBanner.paddingTop,
                layoutBlockedBanner.paddingRight,
                targetPadding
            )
        }
    }

    private fun setKeyboardAnimating(animating: Boolean) {
        if (isAnimating == animating) return
        isAnimating = animating
        onKeyboardAnimationChanged(animating)
    }

    private fun shouldAnchorToBottom(): Boolean {
        return itemCountProvider() > 0 && isAtBottomProvider()
    }

    companion object {
        private const val TAG = "KeyboardPerfDebug"
        // Separate tag for flash/flicker diagnostics. Filter with:
        //   adb logcat -s ImeFlashTrace
        // Covers: onApplyWindowInsets, applyBottomPadding, PreDraw translationY reset,
        // bottom-anchor requests, GIF start/stop events, setupBottomAnchoring fires.
        internal const val FLASH_TAG = "ImeFlashTrace"

        // Threshold (px) for inferring SHOW vs HIDE from the base padding captured in
        // onPrepare. When the keyboard is hidden the base padding is only nav+gap
        // (~143 px); when shown it is keyboard+gap (~1016 px). Any soft keyboard is far
        // taller than any navigation bar, so 300 px cleanly separates the two.
        private const val SHOW_DETECT_THRESHOLD_PX = 300

        // Per-frame Log.d is gated on this flag. Default false in release builds so the
        // ~30 Log.d calls + string interpolations per IME show do not compete with the
        // translationY write for the 16ms frame budget. Set to true (or run a debug
        // build) to re-enable.
        //
        // Filter in logcat: tag:KeyboardPerfDebug
        // onPrepare logs: basePadding at animation start
        // onProgress logs: per-frame ime/nav/delta/translationY (µs cost)
        // onEnd logs: settle time ms + final applied padding
        // val (not const val) because BuildConfig.DEBUG is a Java static final, not a
        // Kotlin compile-time constant.
        val VERBOSE_IME_DEBUG: Boolean = BuildConfig.DEBUG
    }
}
