// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.sdk.ContactIdentityStatus

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityReviewTest {
    private class Identity(var outcome: CompletableDeferred<AcceptOutcome>) : ContactIdentityRepository {
        val accepted = mutableListOf<String>()
        override suspend fun identity(account: String, contact: String): ClientResult<Pair<IdentityState, IdentityChange?>> =
            ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
        override suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity> =
            ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
        override suspend fun accept(account: String, contact: String, newAnchor: String): AcceptOutcome {
            accepted += newAnchor
            return outcome.await()
        }
    }

    private fun change(anchor: String) = IdentityChange("peer", "alice", "old", anchor, false, 0L)

    /** Opens the sheet on `a1`, taps accept, delivers a newer change `a2` while the call runs, then finishes with [result]. */
    private fun TestScope.newerArrivesDuringAccept(result: AcceptOutcome): Pair<IdentityReview, Identity> {
        val identity = Identity(CompletableDeferred())
        val review = IdentityReview(identity, backgroundScope)
        review.open("acct", "peer", change("a1"))
        review.accept()
        runCurrent()
        assertTrue(review.state.value!!.accepting)
        review.update(change("a2"))
        identity.outcome.complete(result)
        runCurrent()
        return review to identity
    }

    @Test fun aFailedAcceptKeepsChangedAgainWhenANewerChangeArrived() = runTest(timeout = 5.seconds) {
        val (review, identity) = newerArrivesDuringAccept(AcceptOutcome.Failed("boom"))
        val shown = review.state.value!!
        assertEquals(IdentityReviewError.CHANGED_AGAIN, shown.error)
        assertEquals("a2", shown.newAnchor)
        assertFalse(shown.accepting)
        // The retry tap is a new decision: it names the newer anchor, never the one that failed.
        identity.outcome = CompletableDeferred(AcceptOutcome.Accepted)
        review.accept()
        runCurrent()
        assertEquals(listOf("a1", "a2"), identity.accepted)
    }

    @Test fun aFailedAcceptWithNoNewerChangeIsAPlainFailure() = runTest(timeout = 5.seconds) {
        val identity = Identity(CompletableDeferred(AcceptOutcome.Failed("boom")))
        val review = IdentityReview(identity, backgroundScope)
        review.open("acct", "peer", change("a1"))
        review.accept()
        runCurrent()
        assertEquals(IdentityReviewError.FAILED, review.state.value!!.error)
        assertEquals("a1", review.state.value!!.newAnchor)
    }

    @Test fun alreadyHandledNeverClosesOverANewerPendingChange() = runTest(timeout = 5.seconds) {
        val outcomes = mutableListOf<AcceptOutcome>()
        val identity = Identity(CompletableDeferred())
        val review = IdentityReview(identity, backgroundScope) { _, _, outcome, _ -> outcomes += outcome }
        review.open("acct", "peer", change("a1"))
        review.accept()
        runCurrent()
        review.update(change("a2"))
        identity.outcome.complete(AcceptOutcome.AlreadyHandled)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        val shown = review.state.value!!
        assertEquals(IdentityReviewError.CHANGED_AGAIN, shown.error)
        assertEquals("a2", shown.newAnchor)
        assertEquals(listOf<AcceptOutcome>(AcceptOutcome.AlreadyHandled), outcomes)
    }

    @Test fun aChangeThatArrivesWhileHandledShowsStaysOpenOnIt() = runTest(timeout = 5.seconds) {
        val identity = Identity(CompletableDeferred(AcceptOutcome.AlreadyHandled))
        val review = IdentityReview(identity, backgroundScope)
        review.open("acct", "peer", change("a1"))
        review.accept()
        runCurrent()
        assertEquals(IdentityReviewError.ALREADY_HANDLED, review.state.value!!.error)
        review.update(change("a2"))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IdentityReviewError.CHANGED_AGAIN, review.state.value!!.error)
        assertEquals("a2", review.state.value!!.newAnchor)
    }

    @Test fun alreadyHandledOfTheOnlyChangeClosesTheSheet() = runTest(timeout = 5.seconds) {
        val identity = Identity(CompletableDeferred(AcceptOutcome.AlreadyHandled))
        val review = IdentityReview(identity, backgroundScope)
        review.open("acct", "peer", change("a1"))
        review.accept()
        runCurrent()
        assertEquals(IdentityReviewError.ALREADY_HANDLED, review.state.value!!.error)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(review.state.value)
    }

    @Test fun anAcceptedAnchorLeavesANewerChangeUnseenForTheNextTap() = runTest(timeout = 5.seconds) {
        val (review, identity) = newerArrivesDuringAccept(AcceptOutcome.Accepted)
        assertEquals(IdentityReviewError.CHANGED_AGAIN, review.state.value!!.error)
        assertEquals("a2", review.state.value!!.newAnchor)
        assertEquals(listOf("a1"), identity.accepted)
    }

    @Test fun aStatusReReadCarriesTheAiMarkerAnEventGave() {
        val status = ContactIdentityStatus("changed", "n", "p")
        val change = DefaultContactIdentityRepository.pendingChange("peer", IdentityState.CHANGED, status, "bot" to true, 7L)!!
        assertEquals("the status read keeps the AI marker", true, change.aiFlag)
        assertEquals("bot", change.username)
        assertEquals("n", change.newAnchor)
    }

    @Test fun aStatusWithItsOwnAiFlagWinsAndNoFlagMeansUnknown() {
        val own = ContactIdentityStatus("changed", "n", null, aiFlag = true)
        assertEquals(true, DefaultContactIdentityRepository.pendingChange("peer", IdentityState.CHANGED, own, null, 0L)!!.aiFlag)
        val human = ContactIdentityStatus("changed", "n", null, aiFlag = false)
        assertEquals(false, DefaultContactIdentityRepository.pendingChange("peer", IdentityState.CHANGED, human, "bot" to true, 0L)!!.aiFlag)
        val none = ContactIdentityStatus("changed", "n", null)
        assertNull("no flag is unknown, not human", DefaultContactIdentityRepository.pendingChange("peer", IdentityState.CHANGED, none, null, 0L)!!.aiFlag)
        assertNull(DefaultContactIdentityRepository.pendingChange("peer", IdentityState.TOFU, own, null, 0L))
    }

    @Test fun anUnknownFlagNeverOverridesAKnownAiMarker() = runTest(timeout = 5.seconds) {
        // After a relaunch the status has no flag: the SDK header's flag (passed to open) is kept, also over a later update.
        val review = IdentityReview(Identity(CompletableDeferred()), backgroundScope)
        val unknown = IdentityChange("peer", null, "old", "a1", null, 0L)
        review.open("acct", "peer", unknown, null, aiFlag = true)
        assertTrue(review.state.value!!.aiFlag)
        review.update(IdentityChange("peer", null, "old", "a1", null, 1L))
        assertTrue("an unknown update keeps the known marker", review.state.value!!.aiFlag)
        review.update(IdentityChange("peer", null, "old", "a2", false, 2L))
        assertFalse("a known flag from the SDK does replace it", review.state.value!!.aiFlag)
    }
}
