// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class AgentDirectoryDataTest {
    /** The fake ABI answering the agent methods as `bus/agent_methods.rs` does (ProtoJSON, lowerCamelCase). */
    private class Oracle : NativeCore {
        val abi = FakeAbi()
        var agents = (0 until 20).map { "anchor-$it" }
        var pending: String? = null
        var refuse: String? = null

        /** The account root `account.status` reports; null answers without one, as an older SDK would. */
        var ownRoot: String? = null
        private var failing = mutableSetOf<Long>()
        private val delegate = abi
        override fun negotiate() = delegate.negotiate()
        override fun open(config: ByteArray) = delegate.open(config)
        override fun close(core: Long) = delegate.close(core)
        override fun releaseClosed(core: Long) = delegate.releaseClosed(core)
        override fun operationCancel(core: Long, operation: Long) = delegate.operationCancel(core, operation)
        override fun operationRelease(core: Long, operation: Long) = delegate.operationRelease(core, operation)
        override fun eventReset(core: Long, generation: Long) = delegate.eventReset(core, generation)
        override fun eventPoll(core: Long) = delegate.eventPoll(core)
        override fun operationPoll(core: Long, operation: Long): NativeReply {
            val code = refuse
            if (operation in failing && code != null) {
                return NativeReply(0, """{"code":1,"effects":"none","response":{"ok":false,"error":{"code":"$code"}}}""".toByteArray())
            }
            return delegate.operationPoll(core, operation)
        }
        fun record(anchor: String, name: String = "Agent $anchor", lifecycle: String = "U") = buildJsonObject {
            put("agentAnchor", anchor); put("custodianAnchor", "owner")
            put("persona", buildJsonObject { put("name", name); put("avatar", "orbit"); put("responsibility", "Research") })
            put("lifecycle", lifecycle); put("observation", ""); put("cleanup", if (lifecycle == "X") "node_record_retained" else "")
            put("access", buildJsonObject { put("dm", "owner"); put("groupInviters", "owner"); put("allowedHumans", buildJsonArray {}) })
            put("role", "named")
        }
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val frame = Json.parseToJsonElement(request.decodeToString()).jsonObject
            val method = frame.getValue("method").jsonPrimitive.content
            val body = frame["body"]?.jsonObject ?: JsonObject(emptyMap())
            abi.completionBody = when (method) {
                "agent.list" -> {
                    val start = body["cursor"]?.jsonPrimitive?.content?.removePrefix("c")?.toInt() ?: 0
                    val limit = body.getValue("limit").jsonPrimitive.int
                    val end = minOf(start + limit, agents.size)
                    buildJsonObject {
                        put("page", buildJsonObject {
                            put("records", buildJsonArray { agents.subList(start, end).forEach { add(record(it)) } })
                            if (end < agents.size) put("next", "c$end")
                            put("journalCursor", "7")
                        })
                    }
                }
                "agent.watch" -> buildJsonObject {
                    put("page", buildJsonObject { put("records", buildJsonArray { add(record("anchor-1", "Renamed")) }); put("journalCursor", "8") })
                }
                "agent.get" -> buildJsonObject { put("record", record(body.getValue("agent_anchor").jsonPrimitive.content)) }
                "agent.persona.edit" -> buildJsonObject {
                    put("record", record(body.getValue("agent_anchor").jsonPrimitive.content, body.getValue("persona").jsonObject.getValue("name").jsonPrimitive.content))
                }
                "agent.delete" -> buildJsonObject { put("record", record(body.getValue("agent_anchor").jsonPrimitive.content, lifecycle = "X")) }
                "agent.operation.get" -> buildJsonObject {
                    put("custody", buildJsonObject {
                        put("requestId", "request-1"); put("state", "authorization_pending"); put("agentAnchor", "new-anchor"); put("expiresAt", "2000000000")
                        put("requestJson", Base64.getEncoder().encodeToString("""{"display_name":"${pending ?: ""}","request_id":"request-1"}""".toByteArray()))
                    })
                }
                "account.status" -> ownRoot?.let { root ->
                    buildJsonObject {
                        put("local_account_id", "account"); put("principal_id", "own"); put("principal_commitment", root)
                        put("device_id", "own-device"); put("local_device_authorized", true); put("authority_blocked", false)
                    }
                } ?: buildJsonObject {}
                "conversation.directory" -> Json.parseToJsonElement(
                    """{"conversations":[{"id":"group-conv","kind":"group","unread_count":0,"archived":false,"hidden":false,"is_unread":false},""" +
                        """{"id":"dm:agent","kind":"direct","unread_count":0,"archived":false,"hidden":false,"is_unread":false,"direct_route":{"recipient_principal_commitment":"anchor-3"}}]}"""
                ).jsonObject
                else -> buildJsonObject {}
            }
            val reply = delegate.submit(core, request, ownership)
            if (refuse != null) failing.add(reply.handle)
            return reply
        }
    }

    @Test fun agentListPagesForwardAndServesRememberedPreviousCursors() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/agent-list", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultAgentDirectoryRepository(SdkRepositoryTransport { client })
            val first = (repo.agentPage("account") as ClientResult.Ready).value
            assertEquals(8, first.page.rows.size)
            assertEquals("7", first.journal)
            assertNull(first.page.previous)
            assertEquals(AgentLifecycle.ACTIVE, first.page.rows.first().lifecycle)
            assertEquals("Research", first.page.rows.first().persona.responsibility)
            assertEquals("owner", first.page.rows.first().access.dm)
            val second = (repo.agentPage("account", first.page.next) as ClientResult.Ready).value
            val third = (repo.agentPage("account", second.page.next) as ClientResult.Ready).value
            assertNull(third.page.next)
            assertEquals(20, (first.page.rows + second.page.rows + third.page.rows).map { it.anchor }.distinct().size)
            assertEquals(first.page.next, third.page.previous)
            // The first page's start is served as a cursor too, so a window can read it again.
            assertEquals(first.page.rows, (repo.agentPage("account", second.page.previous) as ClientResult.Ready).value.page.rows)
            val listed = oracle.abi.submitted.filter { it["method"]?.jsonPrimitive?.content == "agent.list" }
            assertNull(listed.first()["operation_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("agent", listed.first()["sdk_api"]?.jsonPrimitive?.content)
            assertTrue(repo.agentPage("account", "unknown-cursor") is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun watchGetEditDeleteAndPendingCreationReadTheirVariants() = runTest(timeout = 5.seconds) {
        val oracle = Oracle().apply { pending = "Planner" }
        val client = (CoreClient.open(oracle, "/test/agent-calls", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultAgentDirectoryRepository(SdkRepositoryTransport { client })
            val changes = (repo.changes("account", "7") as ClientResult.Ready).value
            assertEquals("8", changes.journal)
            assertEquals("Renamed", changes.records.single().persona.name)
            assertEquals("anchor-2", (repo.agent("account", "anchor-2") as ClientResult.Ready).value.anchor)
            val persona = AgentPersona("New name", "orbit", "Plans")
            assertTrue(repo.editPersona("account", "anchor-2", persona, confirmed = false) is ClientResult.Failed)
            assertFalse(oracle.abi.submitted.any { it["method"]?.jsonPrimitive?.content == "agent.persona.edit" })
            assertEquals("New name", (repo.editPersona("account", "anchor-2", persona, confirmed = true) as ClientResult.Ready).value.persona.name)
            val edit = oracle.abi.submitted.last()
            assertEquals(true, edit["body"]?.jsonObject?.get("confirmed")?.jsonPrimitive?.boolean)
            assertTrue(repo.editPersona("account", "anchor-2", persona.copy(name = " "), confirmed = true) is ClientResult.Failed)
            assertTrue(repo.delete("account", "anchor-2", confirmed = false) is ClientResult.Failed)
            val deleted = (repo.delete("account", "anchor-2", confirmed = true) as ClientResult.Ready).value
            assertEquals(AgentLifecycle.RETIRED, deleted.lifecycle)
            assertEquals("node_record_retained", deleted.cleanup)
            val waiting = (repo.pendingCreation("account") as ClientResult.Ready).value
            assertEquals("request-1", waiting?.requestId)
            assertEquals("Planner", waiting?.name)
            assertEquals("", oracle.abi.submitted.last()["body"]?.jsonObject?.get("operation_id")?.jsonPrimitive?.content)
            assertEquals("dm:agent", (repo.conversation("account", "anchor-3") as ClientResult.Ready).value)
            assertNull((repo.conversation("account", "anchor-9") as ClientResult.Ready).value)
            oracle.refuse = "creation_request_unknown"
            assertNull((repo.pendingCreation("account") as ClientResult.Ready).value)
            oracle.refuse = "agent_not_found"
            assertTrue(repo.agent("account", "gone") is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun theAgentConversationIsDerivedFromBothRootsWithoutADirectoryScan() = runTest(timeout = 5.seconds) {
        val oracle = Oracle().apply { ownRoot = DirectConversationsTest.A }
        val client = (CoreClient.open(oracle, "/test/agent-dm", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultAgentDirectoryRepository(SdkRepositoryTransport { client })
            assertEquals(DirectConversationsTest.ID, (repo.conversation("account", DirectConversationsTest.B) as ClientResult.Ready).value)
            assertTrue(oracle.abi.submitted.none { it["method"]?.jsonPrimitive?.content == "conversation.directory" })
            // A malformed anchor or the account's own root addresses no conversation.
            assertNull((repo.conversation("account", "anchor-3") as ClientResult.Ready).value)
            assertNull((repo.conversation("account", DirectConversationsTest.A) as ClientResult.Ready).value)
        } finally { client.close() }
    }
}

