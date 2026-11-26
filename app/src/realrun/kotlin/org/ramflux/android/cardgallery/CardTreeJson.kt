// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ramflux.data.cards.CardTree

/**
 * The conformance view of trees. [of] writes a held [CardTree] in the runtime's `ramflux.card.tree.v1` shape, so the
 * client's copy and the runtime's `ramflux_card_tree` output compare as JSON. [rebase] applies the fixture rule: the live
 * session becomes 1 in every `session` field and in the session text inside a request id.
 */
internal object CardTreeJson {
    const val SCHEMA = "ramflux.card.tree.v1"

    fun of(tree: CardTree): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("session", tree.session)
        put("signed_version", tree.signedVersion)
        put("signed_hash", tree.signedHash)
        put("tree_version", tree.treeVersion)
        put("edit_seq", tree.editSeq)
        put("root_key", tree.rootKey?.let(::JsonPrimitive) ?: JsonNull)
        put("locale", tree.locale)
        put("format_locale", tree.formatLocale)
        put("direction", tree.direction)
        put("host_slots", JsonArray(tree.hostSlots))
        put(
            "nodes",
            JsonArray(
                tree.nodes.values.map { node ->
                    buildJsonObject {
                        put("attrs", node.attrs)
                        put("children", JsonArray(node.children.map(::JsonPrimitive)))
                        put("component_id", node.componentId)
                        put("key", node.key)
                        put("kind", node.kind)
                        put("parent_key", node.parentKey?.let(::JsonPrimitive) ?: JsonNull)
                        put("scope", node.scope)
                        put("slot", node.slot ?: JsonNull)
                        put("source_instance", JsonArray(node.sourceInstance.map(::JsonPrimitive)))
                    }
                }
            )
        )
    }

    fun rebase(json: JsonObject, live: Long): JsonObject = JsonObject(
        json.mapValues { (name, value) ->
            if (name == "session" && (value as? JsonPrimitive)?.longOrNull == live) JsonPrimitive(1) else rebaseElement(value, live)
        }
    )

    /** Nodes sorted by key: the runtime and the client may list the same nodes in another order. */
    fun normal(json: JsonObject): JsonObject {
        val nodes = (json["nodes"] as? JsonArray).orEmpty().sortedBy(::keyOf)
        return JsonObject(json.toMap() + ("nodes" to JsonArray(nodes)))
    }

    private fun keyOf(element: JsonElement): String = ((element as? JsonObject)?.get("key") as? JsonPrimitive)?.content.orEmpty()

    private fun rebaseElement(element: JsonElement, live: Long): JsonElement = when (element) {
        is JsonObject -> rebase(element, live)
        is JsonArray -> JsonArray(element.map { rebaseElement(it, live) })
        is JsonPrimitive ->
            if (element.isString && element.content.contains("\"session\":$live")) {
                JsonPrimitive(element.content.replace("\"session\":$live", "\"session\":1"))
            } else {
                element
            }
    }
}

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
