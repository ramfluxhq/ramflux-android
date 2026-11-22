// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryDetailTest {
    private fun secretFailure(body: String) = ReplyCodec.decodeSecret(
        NativeReply(0, """{"code":31,"effects":"none","response":{"body":$body,"error":{"code":"UnlockRetryLater","reason_code":"unlock_retry_later"}}}""".toByteArray()),
        null,
    ) as CoreResult.Failure

    @Test fun aSecretRefusalKeepsOnlyThePublicWait() {
        val failure = secretFailure("""{"reason_code":"unlock_retry_later","retry_after_secs":240}""")
        assertEquals("UnlockRetryLater", failure.error.statusName)
        assertEquals(240L, failure.details!!["retry_after_secs"]!!.jsonPrimitive.long)
        assertEquals(setOf("retry_after_secs"), failure.details!!.keys)
    }

    @Test fun aRefusalWithoutAWaitHasNoDetails() {
        assertNull(secretFailure("""{"reason_code":"unlock_attempts_exhausted"}""").details)
        assertNull(secretFailure("null").details)
        assertNull(secretFailure("""{"retry_after_secs":"240"}""").details)
    }
}
