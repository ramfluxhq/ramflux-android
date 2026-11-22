// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * One back stack per top-level destination. Each stack starts at its tab root and is never empty.
 * Back pops the selected tab; at a tab root other than the first tab it returns to the first tab;
 * at the first tab's root it reports that the shell should exit.
 */
class TopLevelNavigator(val stacks: List<MutableList<NavKey>>, private val selection: MutableIntState) {
    init {
        require(stacks.isNotEmpty() && stacks.all { it.isNotEmpty() }) { "every tab needs its root" }
    }

    val selected: Int get() = selection.intValue
    val current: List<NavKey> get() = stacks[selected]
    val roots: List<NavKey> get() = stacks.map { it.first() }
    val atRoot: Boolean get() = current.size == 1

    /** Select a tab, keeping every tab's history. Selecting the selected tab returns it to its root. */
    fun select(tab: Int) {
        require(tab in stacks.indices) { "unknown tab $tab" }
        if (tab == selected) {
            truncate(stacks[tab], 1)
        } else {
            selection.intValue = tab
        }
    }

    /** Push onto the selected tab. A key equal to the top is not pushed twice. */
    fun push(key: NavKey) {
        val stack = stacks[selected]
        if (stack.last() != key) stack.add(key)
    }

    /** Show [detail] as the selection of the tab root: anything above the root is replaced. */
    fun openDetail(detail: NavKey) {
        val stack = stacks[selected]
        truncate(stack, 1)
        stack.add(detail)
    }

    /** Returns false when back should leave the shell. */
    fun pop(): Boolean {
        val stack = stacks[selected]
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            selected != 0 -> {
                selection.intValue = 0
                true
            }
            else -> false
        }
    }

    val canGoBack: Boolean get() = current.size > 1 || selected != 0

    /** Drop every account-scoped key above the tab roots, for a locked session. */
    fun dropAccountScoped(isAccountScoped: (NavKey) -> Boolean) {
        stacks.forEach { stack ->
            for (index in stack.lastIndex downTo 1) {
                if (isAccountScoped(stack[index])) stack.removeAt(index)
            }
        }
    }

    private fun truncate(stack: MutableList<NavKey>, size: Int) {
        while (stack.size > size) stack.removeAt(stack.lastIndex)
    }
}

/**
 * Saveable per-tab stacks: they survive configuration changes (fold, unfold, resize) and process
 * death through the Navigation 3 back-stack saver. [roots] must keep the same size for the life of
 * the composition.
 */
@Composable
fun rememberTopLevelNavigator(roots: List<NavKey>): TopLevelNavigator {
    val stacks = roots.map { root -> key(root) { rememberNavBackStack(root) } }
    val selection = rememberSaveable { mutableIntStateOf(0) }
    return remember(stacks, selection) { TopLevelNavigator(stacks, selection) }
}
