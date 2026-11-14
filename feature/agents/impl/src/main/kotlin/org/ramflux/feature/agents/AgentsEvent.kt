// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
sealed interface AgentsEvent {
    data object Back : AgentsEvent
    data object SetupAccount : AgentsEvent
}
