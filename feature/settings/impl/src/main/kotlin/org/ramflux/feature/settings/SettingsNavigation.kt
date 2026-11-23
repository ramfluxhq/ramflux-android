// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsNavigation(
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onConnections: () -> Unit,
    modifier: Modifier = Modifier,
    onPermissions: () -> Unit = {},
    onBackup: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val account by rememberUpdatedState(onAccount)
    val connections by rememberUpdatedState(onConnections)
    val permissions by rememberUpdatedState(onPermissions)
    val backup by rememberUpdatedState(onBackup)
    BackHandler { vm.onAction(SettingsAction.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(SettingsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                SettingsEvent.Back -> back()
                SettingsEvent.OpenAccount -> account()
                SettingsEvent.Connections -> connections()
                SettingsEvent.Permissions -> permissions()
                SettingsEvent.Backup -> backup()
            }
        }
    }
    SettingsScreen(state, vm::onAction, modifier = modifier, drawInvitation = vm::drawInvitation)
}
