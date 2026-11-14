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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.impl.R
import org.ramflux.generated.*

@Composable
fun PermissionsScreen(state: PermissionsState, onAction: (PermissionsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val dialogMaxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.8f }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(R.string.permissions_title),
                    "",
                    theme,
                    leading = {
                        TextButton(
                            onClick = { onAction(PermissionsAction.Back) }
                        ) { Text(stringResource(R.string.permissions_back)) }
                    },
                    trailing = {
                        TextButton(enabled = !state.loading, onClick = { onAction(PermissionsAction.Refresh) }) {
                            Text(stringResource(R.string.permissions_refresh))
                        }
                    }
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("permissions-body")) {
                    Text(stringResource(R.string.permissions_intro))
                    if (state.loading) Text(stringResource(R.string.permissions_loading))
                    if (state.failed) Text(stringResource(R.string.permissions_failure))
                    if (state.waiting) Text(stringResource(R.string.permissions_waiting))
                    if (state.unavailable) Text(stringResource(R.string.permissions_media_help))
                    if (state.needed) {
                        RFStateBlock(
                            stringResource(R.string.permissions_needed),
                            stringResource(R.string.permissions_needed_help),
                            RFStateKind.Empty,
                            theme,
                            action = {}
                        )
                    }
                    if (state.entry == PermissionEntry.SETTINGS) {
                        for (row in state.rows) {
                            RFSettingsRow(
                                row.permission.name,
                                stringResource(permissionLabel(row.permission)),
                                stringResource(statusLabel(row.status)),
                                theme,
                                disabled = state.waiting || state.loading,
                                leading = {},
                                trailing = {},
                                onAction = { onAction(PermissionsAction.Select(row.permission)) }
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
                        RFActionButton(
                            "pick-photo",
                            stringResource(R.string.permissions_photos),
                            RFButtonKind.Secondary,
                            theme,
                            disabled = state.waiting,
                            onAction = { onAction(PermissionsAction.Pick(true)) }
                        )
                        RFActionButton(
                            "pick-file",
                            stringResource(R.string.permissions_files),
                            RFButtonKind.Secondary,
                            theme,
                            disabled = state.waiting,
                            onAction = { onAction(PermissionsAction.Pick(false)) }
                        )
                        Text(stringResource(R.string.permissions_camera_unavailable))
                        if (state.selected) Text(stringResource(R.string.permissions_selection))
                    }
                }
                RFActionButton(
                    "open-permission-settings",
                    stringResource(R.string.permissions_settings),
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.waiting,
                    onAction = { onAction(PermissionsAction.Settings) }
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
                RFSheetShell(title, why, theme, content = {
                    Text(without)
                    if (permission != OsPermission.BATTERY) Text(stringResource(R.string.permissions_prompt_help))
                }, actions = {
                    HostConfirmationActions(
                        permission,
                        stringResource(
                            if (permission == OsPermission.NOTIFICATIONS) R.string.permissions_not_now else R.string.permissions_cancel
                        ),
                        stringResource(R.string.permissions_continue),
                        description,
                        !state.waiting,
                        onDeny = { onAction(PermissionsAction.Cancel) },
                        onConfirm = { onAction(PermissionsAction.Continue) }
                    )
                })
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
