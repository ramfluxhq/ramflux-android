// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/**
 * One page of a windowed list. [next] and [previous] are opaque cursors for the pages after and before this one;
 * null at that end. Rows carry stable SDK ids.
 */
data class WindowedPage<T>(val rows: List<T>, val next: String?, val previous: String?) {
    override fun toString() = "WindowedPage(rows=${rows.size})"
}

/**
 * Serves window pages over an SDK method that is unpaged today. A read without a cursor takes a fresh snapshot
 * from [load]; cursors address offsets in that snapshot. A cursor from an older snapshot reads the same offset of
 * a fresh one, so a window may briefly mix two snapshots; windows merge by id. The snapshot is the SDK's whole
 * reply, which the SDK already bounds by one frame; only the requested page leaves this class.
 */
internal class SnapshotPager<T>(private val pageSize: Int) {
    private class Snapshot<T>(val key: String, val token: String, val rows: List<T>)
    private val lock = Any()
    private var snapshot: Snapshot<*>? = null

    suspend fun page(key: String, cursor: String?, load: suspend () -> ClientResult<List<T>>): ClientResult<WindowedPage<T>> {
        val parsed = cursor?.let(::parse) ?: if (cursor != null) return invalid() else null
        val current = synchronized(lock) { snapshot?.takeIf { it.key == key && parsed != null && it.token == parsed.first } }
        val rows: Snapshot<*> = current ?: when (val loaded = load()) {
            is ClientResult.Ready -> Snapshot(key, java.util.UUID.randomUUID().toString(), loaded.value).also { synchronized(lock) { snapshot = it } }
            is ClientResult.Failed -> return loaded
            is ClientResult.Unavailable -> return loaded
        }
        @Suppress("UNCHECKED_CAST")
        val list = rows.rows as List<T>
        val offset = (parsed?.second ?: 0).coerceIn(0, list.size)
        val end = (offset + pageSize).coerceAtMost(list.size)
        return ClientResult.Ready(
            WindowedPage(
                list.subList(offset, end).toList(),
                if (end < list.size) "${rows.token}:$end" else null,
                if (offset > 0) "${rows.token}:${(offset - pageSize).coerceAtLeast(0)}" else null
            )
        )
    }

    private fun parse(cursor: String): Pair<String, Int>? {
        if (cursor.length > 128) return null
        val token = cursor.substringBefore(':', "")
        val offset = cursor.substringAfter(':', "").toIntOrNull() ?: return null
        return if (token.isEmpty() || offset < 0) null else token to offset
    }

    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
}
