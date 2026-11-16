// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import org.junit.Assert.assertEquals
import org.junit.Test

class LockedRoutesTest {
    @Test fun lockedStackDropsTheRestoredChatAndKeepsHome() {
        val stack = listOf("home", "chat")
        assertEquals(listOf("home"), routesWhileLocked(stack, shellReady = false) { it == "chat" })
    }

    @Test fun unlockedStackKeepsTheOpenChatSoBackReturnsHome() {
        val stack = listOf("home", "chat")
        assertEquals(stack, routesWhileLocked(stack, shellReady = true) { it == "chat" })
    }
}
