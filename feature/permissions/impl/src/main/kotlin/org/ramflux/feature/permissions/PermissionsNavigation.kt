// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.data.*
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.permissions.impl.R

@Composable
fun PermissionsNavigation(
    route: PermissionsRoute,
    onBack: () -> Unit,
    onReady: () -> Unit = onBack,
    onSelection: (String) -> Unit = {},
    vm: PermissionsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val back by rememberUpdatedState(onBack)
    val ready by rememberUpdatedState(onReady)
    val selection by rememberUpdatedState(onSelection)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onAction(PermissionsAction.SystemFinished)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onAction(PermissionsAction.SystemFinished)
    }
    val photo = rememberLauncherForActivityResult(PermissionsPlatform.photoPicker()) { uri ->
        vm.onAction(PermissionsAction.Picked(PermissionsPlatform.validSelection(uri)))
        if (PermissionsPlatform.validSelection(uri)) selection(uri.toString())
    }
    val file = rememberLauncherForActivityResult(PermissionsPlatform.filePicker()) { uri ->
        vm.onAction(PermissionsAction.Picked(PermissionsPlatform.validSelection(uri)))
        if (PermissionsPlatform.validSelection(uri)) selection(uri.toString())
    }
    val labels = NotificationChannelLabels(
        stringResource(R.string.permissions_messages_channel),
        stringResource(R.string.permissions_calls_channel),
        stringResource(R.string.permissions_system_channel)
    )
    LaunchedEffect(vm, route) { vm.onAction(PermissionsAction.Open(route.entry, route.createdAccountId)) }
    LaunchedEffect(vm, labels) { vm.onAction(PermissionsAction.Channels(labels)) }
    LifecycleResumeEffect(vm) {
        vm.onAction(PermissionsAction.Visible(true))
        onPauseOrDispose { vm.onAction(PermissionsAction.Visible(false)) }
    }
    BackHandler { vm.onAction(PermissionsAction.Back) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            if (event != PermissionsEvent.Back && !vm.isCurrent()) return@collect
            try {
                when (event) {
                    PermissionsEvent.Back -> back()
                    PermissionsEvent.Ready -> ready()
                    PermissionsEvent.OpenSettings -> settings.launch(PermissionsPlatform.appSettings(context))
                    is PermissionsEvent.Pick -> if (event.photo) {
                        photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        file.launch(arrayOf(OsPickerMimeTypes.FILES))
                    }
                    is PermissionsEvent.Request -> if (vm.consumeRequest(event)) {
                        if (event.permission == OsPermission.BATTERY) {
                            settings.launch(PermissionsPlatform.batterySettings())
                        } else {
                            val permission = PermissionsPlatform.permissionName(event.permission)
                            val activity = PermissionsPlatform.activity(context)
                            if (permission == null || activity == null) {
                                vm.onAction(PermissionsAction.SystemFailed)
                            } else {
                                // Rationale never bypasses the host explanation; all denials return to Settings.
                                if (activity.shouldShowRequestPermissionRationale(permission)) {
                                    vm.onAction(PermissionsAction.SystemFinished)
                                } else {
                                    request.launch(permission)
                                }
                            }
                        }
                    }
                }
            } catch (
                _: android.content.ActivityNotFoundException
            ) {
                vm.onAction(
                    if (event is PermissionsEvent.Request && event.permission == OsPermission.BATTERY) {
                        PermissionsAction.BatterySettingsUnavailable
                    } else {
                        PermissionsAction.SystemFailed
                    }
                )
            } catch (_: SecurityException) {
                vm.onAction(PermissionsAction.SystemFailed)
            }
        }
    }
    PermissionsScreen(state, vm::onAction)
}
