// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
@Immutable data class MessageItem(val id: String, val sender: String, val own: Boolean, val deleted: Boolean, val content: String) {
    override fun toString() = "MessageItem(<redacted>)"
}

@Immutable data class ChatState(
    val conversationId: String = "",
    val rows: ImmutableList<MessageItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sending: Boolean = false,
    val draft: String = "",
    val canSend: Boolean = false,
    val problem: String? = null,
    val unavailable: String? = null
) {
    override fun toString() = "ChatState(<redacted>)"
}
