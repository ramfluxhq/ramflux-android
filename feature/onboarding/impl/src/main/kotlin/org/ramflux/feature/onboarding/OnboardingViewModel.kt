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
    private val session: ClientSessionRepository
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
    private var candidate: DiscoveredNode? = null
    private var savedNodes = emptyList<SavedNode>()
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            work?.cancel()
            ticket++
            candidate = null
            savedNodes = emptyList()
            local.value = OnboardingState()
            if (phase == ClientPhase.OPEN) onAction(OnboardingAction.Refresh)
        }
    }
    fun onAction(action: OnboardingAction) {
        if (action == OnboardingAction.Back) {
            work?.cancel()
            ticket++
            candidate = null
            if (local.value.page == OnboardingPage.START) {
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
        if (session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        when (action) {
            OnboardingAction.Refresh -> read()
            is OnboardingAction.Address -> {
                candidate = null
                local.update { it.copy(address = action.value.take(2048), identity = null, normalizedOrigin = null, notice = null) }
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
            is OnboardingAction.SelectAccount -> launch {
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
            OnboardingAction.Back -> Unit
        }
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
        candidate = null
        local.update { it.copy(page = OnboardingPage.CHECKING, identity = null) }
        launch {
            val result = nodes.discover(address)
            checkCurrent()
            when (result) {
                is ClientResult.Ready -> {
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
        if (current.node.keyChanged) return
        local.update { it.copy(page = OnboardingPage.SAVING) }
        launch {
            val result = nodes.confirm(current)
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
        val setup = when (page) {
            OnboardingPage.CREATE -> AccountSetupAction.CREATE
            OnboardingPage.RECOVER -> AccountSetupAction.RECOVER
            OnboardingPage.PAIR -> AccountSetupAction.PAIR
            OnboardingPage.PHRASE -> AccountSetupAction.SHOW_PHRASE
            OnboardingPage.CHALLENGE -> AccountSetupAction.CHECK_PHRASE
            OnboardingPage.LOCAL_UNLOCK -> AccountSetupAction.LOCAL_UNLOCK
            else -> null
        }
        val selectable = setOf(
            OnboardingPage.START,
            OnboardingPage.CONTINUE,
            OnboardingPage.CONNECTION,
            OnboardingPage.ADDRESS,
            OnboardingPage.RESUME,
            OnboardingPage.REPAIR,
            OnboardingPage.WELCOME
        )
        if (setup == null && page !in selectable) return
        candidate = null
        local.update { it.copy(page = page, identity = null, normalizedOrigin = null, notice = null) }
        if (setup != null) {
            launch { failure(accounts.setup(setup)) }
        } else if (page in setOf(OnboardingPage.CONNECTION, OnboardingPage.WELCOME, OnboardingPage.RESUME)) read()
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
    private var operationGeneration = 0L
    private var operationTicket = 0L
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
    private suspend fun failure(result: ClientResult<*>) {
        checkCurrent()
        local.update {
            it.copy(
                notice = result.notice() ?: OnboardingNotice(
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
private fun ClientResult<*>.notice(): OnboardingNotice? = when (this) {
    is ClientResult.Ready -> null
    is ClientResult.Unavailable -> OnboardingNotice(
        when (reason) {
            UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_setup_is_unavailable_no_phrase_or_account)
            else -> uiText(R.string.ui_this_action_is_not_available_in_this_version)
        },
        OnboardingNoticeKind.UNAVAILABLE
    )
    is ClientResult.Failed -> OnboardingNotice(
        if (problem.outcome == ClientOutcome.UNKNOWN) {
            uiText(R.string.ui_the_outcome_is_unknown_refresh_the_current_state)
        } else {
            uiText(R.string.ui_the_request_did_not_complete_code_value_refresh, UiText.Number(problem.code.toLong()))
        },
        if (problem.outcome == ClientOutcome.UNKNOWN) OnboardingNoticeKind.UNKNOWN_OUTCOME else OnboardingNoticeKind.ERROR
    )
}
