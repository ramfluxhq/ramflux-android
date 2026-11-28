// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/*
 * The MLS groups module of the app (contract: task/MLS-GUI-M2-common.md as changed by MLS-GUI-M2-r4-deltas.md). It lives beside
 * the frozen old group module and shares nothing with it. Models mirror the SDK readers; no SDK type crosses this file.
 * Every model that holds a link, name, anchor, id or text prints `<redacted>`.
 */

enum class MlsGroupRole { OWNER, ADMIN, MEMBER, UNKNOWN }

enum class MlsGroupPush { MENTIONS_REPLIES, ALL, NONE, UNKNOWN }

enum class MlsGroupCatchUp { IDLE, RUNNING, REJOINING }

/** The author of a post or a roster row. [display] is null for a non-contact: screens show "Member", never the anchor. */
data class MlsGroupAuthor(val anchor: String, val display: String?, val aiFlag: Boolean, val isMe: Boolean) {
    override fun toString() = "MlsGroupAuthor(<redacted>)"
}

data class MlsGroupQuota(val limit: Long, val remaining: Long, val firstDay: Boolean, val resetsAt: Long?)

data class MlsGroupWarning(val code: String, val hostNode: String?)

data class MlsGroupLastPost(val logSeq: Long?, val kind: MlsGroupPostKind, val author: MlsGroupAuthor?, val snippet: String?, val appendedAt: Long?) {
    override fun toString() = "MlsGroupLastPost(<redacted>)"
}

/** One group of the list or the info read. Quota, privacy delay and catch-up are parsed for M3 and not shown in M2. */
data class MlsGroup(
    val groupId: String,
    val name: String,
    val members: Long,
    val agents: Long,
    val approval: Boolean,
    val myRole: MlsGroupRole,
    val hostNode: String?,
    val hostIpVisible: Boolean,
    val push: MlsGroupPush,
    val catchUp: MlsGroupCatchUp,
    val catchUpDone: Long,
    val catchUpTotal: Long,
    val privacyDelayArmed: Boolean,
    val privacySendAfter: Long?,
    val quotaPost: MlsGroupQuota?,
    val quotaCommit: MlsGroupQuota?,
    val lastLogSeq: Long,
    val lastActivityAt: Long?,
    val unread: Long,
    val unreadMentions: Long,
    val readLogSeq: Long,
    val lastPost: MlsGroupLastPost?,
) {
    override fun toString() = "MlsGroup(<redacted>)"
}

data class MlsGroupPage<T>(val items: List<T>, val cursor: String?) {
    override fun toString() = "MlsGroupPage(<redacted>)"
}

/** One invite of this account. [usesLeft] is known only right after create; the link is held in memory and never stored. */
data class MlsGroupInvite(
    val inviteId: String,
    val link: String,
    val maxUses: Long?,
    val usesLeft: Long?,
    val expiresAt: Long?,
    val revoked: Boolean,
) {
    override fun toString() = "MlsGroupInvite(link=<redacted>)"
}

data class MlsGroupCreated(val groupId: String, val invite: MlsGroupInvite) {
    override fun toString() = "MlsGroupCreated(<redacted>)"
}

data class MlsGroupMember(
    val anchor: String,
    val display: String?,
    val role: MlsGroupRole,
    val aiFlag: Boolean,
    val devices: Long,
    val isMe: Boolean,
) {
    override fun toString() = "MlsGroupMember(<redacted>)"
}

enum class MlsGroupRefusal {
    LINK_INVALID, INVITE_EXPIRED, INVITE_INVALID, GROUP_FULL, QUEUE_FULL, REQUEST_EXPIRED, ALREADY_MEMBER, RATE_LIMITED, UNKNOWN
}

enum class MlsGroupPendingReason { AWAITING_ADMIN, AWAITING_MEMBER_ONLINE, UNKNOWN }

/** The result of `group.join`. */
sealed interface MlsGroupJoin {
    data class Joined(val groupId: String) : MlsGroupJoin {
        override fun toString() = "Joined(<redacted>)"
    }

    data class Pending(val requestId: String, val groupId: String?, val reason: MlsGroupPendingReason, val expiresAt: Long?) : MlsGroupJoin {
        override fun toString() = "Pending(<redacted>)"
    }

    data class Refused(
        val reason: MlsGroupRefusal,
        val expiredAt: Long? = null,
        val leafCap: Long? = null,
        val retryAfterSecs: Long? = null,
        val groupId: String? = null,
        /** True when the app refused the link itself and no call was made. */
        val local: Boolean = false,
    ) : MlsGroupJoin {
        override fun toString() = "Refused(reason=$reason, local=$local)"
    }
}

/** A pending join record as `group.join.pending` returns it. The link is never part of it. */
data class MlsGroupPending(
    val requestId: String,
    val groupId: String?,
    val reason: MlsGroupPendingReason,
    val expiresAt: Long?,
) {
    override fun toString() = "MlsGroupPending(<redacted>)"
}

enum class MlsGroupPostKind { TEXT, ATTACHMENT, CARD, MEMBERSHIP, SETTINGS, VOID, UNKNOWN }

enum class MlsGroupChangeKind { JOINED, LEFT, REMOVED, BANNED, ROLE_CHANGED, DEVICE_ADDED, DEVICE_REMOVED, UNKNOWN }

enum class MlsGroupQueuedReason { CATCHING_UP, HOST_UNREACHABLE, FETCHING_TOKENS, UNKNOWN }

/** The state of a post (history row, send result or `post_state` event). */
sealed interface MlsGroupPostState {
    data class Sent(val logSeq: Long?) : MlsGroupPostState
    data class Queued(val reason: MlsGroupQueuedReason) : MlsGroupPostState
    data class Delayed(val sendAfter: Long?) : MlsGroupPostState
    data class RateLimited(val quota: MlsGroupQuota?) : MlsGroupPostState
    data class Failed(val reason: String?) : MlsGroupPostState
    data object Deleted : MlsGroupPostState
    data class Unknown(val raw: String) : MlsGroupPostState
}

data class MlsGroupChange(
    val change: MlsGroupChangeKind,
    val anchor: String,
    val display: String?,
    val aiFlag: Boolean,
    val role: MlsGroupRole?,
    val isMe: Boolean,
) {
    override fun toString() = "MlsGroupChange(change=$change)"
}

/** One history row, or the row an event carries. Service rows have no [author]; [by] names the actor (r4 item 11). */
data class MlsGroupPost(
    val logSeq: Long?,
    val postId: String,
    val author: MlsGroupAuthor?,
    val appendedAt: Long?,
    val kind: MlsGroupPostKind,
    val text: String?,
    val replyTo: String?,
    val mentions: List<String>,
    val state: MlsGroupPostState,
    val changes: List<MlsGroupChange>,
    val settingsName: String?,
    val settingsApproval: Boolean?,
    val by: MlsGroupAuthor?,
) {
    override fun toString() = "MlsGroupPost(<redacted>)"
}

data class MlsGroupHistory(val posts: List<MlsGroupPost>, val floorLogSeq: Long, val lastLogSeq: Long, val more: Boolean) {
    override fun toString() = "MlsGroupHistory(<redacted>)"
}

/** `group.send` and `group.message.delete` results: the post and its state. */
data class MlsGroupSend(val postId: String, val state: MlsGroupPostState) {
    override fun toString() = "MlsGroupSend(<redacted>)"
}

data class MlsGroupRead(val readLogSeq: Long, val unread: Long, val unreadMentions: Long)

/** One live signal of the `group.event` topic. M2 kinds only; the others are skipped by the repository. */
sealed interface MlsGroupSignal {
    val groupId: String?

    data class Post(override val groupId: String?, val post: MlsGroupPost) : MlsGroupSignal {
        override fun toString() = "Post(<redacted>)"
    }

    data class PostState(override val groupId: String?, val postId: String, val state: MlsGroupPostState, val logSeq: Long?) : MlsGroupSignal {
        override fun toString() = "PostState(<redacted>)"
    }

    data class PostDeleted(override val groupId: String?, val postId: String, val postLogSeq: Long?, val logSeq: Long?) : MlsGroupSignal {
        override fun toString() = "PostDeleted(<redacted>)"
    }

    /** A `membership` or `settings` row. */
    data class Service(override val groupId: String?, val post: MlsGroupPost) : MlsGroupSignal {
        override fun toString() = "Service(<redacted>)"
    }

    data class JoinResult(override val groupId: String?, val requestId: String, val joined: Boolean, val refusal: MlsGroupJoin.Refused?) : MlsGroupSignal {
        override fun toString() = "JoinResult(<redacted>)"
    }

    data class Read(override val groupId: String?, val read: MlsGroupRead) : MlsGroupSignal {
        override fun toString() = "Read(<redacted>)"
    }

    /** The subscription lost events: re-read what is on screen. */
    data object Resync : MlsGroupSignal {
        override val groupId: String? = null
    }
}

/**
 * The MLS groups calls, one suspend function per method. Every call is made once per explicit user action and is never
 * retried here or by the session. Until the pin batch (W) the default implementation answers every call with the typed
 * unavailable result.
 */
interface MlsGroupRepository {
    /** `group.list`. */
    suspend fun list(account: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroup>>

    /** `group.info`. */
    suspend fun info(account: String, groupId: String): ClientResult<MlsGroup>

    /** `group.create` with tier 1000. */
    suspend fun create(account: String, name: String, approval: Boolean): ClientResult<MlsGroupCreated>

    /** `group.invite.create`. */
    suspend fun createInvite(account: String, groupId: String, maxUses: Int, ttlSecs: Long): ClientResult<MlsGroupInvite>

    /** `group.invite.list`: the invites this account created. */
    suspend fun invites(account: String, groupId: String): ClientResult<List<MlsGroupInvite>>

    /** `group.invite.revoke`. */
    suspend fun revokeInvite(account: String, groupId: String, inviteId: String): ClientResult<Unit>

    /** `group.join` with [link] passed verbatim (S2: the session has checked it). */
    suspend fun join(account: String, link: String): ClientResult<MlsGroupJoin>

    /** `group.join.pending`. */
    suspend fun pendingJoins(account: String): ClientResult<List<MlsGroupPending>>

    /** `group.members`. */
    suspend fun members(account: String, groupId: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroupMember>>

    /** `group.history`: newest page when both are null. */
    suspend fun history(account: String, groupId: String, afterLogSeq: Long?, beforeLogSeq: Long?, limit: Int): ClientResult<MlsGroupHistory>

    /** `group.send`, text only. */
    suspend fun send(account: String, groupId: String, text: String, replyTo: String?, mentions: List<String>): ClientResult<MlsGroupSend>

    /** `group.refresh`: fetch from the host now; the results arrive as events. */
    suspend fun refresh(account: String, groupId: String): ClientResult<Unit>

    /** `group.mark_read`. */
    suspend fun markRead(account: String, groupId: String, logSeq: Long): ClientResult<MlsGroupRead>

    /** `group.message.delete` for an own post. */
    suspend fun delete(account: String, groupId: String, postId: String): ClientResult<MlsGroupSend>

    /** `group.leave`. */
    suspend fun leave(account: String, groupId: String): ClientResult<Unit>

    /** The `group.event` topic of [account]. */
    fun events(account: String): Flow<MlsGroupSignal> = emptyFlow()
}

/** For hosts that do not drive MLS groups: every call is the typed unavailable result. */
object UnavailableMlsGroups : MlsGroupRepository {
    private fun <T> unavailable(): ClientResult<T> = ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun list(account: String, cursor: String?) = unavailable<MlsGroupPage<MlsGroup>>()
    override suspend fun info(account: String, groupId: String) = unavailable<MlsGroup>()
    override suspend fun create(account: String, name: String, approval: Boolean) = unavailable<MlsGroupCreated>()
    override suspend fun createInvite(account: String, groupId: String, maxUses: Int, ttlSecs: Long) = unavailable<MlsGroupInvite>()
    override suspend fun invites(account: String, groupId: String) = unavailable<List<MlsGroupInvite>>()
    override suspend fun revokeInvite(account: String, groupId: String, inviteId: String) = unavailable<Unit>()
    override suspend fun join(account: String, link: String) = unavailable<MlsGroupJoin>()
    override suspend fun pendingJoins(account: String) = unavailable<List<MlsGroupPending>>()
    override suspend fun members(account: String, groupId: String, cursor: String?) = unavailable<MlsGroupPage<MlsGroupMember>>()
    override suspend fun history(account: String, groupId: String, afterLogSeq: Long?, beforeLogSeq: Long?, limit: Int) = unavailable<MlsGroupHistory>()
    override suspend fun send(account: String, groupId: String, text: String, replyTo: String?, mentions: List<String>) = unavailable<MlsGroupSend>()
    override suspend fun refresh(account: String, groupId: String) = unavailable<Unit>()
    override suspend fun markRead(account: String, groupId: String, logSeq: Long) = unavailable<MlsGroupRead>()
    override suspend fun delete(account: String, groupId: String, postId: String) = unavailable<MlsGroupSend>()
    override suspend fun leave(account: String, groupId: String) = unavailable<Unit>()
}
