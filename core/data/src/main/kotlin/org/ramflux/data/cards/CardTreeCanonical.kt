// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The comparison form of a `ramflux.card.tree.v1` document, for the conformance against the core's committed trees.
 *
 * The committed fixtures follow the rule of `host-fixtures/README.md`: the live session becomes 1 in every `session`
 * field, including `token.session`, and in the `session` field inside a `request_id`. A `request_id` is a JSON text
 * (the runtime's `EventToken`, struct field order in the runtime's output), and the fixture generator writes it with
 * sorted keys. So the text itself differs between the runtime and the fixture while the JSON is equal: this form parses
 * each `request_id`, rewrites its session, and writes it back with sorted keys. The nodes are sorted by key.
 */
object CardTreeCanonical {
    /** Canonical form of [json] whose live session is [live]; the expected trees of the fixtures use live 1. */
    fun of(json: JsonObject, live: Long): JsonObject {
        val rebased = rebase(json, live)
        val nodes = (rebased["nodes"] as? JsonArray).orEmpty().sortedBy { keyOf(it) }
        return JsonObject(rebased + ("nodes" to JsonArray(nodes)))
    }

    private fun rebase(json: JsonObject, live: Long): JsonObject =
        JsonObject(json.mapValues { (name, value) -> rebaseValue(name, value, live) })

    private fun rebaseValue(name: String, value: JsonElement, live: Long): JsonElement = when {
        name == "session" && value is JsonPrimitive && !value.isString && value.longOrNull == live -> JsonPrimitive(1)
        name == "request_id" && value is JsonPrimitive && value.isString -> requestId(value.content, live)
        value is JsonObject -> rebase(value, live)
        value is JsonArray -> JsonArray(value.map { rebaseValue("", it, live) })
        else -> value
    }

    /** A `request_id` that is a JSON object is rewritten and written with sorted keys; any other text is kept. */
    private fun requestId(text: String, live: Long): JsonPrimitive {
        val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return JsonPrimitive(text)
        return JsonPrimitive(sortedText(rebase(parsed, live)))
    }

    private fun sortedText(element: JsonElement): String = when (element) {
        is JsonObject -> element.toSortedMap().entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + sortedText(value)
        }
        is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { sortedText(it) }
        else -> element.toString()
    }

    private fun keyOf(element: JsonElement): String = ((element as? JsonObject)?.get("key") as? JsonPrimitive)?.content.orEmpty()

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
}
