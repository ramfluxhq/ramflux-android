// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.ramflux.designsystem.LocalRFTheme

data class KitMenuEntry(val label: String, val icon: String, val danger: Boolean = false, val groupBefore: Boolean = false)

@Composable
fun KitContextMenu(entries: List<KitMenuEntry>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_menu)
    Column(
        modifier.width(
            dp(RfMetrics.menu)
        ).border(dp(RfMetrics.stroke), theme.overlay_border, shape).background(theme.overlay, shape).kitTag(tag)
    ) {
        entries.forEachIndexed { index, entry ->
            if (entry.groupBefore) Box(Modifier.fillMaxWidth().height(dp(RfMetrics.stroke)).background(theme.line))
            KitMenuItem(
                entry,
                index < entries.lastIndex && !entries[index + 1].groupBefore,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun KitMenuItem(entry: KitMenuEntry, hairline: Boolean, onClick: () -> Unit) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (entry.danger) theme.danger else theme.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.menuItem))
            .background(if (pressed) theme.selected else Color.Transparent)
            .then(if (hairline) Modifier.kitHairline(theme.line) else Modifier)
            .padding(horizontal = dp(RfMetrics.menuPad))
            .clickableNoRipple(interaction, true, onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        Text(
            entry.label,
            modifier = Modifier.weight(1f),
            style = kitStyle(theme.size_mobile_body, FontWeight.Normal, tint, RfMetrics.lineTitle)
        )
        KitIcon(entry.icon, RfMetrics.menuIcon, tint)
    }
}

@Composable
fun KitReactionBar(
    reactions: List<String>,
    onReaction: (Int) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_menu)
    Row(
        modifier
            .height(dp(RfMetrics.reaction))
            .border(dp(RfMetrics.stroke), theme.overlay_border, shape)
            .background(theme.overlay, shape)
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.reactionPad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.reactionGap))
    ) {
        reactions.forEachIndexed { index, reaction ->
            Box(
                Modifier.size(dp(RfMetrics.reactionCell), dp(RfMetrics.reaction)).clickableNoRipple(true) {
                    onReaction(index)
                },
                contentAlignment = Alignment.Center
            ) {
                Text(reaction, style = kitStyle(theme.size_page, FontWeight.Normal, theme.text, RfMetrics.lineTitle))
            }
        }
        Box(
            Modifier.size(
                dp(RfMetrics.reactionCell),
                dp(RfMetrics.reaction)
            ).background(theme.surface, shapeOf(theme.radius_xs)).clickableNoRipple(true, onMore),
            contentAlignment = Alignment.Center
        ) {
            KitIcon("plus", RfMetrics.moreIcon, theme.text)
        }
    }
}

@Composable
fun KitDateChip(label: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(modifier.fillMaxWidth().kitTag(tag).padding(vertical = dp(RfMetrics.datePadV)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.background(
                theme.surface,
                shapeOf(theme.radius_chip)
            ).padding(horizontal = dp(RfMetrics.chipPadH), vertical = dp(RfMetrics.chipPadV))
        ) {
            Text(label, style = kitStyle(theme.size_meta, FontWeight.Medium, theme.secondary, RfMetrics.lineTitle))
        }
    }
}

@Composable
fun KitUnreadDivider(label: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        modifier.fillMaxWidth().height(dp(RfMetrics.unread)).kitTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.unreadGap))
    ) {
        Box(Modifier.weight(1f).height(dp(RfMetrics.unreadLine)).background(theme.line_strong))
        Text(label, textAlign = TextAlign.Center, style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle))
        Box(Modifier.weight(1f).height(dp(RfMetrics.unreadLine)).background(theme.line_strong))
    }
}
