// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
import kotlin.time.Duration.Companion.seconds

class HostTransportTest {
    @Test fun capacityRefusalIsFailedRatherThanUnknownOutcome() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/capacity", "a".repeat(64)) as CoreResult.Success).value
        val handle = abi.handles.single()
        repeat(64) {
            abi.submit(handle, """{"method":"node.list","sdk_api":"node","body":{},"deadline_ms":1}""".toByteArray())
        }
        val context = RepositoryContext(null, "same-key")
        val result = DefaultNodesRepository(SdkRepositoryTransport { client }).add(context, JsonObject(emptyMap()))
        val failure = result as CoreResult.Failure
        assertEquals(15, failure.error.code)
        assertEquals(Effect.NONE, failure.error.effect)
        assertTrue(PendingMutation(context, "node.add").disposition(result) is MutationDisposition.Failed)
        client.close()
    }

    @Test fun sameRepositoryResolvesReplacementClientAfterForeground() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        var current: CoreClient? = (CoreClient.open(abi, "/test/root", "a".repeat(64)) as CoreResult.Success).value
        val repository = DefaultNodesRepository(SdkRepositoryTransport { current })
        val context = RepositoryContext(null)
        assertTrue(repository.list(context, JsonObject(emptyMap())) is CoreResult.Success)
        current!!.close(); current = null
        assertTrue(repository.list(context, JsonObject(emptyMap())) is CoreResult.Failure)
        current = (CoreClient.open(abi, "/test/root", "a".repeat(64)) as CoreResult.Success).value
        assertTrue(repository.list(context, JsonObject(emptyMap())) is CoreResult.Success)
        assertEquals(1, abi.liveWriters)
        current!!.close()
    }
}
