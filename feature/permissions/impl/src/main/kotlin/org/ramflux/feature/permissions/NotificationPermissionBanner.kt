// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import org.ramflux.data.*
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.impl.R
import org.ramflux.generated.RFTrustBanner

@Composable
fun NotificationPermissionBanner(denied: Boolean, onSettings: () -> Unit) {
    if (denied) {
        RFTrustBanner(
            stringResource(R.string.permissions_notifications),
            stringResource(R.string.permissions_notifications_off),
            false,
            LocalRFTheme.current,
            icon = {},
            action = { TextButton(onClick = onSettings) { Text(stringResource(R.string.permissions_settings)) } }
        )
    }
}

@Composable
fun NotificationPermissionBannerNavigation(onSettings: () -> Unit, vm: PermissionsViewModel = hiltViewModel(key = "notification-banner")) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.onAction(PermissionsAction.Open(PermissionEntry.SETTINGS, null)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(PermissionsAction.Refresh) }
    val labels = NotificationChannelLabels(
        stringResource(R.string.permissions_messages_channel),
        stringResource(R.string.permissions_calls_channel),
        stringResource(R.string.permissions_system_channel)
    )
    LaunchedEffect(vm, labels) { vm.onAction(PermissionsAction.Channels(labels)) }
    NotificationPermissionBanner(
        state.rows.any {
            it.permission == OsPermission.NOTIFICATIONS && it.status == OsPermissionStatus.DENIED
        },
        onSettings
    )
}
