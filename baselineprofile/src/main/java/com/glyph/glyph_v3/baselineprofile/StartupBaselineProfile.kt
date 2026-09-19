package com.glyph.glyph_v3.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Cold-start startup profile for Glyph.
 *
 * Exercises the REAL user launch path only:
 *  1. force-stop the app (true cold start)
 *  2. launch through the manifest LAUNCHER activity (LeanLauncherActivity,
 *     which hands off to MainActivity) — i.e. what a user's icon tap does
 *  3. reach the chat list and allow the first useful frame (waitForIdle)
 *
 * No unrelated navigation: no tab swipes, no chat opens, no scrolling.
 * includeInStartupProfile=true marks the captured classes as the startup
 * profile, so dex2oat AOT-compiles them ahead of first use.
 */
class StartupBaselineProfile {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = "com.glyph.glyph_v3",
        includeInStartupProfile = true,
    ) {
        pressHome()
        device.executeShellCommand("am force-stop com.glyph.glyph_v3")
        // ACTION_MAIN/CATEGORY_LAUNCHER → LeanLauncherActivity → MainActivity
        startActivityAndWait()
        // First useful frame: the chat list has composed and the main thread
        // went idle. Profile capture covers everything exercised on the way.
        device.waitForIdle()
    }
}
