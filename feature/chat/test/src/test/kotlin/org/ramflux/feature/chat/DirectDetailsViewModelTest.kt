// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ramflux.data.ClientResult
import org.ramflux.data.ContactAttempt
import org.ramflux.data.ContactCommand
import org.ramflux.data.ContactDirectoryRepository
import org.ramflux.data.ContactOperation
import org.ramflux.data.ContactRequestSummary
import org.ramflux.data.ContactSafety
import org.ramflux.data.ContactSummary
import org.ramflux.data.ConversationHeader
import org.ramflux.data.ConversationUsage
import org.ramflux.data.MediaStorage
import org.ramflux.data.SafetyState

@OptIn(ExperimentalCoroutinesApi::class)
class DirectDetailsViewModelTest {
    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private class Contacts : ContactDirectoryRepository {
        var safetyReads = 0
        override suspend fun contacts(account: String) =
            ClientResult.Ready(listOf(ContactSummary("link", "principal-alice", "Alice", false, ROOT)))
        override suspend fun requests(account: String) = ClientResult.Ready(emptyList<ContactRequestSummary>())
        override suspend fun safety(account: String, root: String): ClientResult<ContactSafety> {
            safetyReads++
            return ClientResult.Ready(ContactSafety("12345 67890", "fp", SafetyState.CHANGED, verifiable = true))
        }
        override suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> = error("unused")
        override fun attempts(account: String): List<ContactAttempt> = emptyList()
        override suspend fun availability(operation: ContactOperation): ClientResult<Unit> = error("unused")
    }

    private class History : org.ramflux.data.MessageHistoryRepository by FakeChatHistory() {
        var heading: ClientResult<ConversationHeader> = ClientResult.Ready(ConversationHeader(null, peer = ROOT, muted = false))
        val muteCalls = mutableListOf<Boolean>()
        var muteResult: (Boolean) -> ClientResult<Boolean> = { ClientResult.Ready(it) }
        override suspend fun header(account: String, conversation: String) = heading
        override suspend fun setMuted(account: String, conversation: String, muted: Boolean): ClientResult<Boolean> {
            muteCalls += muted
            return muteResult(muted)
        }
    }

    private class Media : org.ramflux.data.MediaRepository by FakeChatMedia() {
        var bytes = 2048L
        val purged = mutableListOf<String>()
        override suspend fun storage(account: String) = MediaStorage(bytes, 0, listOf(ConversationUsage("dm:a", bytes)))
        override suspend fun purgeConversation(account: String, conversation: String) {
            purged += conversation
            bytes = 0
        }
    }

    @Test fun showsTheContactMuteSafetyAndStorageAndMutesThroughTheSdk() = runTest(timeout = 5.seconds) {
        val history = History()
        val media = Media()
        val vm = DirectDetailsViewModel(history, Contacts(), FakeChatSession(), media)
        vm.open("a", "dm:a", "b2b2b2b2b2…")
        runCurrent()
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("the contact's name replaces the short identifier", "Alice", state.name)
        assertEquals(false, state.muted)
        assertEquals("12345 67890", state.safetyNumber)
        assertEquals(SafetyState.CHANGED, state.safety)
        assertEquals(2048L, state.storageBytes)
        vm.onAction(DirectDetailsAction.SetMuted(true))
        runCurrent()
        assertEquals(listOf(true), history.muteCalls)
        assertEquals(true, vm.state.value.muted)
        vm.onAction(DirectDetailsAction.ClearConfirmed)
        runCurrent()
        assertTrue("clearing needs the confirmation first", media.purged.isEmpty())
        vm.onAction(DirectDetailsAction.ClearRequested)
        vm.onAction(DirectDetailsAction.ClearConfirmed)
        runCurrent()
        assertEquals(listOf("dm:a"), media.purged)
        assertEquals(0L, vm.state.value.storageBytes)
    }

    @Test fun aRefusedMuteKeepsTheStateAndSaysSo() = runTest(timeout = 5.seconds) {
        val history = History().apply {
            muteResult = {
                refused()
            }
        }
        val vm = DirectDetailsViewModel(history, Contacts(), FakeChatSession(), Media())
        vm.open("a", "dm:a", "Alice")
        runCurrent()
        vm.onAction(DirectDetailsAction.SetMuted(true))
        runCurrent()
        assertEquals(false, vm.state.value.muted)
        assertFalse(vm.state.value.muting)
        assertTrue(vm.state.value.problem != null)
    }

    @Test fun aConversationWithoutARouteNamesNoSafetyNumber() = runTest(timeout = 5.seconds) {
        val history = History().apply { heading = ClientResult.Ready(ConversationHeader(null)) }
        val contacts = Contacts()
        val vm = DirectDetailsViewModel(history, contacts, FakeChatSession(), Media())
        vm.open("a", "dm:a", "Agent")
        runCurrent()
        assertFalse(vm.state.value.peerKnown)
        assertNull(vm.state.value.safetyNumber)
        assertEquals(0, contacts.safetyReads)
        assertEquals("Agent", vm.state.value.name)
    }

    @Test fun anUnreadableRowIsAnHonestFailure() = runTest(timeout = 5.seconds) {
        val history = History().apply {
            heading = refused()
        }
        val vm = DirectDetailsViewModel(history, Contacts(), FakeChatSession(), Media())
        vm.open("a", "dm:a", "Alice")
        runCurrent()
        assertTrue(vm.state.value.problem != null)
        assertNull(vm.state.value.muted)
    }

    private companion object {
        const val ROOT = "a1a1"

        fun refused() = ClientResult.Failed(
            org.ramflux.data.ClientProblem(org.ramflux.data.ClientFailureKind.FAILED, 1, org.ramflux.data.ClientOutcome.NONE)
        )
    }
}
