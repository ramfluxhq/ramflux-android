// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class)
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
        compose.onNodeWithText("Verify node").performClick()
        compose.runOnIdle { assertEquals(listOf(OnboardingAction.ReviewAddress), actions) }
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

    @Test fun compactLargeTextWithImeInsetKeepsPinnedActionAndAddressVisible() {
        var imePixels = 0f
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 650.dp))) {
                val density = LocalDensity.current
                imePixels = with(density) { 180.dp.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    RamfluxTheme {
                        OnboardingScreen(
                            OnboardingState(
                                page = OnboardingPage.ADDRESS,
                                sessionReady = true,
                                address = "node.example"
                            ),
                            {},
                            contentWindowInsets = WindowInsets(bottom = 180.dp)
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("node-address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Verify node").assertIsDisplayed()
        val window = compose.onNodeWithTag("onboarding-window").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("onboarding-footer").fetchSemanticsNode().boundsInRoot
        assertEquals("IME padding is applied exactly once", window.bottom - imePixels, footer.bottom, 1f)
        val header = compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("onboarding-body").performTouchInput { swipeUp() }
        assertEquals(header, compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot())
        compose.onNodeWithText("Verify node").assertIsDisplayed()
    }
}
