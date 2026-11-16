// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme

enum class KitAvatarKind { Person, Agent, Channel }

@Composable
fun KitAvatar(
    label: String,
    size: Float,
    kind: KitAvatarKind,
    modifier: Modifier = Modifier,
    icon: String = "orbit",
    ring: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val shape = if (kind == KitAvatarKind.Agent) shapeOf(agentRadius(size)) else CircleShape
    val fill = when (kind) {
        KitAvatarKind.Person -> theme.avatar_person
        KitAvatarKind.Agent -> theme.agent_av
        KitAvatarKind.Channel -> theme.surface
    }
    Box(
        modifier
            .size(dp(size))
            .kitBackdrop()
            .clip(shape)
            .background(fill)
            .then(if (ring) Modifier.border(dp(RfMetrics.emphasis), theme.text, shape) else Modifier)
            .kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        when (kind) {
            KitAvatarKind.Person -> Text(
                label,
                style = kitStyle(personInitial(size), FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
            )
            KitAvatarKind.Agent -> KitIcon(icon, agentGlyph(size), theme.on_agent_av)
            KitAvatarKind.Channel -> KitIcon("hash", agentGlyph(size), theme.secondary)
        }
    }
}
