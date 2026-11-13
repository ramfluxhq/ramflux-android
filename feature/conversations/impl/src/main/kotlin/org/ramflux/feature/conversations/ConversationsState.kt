// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*

@Immutable data class ConversationItem(val id: String, val title: String, val summary: String, val unread: String, val hasUnread: Boolean)

@Immutable data class ConversationsState(
    val accountId: String? = null,
    val rows: ImmutableList<ConversationItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val accountRequired: Boolean = false,
    val problem: String? = null,
    val unavailable: String? = null,
    val searchAvailable: Boolean = false
)
