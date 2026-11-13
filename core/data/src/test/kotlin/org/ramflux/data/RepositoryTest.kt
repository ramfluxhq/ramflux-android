// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class RepositoryTest {
    @Test fun replyDoesNotAcceptStrangerOrRetryUnknownSend() = runBlocking {
        val transport = RecordingTransport()
        val repository = DefaultConversationsRepository(transport)
        val context = RepositoryContext("account", "same-operation", 1234)
        val body = SecretBody.fields { publicText("recipient", "stranger"); chars("text", charArrayOf('h', 'i')) }
        val result = repository.messageSubmit(context, body)
        assertEquals(listOf("message.submit"), transport.requests.map { it.method })
        val request = transport.requests.single()
        assertSame(body, request.body); assertEquals("same-operation", request.operationId)
        assertEquals("account", request.accountId); assertEquals(1234L, request.deadlineUnixMs)
        assertEquals(MutationDisposition.Reconcile("same-operation"), PendingMutation(context, request.method).disposition(result))
    }

    @Test fun nodeAddressRejectsCredentialsCleartextAndInvalidPorts() {
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com:0", "https://example.com:99999", "https://example.com#secret", "example.com\n").forEach {
            assertEquals("must reject $it", NodeAddress.Invalid, NodeAddress.parse(it))
        }
        assertEquals(NodeAddress.Valid("https://example.com"), NodeAddress.parse("example.com"))
    }

    private class RecordingTransport : RepositoryTransport {
        val requests = mutableListOf<SecretRequest>()
        override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE))
        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> {
            requests.add(request); request.body.close()
            return CoreResult.Failure(CoreError.Native(10, Effect.UNKNOWN), request.operationId)
        }
        override fun updates(filter: SubscriptionFilter) = emptyFlow<CoreUpdate>()
    }
}
