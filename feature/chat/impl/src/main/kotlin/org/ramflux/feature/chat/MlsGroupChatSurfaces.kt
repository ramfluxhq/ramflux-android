// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsGroupRejoinReason
import org.ramflux.data.MlsGroupWarning
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitButtonSize
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.render
import org.ramflux.feature.chat.impl.R

/*
 * The surfaces of an MLS group chat that M3 adds (D-M3-2 to D-M3-4): the catch-up banner, the warning banners, the skip and void
 * dialogs, the "Undo this change" sheet, the service row with its marks, and the quota and privacy-delay lines. ChatScreen calls
 * them only when `state.mls != null`, so a direct chat and an old group chat are unchanged. Nothing here calls anything by
 * itself: every action is an explicit tap that reaches the view model.
 */

@Composable
private fun rejoinText(reason: MlsGroupRejoinReason?): String = when (reason) {
    null -> stringResource(R.string.group_catchup_rejoining)
    MlsGroupRejoinReason.PRUNED -> stringResource(R.string.group_chat_warning_rejoin_needed_pruned)
    MlsGroupRejoinReason.GATE_REJECTED -> stringResource(R.string.group_chat_warning_rejoin_needed_gate_rejected)
    MlsGroupRejoinReason.APPLY_FAILED -> stringResource(R.string.group_chat_warning_rejoin_needed_apply_failed)
    MlsGroupRejoinReason.WELCOME_EXPIRED -> stringResource(R.string.group_chat_warning_rejoin_needed_welcome_expired)
    MlsGroupRejoinReason.STATE_LOST -> stringResource(R.string.group_chat_warning_rejoin_needed_state_lost)
    MlsGroupRejoinReason.UNKNOWN -> stringResource(R.string.group_chat_warning_rejoin_needed_generic)
}

/** The banner text of one warning; an unknown code is never a banner (group info lists it), so it falls back to the generic text. */
@Composable
internal fun bannerText(warning: MlsGroupWarning): String = when (warning) {
    is MlsGroupWarning.ForkAlarm -> stringResource(R.string.group_chat_warning_fork_alarm)
    is MlsGroupWarning.RejoinNeeded -> rejoinText(warning.reason)
    is MlsGroupWarning.Undecryptable -> {
        val count = warning.count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.group_chat_warning_undecryptable, count, count)
    }
    MlsGroupWarning.AdmissionNeedsOnline -> stringResource(R.string.group_chat_warning_admission_needs_online)
    is MlsGroupWarning.HostUnusual -> stringResource(R.string.group_chat_warning_host_unusual)
    is MlsGroupWarning.HeadCommitRejected -> stringResource(R.string.group_chat_warning_head_commit_rejected)
    is MlsGroupWarning.HostIpVisible, is MlsGroupWarning.Unknown -> stringResource(R.string.group_chat_warning_generic)
}

/**
 * The catch-up banner: running (with numbers, or without when the total is unknown) with Skip, skipping, or rejoining with the
 * text of the reason and no Skip (the SDK rejoins by itself). Skip only opens the dialog.
 */
@Composable
internal fun MlsCatchUpBanner(catchUp: MlsCatchUpUi, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    val text = when (catchUp) {
        is MlsCatchUpUi.Running -> if (catchUp.total > 0) {
            stringResource(R.string.group_catchup_running, catchUp.done.toCount(), catchUp.total.toCount())
        } else {
            stringResource(R.string.group_catchup_running_indeterminate)
        }
        MlsCatchUpUi.Skipping -> stringResource(R.string.group_skip_skipping)
        is MlsCatchUpUi.Rejoining -> rejoinText(catchUp.reason)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
        verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)
    ) {
        KitTipBar(text, "refresh-cw", loading = catchUp !is MlsCatchUpUi.Rejoining, tag = "mls-catchup-banner")
        if (catchUp is MlsCatchUpUi.Running) {
            KitButton(
                stringResource(R.string.group_catchup_skip),
                { onAction(ChatAction.MlsSkip) },
                kind = KitButtonKind.Secondary,
                size = KitButtonSize.Compact,
                tag = "mls-catchup-skip"
            )
        }
    }
}

/**
 * The warning banners, at most two, most severe first (3.7): the critical ones have no Hide; the others can be hidden for the
 * session. "+N" opens group info for the rest. The admission banner of a member adds the agent hint and "Get invite link".
 */
@Composable
internal fun MlsWarningBanners(mls: MlsChatUi, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    if (mls.banners.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
        verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)
    ) {
        mls.banners.forEach { banner ->
            val warning = banner.warning
            val critical = !banner.hideable
            KitTipBar(
                bannerText(warning),
                if (critical) "circle-alert" else "info",
                tag = "mls-warning-" + warning.key.take(TAG_KEY)
            )
            if (warning === MlsGroupWarning.AdmissionNeedsOnline && mls.admissionActions) {
                KitMeta(stringResource(R.string.group_chat_hint_agent_mobile), tag = "mls-hint-agent")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                when (warning) {
                    is MlsGroupWarning.Undecryptable -> KitButton(
                        stringResource(R.string.group_warning_undecryptable_show),
                        { onAction(ChatAction.MlsShowUndecryptable) },
                        kind = KitButtonKind.Secondary,
                        size = KitButtonSize.Compact,
                        tag = "mls-warning-action-undecryptable"
                    )
                    is MlsGroupWarning.HeadCommitRejected -> warning.logSeq?.let { slot ->
                        KitButton(
                            stringResource(R.string.group_warning_head_commit_rejected_action),
                            { onAction(ChatAction.MlsUndo(REJECTED_ROW + slot)) },
                            kind = KitButtonKind.Secondary,
                            size = KitButtonSize.Compact,
                            tag = "mls-warning-action-head_commit_rejected"
                        )
                    }
                    MlsGroupWarning.AdmissionNeedsOnline -> if (mls.admissionActions) {
                        KitButton(
                            stringResource(R.string.group_chat_hint_agent_invite),
                            { onAction(ChatAction.MlsOpenInvite) },
                            kind = KitButtonKind.Secondary,
                            size = KitButtonSize.Compact,
                            tag = "mls-hint-invite"
                        )
                    }
                    else -> Unit
                }
                if (banner.hideable) {
                    KitButton(
                        stringResource(R.string.group_warning_hide),
                        { onAction(ChatAction.MlsHideBanner(warning.key)) },
                        kind = KitButtonKind.Text,
                        size = KitButtonSize.Compact,
                        tag = "mls-warning-hide-" + warning.key.take(TAG_KEY)
                    )
                }
            }
        }
        if (mls.bannersMore > 0) {
            KitButton(
                pluralStringResource(R.plurals.group_warning_more, mls.bannersMore, mls.bannersMore),
                { onAction(ChatAction.MlsOpenAlerts) },
                kind = KitButtonKind.Text,
                size = KitButtonSize.Compact,
                tag = "mls-warning-more"
            )
        }
    }
}

/** The lines above the composer: "Undoing…", the post quota (2.8) and the privacy-delay ETA. Information only. */
@Composable
internal fun MlsComposerLines(mls: MlsChatUi) {
    val theme = LocalRFTheme.current
    if (!mls.voiding && mls.quotaLine == null && mls.privacyEta == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp)) {
        if (mls.voiding) KitMeta(stringResource(R.string.group_void_undoing), tag = "mls-void-undoing")
        mls.quotaLine?.let { q ->
            val line = if (q.firstDay) {
                stringResource(R.string.group_quota_post_first_day, q.remaining.toCount(), q.limit.toCount())
            } else {
                stringResource(R.string.group_quota_post, q.remaining.toCount(), q.limit.toCount())
            }
            val resets = q.resetsAt?.takeIf { q.remaining <= 0 }?.let { stringResource(R.string.group_chat_quota_resets, clock(it)) }
            KitMeta(listOfNotNull(line, resets).joinToString(SPACE), tag = "mls-quota-line")
        }
        mls.privacyEta?.let { KitMeta(stringResource(R.string.group_privacy_delay_eta, clock(it)), tag = "mls-privacy-eta") }
    }
}

/** A centred service line of an MLS group with its marks: the "Undone" mark, and the tap target of the head commit. */
@Composable
internal fun MlsServiceRow(row: MessageItem, voidable: Boolean, modifier: Modifier, onAction: (ChatAction) -> Unit) {
    val slot = MlsGroupChatMapping.slotOf(row.id)
    val line = row.line
    val tagged = voidable || line is GroupLine.VoidBy || line == GroupLine.Void
    Column(
        modifier
            .testTag("service-" + row.id)
            .then(if (tagged && slot != null) Modifier.testTag("mls-void-$slot") else Modifier)
            .then(if (voidable) Modifier.clickable { onAction(ChatAction.Select(row.id)) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KitMeta(line?.let { groupLineText(it) } ?: row.content.render(), centered = true)
        if (row.undone) KitMeta(stringResource(R.string.group_void_undone), centered = true, tag = "mls-undone-" + (slot ?: 0L))
    }
}

/** The one action of the head commit's row: "Undo this change". It opens the confirmation; nothing is sent by it. */
@Composable
internal fun BoxScope.MlsVoidActionSheet(rowId: String, onAction: (ChatAction) -> Unit) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.Dismiss) }
    Scrim("mls-void-action-scrim") { onAction(ChatAction.Dismiss) }
    Column(
        Modifier.align(
            Alignment.BottomCenter
        ).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp).testTag("mls-void-action-sheet"),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitRow(
            stringResource(R.string.group_void_action),
            { onAction(ChatAction.MlsUndo(rowId)) },
            trailing = KitRowTrailing.None,
            divider = false,
            testTag = "mls-void-action"
        )
    }
}

/** The skip dialog: the fixed sentence, a destructive "Skip" and the default "Keep catching up". The only path to the skip call. */
@Composable
internal fun BoxScope.MlsSkipDialog(onAction: (ChatAction) -> Unit) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.MlsCancelSkip) }
    Scrim("mls-skip-scrim") { onAction(ChatAction.MlsCancelSkip) }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp).testTag("mls-skip-dialog"),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitMeta(stringResource(R.string.group_skip_title), tag = "mls-skip-title")
        KitMeta(stringResource(R.string.group_skip_body), tag = "mls-skip-body")
        KitMeta(stringResource(R.string.group_skip_body_more), tag = "mls-skip-body-more")
        KitButton(
            stringResource(R.string.group_skip_confirm),
            { onAction(ChatAction.MlsConfirmSkip) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Destructive,
            tag = "mls-skip-confirm"
        )
        KitButton(
            stringResource(R.string.group_skip_cancel),
            { onAction(ChatAction.MlsCancelSkip) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Secondary,
            tag = "mls-skip-cancel"
        )
    }
}

/** The void confirmation: nothing is undone until "Undo" is tapped. */
@Composable
internal fun BoxScope.MlsVoidDialog(onAction: (ChatAction) -> Unit) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.MlsCancelUndo) }
    Scrim("mls-void-scrim") { onAction(ChatAction.MlsCancelUndo) }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp).testTag("mls-void-dialog"),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitMeta(stringResource(R.string.group_void_title), tag = "mls-void-title")
        KitMeta(stringResource(R.string.group_void_body), tag = "mls-void-body")
        KitButton(
            stringResource(R.string.group_void_confirm),
            { onAction(ChatAction.MlsConfirmUndo) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Destructive,
            tag = "mls-void-confirm"
        )
        KitButton(
            stringResource(R.string.group_delete_cancel),
            { onAction(ChatAction.MlsCancelUndo) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Secondary,
            tag = "mls-void-cancel"
        )
    }
}

@Composable
private fun BoxScope.Scrim(tag: String, onDismiss: () -> Unit) {
    val theme = LocalRFTheme.current
    Box(
        Modifier.fillMaxSize().background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .testTag(tag)
    )
}

private fun Long.toCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** The row id the `head_commit_rejected` banner action passes: the view model reads the log position after the letter. */
private const val REJECTED_ROW = "h"
private const val TAG_KEY = 40
private val SPACE = charArrayOf(' ').concatToString()
private const val HALF = .5f
