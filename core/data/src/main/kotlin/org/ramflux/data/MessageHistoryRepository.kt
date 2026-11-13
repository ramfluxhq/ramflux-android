// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

sealed interface TimelineSummary { val id: String; val createdAt: Long? }
data class MessageSummary(override val id: String, val sender: String?, val own: Boolean, val deleted: Boolean, val text: String?, override val createdAt: Long? = null) : TimelineSummary {
    override fun toString() = "MessageSummary(<redacted>)"
}
data class CallSummary(override val id: String, override val createdAt: Long?) : TimelineSummary {
    override fun toString() = "CallSummary(<redacted>)"
}
data class ConversationHeader(val title: String?) {
    override fun toString() = "ConversationHeader(<redacted>)"
}
interface MessageHistoryRepository {
    suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>>
    suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader>
    suspend fun sendAvailability(): ClientResult<Unit>
    suspend fun send(account: String, conversation: String, text: String): ClientResult<Unit>
}
