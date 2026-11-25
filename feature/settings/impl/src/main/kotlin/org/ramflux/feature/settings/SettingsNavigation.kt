// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.data.clientDebugEvent

@Composable
fun SettingsNavigation(
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onConnections: () -> Unit,
    modifier: Modifier = Modifier,
    onPermissions: () -> Unit = {},
    onDelivery: () -> Unit = {},
    onBackup: () -> Unit = {},
    onStorage: (account: String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
    cardVm: MyContactCardViewModel = hiltViewModel()
) {
    var myCard by rememberSaveable { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val account by rememberUpdatedState(onAccount)
    val connections by rememberUpdatedState(onConnections)
    val permissions by rememberUpdatedState(onPermissions)
    val delivery by rememberUpdatedState(onDelivery)
    val backup by rememberUpdatedState(onBackup)
    val storage by rememberUpdatedState(onStorage)
    BackHandler { vm.onAction(SettingsAction.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(SettingsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                SettingsEvent.Back -> back()
                SettingsEvent.OpenAccount -> {
                    clientDebugEvent("settings.event.open-account")
                    account()
                }
                SettingsEvent.Connections -> connections()
                SettingsEvent.Permissions -> permissions()
                SettingsEvent.Delivery -> delivery()
                SettingsEvent.Backup -> backup()
                is SettingsEvent.Storage -> storage(event.accountId)
                SettingsEvent.MyCard -> {
                    myCard = true
                    cardVm.load()
                }
            }
        }
    }
    Box(modifier) {
        SettingsScreen(state, vm::onAction, drawInvitation = vm::drawInvitation, drawInvitationQr = vm::drawInvitationQr)
        if (myCard) {
            val card by cardVm.state.collectAsStateWithLifecycle()
            MyContactCard(card, cardVm::lendQr, onRetry = cardVm::load) {
                myCard = false
                cardVm.close()
            }
        }
    }
}
