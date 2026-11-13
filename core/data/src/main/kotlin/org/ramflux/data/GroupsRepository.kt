// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface GroupsRepository {
    suspend fun create(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun history(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun invitationAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun invitationDecline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun invitationsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun inviteAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun inviteCreate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun memberAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun memberBan(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun memberKick(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun memberRemove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun members(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun messageDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun nameSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun ownerTransfer(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun read(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun receive(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun roleSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun send(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun sender_keyExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun sender_keyImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultGroupsRepository(transport: RepositoryTransport) : SdkRepository(transport), GroupsRepository {
    override suspend fun create(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.create", "group", context, body)

    override suspend fun history(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("group.history", "group", context, SecretBody.publicJson("group.history", body))

    override suspend fun invitationAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.invitation.accept", "group", context, body)

    override suspend fun invitationDecline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.invitation.decline", "group", context, body)

    override suspend fun invitationsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("group.invitations.list", "group", context, SecretBody.publicJson("group.invitations.list", body))

    override suspend fun inviteAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.invite.accept", "group", context, body)

    override suspend fun inviteCreate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.invite.create", "group", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("group.list", "group", context, SecretBody.publicJson("group.list", body))

    override suspend fun memberAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.member.add", "group", context, body)

    override suspend fun memberBan(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.member.ban", "group", context, body)

    override suspend fun memberKick(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.member.kick", "group", context, body)

    override suspend fun memberRemove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.member.remove", "group", context, body)

    override suspend fun members(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("group.members", "group", context, SecretBody.publicJson("group.members", body))

    override suspend fun messageDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.message.delete", "group", context, body)

    override suspend fun nameSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.name.set", "group", context, body)

    override suspend fun ownerTransfer(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.owner.transfer", "group", context, body)

    override suspend fun read(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.read", "group", context, body)

    override suspend fun receive(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.receive", "group", context, body)

    override suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("group.requests.list", "group", context, SecretBody.publicJson("group.requests.list", body))

    override suspend fun roleSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.role.set", "group", context, body)

    override suspend fun send(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.send", "group", context, body)

    override suspend fun sender_keyExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.sender_key.export", "group", context, body)

    override suspend fun sender_keyImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("group.sender_key.import", "group", context, body)
}
