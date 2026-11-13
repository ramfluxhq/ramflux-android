// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.agents.impl.R
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
    private var visible = false
    private var owner: String? = null
    private var request: Job? = null
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            retire()
            owner = null
            local.value = AgentsState()
            if (visible && it.first == ClientPhase.OPEN) load(false)
        }
    }
    fun onAction(action: AgentsAction) {
        when (action) {
            is AgentsAction.Visible -> {
                visible = action.value
                if (visible) {
                    load(false)
                } else {
                    retire()
                    owner = null
                    local.value = AgentsState()
                }
            }
            is AgentsAction.Mode -> if (visible) {
                retire()
                local.update { it.copy(mode = action.value, problem = null, unavailable = false) }
                load(false)
            }
            is AgentsAction.Name -> if (editable()) {
                local.update { it.copy(name = action.value.take(1024)) }
                issues()
            }
            is AgentsAction.Goal -> if (editable()) {
                local.update { it.copy(goal = action.value.take(1024)) }
                issues()
            }
            is AgentsAction.Avatar -> if (editable()) local.update { it.copy(avatar = action.value) }
            AgentsAction.Create -> if (editable() && local.value.mode == AgentCapability.CREATE) {
                issues()
                if (draft().issue == null) load(true)
            }
            AgentsAction.Refresh -> if (request?.isActive != true) load(false)
            AgentsAction.Back -> {
                if (local.value.mode != AgentCapability.DIRECTORY) {
                    onAction(AgentsAction.Mode(AgentCapability.DIRECTORY))
                } else {
                    retire()
                    owner = null
                    local.value = AgentsState()
                    viewModelScope.launch { channel.send(AgentsEvent.Back) }
                }
            }
            AgentsAction.SetupAccount -> viewModelScope.launch { channel.send(AgentsEvent.SetupAccount) }
        }
    }
    private fun editable() =
        visible && session.status.value.phase == ClientPhase.OPEN && !local.value.loading && !local.value.accountRequired
    private fun draft() = AgentDraft(local.value.name, local.value.goal, local.value.avatar)
    private fun issues() {
        val draft = draft()
        val nameIssue = draft.copy(goal = "valid").issue
        val goalIssue = draft.copy(name = "valid").issue
        local.update { it.copy(nameIssue = nameIssue?.label(), goalIssue = goalIssue?.label()) }
    }
    private fun retire() {
        request?.cancel()
        ticket++
    }
    private fun load(create: Boolean) {
        if (!visible || session.status.value.phase != ClientPhase.OPEN) return
        retire()
        val expected = ticket
        val generation = session.status.value.generation
        val mode = local.value.mode
        val draft = draft()
        local.update { it.copy(loading = true, problem = null, unavailable = false) }
        request = viewModelScope.launch {
            try {
                val account = account(expected, generation) ?: return@launch
                val result = if (create) agents.create(account, draft) else agents.availability(account, mode)
                current(expected, generation)
                if (account(expected, generation) != account) return@launch
                // No published Agent capability exists yet; unexpected Ready cannot fabricate provisioning/navigation.
                when (result) {
                    is ClientResult.Failed -> local.update {
                        it.copy(problem = uiText(R.string.agents_read_failure, UiText.Number(result.problem.code.toLong())))
                    }
                    is ClientResult.Ready, is ClientResult.Unavailable -> local.update { it.copy(unavailable = true) }
                }
            } finally {
                if (ticket == expected) local.update { it.copy(loading = false) }
            }
        }
    }
    private suspend fun account(expected: Long, generation: Long): String? {
        current(expected, generation)
        val result = accounts.list()
        current(expected, generation)
        if (result !is ClientResult.Ready) {
            local.value = AgentsState(
                problem = when (result) {
                    is ClientResult.Failed -> uiText(R.string.agents_read_failure, UiText.Number(result.problem.code.toLong()))
                    else -> uiText(R.string.agents_unavailable)
                }
            )
            owner = null
            return null
        }
        val selected = result.value.accounts.singleOrNull { it.id == result.value.activeId && it.unlocked }
        if (selected == null) {
            owner = null
            local.value = AgentsState(accountRequired = true)
            return null
        }
        if (owner != null && owner != selected.id) {
            owner = selected.id
            local.value = AgentsState(accountRequired = true)
            return null
        }
        owner = selected.id
        local.update { it.copy(accountRequired = false) }
        return selected.id
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!visible || ticket != expected || session.status.value.generation != generation ||
            session.status.value.phase != ClientPhase.OPEN
        ) {
            throw CancellationException("retired agents")
        }
    }
}
private fun AgentDraftIssue.label() = uiText(
    when (this) {
        AgentDraftIssue.NAME_REQUIRED -> R.string.agents_name_required
        AgentDraftIssue.NAME_LIMIT -> R.string.agents_name_limit
        AgentDraftIssue.GOAL_REQUIRED -> R.string.agents_goal_required
        AgentDraftIssue.GOAL_LIMIT -> R.string.agents_goal_limit
    }
)
