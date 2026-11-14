// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk.vault

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Ciphertext lives in no-backup storage. The wrapping key is the non-exportable background Keystore key. */
internal class DirectoryCustodyFiles(private val root: File) : CustodyFiles {
    override fun exists(name: String): Boolean = file(name)?.exists() == true

    override fun read(name: String): ByteArray? {
        val target = file(name) ?: return null
        if (!target.isFile) return null
        val length = target.length()
        if (length !in 1..8192) return byteArrayOf()
        return try {
            target.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    override fun createDurable(name: String, bytes: ByteArray): Boolean {
        val target = file(name) ?: return false
        if (target.exists()) return false
        val partial = File(root, "$name.partial")
        return try {
            root.mkdirs()
            FileOutputStream(partial).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (target.exists() || !partial.renameTo(target)) {
                partial.delete()
                false
            } else if (!syncDirectory(root)) {
                target.delete()
                false
            } else {
                true
            }
        } catch (_: IOException) {
            partial.delete()
            false
        }
    }

    /** Missing records are already gone. An existing record that cannot be removed is a failure. */
    override fun delete(name: String): Boolean {
        val target = file(name) ?: return true
        if (!target.exists()) return true
        return target.delete()
    }

    private fun syncDirectory(dir: File): Boolean = try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (_: IOException) {
        false
    }

    private fun file(name: String): File? {
        if (name.length != 64 || name.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return File(root, name)
    }
}

internal object KeystoreCustodyCipher : CustodyCipher {
    override fun ensure(): CustodyFailure? = failure(KeystoreBridge.ensureKey(0, true).takeIf { it < 0 })

    override fun seal(aad: ByteArray, plaintext: ByteArray): OpenedSecret = try {
        val packed = KeystoreBridge.wrap(0, aad, plaintext)
        try {
            val status = packed.firstOrNull()?.toInt()?.and(255) ?: return OpenedSecret(CustodyFailure.UNAVAILABLE, null)
            if (status != 0 || packed.size < 29) OpenedSecret(fromStatus(status), null)
            else OpenedSecret(null, packed.copyOfRange(1, packed.size))
        } finally {
            packed.fill(0)
        }
    } finally {
        aad.fill(0)
    }

    override fun open(aad: ByteArray, sealed: ByteArray): OpenedSecret = try {
        if (sealed.size < 28) return OpenedSecret(CustodyFailure.CORRUPT, null)
        val iv = sealed.copyOfRange(0, 12)
        val ciphertext = sealed.copyOfRange(12, sealed.size)
        try {
            val packed = KeystoreBridge.unwrap(0, aad, iv, ciphertext)
            try {
                val status = packed.firstOrNull()?.toInt()?.and(255) ?: return OpenedSecret(CustodyFailure.UNAVAILABLE, null)
                if (status != 0) OpenedSecret(fromStatus(status), null)
                else OpenedSecret(null, packed.copyOfRange(1, packed.size))
            } finally {
                packed.fill(0)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    } finally {
        aad.fill(0)
    }

    override fun deleteKey(): CustodyFailure? = failure(KeystoreBridge.deleteKey(0).takeIf { it < 0 })

    private fun failure(negative: Int?): CustodyFailure? = negative?.let { fromStatus(-it) }

    private fun fromStatus(status: Int): CustodyFailure = when (status) {
        1 -> CustodyFailure.UNAVAILABLE
        2 -> CustodyFailure.KEY_INVALIDATED
        3 -> CustodyFailure.LOCKED
        4 -> CustodyFailure.PERMISSION
        5 -> CustodyFailure.CORRUPT
        else -> CustodyFailure.UNAVAILABLE
    }
}

/** Process-wide provider. JNI callbacks enter [invoke] on SDK worker threads. */
class HostCustodySession internal constructor(private val vault: CustodyVault) {
    companion object {
        const val PREFLIGHT = 0
        const val STORE_PRIMARY = 1
        const val LOAD_PRIMARY = 2
        const val DELETE = 3
        const val STORE_BRANCH = 4
        const val LOAD_BRANCH = 5
        const val STORE_PROVISIONING = 6
        const val LOAD_PROVISIONING = 7
    }
    fun invoke(operation: Int, reference: ByteArray, input: ByteArray, output: ByteArray, written: IntArray): Int {
        val call = vault.invoke(operation, reference, input, output)
        if (written.isNotEmpty()) written[0] = call.outputLength
        return call.status
    }

    fun call(operation: Int, reference: ByteArray, input: ByteArray, output: ByteArray): CustodyCall =
        vault.invoke(operation, reference, input, output)
}

object HostCustodyBridge {
    @Volatile private var session: HostCustodySession? = null

    fun install(context: Context): HostCustodySession {
        val installed = open(context)
        session = installed
        installNative(installed)
        return installed
    }

    fun open(context: Context): HostCustodySession {
        val root = File(context.applicationContext.noBackupFilesDir, "custody")
        return HostCustodySession(CustodyVault(DirectoryCustodyFiles(root), KeystoreCustodyCipher))
    }

    fun installed(): HostCustodySession? = session

    /** Drops the wrapping key. Ciphertext that remains then fails as key invalidated. */
    fun deleteWrappingKey(): CustodyFailure? = KeystoreCustodyCipher.deleteKey()

    private external fun installNative(provider: HostCustodySession)
}
