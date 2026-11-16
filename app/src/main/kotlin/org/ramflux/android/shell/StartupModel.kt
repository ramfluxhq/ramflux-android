// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.NodeSetupRepository
import org.ramflux.data.SavedNode

data class StartupUi(
    val place: StartupPlace,
    val accountLabel: String,
    val accountId: String,
    val nodeUrl: String
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
        StartupUi(place, lockedLabel(current), lockedId(current), nodeUrl(current.nodes))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupUi(StartupPlace.SPLASH, "", "", ""))

    init {
        viewModelScope.launch {
            session.status.collect { status ->
                if (status.phase != ClientPhase.OPEN) {
                    facts.value = StartupFacts()
                    return@collect
                }
                val accountResult = accounts.list()
                val nodeResult = nodes.list()
                if (session.status.value.phase != ClientPhase.OPEN) return@collect
                val listed = (accountResult as? ClientResult.Ready)?.value
                val saved = (nodeResult as? ClientResult.Ready)?.value
                facts.value = StartupFacts(listed, saved, failed = listed == null || saved == null)
            }
        }
    }

    fun showWelcome() {
        override.value = StartupPlace.WELCOME
    }

    fun showGateway() {
        override.value = StartupPlace.GATEWAY
    }
}

private fun lockedLabel(facts: StartupFacts): String = facts.accounts?.accounts?.firstOrNull { !it.unlocked }?.label.orEmpty()

private fun lockedId(facts: StartupFacts): String = facts.accounts?.accounts?.firstOrNull { !it.unlocked }?.id.orEmpty()

private fun nodeUrl(nodes: List<SavedNode>?): String = nodes?.firstOrNull { it.enabled }?.url ?: nodes?.firstOrNull()?.url.orEmpty()
