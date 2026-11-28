// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.ramflux.android.R
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.MlsCatchUpResult
import org.ramflux.data.MlsGroup
import org.ramflux.data.MlsGroupCatchUp
import org.ramflux.data.MlsGroupChange
import org.ramflux.data.MlsGroupChangeKind
import org.ramflux.data.MlsGroupCommit
import org.ramflux.data.MlsGroupHostUnusual
import org.ramflux.data.MlsGroupMember
import org.ramflux.data.MlsGroupPage
import org.ramflux.data.MlsGroupPost
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupQuota
import org.ramflux.data.MlsGroupRejoinReason
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsGroupSignal
import org.ramflux.data.MlsGroupWarning
import org.ramflux.data.MlsJoinRequest
import org.ramflux.data.UnavailableReason

private const val SEC = 1000L
private const val HALF = 500L
private const val DAY = 24 * 60 * 60 * SEC

/** A button the gallery shows for a fixture whose walk needs a trigger (F3-8 warnings, F3-12 lock). */
internal class FixtureControl(val tag: String, @param:StringRes val label: Int, val run: (MlsGroupSession, String) -> Unit)

/**
 * The scripted answers of the M3 methods for one fixture (common section 6, F3-1 to F3-14). Replies and events are scripted
 * with real delays through the fixture event bus; the counters of [hit] are by wire method name. Nothing here runs unless a
 * screen asks: the scripts that need a start start on the first read of what they belong to. F3-14 answers every M3 method with
 * the typed unavailable result; the other fixtures answer as the SDK would at W.
 */
internal class FixtureMlsAdmin(
    private val fixtureId: String,
    private val scope: CoroutineScope,
    private val bus: MutableSharedFlow<MlsGroupSignal>,
    private val hit: (String) -> Unit
) {
    val active = fixtureId.startsWith("F3-")
    private val unavailable = fixtureId == "F3-14"
    private var commitSeq = when (fixtureId) {
        "F3-3" -> 309L
        "F3-10" -> 250L
        else -> 300L
    }
    private var envelopeSeq = 0L
    private val extra = mutableMapOf<String, MutableList<MlsGroupPost>>()
    private val names = mutableMapOf<String, String>()
    private val approvals = mutableMapOf<String, Boolean>()
    private val pushes = mutableMapOf<String, MlsGroupPush>()
    private val roles = mutableMapOf<String, MlsGroupRole>()
    private val rosters = mutableMapOf<String, MutableList<MlsGroupMember>>()
    private val warnings = mutableMapOf<String, MutableList<MlsGroupWarning>>()
    private val catchStates = mutableMapOf<String, Triple<MlsGroupCatchUp, Long, Long>>()
    private val catchJobs = mutableMapOf<String, Job>()
    private val quotaPost = mutableMapOf<String, Long>()
    private val requestRows = mutableMapOf<String, MutableList<MlsJoinRequest>>()
    private var requestsScripted = false
    private var queuePageFailed = false
    private var warningStep = -1
    private var staleInfo = false
    private var floor = 1L

    private fun now() = System.currentTimeMillis()
    private fun <T> off(): ClientResult<T> = ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)
    private fun <T> fail(code: String, detail: Long? = null): ClientResult<T> =
        ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = code, detail = detail))

    private fun nextSeq(): Long {
        commitSeq += 1
        return commitSeq
    }

    private fun later(delayMs: Long, block: suspend () -> Unit) {
        scope.launch {
            delay(delayMs)
            block()
        }
    }

    private suspend fun emit(group: String, post: MlsGroupPost, asPost: Boolean = false) {
        extra.getOrPut(group) { mutableListOf() }.add(post)
        bus.emit(if (asPost) MlsGroupSignal.Post(group, post) else MlsGroupSignal.Service(group, post))
    }

    // ---- what the repository reads from the fixture ------------------------------------------------------------------

    /** The posts the fixture adds at fixed positions, in [from]..[to]. */
    fun extraPosts(group: String, from: Long, to: Long): List<MlsGroupPost> =
        extra[group].orEmpty().filter { (it.logSeq ?: 0L) in from..to }

    fun extraNewest(group: String): Long = extra[group].orEmpty().maxOfOrNull { it.logSeq ?: 0L } ?: 0L

    /** The head commit of F3-10 and its older neighbour, in the groups that have them. */
    fun postOverride(group: String, seq: Long): MlsGroupPost? =
        if (active && (group == G_DESIGN || group == G_STALE)) headRows(seq) else null

    fun totalPosts(group: String): Long? = if (active && group == G_STALE) POSTS_STALE else null

    /** The oldest readable position of [group]: a rejoin moves it. */
    fun floor() = floor

    fun roster(group: String): MutableList<MlsGroupMember>? =
        if (!active) null else rosters.getOrPut(group) { rosterOf(group)?.toMutableList() ?: return null }

    fun rosterPage(group: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>>? =
        roster(group)?.let { ClientResult.Ready(MlsGroupPage(it.toList(), null)) }

    /** The info row after the fixture's own state: roles, names, approval, push, catch-up, warnings, quotas and the privacy delay. */
    fun decorate(row: MlsGroup): MlsGroup {
        if (!active) return row
        val id = row.groupId
        val catch = catchStates[id]
        val roster = roster(id)
        val base = row.copy(
            name = names[id] ?: row.name,
            approval = approvals[id] ?: row.approval,
            myRole = roles[id] ?: row.myRole,
            push = pushes[id] ?: row.push,
            catchUp = catch?.first ?: row.catchUp,
            catchUpDone = catch?.second ?: row.catchUpDone,
            catchUpTotal = catch?.third ?: row.catchUpTotal,
            members = roster?.size?.toLong() ?: row.members,
            warnings = warningsOf(id)
        )
        return when {
            fixtureId == "F3-9" && id == G_DESIGN -> base.copy(
                quotaPost = MlsGroupQuota(500, quotaPost.getOrPut(id) { 60 }, false, null),
                quotaCommit = MlsGroupQuota(20, 2, false, null)
            )
            fixtureId == "F3-9" && id == G_NEIGHBOURS -> base.copy(
                quotaPost = MlsGroupQuota(20, quotaPost.getOrPut(id) { 3 }, true, null),
                privacyDelayArmed = true
            )
            fixtureId == "F3-9" && id == G_LARGE -> base.copy(
                quotaPost = MlsGroupQuota(500, quotaPost.getOrPut(id) { 400 }, false, null),
                privacyDelayArmed = true,
                privacySendAfter = sendAfter
            )
            else -> base
        }
    }

    private val sendAfter = now() + 120 * SEC

    private fun warningsOf(group: String): List<MlsGroupWarning> {
        val own = warnings[group].orEmpty()
        val open = if (fixtureId == "F3-13" && group == G_OPEN && MlsGroupWarning.AdmissionNeedsOnline !in own) {
            listOf(MlsGroupWarning.AdmissionNeedsOnline)
        } else {
            emptyList()
        }
        return own + open
    }

    /** Each own send reply makes the next info read return one message less (F3-9). */
    fun onSend(group: String) {
        quotaPost[group]?.let { quotaPost[group] = (it - 1).coerceAtLeast(0) }
    }

    /** The reply of a delayed info read, when the fixture holds one back (F3-8, the stale read). */
    suspend fun infoDelay(): Boolean {
        if (!staleInfo) return false
        staleInfo = false
        delay(3 * SEC)
        return true
    }

    fun staleWarnings(): List<MlsGroupWarning> = listOf(MlsGroupWarning.ForkAlarm(2))

    /** The first info read of a group starts the catch-up script of F3-7 (once per group). */
    fun onInfo(group: String) {
        if (!active || fixtureId != "F3-7" || group !in setOf(G_BOOK, G_LATE, G_CATCH0) || catchJobs.containsKey(group)) return
        val total = if (group == G_CATCH0) 0L else 120L
        catchStates[group] = Triple(MlsGroupCatchUp.RUNNING, 0L, total)
        catchJobs[group] = scope.launch {
            for (done in 0..120L) {
                envelopeSeq += 1
                catchStates[group] = Triple(MlsGroupCatchUp.RUNNING, done, total)
                bus.emit(MlsGroupSignal.CatchUp(group, envelopeSeq, MlsGroupCatchUp.RUNNING, done, total))
                delay(HALF / 5)
            }
        }
    }

    // ---- join requests -----------------------------------------------------------------------------------------------

    private fun rowsOf(group: String): MutableList<MlsJoinRequest> = requestRows.getOrPut(group) {
        when (group) {
            G_QUEUE -> queueRequests().toMutableList()
            // Dana's request expires 30 s after the list is first read.
            G_DESIGN -> f31Requests().map { if (it.requestId == "rq-dana") it.copy(expiresAt = now() + 30 * SEC) else it }.toMutableList()
            else -> mutableListOf()
        }
    }

    suspend fun requests(group: String, cursor: String?): ClientResult<MlsGroupPage<MlsJoinRequest>> {
        hit("group.requests.list")
        if (unavailable) return off()
        val rows = rowsOf(group)
        if (group == G_QUEUE) {
            return if (cursor == null) {
                ClientResult.Ready(MlsGroupPage(rows.take(200), "r2"))
            } else if (!queuePageFailed) {
                queuePageFailed = true
                fail("host_unreachable")
            } else {
                ClientResult.Ready(MlsGroupPage(rows.drop(200), null))
            }
        }
        if (group == G_DESIGN && !requestsScripted) {
            requestsScripted = true
            // Fay arrives 4 s after the first read, and the same event arrives again a second later.
            later(4 * SEC) { bus.emit(MlsGroupSignal.PendingJoin(G_DESIGN, fayRequest())) }
            later(5 * SEC) { bus.emit(MlsGroupSignal.PendingJoin(G_DESIGN, fayRequest())) }
        }
        return ClientResult.Ready(MlsGroupPage(rows.toList(), null))
    }

    suspend fun approve(group: String, requestId: String): ClientResult<MlsGroupCommit> {
        hit("group.requests.approve")
        if (unavailable) return off()
        val rows = rowsOf(group)
        val row = rows.firstOrNull { it.requestId == requestId }
        when (requestId) {
            "rq-ben" -> {
                rows.removeAll { it.requestId == requestId }
                return fail("request_not_found")
            }
            "rq-dana" -> return fail("paced", now() + 5 * SEC)
        }
        if (row == null && group == G_DESIGN) return fail("request_not_found")
        // Eve's reply takes 3 s; in F3-12 every approve does, so the lock lands while it runs.
        if (requestId == "rq-eve" || fixtureId == "F3-12") delay(3 * SEC)
        rows.removeAll { it.requestId == requestId }
        val seq = nextSeq()
        val change = commitChange(MlsGroupChangeKind.JOINED, row?.anchor ?: "a-new", row?.display)
        later(SEC) { emit(group, adminMembership(seq, now(), change)) }
        return ClientResult.Ready(MlsGroupCommit(seq))
    }

    suspend fun decline(group: String, requestId: String): ClientResult<Unit> {
        hit("group.requests.decline")
        if (unavailable) return off()
        delay(SEC)
        val rows = rowsOf(group)
        if (rows.none { it.requestId == requestId }) return fail("request_not_found")
        rows.removeAll { it.requestId == requestId }
        return ClientResult.Ready(Unit)
    }

    // ---- member actions ----------------------------------------------------------------------------------------------

    /** A commit whose event follows the reply by a second, or comes [HALF] before it when [eventFirst]. */
    private suspend fun commit(
        group: String,
        post: (Long) -> MlsGroupPost,
        replyDelay: Long = 0L,
        eventFirst: Boolean = false
    ): ClientResult<MlsGroupCommit> {
        val seq = nextSeq()
        if (eventFirst) {
            emit(group, post(seq))
            delay(HALF)
        } else {
            if (replyDelay > 0) delay(replyDelay)
            later(SEC) { emit(group, post(seq)) }
        }
        return ClientResult.Ready(MlsGroupCommit(seq))
    }

    private fun applyChange(group: String, change: MlsGroupChange) {
        val list = rosters[group] ?: return
        when (change.change) {
            MlsGroupChangeKind.ROLE_CHANGED -> list.replaceAll {
                if (it.anchor == change.anchor) {
                    it.copy(
                        role = change.role ?: it.role
                    )
                } else {
                    it
                }
            }
            MlsGroupChangeKind.REMOVED, MlsGroupChangeKind.BANNED -> list.removeAll { it.anchor == change.anchor }
            else -> Unit
        }
    }

    suspend fun role(group: String, anchor: String, role: MlsGroupRole): ClientResult<MlsGroupCommit> {
        hit("group.role.set")
        if (unavailable) return off()
        val target = roster(group)?.firstOrNull { it.anchor == anchor }
        val change = commitChange(MlsGroupChangeKind.ROLE_CHANGED, anchor, target?.display, role)
        return commit(
            group,
            { seq -> adminMembership(seq, now(), change).also { applyChange(group, change) } },
            eventFirst = anchor == "a-ben"
        )
    }

    suspend fun remove(group: String, anchor: String): ClientResult<MlsGroupCommit> = leaveAction(
        group,
        anchor,
        MlsGroupChangeKind.REMOVED,
        "group.member.remove"
    )

    suspend fun ban(group: String, anchor: String): ClientResult<MlsGroupCommit> = leaveAction(
        group,
        anchor,
        MlsGroupChangeKind.BANNED,
        "group.member.ban"
    )

    private suspend fun leaveAction(group: String, anchor: String, kind: MlsGroupChangeKind, method: String): ClientResult<MlsGroupCommit> {
        hit(method)
        if (unavailable) return off()
        // F3-4: the role changed elsewhere. The refusal is typed, and the info read after it says "member".
        if (fixtureId == "F3-4" && group == G_BOOK) {
            roles[group] = MlsGroupRole.MEMBER
            return fail("not_admin")
        }
        val target = roster(group)?.firstOrNull { it.anchor == anchor }
        if (target?.role == MlsGroupRole.OWNER) return fail("owner_protected")
        if (fixtureId == "F3-11") {
            // This member is removed one second in; the reply comes after three.
            later(
                SEC
            ) { emit(group, adminMembership(nextSeq(), now(), commitChange(MlsGroupChangeKind.REMOVED, "a-me", "Sam", me = true))) }
            delay(3 * SEC)
            return ClientResult.Ready(MlsGroupCommit(nextSeq()))
        }
        val change = commitChange(kind, anchor, target?.display)
        return commit(group, { seq ->
            adminMembership(seq, now(), change).also {
                applyChange(group, change)
            }
        }, replyDelay = if (fixtureId == "F3-5") 3 * SEC else 0L)
    }

    // ---- settings, push, catch-up, void ------------------------------------------------------------------------------

    suspend fun name(group: String, name: String): ClientResult<MlsGroupCommit> {
        hit("group.settings.set")
        if (unavailable) return off()
        return commit(group, { seq -> settingsPost(seq, now(), name, null).also { names[group] = name } })
    }

    suspend fun approval(group: String, approval: Boolean): ClientResult<MlsGroupCommit> {
        hit("group.settings.set")
        if (unavailable) return off()
        val result = commit(group, { seq -> settingsPost(seq, now(), null, approval).also { approvals[group] = approval } })
        // Turning approval off makes the group need an online member; the SDK clears the hint when it is turned on again (Q13).
        later(SEC + HALF) {
            val hint = MlsGroupWarning.AdmissionNeedsOnline
            if (approval) {
                warnings[group]?.remove(
                    hint
                )
            } else {
                warnings.getOrPut(group) { mutableListOf() }.let { if (hint !in it) it.add(hint) }
            }
            bus.emit(MlsGroupSignal.Warning(group, hint, cleared = approval))
        }
        return result
    }

    suspend fun push(group: String, push: MlsGroupPush): ClientResult<MlsGroupPush> {
        hit("group.push.set")
        if (unavailable) return off()
        delay(SEC)
        // F3-6: "None" fails with a generic error; the picker keeps the old value.
        if (push == MlsGroupPush.NONE) return fail("generic")
        pushes[group] = push
        return ClientResult.Ready(push)
    }

    suspend fun skip(group: String): ClientResult<MlsCatchUpResult> {
        hit("group.catch_up")
        if (unavailable) return off()
        catchJobs.remove(group)?.cancel()
        val late = group == G_LATE
        if (late) {
            // The idle event arrives one second in; the reply comes after three and must change nothing.
            later(SEC) { sendCatchUp(group, MlsGroupCatchUp.IDLE) }
        }
        if (late || fixtureId == "F3-12") delay(3 * SEC)
        if (!late) {
            catchStates[group] = Triple(MlsGroupCatchUp.REJOINING, 0L, 0L)
            later(5 * SEC) {
                floor = 4L
                sendCatchUp(group, MlsGroupCatchUp.IDLE)
            }
        }
        return ClientResult.Ready(MlsCatchUpResult.Rejoining)
    }

    private suspend fun sendCatchUp(group: String, state: MlsGroupCatchUp) {
        envelopeSeq += 1
        catchStates[group] = Triple(state, 0L, 0L)
        bus.emit(MlsGroupSignal.CatchUp(group, envelopeSeq, state, 0, 0))
    }

    suspend fun void(group: String, logSeq: Long): ClientResult<MlsGroupCommit> {
        hit("group.void")
        if (unavailable) return off()
        if (group == G_STALE) {
            // The head moved: a membership row lands at 252 and the SDK says so.
            emit(group, adminMembership(STALE_HEAD, now(), commitChange(MlsGroupChangeKind.JOINED, "a-late-2", "Latest joiner")))
            return fail("void_not_head", STALE_HEAD)
        }
        val seq = nextSeq()
        // The void row arrives as `post{kind: void}`, the main shape of 2.6, so walks exercise that path.
        later(SEC) { emit(group, voidPost(seq, logSeq, now()), asPost = true) }
        return ClientResult.Ready(MlsGroupCommit(seq))
    }

    // ---- gallery controls --------------------------------------------------------------------------------------------

    private val steps: List<MlsGroupWarning> = listOf(
        MlsGroupWarning.ForkAlarm(2),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.PRUNED),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.GATE_REJECTED),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.APPLY_FAILED),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.WELCOME_EXPIRED),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.STATE_LOST),
        MlsGroupWarning.RejoinNeeded(MlsGroupRejoinReason.UNKNOWN),
        MlsGroupWarning.Undecryptable(12, 140),
        MlsGroupWarning.AdmissionNeedsOnline,
        MlsGroupWarning.HostUnusual(MlsGroupHostUnusual.LATE_ROLL),
        MlsGroupWarning.HeadCommitRejected(250),
        MlsGroupWarning.Unknown("weird")
    )

    private fun setWarning(group: String, warning: MlsGroupWarning, cleared: Boolean) {
        val list = warnings.getOrPut(group) { mutableListOf() }
        list.removeAll { it.key == warning.key }
        if (!cleared) list.add(warning)
        scope.launch { bus.emit(MlsGroupSignal.Warning(group, warning, cleared)) }
    }

    /** F3-8: the controls step through the warnings; each is cleared by the next "Clear". */
    val controls: List<FixtureControl> = when (fixtureId) {
        "F3-8" -> listOf(
            FixtureControl("mls-f38-next", R.string.mls_admin_next_warning) { _, group ->
                warningStep = (warningStep + 1) % steps.size
                setWarning(group, steps[warningStep], cleared = false)
            },
            FixtureControl("mls-f38-clear", R.string.mls_admin_clear_warning) { _, group ->
                if (warningStep >= 0) setWarning(group, steps[warningStep], cleared = true)
            },
            FixtureControl("mls-f38-rejected", R.string.mls_admin_head_rejected) { _, group ->
                setWarning(group, MlsGroupWarning.HeadCommitRejected(250), cleared = false)
            },
            FixtureControl("mls-f38-stale", R.string.mls_admin_stale_read) { session, group ->
                // The fork alarm is set, an info read goes out, the alarm is cleared a second later, and the read answers after
                // three seconds with the alarm still listed: it must not come back.
                setWarning(group, MlsGroupWarning.ForkAlarm(2), cleared = false)
                staleInfo = true
                session.loadInfo(GALLERY_ACCOUNT, group)
                later(SEC) { setWarning(group, MlsGroupWarning.ForkAlarm(2), cleared = true) }
            }
        )
        "F3-12" -> listOf(
            FixtureControl(
                "mls-f312-lock",
                R.string.mls_admin_lock_now
            ) { session, _ -> scope.launch { session.clearAccount(GALLERY_ACCOUNT) } },
            FixtureControl("mls-f312-unlock", R.string.mls_admin_unlock) { session, _ -> session.admitInOrder(GALLERY_ACCOUNT) }
        )
        else -> emptyList()
    }

    private companion object {
        const val POSTS_STALE = 251L
        const val STALE_HEAD = 252L
    }
}
