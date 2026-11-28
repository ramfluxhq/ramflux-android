// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.text.BreakIterator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The local check of a join link (S2): trimmed of ASCII whitespace, 1 to 512 printable ASCII characters. */
object MlsJoinLink {
    const val MAX = 512

    /** The trimmed link, or null when it fails the check (the join is then refused locally, with no call). */
    fun check(raw: String): String? {
        val link = raw.trim { it == ' ' || it in '\t'..'\r' }
        return link.takeIf { it.length in 1..MAX && it.all { c -> c.code in 0x21..0x7E } }
    }
}

/**
 * The one process-wide owner of everything the MLS groups screens act on: the join owner, the send owner, the timeline stores
 * (a window of 600 rows, kept for the 3 most recently opened groups), the member pages, the invites, the group list and the
 * AI marker set. Calls run in [scope], never in a screen's scope, so an outcome is recorded even when the screen closed. The
 * state is one immutable value; every change goes through [guard], which is never held across a repository call. Results
 * carry their account epoch and are dropped after [clearAccount] until [admit]. Nothing is ever retried or resent here: a call
 * is made once per explicit action of a screen. Nothing is persisted.
 */
class MlsGroupSession(
    internal val repository: MlsGroupRepository,
    internal val scope: CoroutineScope,
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val newId: () -> String = { UUID.randomUUID().toString() },
) {
    internal val guard = Mutex()
    internal val data = MutableStateFlow(MlsSessionData())
    private val watchers = HashMap<String, Job>()

    /** One wake per `(account, group)` at the reset time of a daily limit. A wake only lifts the lock; it never sends. */
    private val limitTimers = HashMap<String, Pair<Long, Job>>()

    /** One wake per `(account, group)` at the earliest `pacedUntil` or request expiry. A wake only bumps a tick; it never calls. */
    internal val adminTimers = HashMap<String, Pair<Long, Job>>()

    // ---- published state -------------------------------------------------------------------------------------------

    fun groups(account: String): Flow<MlsGroupListState> = view(account) { it.list }
    fun join(account: String): Flow<MlsJoinState> = view(account) { it.join }
    fun create(account: String): Flow<MlsCreateState> = view(account) { it.create }
    fun info(account: String, group: String): Flow<MlsInfoState> = view(account) { it.groups[group]?.info ?: MlsInfoState() }
    fun invites(account: String, group: String): Flow<MlsInvitesState> = view(account) { it.groups[group]?.invites ?: MlsInvitesState() }
    fun members(account: String, group: String): Flow<MlsMembersState> = view(account) { it.groups[group]?.members ?: MlsMembersState() }
    fun timeline(account: String, group: String): Flow<MlsTimelineState> = view(account) { it.groups[group]?.timeline ?: MlsTimelineState() }

    internal fun <T> view(account: String, read: (MlsAccountData) -> T): Flow<T> =
        data.map { read(it.accounts[account] ?: MlsAccountData()) }.distinctUntilChanged()

    // ---- account lifecycle -----------------------------------------------------------------------------------------

    /**
     * Empties everything of [account] and marks it purged: late results and new calls are dropped until [admit]. W registers
     * this through `AccountScopedStore` (a lock, an account removal). // W: AccountScopedStore.purgeBeforeClose
     */
    suspend fun clearAccount(account: String) {
        val job = guard.withLock {
            val now = data.value
            data.value = now.copy(
                accounts = now.accounts - account,
                purged = now.purged + account,
                epochs = now.epochs + (account to (now.epochs[account] ?: 0L) + 1L),
            )
            limitTimers.keys.filter { it.startsWith(account + TIMER_SEPARATOR) }.forEach { key -> limitTimers.remove(key)?.second?.cancel() }
            adminTimers.keys.filter { it.startsWith(account + TIMER_SEPARATOR) }.forEach { key -> adminTimers.remove(key)?.second?.cancel() }
            watchers.remove(account)
        }
        job?.cancel()
    }

    /** Lifts the purge mark of [account] (an unlock, an account added). W registers this through `onAdmit`. // W: AccountScopedStore.onAdmit */
    suspend fun admit(account: String) {
        guard.withLock { data.value = data.value.let { it.copy(purged = it.purged - account) } }
    }

    /**
     * The non-suspending form for the `onAdmit` listener. It starts undispatched, so it queues on the fair guard in call order:
     * an admit can never run after a later [clearAccount] of the same account (R2-F1). // W: register as onAdmit(session::admitInOrder)
     */
    fun admitInOrder(account: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { admit(account) }
    }

    /** Follows the `group.event` topic of [account] while a surface needs it. One subscription per account. */
    fun watch(account: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val epoch = epochOf(account)
            val started = guard.withLock {
                if (account in data.value.purged || watchers.containsKey(account)) {
                    false
                } else {
                    watchers[account] = scope.launch { repository.events(account).collect { handle(account, epoch, it) } }
                    true
                }
            }
            if (started) rebuildPending(account, epoch)
        }
    }

    // ---- group list ------------------------------------------------------------------------------------------------

    /** Loads the first page again; the rows stay until it arrives. */
    fun loadGroups(account: String) = launchFor(account) { epoch ->
        val ticket = txn(account, epoch) { a ->
            val next = a.list.ticket + 1
            a.copy(list = a.list.copy(ticket = next, loading = true, loadingMore = false, failure = null)) to next
        } ?: return@launchFor
        val result = repository.list(account, null)
        update(account, epoch) { a ->
            if (a.list.ticket != ticket) return@update a
            when (result) {
                is ClientResult.Ready -> a.copy(
                    list = a.list.copy(
                        rows = mergeRows(a.list.rows, result.value.items, replace = true),
                        cursor = result.value.cursor,
                        loading = false,
                        loaded = true,
                    )
                )
                else -> a.copy(list = a.list.copy(loading = false, loaded = true, failure = failure(result)))
            }
        }
        rebuildPending(account, epoch)
    }

    /** Loads the next page. Ignored while a page is loading or when the last page was read. */
    fun loadMoreGroups(account: String) = launchFor(account) { epoch ->
        val started = txn(account, epoch) { a ->
            val list = a.list
            if (list.cursor == null || list.loading || list.loadingMore) {
                a to null
            } else {
                a.copy(list = list.copy(loadingMore = true, failure = null)) to (list.cursor to list.ticket)
            }
        } ?: return@launchFor
        val (cursor, ticket) = started ?: return@launchFor
        val result = repository.list(account, cursor)
        update(account, epoch) { a ->
            if (a.list.ticket != ticket) return@update a
            when (result) {
                is ClientResult.Ready -> a.copy(
                    list = a.list.copy(
                        rows = mergeRows(a.list.rows, result.value.items, replace = false),
                        cursor = result.value.cursor,
                        loadingMore = false,
                    )
                )
                else -> a.copy(list = a.list.copy(loadingMore = false, failure = failure(result)))
            }
        }
    }

    // ---- join owner ------------------------------------------------------------------------------------------------

    /** A join sheet opened or closed: the next result shows only for an attempt of the new sheet. The call in flight goes on. */
    fun joinSheetChanged(account: String) = launchFor(account) { epoch ->
        update(account, epoch) { a -> a.copy(join = a.join.copy(attempt = a.join.attempt + 1, outcome = null)) }
    }

    /**
     * One tap on Join. A tap while a call is in flight is ignored. A link that fails the local check (S2) is refused without a
     * call. No outcome starts another call. The link is held only by this coroutine for the duration of the call.
     */
    fun submitJoin(account: String, raw: String) = launchFor(account) { epoch ->
        val link = MlsJoinLink.check(raw)
        val attempt = txn(account, epoch) { a ->
            if (a.join.inFlight) return@txn a to IGNORED
            val next = a.join.attempt + 1
            if (link == null) {
                val refusal = MlsGroupJoin.Refused(MlsGroupRefusal.LINK_INVALID, local = true)
                a.copy(join = a.join.copy(attempt = next, outcome = MlsJoinOutcome.Refused(refusal))) to LOCAL
            } else {
                a.copy(join = a.join.copy(attempt = next, inFlight = true, outcome = null)) to next
            }
        } ?: return@launchFor
        if (attempt <= 0 || link == null) return@launchFor
        val result = try {
            repository.join(account, link)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { update(account, epoch) { a -> a.copy(join = a.join.copy(inFlight = false)) } }
            throw cancelled
        }
        var joined: String? = null
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                val current = a.join.attempt == attempt
                val join = a.join.copy(inFlight = false)
                when (result) {
                    is ClientResult.Ready -> when (val value = result.value) {
                        is MlsGroupJoin.Joined -> {
                            joined = value.groupId
                            a.copy(
                                join = join.copy(
                                    outcome = if (current) MlsJoinOutcome.Joined(value.groupId) else join.outcome,
                                    pending = join.pending.filterNot { it.groupId == value.groupId },
                                )
                            )
                        }
                        is MlsGroupJoin.Pending -> {
                            val record = MlsPendingJoinRecord(value.requestId, value.groupId, value.reason, value.expiresAt, attempt)
                            a.copy(
                                join = join.copy(
                                    outcome = if (current) MlsJoinOutcome.Pending(record) else join.outcome,
                                    pending = join.pending.filterNot { it.requestId == record.requestId } + record,
                                )
                            )
                        }
                        is MlsGroupJoin.Refused ->
                            a.copy(join = join.copy(outcome = if (current) MlsJoinOutcome.Refused(value) else join.outcome))
                    }
                    else -> a.copy(join = join.copy(outcome = if (current) MlsJoinOutcome.Failed(failure(result)) else join.outcome))
                }
            }
        }
        // The joined group is read through the list and its info; this is not a join call.
        joined?.let {
            loadGroups(account)
            loadInfo(account, it)
        }
    }

    /**
     * Rebuilds the pending joins from the SDK. A record added while the SDK read ran (not in the snapshot taken before it) is kept,
     * and a record removed meanwhile (a `join_result`) is not brought back by the older read.
     */
    private suspend fun rebuildPending(account: String, epoch: Long) {
        val before = txn(account, epoch) { a -> a to a.join.pending.map { it.requestId }.toSet() } ?: return
        val result = repository.pendingJoins(account)
        if (result !is ClientResult.Ready) return
        update(account, epoch) { a ->
            val known = a.join.pending.associateBy { it.requestId }
            val removed = before - known.keys
            val fromSdk = result.value.filterNot { it.requestId in removed }
                .map { MlsPendingJoinRecord(it.requestId, it.groupId, it.reason, it.expiresAt, known[it.requestId]?.attempt ?: 0) }
            val seen = fromSdk.map { it.requestId }.toSet()
            val fresh = a.join.pending.filter { it.requestId !in before && it.requestId !in seen }
            a.copy(join = a.join.copy(pending = fromSdk + fresh))
        }
    }

    // ---- create, info, invites -------------------------------------------------------------------------------------

    /** One tap on Create. The name is checked here (1 to 64 grapheme clusters, trimmed); a created group is opened only with its id. */
    fun createGroup(account: String, name: String, approval: Boolean) = launchFor(account) { epoch ->
        val trimmed = name.trim()
        val error = when {
            trimmed.isEmpty() -> MlsNameError.REQUIRED
            graphemes(trimmed) > NAME_MAX -> MlsNameError.TOO_LONG
            else -> null
        }
        val attempt = txn(account, epoch) { a ->
            if (a.create.creating) return@txn a to IGNORED
            val next = a.create.attempt + 1
            if (error != null) {
                a.copy(create = MlsCreateState(nameError = error, attempt = next)) to LOCAL
            } else {
                a.copy(create = MlsCreateState(creating = true, attempt = next)) to next
            }
        } ?: return@launchFor
        if (attempt <= 0) return@launchFor
        val result = repository.create(account, trimmed, approval)
        var created: String? = null
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                when (result) {
                    is ClientResult.Ready -> {
                        created = result.value.groupId
                        val seeded = a.withGroup(result.value.groupId) { it.copy(invites = it.invites.copy(fresh = result.value.invite, loaded = false)) }
                        seeded.copy(create = MlsCreateState(created = result.value, attempt = attempt))
                    }
                    else -> a.copy(create = MlsCreateState(failure = failure(result), attempt = attempt))
                }
            }
        }
        created?.let {
            loadGroups(account)
            loadInfo(account, it)
        }
    }

    /** The create result was handled by the screen (it opened the group): the next create starts clean. */
    fun createHandled(account: String) = launchFor(account) { epoch ->
        update(account, epoch) { a -> a.copy(create = MlsCreateState(attempt = a.create.attempt)) }
    }

    /** `group.info`. */
    fun loadInfo(account: String, group: String) = launchFor(account) { epoch ->
        // The warning counter and the catch-up version when the read is issued: the info only replaces what no event touched since (2.7, 3.5).
        val issued = txn(account, epoch) { a ->
            val mark = a.groups[group]?.admin?.let { it.warnings.counter to it.catchUp.version } ?: (0L to 0L)
            a.withGroup(group) { it.copy(info = it.info.copy(loading = true)) } to mark
        } ?: return@launchFor
        val result = repository.info(account, group)
        var rejoined = false
        update(account, epoch) { a ->
            when (result) {
                is ClientResult.Ready -> a.withGroup(group) {
                    val read = adminReadInfo(it, result.value, issued.first, issued.second)
                    rejoined = read.second
                    read.first.copy(info = it.info.copy(group = result.value, loading = false, failure = null, notMember = false))
                }.withListRow(result.value)
                else -> {
                    val gone = (result as? ClientResult.Failed)?.problem?.let { (it.statusName ?: it.check) == "not_member" } == true
                    a.withGroup(group) { it.copy(info = it.info.copy(loading = false, failure = failure(result), notMember = gone)) }
                }
            }
        }
        if (rejoined) reloadAfterRejoin(account, epoch, group)
        armAdminTimer(account, epoch, group)
    }

    /** Re-reads the info after an event, at most once per second (the extra request is queued once). */
    internal suspend fun scheduleInfo(account: String, epoch: Long, group: String) {
        val wait = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            val elapsed = clock() - g.infoRefreshAt
            when {
                elapsed >= INFO_MIN_GAP -> a.withGroup(group) { it.copy(infoRefreshAt = clock()) } to 0L
                g.infoRefreshQueued -> a to -1L
                else -> a.withGroup(group) { it.copy(infoRefreshQueued = true) } to (INFO_MIN_GAP - elapsed)
            }
        } ?: return
        if (wait < 0) return
        if (wait == 0L) {
            loadInfo(account, group)
        } else {
            scope.launch {
                delay(wait)
                update(account, epoch) { a -> a.withGroup(group) { it.copy(infoRefreshQueued = false, infoRefreshAt = clock()) } }
                loadInfo(account, group)
            }
        }
    }

    /** `group.invite.list`. */
    fun loadInvites(account: String, group: String) = launchFor(account) { epoch ->
        update(account, epoch) { a -> a.withGroup(group) { it.copy(invites = it.invites.copy(loading = true, failure = null)) } }
        val result = repository.invites(account, group)
        update(account, epoch) { a ->
            a.withGroup(group) {
                when (result) {
                    is ClientResult.Ready -> it.copy(invites = it.invites.copy(invites = result.value, loaded = true, loading = false))
                    else -> it.copy(invites = it.invites.copy(loading = false, failure = failure(result)))
                }
            }
        }
    }

    /** One tap on "New link". */
    fun createInvite(account: String, group: String, maxUses: Int, ttlSecs: Long) = launchFor(account) { epoch ->
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            if (g.invites.creating) a to false
            else a.withGroup(group) { it.copy(invites = it.invites.copy(creating = true, failure = null)) } to true
        } ?: return@launchFor
        if (!go) return@launchFor
        val result = repository.createInvite(account, group, maxUses, ttlSecs)
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                a.withGroup(group) {
                    when (result) {
                        is ClientResult.Ready -> it.copy(
                            invites = it.invites.copy(
                                creating = false,
                                fresh = result.value,
                                invites = listOf(result.value) + it.invites.invites.filterNot { old -> old.inviteId == result.value.inviteId },
                            )
                        )
                        else -> it.copy(invites = it.invites.copy(creating = false, failure = failure(result)))
                    }
                }
            }
        }
    }

    /** One tap on the confirmed "Revoke". "Revoked" shows only after the reply (S6). */
    fun revokeInvite(account: String, group: String, inviteId: String) = launchFor(account) { epoch ->
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            if (inviteId in g.invites.revoking) a to false
            else a.withGroup(group) { it.copy(invites = it.invites.copy(revoking = it.invites.revoking + inviteId, failure = null)) } to true
        } ?: return@launchFor
        if (!go) return@launchFor
        val result = repository.revokeInvite(account, group, inviteId)
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                a.withGroup(group) {
                    val rest = it.invites.revoking - inviteId
                    when (result) {
                        is ClientResult.Ready -> it.copy(
                            invites = it.invites.copy(
                                revoking = rest,
                                invites = it.invites.invites.map { row -> if (row.inviteId == inviteId) row.copy(revoked = true) else row },
                                fresh = it.invites.fresh?.takeUnless { row -> row.inviteId == inviteId },
                            )
                        )
                        else -> it.copy(invites = it.invites.copy(revoking = rest, failure = failure(result)))
                    }
                }
            }
        }
        if (result is ClientResult.Ready) loadInfo(account, group)
    }

    // ---- members ---------------------------------------------------------------------------------------------------

    /** The first page of members (200 rows). */
    fun loadMembers(account: String, group: String) = launchFor(account) { epoch ->
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            if (g.members.loading) a to false
            else a.withGroup(group) { it.copy(members = MlsMembersState(loading = true)) } to true
        } ?: return@launchFor
        if (go) membersPage(account, epoch, group, null, replace = true)
    }

    /** The next page, or the retry of a failed one (an explicit tap). One page at a time. */
    fun loadMoreMembers(account: String, group: String) = launchFor(account) { epoch ->
        val cursor = membersStart(account, epoch, group) ?: return@launchFor
        membersPage(account, epoch, group, cursor, replace = false)
    }

    /** A search started: the remaining pages load, at most [SEARCH_PAGES], one at a time. */
    fun loadAllMembers(account: String, group: String) = launchFor(account) { epoch ->
        var pages = 0
        while (pages < SEARCH_PAGES) {
            val cursor = membersStart(account, epoch, group) ?: return@launchFor
            val ok = membersPage(account, epoch, group, cursor, replace = false)
            if (!ok) return@launchFor
            pages += 1
            update(account, epoch) { a -> a.withGroup(group) { it.copy(members = it.members.copy(searchPages = pages)) } }
        }
    }

    private suspend fun membersStart(account: String, epoch: Long, group: String): String? = txn(account, epoch) { a ->
        val g = a.groups[group] ?: MlsGroupData()
        val cursor = g.members.cursor
        if (cursor == null || g.members.loading) a to null
        else a.withGroup(group) { it.copy(members = it.members.copy(loading = true, failure = null)) } to cursor
    }

    private suspend fun membersPage(account: String, epoch: Long, group: String, cursor: String?, replace: Boolean): Boolean {
        val result = repository.members(account, group, cursor)
        update(account, epoch) { a ->
            a.withGroup(group) { g ->
                when (result) {
                    is ClientResult.Ready -> {
                        val known = MlsAiFlags.learnMembers(g.aiAnchors, result.value.items)
                        val merged = LinkedHashMap<String, MlsGroupMember>()
                        if (!replace) g.members.rows.forEach { merged[it.anchor] = it }
                        result.value.items.forEach { merged[it.anchor] = MlsAiFlags.apply(known, it) }
                        adminReadMembers(
                            g.copy(
                                aiAnchors = known,
                                members = g.members.copy(
                                    rows = merged.values.toList(),
                                    cursor = result.value.cursor,
                                    loaded = true,
                                    loading = false,
                                    failure = null,
                                ),
                            )
                        )
                    }
                    else -> g.copy(members = g.members.copy(loading = false, loaded = true, failure = failure(result)))
                }
            }
        }
        return result is ClientResult.Ready
    }

    // ---- timeline, send owner --------------------------------------------------------------------------------------

    /**
     * Opens a group: the newest history page, then a pull from the host (once per 5 s), then the info. A group whose window is
     * already loaded only fills the gap after its newest row; a window that was trimmed at the newest end is loaded again.
     */
    fun openGroup(account: String, group: String) = launchFor(account) { epoch ->
        val first = txn(account, epoch) { a ->
            val opened = (listOf(group) + a.opened.filterNot { it == group })
            val evicted = opened.drop(KEPT_WINDOWS)
            var next = a.copy(opened = opened.take(KEPT_WINDOWS))
            evicted.forEach { id ->
                next = next.withGroup(id) { it.copy(timeline = it.timeline.copy(window = it.timeline.window.reset(), loaded = false, loading = false)) }
            }
            val t = (a.groups[group] ?: MlsGroupData()).timeline
            if (t.loaded && !t.window.trimmedNewest) {
                next to false
            } else {
                // A window that lost its newest rows is reloaded from the newest page when the chat opens again.
                next.withGroup(group) {
                    it.copy(timeline = it.timeline.copy(window = t.window.reset(), loaded = false, loading = true, failure = null))
                } to true
            }
        } ?: return@launchFor
        if (first) loadNewest(account, epoch, group) else fillGap(account, group, force = true)
        refreshNow(account, epoch, group)
        loadInfo(account, group)
    }

    /** A reload of the newest page (the "Refresh" action when the window was trimmed or loading failed). */
    fun reloadGroup(account: String, group: String) = launchFor(account) { epoch ->
        update(account, epoch) { a ->
            a.withGroup(group) { it.copy(timeline = it.timeline.copy(window = it.timeline.window.reset(), loaded = false, loading = true, failure = null)) }
        }
        loadNewest(account, epoch, group)
        refreshNow(account, epoch, group)
    }

    internal suspend fun loadNewest(
        account: String,
        epoch: Long,
        group: String,
        started: Long? = data.value.accounts[account]?.groups?.get(group)?.timeline?.window?.gen,
    ) {
        val result = repository.history(account, group, null, null, PAGE)
        update(account, epoch) { a ->
            // A group that was evicted, or a window that was reset again, while the page was read takes no rows (S14).
            if (group !in a.opened) return@update a.withGroup(group) { it.copy(timeline = it.timeline.copy(loading = false)) }
            if (a.groups[group]?.timeline?.window?.gen != started) return@update a
            a.withGroup(group) { g ->
                when (result) {
                    is ClientResult.Ready -> ingest(g, result.value.posts).let { rows ->
                        val window = rows.timeline.window.page(result.value, older = false, newestPage = true)
                        rows.copy(timeline = rows.timeline.copy(window = window, loaded = true, loading = false, failure = null))
                    }
                    else -> g.copy(timeline = g.timeline.copy(loading = false, failure = failure(result)))
                }
            }
        }
    }

    /** The timeline reached its oldest loaded row. */
    fun loadOlder(account: String, group: String) = launchFor(account) { epoch ->
        val before = txn(account, epoch) { a ->
            val t = (a.groups[group] ?: MlsGroupData()).timeline
            val oldest = t.window.oldest
            if (!t.loaded || t.loadingOlder || !t.window.hasOlder || oldest == null) a to null
            else a.withGroup(group) { it.copy(timeline = it.timeline.copy(loadingOlder = true)) } to (oldest to t.window.gen)
        } ?: return@launchFor
        if (before == null) return@launchFor
        val result = repository.history(account, group, null, before.first, PAGE)
        update(account, epoch) { a ->
            a.withGroup(group) { g ->
                // The window was reset, restarted or evicted while the page was read: its rows would leave a hole (R2-M1).
                if (group !in a.opened || g.timeline.window.gen != before.second) return@withGroup g.copy(timeline = g.timeline.copy(loadingOlder = false))
                when (result) {
                    is ClientResult.Ready -> ingest(g, result.value.posts).let { rows ->
                        rows.copy(
                            timeline = rows.timeline.copy(
                                window = rows.timeline.window.page(result.value, older = true, newestPage = false),
                                loadingOlder = false,
                            )
                        )
                    }
                    else -> g.copy(timeline = g.timeline.copy(loadingOlder = false, failure = failure(result)))
                }
            }
        }
    }

    /** The app returned to the foreground with [group] open: fill the gap and pull from the host. */
    fun onForeground(account: String, group: String) = launchFor(account) { epoch ->
        // A trimmed window holds out new posts of others: it shows the newest page again instead of a gap fill (R2-M2).
        if (!reloadIfTrimmed(account, epoch, group)) fillGap(account, group, force = true)
        refreshNow(account, epoch, group)
    }

    /** "Refresh" or pull to refresh: `group.refresh` through the 5 s coalescing. */
    fun refreshGroup(account: String, group: String) = launchFor(account) { epoch ->
        // A window trimmed at the newest end shows the newest page again, so own and new posts reappear.
        reloadIfTrimmed(account, epoch, group)
        refreshNow(account, epoch, group)
    }

    /**
     * Reloads the newest page of a window that lost its newest rows (they hold out the posts of others), when [minNewest] is
     * not above the window's newest row: the newest row of the window is on screen, or the caller does not know (null).
     * True when the reload ran.
     */
    private suspend fun reloadIfTrimmed(account: String, epoch: Long, group: String, minNewest: Long? = null): Boolean {
        val trimmed = txn(account, epoch) { a ->
            val t = (a.groups[group] ?: MlsGroupData()).timeline
            if (group in a.opened && t.window.trimmedNewest && (minNewest == null || minNewest >= t.window.newest)) {
                a.withGroup(group) { it.copy(timeline = it.timeline.copy(window = it.timeline.window.reset(), loaded = false, loading = true, failure = null)) } to true
            } else {
                a to false
            }
        } ?: return false
        if (trimmed) loadNewest(account, epoch, group)
        return trimmed
    }

    private suspend fun refreshNow(account: String, epoch: Long, group: String) {
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            val now = clock()
            if (g.timeline.readOnly != null || now - g.lastRefreshAt < REFRESH_MIN_GAP) a to false
            else a.withGroup(group) { it.copy(lastRefreshAt = now) } to true
        } ?: return
        if (go) repository.refresh(account, group)
    }

    /** Reading position: [logSeq] is the newest posted row on screen. Moves forward only, one call at a time. */
    fun markRead(account: String, group: String, logSeq: Long) = launchFor(account) { epoch ->
        // The newest row of a trimmed window is on screen: posts of others were held out below it, so the window reloads (R2-M2).
        reloadIfTrimmed(account, epoch, group, minNewest = logSeq)
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            if (logSeq <= g.lastMarkedRead || g.markingRead || g.timeline.readOnly != null) a to false
            else a.withGroup(group) { it.copy(markingRead = true) } to true
        } ?: return@launchFor
        if (!go) return@launchFor
        val result = repository.markRead(account, group, logSeq)
        update(account, epoch) { a ->
            val next = a.withGroup(group) {
                it.copy(markingRead = false, lastMarkedRead = if (result is ClientResult.Ready) maxOf(logSeq, result.value.readLogSeq) else it.lastMarkedRead)
            }
            if (result is ClientResult.Ready) next.withRead(group, result.value) else next
        }
    }

    /**
     * Fills the gap after the newest loaded row (or [force]d from it), 200 rows per call while `more`. One fill runs per group; a
     * gap found during a fill queues one more round.
     */
    internal fun fillGap(account: String, group: String, force: Boolean = false): Unit = launchFor(account) { epoch ->
        val start = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            val t = g.timeline
            when {
                !t.loaded || t.window.trimmedNewest || (t.window.gapAfter == null && !force) -> a to null
                g.gapRunning -> a.withGroup(group) { it.copy(gapQueued = true) } to null
                else -> a.withGroup(group) { it.copy(gapRunning = true, gapQueued = false) } to ((t.window.gapAfter ?: t.window.newest) to t.window.gen)
            }
        } ?: return@launchFor
        if (start == null) return@launchFor
        val gen = start.second
        var after = start.first
        var again = false
        try {
            while (true) {
                val result = repository.history(account, group, after, null, GAP_PAGE)
                if (result !is ClientResult.Ready) break
                val rows = result.value
                var stale = false
                update(account, epoch) { a ->
                    a.withGroup(group) { g ->
                        // A reset, restart, eviction or trim since the fill began: the page would leave a hole (R2-M1, R2-F3).
                        if (group !in a.opened || g.timeline.window.gen != gen || g.timeline.window.trimmedNewest) {
                            stale = true
                            return@withGroup g
                        }
                        val ingested = ingest(g, rows.posts)
                        val window = ingested.timeline.window.copy(gapAfter = if (rows.more) ingested.timeline.window.gapAfter else null)
                        ingested.copy(timeline = ingested.timeline.copy(window = window))
                    }
                }
                if (stale) break
                val last = rows.posts.mapNotNull { it.logSeq }.maxOrNull()
                if (!rows.more || last == null || last <= after) break
                after = last
            }
        } finally {
            withContext(NonCancellable) {
                update(account, epoch) { a ->
                    a.withGroup(group) {
                        again = it.gapQueued
                        it.copy(gapRunning = false, gapQueued = false)
                    }
                }
            }
        }
        if (again) fillGap(account, group)
    }

    /** One tap on Send. Refused without a call while the group is read-only or the composer is limited. */
    fun send(account: String, group: String, text: String, replyTo: String?, mentions: List<String>) = launchFor(account) { epoch ->
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@launchFor
        val local = MlsLocalSend(newId(), trimmed, replyTo, mentions.take(MENTIONS_MAX), null, MlsLocalPhase.SUBMITTING, createdAt = clock())
        submit(account, epoch, group, local)
    }

    /** The explicit Retry tap of a NOT_SENT item: a new `group.send` under a new local id; the old bubble goes at once. */
    fun retry(account: String, group: String, localId: String) = launchFor(account) { epoch ->
        val local = txn(account, epoch) { a ->
            val timeline = (a.groups[group] ?: MlsGroupData()).timeline
            val old = timeline.locals.firstOrNull { it.localId == localId && it.phase == MlsLocalPhase.NOT_SENT }
            // A locked composer (daily limit) or a read-only group keeps the bubble and sends nothing.
            val locked = timeline.readOnly != null || timeline.limitedUntil?.let { it > clock() } == true
            if (old == null || locked) a to null
            else a.withGroup(group) { it.copy(timeline = it.timeline.copy(locals = it.timeline.locals.filterNot { l -> l.localId == localId })) } to
                old.copy(localId = newId(), postId = null, phase = MlsLocalPhase.SUBMITTING, queuedReason = null, sendAfter = null, failure = null, createdAt = clock())
        } ?: return@launchFor
        if (local != null) submit(account, epoch, group, local)
    }

    /** Drops a NOT_SENT item (the person's choice). */
    fun discard(account: String, group: String, localId: String) = launchFor(account) { epoch ->
        update(account, epoch) { a ->
            a.withGroup(group) { it.copy(timeline = it.timeline.copy(locals = it.timeline.locals.filterNot { l -> l.localId == localId && l.phase == MlsLocalPhase.NOT_SENT })) }
        }
    }

    private suspend fun submit(account: String, epoch: Long, group: String, local: MlsLocalSend) {
        val go = txn(account, epoch) { a ->
            val t = (a.groups[group] ?: MlsGroupData()).timeline
            val limited = t.limitedUntil?.let { it > clock() } == true
            if (t.readOnly != null || limited) a to false
            else a.withGroup(group) { it.copy(timeline = it.timeline.copy(locals = it.timeline.locals + local)) } to true
        } ?: return
        if (!go) return
        val result = repository.send(account, group, local.text, local.replyTo, local.mentions)
        withContext(NonCancellable) {
            update(account, epoch) { a -> a.withGroup(group) { applySend(it, local.localId, result) } }
        }
        if (data.value.accounts[account]?.groups?.get(group)?.timeline?.window?.gapAfter != null) fillGap(account, group)
        armLimitTimer(account, epoch, group)
    }

    /**
     * The composer lock of a used-up daily limit lifts at its reset time while the chat stays open. The wake is a timer: it
     * clears the lock and calls nothing. A lock of the account (clear) cancels it, and a later reset time re-arms it (the same time is
     * not armed twice).
     */
    private suspend fun armLimitTimer(account: String, epoch: Long, group: String) {
        val key = account + TIMER_SEPARATOR + group
        guard.withLock {
            val now = data.value
            val until = now.accounts[account]?.groups?.get(group)?.timeline?.limitedUntil
            if (account in now.purged || epoch != (now.epochs[account] ?: 0L)) return
            val armed = limitTimers[key]
            if (until == null || armed?.first == until) return
            // A later reset time replaces the timer, so the lock always lifts at the latest reset.
            armed?.second?.cancel()
            limitTimers[key] = until to scope.launch {
                delay(maxOf(0L, until - clock()))
                update(account, epoch) { a -> a.withGroup(group) { g -> lift(g, until) } }
                guard.withLock { if (limitTimers[key]?.first == until) limitTimers.remove(key) }
            }
        }
    }

    private fun lift(g: MlsGroupData, until: Long) =
        if (g.timeline.limitedUntil == until) g.copy(timeline = g.timeline.copy(limitedUntil = null)) else g

    private fun applySend(g: MlsGroupData, localId: String, result: ClientResult<MlsGroupSend>): MlsGroupData {
        val local = g.timeline.locals.firstOrNull { it.localId == localId } ?: return g
        // A pending row of the SDK that arrived while this call ran has the same post id: this item adopts it (r4 item 8).
        val adopted = (result as? ClientResult.Ready)?.value?.postId
        val rest = g.timeline.locals.filterNot { it.localId == localId || (adopted != null && it.postId == adopted) }
        fun keep(next: MlsLocalSend) = g.copy(timeline = g.timeline.timelineWith(locals = rest + next))
        return when (result) {
            is ClientResult.Ready -> {
                val sent = result.value
                val state = sent.state
                if (g.timeline.window.contains(sent.postId) && state !is MlsGroupPostState.Failed && state !is MlsGroupPostState.RateLimited) {
                    return g.copy(timeline = g.timeline.copy(locals = rest))
                }
                when (state) {
                    is MlsGroupPostState.Sent -> {
                        val slot = state.logSeq
                        if (slot == null) keep(local.copy(postId = sent.postId, phase = MlsLocalPhase.QUEUED, queuedReason = MlsGroupQueuedReason.UNKNOWN))
                        else g.copy(timeline = g.timeline.copy(locals = rest, window = windowWithSent(g.timeline.window, local, sent.postId, slot)))
                    }
                    is MlsGroupPostState.Queued -> keep(local.copy(postId = sent.postId, phase = MlsLocalPhase.QUEUED, queuedReason = state.reason))
                    is MlsGroupPostState.Delayed -> keep(local.copy(postId = sent.postId, phase = MlsLocalPhase.DELAYED, sendAfter = state.sendAfter))
                    is MlsGroupPostState.RateLimited -> notSent(g, local, rest, MlsSendFailure("rate_limited", rateLimited = true, quota = state.quota))
                    is MlsGroupPostState.Failed -> notSent(g, local, rest, MlsSendFailure(state.reason))
                    else -> keep(local.copy(postId = sent.postId, phase = MlsLocalPhase.QUEUED, queuedReason = MlsGroupQueuedReason.UNKNOWN))
                }
            }
            is ClientResult.Failed -> notSent(g, local, rest, MlsSendFailure(result.problem.statusName ?: result.problem.check, detail = result.problem.detail))
            is ClientResult.Unavailable -> notSent(g, local, rest, MlsSendFailure(null, unavailable = true))
        }
    }

    private fun notSent(g: MlsGroupData, local: MlsLocalSend, rest: List<MlsLocalSend>, why: MlsSendFailure): MlsGroupData {
        val until = why.quota?.takeIf { it.remaining <= 0 }?.resetsAt?.takeIf { it > clock() }
        val next = local.copy(phase = MlsLocalPhase.NOT_SENT, failure = why, queuedReason = null, sendAfter = null)
        return g.copy(timeline = g.timeline.copy(locals = rest + next, limitedUntil = until ?: g.timeline.limitedUntil))
    }

    private fun MlsTimelineState.timelineWith(locals: List<MlsLocalSend>) = copy(locals = locals)

    /** The own post that reached its log slot: the row an event or history already put there wins. */
    private fun windowWithSent(window: MlsTimelineWindow, local: MlsLocalSend, postId: String, slot: Long): MlsTimelineWindow {
        if (window.posts.any { it.logSeq == slot }) return window
        val row = MlsGroupPost(
            slot, postId, MlsGroupAuthor("", null, aiFlag = false, isMe = true), clock(), MlsGroupPostKind.TEXT, local.text, local.replyTo,
            local.mentions, MlsGroupPostState.Sent(slot), emptyList(), null, null, null,
        )
        // A trimmed window restarts at the own post, which is the newest row the person knows; newer rows load by the gap fill.
        return if (window.trimmedNewest) window.restartAt(row) else window.merge(listOf(row))
    }

    /** One tap on the confirmed "Delete for everyone" of an own posted row. "Deleting…" until the reply or `post_deleted` (S6). */
    fun deletePost(account: String, group: String, postId: String) = launchFor(account) { epoch ->
        val go = txn(account, epoch) { a ->
            val t = (a.groups[group] ?: MlsGroupData()).timeline
            val row = t.window.posts.firstOrNull { it.postId == postId }
            if (t.readOnly != null || postId in t.deleting || row?.logSeq == null || row.author?.isMe != true || row.state == MlsGroupPostState.Deleted) {
                a to false
            } else {
                a.withGroup(group) { it.copy(timeline = it.timeline.copy(deleting = it.timeline.deleting + postId, deleteFailure = null)) } to true
            }
        } ?: return@launchFor
        if (!go) return@launchFor
        val result = repository.delete(account, group, postId)
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                a.withGroup(group) { g ->
                    val rest = g.timeline.deleting - postId
                    when (result) {
                        is ClientResult.Ready -> when (result.value.state) {
                            is MlsGroupPostState.Deleted, is MlsGroupPostState.Sent ->
                                g.copy(timeline = g.timeline.copy(deleting = rest, window = g.timeline.window.tombstone(postId, null)))
                            // Queued or delayed: the delete record is not written yet; the row keeps "Deleting…" until the event.
                            else -> g
                        }
                        else -> g.copy(timeline = g.timeline.copy(deleting = rest, deleteFailure = failure(result)))
                    }
                }
            }
        }
    }

    /** The delete failure was shown. */
    fun deleteFailureShown(account: String, group: String) = launchFor(account) { epoch ->
        update(account, epoch) { a -> a.withGroup(group) { it.copy(timeline = it.timeline.copy(deleteFailure = null)) } }
    }

    /** One tap on the confirmed "Leave". The group is read-only only after `committed` (S6). */
    fun leaveGroup(account: String, group: String) = launchFor(account) { epoch ->
        val go = txn(account, epoch) { a ->
            val g = a.groups[group] ?: MlsGroupData()
            if (g.info.leaving) a to false
            else a.withGroup(group) { it.copy(info = it.info.copy(leaving = true, leaveFailure = null)) } to true
        } ?: return@launchFor
        if (!go) return@launchFor
        val result = repository.leave(account, group)
        withContext(NonCancellable) {
            update(account, epoch) { a ->
                a.withGroup(group) {
                    when (result) {
                        is ClientResult.Ready -> it.copy(
                            info = it.info.copy(leaving = false),
                            timeline = it.timeline.copy(readOnly = MlsReadOnly.LEFT),
                        )
                        else -> it.copy(info = it.info.copy(leaving = false, leaveFailure = failure(result)))
                    }
                }
            }
        }
        if (result is ClientResult.Ready) loadInfo(account, group)
    }

    // ---- events ----------------------------------------------------------------------------------------------------

    private suspend fun handle(account: String, epoch: Long, signal: MlsGroupSignal) {
        val group = signal.groupId
        var unknownGroup = false
        var infoFor: String? = null
        var joined: String? = null
        var resync = false
        var voidFor: String? = null
        var rejoined = false
        update(account, epoch) { a ->
            // Only the most recently opened groups hold a window; the others keep what the list row needs (S14).
            val live = group != null && group in a.opened
            when (signal) {
                is MlsGroupSignal.Post -> {
                    val id = group ?: return@update a
                    if (a.list.rows.none { it.groupId == id }) unknownGroup = true
                    var next = a.withGroup(id) { g -> ingest(g, listOf(signal.post), event = true, live = live) }
                    next = next.bumpList(id, signal.post)
                    next
                }
                is MlsGroupSignal.PostState -> {
                    val id = group ?: return@update a
                    a.withGroup(id) { applyPostState(it, signal, live) }
                }
                is MlsGroupSignal.PostDeleted -> {
                    val id = group ?: return@update a
                    a.withGroup(id) {
                        val window = if (live) it.timeline.window.tombstone(signal.postId, signal.postLogSeq) else it.timeline.window
                        it.copy(timeline = it.timeline.copy(window = window, deleting = it.timeline.deleting - signal.postId))
                    }
                }
                is MlsGroupSignal.Service -> {
                    val id = group ?: return@update a
                    infoFor = id
                    var next = a.withGroup(id) { g ->
                        adminService(applyMembership(ingest(g, listOf(signal.post), event = true, live = live), signal.post), signal.post)
                    }
                    if (signal.post.kind == MlsGroupPostKind.VOID) voidFor = id
                    next = next.bumpList(id, signal.post)
                    val mine = signal.post.changes.filter { it.isMe }
                    if (mine.any { it.change == MlsGroupChangeKind.LEFT || it.change == MlsGroupChangeKind.REMOVED || it.change == MlsGroupChangeKind.BANNED }) {
                        next = next.copy(list = next.list.copy(rows = next.list.rows.filterNot { it.groupId == id }))
                        infoFor = null
                    }
                    next
                }
                is MlsGroupSignal.JoinResult -> {
                    val record = a.join.pending.firstOrNull { it.requestId == signal.requestId }
                    if (record == null) {
                        unknownGroup = true
                        a
                    } else {
                        val current = a.join.attempt == record.attempt
                        val rest = a.join.pending.filterNot { it.requestId == record.requestId }
                        if (signal.joined) joined = signal.groupId ?: record.groupId
                        val outcome = when {
                            !current -> a.join.outcome
                            signal.joined -> (signal.groupId ?: record.groupId)?.let { MlsJoinOutcome.Joined(it) } ?: a.join.outcome
                            else -> signal.refusal?.let { MlsJoinOutcome.Refused(it) } ?: a.join.outcome
                        }
                        a.copy(join = a.join.copy(pending = rest, outcome = outcome))
                    }
                }
                is MlsGroupSignal.Read -> {
                    val id = group ?: return@update a
                    a.withRead(id, signal.read)
                }
                is MlsGroupSignal.PendingJoin -> {
                    val id = group ?: return@update a
                    a.withGroup(id) { adminPendingJoin(it, signal.request) }
                }
                is MlsGroupSignal.CatchUp -> {
                    val id = group ?: return@update a
                    a.withGroup(id) {
                        val applied = adminCatchUp(it, signal)
                        rejoined = applied.second
                        applied.first
                    }
                }
                is MlsGroupSignal.Warning -> {
                    val id = group ?: return@update a
                    a.withGroup(id) { it.copy(admin = it.admin.copy(warnings = MlsAdminRules.applyWarning(it.admin.warnings, signal.warning, signal.cleared))) }
                }
                MlsGroupSignal.Resync -> {
                    resync = true
                    a
                }
            }
        }
        if (unknownGroup || joined != null) loadGroups(account)
        joined?.let { loadInfo(account, it) }
        if (resync) {
            loadGroups(account)
            data.value.accounts[account]?.groups?.forEach { (id, g) ->
                if (g.timeline.loaded && id in (data.value.accounts[account]?.opened ?: emptyList())) {
                    if (g.timeline.window.trimmedNewest) launchFor(account) { e -> reloadIfTrimmed(account, e, id) } else fillGap(account, id, force = true)
                }
            }
        }
        if (group != null && data.value.accounts[account]?.groups?.get(group)?.timeline?.window?.gapAfter != null) fillGap(account, group)
        group?.let { armLimitTimer(account, epoch, it) }
        infoFor?.let { scheduleInfo(account, epoch, it) }
        if (rejoined && group != null) reloadAfterRejoin(account, epoch, group)
        if (voidFor != null && group != null) {
            // The roster may revert with a void: the loaded member pages and the info are read again (2.6).
            if (data.value.accounts[account]?.groups?.get(group)?.members?.loaded == true) loadMembers(account, group)
            scheduleInfo(account, epoch, group)
        }
        group?.let { armAdminTimer(account, epoch, it) }
    }

    private fun applyPostState(g: MlsGroupData, signal: MlsGroupSignal.PostState, live: Boolean): MlsGroupData {
        val state = signal.state
        val local = g.timeline.locals.firstOrNull { it.postId == signal.postId }
        var next = g
        if (local != null) {
            val rest = g.timeline.locals.filterNot { it.localId == local.localId }
            next = when (state) {
                is MlsGroupPostState.Sent -> {
                    val slot = state.logSeq ?: signal.logSeq
                    if (slot == null) next.copy(timeline = next.timeline.copy(locals = rest + local.copy(phase = MlsLocalPhase.QUEUED)))
                    else if (!live) next.copy(timeline = next.timeline.copy(locals = rest))
                    else next.copy(timeline = next.timeline.copy(locals = rest, window = windowWithSent(next.timeline.window, local, signal.postId, slot)))
                }
                is MlsGroupPostState.Queued -> next.copy(timeline = next.timeline.copy(locals = rest + local.copy(phase = MlsLocalPhase.QUEUED, queuedReason = state.reason)))
                is MlsGroupPostState.Delayed -> next.copy(timeline = next.timeline.copy(locals = rest + local.copy(phase = MlsLocalPhase.DELAYED, sendAfter = state.sendAfter)))
                is MlsGroupPostState.Failed -> notSent(next, local, rest, MlsSendFailure(state.reason))
                is MlsGroupPostState.RateLimited -> notSent(next, local, rest, MlsSendFailure("rate_limited", rateLimited = true, quota = state.quota))
                else -> next
            }
        } else if (live && (state is MlsGroupPostState.Sent || state is MlsGroupPostState.Deleted)) {
            next = next.copy(timeline = next.timeline.copy(window = next.timeline.window.withState(signal.postId, state)))
        }
        return next
    }

    /** A membership row: the member list of the group follows it, and an own removal or ban makes the group read-only. */
    private fun applyMembership(g: MlsGroupData, post: MlsGroupPost): MlsGroupData {
        var next = g
        post.changes.forEach { change ->
            next = when (change.change) {
                MlsGroupChangeKind.JOINED -> if (next.members.loaded && next.members.rows.none { it.anchor == change.anchor }) {
                    // The marker learned for this group and anchor is never dropped by a change that says `ai_flag: false` (S8).
                    val row = MlsGroupMember(change.anchor, change.display, change.role ?: MlsGroupRole.MEMBER, change.aiFlag, 1, change.isMe)
                    next.copy(members = next.members.copy(rows = next.members.rows + MlsAiFlags.apply(next.aiAnchors, row)))
                } else next
                MlsGroupChangeKind.LEFT, MlsGroupChangeKind.REMOVED, MlsGroupChangeKind.BANNED ->
                    next.copy(members = next.members.copy(rows = next.members.rows.filterNot { it.anchor == change.anchor }))
                MlsGroupChangeKind.ROLE_CHANGED -> next.copy(
                    members = next.members.copy(rows = next.members.rows.map { if (it.anchor == change.anchor) it.copy(role = change.role ?: it.role) else it })
                )
                else -> next
            }
            if (change.isMe) {
                val reason = when (change.change) {
                    MlsGroupChangeKind.LEFT -> MlsReadOnly.LEFT
                    MlsGroupChangeKind.REMOVED -> MlsReadOnly.REMOVED
                    MlsGroupChangeKind.BANNED -> MlsReadOnly.BANNED
                    else -> null
                }
                if (reason != null) next = next.copy(timeline = next.timeline.copy(readOnly = reason))
            }
        }
        return next
    }

    /**
     * Puts history or event rows into the group: the AI marker set learns first (S8), posted rows go into the window by
     * `log_seq`, and own unsent rows (`log_seq` null) become local items unless a local item already has the post id. A posted
     * row removes the local item with the same post id, so a post shows once.
     */
    private fun ingest(g: MlsGroupData, rows: List<MlsGroupPost>, event: Boolean = false, live: Boolean = true): MlsGroupData {
        var known = g.aiAnchors
        rows.forEach { known = MlsAiFlags.learn(known, it) }
        val marked = rows.map { MlsAiFlags.apply(known, it) }
        val posted = marked.filter { it.logSeq != null }
        val unsent = marked.filter { it.logSeq == null && it.postId.isNotEmpty() }
        var t = g.timeline
        var window = t.window
        var locals = t.locals.filterNot { local -> local.postId != null && posted.any { it.postId == local.postId } }
        if (live) {
            // An own post of a trimmed window that reaches its slot by an event restarts the window there, so it never vanishes.
            val own = if (event && window.trimmedNewest) posted.firstOrNull { row -> t.locals.any { it.postId != null && it.postId == row.postId } } else null
            if (own != null) window = window.restartAt(own)
            window = if (event) posted.fold(window) { w, row -> w.event(row) } else window.merge(posted)
        }
        if (live) unsent.forEach { row ->
            if (locals.none { it.postId == row.postId } && !window.contains(row.postId)) {
                val phase = if (row.state is MlsGroupPostState.Delayed) MlsLocalPhase.DELAYED else MlsLocalPhase.QUEUED
                locals = locals + MlsLocalSend(
                    "post:" + row.postId, row.text.orEmpty(), row.replyTo, row.mentions, row.postId, phase,
                    (row.state as? MlsGroupPostState.Queued)?.reason, (row.state as? MlsGroupPostState.Delayed)?.sendAfter, createdAt = clock(),
                )
            }
        }
        t = t.copy(window = window, locals = locals)
        return g.copy(aiAnchors = known, timeline = t)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    internal fun launchFor(account: String, block: suspend (epoch: Long) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (account in data.value.purged) return@launch
            block(epochOf(account))
        }
    }

    internal fun epochOf(account: String) = data.value.epochs[account] ?: 0L

    /** Runs [block] on the account under the guard; null when the account is purged or no longer in [epoch] (a busy flag never lands in a newer epoch). */
    internal suspend fun <R> txn(account: String, epoch: Long, block: (MlsAccountData) -> Pair<MlsAccountData, R>): R? = guard.withLock {
        val now = data.value
        if (account in now.purged || epoch != (now.epochs[account] ?: 0L)) return null
        val (next, result) = block(now.accounts[account] ?: MlsAccountData())
        data.value = now.copy(accounts = now.accounts + (account to next))
        result
    }

    /** Applies [block] only while the account is not purged and still in [epoch]: a late result never lands. */
    internal suspend fun update(account: String, epoch: Long, block: (MlsAccountData) -> MlsAccountData): Boolean = guard.withLock {
        val now = data.value
        if (account in now.purged || epoch != (now.epochs[account] ?: 0L)) return false
        data.value = now.copy(accounts = now.accounts + (account to block(now.accounts[account] ?: MlsAccountData())))
        true
    }

    internal fun MlsAccountData.withGroup(id: String, change: (MlsGroupData) -> MlsGroupData): MlsAccountData =
        copy(groups = groups + (id to change(groups[id] ?: MlsGroupData())))

    /** A fresh info row replaces the list row of the group when it is not older. */
    private fun MlsAccountData.withListRow(row: MlsGroup): MlsAccountData {
        if (list.rows.none { it.groupId == row.groupId }) return this
        return copy(list = list.copy(rows = mergeRows(list.rows, listOf(row), replace = false)))
    }

    /** An event is newer than any list row: it moves the row to the top with the post's time. */
    private fun MlsAccountData.bumpList(id: String, post: MlsGroupPost): MlsAccountData {
        val at = post.appendedAt ?: return this
        val rows = list.rows.map {
            if (it.groupId == id && at >= (it.lastActivityAt ?: 0L)) it.copy(lastActivityAt = at, lastLogSeq = maxOf(it.lastLogSeq, post.logSeq ?: 0L)) else it
        }
        return copy(list = list.copy(rows = sortRows(rows)))
    }

    private fun MlsAccountData.withRead(id: String, read: MlsGroupRead): MlsAccountData {
        val rows = list.rows.map { if (it.groupId == id) it.copy(unread = read.unread, unreadMentions = read.unreadMentions, readLogSeq = read.readLogSeq) else it }
        return withGroup(id) { g -> g.copy(info = g.info.copy(group = g.info.group?.copy(unread = read.unread, unreadMentions = read.unreadMentions, readLogSeq = read.readLogSeq))) }
            .copy(list = list.copy(rows = rows))
    }

    internal fun failure(result: ClientResult<*>): MlsFailure = when (result) {
        is ClientResult.Failed -> MlsFailure.of(result.problem)
        is ClientResult.Unavailable -> MlsFailure.UNAVAILABLE
        is ClientResult.Ready -> MlsFailure(null)
    }

    companion object {
        const val NAME_MAX = 64
        internal const val TIMER_SEPARATOR = '\u0000'
        const val PAGE = 50
        const val GAP_PAGE = 200
        const val KEPT_WINDOWS = 3
        const val SEARCH_PAGES = 5
        const val MENTIONS_MAX = 50
        const val REFRESH_MIN_GAP = 5_000L
        const val INFO_MIN_GAP = 1_000L
        private const val IGNORED = 0
        private const val LOCAL = -1

        /** Grapheme clusters of [text]. */
        fun graphemes(text: String): Int {
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            var count = 0
            while (it.next() != BreakIterator.DONE) count += 1
            return count
        }

        fun sortRows(rows: Collection<MlsGroup>): List<MlsGroup> =
            rows.sortedWith(compareByDescending<MlsGroup> { it.lastActivityAt ?: 0L }.thenBy { it.groupId })

        /**
         * Rows by `group_id`. An incoming row replaces a stored one only when its `last_activity_at` is not older (an event
         * is newer than any list row). A first page ([replace]) keeps only the incoming ids.
         */
        fun mergeRows(existing: List<MlsGroup>, incoming: List<MlsGroup>, replace: Boolean): List<MlsGroup> {
            val old = existing.associateBy { it.groupId }
            val merged = LinkedHashMap<String, MlsGroup>()
            if (!replace) merged.putAll(old)
            for (row in incoming) {
                val prior = old[row.groupId]
                merged[row.groupId] = if (prior != null && (prior.lastActivityAt ?: 0L) > (row.lastActivityAt ?: 0L)) prior else row
            }
            return sortRows(merged.values)
        }
    }
}
