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

/**
 * Contacts, the strangers area, sent requests and blocked contacts, each read through the repository's windowed
 * pages into a bounded [PageWindow]. Filtering runs in the data layer over the SDK snapshot, off the main thread.
 * While visible, the screen follows the contact journal topics through the shared event hub and reloads the
 * window of the affected kind.
 */
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
        if (status.phase == ClientPhase.OPEN) screen else ContactsState(problem = uiText(R.string.contacts_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ContactsState())
    private var request: Job? = null
    private var lookupJob: Job? = null
    private var queryJob: Job? = null
    private var watch: Job? = null
    private var watchedAccount: String? = null
    private var visible = false
    private var ticket = 0L
    private var activeAccount: String? = null
    private var commands: List<ContactCommand> = emptyList()
    private val window = PageWindow<PersonItem>(WINDOW_PAGES) { it.id }
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            stopWatch()
            commands = emptyList()
            ticket++
            activeAccount = null
            local.value = ContactsState(tab = local.value.tab)
            if (phase == ClientPhase.OPEN) read()
        }
    }

    fun onAction(action: ContactsAction) {
        val overlayAction = action == ContactsAction.Confirm || action == ContactsAction.Deny || action is ContactsAction.Visible
        if (local.value.approval != null && !overlayAction) {
            return
        }
        when (action) {
            ContactsAction.Confirm -> if (local.value.approval != null && request?.isActive != true) executeApproved()
            ContactsAction.Deny -> {
                request?.cancel()
                ticket++
                commands = emptyList()
                local.update { it.copy(approval = null, loading = false) }
            }
            ContactsAction.Refresh -> if (request?.isActive != true && local.value.page == ContactsPage.LIST) read()
            ContactsAction.LoadMore -> if (request?.isActive != true && window.next != null) readPage(forward = true)
            ContactsAction.LoadPrevious -> if (request?.isActive != true && window.previous != null) readPage(forward = false)
            ContactsAction.SetupAccount -> event(ContactsEvent.SetupAccount)
            is ContactsAction.Visible -> {
                visible = action.visible
                if (visible) activeAccount?.let(::startWatch) else stopWatch()
            }
            ContactsAction.Back -> if (local.value.page == ContactsPage.LIST) {
                event(ContactsEvent.Back)
            } else {
                request?.cancel()
                ticket++
                val nested = local.value.page in setOf(ContactsPage.SAFETY, ContactsPage.DECLINE)
                val back = if (nested) ContactsPage.DETAIL else ContactsPage.LIST
                local.update {
                    it.copy(
                        page = back,
                        selected = if (back == ContactsPage.LIST) null else it.selected,
                        selectedIds = persistentSetOf(),
                        loading = false,
                        unavailable = null,
                        safety = null
                    )
                }
                // Changes that arrived while another page was open were not read (the watch reads the list only on
                // the list), so returning to the list reloads it.
                if (back == ContactsPage.LIST) read()
            }
            is ContactsAction.Tab -> if (local.value.tab != action.tab) {
                request?.cancel()
                queryJob?.cancel()
                ticket++
                local.value = ContactsState(tab = action.tab, accountLabel = local.value.accountLabel)
                read()
            }
            is ContactsAction.Query -> {
                val text = action.text.take(MAX_QUERY)
                if (text == local.value.query) return
                local.update { it.copy(query = text) }
                queryJob?.cancel()
                queryJob = viewModelScope.launch {
                    delay(QUERY_DEBOUNCE_MS)
                    request?.cancel()
                    read()
                }
            }
            is ContactsAction.Address -> {
                // A lookup still in flight answers for the old text: retire it so its reply can never reach the new field.
                // Only a lookup is retired here; a submit or approved operation is never cancelled by typing.
                val lookup = lookupJob?.takeIf { it === request && it.isActive }
                if (lookup != null) {
                    lookup.cancel()
                    ticket++
                }
                local.update {
                    it.copy(
                        address = action.text.take(4096),
                        loading = if (lookup != null) false else it.loading,
                        unavailable = null,
                        username = null
                    )
                }
            }
            is ContactsAction.Select -> local.value.rows.find { it.id == action.id }?.let { row ->
                request?.cancel()
                ticket++
                local.update { it.copy(page = ContactsPage.DETAIL, selected = row, unavailable = null, problem = null, loading = false) }
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
                local.update { it.copy(page = action.page, unavailable = null, problem = null, loading = false, safety = null) }
                if (action.page == ContactsPage.SAFETY) readSafety("open")
            }
            is ContactsAction.Operate -> preflight(action.operation)
            ContactsAction.Message -> message()
        }
    }

    /** Addresses the contact's direct conversation by root anchor and opens it; the profile stays as it is. */
    private fun message() {
        val row = local.value.selected ?: return
        val root = row.root ?: return
        if (row.request || row.blocked) return
        val owner = activeAccount ?: return
        request?.cancel()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, problem = null, unavailable = null) }
        request = viewModelScope.launch {
            try {
                if (account(expected, generation, owner) == null) return@launch
                val result = directory.conversation(owner, root)
                current(expected, generation)
                if (account(expected, generation, owner) == null) return@launch
                when (result) {
                    is ClientResult.Ready -> result.value?.let { channel.send(ContactsEvent.OpenChat(owner, it)) }
                        ?: local.update { it.copy(unavailable = uiText(R.string.contacts_no_conversation)) }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }

    private fun event(event: ContactsEvent) {
        request?.cancel()
        stopWatch()
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
        stopWatch()
        local.value = ContactsState(
            tab = local.value.tab, query = local.value.query, problem = problem, unavailable = unavailable, accountRequired = required
        )
    }

    /** Reads the first page of the selected tab into a fresh window. */
    private fun read() {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val tab = local.value.tab
        val query = local.value.query
        local.update { it.copy(loading = true, problem = null, unavailable = null) }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation) ?: return@launch
                val result = page(selected.id, tab, query, null)
                current(expected, generation)
                if (account(expected, generation, selected.id) == null) return@launch
                activeAccount = selected.id
                if (visible) startWatch(selected.id)
                val uncertain = directory.attempts(selected.id).filter { it.phase == ContactAttemptPhase.UNKNOWN }
                    .map { uiText(R.string.contacts_unknown_operation, UiText.Data(it.operationId)) }.toPersistentList()
                when (result) {
                    is ClientResult.Ready -> {
                        window.reset(result.value)
                        local.update {
                            it.copy(
                                loaded = true, accountLabel = selected.label, uncertain = uncertain,
                                rows = window.rows().toPersistentList(),
                                hasMore = window.next != null, hasBefore = window.previous != null
                            )
                        }
                    }
                    is ClientResult.Failed -> local.update { it.copy(uncertain = uncertain, problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(uncertain = uncertain, unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }

    private fun readPage(forward: Boolean) {
        val owner = activeAccount ?: return
        val cursor = (if (forward) window.next else window.previous) ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        val tab = local.value.tab
        val query = local.value.query
        local.update { it.copy(loadingMore = true) }
        request = viewModelScope.launch {
            try {
                val result = page(owner, tab, query, cursor)
                current(expected, generation)
                when (result) {
                    is ClientResult.Ready -> {
                        if (forward) window.append(result.value) else window.prepend(result.value)
                        local.update {
                            it.copy(
                                rows = window.rows().toPersistentList(), hasMore = window.next != null, hasBefore = window.previous != null
                            )
                        }
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loadingMore = false) }
            }
        }
    }

    private suspend fun page(account: String, tab: ContactsTab, query: String, cursor: String?): ClientResult<WindowedPage<PersonItem>> =
        when (tab) {
            ContactsTab.CONTACTS, ContactsTab.BLOCKED -> directory.contactPage(
                account,
                if (tab == ContactsTab.BLOCKED) ContactListScope.BLOCKED else ContactListScope.ACCEPTED,
                query,
                cursor
            ).map { it.person(blocked = tab == ContactsTab.BLOCKED) }
            ContactsTab.STRANGERS, ContactsTab.SENT -> directory.requestPage(
                account,
                if (tab == ContactsTab.STRANGERS) ContactRequestScope.STRANGERS else ContactRequestScope.SENT,
                query,
                cursor
            ).map { it.person() }
        }

    /** One shared subscription while the screen is visible; cancelled when it leaves the screen. */
    private fun startWatch(account: String) {
        if (watch?.isActive == true && watchedAccount == account) return
        stopWatch()
        watchedAccount = account
        val generation = session.status.value.generation
        watch = viewModelScope.launch {
            directory.changes(account).conflate().collect { result ->
                if (session.status.value.generation != generation || activeAccount != account) return@collect
                val change = (result as? ClientResult.Ready)?.value ?: return@collect
                val state = local.value
                val affects = when (change) {
                    ContactChange.RELOAD -> true
                    ContactChange.CONTACTS -> state.tab == ContactsTab.CONTACTS || state.tab == ContactsTab.BLOCKED
                    ContactChange.REQUESTS -> state.tab != ContactsTab.BLOCKED
                    ContactChange.SAFETY -> state.page == ContactsPage.SAFETY || state.tab == ContactsTab.CONTACTS
                }
                val safetyRead = state.page == ContactsPage.SAFETY &&
                    (change == ContactChange.SAFETY || change == ContactChange.RELOAD)
                if (affects && safetyRead) {
                    val busy = request?.isActive == true
                    val approving = state.approval != null
                    clientDebugEvent(
                        "contacts.safety.event:" + change.name + (if (busy || approving) ".ignored" else ".read") +
                            ".request:" + busy + ".approval:" + approving
                    )
                }
                if (!affects || request?.isActive == true || state.approval != null) return@collect
                when {
                    safetyRead -> readSafety("event")
                    state.page == ContactsPage.LIST -> read()
                }
            }
        }
    }

    private fun stopWatch() {
        watch?.cancel()
        watch = null
        watchedAccount = null
    }

    /** [source] names what asked for the read (open, reread after verify, or event); it is traced, never shown. */
    private fun readSafety(source: String) {
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
                clientDebugEvent(
                    "contacts.safety.read:" + source + "." + when (result) {
                        is ClientResult.Ready -> "state:" + result.value.state.name + ".verifiable:" + result.value.verifiable
                        is ClientResult.Failed -> "failed:" + (result.problem.statusName ?: result.problem.code)
                        is ClientResult.Unavailable -> "unavailable:" + result.reason.name
                    }
                )
                when (result) {
                    is ClientResult.Ready -> local.update {
                        val verified = result.value.state == SafetyState.VERIFIED
                        it.copy(
                            safety = SafetyItem(
                                result.value.number, result.value.fingerprint, result.value.state.message(),
                                verified,
                                result.value.verifiable && !verified
                            ),
                            // The read just made is the refresh that a confirmation asked for: the acted-on contact
                            // shows the state read here, and the "refresh to read" notice no longer applies.
                            selected = it.selected?.let { row -> if (row.root == root) row.withVerified(verified) else row },
                            rows = it.rows.map { row -> if (row.root == root) row.withVerified(verified) else row }.toPersistentList(),
                            operationNotice = null
                        )
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }

    private fun preflight(operation: ContactOperation) {
        if (request?.isActive == true || session.status.value.phase != ClientPhase.OPEN) return
        // An `@name` is looked up first; the request goes out only for the name the lookup found.
        if (operation == ContactOperation.ADD && local.value.usernameNeedsLookup()) {
            lookupUsername()
            return
        }
        val selected = local.value.selected
        when (operation) {
            ContactOperation.ACCEPT, ContactOperation.DECLINE, ContactOperation.BLOCK -> if (selected?.inbound != true) return
            ContactOperation.REMOVE, ContactOperation.BLOCK_CONTACT -> if (selected == null || selected.request || selected.blocked) return
            ContactOperation.UNBLOCK -> if (selected == null || selected.request || !selected.blocked) return
            ContactOperation.VERIFY -> if (selected?.root == null || local.value.safety?.verifiable != true) return
            ContactOperation.BATCH_DECLINE -> if (local.value.selectedIds.isEmpty()) return
            ContactOperation.ADD -> if (local.value.address.isBlank()) return
            ContactOperation.REPORT, ContactOperation.REPLY, ContactOperation.SCAN -> {
                local.update { it.copy(unavailable = uiText(R.string.contacts_sdk_unavailable)) }
                return
            }
        }
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
                            ContactOperation.VERIFY -> listOfNotNull(selected?.root)
                            else -> listOfNotNull(selected?.id)
                        }
                        if (targets.isEmpty()) return@launch
                        val uncertain = directory.attempts(active.id)
                            .find { it.phase == ContactAttemptPhase.UNKNOWN && it.target in targets }
                        if (uncertain != null) {
                            val notice = uiText(R.string.contacts_unknown_operation, UiText.Data(uncertain.operationId))
                            local.update { it.copy(problem = notice) }
                            return@launch
                        }
                        val actual = if (operation == ContactOperation.BATCH_DECLINE) ContactOperation.DECLINE else operation
                        commands = targets.map { target ->
                            ContactCommand(
                                active.id, java.util.UUID.randomUUID().toString(), actual, target,
                                local.value.rows.find {
                                    it.id == target
                                }?.principal ?: selected?.principal ?: target
                            )
                        }
                        val shown = when (operation) {
                            ContactOperation.ADD -> targets
                            ContactOperation.BATCH_DECLINE -> local.value.rows.filter { it.id in local.value.selectedIds }.map { it.label }
                            else -> listOfNotNull(selected?.label)
                        }
                        local.update {
                            val approval =
                                ContactApproval(active.id, operation.label(), shown.toPersistentList(), operation.explanation(), operation)
                            it.copy(approval = approval, unavailable = null)
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

    private fun lookupUsername() {
        val query = local.value.address.trim()
        val name = query.removePrefix(USERNAME_PREFIX.toString())
        if (name.isEmpty()) return
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, username = null, problem = null, unavailable = null) }
        lookupJob = viewModelScope.launch {
            try {
                val active = account(expected, generation, activeAccount) ?: return@launch
                val result = directory.resolveUsername(active.id, name)
                current(expected, generation)
                if (account(expected, generation, active.id) == null) return@launch
                local.update {
                    it.copy(
                        username = when (result) {
                            is ClientResult.Ready -> UsernameLookup.Found(query, name, result.value.displayName, result.value.bio)
                            is ClientResult.Failed -> UsernameLookup.Failed(usernameLookupMessage(result.problem))
                            is ClientResult.Unavailable -> UsernameLookup.Failed(result.reason.message())
                        }
                    )
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
        request = lookupJob
    }

    private fun usernameLookupMessage(problem: ClientProblem): UiText = when (problem.check) {
        "not_found" -> uiText(R.string.contacts_username_not_found)
        "invalid_username" -> uiText(R.string.contacts_username_invalid)
        "rate_limited", "capacity" -> problem.retryAfterSecs?.let { uiText(R.string.contacts_username_retry, UiText.Data(it.toString())) }
            ?: uiText(R.string.contacts_username_unreachable)
        "directory_unavailable" -> uiText(R.string.contacts_username_unreachable)
        else -> problem.message()
    }

    private fun executeApproved() {
        if (request?.isActive == true || commands.isEmpty()) return
        val prepared = commands
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, submitting = true, approval = null, operationNotice = null, problem = null) }
        request = viewModelScope.launch {
            var done = false
            try {
                for (command in prepared) {
                    if (account(expected, generation, command.account) == null) return@launch
                    val result = directory.submit(command, confirmed = true)
                    current(expected, generation)
                    if (account(expected, generation, command.account) == null) return@launch
                    clientDebugEvent(
                        "contacts.submit:" + command.operation.name + "." + when (result) {
                            is ClientResult.Ready -> "ok"
                            is ClientResult.Failed ->
                                "failed:" + (result.problem.statusName ?: result.problem.code) + ".outcome:" + result.problem.outcome.name +
                                    (result.problem.reason?.let { ".reason:" + it.name } ?: "")
                            is ClientResult.Unavailable -> "unavailable:" + result.reason.name
                        }
                    )
                    when (result) {
                        is ClientResult.Ready -> {
                            window.remove(command.target)
                            local.update { it.copy(operationNotice = uiText(R.string.contacts_confirmed_refresh)) }
                        }
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
                done = true
            } finally {
                commands = emptyList()
                local.update { it.copy(submitting = false) }
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
            if (!done) return@launch
            val operation = prepared.first().operation
            if (operation == ContactOperation.VERIFY) {
                readSafety("reread")
            } else if (operation != ContactOperation.ADD) {
                // The acted-on row left this list; show the list without it and reload the window.
                local.update {
                    it.copy(
                        page = ContactsPage.LIST, selected = null, selectedIds = persistentSetOf(), rows = window.rows().toPersistentList()
                    )
                }
                read()
            }
        }
    }

    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (expected != ticket || generation != session.status.value.generation || session.status.value.phase != ClientPhase.OPEN) {
            throw CancellationException("retired contacts")
        }
    }

    private companion object {
        /** Six pages of the repository's page size: at most 600 rows held. */
        const val WINDOW_PAGES = 6
        const val MAX_QUERY = 256
        const val QUERY_DEBOUNCE_MS = 250L
    }
}

private inline fun <T, R> ClientResult<WindowedPage<T>>.map(transform: (T) -> R): ClientResult<WindowedPage<R>> = when (this) {
    is ClientResult.Ready -> ClientResult.Ready(WindowedPage(value.rows.map(transform), value.next, value.previous))
    is ClientResult.Failed -> this
    is ClientResult.Unavailable -> this
}

private fun ContactSummary.person(blocked: Boolean) = PersonItem(
    id,
    principal,
    name?.takeIf { it.isNotBlank() } ?: principal,
    uiText(if (blocked) R.string.contacts_blocked else if (verified) R.string.contacts_verified else R.string.contacts_unverified),
    false,
    root = root,
    blocked = blocked,
    verified = verified,
    ai = ai
)

/** The row after a safety read: the status line the list and the detail show follows the state that was read last. */
private fun PersonItem.withVerified(verified: Boolean): PersonItem = copy(
    verified = verified,
    status = if (request || blocked) status else uiText(if (verified) R.string.contacts_verified else R.string.contacts_unverified)
)

private fun ContactRequestSummary.person() = PersonItem(
    id, principal, name?.takeIf { it.isNotBlank() } ?: principal, status.message(), true,
    direction == RequestDirection.INBOUND, note,
    UiText.Quantity(R.plurals.contacts_messages, messageCount), UiText.DateTime(expiresAt),
    root = root,
    ai = ai
)

private fun ClientProblem.message() = failureText(R.string.contacts_read_failed, code, statusName, check)
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
private fun SafetyState.message() = uiText(
    when (this) {
        SafetyState.UNVERIFIED -> R.string.contacts_safety_state_unverified
        SafetyState.VERIFIED -> R.string.contacts_safety_state_verified
        SafetyState.CHANGED -> R.string.contacts_safety_state_changed
        SafetyState.BLOCKED_BY_POLICY -> R.string.contacts_safety_state_blocked
        SafetyState.UNKNOWN -> R.string.contacts_unknown
    }
)

private fun ContactOperation.label() = uiText(
    when (this) {
        ContactOperation.ADD -> R.string.contacts_send_request
        ContactOperation.ACCEPT -> R.string.contacts_accept
        ContactOperation.DECLINE, ContactOperation.BATCH_DECLINE -> R.string.contacts_decline
        ContactOperation.BLOCK, ContactOperation.BLOCK_CONTACT -> R.string.contacts_block
        ContactOperation.REPORT -> R.string.contacts_report
        ContactOperation.REPLY -> R.string.contacts_reply
        ContactOperation.VERIFY -> R.string.contacts_numbers_match
        ContactOperation.SCAN -> R.string.contacts_scan
        ContactOperation.REMOVE -> R.string.contacts_remove
        ContactOperation.UNBLOCK -> R.string.contacts_unblock
    }
)

private fun ContactOperation.explanation(): UiText? = when (this) {
    ContactOperation.REMOVE -> uiText(R.string.contacts_remove_explanation)
    ContactOperation.BLOCK, ContactOperation.BLOCK_CONTACT -> uiText(R.string.contacts_block_explanation)
    ContactOperation.UNBLOCK -> uiText(R.string.contacts_unblock_explanation)
    ContactOperation.VERIFY -> uiText(R.string.contacts_verify_explanation)
    ContactOperation.DECLINE, ContactOperation.BATCH_DECLINE -> uiText(R.string.contacts_decline_explanation)
    else -> null
}
