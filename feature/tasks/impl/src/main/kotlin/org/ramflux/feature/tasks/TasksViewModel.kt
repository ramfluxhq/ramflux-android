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

/**
 * Task conversations, the tasks of one conversation and the permission list read through windowed sources into
 * bounded [PageWindow]s (rows projected once per page as it arrives); a task and a permission detail are single
 * reads. While visible the screen follows only its page's topics: conversations on the conversation list, tasks
 * on the task pages, approvals and tasks on the permission pages.
 */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasks: TaskDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    /** A loaded row with its display row, projected once when its page arrives. */
    private class Shown<T, R>(val value: T, val row: R)

    private val local = MutableStateFlow(TasksState())
    private val channel = Channel<TasksEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) value else TasksState(problem = uiText(R.string.tasks_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), TasksState())
    private val contexts = PageWindow<Shown<TaskContext, TaskContextRow>>(WINDOW_PAGES) { it.value.id }
    private val records = PageWindow<Shown<TaskRecord, TaskRow>>(WINDOW_PAGES) { it.value.id }
    private val permissions = PageWindow<Shown<TaskPermission, TaskPermissionRow>>(WINDOW_PAGES) { it.value.id }
    private var visible = false
    private var owner: String? = null
    private var request: Job? = null
    private var pageJob: Job? = null
    private var watch: Job? = null
    private var watched: Set<TaskTopic> = emptySet()
    private var ticket = 0L
    private var revision = 0L
    private var needsRefresh = false
    private var detail: TaskRecord? = null
    private var permission: TaskPermission? = null
    private var selected: String? = null
    private var prepared: TaskAttempt? = null

    /** The approval decision waiting for the host confirmation: the approval as re-read, and approve or deny. */
    private var preparedApproval: Triple<String, TaskPermission, Boolean>? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            stop()
            local.value = TasksState()
            if (visible && it.first == ClientPhase.OPEN) read()
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
                    read()
                } else {
                    stop()
                    local.value = TasksState()
                }
            }
            TasksAction.Refresh -> if (request?.isActive != true) read()
            is TasksAction.More -> more(action.forward)
            TasksAction.Conversations -> page(TasksPage.CONVERSATIONS)
            TasksAction.Permissions -> page(TasksPage.PERMISSIONS)
            is TasksAction.Conversation -> if (contexts.rows().any { it.value.id == action.id }) page(TasksPage.LIST, action.id)
            is TasksAction.Select -> if (records.rows().any { it.value.id == action.id }) {
                page(TasksPage.DETAIL, local.value.conversation, action.id)
            }
            is TasksAction.Permission -> permissions.rows().find { it.value.id == action.id }?.let { shown ->
                page(TasksPage.PERMISSION, id = action.id, permission = shown.value)
            }
            is TasksAction.Prepare -> {
                val record = detail?.takeIf { it.id == selected } ?: return
                if (!local.value.fresh || local.value.busy || local.value.pendingId != null) return
                if (action.decision == TaskDecision.CANCEL && !record.cancellable ||
                    action.decision != TaskDecision.CANCEL && !record.decidable
                ) {
                    return
                }
                val attempt = TaskAttempt(UUID.randomUUID().toString(), record.copy(steps = record.steps.toList()), action.decision)
                prepare(attempt)
            }
            is TasksAction.ReviewApproval -> reviewApproval(action.id, action.approve)
            is TasksAction.Reconcile -> {
                if (local.value.busy || request?.isActive == true) return
                val account = owner ?: return
                val pending = tasks.pending(account) ?: return
                if (pending.operationId == action.operationId) prepare(pending)
            }
            is TasksAction.Confirm -> {
                preparedApproval?.let { (id, approval, approve) ->
                    if (local.value.confirmation?.id == action.operationId && id == action.operationId) decide(approval, approve)
                    return
                }
                val attempt = prepared ?: return
                if (local.value.confirmation?.id != action.operationId || attempt.operationId != action.operationId) return
                mutate(attempt)
            }
            TasksAction.Deny -> {
                prepared = null
                preparedApproval = null
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
        preparedApproval = null
        local.update { it.copy(confirmation = TaskConfirmation(attempt.operationId, attempt.decision.label(), attempt.facts(account))) }
    }
    private fun page(page: TasksPage, conversation: String? = null, id: String? = null, permission: TaskPermission? = null) {
        if (local.value.busy) return
        request?.cancel()
        pageJob?.cancel()
        ticket++
        prepared = null
        preparedApproval = null
        selected = id
        detail = null
        this.permission = permission
        clearWindows()
        local.value = TasksState(page = page, conversation = conversation)
        read()
    }
    private fun clearWindows() {
        contexts.clear()
        records.clear()
        permissions.clear()
    }
    private fun <T> PageWindow<T>.clear() = reset(WindowedPage(emptyList(), null, null))
    private fun stop() {
        request?.cancel()
        pageJob?.cancel()
        watch?.cancel()
        watch = null
        watched = emptySet()
        ticket++
        owner = null
        prepared = null
        preparedApproval = null
        selected = null
        detail = null
        permission = null
        clearWindows()
        needsRefresh = false
    }
    private fun conceal(problem: UiText? = null, accountRequired: Boolean = false) {
        owner = null
        prepared = null
        preparedApproval = null
        detail = null
        permission = null
        clearWindows()
        watch?.cancel()
        watch = null
        watched = emptySet()
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
        observe(selected.id, topics(local.value.page))
        return selected.id
    }

    /** The topics the shown page depends on; never `*`. */
    private fun topics(page: TasksPage): Set<TaskTopic> = when (page) {
        TasksPage.CONVERSATIONS -> setOf(TaskTopic.CONVERSATIONS)
        TasksPage.LIST, TasksPage.DETAIL -> setOf(TaskTopic.TASKS)
        TasksPage.PERMISSIONS, TasksPage.PERMISSION ->
            setOf(TaskTopic.TASKS, TaskTopic.APPROVAL_REQUESTED, TaskTopic.APPROVAL_GRANTED, TaskTopic.APPROVAL_DENIED)
    }
    private fun observe(account: String, topics: Set<TaskTopic>) {
        if (watch?.isActive == true && watched == topics) return
        watch?.cancel()
        watched = topics
        watch = viewModelScope.launch {
            tasks.updates(account, topics).collect { update ->
                if (!visible || owner != account) return@collect
                revision++
                prepared = null
                preparedApproval = null
                local.update { it.copy(fresh = false, confirmation = null) }
                if (update is ClientResult.Ready) {
                    if (request?.isActive == true) needsRefresh = true else read()
                } else {
                    local.update { it.copy(problem = problem(update)) }
                }
            }
        }
    }

    /** Reads the shown page from its start: the first window page of a list, or the single detail. */
    private fun read() {
        if (!visible || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        val page = local.value.page
        val conversation = local.value.conversation
        val selected = this.selected
        request?.cancel()
        pageJob?.cancel()
        val expected = ++ticket
        val generation = session.status.value.generation
        val observed = revision
        local.update { it.copy(loading = true, loaded = false, fresh = false, confirmation = null, problem = null) }
        request = viewModelScope.launch {
            try {
                val account = validateAccount(expected, generation) ?: return@launch
                val apply: () -> Unit = when (page) {
                    TasksPage.CONVERSATIONS -> when (val result = tasks.contextPage(account, null)) {
                        is ClientResult.Ready -> fun() {
                            contexts.reset(result.value.shownContexts())
                        }
                        else -> return@launch failed(expected, generation, account, result)
                    }
                    TasksPage.LIST -> when (val result = tasks.taskPage(account, conversation ?: return@launch, null)) {
                        is ClientResult.Ready -> fun() {
                            records.reset(result.value.shownRecords())
                        }
                        else -> return@launch failed(expected, generation, account, result)
                    }
                    TasksPage.DETAIL -> when (
                        val result = tasks.detail(account, conversation ?: return@launch, selected ?: return@launch)
                    ) {
                        is ClientResult.Ready -> fun() {
                            detail = result.value
                        }
                        else -> return@launch failed(expected, generation, account, result)
                    }
                    TasksPage.PERMISSIONS -> when (val result = tasks.permissionPage(account, null)) {
                        is ClientResult.Ready -> fun() {
                            permissions.reset(result.value.shownPermissions())
                        }
                        else -> return@launch failed(expected, generation, account, result)
                    }
                    TasksPage.PERMISSION -> {
                        val shown = permission ?: return@launch
                        when (val result = tasks.permission(account, shown.kind, shown.reference)) {
                            is ClientResult.Ready -> fun() {
                                permission = result.value
                            }
                            else -> return@launch failed(expected, generation, account, result)
                        }
                    }
                }
                if (validateAccount(expected, generation, account) == null) return@launch
                if (observed != revision) {
                    needsRefresh = true
                    return@launch
                }
                apply()
                publish()
                val pending = tasks.pending(account)
                local.update {
                    it.copy(
                        loaded = true, fresh = true, pendingId = pending?.operationId,
                        pendingFacts = pending?.facts(account) ?: persistentListOf()
                    )
                }
            } finally {
                if (expected == ticket) {
                    local.update { it.copy(loading = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read()
                    }
                }
            }
        }
    }
    private suspend fun failed(expected: Long, generation: Long, account: String, result: ClientResult<*>) {
        if (validateAccount(expected, generation, account) == null) return
        local.update { it.copy(problem = problem(result)) }
    }

    /** Loads the page after (or before) the window; the window drops pages at the other end beyond its bound. */
    private fun more(forward: Boolean) {
        val account = owner ?: return
        val page = local.value.page
        if (request?.isActive == true || pageJob?.isActive == true || local.value.busy || !local.value.loaded) return
        val conversation = local.value.conversation
        val cursor = when (page) {
            TasksPage.CONVERSATIONS -> if (forward) contexts.next else contexts.previous
            TasksPage.LIST -> if (forward) records.next else records.previous
            TasksPage.PERMISSIONS -> if (forward) permissions.next else permissions.previous
            else -> null
        } ?: return
        val expected = ticket
        val generation = session.status.value.generation
        local.update { it.copy(loadingMore = true) }
        pageJob = viewModelScope.launch {
            try {
                val apply: () -> Unit = when (page) {
                    TasksPage.CONVERSATIONS -> when (val result = tasks.contextPage(account, cursor)) {
                        is ClientResult.Ready -> fun() {
                            contexts.add(forward, result.value.shownContexts())
                        }
                        else -> return@launch pageFailed(expected, generation, result)
                    }
                    TasksPage.LIST -> when (val result = tasks.taskPage(account, conversation ?: return@launch, cursor)) {
                        is ClientResult.Ready -> fun() {
                            records.add(forward, result.value.shownRecords())
                        }
                        else -> return@launch pageFailed(expected, generation, result)
                    }
                    else -> when (val result = tasks.permissionPage(account, cursor)) {
                        is ClientResult.Ready -> fun() {
                            permissions.add(forward, result.value.shownPermissions())
                        }
                        else -> return@launch pageFailed(expected, generation, result)
                    }
                }
                current(expected, generation)
                apply()
                publish()
            } finally {
                if (expected == ticket) local.update { it.copy(loadingMore = false) }
            }
        }
    }
    private suspend fun pageFailed(expected: Long, generation: Long, result: ClientResult<*>) {
        current(expected, generation)
        local.update { it.copy(problem = problem(result)) }
    }
    private fun <T> PageWindow<T>.add(forward: Boolean, page: WindowedPage<T>) = if (forward) append(page) else prepend(page)

    private fun WindowedPage<TaskContext>.shownContexts() =
        WindowedPage(rows.map { Shown(it, TaskContextRow(it.id, it.title)) }, next, previous)
    private fun WindowedPage<TaskRecord>.shownRecords() = WindowedPage(rows.map { Shown(it, it.row()) }, next, previous)
    private fun WindowedPage<TaskPermission>.shownPermissions() = WindowedPage(rows.map { Shown(it, it.row()) }, next, previous)

    /** Publishes the shown page's window (at most [WINDOW_PAGES] pages of already projected rows) or detail. */
    private fun publish() {
        when (local.value.page) {
            TasksPage.CONVERSATIONS -> local.update {
                it.copy(
                    contexts = contexts.rows().map { shown -> shown.row }.toPersistentList(),
                    canMore = contexts.next != null,
                    hasBefore = contexts.previous != null
                )
            }
            TasksPage.LIST -> local.update {
                it.copy(
                    rows = records.rows().map { shown -> shown.row }.toPersistentList(),
                    canMore = records.next != null,
                    hasBefore = records.previous != null
                )
            }
            TasksPage.DETAIL -> local.update { it.copy(selected = detail?.row(), canMore = false, hasBefore = false) }
            TasksPage.PERMISSIONS -> local.update {
                it.copy(
                    permissions = permissions.rows().map { shown -> shown.row }.toPersistentList(),
                    canMore = permissions.next != null,
                    hasBefore = permissions.previous != null
                )
            }
            TasksPage.PERMISSION -> local.update { it.copy(selectedPermission = permission?.row(), canMore = false, hasBefore = false) }
        }
    }

    /** Re-reads the approval; only an unchanged, still pending approval reaches the host confirmation. */
    private fun reviewApproval(id: String, approve: Boolean) {
        val account = owner ?: return
        if (!local.value.fresh || local.value.busy || request?.isActive == true) return
        val shown = (if (local.value.page == TasksPage.PERMISSION) permission?.takeIf { it.id == id } else null)
            ?: permissions.rows().find { it.value.id == id }?.value ?: return
        if (!shown.decidable || approve && !shown.approvable) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val observed = revision
        local.update { it.copy(busy = true, problem = null, notice = null) }
        request = viewModelScope.launch {
            try {
                val fresh = tasks.permission(account, shown.kind, shown.reference)
                if (validateAccount(expected, generation, account) == null) return@launch
                val value = (fresh as? ClientResult.Ready)?.value
                if (observed != revision || value != shown || !value.decidable || approve && !value.approvable) {
                    val changed = if (fresh is ClientResult.Ready) uiText(R.string.tasks_approval_changed) else problem(fresh)
                    local.update { it.copy(problem = changed) }
                    needsRefresh = true
                    return@launch
                }
                val token = UUID.randomUUID().toString()
                preparedApproval = Triple(token, value, approve)
                prepared = null
                local.update {
                    it.copy(
                        confirmation = TaskConfirmation(
                            token,
                            uiText(if (approve) R.string.tasks_approve else R.string.tasks_deny),
                            value.approvalFacts(account, approve)
                        )
                    )
                }
            } finally {
                if (expected == ticket) {
                    local.update { it.copy(busy = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read()
                    }
                }
            }
        }
    }
    private fun decide(approval: TaskPermission, approve: Boolean) {
        val account = owner ?: return
        if (!local.value.fresh || request?.isActive == true || local.value.busy) return
        val expected = ++ticket
        val generation = session.status.value.generation
        preparedApproval = null
        local.update { it.copy(confirmation = null, busy = true, fresh = false, problem = null, notice = null) }
        request = viewModelScope.launch {
            try {
                if (validateAccount(expected, generation, account) == null) return@launch
                val result = tasks.decideApproval(account, approval, approve)
                if (validateAccount(expected, generation, account) == null) return@launch
                local.update {
                    it.copy(
                        notice = (result as? ClientResult.Ready)?.let {
                            uiText(if (approve) R.string.tasks_approval_approved else R.string.tasks_approval_denied)
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
                        read()
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
                        read()
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
    private companion object {
        /** Pages held per list: 300 tasks or contexts, 600 permission rows at the repository's page sizes. */
        const val WINDOW_PAGES = 6
    }
}
