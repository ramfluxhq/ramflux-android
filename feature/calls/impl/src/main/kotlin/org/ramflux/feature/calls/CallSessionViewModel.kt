// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.CallDirection
import org.ramflux.data.CallDirectoryRepository
import org.ramflux.data.CallPhase
import org.ramflux.data.CallRecord
import org.ramflux.data.CallSignal
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.failureText
import org.ramflux.designsystem.failureWords
import org.ramflux.designsystem.uiText
import org.ramflux.feature.calls.impl.R

/** Boards MOB-K01 to K05, K08 and K09. */
enum class CallStage { PERMISSION, STARTING, OUTGOING, INCOMING, CONNECTING, IN_CALL, CONNECTION_LOST, OFFLINE, FAILED, ENDED }

@Immutable data class CallSessionState(
    val stage: CallStage = CallStage.STARTING,
    /** Peer-controlled principal label from the call record. */
    val peer: String = "",
    val incoming: Boolean = false,
    /** The engine refused: the call is signalled, but no audio or video can flow on this phone. */
    val mediaUnavailable: Boolean = false,
    val busy: Boolean = false,
    val problem: UiText? = null
) {
    override fun toString() = "CallSessionState(<redacted>)"
}

sealed interface CallSessionAction {
    data class Load(
        val accountId: String,
        val conversationId: String?,
        val callId: String?,
        val microphoneGranted: Boolean
    ) : CallSessionAction
    data object Answer : CallSessionAction
    data object Decline : CallSessionAction
    data object HangUp : CallSessionAction
    data object Retry : CallSessionAction
    data object AskPermission : CallSessionAction
    data object Close : CallSessionAction
}

sealed interface CallSessionEvent {
    data object Permission : CallSessionEvent
    data object Close : CallSessionEvent
}

/**
 * One call through the real signalling methods (`call.invite`, `call.answer`, `call.reject`,
 * `call.hangup`, read back with `call.list`). State comes only from the SDK record; there is no
 * connected state without media: an active call with the refusing engine says audio and video are
 * unavailable.
 */
@HiltViewModel
class CallSessionViewModel @Inject constructor(
    private val calls: CallDirectoryRepository,
    private val session: ClientSessionRepository,
    private val engine: CallMediaEngine
) : ViewModel() {
    private var account: String? = null
    private var conversation: String? = null
    private var callId: String? = null
    private var record: CallRecord? = null
    private var microphone = false
    private var watch: Job? = null
    private val local = MutableStateFlow(CallSessionState())
    private val channel = Channel<CallSessionEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { screen, status ->
        // A closed session ends the signalling path; the call is not shown as connected.
        if (
            status.phase == ClientPhase.OPEN || screen.stage == CallStage.ENDED
        ) {
            screen
        } else {
            screen.copy(stage = CallStage.CONNECTION_LOST, busy = false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), CallSessionState())

    fun onAction(action: CallSessionAction) {
        when (action) {
            is CallSessionAction.Load -> {
                val changed = account != action.accountId || conversation != action.conversationId || callId != action.callId
                microphone = action.microphoneGranted
                if (changed) {
                    account = action.accountId
                    conversation = action.conversationId
                    callId = action.callId
                    start()
                } else if (local.value.stage == CallStage.PERMISSION && microphone) {
                    start()
                }
            }
            CallSessionAction.Retry -> if (
                local.value.stage in setOf(CallStage.FAILED, CallStage.OFFLINE, CallStage.CONNECTION_LOST)
            ) {
                start()
            }
            CallSessionAction.AskPermission -> viewModelScope.launch { channel.send(CallSessionEvent.Permission) }
            CallSessionAction.Answer -> answer()
            CallSessionAction.Decline -> end(decline = true)
            CallSessionAction.HangUp -> end(decline = false)
            CallSessionAction.Close -> {
                engine.stop()
                viewModelScope.launch { channel.send(CallSessionEvent.Close) }
            }
        }
    }

    private fun start() {
        val id = account ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val existing = callId
        if (existing == null && !microphone) {
            // The microphone is asked through the permissions feature, which explains it first.
            local.value = CallSessionState(stage = CallStage.PERMISSION)
            return
        }
        local.value = CallSessionState(stage = CallStage.STARTING, busy = true)
        viewModelScope.launch {
            if (existing != null) {
                refresh(id)
            } else {
                val target = conversation ?: return@launch
                when (val result = calls.invite(id, target, UUID.randomUUID().toString())) {
                    is ClientResult.Ready -> apply(id, result.value)
                    is ClientResult.Failed -> local.value = CallSessionState(
                        stage = if (result.problem.kind == ClientFailureKind.UNAVAILABLE) CallStage.OFFLINE else CallStage.FAILED,
                        problem = failure(result.problem.code, result.problem.statusName, result.problem.check)
                    )
                    is ClientResult.Unavailable -> local.value = CallSessionState(
                        stage = CallStage.FAILED,
                        problem = uiText(R.string.call_unavailable)
                    )
                }
            }
            watch(id)
        }
    }

    private fun watch(id: String) {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            calls.updates(id).collect { if (record?.phase != CallPhase.ENDED) refresh(id) }
        }
    }

    private suspend fun refresh(id: String) {
        val wanted = callId ?: record?.id ?: return
        when (val result = calls.list(id)) {
            is ClientResult.Ready -> result.value.firstOrNull { it.id == wanted }?.let { apply(id, it) }
                ?: if (record == null) local.update { it.copy(stage = CallStage.ENDED, busy = false) } else Unit
            is ClientResult.Failed -> if (record == null) {
                local.value = CallSessionState(
                    stage = CallStage.FAILED,
                    problem = failure(result.problem.code, result.problem.statusName, result.problem.check)
                )
            }
            is ClientResult.Unavailable -> local.value = CallSessionState(
                stage = CallStage.FAILED,
                problem = uiText(R.string.call_unavailable)
            )
        }
    }

    private suspend fun apply(id: String, next: CallRecord) {
        val previous = record?.phase
        record = next
        callId = next.id
        val stage = when (next.phase) {
            CallPhase.OUTGOING_RINGING -> CallStage.OUTGOING
            CallPhase.INCOMING_RINGING -> CallStage.INCOMING
            CallPhase.CONNECTING -> CallStage.CONNECTING
            CallPhase.ACTIVE -> CallStage.IN_CALL
            CallPhase.ENDED -> CallStage.ENDED
            CallPhase.UNKNOWN -> CallStage.FAILED
        }
        var unavailable = local.value.mediaUnavailable
        val joining = stage == CallStage.CONNECTING || stage == CallStage.IN_CALL
        if (joining && previous != CallPhase.CONNECTING && previous != CallPhase.ACTIVE) {
            unavailable = engine.start(id, next, video = false) is CallMediaResult.Refused
        }
        if (stage == CallStage.ENDED) engine.stop()
        local.value = CallSessionState(
            stage = stage,
            peer = next.peer,
            incoming = next.direction == CallDirection.INCOMING,
            mediaUnavailable = unavailable,
            problem = next.failure?.let { uiText(R.string.call_failed_reason, UiText.Data(it)) }
        )
    }

    private fun answer() {
        val id = account ?: return
        val current = record ?: return
        if (local.value.busy || current.phase != CallPhase.INCOMING_RINGING) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = calls.answer(id, current, UUID.randomUUID().toString())) {
                is ClientResult.Ready -> apply(id, result.value)
                is ClientResult.Failed -> local.update {
                    it.copy(
                        busy = false,
                        problem = failure(result.problem.code, result.problem.statusName, result.problem.check)
                    )
                }
                is ClientResult.Unavailable -> local.update { it.copy(busy = false, problem = uiText(R.string.call_unavailable)) }
            }
        }
    }

    private fun end(decline: Boolean) {
        val id = account ?: return
        val current = record ?: return
        if (local.value.busy || !current.canHangup || (decline && !current.canDecline)) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = calls.signal(id, CallSignal(UUID.randomUUID().toString(), current, decline))) {
                is ClientResult.Ready -> apply(id, result.value)
                is ClientResult.Failed -> local.update {
                    it.copy(
                        busy = false,
                        problem = failure(result.problem.code, result.problem.statusName, result.problem.check)
                    )
                }
                is ClientResult.Unavailable -> local.update { it.copy(busy = false, problem = uiText(R.string.call_unavailable)) }
            }
        }
    }

    override fun onCleared() {
        engine.stop()
    }
}

private fun failure(code: Int, status: String?, reason: String?): UiText = if (status.isNullOrEmpty()) {
    failureText(R.string.call_failed_code, code, null, reason)
} else {
    failureWords(reason) ?: uiText(R.string.call_failed_reason, UiText.Data(status))
}
