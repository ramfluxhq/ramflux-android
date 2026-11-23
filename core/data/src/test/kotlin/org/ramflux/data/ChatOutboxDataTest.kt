// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatOutboxDataTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sends = listOf(
        QueuedSend("local:1", "op-1", "unsent words", 1_700_000_000L),
        QueuedSend("local:2", "op-2", "caption", 1_700_000_001L, definite = true, media = listOf(QueuedMedia("/outbox/stage1.png", "photo.png", "image/png")))
    )

    @Test fun queuedSendsAreSealedRestoredWithTheirOperationIdsAndErased() = runTest {
        val root = Files.createTempDirectory("outbox").toFile()
        try {
            val outbox = EncryptedChatOutboxRepository(root, AesGcmDraftCipher { key })
            assertTrue(outbox.load("a", "c-id").isEmpty())
            assertTrue(outbox.save("a", "c-id", sends) is ClientResult.Ready)
            val stored = root.walkTopDown().filter { it.isFile }.toList()
            assertEquals(1, stored.size)
            val bytes = String(stored.single().readBytes(), Charsets.ISO_8859_1)
            assertFalse(bytes.contains("unsent") || bytes.contains("op-1") || bytes.contains("photo"))
            assertFalse(stored.single().relativeTo(root).path.contains("c-id"))
            // A fresh instance over the same files restores the queue, as after process death or a lock.
            assertEquals(sends, EncryptedChatOutboxRepository(root, AesGcmDraftCipher { key }).load("a", "c-id"))
            assertTrue(outbox.load("a", "other").isEmpty())
            outbox.save("a", "c-id", sends.take(1))
            assertEquals(sends.take(1), outbox.load("a", "c-id"))
            outbox.save("a", "c-id", emptyList())
            assertTrue(root.walkTopDown().none { it.isFile })
            outbox.save("a", "c", sends)
            outbox.save("a", "d", sends)
            outbox.clearAccount("a")
            assertTrue(outbox.load("a", "d").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun anOutboxOfAnotherKeyIsDiscardedAndBadIdsAreRefused() = runTest {
        val root = Files.createTempDirectory("outbox").toFile()
        try {
            EncryptedChatOutboxRepository(root, AesGcmDraftCipher { key }).save("a", "c", sends)
            val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            assertTrue(EncryptedChatOutboxRepository(root, AesGcmDraftCipher { other }).load("a", "c").isEmpty())
            assertTrue(root.walkTopDown().none { it.isFile })
            assertTrue(EncryptedChatOutboxRepository(root, AesGcmDraftCipher { key }).save("", "c", sends) is ClientResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }
}
