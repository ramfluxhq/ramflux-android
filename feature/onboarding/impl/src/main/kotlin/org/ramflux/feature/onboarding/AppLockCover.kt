// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.collectLatest
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
import org.ramflux.data.clientDebugEvent
import org.ramflux.designsystem.AppLockCoverFocus
import org.ramflux.designsystem.LocalAppLockCoverFocus
import org.ramflux.designsystem.LocalInAppLockCover
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.focusTrap
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.kitFormColumn
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
    val recoveryAccount: String? = null,
    /** Why the recovery went back to its first step (phrase mismatch, expired, failed); shown above the form. */
    val recoveryNotice: UiText? = null,
    /** The phrase was accepted and core granted one code try after the wait: the countdown reads as that. */
    val phraseAccepted: Boolean = false,
    /**
     * The next code is the one try core granted after the accepted phrase: a wrong code there is the recovery's wrong code
     * and shows its notice above the exhausted title (D-4 "Recovery failed: code"). Ends with that try or with the lock.
     */
    val grantedTry: Boolean = false,
    /** The locked account's own display name, read for [nameFor] only; [name] is null while it is unread or not this lock's. */
    val nameFor: String? = null,
    val name: String? = null,
    /** The account the lock applies to now; the cover draws [name] only when it is the account [nameFor] names. */
    val accountId: String? = null,
    /** The remove-from-this-device confirmation is open for [removeId]; [removeName] is its display name if known. */
    val removeOpen: Boolean = false,
    val removeId: String? = null,
    val removeName: String? = null,
    val removing: Boolean = false,
    val removeFailed: Boolean = false
)

/**
 * The app-lock cover. It follows the session's app-lock phase: nothing while OPEN, a blank cover with a retry while
 * the lock state is UNKNOWN, and the account-code lock screen while LOCKED. It shows the locked account's own display name,
 * initial and short id line and nothing else of the account (G05 rule 3 as narrowed by UX-FIX-01 D-2 and decision 13): the
 * name is read from the account list for the lock's account id only, bound to that id, and never logged. It has no skip
 * action: only the six-digit account code opens it, or the confirmed removal of the locked account from this device.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val security: AccountSecurityRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(AppLockCoverState())
    private var waitJob: Job? = null

    /** The account the open confirmation was raised for; confirm removes it only while it is still the locked account. */
    private var removeTarget: String? = null

    /**
     * One item (the removed account's display name, or null) after an account was removed from this device, here or in
     * Settings; the cover draws the notice above the destination.
     */
    val removed = accounts.removedNotices

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
                if (showing != null && showing != shownFor) {
                    launch { readName(showing) }
                    launch { readThrottle(showing) }
                }
                shownFor = showing
            }
        }
    }

    /** Eager and computed from the session's current value, so a cover that was not subscribed still starts covered. */
    val state: StateFlow<AppLockCoverState> = combine(session.status, local) { status, screen ->
        // The name is drawn only for the account the lock names now: a late read for another account never shows.
        val account = status.appLockAccount
        screen.copy(
            phase = status.coverPhase(),
            accountId = account,
            name = screen.name.takeIf { account != null && screen.nameFor == account }
        )
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

    /**
     * A recovered account is opened by the normal path: the lock is read again, and the cover lowers only when core's state
     * says it lifted (never on the reply alone). If it still reads locked, the cover returns to its lock page.
     */
    fun recovered() {
        viewModelScope.launch {
            session.recheckAppLock()
            val status = session.status.value
            val account = status.appLockAccount
            if (status.coverPhase() == AppLockPhase.LOCKED && account != null) {
                local.update { it.copy(recoveryAccount = null, recoveryNotice = null) }
                readThrottle(account)
            }
        }
    }

    /**
     * A refused recovery confirmation. Core consumed the ceremony. A wait or a wrong code ends the recovery and reads core's
     * state again; a wrong phrase, an expiry or any other failure goes back to the first step (the form calls `begin` again).
     * Returns true when the recovery restarts.
     */
    fun recoveryRefused(refusal: AccountCodeRefusal?): Boolean {
        val account = session.status.value.appLockAccount
        if (local.value.recoveryAccount == null || account == null) return false
        when (refusal) {
            AccountCodeRefusal.RETRY_LATER -> {
                local.update {
                    it.copy(recoveryAccount = null, recoveryNotice = null, phraseAccepted = true, grantedTry = true, notice = null)
                }
                viewModelScope.launch { readThrottle(account) }
            }
            AccountCodeRefusal.WRONG_CODE -> {
                local.update {
                    it.copy(recoveryAccount = null, recoveryNotice = null, notice = uiText(R.string.app_lock_code_wrong))
                }
                viewModelScope.launch { readThrottle(account) }
            }
            AccountCodeRefusal.ATTEMPTS_EXHAUSTED -> {
                local.update { it.copy(recoveryAccount = null, recoveryNotice = null, notice = null) }
                viewModelScope.launch { readThrottle(account) }
            }
            else -> {
                val line = when (refusal) {
                    AccountCodeRefusal.RECOVERY_PHRASE_MISMATCH -> uiText(R.string.app_lock_phrase_wrong)
                    AccountCodeRefusal.RECOVERY_EXPIRED -> uiText(R.string.app_lock_recovery_expired)
                    else -> uiText(R.string.app_lock_unlock_failed)
                }
                local.update { it.copy(recoveryNotice = line) }
                return true
            }
        }
        return false
    }

    /**
     * Reads the display name of the locked account from the account list. Only the row whose id is [account] is used, and
     * the result is stored with that id, so a lock that moved to another account meanwhile never shows this name. Core
     * leaves the name out of an app-locked account's row (and the list can fail while the lock changes), so the name
     * this process last listed for that same id stands in; after a fresh start with no such read the name stays unknown.
     */
    private suspend fun readName(account: String) {
        val listed = (accounts.list() as? ClientResult.Ready)?.value?.accounts?.firstOrNull { it.id == account }?.label
        val label = listed ?: accounts.knownLabel(account)
        local.update { it.copy(nameFor = account, name = label) }
    }

    /** The code form's notice ends when the person starts typing again. */
    fun edited() {
        if (local.value.notice != null) local.update { it.copy(notice = null) }
    }

    /** Dismisses the notice shown above the exhausted page. */
    fun dismissNotice() {
        local.update { it.copy(notice = null) }
    }

    /**
     * Opens the remove-from-this-device confirmation of the locked account. The dialog names the account it was raised for:
     * the id and the display name are fixed here, and confirm removes that id only.
     */
    fun requestRemove() {
        val account = session.status.value.appLockAccount ?: return
        removeTarget = account
        val name = local.value.name.takeIf { local.value.nameFor == account }
        local.update { it.copy(removeOpen = true, removeId = account, removeName = name, removing = false, removeFailed = false) }
    }

    fun cancelRemove() {
        if (local.value.removing) return
        removeTarget = null
        local.update { it.copy(removeOpen = false, removeId = null, removeName = null, removeFailed = false) }
    }

    /**
     * Removes the locked account and its local data from this device. The account id is the lock's account, never typed;
     * on success the lock is read again, so the cover shows the next locked account or hands over to the picker or
     * onboarding. On failure nothing changed and the dialog says so.
     */
    fun confirmRemove() {
        if (!local.value.removeOpen || local.value.removing) return
        val account = removeTarget
        if (account == null || session.status.value.appLockAccount != account) {
            removeTarget = null
            local.update { it.copy(removeOpen = false, removeId = null, removeName = null, removeFailed = false) }
            return
        }
        val removedName = local.value.removeName
        local.update { it.copy(removing = true, removeFailed = false) }
        viewModelScope.launch {
            try {
                when (accounts.removeLocal(account)) {
                    is ClientResult.Ready -> {
                        resetCover()
                        accounts.announceRemoved(removedName)
                        session.recheckAppLock()
                    }
                    else -> local.update { it.copy(removeFailed = true) }
                }
            } finally {
                local.update { it.copy(removing = false) }
            }
        }
    }

    /** Ends what the cover showed for the lock: the wait countdown, the exhausted route, the notice and any recovery. */
    private fun resetCover() {
        removeTarget = null
        waitJob?.cancel()
        waitJob = null
        local.update {
            it.copy(
                waitSecs = 0L, exhausted = false, notice = null, recoveryAccount = null, recoveryNotice = null,
                phraseAccepted = false, grantedTry = false, name = null, nameFor = null, removeOpen = false, removeId = null,
                removeName = null, removing = false, removeFailed = false
            )
        }
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
        // Debug trace only (a no-op in release): flags and the digit check, never the code.
        val busy = local.value.busy
        val wait = local.value.waitSecs
        val exhausted = local.value.exhausted
        val complete = AccountCode.complete(digits)
        clientDebugEvent("app_lock.unlock:busy.$busy.wait.$wait.exhausted.$exhausted.complete.$complete")
        if (local.value.busy || local.value.waitSecs > 0L || local.value.exhausted) {
            digits.fill('\u0000')
            return
        }
        if (!AccountCode.complete(digits)) {
            digits.fill('\u0000')
            local.update { it.copy(notice = uiText(R.string.ui_enter_the_account_code)) }
            return
        }
        // The granted try is spent by this code whatever core answers; a wrong one shows the recovery's notice (see [failed]).
        val granted = local.value.grantedTry
        local.update { it.copy(busy = true, notice = null, phraseAccepted = false, grantedTry = false) }
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
                    is ClientResult.Failed -> failed(account, result.problem, granted)
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
     * is read from core's state, so the wait is what core holds, and any other failure says so without a guess. [granted]
     * says the code was the one try core granted after the accepted recovery phrase.
     */
    private suspend fun failed(account: String, problem: ClientProblem, granted: Boolean) {
        val refusal = problem.accountCodeRefusal()
        clientDebugEvent("app_lock.unlock.failed:$refusal")
        when (refusal) {
            AccountCodeRefusal.WRONG_CODE -> {
                local.update { it.copy(notice = codeText(AccountCodeRefusal.WRONG_CODE, null)) }
                readThrottle(account)
                // The wrong code that spent the last of the ten tries shows the plain exhausted page (no stale line from the
                // code form). The granted try after the accepted phrase is the recovery's code check: its wrong code shows the
                // recovery's wrong-code notice above the exhausted title, so the person sees the code was refused (D-4).
                if (local.value.exhausted) {
                    local.update { it.copy(notice = if (granted) uiText(R.string.app_lock_code_wrong) else null) }
                }
            }
            AccountCodeRefusal.RETRY_LATER -> readThrottle(account)
            AccountCodeRefusal.ATTEMPTS_EXHAUSTED -> local.update { it.copy(exhausted = true, notice = null) }
            else -> local.update {
                it.copy(
                    notice = refusal?.let { kind -> codeText(kind, problem.retryAfterSecs) }
                        ?: uiText(R.string.app_lock_unlock_failed)
                )
            }
        }
    }

    /** Reads the state core holds now (exhausted or a throttle) and counts a wait down; the field stays disabled until it ends. */
    private suspend fun readThrottle(account: String) {
        val status = (security.appLockState(account) as? ClientResult.Ready)?.value
        val exhausted = status?.codeAttemptsExhausted
        val wait = status?.codeRetryAfterSecs
        clientDebugEvent("app_lock.throttle:exhausted.$exhausted.wait.$wait")
        status ?: return
        if (status.codeAttemptsExhausted) {
            waitJob?.cancel()
            waitJob = null
            local.update { it.copy(exhausted = true, waitSecs = 0L, phraseAccepted = false) }
            return
        }
        // The field returns only when core's state says the account is not exhausted.
        local.update { it.copy(exhausted = false) }
        val waitSecs = status.codeRetryAfterSecs?.takeIf { it > 0L } ?: return
        // One status line: the countdown replaces any notice, and the notice does not return when the wait ends.
        local.update { it.copy(notice = null) }
        val deadline = System.nanoTime() + waitSecs * 1_000_000_000L
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            while (true) {
                val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
                val secs = (left + 999_999_999L) / 1_000_000_000L
                local.update { it.copy(waitSecs = secs) }
                if (secs == 0L) {
                    local.update { it.copy(phraseAccepted = false) }
                    break
                }
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

private const val REMOVED_NOTICE_MILLIS = 5_000L

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
    // The removal notice (from this cover or from Settings) is drawn in this window, above the destination (the picker or
    // onboarding): it outlives the cover going down and the Settings page leaving, and unlike a system toast it belongs to
    // the screen the app lands on. The name is held in this composition only.
    val resources = LocalResources.current
    var removedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) {
        vm.removed.collectLatest { name ->
            removedText = if (name.isNullOrBlank()) {
                resources.getString(R.string.ui_account_removed_unnamed)
            } else {
                resources.getString(R.string.ui_account_removed, name)
            }
            delay(REMOVED_NOTICE_MILLIS)
            removedText = null
        }
    }
    if (ui.removeOpen && ui.phase == AppLockPhase.LOCKED) {
        // The cover's own window: it is shown while the cover is up.
        CompositionLocalProvider(LocalInAppLockCover provides true) {
            RemoveAccountDialog(
                ui.removeName,
                ui.removeId,
                ui.removing,
                ui.removeFailed,
                onConfirm = vm::confirmRemove,
                onDismiss = vm::cancelRemove
            )
        }
    }
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
                LockRecovery(
                    recovering,
                    name = ui.name,
                    notice = ui.recoveryNotice,
                    onBack = vm::endRecovery,
                    onRecovered = vm::recovered,
                    onRefused = vm::recoveryRefused
                )
            } else {
                CoverSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                        // Name, short id line (decision 13) and state: the same row on the code form and the exhausted page.
                        AccountIdentity(
                            ui.name,
                            status = stringResource(R.string.ui_locked),
                            accountId = ui.accountId,
                            nameTag = "app-lock-name",
                            statusTag = "app-lock-status"
                        )
                        if (ui.exhausted) {
                            // No code field after too many wrong codes: recover with the phrase, or remove the account here.
                            // A notice (the recovery's wrong code) sits above the title and can be dismissed.
                            ui.notice?.let {
                                Text(
                                    it.render(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable { vm.dismissNotice() }.testTag("app-lock-notice")
                                )
                            }
                            Text(
                                stringResource(R.string.app_lock_exhausted_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("app-lock-exhausted")
                            )
                            Text(stringResource(R.string.app_lock_exhausted_body), style = MaterialTheme.typography.bodyMedium)
                            KitButton(
                                stringResource(R.string.app_lock_restore),
                                { vm.startRecovery() },
                                Modifier.fillMaxWidth(),
                                tag = "app-lock-restore"
                            )
                            KitButton(
                                stringResource(R.string.app_lock_remove),
                                { vm.requestRemove() },
                                Modifier.fillMaxWidth(),
                                kind = KitButtonKind.Destructive,
                                tag = "app-lock-remove"
                            )
                        } else {
                            val waiting = ui.waitSecs > 0L
                            Text(stringResource(R.string.app_lock_body), style = MaterialTheme.typography.bodyLarge)
                            AccountCodeField(
                                code,
                                stringResource(R.string.ui_account_code),
                                "app-lock-code",
                                enabled = !ui.busy && !waiting,
                                helper = stringResource(R.string.ui_account_code_explanation),
                                onEdited = vm::edited
                            )
                            // One status line, replaced on every state change: the countdown while a wait applies, else the notice.
                            if (waiting) {
                                val wait = stringResource(
                                    R.string.app_lock_countdown,
                                    (ui.waitSecs / 60).toString().padStart(2, '0'),
                                    (ui.waitSecs % 60).toString().padStart(2, '0')
                                )
                                Text(
                                    stringResource(
                                        if (ui.phraseAccepted) R.string.app_lock_phrase_accepted else R.string.app_lock_wait,
                                        wait
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("app-lock-wait")
                                )
                            } else {
                                ui.notice?.let {
                                    Text(
                                        it.render(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("app-lock-notice")
                                    )
                                }
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
    removedText?.let { text ->
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(theme.gap.dp), Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(theme.gap.dp),
                // Announced once by TalkBack, without taking focus.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("account-removed-notice")
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(theme.gap.dp))
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
private fun LockRecovery(
    account: String,
    name: String?,
    notice: UiText?,
    onBack: () -> Unit,
    onRecovered: () -> Unit,
    onRefused: (AccountCodeRefusal?) -> Boolean
) {
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
        ceremony.events.collect { event ->
            when (event) {
                is OnboardingEvent.AccountSelected -> onRecovered()
                // Back to the first step: `begin` again re-reads the fingerprint. The typed buffers are already cleared.
                is OnboardingEvent.RecoveryRefused -> if (onRefused(event.refusal)) ceremony.requestRecovery(account)
                else -> Unit
            }
        }
    }
    val leave = {
        ceremony.onAction(OnboardingAction.Back)
        onBack()
    }
    BackHandler(onBack = leave)
    CoverSurface {
        Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp), modifier = Modifier.testTag("app-lock-recovery")) {
            AccountIdentity(name, status = stringResource(R.string.app_lock_recover_title), nameTag = "app-lock-recovery-name")
            if (state.busy) Text(stringResource(R.string.ui_please_wait), style = MaterialTheme.typography.bodyMedium)
            (notice ?: state.notice?.message)?.let {
                Text(it.render(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-lock-notice"))
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

/**
 * An opaque cover: the content under it receives no pointer input while it is up. The blocker is a sibling drawn behind
 * the cover's own content, not a modifier on their common parent: a touch on a control reaches only that control, and a
 * touch anywhere else ends at the blocker, so the shell underneath is never hit. A parent that consumed every event would
 * also consume the simulated move Compose sends when the layout changes under a held pointer (the countdown line going
 * away, the keyboard insets), and a pressed button that sees a consumed move cancels its click. The form column is capped
 * at the kit's form width (480 dp) and centred, and scrolls, so in landscape or on a tall screen the code slots keep their size.
 */
@Composable
private fun CoverSurface(content: @Composable () -> Unit) {
    val theme = LocalRFTheme.current
    val focus = LocalFocusManager.current
    val coverFocus = LocalAppLockCoverFocus.current ?: remember { AppLockCoverFocus() }
    // Focus starts on the cover's anchor, a non-text target, so the keyboard is not raised when the app returns from the
    // background; the code field takes focus only when the user taps it or tabs to it. Focus cannot leave the cover.
    LaunchedEffect(coverFocus) {
        focus.clearFocus(force = true)
        coverFocus.take()
    }
    CompositionLocalProvider(LocalInAppLockCover provides true) {
        Box(Modifier.fillMaxSize().background(theme.bg).focusTrap()) {
            Spacer(Modifier.matchParentSize().consumeAllPointers())
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(theme.gap.dp)
                    .let { with(coverFocus) { it.coverAnchor() } }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.kitFormColumn()) { content() }
                }
            }
        }
    }
}

/** Takes the pointer events no cover control handles, so nothing under the cover is pressed or scrolled. */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
