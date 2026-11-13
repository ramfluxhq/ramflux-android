// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val directory: ContactDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(ContactsState())
    private val channel = Channel<ContactsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) {
            screen.copy(
                rows = screen.rows.filter {
                    screen.query.isBlank() || it.label.contains(screen.query, ignoreCase = true) ||
                        it.principal.contains(screen.query, ignoreCase = true)
                }.toPersistentList()
            )
        } else {
            ContactsState(problem = uiText(R.string.contacts_closed))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ContactsState())
    private var request: Job? = null
    private var ticket = 0L
    private var activeAccount: String? = null
    private var commands: List<ContactCommand> = emptyList()
    private var hold: Job? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            hold?.cancel()
            commands = emptyList()
            ticket++
            activeAccount = null
            local.value = ContactsState(tab = local.value.tab)
            if (phase == ClientPhase.OPEN) read()
        }
    }
    fun onAction(action: ContactsAction) {
        if (local.value.approval != null && action !is ContactsAction.HoldEnd &&
            action != ContactsAction.HoldStart && action != ContactsAction.Deny
        ) {
            return
        }
        when (action) {
            ContactsAction.HoldStart -> if (local.value.approval != null && request?.isActive != true) {
                if (hold?.isActive == true || local.value.approval?.held == true) return
                local.update { it.copy(approval = it.approval?.copy(held = false)) }
                hold = viewModelScope.launch {
                    delay(1_200)
                    local.update { it.copy(approval = it.approval?.copy(held = true)) }
                }
            }
            is ContactsAction.HoldEnd -> {
                hold?.cancel()
                if (action.released && local.value.approval?.held == true) {
                    executeApproved()
                } else {
                    local.update { it.copy(approval = it.approval?.copy(held = false)) }
                }
            }
            ContactsAction.Deny -> {
                hold?.cancel()
                request?.cancel()
                ticket++
                commands = emptyList()
                local.update { it.copy(approval = null, loading = false) }
            }
            ContactsAction.Refresh -> if (request?.isActive != true) read()
            ContactsAction.SetupAccount -> event(ContactsEvent.SetupAccount)
            ContactsAction.Back -> if (local.value.page == ContactsPage.LIST) {
                event(ContactsEvent.Back)
            } else {
                request?.cancel()
                ticket++
                local.update {
                    it.copy(
                        page = ContactsPage.LIST,
                        selected = null,
                        selectedIds = persistentSetOf(),
                        loading = false,
                        unavailable = null
                    )
                }
            }
            is ContactsAction.Tab -> if (local.value.tab != action.tab) {
                request?.cancel()
                ticket++
                local.value = ContactsState(tab = action.tab)
                read()
            }
            is ContactsAction.Query -> local.update { it.copy(query = action.text.take(256)) }
            is ContactsAction.Address -> local.update { it.copy(address = action.text.take(4096), unavailable = null) }
            is ContactsAction.Select -> local.value.rows.find { it.id == action.id }?.let { row ->
                request?.cancel()
                ticket++
                local.update { it.copy(page = ContactsPage.DETAIL, selected = row, unavailable = null, loading = false) }
            }
            is ContactsAction.Toggle -> if (local.value.rows.any { it.id == action.id && it.inbound }) {
                local.update {
                    it.copy(
                        selectedIds = if (action.id in it.selectedIds) {
                            (it.selectedIds - action.id).toPersistentSet()
                        } else {
                            (it.selectedIds + action.id).toPersistentSet()
                        }
                    )
                }
            }
            is ContactsAction.Page -> {
                if (action.page in setOf(ContactsPage.SAFETY, ContactsPage.DECLINE) && local.value.selected == null) return
                if (action.page == ContactsPage.BATCH && local.value.tab != ContactsTab.STRANGERS) return
                request?.cancel()
                ticket++
                local.update { it.copy(page = action.page, unavailable = null, loading = false, safety = null) }
                if (action.page == ContactsPage.SAFETY) readSafety()
            }
            is ContactsAction.Operate -> preflight(action.operation)
        }
    }
    private fun event(event: ContactsEvent) {
        request?.cancel()
        ticket++
        activeAccount = null
        local.value = ContactsState()
        viewModelScope.launch { channel.send(event) }
    }
    private suspend fun account(expected: Long, generation: Long, required: String? = null): AccountSummary? {
        val catalog = accounts.list()
        current(expected, generation)
        return when (catalog) {
            is ClientResult.Failed -> {
                clear(problem = catalog.problem.message())
                null
            }
            is ClientResult.Unavailable -> {
                clear(unavailable = catalog.reason.message())
                null
            }
            is ClientResult.Ready -> {
                val selected = catalog.value.accounts.find { it.id == catalog.value.activeId && it.unlocked }
                if (selected == null) {
                    clear(required = true)
                    null
                } else if (required != null && selected.id != required) {
                    clear(problem = uiText(R.string.contacts_account_changed))
                    null
                } else {
                    selected
                }
            }
        }
    }
    private fun clear(problem: UiText? = null, unavailable: UiText? = null, required: Boolean = false) {
        activeAccount = null
        local.value = ContactsState(tab = local.value.tab, problem = problem, unavailable = unavailable, accountRequired = required)
    }
    private fun read() {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val tab = local.value.tab
        local.value = ContactsState(tab = tab, loading = true)
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation) ?: return@launch
                val result = if (tab == ContactsTab.CONTACTS) {
                    when (val result = directory.contacts(selected.id)) {
                        is ClientResult.Ready -> ClientResult.Ready(
                            result.value.map {
                                PersonItem(
                                    it.id, it.principal, it.name?.takeIf { name -> name.isNotBlank() } ?: it.principal,
                                    uiText(if (it.verified) R.string.contacts_verified else R.string.contacts_unverified), false,
                                    root = it.root
                                )
                            }
                        )
                        is ClientResult.Failed -> result
                        is ClientResult.Unavailable -> result
                    }
                } else {
                    when (val result = directory.requests(selected.id)) {
                        is ClientResult.Ready -> ClientResult.Ready(
                            result.value.filter {
                                if (tab == ContactsTab.STRANGERS) {
                                    it.direction == RequestDirection.INBOUND && it.status == ContactRequestStatus.PENDING
                                } else {
                                    it.direction == RequestDirection.OUTBOUND
                                }
                            }.map {
                                PersonItem(
                                    it.id, it.principal,
                                    it.name?.takeIf { name -> name.isNotBlank() } ?: it.principal,
                                    it.status.message(), true,
                                    it.direction == RequestDirection.INBOUND, it.note,
                                    UiText.Quantity(R.plurals.contacts_messages, it.messageCount), UiText.DateTime(it.expiresAt),
                                    root = it.root
                                )
                            }
                        )
                        is ClientResult.Failed -> result
                        is ClientResult.Unavailable -> result
                    }
                }
                current(expected, generation)
                if (account(expected, generation, selected.id) == null) return@launch
                activeAccount = selected.id
                local.update {
                    it.copy(
                        uncertain = directory.attempts(selected.id).filter { attempt -> attempt.phase == ContactAttemptPhase.UNKNOWN }
                            .map { attempt ->
                                uiText(R.string.contacts_unknown_operation, UiText.Data(attempt.operationId))
                            }.toPersistentList()
                    )
                }
                when (result) {
                    is ClientResult.Ready -> {
                        if (result.value.size > 1000 || result.value.map { it.id }.distinct().size != result.value.size) {
                            clear(problem = uiText(R.string.contacts_invalid))
                            return@launch
                        }
                        local.update { it.copy(loaded = true, accountLabel = selected.label, rows = result.value.toPersistentList()) }
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun readSafety() {
        val row = local.value.selected ?: return
        val root = row.root
        if (root == null) {
            local.update { it.copy(unavailable = uiText(R.string.contacts_sdk_unavailable)) }
            return
        }
        val owner = activeAccount ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true) }
        request = viewModelScope.launch {
            try {
                if (account(expected, generation, owner) == null) return@launch
                val result = directory.safety(owner, root)
                current(expected, generation)
                if (account(expected, generation, owner) == null) return@launch
                when (result) {
                    is ClientResult.Ready -> local.update { it.copy(safety = SafetyItem(result.value.number, result.value.fingerprint)) }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun preflight(operation: ContactOperation) {
        if (operation == ContactOperation.VERIFY) {
            local.update { it.copy(unavailable = uiText(R.string.contacts_safety_binding_unavailable)) }
            return
        }
        if (request?.isActive == true || session.status.value.phase != ClientPhase.OPEN) return
        val selected = local.value.selected
        val needsRequest = operation in setOf(
            ContactOperation.ACCEPT, ContactOperation.DECLINE, ContactOperation.BLOCK, ContactOperation.REPORT, ContactOperation.REPLY
        )
        if (needsRequest && selected?.inbound != true) return
        if (operation in setOf(ContactOperation.VERIFY, ContactOperation.SCAN) && selected == null) return
        if (operation == ContactOperation.BATCH_DECLINE && local.value.selectedIds.isEmpty()) return
        if (operation == ContactOperation.ADD && local.value.address.isBlank()) return
        val expected = ++ticket
        val generation = session.status.value.generation
        request = viewModelScope.launch {
            try {
                val active = account(expected, generation, activeAccount) ?: return@launch
                val result = directory.availability(operation)
                current(expected, generation)
                if (account(expected, generation, active.id) == null) return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        val targets = when (operation) {
                            ContactOperation.ADD -> listOf(local.value.address)
                            ContactOperation.BATCH_DECLINE -> local.value.rows.filter { it.id in local.value.selectedIds }.map { it.id }
                            else -> listOfNotNull(selected?.id)
                        }
                        if (targets.isEmpty()) return@launch
                        val uncertain = directory.attempts(active.id).find {
                            it.phase == ContactAttemptPhase.UNKNOWN && it.target in targets
                        }
                        if (uncertain != null) {
                            local.update {
                                it.copy(
                                    problem = uiText(R.string.contacts_unknown_operation, UiText.Data(uncertain.operationId))
                                )
                            }
                            return@launch
                        }
                        val actual = if (operation == ContactOperation.BATCH_DECLINE) ContactOperation.DECLINE else operation
                        commands = targets.map { target ->
                            ContactCommand(
                                active.id, java.util.UUID.randomUUID().toString(), actual,
                                target, local.value.rows.find { it.id == target }?.principal ?: target
                            )
                        }
                        local.update {
                            it.copy(
                                approval = ContactApproval(active.id, operation.label(), targets.toPersistentList()), unavailable = null
                            )
                        }
                    }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun executeApproved() {
        if (request?.isActive == true || commands.isEmpty()) return
        val prepared = commands
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, approval = null, operationNotice = null) }
        request = viewModelScope.launch {
            try {
                for (command in prepared) {
                    if (account(expected, generation, command.account) == null) return@launch
                    val result = directory.submit(command, confirmed = true)
                    current(expected, generation)
                    if (account(expected, generation, command.account) == null) return@launch
                    when (result) {
                        is ClientResult.Ready -> local.update { it.copy(operationNotice = uiText(R.string.contacts_confirmed_refresh)) }
                        is ClientResult.Unavailable -> {
                            local.update { it.copy(unavailable = result.reason.message()) }
                            return@launch
                        }
                        is ClientResult.Failed -> {
                            local.update {
                                it.copy(
                                    problem = if (result.problem.outcome == ClientOutcome.UNKNOWN) {
                                        uiText(R.string.contacts_unknown_operation, UiText.Data(command.operationId))
                                    } else {
                                        result.problem.message()
                                    }
                                )
                            }
                            return@launch
                        }
                    }
                }
            } finally {
                commands = emptyList()
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (expected != ticket || generation != session.status.value.generation || session.status.value.phase != ClientPhase.OPEN) {
            throw CancellationException(
                "retired contacts"
            )
        }
    }
}
private fun ClientProblem.message() = uiText(R.string.contacts_read_failed, UiText.Number(code.toLong()))
private fun UnavailableReason.message() = uiText(
    when (this) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> R.string.contacts_action_unavailable
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> R.string.contacts_custody_unavailable
        UnavailableReason.UNSUPPORTED_BY_CORE -> R.string.contacts_sdk_unavailable
    }
)
private fun ContactRequestStatus.message() = uiText(
    when (this) {
        ContactRequestStatus.PENDING -> R.string.contacts_pending
        ContactRequestStatus.ACCEPTED -> R.string.contacts_accepted
        ContactRequestStatus.DECLINED -> R.string.contacts_declined
        ContactRequestStatus.BLOCKED -> R.string.contacts_blocked
        ContactRequestStatus.EXPIRED -> R.string.contacts_expired
        ContactRequestStatus.UNKNOWN -> R.string.contacts_unknown
    }
)

private fun ContactOperation.label() = uiText(
    when (this) {
        ContactOperation.ADD -> R.string.contacts_send_request
        ContactOperation.ACCEPT -> R.string.contacts_accept
        ContactOperation.DECLINE, ContactOperation.BATCH_DECLINE -> R.string.contacts_decline
        ContactOperation.BLOCK -> R.string.contacts_block
        ContactOperation.REPORT -> R.string.contacts_report
        ContactOperation.REPLY -> R.string.contacts_reply
        ContactOperation.VERIFY -> R.string.contacts_verify
        ContactOperation.SCAN -> R.string.contacts_scan
    }
)
