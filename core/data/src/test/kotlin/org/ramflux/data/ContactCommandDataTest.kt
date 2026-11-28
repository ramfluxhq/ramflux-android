// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCommandDataTest {
    @Test fun linkActionsSendTheLink() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/contact-links", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val cases = listOf(
                Triple(ContactOperation.REMOVE, "contact.remove", """{"link_id":"link-0","blocked":false}"""),
                Triple(ContactOperation.BLOCK_CONTACT, "contact.block", """{"link_id":"link-1","blocked":true}"""),
                Triple(ContactOperation.UNBLOCK, "contact.unblock", """{"link_id":"link-2","blocked":false}""")
            )
            for ((index, case) in cases.withIndex()) {
                val (operation, method, reply) = case
                abi.completionBody = Json.parseToJsonElement(reply).jsonObject
                assertTrue(repo.submit(ContactCommand("account", "link-op-$index", operation, "link-$index", "peer"), true) is ClientResult.Ready)
                val frame = abi.submitted.last()
                assertEquals(method, frame["method"]?.jsonPrimitive?.content)
                assertEquals("link-$index", frame["body"]?.jsonObject?.get("link_id")?.jsonPrimitive?.content)
                assertEquals(if (operation == ContactOperation.REMOVE) "me" else null, frame["body"]?.jsonObject?.get("scope")?.jsonPrimitive?.content)
            }
            // A reply for another link is not a confirmation.
            abi.completionBody = Json.parseToJsonElement("""{"link_id":"other","blocked":false}""").jsonObject
            assertTrue(repo.submit(ContactCommand("account", "link-op-x", ContactOperation.UNBLOCK, "link-x", "peer"), true) is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun realBridgeCommandsRequireConsentAndBindTargetAccountAndStableIdentity() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/contact-actions", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val denied = ContactCommand("account", "denied-id", ContactOperation.ACCEPT, "request", "peer")
            assertEquals(ClientOutcome.REFUSED, (repo.submit(denied, false) as ClientResult.Failed).problem.outcome)
            assertTrue(abi.submitted.isEmpty())
            val cases = listOf(ContactOperation.ADD to "contact.add", ContactOperation.ACCEPT to "contact.accept",
                ContactOperation.DECLINE to "contact.decline", ContactOperation.BLOCK to "contact.block")
            for ((index, case) in cases.withIndex()) {
                val (operation, method) = case
                val target = if (operation == ContactOperation.ADD) "peer" else "request-$index"
                val command = ContactCommand("account", "operation-$index", operation, target, "peer")
                abi.completionBody = if (operation == ContactOperation.ADD) Json.parseToJsonElement("""{"contact":{"principal_id":"peer","request_state":"pending"}}""").jsonObject
                else buildJsonObject {
                    put("request_id", target)
                put("principal_id", "peer")
                put("direction", "inbound")
                    put("state", when(operation) { ContactOperation.ACCEPT -> "accepted"
                ContactOperation.DECLINE -> "declined"
                else -> "blocked" })
                }
                assertTrue(repo.submit(command, true) is ClientResult.Ready)
                assertTrue(repo.submit(command, true) is ClientResult.Ready)
                val frame = abi.submitted.last()
                assertEquals(index + 1, abi.submitted.size)
                assertEquals(method, frame["method"]?.jsonPrimitive?.content)
                assertEquals("account", frame["account_id"]?.jsonPrimitive?.content)
                assertEquals(command.operationId, frame["operation_id"]?.jsonPrimitive?.content)
                assertEquals(target, frame["body"]?.jsonObject?.get(if (operation == ContactOperation.ADD) "principal_id" else "request_id")
                        ?.jsonPrimitive?.content)
                assertEquals(ContactAttemptPhase.CONFIRMED, repo.attempts("account").last().phase)
            }
            assertTrue(repo.attempts("other-account").isEmpty())
        } finally { client.close() }
    }
    @Test fun nativeRefusalAndUnknownCompletionRemainDistinctAndUnknownCannotBeResubmitted() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { pendingRequests = true }
        val client = (CoreClient.open(abi, "/test/contact-effects", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val command = ContactCommand("account", "denied-op", ContactOperation.DECLINE, "request", "peer")
            val denied = async { repo.submit(command, true) }
            runCurrent()
            abi.client(abi.handles.single()).operations.values.single().completion = """{"code":12,"effects":"refused"}""".toByteArray()
            advanceUntilIdle()
            assertEquals(ClientOutcome.REFUSED, (denied.await() as ClientResult.Failed).problem.outcome)
            assertEquals(ContactAttemptPhase.REFUSED, repo.attempts("account").single().phase)
            val uncertain = async { repo.submit(command.copy(operationId = "unknown-op"), true) }
            runCurrent()
            val core = abi.handles.single()
            abi.finish(core, abi.client(core).operations.keys.single(), 9)
            advanceUntilIdle()
            assertEquals(ClientOutcome.UNKNOWN, (uncertain.await() as ClientResult.Failed).problem.outcome)
            val count = abi.submitted.size
            assertEquals(ClientOutcome.UNKNOWN, (repo.submit(command.copy(operationId = "unknown-op"), true) as ClientResult.Failed).problem.outcome)
            assertEquals(ClientOutcome.NONE, (repo.submit(command.copy(operationId = "new-op"), true) as ClientResult.Failed).problem.outcome)
            assertEquals(count, abi.submitted.size)
            assertEquals("unknown-op", repo.attempts("account").last().operationId)
        } finally { client.close() }
    }
    @Test fun cancellationReleasesAdmittedNativeHandleAndRetainsOriginalUncertainIdentity() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { pendingRequests = true }
        val client = (CoreClient.open(abi, "/test/contact-cancel", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val command = ContactCommand("account", "cancelled-op", ContactOperation.BLOCK, "request", "peer")
            val pending = launch { repo.submit(command, true) }
            runCurrent()
            val core = abi.handles.single()
            val operation = abi.client(core).operations.keys.single()
            abi.admit(core, operation)
            pending.cancelAndJoin()
            assertTrue(operation in abi.cancelled)
            assertTrue(operation in abi.released)
            assertEquals(ContactAttemptPhase.UNKNOWN, repo.attempts("account").single().phase)
            assertEquals(command.operationId, repo.attempts("account").single().operationId)
        } finally { client.close() }
    }
}
