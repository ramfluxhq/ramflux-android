// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/** The bottom window inset the shell hands to an entry declared with `pane(…, ownsBottomInset = true)`; zero otherwise. */
val LocalShellBottomInset = compositionLocalOf { NO_INSET }

private val NO_INSET = WindowInsets(0)

/** The bottom inset no bar takes, for the entries of the display to place. */
private val LocalUnclaimedBottomInset = compositionLocalOf { NO_INSET }

/**
 * Places the unclaimed bottom inset per entry: an entry that owns it receives it through
 * [LocalShellBottomInset] (beside a list pane too); every other entry is padded above it.
 */
private val BottomInsetDecorator = NavEntryDecorator<NavKey>(decorate = { entry ->
    val inset = LocalUnclaimedBottomInset.current
    if (entry.ownsBottomInset()) {
        Box(Modifier.fillMaxSize().consumeWindowInsets(inset)) {
            CompositionLocalProvider(LocalShellBottomInset provides inset) { entry.Content() }
        }
    } else {
        Box(Modifier.fillMaxSize().windowInsetsPadding(inset)) { entry.Content() }
    }
})

/**
 * The adaptive shell: one Navigation 3 display for the selected tab, with the top-level navigation
 * outside it so it can change form with the window. Every tab's entries stay decorated while the tab
 * is hidden, so switching tabs keeps each tab's saved state and ViewModels.
 *
 * The shell owns the outer window insets and the width caps; screens fill the pane they are given.
 * The top-level navigation follows [AdaptiveSpec.navigationForm]: the [bottomBar] at compact width and
 * the [rail] at medium and expanded widths. Both receive the bottom window inset to lay out themselves
 * (the bar reaches into the home-indicator area as the design frame does). At compact width the bottom
 * bar hides while the selected tab shows more than its root (or its root reports a sub-page through
 * [tabAtRoot]); with two panes it always stays visible, and the rail is always visible.
 */
@Composable
fun AdaptiveShell(
    navigator: TopLevelNavigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    metrics: PaneMetrics,
    insets: WindowInsets,
    bottomBar: @Composable (bottomInset: WindowInsets) -> Unit,
    modifier: Modifier = Modifier,
    rail: (@Composable (bottomInset: WindowInsets) -> Unit)? = null,
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
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        BottomInsetDecorator
                    ),
                    entryProvider = entryProvider
                )
            }
        }
        val useRail = rail != null && spec.navigationForm == NavigationForm.RAIL
        val bottomVisible = !useRail && (spec.twoPane || (navigator.atRoot && tabAtRoot(navigator.selected)))
        BackHandler(enabled = navigator.atRoot && navigator.selected != 0) { navigator.pop() }
        val bottomInset = insets.only(WindowInsetsSides.Bottom)
        val paddedEmpty: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().windowInsetsPadding(LocalUnclaimedBottomInset.current)) { emptyDetail() }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (useRail) Box(Modifier.fillMaxHeight().testTag("shell-rail")) { rail(bottomInset) }
                // The bar takes the bottom inset; without it each entry places the inset (see BottomInsetDecorator).
                val content = if (bottomVisible) Modifier.consumeWindowInsets(bottomInset) else Modifier
                Box(Modifier.weight(1f).fillMaxHeight().then(content)) {
                    CompositionLocalProvider(LocalUnclaimedBottomInset provides if (bottomVisible) NO_INSET else bottomInset) {
                        NavDisplay(
                            entries = decorated[navigator.selected],
                            sceneStrategies = listOf(PaneSceneStrategy(spec.twoPane, metrics, separator, paddedEmpty)),
                            onBack = { navigator.pop() }
                        )
                    }
                }
            }
            if (bottomVisible) Box(Modifier.fillMaxWidth().testTag("shell-bottom-bar")) { bottomBar(bottomInset) }
        }
    }
}
