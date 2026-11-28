// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.MlsAuthor
import org.ramflux.sdk.MlsCatchUpState
import org.ramflux.sdk.MlsChangeKind
import org.ramflux.sdk.MlsEventMetadata
import org.ramflux.sdk.MlsGroupInfoMetadata
import org.ramflux.sdk.MlsHistoryMetadata
import org.ramflux.sdk.MlsHostUnusualKind
import org.ramflux.sdk.MlsJoinRequestMetadata
import org.ramflux.sdk.MlsRejoinReason
import org.ramflux.sdk.MlsWarningCode
import org.ramflux.sdk.MlsInviteMetadata
import org.ramflux.sdk.MlsJoinMetadata
import org.ramflux.sdk.MlsJoinRefusal
import org.ramflux.sdk.MlsMemberMetadata
import org.ramflux.sdk.MlsPendingJoinMetadata
import org.ramflux.sdk.MlsPendingReason
import org.ramflux.sdk.MlsPostKind
import org.ramflux.sdk.MlsPostMetadata
import org.ramflux.sdk.MlsPostState
import org.ramflux.sdk.MlsPrivacyDelay
import org.ramflux.sdk.MlsPush
import org.ramflux.sdk.MlsQuota
import org.ramflux.sdk.MlsQueuedReason
import org.ramflux.sdk.MlsReadMetadata
import org.ramflux.sdk.MlsRole
import org.ramflux.sdk.MlsSendMetadata

/** Maps the SDK readers' values to the app models. Pure; nothing here logs. */
internal object MlsGroupMapping {
    const val HOST_IP_VISIBLE = "host_ip_visible"

    fun role(value: MlsRole) = when (value) {
        MlsRole.OWNER -> MlsGroupRole.OWNER
        MlsRole.ADMIN -> MlsGroupRole.ADMIN
        MlsRole.MEMBER -> MlsGroupRole.MEMBER
        MlsRole.UNKNOWN -> MlsGroupRole.UNKNOWN
    }

    fun author(value: MlsAuthor) = MlsGroupAuthor(value.anchor, value.display, value.aiFlag, value.isMe)

    private fun quota(value: MlsQuota?) = value?.let { MlsGroupQuota(it.limit, it.remaining, it.firstDay, it.resetsAt) }

    fun kind(value: MlsPostKind) = when (value) {
        MlsPostKind.TEXT -> MlsGroupPostKind.TEXT
        MlsPostKind.ATTACHMENT -> MlsGroupPostKind.ATTACHMENT
        MlsPostKind.CARD -> MlsGroupPostKind.CARD
        MlsPostKind.MEMBERSHIP -> MlsGroupPostKind.MEMBERSHIP
        MlsPostKind.SETTINGS -> MlsGroupPostKind.SETTINGS
        MlsPostKind.VOID -> MlsGroupPostKind.VOID
        MlsPostKind.UNKNOWN -> MlsGroupPostKind.UNKNOWN
    }

    fun group(value: MlsGroupInfoMetadata) = MlsGroup(
        groupId = value.groupId,
        name = value.name,
        members = value.members,
        agents = value.agents,
        approval = value.approval,
        myRole = role(value.myRole),
        hostNode = value.hostNode,
        hostIpVisible = value.warnings.any { it.code is MlsWarningCode.HostIpVisible && !it.cleared } || value.hostNode != null,
        push = when (value.push) {
            MlsPush.MENTIONS_REPLIES -> MlsGroupPush.MENTIONS_REPLIES
            MlsPush.ALL -> MlsGroupPush.ALL
            MlsPush.NONE -> MlsGroupPush.NONE
            MlsPush.UNKNOWN -> MlsGroupPush.UNKNOWN
        },
        catchUp = when (value.catchUp) {
            MlsCatchUpState.IDLE -> MlsGroupCatchUp.IDLE
            MlsCatchUpState.RUNNING -> MlsGroupCatchUp.RUNNING
            MlsCatchUpState.REJOINING -> MlsGroupCatchUp.REJOINING
        },
        catchUpDone = value.catchUpDone,
        catchUpTotal = value.catchUpTotal,
        privacyDelayArmed = value.privacyDelay != null,
        privacySendAfter = (value.privacyDelay as? MlsPrivacyDelay.Waiting)?.sendAfter,
        quotaPost = quota(value.quotaPost),
        quotaCommit = quota(value.quotaCommit),
        lastLogSeq = value.lastLogSeq,
        tier = value.tier,
        lastActivityAt = value.lastActivityAt,
        unread = value.unread,
        unreadMentions = value.unreadMentions,
        readLogSeq = value.readLogSeq,
        lastPost = value.lastPost?.let { MlsGroupLastPost(it.logSeq, kind(it.kind), it.author?.let(::author), it.snippet, it.appendedAt) },
        warnings = value.warnings.filterNot { it.cleared }.map { warning(it.code) },
    )

    fun warning(code: MlsWarningCode): MlsGroupWarning = when (code) {
        is MlsWarningCode.ForkAlarm -> MlsGroupWarning.ForkAlarm(code.headN)
        is MlsWarningCode.RejoinNeeded -> MlsGroupWarning.RejoinNeeded(
            when (code.reason) {
                MlsRejoinReason.PRUNED -> MlsGroupRejoinReason.PRUNED
                MlsRejoinReason.GATE_REJECTED -> MlsGroupRejoinReason.GATE_REJECTED
                MlsRejoinReason.APPLY_FAILED -> MlsGroupRejoinReason.APPLY_FAILED
                MlsRejoinReason.WELCOME_EXPIRED -> MlsGroupRejoinReason.WELCOME_EXPIRED
                MlsRejoinReason.STATE_LOST -> MlsGroupRejoinReason.STATE_LOST
                MlsRejoinReason.UNKNOWN -> MlsGroupRejoinReason.UNKNOWN
            }
        )
        is MlsWarningCode.Undecryptable -> MlsGroupWarning.Undecryptable(code.count, code.fromLogSeq)
        MlsWarningCode.AdmissionNeedsOnline -> MlsGroupWarning.AdmissionNeedsOnline
        is MlsWarningCode.HostUnusual -> MlsGroupWarning.HostUnusual(
            when (code.kind) {
                MlsHostUnusualKind.LATE_ROLL -> MlsGroupHostUnusual.LATE_ROLL
                MlsHostUnusualKind.LATE_REPLY -> MlsGroupHostUnusual.LATE_REPLY
                MlsHostUnusualKind.UNKNOWN -> MlsGroupHostUnusual.UNKNOWN
            }
        )
        is MlsWarningCode.HeadCommitRejected -> MlsGroupWarning.HeadCommitRejected(code.logSeq)
        is MlsWarningCode.HostIpVisible -> MlsGroupWarning.HostIpVisible(code.hostNode)
        is MlsWarningCode.Unknown -> MlsGroupWarning.Unknown(code.code)
    }

    fun request(value: MlsJoinRequestMetadata) =
        MlsJoinRequest(value.requestId, value.anchor, value.display, value.aiFlag, value.requestedAt, value.expiresAt)

    fun invite(value: MlsInviteMetadata) =
        MlsGroupInvite(value.inviteId, value.link, value.maxUses, value.usesLeft, value.expiresAt, value.revoked)

    fun member(value: MlsMemberMetadata) =
        MlsGroupMember(value.anchor, value.display, role(value.role), value.aiFlag, value.devices, value.isMe)

    private fun pendingReason(value: MlsPendingReason) = when (value) {
        MlsPendingReason.AWAITING_ADMIN -> MlsGroupPendingReason.AWAITING_ADMIN
        MlsPendingReason.AWAITING_MEMBER_ONLINE -> MlsGroupPendingReason.AWAITING_MEMBER_ONLINE
        MlsPendingReason.UNKNOWN -> MlsGroupPendingReason.UNKNOWN
    }

    fun refusal(value: MlsJoinMetadata.Refused) = MlsGroupJoin.Refused(
        when (value.reason) {
            MlsJoinRefusal.LINK_INVALID -> MlsGroupRefusal.LINK_INVALID
            MlsJoinRefusal.INVITE_EXPIRED -> MlsGroupRefusal.INVITE_EXPIRED
            MlsJoinRefusal.INVITE_INVALID -> MlsGroupRefusal.INVITE_INVALID
            MlsJoinRefusal.GROUP_FULL -> MlsGroupRefusal.GROUP_FULL
            MlsJoinRefusal.QUEUE_FULL -> MlsGroupRefusal.QUEUE_FULL
            MlsJoinRefusal.REQUEST_EXPIRED -> MlsGroupRefusal.REQUEST_EXPIRED
            MlsJoinRefusal.ALREADY_MEMBER -> MlsGroupRefusal.ALREADY_MEMBER
            MlsJoinRefusal.RATE_LIMITED -> MlsGroupRefusal.RATE_LIMITED
            MlsJoinRefusal.UNKNOWN -> MlsGroupRefusal.UNKNOWN
        },
        value.expiredAt, value.leafCap, value.retryAfterSecs, value.groupId,
    )

    fun join(value: MlsJoinMetadata): MlsGroupJoin = when (value) {
        is MlsJoinMetadata.Joined -> MlsGroupJoin.Joined(value.groupId)
        is MlsJoinMetadata.Pending -> MlsGroupJoin.Pending(value.requestId, null, pendingReason(value.reason), value.expiresAt)
        is MlsJoinMetadata.Refused -> refusal(value)
    }

    fun pending(value: MlsPendingJoinMetadata) =
        MlsGroupPending(value.requestId, value.groupId, pendingReason(value.reason), value.expiresAt)

    fun state(value: MlsPostState): MlsGroupPostState = when (value) {
        is MlsPostState.Sent -> MlsGroupPostState.Sent(value.logSeq)
        is MlsPostState.Queued -> MlsGroupPostState.Queued(
            when (value.reason) {
                MlsQueuedReason.CATCHING_UP -> MlsGroupQueuedReason.CATCHING_UP
                MlsQueuedReason.HOST_UNREACHABLE -> MlsGroupQueuedReason.HOST_UNREACHABLE
                MlsQueuedReason.FETCHING_TOKENS -> MlsGroupQueuedReason.FETCHING_TOKENS
                MlsQueuedReason.UNKNOWN -> MlsGroupQueuedReason.UNKNOWN
            }
        )
        is MlsPostState.Delayed -> MlsGroupPostState.Delayed(value.sendAfter)
        is MlsPostState.RateLimited -> MlsGroupPostState.RateLimited(quota(value.quota))
        is MlsPostState.Failed -> MlsGroupPostState.Failed(value.reason)
        MlsPostState.Deleted -> MlsGroupPostState.Deleted
        is MlsPostState.Unknown -> MlsGroupPostState.Unknown(value.raw)
    }

    fun post(value: MlsPostMetadata) = MlsGroupPost(
        logSeq = value.logSeq,
        postId = value.postId,
        author = value.author?.let(::author),
        appendedAt = value.appendedAt,
        kind = kind(value.kind),
        text = value.text,
        replyTo = value.replyTo,
        mentions = value.mentions,
        state = state(value.state),
        changes = value.changes.map {
            MlsGroupChange(
                when (it.change) {
                    MlsChangeKind.JOINED -> MlsGroupChangeKind.JOINED
                    MlsChangeKind.LEFT -> MlsGroupChangeKind.LEFT
                    MlsChangeKind.REMOVED -> MlsGroupChangeKind.REMOVED
                    MlsChangeKind.BANNED -> MlsGroupChangeKind.BANNED
                    MlsChangeKind.ROLE_CHANGED -> MlsGroupChangeKind.ROLE_CHANGED
                    MlsChangeKind.DEVICE_ADDED -> MlsGroupChangeKind.DEVICE_ADDED
                    MlsChangeKind.DEVICE_REMOVED -> MlsGroupChangeKind.DEVICE_REMOVED
                    MlsChangeKind.UNKNOWN -> MlsGroupChangeKind.UNKNOWN
                },
                it.anchor, it.display, it.aiFlag, it.role?.let(::role), it.isMe,
            )
        },
        settingsName = value.settings?.name,
        settingsApproval = value.settings?.approval,
        by = value.by?.let(::author),
        voidedLogSeq = value.voidedLogSeq,
    )

    fun history(value: MlsHistoryMetadata) =
        MlsGroupHistory(value.posts.map(::post), value.floorLogSeq, value.lastLogSeq, value.more)

    fun sent(value: MlsSendMetadata) = MlsGroupSend(value.postId, state(value.state))

    fun read(value: MlsReadMetadata) = MlsGroupRead(value.readLogSeq, value.unread, value.unreadMentions)

    /** The signal of one event, or null for a kind this version skips. */
    fun signal(value: MlsEventMetadata): MlsGroupSignal? = when (value) {
        is MlsEventMetadata.Post -> MlsGroupSignal.Post(value.groupId, post(value.post))
        is MlsEventMetadata.PostState -> MlsGroupSignal.PostState(value.groupId, value.postId, state(value.state), value.logSeq)
        is MlsEventMetadata.PostDeleted -> MlsGroupSignal.PostDeleted(value.groupId, value.postId, value.postLogSeq, value.logSeq)
        is MlsEventMetadata.Service -> MlsGroupSignal.Service(value.groupId, post(value.post))
        is MlsEventMetadata.JoinResult -> MlsGroupSignal.JoinResult(value.groupId, value.requestId, value.joined, value.refusal?.let(::refusal))
        is MlsEventMetadata.Read -> MlsGroupSignal.Read(value.groupId, read(value.read))
        is MlsEventMetadata.PendingJoin -> MlsGroupSignal.PendingJoin(value.groupId, request(value.request))
        is MlsEventMetadata.CatchUp -> MlsGroupSignal.CatchUp(
            value.groupId, value.seq,
            when (value.catchUp.state) {
                MlsCatchUpState.IDLE -> MlsGroupCatchUp.IDLE
                MlsCatchUpState.RUNNING -> MlsGroupCatchUp.RUNNING
                MlsCatchUpState.REJOINING -> MlsGroupCatchUp.REJOINING
            },
            value.catchUp.done, value.catchUp.total,
        )
        is MlsEventMetadata.Warning -> MlsGroupSignal.Warning(value.groupId, warning(value.warning.code), value.warning.cleared)
        is MlsEventMetadata.Skipped -> null
    }
}
