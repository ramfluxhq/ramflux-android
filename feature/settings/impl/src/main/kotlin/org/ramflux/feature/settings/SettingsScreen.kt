// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.*
import org.ramflux.feature.settings.impl.R
import org.ramflux.generated.*

@Composable
fun SettingsScreen(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val title = stringResource(
        when (state.page) {
            SettingsPage.GENERAL -> R.string.settings_settings
            SettingsPage.APPEARANCE -> R.string.settings_appearance
            SettingsPage.DEVICES -> R.string.settings_devices
            SettingsPage.BACKUP -> R.string.settings_backup
        }
    )
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    title,
                    state.accountLabel ?: "",
                    theme,
                    leading = { TextButton(onClick = { onAction(SettingsAction.Back) }) { Text(stringResource(R.string.settings_back)) } },
                    trailing = {
                        TextButton(
                            enabled = !state.loading,
                            onClick = { onAction(SettingsAction.Refresh) }
                        ) { Text(stringResource(R.string.settings_refresh)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading) {
                        item("loading") {
                            RFStateBlock(
                                stringResource(R.string.settings_loading),
                                "",
                                RFStateKind.Loading,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.problem?.let { message ->
                        item("error") {
                            RFStateBlock(
                                stringResource(R.string.settings_error),
                                message.render(),
                                RFStateKind.Error,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.unavailable?.let { message ->
                        item("unavailable") {
                            RFStateBlock(
                                stringResource(R.string.settings_unavailable),
                                message.render(),
                                RFStateKind.Empty,
                                theme,
                                action = {}
                            )
                        }
                    }
                    when (state.page) {
                        SettingsPage.GENERAL -> {
                            if (state.accountRequired) {
                                item("account-required") {
                                    RFStateBlock(
                                        stringResource(R.string.settings_account),
                                        stringResource(R.string.settings_account_needed),
                                        RFStateKind.Empty,
                                        theme,
                                        action = {
                                            TextButton(
                                                onClick = { onAction(SettingsAction.OpenAccount) }
                                            ) { Text(stringResource(R.string.settings_open_account)) }
                                        }
                                    )
                                }
                            }
                            item("appearance") {
                                RFSettingsRow(
                                    "appearance",
                                    stringResource(R.string.settings_appearance),
                                    "",
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(SettingsAction.Page(SettingsPage.APPEARANCE)) }
                                )
                            }
                            item("devices") {
                                RFSettingsRow(
                                    "devices",
                                    stringResource(R.string.settings_devices),
                                    "",
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(SettingsAction.Page(SettingsPage.DEVICES)) }
                                )
                            }
                            item("backup") {
                                RFSettingsRow(
                                    "backup",
                                    stringResource(R.string.settings_backup),
                                    "",
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(SettingsAction.Page(SettingsPage.BACKUP)) }
                                )
                            }
                            item("connections") {
                                RFSettingsRow(
                                    "connections",
                                    stringResource(R.string.settings_connections),
                                    stringResource(R.string.settings_manage_connections),
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(SettingsAction.Connections) }
                                )
                            }
                            state.statusProblem?.let { problem -> item("status-error") { Text(problem.render()) } }
                            state.status?.let { status ->
                                item("secure-sessions") { StatusRow(stringResource(R.string.settings_sessions), status.sessions) }
                                item("sync") { StatusRow(stringResource(R.string.settings_sync), status.sync) }
                                item("node") { StatusRow(stringResource(R.string.settings_node), status.node) }
                                item("last-run") { StatusRow(stringResource(R.string.settings_last_run), status.lastRun) }
                            }
                        }
                        SettingsPage.APPEARANCE -> {
                            item("appearance-help") { Text(stringResource(R.string.settings_appearance_help)) }
                            item("system") {
                                ThemeChoiceRow(
                                    "system",
                                    stringResource(R.string.settings_system),
                                    state.selectedTheme == null,
                                    !state.savingTheme
                                ) { onAction(SettingsAction.Theme(null)) }
                            }
                            items(state.themes, key = { "theme:" + it.id }, contentType = { "theme" }) { choice ->
                                ThemeChoiceRow(
                                    choice.id,
                                    choice.label.render(),
                                    state.selectedTheme == choice.id,
                                    !state.savingTheme
                                ) { onAction(SettingsAction.Theme(choice.id)) }
                            }
                        }
                        SettingsPage.DEVICES -> {
                            state.deviceProblem?.let { problem -> item("device-error") { Text(problem.render()) } }
                            if (state.devicesLoaded && state.devices.isEmpty()) {
                                item(
                                    "no-devices"
                                ) { Text(stringResource(R.string.settings_empty_devices)) }
                            }
                            items(state.devices, key = { "device:" + it.id }, contentType = { "device" }) { device ->
                                RFIdentityRow(
                                    device.id,
                                    device.id,
                                    stringResource(if (device.local) R.string.settings_current_device else R.string.settings_other_device),
                                    theme,
                                    avatar = { RFAvatar(device.id.take(1), RFAvatarKind.Person, theme) },
                                    badge = {},
                                    trailing = {
                                        if (!device.local) {
                                            TextButton(
                                                onClick = { onAction(SettingsAction.Revoke(device.id)) }
                                            ) { Text(stringResource(R.string.settings_revoke)) }
                                        }
                                    }
                                )
                            }
                        }
                        SettingsPage.BACKUP -> item("backup-info") { Text(stringResource(R.string.settings_backup_help)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: UiText) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value.render())
    }
}

@Composable
private fun ThemeChoiceRow(id: String, label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val theme = LocalRFTheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = theme.composer_min.dp).testTag("theme:" + id)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}
