// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import org.ramflux.data.Appearance
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.ThemeCatalog
import org.ramflux.feature.appshell.api.*

/** Navigation owns one-shot collection. Feature screens own no coroutine or side effect. */
@Composable
fun AppShellNavigation(
    vm: AppShellViewModel = hiltViewModel(),
    routeForFeature: (String) -> NavKey = { FeatureRoute(it) },
    home: (@Composable (navigate: (NavKey) -> Unit) -> Unit)? = null,
    shellReady: Boolean = true,
    isAccountRoute: (NavKey) -> Boolean = { false },
    featureEntries: EntryProviderScope<NavKey>.(onBack: () -> Unit, navigate: (NavKey) -> Unit) -> Unit = { _, _ -> }
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(ShellHome)
    val window = LocalActivity.current?.window
    val dark = ThemeCatalog.find(state.namedTheme)?.dark ?: when (state.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.DARK -> true
        Appearance.LIGHT -> false
    }
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val activity = LocalActivity.current
    val currentRoute by rememberUpdatedState(routeForFeature)
    val shellHome = home
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AppShellEvent.Navigate -> stack.add(currentRoute(event.featureId))
            }
        }
    }
    LaunchedEffect(shellReady) {
        if (shellReady) return@LaunchedEffect
        val kept = routesWhileLocked(stack.toList(), false, isAccountRoute)
        stack.toList().filterNot { it in kept }.forEach { stack.remove(it) }
    }
    RamfluxTheme(dark, themeId = state.namedTheme) {
        NavDisplay(
            backStack = stack,
            onBack = {
                if (stack.size > 1) {
                    stack.removeLastOrNull()
                } else if (shellHome != null) {
                    activity?.finish()
                }
            },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                featureEntries({ if (stack.size > 1) stack.removeLastOrNull() }, { stack.add(it) })
                entry<ShellHome> {
                    val open = shellHome
                    if (open != null) open { stack.add(it) } else AppShellScreen(state, vm::onAction)
                }
                // Uninstalled destinations are refused by Data; the app installs supported public routes.
                entry<FeatureRoute> { AppShellScreen(state, vm::onAction) }
            }
        )
    }
}
