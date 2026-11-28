// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.MlsGroupMetadataReader
import org.ramflux.sdk.SubscriptionFilter

/**
 * The native MLS groups repository. Five of the M2 method names (`group.history`, `group.leave`, `group.members`,
 * `group.send`, and the old `group.read`) already exist at the pinned core with the old sender-key shapes, and the other
 * methods do not exist, so no M2 call is made before the pin batch (W): every call returns the typed unavailable result without
 * touching the SDK. Nothing is retried. Only the `group.event` subscription is live; the current SDK accepts the unknown topic
 * and delivers nothing (r3 item 20).
 */
internal class DefaultMlsGroupRepository(private val transport: RepositoryTransport) : MlsGroupRepository {
    private fun <T> unavailable(): ClientResult<T> = ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)

    // W: after the pin, call group.list through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.list
    override suspend fun list(account: String, cursor: String?) = unavailable<MlsGroupPage<MlsGroup>>()

    // W: after the pin, call group.info through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.info
    override suspend fun info(account: String, groupId: String) = unavailable<MlsGroup>()

    // W: after the pin, call group.create through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.created
    override suspend fun create(account: String, name: String, approval: Boolean) = unavailable<MlsGroupCreated>()

    // W: after the pin, call group.invite.create through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.invite
    override suspend fun createInvite(account: String, groupId: String, maxUses: Int, ttlSecs: Long) = unavailable<MlsGroupInvite>()

    // W: after the pin, call group.invite.list through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.invites
    override suspend fun invites(account: String, groupId: String) = unavailable<List<MlsGroupInvite>>()

    // W: after the pin, call group.invite.revoke through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.state
    override suspend fun revokeInvite(account: String, groupId: String, inviteId: String) = unavailable<Unit>()

    // W: after the pin, call group.join through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.join
    override suspend fun join(account: String, link: String) = unavailable<MlsGroupJoin>()

    // W: after the pin, call group.join.pending through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.pending
    override suspend fun pendingJoins(account: String) = unavailable<List<MlsGroupPending>>()

    // W: after the pin, call group.members through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.members
    override suspend fun members(account: String, groupId: String, cursor: String?) = unavailable<MlsGroupPage<MlsGroupMember>>()

    // W: after the pin, call group.history through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.history
    override suspend fun history(account: String, groupId: String, afterLogSeq: Long?, beforeLogSeq: Long?, limit: Int) =
        unavailable<MlsGroupHistory>()

    // W: after the pin, call group.send through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.sent
    override suspend fun send(account: String, groupId: String, text: String, replyTo: String?, mentions: List<String>) =
        unavailable<MlsGroupSend>()

    // W: after the pin, call group.refresh through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.refreshed
    override suspend fun refresh(account: String, groupId: String) = unavailable<Unit>()

    // W: after the pin, call group.mark_read through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.marked
    override suspend fun markRead(account: String, groupId: String, logSeq: Long) = unavailable<MlsGroupRead>()

    // W: after the pin, call group.message.delete through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.deleted
    override suspend fun delete(account: String, groupId: String, postId: String) = unavailable<MlsGroupSend>()

    // W: after the pin, call group.leave through executeSecret in sdk api "group"; parse with MlsGroupMetadataReader.committed
    override suspend fun leave(account: String, groupId: String) = unavailable<Unit>()

    // W: after the pin, call group.requests.list through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.requests
    override suspend fun requests(account: String, groupId: String, cursor: String?, limit: Int) = unavailable<MlsGroupPage<MlsJoinRequest>>()

    // W: after the pin, call group.requests.approve through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun approveRequest(account: String, groupId: String, requestId: String) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.requests.decline through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.declined
    override suspend fun declineRequest(account: String, groupId: String, requestId: String) = unavailable<Unit>()

    // W: after the pin, call group.role.set through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun setRole(account: String, groupId: String, anchor: String, role: MlsGroupRole) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.member.remove through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun removeMember(account: String, groupId: String, anchor: String) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.member.ban through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun banMember(account: String, groupId: String, anchor: String) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.settings.set{name} through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun setName(account: String, groupId: String, name: String) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.settings.set{approval} through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun setApproval(account: String, groupId: String, approval: Boolean) = unavailable<MlsGroupCommit>()

    // W: after the pin, call group.push.set (Q1) through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.push
    override suspend fun setPush(account: String, groupId: String, push: MlsGroupPush) = unavailable<MlsGroupPush>()

    // W: after the pin, call group.catch_up{skip: true} through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.catchUp
    override suspend fun skipCatchUp(account: String, groupId: String) = unavailable<MlsCatchUpResult>()

    // W: after the pin, call group.void through executeSecret in sdk api "group"; map the error frame with MlsGroupMetadataReader.refusal; parse with MlsGroupMetadataReader.committed
    override suspend fun voidCommit(account: String, groupId: String, logSeq: Long) = unavailable<MlsGroupCommit>()

    override fun events(account: String): Flow<MlsGroupSignal> = flow {
        if (account.isBlank() || account.length > 4096) return@flow
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { add(TOPIC) }) })
        transport.updates(filter).collect { update ->
            when (update) {
                // The payload is parsed once and dropped; it is never logged.
                is CoreUpdate.Events -> MlsGroupMetadataReader.event(update.body)?.let(MlsGroupMapping::signal)?.let { emit(it) }
                is CoreUpdate.Failure -> Unit
                is CoreUpdate.Terminal -> if (update.kind == "resync_required") emit(MlsGroupSignal.Resync)
            }
        }
    }

    companion object {
        const val TOPIC = "group.event"
    }
}
