// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import org.ramflux.data.MlsAdminRules
import org.ramflux.data.MlsGroupAuthor
import org.ramflux.data.MlsGroupChangeKind
import org.ramflux.data.MlsGroupPost
import org.ramflux.data.MlsGroupPostKind
import org.ramflux.data.MlsGroupPostState
import org.ramflux.data.MlsGroupQueuedReason
import org.ramflux.data.MlsGroupWarning
import org.ramflux.data.MlsLine
import org.ramflux.data.MlsLineName
import org.ramflux.data.MlsLines
import org.ramflux.data.MlsLocalPhase
import org.ramflux.data.MlsLocalSend
import org.ramflux.data.MlsSendFailure
import org.ramflux.data.MlsTimelineState
import org.ramflux.designsystem.UiText

/** Maps the session's timeline of one MLS group to the shared chat screen's rows. Pure; nothing here logs. */
internal object MlsGroupChatMapping {
    private const val MILLIS = 1000L
    private const val PREVIEW = 140
    const val POSTED = "l"
    const val LOCAL = "u"

    /** The placeholder row of an `undecryptable` warning, followed by its `from_log_seq`. */
    const val PLACEHOLDER = "x"

    /**
     * The rows of the timeline. [voided] are the commits a void row undid (the session's record plus the void rows of the window):
     * their service rows carry the "Undone" mark. [undecryptable] adds one placeholder row at its `from_log_seq`, only once the
     * window covers that position (the older pages load by the M2 paging path).
     */
    fun rows(
        timeline: MlsTimelineState,
        voided: Set<Long> = emptySet(),
        undecryptable: MlsGroupWarning.Undecryptable? = null
    ): List<TimelineItem> {
        val window = timeline.window
        val undone = voided + MlsAdminRules.voidedBy(window)
        val out = ArrayList<TimelineItem>(window.posts.size + timeline.locals.size + 2)
        var placeholder = undecryptable?.takeIf { it.count > 0 && it.fromLogSeq != null && coversPosition(window, it.fromLogSeq ?: 0L) }

        // Rows go in log order; the placeholder goes before the first row at or after its position.
        fun push(item: TimelineItem, logSeq: Long?) {
            val ph = placeholder
            if (ph != null && logSeq != null && logSeq >= (ph.fromLogSeq ?: Long.MAX_VALUE)) {
                out += placeholderRow(ph)
                placeholder = null
            }
            out += item
        }
        if (window.reachedFloor && !window.hasOlder && window.posts.isNotEmpty()) out += service("floor", GroupLine.Floor, null, undone)
        val byPost = window.posts.associateBy { it.postId }
        MlsLines.collapse(window.posts).forEach { line ->
            when (line) {
                is MlsLine.Row -> push(row(line.post, timeline, byPost, undone), line.post.logSeq)
                is MlsLine.Group -> push(
                    service(
                        "g" + line.logSeq + ":" + line.kind,
                        if (line.kind == MlsGroupChangeKind.JOINED) {
                            GroupLine.Joined(name(line.names.first()), line.count - 1)
                        } else {
                            GroupLine.Left(name(line.names.first()), line.count - 1)
                        },
                        line.logSeq,
                        undone
                    ),
                    line.logSeq
                )
                is MlsLine.Change -> {
                    val target = GroupName(line.change.display, line.change.isMe)
                    val actor = line.actor?.let { GroupName(it.display, it.isMe) }
                    val id = "c" + line.logSeq + ":" + line.change.anchor
                    push(
                        service(
                            id,
                            when (line.change.change) {
                                MlsGroupChangeKind.REMOVED -> GroupLine.Removed(actor, target)
                                MlsGroupChangeKind.BANNED -> GroupLine.Banned(actor, target)
                                else -> GroupLine.Role(actor, target, line.change.role == org.ramflux.data.MlsGroupRole.ADMIN)
                            },
                            line.logSeq,
                            undone
                        ),
                        line.logSeq
                    )
                }
                is MlsLine.Settings -> {
                    val actor = line.actor?.let { GroupName(it.display, it.isMe) }
                    var any = false
                    line.name?.let {
                        any = true
                        push(service("s" + line.logSeq + ":name", GroupLine.Renamed(actor, it), line.logSeq, undone), line.logSeq)
                    }
                    line.approval?.let {
                        any = true
                        push(service("s" + line.logSeq + ":approval", GroupLine.Approval(actor, it), line.logSeq, undone), line.logSeq)
                    }
                    if (!any) push(service("s" + line.logSeq, GroupLine.Updated, line.logSeq, undone), line.logSeq)
                }
            }
        }
        placeholder?.let { out += placeholderRow(it) }
        timeline.locals.sortedBy { it.createdAt }.forEach { out += local(it) }
        return out
    }

    /** True when the loaded window reaches [logSeq]: it starts at or before it, or nothing older exists. */
    private fun coversPosition(window: org.ramflux.data.MlsTimelineWindow, logSeq: Long): Boolean {
        val oldest = window.oldest ?: return false
        if (window.trimmedNewest || window.gapAfter != null) return oldest <= logSeq && logSeq <= window.newest
        return oldest <= logSeq || !window.hasOlder
    }

    private fun placeholderRow(warning: MlsGroupWarning.Undecryptable): MessageItem {
        val from = warning.fromLogSeq ?: 0L
        val count = warning.count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return service(PLACEHOLDER + from, GroupLine.Undecryptable(count), from, emptySet())
    }

    /** The id of the first service row at [logSeq] (the head commit's row), or null when it is not drawn. */
    fun rowIdAt(rows: List<TimelineItem>, logSeq: Long): String? = rows.firstOrNull {
        it is MessageItem && it.service && it.line !is GroupLine.VoidBy && it.line !is GroupLine.Void &&
            it.line !is GroupLine.Undecryptable && slotOf(it.id) == logSeq
    }?.id

    /** The group log position a service row id names (`c250:anchor`, `s250:name`, `g250:JOINED`, `l251`), or null. */
    fun slotOf(rowId: String): Long? = rowId.drop(1).takeWhile { it.isDigit() }.toLongOrNull()

    private fun name(value: MlsLineName) = GroupName(value.display, value.isMe)

    private fun service(id: String, line: GroupLine, logSeq: Long?, voided: Set<Long>) = MessageItem(
        id = id, sender = "", own = false, deleted = false, content = UiText.Empty, sentAt = null, service = true, line = line,
        undone = logSeq != null && logSeq in voided
    )

    private fun row(post: MlsGroupPost, timeline: MlsTimelineState, byPost: Map<String, MlsGroupPost>, voided: Set<Long>): MessageItem {
        val id = POSTED + post.logSeq
        when (post.kind) {
            MlsGroupPostKind.VOID -> return service(
                id,
                post.by?.let { GroupLine.VoidBy(GroupName(it.display, it.isMe)) } ?: GroupLine.Void,
                post.logSeq,
                voided
            )
            MlsGroupPostKind.ATTACHMENT, MlsGroupPostKind.CARD -> return service(id, GroupLine.Unsupported, post.logSeq, voided)
            MlsGroupPostKind.UNKNOWN, MlsGroupPostKind.MEMBERSHIP, MlsGroupPostKind.SETTINGS -> return service(
                id,
                GroupLine.Updated,
                post.logSeq,
                voided
            )
            MlsGroupPostKind.TEXT -> Unit
        }
        val author: MlsGroupAuthor? = post.author
        val deleted = post.state == MlsGroupPostState.Deleted
        val own = author?.isMe == true
        val deleting = post.postId in timeline.deleting
        return MessageItem(
            id = id,
            sender = if (own) "" else author?.display.orEmpty(),
            own = own,
            deleted = deleted,
            content = if (deleted) {
                UiText.Resource(
                    org.ramflux.feature.chat.impl.R.string.group_timeline_deleted
                )
            } else {
                UiText.Data(post.text.orEmpty())
            },
            sentAt = post.appendedAt?.let { it / MILLIS },
            replyPreview = post.replyTo?.let { byPost[it]?.text }?.takeIf { it.isNotBlank() }?.take(PREVIEW)
                ?.let { UiText.Untrusted(it, org.ramflux.designsystem.UntrustedKind.Excerpt) },
            senderAi = author?.aiFlag == true,
            groupDelivery = if (deleting) GroupDelivery(GroupDeliveryKind.DELETING, GroupReason.DELETING) else null,
            deletable = own && !deleted && !deleting && post.logSeq != null
        )
    }

    private fun local(send: MlsLocalSend): MessageItem {
        val delivery: LocalDelivery?
        val state: GroupDelivery?
        when (send.phase) {
            MlsLocalPhase.SUBMITTING -> {
                delivery = LocalDelivery.SENDING
                state = null
            }
            MlsLocalPhase.QUEUED -> {
                delivery = null
                state = GroupDelivery(
                    GroupDeliveryKind.QUEUED,
                    when (send.queuedReason) {
                        MlsGroupQueuedReason.CATCHING_UP -> GroupReason.CATCHING_UP
                        MlsGroupQueuedReason.HOST_UNREACHABLE -> GroupReason.HOST_UNREACHABLE
                        MlsGroupQueuedReason.FETCHING_TOKENS -> GroupReason.FETCHING_TOKENS
                        else -> GroupReason.WAITING
                    }
                )
            }
            MlsLocalPhase.DELAYED -> {
                delivery = null
                state = GroupDelivery(GroupDeliveryKind.DELAYED, GroupReason.DELAYED, send.sendAfter)
            }
            MlsLocalPhase.NOT_SENT -> {
                delivery = LocalDelivery.FAILED
                state = notSent(send.failure)
            }
        }
        return MessageItem(
            id = LOCAL + send.localId,
            sender = "",
            own = true,
            deleted = false,
            content = UiText.Data(send.text),
            sentAt = send.createdAt / MILLIS,
            delivery = delivery,
            groupDelivery = state
        )
    }

    /** The typed reason of a refused or failed send; an unknown code is the generic text, never the code. */
    fun notSent(failure: MlsSendFailure?): GroupDelivery = when {
        failure == null -> GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.GENERIC)
        failure.unavailable -> GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.UNAVAILABLE)
        failure.rateLimited -> {
            val quota = failure.quota
            when {
                quota?.firstDay == true -> GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.FIRST_DAY, quota.limit)
                quota?.resetsAt != null -> GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.RATE_LIMITED, quota.resetsAt)
                else -> GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.RATE_LIMITED_NO_RESET)
            }
        }
        else -> GroupDelivery(GroupDeliveryKind.NOT_SENT, reasonOf(failure.code), failure.detail)
    }

    fun reasonOf(code: String?): GroupReason = when (code) {
        "too_large" -> GroupReason.TOO_LARGE
        "not_member" -> GroupReason.NOT_MEMBER
        "rejoin_needed" -> GroupReason.REJOIN_NEEDED
        "host_unreachable" -> GroupReason.HOST_UNREACHABLE
        "paced" -> GroupReason.PACED
        "rate_limited" -> GroupReason.RATE_LIMITED_NO_RESET
        "message_not_found" -> GroupReason.MESSAGE_NOT_FOUND
        "not_author" -> GroupReason.NOT_AUTHOR
        "not_admin", "not_owner" -> GroupReason.NOT_ADMIN
        "void_not_head" -> GroupReason.VOID_NOT_HEAD
        else -> GroupReason.GENERIC
    }
}
