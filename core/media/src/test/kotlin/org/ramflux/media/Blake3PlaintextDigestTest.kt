// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import org.junit.Assert.assertEquals
import org.junit.Test

class Blake3PlaintextDigestTest {
    @Test fun matchesTheReferenceVectorForEmptyInputAndStreamsInPieces() {
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", Blake3PlaintextDigest().hex())
        val bytes = ByteArray(1_500_000) { (it % 251).toByte() }
        val whole = Blake3PlaintextDigest().apply { update(bytes, 0, bytes.size) }.hex()
        val pieces = Blake3PlaintextDigest().apply {
            update(bytes, 0, 524_288)
            update(bytes, 524_288, 524_288)
            update(bytes, 1_048_576, bytes.size - 1_048_576)
        }.hex()
        assertEquals(whole, pieces)
        assertEquals(64, whole.length)
    }
}
