// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client's apply model against the core's card fixtures (`card-fixtures/`, copied from the pinned core, never edited).
 * The core opens a card as `Tree::diff(None, 0, 1)`: one upsert per node in tree order, each with its parent key and its
 * index among the parent's children. [openBatch] builds that batch from every expected tree; [CardTree.EMPTY] must apply
 * it, and the held tree must equal the expected tree.
 */
class CardTreeFixtureTest {
    private val fixtures: File = locateFixtures()

    private companion object {
        const val TREE_SCHEMA = "ramflux.card.tree.v1"
    }

    @Test fun scenarioMountBatchesApplyToEmptyTree() {
        for (name in listOf("approval", "chart", "complete_form", "form", "task")) {
            val batch = readJson(File(fixtures, "fixtures/$name.mount.json"))
            val held = CardTree.EMPTY.apply(batch)
            assertNotNull("$name.mount.json does not apply to an empty tree", held)
            assertEquals("$name.mount.json root", batch.array("mutations").first().jsonObject["node"]?.jsonObject?.text("key"), held?.rootKey)
            assertEquals("$name.mount.json tree version", 1L, held?.treeVersion)
        }
    }

    @Test fun everyTreeFixtureOpenBatchAppliesAndEqualsItsExpectedTree() {
        val cases = readJson(File(fixtures, "host-fixtures/index.json")).array("cases")
        assertEquals(32, cases.size)
        val failures = mutableListOf<String>()
        var applied = 0
        for (case in cases) {
            val row = case.jsonObject
            val kind = row.text("kind")
            val variant = row.text("variant")
            val id = "$kind/${variant ?: "unset"}"
            if (row.text("outcome") != "tree") {
                assertTrue("$id is a refusal row", row.text("expected")?.endsWith(".refusal.json") == true)
                continue
            }
            val expected = readJson(File(fixtures, "host-fixtures/" + row.text("expected")))
            val batch = openBatch(expected)
            val held = CardTree.EMPTY.apply(batch)
            if (held == null) {
                failures += "$id: the open batch does not apply to an empty tree"
                continue
            }
            val want = normal(expected)
            val got = normal(treeJson(held))
            if (want != got) failures += "$id: held tree differs from the expected tree in ${want.keys.filter { want[it] != got[it] }}"
            else applied++
        }
        assertTrue("trees that failed: ${failures.joinToString("; ")}", failures.isEmpty())
        assertEquals(31, applied)
    }

    /** `Tree::diff(None, 0, 1)` over an expected tree, with session 1 as the committed fixtures use. */
    private fun openBatch(tree: JsonObject): JsonObject {
        val nodes = tree.array("nodes")
        val byKey = nodes.associateBy { it.jsonObject.text("key") ?: error("node without key") }
        val mutations = nodes.map { element ->
            val node = element.jsonObject
            val parent = node.text("parent_key")
            val key = node.text("key")
            val index = parent?.let { p -> byKey.getValue(p).jsonObject.array("children").map { (it as JsonPrimitive).content }.indexOf(key) } ?: 0
            buildJsonObject {
                put("op", "upsert")
                put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
                put("index", index)
                put("node", JsonObject(node + ("children" to JsonArray(emptyList()))))
            }
        }
        return buildJsonObject {
            put("schema", CardTree.SCHEMA)
            put("session", 1)
            put("previous_version", 0)
            put("version", 1)
            put("signed_version", tree.getValue("signed_version"))
            put("signed_hash", tree.getValue("signed_hash"))
            put("edit_seq", tree.getValue("edit_seq"))
            put("mutations", JsonArray(mutations))
            put("host_slots", tree.getValue("host_slots"))
            put("locale", tree.getValue("locale"))
            put("format_locale", tree.getValue("format_locale"))
            put("direction", tree.getValue("direction"))
        }
    }

    /** The runtime's `ramflux.card.tree.v1` shape of a held tree (the same fields as the conformance view). */
    private fun treeJson(tree: CardTree): JsonObject = buildJsonObject {
        put("schema", TREE_SCHEMA)
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

    private fun normal(json: JsonObject): JsonObject {
        val nodes = json.array("nodes").sortedBy { it.jsonObject.text("key") }
        return JsonObject(json + ("nodes" to JsonArray(nodes)))
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: error("missing $name")

    private fun readJson(file: File): JsonObject =
        Json.parseToJsonElement(file.readText()).jsonObject

    private fun locateFixtures(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "card-fixtures")
            if (File(candidate, "host-fixtures/index.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("card-fixtures not found above ${System.getProperty("user.dir")}")
    }
}
