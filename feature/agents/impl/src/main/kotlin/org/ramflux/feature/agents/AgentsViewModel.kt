// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
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
import org.ramflux.designsystem.failureText
import org.ramflux.feature.agents.impl.R

/**
 * The agent directory of the active account: `agent.list` pages into a bounded [PageWindow], filtered by name
 * over the loaded rows off the main thread. The SDK projects no agent event topic, so a refresh (resume, pull,
 * after an own change) asks `agent.watch` for the records changed after the list's journal cursor and reloads
 * only when something changed. Edits and deletes need the host confirmation; creation stays on the desktop.
 */
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val agents: AgentDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(AgentsState())
    private val channel = Channel<AgentsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) value else AgentsState(problem = uiText(R.string.agents_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), AgentsState())
    private val window = PageWindow<AgentSummary>(WINDOW_PAGES) { it.anchor }
    private var visible = false
    private var owner: String? = null
    private var journal: String? = null
    private var request: Job? = null
    private var pageJob: Job? = null
    private var filterJob: Job? = null
    private var ticket = 0L

    /** The reviewed change waiting for the host confirmation; [Pending.delete] tells a delete from an edit. */
    private class Pending(val delete: Boolean, val run: suspend () -> ClientResult<AgentSummary>)
    private var pendingChange: Pending? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            reset()
            local.value = AgentsState()
            if (visible && it.first == ClientPhase.OPEN) read()
        }
    }

    fun onAction(action: AgentsAction) {
        val overlay = action == AgentsAction.Confirm || action == AgentsAction.Deny || action is AgentsAction.Visible
        if (local.value.review != null && !overlay) return
        when (action) {
            is AgentsAction.Visible -> {
                visible = action.value
                if (visible) {
                    read()
                } else {
                    reset()
                    local.value = AgentsState()
                }
            }
            AgentsAction.Refresh -> if (request?.isActive != true) refresh()
            AgentsAction.Back -> when (local.value.page) {
                AgentsPage.LIST -> {
                    reset()
                    local.value = AgentsState()
                    viewModelScope.launch { channel.send(AgentsEvent.Back) }
                }
                AgentsPage.PROFILE -> local.update { it.copy(page = AgentsPage.LIST, selected = null, notice = null, problem = null) }
                AgentsPage.EDIT -> local.update { it.copy(page = AgentsPage.PROFILE, nameIssue = null, responsibilityIssue = null) }
            }
            AgentsAction.SetupAccount -> viewModelScope.launch { channel.send(AgentsEvent.SetupAccount) }
            is AgentsAction.Query -> {
                val text = action.value.take(MAX_QUERY)
                if (text == local.value.query) return
                local.update { it.copy(query = text) }
                publish()
            }
            is AgentsAction.More -> more(action.forward)
            is AgentsAction.Select -> window.rows().find { it.anchor == action.id }?.let { record ->
                local.update { it.copy(page = AgentsPage.PROFILE, selected = record.profile(), notice = null, problem = null) }
                reread(record.anchor)
            }
            AgentsAction.Edit -> local.value.selected?.takeIf { it.row.active }?.let { profile ->
                local.update {
                    it.copy(
                        page = AgentsPage.EDIT,
                        name = profile.row.name,
                        responsibility = profile.row.responsibility,
                        nameIssue = null,
                        responsibilityIssue = null,
                        notice = null
                    )
                }
            }
            is AgentsAction.Name -> if (!local.value.busy) local.update { it.copy(name = action.value, nameIssue = null) }
            is AgentsAction.Responsibility -> if (!local.value.busy) {
                local.update {
                    it.copy(
                        responsibility = action.value,
                        responsibilityIssue = null
                    )
                }
            }
            AgentsAction.Save -> save()
            AgentsAction.Delete -> local.value.selected?.takeIf { it.row.active && !local.value.busy }?.let { profile ->
                val account = owner ?: return
                pendingChange = Pending(delete = true) { agents.delete(account, profile.row.id, confirmed = true) }
                local.update {
                    it.copy(
                        review = AgentReview(
                            uiText(R.string.agents_delete),
                            profile.row.name,
                            uiText(R.string.agents_delete_explanation),
                            account
                        )
                    )
                }
            }
            AgentsAction.Message -> message()
            AgentsAction.Deny -> {
                pendingChange = null
                local.update { it.copy(review = null) }
            }
            AgentsAction.Confirm -> if (local.value.review != null && request?.isActive != true) apply()
        }
    }

    private fun reset() {
        request?.cancel()
        pageJob?.cancel()
        filterJob?.cancel()
        ticket++
        owner = null
        journal = null
        pendingChange = null
        window.reset(WindowedPage(emptyList(), null, null))
    }

    /** Reads the first page of agents and the creation that waits for this device. */
    private fun read() {
        if (!visible || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        request?.cancel()
        pageJob?.cancel()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, problem = null) }
        request = viewModelScope.launch {
            try {
                val account = account(expected, generation) ?: return@launch
                val listed = agents.agentPage(account.id)
                current(expected, generation)
                val waiting = agents.pendingCreation(account.id)
                current(expected, generation)
                if (account(expected, generation, account.id) == null) return@launch
                when (listed) {
                    is ClientResult.Ready -> {
                        window.reset(listed.value.page)
                        journal = listed.value.journal
                        local.update {
                            it.copy(
                                loaded = true,
                                account = account.label ?: account.id,
                                pending = (waiting as? ClientResult.Ready)?.value?.row()
                            )
                        }
                        publish()
                    }
                    else -> failure(listed)
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }

    /** Asks `agent.watch` what changed after the journal cursor; reloads the window only when something did. */
    private fun refresh() {
        val account = owner
        val cursor = journal
        if (account == null || cursor == null || !local.value.loaded) {
            read()
            return
        }
        if (!visible || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        val expected = ++ticket
        val generation = session.status.value.generation
        request = viewModelScope.launch {
            val changed = agents.changes(account, cursor)
            current(expected, generation)
            when (changed) {
                is ClientResult.Ready -> if (changed.value.records.isEmpty() && !changed.value.more) {
                    journal = changed.value.journal
                    val waiting = agents.pendingCreation(account)
                    current(expected, generation)
                    local.update { it.copy(pending = (waiting as? ClientResult.Ready)?.value?.row() ?: it.pending) }
                } else {
                    val selected = local.value.selected?.row?.id
                    read()
                    selected?.let { id -> changed.value.records.find { it.anchor == id } }?.let { record ->
                        local.update { it.copy(selected = record.profile()) }
                    }
                }
                // A cursor the SDK no longer accepts (or any failure) falls back to a full read.
                else -> read()
            }
        }
    }

    private fun more(forward: Boolean) {
        val account = owner ?: return
        if (local.value.page != AgentsPage.LIST || request?.isActive == true || pageJob?.isActive == true) return
        val cursor = (if (forward) window.next else window.previous) ?: return
        val expected = ticket
        val generation = session.status.value.generation
        local.update { it.copy(loadingMore = true) }
        pageJob = viewModelScope.launch {
            try {
                val result = agents.agentPage(account, cursor)
                current(expected, generation)
                when (result) {
                    is ClientResult.Ready -> {
                        if (forward) window.append(result.value.page) else window.prepend(result.value.page)
                        publish()
                    }
                    else -> failure(result)
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Projects and filters the loaded window off the main thread (at most a few hundred rows). */
    private fun publish() {
        val rows = window.rows()
        val query = local.value.query
        val more = window.next != null
        val before = window.previous != null
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val shown = withContext(Dispatchers.Default) {
                rows.asSequence().filter { query.isBlank() || it.persona.name.contains(query, ignoreCase = true) }.map { it.row() }.toList()
            }
            local.update { it.copy(rows = shown.toPersistentList(), loadedCount = rows.size, hasMore = more, hasBefore = before) }
        }
    }

    private fun reread(anchor: String) {
        val account = owner ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        request?.cancel()
        request = viewModelScope.launch {
            val result = agents.agent(account, anchor)
            current(expected, generation)
            when (result) {
                is ClientResult.Ready -> local.update { screen ->
                    if (screen.selected?.row?.id == anchor) screen.copy(selected = result.value.profile()) else screen
                }
                else -> failure(result)
            }
        }
    }

    private fun save() {
        val screen = local.value
        val profile = screen.selected?.takeIf { it.row.active } ?: return
        val account = owner ?: return
        if (screen.busy) return
        val persona = AgentPersona(screen.name, profile.avatar, screen.responsibility)
        val nameIssue = when {
            persona.name.isBlank() -> uiText(R.string.agents_name_required)
            persona.name.toByteArray().size > AgentPersona.MAX_NAME_BYTES -> uiText(R.string.agents_name_limit)
            else -> null
        }
        val responsibilityIssue = when {
            persona.responsibility.isBlank() -> uiText(R.string.agents_responsibility_required)
            persona.responsibility.toByteArray().size > AgentPersona.MAX_RESPONSIBILITY_BYTES -> uiText(
                R.string.agents_responsibility_limit
            )
            else -> null
        }
        if (nameIssue != null || responsibilityIssue != null || !persona.valid) {
            local.update { it.copy(nameIssue = nameIssue, responsibilityIssue = responsibilityIssue) }
            return
        }
        pendingChange = Pending(delete = false) { agents.editPersona(account, profile.row.id, persona, confirmed = true) }
        local.update {
            it.copy(review = AgentReview(uiText(R.string.agents_edit), persona.name, uiText(R.string.agents_edit_explanation), account))
        }
    }

    /** Runs the reviewed edit or delete after the host confirmation. */
    private fun apply() {
        val change = pendingChange ?: return
        val account = owner ?: return
        val deleting = change.delete
        pendingChange = null
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(review = null, busy = true, problem = null, notice = null) }
        request = viewModelScope.launch {
            var done = false
            try {
                if (account(expected, generation, account) == null) return@launch
                val result = change.run()
                current(expected, generation)
                when (result) {
                    is ClientResult.Ready -> {
                        done = true
                        if (deleting) {
                            window.remove(result.value.anchor)
                            publish()
                            local.update { it.copy(page = AgentsPage.LIST, selected = null, notice = uiText(R.string.agents_deleted)) }
                        } else {
                            local.update {
                                val saved = result.value.profile()
                                it.copy(page = AgentsPage.PROFILE, selected = saved, notice = uiText(R.string.agents_saved))
                            }
                        }
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(
                            problem = if (result.problem.outcome == ClientOutcome.UNKNOWN) {
                                uiText(R.string.agents_unknown)
                            } else {
                                result.problem.message()
                            }
                        )
                    }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.agents_unavailable)) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(busy = false) }
            }
            if (done) refresh()
        }
    }

    /** Opens the existing direct conversation with the agent, or explains that none exists on this phone yet. */
    private fun message() {
        val profile = local.value.selected?.takeIf { it.row.active } ?: return
        val account = owner ?: return
        if (request?.isActive == true || local.value.busy) return
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(busy = true, notice = null, problem = null) }
        request = viewModelScope.launch {
            try {
                val result = agents.conversation(account, profile.row.id)
                current(expected, generation)
                if (account(expected, generation, account) == null) return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        val conversation = result.value
                        if (conversation == null) {
                            local.update { it.copy(notice = uiText(R.string.agents_no_conversation)) }
                        } else {
                            channel.send(AgentsEvent.OpenChat(account, conversation))
                        }
                    }
                    else -> failure(result)
                }
            } finally {
                if (expected == ticket) local.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun account(expected: Long, generation: Long, required: String? = null): AccountSummary? {
        val result = accounts.list()
        current(expected, generation)
        if (result !is ClientResult.Ready) {
            owner = null
            local.value = AgentsState(
                problem = when (result) {
                    is ClientResult.Failed -> result.problem.message()
                    else -> uiText(R.string.agents_unavailable)
                }
            )
            return null
        }
        val selected = result.value.accounts.singleOrNull { it.id == result.value.activeId && it.unlocked }
        if (selected == null || (required != null && selected.id != required) || (owner != null && owner != selected.id)) {
            reset()
            local.value = AgentsState(
                accountRequired = selected == null,
                problem = selected?.let { uiText(R.string.agents_account_changed) }
            )
            return null
        }
        owner = selected.id
        local.update { it.copy(accountRequired = false) }
        return selected
    }

    private fun failure(result: ClientResult<*>) {
        local.update {
            it.copy(
                problem = when (result) {
                    is ClientResult.Failed -> result.problem.message()
                    is ClientResult.Unavailable -> uiText(R.string.agents_unavailable)
                    is ClientResult.Ready -> null
                }
            )
        }
    }

    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!visible || ticket != expected || session.status.value.generation != generation ||
            session.status.value.phase != ClientPhase.OPEN
        ) {
            throw CancellationException("retired agents")
        }
    }

    private companion object {
        /** Twelve SDK pages of eight agents: at most 96 rows held. */
        const val WINDOW_PAGES = 12
        const val MAX_QUERY = 256
    }
}

private fun ClientProblem.message() = failureText(R.string.agents_read_failure, code, statusName, check)

/** The Kit glyph for a stored avatar token; anything else is the generic agent glyph. */
private fun avatarIcon(avatar: String) = when (avatar.lowercase()) {
    "orbit" -> "orbit"
    "prism", "gem", "diamond" -> "gem"
    "waves" -> "waves"
    "star" -> "star"
    "user", "person" -> "user-round"
    else -> "bot"
}

private fun AgentSummary.row() = AgentRow(
    anchor,
    persona.name.ifBlank { anchor },
    persona.responsibility,
    avatarIcon(persona.avatar),
    uiText(
        when (lifecycle) {
            AgentLifecycle.ACTIVE -> R.string.agents_status_active
            AgentLifecycle.RETIRED -> R.string.agents_status_deleted
            AgentLifecycle.UNKNOWN -> R.string.agents_status_unknown
        }
    ),
    lifecycle == AgentLifecycle.ACTIVE
)

private fun AgentSummary.profile() = AgentProfile(
    row(),
    persona.avatar,
    if (role == "named") uiText(R.string.agents_role_named) else uiText(R.string.agents_role_other, UiText.Data(role.ifBlank { "-" })),
    access.dm.audience(access.allowedHumans),
    access.groupInviters.audience(access.allowedHumans)
)

private fun String.audience(allowed: Int) = when (this) {
    "owner" -> uiText(R.string.agents_access_owner)
    "contacts" -> uiText(R.string.agents_access_contacts)
    "allowlist" -> UiText.Quantity(R.plurals.agents_access_allowlist, allowed.toULong())
    "open" -> uiText(R.string.agents_access_open)
    else -> uiText(R.string.agents_status_unknown)
}

private fun PendingAgentCreation.row() = PendingRow(
    requestId,
    name,
    uiText(if (state == "authorization_pending") R.string.agents_pending_waiting else R.string.agents_status_unknown),
    expiresAt?.let { UiText.DateTime(it) }
)
