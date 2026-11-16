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

/** Repository calls reach the packaged C ABI. A fresh root has no conversation to send on. */
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
                    val read = repo.read("account", "conversation")
                    val sent = repo.send("account", "conversation", "ping", "op-1")
                    assertTrue(read is ClientResult.Failed)
                    assertTrue(sent is ClientResult.Failed)
                    assertFalse(read is ClientResult.Unavailable)
                    assertFalse(sent is ClientResult.Unavailable)
                } finally {
                    client.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
