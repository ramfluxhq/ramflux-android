// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.ramflux.data.AppLockPhase
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.accountCodeRefusal
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.onboarding.impl.R

/** What the app-lock cover shows: [phase] decides the cover, [busy] and [notice] belong to the code entry. */
data class AppLockCoverState(
    val phase: AppLockPhase = AppLockPhase.OPEN,
    val busy: Boolean = false,
    val notice: UiText? = null,
)

/**
 * The app-lock cover. It follows the session's app-lock phase: nothing while OPEN, a blank cover with a retry while
 * the lock state is UNKNOWN, and the account-code lock screen while LOCKED. It shows no account name, and it has no
 * skip or forget action: only the six-digit account code opens it.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository,
) : ViewModel() {
    private val local = MutableStateFlow(AppLockCoverState())

    init {
        // Any account change (unlock, switch, lock, sign-out) can change which account the lock applies to.
        viewModelScope.launch { accounts.revisions.collect { session.recheckAppLock() } }
    }

    /** Eager and computed from the session's current value, so a cover that was not subscribed still starts covered. */
    val state: StateFlow<AppLockCoverState> = combine(session.status, local) { status, screen ->
        screen.copy(phase = status.coverPhase())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLockCoverState(phase = session.status.value.coverPhase(), notice = null))

    /** Reads the lock again: an UNKNOWN cover answers with the next state read. */
    fun retry() {
        viewModelScope.launch { session.recheckAppLock() }
    }

    /** The typed code is wiped on every path; the SDK wipes the copy it receives. */
    fun unlock(code: ErasableBuffer) {
        val digits = code.snapshot()
        code.clear()
        if (local.value.busy) {
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
                val account = session.status.value.appLockAccount
                if (account == null) {
                    // No known locked account: read the lock again rather than guess; the cover follows that read.
                    session.recheckAppLock()
                    return@launch
                }
                when (val result = accounts.unlock(account, digits)) {
                    is ClientResult.Ready -> {
                        local.update { it.copy(notice = null) }
                        session.recheckAppLock()
                    }
                    is ClientResult.Failed -> local.update { it.copy(notice = failureNotice(result.problem)) }
                    is ClientResult.Unavailable -> local.update { it.copy(notice = uiText(R.string.ui_this_action_is_not_available_in_this_version)) }
                }
            } finally {
                digits.fill('\u0000')
                local.update { it.copy(busy = false) }
            }
        }
    }

    /** A failed unlock is a wrong code unless the SDK named another refusal: it never reads as a generic failure. */
    private fun failureNotice(problem: org.ramflux.data.ClientProblem): UiText =
        problem.accountCodeRefusal(unlock = true)?.let { codeText(it, problem.retryAfterSecs) }
            ?: codeText(AccountCodeRefusal.WRONG_CODE, null)
}

private fun ClientSnapshot.coverPhase(): AppLockPhase = if (phase == ClientPhase.OPEN) appLock else AppLockPhase.OPEN

/** The cover for the app lock. Callers draw it above the shell, so the shell keeps its navigation state underneath. */
@Composable
fun AppLockCover(vm: AppLockViewModel = hiltViewModel()) {
    val theme = LocalRFTheme.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // While the cover is up, back does not pop the shell's stack underneath it. It only hides the keyboard: the typed
    // code stays in the entry.
    BackHandler(enabled = ui.phase != AppLockPhase.OPEN) { keyboard?.hide() }
    val code = remember { ErasableBuffer() }
    LaunchedEffect(ui.phase) {
        if (ui.phase != AppLockPhase.OPEN) {
            // The cover rises with the keyboard and focus released: nothing typed elsewhere reaches the shell.
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    when (ui.phase) {
        AppLockPhase.OPEN -> Unit
        AppLockPhase.UNKNOWN -> CoverSurface {
            KitButton(
                stringResource(R.string.app_lock_retry),
                { vm.retry() },
                Modifier.fillMaxWidth(),
                tag = "app-lock-retry",
            )
        }
        AppLockPhase.LOCKED -> CoverSurface {
            Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                Text(stringResource(R.string.app_lock_app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("app-lock-name"))
                Text(stringResource(R.string.app_lock_body), style = MaterialTheme.typography.bodyLarge)
                AccountCodeField(
                    code,
                    stringResource(R.string.ui_account_code),
                    "app-lock-code",
                    enabled = !ui.busy,
                    helper = stringResource(R.string.ui_account_code_explanation),
                )
                ui.notice?.let { Text(it.render(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-lock-notice")) }
                KitButton(
                    stringResource(R.string.ui_unlock),
                    { vm.unlock(code) },
                    Modifier.fillMaxWidth(),
                    enabled = !ui.busy,
                    tag = "app-lock-unlock",
                )
            }
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
            .padding(theme.gap.dp),
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
