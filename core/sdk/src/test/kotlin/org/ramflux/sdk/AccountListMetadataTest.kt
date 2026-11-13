// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test

class AccountListMetadataTest {
    @Test fun onlyAllowlistedDisplayFieldsAreDecodedAndReplyBytesAreWiped() {
        val raw = """{"account_details":[{"id":"a","label":"Work","state":"locked","seed":"do-not-copy"}],"active_account_id":null,"phrase":"do-not-copy"}""".toByteArray()
        val reply = SecretReply(SecretBytes.take(raw))
        val result = AccountListMetadataReader.read(reply) as CoreResult.Success
        assertEquals(listOf(AccountListEntry("a", "Work", "locked")), result.value.accounts)
        assertTrue(raw.all { it == 0.toByte() })
        assertTrue(AccountListMetadataReader.read(reply) is CoreResult.Failure)
    }
    @Test fun malformedOrDuplicateMetadataFailsClosedAndWipesInput() {
        for (body in listOf("{}", """{"account_details":{},"active_account_id":null}""", """{"account_details":[],"account_details":[]}""")) {
            val raw = body.toByteArray()
            assertTrue(AccountListMetadataReader.read(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
}
