// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun ContactsNavigation(onBack: () -> Unit, onSetupAccount: () -> Unit, vm: ContactsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    BackHandler { vm.onAction(ContactsAction.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(ContactsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                ContactsEvent.Back -> back()
                ContactsEvent.SetupAccount -> setup()
            }
        }
    }
    ContactsScreen(state, vm::onAction)
}
