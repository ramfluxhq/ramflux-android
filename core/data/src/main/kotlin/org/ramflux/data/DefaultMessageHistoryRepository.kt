// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultMessageHistoryRepository(transport: RepositoryTransport) : MessageHistoryRepository {
    private val source = DefaultConversationsRepository(transport)
    override suspend fun read(account: String, conversation: String): ClientResult<List<MessageSummary>> {
        if (account.isBlank() || conversation.isBlank() || account.length > 4096 || conversation.length > 4096) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        return when (val result = source.messageList(RepositoryContext(account), buildJsonObject { put("conversation_id", conversation) })) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = MessageHistoryMetadataReader.read(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> {
                    if (decoded.value.any { it.conversationId != conversation }) ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
                    else ClientResult.Ready(decoded.value.map { MessageSummary(it.id, it.sender, it.own, it.deleted, it.text) })
                }
            }
        }
    }
    override suspend fun sendAvailability(): ClientResult<Unit> = unavailable()
    override suspend fun send(account: String, conversation: String, text: String): ClientResult<Unit> = unavailable()
    /** Routing, encryption and recipient selection must come from the published high-level SDK send. */
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
}
