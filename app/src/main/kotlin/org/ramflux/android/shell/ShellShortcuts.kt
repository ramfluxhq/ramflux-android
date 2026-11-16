// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

internal const val DEFAULT_SHELL_ORDER = "messages-groups-agents-contacts-tasks"

internal fun shellIds(raw: String): List<String> = raw.split("-").filter { it.isNotEmpty() }

internal fun shellCore(id: String): Boolean = id == "messages" || id == "groups" || id == "agents"

internal fun moreShortcutIds(order: String, hidden: String): List<String> {
    val gone = shellIds(hidden).toSet()
    return shellIds(order).filter { (it == "contacts" || it == "tasks") && it !in gone }
}

internal fun moveVisibleShortcut(order: String, hidden: String, visibleIndex: Int, delta: Int): String {
    val ids = shellIds(order)
    val gone = shellIds(hidden).toSet()
    val visible = ids.filter { it !in gone }.toMutableList()
    val target = visibleIndex + delta
    if (visibleIndex !in visible.indices || target !in visible.indices) return order
    val item = visible.removeAt(visibleIndex)
    visible.add(target, item)
    var cursor = 0
    return ids.joinToString("-") { id ->
        if (id in gone) {
            id
        } else {
            val next = visible[cursor]
            cursor += 1
            next
        }
    }
}

internal fun hideShortcut(hidden: String, id: String): String {
    if (shellCore(id)) return hidden
    return (shellIds(hidden).toSet() + id).sorted().joinToString("-")
}

internal fun revealShortcut(hidden: String): String {
    val gone = shellIds(hidden).toMutableList()
    if (gone.isEmpty()) return hidden
    gone.removeAt(0)
    return gone.joinToString("-")
}
