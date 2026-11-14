// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun CardsNavigation(onBack: () -> Unit, vm: CardsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    BackHandler { vm.onAction(CardsAction.Back) }
    DisposableEffect(vm) {
        vm.onAction(CardsAction.Visible(true))
        onDispose { vm.onAction(CardsAction.Visible(false)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(CardsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                CardsEvent.Back -> back()
            }
        }
    }
    CardsScreen(state, vm::onAction)
}
