// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/**
 * The adaptive shell: one Navigation 3 display for the selected tab, with the top-level navigation
 * outside it so it can change form with the window. Every tab's entries stay decorated while the tab
 * is hidden, so switching tabs keeps each tab's saved state and ViewModels.
 *
 * The shell owns the outer window insets and the width caps; screens fill the pane they are given.
 * At compact width the bottom bar hides while the selected tab shows more than its root (or its root
 * reports a sub-page through [tabAtRoot]); with two panes or a rail it always stays visible.
 */
@Composable
fun AdaptiveShell(
    navigator: TopLevelNavigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    metrics: PaneMetrics,
    insets: WindowInsets,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    rail: (@Composable () -> Unit)? = null,
    tabAtRoot: (Int) -> Boolean = { true },
    separator: @Composable () -> Unit = {},
    emptyDetail: @Composable () -> Unit = {}
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("adaptive-shell")) {
        val spec = AdaptiveSpec(maxWidth.value.toInt(), maxHeight.value.toInt())
        val decorated = navigator.stacks.mapIndexed { index, stack ->
            key(index) {
                rememberDecoratedNavEntries(
                    backStack = stack,
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                    entryProvider = entryProvider
                )
            }
        }
        val useRail = rail != null && spec.navigationForm == NavigationForm.RAIL
        val bottomVisible = !useRail && (spec.twoPane || (navigator.atRoot && tabAtRoot(navigator.selected)))
        BackHandler(enabled = navigator.atRoot && navigator.selected != 0) { navigator.pop() }
        Column(Modifier.fillMaxSize().windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (useRail) Box(Modifier.fillMaxHeight().testTag("shell-rail")) { rail?.invoke() }
                val content = if (bottomVisible) {
                    Modifier.consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                } else {
                    Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))
                }
                Box(Modifier.weight(1f).fillMaxHeight().then(content)) {
                    NavDisplay(
                        entries = decorated[navigator.selected],
                        sceneStrategies = listOf(PaneSceneStrategy(spec.twoPane, metrics, separator, emptyDetail)),
                        onBack = { navigator.pop() }
                    )
                }
            }
            if (bottomVisible) {
                Box(Modifier.fillMaxWidth().testTag("shell-bottom-bar")) { bottomBar() }
                Spacer(Modifier.windowInsetsBottomHeight(insets))
            }
        }
    }
}
