// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenEmitsAddressAndDiscoveryActionsWithoutStartingWork() {
        val actions = mutableListOf<OnboardingAction>()
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(page = OnboardingPage.ADDRESS, sessionReady = true, address = "node.example"),
                    { actions.add(it) }
                )
            }
        }
        compose.onNodeWithText("Check address").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(OnboardingAction.Discover), actions) }
    }

    @Test fun failedReadDoesNotRenderEmptyAccountClaim() {
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(sessionReady = true, notice = OnboardingNotice("Read failed")),
                    {}
                )
            }
        }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No accounts are saved on this device.").assertDoesNotExist()
    }
}
