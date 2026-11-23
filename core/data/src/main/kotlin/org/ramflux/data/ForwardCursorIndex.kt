// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/**
 * Turns a forward-only SDK cursor into a [WindowedPage] with a `previous` cursor. Per listing key it remembers
 * the start cursor of every page read in the current listing (an id-only index, one short string per page);
 * a read without a cursor starts a new listing. A bounded window that dropped pages at its start reloads them
 * through these cursors. [FIRST] stands for the first page.
 */
internal class ForwardCursorIndex {
    private val lock = Any()
    private val starts = HashMap<String, ArrayList<String>>()

    /** The page index of [cursor] in [key]'s listing (a null cursor starts a new listing), or null when unknown. */
    fun begin(key: String, cursor: String?): Int? = synchronized(lock) {
        if (cursor == null) {
            starts[key] = arrayListOf(FIRST)
            0
        } else {
            starts[key]?.indexOf(cursor)?.takeIf { it >= 0 }
        }
    }

    /** Records the page after [index] and returns the window page with both cursors, or null when the listing was replaced. */
    fun <T> page(key: String, index: Int, rows: List<T>, next: String?): WindowedPage<T>? = synchronized(lock) {
        val chain = starts[key] ?: return null
        if (index >= chain.size) return null
        if (next != null && chain.size == index + 1) chain.add(next)
        WindowedPage(rows, next, if (index > 0) chain[index - 1] else null)
    }

    /** The SDK cursor for a window cursor: none for the first page. */
    fun sdkCursor(cursor: String?): String? = cursor?.takeIf { it != FIRST }

    companion object {
        const val FIRST = "first"
    }
}
