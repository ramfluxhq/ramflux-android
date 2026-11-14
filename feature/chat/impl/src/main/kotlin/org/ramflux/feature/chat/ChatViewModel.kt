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
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val history: MessageHistoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private data class Target(val account: String, val conversation: String, val group: String?)
    private var target: Target? = null
    private val local = MutableStateFlow(ChatState())
    private val channel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) screen else ChatState(problem = uiText(R.string.ui_private_data_is_closed))
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
                val next = Target(action.accountId, action.conversationId, action.groupId)
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
                local.value = ChatState(
                    problem = uiText(
                        R.string.ui_account_state_could_not_be_read_code_value, UiText.Number(catalog.problem.code.toLong())
                    )
                )
                return false
            }
            is ClientResult.Unavailable -> {
                local.value = ChatState(unavailable = uiText(R.string.ui_account_access_is_unavailable_value, catalog.reason.description()))
                return false
            }
        }
        val valid = value.activeId == expectedTarget.account &&
            value.accounts.any { it.id == expectedTarget.account && it.unlocked }
        if (!valid) local.value = ChatState(problem = uiText(R.string.ui_the_active_account_changed_return_to_conversations))
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
                groupId = null,
                canSend = false,
                title = null,
                headerProblem = null,
                loading = true,
                loaded = false,
                rows = kotlinx.collections.immutable.persistentListOf(),
                problem = null
            )
        }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val groupHeader = currentTarget.group?.let {
                    history.groupHeader(currentTarget.account, it, currentTarget.conversation)
                }
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                if (currentTarget.group != null &&
                    (groupHeader !is ClientResult.Ready || groupHeader.value.groupId != currentTarget.group)
                ) {
                    local.value = ChatState(problem = uiText(R.string.ui_conversation_details_unavailable))
                    return@launch
                }
                val result = history.read(currentTarget.account, currentTarget.conversation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                val header = groupHeader ?: history.header(currentTarget.account, currentTarget.conversation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                local.update {
                    it.copy(
                        groupId = (groupHeader as? ClientResult.Ready)?.value?.groupId,
                        title = (header as? ClientResult.Ready)?.value?.title?.takeIf { it.isNotBlank() }?.let { UiText.Data(it) },
                        headerProblem = when (header) {
                            is ClientResult.Ready -> null
                            is ClientResult.Failed, is ClientResult.Unavailable -> uiText(R.string.ui_conversation_details_unavailable)
                        }
                    )
                }
                val capability = history.sendAvailability()
                current(expected, generation, currentTarget)
                when (result) {
                    is ClientResult.Ready -> local.update {
                        it.copy(
                            loaded = true,
                            rows = result.value.map { row ->
                                val time = row.createdAt?.let { UiText.DateTime(it) } ?: uiText(R.string.ui_time_unavailable)
                                when (row) {
                                    is CallSummary -> CallItem(row.id, time)
                                    is MessageSummary -> MessageItem(
                                        row.id, if (row.deleted) "" else row.sender ?: "", row.own, row.deleted,
                                        if (row.deleted) {
                                            uiText(R.string.ui_message_deleted)
                                        } else {
                                            row.text?.let { UiText.Data(it) } ?: uiText(R.string.ui_message_content_is_unavailable)
                                        },
                                        time
                                    )
                                }
                            }.toPersistentList(),
                            canSend = capability is ClientResult.Ready,
                            unavailable = when (capability) {
                                is ClientResult.Unavailable -> uiText(
                                    R.string.ui_sending_unavailable_value, capability.reason.description()
                                )
                                is ClientResult.Ready, is ClientResult.Failed -> null
                            },
                            problem = when (capability) {
                                is ClientResult.Failed -> uiText(
                                    R.string.ui_send_availability_failed_code_value, UiText.Number(capability.problem.code.toLong())
                                )
                                is ClientResult.Ready, is ClientResult.Unavailable -> null
                            }
                        )
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(problem = uiText(R.string.ui_read_failed_code_value, UiText.Number(result.problem.code.toLong())))
                    }
                    is ClientResult.Unavailable -> local.update {
                        it.copy(
                            unavailable = uiText(
                                R.string.ui_message_history_is_unavailable
                            )
                        )
                    }
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
                    is ClientResult.Unavailable -> local.update {
                        it.copy(
                            unavailable = uiText(
                                R.string.ui_sending_unavailable_draft_kept
                            )
                        )
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(
                            problem = if (result.problem.outcome == ClientOutcome.UNKNOWN) {
                                uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
                            } else {
                                uiText(R.string.ui_the_message_was_not_sent_code_value, UiText.Number(result.problem.code.toLong()))
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

private fun UnavailableReason.description(): UiText = when (this) {
    UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_the_feature_is_not_installed)
    UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_the_sdk_operation_is_not_available_in_this)
    UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_account_access_is_required_on_this_device)
}
