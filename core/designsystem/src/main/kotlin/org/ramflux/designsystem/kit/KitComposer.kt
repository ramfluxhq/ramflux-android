// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind

@Composable
fun KitComposer(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    sendActive: Boolean = false,
    attachEnabled: Boolean = true,
    mode: String? = null,
    option: String? = null,
    optionChecked: Boolean = false,
    onOption: (Boolean) -> Unit = {},
    replyAuthor: String? = null,
    replyExcerpt: String? = null,
    onCloseReply: (() -> Unit)? = null,
    attachmentName: String? = null,
    attachmentState: String? = null,
    onRemoveAttachment: (() -> Unit)? = null,
    maxLines: Int = 1,
    enabled: Boolean = true,
    sendLabel: String = "send",
    attachLabel: String = "plus",
    closeLabel: String = "x",
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Column(
        modifier.fillMaxWidth().background(theme.bg).kitHairline(theme.line, top = true).kitTag(tag).padding(dp(RfMetrics.composerPad)),
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        if (!mode.isNullOrEmpty()) {
            Row(
                Modifier
                    .heightIn(min = dp(RfMetrics.modeChip))
                    .background(theme.surface, shapeOf(theme.radius_chip))
                    .padding(horizontal = dp(RfMetrics.modePad)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))
            ) {
                KitText(mode, style = kitStyle(theme.size_secondary, FontWeight.Medium, theme.text, RfMetrics.lineSecondary))
                KitIcon("chevron-down", RfMetrics.chevron, theme.secondary)
            }
        }
        if (!option.isNullOrEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = dp(RfMetrics.optionRow)).padding(horizontal = dp(RfMetrics.headerGap)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.approvalGap))
            ) {
                KitCheckbox(optionChecked, onOption, size = RfMetrics.optionBox, radius = theme.radius_xs, strokeColor = theme.line_strong)
                KitText(option, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
            }
        }
        if (!replyAuthor.isNullOrEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(
                    min =
                    dp(RfMetrics.replyBar)
                ).padding(start = dp(RfMetrics.gutter), top = dp(RfMetrics.capsulePad), bottom = dp(RfMetrics.capsulePad)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.approvalGap))
            ) {
                Box(
                    Modifier.size(
                        dp(RfMetrics.replyAccentWidth),
                        dp(RfMetrics.replyAccentHeight)
                    ).clip(shapeOf(RfMetrics.replyRadius)).background(theme.accent)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.labelGap))) {
                    KitText(
                        untrustedDisplay(replyAuthor, UntrustedKind.Excerpt),
                        style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.text, RfMetrics.lineSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!replyExcerpt.isNullOrEmpty()) {
                        KitText(
                            untrustedDisplay(replyExcerpt, UntrustedKind.Excerpt),
                            style = kitStyle(theme.size_button, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onCloseReply != null) IconHit("x", RfMetrics.iconInfo, theme.secondary, onCloseReply, closeLabel)
            }
        }
        if (!attachmentName.isNullOrEmpty()) {
            Row(
                Modifier
                    .heightIn(min = dp(RfMetrics.attachment))
                    .background(theme.surface, shapeOf(theme.radius_md))
                    .padding(start = dp(RfMetrics.headerGap)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
            ) {
                Box(
                    Modifier.size(dp(RfMetrics.fileTile)).background(theme.surface_white, shapeOf(theme.radius_md)),
                    contentAlignment = Alignment.Center
                ) {
                    KitIcon("file-text", RfMetrics.fileGlyph, theme.text)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.labelGap))) {
                    KitText(attachmentName, style = kitStyle(theme.size_body, FontWeight.Medium, theme.text, RfMetrics.lineBody))
                    if (!attachmentState.isNullOrEmpty()) {
                        KitText(attachmentState, style = kitStyle(theme.size_meta, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle))
                    }
                }
                if (onRemoveAttachment != null) IconHit("x", RfMetrics.fieldIcon, theme.secondary, onRemoveAttachment, closeLabel)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = dp(RfMetrics.composerRow)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))
        ) {
            Box(
                Modifier.size(
                    dp(RfMetrics.iconButton)
                ).alpha(if (attachEnabled) 1f else RfMetrics.disabledAlpha)
                    .semantics { contentDescription = attachLabel }
                    .clickableNoRipple(attachEnabled, onAttach),
                contentAlignment = Alignment.Center
            ) {
                KitIcon("plus", RfMetrics.attachIcon, theme.secondary)
            }
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = dp(RfMetrics.composerRow))
                    .clip(shapeOf(RfMetrics.capsuleRadius))
                    .background(theme.input)
                    .border(dp(RfMetrics.stroke), theme.input_border, shapeOf(RfMetrics.capsuleRadius))
                    .padding(
                        start = dp(RfMetrics.capsulePadStart),
                        top = dp(RfMetrics.capsulePad),
                        end = dp(RfMetrics.capsulePad),
                        bottom = dp(RfMetrics.capsulePad)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    enabled = enabled,
                    singleLine = maxLines == 1,
                    maxLines = maxLines,
                    textStyle = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.text, RfMetrics.lineTitle).forScript(value),
                    cursorBrush = SolidColor(theme.focus),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                KitText(
                                    placeholder,
                                    style = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle)
                                )
                            }
                            inner()
                        }
                    }
                )
                HitOverlap(
                    onSend,
                    slotWidth = RfMetrics.send,
                    slotHeight = RfMetrics.send,
                    enabled = sendActive,
                    label = sendLabel,
                    tag = if (tag == null) null else tag + "-send"
                ) {
                    Box(
                        Modifier
                            .size(dp(RfMetrics.send))
                            .clip(shapeOf(theme.radius_xl))
                            .background(if (sendActive) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
                            .kitTag(if (tag == null) null else tag + "-send-icon"),
                        contentAlignment = Alignment.Center
                    ) {
                        KitIcon("arrow-up", RfMetrics.sendIcon, if (sendActive) theme.on_accent else theme.control)
                    }
                }
            }
        }
    }
}
