// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.ramflux.data.*

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val session: ClientSessionRepository,
    private val preferences: AppearanceRepository
) : ViewModel() {
    private data class Local(val notice: ShellNotice? = null, val saving: Boolean = false)
    private val local = MutableStateFlow(Local())
    private val channel = Channel<AppShellEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state: StateFlow<AppShellState> = combine(session.status, preferences.appearance, local) { status, appearance, local ->
        AppShellState(
            status = when (status.phase) {
                ClientPhase.CLOSED -> "Private data is closed"
                ClientPhase.OPENING -> "Opening private data…"
                ClientPhase.OPEN -> "Client ready"
                ClientPhase.CLOSING -> "Closing private data…"
                ClientPhase.FAILED -> "Unable to open private data"
            },
            ready = status.phase == ClientPhase.OPEN,
            loading = status.phase in setOf(ClientPhase.OPENING, ClientPhase.CLOSING),
            canRetry = status.phase in setOf(ClientPhase.FAILED, ClientPhase.CLOSED),
            error = status.problem?.notice() ?: appearance.problem?.notice(),
            notice = local.notice,
            appearance = appearance.value,
            savingAppearance = local.saving || appearance.loading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), AppShellState())
    private var navigation: Job? = null
    private var reconnect: Job? = null
    private var appearance: Job? = null
    private var generation = 0L
    fun onAction(action: AppShellAction) {
        when (action) {
            AppShellAction.DismissNotice -> local.update { it.copy(notice = null) }
            AppShellAction.Retry -> if (reconnect?.isActive != true) reconnect = viewModelScope.launch { session.foreground() }
            is AppShellAction.ChangeAppearance -> if (appearance?.isActive != true) {
                appearance = viewModelScope.launch {
                    local.update { it.copy(saving = true, notice = null) }
                    when (val result = preferences.setAppearance(action.value)) {
                        is ClientResult.Failed -> local.update { it.copy(notice = result.problem.notice()) }
                        else -> Unit
                    }
                    local.update { it.copy(saving = false) }
                }
            }
            is AppShellAction.Open -> {
                val expected = ++generation
                navigation?.cancel()
                navigation = viewModelScope.launch {
                    val result = session.availability(action.feature)
                    currentCoroutineContext().ensureActive()
                    if (expected != generation) return@launch
                    when (result) {
                        is ClientResult.Ready -> channel.send(AppShellEvent.Navigate(action.feature.id))
                        is ClientResult.Failed -> local.update { it.copy(notice = result.problem.notice()) }
                        is ClientResult.Unavailable -> local.update { it.copy(notice = result.reason.notice()) }
                    }
                }
            }
        }
    }
}

private fun ClientProblem.notice(): ShellNotice = ShellNotice(
    "Unable to continue",
    when (kind) {
        ClientFailureKind.INCOMPATIBLE -> "This app and its account library are not compatible. Update the app to continue."
        ClientFailureKind.UNAVAILABLE -> "This action is not available on this device yet."
        ClientFailureKind.AUTHORIZATION -> "This account cannot perform that action."
        ClientFailureKind.STORAGE -> "The setting or private data could not be saved."
        ClientFailureKind.SHUTDOWN_PENDING -> "Private data is still in use. Try again shortly."
        ClientFailureKind.FAILED -> if (outcome == ClientOutcome.UNKNOWN) {
            "The outcome is unknown. Check the current state before trying again."
        } else {
            "The request did not complete (code $code)."
        }
    }
)

private fun UnavailableReason.notice(): ShellNotice = ShellNotice(
    "Not available yet",
    when (this) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> "This feature is not included in this build yet."
        UnavailableReason.UNSUPPORTED_BY_CORE -> "This action is not available in this version."
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> "Secure account access is not available on this device yet."
    },
    ShellNoticeKind.UNAVAILABLE
)
