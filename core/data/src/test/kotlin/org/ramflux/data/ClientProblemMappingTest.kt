// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.Effect

class ClientProblemMappingTest {
    @Test fun custodySetupInvalidSurfacesAsAVisibleTypedProblem() {
        val problem = CoreError.InvalidArgument(Effect.NONE, "CustodySetupInvalid").appProblem()
        assertEquals(ClientFailureKind.FAILED, problem.kind)
        assertEquals(1, problem.code)
        assertEquals(ClientOutcome.NONE, problem.outcome)
        assertEquals("CustodySetupInvalid", problem.statusName)
        assertEquals(null, problem.check)
    }

    @Test fun wordMismatchKeepsThePositionAndNotAWord() {
        val problem = CoreError.Native(5, Effect.NONE, "CustodyConfirmationFailed", "word_8").appProblem()
        assertEquals("word_8", problem.check)
        assertEquals("CustodyConfirmationFailed", problem.statusName)
        assertFalse(problem.toString().contains("harbor"))
    }
}
