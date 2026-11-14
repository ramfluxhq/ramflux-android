// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk.vault

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** ABI statuses from the host custody table. Unknown values fail closed in the SDK. */
enum class CustodyFailure {
    MISSING, CORRUPT, PERMISSION, LOCKED, BUSY, UNAVAILABLE, KEY_INVALIDATED
}

class CustodyCall(val status: Int, val outputLength: Int, val failure: CustodyFailure?)

internal interface CustodyFiles {
    fun exists(name: String): Boolean
    fun read(name: String): ByteArray?
    fun createDurable(name: String, bytes: ByteArray): Boolean
    fun delete(name: String)
}

/** Seal and open wipe caller plaintext. The Keystore key itself never leaves hardware. */
internal interface CustodyCipher {
    fun ensure(): CustodyFailure?
    fun seal(aad: ByteArray, plaintext: ByteArray): OpenedSecret
    fun open(aad: ByteArray, sealed: ByteArray): OpenedSecret
    fun deleteKey(): CustodyFailure?
}

internal class OpenedSecret(val failure: CustodyFailure?, val bytes: ByteArray?)

/**
 * Device-local vault records. Store is create-only. Bytes on disk are sealed, never the seed.
 * Callback threads may overlap; one lock covers the record and the hardware key.
 */
internal class CustodyVault(private val files: CustodyFiles, private val cipher: CustodyCipher) {
    private val lock = Any()

    fun invoke(operation: Int, reference: ByteArray, input: ByteArray, output: ByteArray): CustodyCall {
        synchronized(lock) {
            try {
                if (reference.size > 128 || input.size > 4096 || output.size > 4096) return failed(CustodyFailure.CORRUPT)
                if (operation == PREFLIGHT) {
                    if (reference.isNotEmpty() || input.isNotEmpty()) return failed(CustodyFailure.CORRUPT)
                    return cipher.ensure().let { if (it == null) ok(0) else failed(it) }
                }
                val name = referenceName(reference) ?: return failed(CustodyFailure.CORRUPT)
                val expected = expectedLength(operation, input.size) ?: return failed(CustodyFailure.CORRUPT)
                if (operation == DELETE) {
                    files.delete(name)
                    return ok(0)
                }
                val aad = aad(reference)
                try {
                    return if (operation == STORE_PRIMARY || operation == STORE_BRANCH || operation == STORE_PROVISIONING) {
                        store(name, aad, input, expected)
                    } else {
                        load(name, aad, output, expected)
                    }
                } finally {
                    aad.fill(0)
                }
            } finally {
                input.fill(0)
            }
        }
    }

    private fun store(name: String, aad: ByteArray, input: ByteArray, expected: Int): CustodyCall {
        if (input.size != expected || files.exists(name)) return failed(CustodyFailure.CORRUPT)
        val ready = cipher.ensure()
        if (ready != null) return failed(ready)
        val sealed = cipher.seal(aad, input.copyOf())
        val bytes = sealed.bytes
        try {
            if (sealed.failure != null || bytes == null || bytes.isEmpty() || containsPlaintext(bytes, input)) {
                return failed(sealed.failure ?: CustodyFailure.CORRUPT)
            }
            return if (files.createDurable(name, bytes)) ok(0) else failed(CustodyFailure.CORRUPT)
        } finally {
            bytes?.fill(0)
        }
    }

    private fun load(name: String, aad: ByteArray, output: ByteArray, expected: Int): CustodyCall {
        val sealed = files.read(name) ?: return failed(CustodyFailure.MISSING)
        val opened = try { cipher.open(aad, sealed) } finally { sealed.fill(0) }
        val bytes = opened.bytes
        try {
            val failure = opened.failure
            if (failure != null) return failed(failure)
            if (bytes == null || bytes.isEmpty() || (expected != 0 && bytes.size != expected) || bytes.size > output.size) {
                return failed(CustodyFailure.CORRUPT)
            }
            bytes.copyInto(output)
            return ok(bytes.size)
        } finally {
            bytes?.fill(0)
        }
    }

    private fun expectedLength(operation: Int, input: Int): Int? = when (operation) {
        STORE_PRIMARY, LOAD_PRIMARY -> 32
        STORE_BRANCH, LOAD_BRANCH -> 64
        STORE_PROVISIONING -> if (input in 1..4096) input else null
        LOAD_PROVISIONING -> 0
        DELETE -> 0
        else -> null
    }

    private fun referenceName(reference: ByteArray): String? {
        if (reference.isEmpty() || reference.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars = try {
            val decoded = decoder.decode(ByteBuffer.wrap(reference))
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } catch (_: CharacterCodingException) {
            return null
        }
        return try {
            if (chars.isEmpty() || chars.any { it == '\u0000' || it == '/' || it == '\\' }) null
            else MessageDigest.getInstance("SHA-256").digest(reference).joinToString("") { "%02x".format(it) }
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun aad(reference: ByteArray): ByteArray {
        val domain = "ramflux.custody.v1".toByteArray()
        return ByteArray(domain.size + 1 + reference.size).also {
            domain.copyInto(it)
            it[domain.size] = 0
            reference.copyInto(it, domain.size + 1)
            domain.fill(0)
        }
    }

    private fun containsPlaintext(sealed: ByteArray, plain: ByteArray): Boolean {
        if (plain.isEmpty() || sealed.size < plain.size) return false
        for (start in 0..sealed.size - plain.size) {
            var same = true
            for (index in plain.indices) if (sealed[start + index] != plain[index]) same = false
            if (same) return true
        }
        return false
    }

    private fun ok(length: Int) = CustodyCall(0, length, null)
    private fun failed(failure: CustodyFailure) = CustodyCall(statusOf(failure), 0, failure)

    companion object {
        const val PREFLIGHT = 0
        const val STORE_PRIMARY = 1
        const val LOAD_PRIMARY = 2
        const val DELETE = 3
        const val STORE_BRANCH = 4
        const val LOAD_BRANCH = 5
        const val STORE_PROVISIONING = 6
        const val LOAD_PROVISIONING = 7

        fun statusOf(failure: CustodyFailure): Int = when (failure) {
            CustodyFailure.MISSING -> 1
            CustodyFailure.CORRUPT, CustodyFailure.KEY_INVALIDATED -> 2
            CustodyFailure.PERMISSION -> 3
            CustodyFailure.LOCKED -> 4
            CustodyFailure.BUSY -> 5
            CustodyFailure.UNAVAILABLE -> 6
        }
    }
}
