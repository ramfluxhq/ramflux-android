// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.tasks.impl.R
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasks: TaskDirectoryRepository,
    private val conversations: ConversationDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private sealed interface ReadSnapshot {
        data class Contexts(val value: ConversationPage) : ReadSnapshot
        data class Tasks(val value: TaskPage) : ReadSnapshot
        data class Detail(val value: TaskRecord) : ReadSnapshot
        data class Permissions(val value: List<TaskPermission>) : ReadSnapshot
    }
    private fun <T> ClientResult<T>.snapshot(map: (T) -> ReadSnapshot): ClientResult<ReadSnapshot> = when (this) {
        is ClientResult.Ready -> ClientResult.Ready(map(value))
        is ClientResult.Failed -> this
        is ClientResult.Unavailable -> this
    }
    private val local = MutableStateFlow(TasksState())
    private val channel = Channel<TasksEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) value else TasksState(problem = uiText(R.string.tasks_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), TasksState())
    private var visible = false
    private var owner: String? = null
    private var request: Job? = null
    private var watch: Job? = null
    private var ticket = 0L
    private var revision = 0L
    private var needsRefresh = false
    private var cursor: String? = null
    private val seen = mutableSetOf<String>()
    private var records: List<TaskRecord> = emptyList()
    private var contexts: List<ConversationSummary> = emptyList()
    private var permissions: List<TaskPermission> = emptyList()
    private var selected: String? = null
    private var prepared: TaskAttempt? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            stop()
            local.value = TasksState()
            if (visible && it.first == ClientPhase.OPEN) read(false)
        }
    }
    fun onAction(action: TasksAction) {
        if (local.value.confirmation != null && action !is TasksAction.Confirm && action != TasksAction.Deny &&
            action !is TasksAction.Visible
        ) {
            return
        }
        when (action) {
            is TasksAction.Visible -> {
                visible = action.value
                if (visible) {
                    read(false)
                } else {
                    stop()
                    local.value = TasksState()
                }
            }
            TasksAction.Refresh -> if (request?.isActive != true) read(false)
            TasksAction.More -> if (request?.isActive != true && local.value.canMore) read(true)
            TasksAction.Conversations -> page(TasksPage.CONVERSATIONS)
            TasksAction.Permissions -> page(TasksPage.PERMISSIONS)
            is TasksAction.Conversation -> if (contexts.any { it.id == action.id }) page(TasksPage.LIST, action.id)
            is TasksAction.Select -> if (records.any { it.id == action.id }) page(TasksPage.DETAIL, local.value.conversation, action.id)
            is TasksAction.Permission -> if (permissions.any { it.id == action.id }) page(TasksPage.PERMISSION, id = action.id)
            is TasksAction.Prepare -> {
                val record = records.find { it.id == selected } ?: return
                if (!local.value.fresh || local.value.busy || local.value.pendingId != null) return
                if (action.decision == TaskDecision.CANCEL && !record.cancellable ||
                    action.decision != TaskDecision.CANCEL && !record.decidable
                ) {
                    return
                }
                val attempt = TaskAttempt(UUID.randomUUID().toString(), record.copy(steps = record.steps.toList()), action.decision)
                prepare(attempt)
            }
            is TasksAction.Reconcile -> {
                if (local.value.busy || request?.isActive == true) return
                val account = owner ?: return
                val pending = tasks.pending(account) ?: return
                if (pending.operationId == action.operationId) prepare(pending)
            }
            is TasksAction.Confirm -> {
                val attempt = prepared ?: return
                if (local.value.confirmation?.id != action.operationId || attempt.operationId != action.operationId) return
                mutate(attempt)
            }
            TasksAction.Deny -> {
                prepared = null
                local.update { it.copy(confirmation = null) }
            }
            TasksAction.Unavailable -> unavailable()
            TasksAction.SetupAccount -> viewModelScope.launch { channel.send(TasksEvent.SetupAccount) }
            TasksAction.Back -> when (local.value.page) {
                TasksPage.DETAIL -> page(TasksPage.LIST, local.value.conversation)
                TasksPage.PERMISSION -> page(TasksPage.PERMISSIONS)
                TasksPage.LIST -> page(TasksPage.CONVERSATIONS)
                else -> {
                    stop()
                    local.value = TasksState()
                    viewModelScope.launch { channel.send(TasksEvent.Back) }
                }
            }
        }
    }
    private fun prepare(attempt: TaskAttempt) {
        val account = owner ?: return
        prepared = attempt
        local.update { it.copy(confirmation = TaskConfirmation(attempt.operationId, attempt.decision.label(), attempt.facts(account))) }
    }
    private fun page(page: TasksPage, conversation: String? = null, id: String? = null) {
        if (local.value.busy) return
        request?.cancel()
        ticket++
        prepared = null
        selected = id
        cursor = null
        seen.clear()
        records = emptyList()
        local.value = TasksState(page = page, conversation = conversation)
        read(false)
    }
    private fun stop() {
        request?.cancel()
        watch?.cancel()
        watch = null
        ticket++
        owner = null
        prepared = null
        selected = null
        cursor = null
        seen.clear()
        records = emptyList()
        contexts = emptyList()
        permissions = emptyList()
        needsRefresh = false
    }
    private fun conceal(problem: UiText? = null, accountRequired: Boolean = false) {
        owner = null
        prepared = null
        records = emptyList()
        contexts = emptyList()
        permissions = emptyList()
        watch?.cancel()
        watch = null
        local.value = TasksState(problem = problem, accountRequired = accountRequired)
    }
    private suspend fun validateAccount(expected: Long, generation: Long, required: String? = owner): String? {
        val result = accounts.list()
        current(expected, generation)
        if (result !is ClientResult.Ready) {
            conceal(problem(result))
            return null
        }
        val selected = result.value.accounts.singleOrNull { it.id == result.value.activeId && it.unlocked }
        if (selected == null) {
            conceal(accountRequired = true)
            return null
        }
        if (required != null && required != selected.id) {
            conceal(uiText(R.string.tasks_account_changed))
            return null
        }
        owner = selected.id
        observe(selected.id)
        return selected.id
    }
    private fun observe(account: String) {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            tasks.updates(account).collect { update ->
                if (!visible || owner != account) return@collect
                revision++
                prepared = null
                local.update { it.copy(fresh = false, confirmation = null) }
                if (update is ClientResult.Ready) {
                    if (request?.isActive == true) needsRefresh = true else read(false)
                } else {
                    local.update { it.copy(problem = problem(update)) }
                }
            }
        }
    }
    private fun read(more: Boolean) {
        if (!visible || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        val page = local.value.page
        val conversation = local.value.conversation
        val selected = this.selected
        val next = if (more) cursor ?: return else null
        if (more && !seen.add(next ?: return)) {
            local.update { it.copy(problem = uiText(R.string.tasks_error), canMore = false) }
            return
        }
        if (!more) {
            cursor = null
            seen.clear()
        }
        request?.cancel()
        val expected = ++ticket
        val generation = session.status.value.generation
        val observed = revision
        local.update { it.copy(loading = true, loaded = false, fresh = false, confirmation = null, problem = null) }
        request = viewModelScope.launch {
            try {
                val account = validateAccount(expected, generation) ?: return@launch
                val result = when (page) {
                    TasksPage.CONVERSATIONS -> conversations.page(account, next).snapshot { ReadSnapshot.Contexts(it) }
                    TasksPage.LIST -> tasks.page(account, conversation ?: return@launch, next).snapshot { ReadSnapshot.Tasks(it) }
                    TasksPage.DETAIL -> tasks.detail(account, conversation ?: return@launch, selected ?: return@launch).snapshot {
                        ReadSnapshot.Detail(it)
                    }
                    TasksPage.PERMISSIONS, TasksPage.PERMISSION -> tasks.permissions(account).snapshot { ReadSnapshot.Permissions(it) }
                }
                if (validateAccount(expected, generation, account) == null) return@launch
                if (observed != revision) {
                    needsRefresh = true
                    return@launch
                }
                if (result !is ClientResult.Ready) {
                    local.update { it.copy(problem = problem(result)) }
                    return@launch
                }
                when (val snapshot = result.value) {
                    is ReadSnapshot.Contexts -> {
                        val value = snapshot.value
                        val rows = (if (more) contexts else emptyList()) + value.rows
                        if (rows.size > 1000 || rows.map { it.id }.distinct().size != rows.size) {
                            local.update { it.copy(problem = uiText(R.string.tasks_error)) }
                            return@launch
                        }
                        contexts = rows
                        cursor = value.nextCursor
                        local.update { it.copy(contexts = rows.map { TaskContextRow(it.id, it.title) }.toPersistentList()) }
                    }
                    is ReadSnapshot.Tasks -> {
                        val value = snapshot.value
                        val rows = (if (more) records else emptyList()) + value.rows
                        if (rows.size > 1000 || rows.map { it.id }.distinct().size != rows.size) {
                            local.update { it.copy(problem = uiText(R.string.tasks_error)) }
                            return@launch
                        }
                        records = rows
                        cursor = value.cursor
                        local.update { it.copy(rows = rows.map { it.row() }.toPersistentList()) }
                    }
                    is ReadSnapshot.Detail -> {
                        val value = snapshot.value
                        records = listOf(value)
                        local.update { it.copy(selected = value.row()) }
                    }
                    is ReadSnapshot.Permissions -> {
                        val value = snapshot.value
                        permissions = value
                        val rows = value.map { it.row() }.toPersistentList()
                        local.update { it.copy(permissions = rows, selectedPermission = rows.find { it.id == selected }) }
                        if (page == TasksPage.PERMISSION && rows.none { it.id == selected }) {
                            local.update { it.copy(problem = uiText(R.string.tasks_error)) }
                            return@launch
                        }
                    }
                }
                val pending = tasks.pending(account)
                local.update {
                    it.copy(
                        loaded = true, fresh = true, canMore = cursor != null, pendingId = pending?.operationId,
                        pendingFacts = pending?.facts(account) ?: persistentListOf()
                    )
                }
            } finally {
                if (expected == ticket) {
                    local.update { it.copy(loading = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read(false)
                    }
                }
            }
        }
    }
    private fun mutate(attempt: TaskAttempt) {
        val account = owner ?: return
        val pending = tasks.pending(account)
        if (!local.value.fresh || request?.isActive == true || local.value.busy || pending != null && pending != attempt) return
        val expected = ++ticket
        val generation = session.status.value.generation
        prepared = null
        local.update { it.copy(confirmation = null, busy = true, fresh = false, problem = null, notice = null) }
        request = viewModelScope.launch {
            try {
                if (validateAccount(expected, generation, account) == null) return@launch
                val result = tasks.mutate(account, attempt)
                if (validateAccount(expected, generation, account) == null) return@launch
                val retained = tasks.pending(account)
                local.update {
                    it.copy(
                        pendingId = retained?.operationId, pendingFacts = retained?.facts(account) ?: persistentListOf(),
                        notice = (result as? ClientResult.Ready)?.let {
                            uiText(if (it.value.pending) R.string.tasks_waiting else R.string.tasks_ack)
                        },
                        problem = if (result is ClientResult.Ready) null else problem(result)
                    )
                }
                needsRefresh = true
            } finally {
                if (expected == ticket) {
                    local.update { it.copy(busy = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read(false)
                    }
                }
            }
        }
    }
    private fun unavailable() {
        if (request?.isActive == true || !visible) return
        val expected = ticket
        val generation = session.status.value.generation
        request = viewModelScope.launch {
            if (validateAccount(expected, generation) == null) return@launch
            val result = tasks.contextualAvailability()
            current(expected, generation)
            local.update {
                it.copy(unavailable = result !is ClientResult.Failed, problem = (result as? ClientResult.Failed)?.let { problem(it) })
            }
        }
    }
    private fun problem(result: ClientResult<*>): UiText = when (result) {
        is ClientResult.Failed -> if (result.problem.outcome == ClientOutcome.UNKNOWN) {
            uiText(R.string.tasks_unknown)
        } else {
            uiText(R.string.tasks_error_code, UiText.Number(result.problem.code.toLong()))
        }
        else -> uiText(R.string.tasks_unavailable_detail)
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!visible || ticket != expected || session.status.value.generation != generation ||
            session.status.value.phase != ClientPhase.OPEN
        ) {
            throw CancellationException(
                "retired tasks"
            )
        }
    }
}
