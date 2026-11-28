// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/*
 * The state the MLS group session publishes. Everything is in memory only: nothing here is written to storage (S1, S4). Types
 * that hold names, ids, texts or links print `<redacted>`.
 */

/** A failed or unavailable call as the screens need it: a typed code, an optional number, or the unavailable result. */
data class MlsFailure(val code: String?, val detail: Long? = null, val retryAfterSecs: Long? = null, val unavailable: Boolean = false) {
    override fun toString() = "MlsFailure(code=$code, unavailable=$unavailable)"

    companion object {
        fun of(problem: ClientProblem) = MlsFailure(problem.statusName ?: problem.check, problem.detail, problem.retryAfterSecs)
        val UNAVAILABLE = MlsFailure(null, unavailable = true)
    }
}

data class MlsGroupListState(
    val rows: List<MlsGroup> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val failure: MlsFailure? = null,
    val ticket: Long = 0,
) {
    override fun toString() = "MlsGroupListState(<redacted>)"
}

/** A join request waiting for approval, kept in memory per `(account, request_id)`. The link is never part of it. */
data class MlsPendingJoinRecord(
    val requestId: String,
    val groupId: String?,
    val reason: MlsGroupPendingReason,
    val expiresAt: Long?,
    val attempt: Int,
) {
    override fun toString() = "MlsPendingJoinRecord(<redacted>)"
}

/** The outcome of one join attempt. */
sealed interface MlsJoinOutcome {
    data class Joined(val groupId: String) : MlsJoinOutcome {
        override fun toString() = "Joined(<redacted>)"
    }

    data class Pending(val record: MlsPendingJoinRecord) : MlsJoinOutcome {
        override fun toString() = "Pending(<redacted>)"
    }

    data class Refused(val refusal: MlsGroupJoin.Refused) : MlsJoinOutcome

    data class Failed(val failure: MlsFailure) : MlsJoinOutcome
}

/**
 * The join owner's state. [attempt] counts taps and sheet sessions; [outcome] is the outcome of the current attempt only, so a
 * late result of an older attempt is recorded (a joined group or a pending record is never lost) but never shown as this
 * attempt's outcome. [inFlight] is true while the one call of the account runs, whichever attempt started it.
 */
data class MlsJoinState(
    val attempt: Int = 0,
    val inFlight: Boolean = false,
    val outcome: MlsJoinOutcome? = null,
    val pending: List<MlsPendingJoinRecord> = emptyList(),
)

enum class MlsNameError { REQUIRED, TOO_LONG }

data class MlsCreateState(
    val creating: Boolean = false,
    val nameError: MlsNameError? = null,
    val failure: MlsFailure? = null,
    val created: MlsGroupCreated? = null,
    val attempt: Int = 0,
) {
    override fun toString() = "MlsCreateState(<redacted>)"
}

data class MlsInfoState(
    val group: MlsGroup? = null,
    val loading: Boolean = false,
    val failure: MlsFailure? = null,
    val notMember: Boolean = false,
    /** `group.leave` is running; the group is read-only only after it answers `committed` (S6). */
    val leaving: Boolean = false,
    val leaveFailure: MlsFailure? = null,
) {
    override fun toString() = "MlsInfoState(<redacted>)"
}

data class MlsInvitesState(
    val invites: List<MlsGroupInvite> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val creating: Boolean = false,
    /** The invite `group.create` or `group.invite.create` just returned: the only place that knows `usesLeft`. */
    val fresh: MlsGroupInvite? = null,
    val revoking: Set<String> = emptySet(),
    val failure: MlsFailure? = null,
) {
    override fun toString() = "MlsInvitesState(<redacted>)"
}

data class MlsMembersState(
    val rows: List<MlsGroupMember> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** A failed page: the error row with "Try again"; nothing loads again by itself. */
    val failure: MlsFailure? = null,
    /** Pages read while a search loads the remaining ones. */
    val searchPages: Int = 0,
) {
    override fun toString() = "MlsMembersState(<redacted>)"
}

data class MlsTimelineState(
    val window: MlsTimelineWindow = MlsTimelineWindow(),
    val locals: List<MlsLocalSend> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val failure: MlsFailure? = null,
    val readOnly: MlsReadOnly? = null,
    /** Post ids whose delete call is running or queued: they show "Deleting…". */
    val deleting: Set<String> = emptySet(),
    val deleteFailure: MlsFailure? = null,
    /** The composer is off until this unix-ms time (rate limit with `remaining == 0`). */
    val limitedUntil: Long? = null,
) {
    override fun toString() = "MlsTimelineState(<redacted>)"
}

/** Everything the session knows of one group. [timelineOrder] is for the LRU of three kept windows. */
internal data class MlsGroupData(
    val info: MlsInfoState = MlsInfoState(),
    val invites: MlsInvitesState = MlsInvitesState(),
    val members: MlsMembersState = MlsMembersState(),
    val timeline: MlsTimelineState = MlsTimelineState(),
    val aiAnchors: Set<String> = emptySet(),
    val gapRunning: Boolean = false,
    val gapQueued: Boolean = false,
    val lastRefreshAt: Long = 0,
    val lastMarkedRead: Long = 0,
    val markingRead: Boolean = false,
    val infoRefreshAt: Long = 0,
    val infoRefreshQueued: Boolean = false,
)

internal data class MlsAccountData(
    val list: MlsGroupListState = MlsGroupListState(),
    val join: MlsJoinState = MlsJoinState(),
    val create: MlsCreateState = MlsCreateState(),
    val groups: Map<String, MlsGroupData> = emptyMap(),
    /** Most recently opened first. */
    val opened: List<String> = emptyList(),
)

internal data class MlsSessionData(
    val accounts: Map<String, MlsAccountData> = emptyMap(),
    /** An account is purged between `clearAccount` and `admit`: late results are dropped. */
    val purged: Set<String> = emptySet(),
    val epochs: Map<String, Long> = emptyMap(),
)
