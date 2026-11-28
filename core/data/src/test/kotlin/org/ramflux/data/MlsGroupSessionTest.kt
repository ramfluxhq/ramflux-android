// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlsGroupSessionTest {
    private val account = "acct"
    private val group = "g1"

    private class Fake : MlsGroupRepository {
        val calls = mutableMapOf<String, Int>()
        val events = MutableSharedFlow<MlsGroupSignal>(extraBufferCapacity = 64)
        var joinGate: CompletableDeferred<ClientResult<MlsGroupJoin>>? = null
        var sendGate: CompletableDeferred<ClientResult<MlsGroupSend>>? = null
        var historyGate: CompletableDeferred<ClientResult<MlsGroupHistory>>? = null
        var history: (Long?, Long?) -> ClientResult<MlsGroupHistory> = { _, _ -> ClientResult.Ready(MlsGroupHistory(emptyList(), 0, 0, false)) }
        var running = 0
        var maxRunning = 0

        private fun hit(name: String) {
            calls[name] = (calls[name] ?: 0) + 1
        }

        fun count(name: String) = calls[name] ?: 0
        override suspend fun list(account: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroup>> {
            hit("list")
            return ClientResult.Ready(MlsGroupPage(emptyList(), null))
        }

        override suspend fun info(account: String, groupId: String): ClientResult<MlsGroup> {
            hit("info")
            return ClientResult.Unavailable(ClientFeature.GROUPS)
        }

        override suspend fun create(account: String, name: String, approval: Boolean): ClientResult<MlsGroupCreated> {
            hit("create")
            return ClientResult.Unavailable(ClientFeature.GROUPS)
        }

        override suspend fun createInvite(account: String, groupId: String, maxUses: Int, ttlSecs: Long): ClientResult<MlsGroupInvite> {
            hit("createInvite")
            return ClientResult.Unavailable(ClientFeature.GROUPS)
        }

        override suspend fun invites(account: String, groupId: String): ClientResult<List<MlsGroupInvite>> {
            hit("invites")
            return ClientResult.Ready(emptyList())
        }

        override suspend fun revokeInvite(account: String, groupId: String, inviteId: String): ClientResult<Unit> {
            hit("revoke")
            return ClientResult.Ready(Unit)
        }

        override suspend fun join(account: String, link: String): ClientResult<MlsGroupJoin> {
            hit("join")
            return joinGate?.await() ?: ClientResult.Ready(MlsGroupJoin.Refused(MlsGroupRefusal.INVITE_INVALID))
        }

        var memberRows: List<MlsGroupMember> = emptyList()
        var pendingGate: CompletableDeferred<ClientResult<List<MlsGroupPending>>>? = null
        override suspend fun pendingJoins(account: String): ClientResult<List<MlsGroupPending>> {
            hit("pending")
            return pendingGate?.await() ?: ClientResult.Ready(emptyList())
        }

        override suspend fun members(account: String, groupId: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>> {
            hit("members")
            return ClientResult.Ready(MlsGroupPage(memberRows, null))
        }

        override suspend fun history(account: String, groupId: String, afterLogSeq: Long?, beforeLogSeq: Long?, limit: Int): ClientResult<MlsGroupHistory> {
            hit("history")
            running += 1
            maxRunning = maxOf(maxRunning, running)
            try {
                return historyGate?.await() ?: history(afterLogSeq, beforeLogSeq)
            } finally {
                running -= 1
            }
        }

        override suspend fun send(account: String, groupId: String, text: String, replyTo: String?, mentions: List<String>): ClientResult<MlsGroupSend> {
            hit("send")
            return sendGate?.await() ?: ClientResult.Ready(MlsGroupSend("p-$text", MlsGroupPostState.Sent(1)))
        }

        override suspend fun refresh(account: String, groupId: String): ClientResult<Unit> {
            hit("refresh")
            return ClientResult.Ready(Unit)
        }

        override suspend fun markRead(account: String, groupId: String, logSeq: Long): ClientResult<MlsGroupRead> {
            hit("markRead")
            return ClientResult.Ready(MlsGroupRead(logSeq, 0, 0))
        }

        override suspend fun delete(account: String, groupId: String, postId: String): ClientResult<MlsGroupSend> {
            hit("delete")
            return ClientResult.Ready(MlsGroupSend(postId, MlsGroupPostState.Deleted))
        }

        override suspend fun leave(account: String, groupId: String): ClientResult<Unit> {
            hit("leave")
            return ClientResult.Ready(Unit)
        }

        override fun events(account: String): Flow<MlsGroupSignal> = events
    }

    private fun post(logSeq: Long?, id: String = "p$logSeq", anchor: String = "a1", ai: Boolean = false, me: Boolean = false, text: String = "t$logSeq") =
        MlsGroupPost(
            logSeq, id, MlsGroupAuthor(anchor, "Ana", ai, me), 100L + (logSeq ?: 0), MlsGroupPostKind.TEXT, text, null, emptyList(),
            MlsGroupPostState.Sent(logSeq), emptyList(), null, null, null,
        )

    private fun page(vararg rows: MlsGroupPost, more: Boolean = false) = ClientResult.Ready(MlsGroupHistory(rows.toList(), 1, rows.maxOfOrNull { it.logSeq ?: 0 } ?: 0, more))

    private suspend fun MlsGroupSession.timelineNow(): MlsTimelineState = timeline(account, group).first()
    private suspend fun MlsGroupSession.joinNow(): MlsJoinState = join(account).first()

    private fun scope(test: TestScope) = TestScope(UnconfinedTestDispatcher(test.testScheduler))

    @Test fun anOlderAttemptsResultIsRecordedButNotShownAsTheCurrentOutcome() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<ClientResult<MlsGroupJoin>>()
        fake.joinGate = gate
        val s = MlsGroupSession(fake, scope(this))
        s.submitJoin(account, "rfinv-pending-0001")
        assertTrue(s.joinNow().inFlight)
        // The sheet is closed and opened again: a new attempt, the call is still running.
        s.joinSheetChanged(account)
        gate.complete(ClientResult.Ready(MlsGroupJoin.Pending("req1", "g9", MlsGroupPendingReason.AWAITING_ADMIN, 5_000_000L)))
        runCurrent()
        val state = s.joinNow()
        assertFalse(state.inFlight)
        assertNull(state.outcome)
        assertEquals(listOf("req1"), state.pending.map { it.requestId })
    }

    @Test fun noCallFollowsAnyJoinOutcome() = runTest {
        val fake = Fake()
        val s = MlsGroupSession(fake, scope(this))
        s.submitJoin(account, "rfinv-x")
        runCurrent()
        assertEquals(1, fake.count("join"))
        assertTrue(s.joinNow().outcome is MlsJoinOutcome.Refused)
        // Nothing runs again by itself: no timer, no retry.
        testScheduler.advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, fake.count("join"))
        for (failure in listOf(
            ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "host_unreachable")),
            ClientResult.Unavailable(ClientFeature.GROUPS),
        )) {
            fake.joinGate = CompletableDeferred(failure)
            s.submitJoin(account, "rfinv-x")
            runCurrent()
        }
        assertEquals(3, fake.count("join"))
        testScheduler.advanceTimeBy(600_000)
        runCurrent()
        assertEquals(3, fake.count("join"))
    }

    @Test fun aSecondTapWhileAJoinIsInFlightIsIgnoredAndBadLinksMakeNoCall() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<ClientResult<MlsGroupJoin>>()
        fake.joinGate = gate
        val s = MlsGroupSession(fake, scope(this))
        s.submitJoin(account, "  rfinv-slow  ")
        s.submitJoin(account, "rfinv-slow")
        assertEquals(1, fake.count("join"))
        gate.complete(ClientResult.Ready(MlsGroupJoin.Joined("g1")))
        runCurrent()
        s.submitJoin(account, "x".repeat(513))
        s.submitJoin(account, "rfinv has space")
        assertEquals(1, fake.count("join"))
        val outcome = s.joinNow().outcome as MlsJoinOutcome.Refused
        assertTrue(outcome.refusal.local)
        assertEquals(MlsGroupRefusal.LINK_INVALID, outcome.refusal.reason)
        assertEquals("rfinv-a", MlsJoinLink.check(" \trfinv-a\n"))
    }

    @Test fun aCancelledCallerStillHasItsOutcomeRecorded() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<ClientResult<MlsGroupJoin>>()
        fake.joinGate = gate
        val s = MlsGroupSession(fake, scope(this))
        val caller = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { s.submitJoin(account, "rfinv-gone") }
        caller.cancelAndJoin()
        gate.complete(ClientResult.Ready(MlsGroupJoin.Joined("g7")))
        runCurrent()
        assertEquals(MlsJoinOutcome.Joined("g7"), s.joinNow().outcome)
    }

    @Test fun anEventAndAHistoryRowWithTheSameLogSeqAreOneItem() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1), post(2)) }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(2, text = "from the event")))
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(3)))
        runCurrent()
        val posts = s.timelineNow().window.posts
        assertEquals(listOf(1L, 2L, 3L), posts.map { it.logSeq })
        assertEquals("from the event", posts[1].text)
    }

    @Test fun sentAfterAnEventMovesTheItemWithoutDuplicatingIt() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1)) }
        val gate = CompletableDeferred<ClientResult<MlsGroupSend>>()
        fake.sendGate = gate
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        s.send(account, group, "hello", null, emptyList())
        assertEquals(1, s.timelineNow().locals.size)
        // The event with the post arrives before the reply.
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(2, id = "p-hello", me = true, text = "hello")))
        runCurrent()
        gate.complete(ClientResult.Ready(MlsGroupSend("p-hello", MlsGroupPostState.Sent(2))))
        runCurrent()
        val t = s.timelineNow()
        assertEquals(listOf(1L, 2L), t.window.posts.map { it.logSeq })
        assertTrue(t.locals.isEmpty())
        // A queued post whose state event says sent moves to its slot too.
        gate.let { fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p-two", MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP)))) }
        s.send(account, group, "two", null, emptyList())
        runCurrent()
        assertEquals(MlsLocalPhase.QUEUED, s.timelineNow().locals.single().phase)
        fake.events.tryEmit(MlsGroupSignal.PostState(group, "p-two", MlsGroupPostState.Sent(3), 3))
        runCurrent()
        val after = s.timelineNow()
        assertTrue(after.locals.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), after.window.posts.map { it.logSeq })
    }

    @Test fun aFailedSendIsKeptAndOnlyAnExplicitRetrySendsAgain() = runTest {
        val fake = Fake()
        fake.sendGate = CompletableDeferred(ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "too_large", detail = 65536)))
        val s = MlsGroupSession(fake, scope(this))
        s.send(account, group, "big", null, emptyList())
        runCurrent()
        val local = s.timelineNow().locals.single()
        assertEquals(MlsLocalPhase.NOT_SENT, local.phase)
        assertEquals(65536L, local.failure?.detail)
        testScheduler.advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, fake.count("send"))
        fake.sendGate = null
        s.retry(account, group, local.localId)
        runCurrent()
        assertEquals(2, fake.count("send"))
    }

    @Test fun onlyOneGapFillRunsAtATime() = runTest {
        val fake = Fake()
        fake.history = { after, _ -> if (after == null) page(post(1), post(2)) else page(post((after) + 1)) }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        val gate = CompletableDeferred<ClientResult<MlsGroupHistory>>()
        fake.historyGate = gate
        // An event two ahead opens a gap; a second jump while the fill runs only queues one more round.
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(5)))
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(9)))
        runCurrent()
        assertEquals(1, fake.maxRunning)
        gate.complete(page(post(3), post(4), more = false))
        runCurrent()
        assertEquals(1, fake.maxRunning)
    }

    @Test fun theAiFlagStaysTrueAfterAFalseRow() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1, anchor = "bot", ai = true)) }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(2, anchor = "bot", ai = false)))
        runCurrent()
        val posts = s.timelineNow().window.posts
        assertTrue(posts[0].author?.aiFlag == true)
        assertTrue(posts[1].author?.aiFlag == true)
    }

    @Test fun clearAccountDropsLateResultsUntilAdmit() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<ClientResult<MlsGroupJoin>>()
        fake.joinGate = gate
        val s = MlsGroupSession(fake, scope(this))
        s.submitJoin(account, "rfinv-late")
        s.clearAccount(account)
        gate.complete(ClientResult.Ready(MlsGroupJoin.Joined("g1")))
        runCurrent()
        assertEquals(MlsJoinState(), s.joinNow())
        // A new call is refused while purged.
        s.submitJoin(account, "rfinv-new")
        runCurrent()
        assertEquals(1, fake.count("join"))
        s.admit(account)
        fake.joinGate = null
        s.submitJoin(account, "rfinv-new")
        runCurrent()
        assertEquals(2, fake.count("join"))
        // The late result of the first epoch still did not land.
        assertTrue(s.joinNow().outcome is MlsJoinOutcome.Refused)
    }

    @Test fun aGroupThatBecameReadOnlyNeverCallsSend() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1)) }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        val removed = MlsGroupPost(
            2, "", null, 5, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(), MlsGroupPostState.Sent(2),
            listOf(MlsGroupChange(MlsGroupChangeKind.REMOVED, "me", null, false, null, true)), null, null, null,
        )
        fake.events.tryEmit(MlsGroupSignal.Service(group, removed))
        runCurrent()
        assertEquals(MlsReadOnly.REMOVED, s.timelineNow().readOnly)
        s.send(account, group, "hi", null, emptyList())
        s.refreshGroup(account, group)
        runCurrent()
        assertEquals(0, fake.count("send"))
    }

    @Test fun evictingATimelineKeepsItsNotSentPostsAndInFlightResults() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1)) }
        val s = MlsGroupSession(fake, scope(this))
        s.openGroup(account, "a")
        s.openGroup(account, "b")
        runCurrent()
        fake.sendGate = CompletableDeferred(ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "too_large")))
        s.send(account, "a", "kept", null, emptyList())
        val gate = CompletableDeferred<ClientResult<MlsGroupSend>>()
        fake.sendGate = gate
        s.send(account, "b", "in flight", null, emptyList())
        s.openGroup(account, "c")
        s.openGroup(account, "d")
        runCurrent()
        assertEquals(MlsLocalPhase.NOT_SENT, s.timeline(account, "a").first().locals.single().phase)
        gate.complete(ClientResult.Ready(MlsGroupSend("pb", MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP))))
        runCurrent()
        assertEquals(MlsLocalPhase.QUEUED, s.timeline(account, "b").first().locals.single().phase)
    }

    @Test fun anSdkPendingRowArrivingDuringTheSubmitIsOneItem() = runTest {
        val fake = Fake()
        fake.history = { _, _ -> page(post(1)) }
        val gate = CompletableDeferred<ClientResult<MlsGroupSend>>()
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        fake.sendGate = gate
        s.send(account, group, "hello", null, emptyList())
        val pending = post(null, id = "p-pending", me = true, text = "hello").copy(state = MlsGroupPostState.Queued(MlsGroupQueuedReason.UNKNOWN))
        fake.events.tryEmit(MlsGroupSignal.Post(group, pending))
        runCurrent()
        gate.complete(ClientResult.Ready(MlsGroupSend("p-pending", MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP))))
        runCurrent()
        assertEquals(1, s.timelineNow().locals.size)
    }

    @Test fun theLimitLockLiftsAtTheResetTimeWithoutSendingAndALockCancelsIt() = runTest {
        val fake = Fake()
        val base = 5_000_000L
        val s = MlsGroupSession(fake, scope(this), { base + testScheduler.currentTime })
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p", MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, false, base + 10_000)))))
        s.send(account, group, "x", null, emptyList())
        runCurrent()
        assertEquals(base + 10_000, s.timelineNow().limitedUntil)
        val local = s.timelineNow().locals.single()
        // Retry while locked keeps the bubble and sends nothing.
        s.retry(account, group, local.localId)
        runCurrent()
        assertEquals(1, fake.count("send"))
        assertEquals(1, s.timelineNow().locals.size)
        testScheduler.advanceTimeBy(10_001)
        runCurrent()
        assertNull(s.timelineNow().limitedUntil)
        assertEquals(1, fake.count("send"))
        // A second lock is cancelled by clearAccount: nothing lands afterwards.
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p2", MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, false, base + 50_000)))))
        s.send(account, group, "y", null, emptyList())
        runCurrent()
        s.clearAccount(account)
        s.admit(account)
        testScheduler.advanceTimeBy(60_000)
        runCurrent()
        assertNull(s.timelineNow().limitedUntil)
        assertEquals(2, fake.count("send"))
    }

    @Test fun membershipRowsCollapseAsTheContractSays() {
        fun joined(seq: Long, vararg names: String) = MlsGroupPost(
            seq, "", null, null, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(), MlsGroupPostState.Sent(seq),
            names.map { MlsGroupChange(MlsGroupChangeKind.JOINED, it, it, false, null, false) }, null, null, null,
        )
        val lines = MlsLines.collapse(listOf(joined(1, "a", "b", "c"), joined(2, "d"), post(3), joined(4, "e")))
        assertEquals(3, lines.size)
        assertEquals(4, (lines[0] as MlsLine.Group).count)
        assertEquals(1, (lines[2] as MlsLine.Group).count)
    }

    // ---- audit round 1 regressions ----

    @Test fun aTrimmedWindowKeepsAnOwnPostThatBecomesSentAndRefreshReloadsTheNewestPage() = runTest {
        val fake = Fake()
        fake.history = { after, before ->
            when {
                before != null -> page(*((before - 50) until before).map { post(it) }.toTypedArray(), more = true)
                after != null -> page()
                else -> page(*(951L..1000L).map { post(it) }.toTypedArray(), more = true)
            }
        }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        repeat(12) { s.loadOlder(account, group); runCurrent() }
        val t0 = s.timelineNow()
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p-mine", MlsGroupPostState.Sent(1001))))
        s.send(account, group, "mine", null, emptyList())
        runCurrent()
        val t1 = s.timelineNow()
        val visible = t1.locals.any { it.text == "mine" } || t1.window.posts.any { it.postId == "p-mine" }
        // Another member's post event also held.
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(1002, id = "other")))
        runCurrent()
        // What the chat's Refresh does when loaded and no problem: only refreshGroup. And reopening.
        testScheduler.advanceTimeBy(6_000)
        s.refreshGroup(account, group)
        s.openGroup(account, group)
        runCurrent()
        val t2 = s.timelineNow()
        assertTrue("own sent post stays visible while trimmed", visible)
        assertFalse(t2.window.trimmedNewest)
        assertTrue(t2.window.posts.any { it.logSeq == 1001L })
    }

    @Test fun refreshAndReopenReloadTheNewestPageOfATrimmedWindow() = runTest {
        val fake = Fake()
        var newest = 1000L
        fake.history = { _, before ->
            when {
                before != null -> page(*((before - 50) until before).map { post(it) }.toTypedArray(), more = true)
                else -> page(*((newest - 49)..newest).map { post(it) }.toTypedArray(), more = true)
            }
        }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        repeat(12) { s.loadOlder(account, group); runCurrent() }
        assertTrue(s.timelineNow().window.trimmedNewest)
        newest = 1010L
        testScheduler.advanceTimeBy(6_000)
        s.refreshGroup(account, group)
        runCurrent()
        val refreshed = s.timelineNow().window
        assertFalse(refreshed.trimmedNewest)
        assertEquals(1010L, refreshed.newest)
        repeat(12) { s.loadOlder(account, group); runCurrent() }
        assertTrue(s.timelineNow().window.trimmedNewest)
        newest = 1020L
        s.openGroup(account, group)
        runCurrent()
        val reopened = s.timelineNow().window
        assertFalse(reopened.trimmedNewest)
        assertEquals(1020L, reopened.newest)
    }

    @Test fun anOwnPostOfATrimmedWindowThatArrivesByEventRestartsTheWindowThere() = runTest {
        val fake = Fake()
        fake.history = { after, before ->
            when {
                before != null -> page(*((before - 50) until before).map { post(it) }.toTypedArray(), more = true)
                after != null -> page()
                else -> page(*(951L..1000L).map { post(it) }.toTypedArray(), more = true)
            }
        }
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        runCurrent()
        repeat(12) { s.loadOlder(account, group); runCurrent() }
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p-mine", MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP))))
        s.send(account, group, "mine", null, emptyList())
        runCurrent()
        fake.events.tryEmit(MlsGroupSignal.Post(group, post(1001, id = "p-mine", me = true)))
        runCurrent()
        val t = s.timelineNow()
        assertTrue(t.window.posts.any { it.postId == "p-mine" })
        assertTrue(t.locals.none { it.postId == "p-mine" })
        assertFalse(t.window.trimmedNewest)
    }

    @Test fun anEvictedGroupHoldsNoWindowAndStillBumpsItsListRow() = runTest {
        val fake = Fake()
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        for (g in listOf("g1", "g2", "g3", "g4")) {
            s.openGroup(account, g)
            runCurrent()
        }
        for (i in 1L..700L) {
            fake.events.tryEmit(MlsGroupSignal.Post("g1", post(i)))
            if (i % 50 == 0L) runCurrent()
        }
        runCurrent()
        assertEquals(0, s.timeline(account, "g1").first().window.posts.size)
    }

    @Test fun theLimitTimerSendsNothingAndAClearCancelsIt() = runTest {
        val fake = Fake()
        val base = 5_000_000L
        val s = MlsGroupSession(fake, scope(this), { base + testScheduler.currentTime })
        s.watch(account)
        runCurrent()
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p", MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, false, base + 10_000)))))
        s.send(account, group, "x", null, emptyList())
        runCurrent()
        assertEquals(1, fake.count("send"))
        s.clearAccount(account)
        s.admit(account)
        testScheduler.advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, fake.count("send"))
        assertNull(s.timelineNow().limitedUntil)
    }

    @Test fun eventsForGroupsThatWereNeverOpenedHoldNoRows() = runTest {
        val fake = Fake()
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        runCurrent()
        for (g in listOf("x", "y", "z", "w", "v")) for (i in 1L..700L) fake.events.tryEmit(MlsGroupSignal.Post(g, post(i))).also { if (i % 50 == 0L) runCurrent() }
        runCurrent()
        val total = listOf("x", "y", "z", "w", "v").sumOf { s.timeline(account, it).first().window.posts.size }
        assertTrue("rows of never-opened groups are held: $total", total == 0)
    }

    @Test fun theLimitLockLiftsAtTheLatestResetWhenItMovesLater() = runTest {
        val fake = Fake()
        val base = 5_000_000L
        val s = MlsGroupSession(fake, scope(this), { base + testScheduler.currentTime })
        s.watch(account)
        runCurrent()
        // A queued post first.
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("pq", MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP))))
        s.send(account, group, "q", null, emptyList())
        runCurrent()
        // Then a send hits the limit with reset T1.
        fake.sendGate = CompletableDeferred(ClientResult.Ready(MlsGroupSend("p", MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, false, base + 10_000)))))
        s.send(account, group, "x", null, emptyList())
        runCurrent()
        // The queued post is refused later by event with a later reset T2.
        fake.events.tryEmit(MlsGroupSignal.PostState(group, "pq", MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, false, base + 30_000)), null))
        runCurrent()
        testScheduler.advanceTimeBy(40_000)
        runCurrent()
        assertNull("lock never lifts", s.timelineNow().limitedUntil)
    }

    @Test fun aPendingJoinRebuildKeepsARecordMadeDuringTheRead() = runTest {
        val fake = Fake()
        val s = MlsGroupSession(fake, scope(this))
        val gate = CompletableDeferred<ClientResult<List<MlsGroupPending>>>()
        fake.pendingGate = gate
        s.loadGroups(account)
        runCurrent()
        fake.joinGate = CompletableDeferred(ClientResult.Ready(MlsGroupJoin.Pending("req1", "g9", MlsGroupPendingReason.AWAITING_ADMIN, 9_000_000_000_000L)))
        s.submitJoin(account, "rfinv-admin")
        runCurrent()
        assertEquals(listOf("req1"), s.joinNow().pending.map { it.requestId })
        gate.complete(ClientResult.Ready(emptyList()))
        runCurrent()
        assertEquals(listOf("req1"), s.joinNow().pending.map { it.requestId })
    }

    @Test fun aReopenedSheetSeesJoiningAndASecondJoinIsRefused() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<ClientResult<MlsGroupJoin>>()
        fake.joinGate = gate
        val s = MlsGroupSession(fake, scope(this))
        s.submitJoin(account, "rfinv-a")
        s.joinSheetChanged(account)
        s.joinSheetChanged(account)
        assertTrue(s.joinNow().inFlight)
        s.submitJoin(account, "rfinv-b")
        s.submitJoin(account, "bad link")
        runCurrent()
        assertEquals(1, fake.count("join"))
        assertNull(s.joinNow().outcome)
        gate.complete(ClientResult.Ready(MlsGroupJoin.Joined("g1")))
        runCurrent()
        assertNull(s.joinNow().outcome)
    }

    @Test fun admitOfOneAccountNeverUndoesTheClearOfAnother() {
        val repo = Fake()
        var lost = 0
        var lostAdmit = 0
        repeat(3000) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val s = MlsGroupSession(repo, scope)
            kotlinx.coroutines.runBlocking {
                s.clearAccount("A")
                val a = launch(kotlinx.coroutines.Dispatchers.Default) { s.clearAccount("B") }
                val b = launch(kotlinx.coroutines.Dispatchers.Default) { s.admit("A") }
                a.join(); b.join()
            }
            val f = MlsGroupSession::class.java.getDeclaredField("data").also { it.isAccessible = true }
            val flow = f.get(s) as kotlinx.coroutines.flow.MutableStateFlow<*>
            val purged = flow.value!!.javaClass.getDeclaredMethod("getPurged").invoke(flow.value) as Set<*>
            if ("B" !in purged) lost += 1 else if ("A" in purged) lostAdmit += 1
        }
        assertEquals(0, lost)
    }

    @Test fun theAiMarkerSurvivesARejoinWithAiFlagFalseInTheMembers() = runTest {
        val fake = Fake()
        fake.memberRows = listOf(MlsGroupMember("bot", "Scout", MlsGroupRole.MEMBER, true, 1, false))
        val s = MlsGroupSession(fake, scope(this))
        s.watch(account)
        s.openGroup(account, group)
        s.loadMembers(account, group)
        runCurrent()
        fun svc(seq: Long, kind: MlsGroupChangeKind) = MlsGroupPost(
            seq, "", null, 5, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(), MlsGroupPostState.Sent(seq),
            listOf(MlsGroupChange(kind, "bot", "Scout", false, null, false)), null, null, null,
        )
        fake.events.tryEmit(MlsGroupSignal.Service(group, svc(1, MlsGroupChangeKind.LEFT)))
        fake.events.tryEmit(MlsGroupSignal.Service(group, svc(2, MlsGroupChangeKind.JOINED)))
        runCurrent()
        val row = s.members(account, group).first().rows.single { it.anchor == "bot" }
        assertTrue(row.aiFlag)
    }
}
