// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** An account created or restored on this device. Public facts only: no code, phrase, key or token. */
data class KnownAccount(
    val id: String,
    val displayName: String,
    /** Discovery origin of the account's node, when the device knew it at creation. */
    val nodeOrigin: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

/**
 * The accounts this device created or restored, so that unlock and account switch can offer them while the
 * SDK's `account.list` reports only open accounts. The SDK stays the authority: an entry is only an offer, and
 * unlocking it goes through `account.unlock` like any other account.
 */
interface KnownAccountRegistry {
    fun all(): List<KnownAccount>

    /** Adds the account, or refreshes its display name (and node origin when one is given). */
    fun record(account: KnownAccount)
    fun remove(id: String)
}

/** Keeps the registry rules (bounds, de-duplication, update in place) for both stores. */
abstract class BoundedKnownAccountRegistry : KnownAccountRegistry {
    private val gate = Any()
    private var cache: List<KnownAccount>? = null

    protected abstract fun load(): List<KnownAccount>
    protected abstract fun save(entries: List<KnownAccount>)

    final override fun all(): List<KnownAccount> = synchronized(gate) { current() }

    final override fun record(account: KnownAccount) {
        if (!valid(account)) return
        synchronized(gate) {
            val entries = current().toMutableList()
            val index = entries.indexOfFirst { it.id == account.id }
            if (index >= 0) {
                val old = entries[index]
                entries[index] = old.copy(
                    displayName = account.displayName.ifBlank { old.displayName },
                    nodeOrigin = account.nodeOrigin ?: old.nodeOrigin
                )
            } else {
                entries += account
                while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            }
            store(entries)
        }
    }

    final override fun remove(id: String) {
        synchronized(gate) {
            val entries = current()
            if (entries.none { it.id == id }) return
            store(entries.filterNot { it.id == id })
        }
    }

    private fun current(): List<KnownAccount> = cache ?: sanitize(load()).also { cache = it }

    private fun store(entries: List<KnownAccount>) {
        cache = entries
        save(entries)
    }

    companion object {
        const val MAX_ENTRIES = 64

        fun valid(account: KnownAccount): Boolean = account.id.isNotEmpty() && account.id.length <= 128 &&
            account.displayName.length <= 1024 && (account.nodeOrigin?.length ?: 0) <= 2048

        internal fun sanitize(entries: List<KnownAccount>): List<KnownAccount> {
            val seen = HashSet<String>()
            return entries.filter { valid(it) && seen.add(it.id) }.takeLast(MAX_ENTRIES)
        }
    }
}

/**
 * The registry as one small JSON file. The Android composition places it in `noBackupFilesDir`, beside (not
 * inside) the SDK data root: app-private and excluded from cloud and device-transfer backup. A missing or
 * damaged file reads as empty; the registry is a convenience and never decides what an account is.
 */
class FileKnownAccountRegistry(private val file: File) : BoundedKnownAccountRegistry() {
    override fun load(): List<KnownAccount> = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            emptyList()
        } else {
            decode(file.readText())
        }
    } catch (_: IOException) {
        emptyList()
    }

    override fun save(entries: List<KnownAccount>) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(encode(entries))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
            // The SDK still lists open accounts; a lost entry only removes an offer.
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 512L * 1024

        internal fun encode(entries: List<KnownAccount>): String = buildJsonArray {
            for (entry in entries) {
                add(
                    buildJsonObject {
                        put("id", entry.id)
                        put("display_name", entry.displayName)
                        entry.nodeOrigin?.let { put("node_origin", it) }
                        put("created_at_ms", entry.createdAtMillis)
                    }
                )
            }
        }.toString()

        internal fun decode(text: String): List<KnownAccount> = try {
            (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                KnownAccount(
                    id,
                    row.string("display_name").orEmpty(),
                    row.string("node_origin"),
                    (row["created_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
                )
            }
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Process-memory registry for tests and previews. */
class MemoryKnownAccountRegistry(initial: List<KnownAccount> = emptyList()) : BoundedKnownAccountRegistry() {
    private var stored = initial
    override fun load(): List<KnownAccount> = stored
    override fun save(entries: List<KnownAccount>) {
        stored = entries
    }
}

/**
 * The SDK's catalog named from the registry, followed by the registry's other accounts as locked offers. The
 * SDK's own label wins when it has one; a registry entry never makes an account look open.
 */
internal fun AccountCatalog.mergedWith(known: List<KnownAccount>): AccountCatalog {
    val names = known.associate { it.id to it.displayName }
    val listed = accounts.mapTo(HashSet()) { it.id }
    val named = accounts.map { row ->
        val name = row.label?.takeIf { it.isNotBlank() } ?: names[row.id]?.takeIf { it.isNotBlank() }
        row.copy(label = name, known = row.id in names)
    }
    val offered = known.filter { it.id !in listed }.map { entry ->
        AccountSummary(
            entry.id,
            entry.displayName.ifBlank { null },
            unlocked = false,
            state = "locked",
            known = true,
            listed = false
        )
    }
    return copy(accounts = named + offered)
}
