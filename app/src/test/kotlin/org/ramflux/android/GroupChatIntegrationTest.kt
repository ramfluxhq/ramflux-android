// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.feature.chat.*
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.groups.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupChatIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creationCanonicalRouteLoadsActualChatWithOwnAndPeerSenderAllLeft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = FakeGroupSession()
        val accounts = FakeGroupAccounts()
        val repo = FakeGroups()
        val groups = GroupsViewModel(repo, FakeGroupContacts(), accounts, session)
        val history = object : MessageHistoryRepository {
            var validated = false
            override suspend fun groupHeader(account: String, group: String, conversation: String): ClientResult<ConversationHeader> {
                assertEquals("a", account)
                assertEquals(repo.changes.single().second.group, group)
                assertEquals("canonical", conversation)
                validated = true
                return ClientResult.Ready(ConversationHeader("Actual group", group))
            }
            override suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>> {
                assertTrue(validated)
                return ClientResult.Ready(
                    listOf(
                        MessageSummary("own", "Self identity", true, false, "Own body"),
                        MessageSummary("peer", "Peer identity", false, false, "Peer body")
                    )
                )
            }
            override suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader> =
                error("must use group header")
            override suspend fun sendAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CHAT)
            override suspend fun send(account: String, conversation: String, text: String) = sendAvailability()
        }
        val chat = ChatViewModel(history, accounts, session)
        var rendered by mutableStateOf(ChatState())
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { groups.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { chat.state.collect { rendered = it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                groups.events.collect { event ->
                    if (event is GroupsEvent.OpenChat) {
                        val route = ChatRoute(event.account, event.conversation, event.group)
                        chat.onAction(ChatAction.Load(route.accountId, route.conversationId, route.groupId))
                    }
                }
            }
            runCurrent()
            groups.onAction(GroupsAction.Visible(true))
            runCurrent()
            groups.onAction(GroupsAction.Page(GroupsPage.CREATE))
            runCurrent()
            groups.onAction(GroupsAction.Name("New group"))
            groups.onAction(GroupsAction.Create)
            runCurrent()
            groups.onAction(GroupsAction.Confirm)
            runCurrent()
            assertTrue(rendered.loaded)
            assertEquals(repo.changes.single().second.group, rendered.groupId)
            compose.setContent { RamfluxTheme { ChatScreen(rendered, chat::onAction) } }
            compose.onNodeWithText("Actual group").assertExists()
            compose.onNodeWithText("Self identity").assertExists()
            compose.onNodeWithText("Peer identity").assertExists()
            val own = compose.onNodeWithTag("own").fetchSemanticsNode().boundsInRoot
            val peer = compose.onNodeWithTag("peer").fetchSemanticsNode().boundsInRoot
            assertEquals(peer.left, own.left, 0.1f)
        } finally {
            groups.onAction(GroupsAction.Visible(false))
            chat.onAction(ChatAction.Back)
            session.background()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
