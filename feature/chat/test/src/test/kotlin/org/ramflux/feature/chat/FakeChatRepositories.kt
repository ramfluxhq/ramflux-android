// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
import org.ramflux.data.cards.CardSessionRepository
import org.ramflux.data.cards.UnavailableCardSessionRepository
class FakeChatHistory : MessageHistoryRepository {
    var reads = 0
    var sends = 0
    var reading: suspend (String) -> ClientResult<List<TimelineSummary>> = { ClientResult.Ready(emptyList()) }
    override suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>> {
        reads++
        return reading(conversation)
    }
    var paging: (suspend (String?) -> ClientResult<TimelinePage>)? = null
    val pageCursors = mutableListOf<String?>()
    override suspend fun page(account: String, conversation: String, before: String?): ClientResult<TimelinePage> {
        val source = paging ?: return super.page(account, conversation, before)
        reads++
        pageCursors.add(before)
        return source(before)
    }
    val marked = mutableListOf<String>()
    var markResult: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun markRead(account: String, conversation: String, message: String): ClientResult<Unit> {
        marked.add(message)
        return markResult
    }
    val reactions = mutableListOf<String>()
    override suspend fun react(
        account: String,
        conversation: String,
        message: String,
        token: String,
        remove: Boolean,
        operationId: String
    ): ClientResult<Unit> {
        reactions.add(token)
        return ClientResult.Ready(Unit)
    }
    val deleted = mutableListOf<Pair<String, MessageDeleteScope>>()
    override suspend fun delete(
        account: String,
        conversation: String,
        message: String,
        scope: MessageDeleteScope,
        operationId: String
    ): ClientResult<Unit> {
        deleted.add(message to scope)
        return ClientResult.Ready(Unit)
    }
    var heading: ClientResult<ConversationHeader> = ClientResult.Ready(ConversationHeader(null))
    override suspend fun header(account: String, conversation: String) = heading
    var capability: ClientResult<Unit> = ClientResult.Unavailable(
        ClientFeature.CHAT,
        UnavailableReason.UNSUPPORTED_BY_CORE
    )
    override suspend fun sendAvailability(): ClientResult<Unit> = capability
    var sendResult: ClientResult<SendReceipt>? = null

    /** When set, a send suspends until it completes. */
    var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var sentOperation: String? = null
    val sentOperations = mutableListOf<String>()
    var editedRevision: Long? = null
    override suspend fun send(account: String, conversation: String, text: String, operationId: String): ClientResult<SendReceipt> {
        sends++
        sentOperation = operationId
        sentOperations.add(operationId)
        sendGate?.await()
        sendResult?.let { return it }
        return when (val availability = sendAvailability()) {
            is ClientResult.Ready -> ClientResult.Ready(SendReceipt("sent"))
            is ClientResult.Failed -> availability
            is ClientResult.Unavailable -> availability
        }
    }
    override suspend fun edit(
        account: String,
        conversation: String,
        message: String,
        text: String,
        expectedRevision: Long,
        operationId: String
    ): ClientResult<Unit> {
        editedRevision = expectedRevision
        return ClientResult.Ready(Unit)
    }
    private val signals = MutableSharedFlow<ClientResult<ChatSignal>>(extraBufferCapacity = 4)
    override fun updates(account: String) = signals
    fun signal(value: ChatSignal) {
        signals.tryEmit(ClientResult.Ready(value))
    }
    fun peer(conversation: String, active: Boolean, expiresAtMs: Long) = PeerTyping(conversation, active, expiresAtMs)
    var targets: suspend (String?) -> ClientResult<ForwardTargetPage> = {
        ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
    }
    val targetCursors = mutableListOf<String?>()
    override suspend fun forwardTargets(account: String, cursor: String?): ClientResult<ForwardTargetPage> {
        targetCursors.add(cursor)
        return targets(cursor)
    }
    var typingCalls = 0
    var typingResult: ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun setTyping(account: String, conversation: String, active: Boolean): ClientResult<Unit> {
        typingCalls++
        return typingResult
    }
}
class FakeChatAccounts : AccountAccessRepository {
    var active = "a"
    var unlocked = true
    var followActive = true
    var reportedActive: String? = null
    var failure: ClientResult<AccountCatalog>? = null
    override suspend fun list(): ClientResult<AccountCatalog> = failure ?: ClientResult.Ready(
        AccountCatalog(
            listOf(AccountSummary(active, "Work", unlocked, if (unlocked) "unlocked" else "locked")),
            if (followActive) active else reportedActive
        )
    )
    override suspend fun select(id: String): ClientResult<Unit> {
        active = id
        return ClientResult.Ready(Unit)
    }
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeChatSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}

class FakeChatDrafts : ChatDraftRepository {
    val stored = mutableMapOf<String, String>()
    override suspend fun load(account: String, conversation: String): String? = stored["$account/$conversation"]
    override suspend fun save(account: String, conversation: String, text: String): ClientResult<Unit> {
        if (text.isBlank()) stored.remove("$account/$conversation") else stored["$account/$conversation"] = text
        return ClientResult.Ready(Unit)
    }
    override suspend fun clear(account: String, conversation: String) {
        stored.remove("$account/$conversation")
    }
    override suspend fun clearAccount(account: String) {
        stored.keys.removeAll { it.startsWith("$account/") }
    }
}

class FakeChatOutbox : ChatOutboxRepository {
    val stored = mutableMapOf<String, List<QueuedSend>>()

    /** When set, a save suspends until it completes (a slow store). */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun load(account: String, conversation: String): List<QueuedSend> = stored["$account/$conversation"].orEmpty()
    override suspend fun save(account: String, conversation: String, sends: List<QueuedSend>): ClientResult<Unit> {
        gate?.await()
        if (sends.isEmpty()) stored.remove("$account/$conversation") else stored["$account/$conversation"] = sends
        return ClientResult.Ready(Unit)
    }
    override suspend fun clearAccount(account: String) {
        stored.keys.removeAll { it.startsWith("$account/") }
    }
}

class FakeChatMedia : MediaRepository {
    val held = mutableMapOf<String, java.io.File>()
    var downloadResult: (MediaAttachment) -> TransferState = { TransferState.Ready(java.io.File("/tmp/" + it.messageId)) }
    val downloads = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    val purged = mutableListOf<String>()
    val sent = mutableListOf<List<StagedMedia>>()
    val destinations = mutableListOf<MediaDestination>()
    val captions = mutableListOf<String>()
    val copied = mutableListOf<String>()
    var sendResult: ClientResult<SendReceipt> = ClientResult.Ready(SendReceipt("media"))

    /** When set, a media send suspends until it completes. */
    var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val sentOperations = mutableListOf<String>()
    override fun localFile(account: String, attachment: MediaAttachment) = held[attachment.messageId + "/" + attachment.index]
    override suspend fun download(account: String, attachment: MediaAttachment, progress: (TransferState) -> Unit): TransferState {
        downloads.add(attachment.messageId)
        return downloadResult(attachment)
    }
    override suspend fun send(
        account: String,
        destination: MediaDestination,
        caption: String,
        items: List<StagedMedia>,
        operationId: String,
        progress: (TransferState) -> Unit
    ): ClientResult<SendReceipt> {
        sent.add(items)
        destinations.add(destination)
        captions.add(caption)
        sentOperations.add(operationId)
        sendGate?.await()
        return sendResult
    }
    override suspend fun revoke(account: String, message: String): ClientResult<Unit> {
        revoked.add(message)
        return ClientResult.Ready(Unit)
    }
    override suspend fun purge(account: String, message: String) {
        purged.add(message)
    }
    val purgedAccounts = mutableListOf<String>()
    override suspend fun purgeAccount(account: String) {
        purgedAccounts.add(account)
    }
    override suspend fun usage(account: String): Long = 0
    override fun outbox(account: String): java.io.File? = null
    override suspend fun copyToOutbox(account: String, source: java.io.File, fileName: String, mime: String): StagedMedia {
        copied.add(source.path)
        return StagedMedia(java.io.File("/tmp/outbox-" + copied.size), fileName, mime)
    }
}

class FakeChatStager : MediaStager {
    var dropped = 0
    val captures = mutableListOf<String>()
    val visuals = mutableListOf<String>()
    var visualResult: (String) -> StageResult = { uri ->
        val mime = if (uri.contains("video")) "video/mp4" else "image/jpeg"
        StageResult.Staged(StagedMedia(java.io.File("/tmp/visual-" + uri.hashCode()), uri.substringAfterLast('/'), mime))
    }
    override suspend fun visual(account: String, uri: String, progress: (Float) -> Unit): StageResult {
        visuals.add(uri)
        progress(.5f)
        return visualResult(uri)
    }
    override suspend fun camera(account: String, uri: String): StagedMedia? {
        captures.add(uri)
        return image(account, uri)
    }

    /** When set, staged images are real files in this directory, so a restored outbox keeps them. */
    var directory: java.io.File? = null
    override suspend fun image(account: String, uri: String): StagedMedia {
        val file = directory?.let { java.io.File(it, "staged-" + uri.hashCode()).also { f -> f.writeText("x") } }
            ?: java.io.File("/tmp/staged-" + uri.hashCode())
        return StagedMedia(file, "IMG_1.jpg", "image/jpeg")
    }
    override suspend fun file(account: String, uri: String): StagedMedia? = null
    override fun dropMemory() {
        dropped++
    }
}

/** The card runtime is outside these chat tests: every card call is the typed unavailable state. */
class FakeChatCards : CardSessionRepository by UnavailableCardSessionRepository()
