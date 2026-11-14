// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
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
import org.ramflux.feature.settings.impl.R

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val devices: DeviceDirectoryRepository,
    private val accountStatus: AccountStatusRepository,
    private val preferences: AppearanceRepository,
    private val session: ClientSessionRepository,
    private val language: LanguageRepository
) : ViewModel() {
    private val local = MutableStateFlow(SettingsState())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val saving = MutableStateFlow(false)
    val state = combine(
        local,
        preferences.appearance,
        session.status,
        saving,
        language.language
    ) { screen, appearance, status, savingTheme, locale ->
        val visible = if (status.phase == ClientPhase.OPEN) {
            screen
        } else {
            screen.copy(
                accountLabel = null,
                devices = persistentListOf(),
                devicesLoaded = false,
                status = null,
                accountRequired = false,
                loading = false,
                problem = uiText(R.string.settings_closed)
            )
        }
        visible.copy(
            selectedTheme = appearance.themeId ?: when (appearance.value) {
                Appearance.SYSTEM -> null
                Appearance.LIGHT -> "light"
                Appearance.DARK -> "dark"
            },
            savingTheme = appearance.loading || savingTheme,
            selectedLanguage = screen.languageDraft ?: locale.value,
            savedLanguage = locale.value,
            savingLanguage = locale.loading || screen.savingLanguage,
            problem = locale.problem?.message() ?: appearance.problem?.message()
                ?: if (appearance.themeId != null && ThemeCatalog.find(appearance.themeId) == null) {
                    uiText(R.string.settings_theme_missing)
                } else {
                    visible.problem
                }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), SettingsState())
    private var request: Job? = null
    private var preferenceJob: Job? = null
    private var capability: Job? = null
    private var languageJob: Job? = null
    private var ticket = 0L
    private var activeAccount: String? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            capability?.cancel()
            ticket++
            local.update {
                it.copy(
                    accountLabel = null, devices = persistentListOf(), devicesLoaded = false, status = null,
                    deviceProblem = null, statusProblem = null, accountRequired = false, loading = false, problem = null, unavailable = null
                )
            }
            if (phase == ClientPhase.OPEN) onAction(SettingsAction.Refresh)
        }
    }
    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.Language -> if (languageJob?.isActive != true) local.update { it.copy(languageDraft = action.language) }
            SettingsAction.ApplyLanguage -> if (languageJob?.isActive != true) {
                val choice = local.value.languageDraft ?: language.language.value.value
                languageJob = viewModelScope.launch {
                    local.update { it.copy(savingLanguage = true, problem = null) }
                    try {
                        when (val result = language.setLanguage(choice)) {
                            is ClientResult.Ready -> local.update { it.copy(languageDraft = null) }
                            is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                            is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                        }
                    } finally {
                        local.update { it.copy(savingLanguage = false) }
                    }
                }
            }
            SettingsAction.Refresh -> if (request?.isActive != true) read()
            SettingsAction.Back -> {
                request?.cancel()
                capability?.cancel()
                ticket++
                if (local.value.page == SettingsPage.GENERAL) {
                    event(SettingsEvent.Back)
                } else {
                    local.update { it.copy(page = SettingsPage.GENERAL, loading = false, problem = null, unavailable = null) }
                }
            }
            SettingsAction.OpenAccount -> event(SettingsEvent.OpenAccount)
            SettingsAction.Permissions -> event(SettingsEvent.Permissions)
            SettingsAction.Connections -> event(SettingsEvent.Connections)
            is SettingsAction.Page -> {
                capability?.cancel()
                local.update { it.copy(page = action.page, problem = null, unavailable = null) }
                if (action.page == SettingsPage.BACKUP) capability { accountStatus.backupAvailability() }
            }
            is SettingsAction.Theme -> if (preferenceJob?.isActive != true && (action.id == null || ThemeCatalog.find(action.id) != null)) {
                capability?.cancel()
                preferenceJob = viewModelScope.launch {
                    saving.value = true
                    local.update { it.copy(problem = null, unavailable = null) }
                    try {
                        val result = if (action.id == null) {
                            preferences.setAppearance(Appearance.SYSTEM)
                        } else {
                            preferences.setNamedTheme(action.id)
                        }
                        when (result) {
                            is ClientResult.Ready -> Unit
                            is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                            is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
                        }
                    } finally {
                        saving.value = false
                        local.update { it.copy(savingTheme = false) }
                    }
                }
            }
            is SettingsAction.Revoke -> if (local.value.devices.any { it.id == action.id && !it.local }) {
                val account = activeAccount ?: return
                capability { devices.revoke(account, action.id) }
            }
        }
    }
    private fun event(event: SettingsEvent) {
        request?.cancel()
        capability?.cancel()
        ticket++
        clear()
        local.update { it.copy(loading = false) }
        viewModelScope.launch { channel.send(event) }
    }
    private fun capability(call: suspend () -> ClientResult<Unit>) {
        capability?.cancel()
        val expected = ticket
        val generation = session.status.value.generation
        capability = viewModelScope.launch {
            current(expected, generation)
            val result = call()
            current(expected, generation)
            when (result) {
                is ClientResult.Ready -> Unit
                is ClientResult.Failed -> local.update { it.copy(problem = result.problem.message()) }
                is ClientResult.Unavailable -> local.update { it.copy(unavailable = result.reason.message()) }
            }
        }
    }
    private suspend fun account(expected: Long, generation: Long, required: String? = null): AccountSummary? {
        val result = accounts.list()
        current(expected, generation)
        when (result) {
            is ClientResult.Failed -> {
                clear(problem = result.problem.message())
                return null
            }
            is ClientResult.Unavailable -> {
                clear(unavailable = result.reason.message())
                return null
            }
            is ClientResult.Ready -> {
                val selected = result.value.accounts.find { it.id == result.value.activeId && it.unlocked }
                if (selected == null) {
                    clear(required = true)
                    return null
                }
                if (required != null && selected.id != required) {
                    clear(problem = uiText(R.string.settings_account_changed))
                    return null
                }
                return selected
            }
        }
    }
    private fun clear(problem: UiText? = null, unavailable: UiText? = null, required: Boolean = false) {
        activeAccount = null
        local.update {
            it.copy(
                accountLabel = null, devices = persistentListOf(), devicesLoaded = false, status = null,
                statusProblem = null, deviceProblem = null, problem = problem, unavailable = unavailable, accountRequired = required
            )
        }
    }
    private fun read() {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        clear()
        local.update { it.copy(loading = true) }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation) ?: return@launch
                val status = accountStatus.read(selected.id)
                current(expected, generation)
                val rows = devices.list(selected.id)
                current(expected, generation)
                val confirmed = account(expected, generation, selected.id) ?: return@launch
                activeAccount = confirmed.id
                local.update {
                    it.copy(
                        accountLabel = confirmed.label ?: confirmed.id,
                        devices = when (rows) {
                            is ClientResult.Ready -> rows.value.map { row -> SettingsDevice(row.id, row.local) }.toPersistentList()
                            else -> persistentListOf()
                        },
                        devicesLoaded = rows is ClientResult.Ready,
                        deviceProblem = when (rows) {
                            is ClientResult.Ready -> null
                            is ClientResult.Failed -> rows.problem.message()
                            is ClientResult.Unavailable -> rows.reason.message()
                        },
                        status = (status as? ClientResult.Ready)?.value?.presentation(),
                        statusProblem = when (status) {
                            is ClientResult.Ready -> null
                            is ClientResult.Failed -> status.problem.message()
                            is ClientResult.Unavailable -> status.reason.message()
                        }
                    )
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != expected || generation != session.status.value.generation || session.status.value.phase != ClientPhase.OPEN) {
            throw CancellationException("retired settings")
        }
    }
}
private fun ClientProblem.message(): UiText = if (outcome == ClientOutcome.UNKNOWN) {
    uiText(R.string.settings_unknown_outcome)
} else {
    uiText(R.string.settings_read_failed, UiText.Number(code.toLong()))
}
private fun UnavailableReason.message(): UiText = uiText(
    when (this) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> R.string.settings_action_missing
        UnavailableReason.UNSUPPORTED_BY_CORE -> R.string.settings_sdk_missing
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> R.string.settings_custody_missing
    }
)
private fun AccountStatus.presentation() = SettingsStatus(
    uiText(
        when (sessions) {
            SecureSessions.READY -> R.string.settings_session_ready
            SecureSessions.REESTABLISHING -> R.string.settings_session_reestablishing
            SecureSessions.UNKNOWN -> R.string.settings_status_unknown
        }
    ),
    uiText(
        when (sync) {
            SyncPhase.RUNNING -> R.string.settings_sync_running
            SyncPhase.IDLE -> R.string.settings_sync_idle
            SyncPhase.ERROR -> R.string.settings_sync_error
            SyncPhase.NOT_RECORDED -> R.string.settings_not_recorded
            SyncPhase.UNKNOWN -> R.string.settings_status_unknown
        }
    ),
    uiText(
        when (node) {
            NodeHealth.CONNECTED -> R.string.settings_node_connected
            NodeHealth.UNAVAILABLE -> R.string.settings_node_unavailable
            NodeHealth.NOT_RECORDED -> R.string.settings_not_recorded
            NodeHealth.UNKNOWN -> R.string.settings_status_unknown
        }
    ),
    lastRun?.let { UiText.DateTime(it) } ?: uiText(R.string.settings_not_recorded)
)
