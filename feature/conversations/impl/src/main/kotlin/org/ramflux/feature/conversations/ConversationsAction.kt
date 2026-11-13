// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
sealed interface ConversationsAction {
    data object Refresh : ConversationsAction
    data object LoadMore : ConversationsAction
    data object Back : ConversationsAction
    data object SetupAccount : ConversationsAction
    data class Open(val id: String) : ConversationsAction
}
