// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class InvitationQrTest {
    private fun modules(qr: InvitationQr): List<String> {
        val rows = MutableList(qr.modules) { StringBuilder() }
        qr.lend { dark -> for (y in 0 until qr.modules) for (x in 0 until qr.modules) rows[y].append(if (dark(x, y)) '#' else '.') }
        return rows.map(StringBuilder::toString)
    }

    private fun decode(qr: InvitationQr): String {
        val matrix = BitMatrix(qr.modules)
        qr.lend { dark -> for (y in 0 until qr.modules) for (x in 0 until qr.modules) if (dark(x, y)) matrix.set(x, y) }
        return Decoder().decode(matrix).text
    }

    private fun reference(text: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): List<String> {
        val matrix = Encoder.encode(text, level).matrix
        return (0 until matrix.height).map { y ->
            (0 until matrix.width).joinToString("") { x -> if (matrix.get(x, y).toInt() == 1) "#" else "." }
        }
    }

    /** Version 1-M, byte mode, mask chosen by penalty: the symbol ZXing's own String encoder produces. */
    @Test fun knownVector() {
        val qr = requireNotNull(InvitationQr.encode("rf-pair".toCharArray()))
        val expected = listOf(
            "#######...##..#######",
            "#.....#...#...#.....#",
            "#.###.#.#.###.#.###.#",
            "#.###.#.###...#.###.#",
            "#.###.#.#...#.#.###.#",
            "#.....#.####..#.....#",
            "#######.#.#.#.#######",
            "........##...........",
            "#.#####..###..#####..",
            "....##.##.######.##.#",
            ".#.##.#.....#.##...#.",
            ".......##.#######.#.#",
            ".##.#####...#..#.....",
            "........##..#...##.##",
            "#######..###.#...###.",
            "#.....#.#........####",
            "#.###.#.####.#.###.#.",
            "#.###.#.#..######.#..",
            "#.###.#.#...#.#...#..",
            "#.....#..######..##..",
            "#######.#.#.#..#...#."
        )
        assertEquals(expected, modules(qr))
        assertEquals(expected, reference("rf-pair"))
        assertEquals("rf-pair", decode(qr))
    }

    /** Invitation-sized ASCII tokens (several Reed-Solomon blocks, version information) match ZXing's encoder. */
    @Test fun matchesZxingEncoderAcrossVersions() {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_{}\":,"
        for (length in listOf(10, 60, 271, 800, 1_500, 2_200)) {
            val text = String(CharArray(length) { alphabet[(it * 7 + length) % alphabet.length] })
            val qr = requireNotNull(InvitationQr.encode(text.toCharArray()))
            assertEquals("length $length", reference(text), modules(qr))
            assertEquals(text, decode(qr))
        }
    }

    @Test fun nonAsciiCarriesTheUtf8EciAndLongTokensFallBackToLevelL() {
        val text = "Ramflux \u914d\u5bf9 \u2713 \ud83d\udd11"
        assertEquals(text, decode(requireNotNull(InvitationQr.encode(text.toCharArray()))))
        // 2,331 bytes is the byte capacity of 40-M; 2,500 fits only at L (2,953).
        val long = "x".repeat(2_500)
        val qr = requireNotNull(InvitationQr.encode(long.toCharArray()))
        assertEquals(reference(long, ErrorCorrectionLevel.L), modules(qr))
        assertNull("beyond version 40 the numbered text chunks remain", InvitationQr.encode(CharArray(2_954) { 'x' }))
    }

    @Test fun closeErasesTheModulesAndTheInputIsUntouched() {
        val chars = "{\"recipient_token\":\"secret\"}".toCharArray()
        val qr = requireNotNull(InvitationQr.encode(chars))
        assertTrue(qr.modules > 0)
        assertEquals("the caller owns and erases its characters", "{\"recipient_token\":\"secret\"}", String(chars))
        qr.close()
        assertEquals(0, qr.modules)
        var lent = false
        qr.lend { lent = true }
        assertFalse(lent)
    }

    /** Reported, not asserted: the encode runs once per invitation on the view model's thread. */
    @Test fun measuresAnInvitationSizedEncode() {
        val chars = CharArray(2_048) { 'a' + it % 26 }
        repeat(3) { InvitationQr.encode(chars)?.close() }
        val start = System.nanoTime()
        val qr = requireNotNull(InvitationQr.encode(chars))
        println("INVITATION_QR_ENCODE 2048 chars -> ${qr.modules} modules in ${(System.nanoTime() - start) / 1_000_000} ms")
        qr.close()
    }
}
