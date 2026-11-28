// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.MlsGroup
import org.ramflux.data.MlsGroupChangeKind
import org.ramflux.data.MlsGroupCreated
import org.ramflux.data.MlsGroupHistory
import org.ramflux.data.MlsGroupInvite
import org.ramflux.data.MlsGroupJoin
import org.ramflux.data.MlsGroupMember
import org.ramflux.data.MlsGroupPage
import org.ramflux.data.MlsGroupPending
import org.ramflux.data.MlsGroupPendingReason
import org.ramflux.data.MlsGroupPost
import org.ramflux.data.MlsGroupPostKind
import org.ramflux.data.MlsGroupPostState
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupQueuedReason
import org.ramflux.data.MlsGroupQuota
import org.ramflux.data.MlsGroupRead
import org.ramflux.data.MlsGroupRefusal
import org.ramflux.data.MlsGroupRepository
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsGroupSend
import org.ramflux.data.MlsGroupSignal
import org.ramflux.data.UnavailableReason

private const val SEC = 1000L
private const val PAGE_ROWS = 50
private const val MEMBER_PAGE = 200
private const val DAY = 24 * 60 * 60 * SEC

/**
 * The scripted MLS groups repository of one gallery fixture (common section 6). The data is deterministic; delays are real
 * time. [asked] counts the calls by wire method name, so a runner can check that nothing was sent without a tap. F2-11 answers
 * every call with the typed unavailable result, as the native repository does at the current pin.
 */
internal class FixtureMlsGroupRepository(private val fixture: MlsGroupFixture, private val scope: CoroutineScope) : MlsGroupRepository {
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val asked: StateFlow<Map<String, Int>> = counts
    private val bus = MutableSharedFlow<MlsGroupSignal>(extraBufferCapacity = 64)

    /** The scripted M3 answers (join requests, member actions, settings, push, catch-up, void) and the controls of F3-8 and F3-12. */
    private val admin = FixtureMlsAdmin(fixture.id, scope, bus, ::hit)
    val controls: List<FixtureControl> get() = admin.controls

    private val unavailable = fixture.id == "F2-11"
    private val joined = mutableListOf<MlsGroup>()
    private val gone = mutableSetOf<String>()
    private val pending = mutableListOf<MlsGroupPending>()
    private val created = mutableMapOf<String, MlsGroup>()
    private val invites = mutableListOf<MlsGroupInvite>(
        MlsGroupInvite("inv-old", "rfinv-revoked-0001", 10, null, NOW_BASE + DAY, true),
        MlsGroupInvite("inv-exp", "rfinv-expired-0002", 100, null, NOW_BASE - DAY, false)
    )
    private var membersFailedOnce = false
    private var sends = 0
    private var inviteCount = 0
    private var scripted = false
    private val extra = mutableMapOf<String, MutableList<MlsGroupPost>>()

    private fun hit(method: String) = counts.update { it + (method to ((it[method] ?: 0) + 1)) }
    private fun now() = System.currentTimeMillis()
    private fun <T> off(): ClientResult<T> = ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)
    private fun <T> fail(code: String, detail: Long? = null): ClientResult<T> =
        ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = code, detail = detail))

    override fun events(account: String): Flow<MlsGroupSignal> = bus

    private fun baseRows(): List<MlsGroup> {
        val rows = if (fixture.id == "F2-1") listGroups() else listGroups().take(3) + if (admin.active) adminGroups() else emptyList()
        return (rows + joined + created.values).filter { it.groupId !in gone }
    }

    override suspend fun list(account: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroup>> {
        hit("group.list")
        if (unavailable) return off()
        val rows = baseRows()
        if (fixture.id != "F2-1") return ClientResult.Ready(MlsGroupPage(rows, null))
        return when (cursor) {
            null -> ClientResult.Ready(MlsGroupPage(page(rows, 0, PAGE_ROWS), "l2"))
            "l2" -> {
                val bumped = rows[11].copy(lastActivityAt = NOW_BASE + 5 * SEC * 60)
                ClientResult.Ready(MlsGroupPage(page(rows, PAGE_ROWS, 2 * PAGE_ROWS) + bumped, "l3"))
            }
            else -> ClientResult.Ready(MlsGroupPage(page(rows, 2 * PAGE_ROWS, 3 * PAGE_ROWS), null))
        }
    }

    override suspend fun info(account: String, groupId: String): ClientResult<MlsGroup> {
        hit("group.info")
        if (unavailable) return off()
        if (groupId in gone) return fail("not_member")
        val row = baseRows().firstOrNull { it.groupId == groupId }
            ?: if (groupId == G_LARGE) group(G_LARGE, "Large group", MlsGroupRole.OWNER, 1000, activity = NOW_BASE) else null
        if (row == null) return fail("not_member")
        admin.onInfo(groupId)
        val shown = admin.decorate(row)
        // F3-8: a read that was issued before a warning was cleared answers after it and still lists the warning.
        return if (admin.infoDelay()) ClientResult.Ready(shown.copy(warnings = admin.staleWarnings())) else ClientResult.Ready(shown)
    }

    override suspend fun create(account: String, name: String, approval: Boolean): ClientResult<MlsGroupCreated> {
        hit("group.create")
        if (unavailable) return off()
        delay(SEC)
        created[G_NEW] = group(G_NEW, name, MlsGroupRole.OWNER, 1, activity = now(), approval = approval)
        val invite = MlsGroupInvite("inv-new-0", "rfinv-created-0001", 100, 100, now() + 7 * DAY, false)
        invites.add(0, invite)
        return ClientResult.Ready(MlsGroupCreated(G_NEW, invite))
    }

    override suspend fun createInvite(account: String, groupId: String, maxUses: Int, ttlSecs: Long): ClientResult<MlsGroupInvite> {
        hit("group.invite.create")
        if (unavailable) return off()
        inviteCount += 1
        val invite =
            MlsGroupInvite(
                "inv-n-$inviteCount",
                "rfinv-created-%04d".format(inviteCount + 1),
                maxUses.toLong(),
                maxUses.toLong(),
                now() + ttlSecs * SEC,
                false
            )
        invites.add(0, invite)
        return ClientResult.Ready(invite)
    }

    override suspend fun invites(account: String, groupId: String): ClientResult<List<MlsGroupInvite>> {
        hit("group.invite.list")
        if (unavailable) return off()
        return ClientResult.Ready(invites.map { it.copy(usesLeft = null) })
    }

    override suspend fun revokeInvite(account: String, groupId: String, inviteId: String): ClientResult<Unit> {
        hit("group.invite.revoke")
        if (unavailable) return off()
        // The reply is slow on purpose: "Revoked" must show only after it (S6).
        delay(2 * SEC)
        val at = invites.indexOfFirst { it.inviteId == inviteId }
        if (at < 0) return fail("unknown_invite")
        invites[at] = invites[at].copy(revoked = true)
        return ClientResult.Ready(Unit)
    }

    override suspend fun join(account: String, link: String): ClientResult<MlsGroupJoin> {
        hit("group.join")
        if (unavailable) return off()
        fun refused(reason: MlsGroupRefusal, expiredAt: Long? = null, retry: Long? = null, group: String? = null) = ClientResult.Ready(
            MlsGroupJoin.Refused(reason, expiredAt, if (reason == MlsGroupRefusal.GROUP_FULL) LEAF_CAP else null, retry, group)
        )
        return when (link) {
            "rfinv-joined" -> joinedNow("g-joined", "Joined group")
            "rfinv-slow" -> {
                delay(4 * SEC)
                joinedNow("g-slow", "Slow group")
            }
            "rfinv-admin" -> pendingNow("req-admin", "g-admin", MlsGroupPendingReason.AWAITING_ADMIN)
            "rfinv-online" -> {
                val result = pendingNow("req-online", "g-online", MlsGroupPendingReason.AWAITING_MEMBER_ONLINE)
                scope.launch {
                    delay(5 * SEC)
                    pending.removeAll { it.requestId == "req-online" }
                    joined.add(group("g-online", "Online group", MlsGroupRole.MEMBER, 8, activity = now()))
                    bus.emit(MlsGroupSignal.JoinResult("g-online", "req-online", joined = true, refusal = null))
                }
                result
            }
            "rfinv-down" -> fail("host_unreachable")
            "rfinv-unknownreason" -> refused(MlsGroupRefusal.UNKNOWN)
            "rfinv-r-link_invalid" -> refused(MlsGroupRefusal.LINK_INVALID)
            "rfinv-r-invite_expired" -> refused(MlsGroupRefusal.INVITE_EXPIRED, expiredAt = now() - DAY)
            "rfinv-r-invite_invalid" -> refused(MlsGroupRefusal.INVITE_INVALID)
            "rfinv-r-group_full" -> refused(MlsGroupRefusal.GROUP_FULL)
            "rfinv-r-queue_full" -> refused(MlsGroupRefusal.QUEUE_FULL)
            "rfinv-r-request_expired" -> refused(MlsGroupRefusal.REQUEST_EXPIRED, expiredAt = now() - DAY)
            "rfinv-r-already_member" -> refused(MlsGroupRefusal.ALREADY_MEMBER, group = G_DESIGN)
            "rfinv-r-rate_limited" -> refused(MlsGroupRefusal.RATE_LIMITED, retry = 10)
            else -> refused(MlsGroupRefusal.INVITE_INVALID)
        }
    }

    private fun joinedNow(id: String, name: String): ClientResult<MlsGroupJoin> {
        joined.add(group(id, name, MlsGroupRole.MEMBER, 5, activity = now()))
        return ClientResult.Ready(MlsGroupJoin.Joined(id))
    }

    private fun pendingNow(request: String, groupId: String, reason: MlsGroupPendingReason): ClientResult<MlsGroupJoin> {
        val expires = now() + DAY
        pending.add(MlsGroupPending(request, groupId, reason, expires))
        return ClientResult.Ready(MlsGroupJoin.Pending(request, groupId, reason, expires))
    }

    override suspend fun pendingJoins(account: String): ClientResult<List<MlsGroupPending>> {
        hit("group.join.pending")
        if (unavailable) return off()
        return ClientResult.Ready(pending.toList())
    }

    private fun totalMembers(group: String) = when (group) {
        G_NEIGHBOURS -> 987
        G_LARGE -> 1000
        else -> 12
    }

    override suspend fun members(account: String, groupId: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>> {
        hit("group.members")
        if (unavailable) return off()
        admin.rosterPage(groupId, cursor)?.let { return it }
        val total = totalMembers(groupId)
        val index = cursor?.removePrefix("p")?.toIntOrNull()?.minus(1) ?: 0
        if (index == 2 && fixture.id == "F2-2" && !membersFailedOnce) {
            membersFailedOnce = true
            return fail("host_unreachable")
        }
        val from = index * MEMBER_PAGE
        val to = minOf(total, from + MEMBER_PAGE)
        val rows = (from until to).map { member(it, total) }
        return ClientResult.Ready(MlsGroupPage(rows, if (to < total) "p" + (index + 2) else null))
    }

    private fun totalPosts(group: String): Long = admin.totalPosts(group) ?: when {
        group == G_LARGE -> 20_000L
        group == G_DESIGN && fixture.id == "F2-4" -> 3L
        group == G_DESIGN -> 260L
        else -> 8L
    }

    private fun posts(group: String, from: Long, to: Long): List<MlsGroupPost> {
        val total = totalPosts(group)
        val base = (from..to).filter { it in maxOf(1L, if (admin.active) admin.floor() else 1L)..total }
            .map { admin.postOverride(group, it) ?: if (group == G_BOOK) bookPost(it) else postAt(it, total) }
        return base + (extra[group].orEmpty().filter { (it.logSeq ?: 0L) in from..to }) + admin.extraPosts(group, from, to)
    }

    private fun bookPost(seq: Long) = MlsGroupPost(
        seq, "b$seq", if (seq % 2 == 0L) ANA_A else BEN_A, NOW_BASE - (8 - seq) * 60_000L,
        MlsGroupPostKind.TEXT, "Book note $seq", null, emptyList(),
        MlsGroupPostState.Sent(seq), emptyList(), null, null, null
    )

    override suspend fun history(
        account: String,
        groupId: String,
        afterLogSeq: Long?,
        beforeLogSeq: Long?,
        limit: Int
    ): ClientResult<MlsGroupHistory> {
        hit("group.history")
        if (unavailable) return off()
        if (fixture.id == "F2-7" && groupId == G_BOOK) script()
        val total = maxOf(totalPosts(groupId) + extra[groupId].orEmpty().size, admin.extraNewest(groupId))
        val rows: List<MlsGroupPost>
        val more: Boolean
        when {
            afterLogSeq != null -> {
                rows = posts(groupId, afterLogSeq + 1, minOf(total, afterLogSeq + limit))
                more = false
            }
            beforeLogSeq != null -> {
                val low = if (admin.active) admin.floor() else 1L
                val start = maxOf(low, beforeLogSeq - limit)
                rows = posts(groupId, start, beforeLogSeq - 1)
                more = start > low
            }
            else -> {
                val low = if (admin.active) admin.floor() else 1L
                val start = maxOf(low, total - limit + 1)
                rows = posts(groupId, start, total)
                more = start > low
            }
        }
        return ClientResult.Ready(MlsGroupHistory(rows, if (admin.active) admin.floor() else 1L, total, more))
    }

    override suspend fun send(
        account: String,
        groupId: String,
        text: String,
        replyTo: String?,
        mentions: List<String>
    ): ClientResult<MlsGroupSend> {
        hit("group.send")
        if (unavailable) return off()
        sends += 1
        admin.onSend(groupId)
        val total = totalPosts(groupId) + extra[groupId].orEmpty().size
        val id = "p-send-$sends"
        fun own(seq: Long) = MlsGroupPost(
            seq, id, ME, now(), MlsGroupPostKind.TEXT, text, replyTo, mentions, MlsGroupPostState.Sent(seq), emptyList(), null, null, null
        )
        return when (sends) {
            1 -> {
                extra.getOrPut(groupId) { mutableListOf() }.add(own(total + 1))
                scope.launch {
                    delay(2 * SEC)
                    // A post that repeats a log_seq the timeline already holds must not add a row.
                    bus.emit(MlsGroupSignal.Post(groupId, posts(groupId, total, total).first()))
                }
                ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.Sent(total + 1)))
            }
            2 -> {
                scope.launch {
                    delay(3 * SEC)
                    extra.getOrPut(groupId) { mutableListOf() }.add(own(total + 1))
                    bus.emit(MlsGroupSignal.PostState(groupId, id, MlsGroupPostState.Sent(total + 1), total + 1))
                }
                ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.Queued(MlsGroupQueuedReason.CATCHING_UP)))
            }
            3 -> {
                scope.launch {
                    delay(6 * SEC)
                    bus.emit(MlsGroupSignal.PostState(groupId, id, MlsGroupPostState.Failed("host_rejected"), null))
                }
                ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.Delayed(now() + 90 * SEC)))
            }
            // No reset time: the composer stays, so the walk can go on to the next sends.
            4 -> ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.RateLimited(MlsGroupQuota(20, 0, true, null))))
            5 -> fail("too_large", 65_536)
            6 -> ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.Unknown("parked")))
            else -> {
                extra.getOrPut(groupId) { mutableListOf() }.add(own(total + 1))
                ClientResult.Ready(MlsGroupSend(id, MlsGroupPostState.Sent(total + 1)))
            }
        }
    }

    override suspend fun refresh(account: String, groupId: String): ClientResult<Unit> {
        hit("group.refresh")
        if (unavailable) return off()
        return ClientResult.Ready(Unit)
    }

    override suspend fun markRead(account: String, groupId: String, logSeq: Long): ClientResult<MlsGroupRead> {
        hit("group.mark_read")
        if (unavailable) return off()
        return ClientResult.Ready(MlsGroupRead(logSeq, 0, 0))
    }

    override suspend fun delete(account: String, groupId: String, postId: String): ClientResult<MlsGroupSend> {
        hit("group.message.delete")
        if (unavailable) return off()
        // The forced refusal of F2-10: an own-looking post the fixture says belongs to someone else.
        if (postId == FORCED_FOREIGN) return fail("not_author")
        delay(2 * SEC)
        val slot = postId.removePrefix("p").toLongOrNull()
        scope.launch {
            // The event comes twice: the tombstone appears once.
            delay(SEC / 5)
            bus.emit(MlsGroupSignal.PostDeleted(groupId, postId, slot, (slot ?: 0L) + 1000))
            delay(SEC)
            bus.emit(MlsGroupSignal.PostDeleted(groupId, postId, slot, (slot ?: 0L) + 1000))
        }
        return ClientResult.Ready(MlsGroupSend(postId, MlsGroupPostState.Deleted))
    }

    override suspend fun leave(account: String, groupId: String): ClientResult<Unit> {
        hit("group.leave")
        if (unavailable) return off()
        if (baseRows().firstOrNull { it.groupId == groupId }?.myRole == MlsGroupRole.OWNER) return fail("owner_protected")
        scope.launch {
            delay(SEC + SEC / 2)
            bus.emit(
                MlsGroupSignal.Service(
                    groupId,
                    membership(totalPosts(groupId) + 1, now(), listOf(change(MlsGroupChangeKind.LEFT, "a-me", "Dana", me = true)), null)
                )
            )
            gone.add(groupId)
        }
        return ClientResult.Ready(Unit)
    }

    override suspend fun requests(account: String, groupId: String, cursor: String?, limit: Int) = admin.requests(groupId, cursor)
    override suspend fun approveRequest(account: String, groupId: String, requestId: String) = admin.approve(groupId, requestId)
    override suspend fun declineRequest(account: String, groupId: String, requestId: String) = admin.decline(groupId, requestId)
    override suspend fun setRole(account: String, groupId: String, anchor: String, role: MlsGroupRole) = admin.role(groupId, anchor, role)
    override suspend fun removeMember(account: String, groupId: String, anchor: String) = admin.remove(groupId, anchor)
    override suspend fun banMember(account: String, groupId: String, anchor: String) = admin.ban(groupId, anchor)
    override suspend fun setName(account: String, groupId: String, name: String) = admin.name(groupId, name)
    override suspend fun setApproval(account: String, groupId: String, approval: Boolean) = admin.approval(groupId, approval)
    override suspend fun setPush(account: String, groupId: String, push: MlsGroupPush) = admin.push(groupId, push)
    override suspend fun skipCatchUp(account: String, groupId: String) = admin.skip(groupId)
    override suspend fun voidCommit(account: String, groupId: String, logSeq: Long) = admin.void(groupId, logSeq)

    /** F2-7: the service events, one every two seconds, ending with the removal of this member from "Book club". */
    private fun script() {
        if (scripted) return
        scripted = true
        scope.launch {
            val steps = listOf(
                membership(9, now(), listOf(change(MlsGroupChangeKind.REMOVED, "a-chen", "Chen")), ANA_A),
                membership(10, now(), listOf(change(MlsGroupChangeKind.BANNED, "a-ben", "Ben")), ANA_A),
                membership(11, now(), listOf(change(MlsGroupChangeKind.ROLE_CHANGED, "a-chen", "Chen", role = MlsGroupRole.ADMIN)), ANA_A),
                membership(12, now(), listOf(change(MlsGroupChangeKind.DEVICE_ADDED, "a-ana", "Ana")), null),
                MlsGroupPost(
                    13, "p13", null, now(), MlsGroupPostKind.SETTINGS, null, null, emptyList(),
                    MlsGroupPostState.Sent(
                        13
                    ),
                    emptyList(), null, false, ANA_A
                ),
                membership(14, now(), listOf(change(MlsGroupChangeKind.REMOVED, "a-me", "Dana", me = true)), ANA_A)
            )
            steps.forEach {
                delay(2 * SEC)
                bus.emit(
                    if (it.kind == MlsGroupPostKind.SETTINGS || it.kind == MlsGroupPostKind.MEMBERSHIP) {
                        MlsGroupSignal.Service(
                            G_BOOK,
                            it
                        )
                    } else {
                        MlsGroupSignal.Post(G_BOOK, it)
                    }
                )
            }
        }
    }

    private companion object {
        const val FORCED_FOREIGN = "p252"
        const val LEAF_CAP = 3000L
    }
}
