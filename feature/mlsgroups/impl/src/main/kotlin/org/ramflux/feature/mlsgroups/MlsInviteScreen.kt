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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitQrCode
import org.ramflux.designsystem.kit.KitSegmented
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.LtrCode
import org.ramflux.feature.mlsgroups.impl.R

private const val HALF = .5f

/**
 * The invite panel (derived design D-M2-1): the secret note, the link (monospace, middle-truncated, always left to right),
 * Copy and Share (each on its own tap), the QR code, the duration and uses presets, "New link" and the list. A row shows
 * "Revoked" only after the reply to the revoke (S6). [drawQr] lends the QR modules to the drawing frame.
 */
@Composable
fun MlsInviteScreen(
    state: MlsInviteUi,
    onAction: (MlsInviteAction) -> Unit,
    links: MlsLinkActions,
    drawQr: (((Int, Int) -> Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    var copied by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(
                stringResource(R.string.group_invite_title),
                onBack = { onAction(MlsInviteAction.Back) },
                tag = "mls-invite-header"
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                KitTipBar(stringResource(R.string.group_invite_secret_note), "info", tag = "mls-invite-note")
                val link = state.link
                if (link != null) {
                    LtrCode(middleTruncated(link), Modifier.fillMaxWidth().testTag("mls-invite-link"))
                    LinkMeta(state)
                    Row(horizontalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                        Box(Modifier.weight(1f)) {
                            KitButton(
                                stringResource(R.string.group_invite_copy),
                                {
                                    links.copy(link)
                                    copied = true
                                },
                                Modifier.fillMaxWidth(),
                                kind = KitButtonKind.Secondary,
                                tag = "mls-invite-copy"
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            KitButton(
                                stringResource(R.string.group_invite_share),
                                { links.share(link) },
                                Modifier.fillMaxWidth(),
                                kind = KitButtonKind.Secondary,
                                tag = "mls-invite-share"
                            )
                        }
                    }
                    if (copied) KitMeta(stringResource(R.string.group_invite_copied), tag = "mls-invite-copied")
                    if (state.qrModules > 0) {
                        KitQrCode(state.qrModules, drawQr, stringResource(R.string.group_invite_qr), tag = "mls-invite-qr")
                    }
                }
                Presets(state, onAction)
                state.failure?.let { KitMeta(failureText(it), tone = theme.danger, tag = "mls-invite-error") }
                KitButton(
                    if (state.creating) stringResource(R.string.group_invite_creating) else stringResource(R.string.group_invite_new),
                    { onAction(MlsInviteAction.New) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.creating,
                    loading = state.creating,
                    tag = "mls-invite-new"
                )
                KitMeta(stringResource(R.string.group_invite_list), tag = "mls-invite-list-title")
                if (state.rows.isEmpty() && !state.loading) KitMeta(stringResource(R.string.group_invite_none), tag = "mls-invite-none")
                state.rows.forEach { row -> InviteRow(row, onAction) }
            }
        }
        val confirm = state.confirmRevoke
        if (confirm != null) {
            ConfirmPanel(
                stringResource(R.string.group_invite_revoke_confirm_title),
                stringResource(R.string.group_invite_revoke_confirm_body),
                stringResource(R.string.group_invite_revoke),
                stringResource(R.string.group_invite_cancel),
                { onAction(MlsInviteAction.ConfirmRevoke) },
                { onAction(MlsInviteAction.CancelRevoke) },
                "mls-invite-revoke-confirm"
            )
        }
        state.hostNotice?.let { host ->
            val shown = host.ifEmpty { stringResource(R.string.group_info_host_unknown) }
            Column(
                Modifier.align(
                    Alignment.BottomCenter
                ).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp).testTag("mls-host-notice"),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                KitMeta(stringResource(R.string.group_notice_host_ip_title))
                KitMeta(stringResource(R.string.group_notice_host_ip_body, shown))
                KitButton(
                    stringResource(R.string.group_notice_host_ip_ok),
                    { onAction(MlsInviteAction.NoticeSeen) },
                    Modifier.fillMaxWidth(),
                    tag = "mls-host-notice-ok"
                )
            }
        }
    }
}

@Composable
private fun LinkMeta(state: MlsInviteUi) {
    val expires = state.linkExpiresAt?.let { stringResource(R.string.group_invite_expires, timeText(it)) }
    val left = state.linkUsesLeft?.let { stringResource(R.string.group_invite_uses_left, it.toInt(), (state.linkMaxUses ?: it).toInt()) }
    listOfNotNull(expires, left).forEach { KitMeta(it, tag = "mls-invite-meta") }
}

@Composable
private fun Presets(state: MlsInviteUi, onAction: (MlsInviteAction) -> Unit) {
    KitMeta(stringResource(R.string.group_invite_duration))
    KitSegmented(
        MlsInviteUi.DAYS.map { pluralStringResource(R.plurals.group_invite_duration_day, it, it) },
        MlsInviteUi.DAYS.indexOf(state.ttlDays).coerceAtLeast(0),
        { onAction(MlsInviteAction.Days(MlsInviteUi.DAYS[it])) },
        enabled = !state.creating,
        tag = "mls-invite-days"
    )
    KitMeta(stringResource(R.string.group_invite_uses))
    KitSegmented(
        MlsInviteUi.USES.map { pluralStringResource(R.plurals.group_invite_max_uses, it, it) },
        MlsInviteUi.USES.indexOf(state.maxUses).coerceAtLeast(0),
        { onAction(MlsInviteAction.Uses(MlsInviteUi.USES[it])) },
        enabled = !state.creating,
        tag = "mls-invite-uses"
    )
}

@Composable
private fun InviteRow(row: MlsInviteRowUi, onAction: (MlsInviteAction) -> Unit) {
    val theme = LocalRFTheme.current
    Column(
        Modifier.fillMaxWidth().testTag("mls-invite-row-" + row.id),
        verticalArrangement = Arrangement.spacedBy((theme.gap * HALF * HALF).dp)
    ) {
        row.expiresAt?.let { KitMeta(stringResource(R.string.group_invite_expires, timeText(it))) }
        row.maxUses?.let { KitMeta(pluralStringResource(R.plurals.group_invite_max_uses, it.toInt(), it.toInt())) }
        when {
            row.revoked -> KitMeta(stringResource(R.string.group_invite_revoked), tone = theme.danger, tag = "mls-invite-badge-" + row.id)
            row.expired -> KitMeta(stringResource(R.string.group_invite_expired), tone = theme.danger, tag = "mls-invite-badge-" + row.id)
            else -> KitButton(
                if (row.revoking) stringResource(R.string.group_invite_revoking) else stringResource(R.string.group_invite_revoke),
                { onAction(MlsInviteAction.AskRevoke(row.id)) },
                kind = KitButtonKind.Secondary,
                enabled = !row.revoking,
                loading = row.revoking,
                tag = "mls-invite-revoke-" + row.id
            )
        }
    }
}
