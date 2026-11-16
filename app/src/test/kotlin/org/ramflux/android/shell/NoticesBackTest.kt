// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.feature.appshell.api.NoticesRoute
import org.ramflux.feature.appshell.api.ShellHome
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticesBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 30_000)
    fun systemBackFromNoticesReturnsToMore() {
        compose.setContent {
            val stack = rememberNavBackStack(ShellHome)
            RamfluxTheme {
                NavDisplay(
                    backStack = stack,
                    onBack = { if (stack.size > 1) stack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        entry<ShellHome> {
                            MoreHub(DEFAULT_SHELL_ORDER, "", {}, {}, { stack.add(NoticesRoute) })
                        }
                        entry<NoticesRoute> { OpenSourceNotices { stack.removeLastOrNull() } }
                    }
                )
            }
        }
        compose.onNodeWithText("Open-source notices").performClick()
        compose.onNodeWithText("SIL", substring = true).assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithText("Edit navigation").assertExists()
        compose.onNodeWithText("SIL", substring = true).assertDoesNotExist()
    }
}
