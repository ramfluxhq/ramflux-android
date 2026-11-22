// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ramflux.data.Appearance
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.render
import org.ramflux.feature.app_shell.impl.R

/** Pure presentation: all business policy, I/O and unavailable decisions live in the state holder/data layer. */
@Composable
fun AppShellScreen(state: AppShellState, onAction: (AppShellAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    // Screen contract: the caller owns window insets and the width cap; the screen fills its pane.
    Surface(modifier.fillMaxSize(), color = theme.bg) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                val status = state.status.render()
                KitPageHeader(stringResource(R.string.ui_ramflux), subtitle = status.takeIf { it.isNotEmpty() })
                if (status.isNotEmpty()) Text(status, color = theme.secondary)
                if (state.loading) {
                    KitEmpty(
                        "loader",
                        stringResource(R.string.ui_please_wait),
                        status
                    )
                }
                state.error?.let {
                    KitEmpty("circle-alert", it.title.render(), it.description.render())
                }
                if (state.canRetry) {
                    KitButton(
                        stringResource(R.string.ui_try_again),
                        { onAction(AppShellAction.Retry) },
                        tag = "retry"
                    )
                }
                Row {
                    Text(stringResource(R.string.ui_get_started), Modifier.weight(1f), color = theme.secondary)
                }
                for (destination in (listOf(state.onboarding, state.conversations, state.contacts, state.settings) + state.additional)) {
                    KitRow(
                        destination.title.render(),
                        { onAction(AppShellAction.Open(destination.feature)) },
                        enabled = state.ready,
                        trailing = KitRowTrailing.None,
                        trailingContent = { Text(stringResource(R.string.ui_)) },
                        testTag = destination.feature.id
                    )
                }
                state.notice?.let { notice ->
                    KitEmpty(
                        if (notice.kind == ShellNoticeKind.UNAVAILABLE) "inbox" else "circle-alert",
                        notice.title.render(),
                        notice.description.render(),
                        action = stringResource(R.string.ui_dismiss),
                        onAction = { onAction(AppShellAction.DismissNotice) }
                    )
                }
                Row {
                    Text(stringResource(R.string.ui_appearance), Modifier.weight(1f), color = theme.secondary)
                }
                Column(Modifier.selectableGroup()) {
                    ThemeOption(
                        stringResource(R.string.ui_use_device_setting),
                        state.appearance == Appearance.SYSTEM && state.namedTheme == null,
                        !state.savingAppearance
                    ) {
                        onAction(AppShellAction.ChangeAppearance(Appearance.SYSTEM))
                    }
                    for (choice in state.themes) {
                        val selected = state.namedTheme == choice.id || (
                            state.namedTheme == null &&
                                (
                                    (state.appearance == Appearance.LIGHT && choice.id == "light") ||
                                        (state.appearance == Appearance.DARK && choice.id == "dark")
                                    )
                            )
                        ThemeOption(
                            choice.label.render(),
                            selected,
                            !state.savingAppearance
                        ) { onAction(AppShellAction.ChangeTheme(choice.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalRFTheme.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(theme.gap.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Spacer(Modifier.width(theme.gap.dp))
        Text(label)
    }
}
