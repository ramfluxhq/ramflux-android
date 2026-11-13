// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the groups flows. No retries or optimistic outcomes. */
interface GroupsRepository {
    suspend fun create(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun history(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun invitationAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun invitationDecline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun invitationsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun inviteAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun inviteCreate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun memberAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun memberBan(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun memberKick(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun memberRemove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun members(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun nameSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun ownerTransfer(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun read(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun receive(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun roleSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun send(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun sender_keyExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun sender_keyImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultGroupsRepository(transport: RepositoryTransport) : SdkRepository(transport), GroupsRepository {
    override suspend fun create(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.create", "group", context, body)

    override suspend fun history(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.history", "group", context, body)

    override suspend fun invitationAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.invitation.accept", "group", context, body)

    override suspend fun invitationDecline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.invitation.decline", "group", context, body)

    override suspend fun invitationsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.invitations.list", "group", context, body)

    override suspend fun inviteAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.invite.accept", "group", context, body)

    override suspend fun inviteCreate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.invite.create", "group", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.list", "group", context, body)

    override suspend fun memberAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.member.add", "group", context, body)

    override suspend fun memberBan(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.member.ban", "group", context, body)

    override suspend fun memberKick(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.member.kick", "group", context, body)

    override suspend fun memberRemove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.member.remove", "group", context, body)

    override suspend fun members(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.members", "group", context, body)

    override suspend fun messageDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.message.delete", "group", context, body)

    override suspend fun nameSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.name.set", "group", context, body)

    override suspend fun ownerTransfer(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.owner.transfer", "group", context, body)

    override suspend fun read(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.read", "group", context, body)

    override suspend fun receive(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.receive", "group", context, body)

    override suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.requests.list", "group", context, body)

    override suspend fun roleSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.role.set", "group", context, body)

    override suspend fun send(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.send", "group", context, body)

    override suspend fun sender_keyExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.sender_key.export", "group", context, body)

    override suspend fun sender_keyImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("group.sender_key.import", "group", context, body)
}
