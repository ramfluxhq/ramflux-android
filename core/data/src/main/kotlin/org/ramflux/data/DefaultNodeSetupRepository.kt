// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultNodeSetupRepository(transport: RepositoryTransport) : NodeSetupRepository {
    private val nodes = DefaultNodesRepository(transport)
    private val empty = JsonObject(emptyMap())
    private fun context(mutation: Boolean = false) = RepositoryContext(null, if (mutation) UUID.randomUUID().toString() else null)
    private val invalid get() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
    private fun node(row: JsonObject, default: String? = null): SavedNode {
        fun text(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 4096 }
            ?: throw IllegalArgumentException("missing node field")
        val id = text("node_id")
        val warnings = row["safety_warnings"] as? JsonArray ?: throw IllegalArgumentException("missing warnings")
        require(warnings.size <= 64)
        val enabled = (row["enabled"] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("missing enabled")
        return SavedNode(id, text("discovery_url"), text("node_public_key"), id == default,
            warnings.any { it.jsonPrimitive.content == "key_changed" }, enabled)
    }
    private fun <T> decode(result: CoreResult<JsonObject>, mutation: Boolean = false, project: (JsonObject) -> T): ClientResult<T> = when (result) {
        is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
        is CoreResult.Success -> try {
            val body = result.value["body"] as? JsonObject ?: throw IllegalArgumentException("missing body")
            ClientResult.Ready(project(body))
        } catch (_: IllegalArgumentException) {
            ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16,
                if (mutation) ClientOutcome.UNKNOWN else ClientOutcome.NONE))
        }
    }
    override suspend fun list(): ClientResult<List<SavedNode>> = decode(nodes.list(context(), empty)) { body ->
        val rows = body["nodes"] as? JsonArray ?: throw IllegalArgumentException("missing nodes")
        require(rows.size <= 256)
        val default = (body["default_node_id"] as? JsonPrimitive)?.contentOrNull
        rows.map { node(it.jsonObject, default) }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
    }
    override suspend fun discover(address: String): ClientResult<DiscoveredNode> {
        if (DiscoveryOriginParser.preview(address)?.origin != address) return invalid
        return decode(nodes.add(context(true), buildJsonObject { put("url", address) })) { body ->
            val candidate = node(body["node"] as? JsonObject ?: throw IllegalArgumentException("missing node"))
            val fingerprint = (body["key_fingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("missing fingerprint")
            require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
            DiscoveredNode(candidate, fingerprint)
        }
    }
    override suspend fun confirm(candidate: DiscoveredNode, operatorKey: String): ClientResult<Unit> {
        if (candidate.node.keyChanged) return ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.UNSUPPORTED_BY_CORE)
        // The separately entered operator key is the SDK's atomic expected-key predicate.
        if (operatorKey.isBlank() || operatorKey != candidate.node.publicKey) return invalid
        return decode(nodes.confirm(context(true), buildJsonObject {
            put("node_id", candidate.node.id); put("expected_public_key", operatorKey); put("pin_source", "operator")
        }), mutation = true) { body ->
            val confirmed = node(body)
            require(confirmed.id == candidate.node.id && confirmed.publicKey == candidate.node.publicKey && !confirmed.keyChanged)
            Unit
        }
    }
    override suspend fun select(id: String): ClientResult<Unit> {
        val current = when (val result = list()) {
            is ClientResult.Ready -> result.value
            is ClientResult.Failed -> return result
            is ClientResult.Unavailable -> return result
        }
        if (current.none { it.id == id && it.enabled && !it.keyChanged }) return invalid
        return decode(nodes.set_default(context(true), buildJsonObject { put("node_id", id) }), mutation = true) { body ->
            require((body["ok"] as? JsonPrimitive)?.booleanOrNull == true); Unit
        }
    }
}
