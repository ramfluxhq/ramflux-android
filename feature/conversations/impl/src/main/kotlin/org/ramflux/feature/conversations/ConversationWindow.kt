// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations

import org.ramflux.data.ConversationPreview
import org.ramflux.data.ConversationSummary

/**
 * The bounded in-memory window of the conversation list.
 *
 * The SDK pages `conversation.directory` by id, not by activity, so recent-first holds within the
 * rows loaded so far: every page merges into the window, which keeps at most [capacity] rows and
 * evicts the least recent. After the whole directory has been paged through, the window holds the
 * [capacity] most recent conversations overall; live upserts keep it current without a reload.
 *
 * Not thread-safe: the owner confines it to one coroutine at a time and sorts off the main thread.
 */
class ConversationWindow(private val capacity: Int = CAPACITY) {
    private val rows = HashMap<String, ConversationSummary>()
    private var ordered: List<ConversationSummary> = emptyList()
    private var dirty = false

    val size: Int get() = rows.size

    /** Merges one page. Returns how many of its rows are still in the window after eviction. */
    fun merge(page: List<ConversationSummary>): Int {
        page.forEach { rows[it.id] = it }
        dirty = true
        ordered()
        return page.count { rows[it.id] != null }
    }

    /** A live row; a missing title keeps the one already known. */
    fun upsert(row: ConversationSummary, titled: Boolean) {
        val previous = rows[row.id]
        // An event that carries no preview keeps the one already shown.
        val keepPreview = if (row.preview == ConversationPreview.None && previous != null) previous.preview else row.preview
        rows[row.id] = if (!titled && previous != null) {
            row.copy(title = previous.title, titleIsIdentifier = previous.titleIsIdentifier, preview = keepPreview)
        } else {
            row.copy(preview = keepPreview)
        }
        dirty = true
    }

    fun remove(id: String) {
        if (rows.remove(id) != null) dirty = true
    }

    fun clear() {
        rows.clear()
        ordered = emptyList()
        dirty = false
    }

    operator fun contains(id: String) = id in rows

    /** Recent first by `last_activity`, rows without activity last, ties by id; evicts beyond capacity. */
    fun ordered(): List<ConversationSummary> {
        if (!dirty) return ordered
        val sorted = rows.values.sortedWith(ORDER)
        if (sorted.size > capacity) sorted.subList(capacity, sorted.size).forEach { rows.remove(it.id) }
        ordered = if (sorted.size > capacity) sorted.subList(0, capacity).toList() else sorted
        dirty = false
        return ordered
    }

    companion object {
        const val CAPACITY = 1_000
        private val ORDER = compareByDescending<ConversationSummary> { it.lastActivity ?: Long.MIN_VALUE }.thenBy { it.id }
    }
}
