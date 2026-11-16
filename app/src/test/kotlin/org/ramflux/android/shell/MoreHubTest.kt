// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.runtime.mutableStateOf
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
class MoreHubTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 30_000)
    fun contactsOpensAndAHiddenTaskIsAbsent() {
        var opened = ""
        compose.setContent {
            RamfluxTheme {
                MoreHub(DEFAULT_SHELL_ORDER, "tasks", { opened = it }, {}, {})
            }
        }
        compose.onNodeWithText("Tasks").assertDoesNotExist()
        compose.onNodeWithText("Contacts").performClick()
        compose.runOnIdle { assertEquals("contacts", opened) }
        compose.onNodeWithText("Edit navigation").assertExists()
    }

    @Test(timeout = 30_000)
    fun noticesOpenFromTheMoreHub() {
        var opened = false
        val showNotices = mutableStateOf(false)
        compose.setContent {
            RamfluxTheme {
                if (showNotices.value) {
                    OpenSourceNotices({})
                } else {
                    MoreHub(DEFAULT_SHELL_ORDER, "", {}, {}, {
                        opened = true
                        showNotices.value = true
                    })
                }
            }
        }
        compose.onNodeWithText("Open-source notices").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
        compose.onNodeWithText("Open-source notices").assertExists()
        compose.onNodeWithText("SIL", substring = true).assertExists()
    }
}
