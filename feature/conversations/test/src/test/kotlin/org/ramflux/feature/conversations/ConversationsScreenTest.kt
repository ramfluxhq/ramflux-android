// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderFailureWithoutEmptySuccessAndEmitRefreshOnly() {
        var action: ConversationsAction? = null
        compose.setContent { RamfluxTheme { ConversationsScreen(ConversationsState(problem = "Read failed"), { action = it }) } }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No conversations").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.Refresh, action) }
    }

    @Test fun successfulEmptyHasExplicitEmptyState() {
        compose.setContent { RamfluxTheme { ConversationsScreen(ConversationsState(loaded = true), {}) } }
        compose.onNodeWithText("No conversations").assertExists()
        compose.onNodeWithTag("conversation-search").assertExists()
    }

    @Test fun exactUnreadTimestampAndManualMarkerStayInsideRowsUnderFixedAccountHeader() {
        val rows = (0..30).map { index ->
            ConversationItem(
                "c$index",
                "Team $index",
                "Conversation",
                if (index == 0) ULong.MAX_VALUE.toString() else "0",
                true,
                "10:30"
            )
        }.toPersistentList()
        compose.setContent {
            RamfluxTheme { ConversationsScreen(ConversationsState(accountLabel = "Work account", loaded = true, rows = rows), {}) }
        }
        compose.onNodeWithText("Work account").assertExists()
        compose.onNodeWithContentDescription("${ULong.MAX_VALUE} unread").assertExists()
        assertTrue(compose.onAllNodesWithText("10:30", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val header = compose.onNodeWithTag("conversations-header").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        assertEquals(header, compose.onNodeWithTag("conversations-header").getUnclippedBoundsInRoot())
        compose.onAllNodesWithContentDescription("Marked unread").onFirst().assertExists()
    }
}
