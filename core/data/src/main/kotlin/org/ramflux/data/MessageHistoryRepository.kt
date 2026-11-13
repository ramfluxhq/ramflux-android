// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

data class MessageSummary(val id: String, val sender: String?, val own: Boolean, val deleted: Boolean, val text: String?) {
    override fun toString() = "MessageSummary(<redacted>)"
}
interface MessageHistoryRepository {
    suspend fun read(account: String, conversation: String): ClientResult<List<MessageSummary>>
    suspend fun sendAvailability(): ClientResult<Unit>
    suspend fun send(account: String, conversation: String, text: String): ClientResult<Unit>
}
