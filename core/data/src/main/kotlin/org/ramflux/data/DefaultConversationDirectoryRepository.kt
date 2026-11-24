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
    private val directory = PeerDirectory(contacts)
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
                    // Names come from the SDK; the AI marker of direct peers from one contact lookup per page.
                    val wanted = visible.filter { it.direct() }.flatMapTo(HashSet()) { it.peerKeys() }
                    val peers = directory.peers(account, wanted)
                    ClientResult.Ready(ConversationPage(visible.map { it.summary(peers) }, decoded.value.nextCursor))
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
                is CoreUpdate.Events -> emit(ClientResult.Ready(named(account, change(ConversationDirectoryMetadataReader.change(update.body)))))
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
        if (row.hidden) return ConversationChange.Remove(row.id)
        synchronized(pendingPeers) {
            pendingPeers.remove(row.id)
            if (row.direct()) pendingPeers[row.id] = row.peerKeys()
        }
        return ConversationChange.Upsert(row.summary(emptyMap()), row.title != null || row.peerName != null)
    }

    /** Peer keys of the direct rows of recent events, read by [named]; bounded. */
    private val pendingPeers = object : LinkedHashMap<String, List<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 64
    }

    /** A direct row from an event takes its peer's AI marker (and, without an SDK name, the contact name). */
    private suspend fun named(account: String, change: ConversationChange): ConversationChange {
        val pending = change as? ConversationChange.Upsert ?: return change
        val keys = synchronized(pendingPeers) { pendingPeers[pending.row.id] } ?: return change
        val peers = directory.peers(account, keys.toSet())
        val peer = keys.firstNotNullOfOrNull { peers[it] } ?: return change
        val name = peer.name?.takeIf { pending.row.titleIsIdentifier }
        return ConversationChange.Upsert(
            pending.row.copy(title = name ?: pending.row.title, titleIsIdentifier = pending.row.titleIsIdentifier && name == null, ai = peer.ai),
            titled = pending.titled || name != null
        )
    }

    override suspend fun searchAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONVERSATIONS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit> =
        if (account.isNotBlank() && conversation.isNotBlank()) ClientResult.Ready(Unit)
        else ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    private fun ConversationDirectoryEntry.direct() = kind != "group" && groupId == null

    private fun ConversationDirectoryEntry.peerKeys() = PeerDirectory.peers(peer, participants)

    /** The SDK's title, else its name of the direct peer; a contact name only when the SDK has none; else a short id. */
    private fun ConversationDirectoryEntry.summary(peers: Map<String, Peer>): ConversationSummary {
        val group = kind == "group"
        val known = if (direct()) peerKeys().firstNotNullOfOrNull { peers[it] } else null
        val sdkTitle = title?.takeIf { it.isNotBlank() } ?: peerName.takeIf { direct() }
        val named = if (sdkTitle == null) known?.name else null
        val identifier = sdkTitle == null && named == null && !group
        val shown = sdkTitle ?: named ?: if (identifier) PeerDirectory.shortIdentifier(peer, id) else id
        return ConversationSummary(
            id, shown, unread, archived, isUnread, lastActivity, muted, group, groupId.takeIf { group },
            titleIsIdentifier = identifier, ai = known?.ai == true
        )
    }

    companion object {
        const val PAGE = 100

        /** Journal topics of the pinned SDK (`bus/journal/project.rs`): rows, new messages and inbound requests. */
        private val REQUEST_TOPICS = setOf("contact.requested", "contact.accepted", "contact.declined", "contact.blocked", "contact.request.expired")
        val TOPICS = listOf("conversation.changed", "message.received") + REQUEST_TOPICS
    }
}
