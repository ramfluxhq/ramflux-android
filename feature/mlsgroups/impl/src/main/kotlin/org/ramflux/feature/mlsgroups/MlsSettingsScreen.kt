// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsNameError
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitSwitch
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.feature.mlsgroups.impl.R

/**
 * The group settings (C23): the name field with Save and the approval switch. Both show the confirmed value from info; "Saving…"
 * shows from the committed reply until the `settings` event. Turning approval off asks first, with the agent hint.
 */
@Composable
fun MlsSettingsScreen(state: MlsSettingsUi, onAction: (MlsSettingsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(
                stringResource(R.string.group_settings_title),
                onBack = { onAction(MlsSettingsAction.Back) },
                tag = "mls-settings-header"
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                if (state.loading) KitMeta(stringResource(R.string.group_info_loading), tag = "mls-settings-loading")
                KitTextField(
                    value = state.draft,
                    onValue = { onAction(MlsSettingsAction.Draft(it)) },
                    label = stringResource(R.string.group_settings_name),
                    error = when (state.nameError) {
                        MlsNameError.REQUIRED -> stringResource(R.string.group_create_name_required)
                        MlsNameError.TOO_LONG -> stringResource(R.string.group_create_name_too_long)
                        null -> null
                    },
                    enabled = state.canEdit && !state.nameSaving,
                    tag = "mls-settings-name"
                )
                KitButton(
                    if (state.nameSaving) stringResource(R.string.group_settings_saving) else stringResource(R.string.group_settings_save),
                    { onAction(MlsSettingsAction.Save) },
                    Modifier.fillMaxWidth(),
                    enabled = state.canSave,
                    loading = state.nameSaving,
                    tag = "mls-settings-save"
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        KitMeta(stringResource(R.string.group_settings_approval), tag = "mls-settings-approval-label")
                        if (state.approvalSaving) {
                            KitMeta(
                                stringResource(R.string.group_settings_saving),
                                tag = "mls-settings-approval-saving"
                            )
                        }
                    }
                    KitSwitch(
                        checked = state.approval,
                        onChecked = { onAction(MlsSettingsAction.Approval(it)) },
                        enabled = state.canEdit && !state.busy && !state.paced && state.approvalKnown,
                        label = stringResource(R.string.group_settings_approval),
                        tag = "mls-settings-approval",
                        stateKnown = state.approvalKnown
                    )
                }
                if (state.busy && !state.nameSaving && !state.approvalSaving) {
                    KitMeta(stringResource(R.string.group_member_action_busy), tag = "mls-settings-busy")
                }
                if (state.paced) {
                    KitMeta(failureText(MlsFailure("paced", state.pacedUntil)), tone = theme.danger, tag = "mls-settings-paced")
                }
                state.failure?.let { KitMeta(failureText(it), tone = theme.danger, tag = "mls-settings-error") }
            }
        }
        if (state.confirmApprovalOff) {
            MlsAdminConfirm(
                approvalOffSpec(),
                "mls-settings-confirm",
                { onAction(MlsSettingsAction.ConfirmApprovalOff) },
                { onAction(MlsSettingsAction.CancelApprovalOff) }
            )
        }
    }
}
