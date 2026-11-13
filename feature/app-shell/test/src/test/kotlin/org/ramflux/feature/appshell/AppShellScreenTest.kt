// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.rememberNavBackStack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.feature.appshell.api.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenOnlyEmitsTypedActionsAndDisplaysUnavailableState() {
        var selected: AppShellAction? = null
        compose.setContent {
            RamfluxTheme {
                AppShellScreen(
                    AppShellState(
                        ready = true,
                        loading = false,
                        notice = ShellNotice(UiText.Data("Not available yet"), UiText.Data("Feature unavailable"))
                    ),
                    {
                        selected = it
                    }
                )
            }
        }
        compose.onNodeWithText("Set up your account").performClick()
        compose.runOnIdle { assertEquals(AppShellAction.Open(org.ramflux.data.ClientFeature.ONBOARDING), selected) }
        compose.onNodeWithText("Feature unavailable").assertExists()
    }

    @Test fun navigationKeysSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var size = 0
        var push: (() -> Unit)? = null
        restoration.setContent {
            val stack = rememberNavBackStack(ShellHome)
            val currentSize = stack.size
            SideEffect {
                size = currentSize
                push = { stack.add(FeatureRoute("onboarding")) }
            }
        }
        compose.runOnIdle { push?.invoke() }
        compose.waitForIdle()
        assertEquals(2, size)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(2, size) }
    }
}
