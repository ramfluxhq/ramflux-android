// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.encodeErasableBytes
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * The pairing invitation as QR modules. It is encoded from the erasable invitation characters through an
 * owned UTF-8 buffer (no String is ever made) and lent to the Kit for each draw; [close] erases the
 * modules. Nothing is logged and the matrix is never part of a state object.
 */
class InvitationQr private constructor(private var matrix: ByteMatrix?) : AutoCloseable {
    /** Modules per side; zero once closed. */
    val modules: Int get() = matrix?.width ?: 0

    /** Lends a reader over the modules to [block] while a frame is drawn; nothing is copied. */
    fun lend(block: ((Int, Int) -> Boolean) -> Unit) {
        val modules = matrix ?: return
        block { x, y -> modules.get(x, y).toInt() == 1 }
    }

    override fun close() {
        matrix?.clear(0)
        matrix = null
    }

    companion object {
        /**
         * Encodes [chars] at error correction level M, or L when M does not fit; null when even version 40
         * at L cannot hold them (the numbered text chunks remain).
         */
        fun encode(chars: CharArray): InvitationQr? {
            // Sized for the worst case up front, so the encoder never grows (and abandons) a copy.
            val bytes = ByteArray(chars.size * UTF8_MAX_BYTES_PER_CHAR)
            try {
                val out = ByteBuffer.wrap(bytes)
                val encoder = Charsets.UTF_8.newEncoder()
                val coded = encoder.encode(CharBuffer.wrap(chars), out, true)
                if (coded.isError || encoder.flush(out).isError) return null
                val utf8 = chars.any { it.code > ASCII_MAX }
                val matrix = encodeErasableBytes(bytes, out.position(), utf8, ErrorCorrectionLevel.M)
                    ?: encodeErasableBytes(bytes, out.position(), utf8, ErrorCorrectionLevel.L)
                return matrix?.let(::InvitationQr)
            } finally {
                bytes.fill(0)
            }
        }
    }
}

private const val ASCII_MAX = 0x7F
private const val UTF8_MAX_BYTES_PER_CHAR = 3
