// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultConversationDirectoryRepository(
    private val transport: RepositoryTransport,
    private val contacts: ContactDirectoryRepository = DefaultContactDirectoryRepository(transport)
) : ConversationDirectoryRepository {
    private val source = DefaultConversationsRepository(transport)
    override suspend fun page(account: String, cursor: String?): ClientResult<ConversationPage> {
        if (account.isBlank() || account.length > 4096 || (cursor?.length ?: 0) > 8192) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        val body = buildJsonObject { put("limit", PAGE); cursor?.let { put("cursor", it) } }
        return when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = ConversationDirectoryMetadataReader.read(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> {
                    val visible = decoded.value.rows.filterNot { it.hidden }
                    ClientResult.Ready(ConversationPage(visible.map { it.summary() }, decoded.value.nextCursor))
                }
            }
        }
    }

    override suspend fun pendingRequests(account: String): ClientResult<Int> = when (val listed = contacts.requests(account)) {
        is ClientResult.Ready -> ClientResult.Ready(
            listed.value.count { it.direction == RequestDirection.INBOUND && it.status == ContactRequestStatus.PENDING }
        )
        is ClientResult.Failed -> listed
        is ClientResult.Unavailable -> listed
    }

    override fun changes(account: String): Flow<ClientResult<ConversationChange>> = flow {
        if (account.isBlank() || account.length > 4096) {
            emit(ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE)))
            return@flow
        }
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { TOPICS.forEach { add(it) } }) })
        transport.updates(filter).collect { update ->
            when (update) {
                is CoreUpdate.Events -> emit(ClientResult.Ready(change(ConversationDirectoryMetadataReader.change(update.body))))
                is CoreUpdate.Failure -> emit(ClientResult.Failed(update.error.appProblem()))
                // The shared event hub has already reset the native queue: reload the window.
                is CoreUpdate.Terminal -> emit(
                    if (update.kind == "resync_required") ClientResult.Ready(ConversationChange.Reload)
                    else ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
                )
            }
        }
    }

    /** The topic decides the change; the row is read by the bounded SDK reader, never as a JSON tree. */
    internal fun change(event: ConversationDirectoryEvent): ConversationChange {
        if (event.topic in REQUEST_TOPICS) return ConversationChange.Requests
        val row = event.row ?: return ConversationChange.Reload
        return if (row.hidden) ConversationChange.Remove(row.id) else ConversationChange.Upsert(row.summary(), row.title != null)
    }

    override suspend fun searchAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONVERSATIONS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit> =
        if (account.isNotBlank() && conversation.isNotBlank()) ClientResult.Ready(Unit)
        else ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    private fun ConversationDirectoryEntry.summary() =
        ConversationSummary(id, title ?: id, unread, archived, isUnread, lastActivity, muted, kind == "group", groupId.takeIf { kind == "group" })

    companion object {
        const val PAGE = 100

        /** Journal topics of the pinned SDK (`bus/journal/project.rs`): rows, new messages and inbound requests. */
        private val REQUEST_TOPICS = setOf("contact.requested", "contact.accepted", "contact.declined", "contact.blocked", "contact.request.expired")
        val TOPICS = listOf("conversation.changed", "message.received") + REQUEST_TOPICS
    }
}
