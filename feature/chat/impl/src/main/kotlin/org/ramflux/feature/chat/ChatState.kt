// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
@Immutable sealed interface TimelineItem {
    val id: String
    val time: UiText
}

@Immutable data class CallItem(override val id: String, override val time: UiText) : TimelineItem

@Immutable data class MessageItem(
    override val id: String,
    val sender: String,
    val own: Boolean,
    val deleted: Boolean,
    val content: UiText,
    override val time: UiText = UiText.Empty
) : TimelineItem {
    override fun toString() = "MessageItem(<redacted>)"
}

@Immutable data class ChatState(
    val conversationId: String = "",
    val title: UiText? = null,
    val headerProblem: UiText? = null,
    val rows: ImmutableList<TimelineItem> = persistentListOf(),
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
