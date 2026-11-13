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
import org.ramflux.designsystem.ThemeCatalog
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.app_shell.impl.R

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
                ClientPhase.CLOSED -> uiText(R.string.ui_private_data_is_closed_494e48)
                ClientPhase.OPENING -> uiText(R.string.ui_opening_private_data)
                ClientPhase.OPEN -> uiText(R.string.ui_client_ready)
                ClientPhase.CLOSING -> uiText(R.string.ui_closing_private_data)
                ClientPhase.FAILED -> uiText(R.string.ui_unable_to_open_private_data)
            },
            ready = status.phase == ClientPhase.OPEN,
            loading = status.phase in setOf(ClientPhase.OPENING, ClientPhase.CLOSING),
            canRetry = status.phase in setOf(ClientPhase.FAILED, ClientPhase.CLOSED),
            error = status.problem?.notice() ?: appearance.problem?.notice() ?: if (appearance.themeId != null &&
                ThemeCatalog.find(appearance.themeId) == null
            ) {
                ShellNotice(
                    uiText(R.string.ui_not_available_yet),
                    uiText(R.string.ui_selected_theme_is_unavailable_choose_another_theme), ShellNoticeKind.UNAVAILABLE
                )
            } else {
                null
            },
            notice = local.notice,
            appearance = appearance.value,
            namedTheme = appearance.themeId,
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
            is AppShellAction.ChangeTheme -> if (appearance?.isActive != true && ThemeCatalog.find(action.id) != null) {
                appearance = viewModelScope.launch {
                    local.update { it.copy(saving = true, notice = null) }
                    when (val result = preferences.setNamedTheme(action.id)) {
                        is ClientResult.Failed -> local.update { it.copy(notice = result.problem.notice()) }
                        is ClientResult.Unavailable -> local.update { it.copy(notice = result.reason.notice()) }
                        is ClientResult.Ready -> Unit
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
    uiText(R.string.ui_unable_to_continue),
    when (kind) {
        ClientFailureKind.INCOMPATIBLE -> uiText(R.string.ui_this_app_and_its_account_library_are_not)
        ClientFailureKind.UNAVAILABLE -> uiText(R.string.ui_this_action_is_not_available_on_this_device)
        ClientFailureKind.AUTHORIZATION -> uiText(R.string.ui_this_account_cannot_perform_that_action)
        ClientFailureKind.STORAGE -> uiText(R.string.ui_the_setting_or_private_data_could_not_be)
        ClientFailureKind.SHUTDOWN_PENDING -> uiText(R.string.ui_private_data_is_still_in_use_try_again)
        ClientFailureKind.FAILED -> if (outcome == ClientOutcome.UNKNOWN) {
            uiText(R.string.ui_the_outcome_is_unknown_check_the_current_state)
        } else {
            uiText(R.string.ui_the_request_did_not_complete_code_value, UiText.Number(code.toLong()))
        }
    }
)

private fun UnavailableReason.notice(): ShellNotice = ShellNotice(
    uiText(R.string.ui_not_available_yet),
    when (this) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_this_feature_is_not_included_in_this_build)
        UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_this_action_is_not_available_in_this_version)
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_account_access_is_not_available_on_this)
    },
    ShellNoticeKind.UNAVAILABLE
)
