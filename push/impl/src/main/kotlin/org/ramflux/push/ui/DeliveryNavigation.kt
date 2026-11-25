// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DeliveryNavigation(onBack: () -> Unit, modifier: Modifier = Modifier, vm: DeliveryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    // Every return to the page re-reads the settings, so a change made in the system screens shows at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    DeliveryScreen(
        state,
        onBack = onBack,
        onOpen = { vm.open(context, it) },
        onConfirm = vm::confirm,
        onUndo = vm::undo,
        onOpenAppSettings = { vm.openAppSettings(context) },
        modifier = modifier
    )
}
