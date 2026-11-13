// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the permissions flows. No retries or optimistic outcomes. */
interface PermissionsRepository {
    suspend fun grantApprove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantCreate_standing_approval(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantDeny(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantList_standing_approvals(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantRequest(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantRevoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun grantRevoke_standing_approval(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpApprovalDenied(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpApprovalGranted(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpApprovalList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpAuditList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpServerAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpServerList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpServerRefresh(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpToolList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mcpToolStarted(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultPermissionsRepository(transport: RepositoryTransport) : SdkRepository(transport), PermissionsRepository {
    override suspend fun grantApprove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.approve", "grant", context, body)

    override suspend fun grantCreate_standing_approval(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.create_standing_approval", "grant", context, body)

    override suspend fun grantDeny(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.deny", "grant", context, body)

    override suspend fun grantList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.list", "grant", context, body)

    override suspend fun grantList_standing_approvals(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.list_standing_approvals", "grant", context, body)

    override suspend fun grantRequest(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.request", "grant", context, body)

    override suspend fun grantRevoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.revoke", "grant", context, body)

    override suspend fun grantRevoke_standing_approval(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("grant.revoke_standing_approval", "grant", context, body)

    override suspend fun mcpApprovalDenied(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.approval.denied", "mcp", context, body)

    override suspend fun mcpApprovalGranted(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.approval.granted", "mcp", context, body)

    override suspend fun mcpApprovalList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.approval.list", "mcp", context, body)

    override suspend fun mcpAuditList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.audit.list", "mcp", context, body)

    override suspend fun mcpServerAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.server.add", "mcp", context, body)

    override suspend fun mcpServerList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.server.list", "mcp", context, body)

    override suspend fun mcpServerRefresh(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.server.refresh", "mcp", context, body)

    override suspend fun mcpToolList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.tool.list", "mcp", context, body)

    override suspend fun mcpToolStarted(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("mcp.tool.started", "mcp", context, body)
}
