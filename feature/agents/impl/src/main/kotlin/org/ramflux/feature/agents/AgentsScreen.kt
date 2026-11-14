// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.AgentAvatar
import org.ramflux.data.AgentCapability
import org.ramflux.designsystem.*
import org.ramflux.feature.agents.impl.R
import org.ramflux.generated.*
@Composable
fun AgentsScreen(state: AgentsState, onAction: (AgentsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(state.mode.label()),
                    stringResource(R.string.agents_title),
                    theme,
                    leading = { TextButton(onClick = { onAction(AgentsAction.Back) }) { Text(stringResource(R.string.agents_back)) } },
                    trailing = {
                        TextButton(
                            enabled = !state.loading,
                            onClick = { onAction(AgentsAction.Refresh) }
                        ) { Text(stringResource(R.string.agents_retry)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading) {
                        item(
                            "loading"
                        ) { RFStateBlock(stringResource(R.string.agents_loading), "", RFStateKind.Loading, theme, {}) }
                    }
                    state.problem?.let {
                        item(
                            "error"
                        ) { RFStateBlock(stringResource(R.string.agents_unavailable), it.render(), RFStateKind.Error, theme, {}) }
                    }
                    if (state.accountRequired) {
                        item("account") {
                            Text(stringResource(R.string.agents_account))
                            TextButton(onClick = { onAction(AgentsAction.SetupAccount) }) { Text(stringResource(R.string.agents_setup)) }
                        }
                    }
                    if (state.unavailable) {
                        item("unavailable") {
                            RFStateBlock(
                                stringResource(R.string.agents_unavailable),
                                stringResource(R.string.agents_explanation),
                                RFStateKind.Empty,
                                theme,
                                {}
                            )
                        }
                    }
                    if (state.mode == AgentCapability.DIRECTORY) {
                        item("navigation") {
                            TextButton(
                                onClick = { onAction(AgentsAction.Mode(AgentCapability.CREATE)) }
                            ) { Text(stringResource(R.string.agents_create)) }
                            for (mode in listOf(
                                AgentCapability.PROFILE,
                                AgentCapability.MANAGE,
                                AgentCapability.COLLABORATION,
                                AgentCapability.HIDE
                            )) {
                                TextButton(onClick = { onAction(AgentsAction.Mode(mode)) }) { Text(stringResource(mode.label())) }
                            }
                        }
                    }
                    if (state.mode in setOf(AgentCapability.CREATE, AgentCapability.EDIT, AgentCapability.GOAL)) {
                        item("draft-note") { Text(stringResource(R.string.agents_draft_only)) }
                        if (state.mode == AgentCapability.CREATE) {
                            item("avatar") {
                                Text(stringResource(R.string.agents_avatar))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
                                    for (avatar in AgentAvatar.entries) FilterChip(
                                        selected = state.avatar == avatar,
                                        onClick = { onAction(AgentsAction.Avatar(avatar)) },
                                        enabled = !state.loading && !state.accountRequired,
                                        label = { Text(stringResource(avatar.label())) }
                                    )
                                }
                            }
                        }
                        item("name") {
                            OutlinedTextField(
                                state.name,
                                { onAction(AgentsAction.Name(it)) },
                                label = { Text(stringResource(R.string.agents_name)) },
                                enabled = !state.loading && !state.accountRequired,
                                isError = state.nameIssue != null,
                                supportingText = { state.nameIssue?.let { Text(it.render()) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item("goal") {
                            OutlinedTextField(
                                state.goal,
                                { onAction(AgentsAction.Goal(it)) },
                                label = { Text(stringResource(R.string.agents_responsibility)) },
                                enabled = !state.loading && !state.accountRequired,
                                isError = state.goalIssue != null,
                                supportingText = {
                                    state.goalIssue?.let { Text(it.render()) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4
                            )
                        }
                        item("defaults") {
                            Text(stringResource(R.string.agents_defaults), style = MaterialTheme.typography.titleMedium)
                            for (label in listOf(
                                R.string.agents_runtime,
                                R.string.agents_tools,
                                R.string.agents_scope,
                                R.string.agents_desktop
                            )) Text(stringResource(label))
                        }
                    }
                    if (state.mode == AgentCapability.PROFILE || state.mode == AgentCapability.MANAGE) {
                        item("context-actions") {
                            for (mode in listOf(
                                AgentCapability.ADVANCED,
                                AgentCapability.CHAT,
                                AgentCapability.GOAL,
                                AgentCapability.EDIT,
                                AgentCapability.PAUSE,
                                AgentCapability.REMOVE
                            )) {
                                TextButton(onClick = { onAction(AgentsAction.Mode(mode)) }) { Text(stringResource(mode.label())) }
                            }
                        }
                    }
                }
                if (state.mode == AgentCapability.CREATE) {
                    Button(
                        onClick = { onAction(AgentsAction.Create) },
                        enabled = !state.loading && !state.accountRequired,
                        modifier = Modifier.fillMaxWidth().padding(theme.gap.dp)
                    ) { Text(stringResource(R.string.agents_create)) }
                }
            }
        }
    }
}
private fun AgentCapability.label() = when (this) {
    AgentCapability.DIRECTORY -> R.string.agents_directory
    AgentCapability.CREATE -> R.string.agents_create
    AgentCapability.PROFILE -> R.string.agents_profile
    AgentCapability.ADVANCED -> R.string.agents_advanced
    AgentCapability.CHAT -> R.string.agents_chat
    AgentCapability.GOAL -> R.string.agents_goal
    AgentCapability.MANAGE -> R.string.agents_manage
    AgentCapability.COLLABORATION -> R.string.agents_collaboration
    AgentCapability.EDIT -> R.string.agents_edit
    AgentCapability.REMOVE -> R.string.agents_remove
    AgentCapability.PAUSE -> R.string.agents_pause
    AgentCapability.HIDE -> R.string.agents_hide
}
private fun AgentAvatar.label() = when (this) {
    AgentAvatar.ORBIT -> R.string.agents_orbit
    AgentAvatar.PRISM -> R.string.agents_prism
    AgentAvatar.WAVES -> R.string.agents_waves
    AgentAvatar.STAR -> R.string.agents_star
}
