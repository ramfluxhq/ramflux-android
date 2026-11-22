// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.impl.R

@Composable
fun PermissionsScreen(state: PermissionsState, onAction: (PermissionsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    // The explanation dialog is capped by the space the screen is given, never by the window size.
    BoxWithConstraints(modifier) {
        val dialogMaxHeight = maxHeight * DIALOG_HEIGHT_FRACTION
        Scaffold(modifier = Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize()) {
                    KitPageHeader(
                        stringResource(R.string.permissions_title),
                        onBack = { onAction(PermissionsAction.Back) },
                        actionLabel = stringResource(R.string.permissions_refresh),
                        onAction = { if (!state.loading) onAction(PermissionsAction.Refresh) }
                    )
                    if (state.problem == PermissionsProblem.BATTERY_SETTINGS_UNAVAILABLE) {
                        Text(stringResource(R.string.permissions_battery_settings_unavailable))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("permissions-body")) {
                        Text(stringResource(R.string.permissions_intro))
                        if (state.loading) Text(stringResource(R.string.permissions_loading))
                        if (state.failed) Text(stringResource(R.string.permissions_failure))
                        if (state.waiting) Text(stringResource(R.string.permissions_waiting))
                        if (state.unavailable) Text(stringResource(R.string.permissions_media_help))
                        if (state.needed) {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.permissions_needed),
                                stringResource(R.string.permissions_needed_help)
                            )
                        }
                        if (state.entry == PermissionEntry.SETTINGS) {
                            for (row in state.rows) {
                                KitRow(
                                    stringResource(permissionLabel(row.permission)),
                                    { onAction(PermissionsAction.Select(row.permission)) },
                                    value = stringResource(statusLabel(row.status)),
                                    enabled = !state.waiting && !state.loading,
                                    trailing = KitRowTrailing.None,
                                    testTag = row.permission.name
                                )
                                when (row.permission) {
                                    OsPermission.MICROPHONE, OsPermission.INCOMING_CALL -> Text(stringResource(R.string.permissions_media_help))
                                    OsPermission.PHOTOS_FILES -> Text(stringResource(R.string.permissions_picker_help))
                                    OsPermission.SAVE_PHOTO -> Text(stringResource(R.string.permissions_save_help))
                                    else -> Unit
                                }
                            }
                        }
                        if (state.entry == PermissionEntry.ATTACHMENTS) {
                            Text(stringResource(R.string.permissions_picker_help))
                            KitButton(
                                stringResource(R.string.permissions_photos),
                                { onAction(PermissionsAction.Pick(true)) },
                                kind = KitButtonKind.Secondary,
                                enabled = !state.waiting,
                                tag = "pick-photo"
                            )
                            KitButton(
                                stringResource(R.string.permissions_files),
                                { onAction(PermissionsAction.Pick(false)) },
                                kind = KitButtonKind.Secondary,
                                enabled = !state.waiting,
                                tag = "pick-file"
                            )
                            Text(stringResource(R.string.permissions_camera_unavailable))
                            if (state.selected) Text(stringResource(R.string.permissions_selection))
                        }
                    }
                    KitButton(
                        stringResource(R.string.permissions_settings),
                        { onAction(PermissionsAction.Settings) },
                        enabled = !state.waiting,
                        tag = "open-permission-settings"
                    )
                }
            }
        }
        state.explanation?.let { permission ->
            val title = stringResource(
                when (permission) {
                    OsPermission.NOTIFICATIONS -> R.string.permissions_notification_title
                    OsPermission.BATTERY -> R.string.permissions_battery_title
                    else -> R.string.permissions_camera_title
                }
            )
            val why = stringResource(
                when (permission) {
                    OsPermission.NOTIFICATIONS -> R.string.permissions_notification_why
                    OsPermission.BATTERY -> R.string.permissions_battery_why
                    else -> R.string.permissions_camera_why
                }
            )
            val without = stringResource(
                when (permission) {
                    OsPermission.NOTIFICATIONS -> R.string.permissions_notification_without
                    OsPermission.BATTERY -> R.string.permissions_battery_without
                    else -> R.string.permissions_camera_without
                }
            )
            val description = stringResource(R.string.permissions_confirm_description, title)
            HostConfirmationDialog(onDismissRequest = { onAction(PermissionsAction.Cancel) }) {
                Column(
                    Modifier.heightIn(max = dialogMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                        Text(title, color = theme.text)
                        Text(why)
                        Text(without)
                        if (permission != OsPermission.BATTERY) Text(stringResource(R.string.permissions_prompt_help))
                        HostConfirmationActions(
                            permission,
                            stringResource(
                                if (permission == OsPermission.NOTIFICATIONS) {
                                    R.string.permissions_not_now
                                } else {
                                    R.string.permissions_cancel
                                }
                            ),
                            stringResource(R.string.permissions_continue),
                            description,
                            !state.waiting,
                            onDeny = { onAction(PermissionsAction.Cancel) },
                            onConfirm = { onAction(PermissionsAction.Continue) }
                        )
                    }
                }
            }
        }
    }
}
internal fun permissionLabel(permission: OsPermission): Int = when (permission) {
    OsPermission.NOTIFICATIONS -> R.string.permissions_notifications
    OsPermission.MICROPHONE -> R.string.permissions_microphone
    OsPermission.CAMERA -> R.string.permissions_camera
    OsPermission.INCOMING_CALL -> R.string.permissions_incoming_call
    OsPermission.BATTERY -> R.string.permissions_battery
    OsPermission.SAVE_PHOTO -> R.string.permissions_save_photo
    OsPermission.PHOTOS_FILES -> R.string.permissions_photos_files
}
internal fun statusLabel(status: OsPermissionStatus): Int = when (status) {
    OsPermissionStatus.ALLOWED -> R.string.permissions_allowed
    OsPermissionStatus.DENIED -> R.string.permissions_denied
    OsPermissionStatus.NOT_ASKED -> R.string.permissions_not_asked
    OsPermissionStatus.NOT_REQUIRED -> R.string.permissions_not_required
    OsPermissionStatus.UNAVAILABLE -> R.string.permissions_unavailable
}

private const val DIALOG_HEIGHT_FRACTION = 0.8f
