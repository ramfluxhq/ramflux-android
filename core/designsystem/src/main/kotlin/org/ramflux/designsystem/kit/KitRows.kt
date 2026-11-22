// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind

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

/**
 * A settings or action row. Label and value wrap instead of clipping; the row grows from its 58 dp frame.
 * With [peer], the label is peer-supplied (a contact or group name): it is neutralised and laid out in its
 * own content direction, so it cannot reorder the row. The value stays host text.
 */
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
    peer: Boolean = false,
    testTag: String? = null
) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tappable = enabled && !locked
    val shownLabel = if (peer) untrustedDisplay(label, UntrustedKind.Name) else label
    val direction = if (peer) TextDirection.Content else TextDirection.Unspecified
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = dp(RfMetrics.row))
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .kitBackdrop()
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
            KitText(
                shownLabel,
                designLine = true,
                style = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.text, RfMetrics.lineTitle).copy(textDirection = direction)
            )
            if (!value.isNullOrEmpty()) {
                KitText(
                    value,
                    designLine = true,
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
                KitText(tagLabel, style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.warning, RfMetrics.lineMetaRow()))
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
            .heightIn(min = dp(RfMetrics.conversation))
            .height(IntrinsicSize.Min)
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .kitBackdrop()
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
                KitText(
                    untrustedDisplay(title, UntrustedKind.Excerpt),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle).copy(textDirection = TextDirection.Content)
                )
                if (muted) KitIcon("bell-off", RfMetrics.metaIcon, theme.secondary)
                Box(Modifier.weight(1f))
                KitText(
                    time,
                    style = kitStyle(theme.size_mobile_caption, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle, RfFonts.latin)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))) {
                if (read) KitIcon("check-check", RfMetrics.metaIcon, theme.secondary)
                if (!sender.isNullOrEmpty()) {
                    KitText(
                        untrustedDisplay(sender, UntrustedKind.Excerpt),
                        style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, RfMetrics.lineBody).copy(textDirection = TextDirection.Content)
                    )
                }
                KitText(
                    untrustedDisplay(preview, UntrustedKind.Excerpt),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_body, FontWeight.Normal, theme.secondary, RfMetrics.lineBody).copy(textDirection = TextDirection.Content)
                )
                if (!badge.isNullOrEmpty()) {
                    Box(
                        Modifier
                            .heightIn(min = dp(RfMetrics.listBadge))
                            .background(theme.badge, shapeOf(RfMetrics.listBadgeRadius))
                            .padding(horizontal = dp(RfMetrics.listBadgePad)),
                        contentAlignment = Alignment.Center
                    ) {
                        KitText(badge, style = kitStyle(theme.size_meta, FontWeight.SemiBold, theme.on_badge, RfMetrics.lineTitle))
                    }
                }
                if (pinned) KitIcon("pin", RfMetrics.metaIcon, theme.secondary)
            }
        }
    }
}

private fun RfMetrics.lineMetaRow(): Float = lineTitle
