// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Unsent composer text per conversation. Drafts survive backgrounding and process death only as
 * ciphertext in app-private no-backup storage; the key never leaves the platform key store. Nothing is
 * written to saved instance state.
 */
interface ChatDraftRepository {
    suspend fun load(account: String, conversation: String): String?
    suspend fun save(account: String, conversation: String, text: String): ClientResult<Unit>
    suspend fun clear(account: String, conversation: String)
    /** Removes every stored draft of [account], for logout and account removal. */
    suspend fun clearAccount(account: String)
}

/** Seals and opens one draft. The production key lives in the Android Keystore. */
interface DraftCipher {
    fun seal(plain: ByteArray, associated: ByteArray): ByteArray
    fun open(sealed: ByteArray, associated: ByteArray): ByteArray
}

class AesGcmDraftCipher(private val key: () -> SecretKey) : DraftCipher {
    override fun seal(plain: ByteArray, associated: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associated)
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray, associated: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.updateAAD(associated)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val ALIAS = "org.ramflux.chat.drafts.v1"

        /** A non-exportable AES-256 key in the Android Keystore, created on first use. */
        fun keystore(): AesGcmDraftCipher = AesGcmDraftCipher {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setRandomizedEncryptionRequired(true)
                            .build()
                    )
                }.generateKey()
        }
    }
}

/** Files are named by a digest of the account and conversation ids, so no id appears on disk. */
class EncryptedChatDraftRepository(private val root: File, private val cipher: DraftCipher) : ChatDraftRepository {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "chat-drafts"), AesGcmDraftCipher.keystore())

    private val guard = Mutex()

    override suspend fun load(account: String, conversation: String): String? = io {
        val file = file(account, conversation) ?: return@io null
        if (!file.isFile) return@io null
        val plain = try {
            cipher.open(file.readBytes(), associated(account, conversation))
        } catch (_: Exception) {
            // A draft that no longer opens (key replaced, file damaged) is discarded, never shown.
            file.delete()
            return@io null
        }
        try {
            String(plain, Charsets.UTF_8).takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun save(account: String, conversation: String, text: String): ClientResult<Unit> = io {
        val file = file(account, conversation) ?: return@io ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        if (text.isBlank()) {
            file.delete()
            return@io ClientResult.Ready(Unit)
        }
        val plain = text.take(MAX_CHARS).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.seal(plain, associated(account, conversation))
            file.parentFile?.mkdirs()
            val staging = File(file.parentFile, file.name + ".tmp")
            staging.writeBytes(sealed)
            if (!staging.renameTo(file)) {
                staging.delete()
                return@io ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
            }
            ClientResult.Ready(Unit)
        } catch (_: Exception) {
            ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun clear(account: String, conversation: String) {
        io { file(account, conversation)?.delete() }
    }

    override suspend fun clearAccount(account: String) {
        io { directory(account)?.deleteRecursively() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { guard.withLock { block() } }

    private fun directory(account: String): File? = if (!usable(account)) null else File(root, digest(account))

    private fun file(account: String, conversation: String): File? {
        if (!usable(conversation)) return null
        val folder = directory(account) ?: return null
        return File(folder, digest(account + "\u0000" + conversation))
    }

    private fun associated(account: String, conversation: String) = (account + "\u0000" + conversation).toByteArray(Charsets.UTF_8)

    private fun usable(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CHARS = 65536
    }
}
