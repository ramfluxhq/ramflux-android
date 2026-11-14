// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
sealed interface GroupsEvent {
    data object Back : GroupsEvent
    data object SetupAccount : GroupsEvent
    data class OpenChat(val account: String, val conversation: String, val group: String) : GroupsEvent
}
