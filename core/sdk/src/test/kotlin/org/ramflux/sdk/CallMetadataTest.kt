// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class CallMetadataTest {
    @Test fun signalingProjectionKeepsExactVersionAndNeverMaterializesMediaKeys() {
        val raw = """{"calls":[{"call_id":"call","conversation_id":"c","peer_id":"peer","direction":"incoming","state":"incoming_ringing","seq":18446744073709551615,"media_generation":"18446744073709551615","created_at":1700000000,"answered_at":null,"ended_at":null,"end_reason":null,"media_key":"must-not-copy"}]}""".toByteArray()
        val row = (CallMetadataReader.list(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value.single()
        assertEquals(ULong.MAX_VALUE, row.sequence); assertEquals(ULong.MAX_VALUE, row.generation)
        assertFalse(row.toString().contains("must-not-copy")); assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun malformedOrDuplicateRowsFailInsteadOfAnInventedEmptyHistory() {
        val raw = """{"calls":[{"call_id":"c","created_at":-1}]}""".toByteArray()
        assertTrue(CallMetadataReader.list(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
        assertTrue(raw.all { it == 0.toByte() })
    }
}
