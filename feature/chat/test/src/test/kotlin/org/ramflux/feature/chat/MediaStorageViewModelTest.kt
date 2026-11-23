// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.ConversationUsage
import org.ramflux.data.MediaStorage

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStorageViewModelTest {
    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private class StorageMedia : org.ramflux.data.MediaRepository by FakeChatMedia() {
        var stored = MediaStorage(30, 10, listOf(ConversationUsage("c1", 15), ConversationUsage("c2", 5)))
        val cleared = mutableListOf<String>()
        var evictions = 0
        override suspend fun storage(account: String) = stored
        override suspend fun evict(account: String, nowMs: Long): Long {
            evictions++
            return 0
        }
        override suspend fun purgeConversation(account: String, conversation: String) {
            cleared += conversation
            stored = stored.copy(conversations = stored.conversations.filter { it.conversation != conversation })
        }
    }

    @Test fun usageByConversationResolvesTitlesAndClearsOnlyAfterConfirmation() = runTest(timeout = 5.seconds) {
        val media = StorageMedia()
        val history = object : org.ramflux.data.MessageHistoryRepository by FakeChatHistory() {
            override suspend fun conversationTitles(account: String, conversations: Set<String>) = ClientResult.Ready(mapOf("c1" to "Dana"))
        }
        val session = FakeChatSession()
        val vm = MediaStorageViewModel(media, history, FakeChatAccounts(), session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.onAction(MediaStorageAction.Load("a"))
        runCurrent()
        val state = vm.state.value
        assertEquals(1, media.evictions)
        assertEquals(30L, state.total)
        assertEquals(listOf("Dana", ""), state.rows.map { it.title })
        vm.onAction(MediaStorageAction.AskClear("c1"))
        runCurrent()
        assertEquals("c1", vm.state.value.confirm)
        assertTrue(media.cleared.isEmpty())
        vm.onAction(MediaStorageAction.ConfirmClear)
        runCurrent()
        assertEquals(listOf("c1"), media.cleared)
        assertEquals(listOf("c2"), vm.state.value.rows.map { it.conversation })
        assertNull(vm.state.value.confirm)
        session.status.value = ClientSnapshot(ClientPhase.CLOSED, 2)
        runCurrent()
        assertTrue(vm.state.value.closed)
    }
}
