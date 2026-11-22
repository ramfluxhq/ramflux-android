// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class MessageHistoryDataTest {
    @Test fun mixedNativeReplyReversesNewestFirstAndReadsTheGenerationOneModel() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = page(tombstone(12_000), call(11_000), message("one", 10_000, """"text":"approved"""")) }
        val client = open(abi, "/test/mixed")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val rows = (repo.read("a", "c") as ClientResult.Ready).value
            assertEquals(listOf("one", "call", "deleted"), rows.map { it.id })
            assertTrue(rows[1] is CallSummary)
            assertEquals(12L, rows[2].createdAt)
            assertNull((rows[2] as MessageSummary).text)
            val request = abi.submitted.single()
            assertEquals("message.read", request["method"]?.jsonPrimitive?.content)
            assertEquals("a", request["account_id"]?.jsonPrimitive?.content)
            val body = request["body"]?.jsonObject
            assertEquals("c", body?.get("conversation_id")?.jsonPrimitive?.content)
            assertEquals(true, body?.get("read_model")?.jsonPrimitive?.boolean)
            assertEquals(100, body?.get("limit")?.jsonPrimitive?.int)
            abi.completionBody = Json.parseToJsonElement(
                """{"conversations":[{"id":"c","title":"Actual title","unread_count":0,""" +
                    """"is_unread":false,"archived":false,"hidden":false}],"next_cursor":null}""",
            ).jsonObject
            assertEquals("Actual title", (repo.header("a", "c") as ClientResult.Ready).value.title)
            assertTrue(repo.read("a", "other") is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun sendEditReactAndDeleteUseConversationMethodsAndRetainUncertainSends() = runTest(timeout = 5.seconds) {
        var partial = false
        val abi = FakeAbi().apply {
            completionBody = page(message("m", 10, """"text":"approved","is_own":true,"revision":4"""))
            onSubmitted = { frame ->
                val method = frame["method"]?.jsonPrimitive?.content
                val operation = frame["operation_id"]?.jsonPrimitive?.content
                completionBody = when {
                    method == "conversation.delete" -> delivery("local")
                    method == "conversation.send" && operation == "op-fail" -> delivery("failed")
                    method == "conversation.send" && partial -> partialDelivery()
                    else -> delivery("submitted")
                }
            }
        }
        val client = open(abi, "/test/send")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            assertTrue(repo.sendAvailability() is ClientResult.Ready)
            val sent = repo.send("a", "c", "hello", "op-1") as ClientResult.Ready
            assertEquals("m", sent.value.messageId)
            val send = abi.submitted.last()
            assertEquals("conversation.send", send["method"]?.jsonPrimitive?.content)
            assertEquals("op-1", send["operation_id"]?.jsonPrimitive?.content)
            assertEquals("hello", send["body"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
            val before = sends(abi)
            val again = repo.send("a", "c", "hello", "op-2") as ClientResult.Ready
            assertEquals(before + 1, sends(abi))
            assertEquals("op-2", abi.submitted.last()["operation_id"]?.jsonPrimitive?.content)
            assertEquals("m", again.value.messageId)
            val cached = repo.send("a", "c", "hello", "op-1") as ClientResult.Ready
            assertEquals(before + 1, sends(abi))
            assertEquals(sent.value.messageId, cached.value.messageId)
            val mismatched = repo.send("a", "c", "other", "op-1") as ClientResult.Failed
            assertEquals(1, mismatched.problem.code)
            assertEquals(before + 1, sends(abi))
            assertTrue(repo.send("a", "c", "next", "op-3") is ClientResult.Ready)
            assertEquals(before + 2, sends(abi))
            val edited = repo.edit("a", "c", "m", "next-text", 4, "edit-1")
            assertTrue(edited is ClientResult.Ready)
            val edit = abi.submitted.last()
            assertEquals("conversation.edit", edit["method"]?.jsonPrimitive?.content)
            assertEquals(4, edit["body"]?.jsonObject?.get("expected_revision")?.jsonPrimitive?.int)
            assertTrue(repo.react("a", "c", "m", ChatReaction.ACK, false, "react-1") is ClientResult.Ready)
            val react = abi.submitted.last()["body"]?.jsonObject
            assertEquals(ChatReaction.ACK, react?.get("token")?.jsonPrimitive?.content)
            assertNull(react?.get("remove"))
            assertTrue(repo.delete("a", "c", "m", MessageDeleteScope.EVERYONE, "delete-1") is ClientResult.Ready)
            assertEquals("everyone", abi.submitted.last()["body"]?.jsonObject?.get("scope")?.jsonPrimitive?.content)
            val repliesBefore = replies(abi)
            assertTrue(repo.reply("a", "c", "m", "same", "reply-1") is ClientResult.Ready)
            assertTrue(repo.reply("a", "c", "m", "same", "reply-2") is ClientResult.Ready)
            assertEquals(repliesBefore + 2, replies(abi))
            assertTrue(repo.reply("a", "c", "m", "same", "reply-1") is ClientResult.Ready)
            assertEquals(repliesBefore + 2, replies(abi))
            assertEquals("reply-2", abi.submitted.last {
                it["method"]?.jsonPrimitive?.content == "conversation.reply"
            }["operation_id"]?.jsonPrimitive?.content)
            val failed = repo.send("a", "c", "nope", "op-fail") as ClientResult.Failed
            assertEquals(ClientOutcome.NONE, failed.problem.outcome)
            val failedSends = sends(abi)
            assertTrue(repo.send("a", "c", "nope", "op-fail") is ClientResult.Failed)
            assertEquals(failedSends, sends(abi))
            partial = true
            val unknown = repo.send("a", "c", "split", "op-4") as ClientResult.Failed
            assertEquals(ClientOutcome.UNKNOWN, unknown.problem.outcome)
            val retried = sends(abi)
            val retry = repo.send("a", "c", "split", "op-4") as ClientResult.Failed
            assertEquals(ClientOutcome.UNKNOWN, retry.problem.outcome)
            assertEquals(retried + 1, sends(abi))
            assertEquals("op-4", abi.submitted.last()["operation_id"]?.jsonPrimitive?.content)
            assertTrue(repo.send("a", "c", "split", "op-5") is ClientResult.Failed)
            assertEquals(retried + 2, sends(abi))
            assertEquals("op-5", abi.submitted.last()["operation_id"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }

    @Test fun unknownKindDoesNotFailTheThreadAndTypingReachesFocusAndSubmit() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            completionBody = page(
                """{"id":"future","conversation_id":"c","kind":"future-kind","hlc_ms":11,"text":"hidden-kind"}""",
                message("one", 10, """"text":"approved""""),
            )
            onSubmitted = { frame ->
                if (frame["method"]?.jsonPrimitive?.content != "message.read") completionBody = buildJsonObject { }
            }
        }
        val client = open(abi, "/test/typing")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val rows = (repo.read("a", "c") as ClientResult.Ready).value
            assertEquals("approved", (rows[0] as MessageSummary).text)
            assertTrue((rows[1] as MessageSummary).placeholder)
            assertNull((rows[1] as MessageSummary).text)
            assertTrue(repo.setTyping("a", "c", true) is ClientResult.Ready)
            val methods = abi.submitted.map { it["method"]?.jsonPrimitive?.content }
            assertEquals(listOf("message.read", "message.focus", "message.submit"), methods)
            val submit = abi.submitted.last()["body"]?.jsonObject
            val typing = submit?.get("send")?.jsonObject?.get("content")?.jsonObject?.get("typing")?.jsonObject
            assertEquals(true, typing?.get("active")?.jsonPrimitive?.boolean)
            assertTrue(repo.setTyping("a", "c", false) is ClientResult.Ready)
            assertEquals("message.submit", abi.submitted[abi.submitted.lastIndex - 1]["method"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, abi.submitted.last()["body"]?.jsonObject?.get("conversation_id"))
        } finally { client.close() }
    }

    @Test fun malformedReadDoesNotReturnInventedEmptyHistory() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = open(abi, "/test/history-invalid")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            assertTrue(repo.read("a", "c") is ClientResult.Failed)
            val count = abi.submitted.size
            assertTrue(repo.send("", "c", "hello", "op") is ClientResult.Failed)
            assertEquals(count, abi.submitted.size)
        } finally { client.close() }
    }

    private suspend fun open(abi: FakeAbi, root: String) =
        (CoreClient.open(abi, root, "a".repeat(64)) as CoreResult.Success).value

    private fun sends(abi: FakeAbi) = abi.submitted.count { it["method"]?.jsonPrimitive?.content == "conversation.send" }

    private fun replies(abi: FakeAbi) = abi.submitted.count { it["method"]?.jsonPrimitive?.content == "conversation.reply" }

    private fun page(vararg rows: String) = Json.parseToJsonElement(
        """{"schema":"ramflux.message.read_model.v1","conversation_id":"c","messages":[${rows.joinToString(",")}]}""",
    ).jsonObject

    private fun message(id: String, hlc: Long, extra: String) =
        """{"id":"$id","conversation_id":"c","kind":"message","state":"content",""" +
            """"hlc_ms":$hlc,"device_sequence":"00000000000000000001",$extra}"""

    private fun call(hlc: Long) = """{"id":"call","conversation_id":"c","kind":"call","hlc_ms":$hlc}"""

    private fun tombstone(hlc: Long) =
        """{"id":"deleted","conversation_id":"c","kind":"message","state":"tombstone","is_own":true,"hlc_ms":$hlc,"text":"must-hide"}"""

    private fun delivery(state: String) = Json.parseToJsonElement(
        """{"message_id":"m","conversation_id":"c","target_message_id":"m","delivery_state":"$state"}""",
    ).jsonObject

    private fun partialDelivery() = Json.parseToJsonElement(
        """{"message_id":"m","conversation_id":"c","delivery_state":"submitted","deliveries":[{"state":"submitted"},{"state":"failed"}]}""",
    ).jsonObject
}
