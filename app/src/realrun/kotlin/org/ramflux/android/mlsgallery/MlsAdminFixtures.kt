// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import org.ramflux.android.R
import org.ramflux.data.MlsGroup
import org.ramflux.data.MlsGroupChange
import org.ramflux.data.MlsGroupChangeKind
import org.ramflux.data.MlsGroupMember
import org.ramflux.data.MlsGroupPost
import org.ramflux.data.MlsGroupPostKind
import org.ramflux.data.MlsGroupPostState
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsJoinRequest

/*
 * The data of the "Admin (M3)" fixtures F3-1 to F3-14 (common section 6). Names are neutral; the host is `node.example`; request
 * ids carry the prefix `rq-` and anchors `a-`. The variants of a fixture are bound to their own group, so a runner needs no
 * toggle: it opens the group from the groups list.
 */

internal const val G_QUEUE = "g-queue"
internal const val G_OPEN = "g-open"
internal const val G_LATE = "g-latecatch"
internal const val G_CATCH0 = "g-catch0"
internal const val G_STALE = "g-stale"

private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

internal val ADMIN_FIXTURES: List<MlsGroupFixture> = listOf(
    MlsGroupFixture("F3-1", R.string.mls_group_f3_1, G_DESIGN),
    MlsGroupFixture("F3-2", R.string.mls_group_f3_2, G_QUEUE),
    MlsGroupFixture("F3-3", R.string.mls_group_f3_3, G_DESIGN),
    MlsGroupFixture("F3-4", R.string.mls_group_f3_4, G_BOOK),
    MlsGroupFixture("F3-5", R.string.mls_group_f3_5, G_DESIGN),
    MlsGroupFixture("F3-6", R.string.mls_group_f3_6, G_DESIGN),
    MlsGroupFixture("F3-7", R.string.mls_group_f3_7, G_BOOK),
    MlsGroupFixture("F3-8", R.string.mls_group_f3_8, G_DESIGN),
    MlsGroupFixture("F3-9", R.string.mls_group_f3_9, G_DESIGN),
    MlsGroupFixture("F3-10", R.string.mls_group_f3_10, G_DESIGN),
    MlsGroupFixture("F3-11", R.string.mls_group_f3_11, G_BOOK),
    MlsGroupFixture("F3-12", R.string.mls_group_f3_12, G_DESIGN),
    MlsGroupFixture("F3-13", R.string.mls_group_f3_13, G_OPEN),
    MlsGroupFixture("F3-14", R.string.mls_group_f3_14, G_DESIGN)
)

/** The groups every M3 fixture lists next to the three M2 groups; each variant of F3-2, F3-7, F3-9, F3-10 and F3-13 lives in one. */
internal fun adminGroups(): List<MlsGroup> = listOf(
    group(G_QUEUE, "Queue", MlsGroupRole.OWNER, 12, activity = NOW_BASE - 3 * MINUTE, approval = true),
    group(G_OPEN, "Open house", MlsGroupRole.ADMIN, 9, activity = NOW_BASE - 4 * MINUTE, approval = false),
    group(G_LATE, "Late catch-up", MlsGroupRole.ADMIN, 3, activity = NOW_BASE - 5 * MINUTE, catchUp = true),
    group(G_CATCH0, "No total", MlsGroupRole.ADMIN, 3, activity = NOW_BASE - 6 * MINUTE, catchUp = true),
    group(G_STALE, "Stale head", MlsGroupRole.ADMIN, 5, activity = NOW_BASE - 7 * MINUTE),
    group(G_LARGE, "Large group", MlsGroupRole.OWNER, 1000, activity = NOW_BASE - 8 * MINUTE)
)

/** A join request of F3-1 and F3-2. Ids are `rq-<key>`, anchors `a-req-<key>`; a null [name] is a non-contact. */
private fun request(key: String, name: String?, order: Int, ai: Boolean = false, expires: Long = NOW_BASE + DAY) =
    MlsJoinRequest("rq-$key", "a-req-$key", name, ai, NOW_BASE - (100 - order) * MINUTE, expires)

/** The seven requests of F3-1, oldest first. Dana's expiry is set by the fixture when the list is first read. */
internal fun f31Requests(): List<MlsJoinRequest> = listOf(
    request("ana", "Ana", 1),
    request("ben", "Ben", 2),
    request("chen", "Chen", 3),
    request("dana", "Dana", 4),
    request("eve", "Eve", 5),
    request("scout", "Scout", 6, ai = true),
    request("anon", null, 7)
)

/** Fay arrives as a `pending_join` event. */
internal fun fayRequest() = request("fay", "Fay", 8)

/** F3-2: 230 requests, nameless ones every 17th. */
internal fun queueRequests(): List<MlsJoinRequest> = (1..230).map { n ->
    request("q%03d".format(n), if (n % 17 == 0) null else "Person %d".format(n), n % 100, ai = n % 50 == 0)
}

/** The roster of the M3 groups where members are acted on: the member list, with this device as "Sam". */
internal fun rosterOf(group: String): List<MlsGroupMember>? = when (group) {
    G_DESIGN -> listOf(
        MlsGroupMember("a-me", "Sam", MlsGroupRole.OWNER, false, 2, true),
        MlsGroupMember("a-chen", "Chen", MlsGroupRole.ADMIN, false, 1, false),
        MlsGroupMember("a-ben", "Ben", MlsGroupRole.MEMBER, false, 1, false),
        MlsGroupMember("a-dana", "Dana", MlsGroupRole.MEMBER, false, 2, false),
        MlsGroupMember("a-eve", "Eve", MlsGroupRole.MEMBER, false, 1, false),
        MlsGroupMember("a-fay", "Fay", MlsGroupRole.MEMBER, false, 1, false),
        MlsGroupMember("a-scout", "Scout", MlsGroupRole.MEMBER, true, 1, false),
        MlsGroupMember("a-anon", null, MlsGroupRole.MEMBER, false, 1, false)
    )
    G_BOOK -> listOf(
        MlsGroupMember("a-hana", "Hana", MlsGroupRole.OWNER, false, 1, false),
        MlsGroupMember("a-me", "Sam", MlsGroupRole.ADMIN, false, 2, true),
        MlsGroupMember("a-gus", "Gus", MlsGroupRole.ADMIN, false, 1, false),
        MlsGroupMember("a-ana", "Ana", MlsGroupRole.MEMBER, false, 1, false),
        MlsGroupMember("a-ben", "Ben", MlsGroupRole.MEMBER, false, 1, false),
        MlsGroupMember("a-chen", "Chen", MlsGroupRole.MEMBER, false, 1, false)
    )
    else -> null
}

internal fun commitChange(kind: MlsGroupChangeKind, anchor: String, display: String?, role: MlsGroupRole? = null, me: Boolean = false) =
    MlsGroupChange(kind, anchor, display, false, role, me)

/** A history row the M3 fixtures put at a fixed position: the head commit of F3-10 and the older membership row. */
internal fun headRows(seq: Long): MlsGroupPost? = when (seq) {
    240L -> membership(seq, NOW_BASE - 20 * MINUTE, listOf(commitChange(MlsGroupChangeKind.JOINED, "a-late", "Late joiner")), ANA_A)
    250L -> MlsGroupPost(
        seq, "p$seq", null, NOW_BASE - 10 * MINUTE, MlsGroupPostKind.SETTINGS, null, null, emptyList(), MlsGroupPostState.Sent(seq),
        emptyList(), "Design team", null, ANA_A
    )
    else -> null
}

/** A void row: [by] undid the commit at [voided]. */
internal fun voidPost(seq: Long, voided: Long, at: Long) = MlsGroupPost(
    seq, "p$seq", null, at, MlsGroupPostKind.VOID, null, null, emptyList(), MlsGroupPostState.Sent(seq), emptyList(), null, null, ME,
    voidedLogSeq = voided
)

/** A settings row of the fixture events. */
internal fun settingsPost(seq: Long, at: Long, name: String?, approval: Boolean?) = MlsGroupPost(
    seq, "p$seq", null, at, MlsGroupPostKind.SETTINGS, null, null, emptyList(), MlsGroupPostState.Sent(seq), emptyList(), name, approval, ME
)

/** A membership row of the fixture events, by this member. */
internal fun adminMembership(seq: Long, at: Long, change: MlsGroupChange) = MlsGroupPost(
    seq, "p$seq", null, at, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(),
    MlsGroupPostState.Sent(
        seq
    ),
    listOf(change), null, null, ME
)
