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

        override suspend fun pendingJoins(account: String): ClientResult<List<MlsGroupPending>> {
            hit("pending")
            return ClientResult.Ready(emptyList())
        }

        override suspend fun members(account: String, groupId: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>> {
            hit("members")
            return ClientResult.Ready(MlsGroupPage(emptyList(), null))
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
}
