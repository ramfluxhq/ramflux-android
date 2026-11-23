// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme

@Composable
fun KitSummaryCard(title: String, body: String, modifier: Modifier = Modifier, selected: Boolean = true, tag: String? = null) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_card)
    Row(
        modifier
            .fillMaxWidth()
            .border(dp(RfMetrics.controlStroke), theme.text, shape)
            .background(theme.surface_white, shape)
            .padding(dp(RfMetrics.gutter))
            .kitTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.tabGap))) {
            // Design-line boxes: the card keeps one height whichever script's face renders the text.
            KitText(title, designLine = true, style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle))
            if (body.isNotEmpty()) {
                KitText(body, designLine = true, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
            }
        }
        if (selected) KitIcon("circle-check", RfMetrics.iconAction, theme.text)
    }
}
