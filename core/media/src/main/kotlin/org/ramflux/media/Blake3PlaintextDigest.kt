// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import org.bouncycastle.crypto.digests.Blake3Digest
import org.ramflux.data.PlaintextDigest

/** Unkeyed BLAKE3-256 as 64 lowercase hex characters, the digest `attachment.prepare.finish` checks. */
class Blake3PlaintextDigest : PlaintextDigest {
    private val digest = Blake3Digest()

    override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)

    override fun hex(): String {
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.joinToString("") { "%02x".format(it) }
    }
}
