// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vectors are the SDK's own: `golden_ascii_ordered_dm_scope` and `malformed_roots_and_namespace_are_refused`
 * in `crates/ramflux-protocol/src/message/dm_scope.rs` (core commit 3dafe1f8).
 */
class DirectConversationsTest {
    @Test fun theProductGoldenVectorDerivesInEitherOrder() {
        assertEquals(ID, DirectConversationId.derive(A, B))
        assertEquals(ID, DirectConversationId.derive(B, A))
        // Ordered by the encoded ASCII anchors, not by the decoded bytes.
        assertTrue(A < B)
    }

    @Test fun malformedOrEqualRootsAddressNoConversation() {
        assertNull(DirectConversationId.derive(A, A))
        for (bad in MALFORMED) {
            assertFalse(bad, DirectConversationId.anchor(bad))
            assertNull(bad, DirectConversationId.derive(A, bad))
        }
        assertTrue(DirectConversationId.anchor(DirectConversationId.derive(A, B)!!.removePrefix(DirectConversationId.PREFIX)))
    }

    companion object {
        const val A = "0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NA"
        const val B = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val ID = "dm:kMnoa9jDpfKsJjopJ4NV7ScM259q2dzi2X3NpgG6bFQ"
        val MALFORMED = listOf(
            "",
            "AAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB",
            " AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\u00e9"
        )
    }
}
