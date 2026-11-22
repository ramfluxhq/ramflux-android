// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
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
    var heading: ClientResult<ConversationHeader> = ClientResult.Ready(ConversationHeader(null))
    override suspend fun header(account: String, conversation: String) = heading
    var capability: ClientResult<Unit> = ClientResult.Unavailable(
        ClientFeature.CHAT,
        UnavailableReason.UNSUPPORTED_BY_CORE
    )
    override suspend fun sendAvailability(): ClientResult<Unit> = capability
    var sendResult: ClientResult<SendReceipt>? = null
    var sentOperation: String? = null
    val sentOperations = mutableListOf<String>()
    var editedRevision: Long? = null
    override suspend fun send(account: String, conversation: String, text: String, operationId: String): ClientResult<SendReceipt> {
        sends++
        sentOperation = operationId
        sentOperations.add(operationId)
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
