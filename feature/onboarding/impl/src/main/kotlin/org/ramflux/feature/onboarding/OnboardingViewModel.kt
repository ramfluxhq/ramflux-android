// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.onboarding.impl.R

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val nodes: NodeSetupRepository,
    private val session: ClientSessionRepository,
    private val ceremony: AccountCeremonyRepository = UnavailableAccountCeremonyRepository(),
    private val confirmTrace: ConfirmTrace = ConfirmTrace {}
) : ViewModel() {
    private val local = MutableStateFlow(OnboardingState())
    private val channel = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state: StateFlow<OnboardingState> = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) {
            screen.copy(sessionReady = true)
        } else {
            OnboardingState(
                busy = status.phase == ClientPhase.OPENING,
                notice = OnboardingNotice(uiText(R.string.ui_private_data_is_closed_return_to_the_start))
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), OnboardingState())
    private var work: Job? = null
    private var phrase: RecoveryPhrase? = null

    override fun onCleared() {
        phrase?.close()
        phrase = null
        super.onCleared()
    }
    private var candidate: DiscoveredNode? = null
    private var pendingUnlock: String? = null
    private var requestedCreate = false
    private var requestedUnlock = ""
    private var savedNodes = emptyList<SavedNode>()
    private var ticket = 0L
    private var operationGeneration = 0L
    private var operationTicket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            work?.cancel()
            ticket++
            candidate = null
            savedNodes = emptyList()
            phrase?.close()
            phrase = null
            local.value = OnboardingState(page = entryPage())
            if (phase == ClientPhase.OPEN) onAction(OnboardingAction.Refresh)
        }
    }

    /** Opens create or the existing local unlock when this screen was pushed from startup. */
    fun requestEntry(create: Boolean, unlockId: String) {
        requestedCreate = create
        requestedUnlock = unlockId
        if (unlockId.isNotEmpty()) pendingUnlock = unlockId
        if (session.status.value.phase == ClientPhase.OPEN) local.update { it.copy(page = entryPage()) }
    }

    fun onAction(action: OnboardingAction) {
        if (action == OnboardingAction.Back) {
            work?.cancel()
            ticket++
            candidate = null
            val dropCeremony = local.value.page == OnboardingPage.PHRASE || local.value.page == OnboardingPage.CHALLENGE
            val dropRecovery = local.value.recovery != null
            phrase?.close()
            phrase = null
            if (dropCeremony) viewModelScope.launch { ceremony.cancel() }
            if (dropRecovery) {
                local.update { it.copy(recovery = null) }
                viewModelScope.launch { ceremony.cancelRecover() }
            }
            if (local.value.page == OnboardingPage.START || leavingEntry()) {
                viewModelScope.launch { channel.send(OnboardingEvent.Back) }
            } else {
                local.update {
                    it.copy(
                        page = if (it.page == OnboardingPage.CONFIRM) OnboardingPage.ADDRESS else OnboardingPage.START,
                        busy = false,
                        identity = null,
                        normalizedOrigin = null,
                        notice = null
                    )
                }
            }
            return
        }
        if (session.status.value.phase != ClientPhase.OPEN || local.value.busy) {
            if (action is OnboardingAction.ConfirmChallenge) {
                val phase = session.status.value.phase
                val busy = local.value.busy
                logConfirm("confirm.ignored.$phase.$busy")
            }
            return
        }
        when (action) {
            OnboardingAction.Refresh -> read()
            is OnboardingAction.Address -> {
                candidate = null
                local.update { it.copy(address = action.value.take(2048), identity = null, normalizedOrigin = null, notice = null) }
            }
            is OnboardingAction.OperatorKey -> {
                if (local.value.page == OnboardingPage.ADDRESS && candidate == null) {
                    local.update { it.copy(operatorKey = action.value.take(4096), operatorKeyIssue = null, notice = null) }
                }
            }
            is OnboardingAction.Page -> page(action.page)
            OnboardingAction.ReviewAddress -> reviewAddress()
            OnboardingAction.Discover -> discover()
            OnboardingAction.Confirm -> confirm()
            is OnboardingAction.ChooseNode -> savedNodes.find { it.id == action.id }?.let { node ->
                local.update { it.copy(page = OnboardingPage.DEFAULT_NODE, selectedNode = node.item(), notice = null) }
            }
            OnboardingAction.UseNode -> local.value.selectedNode?.let { selected ->
                launch {
                    val result = nodes.select(selected.id)
                    checkCurrent()
                    when (result) {
                        is ClientResult.Ready -> local.update { it.copy(page = OnboardingPage.WELCOME, selectedNode = null) }
                        else -> failure(result)
                    }
                }
            }
            is OnboardingAction.DisplayName -> if (local.value.page == OnboardingPage.CREATE) {
                local.update { it.copy(displayName = action.value.trim().take(64), notice = null) }
            }
            OnboardingAction.BeginCreate -> beginCreate()
            OnboardingAction.PhraseSaved -> hidePhrase()
            is OnboardingAction.ConfirmChallenge -> confirmChallenge(action)
            OnboardingAction.ContinueCreated -> local.value.createdAccountId?.let { id ->
                viewModelScope.launch { channel.send(OnboardingEvent.AccountCreated(id)) }
            }
            is OnboardingAction.SelectAccount -> {
                val known = local.value.accounts.find { it.id == action.id }
                if (known != null && !known.unlocked) {
                    pendingUnlock = action.id
                    local.update { it.copy(page = OnboardingPage.LOCAL_UNLOCK, notice = null) }
                } else {
                    pendingUnlock = null
                    launch {
                        val result = accounts.select(action.id)
                        checkCurrent()
                        when (result) {
                            is ClientResult.Ready -> {
                                checkCurrent()
                                channel.send(OnboardingEvent.AccountSelected(action.id))
                            }
                            else -> failure(result)
                        }
                    }
                }
            }
            is OnboardingAction.SubmitUnlock -> submitUnlock(action)
            is OnboardingAction.BeginRecover -> beginRecover(action.accountId)
            is OnboardingAction.ConfirmRecover -> confirmRecover(action)
            OnboardingAction.Back -> Unit
            OnboardingAction.RestoreBackup -> if (!local.value.busy) viewModelScope.launch { channel.send(OnboardingEvent.RestoreBackup) }
        }
    }
    private fun entryPage(): OnboardingPage = when {
        requestedUnlock.isNotEmpty() -> {
            pendingUnlock = requestedUnlock
            OnboardingPage.LOCAL_UNLOCK
        }
        requestedCreate -> OnboardingPage.CREATE
        else -> OnboardingPage.START
    }

    private fun leavingEntry(): Boolean {
        val page = local.value.page
        return (requestedCreate && page == OnboardingPage.CREATE) ||
            (requestedUnlock.isNotEmpty() && page == OnboardingPage.LOCAL_UNLOCK)
    }

    private fun read() = launch {
        val accountResult = accounts.list()
        checkCurrent()
        val nodeResult = nodes.list()
        checkCurrent()
        val accountRows = (accountResult as? ClientResult.Ready)?.value
        val nodeRows = (nodeResult as? ClientResult.Ready)?.value
        savedNodes = nodeRows ?: emptyList()
        local.update {
            it.copy(
                accounts = accountRows?.accounts?.map { row ->
                    AccountItem(
                        row.id, row.label ?: row.id,
                        when (row.state) {
                            "unlocked" -> uiText(R.string.ui_unlocked)
                            "locked" -> uiText(R.string.ui_locked)
                            else -> uiText(R.string.ui_account_unavailable)
                        },
                        row.unlocked
                    )
                }?.toPersistentList() ?: kotlinx.collections.immutable.persistentListOf(),
                nodes = savedNodes.map { row -> row.item() }.toPersistentList(),
                accountsLoaded = accountRows != null,
                nodesLoaded = nodeRows != null,
                notice = accountResult.notice() ?: nodeResult.notice()
            )
        }
    }
    private fun reviewAddress() {
        if (local.value.operatorKey.isBlank()) {
            local.update { it.copy(operatorKeyIssue = OperatorKeyIssue.REQUIRED) }
            return
        }
        val preview = DiscoveryOriginParser.preview(local.value.address)
        if (preview == null) {
            local.update { it.copy(notice = OnboardingNotice(uiText(R.string.ui_enter_an_https_address_without_a_username_or))) }
            return
        }
        candidate = null
        local.update { it.copy(page = OnboardingPage.CONFIRM, normalizedOrigin = preview.origin, identity = null, notice = null) }
    }
    private fun discover() {
        if (local.value.page != OnboardingPage.CONFIRM) return
        val address = local.value.normalizedOrigin ?: return
        val operatorKey = local.value.operatorKey
        if (operatorKey.isBlank()) return
        candidate = null
        local.update { it.copy(page = OnboardingPage.CHECKING, identity = null) }
        launch {
            val result = nodes.discover(address)
            checkCurrent()
            when (result) {
                is ClientResult.Ready -> {
                    if (result.value.node.publicKey != operatorKey) {
                        local.update {
                            it.copy(
                                page = OnboardingPage.ADDRESS,
                                identity = null,
                                operatorKeyIssue = OperatorKeyIssue.MISMATCH
                            )
                        }
                        return@launch
                    }
                    candidate = result.value
                    local.update {
                        it.copy(
                            page = if (result.value.node.keyChanged) OnboardingPage.IDENTITY_CHANGED else OnboardingPage.IDENTITY,
                            identity = IdentityFacts(
                                result.value.node.id,
                                result.value.node.url,
                                result.value.fingerprint,
                                result.value.node.keyChanged
                            ),
                            notice = if (result.value.node.keyChanged) {
                                OnboardingNotice(
                                    uiText(R.string.ui_the_saved_node_key_changed_confirmation_is_blocked),
                                    OnboardingNoticeKind.UNAVAILABLE
                                )
                            } else {
                                null
                            }
                        )
                    }
                }
                else -> {
                    local.update { it.copy(page = OnboardingPage.CONNECTION_FAILED) }
                    failure(result)
                }
            }
        }
    }
    private fun confirm() {
        val current = candidate ?: return
        if (current.node.keyChanged || current.node.publicKey != local.value.operatorKey) return
        local.update { it.copy(page = OnboardingPage.SAVING) }
        launch {
            val result = nodes.confirm(current, local.value.operatorKey)
            checkCurrent()
            when (result) {
                is ClientResult.Ready -> {
                    candidate = null
                    local.update { it.copy(page = OnboardingPage.SAVED, identity = null, nodesLoaded = false) }
                }
                else -> {
                    candidate = null
                    local.update { it.copy(page = OnboardingPage.SAVE_FAILED, identity = null) }
                    failure(result)
                }
            }
        }
    }
    private fun page(page: OnboardingPage) {
        if (page == OnboardingPage.PHRASE || page == OnboardingPage.CHALLENGE || page == OnboardingPage.CREATED) return
        val setup = when (page) {
            OnboardingPage.PAIR -> AccountSetupAction.PAIR
            else -> null
        }
        if (page != OnboardingPage.LOCAL_UNLOCK) pendingUnlock = null
        val selectable = setOf(
            OnboardingPage.START,
            OnboardingPage.CONTINUE,
            OnboardingPage.CONNECTION,
            OnboardingPage.ADDRESS,
            OnboardingPage.RESUME,
            OnboardingPage.REPAIR,
            OnboardingPage.WELCOME,
            OnboardingPage.CREATE,
            OnboardingPage.RECOVER
        )
        if (setup == null && page !in selectable && page != OnboardingPage.LOCAL_UNLOCK) return
        candidate = null
        local.update { it.copy(page = page, identity = null, normalizedOrigin = null, notice = null, recovery = null) }
        if (setup != null) {
            launch { failure(accounts.setup(setup)) }
        } else if (page in setOf(OnboardingPage.CONNECTION, OnboardingPage.WELCOME, OnboardingPage.RESUME, OnboardingPage.RECOVER)) read()
    }

    /** Draws one recovery word into the caller and never copies it into screen state. */
    fun drawPhrase(index: Int, block: (CharArray) -> Unit) {
        phrase?.draw(index, block)
    }

    private fun submitUnlock(action: OnboardingAction.SubmitUnlock) {
        val id = pendingUnlock
        val code = action.code.snapshot()
        action.code.clear()
        val incomplete = !AccountCode.complete(code)
        if (local.value.page != OnboardingPage.LOCAL_UNLOCK || id.isNullOrEmpty() || incomplete) {
            code.fill('\u0000')
            if (local.value.page == OnboardingPage.LOCAL_UNLOCK && incomplete) {
                local.update { it.copy(notice = OnboardingNotice(uiText(R.string.ui_enter_the_account_code))) }
            }
            return
        }
        launch {
            try {
                when (val result = accounts.unlock(id, code)) {
                    is ClientResult.Ready -> {
                        pendingUnlock = null
                        checkCurrent()
                        channel.send(OnboardingEvent.AccountSelected(id))
                    }
                    else -> {
                        failure(result, unlock = true)
                        val refusal = (result as? ClientResult.Failed)?.problem?.accountCodeRefusal(unlock = true)
                        if (refusal == AccountCodeRefusal.ATTEMPTS_EXHAUSTED || refusal == AccountCodeRefusal.DEVICE_UNLOCK_UNAVAILABLE) {
                            local.update { it.copy(recoverAccountId = id) }
                        }
                    }
                }
            } finally {
                code.fill('\u0000')
            }
        }
    }

    private fun beginRecover(accountId: String) {
        if (local.value.accounts.none { it.id == accountId }) return
        launch {
            when (val result = ceremony.beginRecover(accountId)) {
                is ClientResult.Ready -> {
                    checkCurrent()
                    pendingUnlock = null
                    local.update {
                        it.copy(
                            page = OnboardingPage.RECOVER,
                            recovery = RecoveryFacts(result.value.accountId, result.value.anchor),
                            recoverAccountId = null,
                            notice = null
                        )
                    }
                }
                else -> failure(result)
            }
        }
    }

    private fun confirmRecover(action: OnboardingAction.ConfirmRecover) {
        val facts = local.value.recovery
        val phraseChars = action.phrase.snapshot()
        val code = action.code.snapshot()
        action.phrase.clear()
        action.code.clear()
        if (facts == null || phraseChars.all { it.isWhitespace() } || !AccountCode.complete(code)) {
            phraseChars.fill('\u0000')
            code.fill('\u0000')
            if (facts != null) local.update { it.copy(notice = OnboardingNotice(uiText(R.string.ui_enter_the_phrase_and_code))) }
            return
        }
        launch {
            // One attempt per ceremony: the facts are dropped whatever the outcome.
            local.update { it.copy(recovery = null) }
            when (val result = ceremony.confirmRecover(phraseChars, code, facts.anchor)) {
                is ClientResult.Ready -> {
                    checkCurrent()
                    channel.send(OnboardingEvent.AccountSelected(result.value))
                }
                else -> failure(result)
            }
        }
    }

    private fun beginCreate() {
        val name = local.value.displayName.trim()
        if (name.isEmpty()) return
        val nodeId = local.value.selectedNode?.id ?: savedNodes.firstOrNull { it.isDefault }?.id
        launch {
            when (val pending = ceremony.beginCreate(name, nodeId)) {
                is ClientResult.Ready -> when (val shown = ceremony.showPhrase()) {
                    is ClientResult.Ready -> {
                        var adopted = false
                        try {
                            checkCurrent()
                            phrase?.close()
                            phrase = shown.value
                            adopted = true
                            local.update {
                                it.copy(page = OnboardingPage.PHRASE, phraseSlots = shown.value.length, notice = null)
                            }
                        } finally {
                            if (!adopted) shown.value.close()
                        }
                    }
                    else -> {
                        ceremony.cancel()
                        failure(shown)
                    }
                }
                else -> failure(pending)
            }
        }
    }

    private fun hidePhrase() {
        launch {
            phrase?.close()
            phrase = null
            local.update { it.copy(phraseSlots = 0) }
            when (val result = ceremony.challenge()) {
                is ClientResult.Ready -> {
                    checkCurrent()
                    local.update {
                        it.copy(page = OnboardingPage.CHALLENGE, challengePositions = result.value.toList().toPersistentList())
                    }
                }
                else -> failure(result)
            }
        }
    }

    private fun confirmChallenge(action: OnboardingAction.ConfirmChallenge) {
        launch {
            val words = Array(action.words.size) { action.words[it].snapshot() }
            val code = action.code.snapshot()
            try {
                val blank = words.size != 3 || words.any { it.isEmpty() || it.all(Char::isWhitespace) } || !AccountCode.complete(code)
                if (blank) {
                    val count = words.size
                    logConfirm("confirm.rejected.$count")
                    local.update {
                        it.copy(notice = OnboardingNotice(incompleteChallengeText(words, code)))
                    }
                } else {
                    when (val result = ceremony.confirm(words, code)) {
                        is ClientResult.Ready -> {
                            logConfirm("confirm.ready")
                            try {
                                checkCurrent()
                                local.update {
                                    it.copy(
                                        page = OnboardingPage.CREATED,
                                        createdAccountId = result.value,
                                        challengePositions = kotlinx.collections.immutable.persistentListOf(),
                                        notice = null
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                unknownOutcome()
                                throw cancelled
                            }
                        }
                        is ClientResult.Failed -> {
                            val code = result.problem.code
                            val name = result.problem.statusName ?: "none"
                            val outcome = result.problem.outcome
                            logConfirm("confirm.failed.$code.$name.$outcome")
                            record(result)
                        }
                        is ClientResult.Unavailable -> {
                            val reason = result.reason
                            logConfirm("confirm.unavailable.$reason")
                            record(result)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                logConfirm("confirm.cancelled")
                throw cancelled
            } catch (error: Exception) {
                val kind = error.javaClass.simpleName
                logConfirm("confirm.thrown.$kind")
                unknownOutcome()
            } finally {
                words.forEach { it.fill('\u0000') }
                code.fill('\u0000')
                action.words.forEach { it.clear() }
                action.code.clear()
            }
        }
    }

    /** A failed confirm writes its notice before the session fence. Success still has to pass the fence. */
    private fun record(result: ClientResult<*>) {
        local.update {
            it.copy(
                notice = result.notice() ?: OnboardingNotice(
                    uiText(R.string.ui_this_setup_operation_is_unavailable),
                    OnboardingNoticeKind.UNAVAILABLE
                )
            )
        }
    }

    private fun logConfirm(message: String) {
        confirmTrace.event(message)
    }

    private fun unknownOutcome() {
        local.update {
            it.copy(
                notice = OnboardingNotice(
                    uiText(R.string.ui_the_outcome_is_unknown_refresh_the_current_state),
                    OnboardingNoticeKind.UNKNOWN_OUTCOME
                )
            )
        }
    }

    /** Every completion is fenced by lifecycle generation as well as cancellation. */
    private fun launch(block: suspend () -> Unit) {
        work?.cancel()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(busy = true, notice = null) }
        work = viewModelScope.launch {
            try {
                operationGeneration = generation
                operationTicket = expected
                block()
                checkCurrent()
            } finally {
                if (expected == ticket) local.update { it.copy(busy = false) }
            }
        }
    }
    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (session.status.value.phase != ClientPhase.OPEN ||
            session.status.value.generation != operationGeneration || operationTicket != ticket
        ) {
            throw CancellationException(
                "retired session"
            )
        }
    }
    private suspend fun failure(result: ClientResult<*>, unlock: Boolean = false) {
        checkCurrent()
        local.update {
            it.copy(
                notice = result.notice(unlock) ?: OnboardingNotice(
                    uiText(
                        R.string.ui_this_setup_operation_is_unavailable
                    ),
                    OnboardingNoticeKind.UNAVAILABLE
                )
            )
        }
    }
}
private fun SavedNode.item() = NodeItem(id, url, keyChanged, isDefault, enabled)

/** Words missing: ask for everything; words present but a refused new code: say why. */
private fun incompleteChallengeText(words: Array<CharArray>, code: CharArray): UiText {
    val wordsReady = words.size == 3 && words.none { it.isEmpty() || it.all(Char::isWhitespace) }
    val refusal = if (wordsReady && code.isNotEmpty()) AccountCode.newCodeRefusal(code) else null
    return refusal?.let { codeText(it, null) } ?: uiText(R.string.ui_enter_the_recovery_words_and_the_account_code)
}

/** The account-code refusals the SDK reports by name, as sentences. */
internal fun codeText(refusal: AccountCodeRefusal, retryAfterSecs: Long?): UiText = when (refusal) {
    AccountCodeRefusal.NOT_SIX_DIGITS, AccountCodeRefusal.CODE_REQUIRED -> uiText(R.string.ui_code_not_six_digits)
    AccountCodeRefusal.TRIVIAL_CODE -> uiText(R.string.ui_code_trivial)
    AccountCodeRefusal.WRONG_CODE -> uiText(R.string.ui_code_wrong)
    AccountCodeRefusal.RETRY_LATER -> UiText.Quantity(R.plurals.ui_code_retry_later, (retryAfterSecs ?: 0L).coerceAtLeast(0L).toULong())
    AccountCodeRefusal.ATTEMPTS_EXHAUSTED -> uiText(R.string.ui_code_attempts_exhausted)
    AccountCodeRefusal.DEVICE_UNLOCK_UNAVAILABLE -> uiText(R.string.ui_code_device_unlock_unavailable)
}

private fun ClientResult<*>.notice(unlock: Boolean = false): OnboardingNotice? = when (this) {
    is ClientResult.Ready -> null
    is ClientResult.Unavailable -> OnboardingNotice(
        when (reason) {
            UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_setup_is_unavailable_no_phrase_or_account)
            else -> uiText(R.string.ui_this_action_is_not_available_in_this_version)
        },
        OnboardingNoticeKind.UNAVAILABLE
    )
    is ClientResult.Failed -> problem.accountCodeRefusal(unlock)?.let { refusal ->
        OnboardingNotice(codeText(refusal, problem.retryAfterSecs))
    } ?: run {
        val name = problem.statusName
        val check = problem.check
        OnboardingNotice(
            when {
                problem.outcome == ClientOutcome.UNKNOWN ->
                    uiText(R.string.ui_the_outcome_is_unknown_refresh_the_current_state)
                name != null && check != null -> uiText(
                    R.string.ui_the_request_did_not_complete_code_value_name_check,
                    UiText.Number(problem.code.toLong()),
                    UiText.Data(name),
                    UiText.Data(check)
                )
                name != null -> uiText(
                    R.string.ui_the_request_did_not_complete_code_value_name,
                    UiText.Number(problem.code.toLong()),
                    UiText.Data(name)
                )
                else -> uiText(
                    R.string.ui_the_request_did_not_complete_code_value_refresh,
                    UiText.Number(problem.code.toLong())
                )
            },
            if (problem.outcome == ClientOutcome.UNKNOWN) OnboardingNoticeKind.UNKNOWN_OUTCOME else OnboardingNoticeKind.ERROR
        )
    }
}
