// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import org.ramflux.data.AgentAvatar
import org.ramflux.data.AgentCapability
sealed interface AgentsAction {
    data class Visible(val value: Boolean) : AgentsAction
    data class Mode(val value: AgentCapability) : AgentsAction
    data class Name(val value: String) : AgentsAction
    data class Goal(val value: String) : AgentsAction
    data class Avatar(val value: AgentAvatar) : AgentsAction
    data object Create : AgentsAction
    data object Refresh : AgentsAction
    data object Back : AgentsAction
    data object SetupAccount : AgentsAction
}
