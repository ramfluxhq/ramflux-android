// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.Effect

class AccountCodeTest {
    @Test fun aNewCodeFollowsTheSdkAdmissionRules() {
        assertEquals(AccountCodeRefusal.NOT_SIX_DIGITS, AccountCode.newCodeRefusal("12345".toCharArray()))
        assertEquals(AccountCodeRefusal.NOT_SIX_DIGITS, AccountCode.newCodeRefusal("12345a".toCharArray()))
        assertEquals(AccountCodeRefusal.NOT_SIX_DIGITS, AccountCode.newCodeRefusal("١٢٣٤٥٦".toCharArray()))
        for (trivial in listOf("000000", "999999", "012345", "456789", "987654", "543210")) {
            assertEquals(trivial, AccountCodeRefusal.TRIVIAL_CODE, AccountCode.newCodeRefusal(trivial.toCharArray()))
        }
        assertNull(AccountCode.newCodeRefusal("482915".toCharArray()))
        assertNull(AccountCode.newCodeRefusal("112233".toCharArray()))
        assertTrue(AccountCode.complete("482915".toCharArray()))
        assertFalse(AccountCode.complete("4829150".toCharArray()))
    }

    @Test fun sdkRefusalsMapToTypedOutcomes() {
        fun problem(name: String?, check: String? = null) = ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = name, check = check)
        assertEquals(AccountCodeRefusal.TRIVIAL_CODE, problem("trivial_code").accountCodeRefusal())
        assertEquals(AccountCodeRefusal.RETRY_LATER, problem("UnlockRetryLater").accountCodeRefusal())
        assertEquals(AccountCodeRefusal.ATTEMPTS_EXHAUSTED, problem(null, "unlock_attempts_exhausted").accountCodeRefusal())
        assertEquals(AccountCodeRefusal.DEVICE_UNLOCK_UNAVAILABLE, problem("DeviceUnlockUnavailable").accountCodeRefusal())
        assertEquals("a wrong code is named only by wrong_passphrase", AccountCodeRefusal.WRONG_CODE, problem("AuthFailed", "wrong_passphrase").accountCodeRefusal())
        assertNull("a status alone is never a wrong code", problem("AuthFailed").accountCodeRefusal())
        assertNull(problem("ValidationFailed").accountCodeRefusal())
        assertEquals(AccountCodeRefusal.REAUTH_REQUIRED, problem("AuthFailed", "reauth_required").accountCodeRefusal())
        assertNull(problem("CustodyConfirmationFailed").accountCodeRefusal())
    }

    @Test fun theSdkWaitIsCarriedIntoTheProblem() {
        val failure = CoreResult.Failure(CoreError.Native(31, Effect.NONE, "UnlockRetryLater", "unlock_retry_later"), null,
            JsonObject(mapOf("retry_after_secs" to JsonPrimitive(120))))
        val problem = failure.appProblem()
        assertEquals(120L, problem.retryAfterSecs)
        assertEquals(AccountCodeRefusal.RETRY_LATER, problem.accountCodeRefusal())
    }
}
