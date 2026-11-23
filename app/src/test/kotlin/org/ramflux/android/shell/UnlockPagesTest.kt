// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
class UnlockPagesTest {
    @get:Rule val compose = createComposeRule()

    /** MOB-A01 -> A02 -> A07 -> the code entry: the account section, the details and the info page lead on. */
    @Test(timeout = 30_000)
    fun entryDetailsAndInfoLeadToTheCodeEntry() {
        val steps = mutableListOf<String>()
        compose.setContent {
            RamfluxTheme {
                UnlockEntry("Work", "", { steps += "details" }, { steps += "node" }, { steps += "unauthorized" })
            }
        }
        compose.onNodeWithText("Account already on this device").assertExists()
        compose.onNodeWithContentDescription("Details of Work").performClick()
        compose.onNodeWithText("Unlock existing account").performClick()
        compose.runOnIdle { assertEquals(listOf("details", "details"), steps) }
    }

    @Test(timeout = 30_000)
    fun detailsOpenTheInfoPageWhoseButtonOpensTheCodeEntry() {
        val steps = mutableListOf<String>()
        compose.setContent {
            RamfluxTheme {
                UnlockDetails("Work", { steps += "back" }, { steps += "info" })
            }
        }
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText("Existing human identity").assertExists()
        compose.onNodeWithText("View device unlock info").performClick()
        compose.onNodeWithContentDescription("More information").performClick()
        compose.runOnIdle { assertEquals(listOf("info", "info"), steps) }
    }

    @Test(timeout = 30_000)
    fun infoPageExplainsTheAccountCodeAndOpensItsEntry() {
        var unlocked = 0
        compose.setContent {
            RamfluxTheme {
                UnlockInfo({}, { unlocked++ })
            }
        }
        compose.onNodeWithText("Device unlock info").assertExists()
        compose.onNodeWithText("No biometric unlock yet").assertExists()
        compose.onNodeWithText("Forgot your account code").assertExists()
        compose.onAllNodesWithText("Unlock with account code").assertCountEquals(2)
        compose.onNodeWithTag("startup-unlock-code").performClick()
        compose.runOnIdle { assertEquals(1, unlocked) }
    }
}
