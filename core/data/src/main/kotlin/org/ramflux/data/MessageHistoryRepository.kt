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
    /** The `content.message.media[]` references, addressed as (message id, index). */
    val attachments: List<MediaAttachment> = emptyList(),
) : TimelineSummary {
    override fun toString() = "MessageSummary(<redacted>)"
}
data class CallSummary(override val id: String, override val createdAt: Long?) : TimelineSummary {
    override fun toString() = "CallSummary(<redacted>)"
}
/**
 * [unread] is the reader-scoped unread count of `conversation.directory`; the read-model page has no read
 * position, so it is the only unread signal.
 */
data class ConversationHeader(val title: String?, val groupId: String? = null, val unread: Long = 0) {
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

/** Reaction tokens: the SDK accepts exactly one grapheme, at most 64 bytes, no controls or blanks. */
object ChatReaction {
    /** The acknowledgement token other clients send; its UI label stays in resources. */
    const val ACK: String = "+"

    fun accepts(token: String): Boolean {
        if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 64) return false
        if (token.any { it.isISOControl() } || token.isBlank()) return false
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(token)
        iterator.first()
        return iterator.next() == token.length && iterator.next() == java.text.BreakIterator.DONE
    }
}

/** One read-model page in reading order (oldest first). [older] is the opaque cursor of the previous page. */
data class TimelinePage(val rows: List<TimelineSummary>, val older: String?) {
    override fun toString() = "TimelinePage(<redacted>)"
}

interface MessageHistoryRepository {
    suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>>

    /** The newest page when [before] is null, otherwise the page older than the [TimelinePage.older] cursor. */
    suspend fun page(account: String, conversation: String, before: String? = null): ClientResult<TimelinePage> =
        if (before != null) {
            ClientResult.Ready(TimelinePage(emptyList(), null))
        } else {
            when (val rows = read(account, conversation)) {
                is ClientResult.Ready -> ClientResult.Ready(TimelinePage(rows.value, null))
                is ClientResult.Failed -> rows
                is ClientResult.Unavailable -> rows
            }
        }

    /** Moves this device's read position to [message] (local projection; see the implementation). */
    suspend fun markRead(account: String, conversation: String, message: String): ClientResult<Unit> = unavailable()
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
