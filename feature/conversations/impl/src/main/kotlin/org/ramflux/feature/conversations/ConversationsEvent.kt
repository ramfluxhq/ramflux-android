// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
sealed interface ConversationsEvent {
    data object Back : ConversationsEvent
    data object SetupAccount : ConversationsEvent
    data object Compose : ConversationsEvent
    data object Requests : ConversationsEvent
    data class OpenChat(val accountId: String, val conversationId: String, val title: String, val groupId: String? = null) : ConversationsEvent
}
