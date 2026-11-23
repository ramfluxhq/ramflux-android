// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ramflux.data.*
import org.ramflux.sdk.SecretBytes

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSecurityTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(StandardTestDispatcher())
        override fun finished(description: Description) = Dispatchers.resetMain()
    }
    private fun TestScope.open(security: FakeAccountSecurity): SettingsViewModel {
        val vm = SettingsViewModel(
            FakeSettingsAccounts(), FakeDeviceDirectory(), FakeAccountStatus(), FakeSettingsAppearance(),
            FakeSettingsSession(), FakeSettingsLanguage(), security
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        return vm
    }
    private fun state(phase: PairingPhase, emoji: List<Int>? = null, proposal: String? = null, result: String? = null) = PairingState(
        "op", 2u, phase, 2_000_000_000, if (phase == PairingPhase.INVITED) null else "th", emoji,
        if (phase == PairingPhase.INVITED) null else "laptop", if (phase == PairingPhase.INVITED) null else "Laptop",
        listOf("message", "sync"), 1_900_000_000, proposal, result
    )

    @Test fun invitationIsDrawnFromErasableCharsThenCompareReviewAndConfirmedApproval() = runTest(timeout = 5.seconds) {
        val token = "{\"schema\":\"ramflux.pairing.InviteToken.v2\",\"recipient_token\":\"secret\"}".toByteArray()
        val security = FakeAccountSecurity().apply {
            begin = ClientResult.Ready(PairingInvitation("op", 2u, 2_000_000_000, SecretBytes.take(token)))
            statuses += ClientResult.Ready(state(PairingPhase.INVITED))
        }
        val vm = open(security)
        vm.onAction(SettingsAction.Page(SettingsPage.DEVICES))
        vm.onAction(SettingsAction.Page(SettingsPage.PAIRING))
        vm.onAction(SettingsAction.StartPairing)
        runCurrent()
        assertTrue(token.all { it == 0.toByte() })
        val pairing = vm.state.value.pairing
        assertEquals(PairingStep.INVITED, pairing.step)
        assertFalse(pairing.toString().contains("secret"))
        val drawn = StringBuilder()
        repeat(pairing.invitationChunks) { index -> vm.drawInvitation(index) { drawn.append(it) } }
        assertTrue(drawn.contains("secret"))

        security.statuses += ClientResult.Ready(state(PairingPhase.SAS_PENDING, emoji = listOf(0, 1, 2, 3, 4, 5, 63)))
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(PairingStep.COMPARE, vm.state.value.pairing.step)
        assertEquals(0, vm.state.value.pairing.invitationChunks)
        var after = StringBuilder()
        vm.drawInvitation(0) { after.append(it) }
        assertTrue("the invitation is erased once the joining device used it", after.isEmpty())

        security.step = ClientResult.Ready(state(PairingPhase.PROPOSAL_PENDING, proposal = "ph"))
        vm.onAction(SettingsAction.ComparePairing(true))
        runCurrent()
        assertEquals(PairingStep.REVIEW, vm.state.value.pairing.step)
        assertEquals("Laptop", vm.state.value.pairing.peerName)
        vm.onAction(SettingsAction.ApprovePairing)
        runCurrent()
        assertFalse("approval waits for the host confirmation", security.calls.any { it.startsWith("approve") })
        assertTrue(vm.state.value.pairing.confirmApprove)
        security.step = ClientResult.Ready(state(PairingPhase.COMPLETED, result = "Completed"))
        vm.onAction(SettingsAction.ConfirmApprovePairing)
        runCurrent()
        assertEquals("approve:true", security.calls.last { it.startsWith("approve") })
        assertEquals(PairingEnd.COMPLETED, vm.state.value.pairing.ended)
        assertEquals(PairingStep.ENDED, vm.state.value.pairing.step)
    }

    @Test fun leavingAnOpenInvitationCancelsItAtTheSdkAndRefusalsEndTyped() = runTest(timeout = 5.seconds) {
        val security = FakeAccountSecurity().apply {
            begin = ClientResult.Ready(PairingInvitation("op", 2u, 2_000_000_000, SecretBytes.take("{}".toByteArray())))
        }
        val vm = open(security)
        vm.onAction(SettingsAction.Page(SettingsPage.PAIRING))
        vm.onAction(SettingsAction.StartPairing)
        runCurrent()
        vm.onAction(SettingsAction.Back)
        runCurrent()
        assertEquals("cancel:op", security.calls.last())
        assertEquals(SettingsPage.DEVICES, vm.state.value.page)
        assertEquals(PairingStep.IDLE, vm.state.value.pairing.step)

        security.begin = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = "NotFound"))
        vm.onAction(SettingsAction.Page(SettingsPage.PAIRING))
        vm.onAction(SettingsAction.StartPairing)
        runCurrent()
        assertEquals(PairingEnd.FAILED, vm.state.value.pairing.ended)
        assertEquals("NotFound", vm.state.value.pairing.result)
    }

    @Test fun requestPolicyReadsAndWritesAndCodeChangeKeepsDigitsOutOfState() = runTest(timeout = 5.seconds) {
        val security = FakeAccountSecurity()
        val vm = open(security)
        vm.onAction(SettingsAction.Page(SettingsPage.ACCOUNT))
        vm.onAction(SettingsAction.Page(SettingsPage.PRIVACY))
        runCurrent()
        assertEquals(ContactRequestPolicy.MANUAL, vm.state.value.privacy.policy)
        vm.onAction(SettingsAction.RequestPolicy(ContactRequestPolicy.AUTO_ACCEPT))
        runCurrent()
        assertEquals(ContactRequestPolicy.AUTO_ACCEPT, vm.state.value.privacy.policy)
        assertEquals("policy:auto_accept", security.calls.last())
        vm.onAction(SettingsAction.Back)
        runCurrent()
        assertEquals(SettingsPage.ACCOUNT, vm.state.value.page)

        vm.onAction(SettingsAction.OpenCodeChange)
        "258036".forEach { vm.onAction(SettingsAction.CodeDigit(false, it)) }
        "740399".forEach { vm.onAction(SettingsAction.CodeDigit(true, it)) }
        vm.onAction(SettingsAction.CodeDigit(true, null))
        vm.onAction(SettingsAction.CodeDigit(true, '5'))
        runCurrent()
        assertEquals(6, vm.state.value.codeChange.nextDigits)
        assertFalse(vm.state.value.toString().contains("258036"))
        vm.onAction(SettingsAction.SubmitCodeChange)
        runCurrent()
        assertEquals("258036" to "740395", security.codes.single())
        assertTrue(vm.state.value.codeChange.done)

        security.code = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "trivial_code"))
        vm.onAction(SettingsAction.OpenCodeChange)
        "258036".forEach { vm.onAction(SettingsAction.CodeDigit(false, it)) }
        "111111".forEach { vm.onAction(SettingsAction.CodeDigit(true, it)) }
        vm.onAction(SettingsAction.SubmitCodeChange)
        runCurrent()
        assertNotNull(vm.state.value.codeChange.problem)
        assertEquals(0, vm.state.value.codeChange.currentDigits)
    }
}
