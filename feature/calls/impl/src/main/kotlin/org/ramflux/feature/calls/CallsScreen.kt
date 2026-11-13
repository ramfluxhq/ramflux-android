// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.*
import org.ramflux.feature.calls.impl.R
import org.ramflux.generated.*
@Composable
fun CallsScreen(state: CallsState, onAction: (CallsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(if (state.selected == null) R.string.calls_history else R.string.calls_call),
                    state.account.orEmpty(),
                    theme,
                    leading = { TextButton(onClick = { onAction(CallsAction.Back) }) { Text(stringResource(R.string.calls_back)) } },
                    trailing = {
                        TextButton(enabled = !state.loading && !state.busy, onClick = {
                            onAction(CallsAction.Refresh)
                        }) { Text(stringResource(R.string.calls_refresh)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            RFStateBlock(stringResource(R.string.calls_loading), "", RFStateKind.Loading, theme, action = {})
                        }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { RFStateBlock(stringResource(R.string.calls_error), it.render(), RFStateKind.Error, theme, action = {}) }
                    }
                    state.unavailable?.let {
                        item(
                            "unavailable"
                        ) {
                            RFStateBlock(
                                stringResource(R.string.calls_unavailable_title),
                                it.render(),
                                RFStateKind.Empty,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.pending?.let { item("pending") { Text(stringResource(R.string.calls_pending, it)) } }
                    if (state.accountRequired) {
                        item("account") {
                            RFStateBlock(
                                stringResource(
                                    R.string.calls_account_needed
                                ),
                                stringResource(R.string.calls_account_help),
                                RFStateKind.Empty,
                                theme,
                                action = {
                                    TextButton(
                                        onClick = { onAction(CallsAction.SetupAccount) }
                                    ) { Text(stringResource(R.string.calls_account_action)) }
                                }
                            )
                        }
                    }
                    if (state.selected == null) {
                        if (state.loaded && state.rows.isEmpty()) {
                            item("empty") {
                                RFStateBlock(
                                    stringResource(R.string.calls_empty),
                                    stringResource(R.string.calls_empty_help),
                                    RFStateKind.Empty,
                                    theme,
                                    action = {}
                                )
                            }
                        }
                        items(state.rows, key = { it.id }) { row ->
                            RFListRow(
                                row.id,
                                row.peer,
                                row.reason?.render() ?: row.phase.render(),
                                theme,
                                leading = { RFAvatar(row.peer.take(1), RFAvatarKind.Person, theme) },
                                trailing = { Text(row.time.render()) },
                                onAction = { onAction(CallsAction.Select(row.id)) }
                            )
                        }
                    } else {
                        state.selected.let { row ->
                            item("identity") {
                                Column(
                                    Modifier.fillMaxWidth().heightIn(min = (theme.row_conversation * 3).dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                                ) {
                                    RFAvatar(row.peer.take(1), RFAvatarKind.Person, theme)
                                    Text(row.peer, style = MaterialTheme.typography.headlineMedium)
                                    Text(row.phase.render(), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.calls_no_audio))
                                    if (!state.fresh) Text(stringResource(R.string.calls_stale))
                                }
                            }
                            row.reason?.let { item("reason") { Text(it.render()) } }
                            if (row.hasAudioControls) {
                                item("audio-controls") {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                                        for (label in listOf(
                                            R.string.calls_mute,
                                            R.string.calls_speaker,
                                            R.string.calls_share
                                        )) TextButton(onClick = {
                                            onAction(CallsAction.Audio)
                                        }) { Text(stringResource(label)) }
                                    }
                                }
                            }
                            item(
                                "details-toggle"
                            ) {
                                TextButton(
                                    onClick = { onAction(CallsAction.ToggleFacts) }
                                ) { Text(stringResource(R.string.calls_details)) }
                            }
                            if (state.expanded) {
                                item("details") {
                                    Column(verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
                                        Text(row.id)
                                        Text(row.direction.render())
                                        Text(row.time.render())
                                        row.answered?.let { Text(stringResource(R.string.calls_answered_at, it.render())) }
                                        row.ended?.let { Text(stringResource(R.string.calls_ended_at, it.render())) }
                                        Text(stringResource(R.string.calls_sequence, UiText.Unsigned(row.sequence).render()))
                                        Text(stringResource(R.string.calls_generation, row.generation.render()))
                                    }
                                }
                            }
                        }
                    }
                }
                state.selected?.let { row ->
                    FlowRow(Modifier.fillMaxWidth().padding(theme.gap.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (row.incoming) {
                            CallControl(
                                stringResource(R.string.calls_decline), Icons.Default.CallEnd, false,
                                state.fresh && !state.busy && state.pending == null && row.canDecline,
                                onClick = { onAction(CallsAction.End(row.id, row.sequence, true)) }
                            )
                            CallControl(stringResource(R.string.calls_answer), Icons.Default.Call, true, !state.busy) {
                                onAction(CallsAction.Audio)
                            }
                        } else if (row.canHangup) {
                            CallControl(
                                stringResource(R.string.calls_hangup), Icons.Default.CallEnd, false,
                                state.fresh && !state.busy && state.pending == null,
                                onClick = { onAction(CallsAction.End(row.id, row.sequence, false)) }
                            )
                        }
                    }
                } ?: TextButton(onClick = { onAction(CallsAction.Audio) }) { Text(stringResource(R.string.calls_start)) }
            }
        }
    }
}

/** Host-native call action: token-sized circle, semantic color pair and wrapping accessible label. */
@Composable
private fun CallControl(label: String, icon: ImageVector, answer: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalRFTheme.current
    Column(
        Modifier.widthIn(min = (theme.gap * 4.5f).dp, max = (theme.gap * 8).dp)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding((theme.gap / 2).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)
    ) {
        Surface(
            Modifier.size((theme.gap * 4.5f).dp),
            shape = CircleShape,
            color = if (answer) theme.success else theme.danger,
            contentColor = if (answer) theme.on_success else theme.on_danger
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size((theme.gap * 1.875f).dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
