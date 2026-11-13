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
import org.ramflux.designsystem.render
import org.ramflux.feature.app_shell.impl.R
import org.ramflux.generated.*

/** Pure presentation: all business policy, I/O and unavailable decisions live in the state holder/data layer. */
@Composable
fun AppShellScreen(state: AppShellState, onAction: (AppShellAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = theme.column_max.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                RFPageHeader(stringResource(R.string.ui_ramflux), state.status.render(), theme, leading = {}, trailing = {})
                if (state.loading) {
                    RFStateBlock(
                        stringResource(R.string.ui_please_wait),
                        state.status.render(),
                        RFStateKind.Loading,
                        theme,
                        action = {}
                    )
                }
                state.error?.let { RFStateBlock(it.title.render(), it.description.render(), RFStateKind.Error, theme, action = {}) }
                if (state.canRetry) {
                    RFActionButton(
                        "retry",
                        stringResource(R.string.ui_try_again),
                        RFButtonKind.Primary,
                        theme,
                        onAction = { onAction(AppShellAction.Retry) }
                    )
                }
                RFSectionHeader(stringResource(R.string.ui_get_started), theme, trailing = {})
                for (destination in listOf(state.onboarding, state.conversations, state.contacts, state.settings)) {
                    RFListRow(
                        destination.feature.id,
                        destination.title.render(),
                        "",
                        theme,
                        disabled = !state.ready,
                        leading = {},
                        trailing = { Text(stringResource(R.string.ui_)) },
                        onAction = { onAction(AppShellAction.Open(destination.feature)) }
                    )
                }
                state.notice?.let { notice ->
                    RFStateBlock(
                        notice.title.render(),
                        notice.description.render(),
                        if (notice.kind == ShellNoticeKind.UNAVAILABLE) RFStateKind.Empty else RFStateKind.Error,
                        theme,
                        action = {
                            TextButton(onClick = { onAction(AppShellAction.DismissNotice) }) { Text(stringResource(R.string.ui_dismiss)) }
                        }
                    )
                }
                RFSectionHeader(stringResource(R.string.ui_appearance), theme, trailing = {})
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
