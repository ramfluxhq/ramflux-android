// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

// This file sits in ZXing's encoder package on purpose: ZXing's public encoder only accepts a String, an
// immutable copy that cannot be erased. Symbol placement and mask scoring (MatrixUtil, MaskUtil) are
// package-private, so the byte-mode segment, padding and Reed-Solomon interleaving are built here in
// buffers this file owns and erases, and only the placement is delegated. The pinned ZXing version is
// exact (gradle/libs.versions.toml); InvitationQrTest checks the result against ZXing's own encoder and
// decoder, so a change of these package-private signatures fails the build instead of the user.
package com.google.zxing.qrcode.encoder

import com.google.zxing.common.BitArray
import com.google.zxing.common.CharacterSetECI
import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.decoder.Version

/**
 * Encodes [count] bytes of [bytes] as one QR byte-mode segment at [ecLevel] and returns the module
 * matrix (1 dark, 0 light), or null when they do not fit version 40. With [utf8] the segment is preceded
 * by the UTF-8 ECI so readers do not decode the bytes as ISO-8859-1. Version and mask are chosen as
 * ZXing's encoder chooses them. Every buffer created here that holds the payload is erased before
 * returning; the caller owns and erases [bytes] and the returned matrix (`clear(0)`).
 */
internal fun encodeErasableBytes(bytes: ByteArray, count: Int, utf8: Boolean, ecLevel: ErrorCorrectionLevel): ByteMatrix? {
    require(count in 0..bytes.size)
    val headerBits = (if (utf8) ECI_HEADER_BITS else 0) + MODE_BITS
    fun bitsNeeded(version: Version) = headerBits + Mode.BYTE.getCharacterCountBits(version) + count * Byte.SIZE_BITS
    val provisional = smallestVersion(bitsNeeded(Version.getVersionForNumber(1)), ecLevel) ?: return null
    val version = smallestVersion(bitsNeeded(provisional), ecLevel) ?: return null
    val blocks = version.getECBlocksForLevel(ecLevel)
    val totalBytes = version.totalCodewords
    val dataBytes = totalBytes - blocks.totalECCodewords
    if (bitsNeeded(version) > dataBytes * Byte.SIZE_BITS) return null

    val data = IntArray(dataBytes)
    val final = BitArray(totalBytes * Byte.SIZE_BITS)
    try {
        val writer = CodewordWriter(data)
        if (utf8) {
            writer.put(Mode.ECI.bits, MODE_BITS)
            writer.put(CharacterSetECI.UTF8.value, ECI_VALUE_BITS)
        }
        writer.put(Mode.BYTE.bits, MODE_BITS)
        writer.put(count, Mode.BYTE.getCharacterCountBits(version))
        for (index in 0 until count) writer.put(bytes[index].toInt() and BYTE_MASK, Byte.SIZE_BITS)
        writer.terminate()
        interleave(data, blocks, final)
        val dimension = version.dimensionForVersion
        val matrix = ByteMatrix(dimension, dimension)
        var best = 0
        var lowest = Int.MAX_VALUE
        for (mask in 0 until QRCode.NUM_MASK_PATTERNS) {
            MatrixUtil.buildMatrix(final, ecLevel, version, mask, matrix)
            val penalty = MaskUtil.applyMaskPenaltyRule1(matrix) + MaskUtil.applyMaskPenaltyRule2(matrix) +
                MaskUtil.applyMaskPenaltyRule3(matrix) + MaskUtil.applyMaskPenaltyRule4(matrix)
            if (penalty < lowest) {
                lowest = penalty
                best = mask
            }
        }
        MatrixUtil.buildMatrix(final, ecLevel, version, best, matrix)
        return matrix
    } finally {
        data.fill(0)
        final.clear()
    }
}

private fun smallestVersion(bits: Int, ecLevel: ErrorCorrectionLevel): Version? = (1..MAX_VERSION).asSequence()
    .map(Version::getVersionForNumber)
    .firstOrNull { version ->
        val dataBytes = version.totalCodewords - version.getECBlocksForLevel(ecLevel).totalECCodewords
        dataBytes >= (bits + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
    }

/** Writes bits most significant first into codewords preallocated to the data capacity, so nothing grows. */
private class CodewordWriter(private val codewords: IntArray) {
    private var bit = 0

    fun put(value: Int, width: Int) {
        for (shift in width - 1 downTo 0) {
            if ((value ushr shift) and 1 == 1) {
                val index = bit / Byte.SIZE_BITS
                codewords[index] = codewords[index] or (1 shl (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS))
            }
            bit++
        }
    }

    /** The terminator and bit padding are zero bits already; the remaining codewords alternate 0xEC, 0x11. */
    fun terminate() {
        val capacity = codewords.size * Byte.SIZE_BITS
        bit = minOf(capacity, bit + TERMINATOR_BITS)
        var index = (bit + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        var pad = 0
        while (index < codewords.size) {
            codewords[index++] = PADDING[pad++ % PADDING.size]
        }
    }
}

/** Splits [data] into the version's Reed-Solomon blocks and writes data then error codewords interleaved. */
private fun interleave(data: IntArray, blocks: Version.ECBlocks, out: BitArray) {
    val ecPerBlock = blocks.ecCodewordsPerBlock
    val sizes = blocks.ecBlocks.flatMap { group -> List(group.count) { group.dataCodewords } }
    val encoder = ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256)
    val coded = ArrayList<IntArray>(sizes.size)
    try {
        var offset = 0
        for (size in sizes) {
            val block = IntArray(size + ecPerBlock)
            data.copyInto(block, 0, offset, offset + size)
            encoder.encode(block, ecPerBlock)
            coded += block
            offset += size
        }
        var bit = 0
        fun put(codeword: Int) {
            for (shift in Byte.SIZE_BITS - 1 downTo 0) {
                if ((codeword ushr shift) and 1 == 1) out.set(bit)
                bit++
            }
        }
        val longest = sizes.max()
        for (index in 0 until longest) {
            sizes.forEachIndexed { block, size -> if (index < size) put(coded[block][index]) }
        }
        for (index in 0 until ecPerBlock) {
            sizes.forEachIndexed { block, size -> put(coded[block][size + index]) }
        }
    } finally {
        coded.forEach { it.fill(0) }
    }
}

private const val MODE_BITS = 4
private const val ECI_VALUE_BITS = 8
private const val ECI_HEADER_BITS = MODE_BITS + ECI_VALUE_BITS
private const val TERMINATOR_BITS = 4
private const val BYTE_MASK = 0xFF
private const val MAX_VERSION = 40
private val PADDING = intArrayOf(0xEC, 0x11)
