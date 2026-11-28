// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsFailure
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.shapeOf
import org.ramflux.feature.mlsgroups.impl.R

@Composable
private fun actionLabel(kind: MlsMemberActionKind): String = when (kind) {
    MlsMemberActionKind.MAKE_ADMIN -> stringResource(R.string.group_member_action_make_admin)
    MlsMemberActionKind.MAKE_MEMBER -> stringResource(R.string.group_member_action_make_member)
    MlsMemberActionKind.REMOVE -> stringResource(R.string.group_member_action_remove)
    MlsMemberActionKind.BAN -> stringResource(R.string.group_member_action_ban)
}

/**
 * The action menu of one member row (3.3): the entries the role allows, disabled with the busy text while another admin commit
 * is in flight and with the wait text until `paced{retry_at}`. Choosing an entry opens its confirmation; nothing is sent here.
 */
@Composable
internal fun BoxScope.MlsMemberMenu(row: MlsMemberUi, state: MlsMembersUi, onAction: (MlsMembersAction) -> Unit) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val enabled = !state.busy && !state.paced
    val close = { onAction(MlsMembersAction.CloseMenu) }
    BackHandler(onBack = close)
    Box(
        Modifier.fillMaxSize().background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close)
            .testTag("mls-member-menu-scrim")
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay, shapeOf(theme.radius_sheet)).padding(theme.gap.dp)
            .testTag("mls-member-menu"),
        verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)
    ) {
        val name = row.display?.let { Bidi.isolate(it.take(NAME_DISPLAY), UntrustedKind.Name) }
            ?: stringResource(R.string.group_member_this)
        KitMeta(name, tag = "mls-member-menu-title")
        if (state.busy) KitMeta(stringResource(R.string.group_member_action_busy), tag = "mls-member-menu-busy")
        if (state.paced) {
            KitMeta(failureText(MlsFailure("paced", state.pacedUntil)), tone = theme.danger, tag = "mls-member-menu-paced")
        }
        row.actions.forEach { kind ->
            KitRow(
                actionLabel(kind),
                { onAction(MlsMembersAction.Choose(kind)) },
                trailing = KitRowTrailing.None,
                enabled = enabled,
                divider = false,
                testTag = "mls-member-action-" + kind.tag
            )
        }
    }
}

/** The confirmation of the chosen member action: it names the member and the group; only its confirm button calls the session. */
@Composable
internal fun BoxScope.MlsMemberConfirm(ask: MlsMemberConfirmUi, groupName: String?, onAction: (MlsMembersAction) -> Unit) {
    MlsAdminConfirm(
        memberSpec(ask.kind, ask.display, ask.ai, groupName),
        "mls-member-confirm",
        { onAction(MlsMembersAction.Confirm) },
        { onAction(MlsMembersAction.CancelConfirm) }
    )
}

private const val NAME_DISPLAY = 64
private const val HALF = .5f
