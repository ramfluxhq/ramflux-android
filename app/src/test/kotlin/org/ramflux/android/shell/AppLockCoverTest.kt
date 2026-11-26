// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.hiddenWhileCovered
import org.ramflux.feature.onboarding.CoverBackGuard
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockCoverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 30_000)
    fun backOnTheCoverNeverReachesAHandlerComposedAfterTheCover() {
        var covered by mutableStateOf(false)
        var chatOpen by mutableStateOf(false)
        var coverBack = 0
        var chatBack = 0
        compose.setContent {
            CoverBackGuard(covered) { coverBack++ }
            // A chat opened after the app started: its handler is composed after the cover's first composition.
            if (chatOpen) BackHandler { chatBack++ }
        }
        compose.runOnIdle { chatOpen = true }
        compose.runOnIdle { covered = true }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("the cover takes back", 1, coverBack)
        assertEquals("the chat underneath is not popped", 0, chatBack)
        compose.runOnIdle { covered = false }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("back reaches the chat again once the cover is gone", 1, chatBack)
        assertEquals(1, coverBack)
    }

    @Test(timeout = 30_000)
    fun theShellUnderTheCoverLeavesTheAccessibilityTree() {
        var covered by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.hiddenWhileCovered(covered)) {
                Box(Modifier.testTag("chat-header")) { Text("Chat") }
            }
        }
        compose.onNodeWithTag("chat-header").assertExists()
        compose.runOnIdle { covered = true }
        compose.onNodeWithTag("chat-header").assertDoesNotExist()
        compose.runOnIdle { covered = false }
        compose.onNodeWithTag("chat-header").assertExists()
    }
}
