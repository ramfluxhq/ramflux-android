// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/*
 * Readers of the MLS group methods (RFCORE-144 method table r3) and of the `group.event` topic. They are pure functions over
 * one SDK reply: the reply is read once and wiped. Unknown enum values are kept as UNKNOWN with the raw code; a payload is
 * never logged and every type that holds a link, name, anchor or text prints `<redacted>`.
 *
 * Shapes follow method table r3 as changed by r4 (MLS-GUI-M2-r4-deltas): service rows have no `author` and only `by` names the
 * actor; `settings` has no `push`; `group.read` is `group.refresh`; unread state and `last_post` ride on the group rows; own
 * unsent rows are `state: pending` with a `send{state, params}`. The event frame carries the payload in `body` (the frame
 * root is the fallback), a `post` event carries the history row under `post`, and `membership` and `settings` events carry
 * their body under `body`. Where r4 does not give a wire position (the `post` event nesting, the `body` nesting) the reader
 * assumes the nesting above.
 */

enum class MlsRole { OWNER, ADMIN, MEMBER, UNKNOWN }

enum class MlsPush { MENTIONS_REPLIES, ALL, NONE, UNKNOWN }

enum class MlsCatchUpState { IDLE, RUNNING, REJOINING }

/** The SDK-verified author of a post or a roster row. [display] is null for a non-contact (the screen shows "Member"). */
data class MlsAuthor(val anchor: String, val display: String?, val aiFlag: Boolean, val isMe: Boolean) {
    override fun toString() = "MlsAuthor(<redacted>)"
}

/** One invite. [usesLeft] is set only by `group.create` and `group.invite.create`; [revoked] only by `group.invite.list`. */
data class MlsInviteMetadata(
    val inviteId: String,
    val link: String,
    val maxUses: Long?,
    val usesLeft: Long?,
    val expiresAt: Long?,
    val revoked: Boolean,
) {
    override fun toString() = "MlsInviteMetadata(link=<redacted>)"
}

data class MlsQuota(val limit: Long, val remaining: Long, val firstDay: Boolean, val resetsAt: Long?)

/** `privacy_delay`: null (never delays), armed (`send_after` null) or waiting until [sendAfter]. */
sealed interface MlsPrivacyDelay {
    data object Armed : MlsPrivacyDelay
    data class Waiting(val sendAfter: Long) : MlsPrivacyDelay
}

data class MlsWarning(val code: String, val cleared: Boolean, val hostNode: String?) {
    override fun toString() = "MlsWarning(code=$code)"
}

/** A `group.list` row or the `group.info` result. */
data class MlsGroupInfoMetadata(
    val groupId: String,
    val name: String,
    val tier: Long,
    val members: Long,
    val leaves: Long,
    val agents: Long,
    val approval: Boolean,
    val myRole: MlsRole,
    val hostNode: String?,
    val push: MlsPush,
    val catchUp: MlsCatchUpState,
    val catchUpDone: Long,
    val catchUpTotal: Long,
    val privacyDelay: MlsPrivacyDelay?,
    val quotaPost: MlsQuota?,
    val quotaCommit: MlsQuota?,
    val lastLogSeq: Long,
    val lastActivityAt: Long?,
    val warnings: List<MlsWarning>,
    val unread: Long = 0,
    val unreadMentions: Long = 0,
    val readLogSeq: Long = 0,
    val lastPost: MlsLastPost? = null,
) {
    override fun toString() = "MlsGroupInfoMetadata(<redacted>)"
}

/** The preview of a group's newest post (text only, at most 100 characters). */
data class MlsLastPost(val logSeq: Long?, val kind: MlsPostKind, val author: MlsAuthor?, val snippet: String?, val appendedAt: Long?) {
    override fun toString() = "MlsLastPost(<redacted>)"
}

/** One pending join request (`group.join.pending`). It is rebuilt after a relaunch; the link is not part of it. */
data class MlsPendingJoinMetadata(
    val requestId: String,
    val groupId: String,
    val hostNode: String?,
    val reason: MlsPendingReason,
    val requestedAt: Long?,
    val expiresAt: Long?,
) {
    override fun toString() = "MlsPendingJoinMetadata(<redacted>)"
}

/** `group.refresh`: one row per group the host answered for. */
data class MlsRefreshedMetadata(val groupId: String, val lastLogSeq: Long, val newPosts: Long) {
    override fun toString() = "MlsRefreshedMetadata(<redacted>)"
}

/** `group.mark_read` and the `read` event. */
data class MlsReadMetadata(val readLogSeq: Long, val unread: Long, val unreadMentions: Long)

data class MlsGroupPageMetadata(val groups: List<MlsGroupInfoMetadata>, val cursor: String?) {
    override fun toString() = "MlsGroupPageMetadata(<redacted>)"
}

data class MlsCreateMetadata(val groupId: String, val invite: MlsInviteMetadata) {
    override fun toString() = "MlsCreateMetadata(<redacted>)"
}

data class MlsMemberMetadata(
    val anchor: String,
    val display: String?,
    val role: MlsRole,
    val aiFlag: Boolean,
    val devices: Long,
    val isMe: Boolean,
) {
    override fun toString() = "MlsMemberMetadata(<redacted>)"
}

data class MlsMemberPageMetadata(val rows: List<MlsMemberMetadata>, val cursor: String?) {
    override fun toString() = "MlsMemberPageMetadata(<redacted>)"
}

enum class MlsJoinRefusal {
    LINK_INVALID, INVITE_EXPIRED, INVITE_INVALID, GROUP_FULL, QUEUE_FULL, REQUEST_EXPIRED, ALREADY_MEMBER, RATE_LIMITED, UNKNOWN
}

enum class MlsPendingReason { AWAITING_ADMIN, AWAITING_MEMBER_ONLINE, UNKNOWN }

/** The result of `group.join`. */
sealed interface MlsJoinMetadata {
    data class Joined(val groupId: String) : MlsJoinMetadata {
        override fun toString() = "Joined(<redacted>)"
    }

    data class Pending(val requestId: String, val reason: MlsPendingReason, val expiresAt: Long?) : MlsJoinMetadata {
        override fun toString() = "Pending(<redacted>)"
    }

    /** [expiredAt], [leafCap] and [retryAfterSecs] are the typed `params`; [groupId] is always present on `already_member` (r4 item 6). */
    data class Refused(
        val reason: MlsJoinRefusal,
        val rawReason: String?,
        val expiredAt: Long? = null,
        val leafCap: Long? = null,
        val retryAfterSecs: Long? = null,
        val groupId: String? = null,
    ) : MlsJoinMetadata {
        override fun toString() = "Refused(reason=$reason)"
    }
}

enum class MlsPostKind { TEXT, ATTACHMENT, CARD, MEMBERSHIP, SETTINGS, VOID, UNKNOWN }

enum class MlsChangeKind { JOINED, LEFT, REMOVED, BANNED, ROLE_CHANGED, DEVICE_ADDED, DEVICE_REMOVED, UNKNOWN }

enum class MlsQueuedReason { CATCHING_UP, HOST_UNREACHABLE, FETCHING_TOKENS, UNKNOWN }

/** The state of a post: the history `state` with its `params`, a `group.send` result or a `post_state` event. */
sealed interface MlsPostState {
    data class Sent(val logSeq: Long?) : MlsPostState
    data class Queued(val reason: MlsQueuedReason) : MlsPostState
    data class Delayed(val sendAfter: Long?, val delaySecs: Long?) : MlsPostState
    data class RateLimited(val quota: MlsQuota?, val bucket: String?) : MlsPostState
    data class Failed(val reason: String?) : MlsPostState
    data object Deleted : MlsPostState
    data class Unknown(val raw: String) : MlsPostState
}

data class MlsMembershipChange(
    val change: MlsChangeKind,
    val anchor: String,
    val display: String?,
    val aiFlag: Boolean,
    val role: MlsRole?,
    val isMe: Boolean = false,
) {
    override fun toString() = "MlsMembershipChange(change=$change)"
}

/** The settings a `settings` row changed; absent fields are null. Push mode is a per-device setting and not part of a row. */
data class MlsSettingsChange(val name: String?, val approval: Boolean?) {
    override fun toString() = "MlsSettingsChange(<redacted>)"
}

/** One history row, or the row a `post`, `membership` or `settings` event carries. */
data class MlsPostMetadata(
    val logSeq: Long?,
    val postId: String,
    val author: MlsAuthor?,
    val appendedAt: Long?,
    val kind: MlsPostKind,
    val rawKind: String?,
    val text: String?,
    val replyTo: String?,
    val mentions: List<String>,
    val state: MlsPostState,
    val changes: List<MlsMembershipChange>,
    val settings: MlsSettingsChange?,
    val by: MlsAuthor?,
) {
    override fun toString() = "MlsPostMetadata(<redacted>)"
}

data class MlsHistoryMetadata(val posts: List<MlsPostMetadata>, val floorLogSeq: Long, val lastLogSeq: Long, val more: Boolean) {
    override fun toString() = "MlsHistoryMetadata(<redacted>)"
}

data class MlsSendMetadata(val postId: String, val state: MlsPostState) {
    override fun toString() = "MlsSendMetadata(<redacted>)"
}

/** `group.message.delete` and `group.leave`: `state: committed` with the group log position. */
data class MlsCommitMetadata(val committed: Boolean, val logSeq: Long?)

/** The envelope fields of one `group.event`. [seq] is the account log position; [logSeq] the group log position. */
sealed interface MlsEventMetadata {
    val groupId: String?
    val seq: Long?

    data class Post(override val groupId: String?, override val seq: Long?, val post: MlsPostMetadata) : MlsEventMetadata {
        override fun toString() = "Post(<redacted>)"
    }

    data class PostState(
        override val groupId: String?,
        override val seq: Long?,
        val postId: String,
        val state: MlsPostState,
        val logSeq: Long?,
    ) : MlsEventMetadata {
        override fun toString() = "PostState(<redacted>)"
    }

    /** [postLogSeq] is the deleted post's position; the envelope `log_seq` ([logSeq]) is the delete record's own. */
    data class PostDeleted(
        override val groupId: String?,
        override val seq: Long?,
        val postId: String,
        val postLogSeq: Long?,
        val logSeq: Long?,
    ) : MlsEventMetadata {
        override fun toString() = "PostDeleted(<redacted>)"
    }

    /** `read`: the local read position moved (also from another surface of this device). */
    data class Read(override val groupId: String?, override val seq: Long?, val read: MlsReadMetadata) : MlsEventMetadata {
        override fun toString() = "Read(<redacted>)"
    }

    /** A `membership` or `settings` event; the row carries the changes or the settings and the `log_seq`. */
    data class Service(override val groupId: String?, override val seq: Long?, val post: MlsPostMetadata) : MlsEventMetadata {
        override fun toString() = "Service(<redacted>)"
    }

    data class JoinResult(
        override val groupId: String?,
        override val seq: Long?,
        val requestId: String,
        val joined: Boolean,
        val refusal: MlsJoinMetadata.Refused?,
    ) : MlsEventMetadata {
        override fun toString() = "JoinResult(<redacted>)"
    }

    /** `pending_join`, `catch_up`, `warning`, `void` and any unknown kind: M2 skips them without reading the payload. */
    data class Skipped(override val groupId: String?, override val seq: Long?, val kind: String?) : MlsEventMetadata {
        override fun toString() = "Skipped(kind=$kind)"
    }
}

object MlsGroupMetadataReader {
    private const val BOUND = 4096
    private const val NAME = 4096
    private const val BODY = 65536
    private const val PAGE = 200
    private const val CHANGES = 100
    private const val MENTIONS = 50
    private const val WARNINGS = 32

    /** `group.list`: `{groups[], cursor?}`. */
    fun list(reply: SecretReply): CoreResult<MlsGroupPageMetadata> = decode(reply) { f ->
        MlsGroupPageMetadata(f.rows("groups", PAGE).map { info(f, it) }, cursor(f, f.root))
    }

    /** `group.info`. */
    fun info(reply: SecretReply): CoreResult<MlsGroupInfoMetadata> = decode(reply) { f -> info(f, f.root) }

    /** `group.create`: `{group_id, invite{...}}`. */
    fun created(reply: SecretReply): CoreResult<MlsCreateMetadata> = decode(reply) { f ->
        val invite = requireNotNull(obj(f, f.root, "invite"))
        MlsCreateMetadata(requireNotNull(text(f, f.root, "group_id", BOUND)), invite(f, invite))
    }

    /** `group.invite.create`: the invite is the result itself. */
    fun invite(reply: SecretReply): CoreResult<MlsInviteMetadata> = decode(reply) { f -> invite(f, f.root) }

    /** `group.invite.list`: `invites[]`. */
    fun invites(reply: SecretReply): CoreResult<List<MlsInviteMetadata>> = decode(reply) { f ->
        f.rows("invites", PAGE).map { invite(f, it) }
    }

    /** `group.invite.revoke`: any well-formed reply with a `state` confirms; the state token is returned. */
    fun state(reply: SecretReply): CoreResult<String> = decode(reply) { f -> requireNotNull(token(text(f, f.root, "state", 64))) }

    /** `group.join`: `joined`, `pending` or `refused`. An unknown top-level state is a failure of the read, not a refusal. */
    fun join(reply: SecretReply): CoreResult<MlsJoinMetadata> = decode(reply) { f -> join(f, f.root) }

    /** `group.members`: `{rows[], cursor?}`. */
    fun members(reply: SecretReply): CoreResult<MlsMemberPageMetadata> = decode(reply) { f ->
        MlsMemberPageMetadata(
            f.rows("rows", PAGE).map { row ->
                val author = author(f, row, "anchor")
                MlsMemberMetadata(
                    author.anchor, author.display, role(f, row, "role"), author.aiFlag,
                    f.optionalUnsigned(row, "devices") ?: 0L, author.isMe,
                )
            },
            cursor(f, f.root),
        )
    }

    /** `group.history`. */
    fun history(reply: SecretReply): CoreResult<MlsHistoryMetadata> = decode(reply) { f ->
        MlsHistoryMetadata(
            f.rows("posts", PAGE).map { post(f, it) },
            f.optionalUnsigned(f.root, "floor_log_seq") ?: 0L,
            f.optionalUnsigned(f.root, "last_log_seq") ?: 0L,
            f.optionalBoolean(f.root, "more") ?: false,
        )
    }

    /** `group.send`: `{post_id, state, params}`. */
    fun sent(reply: SecretReply): CoreResult<MlsSendMetadata> = decode(reply) { f ->
        MlsSendMetadata(requireNotNull(text(f, f.root, "post_id", BOUND)), postState(f, f.root, f.root))
    }

    /** `group.leave`: committed only when `state` is `committed`. */
    fun committed(reply: SecretReply): CoreResult<MlsCommitMetadata> = decode(reply) { f ->
        MlsCommitMetadata(text(f, f.root, "state", 64) == "committed", f.optionalUnsigned(f.root, "log_seq"))
    }

    /** `group.message.delete`: `deleted{log_seq}` when the record is written, else `queued` or `delayed_privacy` (r4 item 10). */
    fun deleted(reply: SecretReply): CoreResult<MlsSendMetadata> = decode(reply) { f ->
        MlsSendMetadata(requireNotNull(text(f, f.root, "post_id", BOUND)), postState(f, f.root, obj(f, f.root, "params") ?: f.root))
    }

    /** `group.refresh`: `groups[{group_id, last_log_seq, new_posts}]`. */
    fun refreshed(reply: SecretReply): CoreResult<List<MlsRefreshedMetadata>> = decode(reply) { f ->
        f.rows("groups", PAGE).map {
            MlsRefreshedMetadata(
                requireNotNull(text(f, it, "group_id", BOUND)),
                f.optionalUnsigned(it, "last_log_seq") ?: 0L,
                f.optionalUnsigned(it, "new_posts") ?: 0L,
            )
        }
    }

    /** `group.mark_read`: `{read_log_seq, unread, unread_mentions}`. */
    fun marked(reply: SecretReply): CoreResult<MlsReadMetadata> = decode(reply) { f -> read(f, f.root) }

    /** `group.join.pending`: `requests[]`, at most 100 rows, read from the local account database. */
    fun pending(reply: SecretReply): CoreResult<List<MlsPendingJoinMetadata>> = decode(reply) { f ->
        f.rows("requests", 100).map { row ->
            MlsPendingJoinMetadata(
                requireNotNull(text(f, row, "request_id", BOUND)),
                requireNotNull(text(f, row, "group_id", BOUND)),
                text(f, row, "host_node", BOUND)?.takeIf { it.isNotEmpty() },
                pendingReason(token(text(f, row, "reason", 64))),
                f.optionalUnsigned(row, "requested_at"),
                f.optionalUnsigned(row, "expires_at"),
            )
        }
    }

    private fun read(f: DisplayFields, row: ByteJson.Node) = MlsReadMetadata(
        f.optionalUnsigned(row, "read_log_seq") ?: 0L,
        f.optionalUnsigned(row, "unread") ?: 0L,
        f.optionalUnsigned(row, "unread_mentions") ?: 0L,
    )

    private fun pendingReason(raw: String?) = when (raw) {
        "awaiting_admin" -> MlsPendingReason.AWAITING_ADMIN
        "awaiting_member_online" -> MlsPendingReason.AWAITING_MEMBER_ONLINE
        else -> MlsPendingReason.UNKNOWN
    }

    /**
     * One frame of the `group.event` topic, read once (the frame is wiped). Another topic, a frame that does not parse and a
     * kind with a missing required field give null.
     */
    fun event(reply: SecretReply): MlsEventMetadata? = try {
        reply.useOnce { raw -> event(DisplayFields(raw)) }
    } catch (_: Exception) { null }
    finally { reply.close() }

    internal fun event(f: DisplayFields): MlsEventMetadata? {
        val body = f.field(f.root, "body")?.takeIf { it.kind == 123 } ?: f.root
        val method = f.field(f.root, "method")?.takeIf { it.kind == 34 }?.let { f.text(f.root, "method") }
        if (method != null && method != "group.event") return null
        val group = text(f, body, "group_id", BOUND)
        val seq = f.optionalUnsigned(body, "seq")
        val kind = token(text(f, body, "kind", 64))
        return when (kind) {
            "post" -> MlsEventMetadata.Post(group, seq, post(f, obj(f, body, "post") ?: return null, f.optionalUnsigned(body, "log_seq")))
            "post_state" -> MlsEventMetadata.PostState(
                group, seq, requireNotNull(text(f, body, "post_id", BOUND)), postState(f, body, body), f.optionalUnsigned(body, "log_seq"),
            )
            "post_deleted" -> MlsEventMetadata.PostDeleted(
                group, seq, requireNotNull(text(f, body, "post_id", BOUND)), f.optionalUnsigned(body, "post_log_seq"),
                f.optionalUnsigned(body, "log_seq"),
            )
            "read" -> MlsEventMetadata.Read(group, seq, read(f, body))
            "membership", "settings" -> {
                val row = obj(f, body, "body") ?: body
                MlsEventMetadata.Service(
                    group, seq,
                    serviceRow(f, row, if (kind == "membership") MlsPostKind.MEMBERSHIP else MlsPostKind.SETTINGS, f.optionalUnsigned(body, "log_seq")),
                )
            }
            "join_result" -> {
                val joined = text(f, body, "state", 64) == "joined"
                MlsEventMetadata.JoinResult(
                    group, seq, requireNotNull(text(f, body, "request_id", BOUND)), joined,
                    if (joined) null else refusal(f, body, text(f, body, "reason", 64)),
                )
            }
            else -> MlsEventMetadata.Skipped(group, seq, kind)
        }
    }

    private fun info(f: DisplayFields, row: ByteJson.Node): MlsGroupInfoMetadata {
        val catchUp = obj(f, row, "catch_up")
        val warnings = f.field(row, "warnings")?.takeIf { it.kind == 91 }?.children.orEmpty()
        require(warnings.size <= WARNINGS)
        return MlsGroupInfoMetadata(
            groupId = requireNotNull(text(f, row, "group_id", BOUND)),
            name = text(f, row, "name", NAME).orEmpty(),
            tier = f.optionalUnsigned(row, "tier") ?: 0L,
            members = f.optionalUnsigned(row, "members") ?: 0L,
            leaves = f.optionalUnsigned(row, "leaves") ?: 0L,
            agents = f.optionalUnsigned(row, "agents") ?: 0L,
            approval = f.optionalBoolean(row, "approval") ?: false,
            myRole = role(f, row, "my_role"),
            hostNode = text(f, row, "host_node", BOUND)?.takeIf { it.isNotEmpty() },
            push = push(text(f, row, "push", 64)),
            catchUp = when (catchUp?.let { text(f, it, "state", 64) }) {
                "running" -> MlsCatchUpState.RUNNING
                "rejoining" -> MlsCatchUpState.REJOINING
                else -> MlsCatchUpState.IDLE
            },
            catchUpDone = catchUp?.let { f.optionalUnsigned(it, "done") } ?: 0L,
            catchUpTotal = catchUp?.let { f.optionalUnsigned(it, "total") } ?: 0L,
            privacyDelay = obj(f, row, "privacy_delay")?.let { delay ->
                f.optionalUnsigned(delay, "send_after")?.let { MlsPrivacyDelay.Waiting(it) } ?: MlsPrivacyDelay.Armed
            },
            quotaPost = obj(f, row, "quota")?.let { q -> obj(f, q, "post")?.let { quota(f, it) } },
            quotaCommit = obj(f, row, "quota")?.let { q -> obj(f, q, "commit")?.let { quota(f, it) } },
            lastLogSeq = f.optionalUnsigned(row, "last_log_seq") ?: 0L,
            lastActivityAt = f.optionalUnsigned(row, "last_activity_at"),
            unread = f.optionalUnsigned(row, "unread") ?: 0L,
            unreadMentions = f.optionalUnsigned(row, "unread_mentions") ?: 0L,
            readLogSeq = f.optionalUnsigned(row, "read_log_seq") ?: 0L,
            lastPost = obj(f, row, "last_post")?.let { last ->
                MlsLastPost(
                    f.optionalUnsigned(last, "log_seq"), postKind(token(text(f, last, "kind", 64))),
                    obj(f, last, "author")?.let { author(f, it, "anchor") },
                    text(f, last, "snippet", 400)?.take(100), f.optionalUnsigned(last, "appended_at"),
                )
            },
            warnings = warnings.mapNotNull { w ->
                val code = token(text(f, w, "code", 64)) ?: return@mapNotNull null
                MlsWarning(code, f.optionalBoolean(w, "cleared") ?: false, obj(f, w, "params")?.let { text(f, it, "host_node", BOUND) })
            },
        )
    }

    private fun invite(f: DisplayFields, row: ByteJson.Node) = MlsInviteMetadata(
        inviteId = requireNotNull(text(f, row, "invite_id", BOUND)),
        link = requireNotNull(text(f, row, "link", BOUND)),
        maxUses = f.optionalUnsigned(row, "max_uses"),
        usesLeft = f.optionalUnsigned(row, "uses_left"),
        expiresAt = f.optionalUnsigned(row, "expires_at"),
        revoked = f.optionalBoolean(row, "revoked") ?: false,
    )

    private fun quota(f: DisplayFields, row: ByteJson.Node) = MlsQuota(
        f.optionalUnsigned(row, "limit") ?: 0L,
        f.optionalUnsigned(row, "remaining") ?: 0L,
        f.optionalBoolean(row, "first_day") ?: false,
        f.optionalUnsigned(row, "resets_at"),
    )

    private fun join(f: DisplayFields, row: ByteJson.Node): MlsJoinMetadata {
        val params = obj(f, row, "params") ?: row
        return when (token(text(f, row, "state", 64))) {
            "joined" -> MlsJoinMetadata.Joined(requireNotNull(text(f, params, "group_id", BOUND) ?: text(f, row, "group_id", BOUND)))
            "pending" -> MlsJoinMetadata.Pending(
                requireNotNull(text(f, row, "request_id", BOUND) ?: text(f, params, "request_id", BOUND)),
                pendingReason(token(text(f, row, "reason", 64) ?: text(f, params, "reason", 64))),
                f.optionalUnsigned(row, "expires_at") ?: f.optionalUnsigned(params, "expires_at"),
            )
            "refused" -> refusal(f, row, text(f, row, "reason", 64))
            else -> throw IllegalArgumentException("join state")
        }
    }

    private fun refusal(f: DisplayFields, row: ByteJson.Node, rawReason: String?): MlsJoinMetadata.Refused {
        val params = obj(f, row, "params") ?: row
        val raw = token(rawReason)
        val reason = when (raw) {
            "link_invalid" -> MlsJoinRefusal.LINK_INVALID
            "invite_expired" -> MlsJoinRefusal.INVITE_EXPIRED
            "invite_invalid" -> MlsJoinRefusal.INVITE_INVALID
            "group_full" -> MlsJoinRefusal.GROUP_FULL
            "queue_full" -> MlsJoinRefusal.QUEUE_FULL
            "request_expired" -> MlsJoinRefusal.REQUEST_EXPIRED
            "already_member" -> MlsJoinRefusal.ALREADY_MEMBER
            "rate_limited" -> MlsJoinRefusal.RATE_LIMITED
            else -> MlsJoinRefusal.UNKNOWN
        }
        return MlsJoinMetadata.Refused(
            reason,
            raw.takeIf { reason == MlsJoinRefusal.UNKNOWN },
            expiredAt = f.optionalUnsigned(params, "expired_at"),
            leafCap = f.optionalUnsigned(params, "leaf_cap"),
            retryAfterSecs = f.optionalUnsigned(params, "retry_after_s"),
            groupId = text(f, params, "group_id", BOUND),
        )
    }

    /** A history row. [envelopeLogSeq] fills a missing `log_seq` of a `post` event. */
    private fun post(f: DisplayFields, row: ByteJson.Node, envelopeLogSeq: Long? = null): MlsPostMetadata {
        val rawKind = token(text(f, row, "kind", 64))
        val kind = postKind(rawKind)
        val logSeq = f.optionalUnsigned(row, "log_seq") ?: envelopeLogSeq
        return when (kind) {
            MlsPostKind.MEMBERSHIP, MlsPostKind.SETTINGS -> serviceRow(f, obj(f, row, "body") ?: row, kind, logSeq).copy(
                postId = text(f, row, "post_id", BOUND).orEmpty(),
                appendedAt = f.optionalUnsigned(row, "appended_at"),
                state = postState(f, row, obj(f, row, "params") ?: row),
            )
            else -> {
                val bodyNode = f.field(row, "body")
                val bodyObject = bodyNode?.takeIf { it.kind == 123 }
                MlsPostMetadata(
                    logSeq = logSeq,
                    postId = text(f, row, "post_id", BOUND).orEmpty(),
                    author = obj(f, row, "author")?.let { author(f, it, "anchor") },
                    appendedAt = f.optionalUnsigned(row, "appended_at"),
                    kind = kind,
                    rawKind = rawKind.takeIf { kind == MlsPostKind.UNKNOWN },
                    text = if (kind == MlsPostKind.TEXT && bodyNode?.kind == 34) text(f, row, "body", BODY) else null,
                    replyTo = text(f, row, "reply_to", BOUND),
                    mentions = strings(f, row, "mentions", MENTIONS),
                    state = postState(f, row, obj(f, row, "params") ?: row),
                    changes = emptyList(),
                    settings = null,
                    by = bodyObject?.let { by(f, it) },
                )
            }
        }
    }

    /** The body of a `membership` or `settings` row: the changes or the settings, and `by`. */
    private fun serviceRow(f: DisplayFields, body: ByteJson.Node, kind: MlsPostKind, logSeq: Long?): MlsPostMetadata {
        val changes = if (kind == MlsPostKind.MEMBERSHIP) {
            val list = f.field(body, "changes")?.takeIf { it.kind == 91 }?.children.orEmpty()
            require(list.size <= CHANGES)
            list.map { change ->
                val who = author(f, change, "anchor")
                MlsMembershipChange(
                    changeKind(token(text(f, change, "change", 64))), who.anchor, who.display, who.aiFlag,
                    if (f.field(change, "role")?.kind == 34) role(f, change, "role") else null,
                    who.isMe,
                )
            }
        } else {
            emptyList()
        }
        val settings = if (kind == MlsPostKind.SETTINGS) {
            MlsSettingsChange(
                text(f, body, "name", NAME),
                f.optionalBoolean(body, "approval"),
            )
        } else {
            null
        }
        return MlsPostMetadata(
            logSeq, "", null, null, kind, null, null, null, emptyList(), MlsPostState.Sent(logSeq), changes, settings, by(f, body),
        )
    }

    /** `by`: an author object, or a bare anchor (Q2). */
    private fun by(f: DisplayFields, body: ByteJson.Node): MlsAuthor? {
        val node = f.field(body, "by") ?: return null
        return when (node.kind) {
            123 -> author(f, node, "anchor")
            34 -> MlsAuthor(requireNotNull(text(f, body, "by", BOUND)), null, false, false)
            else -> null
        }
    }

    private fun postState(f: DisplayFields, row: ByteJson.Node, params: ByteJson.Node): MlsPostState {
        // An own unsent row: `state: pending` and the outbox state in `send{state, params}` (r4 item 8).
        if (text(f, row, "state", 64) == "pending") {
            val send = obj(f, row, "send") ?: return MlsPostState.Queued(MlsQueuedReason.UNKNOWN)
            return postState(f, send, obj(f, send, "params") ?: send)
        }
        if (f.field(row, "state") == null) return MlsPostState.Sent(f.optionalUnsigned(params, "log_seq") ?: f.optionalUnsigned(row, "log_seq"))
        val raw = token(text(f, row, "state", 64)) ?: return MlsPostState.Unknown("")
        return when (raw) {
            "sent" -> MlsPostState.Sent(f.optionalUnsigned(params, "log_seq") ?: f.optionalUnsigned(row, "log_seq"))
            "queued" -> MlsPostState.Queued(
                when (token(text(f, params, "reason", 64))) {
                    "catching_up" -> MlsQueuedReason.CATCHING_UP
                    "host_unreachable" -> MlsQueuedReason.HOST_UNREACHABLE
                    "fetching_tokens" -> MlsQueuedReason.FETCHING_TOKENS
                    else -> MlsQueuedReason.UNKNOWN
                },
            )
            "delayed_privacy" -> MlsPostState.Delayed(f.optionalUnsigned(params, "send_after"), f.optionalUnsigned(params, "delay_s"))
            "rate_limited" -> MlsPostState.RateLimited(
                if (f.field(params, "limit") != null) quota(f, params) else null,
                text(f, params, "bucket", 64),
            )
            "failed" -> MlsPostState.Failed(token(text(f, params, "reason", 64)))
            "deleted" -> MlsPostState.Deleted
            else -> MlsPostState.Unknown(raw)
        }
    }

    /** An author object; [anchorKey] names the anchor field (`anchor` in every r3 shape). */
    private fun author(f: DisplayFields, row: ByteJson.Node, anchorKey: String) = MlsAuthor(
        requireNotNull(text(f, row, anchorKey, BOUND)?.takeIf { it.isNotEmpty() }),
        text(f, row, "display", NAME)?.takeIf { it.isNotEmpty() },
        f.optionalBoolean(row, "ai_flag") ?: false,
        f.optionalBoolean(row, "is_me") ?: false,
    )

    private fun postKind(raw: String?) = when (raw) {
        "text" -> MlsPostKind.TEXT
        "attachment" -> MlsPostKind.ATTACHMENT
        "card" -> MlsPostKind.CARD
        "membership" -> MlsPostKind.MEMBERSHIP
        "settings" -> MlsPostKind.SETTINGS
        "void" -> MlsPostKind.VOID
        else -> MlsPostKind.UNKNOWN
    }

    private fun role(f: DisplayFields, row: ByteJson.Node, key: String) = when (token(text(f, row, key, 64))) {
        "owner" -> MlsRole.OWNER
        "admin" -> MlsRole.ADMIN
        "member" -> MlsRole.MEMBER
        else -> MlsRole.UNKNOWN
    }

    private fun push(value: String?) = when (token(value)) {
        "mentions_replies" -> MlsPush.MENTIONS_REPLIES
        "all" -> MlsPush.ALL
        "none" -> MlsPush.NONE
        else -> MlsPush.UNKNOWN
    }

    private fun changeKind(value: String?) = when (value) {
        "joined" -> MlsChangeKind.JOINED
        "left" -> MlsChangeKind.LEFT
        "removed" -> MlsChangeKind.REMOVED
        "banned" -> MlsChangeKind.BANNED
        "role_changed" -> MlsChangeKind.ROLE_CHANGED
        "device_added" -> MlsChangeKind.DEVICE_ADDED
        "device_removed" -> MlsChangeKind.DEVICE_REMOVED
        else -> MlsChangeKind.UNKNOWN
    }

    private fun strings(f: DisplayFields, row: ByteJson.Node, key: String, bound: Int): List<String> {
        val array = f.field(row, key)?.takeIf { it.kind == 91 } ?: return emptyList()
        require(array.children.size <= bound)
        return if (array.children.isEmpty()) emptyList() else f.texts(row, key, bound, minimum = 0, maximum = BOUND)
    }

    /** A paging cursor: an opaque string, or an opaque object kept as its raw JSON. Absent and null are the last page. */
    private fun cursor(f: DisplayFields, row: ByteJson.Node): String? {
        val node = f.field(row, "cursor") ?: return null
        return when (node.kind) {
            34 -> text(f, row, "cursor", BOUND)?.takeIf { it.isNotEmpty() }
            123 -> f.raw(node).takeIf { it.length <= BOUND }
            else -> null
        }
    }

    private fun obj(f: DisplayFields, row: ByteJson.Node, key: String): ByteJson.Node? = f.field(row, key)?.takeIf { it.kind == 123 }

    private fun text(f: DisplayFields, row: ByteJson.Node, key: String, bound: Int): String? =
        f.text(row, key)?.also { require(it.length <= bound) }

    /** A lower-case code token (`a-z`, digits, `_`), at most 64 characters; anything else is not a token. */
    private fun token(value: String?): String? =
        value?.takeIf { it.length in 1..64 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' } }

    private fun <T> decode(reply: SecretReply, body: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(body(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
