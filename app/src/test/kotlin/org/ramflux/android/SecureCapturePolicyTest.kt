// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RamfluxApplication::class, sdk = [28])
class SecureCapturePolicyTest {
    @Test fun releaseSetsFlagSecureAndDebugDoesNot() {
        val release = newWindow()
        applySecureCapturePolicy(release, debugBuild = false)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, release.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)

        val debug = newWindow()
        applySecureCapturePolicy(debug, debugBuild = true)
        assertEquals(0, debug.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test fun debugBuildConfigLeavesTheWindowCapturable() {
        assertTrue(BuildConfig.DEBUG)
        val window = newWindow()
        applySecureCapturePolicy(window, BuildConfig.DEBUG)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun newWindow() = Robolectric.buildActivity(Activity::class.java).setup().get().window
}
