// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.sdk.*
import org.ramflux.sdk.vault.HostCustodyBridge

/** Exercises the installed shipping-profile libraries, not a Robolectric/native fixture. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeSmokeTest {
    @Test fun packagedJniLoadsAndRealSdkListsNodes(): Unit = runBlocking {
        withTimeout(15_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = Files.createTempDirectory(context.filesDir.toPath(), "native-runtime-").toFile()
            try {
                val native = JniCore.load()
                HostCustodyBridge.install(context)
                val result = CoreClient.open(native, root.absolutePath, BuildConfig.MESSAGE_DESCRIPTOR)
                assertTrue("Real SDK negotiation/open must succeed: $result", result is CoreResult.Success)
                val client = (result as CoreResult.Success).value
                try {
                    val reply = client.execute(
                        CoreRequest(
                            method = "node.list",
                            sdkApi = "node",
                            accountId = null,
                            operationId = null,
                            body = JsonObject(emptyMap()),
                            deadlineUnixMs = System.currentTimeMillis() + 10_000
                        )
                    )
                    assertTrue("Real SDK node.list must succeed: $reply", reply is CoreResult.Success)
                    val body = (reply as CoreResult.Success).value["body"] as? JsonObject
                    val nodes = body?.get("nodes") as? JsonArray
                    assertNotNull("SDK must return the real node-list shape", nodes)
                    assertEquals("Fresh private root has no node profiles", 0, nodes?.size)
                    Log.i("RamfluxNativeSmoke", "NATIVE_SDK_CALL_OK node.list")
                } finally {
                    assertTrue("Native close must succeed", client.close() is CoreResult.Success)
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
