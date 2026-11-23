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
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.feature.calls.impl.R
@Composable
fun CallsScreen(state: CallsState, onAction: (CallsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                val subtitle = state.account.orEmpty()
                KitPageHeader(
                    stringResource(if (state.selected == null) R.string.calls_history else R.string.calls_call),
                    subtitle = subtitle.takeIf { it.isNotEmpty() },
                    onBack = { onAction(CallsAction.Back) },
                    actionLabel = stringResource(R.string.calls_refresh),
                    onAction = { if (!state.loading && !state.busy) onAction(CallsAction.Refresh) }
                )
                if (subtitle.isNotEmpty()) Text(subtitle, color = theme.secondary)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            KitEmpty("loader", stringResource(R.string.calls_loading), "")
                        }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { KitEmpty("circle-alert", stringResource(R.string.calls_error), it.render()) }
                    }
                    state.unavailable?.let {
                        item(
                            "unavailable"
                        ) {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.calls_unavailable_title),
                                it.render()
                            )
                        }
                    }
                    state.pending?.let { item("pending") { Text(stringResource(R.string.calls_pending, it)) } }
                    if (state.accountRequired) {
                        item("account") {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.calls_account_needed),
                                stringResource(R.string.calls_account_help),
                                action = stringResource(R.string.calls_account_action),
                                onAction = { onAction(CallsAction.SetupAccount) }
                            )
                        }
                    }
                    if (state.selected == null) {
                        if (state.loaded && state.rows.isEmpty()) {
                            item("empty") {
                                KitEmpty(
                                    "inbox",
                                    stringResource(R.string.calls_empty),
                                    stringResource(R.string.calls_empty_help)
                                )
                            }
                        }
                        items(state.rows, key = { it.id }) { row ->
                            val detail = row.reason?.render() ?: row.phase.render()
                            KitRow(
                                row.peer,
                                { onAction(CallsAction.Select(row.id)) },
                                value = detail.takeIf { it.isNotEmpty() },
                                leading = { KitAvatar(row.peer.take(1), 36f, KitAvatarKind.Person) },
                                trailing = KitRowTrailing.None,
                                trailingContent = { Text(row.time.render()) },
                                testTag = row.id
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
                                    KitAvatar(row.peer.take(1), 36f, KitAvatarKind.Person)
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
