// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Key(val name: String) : NavKey
private data class AccountKey(val name: String) : NavKey, AccountScoped

class TopLevelNavigatorTest {
    private val messages = Key("messages")
    private val groups = Key("groups")
    private val more = Key("more")

    private fun navigator() = TopLevelNavigator(
        listOf(mutableListOf<NavKey>(messages), mutableListOf<NavKey>(groups), mutableListOf<NavKey>(more)),
        mutableIntStateOf(0)
    )

    @Test fun switchingTabsKeepsEachTabsStack() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        nav.select(1)
        nav.push(AccountKey("group-chat"))
        nav.select(0)
        assertEquals(listOf(messages, AccountKey("chat-a")), nav.current)
        nav.select(1)
        assertEquals(listOf(groups, AccountKey("group-chat")), nav.current)
    }

    @Test fun backPopsTheTabThenReturnsToTheFirstTabThenExits() {
        val nav = navigator()
        nav.select(2)
        nav.push(Key("settings"))
        nav.push(Key("language"))
        assertTrue(nav.pop())
        assertEquals(listOf(more, Key("settings")), nav.current)
        assertTrue(nav.pop())
        assertEquals(listOf(more), nav.current)
        assertTrue(nav.canGoBack)
        assertTrue(nav.pop())
        assertEquals(0, nav.selected)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
    }

    @Test fun reselectingTheSelectedTabReturnsToItsRoot() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        nav.select(0)
        assertEquals(listOf(messages), nav.current)
    }

    @Test fun openingAnotherDetailReplacesTheSelectionSoBackReturnsToTheList() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        nav.openDetail(AccountKey("chat-b"))
        assertEquals(listOf(messages, AccountKey("chat-b")), nav.current)
        nav.pop()
        assertEquals(listOf(messages), nav.current)
    }

    @Test fun pushingTheTopAgainIsIgnored() {
        val nav = navigator()
        nav.push(Key("notices"))
        nav.push(Key("notices"))
        assertEquals(2, nav.current.size)
    }

    @Test fun lockedSessionDropsAccountRoutesFromEveryTabAndKeepsRoots() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        nav.select(2)
        nav.push(AccountKey("contacts"))
        nav.push(Key("notices"))
        nav.push(AccountKey("tasks"))
        nav.dropAccountScoped(RoutePolicy()::isAccountScoped)
        assertEquals(listOf(more, Key("notices")), nav.stacks[2])
        assertEquals(listOf(messages), nav.stacks[0])
        assertEquals(listOf(groups), nav.stacks[1])
        assertEquals(2, nav.selected)
    }

    @Test fun compactShowsOnlyTheDetailAndTwoPaneShowsTheListBesideIt() {
        val stack = listOf<NavKey>(messages, AccountKey("chat-a"))
        val roles = mapOf<NavKey, PaneRole>(messages to PaneRole.LIST, AccountKey("chat-a") to PaneRole.DETAIL)
        val compact = paneLayout(stack, twoPane = false) { roles[it] }
        assertEquals(AccountKey("chat-a"), compact.single)
        assertFalse(compact.twoPane)
        assertEquals(listOf<NavKey>(messages), compact.previous)
        val expanded = paneLayout(stack, twoPane = true) { roles[it] }
        assertEquals(messages, expanded.list)
        assertEquals(AccountKey("chat-a"), expanded.detail)
        assertEquals(listOf<NavKey>(messages), expanded.previous)
    }

    @Test fun compactToExpandedAndBackKeepsTheSelectedConversation() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        val roles = mapOf<NavKey, PaneRole>(messages to PaneRole.LIST, AccountKey("chat-a") to PaneRole.DETAIL)
        // The window changes width; the stack, and with it the selection, is untouched.
        val unfolded = paneLayout(nav.current, twoPane = true) { roles[it] }
        assertEquals(AccountKey("chat-a"), unfolded.detail)
        val folded = paneLayout(nav.current, twoPane = false) { roles[it] }
        assertEquals(AccountKey("chat-a"), folded.single)
    }

    @Test fun backInTwoPanesClearsTheSelectionAndKeepsTheList() {
        val nav = navigator()
        nav.openDetail(AccountKey("chat-a"))
        nav.pop()
        val layout = paneLayout(nav.current, twoPane = true) { if (it == messages) PaneRole.LIST else PaneRole.DETAIL }
        assertEquals(messages, layout.list)
        assertNull(layout.detail)
        assertTrue(layout.previous.isEmpty())
    }

    @Test fun detailWithoutItsListUsesOnePane() {
        val layout = paneLayout(listOf<NavKey>(more, AccountKey("chat")), twoPane = true) {
            if (it == more) null else PaneRole.DETAIL
        }
        assertEquals(AccountKey("chat"), layout.single)
    }

    @Test fun windowClassesFollowTheWidthThresholds() {
        assertEquals(WidthClass.COMPACT, AdaptiveSpec(393, 852).widthClass)
        assertEquals(WidthClass.MEDIUM, AdaptiveSpec(700, 840).widthClass)
        assertEquals(WidthClass.EXPANDED, AdaptiveSpec(1000, 760).widthClass)
        assertFalse(AdaptiveSpec(600, 900).twoPane)
        assertTrue(AdaptiveSpec(700, 840).twoPane)
        assertTrue(AdaptiveSpec(393, 400).shortHeight)
        assertEquals(NavigationForm.RAIL, AdaptiveSpec(700, 840).navigationForm)
        assertEquals(NavigationForm.BOTTOM_BAR, AdaptiveSpec(360, 800).navigationForm)
        assertEquals(NavigationForm.BOTTOM_BAR, AdaptiveSpec(599, 800).navigationForm)
        assertEquals(NavigationForm.RAIL, AdaptiveSpec(600, 800).navigationForm)
        assertEquals(NavigationForm.RAIL, AdaptiveSpec(1000, 760).navigationForm)
    }

    @Test fun theListPaneNeverOutgrowsTheDetail() {
        // 700 dp window minus an 80 dp rail: the panes share 620 dp.
        assertEquals(279f, listPaneWidth(620.dp, 393.dp).value, 0.01f)
        assertEquals(393f, listPaneWidth(1000.dp, 393.dp).value, 0.01f)
        assertTrue(listPaneWidth(620.dp, 393.dp) < 620.dp - listPaneWidth(620.dp, 393.dp))
    }
}
