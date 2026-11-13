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
import org.ramflux.feature.appshell.api.*

/** Navigation owns one-shot collection. Feature screens own no coroutine or side effect. */
@Composable
fun AppShellNavigation(vm: AppShellViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(ShellHome)
    val window = LocalActivity.current?.window
    val dark = when (state.appearance) {
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
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AppShellEvent.Navigate -> stack.add(FeatureRoute(event.featureId))
            }
        }
    }
    RamfluxTheme(dark) {
        NavDisplay(
            backStack = stack,
            onBack = { if (stack.size > 1) stack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<ShellHome> { AppShellScreen(state, vm::onAction) }
                // No other feature implementation is installed in this module. Data never emits a success for it.
                entry<FeatureRoute> { AppShellScreen(state, vm::onAction) }
            }
        )
    }
}
