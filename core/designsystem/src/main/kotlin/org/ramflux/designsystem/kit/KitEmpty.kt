// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme

@Composable
fun KitEmpty(
    icon: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    tag: String? = null,
    compactAction: Boolean = false
) {
    val theme = LocalRFTheme.current
    Column(
        modifier.fillMaxWidth().padding(dp(RfMetrics.gutter)).kitTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        KitIconTile(icon)
        KitText(title, style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle))
        if (body.isNotEmpty()) {
            KitText(body, style = kitStyle(theme.size_body, FontWeight.Normal, theme.secondary, RfMetrics.lineBody))
        }
        if (action != null && onAction != null) {
            // Both button forms carry the action tag, so the action is addressable whichever form a screen uses.
            val actionTag = tag?.let { "$it-action" }
            // Boards MOB-B01X and MOB-B12 use a compact outlined button sized to its label.
            if (compactAction) {
                KitButton(action, onAction, kind = KitButtonKind.Secondary, size = KitButtonSize.Compact, tag = actionTag)
            } else {
                KitButton(action, onAction, Modifier.fillMaxWidth(), tag = actionTag)
            }
        }
    }
}
