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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme

/**
 * One reaction cell: an emoji [glyph], or a kit [icon] for a reaction that has no emoji. [label] is what
 * accessibility services read.
 */
data class KitReaction(val label: String, val glyph: String = label, val icon: String? = null)

data class KitMenuEntry(val label: String, val icon: String, val danger: Boolean = false, val groupBefore: Boolean = false)

@Composable
fun KitContextMenu(entries: List<KitMenuEntry>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    // Not shown under the app-lock cover.
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_menu)
    Column(
        modifier
            .widthIn(max = dp(RfMetrics.menu))
            .fillMaxWidth()
            .border(dp(RfMetrics.stroke), theme.overlay_border, shape)
            .background(theme.overlay, shape)
            .kitTag(tag)
    ) {
        var start = 0
        entries.forEachIndexed { index, entry ->
            if (entry.groupBefore && index > start) {
                MenuRun(entries.subList(start, index), start, onSelect)
                Box(Modifier.fillMaxWidth().height(dp(RfMetrics.stroke)).background(theme.line))
                start = index
            }
        }
        if (start < entries.size) MenuRun(entries.subList(start, entries.size), start, onSelect)
    }
}

@Composable
private fun MenuRun(entries: List<KitMenuEntry>, offset: Int, onSelect: (Int) -> Unit) {
    val visual = dp(RfMetrics.menuItem)
    val hit = dp(RfMetrics.hit)
    Layout(content = {
        entries.forEachIndexed { index, entry ->
            KitMenuItem(entry, index < entries.lastIndex, offset.toString() + "-" + index.toString()) {
                onSelect(offset + index)
            }
        }
    }) { measurables, constraints ->
        val visualPx = visual.roundToPx()
        val hitPx = hit.roundToPx()
        val placeables = measurables.map {
            it.measure(constraints.copy(minWidth = constraints.maxWidth, minHeight = hitPx, maxHeight = Constraints.Infinity))
        }
        // Each row draws at least the item height; a label that wraps grows its row and its hit frame.
        val drawn = placeables.map { maxOf(visualPx, it.height - (hitPx - visualPx)) }
        layout(constraints.maxWidth, drawn.sum()) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(0, y + (drawn[index] - placeable.height) / 2)
                y += drawn[index]
            }
        }
    }
}

@Composable
private fun KitMenuItem(entry: KitMenuEntry, hairline: Boolean, hitTag: String, onClick: () -> Unit) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (entry.danger) theme.danger else theme.text
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = dp(RfMetrics.hit))
            .semantics { contentDescription = entry.label }
            .clickableNoRipple(interaction, true, onClick)
            .kitTag("menu-hit-" + hitTag),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = dp(RfMetrics.menuItem))
                .background(if (pressed) theme.selected else Color.Transparent)
                .then(if (hairline) Modifier.kitHairline(theme.line) else Modifier)
                .padding(horizontal = dp(RfMetrics.menuPad)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
        ) {
            KitText(
                entry.label,
                modifier = Modifier.weight(1f),
                style = kitStyle(theme.size_mobile_body, FontWeight.Normal, tint, RfMetrics.lineTitle)
            )
            KitIcon(entry.icon, RfMetrics.menuIcon, tint)
        }
    }
}

@Composable
fun KitReactionBar(
    reactions: List<KitReaction>,
    onReaction: (Int) -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    moreLabel: String = "plus",
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_menu)
    Row(
        modifier
            .heightIn(min = dp(RfMetrics.reaction))
            .kitBackdrop()
            .border(dp(RfMetrics.stroke), theme.overlay_border, shape)
            .background(theme.overlay, shape)
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.reactionPad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.reactionGap))
    ) {
        reactions.forEachIndexed { index, reaction ->
            HitOverlap(
                { onReaction(index) },
                slotWidth = RfMetrics.reactionCell,
                slotHeight = RfMetrics.reaction,
                label = reaction.label,
                tag = if (tag == null) null else tag + "-" + index.toString()
            ) {
                Box(
                    Modifier
                        .sizeIn(minWidth = dp(RfMetrics.reactionCell), minHeight = dp(RfMetrics.reaction))
                        .kitTag(if (tag == null) null else tag + "-cell-" + index.toString()),
                    contentAlignment = Alignment.Center
                ) {
                    if (reaction.icon != null) {
                        KitIcon(reaction.icon, RfMetrics.moreIcon, theme.text)
                    } else {
                        KitText(reaction.glyph, style = kitStyle(theme.size_page, FontWeight.Normal, theme.text, RfMetrics.lineTitle))
                    }
                }
            }
        }
        if (onMore != null) {
            ReactionMore(onMore, moreLabel, tag)
        }
    }
}

@Composable
private fun ReactionMore(onMore: () -> Unit, label: String, tag: String?) {
    val theme = LocalRFTheme.current
    HitOverlap(
        onMore,
        slotWidth = RfMetrics.reactionCell,
        slotHeight = RfMetrics.reaction,
        label = label,
        tag = if (tag == null) null else tag + "-more"
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = dp(RfMetrics.reactionCell), minHeight = dp(RfMetrics.reaction))
                .background(theme.surface, shapeOf(theme.radius_xs))
                .kitTag(if (tag == null) null else tag + "-more-cell"),
            contentAlignment = Alignment.Center
        ) {
            KitIcon("plus", RfMetrics.moreIcon, theme.text)
        }
    }
}

/** A secondary caption line: call records, typing, reactions and delivery notes in a timeline. */
@Composable
fun KitMeta(
    text: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    tag: String? = null,
    tone: androidx.compose.ui.graphics.Color? = null
) {
    val theme = LocalRFTheme.current
    KitText(
        text,
        modifier.kitTag(tag),
        style = kitStyle(theme.size_meta, FontWeight.Normal, tone ?: theme.secondary, RfMetrics.lineTitle),
        textAlign = if (centered) TextAlign.Center else TextAlign.Start
    )
}

@Composable
fun KitDateChip(label: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(modifier.fillMaxWidth().kitBackdrop().kitTag(tag).padding(vertical = dp(RfMetrics.datePadV)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.background(
                theme.surface,
                shapeOf(theme.radius_chip)
            ).padding(horizontal = dp(RfMetrics.chipPadH), vertical = dp(RfMetrics.chipPadV))
        ) {
            KitText(label, style = kitStyle(theme.size_meta, FontWeight.Medium, theme.secondary, RfMetrics.lineTitle), designLine = true)
        }
    }
}

@Composable
fun KitUnreadDivider(label: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        modifier.fillMaxWidth().heightIn(min = dp(RfMetrics.unread)).kitBackdrop().kitTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.unreadGap))
    ) {
        Box(Modifier.weight(1f).height(dp(RfMetrics.unreadLine)).background(theme.line_strong))
        KitText(
            label,
            textAlign = TextAlign.Center,
            style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
        )
        Box(Modifier.weight(1f).height(dp(RfMetrics.unreadLine)).background(theme.line_strong))
    }
}
