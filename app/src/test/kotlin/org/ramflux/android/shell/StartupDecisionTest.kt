// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ramflux.data.AccountCatalog
import org.ramflux.data.AccountSummary
import org.ramflux.data.ClientPhase
import org.ramflux.data.SavedNode

class StartupDecisionTest {
    @Test fun openingStaysOnTheSplash() {
        assertEquals(StartupPlace.SPLASH, decideStartup(ClientPhase.OPENING, StartupFacts()))
        assertEquals(StartupPlace.SPLASH, decideStartup(ClientPhase.CLOSED, StartupFacts()))
        assertEquals(StartupPlace.SPLASH, decideStartup(ClientPhase.OPEN, StartupFacts()))
    }

    @Test fun failedReadOpensTheShell() {
        assertEquals(StartupPlace.SHELL, decideStartup(ClientPhase.FAILED, StartupFacts()))
        assertEquals(StartupPlace.SHELL, decideStartup(ClientPhase.OPEN, StartupFacts(failed = true)))
    }

    @Test fun noGatewayOpensTheGateway() {
        val facts = StartupFacts(AccountCatalog(emptyList(), null), emptyList())
        assertEquals(StartupPlace.GATEWAY, decideStartup(ClientPhase.OPEN, facts))
    }

    @Test fun savedNodeWithoutAccountOpensWelcome() {
        val facts = StartupFacts(AccountCatalog(emptyList(), null), listOf(node()))
        assertEquals(StartupPlace.WELCOME, decideStartup(ClientPhase.OPEN, facts))
    }

    @Test fun unlockedAccountOpensTheShell() {
        val facts = StartupFacts(AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a"), emptyList())
        assertEquals(StartupPlace.SHELL, decideStartup(ClientPhase.OPEN, facts))
    }

    @Test fun lockedAccountOpensUnlock() {
        val facts = StartupFacts(AccountCatalog(listOf(AccountSummary("a", "Work", false, "locked")), null), listOf(node()))
        assertEquals(StartupPlace.UNLOCK, decideStartup(ClientPhase.OPEN, facts))
    }

    private fun node() = SavedNode("n", "https://node.example", "key", true, false, true)
}
