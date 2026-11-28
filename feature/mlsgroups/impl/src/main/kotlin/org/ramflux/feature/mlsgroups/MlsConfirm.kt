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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAiMarker
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.shapeOf

/**
 * A confirmation over the screen: nothing happens until [onConfirm] is tapped; the scrim, the back key and [cancel] dismiss it.
 * Revoke and Leave use it, so neither can happen by a stray tap (S3, S6).
 */
@Composable
internal fun BoxScope.ConfirmPanel(
    title: String,
    body: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tag: String,
    /** False for a confirmation of something that is not a loss (Approve, Make admin): the button is the primary one. */
    destructive: Boolean = true,
    /** The person named in the title is an agent: the AI marker and [note] show under the body. */
    ai: Boolean = false,
    note: String? = null,
    confirmTag: String = "$tag-confirm",
    cancelTag: String = "$tag-cancel"
) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
            .testTag("$tag-scrim")
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay, shapeOf(theme.radius_sheet)).padding(theme.gap.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitMeta(title, tag = "$tag-title")
        KitMeta(body, tag = "$tag-body")
        if (ai) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)) {
                KitAiMarker()
                if (note != null) KitMeta(note, tag = "$tag-agent-note")
            }
        }
        KitButton(
            confirm,
            onConfirm,
            Modifier.fillMaxWidth(),
            kind = if (destructive) KitButtonKind.Destructive else KitButtonKind.Primary,
            tag = confirmTag
        )
        KitButton(cancel, onCancel, Modifier.fillMaxWidth(), kind = KitButtonKind.Secondary, tag = cancelTag)
    }
}

private const val HALF = .5f
