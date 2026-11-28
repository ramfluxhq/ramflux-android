// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsGroupPush
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.feature.mlsgroups.impl.R

private val CHOICES = listOf(
    MlsGroupPush.MENTIONS_REPLIES to "mentions_replies",
    MlsGroupPush.ALL to "all",
    MlsGroupPush.NONE to "none"
)

/**
 * The per-device push picker (D-M3-5): this device's confirmed mode is the checked row, and a choice shows "Saving…" until the
 * reply, never an optimistic value. A failure keeps the old value and says so. It is a device setting, not a group change.
 */
@Composable
internal fun MlsPushPicker(push: MlsPushUi, onChoose: (MlsGroupPush) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
        KitMeta(stringResource(R.string.group_push_title), Modifier.padding(horizontal = theme.gap.dp), tag = "mls-push-title")
        KitMeta(stringResource(R.string.group_push_help), Modifier.padding(horizontal = theme.gap.dp), tag = "mls-push-help")
        CHOICES.forEach { (value, tag) ->
            KitRow(
                pushLabel(value),
                { if (value != push.value && push.saving == null) onChoose(value) },
                trailing = if (value == push.value) KitRowTrailing.Check else KitRowTrailing.None,
                // The current choice never looks disabled, also while another one is saving.
                enabled = push.saving == null || value == push.value,
                divider = false,
                testTag = "mls-push-$tag"
            )
        }
        if (push.saving != null) {
            KitMeta(
                stringResource(R.string.group_push_saving),
                Modifier.padding(horizontal = theme.gap.dp),
                tag = "mls-push-saving"
            )
        }
        push.failure?.let {
            // A typed unavailable result says so; any other failure keeps the old value and says the change failed.
            val text = if (it.unavailable) failureText(it) else stringResource(R.string.group_push_failed)
            KitMeta(text, Modifier.padding(horizontal = theme.gap.dp), tone = theme.danger, tag = "mls-push-failed")
        }
    }
}

@Composable
internal fun pushLabel(push: MlsGroupPush): String = when (push) {
    MlsGroupPush.MENTIONS_REPLIES, MlsGroupPush.UNKNOWN -> stringResource(R.string.group_push_mentions_replies)
    MlsGroupPush.ALL -> stringResource(R.string.group_push_all)
    MlsGroupPush.NONE -> stringResource(R.string.group_push_none)
}

private const val HALF = .5f
