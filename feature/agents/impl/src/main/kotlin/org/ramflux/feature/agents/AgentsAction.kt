// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
sealed interface AgentsAction {
    data class Visible(val value: Boolean) : AgentsAction
    data object Refresh : AgentsAction
    data object Back : AgentsAction
    data object SetupAccount : AgentsAction
    data class Query(val value: String) : AgentsAction

    /** Loads the next ([forward]) or previous window page of agents. */
    data class More(val forward: Boolean = true) : AgentsAction
    data class Select(val id: String) : AgentsAction
    data object Edit : AgentsAction
    data class Name(val value: String) : AgentsAction
    data class Responsibility(val value: String) : AgentsAction
    data object Save : AgentsAction
    data object Delete : AgentsAction
    data object Message : AgentsAction
    data object Confirm : AgentsAction
    data object Deny : AgentsAction
}
