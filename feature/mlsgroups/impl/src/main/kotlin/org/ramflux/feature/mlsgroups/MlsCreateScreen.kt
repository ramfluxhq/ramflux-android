// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import org.ramflux.data.MlsNameError
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitSwitch
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.feature.mlsgroups.impl.R

/** The create form: the name (1 to 64 characters), approval (on by default) with its help, the size line and Create. */
@Composable
fun MlsCreateScreen(state: MlsCreateUi, onAction: (MlsCreateAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Column(modifier.fillMaxSize().background(theme.bg)) {
        KitPageHeader(stringResource(R.string.group_create_title), onBack = { onAction(MlsCreateAction.Back) }, tag = "mls-create-header")
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(theme.gap.dp),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            KitTextField(
                value = state.name,
                onValue = { onAction(MlsCreateAction.Name(it)) },
                label = stringResource(R.string.group_create_name),
                error = when (state.nameError) {
                    MlsNameError.REQUIRED -> stringResource(R.string.group_create_name_required)
                    MlsNameError.TOO_LONG -> stringResource(R.string.group_create_name_too_long)
                    null -> null
                },
                enabled = !state.creating,
                tag = "mls-create-name"
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitMeta(stringResource(R.string.group_create_approval), Modifier.weight(1f))
                KitSwitch(
                    state.approval,
                    { onAction(MlsCreateAction.Approval(it)) },
                    enabled = !state.creating,
                    label = stringResource(R.string.group_create_approval),
                    tag = "mls-create-approval"
                )
            }
            KitMeta(
                if (state.approval) {
                    stringResource(
                        R.string.group_create_approval_on_help
                    )
                } else {
                    stringResource(R.string.group_create_approval_off_help)
                },
                tag = "mls-create-help"
            )
            KitMeta(stringResource(R.string.group_create_size), tag = "mls-create-size")
            state.failure?.let { KitMeta(failureText(it), tone = theme.danger, tag = "mls-create-error") }
            KitButton(
                if (state.creating) stringResource(R.string.group_create_creating) else stringResource(R.string.group_create_submit),
                { onAction(MlsCreateAction.Submit) },
                Modifier.fillMaxWidth(),
                enabled = !state.creating,
                loading = state.creating,
                tag = "mls-create-submit"
            )
        }
    }
}
