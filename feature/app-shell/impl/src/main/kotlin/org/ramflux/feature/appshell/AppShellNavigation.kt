// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import org.ramflux.data.Appearance
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.ThemeCatalog
import org.ramflux.feature.appshell.api.*
import org.ramflux.navigation.RoutePolicy
import org.ramflux.navigation.TopLevelNavigator

/**
 * Root navigation: the shell home plus global destinations shown over the whole shell. Tab content
 * lives in the per-tab stacks of [tabs]; while the session is locked, account-scoped keys are dropped
 * from the root stack and from every tab stack. Navigation owns one-shot collection.
 */
@Composable
fun AppShellNavigation(
    vm: AppShellViewModel = hiltViewModel(),
    routeForFeature: (String) -> NavKey = { FeatureRoute(it) },
    home: (@Composable (navigate: (NavKey) -> Unit) -> Unit)? = null,
    shellReady: Boolean = true,
    policy: RoutePolicy = RoutePolicy(),
    tabs: TopLevelNavigator? = null,
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
        val kept = routesWhileLocked(stack.toList(), false, policy::isAccountScoped)
        stack.toList().filterNot { it in kept }.forEach { stack.remove(it) }
        tabs?.dropAccountScoped(policy::isAccountScoped)
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
                    if (open != null) open { stack.add(it) } else ShellPage { AppShellScreen(state, vm::onAction, it) }
                }
                // Uninstalled destinations are refused by Data; the app installs supported public routes.
                entry<FeatureRoute> { ShellPage { AppShellScreen(state, vm::onAction, it) } }
            }
        )
    }
}

/**
 * The shell side of the screen contract for a page on the root stack (outside the tabs, for example
 * account setup): window insets, including the keyboard, and the width cap.
 */
@Composable
fun ShellPage(content: @Composable (Modifier) -> Unit) {
    val theme = LocalRFTheme.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = theme.column_max.dp))
    }
}
