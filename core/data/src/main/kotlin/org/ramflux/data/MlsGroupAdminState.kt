// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/*
 * The state and the pure rules of the MLS admin owner (M3 contract 3.1 to 3.7). Everything is in memory and belongs to one
 * `(account, group)` inside the session's account data, so `clearAccount` drops it with the account. Types that hold names,
 * ids or anchors print `<redacted>`.
 */

enum class MlsAdminKind { APPROVE, ROLE, REMOVE, BAN, SETTINGS, VOID }

/**
 * The one commit-producing action of a group that is in flight. [target] is a request id, an anchor, a settings field or a
 * log position; it is never shown and never logged.
 */
data class MlsAdminAction(
    val actionId: String,
    val kind: MlsAdminKind,
    val target: String,
    val issuedEpoch: Long,
    val issuedAt: Long,
) {
    override fun toString() = "MlsAdminAction(kind=$kind)"
}

/** The committed reply came; the event at [logSeq] (or a re-read that shows the result) has not. */
data class MlsAdminPending(val action: MlsAdminAction, val logSeq: Long) {
    override fun toString() = "MlsAdminPending(kind=${action.kind})"
}

/** The typed refusal of the last admin call of the group, for the screen that started it. */
data class MlsAdminFailure(val kind: MlsAdminKind, val target: String, val failure: MlsFailure) {
    override fun toString() = "MlsAdminFailure(kind=$kind, failure=$failure)"
}

enum class MlsRequestPhase { IDLE, APPROVING, ADDING, DECLINING }

/** One loaded join request. [gen] is the page generation it was read or inserted in; [fromEvent] marks a `pending_join` row. */
data class MlsRequestRow(
    val request: MlsJoinRequest,
    val phase: MlsRequestPhase = MlsRequestPhase.IDLE,
    val failure: MlsFailure? = null,
    val gen: Long = 0,
    val fromEvent: Boolean = false,
) {
    override fun toString() = "MlsRequestRow(phase=$phase)"
}

/** The requests pager of one group (3.2). [pageGen] is bumped by every first-page read and every close. */
data class MlsRequestsState(
    val rows: List<MlsRequestRow> = emptyList(),
    val cursor: String? = null,
    val pageGen: Long = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val loadFailure: MlsFailure? = null,
    val pageFailure: MlsFailure? = null,
    /** Counts the `request_not_found` answers: the screen shows one toast per change. */
    val goneTicks: Int = 0,
) {
    override fun toString() = "MlsRequestsState(rows=${rows.size})"
}

/**
 * The catch-up record (3.5). [version] counts every applied event or info state; [eventSeq] is the envelope position of the
 * last applied event. A skip result applies only while [version] still equals [skipFromVersion].
 */
data class MlsCatchUpRecord(
    val state: MlsGroupCatchUp = MlsGroupCatchUp.IDLE,
    val done: Long = 0,
    val total: Long = 0,
    val eventSeq: Long? = null,
    val version: Long = 0,
    val skipping: Boolean = false,
    val skipFromVersion: Long = 0,
    val skipFailure: MlsFailure? = null,
)

/** The warning set of a group, keyed by code, with the marks of the 2.7 merge rule. */
data class MlsWarningSet(
    val items: Map<String, MlsGroupWarning> = emptyMap(),
    /** Counts the warning events applied to this group. */
    val counter: Long = 0,
    /** The counter value at which an event last touched a code. */
    val touched: Map<String, Long> = emptyMap(),
) {
    override fun toString() = "MlsWarningSet(count=${items.size})"
}

/** Everything the admin owner knows of one group. */
data class MlsAdminState(
    val slot: MlsAdminAction? = null,
    val pending: MlsAdminPending? = null,
    /** What the action in flight expects a re-read to show (a role, a name, `true` or `false`); never shown or logged. */
    val expect: String? = null,
    /** The last 64 applied commit positions: an event that came before its reply resolves the action at once. */
    val applied: List<Long> = emptyList(),
    val pacedUntil: Long? = null,
    val requests: MlsRequestsState = MlsRequestsState(),
    val lastFailure: MlsAdminFailure? = null,
    val pushSaving: MlsGroupPush? = null,
    val pushFailure: MlsFailure? = null,
    val catchUp: MlsCatchUpRecord = MlsCatchUpRecord(),
    val warnings: MlsWarningSet = MlsWarningSet(),
    /** Warning codes the person hid for this session (memory only; cleared on lock). */
    val hidden: Set<String> = emptySet(),
    /** Commits this session knows were undone, by `log_seq`. */
    val voided: Set<Long> = emptySet(),
    /** The timer wake bumps this so a screen renders again; it calls nothing. */
    val tick: Long = 0,
) {
    /** True while an admin commit is running or waits for its event: every commit-producing entry is disabled. */
    val busy: Boolean get() = slot != null || pending != null

    override fun toString() = "MlsAdminState(busy=$busy)"
}

/** The pure rules of the admin owner. */
object MlsAdminRules {
    const val APPLIED_KEEP = 64
    const val REQUEST_LIMIT = 200
    const val REQUESTS_MAX = 2000

    fun isAdmin(role: MlsGroupRole) = role == MlsGroupRole.OWNER || role == MlsGroupRole.ADMIN

    /** The requests entry and screen exist only for an owner or admin of an approval group that is not read-only. */
    fun requestsEligible(group: MlsGroup?, readOnly: MlsReadOnly?) =
        group != null && readOnly == null && group.approval && isAdmin(group.myRole)

    /**
     * The head commit (contract 2.6): the commit row with the greatest `log_seq` of the loaded window, only while the newest page
     * is loaded, and never a `void` row or a row that is already undone. The role and read-only checks are the caller's.
     */
    fun headCommit(window: MlsTimelineWindow, voided: Set<Long>): Long? {
        if (window.trimmedNewest) return null
        val newest = window.posts
            .filter { it.logSeq != null && it.kind in COMMIT_KINDS }
            .maxByOrNull { it.logSeq ?: 0L } ?: return null
        val slot = newest.logSeq ?: return null
        if (newest.kind == MlsGroupPostKind.VOID || slot in voided || slot in voidedBy(window)) return null
        return slot
    }

    /** The commits the void rows of [window] undo. */
    fun voidedBy(window: MlsTimelineWindow): Set<Long> =
        window.posts.filter { it.kind == MlsGroupPostKind.VOID }.mapNotNull { it.voidedLogSeq }.toSet()

    /** The merge of an info read into the warning set (contract 2.7). [issued] is the counter when the read was issued. */
    fun mergeWarnings(current: MlsWarningSet, read: List<MlsGroupWarning>, issued: Long): MlsWarningSet {
        val untouchedSinceIssue = { key: String -> (current.touched[key] ?: 0L) <= issued }
        val next = LinkedHashMap<String, MlsGroupWarning>()
        // A code an event touched after the read was issued stays as the events left it.
        current.items.forEach { (key, warning) -> if (!untouchedSinceIssue(key)) next[key] = warning }
        read.forEach { if (untouchedSinceIssue(it.key)) next[it.key] = it }
        return current.copy(items = next)
    }

    /** One warning event: sets or clears one code and marks it touched. */
    fun applyWarning(current: MlsWarningSet, warning: MlsGroupWarning, cleared: Boolean): MlsWarningSet {
        val counter = current.counter + 1
        val items = if (cleared) current.items - warning.key else current.items + (warning.key to warning)
        return current.copy(items = items, counter = counter, touched = current.touched + (warning.key to counter))
    }

    /** A quota line shows on the first day, or when [MlsGroupQuota.remaining] is at most a fifth of the limit (at least 1) (2.8). */
    fun quotaLineVisible(quota: MlsGroupQuota?): Boolean =
        quota != null && quota.limit > 0 && (quota.firstDay || quota.remaining <= maxOf(1L, quota.limit / 5))

    /** The banner order of 3.7: critical codes, then warnings, then the admission hint, unknown codes last. */
    fun severity(warning: MlsGroupWarning): Int = when (warning) {
        is MlsGroupWarning.ForkAlarm -> 0
        is MlsGroupWarning.RejoinNeeded -> 1
        is MlsGroupWarning.HeadCommitRejected -> 2
        is MlsGroupWarning.Undecryptable -> 3
        is MlsGroupWarning.HostUnusual -> 4
        MlsGroupWarning.AdmissionNeedsOnline -> 5
        is MlsGroupWarning.HostIpVisible -> 6
        is MlsGroupWarning.Unknown -> 7
    }

    /** `fork_alarm`, `rejoin_needed` and `head_commit_rejected` cannot be hidden; the others can, for the session. */
    fun hideable(warning: MlsGroupWarning) = warning is MlsGroupWarning.Undecryptable || warning is MlsGroupWarning.HostUnusual ||
        warning === MlsGroupWarning.AdmissionNeedsOnline

    /**
     * The warnings a person sees, most severe first: `head_commit_rejected` only for an owner or admin, `host_ip_visible` never
     * (the M2 info row), the rest as they are.
     */
    fun visibleWarnings(set: MlsWarningSet, role: MlsGroupRole?): List<MlsGroupWarning> =
        set.items.values
            .filterNot { it is MlsGroupWarning.HostIpVisible }
            .filterNot { it is MlsGroupWarning.HeadCommitRejected && (role == null || !isAdmin(role)) }
            .sortedWith(compareBy({ severity(it) }, { it.key }))

    fun withApplied(applied: List<Long>, logSeq: Long?): List<Long> =
        if (logSeq == null || logSeq in applied) applied else (applied + logSeq).takeLast(APPLIED_KEEP)

    /** The kind of change a member action expects to see in the event. */
    fun expects(kind: MlsAdminKind) = when (kind) {
        MlsAdminKind.ROLE -> MlsGroupChangeKind.ROLE_CHANGED
        MlsAdminKind.REMOVE -> MlsGroupChangeKind.REMOVED
        MlsAdminKind.BAN -> MlsGroupChangeKind.BANNED
        else -> null
    }

    private val COMMIT_KINDS = setOf(MlsGroupPostKind.MEMBERSHIP, MlsGroupPostKind.SETTINGS, MlsGroupPostKind.VOID)
}
