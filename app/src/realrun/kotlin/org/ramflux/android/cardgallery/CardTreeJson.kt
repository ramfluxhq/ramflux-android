// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.data.cards.CardTree

/**
 * The conformance view of trees. [of] writes a held [CardTree] in the runtime's `ramflux.card.tree.v1` shape, so the
 * client's copy and the runtime's `ramflux_card_tree` output compare as JSON through [CardTreeCanonical].
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
}
