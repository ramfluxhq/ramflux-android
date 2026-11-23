// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.CallHistoryEntry
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.MessageDelivery
import org.ramflux.sdk.MessageHistoryEntry
import org.ramflux.sdk.MessageHistoryMetadataReader
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SubscriptionFilter
import org.ramflux.sdk.TimelineAttachment

/** Directory pages read at most to resolve titles for the storage screen. */
private const val TITLE_PAGES = 50

class DefaultMessageHistoryRepository(private val transport: RepositoryTransport) : MessageHistoryRepository, AttachmentHost {
    constructor(client: org.ramflux.sdk.CoreClient) : this(SdkRepositoryTransport { client })
    private val source = DefaultConversationsRepository(transport)
    private val groups = DefaultGroupDirectoryRepository(transport)
    private val sends = linkedMapOf<String, Attempt<SendReceipt>>()
    private val mutations = linkedMapOf<String, Attempt<Unit>>()
    private val devices = DefaultDeviceDirectoryRepository(transport)
    private val readers = HashMap<String, String>()

    override fun repositoryTransport(): RepositoryTransport = transport

    override suspend fun groupHeader(account: String, group: String, conversation: String): ClientResult<ConversationHeader> {
        if (!identity(account) || !identity(group) || !identity(conversation)) return invalid()
        when (val canonical = groups.conversation(account, group)) {
            is ClientResult.Ready -> if (canonical.value != conversation) return invalid()
            is ClientResult.Failed -> return canonical
            is ClientResult.Unavailable -> return canonical
        }
        return when (val detail = groups.detail(account, group)) {
            is ClientResult.Ready -> ClientResult.Ready(ConversationHeader(detail.value.group.name, detail.value.group.id))
            is ClientResult.Failed -> detail
            is ClientResult.Unavailable -> detail
        }
    }

    override suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>> =
        when (val result = page(account, conversation, null)) {
            is ClientResult.Ready -> ClientResult.Ready(result.value.rows)
            is ClientResult.Failed -> result
            is ClientResult.Unavailable -> result
        }

    override suspend fun page(account: String, conversation: String, before: String?): ClientResult<TimelinePage> {
        if (!identity(account) || !identity(conversation)) return invalid()
        val cursor = before?.let {
            if (it.length > 8192) return invalid()
            try {
                Json.parseToJsonElement(it) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return invalid()
        }
        val body = buildJsonObject {
            put("conversation_id", conversation)
            put("read_model", true)
            put("limit", PAGE)
            cursor?.let { put("before_order_key", it) }
        }
        val reply = when (val result = source.messageRead(RepositoryContext(account), body)) {
            is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> result.value
        }
        return when (val decoded = MessageHistoryMetadataReader.page(reply)) {
            is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
            is CoreResult.Success -> {
                val page = decoded.value
                if (page.rows.any { it.conversationId != conversation }) {
                    invalid()
                } else {
                    // The SDK stores a message this device sent with conversation.reply with is_own false; a row
                    // authored by this device (author.device_id is the local device) counts as own as well. The
                    // local device is read (and cached) only when a page has such a candidate row.
                    val candidate = page.rows.any { it is MessageHistoryEntry && !it.own && it.sender != null }
                    val me = if (candidate) (localDevice(account) as? ClientResult.Ready)?.value else null
                    ClientResult.Ready(TimelinePage(page.rows.asReversed().map { summary(it, me) }, page.nextBefore))
                }
            }
        }
    }

    override suspend fun markRead(account: String, conversation: String, message: String): ClientResult<Unit> {
        if (!identity(account) || !identity(conversation) || !identity(message)) return invalid()
        val reader = when (val device = localDevice(account)) {
            is ClientResult.Ready -> device.value
            is ClientResult.Failed -> return device
            is ClientResult.Unavailable -> return device
        }
        // Local projection only: the peer-visible receipt also needs the delivery id of the incoming
        // envelope (`target_delivery_id`), which the read model does not carry.
        val body = SecretBody.fields {
            publicText("conversation_id", conversation)
            publicText("message_id", message)
            publicText("reader_id", reader)
        }
        return when (val result = source.messageReceiptRead(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                ClientResult.Ready(Unit)
            }
        }
    }

    private suspend fun localDevice(account: String): ClientResult<String> {
        synchronized(readers) { readers[account] }?.let { return ClientResult.Ready(it) }
        return when (val listed = devices.list(account)) {
            is ClientResult.Ready -> listed.value.firstOrNull { it.local }?.id?.let { id ->
                synchronized(readers) {
                    if (readers.size >= 8) readers.clear()
                    readers[account] = id
                }
                ClientResult.Ready(id)
            } ?: ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
            is ClientResult.Failed -> listed
            is ClientResult.Unavailable -> listed
        }
    }

    private fun summary(row: org.ramflux.sdk.TimelineMetadata, me: String?): TimelineSummary = when (row) {
        is MessageHistoryEntry -> MessageSummary(
            row.id, row.sender, row.own || (me != null && row.sender == me), row.deleted, row.text, row.createdAt, row.revision, row.edited,
            row.reactions.map { MessageReaction(it.token, it.count) },
            row.receipts.map { MessageReceipt(it.deviceId, it.state) },
            row.replyPreview, row.placeholder, row.service,
            if (row.deleted) emptyList() else row.attachments.map { attachment(row, it) },
        )
        is CallHistoryEntry -> CallSummary(row.id, row.createdAt)
    }

    /** The SDK reader's attachment reference as the media layer's model; key material never reaches either. */
    private fun attachment(row: MessageHistoryEntry, item: TimelineAttachment) = MediaAttachment(
        row.id, item.index, MediaAttachment.kindOf(item.mimeType), item.mimeType, item.fileName, item.size,
        item.thumbnail?.let { MediaThumbnail(it.mimeType, it.width, it.height, it.bytes) }, item.expiresAt,
        conversation = row.conversationId,
    )

    override suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader> {
        if (!identity(account) || !identity(conversation)) return invalid()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(10) {
            val body = buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }
            val reply = when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            val page = when (val result = org.ramflux.sdk.ConversationDirectoryMetadataReader.read(reply)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            page.rows.firstOrNull { it.id == conversation && !it.hidden }?.let {
                val unread = it.unread.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
                return ClientResult.Ready(ConversationHeader(it.title, unread = unread, peer = it.peer, muted = it.muted))
            }
            val next = page.nextCursor ?: return ClientResult.Ready(ConversationHeader(null))
            if (next.length > 8192 || !seen.add(next)) return invalid()
            cursor = next
        }
        return invalid()
    }

    override suspend fun setMuted(account: String, conversation: String, muted: Boolean): ClientResult<Boolean> {
        if (!identity(account) || !identity(conversation)) return invalid()
        val body = SecretBody.fields {
            publicText("conversation_id", conversation)
            publicFlag("unmute", !muted)
        }
        return when (val reply = source.conversationMute(RepositoryContext(account, UUID.randomUUID().toString()), body)) {
            is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
            is CoreResult.Success -> {
                reply.value.close()
                ClientResult.Ready(muted)
            }
        }
    }

    override suspend fun sendAvailability(): ClientResult<Unit> = ClientResult.Ready(Unit)

    override suspend fun conversationTitles(account: String, conversations: Set<String>): ClientResult<Map<String, String>> {
        if (!identity(account)) return invalid()
        val found = HashMap<String, String>()
        var cursor: String? = null
        val seen = HashSet<String>()
        repeat(TITLE_PAGES) {
            if (found.size == conversations.size) return ClientResult.Ready(found)
            val body = buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }
            val reply = when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            val page = when (val result = org.ramflux.sdk.ConversationDirectoryMetadataReader.read(reply)) {
                is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> result.value
            }
            page.rows.forEach { row -> if (row.id in conversations && !row.hidden) found[row.id] = row.title.orEmpty() }
            val next = page.nextCursor ?: return ClientResult.Ready(found)
            if (next.length > 8192 || !seen.add(next)) return ClientResult.Ready(found)
            cursor = next
        }
        return ClientResult.Ready(found)
    }

    /** Direct conversations, and group conversations whose row carries the group id `attachment.prepare.begin` needs. */
    override suspend fun forwardTargets(account: String, cursor: String?): ClientResult<ForwardTargetPage> {
        if (!identity(account) || (cursor?.length ?: 0) > 8192) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        val body = buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }
        val reply = when (val result = source.conversationDirectory(RepositoryContext(account), body)) {
            is CoreResult.Failure -> return ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> result.value
        }
        return when (val page = org.ramflux.sdk.ConversationDirectoryMetadataReader.read(reply)) {
            is CoreResult.Failure -> ClientResult.Failed(page.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(
                ForwardTargetPage(
                    page.value.rows.filter { !it.hidden && (it.kind == "direct" || (it.kind == "group" && it.groupId != null)) }
                        .map { ForwardTarget(it.id, it.title ?: "", it.groupId.takeIf { _ -> it.kind == "group" }) },
                    page.value.nextCursor?.takeIf { it.length <= 8192 }
                )
            )
        }
    }

    override suspend fun send(account: String, conversation: String, text: String, operationId: String): ClientResult<SendReceipt> {
        if (!identity(account) || !identity(conversation) || !usable(text) || !identity(operationId)) return invalid()
        return retain(sendScope(account, conversation), text, operationId, sends) { id ->
            val body = SecretBody.fields {
                publicText("conversation_id", conversation)
                chars("text", text.toCharArray())
            }
            when (val result = source.conversationSend(RepositoryContext(account, id), body)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> when (val delivery = MessageHistoryMetadataReader.delivery(result.value, conversation, null)) {
                    is CoreResult.Failure -> unknown()
                    is CoreResult.Success -> acceptSend(delivery.value)
                }
            }
        }
    }

    override suspend fun edit(
        account: String, conversation: String, message: String, text: String, expectedRevision: Long, operationId: String,
    ): ClientResult<Unit> {
        if (expectedRevision < 0) return invalid()
        val fingerprint = listOf("edit", message, text, expectedRevision.toString()).joinToString("\u0000")
        return mutate(account, conversation, message, text, operationId, fingerprint, setOf("submitted")) {
            SecretBody.fields {
                publicText("conversation_id", conversation)
                publicText("message_id", message)
                chars("text", text.toCharArray())
                publicUnsigned("expected_revision", expectedRevision.toULong())
            }.let { body -> source.conversationEdit(RepositoryContext(account, it), body) }
        }
    }

    override suspend fun reply(
        account: String, conversation: String, message: String, text: String, operationId: String,
    ): ClientResult<Unit> {
        val fingerprint = listOf("reply", message, text).joinToString("\u0000")
        return mutate(account, conversation, message, text, operationId, fingerprint, setOf("submitted")) {
            SecretBody.fields {
                publicText("conversation_id", conversation)
                publicText("message_id", message)
                chars("text", text.toCharArray())
            }.let { body -> source.conversationReply(RepositoryContext(account, it), body) }
        }
    }

    override suspend fun react(
        account: String, conversation: String, message: String, token: String, remove: Boolean, operationId: String,
    ): ClientResult<Unit> {
        if (!ChatReaction.accepts(token)) return invalid()
        val fingerprint = listOf("react", message, token, remove.toString()).joinToString("\u0000")
        return mutate(account, conversation, message, null, operationId, fingerprint, setOf("submitted")) {
            SecretBody.fields {
                publicText("conversation_id", conversation)
                publicText("message_id", message)
                publicText("token", token)
                if (remove) publicFlag("remove", true)
            }.let { body -> source.conversationReact(RepositoryContext(account, it), body) }
        }
    }

    override suspend fun delete(
        account: String, conversation: String, message: String, scope: MessageDeleteScope, operationId: String,
    ): ClientResult<Unit> {
        val scopeName = if (scope == MessageDeleteScope.LOCAL) "local" else "everyone"
        val fingerprint = listOf("delete", message, scopeName).joinToString("\u0000")
        return mutate(account, conversation, message, null, operationId, fingerprint, setOf("submitted", "local")) {
            SecretBody.fields {
                publicText("conversation_id", conversation)
                publicText("message_id", message)
                publicText("scope", scopeName)
            }.let { body -> source.conversationDelete(RepositoryContext(account, it), body) }
        }
    }

    override suspend fun setTyping(account: String, conversation: String, active: Boolean): ClientResult<Unit> {
        if (!identity(account) || !identity(conversation)) return invalid()
        val operation = UUID.randomUUID().toString().replace("-", "")
        if (active) {
            val focused = focus(account, conversation)
            if (focused !is ClientResult.Ready) return focused
        }
        val submitted = submitTyping(account, conversation, operation, active)
        if (active || submitted !is ClientResult.Ready) return submitted
        val blurred = focus(account, null)
        return if (blurred is ClientResult.Ready) submitted else blurred
    }

    override fun updates(account: String): Flow<ClientResult<ChatSignal>> = flow {
        if (!identity(account)) {
            emit(invalid())
            return@flow
        }
        val filter = SubscriptionFilter(account, buildJsonObject {
            // Named topics only (scale rule 7): inserts, edits/receipts/tombstones, directory changes, typing.
            put("topics", buildJsonArray { TOPICS.forEach { add(JsonPrimitive(it)) } })
        })
        source.updates(filter).collect { update ->
            when (update) {
                is CoreUpdate.Events -> {
                    val typing = MessageHistoryMetadataReader.peerTyping(update.body, System.currentTimeMillis())
                    emit(ClientResult.Ready(ChatSignal(
                        refresh = true,
                        peerTyping = typing?.let { PeerTyping(it.conversationId, it.active, it.expiresAtMs) },
                    )))
                }
                is CoreUpdate.Failure -> emit(ClientResult.Failed(update.error.appProblem()))
                is CoreUpdate.Terminal -> {
                    if (update.kind == "resync_required") {
                        transport.resetEvents(update.subscriptionGeneration)
                        emit(ClientResult.Ready(ChatSignal(refresh = true, resubscribe = true)))
                    } else {
                        emit(ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE)))
                    }
                }
            }
        }
    }

    private suspend fun mutate(
        account: String,
        conversation: String,
        message: String,
        text: String?,
        operationId: String,
        fingerprint: String,
        states: Set<String>,
        call: suspend (String) -> CoreResult<org.ramflux.sdk.SecretReply>,
    ): ClientResult<Unit> {
        if (!identity(account) || !identity(conversation) || !identity(message) || !identity(operationId)) return invalid()
        if (text != null && !usable(text)) return invalid()
        return retain(mutationScope(account, conversation), fingerprint, operationId, mutations) { id ->
            when (val result = call(id)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> when (val delivery = MessageHistoryMetadataReader.delivery(result.value, conversation, message)) {
                    is CoreResult.Failure -> unknown()
                    is CoreResult.Success -> acceptMutation(delivery.value, states)
                }
            }
        }
    }

    private suspend fun focus(account: String, conversation: String?): ClientResult<Unit> {
        val body = SecretBody.takeJson(buildJsonObject {
            put("conversation_id", conversation?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString().toByteArray())
        return when (val result = source.messageFocus(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                ClientResult.Ready(Unit)
            }
        }
    }

    private suspend fun submitTyping(account: String, conversation: String, operation: String, active: Boolean): ClientResult<Unit> {
        val encoded = Base64.getEncoder().encodeToString(operation.toByteArray(Charsets.UTF_8))
        val typing = buildJsonObject { put("active", active) }
        val content = buildJsonObject { put("typing", typing) }
        val send = buildJsonObject {
            put("conversationId", conversation)
            put("operationId", encoded)
            put("content", content)
        }
        val body = SecretBody.takeJson(buildJsonObject {
            put("requestId", operation)
            put("accountId", account)
            put("send", send)
        }.toString().toByteArray())
        return when (val result = source.messageSubmit(RepositoryContext(account, operation), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                ClientResult.Ready(Unit)
            }
        }
    }

    private fun acceptSend(delivery: MessageDelivery): ClientResult<SendReceipt> = when {
        delivery.partial -> unknown()
        delivery.state == "submitted" && delivery.messageId.isNotEmpty() -> ClientResult.Ready(SendReceipt(delivery.messageId))
        delivery.state == "failed" -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
        else -> unknown()
    }

    private fun acceptMutation(delivery: MessageDelivery, states: Set<String>): ClientResult<Unit> = when {
        delivery.partial -> unknown()
        delivery.state == "failed" -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
        delivery.state in states -> ClientResult.Ready(Unit)
        else -> unknown()
    }

    private suspend fun <T> retain(
        scope: String,
        fingerprint: String,
        operationId: String,
        store: MutableMap<String, Attempt<T>>,
        call: suspend (String) -> ClientResult<T>,
    ): ClientResult<T> {
        val key = scope + "\u0000" + operationId
        val existing = synchronized(store) { store[key] }
        if (existing != null) {
            if (existing.fingerprint != fingerprint) return invalid()
            val stored = existing.result
            if (stored == null) return unknown()
            if (stored is ClientResult.Ready) return stored
            if (stored is ClientResult.Failed && stored.problem.outcome == ClientOutcome.UNKNOWN) {
                val again = call(existing.operationId)
                if (again !is ClientResult.Failed || again.problem.outcome != ClientOutcome.UNKNOWN) {
                    synchronized(store) { existing.result = again }
                }
                return again
            }
            return stored
        }
        val attempt = Attempt<T>(operationId, fingerprint, null)
        synchronized(store) {
            if (store.containsKey(key)) return unknown()
            store[key] = attempt
            prune(store)
        }
        return try {
            val result = call(operationId)
            synchronized(store) { attempt.result = result }
            result
        } catch (cancelled: CancellationException) {
            synchronized(store) { if (store[key] === attempt) store.remove(key) }
            throw cancelled
        }
    }

    private fun <T> prune(store: MutableMap<String, Attempt<T>>) {
        while (store.size > 64) {
            val drop = store.entries.firstOrNull { entry ->
                val result = entry.value.result
                result is ClientResult.Ready ||
                    (result is ClientResult.Failed && result.problem.outcome != ClientOutcome.UNKNOWN)
            }?.key ?: return
            store.remove(drop)
        }
    }

    private fun sendScope(account: String, conversation: String) = account + "\u0000" + conversation
    private fun mutationScope(account: String, conversation: String) = account + "\u0000" + conversation + "\u0000mutation"
    private fun identity(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256
    private fun usable(text: String) = text.trim().isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 65536
    private fun invalid(): ClientResult<Nothing> = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun unknown(): ClientResult<Nothing> = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))

    internal companion object {
        private const val PAGE = 100
        fun subscriptionTopics(): List<String> = TOPICS
        private val TOPICS = listOf("message.received", "group.message.received", "message.updated", "conversation.changed", "message.transient")
    }

    private class Attempt<T>(val operationId: String, val fingerprint: String, var result: ClientResult<T>?)
}
