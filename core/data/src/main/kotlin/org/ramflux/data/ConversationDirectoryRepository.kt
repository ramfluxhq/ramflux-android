// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Counts and visibility come from the SDK's reader-qualified projection. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val unread: ULong,
    val archived: Boolean,
    val isUnread: Boolean,
    val lastActivity: Long? = null,
    val muted: Boolean = false,
    val group: Boolean = false
)
data class ConversationPage(val rows: List<ConversationSummary>, val nextCursor: String?)

/** One live change of the directory, from the shared event subscription. */
sealed interface ConversationChange {
    /** A changed row. [titled] is false when the event carried no title; keep the one already shown. */
    data class Upsert(val row: ConversationSummary, val titled: Boolean) : ConversationChange
    data class Remove(val id: String) : ConversationChange

    /** Inbound contact requests changed; recount the message-requests row. */
    data object Requests : ConversationChange

    /** The event queue was reset or the event had no usable row: reload the loaded window. */
    data object Reload : ConversationChange
}

interface ConversationDirectoryRepository {
    suspend fun page(account: String, cursor: String? = null): ClientResult<ConversationPage>
    suspend fun searchAvailability(): ClientResult<Unit>
    suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit>

    /** Pending inbound contact requests: the message-requests row of the list. */
    suspend fun pendingRequests(account: String): ClientResult<Int> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    /** Live changes for [account]; collect while the list is visible. */
    fun changes(account: String): Flow<ClientResult<ConversationChange>> = emptyFlow()
}
