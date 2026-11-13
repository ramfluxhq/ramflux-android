// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultMessageHistoryRepository(transport: RepositoryTransport) : MessageHistoryRepository {
    private val source = DefaultConversationsRepository(transport)
    private val groups = DefaultGroupDirectoryRepository(transport)
    override suspend fun groupHeader(account: String, group: String, conversation: String): ClientResult<ConversationHeader> {
        if (account.isBlank() || group.isBlank() || conversation.isBlank() ||
            account.length > 4096 || group.length > 4096 || conversation.length > 4096) return invalidHeader()
        when (val canonical = groups.conversation(account, group)) {
            is ClientResult.Ready -> if (canonical.value != conversation) return invalidHeader()
            is ClientResult.Failed -> return canonical
            is ClientResult.Unavailable -> return canonical
        }
        return when (val detail = groups.detail(account, group)) {
            is ClientResult.Ready -> ClientResult.Ready(ConversationHeader(detail.value.group.name, detail.value.group.id))
            is ClientResult.Failed -> detail
            is ClientResult.Unavailable -> detail
        }
    }
    override suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>> {
        if (account.isBlank() || conversation.isBlank() || account.length > 4096 || conversation.length > 4096) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        return when (val result = source.messageList(RepositoryContext(account), buildJsonObject { put("conversation_id", conversation) })) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = MessageHistoryMetadataReader.read(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> {
                    if (decoded.value.any { it.conversationId != conversation }) ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
                    else ClientResult.Ready(decoded.value.map { when (it) {
                        is MessageHistoryEntry -> MessageSummary(it.id, it.sender, it.own, it.deleted, it.text, it.createdAt)
                        is CallHistoryEntry -> CallSummary(it.id, it.createdAt)
                    } })
                }
            }
        }
    }
    override suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader> {
        if (account.isBlank() || conversation.isBlank() || account.length > 4096 || conversation.length > 4096) return invalidHeader()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(10) {
            val body = buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }
            val reply = when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            val page = when (val result = ConversationDirectoryMetadataReader.read(reply)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            page.rows.firstOrNull { it.id == conversation && !it.hidden }?.let { return ClientResult.Ready(ConversationHeader(it.title)) }
            val next = page.nextCursor ?: return ClientResult.Ready(ConversationHeader(null))
            if (next.length > 8192 || !seen.add(next)) return invalidHeader()
            cursor = next
        }
        return invalidHeader()
    }
    private fun invalidHeader() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
    override suspend fun sendAvailability(): ClientResult<Unit> = unavailable()
    override suspend fun send(account: String, conversation: String, text: String): ClientResult<Unit> = unavailable()
    /** Routing, encryption and recipient selection must come from the published high-level SDK send. */
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
}
