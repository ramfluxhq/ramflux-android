// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.Effect
import org.ramflux.sdk.UnavailableCapability

class UnavailableRepositoryTest {
    @Test fun absentCapabilitiesReturnTypedUnavailableAndPreserveIdentity() = runTest {
        val context = RepositoryContext("account", "original-operation")
        val results = listOf(
            UnavailableLocalSearchRepository().search(LocalSearchQuery("account", "private-query")) to UnavailableCapability.LOCAL_SEARCH,
            UnavailableAgentConfigurationRepository().create(context, AgentConfiguration("name", "private-goal")) to UnavailableCapability.AGENT_CONFIGURATION,
            UnavailableStrangerReportingRepository().report(context, "contact", StrangerReportReason.SPAM) to UnavailableCapability.STRANGER_REPORTING,
        )
        results.forEach { (result, capability) ->
            assertTrue("unavailable must not yield fabricated data", result is CoreResult.Failure)
            val failure = result as CoreResult.Failure
            assertEquals(CoreError.Unavailable(capability), failure.error)
            assertEquals(Effect.NONE, failure.error.effect)
        }
        assertEquals("original-operation", (results[1].first as CoreResult.Failure).operationId)
    }
}
