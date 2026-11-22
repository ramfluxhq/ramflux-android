// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class CallSessionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSignalledCallWithoutAnEngineSaysSoAndKeepsMediaControlsOff() {
        val actions = mutableListOf<CallSessionAction>()
        compose.setContent {
            RamfluxTheme { CallSessionScreen(CallSessionState(CallStage.IN_CALL, "Dana", mediaUnavailable = true), { actions.add(it) }) }
        }
        compose.onNodeWithTag("call-media-unavailable").assertExists()
        compose.onNodeWithText("In call").assertExists()
        // A disabled Kit icon button has no click action.
        compose.onNodeWithTag("call-mute").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("call-camera").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("call-hang-up").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(CallSessionAction.HangUp), actions) }
    }
}
