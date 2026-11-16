// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.ClientResult
import org.ramflux.data.DefaultMessageHistoryRepository
import org.ramflux.sdk.CoreClient
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.JniCore
import org.ramflux.sdk.vault.HostCustodyBridge

/**
 * Repository calls reach the packaged C ABI and fail closed on an empty root.
 * A positive send needs the vault create ceremony plus a node conversation, which this process cannot open.
 */
@RunWith(AndroidJUnit4::class)
class MessageHistoryAbiTest {
    @Test fun freshRootReadAndSendFailInTheSdkInsteadOfStayingUnavailable(): Unit = runBlocking {
        withTimeout(15_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = Files.createTempDirectory(context.filesDir.toPath(), "message-history-").toFile()
            try {
                val native = JniCore.load()
                HostCustodyBridge.install(context)
                val opened = CoreClient.open(native, root.absolutePath, BuildConfig.MESSAGE_DESCRIPTOR)
                assertTrue(opened is CoreResult.Success)
                val client = (opened as CoreResult.Success).value
                try {
                    val repo = DefaultMessageHistoryRepository(client)
                    val results = listOf(
                        repo.read("account", "conversation"),
                        repo.send("account", "conversation", "ping", "op-send"),
                        repo.reply("account", "conversation", "message", "ping", "op-reply"),
                        repo.edit("account", "conversation", "message", "ping", 0, "op-edit"),
                        repo.react("account", "conversation", "message", "+", false, "op-react"),
                        repo.delete("account", "conversation", "message", org.ramflux.data.MessageDeleteScope.LOCAL, "op-me"),
                        repo.delete(
                            "account",
                            "conversation",
                            "message",
                            org.ramflux.data.MessageDeleteScope.EVERYONE,
                            "op-all"
                        )
                    )
                    for (result in results) {
                        val failed = result as? ClientResult.Failed
                        assertNotNull(failed)
                        assertEquals(1, failed?.problem?.code)
                        assertEquals(org.ramflux.data.ClientFailureKind.FAILED, failed?.problem?.kind)
                    }
                } finally {
                    client.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
