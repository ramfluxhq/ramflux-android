// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.feature.onboarding.impl.R

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: OnboardingViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
    private fun failure(outcome: ClientOutcome = ClientOutcome.NONE) =
        ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, outcome))

    @Test fun failedReadsNeverClaimLoadedEmptyCollections() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply { listing = { failure() } }
        val nodes = FakeNodeSetup().apply { listing = failure() }
        val vm = OnboardingViewModel(accounts, nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.accountsLoaded)
        assertFalse(vm.state.value.nodesLoaded)
        assertNotNull(vm.state.value.notice)
        assertFalse(vm.state.value.busy)
    }

    @Test fun successfulEmptyReadsAreExplicitAndDuplicateRefreshIsDeduplicated() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeAccountAccess().apply {
            listing = {
                barrier.await()
                ClientResult.Ready(AccountCatalog(emptyList(), null))
            }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Refresh)
        vm.onAction(OnboardingAction.Refresh)
        runCurrent()
        assertEquals(1, accounts.reads)
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountsLoaded)
        assertTrue(vm.state.value.nodesLoaded)
        assertNull(vm.state.value.notice)
    }

    @Test fun confirmationUsesReviewedIdentityAndOnlyAcknowledgedSaveShowsSaved() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY, vm.state.value.page)
        assertEquals("a".repeat(64), vm.state.value.identity?.fingerprint)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(FakeNodeSetup.candidate(), nodes.lastConfirmed)
        assertEquals(OnboardingPage.SAVED, vm.state.value.page)
        assertNull(vm.state.value.identity)
    }

    @Test fun changedKeyWithoutOperatorKeyCannotAdvanceConfirmation() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { discoverResult = { ClientResult.Ready(FakeNodeSetup.candidate(true)) } }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY_CHANGED, vm.state.value.page)
        assertNull("the D-5.3 card is the only warning", vm.state.value.notice)
        vm.onAction(OnboardingAction.Page(OnboardingPage.SAVED))
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY_CHANGED, vm.state.value.page)
        assertEquals(0, nodes.confirmations)
    }

    @Test fun changedKeyIsTrustedOnlyThroughTheMatchingOperatorKey() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { discoverResult = { ClientResult.Ready(FakeNodeSetup.candidate(true)) } }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY_CHANGED, vm.state.value.page)
        assertNull(vm.state.value.notice)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(1, nodes.confirmations)
        assertEquals("public-key", nodes.lastOperatorKey)
        assertEquals(OnboardingPage.SAVED, vm.state.value.page)
    }

    @Test fun anOperatorPinnedNodeIsTrustedFromTheReplyEvenWhenTheListIsNotLoaded() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply {
            listing = ClientResult.Ready(emptyList())
            discoverResult = { ClientResult.Ready(FakeNodeSetup.candidate(pinSource = "operator")) }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(true, vm.state.value.identity?.alreadyTrusted)
    }

    @Test fun aTofuPinCanBeUpgradedByTheOperatorKeyButIsTrustedWithoutOne() = runTest(timeout = 5.seconds) {
        val saved = FakeNodeSetup.candidate(pinSource = "tofu").node
        val nodes = FakeNodeSetup().apply {
            listing = ClientResult.Ready(listOf(saved))
            discoverResult = { ClientResult.Ready(FakeNodeSetup.candidate(pinSource = "tofu")) }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(true, vm.state.value.identity?.alreadyTrusted)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey("public-key"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(false, vm.state.value.identity?.alreadyTrusted)
    }

    @Test fun uncertainSaveClearsCandidateAndRequiresReadBeforeAnotherConfirmation() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { confirmResult = failure(ClientOutcome.UNKNOWN) }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(OnboardingPage.SAVE_FAILED, vm.state.value.page)
        assertEquals(OnboardingNoticeKind.UNKNOWN_OUTCOME, vm.state.value.notice?.kind)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(1, nodes.confirmations)
    }

    @Test fun namedCreateFailureStaysAVisibleError() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            beginResult = ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "CustodySetupInvalid")
            )
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.CREATE))
        vm.onAction(OnboardingAction.DisplayName("Ada"))
        vm.onAction(OnboardingAction.BeginCreate)
        runCurrent()
        assertEquals(OnboardingNoticeKind.ERROR, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(org.ramflux.core.designsystem.R.string.failure_generic, message.id)
        assertTrue(message.leaves().none { it is UiText.Number || it is UiText.Code })
    }

    @Test fun confirmWordMismatchShowsThePositionAndNotTheWord() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ ->
                ClientResult.Failed(
                    ClientProblem(
                        ClientFailureKind.FAILED,
                        5,
                        ClientOutcome.NONE,
                        statusName = "CustodyConfirmationFailed",
                        check = "word_8"
                    )
                )
            }
        }
        val traced = mutableListOf<String>()
        val vm = OnboardingViewModel(
            FakeAccountAccess(),
            FakeNodeSetup(),
            FakeOnboardingSession(),
            ceremony,
            ConfirmTrace { traced.add(it) }
        )
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(listOf("confirm.failed.5.CustodyConfirmationFailed.NONE"), traced)
        assertTrue(traced.none { it.contains("word_") || it.contains("passphrase_") })
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(org.ramflux.core.designsystem.R.string.failure_generic, message.id)
        assertTrue(message.leaves().none { it is UiText.Code })
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun setupAndLockedSelectionAreTypedUnavailableWithoutFakeAccount() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", false, "locked")), null)) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals(OnboardingPage.LOCAL_UNLOCK, vm.state.value.page)
        assertNull(vm.state.value.notice)
        assertEquals(0, accounts.selections)
        vm.onAction(OnboardingAction.Page(OnboardingPage.CREATE))
        runCurrent()
        assertEquals(OnboardingPage.CREATE, vm.state.value.page)
        assertEquals(null, vm.state.value.notice)
        vm.onAction(OnboardingAction.Page(OnboardingPage.PAIR))
        runCurrent()
        assertEquals(OnboardingPage.PAIR, vm.state.value.page)
        assertEquals(OnboardingNoticeKind.UNAVAILABLE, vm.state.value.notice?.kind)
        // Recovery lists the accounts on this phone instead of a setup refusal.
        vm.onAction(OnboardingAction.Page(OnboardingPage.RECOVER))
        runCurrent()
        assertEquals(OnboardingPage.RECOVER, vm.state.value.page)
        assertNull(vm.state.value.notice)
        assertEquals(1, accounts.setups)
    }

    @Test fun createDrawsThePhraseOnceAndLeavesItOutOfState() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony()
        val nodes = FakeNodeSetup().apply {
            listing = ClientResult.Ready(listOf(SavedNode("node-1", "https://node.example", "key", true, false, true)))
        }
        val access = FakeAccountAccess().apply { networking = { read -> ClientResult.Ready(read >= 3) } }
        val vm = OnboardingViewModel(access, nodes, FakeOnboardingSession(), ceremony)
        vm.networkWatch = 10 to 100L
        observe(vm)
        val events = mutableListOf<OnboardingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.CREATE))
        vm.onAction(OnboardingAction.DisplayName("Ada"))
        vm.onAction(OnboardingAction.BeginCreate)
        runCurrent()
        assertEquals(OnboardingPage.PHRASE, vm.state.value.page)
        assertEquals(24, vm.state.value.phraseSlots)
        assertFalse(vm.state.value.toString().contains("alpha"))
        val drawn = CharArray(8)
        vm.drawPhrase(0) { word -> word.copyInto(drawn) }
        assertEquals("alpha0", String(drawn).trimEnd('\u0000'))
        drawn.fill('\u0000')
        vm.onAction(OnboardingAction.PhraseSaved)
        runCurrent()
        var drawnAfterHide = false
        vm.drawPhrase(0) { drawnAfterHide = true }
        assertFalse(drawnAfterHide)
        assertEquals(OnboardingPage.CHALLENGE, vm.state.value.page)
        assertEquals(listOf(2, 15, 24), vm.state.value.challengePositions)
        var accepted = false
        ceremony.confirmResult = { typed, secret ->
            accepted = typed.size == 3 && typed[0].contentEquals("alpha1".toCharArray()) &&
                typed[1].contentEquals("alpha14".toCharArray()) && typed[2].contentEquals("alpha23".toCharArray()) &&
                secret.contentEquals("482915".toCharArray())
            ClientResult.Ready("account-created")
        }
        val words = challengeBuffers("alpha1", "alpha14", "alpha23")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertTrue(accepted)
        assertEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertEquals("account-created", vm.state.value.createdAccountId)
        // The completion step follows account.status: connecting while pending, then connected once live.
        assertEquals(CreatedNetworking.CONNECTING, vm.state.value.createdNetworking)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(CreatedNetworking.LIVE, vm.state.value.createdNetworking)
        assertEquals(3, access.networkingReads)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
        assertFalse(vm.state.value.toString().contains("482915"))
        assertEquals("Ada", ceremony.lastName)
        assertEquals("node-1", ceremony.lastNode)
        // Back from the created step continues into the account; it never returns to the ceremony or the start.
        vm.onAction(OnboardingAction.Back)
        runCurrent()
        assertEquals("account-created", (events.single { it is OnboardingEvent.AccountCreated } as OnboardingEvent.AccountCreated).id)
        assertFalse(events.contains(OnboardingEvent.Back))
        // Continuing selects the new account (its switch makes the startup gate read the accounts again).
        assertEquals(1, access.selections)
    }

    @Test fun stalePhraseCompletionWipesTheUnadoptedArrays() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony()
        val session = FakeOnboardingSession()
        ceremony.beforePhrase = { session.retire() }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), session, ceremony)
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.CREATE))
        vm.onAction(OnboardingAction.DisplayName("Ada"))
        vm.onAction(OnboardingAction.BeginCreate)
        runCurrent()
        assertTrue(ceremony.heldWords.isNotEmpty())
        assertTrue(ceremony.heldWords.all { word -> word.all { it == '\u0000' } })
        var drawn = false
        vm.drawPhrase(0) { drawn = true }
        assertFalse(drawn)
        assertEquals(0, vm.state.value.phraseSlots)
    }

    @Test fun clearingTheViewModelWipesTheRetainedPhrase() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony()
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.CREATE))
        vm.onAction(OnboardingAction.DisplayName("Ada"))
        vm.onAction(OnboardingAction.BeginCreate)
        runCurrent()
        assertEquals(OnboardingPage.PHRASE, vm.state.value.page)
        val words = ceremony.heldWords
        assertTrue(words[0].any { it != '\u0000' })
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { vm } })[OnboardingViewModel::class]
        store.clear()
        assertTrue(words.all { word -> word.all { it == '\u0000' } })
        var drawn = false
        vm.drawPhrase(0) { drawn = true }
        assertFalse(drawn)
    }

    @Test fun sessionCloseRemovesPrivateRowsAndDropsLateDiscovery() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val nodes = FakeNodeSetup().apply {
            discoverResult = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(FakeNodeSetup.candidate())
            }
        }
        val session = FakeOnboardingSession()
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Private", true, "unlocked")), "a")) }
        }
        val vm = OnboardingViewModel(accounts, nodes, session)
        observe(vm)
        runCurrent()
        assertEquals("Private", vm.state.value.accounts.single().label)
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        session.background()
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.sessionReady)
        assertTrue(vm.state.value.accounts.isEmpty())
        assertNull(vm.state.value.identity)
    }

    @Test fun viewModelClearCancelsLateSelectionAndNavigationEventIsOneShot() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", null, true, "unlocked")), "a")) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        val events = mutableListOf<OnboardingEvent>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals(listOf(OnboardingEvent.AccountSelected("a")), events)
        collection.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
        val barrier = CompletableDeferred<Unit>()
        accounts.listing = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", null, true, "unlocked")), "a"))
        }
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { vm } })[OnboardingViewModel::class]
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        store.clear()
        barrier.complete(Unit)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test fun addressCredentialsAreRejectedBeforeDiscovery() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("https://name:password@node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(0, nodes.discoveries)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun discoveryRequiresExplicitOriginConfirmationAndReturnToEditPreservesInput() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        val entered = "Node.Example/team?source=invite#join"
        vm.onAction(OnboardingAction.Address(entered))
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(0, nodes.discoveries)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        runCurrent()
        assertEquals(OnboardingPage.CONFIRM, vm.state.value.page)
        assertEquals(entered, vm.state.value.address)
        assertEquals("https://node.example", vm.state.value.normalizedOrigin)
        assertEquals(0, nodes.discoveries)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        runCurrent()
        assertEquals(entered, vm.state.value.address)
        assertNull(vm.state.value.normalizedOrigin)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(1, nodes.discoveries)
        assertEquals("https://node.example", nodes.lastAddress)
    }

    @Test fun typedMalformedDiscoveryFailureIsVisibleAndBusyClears() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { discoverResult = { failure() } }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.CONNECTION_FAILED, vm.state.value.page)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun immediateMainStartupDoesNotResetGenerationWhileFirstReadIsSuspended() = runTest(timeout = 5.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeAccountAccess().apply {
            listing = {
                barrier.await()
                ClientResult.Ready(AccountCatalog(emptyList(), null))
            }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountsLoaded)
        assertTrue(vm.state.value.nodesLoaded)
        assertFalse(vm.state.value.busy)
    }

    @Test fun withoutAKeyAnHttpsNodeIsShownForFirstConnectionAndAnEnteredKeyMustMatch() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        // No operator key: the https node is shown for first-connection trust.
        assertEquals(1, nodes.discoveries)
        assertNotNull(vm.state.value.identity)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        vm.onAction(OnboardingAction.OperatorKey("wrong-key"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OperatorKeyIssue.MISMATCH, vm.state.value.operatorKeyIssue)
        assertNull(vm.state.value.identity)
        vm.onAction(OnboardingAction.Confirm)
        assertEquals(0, nodes.confirmations)
        vm.onAction(OnboardingAction.OperatorKey(FakeNodeSetup.candidate().node.publicKey))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertNotNull(vm.state.value.identity)
        vm.onAction(OnboardingAction.OperatorKey("replacement"))
        assertEquals(FakeNodeSetup.candidate().node.publicKey, vm.state.value.operatorKey)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(1, nodes.confirmations)
    }

    @Test fun confirmHandlerKeepsBuffersUntilTheCallReturnsThenShowsTheAccount() = runTest(timeout = 5.seconds) {
        val gate = CompletableDeferred<ClientResult<String>>()
        var sawSecrets = false
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { typed, secret ->
                sawSecrets = typed.size == 3 && typed.all { it.isNotEmpty() } && secret.isNotEmpty()
                gate.await()
            }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertTrue(sawSecrets)
        assertTrue(vm.state.value.busy)
        assertTrue(words.all { !it.isEmpty() })
        assertFalse(passphrase.isEmpty())
        assertEquals(OnboardingPage.START, vm.state.value.page)
        gate.complete(ClientResult.Ready("account-created"))
        runCurrent()
        assertEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertEquals("account-created", vm.state.value.createdAccountId)
        assertFalse(vm.state.value.busy)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun confirmFailureShowsATypedNoticeThenErasesTheBuffers() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ ->
                ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE, statusName = "CustodyExpired"))
            }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(1, ceremony.confirmations)
        assertEquals(OnboardingNoticeKind.ERROR, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(org.ramflux.core.designsystem.R.string.failure_generic, message.id)
        assertTrue(message.leaves().none { it is UiText.Number || it is UiText.Code })
        assertFalse(vm.state.value.busy)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
    }

    @Test fun confirmThrowShowsATypedNoticeThenErasesTheBuffers() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ -> throw IllegalStateException("confirm broke") }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(1, ceremony.confirmations)
        assertEquals(OnboardingNoticeKind.UNKNOWN_OUTCOME, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_the_outcome_is_unknown_refresh_the_current_state, message.id)
        assertFalse(vm.state.value.busy)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun confirmHandlerRejectsEmptyBuffersWithoutCallingTheCeremony() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony()
        val traced = mutableListOf<String>()
        val vm = OnboardingViewModel(
            FakeAccountAccess(),
            FakeNodeSetup(),
            FakeOnboardingSession(),
            ceremony,
            ConfirmTrace { traced.add(it) }
        )
        observe(vm)
        runCurrent()
        val words = List(3) { ErasableBuffer() }
        val passphrase = ErasableBuffer()
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(0, ceremony.confirmations)
        assertEquals(listOf("confirm.rejected.3"), traced)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_enter_the_recovery_words_and_the_account_code, message.id)
        assertEquals(OnboardingNoticeKind.ERROR, vm.state.value.notice?.kind)
        assertFalse(vm.state.value.busy)
    }

    @Test fun confirmUnavailableShowsATypedNoticeThenErasesTheBuffers() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ ->
                ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
            }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(1, ceremony.confirmations)
        assertEquals(OnboardingNoticeKind.UNAVAILABLE, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_secure_setup_is_unavailable_no_phrase_or_account, message.id)
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertNull(vm.state.value.createdAccountId)
        assertFalse(vm.state.value.busy)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun confirmUnknownOutcomeShowsATypedNoticeThenErasesTheBuffers() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ ->
                ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 9, ClientOutcome.UNKNOWN))
            }
        }
        val traced = mutableListOf<String>()
        val vm = OnboardingViewModel(
            FakeAccountAccess(),
            FakeNodeSetup(),
            FakeOnboardingSession(),
            ceremony,
            ConfirmTrace { traced.add(it) }
        )
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(1, ceremony.confirmations)
        assertEquals(listOf("confirm.failed.9.none.UNKNOWN"), traced)
        assertEquals(OnboardingNoticeKind.UNKNOWN_OUTCOME, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_the_outcome_is_unknown_refresh_the_current_state, message.id)
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertFalse(vm.state.value.busy)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun cancellingConfirmErasesTheBuffersWithoutPublishingTheAccount() = runTest(timeout = 5.seconds) {
        val gate = CompletableDeferred<ClientResult<String>>()
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ -> gate.await() }
        }
        val session = FakeOnboardingSession()
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), session, ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertEquals(1, ceremony.confirmations)
        assertTrue(words.all { !it.isEmpty() })
        assertFalse(passphrase.isEmpty())
        session.retire()
        runCurrent()
        assertNull(vm.state.value.createdAccountId)
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun staleConfirmDoesNotPublishTheCreatedAccount() = runTest(timeout = 5.seconds) {
        val gate = CompletableDeferred<ClientResult<String>>()
        val session = FakeOnboardingSession()
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ -> withContext(NonCancellable) { gate.await() } }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), session, ceremony)
        observe(vm)
        runCurrent()
        val words = challengeBuffers("alpha", "bravo", "delta")
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmChallenge(words, passphrase))
        runCurrent()
        assertTrue(vm.state.value.busy)
        session.advanceGeneration()
        runCurrent()
        gate.complete(ClientResult.Ready("account-created"))
        runCurrent()
        assertNull(vm.state.value.createdAccountId)
        assertNotEquals(OnboardingPage.CREATED, vm.state.value.page)
        assertEquals(OnboardingNoticeKind.UNKNOWN_OUTCOME, vm.state.value.notice?.kind)
        val message = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_the_outcome_is_unknown_refresh_the_current_state, message.id)
        assertTrue(words.all { it.isEmpty() })
        assertTrue(passphrase.isEmpty())
    }

    @Test fun lockedAccountAsksForThePassphraseThenUnlocksOnce() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Phone", false, "locked")), null)) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals(OnboardingPage.LOCAL_UNLOCK, vm.state.value.page)
        assertEquals(0, accounts.selections)
        val empty = ErasableBuffer()
        vm.onAction(OnboardingAction.SubmitUnlock(empty))
        runCurrent()
        assertEquals(0, accounts.unlocks)
        val rejected = vm.state.value.notice?.message as UiText.Resource
        assertEquals(R.string.ui_enter_the_account_code, rejected.id)
        val events = mutableListOf<OnboardingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        val passphrase = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.SubmitUnlock(passphrase))
        runCurrent()
        assertEquals(listOf(OnboardingEvent.AccountSelected("a")), events)
        assertEquals(1, accounts.unlocks)
        assertEquals("a", accounts.lastUnlockId)
        assertTrue(passphrase.isEmpty())
        assertNull(vm.state.value.notice)
    }

    private fun TestScope.lockedVm(
        result: ClientResult<Unit>,
        ceremony: FakeAccountCeremony = FakeAccountCeremony()
    ): Pair<OnboardingViewModel, FakeAccountAccess> {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Phone", false, "locked")), null)) }
            unlockResult = result
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        vm.onAction(OnboardingAction.SubmitUnlock(ErasableBuffer().also { it.replace("482915".toCharArray()) }))
        runCurrent()
        return vm to accounts
    }

    private fun refused(name: String, retry: Long? = null, check: String? = null) = ClientResult.Failed(
        ClientProblem(
            ClientFailureKind.FAILED,
            1,
            ClientOutcome.NONE,
            statusName = name,
            check = check,
            retryAfterSecs = retry
        )
    )

    @Test fun aWrongCodeIsNamedAndNotRetriedSilently() = runTest(timeout = 5.seconds) {
        val (vm, accounts) = lockedVm(refused("AuthFailed", check = "wrong_passphrase"))
        assertEquals(1, accounts.unlocks)
        assertEquals(R.string.ui_code_wrong, (vm.state.value.notice?.message as UiText.Resource).id)
        assertNull(vm.state.value.recoverAccountId)
    }

    @Test fun aLockedAccountTheSdkRefusesShowsTheReasonAndCanBeRemoved() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = {
                ClientResult.Ready(
                    AccountCatalog(listOf(AccountSummary("a", "Phone", false, "restore_failed")), null)
                )
            }
            unlockResult = refused("account_not_found")
            labels = mapOf("a" to "Phone")
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession(), FakeAccountCeremony())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals("the code page names the account", "a", vm.state.value.unlockAccountId)
        assertFalse(vm.state.value.accounts.single().unlocked)
        assertNull(vm.state.value.forgetAccountId)
        vm.onAction(OnboardingAction.SubmitUnlock(ErasableBuffer().also { it.replace("482915".toCharArray()) }))
        runCurrent()
        assertEquals(
            org.ramflux.core.designsystem.R.string.failure_generic,
            (vm.state.value.notice?.message as UiText.Resource).id
        )
        assertEquals("a", vm.state.value.forgetAccountId)
        vm.onAction(OnboardingAction.ForgetAccount("a"))
        runCurrent()
        assertEquals(listOf("a"), accounts.forgotten)
        assertEquals("the removal notice names the removed account", listOf<String?>("Phone"), accounts.announced)
        assertEquals(OnboardingPage.START, vm.state.value.page)
        assertNull(vm.state.value.forgetAccountId)
    }

    @Test fun aWrongCodeOffersNoRemoval() = runTest(timeout = 5.seconds) {
        val (vm, _) = lockedVm(refused("AuthFailed", check = "wrong_passphrase"))
        assertNull(vm.state.value.forgetAccountId)
    }

    @Test fun aRateLimitedCodeShowsTheWaitTheSdkReported() = runTest(timeout = 5.seconds) {
        val (vm, _) = lockedVm(refused("UnlockRetryLater", 60))
        val message = vm.state.value.notice?.message as UiText.Quantity
        assertEquals(R.plurals.ui_code_retry_later, message.id)
        assertEquals(60uL, message.value)
    }

    @Test fun exhaustedAttemptsOfferPhraseRecoveryWhichNeedsTheAnchorPhraseAndCode() = runTest(timeout = 5.seconds) {
        val ceremony = FakeAccountCeremony()
        val (vm, _) = lockedVm(refused("UnlockAttemptsExhausted"), ceremony)
        assertEquals(R.string.ui_code_attempts_exhausted, (vm.state.value.notice?.message as UiText.Resource).id)
        assertEquals("a", vm.state.value.recoverAccountId)
        val events = mutableListOf<OnboardingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(OnboardingAction.BeginRecover("a"))
        runCurrent()
        assertEquals(OnboardingPage.RECOVER, vm.state.value.page)
        assertEquals(RecoveryFacts("a", "anchor-abcd-efgh"), vm.state.value.recovery)
        val phrase = ErasableBuffer().also { it.replace("abandon ability able".toCharArray()) }
        val code = ErasableBuffer().also { it.replace("482915".toCharArray()) }
        vm.onAction(OnboardingAction.ConfirmRecover(phrase, code))
        runCurrent()
        assertEquals("anchor-abcd-efgh", ceremony.lastAnchor)
        val held = ceremony.heldRecovery!!
        assertTrue(held.first.all { it == '\u0000' } && held.second.all { it == '\u0000' })
        assertTrue(phrase.isEmpty() && code.isEmpty())
        assertNull(vm.state.value.recovery)
        assertEquals(listOf(OnboardingEvent.AccountSelected("a")), events)
    }

    @Test fun anIncompleteCodeNeverReachesTheSdk() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Phone", false, "locked")), null)) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        val short = ErasableBuffer().also { it.replace("4829".toCharArray()) }
        vm.onAction(OnboardingAction.SubmitUnlock(short))
        runCurrent()
        assertEquals(0, accounts.unlocks)
        assertTrue(short.isEmpty())
        assertEquals(R.string.ui_enter_the_account_code, (vm.state.value.notice?.message as UiText.Resource).id)
    }
}

private fun challengeBuffers(first: String, second: String, third: String): List<ErasableBuffer> {
    val words = List(3) { ErasableBuffer() }
    listOf(first, second, third).forEachIndexed { index, text -> words[index].replace(text.toCharArray()) }
    return words
}

/** Every leaf argument of a message, descending into nested resources such as the shared failure detail. */
private fun UiText.Resource.leaves(): List<UiText> = args.flatMap { if (it is UiText.Resource) it.leaves() else listOf(it) }
