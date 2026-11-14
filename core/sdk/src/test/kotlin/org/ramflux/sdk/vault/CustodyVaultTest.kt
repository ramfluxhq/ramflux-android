// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk.vault

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodyVaultTest {
    private class XorCipher : CustodyCipher {
        var invalidated = false
        override fun ensure(): CustodyFailure? = if (invalidated) CustodyFailure.KEY_INVALIDATED else null
        override fun seal(aad: ByteArray, plaintext: ByteArray): OpenedSecret {
            val sealed = ByteArray(12 + plaintext.size)
            for (index in plaintext.indices) sealed[12 + index] = (plaintext[index].toInt() xor 0x5a).toByte()
            plaintext.fill(0)
            aad.fill(0)
            return OpenedSecret(null, sealed)
        }
        override fun open(aad: ByteArray, sealed: ByteArray): OpenedSecret {
            aad.fill(0)
            if (invalidated) return OpenedSecret(CustodyFailure.KEY_INVALIDATED, null)
            if (sealed.size < 13) return OpenedSecret(CustodyFailure.CORRUPT, null)
            return OpenedSecret(null, ByteArray(sealed.size - 12) { (sealed[12 + it].toInt() xor 0x5a).toByte() })
        }
        override fun deleteKey(): CustodyFailure? {
            invalidated = true
            return null
        }
    }

    @Test fun storeLoadRotateDeleteAndInvalidationKeepPlaintextOffDisk() {
        val root = Files.createTempDirectory("custody-vault").toFile()
        try {
            val cipher = XorCipher()
            val files = DirectoryCustodyFiles(root)
            val vault = CustodyVault(files, cipher)
            assertEquals(0, vault.invoke(CustodyVault.PREFLIGHT, ByteArray(0), ByteArray(0), ByteArray(0)).status)
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.PREFLIGHT, ByteArray(0), byteArrayOf(1), ByteArray(0)).failure)
            val reference = "primary-one".toByteArray()
            val seed = ByteArray(32) { it.toByte() }
            val input = seed.copyOf()
            assertEquals(0, vault.invoke(CustodyVault.STORE_PRIMARY, reference, input, ByteArray(0)).status)
            assertTrue(input.all { it == 0.toByte() })
            val stored = root.listFiles()?.single { it.isFile && !it.name.endsWith(".partial") }
            assertTrue(stored != null && stored.readBytes().let { bytes -> !contains(bytes, seed) })
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.STORE_PRIMARY, reference, seed.copyOf(), ByteArray(0)).failure)
            val loaded = ByteArray(32)
            val load = vault.invoke(CustodyVault.LOAD_PRIMARY, reference, ByteArray(0), loaded)
            assertEquals(0, load.status)
            assertEquals(32, load.outputLength)
            assertTrue(loaded.contentEquals(seed))
            assertEquals(0, vault.invoke(CustodyVault.DELETE, reference, ByteArray(0), ByteArray(0)).status)
            assertEquals(CustodyFailure.MISSING, vault.invoke(CustodyVault.LOAD_PRIMARY, reference, ByteArray(0), loaded).failure)
            val rotated = ByteArray(32) { (it + 9).toByte() }
            assertEquals(0, vault.invoke(CustodyVault.STORE_PRIMARY, reference, rotated.copyOf(), ByteArray(0)).status)
            assertEquals(0, vault.invoke(CustodyVault.LOAD_PRIMARY, reference, ByteArray(0), loaded).status)
            assertTrue(loaded.contentEquals(rotated))
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.STORE_PRIMARY, "bad/name".toByteArray(), seed.copyOf(), ByteArray(0)).failure)
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.STORE_PRIMARY, byteArrayOf(0), seed.copyOf(), ByteArray(0)).failure)
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.STORE_PRIMARY, "short".toByteArray(), ByteArray(31), ByteArray(0)).failure)
            val blob = ByteArray(4096) { 7 }
            assertEquals(0, vault.invoke(CustodyVault.STORE_PROVISIONING, "provision".toByteArray(), blob.copyOf(), ByteArray(0)).status)
            assertEquals(CustodyFailure.CORRUPT, vault.invoke(CustodyVault.STORE_PROVISIONING, "empty-provision".toByteArray(), ByteArray(0), ByteArray(0)).failure)
            cipher.invalidated = true
            val invalidated = vault.invoke(CustodyVault.LOAD_PRIMARY, reference, ByteArray(0), loaded)
            assertEquals(CustodyFailure.KEY_INVALIDATED, invalidated.failure)
            assertEquals(2, invalidated.status)
            assertEquals(0, vault.invoke(CustodyVault.DELETE, "missing".toByteArray(), ByteArray(0), ByteArray(0)).status)
            val blocked = "blocked-delete".toByteArray()
            val blockedName = MessageDigest.getInstance("SHA-256").digest(blocked).joinToString("") { "%02x".format(it) }
            val blockedDir = File(root, blockedName)
            assertTrue(blockedDir.mkdirs())
            File(blockedDir, "child").writeBytes(byteArrayOf(1))
            val refused = vault.invoke(CustodyVault.DELETE, blocked, ByteArray(0), ByteArray(0))
            assertEquals(CustodyFailure.BUSY, refused.failure)
            assertEquals(5, refused.status)
            assertTrue(blockedDir.exists())
            assertFalse(files.delete(blockedName))
            val fresh = "ab".repeat(32)
            assertTrue(files.createDurable(fresh, byteArrayOf(1, 2, 3)))
            assertTrue(File(root, fresh).isFile)
            assertFalse(File(root, "$fresh.partial").exists())
            assertTrue(files.delete("cd".repeat(32)))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }
}
