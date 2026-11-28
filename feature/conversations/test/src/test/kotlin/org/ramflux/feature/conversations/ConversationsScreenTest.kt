// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.conversations.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderFailureWithoutEmptySuccessAndEmitRefreshOnly() {
        var action: ConversationsAction? = null
        compose.setContent {
            RamfluxTheme {
                ConversationsScreen(
                    ConversationsState(problem = UiText.Data("Read failed")),
                    { action = it }
                )
            }
        }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No conversations yet").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.Refresh, action) }
    }

    @Test fun successfulEmptyHasExplicitEmptyState() {
        var action: ConversationsAction? = null
        compose.setContent { RamfluxTheme { ConversationsScreen(ConversationsState(loaded = true), { action = it }) } }
        compose.onNodeWithText("No conversations yet").assertExists()
        compose.onNodeWithTag("conversation-search").assertDoesNotExist()
        compose.onNodeWithText("Add contact").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.Compose, action) }
    }

    @Test fun firstLoadShowsSkeletonAndFirstFailureIsCentredWithRetry() {
        var state by mutableStateOf(ConversationsState(loading = true, accountId = "a"))
        var action: ConversationsAction? = null
        compose.setContent { RamfluxTheme { ConversationsScreen(state, { action = it }, rooted = true) } }
        compose.onNodeWithTag("conversations-loading").assertExists()
        compose.runOnIdle { state = ConversationsState(accountId = "a", problem = UiText.Data("Offline")) }
        compose.onNodeWithTag("conversations-loading").assertDoesNotExist()
        compose.onNodeWithText("Cannot connect right now").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.Refresh, action) }
    }

    @Test fun requestsRowShowsPendingCountAndOpensRequests() {
        var action: ConversationsAction? = null
        compose.setContent {
            RamfluxTheme {
                ConversationsScreen(
                    ConversationsState(accountId = "a", loaded = true, requests = 3),
                    { action = it },
                    rooted = true
                )
            }
        }
        compose.onNodeWithText("No conversations yet").assertDoesNotExist()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithTag("conversations-requests").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.OpenRequests, action) }
        compose.onNodeWithContentDescription("New message").performClick()
        compose.runOnIdle { assertEquals(ConversationsAction.Compose, action) }
    }

    @Test fun exactUnreadTimestampAndManualMarkerStayInsideRowsUnderFixedAccountHeader() {
        val rows = (0..30).map { index ->
            ConversationItem(
                "c$index",
                "Team $index",
                uiText(R.string.ui_conversation),
                if (index == 0) ULong.MAX_VALUE.toString() else "0",
                true,
                UiText.Data("10:30")
            )
        }.toPersistentList()
        compose.setContent {
            RamfluxTheme { ConversationsScreen(ConversationsState(accountLabel = "Work account", loaded = true, rows = rows), {}) }
        }
        compose.onNodeWithText("Work account").assertExists()
        compose.onNodeWithContentDescription(
            java.text.NumberFormat.getIntegerInstance(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext<android.content.Context>().resources.configuration.locales[0]
            )
                .format(java.math.BigInteger(ULong.MAX_VALUE.toString())) + " unread messages"
        ).assertExists()
        assertTrue(compose.onAllNodesWithText("10:30", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val header = compose.onNodeWithTag("conversations-header").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        assertEquals(header, compose.onNodeWithTag("conversations-header").getUnclippedBoundsInRoot())
        compose.onAllNodesWithContentDescription("Marked unread").onFirst().assertExists()
    }
}
