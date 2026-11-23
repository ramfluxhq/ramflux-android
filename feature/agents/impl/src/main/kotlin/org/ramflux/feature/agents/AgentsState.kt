// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.ramflux.designsystem.UiText

/** LIST is MOB-D01 (with D01E, D20, D21), PROFILE is MOB-D03, EDIT is MOB-D10. */
enum class AgentsPage { LIST, PROFILE, EDIT }

/** One agent row: [name] and [responsibility] are agent-controlled text, shown through the untrusted primitives. */
@Immutable data class AgentRow(
    val id: String,
    val name: String,
    val responsibility: String,
    val icon: String,
    val status: UiText,
    val active: Boolean
)

/** The profile of the selected agent; the access fields are read only while `agent.access.set` is blocked. */
@Immutable data class AgentProfile(
    val row: AgentRow,
    val avatar: String,
    val role: UiText,
    val directMessages: UiText,
    val groupInvitations: UiText
)

/** A creation started on the desktop that waits for this device; shown read only. */
@Immutable data class PendingRow(val id: String, val name: String?, val state: UiText, val expiry: UiText?)

/** A host-confirmed review of an edit or a delete. [target] is the agent name (agent-controlled). */
@Immutable data class AgentReview(val operation: UiText, val target: String, val explanation: UiText, val account: String)

@Immutable data class AgentsState(
    val page: AgentsPage = AgentsPage.LIST,
    val account: String? = null,
    val accountRequired: Boolean = false,
    /** The loaded window, filtered by [query] over loaded rows only. */
    val rows: ImmutableList<AgentRow> = persistentListOf(),
    /** Rows in the loaded window before the filter. */
    val loadedCount: Int = 0,
    val hasMore: Boolean = false,
    val hasBefore: Boolean = false,
    val query: String = "",
    val selected: AgentProfile? = null,
    val pending: PendingRow? = null,
    val name: String = "",
    val responsibility: String = "",
    val nameIssue: UiText? = null,
    val responsibilityIssue: UiText? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val problem: UiText? = null,
    val notice: UiText? = null,
    val review: AgentReview? = null
) {
    override fun toString() = "AgentsState(<redacted>)"
}
