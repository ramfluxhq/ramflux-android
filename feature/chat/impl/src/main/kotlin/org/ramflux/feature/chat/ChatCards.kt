// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.feature.chat.impl.R

/**
 * One agent surface in the timeline. Until the native renderer draws the tree, the author's fallback text is the
 * content, as peer-controlled text through the untrusted-text primitive. The card has no controls in this build.
 */
@Composable
internal fun CardView(card: CardItem, cap: Dp, modifier: Modifier) {
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(theme.radius_card.dp)
    Box(modifier) {
        Column(
            Modifier
                .widthIn(max = cap)
                .border(theme.gap.dp / 16, theme.line, shape)
                .background(theme.surface_white, shape)
                .padding(theme.gap.dp)
                .testTag("card-" + card.ref),
            verticalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
        ) {
            UntrustedText(card.fallback, kind = UntrustedKind.Excerpt)
            when (card.lifecycle) {
                CardLifecycle.CLOSED, CardLifecycle.SUPERSEDED -> KitMeta(stringResource(R.string.card_closed))
                CardLifecycle.OUT_OF_SYNC -> KitMeta(stringResource(R.string.card_out_of_sync))
                else -> Unit
            }
        }
    }
}
