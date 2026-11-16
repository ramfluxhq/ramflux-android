// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.ramflux.designsystem.LocalRFTheme

enum class KitRowTrailing { Chevron, Check, None }

@Composable
fun KitIconTile(icon: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(
        modifier.size(dp(RfMetrics.tile)).background(theme.surface, shapeOf(theme.radius_sm)).kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        KitIcon(icon, RfMetrics.tileGlyph, theme.text)
    }
}

@Composable
fun KitInfoButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "info", tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(
        modifier
            .size(dp(RfMetrics.hit))
            .semantics { contentDescription = label }
            .clickableNoRipple(true, onClick)
            .kitTag(tag?.let { "$it-hit" }),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(dp(RfMetrics.iconButton)).kitTag(tag), contentAlignment = Alignment.Center) {
            KitIcon("info", RfMetrics.iconInfo, theme.secondary)
        }
    }
}

@Composable
fun KitRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    value: String? = null,
    tagLabel: String? = null,
    trailing: KitRowTrailing = KitRowTrailing.Chevron,
    enabled: Boolean = true,
    locked: Boolean = false,
    divider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    testTag: String? = null
) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tappable = enabled && !locked
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.row))
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .background(if (pressed && tappable) theme.selected else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (divider) Modifier.kitHairline(theme.line) else Modifier)
            .kitTag(testTag)
            .clickableNoRipple(interaction, tappable, onClick)
            .padding(horizontal = dp(RfMetrics.gutter), vertical = dp(RfMetrics.rowPadV)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.rowGap))
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            KitIconTile(icon)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.labelGap))) {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.text, RfMetrics.lineTitle)
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary)
                )
            }
        }
        if (!tagLabel.isNullOrEmpty()) {
            Box(
                Modifier.background(
                    theme.warning_bg,
                    shapeOf(theme.radius_chip)
                ).padding(horizontal = dp(RfMetrics.tagPadH), vertical = dp(RfMetrics.tagPadV))
            ) {
                Text(tagLabel, style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.warning, RfMetrics.lineMetaRow()))
            }
        }
        if (trailingContent != null) {
            trailingContent()
        } else {
            when (trailing) {
                KitRowTrailing.Chevron -> if (locked) {
                    Box(Modifier.alpha(RfMetrics.disabledAlpha)) {
                        KitIcon("chevron-right", RfMetrics.chevron, theme.control)
                    }
                } else {
                    KitIcon("chevron-right", RfMetrics.chevron, theme.control)
                }
                KitRowTrailing.Check -> KitIcon("circle-check", RfMetrics.chevron, theme.text)
                KitRowTrailing.None -> Unit
            }
        }
    }
}

@Composable
fun KitConversationRow(
    title: String,
    preview: String,
    time: String,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sender: String? = null,
    muted: Boolean = false,
    pinned: Boolean = false,
    read: Boolean = false,
    badge: String? = null,
    enabled: Boolean = true,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.conversation))
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .background(if (pressed && enabled) theme.selected else androidx.compose.ui.graphics.Color.Transparent)
            .kitTag(tag)
            .clickableNoRipple(interaction, enabled, onClick)
            .padding(start = dp(RfMetrics.gutter)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.avatarGap))
    ) {
        avatar()
        Column(
            Modifier.weight(1f).fillMaxHeight().kitHairline(theme.line).padding(end = dp(RfMetrics.gutter)),
            verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.summaryGap), Alignment.CenterVertically)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
                )
                if (muted) KitIcon("bell-off", RfMetrics.metaIcon, theme.secondary)
                Box(Modifier.weight(1f))
                Text(
                    time,
                    style = kitStyle(theme.size_mobile_caption, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle, RfFonts.latin)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))) {
                if (read) KitIcon("check-check", RfMetrics.metaIcon, theme.secondary)
                if (!sender.isNullOrEmpty()) {
                    Text(sender, style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, RfMetrics.lineBody))
                }
                Text(
                    preview,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_body, FontWeight.Normal, theme.secondary, RfMetrics.lineBody)
                )
                if (!badge.isNullOrEmpty()) {
                    Box(
                        Modifier
                            .height(dp(RfMetrics.listBadge))
                            .background(theme.badge, shapeOf(RfMetrics.listBadgeRadius))
                            .padding(horizontal = dp(RfMetrics.listBadgePad)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(badge, style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.on_badge, RfMetrics.lineTitle))
                    }
                }
                if (pinned) KitIcon("pin", RfMetrics.metaIcon, theme.secondary)
            }
        }
    }
}

private fun RfMetrics.lineMetaRow(): Float = lineTitle
