// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.UnavailableCapability

class AgentConfiguration(val displayName: String, val goal: String) {
    override fun toString() = "AgentConfiguration(<redacted>)"
}
interface AgentConfigurationRepository {
    suspend fun create(context: RepositoryContext, configuration: AgentConfiguration): CoreResult<String>
    suspend fun configure(context: RepositoryContext, agentId: String, configuration: AgentConfiguration): CoreResult<Unit>
}

/** Existing bot roster/install APIs do not imply dedicated Agent creation/configuration. */
class UnavailableAgentConfigurationRepository : AgentConfigurationRepository {
    override suspend fun create(context: RepositoryContext, configuration: AgentConfiguration): CoreResult<String> =
        CoreResult.Failure(CoreError.Unavailable(UnavailableCapability.AGENT_CONFIGURATION), context.operationId)
    override suspend fun configure(context: RepositoryContext, agentId: String, configuration: AgentConfiguration): CoreResult<Unit> =
        CoreResult.Failure(CoreError.Unavailable(UnavailableCapability.AGENT_CONFIGURATION), context.operationId)
}
