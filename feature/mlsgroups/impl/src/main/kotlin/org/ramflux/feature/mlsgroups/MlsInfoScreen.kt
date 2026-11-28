// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupWarning
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSecurityWarning
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitWarningTone
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.feature.mlsgroups.impl.R

/**
 * The group info page: name, the member and agent counts, the `host_ip_visible` row, approval and push (read-only), the
 * invites and members entries, Refresh and Leave (confirmed first). The name is peer data.
 */
@Composable
fun MlsInfoScreen(state: MlsInfoUi, onAction: (MlsInfoAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(stringResource(R.string.group_info_title), onBack = { onAction(MlsInfoAction.Back) }, tag = "mls-info-header")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                val title = Modifier.padding(horizontal = theme.gap.dp)
                if (state.name != null) {
                    UntrustedText(state.name.take(NAME_DISPLAY), title.testTag("mls-info-name"))
                } else if (state.loading) {
                    KitMeta(stringResource(R.string.group_info_loading), title)
                }
                Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                    KitMeta(
                        pluralStringResource(R.plurals.group_info_members, state.members.toCount(), state.members.toCount()),
                        tag = "mls-info-members"
                    )
                    if (state.agents > 0) {
                        KitMeta(
                            pluralStringResource(R.plurals.group_info_agents, state.agents.toCount(), state.agents.toCount()),
                            tag = "mls-info-agents"
                        )
                    }
                    if (state.hostIpVisible) {
                        KitSecurityWarning(
                            stringResource(R.string.group_notice_host_ip_title),
                            stringResource(
                                R.string.group_warning_host_ip_visible,
                                state.hostNode ?: stringResource(R.string.group_info_host_unknown)
                            ),
                            KitWarningTone.Warning
                        )
                    }
                    KitMeta(
                        if (state.approval) {
                            stringResource(
                                R.string.group_info_approval_on
                            )
                        } else {
                            stringResource(R.string.group_info_approval_off)
                        },
                        tag = "mls-info-approval"
                    )
                    // A read-only group has no picker (A16): the confirmed mode stays a plain row.
                    if (state.admin.push == null) {
                        KitRow(
                            stringResource(R.string.group_info_push),
                            {},
                            value = pushText(state.push),
                            trailing = KitRowTrailing.None,
                            divider = false,
                            testTag = "mls-info-push"
                        )
                    }
                    if (state.admin.privacyDelay) {
                        KitMeta(stringResource(R.string.group_privacy_delay_info), tag = "mls-info-privacy-delay")
                    }
                    state.admin.commitQuota?.let { q ->
                        val line = stringResource(R.string.group_quota_commit, q.remaining.toCount(), q.limit.toCount())
                        val resets = q.resetsAt?.takeIf { q.remaining <= 0 }?.let {
                            stringResource(
                                R.string.group_quota_resets,
                                timeText(it)
                            )
                        }
                        KitMeta(dotted(listOfNotNull(line, resets)), tag = "mls-info-commit-quota")
                    }
                }
                state.admin.push?.let { MlsPushPicker(it, { push -> onAction(MlsInfoAction.Push(push)) }) }
                WarningRows(state, onAction)
                state.admin.requestCount?.let { count ->
                    KitRow(
                        if (state.admin.requestsMore) {
                            stringResource(R.string.group_requests_entry_more, count)
                        } else {
                            pluralStringResource(R.plurals.group_requests_entry, count, count)
                        },
                        { onAction(MlsInfoAction.Requests) },
                        testTag = "mls-requests-entry"
                    )
                }
                if (state.admin.settingsEntry) {
                    KitRow(
                        stringResource(R.string.group_settings_title),
                        { onAction(MlsInfoAction.Settings) },
                        testTag = "mls-settings-entry"
                    )
                }
                KitRow(
                    stringResource(R.string.group_info_members_entry),
                    { onAction(MlsInfoAction.Members) },
                    testTag = "mls-info-members-entry"
                )
                // Any member may create invites (r4 item 9); the list and revoke cover this account's own invites.
                if (!state.readOnly) {
                    KitRow(
                        stringResource(R.string.group_info_invites_entry),
                        { onAction(MlsInfoAction.Invites) },
                        testTag = "mls-info-invites-entry"
                    )
                }
                state.failure?.let {
                    KitMeta(
                        failureText(it),
                        Modifier.padding(horizontal = theme.gap.dp),
                        tone = theme.danger,
                        tag = "mls-info-error"
                    )
                }
                Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                    KitButton(
                        stringResource(R.string.group_info_refresh),
                        { onAction(MlsInfoAction.Refresh) },
                        Modifier.fillMaxWidth(),
                        kind = KitButtonKind.Secondary,
                        enabled = !state.loading,
                        tag = "mls-info-refresh"
                    )
                    when {
                        state.left -> KitMeta(stringResource(R.string.group_leave_done), tag = "mls-info-left")
                        else -> {
                            state.leaveFailure?.let {
                                val text = if (it.code == "owner_protected") stringResource(R.string.group_leave_owner) else failureText(it)
                                KitMeta(text, tone = theme.danger, tag = "mls-info-leave-error")
                            }
                            KitButton(
                                if (state.leaving) stringResource(R.string.group_leaving) else stringResource(R.string.group_info_leave),
                                { onAction(MlsInfoAction.AskLeave) },
                                Modifier.fillMaxWidth(),
                                kind = KitButtonKind.Destructive,
                                enabled = !state.leaving,
                                loading = state.leaving,
                                tag = "mls-info-leave"
                            )
                        }
                    }
                }
            }
        }
        if (state.confirmLeave) {
            ConfirmPanel(
                stringResource(R.string.group_leave_title),
                stringResource(R.string.group_leave_body),
                stringResource(R.string.group_leave_confirm),
                stringResource(R.string.group_invite_cancel),
                { onAction(MlsInfoAction.ConfirmLeave) },
                { onAction(MlsInfoAction.CancelLeave) },
                "mls-info-leave-confirm"
            )
        }
    }
}

@Composable
private fun pushText(push: MlsGroupPush): String = when (push) {
    MlsGroupPush.MENTIONS_REPLIES, MlsGroupPush.UNKNOWN -> stringResource(R.string.group_push_mentions_replies)
    MlsGroupPush.ALL -> stringResource(R.string.group_push_all)
    MlsGroupPush.NONE -> stringResource(R.string.group_push_none)
}

/**
 * The warning rows (D-M3-3): each repeats the banner text; unknown codes appear here only, as the generic text. The admission
 * row of a member of a group that is not read-only adds "Get invite link" and the agent hint of the phones (no agent is
 * created on a phone). Nothing here calls anything: the entry opens the invite view.
 */
@Composable
private fun WarningRows(state: MlsInfoUi, onAction: (MlsInfoAction) -> Unit) {
    val theme = LocalRFTheme.current
    if (state.admin.warnings.isEmpty()) return
    Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
        state.admin.warnings.forEach { warning ->
            KitTipBar(warningText(warning), "circle-alert", tag = "mls-warning-" + warning.key.take(WARNING_TAG))
            if (warning === MlsGroupWarning.AdmissionNeedsOnline && state.admin.hintActions) {
                KitMeta(stringResource(R.string.group_hint_agent_mobile), tag = "mls-hint-agent")
                KitButton(
                    stringResource(R.string.group_hint_agent_invite),
                    { onAction(MlsInfoAction.Invites) },
                    Modifier.fillMaxWidth(),
                    kind = KitButtonKind.Secondary,
                    tag = "mls-hint-invite"
                )
            }
        }
    }
}

private const val WARNING_TAG = 40

private fun Long.toCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private const val NAME_DISPLAY = 64
private const val HALF = .5f
