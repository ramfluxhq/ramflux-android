// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class AgentConfigurationDataTest {
    @Test fun noPublishedAgentOperationIsReplacedByBotInstallation() = runTest(timeout = 5.seconds) {
        val repo = UnavailableAgentDirectoryRepository()
        for (capability in AgentCapability.entries) {
            assertEquals(ClientResult.Unavailable(ClientFeature.AGENTS, UnavailableReason.UNSUPPORTED_BY_CORE), repo.availability("a", capability))
        }
        assertTrue(repo.create("a", AgentDraft("Agent", "Read")) is ClientResult.Unavailable)
    }
    @Test fun unicodeScalarAndUtf8LimitsValidateLiteralDraftWithoutTrimmingOrInventingDefaults() {
        assertNull(AgentDraft("😀".repeat(40), "中".repeat(85)).issue)
        assertEquals(AgentDraftIssue.NAME_LIMIT, AgentDraft("😀".repeat(41), "read").issue)
        assertEquals(AgentDraftIssue.GOAL_LIMIT, AgentDraft("Name", "中".repeat(86)).issue)
        assertEquals(AgentDraftIssue.NAME_REQUIRED, AgentDraft(" ", "read").issue)
        assertEquals(AgentDraftIssue.GOAL_REQUIRED, AgentDraft("Name", "	").issue)
        assertFalse(AgentDraft("private", "text").toString().contains("private"))
    }
}
