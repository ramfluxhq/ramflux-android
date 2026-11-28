// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsGroupRejoinReason
import org.ramflux.data.MlsGroupWarning
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitButtonSize
import org.ramflux.designsystem.kit.KitIcon
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSpinner
import org.ramflux.designsystem.kit.shapeOf
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
 * text of the reason and no Skip (the SDK rejoins by itself). Skip only opens the dialog. A rejoin the user chose by skipping is a
 * warning; a forced one (`rejoin_needed`, a reason is known) is critical.
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
    val tone = when {
        catchUp is MlsCatchUpUi.Rejoining && catchUp.reason != null -> BannerTone.Critical
        catchUp is MlsCatchUpUi.Rejoining -> BannerTone.Warning
        else -> BannerTone.Info
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp)) {
        MlsBannerCard(
            text,
            tone,
            "refresh-cw",
            "mls-catchup-banner",
            loading = catchUp !is MlsCatchUpUi.Rejoining,
            actions = if (catchUp is MlsCatchUpUi.Running) {
                {
                    KitButton(
                        stringResource(R.string.group_catchup_skip),
                        { onAction(ChatAction.MlsSkip) },
                        Modifier.weight(1f),
                        kind = KitButtonKind.Secondary,
                        size = KitButtonSize.Compact,
                        tag = "mls-catchup-skip"
                    )
                }
            } else {
                null
            }
        )
    }
}

private enum class BannerTone { Critical, Warning, Info }

private fun toneOf(warning: MlsGroupWarning) = when (warning) {
    is MlsGroupWarning.ForkAlarm, is MlsGroupWarning.RejoinNeeded, is MlsGroupWarning.HeadCommitRejected -> BannerTone.Critical
    is MlsGroupWarning.Undecryptable, is MlsGroupWarning.HostUnusual -> BannerTone.Warning
    else -> BannerTone.Info
}

/**
 * One banner: the tint and the glyph say the severity (critical, warning, information), and its actions sit inside the card,
 * not loose under it.
 */
@Composable
private fun MlsBannerCard(
    text: String,
    tone: BannerTone,
    infoIcon: String,
    tag: String,
    loading: Boolean = false,
    extra: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val theme = LocalRFTheme.current
    val (background, tint, icon) = when (tone) {
        BannerTone.Critical -> Triple(theme.danger_bg, theme.danger, "circle-alert")
        BannerTone.Warning -> Triple(theme.warning_bg, theme.warning, "triangle-alert")
        BannerTone.Info -> Triple(theme.surface, theme.secondary, infoIcon)
    }
    Column(
        Modifier.fillMaxWidth().background(background, shapeOf(theme.radius_card)).padding(theme.gap.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp), verticalAlignment = Alignment.Top) {
            if (loading) KitSpinner(ICON, tint) else KitIcon(icon, ICON, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                KitMeta(text, tone = theme.text)
                if (extra != null) KitMeta(extra, tag = "$tag-hint")
            }
        }
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp), content = actions)
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
            val admission = warning === MlsGroupWarning.AdmissionNeedsOnline && mls.admissionActions
            val rejected = warning is MlsGroupWarning.HeadCommitRejected && warning.logSeq != null
            val hasActions = warning is MlsGroupWarning.Undecryptable || rejected || admission || banner.hideable
            MlsBannerCard(
                bannerText(warning),
                toneOf(warning),
                "info",
                "mls-warning-" + warning.key.take(TAG_KEY),
                extra = if (admission) stringResource(R.string.group_chat_hint_agent_mobile) else null,
                actions = if (hasActions) {
                    {
                        when (warning) {
                            is MlsGroupWarning.Undecryptable -> BannerButton(
                                R.string.group_warning_undecryptable_show,
                                "mls-warning-action-undecryptable"
                            ) { onAction(ChatAction.MlsShowUndecryptable) }
                            is MlsGroupWarning.HeadCommitRejected -> warning.logSeq?.let { slot ->
                                BannerButton(
                                    R.string.group_warning_head_commit_rejected_action,
                                    "mls-warning-action-head_commit_rejected"
                                ) { onAction(ChatAction.MlsUndo(REJECTED_ROW + slot)) }
                            }
                            MlsGroupWarning.AdmissionNeedsOnline -> if (admission) {
                                BannerButton(
                                    R.string.group_chat_hint_agent_invite,
                                    "mls-hint-invite"
                                ) { onAction(ChatAction.MlsOpenInvite) }
                            }
                            else -> Unit
                        }
                        if (banner.hideable) {
                            BannerButton(R.string.group_warning_hide, "mls-warning-hide-" + warning.key.take(TAG_KEY)) {
                                onAction(ChatAction.MlsHideBanner(warning.key))
                            }
                        }
                    }
                } else {
                    null
                }
            )
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

/** One banner action: every action of a banner has the same secondary style and shares the row. */
@Composable
private fun RowScope.BannerButton(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    KitButton(
        stringResource(label),
        onClick,
        Modifier.weight(1f),
        kind = KitButtonKind.Secondary,
        size = KitButtonSize.Compact,
        tag = tag
    )
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
        mls.privacyEta?.let { KitMeta(stringResource(R.string.group_privacy_delay_eta, timeOfDay(it)), tag = "mls-privacy-eta") }
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
    val cancelFocus = rememberCancelFocus()
    BackHandler { onAction(ChatAction.MlsCancelSkip) }
    Scrim("mls-skip-scrim") { onAction(ChatAction.MlsCancelSkip) }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp)
            .confirmPane(stringResource(R.string.group_skip_title)).testTag("mls-skip-dialog"),
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
            Modifier.fillMaxWidth().cancelFirst(cancelFocus),
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
    val cancelFocus = rememberCancelFocus()
    BackHandler { onAction(ChatAction.MlsCancelUndo) }
    Scrim("mls-void-scrim") { onAction(ChatAction.MlsCancelUndo) }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp)
            .confirmPane(stringResource(R.string.group_void_title)).testTag("mls-void-dialog"),
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
            Modifier.fillMaxWidth().cancelFirst(cancelFocus),
            kind = KitButtonKind.Secondary,
            tag = "mls-void-cancel"
        )
    }
}

/**
 * Initial focus and the first TalkBack stop of a confirmation go to Cancel (the safe button): a dialog that destroys something or
 * grants authority is never one key press or one swipe-and-tap away from its confirm button.
 */
@Composable
internal fun rememberCancelFocus(): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return focus
}

internal fun Modifier.cancelFirst(focus: FocusRequester): Modifier = focusRequester(focus).semantics { traversalIndex = CANCEL_FIRST }

internal fun Modifier.confirmPane(title: String): Modifier = semantics {
    isTraversalGroup = true
    paneTitle = title
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
private const val CANCEL_FIRST = -1f
private const val ICON = 18f
