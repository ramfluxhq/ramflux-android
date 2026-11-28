// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlsGroupAdminTest {
    private val account = "acct"
    private val group = "g1"

    /** A repository whose admin calls wait on gates the test completes. Everything else is the typed unavailable result. */
    private class Fake : MlsGroupRepository by UnavailableMlsGroups {
        val calls = mutableMapOf<String, Int>()
        val events = MutableSharedFlow<MlsGroupSignal>(extraBufferCapacity = 64)
        var info: MlsGroup = group(MlsGroupRole.OWNER)
        var infoGate: CompletableDeferred<ClientResult<MlsGroup>>? = null
        var members: List<MlsGroupMember> = emptyList()
        var commitGate: CompletableDeferred<ClientResult<MlsGroupCommit>>? = null
        var skipGate: CompletableDeferred<ClientResult<MlsCatchUpResult>>? = null
        var pages: MutableList<CompletableDeferred<ClientResult<MlsGroupPage<MlsJoinRequest>>>> = mutableListOf()

        private fun hit(name: String) {
            calls[name] = (calls[name] ?: 0) + 1
        }

        fun count(name: String) = calls[name] ?: 0

        override suspend fun info(account: String, groupId: String): ClientResult<MlsGroup> {
            hit("info")
            return infoGate?.await() ?: ClientResult.Ready(info)
        }

        override suspend fun members(account: String, groupId: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>> {
            hit("members")
            return ClientResult.Ready(MlsGroupPage(members, null))
        }

        override suspend fun history(account: String, groupId: String, afterLogSeq: Long?, beforeLogSeq: Long?, limit: Int): ClientResult<MlsGroupHistory> {
            hit("history")
            return ClientResult.Ready(MlsGroupHistory(emptyList(), 0, 0, false))
        }

        override suspend fun refresh(account: String, groupId: String): ClientResult<Unit> = ClientResult.Ready(Unit)

        override suspend fun requests(account: String, groupId: String, cursor: String?, limit: Int): ClientResult<MlsGroupPage<MlsJoinRequest>> {
            hit("requests")
            return pages.removeAt(0).await()
        }

        override suspend fun approveRequest(account: String, groupId: String, requestId: String): ClientResult<MlsGroupCommit> {
            hit("approve")
            return commitGate!!.await()
        }

        override suspend fun removeMember(account: String, groupId: String, anchor: String): ClientResult<MlsGroupCommit> {
            hit("remove")
            return commitGate!!.await()
        }

        override suspend fun banMember(account: String, groupId: String, anchor: String): ClientResult<MlsGroupCommit> {
            hit("ban")
            return commitGate!!.await()
        }

        override suspend fun setRole(account: String, groupId: String, anchor: String, role: MlsGroupRole): ClientResult<MlsGroupCommit> {
            hit("role")
            return commitGate!!.await()
        }

        override suspend fun voidCommit(account: String, groupId: String, logSeq: Long): ClientResult<MlsGroupCommit> {
            hit("void")
            return commitGate!!.await()
        }

        override suspend fun skipCatchUp(account: String, groupId: String): ClientResult<MlsCatchUpResult> {
            hit("skip")
            return skipGate!!.await()
        }

        override fun events(account: String) = events
    }

    private companion object {
        fun group(role: MlsGroupRole, approval: Boolean = true, warnings: List<MlsGroupWarning> = emptyList(), catchUp: MlsGroupCatchUp = MlsGroupCatchUp.IDLE) = MlsGroup(
            "g1", "Design", 5, 0, approval, role, null, false, MlsGroupPush.MENTIONS_REPLIES, catchUp, 0, 0, false, null, null, null,
            1, 1, 0, 0, 0, null, warnings,
        )

        fun refused(code: String, detail: Long? = null) =
            ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = code, detail = detail))

        fun request(id: String, at: Long, expires: Long? = null) = MlsJoinRequest(id, "anchor-$id", "Person $id", false, at, expires)

        fun member(anchor: String, role: MlsGroupRole = MlsGroupRole.MEMBER, me: Boolean = false) = MlsGroupMember(anchor, "M $anchor", role, false, 1, me)

        fun service(logSeq: Long, change: MlsGroupChangeKind, anchor: String, me: Boolean = false, role: MlsGroupRole? = null) = MlsGroupPost(
            logSeq, "", null, 100, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(), MlsGroupPostState.Sent(logSeq),
            listOf(MlsGroupChange(change, anchor, "M $anchor", false, role, me)), null, null, null,
        )

        fun commitRow(logSeq: Long, kind: MlsGroupPostKind, voided: Long? = null) = MlsGroupPost(
            logSeq, "", null, 100, kind, null, null, emptyList(), MlsGroupPostState.Sent(logSeq), emptyList(), null, null, null, voidedLogSeq = voided,
        )
    }

    private fun scope(test: TestScope) = TestScope(UnconfinedTestDispatcher(test.testScheduler))

    private suspend fun MlsGroupSession.adminNow() = admin(account, group).first()

    /** A session over [fake] with the group's info and members loaded, the event watcher running. */
    private suspend fun TestScope.ready(fake: Fake, clock: () -> Long = { currentTime }): MlsGroupSession {
        var n = 0
        val s = MlsGroupSession(fake, scope(this), clock, { "act-${n++}" })
        s.watch(account)
        s.loadInfo(account, group)
        s.loadMembers(account, group)
        runCurrent()
        return s
    }

    @Test fun aSecondAdminActionOnABusyGroupIsRefusedAndTheSlotFreesOnATypedRefusal() = runTest {
        val fake = Fake()
        fake.members = listOf(member("a-dana"), member("a-eve"))
        fake.commitGate = CompletableDeferred()
        val s = ready(fake)
        s.removeMember(account, group, "a-dana")
        s.banMember(account, group, "a-eve")
        s.removeMember(account, group, "a-dana")
        assertEquals(1, fake.count("remove") + fake.count("ban"))
        assertTrue(s.adminNow().busy)
        fake.commitGate!!.complete(refused("owner_protected"))
        runCurrent()
        val state = s.adminNow()
        assertFalse(state.busy)
        assertEquals("owner_protected", state.lastFailure?.failure?.code)
        // The slot is free again: the next tap makes one call.
        fake.commitGate = CompletableDeferred()
        s.banMember(account, group, "a-eve")
        assertEquals(1, fake.count("ban"))
    }

    @Test fun anEventThatCameBeforeTheReplyResolvesThePendingAction() = runTest {
        val fake = Fake()
        fake.members = listOf(member("a-ben"))
        fake.commitGate = CompletableDeferred()
        val s = ready(fake)
        s.setRole(account, group, "a-ben", MlsGroupRole.ADMIN)
        assertEquals(1, fake.count("role"))
        // The membership event at the log position arrives half a second before the reply.
        fake.events.tryEmit(MlsGroupSignal.Service(group, service(310, MlsGroupChangeKind.ROLE_CHANGED, "a-ben", role = MlsGroupRole.ADMIN)))
        runCurrent()
        assertTrue(s.adminNow().busy)
        fake.commitGate!!.complete(ClientResult.Ready(MlsGroupCommit(310)))
        runCurrent()
        val state = s.adminNow()
        assertFalse(state.busy)
        assertNull(state.pending)
        assertEquals(MlsGroupRole.ADMIN, s.members(account, group).first().rows.single().role)
        // The event after the reply resolves it the same way.
        fake.commitGate = CompletableDeferred()
        s.removeMember(account, group, "a-ben")
        fake.commitGate!!.complete(ClientResult.Ready(MlsGroupCommit(311)))
        runCurrent()
        assertEquals(311L, s.adminNow().pending?.logSeq)
        fake.events.tryEmit(MlsGroupSignal.Service(group, service(311, MlsGroupChangeKind.REMOVED, "a-ben")))
        runCurrent()
        assertFalse(s.adminNow().busy)
    }

    @Test fun aVoidThatArrivesAsAPostResolvesThePendingUndoAndShowsTheRow() = runTest {
        val fake = Fake()
        fake.commitGate = CompletableDeferred()
        val s = ready(fake)
        s.openGroup(account, group)
        runCurrent()
        s.voidCommit(account, group, 250)
        assertEquals(1, fake.count("void"))
        fake.commitGate!!.complete(ClientResult.Ready(MlsGroupCommit(251)))
        runCurrent()
        assertEquals(251L, s.adminNow().pending?.logSeq)
        assertTrue(s.adminNow().pending != null)
        // The void row arrives only as `post{kind: void}`, never as the dedicated event.
        fake.events.tryEmit(MlsGroupSignal.Post(group, commitRow(251, MlsGroupPostKind.VOID, voided = 250)))
        runCurrent()
        val state = s.adminNow()
        assertFalse(state.busy)
        assertNull(state.pending)
        assertTrue(250L in state.voided)
        assertEquals(251L, s.timeline(account, group).first().window.posts.lastOrNull()?.logSeq)
        // The slot is free: the next admin tap makes a call.
        fake.commitGate = CompletableDeferred()
        s.voidCommit(account, group, 251)
        assertEquals(2, fake.count("void"))
    }

    @Test fun aResultAfterALockAndUnlockIsDropped() = runTest {
        val fake = Fake()
        fake.members = listOf(member("a-dana"))
        fake.commitGate = CompletableDeferred()
        val s = ready(fake)
        s.removeMember(account, group, "a-dana")
        s.clearAccount(account)
        s.admit(account)
        fake.commitGate!!.complete(ClientResult.Ready(MlsGroupCommit(400)))
        runCurrent()
        assertEquals(MlsAdminState(), s.adminNow())
        assertNull(s.info(account, group).first().group)
    }

    @Test fun aStaleInfoReadDoesNotBringBackAClearedWarning() = runTest {
        val fork = MlsGroupWarning.ForkAlarm(2)
        val fake = Fake()
        fake.info = group(MlsGroupRole.OWNER, warnings = listOf(fork))
        val s = ready(fake)
        assertEquals(setOf("fork_alarm"), s.adminNow().warnings.items.keys)
        // An info read is issued, the code is cleared by an event, then the read answers with the code still listed.
        val gate = CompletableDeferred<ClientResult<MlsGroup>>()
        fake.infoGate = gate
        s.loadInfo(account, group)
        fake.events.tryEmit(MlsGroupSignal.Warning(group, fork, cleared = true))
        fake.events.tryEmit(MlsGroupSignal.Warning(group, MlsGroupWarning.HostUnusual(MlsGroupHostUnusual.LATE_ROLL), cleared = false))
        runCurrent()
        gate.complete(ClientResult.Ready(fake.info))
        runCurrent()
        assertEquals(setOf("host_unusual"), s.adminNow().warnings.items.keys)
        // A read issued after the events replaces the whole set.
        fake.infoGate = null
        fake.info = group(MlsGroupRole.OWNER, warnings = listOf(MlsGroupWarning.Unknown("weird")))
        s.loadInfo(account, group)
        runCurrent()
        assertEquals(setOf("weird"), s.adminNow().warnings.items.keys)
    }

    @Test fun aLateSkipResultAfterAnIdleEventChangesNothing() = runTest {
        val fake = Fake()
        fake.info = group(MlsGroupRole.ADMIN, catchUp = MlsGroupCatchUp.RUNNING)
        fake.skipGate = CompletableDeferred()
        val s = ready(fake)
        assertEquals(MlsGroupCatchUp.RUNNING, s.adminNow().catchUp.state)
        s.skipCatchUp(account, group)
        s.skipCatchUp(account, group)
        assertEquals(1, fake.count("skip"))
        assertTrue(s.adminNow().catchUp.skipping)
        fake.events.tryEmit(MlsGroupSignal.CatchUp(group, 9, MlsGroupCatchUp.IDLE, 0, 0))
        runCurrent()
        fake.skipGate!!.complete(ClientResult.Ready(MlsCatchUpResult.Rejoining))
        runCurrent()
        val record = s.adminNow().catchUp
        assertEquals(MlsGroupCatchUp.IDLE, record.state)
        assertFalse(record.skipping)
        // An event with an older envelope position is ignored.
        fake.events.tryEmit(MlsGroupSignal.CatchUp(group, 8, MlsGroupCatchUp.RUNNING, 5, 9))
        runCurrent()
        assertEquals(MlsGroupCatchUp.IDLE, s.adminNow().catchUp.state)
        // No skip call without a running catch-up.
        s.skipCatchUp(account, group)
        assertEquals(1, fake.count("skip"))
    }

    @Test fun aRequestsPageOfAnOlderGenerationIsDropped() = runTest {
        val fake = Fake()
        val first = CompletableDeferred<ClientResult<MlsGroupPage<MlsJoinRequest>>>()
        val second = CompletableDeferred<ClientResult<MlsGroupPage<MlsJoinRequest>>>()
        fake.pages = mutableListOf(first, second)
        val s = ready(fake)
        s.loadRequests(account, group)
        assertTrue(s.adminNow().requests.loading)
        // Approval turns off while the first page is out: the pager closes and its generation moves on.
        fake.events.tryEmit(
            MlsGroupSignal.Service(
                group,
                commitRow(320, MlsGroupPostKind.SETTINGS).copy(settingsApproval = false),
            ),
        )
        runCurrent()
        fake.info = group(MlsGroupRole.OWNER, approval = false)
        first.complete(ClientResult.Ready(MlsGroupPage(listOf(request("r1", 1)), null)))
        runCurrent()
        assertTrue(s.adminNow().requests.rows.isEmpty())
        assertFalse(s.adminNow().requests.loaded)
        // Two first-page reads: only the newer one lands.
        fake.info = group(MlsGroupRole.OWNER)
        s.loadInfo(account, group)
        runCurrent()
        s.loadRequests(account, group)
        runCurrent()
        assertEquals(2, fake.count("requests"))
        second.complete(ClientResult.Ready(MlsGroupPage(listOf(request("r2", 1), request("r3", 2)), "c2")))
        runCurrent()
        val state = s.adminNow().requests
        assertEquals(listOf("r2", "r3"), state.rows.map { it.request.requestId })
        assertEquals("c2", state.cursor)
        // A pending_join of a known id is not added twice; an unknown one is.
        fake.events.tryEmit(MlsGroupSignal.PendingJoin(group, request("r3", 2)))
        fake.events.tryEmit(MlsGroupSignal.PendingJoin(group, request("r4", 3)))
        runCurrent()
        assertEquals(listOf("r2", "r3", "r4"), s.adminNow().requests.rows.map { it.request.requestId })
    }

    @Test fun pacedKeepsTheEntriesDisabledUntilRetryAtAndTheWakeCallsNothing() = runTest {
        val fake = Fake()
        fake.members = listOf(member("a-dana"), member("a-eve"))
        val s = ready(fake)
        val retryAt = currentTime + 5_000
        fake.commitGate = CompletableDeferred(refused("paced", retryAt))
        s.removeMember(account, group, "a-dana")
        runCurrent()
        assertEquals(retryAt, s.adminNow().pacedUntil)
        val before = s.adminNow().tick
        val calls = fake.calls.toMap()
        // While paced, a tap makes no call.
        s.banMember(account, group, "a-eve")
        assertEquals(1, fake.count("remove"))
        assertEquals(0, fake.count("ban"))
        advanceTimeBy(5_001)
        runCurrent()
        val after = s.adminNow()
        assertNull(after.pacedUntil)
        assertTrue(after.tick > before)
        // The wake changed state only: no new call, except the one coalesced info read of the first reply.
        assertEquals(calls - "info", fake.calls.toMap() - "info")
        s.banMember(account, group, "a-eve")
        assertEquals(1, fake.count("ban"))
    }

    @Test fun theHeadCommitRuleHoldsNewestCommitRowOnly() {
        fun window(vararg rows: MlsGroupPost, trimmed: Boolean = false) = MlsTimelineWindow(posts = rows.toList(), trimmedNewest = trimmed)
        val text = MlsGroupPost(
            260, "p", null, 1, MlsGroupPostKind.TEXT, "x", null, emptyList(), MlsGroupPostState.Sent(260), emptyList(), null, null, null,
        )
        val rows = arrayOf(
            commitRow(240, MlsGroupPostKind.MEMBERSHIP), commitRow(250, MlsGroupPostKind.SETTINGS), text,
        )
        assertEquals(250L, MlsAdminRules.headCommit(window(*rows), emptySet()))
        // Not while the newest end is trimmed, and not when the row is already undone.
        assertNull(MlsAdminRules.headCommit(window(*rows, trimmed = true), emptySet()))
        assertNull(MlsAdminRules.headCommit(window(*rows), setOf(250L)))
        // The newest commit row is a void row: no entry on it.
        assertNull(MlsAdminRules.headCommit(window(*rows, commitRow(251, MlsGroupPostKind.VOID, voided = 250)), emptySet()))
        // A commit that a void row in the window undoes is not offered either, and an empty window has no head.
        assertNull(MlsAdminRules.headCommit(window(commitRow(250, MlsGroupPostKind.SETTINGS), commitRow(251, MlsGroupPostKind.VOID, 250)), emptySet()))
        assertNull(MlsAdminRules.headCommit(window(), emptySet()))
    }

    @Test fun ownRemovalClearsTheSlotAndALateReplyChangesNothing() = runTest {
        val fake = Fake()
        fake.members = listOf(member("a-ana"))
        fake.commitGate = CompletableDeferred()
        val s = ready(fake)
        s.removeMember(account, group, "a-ana")
        fake.events.tryEmit(MlsGroupSignal.Service(group, service(330, MlsGroupChangeKind.REMOVED, "me", me = true)))
        runCurrent()
        assertFalse(s.adminNow().busy)
        fake.commitGate!!.complete(ClientResult.Ready(MlsGroupCommit(331)))
        runCurrent()
        assertNull(s.adminNow().pending)
        assertEquals(MlsReadOnly.REMOVED, s.timeline(account, group).first().readOnly)
        s.removeMember(account, group, "a-ana")
        assertEquals(1, fake.count("remove"))
    }
}
