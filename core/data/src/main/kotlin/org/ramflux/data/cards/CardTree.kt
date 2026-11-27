// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One node of the native tree (`ramflux.card.tree.v1`), read from a mutation upsert. [children] are keys, in order. */
data class CardNode(
    val key: String,
    val componentId: String,
    val kind: String,
    val scope: String,
    val parentKey: String?,
    val sourceInstance: List<String>,
    val slot: JsonObject?,
    val attrs: JsonObject,
    val children: List<String>,
) {
    /** Attrs hold the author's text and the person's drafts: they are never printed. */
    override fun toString() = "CardNode(<redacted>)"
}

/**
 * Which tree a person saw when they acted: every field of the runtime's event token, so a stamp taken in one runtime
 * session (or before a signed update) never equals one of another, even when the tree version and edit sequence repeat
 * after a reopen. A host event is dropped when the held tree has moved on.
 */
data class CardStamp(val session: Long, val signedVersion: Long, val treeVersion: Long, val editSeq: Long)

/**
 * The client copy of one card's native tree. The only way to change it is [apply]: an atomic
 * `ramflux.card.mutations.v1` batch, checked against the held session and version. A batch that does not fit
 * returns null and the held tree stays as it was; the caller then re-reads the whole tree.
 */
class CardTree private constructor(
    val session: Long,
    val treeVersion: Long,
    val signedVersion: Long,
    val signedHash: String,
    val editSeq: Long,
    val rootKey: String?,
    val nodes: Map<String, CardNode>,
    val hostSlots: List<JsonObject>,
    val locale: String,
    val formatLocale: String,
    val direction: String,
    val generating: Boolean,
    val lifecycle: String,
) {
    fun root(): CardNode? = rootKey?.let { nodes[it] }

    /** The version a host event is stamped with when it is raised from this tree. */
    val stamp: CardStamp get() = CardStamp(session, signedVersion, treeVersion, editSeq)

    /** The `token` a host event carries for [key] of this tree (the runtime's `EventToken`, camelCase). */
    fun tokenFor(key: String): JsonObject = buildJsonObject {
        put("session", session)
        put("signedVersion", signedVersion)
        put("editSeq", editSeq)
        put("treeVersion", treeVersion)
        put("key", key)
    }

    /** Applies one batch atomically; null when the batch does not fit this tree or breaks a limit. */
    fun apply(batch: JsonObject): CardTree? {
        if (batch.text("schema") != SCHEMA) return null
        val batchSession = batch.long("session") ?: return null
        val previous = batch.long("previous_version") ?: return null
        val version = batch.long("version") ?: return null
        // The empty tree (treeVersion 0) takes any session: it is the base of an open or resume batch.
        if (previous != treeVersion || version <= previous) return null
        if (treeVersion != 0L && batchSession != session) return null
        val ops = batch.array("mutations") ?: return null
        if (ops.size > MAX_OPS || batch.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val work = LinkedHashMap<String, Working>()
        nodes.values.forEach { work[it.key] = Working(it, it.children.toMutableList()) }
        var root = rootKey
        for (entry in ops) {
            val row = entry as? JsonObject ?: return null
            when (row.text("op")) {
                "remove" -> if (!remove(work, row.text("key") ?: return null)) return null
                "upsert" -> {
                    val parent = (row["parent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (!upsert(work, parent, row)) return null
                    if (parent == null) root = (row["node"] as? JsonObject)?.text("key") ?: return null
                }
                else -> return null
            }
        }
        val rootNow = root ?: return null
        if (work.size > MAX_NODES || work[rootNow]?.node?.parentKey != null) return null
        // Every node is reachable from the root exactly once, and each edge agrees with the child's parent key.
        val seen = HashSet<String>()
        val pending = ArrayDeque<String>().apply { add(rootNow) }
        while (pending.isNotEmpty()) {
            val key = pending.removeLast()
            if (!seen.add(key)) return null
            val item = work[key] ?: return null
            for (child in item.children) {
                if (work[child]?.node?.parentKey != key) return null
                pending.addLast(child)
            }
        }
        if (seen.size != work.size) return null
        val built = LinkedHashMap<String, CardNode>(work.size)
        work.forEach { (key, item) -> built[key] = item.node.copy(children = item.children.toList()) }
        return CardTree(
            session = batchSession,
            treeVersion = version,
            signedVersion = batch.long("signed_version") ?: return null,
            signedHash = batch.text("signed_hash") ?: return null,
            editSeq = batch.long("edit_seq") ?: return null,
            rootKey = rootNow,
            nodes = built,
            hostSlots = batch.array("host_slots")?.map { it as? JsonObject ?: return null } ?: return null,
            locale = batch.text("locale") ?: return null,
            formatLocale = batch.text("format_locale") ?: return null,
            direction = batch.text("direction") ?: return null,
            generating = batch.flag("generating") == true,
            lifecycle = batch.text("lifecycle") ?: "open",
        )
    }

    private class Working(var node: CardNode, val children: MutableList<String>)

    private fun detach(work: MutableMap<String, Working>, key: String) {
        val parent = work[key]?.node?.parentKey?.let { work[it] } ?: return
        parent.children.remove(key)
    }

    /** Descendants first: a removed key leaves its parent's list, then its own listed children go too. */
    private fun remove(work: MutableMap<String, Working>, key: String): Boolean {
        val item = work[key] ?: return false
        detach(work, key)
        work.remove(key)
        item.children.toList().forEach { child -> if (work.containsKey(child)) remove(work, child) }
        return true
    }

    /** Parents first; a moved or changed node leaves its old parent and lands at [index] of the new one. */
    private fun upsert(work: MutableMap<String, Working>, parent: String?, row: JsonObject): Boolean {
        val index = (row["index"] as? JsonPrimitive)?.intOrNull ?: return false
        val node = parseNode(row["node"] as? JsonObject ?: return false) ?: return false
        if (node.parentKey != parent) return false
        val existing = work[node.key]
        detach(work, node.key)
        val children = existing?.children ?: mutableListOf()
        if (parent == null) {
            if (index != 0) return false
        } else {
            val holder = work[parent] ?: return false
            if (index < 0 || index > holder.children.size) return false
            holder.children.add(index, node.key)
        }
        work[node.key] = Working(node, children)
        return true
    }

    private fun parseNode(row: JsonObject): CardNode? {
        val key = row.text("key") ?: return null
        val source = row.array("source_instance")?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: return null
        return CardNode(
            key = key,
            componentId = row.text("component_id") ?: return null,
            kind = row.text("kind") ?: return null,
            scope = row.text("scope") ?: return null,
            parentKey = (row["parent_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            sourceInstance = source,
            slot = row["slot"] as? JsonObject,
            attrs = row["attrs"] as? JsonObject ?: return null,
            children = emptyList(),
        )
    }

    companion object {
        const val SCHEMA = "ramflux.card.mutations.v1"
        const val MAX_OPS = 512
        const val MAX_BYTES = 256 * 1024
        const val MAX_NODES = 256

        /** The base of an open, resume or reset: every node arrives as an upsert of a batch from version 0. */
        val EMPTY = CardTree(0, 0, 0, "", 0, null, emptyMap(), emptyList(), "en", "en", "ltr", false, "open")
    }
}
