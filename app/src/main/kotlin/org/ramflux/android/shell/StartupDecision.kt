// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import org.ramflux.data.AccountCatalog
import org.ramflux.data.ClientPhase
import org.ramflux.data.SavedNode

enum class StartupPlace { SPLASH, GATEWAY, WELCOME, UNLOCK, SHELL }

data class StartupFacts(
    val accounts: AccountCatalog? = null,
    val nodes: List<SavedNode>? = null,
    val failed: Boolean = false
)

fun decideStartup(phase: ClientPhase, facts: StartupFacts): StartupPlace {
    if (phase == ClientPhase.FAILED || facts.failed) return StartupPlace.SHELL
    if (phase != ClientPhase.OPEN || facts.accounts == null || facts.nodes == null) return StartupPlace.SPLASH
    val catalog = facts.accounts
    if (catalog.accounts.any { it.unlocked }) return StartupPlace.SHELL
    if (catalog.accounts.isNotEmpty()) return StartupPlace.UNLOCK
    if (facts.nodes.isEmpty()) return StartupPlace.GATEWAY
    return StartupPlace.WELCOME
}
