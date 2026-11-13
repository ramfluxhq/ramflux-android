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
    @Test fun actualSafetyReadBindsRootAndAccountWithoutRecordingVerification() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contact_identity_commitment":"root","safety_number":["1234","5678"],"fingerprint_hex":"abcdef"}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/contact-safety", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            assertEquals(ContactSafety("1234 5678", "abcdef"), (repo.safety("account", "root") as ClientResult.Ready).value)
            val request = abi.submitted.single()
            assertEquals("contact.safety_number", request["method"]?.jsonPrimitive?.content)
            assertEquals("account", request["account_id"]?.jsonPrimitive?.content)
            assertEquals("root", request["body"]?.jsonObject?.get("contact_identity_commitment")?.jsonPrimitive?.content)
            assertTrue(repo.safety("account", "different-root") is ClientResult.Failed)
            assertTrue(repo.availability(ContactOperation.VERIFY) is ClientResult.Unavailable)
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
            val cases = listOf(ContactOperation.ADD to "contact.add", ContactOperation.ACCEPT to "contact.request.accept",
                ContactOperation.DECLINE to "contact.request.decline", ContactOperation.BLOCK to "contact.request.block")
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
