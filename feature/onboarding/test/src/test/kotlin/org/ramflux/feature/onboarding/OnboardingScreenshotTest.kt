// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenshotTest(private val page: OnboardingPage, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}")
        fun cases(): List<Array<Any>> = OnboardingPage.entries.flatMap { page -> listOf(arrayOf(page, false), arrayOf(page, true)) }
    }

    @Test fun screen() {
        val wide = page.ordinal % 2 == 0
        val identityPage = page in setOf(OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED, OnboardingPage.CONFIRM)
        val facts = if (identityPage) {
            IdentityFacts(
                "node",
                "https://node.example",
                "a".repeat(64),
                page == OnboardingPage.IDENTITY_CHANGED
            )
        } else {
            null
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(if (wide) 900.dp else 400.dp, 500.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (page == OnboardingPage.CREATE) 1.5f else 1f)) {
                    RamfluxTheme(dark) {
                        OnboardingScreen(
                            OnboardingState(
                                page = page,
                                sessionReady = true,
                                accountsLoaded = true,
                                nodesLoaded = true,
                                busy = page in setOf(OnboardingPage.CHECKING, OnboardingPage.SAVING),
                                identity = facts,
                                notice = if (page in setOf(OnboardingPage.SAVE_FAILED, OnboardingPage.CONNECTION_FAILED)) {
                                    OnboardingNotice(
                                        "Request failed. Refresh the current state."
                                    )
                                } else {
                                    null
                                }
                            ),
                            {}
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding-${page.name}-$dark.png")
    }
}
