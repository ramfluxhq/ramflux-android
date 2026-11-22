// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.ramflux.designsystem.LocalRFTheme

/** A round neutral avatar with a glyph: the message-requests row and group rows on MOB-B01. */
@Composable
fun KitIconAvatar(icon: String, size: Float, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(
        modifier.size(dp(size)).clip(CircleShape).background(theme.surface).kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        KitIcon(icon, agentGlyph(size), theme.secondary)
    }
}
