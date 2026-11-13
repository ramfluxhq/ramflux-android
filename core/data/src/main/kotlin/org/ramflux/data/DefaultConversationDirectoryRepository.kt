// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultConversationDirectoryRepository(transport: RepositoryTransport) : ConversationDirectoryRepository {
    private val source = DefaultConversationsRepository(transport)
    override suspend fun page(account: String, cursor: String?): ClientResult<ConversationPage> {
        if (account.isBlank() || account.length > 4096 || (cursor?.length ?: 0) > 8192) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        val body = buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }
        return when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = ConversationDirectoryMetadataReader.read(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(ConversationPage(decoded.value.rows.filterNot { it.hidden }.map {
                    ConversationSummary(it.id, it.title ?: it.id, it.unread, it.archived)
                }, decoded.value.nextCursor))
            }
        }
    }
    override suspend fun searchAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONVERSATIONS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.FEATURE_NOT_INSTALLED)
}
