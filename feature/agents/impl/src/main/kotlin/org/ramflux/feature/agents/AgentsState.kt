// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.runtime.Immutable
import org.ramflux.data.AgentAvatar
import org.ramflux.data.AgentCapability
import org.ramflux.designsystem.UiText
@Immutable data class AgentsState(
    val mode: AgentCapability = AgentCapability.DIRECTORY,
    val name: String = "",
    val goal: String = "",
    val avatar: AgentAvatar = AgentAvatar.ORBIT,
    val nameIssue: UiText? = null,
    val goalIssue: UiText? = null,
    val loading: Boolean = false,
    val accountRequired: Boolean = false,
    val unavailable: Boolean = false,
    val problem: UiText? = null
) {
    override fun toString() = "AgentsState(<redacted>)"
}
