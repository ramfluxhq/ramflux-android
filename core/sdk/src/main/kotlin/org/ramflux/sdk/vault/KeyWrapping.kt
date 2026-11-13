// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk.vault

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The second key requires a per-use CryptoObject authorization supplied by the native host. */
enum class WrappingKey(val kind: Int) { BACKGROUND(0), PRESENCE(1) }
enum class VaultError { NOT_HARDWARE_BACKED, KEY_MISSING, LOCKED, PRESENCE_REFUSED, AUTHENTICATION, FAILED }
sealed interface VaultResult<out T> {
    class Success<T>(val value: T) : VaultResult<T> { override fun toString() = "VaultSuccess(<redacted>)" }
    data class Failure(val error: VaultError) : VaultResult<Nothing>
}

/** Key bytes never leave AndroidKeyStore; no software provider or file fallback exists. */
class KeyWrapping {
    suspend fun prepareBackground(preferStrongBox: Boolean = true): VaultResult<Int> = withContext(Dispatchers.IO) {
        val level = KeystoreBridge.ensureKey(WrappingKey.BACKGROUND.kind, preferStrongBox)
        if (level < 0) return@withContext VaultResult.Failure(error(-level))
        val probe = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val expected = probe.copyOf()
        val aad = "ramflux.keystore.roundtrip.v1".toByteArray()
        try {
            val wrapped = unpack(KeystoreBridge.wrap(0, aad, probe))
            if (wrapped is VaultResult.Failure) return@withContext wrapped
            val payload = (wrapped as VaultResult.Success).value
            try {
                val unwrapped = unwrapBytes(WrappingKey.BACKGROUND, aad, payload)
                if (unwrapped is VaultResult.Failure) return@withContext unwrapped
                val clear = (unwrapped as VaultResult.Success).value
                try {
                    if (MessageDigest.isEqual(expected, clear)) VaultResult.Success(level)
                    else VaultResult.Failure(VaultError.AUTHENTICATION)
                } finally { clear.fill(0) }
            } finally { payload.fill(0) }
        } finally { probe.fill(0); expected.fill(0); aad.fill(0) }
    }

    suspend fun preparePresence(preferStrongBox: Boolean = true): VaultResult<Int> = withContext(Dispatchers.IO) {
        val level = KeystoreBridge.ensureKey(WrappingKey.PRESENCE.kind, preferStrongBox)
        if (level < 0) VaultResult.Failure(error(-level)) else VaultResult.Success(level)
    }

    /** Never call ensureKey during unwrap: loss of a key must not silently create a replacement. */
    suspend fun unwrap(key: WrappingKey, aad: ByteArray, payload: ByteArray): VaultResult<ByteArray> {
        var unreturned: ByteArray? = null
        try {
            val result = withContext(Dispatchers.IO) {
                unwrapBytes(key, aad, payload).also { if (it is VaultResult.Success) unreturned = it.value }
            }
            currentCoroutineContext().ensureActive()
            unreturned = null
            return result
        } finally { unreturned?.fill(0) }
    }

    /** Consumes and erases plaintext, including when hardware or authentication is unavailable. */
    suspend fun wrap(key: WrappingKey, aad: ByteArray, plaintext: ByteArray): VaultResult<ByteArray> =
        try { withContext(Dispatchers.IO) { unpack(KeystoreBridge.wrap(key.kind, aad, plaintext)) } }
        finally { plaintext.fill(0) }

    private fun unwrapBytes(key: WrappingKey, aad: ByteArray, payload: ByteArray): VaultResult<ByteArray> {
        if (payload.size !in 28..4124) return VaultResult.Failure(VaultError.FAILED)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        return try { unpack(KeystoreBridge.unwrap(key.kind, aad, iv, ciphertext)) }
        finally { iv.fill(0); ciphertext.fill(0) }
    }

    private fun unpack(bytes: ByteArray): VaultResult<ByteArray> = try {
        val status = bytes.firstOrNull()?.toInt()?.and(255) ?: 6
        if (status == 0) VaultResult.Success(bytes.copyOfRange(1, bytes.size))
        else VaultResult.Failure(error(status))
    } finally { bytes.fill(0) }

    private fun error(status: Int): VaultError = when (status) {
        1 -> VaultError.NOT_HARDWARE_BACKED
        2 -> VaultError.KEY_MISSING
        3 -> VaultError.LOCKED
        4 -> VaultError.PRESENCE_REFUSED
        5 -> VaultError.AUTHENTICATION
        else -> VaultError.FAILED
    }
}
