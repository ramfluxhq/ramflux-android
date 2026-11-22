// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitTab
import org.ramflux.designsystem.kit.KitTabBar
import org.ramflux.feature.agents.api.AgentsRoute
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.groups.api.GroupsRoute
import org.ramflux.navigation.AdaptiveShell
import org.ramflux.navigation.PaneMetrics
import org.ramflux.navigation.RoutePolicy
import org.ramflux.navigation.TopLevelNavigator

/** Roots of the four top-level tabs, in tab-bar order. */
val SHELL_TAB_ROOTS: List<NavKey> = listOf(ConversationsRoute, GroupsRoute, AgentsRoute, MoreRoute)

/**
 * What a tab entry may do with navigation. Global keys (see [RoutePolicy.isGlobal]) open over the
 * whole shell; every other key is pushed on the selected tab.
 */
class ShellNavigation(
    private val navigator: TopLevelNavigator,
    private val policy: RoutePolicy,
    private val global: (NavKey) -> Unit,
    private val atRoot: (Int, Boolean) -> Unit,
    val shortcuts: ShellShortcutState
) {
    fun navigate(key: NavKey) = if (policy.isGlobal(key)) global(key) else navigator.push(key)

    /** Show [key] as the selection of the tab's list (beside it when there is room for two panes). */
    fun openDetail(key: NavKey) = navigator.openDetail(key)

    fun back() {
        navigator.pop()
    }

    /** A tab root with private sub-pages reports whether it shows its root page. */
    fun reportAtRoot(tab: Int, value: Boolean) = atRoot(tab, value)
}

/** Order and hidden ids of the More shortcuts, edited by the navigation editor. */
class ShellShortcutState(val order: String, val hidden: String, val onOrder: (String) -> Unit, val onHidden: (String) -> Unit)

/**
 * The tabbed shell. It owns the outer insets, the bottom bar (outside the navigation display) and
 * the pane width caps; the tab content comes from [entries].
 */
@Composable
fun RamfluxShell(
    navigator: TopLevelNavigator,
    policy: RoutePolicy,
    navigateGlobal: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.safeDrawing,
    entries: EntryProviderScope<NavKey>.(ShellNavigation) -> Unit = { shellEntries(it) }
) {
    val theme = LocalRFTheme.current
    var order by rememberSaveable { mutableStateOf(DEFAULT_SHELL_ORDER) }
    var hidden by rememberSaveable { mutableStateOf("") }
    val rootPages = remember { mutableStateListOf(*Array(SHELL_TAB_ROOTS.size) { true }) }
    val navigation = ShellNavigation(
        navigator,
        policy,
        navigateGlobal,
        { tab, value -> if (rootPages[tab] != value) rootPages[tab] = value },
        ShellShortcutState(order, hidden, { order = it }, { hidden = it })
    )
    val provider = entryProvider { entries(navigation) }
    AdaptiveShell(
        navigator = navigator,
        entryProvider = provider,
        metrics = PaneMetrics(dimensionResource(R.dimen.shell_list_pane_width), theme.column_max.dp),
        insets = insets,
        bottomBar = { KitTabBar(shellTabs(), navigator.selected, navigator::select, tag = "shell-tabs") },
        modifier = modifier.background(theme.bg),
        tabAtRoot = { rootPages[it] },
        separator = { PaneSeparator() },
        emptyDetail = {
            KitEmpty(
                "messages-square",
                stringResource(R.string.shell_detail_empty_title),
                stringResource(R.string.shell_detail_empty_body),
                tag = "shell-detail-empty"
            )
        }
    )
}

@Composable
private fun PaneSeparator() {
    val theme = LocalRFTheme.current
    androidx.compose.foundation.layout.Box(Modifier.width(Dp.Hairline).fillMaxHeight().background(theme.line))
}

@Composable
private fun shellTabs(): List<KitTab> = listOf(
    KitTab("messages-square", stringResource(R.string.shell_tab_messages)),
    KitTab("users-round", stringResource(R.string.shell_tab_groups)),
    KitTab("bot", stringResource(R.string.shell_tab_agents), latin = true),
    KitTab("ellipsis", stringResource(R.string.shell_tab_more))
)
