// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.UnavailableReason
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.UiText

@Immutable data class ConversationItem(
    val id: String,
    val title: String,
    val summary: UiText,
    val unread: String,
    val hasUnread: Boolean,
    val timestamp: UiText = UiText.Empty,
    val muted: Boolean = false,
    val group: Boolean = false,
    val groupId: String? = null,
    /** [title] is a short identifier (no contact name is known); shown left-to-right isolated. */
    val identifier: Boolean = false,
    /** The direct peer is an SDK-verified AI agent: shown with the AI marker, otherwise like a person. */
    val ai: Boolean = false,
    /** The direct peer's contact identifier, to match identity changes against; null for a group. */
    val peer: String? = null
) {
    /** The title as drawn: identifiers through the left-to-right identifier primitive. */
    val shownTitle: String get() = if (identifier) Bidi.ltr(title) else title
}

@Immutable data class ConversationsState(
    val accountId: String? = null,
    val accountLabel: String? = null,
    val rows: ImmutableList<ConversationItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    /** False after a page that added no row to the bounded window: further pages load on request only. */
    val autoLoad: Boolean = true,
    /** Pending inbound contact requests (the message-requests row); null when they could not be read. */
    val requests: Int? = null,
    val accountRequired: Boolean = false,
    val problem: UiText? = null,
    val problemKind: ClientFailureKind? = null,
    val unavailable: UiText? = null,
    val unavailableReason: UnavailableReason? = null,
    val searchAvailable: Boolean = false,
    /** Contacts whose identity is changed and not accepted (the list shows a warning glyph on their direct rows). */
    val changedPeers: ImmutableSet<String> = persistentSetOf()
)
