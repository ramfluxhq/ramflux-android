// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

enum class AgentCapability { DIRECTORY, CREATE, PROFILE, ADVANCED, CHAT, GOAL, MANAGE, COLLABORATION, EDIT, REMOVE, PAUSE, HIDE }
enum class AgentAvatar { ORBIT, PRISM, WAVES, STAR }
enum class AgentDraftIssue { NAME_REQUIRED, NAME_LIMIT, GOAL_REQUIRED, GOAL_LIMIT }
data class AgentDraft(val name: String = "", val goal: String = "", val avatar: AgentAvatar = AgentAvatar.ORBIT) {
    val issue: AgentDraftIssue? get() = when {
        name.isBlank() -> AgentDraftIssue.NAME_REQUIRED
        name.codePointCount(0, name.length) > 40 || name.toByteArray(Charsets.UTF_8).size > 256 -> AgentDraftIssue.NAME_LIMIT
        goal.isBlank() -> AgentDraftIssue.GOAL_REQUIRED
        goal.toByteArray(Charsets.UTF_8).size > 256 -> AgentDraftIssue.GOAL_LIMIT
        else -> null
    }
    override fun toString() = "AgentDraft(<redacted>)"
}
interface AgentDirectoryRepository {
    suspend fun availability(account: String, capability: AgentCapability): ClientResult<Unit>
    suspend fun create(account: String, draft: AgentDraft): ClientResult<String>
}
/** AGENT-CONFIG-01 owns provisioning; bot installation is a separate SDK domain. */
internal class UnavailableAgentDirectoryRepository : AgentDirectoryRepository {
    override suspend fun availability(account: String, capability: AgentCapability): ClientResult<Unit> = unavailable()
    override suspend fun create(account: String, draft: AgentDraft): ClientResult<String> = unavailable()
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.AGENTS, UnavailableReason.UNSUPPORTED_BY_CORE)
}
