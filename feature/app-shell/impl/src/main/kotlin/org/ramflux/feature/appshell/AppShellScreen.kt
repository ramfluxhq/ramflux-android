// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ramflux.data.Appearance
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.generated.*

/** Pure presentation: all business policy, I/O and unavailable decisions live in the state holder/data layer. */
@Composable
fun AppShellScreen(state: AppShellState, onAction: (AppShellAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = theme.column_max.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RFPageHeader("Ramflux", state.status, theme, leading = {}, trailing = {})
                if (state.loading) RFStateBlock("Please wait", state.status, RFStateKind.Loading, theme, action = {})
                state.error?.let { RFStateBlock(it.title, it.description, RFStateKind.Error, theme, action = {}) }
                if (state.canRetry) {
                    RFActionButton(
                        "retry",
                        "Try again",
                        RFButtonKind.Primary,
                        theme,
                        onAction = { onAction(AppShellAction.Retry) }
                    )
                }
                RFSectionHeader("Get started", theme, trailing = {})
                for (destination in listOf(state.onboarding, state.conversations, state.contacts, state.settings)) {
                    RFListRow(
                        destination.feature.id,
                        destination.title,
                        "",
                        theme,
                        disabled = !state.ready,
                        leading = {},
                        trailing = { Text("›") },
                        onAction = { onAction(AppShellAction.Open(destination.feature)) }
                    )
                }
                state.notice?.let { notice ->
                    RFStateBlock(
                        notice.title,
                        notice.description,
                        if (notice.kind == ShellNoticeKind.UNAVAILABLE) RFStateKind.Empty else RFStateKind.Error,
                        theme,
                        action = {
                            TextButton(onClick = { onAction(AppShellAction.DismissNotice) }) { Text("Dismiss") }
                        }
                    )
                }
                RFSectionHeader("Appearance", theme, trailing = {})
                for (appearance in Appearance.entries) Row {
                    RadioButton(selected = appearance == state.appearance, enabled = !state.savingAppearance, onClick = {
                        onAction(AppShellAction.ChangeAppearance(appearance))
                    })
                    TextButton(enabled = !state.savingAppearance, onClick = { onAction(AppShellAction.ChangeAppearance(appearance)) }) {
                        Text(
                            when (appearance) {
                                Appearance.SYSTEM -> "Use device setting"
                                Appearance.LIGHT -> "Light"
                                Appearance.DARK -> "Dark"
                            }
                        )
                    }
                }
            }
        }
    }
}
