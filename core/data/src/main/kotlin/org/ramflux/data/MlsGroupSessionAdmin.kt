// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * The admin owner of the MLS group session (M3 contract 3.1 to 3.7): join requests, member actions, settings, the push mode,
 * the catch-up skip and the void. Every function is one call per explicit, confirmed tap; none is made by a timer, an event, a
 * warning, a reconnect, an unlock, a foreground or an outcome. Results carry the account epoch and the action id and are
 * dropped when either moved. State lives in `MlsGroupData.admin`, so `clearAccount` drops it with the account.
 */

fun MlsGroupSession.admin(account: String, group: String): Flow<MlsAdminState> = view(account) { it.groups[group]?.admin ?: MlsAdminState() }

/**
 * The lock epoch of [account]: it moves when the account is cleared (the app lock, an account lock, a switch away from it,
 * sign-out or removal) and when a cleared account is admitted again. A screen that sees it move closes every sheet, dialog and
 * confirmation it holds and drops their state (nothing may survive a lock, and a screen opened while purged does not stay empty).
 */
fun MlsGroupSession.epoch(account: String): Flow<Long> {
    seen.add(account)
    return data.map { it.epochs[account] ?: 0L }.distinctUntilChanged()
}

fun MlsGroupSession.requests(account: String, group: String): Flow<MlsRequestsState> =
    view(account) { it.groups[group]?.admin?.requests ?: MlsRequestsState() }

// ---- join requests -----------------------------------------------------------------------------------------------------

/**
 * The first page of requests (200 rows), when the info of an owner or admin of an approval group opens or the requests screen
 * opens or "Try again" is tapped. The rows stay until the page arrives; a page of an older generation is dropped (R2-M1).
 */
fun MlsGroupSession.loadRequests(account: String, group: String) = launchFor(account) { epoch ->
    val gen = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        if (!MlsAdminRules.requestsEligible(g.info.group, g.timeline.readOnly) || g.admin.requests.loading) {
            a to null
        } else {
            val next = g.admin.requests.pageGen + 1
            a.withGroup(group) {
                it.copy(admin = it.admin.copy(requests = it.admin.requests.copy(pageGen = next, loading = true, loadingMore = false, loadFailure = null, pageFailure = null)))
            } to next
        }
    } ?: return@launchFor
    val result = repository.requests(account, group, null, MlsAdminRules.REQUEST_LIMIT)
    update(account, epoch) { a ->
        a.withGroup(group) { g ->
            // A reset or a newer first page since this one was issued: its rows would resurrect what the newer state removed.
            if (g.admin.requests.pageGen != gen) return@withGroup g
            when (result) {
                is ClientResult.Ready -> {
                    val known = MlsAiFlags.learnRequests(g.aiAnchors, result.value.items)
                    val fresh = result.value.items.map { MlsRequestRow(MlsAiFlags.apply(known, it), gen = gen) }
                    val ids = fresh.map { it.request.requestId }.toSet()
                    // Rows that are in flight, and rows an event inserted after this read was issued, survive the replacement.
                    val kept = g.admin.requests.rows.filter {
                        it.request.requestId !in ids && (it.phase != MlsRequestPhase.IDLE || (it.fromEvent && it.gen == gen))
                    }
                    val inFlight = g.admin.requests.rows.filter { it.request.requestId in ids && it.phase != MlsRequestPhase.IDLE }.associateBy { it.request.requestId }
                    val rows = (fresh.map { inFlight[it.request.requestId]?.copy(request = it.request) ?: it } + kept)
                    g.copy(
                        aiAnchors = known,
                        admin = g.admin.copy(requests = g.admin.requests.copy(rows = ordered(rows), cursor = result.value.cursor, loading = false, loaded = true)),
                    )
                }
                else -> g.copy(admin = g.admin.copy(requests = g.admin.requests.copy(loading = false, loaded = true, loadFailure = failure(result))))
            }
        }
    }
    armAdminTimer(account, epoch, group)
}

/** The next page, or the retry of a failed one (an explicit tap or the list reaching its last rows). One page at a time. */
fun MlsGroupSession.loadMoreRequests(account: String, group: String) = launchFor(account) { epoch ->
    val start = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        val r = g.admin.requests
        if (!MlsAdminRules.requestsEligible(g.info.group, g.timeline.readOnly) || r.cursor == null || r.loading || r.loadingMore) {
            a to null
        } else {
            a.withGroup(group) { it.copy(admin = it.admin.copy(requests = r.copy(loadingMore = true, pageFailure = null))) } to (r.cursor to r.pageGen)
        }
    } ?: return@launchFor
    val result = repository.requests(account, group, start.first, MlsAdminRules.REQUEST_LIMIT)
    update(account, epoch) { a ->
        a.withGroup(group) { g ->
            if (g.admin.requests.pageGen != start.second) return@withGroup g
            when (result) {
                is ClientResult.Ready -> {
                    val known = MlsAiFlags.learnRequests(g.aiAnchors, result.value.items)
                    val have = g.admin.requests.rows.map { it.request.requestId }.toSet()
                    val added = result.value.items.filter { it.requestId !in have }.map { MlsRequestRow(MlsAiFlags.apply(known, it), gen = start.second) }
                    g.copy(
                        aiAnchors = known,
                        admin = g.admin.copy(requests = g.admin.requests.copy(rows = ordered(g.admin.requests.rows + added), cursor = result.value.cursor, loadingMore = false)),
                    )
                }
                else -> g.copy(admin = g.admin.copy(requests = g.admin.requests.copy(loadingMore = false, pageFailure = failure(result))))
            }
        }
    }
}

private fun ordered(rows: List<MlsRequestRow>): List<MlsRequestRow> {
    val seen = HashSet<String>()
    return rows.filter { seen.add(it.request.requestId) }.sortedBy { it.request.requestedAt ?: Long.MAX_VALUE }.take(MlsAdminRules.REQUESTS_MAX)
}

/** One tap on the confirmed "Approve". An admin commit: the row is "Adding" until the `membership` event. */
fun MlsGroupSession.approveRequest(account: String, group: String, requestId: String) = launchFor(account) { epoch ->
    runCommit(
        account, epoch, group, MlsAdminKind.APPROVE, requestId, null,
        allowed = { g ->
            val row = g.admin.requests.rows.firstOrNull { it.request.requestId == requestId }
            MlsAdminRules.requestsEligible(g.info.group, g.timeline.readOnly) && row != null && row.phase == MlsRequestPhase.IDLE &&
                (row.request.expiresAt?.let { it > clock() } ?: true)
        },
        mark = { g -> g.withRow(requestId) { it.copy(phase = MlsRequestPhase.APPROVING, failure = null) } },
    ) { repository.approveRequest(account, group, requestId) }
}

/** One tap on the confirmed "Decline". Nothing is committed: its own in-flight mark is the row. */
fun MlsGroupSession.declineRequest(account: String, group: String, requestId: String) = launchFor(account) { epoch ->
    val go = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        val row = g.admin.requests.rows.firstOrNull { it.request.requestId == requestId }
        if (!MlsAdminRules.requestsEligible(g.info.group, g.timeline.readOnly) || row == null || row.phase != MlsRequestPhase.IDLE) {
            a to false
        } else {
            a.withGroup(group) { it.withRow(requestId) { r -> r.copy(phase = MlsRequestPhase.DECLINING, failure = null) } } to true
        }
    } ?: return@launchFor
    if (!go) return@launchFor
    val result = repository.declineRequest(account, group, requestId)
    withContext(NonCancellable) {
        update(account, epoch) { a ->
            a.withGroup(group) { g ->
                val row = g.admin.requests.rows.firstOrNull { it.request.requestId == requestId } ?: return@withGroup g
                if (g.info.group == null || g.timeline.readOnly != null) return@withGroup g.withRow(requestId) { it.copy(phase = MlsRequestPhase.IDLE) }
                when {
                    result is ClientResult.Ready -> g.withoutRow(requestId)
                    result.codeOf() == "request_not_found" -> g.withoutRow(requestId).withGone()
                    else -> g.withRow(requestId) { it.copy(phase = MlsRequestPhase.IDLE, failure = failure(result)) }
                }
            }
        }
    }
    if (result.codeOf() == "not_admin" || result.codeOf() == "not_owner") scheduleInfo(account, epoch, group)
}

// ---- member actions, settings, void ------------------------------------------------------------------------------------

/** One tap on the confirmed role change; the owner only, to ADMIN or MEMBER. */
fun MlsGroupSession.setRole(account: String, group: String, anchor: String, role: MlsGroupRole) = launchFor(account) { epoch ->
    if (role != MlsGroupRole.ADMIN && role != MlsGroupRole.MEMBER) return@launchFor
    runCommit(
        account, epoch, group, MlsAdminKind.ROLE, anchor, role.name,
        allowed = { g -> g.info.group?.myRole == MlsGroupRole.OWNER && targetOk(g, anchor) },
    ) { repository.setRole(account, group, anchor, role) }
}

/** One tap on the confirmed "Remove from group". */
fun MlsGroupSession.removeMember(account: String, group: String, anchor: String) = launchFor(account) { epoch ->
    runCommit(account, epoch, group, MlsAdminKind.REMOVE, anchor, null, allowed = { g -> memberActionOk(g, anchor) }) {
        repository.removeMember(account, group, anchor)
    }
}

/** One tap on the confirmed "Ban from group". */
fun MlsGroupSession.banMember(account: String, group: String, anchor: String) = launchFor(account) { epoch ->
    runCommit(account, epoch, group, MlsAdminKind.BAN, anchor, null, allowed = { g -> memberActionOk(g, anchor) }) {
        repository.banMember(account, group, anchor)
    }
}

/** The name form's Save: trimmed, 1 to 64 grapheme clusters, changed. The name field is its own confirmation. */
fun MlsGroupSession.setName(account: String, group: String, name: String) = launchFor(account) { epoch ->
    val trimmed = name.trim()
    if (trimmed.isEmpty() || MlsGroupSession.graphemes(trimmed) > MlsGroupSession.NAME_MAX) return@launchFor
    runCommit(
        account, epoch, group, MlsAdminKind.SETTINGS, "name", trimmed,
        allowed = { g -> g.info.group?.let { MlsAdminRules.isAdmin(it.myRole) && it.name != trimmed } == true },
    ) { repository.setName(account, group, trimmed) }
}

/** The approval switch: off after its confirmation, on at once. A switch to the value info already shows makes no call. */
fun MlsGroupSession.setApproval(account: String, group: String, approval: Boolean) = launchFor(account) { epoch ->
    runCommit(
        account, epoch, group, MlsAdminKind.SETTINGS, "approval", approval.toString(),
        allowed = { g -> g.info.group?.let { MlsAdminRules.isAdmin(it.myRole) && it.approval != approval } == true },
    ) { repository.setApproval(account, group, approval) }
}

/** One tap on the confirmed "Undo this change" for the commit at [logSeq] that was the head when the tap happened. */
fun MlsGroupSession.voidCommit(account: String, group: String, logSeq: Long) = launchFor(account) { epoch ->
    runCommit(
        account, epoch, group, MlsAdminKind.VOID, logSeq.toString(), null,
        // The head-commit rule picks the row that offers "Undo this change" (the caller applies it when the tap happens); the SDK is
        // the authority on whether the commit is still the head, and answers `void_not_head{head_log_seq}` when it moved (2.6).
        allowed = { g -> g.info.group?.let { MlsAdminRules.isAdmin(it.myRole) } == true },
    ) { repository.voidCommit(account, group, logSeq) }
}

private fun targetOk(g: MlsGroupData, anchor: String): Boolean {
    val row = g.members.rows.firstOrNull { it.anchor == anchor } ?: return false
    return !row.isMe && row.role != MlsGroupRole.OWNER
}

/** The owner acts on any member row; an admin on plain member rows only (3.3). */
private fun memberActionOk(g: MlsGroupData, anchor: String): Boolean {
    val role = g.info.group?.myRole ?: return false
    val row = g.members.rows.firstOrNull { it.anchor == anchor } ?: return false
    return when (role) {
        MlsGroupRole.OWNER -> targetOk(g, anchor)
        MlsGroupRole.ADMIN -> targetOk(g, anchor) && row.role == MlsGroupRole.MEMBER
        else -> false
    }
}

/**
 * The one commit-producing action of the group: refused while the slot is busy, while `paced` holds, or when [allowed] says no.
 * The reply moves the action to [MlsAdminPending]; the matching event resolves it.
 */
private suspend fun MlsGroupSession.runCommit(
    account: String,
    epoch: Long,
    group: String,
    kind: MlsAdminKind,
    target: String,
    expect: String?,
    allowed: (MlsGroupData) -> Boolean,
    mark: (MlsGroupData) -> MlsGroupData = { it },
    call: suspend () -> ClientResult<MlsGroupCommit>,
) {
    val started = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        val now = clock()
        val held = g.admin.pacedUntil?.let { it > now } == true
        if (g.info.group == null || g.timeline.readOnly != null || g.admin.busy || held || !allowed(g)) {
            a to null
        } else {
            val action = MlsAdminAction(newId(), kind, target, epoch, now)
            a.withGroup(group) {
                val marked = mark(it)
                marked.copy(admin = marked.admin.copy(slot = action, lastFailure = null, expect = expect))
            } to action
        }
    } ?: return
    val result = call()
    withContext(NonCancellable) {
        update(account, epoch) { a -> a.withGroup(group) { finishCommit(it, started, result) } }
    }
    // After each own admin reply the info is read again, coalesced (2.8). A refusal that says the role moved reads it too.
    when (val code = result.codeOf()) {
        null -> scheduleInfo(account, epoch, group)
        "not_admin", "not_owner" -> scheduleInfo(account, epoch, group)
        "void_not_head" -> {
            scheduleInfo(account, epoch, group)
            fillGap(account, group, force = true)
        }
        else -> if (code == "paced" || code == "rate_limited") scheduleInfo(account, epoch, group)
    }
    armAdminTimer(account, epoch, group)
}

private fun MlsGroupSession.finishCommit(g: MlsGroupData, action: MlsAdminAction, result: ClientResult<MlsGroupCommit>): MlsGroupData {
    val mine = g.admin.slot?.actionId == action.actionId
    // The group is gone, read-only, or the slot was cleared (a removal): only this call's own busy mark clears.
    if (g.info.group == null || g.timeline.readOnly != null || !mine) {
        return if (action.kind == MlsAdminKind.APPROVE) g.withRow(action.target) { if (it.phase == MlsRequestPhase.APPROVING) it.copy(phase = MlsRequestPhase.IDLE) else it } else g
    }
    return when (result) {
        is ClientResult.Ready -> {
            val logSeq = result.value.logSeq
            val early = logSeq in g.admin.applied
            val withPending = g.copy(
                admin = g.admin.copy(
                    slot = null,
                    pending = if (early) null else MlsAdminPending(action, logSeq),
                    applied = MlsAdminRules.withApplied(g.admin.applied, logSeq),
                ),
            )
            when {
                action.kind != MlsAdminKind.APPROVE -> withPending
                early -> withPending.withoutRow(action.target)
                else -> withPending.withRow(action.target) { it.copy(phase = MlsRequestPhase.ADDING) }
            }
        }
        else -> {
            val f = failure(result)
            val until = when {
                f.code == "paced" -> f.detail
                f.code == "rate_limited" && f.retryAfterSecs != null -> clock() + f.retryAfterSecs * MILLIS
                else -> null
            }
            var next = g.copy(admin = g.admin.copy(slot = null, lastFailure = MlsAdminFailure(action.kind, action.target, f), pacedUntil = until ?: g.admin.pacedUntil))
            if (action.kind == MlsAdminKind.APPROVE) {
                next = if (f.code == "request_not_found") next.withoutRow(action.target).withGone() else next.withRow(action.target) { it.copy(phase = MlsRequestPhase.IDLE, failure = f) }
            }
            next
        }
    }
}

/** Forgets the last admin failure once its screen showed it. */
fun MlsGroupSession.clearAdminFailure(account: String, group: String) = launchFor(account) { epoch ->
    update(account, epoch) { a -> a.withGroup(group) { it.copy(admin = it.admin.copy(lastFailure = null)) } }
}

// ---- push, catch-up, banners -------------------------------------------------------------------------------------------

/** The push picker: this device only, one call at a time, shown only on the reply (3.4). */
fun MlsGroupSession.setPush(account: String, group: String, push: MlsGroupPush) = launchFor(account) { epoch ->
    if (push == MlsGroupPush.UNKNOWN) return@launchFor
    val go = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        if (g.info.group == null || g.timeline.readOnly != null || g.admin.pushSaving != null) a to false
        else a.withGroup(group) { it.copy(admin = it.admin.copy(pushSaving = push, pushFailure = null)) } to true
    } ?: return@launchFor
    if (!go) return@launchFor
    val result = repository.setPush(account, group, push)
    withContext(NonCancellable) {
        update(account, epoch) { a ->
            a.withGroup(group) { g ->
                val cleared = g.admin.copy(pushSaving = null)
                if (g.info.group == null || g.timeline.readOnly != null) return@withGroup g.copy(admin = cleared)
                when (result) {
                    is ClientResult.Ready -> g.copy(
                        admin = cleared,
                        info = g.info.copy(group = g.info.group.copy(push = result.value)),
                    )
                    else -> g.copy(admin = cleared.copy(pushFailure = failure(result)))
                }
            }
        }
    }
}

/** The skip dialog's confirm: the only path to `skip: true` (A3). Applies only if nothing newer was recorded meanwhile. */
fun MlsGroupSession.skipCatchUp(account: String, group: String) = launchFor(account) { epoch ->
    val from = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        val record = g.admin.catchUp
        if (g.info.group == null || g.timeline.readOnly != null || record.state != MlsGroupCatchUp.RUNNING || record.skipping) {
            a to null
        } else {
            a.withGroup(group) { it.copy(admin = it.admin.copy(catchUp = record.copy(skipping = true, skipFromVersion = record.version, skipFailure = null))) } to record.version
        }
    } ?: return@launchFor
    val result = repository.skipCatchUp(account, group)
    withContext(NonCancellable) {
        update(account, epoch) { a ->
            a.withGroup(group) { g ->
                val record = g.admin.catchUp
                val cleared = record.copy(skipping = false)
                // A newer catch_up event or info state was applied while the call ran: the late result changes nothing (3.5).
                if (g.info.group == null || g.timeline.readOnly != null || record.version != from) return@withGroup g.copy(admin = g.admin.copy(catchUp = cleared))
                val next = when (result) {
                    is ClientResult.Ready -> when (val r = result.value) {
                        MlsCatchUpResult.Rejoining -> cleared.copy(state = MlsGroupCatchUp.REJOINING, done = 0, total = 0, version = record.version + 1)
                        is MlsCatchUpResult.Running -> cleared.copy(state = MlsGroupCatchUp.RUNNING, done = r.done, total = r.total, version = record.version + 1)
                    }
                    else -> cleared.copy(skipFailure = failure(result))
                }
                g.copy(admin = g.admin.copy(catchUp = next))
            }
        }
    }
}

/** Hides a warning banner for this session (memory only, cleared on lock). Critical codes cannot be hidden (3.7). */
fun MlsGroupSession.hideWarning(account: String, group: String, key: String) = launchFor(account) { epoch ->
    if (key !in HIDEABLE) return@launchFor
    update(account, epoch) { a -> a.withGroup(group) { it.copy(admin = it.admin.copy(hidden = it.admin.hidden + key)) } }
}

private val HIDEABLE = setOf("undecryptable", "host_unusual", "admission_needs_online")
private const val MILLIS = 1000L

// ---- hooks used by the session -----------------------------------------------------------------------------------------

private fun ClientResult<*>.codeOf(): String? = when (this) {
    is ClientResult.Ready -> null
    is ClientResult.Failed -> problem.statusName ?: problem.check
    is ClientResult.Unavailable -> "unavailable"
}

private fun MlsGroupData.withRow(requestId: String, change: (MlsRequestRow) -> MlsRequestRow): MlsGroupData =
    copy(admin = admin.copy(requests = admin.requests.copy(rows = admin.requests.rows.map { if (it.request.requestId == requestId) change(it) else it })))

private fun MlsGroupData.withoutRow(requestId: String): MlsGroupData =
    copy(admin = admin.copy(requests = admin.requests.copy(rows = admin.requests.rows.filterNot { it.request.requestId == requestId })))

private fun MlsGroupData.withGone(): MlsGroupData =
    copy(admin = admin.copy(requests = admin.requests.copy(goneTicks = admin.requests.goneTicks + 1)))

private fun MlsRequestsState.isOpen() = rows.isNotEmpty() || loading || loadingMore || loaded || cursor != null

/** The requests pager closes: in-flight pages of the old generation are dropped. */
private fun MlsRequestsState.closed() = MlsRequestsState(pageGen = pageGen + 1, goneTicks = goneTicks)

/**
 * An info read arrived: the warnings merge by the 2.7 rule, the catch-up state follows it unless an event came since, the
 * requests close when the group stopped being an approval group of this admin, and a pending settings action the read shows is
 * resolved. The second value is true when a rejoin just ended (the newest page is read again).
 */
internal fun adminReadInfo(g: MlsGroupData, info: MlsGroup, issuedCounter: Long, issuedVersion: Long): Pair<MlsGroupData, Boolean> {
    var admin = g.admin
    admin = admin.copy(warnings = MlsAdminRules.mergeWarnings(admin.warnings, info.warnings, issuedCounter))
    var rejoined = false
    val record = admin.catchUp
    if (record.version == issuedVersion && (record.state != info.catchUp || record.done != info.catchUpDone || record.total != info.catchUpTotal)) {
        rejoined = record.state == MlsGroupCatchUp.REJOINING && info.catchUp == MlsGroupCatchUp.IDLE
        admin = admin.copy(catchUp = record.copy(state = info.catchUp, done = info.catchUpDone, total = info.catchUpTotal, version = record.version + 1))
    }
    if (!MlsAdminRules.requestsEligible(info, g.timeline.readOnly) && admin.requests.isOpen()) {
        admin = admin.copy(requests = admin.requests.closed())
    }
    val pending = admin.pending
    if (pending != null && pending.action.kind == MlsAdminKind.SETTINGS) {
        val shown = if (pending.action.target == "name") info.name == admin.expect else info.approval.toString() == admin.expect
        if (shown) admin = admin.copy(pending = null)
    }
    return g.copy(admin = admin) to rejoined
}

/** A complete member read that shows the result of a pending role, remove or ban resolves it (3.1 rule 3). */
internal fun adminReadMembers(g: MlsGroupData): MlsGroupData {
    val pending = g.admin.pending ?: return g
    if (g.members.cursor != null) return g
    val row = g.members.rows.firstOrNull { it.anchor == pending.action.target }
    val shown = when (pending.action.kind) {
        MlsAdminKind.ROLE -> row?.role?.name == g.admin.expect
        MlsAdminKind.REMOVE, MlsAdminKind.BAN -> row == null
        else -> false
    }
    return if (shown) g.copy(admin = g.admin.copy(pending = null)) else g
}

/**
 * A `membership`, `settings` or `void` row arrived. The commit position is recorded, a pending action at it is resolved, the
 * rows of people who joined or were removed leave the requests, the requests close when approval turns off, a void marks its
 * target, and an own removal, ban or leave clears the slot (3.1 rule 7).
 */
internal fun adminService(g: MlsGroupData, post: MlsGroupPost): MlsGroupData {
    var admin = g.admin
    val seq = post.logSeq
    admin = admin.copy(applied = MlsAdminRules.withApplied(admin.applied, seq))
    if (seq != null && admin.pending?.logSeq == seq) admin = admin.copy(pending = null)
    val anchors = post.changes.filter { it.change in LEAVES_REQUESTS }.map { it.anchor }.toSet()
    if (anchors.isNotEmpty()) {
        admin = admin.copy(requests = admin.requests.copy(rows = admin.requests.requestsWithout(anchors)))
    }
    if (post.changes.any { it.isMe && it.change in OWN_EXIT }) {
        admin = admin.copy(slot = null, pending = null, requests = admin.requests.closed(), pushSaving = null)
    }
    if (post.kind == MlsGroupPostKind.VOID) post.voidedLogSeq?.let { admin = admin.copy(voided = admin.voided + it) }
    if (post.kind == MlsGroupPostKind.SETTINGS) {
        when (post.settingsApproval) {
            false -> admin = admin.copy(requests = admin.requests.closed())
            // Q13: turning approval on also drops the admission hint the SDK may not clear itself.
            true -> admin = admin.copy(warnings = MlsAdminRules.applyWarning(admin.warnings, MlsGroupWarning.AdmissionNeedsOnline, cleared = true))
            null -> Unit
        }
    }
    return g.copy(admin = admin)
}

private val LEAVES_REQUESTS = setOf(MlsGroupChangeKind.JOINED, MlsGroupChangeKind.REMOVED, MlsGroupChangeKind.BANNED)
private val OWN_EXIT = setOf(MlsGroupChangeKind.LEFT, MlsGroupChangeKind.REMOVED, MlsGroupChangeKind.BANNED)

private fun MlsRequestsState.requestsWithout(anchors: Set<String>) = rows.filterNot { it.request.anchor in anchors }

/** `pending_join`: only an owner or admin of an approval group keeps it; deduped by request id; the AI flag is kept (A12). */
internal fun adminPendingJoin(g: MlsGroupData, request: MlsJoinRequest): MlsGroupData {
    if (!MlsAdminRules.requestsEligible(g.info.group, g.timeline.readOnly)) return g
    val known = MlsAiFlags.learnRequests(g.aiAnchors, listOf(request))
    val r = g.admin.requests
    if (r.rows.any { it.request.requestId == request.requestId }) return g.copy(aiAnchors = known)
    val row = MlsRequestRow(MlsAiFlags.apply(known, request), gen = r.pageGen, fromEvent = true)
    return g.copy(aiAnchors = known, admin = g.admin.copy(requests = r.copy(rows = ordered(r.rows + row))))
}

/** One `catch_up` event: an older or repeated envelope position is ignored. The second value is true when a rejoin ended. */
internal fun adminCatchUp(g: MlsGroupData, signal: MlsGroupSignal.CatchUp): Pair<MlsGroupData, Boolean> {
    val record = g.admin.catchUp
    val last = record.eventSeq
    if (signal.seq != null && last != null && signal.seq <= last) return g to false
    val next = record.copy(
        state = signal.state, done = signal.done, total = signal.total,
        eventSeq = signal.seq ?: last, version = record.version + 1,
    )
    return g.copy(admin = g.admin.copy(catchUp = next)) to (record.state == MlsGroupCatchUp.REJOINING && signal.state == MlsGroupCatchUp.IDLE)
}

/** A rejoin ended: the newest history page is read again (the floor moved) and the info is read again. Nothing else. */
internal suspend fun MlsGroupSession.reloadAfterRejoin(account: String, epoch: Long, group: String) {
    val gen = txn(account, epoch) { a ->
        if (group !in a.opened) {
            a to null
        } else {
            // The generation is read inside the guard, together with the reset (R3-F1).
            val window = (a.groups[group] ?: MlsGroupData()).timeline.window.reset()
            a.withGroup(group) { it.copy(timeline = it.timeline.copy(window = window, loaded = false, loading = true, failure = null)) } to window.gen
        }
    } ?: return
    loadNewest(account, epoch, group, gen)
    scheduleInfo(account, epoch, group)
}

/**
 * One wake per `(account, group)`, armed for the earliest of `pacedUntil` and the next request expiry. It bumps a tick so the
 * screens render again and lifts an elapsed pace; it never calls.
 */
internal suspend fun MlsGroupSession.armAdminTimer(account: String, epoch: Long, group: String) {
    val key = account + MlsGroupSession.TIMER_SEPARATOR + group
    guard.withLock {
        val now = data.value
        if (account in now.purged || epoch != (now.epochs[account] ?: 0L)) return
        val admin = now.accounts[account]?.groups?.get(group)?.admin
        val clockNow = clock()
        val times = listOfNotNull(admin?.pacedUntil) + (admin?.requests?.rows?.mapNotNull { it.request.expiresAt }.orEmpty())
        val next = times.filter { it > clockNow }.minOrNull()
        val armed = adminTimers[key]
        if (next == null) {
            armed?.second?.cancel()
            adminTimers.remove(key)
            return
        }
        if (armed?.first == next) return
        armed?.second?.cancel()
        adminTimers[key] = next to scope.launch {
            delay(maxOf(0L, next - clock()))
            update(account, epoch) { a ->
                a.withGroup(group) { g ->
                    val lifted = if (g.admin.pacedUntil?.let { it <= clock() } == true) null else g.admin.pacedUntil
                    g.copy(admin = g.admin.copy(pacedUntil = lifted, tick = g.admin.tick + 1))
                }
            }
            guard.withLock { if (adminTimers[key]?.first == next) adminTimers.remove(key) }
            armAdminTimer(account, epoch, group)
        }
    }
}
