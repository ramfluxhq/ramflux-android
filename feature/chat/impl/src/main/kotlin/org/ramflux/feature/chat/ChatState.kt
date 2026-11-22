// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
/** Timeline entries carry typed values; the screen formats times and dates for the current locale. */
@Immutable sealed interface TimelineItem {
    val id: String

    /** Creation time in epoch seconds, or null when the read model has none. */
    val sentAt: Long?
}

@Immutable data class CallItem(override val id: String, override val sentAt: Long?) : TimelineItem

/** The strongest delivery state the read model reports for an own message. */
enum class Receipt { SENT, DELIVERED, READ }

enum class ComposerMode { SEND, REPLY, EDIT }

@Immutable data class MessageItem(
    override val id: String,
    val sender: String,
    val own: Boolean,
    val deleted: Boolean,
    val content: UiText,
    override val sentAt: Long? = null,
    val revision: Long = 0,
    val edited: Boolean = false,
    val replyPreview: UiText? = null,
    val placeholder: Boolean = false,
    val service: Boolean = false,
    val receipt: Receipt? = null,
    val reaction: UiText? = null
) : TimelineItem {
    override fun toString() = "MessageItem(<redacted>)"
}

@Immutable data class ChatState(
    val conversationId: String = "",
    val groupId: String? = null,
    val title: UiText? = null,
    val headerProblem: UiText? = null,
    val rows: ImmutableList<TimelineItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sending: Boolean = false,
    val draft: String = "",
    val canSend: Boolean = false,
    val problem: UiText? = null,
    val unavailable: UiText? = null,
    val composer: ComposerMode = ComposerMode.SEND,
    val actionTarget: String? = null,
    val expectedRevision: Long = 0,
    val selectedId: String? = null,
    val peerTyping: Boolean = false,
    val deliveryNotice: UiText? = null,
    val typingProblem: UiText? = null,
    /** The first unread message, when the read model reports one; the timeline draws the unread divider above it. */
    val firstUnreadId: String? = null
) {
    override fun toString() = "ChatState(<redacted>)"
}
