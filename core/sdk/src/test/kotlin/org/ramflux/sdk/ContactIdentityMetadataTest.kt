// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ContactIdentityMetadataTest {
    private fun status(json: String) =
        (ContactIdentityMetadataReader.status(SecretReply(SecretBytes.take(json.toByteArray()))) as CoreResult.Success).value

    @Test fun statusCarriesTheRepliesAiFlagOnlyWhenItNamesOne() {
        val withFlag = status("""{"identity_state":"changed","pending_anchor":"n","previous_anchor":"p","ai_flag":true}""")
        assertEquals(true, withFlag.aiFlag)
        assertEquals("n", withFlag.pendingAnchor)
        assertNull(status("""{"identity_state":"changed","pending_anchor":"n"}""").aiFlag)
        assertEquals(false, status("""{"identity_state":"changed","pending_anchor":"n","ai_flag":false}""").aiFlag)
    }
}
