// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.NodeSetupRepository
import org.ramflux.data.SavedNode
import org.ramflux.data.clientDebugEvent

data class StartupUi(
    val place: StartupPlace,
    val accountLabel: String,
    val accountId: String,
    val nodeUrl: String,
    /** The unlocked active account, when there is one. */
    val activeAccount: String? = null
)

@HiltViewModel
class StartupModel @Inject constructor(
    private val session: ClientSessionRepository,
    private val accounts: AccountAccessRepository,
    private val nodes: NodeSetupRepository
) : ViewModel() {
    private val facts = MutableStateFlow(StartupFacts())
    private val override = MutableStateFlow<StartupPlace?>(null)
    val ui: StateFlow<StartupUi> = combine(session.status, facts, override) { status, current, chosen ->
        val decided = decideStartup(status.phase, current)
        val place = if (decided == StartupPlace.SHELL || decided == StartupPlace.SPLASH) decided else chosen ?: decided
        StartupUi(place, lockedLabel(current), lockedId(current), nodeUrl(current.nodes), activeId(current))
    }.distinctUntilChanged().onEach {
        clientDebugEvent("startup.place:" + it.place)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupUi(StartupPlace.SPLASH, "", "", ""))

    init {
        viewModelScope.launch {
            session.status.collect { status ->
                if (status.phase != ClientPhase.OPEN) {
                    facts.value = StartupFacts()
                    return@collect
                }
                refresh()
            }
        }
        viewModelScope.launch {
            accounts.revisions.collect {
                if (session.status.value.phase == ClientPhase.OPEN) refresh()
            }
        }
    }

    private suspend fun refresh() {
        val accountResult = settledAccounts(accounts, session)
        val nodeResult = nodes.list()
        if (session.status.value.phase != ClientPhase.OPEN) return
        val listed = (accountResult as? ClientResult.Ready)?.value
        val saved = (nodeResult as? ClientResult.Ready)?.value
        facts.value = StartupFacts(listed, saved, failed = listed == null || saved == null)
    }

    fun showWelcome() {
        override.value = StartupPlace.WELCOME
    }

    fun showGateway() {
        override.value = StartupPlace.GATEWAY
    }
}

/**
 * The account list once the SDK has finished reopening accounts after a cold start. The client reports `open` before
 * that restore ends and publishes no event when it does, so the wait is bounded and shared by everything that needs
 * the unlocked account right after the session opens (the startup gate, the message driver).
 */
internal suspend fun settledAccounts(
    accounts: AccountAccessRepository,
    session: ClientSessionRepository
): ClientResult<org.ramflux.data.AccountCatalog> {
    var result = accounts.list()
    var waited = 0L
    while (restoring(result) && waited < RESTORE_WAIT_MS && session.status.value.phase == ClientPhase.OPEN) {
        delay(RESTORE_POLL_MS)
        waited += RESTORE_POLL_MS
        result = accounts.list()
    }
    return result
}

private fun restoring(result: ClientResult<org.ramflux.data.AccountCatalog>): Boolean =
    (result as? ClientResult.Ready)?.value?.accounts?.any { it.state == RESTORE_PENDING } == true

private const val RESTORE_POLL_MS = 250L
private const val RESTORE_WAIT_MS = 30_000L

private fun lockedLabel(facts: StartupFacts): String = facts.accounts?.accounts?.firstOrNull { !it.unlocked }?.label.orEmpty()

private fun activeId(facts: StartupFacts): String? =
    facts.accounts?.let { catalog -> catalog.accounts.firstOrNull { it.id == catalog.activeId && it.unlocked }?.id }

private fun lockedId(facts: StartupFacts): String = facts.accounts?.accounts?.firstOrNull { !it.unlocked }?.id.orEmpty()

private fun nodeUrl(nodes: List<SavedNode>?): String = nodes?.firstOrNull { it.enabled }?.url ?: nodes?.firstOrNull()?.url.orEmpty()
