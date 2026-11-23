// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/**
 * A bounded window of consecutive [WindowedPage]s in source order. Appending past [maxPages] drops the first page
 * and prepending drops the last, so the window never holds more than `maxPages` pages; the cursors at both ends
 * reload what was dropped. Rows are distinct by [id] (a row that moved between snapshots appears once). Not
 * thread-safe: callers confine it to one coroutine or lock.
 */
class PageWindow<T>(private val maxPages: Int, private val id: (T) -> String) {
    private val pages = ArrayDeque<WindowedPage<T>>()

    init {
        require(maxPages >= 2)
    }

    val next: String? get() = pages.lastOrNull()?.next
    val previous: String? get() = pages.firstOrNull()?.previous
    val isEmpty: Boolean get() = pages.all { it.rows.isEmpty() }

    fun reset(first: WindowedPage<T>) {
        pages.clear()
        pages.addLast(first)
    }

    fun append(page: WindowedPage<T>) {
        pages.addLast(page)
        while (pages.size > maxPages) pages.removeFirst()
    }

    fun prepend(page: WindowedPage<T>) {
        pages.addFirst(page)
        while (pages.size > maxPages) pages.removeLast()
    }

    /** Removes one row by id from the loaded pages (a confirmed removal before the next reload). */
    fun remove(rowId: String) {
        for (index in pages.indices) {
            val page = pages[index]
            if (page.rows.any { id(it) == rowId }) pages[index] = page.copy(rows = page.rows.filterNot { id(it) == rowId })
        }
    }

    fun rows(): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>(pages.sumOf { it.rows.size })
        for (page in pages) for (row in page.rows) if (seen.add(id(row))) out.add(row)
        return out
    }
}
