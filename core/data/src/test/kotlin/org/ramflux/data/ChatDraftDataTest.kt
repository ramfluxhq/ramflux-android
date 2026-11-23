// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatDraftDataTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun draftsAreSealedRestoredAndErased() = runTest {
        val root = Files.createTempDirectory("drafts").toFile()
        try {
            val drafts = EncryptedChatDraftRepository(root, AesGcmDraftCipher { key })
            assertNull(drafts.load("a", "c"))
            assertTrue(drafts.save("a", "c-id", "unsent words") is ClientResult.Ready)
            val stored = root.walkTopDown().filter { it.isFile }.toList()
            assertEquals(1, stored.size)
            val bytes = stored.single().readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("unsent"))
            assertFalse(stored.single().relativeTo(root).path.contains("c-id"))
            assertEquals("unsent words", drafts.load("a", "c-id"))
            assertNull(drafts.load("a", "other"))
            // A fresh instance over the same files restores the draft, as after process death.
            assertEquals("unsent words", EncryptedChatDraftRepository(root, AesGcmDraftCipher { key }).load("a", "c-id"))
            drafts.save("a", "c-id", "  ")
            assertNull(drafts.load("a", "c-id"))
            drafts.save("a", "c", "again")
            drafts.clear("a", "c")
            assertNull(drafts.load("a", "c"))
            drafts.save("a", "c", "one")
            drafts.save("a", "d", "two")
            drafts.clearAccount("a")
            assertNull(drafts.load("a", "d"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun draftsBoundToAnotherConversationOrKeyAreDiscarded() = runTest {
        val root = Files.createTempDirectory("drafts").toFile()
        try {
            val drafts = EncryptedChatDraftRepository(root, AesGcmDraftCipher { key })
            drafts.save("a", "c", "secret")
            val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            assertNull(EncryptedChatDraftRepository(root, AesGcmDraftCipher { other }).load("a", "c"))
            assertTrue(root.walkTopDown().none { it.isFile })
            assertTrue(drafts.save("", "c", "x") is ClientResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }
}
