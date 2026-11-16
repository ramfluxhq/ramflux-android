// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.ConversationDirectoryEntry

/**
 * Accept creates one empty direct row and the first message can land in another row for the same
 * two participants. Keep the row that has activity and drop the empty twin.
 */
internal fun collapseDirectPeers(rows: List<ConversationDirectoryEntry>): List<ConversationDirectoryEntry> {
    val keyOf = HashMap<Int, String>(rows.size)
    rows.forEachIndexed { index, row -> peerKey(row)?.let { keyOf[index] = it } }
    val drop = HashSet<Int>()
    for (indexes in keyOf.entries.groupBy({ it.value }, { it.key }).values) {
        if (indexes.size < 2) continue
        val keeper = indexes.maxWith(
            compareBy<Int> { rows[it].lastActivity != null }
                .thenBy { rows[it].lastActivity ?: Long.MIN_VALUE }
                .thenBy { rows[it].unread }
        )
        for (index in indexes) {
            if (index == keeper) continue
            val row = rows[index]
            if (row.lastActivity == null && row.unread == 0uL && !row.isUnread) drop.add(index)
        }
    }
    return rows.filterIndexed { index, _ -> index !in drop }
}

private fun peerKey(row: ConversationDirectoryEntry): String? {
    if (row.kind != null && row.kind != "direct") return null
    val people = row.participants.filter { it.isNotEmpty() }.distinct().sorted()
    if (people.size != 2) return null
    return people.joinToString("\u0000")
}
