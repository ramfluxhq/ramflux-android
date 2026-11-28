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
    private val language: LanguageRepository,
    private val security: AccountSecurityRepository
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
            languages = language.languages.toPersistentList(),
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
    private var reads = 0L
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
            SettingsAction.Refresh -> if (request?.isActive != true) {
                read()
                if (local.value.page == SettingsPage.APP_LOCK) readAppLock()
            }
            SettingsAction.Back -> {
                request?.cancel()
                capability?.cancel()
                ticket++
                val page = local.value.page
                if (page == SettingsPage.GENERAL) {
                    endPairing()
                    event(SettingsEvent.Back)
                } else {
                    if (page == SettingsPage.PAIRING) endPairing()
                    if (page == SettingsPage.ACCOUNT) closeCodeChange()
                    if (page == SettingsPage.USERNAME && usernameJob?.isActive != true) {
                        local.update {
                            it.copy(
                                usernameClaim = SettingsUsernameClaim()
                            )
                        }
                    }
                    local.update { it.copy(page = parent(page), loading = false, problem = null, unavailable = null) }
                }
            }
            SettingsAction.OpenAccount -> event(SettingsEvent.OpenAccount)
            SettingsAction.Permissions -> event(SettingsEvent.Permissions)
            SettingsAction.Delivery -> event(SettingsEvent.Delivery)
            SettingsAction.MyCard -> event(SettingsEvent.MyCard)
            SettingsAction.Connections -> event(SettingsEvent.Connections)
            SettingsAction.Backup -> event(SettingsEvent.Backup)
            SettingsAction.Storage -> activeAccount?.let { event(SettingsEvent.Storage(it)) }
            SettingsAction.OpenUsernameClaim -> if (usernameJob?.isActive != true && activeAccount != null) {
                local.update { it.copy(usernameClaim = SettingsUsernameClaim(step = SettingsUsernameStep.NAME)) }
            }
            is SettingsAction.UsernameName -> local.update {
                it.copy(usernameClaim = it.usernameClaim.copy(name = action.text.take(MAX_USERNAME_NAME), problem = null))
            }
            SettingsAction.UsernameNext -> local.update {
                val form = it.usernameClaim
                val name = form.name.trim().let { typed -> if (typed.startsWith(USERNAME_SIGIL)) typed.drop(1) else typed }
                if (form.working || form.step != SettingsUsernameStep.NAME) {
                    it
                } else if (name.isEmpty()) {
                    it.copy(usernameClaim = form.copy(problem = uiText(R.string.settings_username_empty)))
                } else {
                    it.copy(usernameClaim = form.copy(name = name, step = SettingsUsernameStep.PHRASE, problem = null))
                }
            }
            SettingsAction.CancelUsername -> if (usernameJob?.isActive != true) {
                local.update { it.copy(usernameClaim = SettingsUsernameClaim()) }
            }
            is SettingsAction.ClaimUsername -> submitUsername(action.phrase)
            is SettingsAction.Page -> {
                capability?.cancel()
                local.update { it.copy(page = action.page, problem = null, unavailable = null) }
                if (action.page == SettingsPage.PRIVACY) readPolicy()
                if (action.page == SettingsPage.APP_LOCK) readAppLock()
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
                local.update { it.copy(confirmRevoke = action.id) }
            }
            SettingsAction.CancelRevoke -> local.update { it.copy(confirmRevoke = null) }
            SettingsAction.ConfirmRevoke -> {
                val device = local.value.confirmRevoke ?: return
                local.update { it.copy(confirmRevoke = null) }
                if (local.value.devices.none { it.id == device && !it.local }) return
                val account = activeAccount ?: return
                capability {
                    devices.revoke(account, device).also { if (it is ClientResult.Ready) read() }
                }
            }
            SettingsAction.Lock -> {
                val account = activeAccount
                clientDebugEvent("settings.lock.tap.account:" + (account != null) + ".loading:" + local.value.loading + ".ticket:" + ticket)
                if (account == null) return
                capability {
                    clientDebugEvent("settings.lock.call")
                    accounts.lock(account).also {
                        clientDebugEvent("settings.lock.result:" + it.javaClass.simpleName + ".ticket:" + ticket)
                        if (it is ClientResult.Ready) event(SettingsEvent.OpenAccount)
                    }
                }
            }
            is SettingsAction.AccountExit -> if (activeAccount != null) local.update { it.copy(confirmExit = action.exit) }
            SettingsAction.CancelAccountExit -> local.update { it.copy(confirmExit = null) }
            SettingsAction.ConfirmAccountExit -> {
                val exit = local.value.confirmExit ?: return
                local.update { it.copy(confirmExit = null) }
                val account = activeAccount ?: return
                capability {
                    when (exit) {
                        SettingsAccountExit.SIGN_OUT -> accounts.signOut(account)
                        SettingsAccountExit.REMOVE -> accounts.removeLocal(account)
                    }.also { if (it is ClientResult.Ready) event(SettingsEvent.OpenAccount) }
                }
            }
            SettingsAction.StartPairing -> startPairing()
            is SettingsAction.InvitationAsText -> pairing { it.copy(invitationAsText = action.text) }
            is SettingsAction.ComparePairing -> pairingStep(
                PairingStep.COMPARE
            ) { account, state -> security.confirmPairing(account, state, action.match) }
            SettingsAction.ApprovePairing -> if (local.value.pairing.step == PairingStep.REVIEW) pairing { it.copy(confirmApprove = true) }
            SettingsAction.ConfirmApprovePairing -> if (local.value.pairing.confirmApprove) {
                pairingStep(PairingStep.REVIEW) { account, state -> security.approvePairing(account, state, true) }
            }
            SettingsAction.DeclinePairing -> pairingStep(
                PairingStep.REVIEW
            ) { account, state -> security.approvePairing(account, state, false) }
            SettingsAction.CancelPairing -> cancelPairing()
            is SettingsAction.RequestPolicy -> writePolicy(action.policy)
            is SettingsAction.SetAppLock -> writeAppLock(action.enabled, local.value.appLock.timeoutMs)
            is SettingsAction.AppLockTimeout -> writeAppLock(local.value.appLock.enabled, action.timeoutMs)
            SettingsAction.OpenCodeChange -> if (activeAccount != null) {
                closeCodeChange()
                local.update { it.copy(codeChange = SettingsCodeChange(open = true)) }
            }
            is SettingsAction.CodeDigit -> codeDigit(action.next, action.digit)
            is SettingsAction.RepeatDigit -> repeatDigit(action.digit)
            SettingsAction.SubmitCodeChange -> submitCodeChange()
            SettingsAction.CloseCodeChange -> closeCodeChange()
            is SettingsAction.ReauthDigit -> reauthDigit(action.digit)
            SettingsAction.SubmitReauth -> submitReauth()
            SettingsAction.CancelReauth -> AttendedAccountCode.refuse()
            SettingsAction.RecoverAccount -> activeAccount?.let { event(SettingsEvent.Recover(it)) }
        }
    }

    private fun parent(page: SettingsPage) = when (page) {
        SettingsPage.PRIVACY -> SettingsPage.ACCOUNT
        SettingsPage.APP_LOCK -> SettingsPage.PRIVACY
        SettingsPage.USERNAME -> SettingsPage.PRIVACY
        SettingsPage.PAIRING -> SettingsPage.DEVICES
        SettingsPage.LANGUAGE -> SettingsPage.APPEARANCE
        else -> SettingsPage.GENERAL
    }

    // Linking a new device. The invitation token lives only in [invitation], erased when the ceremony leaves INVITED.
    private var invitation: CharArray? = null
    private var invitationQr: InvitationQr? = null
    private var pairingState: PairingState? = null
    private var pairingJob: Job? = null
    private var pairingAccount: String? = null

    /** The begun operation (id, generation), known before the first status read. */
    private var pairingOperation: Pair<String, ULong>? = null

    /** Lends invitation chunk [index] to [block] while a frame is drawn; nothing is copied. */
    fun drawInvitation(index: Int, block: (CharArray) -> Unit) {
        val token = invitation ?: return
        val start = index * INVITATION_CHUNK
        if (index < 0 || start >= token.size) return
        val chunk = token.copyOfRange(start, minOf(token.size, start + INVITATION_CHUNK))
        try {
            block(chunk)
        } finally {
            chunk.fill('\u0000')
        }
    }

    /** Lends the invitation's QR modules to [block] while a frame is drawn; nothing is copied. */
    fun drawInvitationQr(block: ((Int, Int) -> Boolean) -> Unit) {
        invitationQr?.lend(block)
    }

    private fun pairing(update: (SettingsPairing) -> SettingsPairing) = local.update { it.copy(pairing = update(it.pairing)) }

    private fun failedPairing(problem: ClientProblem) =
        SettingsPairing(step = PairingStep.ENDED, ended = PairingEnd.FAILED, result = problem.statusName, problem = problem.message())

    private fun eraseInvitation() {
        invitation?.fill('\u0000')
        invitation = null
        invitationQr?.close()
        invitationQr = null
        pairing { it.copy(invitationChunks = 0, invitationModules = 0, invitationAsText = false) }
    }

    private fun startPairing() {
        val account = activeAccount ?: return
        if (pairingJob?.isActive == true || local.value.pairing.step !in setOf(PairingStep.IDLE, PairingStep.ENDED)) return
        eraseInvitation()
        pairingState = null
        pairingOperation = null
        pairingAccount = account
        local.update { it.copy(pairing = SettingsPairing(step = PairingStep.STARTING)) }
        pairingJob = viewModelScope.launch {
            when (val begun = security.beginPairing(account, DEVICE_NAME)) {
                is ClientResult.Ready -> begun.value.use { started ->
                    val chars = started.token.useOnce { bytes -> Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes)) }
                    val token = CharArray(chars.remaining()).also { chars.get(it) }
                    if (chars.hasArray()) chars.array().fill('\u0000')
                    invitation = token
                    invitationQr = InvitationQr.encode(token)
                    pairingOperation = started.operationId to started.generation
                    pairing {
                        it.copy(
                            step = PairingStep.INVITED,
                            expiresAt = started.expiresAt,
                            invitationChunks = (token.size + INVITATION_CHUNK - 1) / INVITATION_CHUNK,
                            invitationModules = invitationQr?.modules ?: 0
                        )
                    }
                    follow(account, started.operationId, started.generation)
                }
                is ClientResult.Failed -> pairing {
                    failedPairing(begun.problem)
                }
                is ClientResult.Unavailable -> pairing {
                    SettingsPairing(step = PairingStep.ENDED, ended = PairingEnd.FAILED, problem = begun.reason.message())
                }
            }
        }
    }

    /** Follows the SDK's pairing state until the ceremony needs this phone or ends. */
    private suspend fun follow(account: String, operationId: String, generation: ULong) {
        while (true) {
            when (val read = security.pairingStatus(account, operationId, generation)) {
                is ClientResult.Ready -> if (apply(read.value)) return
                is ClientResult.Failed -> {
                    pairing { it.copy(problem = read.problem.message()) }
                    if (read.problem.outcome == ClientOutcome.REFUSED) {
                        eraseInvitation()
                        pairing { it.copy(step = PairingStep.ENDED, ended = PairingEnd.FAILED, result = read.problem.statusName) }
                        return
                    }
                }
                is ClientResult.Unavailable -> {
                    eraseInvitation()
                    pairing { it.copy(step = PairingStep.ENDED, ended = PairingEnd.FAILED, problem = read.reason.message()) }
                    return
                }
            }
            delay(PAIRING_POLL_MILLIS)
        }
    }

    /** Shows [state]; true when following stops (this phone must act, or the ceremony ended). */
    private fun apply(state: PairingState): Boolean {
        pairingState = state
        if (state.phase != PairingPhase.INVITED) eraseInvitation()
        val step = when {
            state.phase.terminal -> PairingStep.ENDED
            state.phase == PairingPhase.SAS_PENDING && state.emojiIndices != null -> PairingStep.COMPARE
            state.phase == PairingPhase.PROPOSAL_PENDING && state.proposalHash != null -> PairingStep.REVIEW
            state.phase == PairingPhase.INVITED -> PairingStep.INVITED
            else -> PairingStep.WORKING
        }
        val ended = when (state.phase) {
            PairingPhase.COMPLETED -> PairingEnd.COMPLETED
            PairingPhase.CANCELLED -> PairingEnd.CANCELLED
            PairingPhase.EXPIRED -> PairingEnd.EXPIRED
            PairingPhase.REJECTED -> PairingEnd.REJECTED
            else -> null
        }
        pairing {
            it.copy(
                step = step, expiresAt = state.expiresAt, emoji = (state.emojiIndices ?: emptyList()).toPersistentList(),
                peerName = state.peerName, peerDevice = state.peerDevice, capabilities = state.capabilities.toPersistentList(),
                leaseExpiresAt = state.leaseExpiresAt, ended = ended, result = state.result, problem = null, confirmApprove = false
            )
        }
        if (ended == PairingEnd.COMPLETED) read()
        return step == PairingStep.ENDED || step == PairingStep.COMPARE || step == PairingStep.REVIEW
    }

    private fun pairingStep(expected: PairingStep, call: suspend (String, PairingState) -> ClientResult<PairingState>) {
        val account = pairingAccount ?: return
        val state = pairingState ?: return
        if (local.value.pairing.step != expected || pairingJob?.isActive == true) return
        pairing { it.copy(step = PairingStep.WORKING, confirmApprove = false, problem = null) }
        pairingJob = viewModelScope.launch {
            when (val result = call(account, state)) {
                is ClientResult.Ready -> if (!apply(result.value)) follow(account, state.operationId, state.generation)
                is ClientResult.Failed -> {
                    pairing { it.copy(step = expected, problem = result.problem.message()) }
                    if (result.problem.outcome == ClientOutcome.REFUSED) follow(account, state.operationId, state.generation)
                }
                is ClientResult.Unavailable -> pairing { it.copy(step = expected, problem = result.reason.message()) }
            }
        }
    }

    private fun cancelPairing() {
        val account = pairingAccount ?: return
        val step = local.value.pairing.step
        if (step == PairingStep.IDLE || step == PairingStep.ENDED) return
        pairingJob?.cancel()
        eraseInvitation()
        val (operation, generation) = pairingOperation ?: run {
            pairing { SettingsPairing(step = PairingStep.ENDED, ended = PairingEnd.CANCELLED) }
            return
        }
        pairing { it.copy(step = PairingStep.WORKING) }
        pairingJob = viewModelScope.launch {
            when (val result = security.cancelPairing(account, operation, generation)) {
                is ClientResult.Ready -> apply(result.value)
                is ClientResult.Failed -> pairing {
                    failedPairing(result.problem)
                }
                is ClientResult.Unavailable -> pairing {
                    it.copy(step = PairingStep.ENDED, ended = PairingEnd.FAILED, problem = result.reason.message())
                }
            }
        }
    }

    /** Leaving the pairing page: an unfinished ceremony is cancelled at the SDK, the invitation erased. */
    private fun endPairing() {
        val step = local.value.pairing.step
        if (step != PairingStep.IDLE && step != PairingStep.ENDED) {
            val account = pairingAccount
            val operation = pairingOperation
            pairingJob?.cancel()
            if (account != null && operation != null) {
                viewModelScope.launch {
                    security.cancelPairing(
                        account,
                        operation.first,
                        operation.second
                    )
                }
            }
        }
        eraseInvitation()
        pairingState = null
        pairingOperation = null
        local.update { it.copy(pairing = SettingsPairing()) }
    }

    private var policyJob: Job? = null
    private fun readPolicy() {
        val account = activeAccount ?: return
        policyJob?.cancel()
        local.update { it.copy(privacy = it.privacy.copy(loading = true, problem = null)) }
        policyJob = viewModelScope.launch { showPolicy(security.requestPolicy(account)) }
    }
    private fun writePolicy(policy: ContactRequestPolicy) {
        val account = activeAccount ?: return
        if (policyJob?.isActive == true || local.value.privacy.policy == policy) return
        local.update { it.copy(privacy = it.privacy.copy(loading = true, problem = null)) }
        policyJob = viewModelScope.launch { showPolicy(security.setRequestPolicy(account, policy)) }
    }
    private fun showPolicy(result: ClientResult<ContactRequestPolicy>) = local.update {
        it.copy(
            privacy = when (result) {
                is ClientResult.Ready -> SettingsPrivacy(result.value)
                is ClientResult.Failed -> it.privacy.copy(loading = false, problem = result.problem.message())
                is ClientResult.Unavailable -> it.privacy.copy(loading = false, problem = result.reason.message())
            }
        )
    }

    private var appLockJob: Job? = null
    private var waitJob: Job? = null

    // The account code typed into the weakening sheet: erasable digits, wiped when the sheet closes or answers.
    private val reauthCode = CharArray(AccountCode.DIGITS)
    private var reauthCount = 0

    init {
        viewModelScope.launch {
            AttendedAccountCode.waiting.collect { open ->
                if (!open) {
                    reauthCode.fill('\u0000')
                    reauthCount = 0
                }
                local.update { it.copy(appLock = it.appLock.copy(reauthOpen = open, reauthDigits = 0)) }
            }
        }
    }

    private fun reauthDigit(digit: Char?) {
        if (!AttendedAccountCode.waiting.value) return
        reauthCount = typed(reauthCode, reauthCount, digit)
        local.update { it.copy(appLock = it.appLock.copy(reauthDigits = reauthCount)) }
    }

    private fun submitReauth() {
        if (reauthCount != AccountCode.DIGITS) return
        val code = reauthCode.copyOf()
        reauthCode.fill('\u0000')
        reauthCount = 0
        local.update { it.copy(appLock = it.appLock.copy(reauthDigits = 0)) }
        // The shared request wipes what it is given when no request waits; the core wipes its copy after use.
        AttendedAccountCode.submit(code)
    }

    /** Reads the app lock of the active account. A refresh keeps what the page shows until the answer arrives. */
    private fun readAppLock() {
        val account = activeAccount ?: return
        appLockJob?.cancel()
        local.update { it.copy(appLock = it.appLock.copy(loading = true, problem = null)) }
        appLockJob = viewModelScope.launch { showAppLock(security.appLockState(account)) }
    }

    /**
     * Changes the lock. Every change goes through the attended path: core asks for the account code only when the change
     * weakens the lock, and the sheet answers that request. Afterwards the page shows the state core holds now.
     */
    private fun writeAppLock(enabled: Boolean, timeoutMs: Long) {
        val account = activeAccount ?: return
        if (appLockJob?.isActive == true) return
        local.update {
            it.copy(
                appLock = it.appLock.copy(
                    loading = true,
                    problem = null,
                    reauthTurnOff = !enabled,
                    wrongCode = false,
                    notChanged = false
                )
            )
        }
        appLockJob = viewModelScope.launch {
            when (val written = security.configureAppLock(account, enabled, timeoutMs) { AttendedAccountCode.ask() }) {
                is ClientResult.Ready -> {
                    // The session's appLockEnabled (banners, capture policy) is current at once.
                    session.recheckAppLock()
                    showAppLock(security.appLockState(account))
                }
                is ClientResult.Failed -> refusedAppLock(account, written.problem)
                is ClientResult.Unavailable -> local.update {
                    it.copy(appLock = it.appLock.copy(loading = false, problem = written.reason.message()))
                }
            }
        }
    }

    /** A refused change: the stored state is read again and shown; only a typed refusal adds its line. */
    private suspend fun refusedAppLock(account: String, problem: ClientProblem) {
        showAppLock(security.appLockState(account))
        val line: (SettingsAppLock) -> SettingsAppLock = when (problem.accountCodeRefusal()) {
            AccountCodeRefusal.WRONG_CODE -> { lock -> lock.copy(wrongCode = true) }
            // Cancelled or closed: nothing is said. Timed out: the lock was not changed, and the page says so.
            AccountCodeRefusal.REAUTH_REQUIRED -> { lock -> lock.copy(notChanged = AttendedAccountCode.timedOut) }
            AccountCodeRefusal.RETRY_LATER, AccountCodeRefusal.ATTEMPTS_EXHAUSTED -> { lock -> lock }
            AccountCodeRefusal.APP_LOCK_CHANGE_BUSY -> { lock -> lock.copy(problem = uiText(R.string.settings_app_lock_busy)) }
            else -> { lock -> lock.copy(problem = problem.message()) }
        }
        local.update { it.copy(appLock = line(it.appLock)) }
    }

    private fun showAppLock(result: ClientResult<AppLockStatus>) {
        when (result) {
            is ClientResult.Ready -> {
                val status = result.value
                local.update {
                    it.copy(
                        // The sheet's own fields are kept: a refresh must not hide a sheet that still waits for the code.
                        appLock = it.appLock.copy(
                            loaded = true,
                            loading = false,
                            problem = null,
                            wrongCode = false,
                            notChanged = false,
                            enabled = status.enabled,
                            timeoutMs = status.timeoutMs,
                            unavailable = if (status.state == AppLockState.UNAVAILABLE) {
                                uiText(R.string.settings_app_lock_unavailable)
                            } else {
                                null
                            },
                            exhausted = status.codeAttemptsExhausted,
                            waitSecs = status.codeRetryAfterMs?.let { (it + 999L) / 1000L } ?: 0L
                        )
                    )
                }
                startWait(status.codeRetryAfterMs)
            }
            is ClientResult.Failed -> local.update {
                it.copy(appLock = it.appLock.copy(loading = false, problem = result.problem.message()))
            }
            is ClientResult.Unavailable -> local.update {
                it.copy(appLock = it.appLock.copy(loading = false, problem = result.reason.message()))
            }
        }
    }

    /**
     * Counts the throttle wait down from core's figure on the monotonic clock. Nothing is stored and nothing is asked
     * again when the count ends.
     */
    private fun startWait(waitMs: Long?) {
        waitJob?.cancel()
        if (waitMs == null || waitMs <= 0L) return
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        waitJob = viewModelScope.launch {
            while (true) {
                val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
                val secs = (left + 999_999_999L) / 1_000_000_000L
                local.update { it.copy(appLock = it.appLock.copy(waitSecs = secs)) }
                if (secs == 0L) break
                delay(1_000L)
            }
        }
    }

    // The account-code change: digits live only in these arrays and are wiped on every exit.
    private val currentCode = CharArray(AccountCode.DIGITS)
    private val nextCode = CharArray(AccountCode.DIGITS)
    private val repeatCode = CharArray(AccountCode.DIGITS)
    private var currentCount = 0
    private var nextCount = 0
    private var repeatCount = 0
    private var codeJob: Job? = null
    private var usernameJob: Job? = null

    private fun codeDigit(next: Boolean, digit: Char?) {
        val form = local.value.codeChange
        if (!form.open || form.working) return
        if (next) nextCount = typed(nextCode, nextCount, digit) else currentCount = typed(currentCode, currentCount, digit)
        publishCodeForm(form)
    }

    private fun repeatDigit(digit: Char?) {
        val form = local.value.codeChange
        if (!form.open || form.working) return
        repeatCount = typed(repeatCode, repeatCount, digit)
        publishCodeForm(form)
    }

    /** Publishes the digit counts and whether the repeated code differs from the new one (only once all six are typed). */
    private fun publishCodeForm(form: SettingsCodeChange) {
        val mismatch = repeatCount == AccountCode.DIGITS && nextCount == AccountCode.DIGITS && !repeatCode.contentEquals(nextCode)
        local.update {
            it.copy(
                codeChange = form.copy(
                    currentDigits = currentCount,
                    nextDigits = nextCount,
                    repeatDigits = repeatCount,
                    mismatch = mismatch,
                    problem = null,
                    done = false
                )
            )
        }
    }
    private fun typed(buffer: CharArray, count: Int, digit: Char?): Int = when {
        digit == null -> if (count > 0) {
            buffer[count - 1] = '\u0000'
            count - 1
        } else {
            0
        }
        digit in '0'..'9' && count < buffer.size -> {
            buffer[count] = digit
            count + 1
        }
        else -> count
    }
    private fun submitCodeChange() {
        val account = activeAccount ?: return
        val form = local.value.codeChange
        val complete = currentCount == AccountCode.DIGITS && nextCount == AccountCode.DIGITS && repeatCount == AccountCode.DIGITS
        if (!form.open || form.working || !complete || !repeatCode.contentEquals(nextCode)) return
        // The repository wipes these copies; the form's own arrays are wiped right after.
        val current = currentCode.copyOf()
        val next = nextCode.copyOf()
        wipeCodes()
        local.update {
            it.copy(
                codeChange = form.copy(
                    currentDigits = 0,
                    nextDigits = 0,
                    repeatDigits = 0,
                    mismatch = false,
                    working = true,
                    problem = null
                )
            )
        }
        codeJob = viewModelScope.launch {
            val result = security.changeAccountCode(account, current, next)
            local.update {
                it.copy(
                    codeChange = when (result) {
                        is ClientResult.Ready -> SettingsCodeChange(open = false, done = true)
                        is ClientResult.Failed -> it.codeChange.copy(working = false, problem = result.problem.codeMessage())
                        is ClientResult.Unavailable -> it.codeChange.copy(working = false, problem = result.reason.message())
                    }
                )
            }
        }
    }
    private fun wipeCodes() {
        currentCode.fill('\u0000')
        nextCode.fill('\u0000')
        repeatCode.fill('\u0000')
        currentCount = 0
        nextCount = 0
        repeatCount = 0
    }

    /**
     * Claims the typed name with the recovery phrase. [phrase] is erased by the repository on every path; this view model
     * keeps only the name, the step and the typed outcome.
     */
    private fun submitUsername(phrase: CharArray) {
        val account = activeAccount
        val form = local.value.usernameClaim
        val refused = account == null || form.working || form.step != SettingsUsernameStep.PHRASE || usernameJob?.isActive == true
        if (refused || phrase.isEmpty()) {
            phrase.fill('\u0000')
            return
        }
        local.update { it.copy(usernameClaim = form.copy(working = true, problem = null, outcome = null)) }
        // ATOMIC: the body always runs, so the phrase is erased by the finally even when the scope is cancelled before start.
        usernameJob = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val result = security.claimUsername(account, form.name, phrase)
                val committed = (result as? ClientResult.Ready)?.value as? UsernameClaimResult.Committed
                local.update { current ->
                    current.copy(
                        usernameClaim = usernameOutcome(current.usernameClaim, result),
                        username = committed?.name?.let { uiText(R.string.settings_username_value, UiText.Untrusted(it)) }
                            ?: current.username
                    )
                }
            } finally {
                phrase.fill('\u0000')
            }
        }
    }

    private fun usernameOutcome(form: SettingsUsernameClaim, result: ClientResult<UsernameClaimResult>): SettingsUsernameClaim =
        when (result) {
            is ClientResult.Ready -> when (val outcome = result.value) {
                UsernameClaimResult.Unchanged -> SettingsUsernameClaim(outcome = uiText(R.string.settings_username_unchanged))
                is UsernameClaimResult.Committed -> SettingsUsernameClaim(outcome = uiText(R.string.settings_username_committed))
                is UsernameClaimResult.Aborted -> SettingsUsernameClaim(
                    step = SettingsUsernameStep.PHRASE,
                    outcome = outcome.reason?.let { uiText(R.string.settings_username_aborted, UiText.Data(it)) }
                        ?: uiText(R.string.settings_username_aborted_plain)
                )
                UsernameClaimResult.Pending -> SettingsUsernameClaim(outcome = uiText(R.string.settings_username_pending))
            }
            is ClientResult.Failed -> {
                val step = when (result.problem.check) {
                    "invalid_username", "username_taken" -> SettingsUsernameStep.NAME
                    else -> SettingsUsernameStep.PHRASE
                }
                SettingsUsernameClaim(step = step, name = form.name, problem = usernameProblem(result.problem))
            }
            is ClientResult.Unavailable -> SettingsUsernameClaim(
                step = SettingsUsernameStep.PHRASE,
                name = form.name,
                problem = result.reason.message()
            )
        }

    private fun usernameProblem(problem: ClientProblem): UiText = when (problem.check) {
        "invalid_username" -> uiText(R.string.settings_username_invalid)
        "username_taken" -> uiText(R.string.settings_username_taken)
        "authentication_required" -> uiText(R.string.settings_username_phrase_mismatch)
        "rate_limited", "capacity" -> problem.retryAfterSecs?.let { uiText(R.string.settings_username_retry, UiText.Data(it.toString())) }
            ?: uiText(R.string.settings_username_directory_unreachable)
        "directory_unavailable" -> uiText(R.string.settings_username_directory_unreachable)
        else -> problem.message()
    }

    private fun closeCodeChange() {
        if (codeJob?.isActive == true) return
        wipeCodes()
        local.update { it.copy(codeChange = SettingsCodeChange()) }
    }

    override fun onCleared() {
        pairingJob?.cancel()
        invitation?.fill('\u0000')
        invitation = null
        invitationQr?.close()
        invitationQr = null
        wipeCodes()
        super.onCleared()
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

    /**
     * Reads the account, its status and its devices. A refresh keeps what the screen shows (and its actions usable)
     * until the new answer replaces it: the screen refreshes on every resume, and blanking the account there disabled
     * Lock, Sign out and Remove for the length of a read, so a tap in that window was silently dropped. A refresh
     * does not retire a running account action either; only leaving the page or a new session does ([ticket]).
     */
    private fun read() {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ticket
        val serial = ++reads
        clientDebugEvent("settings.read.ticket:" + expected + ".serial:" + serial)
        val generation = session.status.value.generation
        local.update { it.copy(loading = true, problem = null, unavailable = null) }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation) ?: return@launch
                val status = accountStatus.read(selected.id)
                current(expected, generation)
                val rows = devices.list(selected.id)
                current(expected, generation)
                val name = security.usernameStatus(selected.id)
                current(expected, generation)
                val confirmed = account(expected, generation, selected.id) ?: return@launch
                if (serial != reads) return@launch
                activeAccount = confirmed.id
                // A page opened before the account was known reads its lock now that the account is.
                if (local.value.page == SettingsPage.APP_LOCK) readAppLock()
                local.update {
                    it.copy(
                        // An account without a display name shows as Unknown; an id is never a user-facing name.
                        accountLabel = confirmed.label.orEmpty(),
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
                        username = when (name) {
                            is ClientResult.Ready -> name.value.name?.let { uiText(R.string.settings_username_value, UiText.Untrusted(it)) }
                                ?: uiText(R.string.settings_username_none)
                            // The directory's refusal reads as one sentence; its code and reason go to the log.
                            is ClientResult.Failed -> {
                                failureSentence(name.problem.code, name.problem.statusName, name.problem.check)
                                uiText(R.string.settings_username_unavailable)
                            }
                            is ClientResult.Unavailable -> uiText(R.string.settings_username_unavailable)
                        },
                        statusProblem = when (status) {
                            is ClientResult.Ready -> null
                            is ClientResult.Failed -> status.problem.message()
                            is ClientResult.Unavailable -> status.reason.message()
                        }
                    )
                }
            } finally {
                if (expected == ticket && serial == reads) local.update { it.copy(loading = false) }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != expected || generation != session.status.value.generation || session.status.value.phase != ClientPhase.OPEN) {
            clientDebugEvent(
                "settings.retired.ticket:" + expected + ".to:" + ticket + ".generation:" + generation + ".to:" +
                    session.status.value.generation + ".phase:" + session.status.value.phase
            )
            throw CancellationException("retired settings")
        }
    }
}
private fun ClientProblem.codeMessage(): UiText = when (statusName) {
    "not_six_digits" -> uiText(R.string.settings_code_not_six)
    "trivial_code" -> uiText(R.string.settings_code_trivial)
    SAME_CODE -> uiText(R.string.settings_code_same)
    else -> if (outcome == ClientOutcome.REFUSED) uiText(R.string.settings_code_refused) else message()
}

/** Invitation characters drawn per chunk; chunks are numbered so they can be checked in order. */
private const val INVITATION_CHUNK = 32
private const val PAIRING_POLL_MILLIS = 2_000L

/** The longest username the claim form takes, without the at sign. */
private const val MAX_USERNAME_NAME = 64
private const val USERNAME_SIGIL = '@'

/** The name this phone gives itself in the invitation (`display_name`); the SDK bounds it to 128 bytes. */
private val DEVICE_NAME = android.os.Build.MODEL?.takeIf { it.isNotBlank() }?.take(64) ?: "android"
private fun ClientProblem.message(): UiText = if (outcome == ClientOutcome.UNKNOWN) {
    uiText(R.string.settings_unknown_outcome)
} else {
    failureSentence(code, statusName, check)
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
