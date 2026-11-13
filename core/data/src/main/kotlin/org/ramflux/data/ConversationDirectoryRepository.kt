// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** Counts and visibility come from the SDK's reader-qualified projection. */
data class ConversationSummary(val id: String, val title: String, val unread: ULong, val archived: Boolean, val isUnread: Boolean, val lastActivity: Long? = null)
data class ConversationPage(val rows: List<ConversationSummary>, val nextCursor: String?)
interface ConversationDirectoryRepository {
    suspend fun page(account: String, cursor: String? = null): ClientResult<ConversationPage>
    suspend fun searchAvailability(): ClientResult<Unit>
    suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit>
}
