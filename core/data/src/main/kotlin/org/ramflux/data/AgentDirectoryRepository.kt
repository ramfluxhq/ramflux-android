// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** `lifecycle` of a stored agent record: `U` (active) or `X` (retired). */
enum class AgentLifecycle { ACTIVE, RETIRED, UNKNOWN }

/** The access policy of an agent as stored (closed tokens `owner`, `contacts`, `allowlist`, `open`). Read only. */
data class AgentAccess(val dm: String, val groupInviters: String, val allowedHumans: Int)

/** Persona fields of `agent.persona.edit`; byte limits are the SDK's (`agent_record/policy.rs`). */
data class AgentPersona(val name: String, val avatar: String, val responsibility: String) {
    val valid: Boolean
        get() = name.isNotBlank() && responsibility.isNotBlank() && name.toByteArray().size <= MAX_NAME_BYTES &&
            avatar.toByteArray().size <= MAX_AVATAR_BYTES && responsibility.toByteArray().size <= MAX_RESPONSIBILITY_BYTES
    override fun toString() = "AgentPersona(<redacted>)"

    companion object {
        const val MAX_NAME_BYTES = 256
        const val MAX_AVATAR_BYTES = 2048
        const val MAX_RESPONSIBILITY_BYTES = 4096
    }
}

/** One stored agent record of this account. [anchor] is the agent's account anchor, the stable id. */
data class AgentSummary(
    val anchor: String,
    val persona: AgentPersona,
    val lifecycle: AgentLifecycle,
    val role: String,
    val access: AgentAccess,
    /** `node_record_retained` after a delete: the record published on the home node stays. */
    val cleanup: String = ""
) {
    override fun toString() = "AgentSummary(<redacted>)"
}

/** A window page of agents plus the journal cursor at which a watch continues. */
data class AgentPage(val page: WindowedPage<AgentSummary>, val journal: String)

/** The records changed after a journal cursor (`agent.watch`). */
data class AgentChanges(val records: List<AgentSummary>, val journal: String, val more: Boolean) {
    override fun toString() = "AgentChanges(rows=${records.size})"
}

/**
 * A creation started on a paired desktop that waits for this device's decision (`agent.operation.get` with an
 * empty id). The phone cannot approve it today: `agent.creation.decide` needs the plan hash, which the C ABI does
 * not return.
 */
data class PendingAgentCreation(val requestId: String, val state: String, val anchor: String?, val name: String?, val expiresAt: Long?) {
    override fun toString() = "PendingAgentCreation(<redacted>)"
}

interface AgentDirectoryRepository {
    /** A window page of `agent.list`; a null cursor starts a new read. */
    suspend fun agentPage(account: String, cursor: String? = null): ClientResult<AgentPage>
    suspend fun agent(account: String, anchor: String): ClientResult<AgentSummary>
    suspend fun changes(account: String, journal: String): ClientResult<AgentChanges>

    /** The oldest creation waiting for this device, or null when none waits. */
    suspend fun pendingCreation(account: String): ClientResult<PendingAgentCreation?>

    /** Requires the host confirmation ([confirmed]); returns the stored record after the edit. */
    suspend fun editPersona(account: String, anchor: String, persona: AgentPersona, confirmed: Boolean): ClientResult<AgentSummary>

    /** Requires the host confirmation; only an active record can be deleted. Returns the retired record. */
    suspend fun delete(account: String, anchor: String, confirmed: Boolean): ClientResult<AgentSummary>

    /** The existing direct conversation with the agent, or null when none exists on this device yet. */
    suspend fun conversation(account: String, anchor: String): ClientResult<String?>
}
