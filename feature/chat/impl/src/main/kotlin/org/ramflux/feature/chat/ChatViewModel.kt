// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val history: MessageHistoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private data class Target(val account: String, val conversation: String)
    private var target: Target? = null
    private val local = MutableStateFlow(ChatState())
    private val channel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) screen else ChatState(problem = "Private data is closed.")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ChatState())
    private var request: Job? = null
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            ticket++
            local.value = ChatState()
            if (phase == ClientPhase.OPEN && target != null) onAction(ChatAction.Refresh)
        }
    }
    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.Load -> {
                val next = Target(action.accountId, action.conversationId)
                if (target != next) {
                    request?.cancel()
                    ticket++
                    target = next
                    local.value = ChatState(conversationId = next.conversation)
                    read()
                }
            }
            ChatAction.Refresh -> if (request?.isActive != true) read()
            is ChatAction.Draft -> if (!local.value.sending && target != null && session.status.value.phase == ClientPhase.OPEN) {
                local.update { it.copy(draft = action.text.take(65536)) }
            }
            ChatAction.Send -> if (request?.isActive != true && local.value.draft.isNotBlank()) send()
            ChatAction.Back -> {
                request?.cancel()
                ticket++
                local.value = ChatState()
                target = null
                viewModelScope.launch { channel.send(ChatEvent.Back) }
            }
        }
    }
    private suspend fun validate(expected: Long, generation: Long, expectedTarget: Target): Boolean {
        current(expected, generation, expectedTarget)
        val catalog = accounts.list()
        current(expected, generation, expectedTarget)
        val value = when (catalog) {
            is ClientResult.Ready -> catalog.value
            is ClientResult.Failed -> {
                local.value = ChatState(problem = "Account state could not be read (code ${catalog.problem.code}).")
                return false
            }
            is ClientResult.Unavailable -> {
                local.value = ChatState(unavailable = "Account access is unavailable: ${catalog.reason.description()}")
                return false
            }
        }
        val valid = value.activeId == expectedTarget.account &&
            value.accounts.any { it.id == expectedTarget.account && it.unlocked }
        if (!valid) local.value = ChatState(problem = "The active account changed. Return to conversations.")
        return valid
    }
    private fun read() {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update {
            it.copy(
                conversationId = currentTarget.conversation,
                loading = true,
                loaded = false,
                rows = kotlinx.collections.immutable.persistentListOf(),
                problem = null
            )
        }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val result = history.read(currentTarget.account, currentTarget.conversation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                val capability = history.sendAvailability()
                current(expected, generation, currentTarget)
                when (result) {
                    is ClientResult.Ready -> local.update {
                        it.copy(
                            loaded = true,
                            rows = result.value.map { row ->
                                MessageItem(
                                    row.id, if (row.deleted) "" else row.sender ?: "", row.own, row.deleted,
                                    if (row.deleted) "Message deleted" else row.text ?: "Message content is unavailable."
                                )
                            }.toPersistentList(),
                            canSend = capability is ClientResult.Ready,
                            unavailable = when (capability) {
                                is ClientResult.Unavailable -> "Sending unavailable: ${capability.reason.description()}"
                                is ClientResult.Ready, is ClientResult.Failed -> null
                            },
                            problem = when (capability) {
                                is ClientResult.Failed -> "Send availability failed (code ${capability.problem.code})."
                                is ClientResult.Ready, is ClientResult.Unavailable -> null
                            }
                        )
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = "Read failed (code ${result.problem.code}).") }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = "Message history is unavailable.") }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun send() {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val text = local.value.draft
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(sending = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val result = history.send(currentTarget.account, currentTarget.conversation, text)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        local.update { it.copy(draft = "") }
                        channel.send(ChatEvent.Sent)
                    }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = "Sending unavailable. Draft kept.") }
                    is ClientResult.Failed -> local.update {
                        it.copy(
                            problem = if (result.problem.outcome == ClientOutcome.UNKNOWN) {
                                "Send outcome is unknown. Refresh before making another request."
                            } else {
                                "The message was not sent (code ${result.problem.code})."
                            }
                        )
                    }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(sending = false) }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long, expectedTarget: Target) {
        currentCoroutineContext().ensureActive()
        if (expected != ticket || generation != session.status.value.generation ||
            session.status.value.phase != ClientPhase.OPEN || target != expectedTarget
        ) {
            throw CancellationException(
                "retired chat"
            )
        }
    }
}

private fun UnavailableReason.description(): String = when (this) {
    UnavailableReason.FEATURE_NOT_INSTALLED -> "the feature is not installed."
    UnavailableReason.UNSUPPORTED_BY_CORE -> "the SDK operation is not available in this version."
    UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> "secure account access is required on this device."
}
