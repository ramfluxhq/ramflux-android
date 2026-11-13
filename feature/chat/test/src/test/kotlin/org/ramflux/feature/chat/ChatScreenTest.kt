// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftIsAnActionAndBlockedSendCannotFire() {
        val actions = mutableListOf<ChatAction>()
        compose.setContent {
            RamfluxTheme { ChatScreen(ChatState(loaded = true, draft = "Draft", unavailable = "Sending unavailable"), { actions.add(it) }) }
        }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Draft").performTextInput("!")
        compose.runOnIdle { assertTrue(actions.single() is ChatAction.Draft) }
    }

    @Test fun deletedRowAndReadErrorRenderWithoutEmptyHistoryClaim() {
        compose.setContent {
            RamfluxTheme {
                ChatScreen(
                    ChatState(problem = "Read failed", rows = persistentListOf(MessageItem("m", "", false, true, "Message deleted"))),
                    {}
                )
            }
        }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No messages").assertDoesNotExist()
        compose.onNodeWithText("Message deleted").assertExists()
    }
}
