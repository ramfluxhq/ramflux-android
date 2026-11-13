// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface PermissionsRepository {
    suspend fun grantApprove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun grantCreate_standing_approval(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun grantDeny(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun grantList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun grantList_standing_approvals(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun grantRequest(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun grantRevoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun grantRevoke_standing_approval(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun mcpApprovalDenied(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun mcpApprovalGranted(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun mcpApprovalList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mcpAuditList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mcpServerAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun mcpServerList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mcpServerRefresh(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mcpToolList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mcpToolStarted(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultPermissionsRepository(transport: RepositoryTransport) : SdkRepository(transport), PermissionsRepository {
    override suspend fun grantApprove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.approve", "grant", context, body)

    override suspend fun grantCreate_standing_approval(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.create_standing_approval", "grant", context, body)

    override suspend fun grantDeny(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.deny", "grant", context, body)

    override suspend fun grantList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("grant.list", "grant", context, SecretBody.publicJson("grant.list", body))

    override suspend fun grantList_standing_approvals(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("grant.list_standing_approvals", "grant", context, SecretBody.publicJson("grant.list_standing_approvals", body))

    override suspend fun grantRequest(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.request", "grant", context, body)

    override suspend fun grantRevoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.revoke", "grant", context, body)

    override suspend fun grantRevoke_standing_approval(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("grant.revoke_standing_approval", "grant", context, body)

    override suspend fun mcpApprovalDenied(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("mcp.approval.denied", "mcp", context, body)

    override suspend fun mcpApprovalGranted(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("mcp.approval.granted", "mcp", context, body)

    override suspend fun mcpApprovalList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("mcp.approval.list", "mcp", context, SecretBody.publicJson("mcp.approval.list", body))

    override suspend fun mcpAuditList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("mcp.audit.list", "mcp", context, SecretBody.publicJson("mcp.audit.list", body))

    override suspend fun mcpServerAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("mcp.server.add", "mcp", context, body)

    override suspend fun mcpServerList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("mcp.server.list", "mcp", context, SecretBody.publicJson("mcp.server.list", body))

    override suspend fun mcpServerRefresh(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("mcp.server.refresh", "mcp", context, SecretBody.publicJson("mcp.server.refresh", body))

    override suspend fun mcpToolList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("mcp.tool.list", "mcp", context, SecretBody.publicJson("mcp.tool.list", body))

    override suspend fun mcpToolStarted(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("mcp.tool.started", "mcp", context, body)
}
