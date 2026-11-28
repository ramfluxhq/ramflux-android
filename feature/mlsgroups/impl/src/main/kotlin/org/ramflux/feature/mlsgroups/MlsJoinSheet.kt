// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.shapeOf
import org.ramflux.feature.mlsgroups.impl.R

private const val GRABBER_W = 36f
private const val GRABBER_H = 4f
private const val GRABBER_R = 2f

/**
 * The join sheet (derived design D-M2-2, the add-contact dialog look): the field, Paste (reads the clipboard on the tap only),
 * Join (disabled while the call runs; one call per tap) and the outcome of this sheet's own attempt. Closing clears the field.
 * The link is passed on verbatim; nothing here parses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MlsJoinSheet(sheet: MlsJoinSheetUi, onAction: (MlsGroupsAction) -> Unit, links: MlsLinkActions) {
    // Not shown under the app-lock cover.
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(topStart = theme.radius_sheet.dp, topEnd = theme.radius_sheet.dp)
    ModalBottomSheet(
        onDismissRequest = { onAction(MlsGroupsAction.CloseJoin) },
        modifier = Modifier.testTag("mls-join-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = theme.overlay,
        contentColor = theme.text,
        dragHandle = {
            Box(
                Modifier.padding(top = theme.gap.dp).size(GRABBER_W.dp, GRABBER_H.dp).clip(shapeOf(GRABBER_R)).background(theme.line_strong)
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = theme.gap.dp, vertical = theme.gap.dp),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            KitMeta(stringResource(R.string.group_join_title), Modifier.testTag("mls-join-title"))
            KitTextField(
                value = sheet.link,
                onValue = { onAction(MlsGroupsAction.Link(it)) },
                label = stringResource(R.string.group_join_field),
                enabled = !sheet.joining,
                tag = "mls-join-field",
                code = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                Box(Modifier.weight(1f)) {
                    KitButton(
                        stringResource(R.string.group_join_paste),
                        { links.paste()?.let { onAction(MlsGroupsAction.Paste(it)) } },
                        Modifier.fillMaxWidth(),
                        kind = KitButtonKind.Secondary,
                        enabled = !sheet.joining,
                        tag = "mls-join-paste"
                    )
                }
                Box(Modifier.weight(1f)) {
                    KitButton(
                        if (sheet.joining) stringResource(R.string.group_join_joining) else stringResource(R.string.group_join_submit),
                        { if (!sheet.joining) onAction(MlsGroupsAction.Submit) },
                        Modifier.fillMaxWidth(),
                        enabled = !sheet.joining && sheet.link.isNotBlank(),
                        loading = sheet.joining,
                        tag = "mls-join-submit"
                    )
                }
            }
            sheet.result?.let { Outcome(it, onAction) }
            KitButton(
                stringResource(R.string.group_join_close),
                { onAction(MlsGroupsAction.CloseJoin) },
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Text,
                tag = "mls-join-close"
            )
        }
    }
}

@Composable
private fun Outcome(result: MlsJoinResultUi, onAction: (MlsGroupsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxWidth().testTag("mls-join-outcome"), verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
        when (result) {
            is MlsJoinResultUi.Joined -> {
                // The group's name is not known to the sheet: the neutral wording is used (S6: only a confirmed join says "joined").
                KitMeta(stringResource(R.string.group_join_joined, stringResource(R.string.group_join_the_group)), tag = "mls-join-text")
                KitButton(
                    stringResource(R.string.group_join_open),
                    { onAction(MlsGroupsAction.OpenJoined(result.groupId)) },
                    Modifier.fillMaxWidth(),
                    tag = "mls-join-open-group"
                )
            }
            is MlsJoinResultUi.Pending -> {
                val time = result.expiresAt?.let { timeText(it) }
                val text = when {
                    time == null -> stringResource(R.string.group_join_pending_row)
                    result.reason == org.ramflux.data.MlsGroupPendingReason.AWAITING_MEMBER_ONLINE ->
                        stringResource(R.string.group_join_pending_online, time)
                    else -> stringResource(R.string.group_join_pending_admin, time)
                }
                KitMeta(text, tag = "mls-join-text")
            }
            is MlsJoinResultUi.Refused -> {
                KitMeta(refusalText(result), tone = theme.danger, tag = "mls-join-text")
                if (result.reason == org.ramflux.data.MlsGroupRefusal.ALREADY_MEMBER) {
                    KitButton(
                        stringResource(R.string.group_join_open),
                        { onAction(MlsGroupsAction.OpenJoined(result.groupId)) },
                        Modifier.fillMaxWidth(),
                        kind = KitButtonKind.Secondary,
                        tag = "mls-join-open-group"
                    )
                }
            }
            is MlsJoinResultUi.Failed -> KitMeta(failureText(result.failure), tone = theme.danger, tag = "mls-join-text")
        }
    }
}

private const val HALF = .5f
