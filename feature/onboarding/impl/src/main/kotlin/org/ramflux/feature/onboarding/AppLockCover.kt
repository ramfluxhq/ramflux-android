// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.AccountCode
import org.ramflux.data.AccountCodeRefusal
import org.ramflux.data.AccountSecurityRepository
import org.ramflux.data.AppLockPhase
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.accountCodeRefusal
import org.ramflux.data.appLockCovers
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.onboarding.impl.R

/**
 * What the app-lock cover shows: [phase] decides the cover, [busy] and [notice] belong to the code entry. [waitSecs] is the
 * throttle wait core reported (the field is disabled meanwhile), [exhausted] routes to recovery from the recovery phrase.
 */
data class AppLockCoverState(
    val phase: AppLockPhase = AppLockPhase.OPEN,
    val busy: Boolean = false,
    val notice: UiText? = null,
    val waitSecs: Long = 0L,
    val exhausted: Boolean = false,
    /** The locked account whose recovery phrase the cover shows in place of the code form; null while the code form shows. */
    val recoveryAccount: String? = null
)

/**
 * The app-lock cover. It follows the session's app-lock phase: nothing while OPEN, a blank cover with a retry while
 * the lock state is UNKNOWN, and the account-code lock screen while LOCKED. It shows no account name, and it has no
 * skip or forget action: only the six-digit account code opens it.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val security: AccountSecurityRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(AppLockCoverState())
    private var waitJob: Job? = null

    init {
        // Any account change (unlock, switch, lock, sign-out) can change which account the lock applies to.
        viewModelScope.launch { accounts.revisions.collect { session.recheckAppLock() } }
        // The wait, the exhausted route, the notice and an in-cover recovery belong to one lock. They end when the app goes
        // to the background, when the lock lifts or when it names another account; the next lock starts with the code form.
        viewModelScope.launch {
            var lockedAccount: String? = null
            var shownFor: String? = null
            session.status.collect { status ->
                val phase = status.coverPhase()
                if (status.suspended || phase != AppLockPhase.LOCKED || status.appLockAccount != lockedAccount) resetCover()
                lockedAccount = status.appLockAccount
                // Each time a lock is shown, the wait and the exhausted route are read from core again, so a return from
                // the background or a restart never re-enables the code field while core still refuses it.
                val showing = status.appLockAccount?.takeIf { !status.suspended && phase == AppLockPhase.LOCKED }
                if (showing != null && showing != shownFor) launch { readThrottle(showing) }
                shownFor = showing
            }
        }
    }

    /** Eager and computed from the session's current value, so a cover that was not subscribed still starts covered. */
    val state: StateFlow<AppLockCoverState> = combine(session.status, local) { status, screen ->
        screen.copy(phase = status.coverPhase())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLockCoverState(phase = session.status.value.coverPhase(), notice = null))

    /** Shows the recovery-phrase restore of the locked account inside the cover; the code form stays behind it. */
    fun startRecovery() {
        val account = session.status.value.appLockAccount ?: return
        local.update { it.copy(recoveryAccount = account, notice = null) }
    }

    /** Back from the recovery: the code form shows again. */
    fun endRecovery() {
        local.update { it.copy(recoveryAccount = null) }
    }

    /** A recovered account is opened by the normal path: the lock is read again, and the cover lowers only if it lifts. */
    fun recovered() {
        resetCover()
        viewModelScope.launch { session.recheckAppLock() }
    }

    /** Ends what the cover showed for the lock: the wait countdown, the exhausted route, the notice and any recovery. */
    private fun resetCover() {
        waitJob?.cancel()
        waitJob = null
        local.update { it.copy(waitSecs = 0L, exhausted = false, notice = null, recoveryAccount = null) }
    }

    /** Reads the lock again, or reopens the session when it failed: an UNKNOWN cover answers with the next state read. */
    fun retry() {
        viewModelScope.launch {
            if (session.status.value.phase == ClientPhase.FAILED) session.foreground() else session.recheckAppLock()
        }
    }

    /** The typed code is wiped on every path; the SDK wipes the copy it receives. */
    fun unlock(code: ErasableBuffer) {
        val digits = code.snapshot()
        code.clear()
        if (local.value.busy || local.value.waitSecs > 0L || local.value.exhausted) {
            digits.fill('\u0000')
            return
        }
        if (!AccountCode.complete(digits)) {
            digits.fill('\u0000')
            local.update { it.copy(notice = uiText(R.string.ui_enter_the_account_code)) }
            return
        }
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                // The locked account is not the active one: its id comes from the lock read, never from the active account.
                var account = session.status.value.appLockAccount
                if (account == null) {
                    // No known locked account: read the lock again rather than guess, then unlock once with the code
                    // still held here. If the read names no account the code is gone and the user types it again.
                    session.recheckAppLock()
                    account = session.status.value.appLockAccount
                    if (account == null) {
                        local.update { it.copy(notice = uiText(R.string.ui_enter_the_account_code)) }
                        return@launch
                    }
                }
                when (val result = accounts.unlock(account, digits)) {
                    is ClientResult.Ready -> {
                        resetCover()
                        session.recheckAppLock()
                    }
                    is ClientResult.Failed -> failed(account, result.problem)
                    is ClientResult.Unavailable -> local.update {
                        it.copy(
                            notice = uiText(R.string.ui_this_action_is_not_available_in_this_version)
                        )
                    }
                }
            } finally {
                digits.fill('\u0000')
                local.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * A refused unlock. Only the typed `wrong_passphrase` reason reads as a wrong code; a throttle or an exhausted count
     * is read from core's state, so the wait is what core holds, and any other failure says so without a guess.
     */
    private suspend fun failed(account: String, problem: ClientProblem) {
        when (problem.accountCodeRefusal()) {
            AccountCodeRefusal.WRONG_CODE -> {
                local.update { it.copy(notice = codeText(AccountCodeRefusal.WRONG_CODE, null)) }
                readThrottle(account)
            }
            AccountCodeRefusal.RETRY_LATER -> readThrottle(account)
            AccountCodeRefusal.ATTEMPTS_EXHAUSTED -> local.update {
                it.copy(exhausted = true, notice = codeText(AccountCodeRefusal.ATTEMPTS_EXHAUSTED, null))
            }
            else -> local.update {
                it.copy(
                    notice = problem.accountCodeRefusal()?.let { refusal -> codeText(refusal, problem.retryAfterSecs) }
                        ?: uiText(R.string.app_lock_unlock_failed)
                )
            }
        }
    }

    /** Reads the throttle core holds now and counts it down; the field stays disabled until the count ends. */
    private suspend fun readThrottle(account: String) {
        val status = (security.appLockState(account) as? ClientResult.Ready)?.value ?: return
        if (status.codeAttemptsExhausted) {
            local.update { it.copy(exhausted = true, notice = codeText(AccountCodeRefusal.ATTEMPTS_EXHAUSTED, null)) }
            return
        }
        val waitMs = status.codeRetryAfterMs?.takeIf { it > 0L } ?: return
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            while (true) {
                val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
                val secs = (left + 999_999_999L) / 1_000_000_000L
                local.update { it.copy(waitSecs = secs) }
                if (secs == 0L) break
                delay(1_000L)
            }
        }
    }
}

private fun ClientSnapshot.coverPhase(): AppLockPhase = when {
    phase == ClientPhase.OPEN -> appLock
    appLockCovers() -> AppLockPhase.UNKNOWN
    else -> AppLockPhase.OPEN
}

/**
 * Takes the system back while the cover is up. The dispatcher calls the callback registered last first, and a handler
 * that is composed once (enabled or not) keeps its first place in that order, so a chat opened after the app started
 * would win over it and pop its own stack under the cover. The handler therefore enters the composition only while the
 * cover is up: it registers when the cover rises, after every handler the shell composed before.
 */
@Composable
fun CoverBackGuard(covered: Boolean, onBack: () -> Unit) {
    if (covered) BackHandler(enabled = true, onBack = onBack)
}

/** The cover for the app lock. Callers draw it above the shell, so the shell keeps its navigation state underneath. */
@Composable
fun AppLockCover(vm: AppLockViewModel = hiltViewModel()) {
    val theme = LocalRFTheme.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // While the cover is up, back does not pop the shell's stack underneath it. It only hides the keyboard: the typed
    // code stays in the entry.
    CoverBackGuard(covered = ui.phase != AppLockPhase.OPEN) { keyboard?.hide() }
    val code = remember { ErasableBuffer() }
    LaunchedEffect(ui.phase) {
        if (ui.phase != AppLockPhase.OPEN) {
            // The cover rises with the keyboard and focus released: nothing typed elsewhere reaches the shell.
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    val recovering = ui.recoveryAccount
    when (ui.phase) {
        AppLockPhase.OPEN -> Unit
        AppLockPhase.UNKNOWN -> CoverSurface {
            KitButton(
                stringResource(R.string.app_lock_retry),
                { vm.retry() },
                Modifier.fillMaxWidth(),
                tag = "app-lock-retry"
            )
        }
        AppLockPhase.LOCKED -> {
            if (recovering != null) {
                LockRecovery(recovering, onBack = vm::endRecovery, onRecovered = vm::recovered)
            } else {
                CoverSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                        Text(
                            stringResource(R.string.app_lock_app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("app-lock-name")
                        )
                        Text(stringResource(R.string.app_lock_body), style = MaterialTheme.typography.bodyLarge)
                        if (ui.exhausted) {
                            // No code field after too many wrong codes: only the recovery phrase opens the account.
                            ui.notice?.let {
                                Text(
                                    it.render(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("app-lock-notice")
                                )
                            }
                            KitButton(
                                stringResource(R.string.app_lock_restore),
                                { vm.startRecovery() },
                                Modifier.fillMaxWidth(),
                                tag = "app-lock-restore"
                            )
                        } else {
                            val waiting = ui.waitSecs > 0L
                            AccountCodeField(
                                code,
                                stringResource(R.string.ui_account_code),
                                "app-lock-code",
                                enabled = !ui.busy && !waiting,
                                helper = stringResource(R.string.ui_account_code_explanation)
                            )
                            if (waiting) {
                                val wait = "%02d:%02d".format(ui.waitSecs / 60, ui.waitSecs % 60)
                                Text(
                                    stringResource(R.string.app_lock_wait, wait),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("app-lock-wait")
                                )
                            }
                            ui.notice?.let {
                                Text(
                                    it.render(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("app-lock-notice")
                                )
                            }
                            KitButton(
                                stringResource(R.string.ui_unlock),
                                { vm.unlock(code) },
                                Modifier.fillMaxWidth(),
                                enabled = !ui.busy && !waiting,
                                tag = "app-lock-unlock"
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phrase restore of the locked account, drawn inside the cover: the cover stays up and no shell page opens under it.
 * The account is fixed to the lock's account, so there is no picker and no other onboarding entry. Back returns to the
 * code form; a recovered account goes through the normal unlock path.
 */
@Composable
private fun LockRecovery(account: String, onBack: () -> Unit, onRecovered: () -> Unit) {
    val theme = LocalRFTheme.current
    val ceremony = hiltViewModel<OnboardingViewModel>(key = "app-lock-recovery")
    val state by ceremony.state.collectAsStateWithLifecycle()
    val entry = remember { RecoveryEntry() }
    DisposableEffect(Unit) {
        onDispose {
            entry.phrase.clear()
            entry.code.clear()
            // Leaving the cover (suspend or the lock lifting) ends the core recover session, not only the typed buffers.
            ceremony.cancelRecovery()
        }
    }
    LaunchedEffect(account) { ceremony.requestRecovery(account) }
    LaunchedEffect(ceremony) {
        ceremony.events.collect { event -> if (event is OnboardingEvent.AccountSelected) onRecovered() }
    }
    val leave = {
        ceremony.onAction(OnboardingAction.Back)
        onBack()
    }
    BackHandler(onBack = leave)
    CoverSurface {
        Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp), modifier = Modifier.testTag("app-lock-recovery")) {
            Text(stringResource(R.string.ui_recover_account), style = MaterialTheme.typography.headlineSmall)
            if (state.busy) Text(stringResource(R.string.ui_please_wait), style = MaterialTheme.typography.bodyMedium)
            state.notice?.let {
                Text(it.message.render(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-lock-notice"))
            }
            if (state.recovery != null) {
                RecoverPage(state, entry, ceremony::onAction)
                KitButton(
                    stringResource(R.string.ui_recover_account_action),
                    { ceremony.onAction(OnboardingAction.ConfirmRecover(entry.phrase, entry.code)) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy && entry.ready(),
                    tag = "confirm-recovery"
                )
            }
            KitButton(
                stringResource(R.string.ui_back),
                leave,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                tag = "app-lock-recovery-back"
            )
        }
    }
}

/** An opaque cover: the content under it receives no pointer input while it is up. */
@Composable
private fun CoverSurface(content: @Composable () -> Unit) {
    val theme = LocalRFTheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg)
            .consumeAllPointers()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(theme.gap.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { content() }
    }
}

/** Takes the pointer events the content does not handle, so nothing under the cover is pressed or scrolled. */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
