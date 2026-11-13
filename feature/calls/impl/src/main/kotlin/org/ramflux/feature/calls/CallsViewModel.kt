// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
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
import org.ramflux.designsystem.*
import org.ramflux.feature.calls.impl.R
@HiltViewModel
class CallsViewModel @Inject constructor(
    private val calls: CallDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(CallsState())
    private val channel = Channel<CallsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(
        local,
        session.status
    ) { value, status -> if (status.phase == ClientPhase.OPEN) value else CallsState(problem = uiText(R.string.calls_closed)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), CallsState())
    private var active = false
    private var owner: String? = null
    private var cache: List<CallRecord> = emptyList()
    private var request: Job? = null
    private var watch: Job? = null
    private var ticket = 0L
    private var revision = 0L
    private var needsRefresh = false
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            stop()
            local.value = CallsState()
            if (active && it.first == ClientPhase.OPEN) read()
        }
    }
    fun onAction(action: CallsAction) {
        when (action) {
            is CallsAction.Visible -> {
                active = action.value
                if (active) {
                    read()
                } else {
                    stop()
                    local.value = CallsState()
                }
            }
            CallsAction.Refresh -> if (request?.isActive != true) read()
            CallsAction.Back -> if (local.value.selected != null) {
                local.update {
                    it.copy(selected = null, expanded = false)
                }
            } else {
                event(CallsEvent.Back)
            }
            CallsAction.SetupAccount -> event(CallsEvent.SetupAccount)
            is CallsAction.Select -> local.value.rows.find {
                it.id == action.id
            }?.let { row -> local.update { it.copy(selected = row, expanded = false) } }
            CallsAction.ToggleFacts -> local.update { it.copy(expanded = !it.expanded) }
            CallsAction.Audio -> if (request?.isActive != true) {
                val expected = ticket
                val generation = session.status.value.generation
                request = viewModelScope.launch {
                    val accountId = owner
                    if (accountId != null && account(expected, generation, accountId) == null) return@launch
                    val result = calls.audioAvailability()
                    if (accountId != null && account(expected, generation, accountId) == null) return@launch
                    current(expected, generation)
                    local.update {
                        it.copy(
                            unavailable = uiText(R.string.calls_audio_unavailable),
                            problem = (result as? ClientResult.Failed)?.problem?.message()
                        )
                    }
                }
            }
            is CallsAction.End -> signal(action)
        }
    }
    private fun stop() {
        request?.cancel()
        watch?.cancel()
        watch = null
        owner = null
        cache = emptyList()
        ticket++
    }
    private fun event(event: CallsEvent) {
        stop()
        local.value = CallsState()
        viewModelScope.launch { channel.send(event) }
    }
    private suspend fun account(expected: Long, generation: Long, required: String? = null): AccountSummary? {
        val result = accounts.list()
        current(expected, generation)
        when (result) {
            is ClientResult.Failed -> {
                conceal(result.problem.message())
                return null
            }
            is ClientResult.Unavailable -> {
                conceal(uiText(R.string.calls_unavailable))
                return null
            }
            is ClientResult.Ready -> {
                val selected = result.value.accounts.find { it.id == result.value.activeId && it.unlocked }
                if (selected == null) {
                    conceal(required = true)
                    return null
                }
                if (required != null && selected.id != required) {
                    conceal(uiText(R.string.calls_account_changed))
                    return null
                }
                return selected
            }
        }
    }
    private fun conceal(problem: UiText? = null, required: Boolean = false) {
        owner = null
        cache = emptyList()
        watch?.cancel()
        watch = null
        local.value = CallsState(problem = problem, accountRequired = required)
    }
    private fun observe(account: String) {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            calls.updates(account).collect { update ->
                if (!active || owner != account) return@collect
                revision++
                local.update { it.copy(fresh = false) }
                when (update) {
                    is ClientResult.Ready -> if (request?.isActive == true) needsRefresh = true else read()
                    is ClientResult.Failed -> local.update { it.copy(problem = update.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.calls_unavailable)) }
                }
            }
        }
    }
    private fun read() {
        if (!active || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val observed = revision
        local.update { it.copy(loading = true, loaded = false, fresh = false, problem = null) }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation, owner) ?: return@launch
                owner = selected.id
                observe(selected.id)
                val result = calls.list(selected.id)
                if (account(expected, generation, selected.id) == null) return@launch
                if (observed != revision) {
                    needsRefresh = true
                    return@launch
                }
                when (result) {
                    is ClientResult.Ready -> {
                        if (result.value.size > 1000 || result.value.map { it.id }.distinct().size != result.value.size) {
                            local.update { it.copy(problem = uiText(R.string.calls_invalid)) }
                            return@launch
                        }
                        cache = result.value
                        val rows = cache.map { it.item() }.toPersistentList()
                        local.update {
                            it.copy(
                                account = selected.label ?: selected.id, rows = rows, loaded = true, fresh = true,
                                selected = rows.find { row -> row.id == it.selected?.id }, pending = calls.pending(selected.id)?.operationId
                            )
                        }
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.calls_unavailable)) }
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
    private fun signal(action: CallsAction.End) {
        if (request?.isActive == true || !local.value.fresh || local.value.pending != null) return
        val row = cache.find { it.id == action.id && it.sequence == action.sequence } ?: return
        if (!row.canHangup || (action.decline && !row.canDecline)) return
        val owner = owner ?: return
        val attempt = CallSignal(UUID.randomUUID().toString(), row, action.decline)
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(busy = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (account(expected, generation, owner) == null) return@launch
                val result = calls.signal(owner, attempt)
                current(expected, generation)
                if (account(expected, generation, owner) == null) return@launch
                local.update { it.copy(pending = calls.pending(owner)?.operationId) }
                when (result) {
                    is ClientResult.Ready -> {
                        cache = cache.map { if (it.id == result.value.id) result.value else it }
                        val rows = cache.map { it.item() }.toPersistentList()
                        local.update { it.copy(rows = rows, selected = rows.find { row -> row.id == it.selected?.id }, fresh = true) }
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message(), fresh = false) }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.calls_unavailable), fresh = false) }
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
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!active || expected != ticket || generation != session.status.value.generation ||
            session.status.value.phase != ClientPhase.OPEN
        ) {
            throw CancellationException(
                "retired calls"
            )
        }
    }
}
private fun ClientProblem.message() = uiText(
    if (outcome == ClientOutcome.UNKNOWN) R.string.calls_unknown_outcome else R.string.calls_error_code,
    UiText.Number(code.toLong())
)
private fun CallRecord.item() = CallItem(
    id, peer,
    uiText(
        when (direction) {
            CallDirection.INCOMING -> R.string.calls_incoming
            CallDirection.OUTGOING -> R.string.calls_outgoing
            else -> R.string.calls_unknown
        }
    ),
    uiText(
        when (phase) {
            CallPhase.INCOMING_RINGING -> R.string.calls_incoming_ringing
            CallPhase.OUTGOING_RINGING -> R.string.calls_outgoing_ringing
            CallPhase.CONNECTING -> R.string.calls_connecting
            CallPhase.ACTIVE -> R.string.calls_active
            CallPhase.ENDED -> R.string.calls_ended
            CallPhase.UNKNOWN -> R.string.calls_unknown
        }
    ),
    UiText.DateTime(created), sequence, UiText.Unsigned(mediaGeneration),
    answered?.let {
        UiText.DateTime(it)
    },
    ended?.let { UiText.DateTime(it) },
    (failure ?: peerReason ?: endReason)?.reason(), canDecline, canHangup,
    direction == CallDirection.INCOMING && phase == CallPhase.INCOMING_RINGING,
    phase in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE)
)
private fun String.reason() = uiText(
    when (this) {
        "authority_unavailable" -> R.string.calls_authority_unavailable
        "authority_refused" -> R.string.calls_authority_refused
        "relay_unavailable" -> R.string.calls_relay_unavailable
        "media_lease_expired" -> R.string.calls_lease_expired
        "daemon_restarted" -> R.string.calls_restarted
        "signaling_unavailable" -> R.string.calls_signaling_unavailable
        "missed" -> R.string.calls_missed
        "declined", "rejected" -> R.string.calls_declined
        "hangup", "ended" -> R.string.calls_ended
        else -> R.string.calls_unknown_reason
    }
)
