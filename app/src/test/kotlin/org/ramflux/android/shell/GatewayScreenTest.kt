// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 30_000)
    fun continueOpensWelcomeAndChangeNodeIsPresent() {
        var step = ""
        compose.setContent {
            RamfluxTheme {
                GatewayScreen({ step = "welcome" }, { step = "node" })
            }
        }
        compose.onNodeWithText("Official node").assertExists()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals("welcome", step) }
        compose.onNodeWithText("Change node").assertExists()
    }
}
