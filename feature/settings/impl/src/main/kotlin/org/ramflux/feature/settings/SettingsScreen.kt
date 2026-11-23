// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.ramflux.data.AppLanguage
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.settings.impl.R

@Composable
fun SettingsScreen(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    drawInvitation: (Int, (CharArray) -> Unit) -> Unit = { _, _ -> }
) {
    val theme = LocalRFTheme.current
    // The root page (MOB-G02) shows the account in its first row; sub-pages name it under the title.
    val subtitle = state.accountLabel?.takeIf { it.isNotEmpty() && state.page != SettingsPage.GENERAL }
    val title = stringResource(
        when (state.page) {
            SettingsPage.GENERAL -> R.string.settings_settings
            SettingsPage.ACCOUNT -> R.string.settings_account_security
            SettingsPage.PRIVACY -> R.string.settings_privacy
            SettingsPage.PAIRING -> R.string.settings_pairing_title
            SettingsPage.DIAGNOSTICS -> R.string.settings_diagnostics_about
            SettingsPage.APPEARANCE -> R.string.settings_appearance_language
            SettingsPage.LANGUAGE -> R.string.settings_language
            SettingsPage.DEVICES -> R.string.settings_devices
        }
    )
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                KitPageHeader(
                    title,
                    subtitle = subtitle,
                    onBack = { onAction(SettingsAction.Back) },
                    actionLabel = if (state.page == SettingsPage.GENERAL) null else stringResource(R.string.settings_refresh),
                    onAction = if (state.page == SettingsPage.GENERAL) null else ({ if (!state.loading) onAction(SettingsAction.Refresh) })
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading) {
                        item("loading") {
                            KitEmpty("loader", stringResource(R.string.settings_loading), "")
                        }
                    }
                    state.problem?.let { message ->
                        item("error") {
                            KitEmpty("circle-alert", stringResource(R.string.settings_error), message.render())
                        }
                    }
                    state.unavailable?.let { message ->
                        item("unavailable") {
                            KitEmpty("inbox", stringResource(R.string.settings_unavailable), message.render())
                        }
                    }
                    if (state.accountRequired && state.page in setOf(SettingsPage.GENERAL, SettingsPage.ACCOUNT, SettingsPage.DEVICES)) {
                        item("account-required") {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.settings_account),
                                stringResource(R.string.settings_account_needed),
                                action = stringResource(R.string.settings_open_account),
                                onAction = { onAction(SettingsAction.OpenAccount) }
                            )
                        }
                    }
                    when (state.page) {
                        SettingsPage.GENERAL -> generalPage(state, onAction)
                        SettingsPage.ACCOUNT -> accountPage(state, onAction)
                        SettingsPage.PRIVACY -> privacyPage(state, onAction)
                        SettingsPage.PAIRING -> pairingPage(state, onAction, drawInvitation)
                        SettingsPage.DIAGNOSTICS -> diagnosticsPage(state, { AboutVersion() }) { label, value -> StatusRow(label, value) }
                        SettingsPage.LANGUAGE -> languagePage(state, onAction) { id, label, selected, enabled, select ->
                            ThemeChoiceRow(id, label, selected, enabled, select)
                        }
                        SettingsPage.APPEARANCE -> {
                            item("language") {
                                KitRow(
                                    stringResource(R.string.settings_language),
                                    { onAction(SettingsAction.Page(SettingsPage.LANGUAGE)) },
                                    icon = "languages",
                                    value = if (state.savedLanguage == AppLanguage.SYSTEM) {
                                        stringResource(
                                            R.string.settings_system
                                        )
                                    } else {
                                        languageName(state.savedLanguage.tag)
                                    },
                                    testTag = "language"
                                )
                            }
                            item("appearance-help") { Text(stringResource(R.string.settings_appearance_help)) }
                            item("system") {
                                ThemeChoiceRow(
                                    "theme:system",
                                    stringResource(R.string.settings_system),
                                    state.selectedTheme == null,
                                    !state.savingTheme
                                ) { onAction(SettingsAction.Theme(null)) }
                            }
                            items(state.themes, key = { "theme:" + it.id }, contentType = { "theme" }) { choice ->
                                ThemeChoiceRow(
                                    "theme:" + choice.id,
                                    choice.label.render(),
                                    state.selectedTheme == choice.id,
                                    !state.savingTheme
                                ) { onAction(SettingsAction.Theme(choice.id)) }
                            }
                        }
                        SettingsPage.DEVICES -> {
                            item("link-device") {
                                KitRow(
                                    stringResource(R.string.settings_pairing_title),
                                    { onAction(SettingsAction.Page(SettingsPage.PAIRING)) },
                                    icon = "link",
                                    value = stringResource(R.string.settings_pairing_row_help),
                                    enabled = state.accountLabel != null,
                                    testTag = "link-device"
                                )
                            }
                            state.deviceProblem?.let { problem -> item("device-error") { Text(problem.render()) } }
                            if (state.devicesLoaded && state.devices.isEmpty()) {
                                item("no-devices") { Text(stringResource(R.string.settings_empty_devices)) }
                            }
                            state.confirmRevoke?.let { device ->
                                item("confirm-revoke") {
                                    Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                                        KitSecurityWarning(
                                            stringResource(R.string.settings_revoke_confirm_title),
                                            stringResource(R.string.settings_revoke_confirm_body),
                                            KitWarningTone.Danger
                                        )
                                        LtrCode(device, tag = "revoke-device-id")
                                        KitButton(
                                            stringResource(R.string.settings_revoke_confirm),
                                            { onAction(SettingsAction.ConfirmRevoke) },
                                            kind = KitButtonKind.Destructive,
                                            tag = "confirm-revoke"
                                        )
                                        KitButton(
                                            stringResource(R.string.settings_cancel),
                                            { onAction(SettingsAction.CancelRevoke) },
                                            kind = KitButtonKind.Secondary,
                                            tag = "cancel-revoke"
                                        )
                                    }
                                }
                            }
                            items(state.devices, key = { "device:" + it.id }, contentType = { "device" }) { device ->
                                KitRow(
                                    device.id,
                                    {},
                                    value = stringResource(
                                        if (device.local) R.string.settings_current_device else R.string.settings_other_device
                                    ),
                                    leading = { KitAvatar(device.id.take(1), 36f, KitAvatarKind.Person) },
                                    trailingContent = {
                                        if (!device.local) {
                                            TextButton(onClick = { onAction(SettingsAction.Revoke(device.id)) }) {
                                                Text(stringResource(R.string.settings_revoke))
                                            }
                                        }
                                    },
                                    testTag = device.id
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutVersion() {
    val context = LocalContext.current
    val info = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
    val name = info?.versionName ?: return
    Column(Modifier.testTag("about-version")) {
        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.settings_version, name, info.longVersionCode))
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
        Modifier.fillMaxWidth().heightIn(min = theme.composer_min.dp).testTag(id)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}
