// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
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
    private var watch: Job? = null
    private var watchTarget: Target? = null
    private var typingJob: Job? = null
    private var typingActive = false
    private var needsRefresh = false
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            watch?.cancel()
            watchTarget = null
            stopTyping()
            ticket++
            local.value = ChatState()
            val openTarget = target
            if (phase == ClientPhase.OPEN && openTarget != null) {
                ensureWatch(openTarget)
                onAction(ChatAction.Refresh)
            }
        }
    }
    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.Load -> {
                val next = Target(action.accountId, action.conversationId, action.groupId)
                if (target != next) {
                    request?.cancel()
                    watch?.cancel()
                    watchTarget = null
                    stopTyping()
                    ticket++
                    target = next
                    local.value = ChatState(conversationId = next.conversation)
                    ensureWatch(next)
                    read()
                }
            }
            ChatAction.Refresh -> if (request?.isActive != true) read()
            is ChatAction.Draft -> if (!local.value.sending && target != null && session.status.value.phase == ClientPhase.OPEN) {
                val text = action.text.take(65536)
                local.update { it.copy(draft = text) }
                if (local.value.composer == ComposerMode.SEND) syncTyping(text.isNotBlank())
            }
            ChatAction.Send -> if (request?.isActive != true && local.value.draft.isNotBlank()) compose(local.value.composer)
            ChatAction.Attachments -> if (target != null && !local.value.sending) {
                viewModelScope.launch { channel.send(ChatEvent.Attachments) }
            }
            ChatAction.Back -> {
                request?.cancel()
                watch?.cancel()
                watchTarget = null
                stopTyping()
                ticket++
                local.value = ChatState()
                target = null
                viewModelScope.launch { channel.send(ChatEvent.Back) }
            }
            is ChatAction.Select -> if (target != null && session.status.value.phase == ClientPhase.OPEN) {
                local.update { it.copy(selectedId = action.id) }
            }
            ChatAction.Reply -> selected()?.let { row ->
                if (!row.deleted && !row.placeholder) {
                    local.update { it.copy(composer = ComposerMode.REPLY, actionTarget = row.id, expectedRevision = 0) }
                }
            }
            ChatAction.Edit -> selected()?.let { row ->
                val text = (row.content as? UiText.Data)?.value ?: return@let
                if (row.own && !row.deleted && !row.placeholder && !row.service) {
                    local.update {
                        it.copy(
                            composer = ComposerMode.EDIT,
                            actionTarget = row.id,
                            expectedRevision = row.revision,
                            draft = text
                        )
                    }
                }
            }
            ChatAction.React -> mutate(local.value.selectedId) { account, conversation, id, operation ->
                history.react(account, conversation, id, ChatReaction.ACK, false, operation)
            }
            ChatAction.DeleteForMe -> mutate(local.value.selectedId) { account, conversation, id, operation ->
                history.delete(account, conversation, id, MessageDeleteScope.LOCAL, operation)
            }
            ChatAction.DeleteForEveryone -> mutate(local.value.selectedId) { account, conversation, id, operation ->
                history.delete(account, conversation, id, MessageDeleteScope.EVERYONE, operation)
            }
            ChatAction.CancelComposer -> local.update {
                it.copy(composer = ComposerMode.SEND, actionTarget = null, expectedRevision = 0)
            }
        }
    }
    private fun selected(): MessageItem? {
        val id = local.value.selectedId ?: return null
        return local.value.rows.filterIsInstance<MessageItem>().firstOrNull { it.id == id }
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
                        R.string.ui_account_state_could_not_be_read_code_value,
                        UiText.Number(catalog.problem.code.toLong())
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
                rows = persistentListOf(),
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
                        title = (header as? ClientResult.Ready)?.value?.title?.takeIf { title -> title.isNotBlank() }?.let { title ->
                            UiText.Data(title)
                        },
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
                                when (row) {
                                    is CallSummary -> CallItem(row.id, time(row.createdAt))
                                    is MessageSummary -> messageItem(row)
                                }
                            }.toPersistentList(),
                            canSend = capability is ClientResult.Ready,
                            unavailable = when (capability) {
                                is ClientResult.Unavailable -> uiText(
                                    R.string.ui_sending_unavailable_value,
                                    capability.reason.description()
                                )
                                is ClientResult.Ready, is ClientResult.Failed -> null
                            },
                            problem = when (capability) {
                                is ClientResult.Failed -> uiText(
                                    R.string.ui_send_availability_failed_code_value,
                                    UiText.Number(capability.problem.code.toLong())
                                )
                                is ClientResult.Ready, is ClientResult.Unavailable -> null
                            }
                        )
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(problem = uiText(R.string.ui_read_failed_code_value, UiText.Number(result.problem.code.toLong())))
                    }
                    is ClientResult.Unavailable -> local.update {
                        it.copy(unavailable = uiText(R.string.ui_message_history_is_unavailable))
                    }
                }
            } finally {
                finish(expected)
            }
        }
    }
    private fun compose(mode: ComposerMode) {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val text = local.value.draft
        val messageId = local.value.actionTarget
        val revision = local.value.expectedRevision
        if (mode != ComposerMode.SEND && messageId == null) return
        val operation = UUID.randomUUID().toString()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(sending = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                if (mode == ComposerMode.SEND) {
                    val sent = history.send(currentTarget.account, currentTarget.conversation, text, operation)
                    current(expected, generation, currentTarget)
                    if (!validate(expected, generation, currentTarget)) return@launch
                    applySend(sent)
                } else {
                    val targetId = messageId ?: return@launch
                    val changed = if (mode == ComposerMode.REPLY) {
                        history.reply(currentTarget.account, currentTarget.conversation, targetId, text, operation)
                    } else {
                        history.edit(
                            currentTarget.account,
                            currentTarget.conversation,
                            targetId,
                            text,
                            revision,
                            operation
                        )
                    }
                    current(expected, generation, currentTarget)
                    if (!validate(expected, generation, currentTarget)) return@launch
                    applyUnit(changed, clearDraft = true)
                }
            } finally {
                finish(expected)
            }
        }
    }
    private fun mutate(id: String?, call: suspend (String, String, String, String) -> ClientResult<Unit>) {
        val currentTarget = target ?: return
        val messageId = id ?: return
        if (session.status.value.phase != ClientPhase.OPEN || request?.isActive == true) return
        val operation = UUID.randomUUID().toString()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(sending = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val result = call(currentTarget.account, currentTarget.conversation, messageId, operation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                applyUnit(result, clearDraft = false)
            } finally {
                finish(expected)
            }
        }
    }
    private suspend fun applySend(result: ClientResult<SendReceipt>) {
        when (result) {
            is ClientResult.Ready -> {
                local.update {
                    it.copy(
                        draft = "",
                        composer = ComposerMode.SEND,
                        actionTarget = null,
                        deliveryNotice = uiText(R.string.ui_delivery_submitted)
                    )
                }
                syncTyping(false)
                channel.send(ChatEvent.Sent)
                needsRefresh = true
            }
            is ClientResult.Unavailable -> local.update {
                it.copy(unavailable = uiText(R.string.ui_sending_unavailable_draft_kept))
            }
            is ClientResult.Failed -> local.update {
                it.copy(problem = sendFailure(result.problem), deliveryNotice = failedNotice(result.problem))
            }
        }
    }
    private fun applyUnit(result: ClientResult<Unit>, clearDraft: Boolean) {
        when (result) {
            is ClientResult.Ready -> {
                local.update {
                    it.copy(
                        draft = if (clearDraft) "" else it.draft,
                        composer = ComposerMode.SEND,
                        actionTarget = null,
                        expectedRevision = 0,
                        selectedId = null,
                        deliveryNotice = uiText(R.string.ui_delivery_submitted)
                    )
                }
                needsRefresh = true
            }
            is ClientResult.Unavailable -> local.update {
                it.copy(unavailable = uiText(R.string.ui_sending_unavailable_draft_kept))
            }
            is ClientResult.Failed -> local.update {
                it.copy(
                    problem = actionFailure(result.problem),
                    deliveryNotice = failedNotice(result.problem)
                )
            }
        }
    }
    private fun finish(expected: Long) {
        if (expected != ticket) return
        local.update { it.copy(loading = false, sending = false) }
        if (needsRefresh) {
            needsRefresh = false
            read()
        }
    }
    private fun ensureWatch(current: Target) {
        if (watchTarget == current && watch?.isActive == true) return
        watch?.cancel()
        watchTarget = current
        watch = viewModelScope.launch {
            var again = true
            while (again && currentCoroutineContext().isActive) {
                again = false
                history.updates(current.account).collect { result ->
                    if (target != current || session.status.value.phase != ClientPhase.OPEN) return@collect
                    if (result is ClientResult.Ready) {
                        result.value.peerTyping?.let { typing -> local.update { it.copy(peerTyping = typing) } }
                        if (result.value.refresh) {
                            if (request?.isActive == true) needsRefresh = true else read()
                        }
                        if (result.value.resubscribe) again = true
                    }
                }
            }
        }
    }
    private fun syncTyping(active: Boolean) {
        val current = target ?: return
        if (typingActive == active) return
        typingActive = active
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            val result = history.setTyping(current.account, current.conversation, active)
            if (target != current || session.status.value.phase != ClientPhase.OPEN) return@launch
            local.update { it.copy(typingProblem = typingNotice(result)) }
        }
    }
    private fun stopTyping() {
        val current = target
        val wasActive = typingActive
        typingActive = false
        typingJob?.cancel()
        typingJob = null
        if (wasActive && current != null) {
            val account = current.account
            val conversation = current.conversation
            viewModelScope.launch { history.setTyping(account, conversation, false) }
        }
    }
    private fun messageItem(row: MessageSummary): MessageItem {
        val content = when {
            row.deleted -> uiText(R.string.ui_message_deleted)
            row.placeholder -> uiText(R.string.ui_message_placeholder)
            row.service && row.text == null -> uiText(R.string.ui_service_message)
            else -> row.text?.let { UiText.Data(it) } ?: uiText(R.string.ui_message_content_is_unavailable)
        }
        return MessageItem(
            row.id,
            if (row.deleted) "" else row.sender ?: "",
            row.own,
            row.deleted,
            content,
            time(row.createdAt),
            row.revision,
            row.edited,
            row.replyPreview?.let { UiText.Data(it) },
            row.placeholder,
            row.service,
            receiptLabel(row.receipts),
            reactionLabel(row.reactions)
        )
    }
    private fun time(createdAt: Long?) = createdAt?.let { UiText.DateTime(it) } ?: uiText(R.string.ui_time_unavailable)
    private suspend fun current(expected: Long, generation: Long, expectedTarget: Target) {
        currentCoroutineContext().ensureActive()
        if (expected != ticket || generation != session.status.value.generation ||
            session.status.value.phase != ClientPhase.OPEN || target != expectedTarget
        ) {
            throw CancellationException("retired chat")
        }
    }
}

private fun receiptLabel(receipts: List<MessageReceipt>): UiText? {
    val state = receipts.maxByOrNull { rank(it.state) }?.state ?: return null
    return when (state) {
        "read" -> uiText(R.string.ui_receipt_read)
        "delivered" -> uiText(R.string.ui_receipt_delivered)
        "sent" -> uiText(R.string.ui_receipt_sent)
        else -> null
    }
}

private fun rank(state: String) = when (state) {
    "read" -> 3
    "delivered" -> 2
    "sent" -> 1
    else -> 0
}

private fun reactionLabel(reactions: List<MessageReaction>): UiText? {
    val token = reactions.firstOrNull()?.token ?: return null
    return if (token == ChatReaction.ACK) uiText(R.string.ui_reaction_ack) else UiText.Data(token)
}

private fun sendFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> coded(
        problem,
        R.string.ui_the_message_was_not_sent_code_value,
        R.string.ui_the_message_was_not_sent_status_value
    )
}

private fun actionFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> coded(
        problem,
        R.string.ui_action_failed_code_value,
        R.string.ui_action_failed_status_value
    )
}

private fun failedNotice(problem: ClientProblem): UiText? =
    if (problem.outcome == ClientOutcome.UNKNOWN) null else uiText(R.string.ui_delivery_failed)

private fun typingNotice(result: ClientResult<Unit>): UiText? = when (result) {
    is ClientResult.Ready -> null
    is ClientResult.Unavailable -> uiText(R.string.ui_typing_unavailable)
    is ClientResult.Failed -> actionFailure(result.problem)
}

private fun coded(problem: ClientProblem, codeOnly: Int, withStatus: Int): UiText {
    val code = UiText.Number(problem.code.toLong())
    val status = problem.statusName
    return if (status.isNullOrEmpty()) uiText(codeOnly, code) else uiText(withStatus, UiText.Data(status), code)
}

private fun UnavailableReason.description(): UiText = when (this) {
    UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_the_feature_is_not_installed)
    UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_the_sdk_operation_is_not_available_in_this)
    UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_account_access_is_required_on_this_device)
}
