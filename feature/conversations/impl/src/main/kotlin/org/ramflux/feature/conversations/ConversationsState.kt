// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.UnavailableReason

@Immutable data class ConversationItem(
    val id: String,
    val title: String,
    val summary: String,
    val unread: String,
    val hasUnread: Boolean,
    val timestamp: String = ""
)

@Immutable data class ConversationsState(
    val accountId: String? = null,
    val accountLabel: String? = null,
    val rows: ImmutableList<ConversationItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val accountRequired: Boolean = false,
    val problem: String? = null,
    val problemKind: ClientFailureKind? = null,
    val unavailable: String? = null,
    val unavailableReason: UnavailableReason? = null,
    val searchAvailable: Boolean = false
)
