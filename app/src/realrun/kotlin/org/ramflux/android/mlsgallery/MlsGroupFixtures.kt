// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.annotation.StringRes
import org.ramflux.android.R
import org.ramflux.data.MlsGroup
import org.ramflux.data.MlsGroupAuthor
import org.ramflux.data.MlsGroupCatchUp
import org.ramflux.data.MlsGroupChange
import org.ramflux.data.MlsGroupChangeKind
import org.ramflux.data.MlsGroupMember
import org.ramflux.data.MlsGroupPost
import org.ramflux.data.MlsGroupPostKind
import org.ramflux.data.MlsGroupPostState
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupRole

/** The groups of the M2 fixtures (common section 6). Names are neutral; the host is `node.example`. */
internal const val G_DESIGN = "g-design"
internal const val G_NEIGHBOURS = "g-neighbours"
internal const val G_BOOK = "g-book"
internal const val G_LARGE = "g-large"
internal const val G_NEW = "g-new"
internal const val HOST = "node.example"

/** One fixture of the "Groups (M2)" gallery section. [group] is the group its chat, info and member views open. */
internal class MlsGroupFixture(val id: String, @param:StringRes val title: Int, val group: String)

internal val GROUP_FIXTURES: List<MlsGroupFixture> = listOf(
    MlsGroupFixture("F2-1", R.string.mls_group_f2_1, G_DESIGN),
    MlsGroupFixture("F2-2", R.string.mls_group_f2_2, G_NEIGHBOURS),
    MlsGroupFixture("F2-3", R.string.mls_group_f2_3, G_DESIGN),
    MlsGroupFixture("F2-4", R.string.mls_group_f2_4, G_DESIGN),
    MlsGroupFixture("F2-5", R.string.mls_group_f2_5, G_DESIGN),
    MlsGroupFixture("F2-6", R.string.mls_group_f2_6, G_NEW),
    MlsGroupFixture("F2-7", R.string.mls_group_f2_7, G_BOOK),
    MlsGroupFixture("F2-8", R.string.mls_group_f2_8, G_DESIGN),
    MlsGroupFixture("F2-9", R.string.mls_group_f2_9, G_LARGE),
    MlsGroupFixture("F2-10", R.string.mls_group_f2_10, G_DESIGN),
    MlsGroupFixture("F2-11", R.string.mls_group_f2_11, G_DESIGN)
)

internal const val NOW_BASE = 1_800_000_000_000L
private const val MINUTE = 60_000L

internal fun author(anchor: String, display: String?, ai: Boolean = false, me: Boolean = false) = MlsGroupAuthor(anchor, display, ai, me)

internal val ME = author("a-me", "Dana", me = true)
internal val ANA_A = author("a-ana", "Ana")
internal val BEN_A = author("a-ben", "Ben")
internal val CHEN_A = author("a-chen", "Chen")
internal val SCOUT_A = author("a-scout", "Scout", ai = true)
internal val NAMELESS = (1..5).map { author("a-anon-$it", null) }

internal fun group(
    id: String,
    name: String,
    role: MlsGroupRole,
    members: Long,
    agents: Long = 0,
    activity: Long,
    approval: Boolean = true,
    catchUp: Boolean = false,
    unread: Long = 0,
    snippet: String? = null
) = MlsGroup(
    groupId = id,
    name = name,
    members = members,
    agents = agents,
    approval = approval,
    myRole = role,
    hostNode = HOST,
    hostIpVisible = true,
    push = MlsGroupPush.MENTIONS_REPLIES,
    catchUp = if (catchUp) MlsGroupCatchUp.RUNNING else MlsGroupCatchUp.IDLE,
    catchUpDone = if (catchUp) 40 else 0,
    catchUpTotal = if (catchUp) 120 else 0,
    privacyDelayArmed = false,
    privacySendAfter = null,
    quotaPost = null,
    quotaCommit = null,
    lastLogSeq = 0,
    lastActivityAt = activity,
    unread = unread,
    unreadMentions = 0,
    readLogSeq = 0,
    lastPost = snippet?.let { org.ramflux.data.MlsGroupLastPost(null, MlsGroupPostKind.TEXT, ANA_A, it, activity) }
)

/** F2-1: 120 groups; the three named groups lead. Group 12 comes again on page 2 with a newer time. */
internal fun listGroups(): List<MlsGroup> {
    val named = listOf(
        group(G_DESIGN, "Design", MlsGroupRole.OWNER, 12, agents = 1, activity = NOW_BASE, unread = 3, snippet = "Ready when you are"),
        group(G_NEIGHBOURS, "Neighbours", MlsGroupRole.MEMBER, 987, activity = NOW_BASE - MINUTE),
        group(G_BOOK, "Book club", MlsGroupRole.ADMIN, 3, activity = NOW_BASE - 2 * MINUTE, catchUp = true)
    )
    val others = (4..120).map { n ->
        group("g-%03d".format(n), "Group %d".format(n), MlsGroupRole.MEMBER, (n % 40 + 2).toLong(), activity = NOW_BASE - n * MINUTE)
    }
    return named + others
}

internal fun page(rows: List<MlsGroup>, from: Int, to: Int) = rows.subList(from, minOf(to, rows.size))

/** F2-2 and F2-9: member rows. Row 0 is the owner, rows 1 to 6 admins; four agents, one own row and 30 nameless rows. */
internal fun member(index: Int, total: Int): MlsGroupMember {
    val names = listOf("Ana", "Ben", "Chen", "Dana", "Eli", "Fay", "Gus", "Hana")
    val role = when {
        index == 0 -> MlsGroupRole.OWNER
        index in 1..6 -> MlsGroupRole.ADMIN
        else -> MlsGroupRole.MEMBER
    }
    val agent = index in setOf(7, 77, 177, total - 5)
    val me = index == 3
    val nameless = index >= 100 && index % 33 == 0 && !agent
    val display = when {
        me -> "Dana"
        agent -> "Scout %d".format(index)
        nameless -> null
        else -> "%s %d".format(names[index % names.size], index)
    }
    return MlsGroupMember("a-m-$index", display, role, agent, (index % 3 + 1).toLong(), me)
}

/** Posts of F2-3 and F2-9: a deterministic row for [seq]. */
internal fun postAt(seq: Long, total: Long): MlsGroupPost {
    val who = when ((seq % 7).toInt()) {
        0 -> ME
        1 -> ANA_A
        2 -> BEN_A
        3 -> SCOUT_A
        4 -> NAMELESS[(seq % 5).toInt()]
        5 -> CHEN_A
        else -> ANA_A
    }
    val at = NOW_BASE - (total - seq) * MINUTE
    val state: MlsGroupPostState = if (seq == 50L) MlsGroupPostState.Deleted else MlsGroupPostState.Sent(seq)
    return when (seq) {
        20L -> membership(seq, at, (1..15).map { change(MlsGroupChangeKind.JOINED, "a-joiner-$it", "Joiner $it") }, ANA_A)
        30L -> MlsGroupPost(
            seq, "p$seq", null, at, MlsGroupPostKind.SETTINGS, null, null, emptyList(),
            MlsGroupPostState.Sent(
                seq
            ),
            emptyList(), "Design team", null, ANA_A
        )
        40L -> MlsGroupPost(
            seq, "p$seq", null, at, MlsGroupPostKind.VOID, null, null, emptyList(),
            MlsGroupPostState.Sent(
                seq
            ),
            emptyList(), null, null, ANA_A
        )
        45L -> MlsGroupPost(
            seq, "p$seq", who, at, MlsGroupPostKind.UNKNOWN, null, null, emptyList(),
            MlsGroupPostState.Sent(
                seq
            ),
            emptyList(), null, null, null
        )
        else -> MlsGroupPost(
            seq, "p$seq", who, at, MlsGroupPostKind.TEXT, if (state == MlsGroupPostState.Deleted) null else "Message $seq",
            null, emptyList(), state, emptyList(), null, null, null
        )
    }
}

internal fun change(kind: MlsGroupChangeKind, anchor: String, display: String?, me: Boolean = false, role: MlsGroupRole? = null) =
    MlsGroupChange(kind, anchor, display, false, role, me)

internal fun membership(seq: Long, at: Long, changes: List<MlsGroupChange>, by: MlsGroupAuthor?) = MlsGroupPost(
    seq, "p$seq", null, at, MlsGroupPostKind.MEMBERSHIP, null, null, emptyList(), MlsGroupPostState.Sent(seq), changes, null, null, by
)
