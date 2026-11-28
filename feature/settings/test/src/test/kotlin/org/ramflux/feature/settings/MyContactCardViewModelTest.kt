// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.ContactAttempt
import org.ramflux.data.ContactCommand
import org.ramflux.data.ContactDirectoryRepository
import org.ramflux.data.ContactOperation
import org.ramflux.data.ContactRequestSummary
import org.ramflux.data.ContactSummary
import org.ramflux.data.OwnContactCard

@OptIn(ExperimentalCoroutinesApi::class)
class MyContactCardViewModelTest {
    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private class Cards(var result: ClientResult<OwnContactCard>) : ContactDirectoryRepository {
        val asked = mutableListOf<String>()
        override suspend fun ownCard(account: String): ClientResult<OwnContactCard> {
            asked += account
            return result
        }
        override suspend fun contacts(account: String) = ClientResult.Ready(emptyList<ContactSummary>())
        override suspend fun requests(account: String) = ClientResult.Ready(emptyList<ContactRequestSummary>())
        override suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> = error("unused")
        override fun attempts(account: String): List<ContactAttempt> = emptyList()
        override suspend fun availability(operation: ContactOperation): ClientResult<Unit> = error("unused")
    }

    @Test fun showsTheActiveAccountsCardAsTextAndQr() = runTest(timeout = 5.seconds) {
        val cards = Cards(ClientResult.Ready(OwnContactCard("ramflux-card:v3:abc", "ramflux://contact/v3/abc")))
        val vm = MyContactCardViewModel(FakeSettingsAccounts(), cards, FakeSettingsSession())
        vm.load()
        runCurrent()
        assertEquals(listOf("a"), cards.asked)
        assertEquals("ramflux-card:v3:abc", vm.state.value.text)
        assertTrue(vm.state.value.qrModules > 0)
        var dark = 0
        vm.lendQr { module -> for (x in 0 until vm.state.value.qrModules) if (module(x, 0)) dark++ }
        assertTrue("the QR finder row is drawn", dark > 0)
        vm.close()
        assertEquals(MyCardState(), vm.state.value)
    }

    @Test fun aRefusedExportIsAnHonestFailure() = runTest(timeout = 5.seconds) {
        val cards = Cards(ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE)))
        val vm = MyContactCardViewModel(FakeSettingsAccounts(), cards, FakeSettingsSession())
        vm.load()
        runCurrent()
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.loading)
        assertEquals(null, vm.state.value.text)
    }
}
