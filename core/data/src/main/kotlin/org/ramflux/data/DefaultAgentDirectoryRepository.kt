// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ramflux.sdk.CoreResult

/**
 * The agent methods of the pinned SDK (`bus/agent_methods.rs`). They are connection-level calls without an
 * operation id; the reply body is the `AgentConfigResult` oneof (`page`, `record` or `custody`) in ProtoJSON.
 *
 * `agent.list` pages forward only; [ForwardCursorIndex] serves the start cursor of each page read in the current
 * listing as the page's `previous` cursor, so a bounded window can reload pages it dropped at its start.
 */
internal class DefaultAgentDirectoryRepository(transport: RepositoryTransport) : AgentDirectoryRepository {
    private val source = DefaultAgentsRepository(transport)
    private val direct = DirectConversations(transport)
    private val starts = ForwardCursorIndex()

    override suspend fun agentPage(account: String, cursor: String?): ClientResult<AgentPage> {
        if (!valid(account) || (cursor?.length ?: 0) > MAX_CURSOR) return invalid()
        val index = starts.begin(account, cursor) ?: return invalid()
        val body = buildJsonObject {
            put("limit", PAGE)
            starts.sdkCursor(cursor)?.let { put("cursor", it) }
        }
        return when (val reply = result(source.agentList(RepositoryContext(account), body), "page")) {
            is ClientResult.Ready -> try {
                val page = reply.value
                val rows = (page["records"] as? JsonArray).orEmpty().map { record(it as JsonObject) }
                require(rows.map { it.anchor }.distinct().size == rows.size)
                val next = text(page, "next")?.takeIf { it.isNotEmpty() && it.length <= MAX_CURSOR && rows.isNotEmpty() }
                val windowed = starts.page(account, index, rows, next) ?: return invalid()
                ClientResult.Ready(AgentPage(windowed, requireNotNull(text(page, "journalCursor"))))
            } catch (_: Exception) {
                malformed()
            }
            is ClientResult.Failed -> reply
            is ClientResult.Unavailable -> reply
        }
    }

    override suspend fun agent(account: String, anchor: String): ClientResult<AgentSummary> {
        if (!valid(account) || !valid(anchor)) return invalid()
        return recordOf(source.agentGet(RepositoryContext(account), buildJsonObject { put("agent_anchor", anchor) }), anchor)
    }

    override suspend fun changes(account: String, journal: String): ClientResult<AgentChanges> {
        if (!valid(account) || journal.length > MAX_CURSOR) return invalid()
        return when (val reply = result(source.agentWatch(RepositoryContext(account), buildJsonObject { put("journal_cursor", journal) }), "page")) {
            is ClientResult.Ready -> try {
                val page = reply.value
                val rows = (page["records"] as? JsonArray).orEmpty().map { record(it as JsonObject) }
                ClientResult.Ready(AgentChanges(rows, requireNotNull(text(page, "journalCursor")), !text(page, "next").isNullOrEmpty()))
            } catch (_: Exception) {
                malformed()
            }
            is ClientResult.Failed -> reply
            is ClientResult.Unavailable -> reply
        }
    }

    override suspend fun pendingCreation(account: String): ClientResult<PendingAgentCreation?> {
        if (!valid(account)) return invalid()
        return when (val reply = result(source.agentOperationGet(RepositoryContext(account), buildJsonObject { put("operation_id", "") }), "custody")) {
            is ClientResult.Ready -> try {
                val status = reply.value
                ClientResult.Ready(
                    PendingAgentCreation(
                        requireNotNull(text(status, "requestId")),
                        requireNotNull(text(status, "state")),
                        text(status, "agentAnchor"),
                        text(status, "requestJson")?.let(::planName),
                        number(status, "expiresAt")
                    )
                )
            } catch (_: Exception) {
                malformed()
            }
            // An empty id that finds nothing waiting is answered with this refusal, not an empty result.
            is ClientResult.Failed -> if (reply.problem.statusName == "creation_request_unknown") ClientResult.Ready(null) else reply
            is ClientResult.Unavailable -> reply
        }
    }

    override suspend fun editPersona(account: String, anchor: String, persona: AgentPersona, confirmed: Boolean): ClientResult<AgentSummary> {
        if (!valid(account) || !valid(anchor) || !persona.valid) return invalid()
        if (!confirmed) return unconfirmed()
        val body = buildJsonObject {
            put("agent_anchor", anchor)
            put(
                "persona",
                buildJsonObject {
                    put("name", persona.name)
                    put("avatar", persona.avatar)
                    put("responsibility", persona.responsibility)
                }
            )
            put("confirmed", true)
        }
        return recordOf(source.agentPersonaEdit(RepositoryContext(account), body), anchor)
    }

    override suspend fun delete(account: String, anchor: String, confirmed: Boolean): ClientResult<AgentSummary> {
        if (!valid(account) || !valid(anchor)) return invalid()
        if (!confirmed) return unconfirmed()
        val body = buildJsonObject {
            put("agent_anchor", anchor)
            put("confirmed", true)
        }
        return recordOf(source.agentDelete(RepositoryContext(account), body), anchor)
    }

    /** The ordinary direct conversation with the agent, addressed by its anchor through [DirectConversations]. */
    override suspend fun conversation(account: String, anchor: String): ClientResult<String?> {
        if (!valid(account) || !valid(anchor)) return invalid()
        return direct.resolve(account, anchor)
    }

    private fun recordOf(reply: CoreResult<JsonObject>, anchor: String): ClientResult<AgentSummary> = when (val value = result(reply, "record")) {
        is ClientResult.Ready -> try {
            record(value.value).takeIf { it.anchor == anchor }?.let { ClientResult.Ready(it) } ?: malformed()
        } catch (_: Exception) {
            malformed()
        }
        is ClientResult.Failed -> value
        is ClientResult.Unavailable -> value
    }

    /** The `AgentConfigResult` variant [variant] of a reply, or the reply's failure. */
    private fun result(reply: CoreResult<JsonObject>, variant: String): ClientResult<JsonObject> = when (reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
        is CoreResult.Success -> ((reply.value["body"] as? JsonObject)?.get(variant) as? JsonObject)?.let { ClientResult.Ready(it) } ?: malformed()
    }

    private fun record(row: JsonObject): AgentSummary {
        val persona = row["persona"] as? JsonObject
        val access = row["access"] as? JsonObject
        val anchor = requireNotNull(text(row, "agentAnchor")).also { require(it.isNotEmpty() && it.length <= MAX_ID) }
        return AgentSummary(
            anchor,
            AgentPersona(
                persona?.let { text(it, "name") }.orEmpty(),
                persona?.let { text(it, "avatar") }.orEmpty(),
                persona?.let { text(it, "responsibility") }.orEmpty()
            ),
            when (text(row, "lifecycle")) {
                "U" -> AgentLifecycle.ACTIVE
                "X" -> AgentLifecycle.RETIRED
                else -> AgentLifecycle.UNKNOWN
            },
            text(row, "role").orEmpty(),
            AgentAccess(
                access?.let { text(it, "dm") }.orEmpty(),
                access?.let { text(it, "groupInviters") }.orEmpty(),
                (access?.let { it["allowedHumans"] ?: it["allowed_humans"] } as? JsonArray)?.size ?: 0
            ),
            text(row, "cleanup").orEmpty()
        )
    }

    /** ProtoJSON writes lowerCamelCase names; the snake_case spelling is accepted as well. */
    private fun field(row: JsonObject, camel: String): JsonElement? = row[camel] ?: row[camel.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }]

    private fun text(row: JsonObject, camel: String): String? = (field(row, camel) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun number(row: JsonObject, camel: String): Long? = (field(row, camel) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    /** The display name of a creation plan (canonical JSON, base64 in ProtoJSON); null when it cannot be read. */
    private fun planName(encoded: String): String? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_PLAN_BYTES)
        val plan = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        (plan["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun valid(value: String) = value.isNotBlank() && value.length <= MAX_ID
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun malformed() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
    private fun unconfirmed() = ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))

    private companion object {
        /** One SDK reply is serialized into at most 8 KiB (`custody_response::success`); eight typical records fit. */
        const val PAGE = 8
        const val MAX_CURSOR = 512
        const val MAX_ID = 4096
        const val MAX_PLAN_BYTES = 16 * 1024
    }
}
