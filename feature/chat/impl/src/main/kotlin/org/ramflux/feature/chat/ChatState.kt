// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
@Immutable data class MessageItem(val id: String, val sender: String, val own: Boolean, val deleted: Boolean, val content: UiText) {
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
    val problem: UiText? = null,
    val unavailable: UiText? = null
) {
    override fun toString() = "ChatState(<redacted>)"
}
