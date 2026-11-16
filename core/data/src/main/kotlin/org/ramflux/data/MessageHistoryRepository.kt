// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface TimelineSummary { val id: String; val createdAt: Long? }
data class MessageReaction(val token: String, val count: Int) {
    override fun toString() = "MessageReaction(<redacted>)"
}
data class MessageReceipt(val deviceId: String, val state: String) {
    override fun toString() = "MessageReceipt(<redacted>)"
}
data class MessageSummary(
    override val id: String,
    val sender: String?,
    val own: Boolean,
    val deleted: Boolean,
    val text: String?,
    override val createdAt: Long? = null,
    val revision: Long = 0,
    val edited: Boolean = false,
    val reactions: List<MessageReaction> = emptyList(),
    val receipts: List<MessageReceipt> = emptyList(),
    val replyPreview: String? = null,
    val placeholder: Boolean = false,
    val service: Boolean = false,
) : TimelineSummary {
    override fun toString() = "MessageSummary(<redacted>)"
}
data class CallSummary(override val id: String, override val createdAt: Long?) : TimelineSummary {
    override fun toString() = "CallSummary(<redacted>)"
}
data class ConversationHeader(val title: String?, val groupId: String? = null) {
    override fun toString() = "ConversationHeader(<redacted>)"
}
data class SendReceipt(val messageId: String) {
    override fun toString() = "SendReceipt(<redacted>)"
}
data class PeerTyping(val conversationId: String, val active: Boolean, val expiresAtMs: Long) {
    override fun toString() = "PeerTyping(<redacted>)"
}
data class ChatSignal(
    val refresh: Boolean,
    val peerTyping: PeerTyping? = null,
    val resubscribe: Boolean = false,
)
enum class MessageDeleteScope { LOCAL, EVERYONE }

/** One grapheme the conversation.react intent accepts. The UI label stays in resources. */
object ChatReaction {
    const val ACK: String = "+"
}

interface MessageHistoryRepository {
    suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>>
    suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader>
    /** Group presentation is enabled only after canonical group/conversation readback. */
    suspend fun groupHeader(account: String, group: String, conversation: String): ClientResult<ConversationHeader> =
        ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
    suspend fun sendAvailability(): ClientResult<Unit>
    suspend fun send(
        account: String,
        conversation: String,
        text: String,
        operationId: String,
    ): ClientResult<SendReceipt>
    suspend fun edit(
        account: String,
        conversation: String,
        message: String,
        text: String,
        expectedRevision: Long,
        operationId: String,
    ): ClientResult<Unit> = unavailable()
    suspend fun reply(
        account: String,
        conversation: String,
        message: String,
        text: String,
        operationId: String,
    ): ClientResult<Unit> = unavailable()
    suspend fun react(
        account: String,
        conversation: String,
        message: String,
        token: String,
        remove: Boolean,
        operationId: String,
    ): ClientResult<Unit> = unavailable()
    suspend fun delete(
        account: String,
        conversation: String,
        message: String,
        scope: MessageDeleteScope,
        operationId: String,
    ): ClientResult<Unit> = unavailable()
    suspend fun setTyping(account: String, conversation: String, active: Boolean): ClientResult<Unit> = unavailable()
    fun updates(account: String): Flow<ClientResult<ChatSignal>> = emptyFlow()
}

private fun unavailable(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
