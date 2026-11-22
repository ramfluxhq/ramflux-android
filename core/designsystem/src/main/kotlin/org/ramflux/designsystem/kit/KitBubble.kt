// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import org.ramflux.designsystem.LocalRFTheme

enum class KitBubbleKind { Own, Incoming, File }

@Composable
fun KitBubble(
    body: String,
    modifier: Modifier = Modifier,
    kind: KitBubbleKind = KitBubbleKind.Own,
    time: String? = null,
    edited: String? = null,
    sender: String? = null,
    agentTag: String? = null,
    pending: Boolean = false,
    read: Boolean = false,
    failed: Boolean = false,
    transient: Boolean = false,
    fileName: String? = null,
    fileMeta: String? = null,
    fileIcon: String = "file-text",
    maxWidth: Dp? = null,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val own = kind == KitBubbleKind.Own
    val fill = if (own) theme.outgoing else theme.incoming
    val text = if (own) theme.outgoing_text else theme.text
    val meta = if (own) theme.outgoing_meta else theme.incoming_meta
    val shape = if (kind == KitBubbleKind.File) {
        RoundedCornerShape(
            topStart = dp(theme.radius_bubble_joined),
            topEnd = dp(theme.radius_bubble),
            bottomEnd = dp(theme.radius_bubble),
            bottomStart = dp(theme.radius_bubble)
        )
    } else {
        shapeOf(theme.radius_bubble)
    }
    BoxWithConstraints(modifier.alpha(if (transient) 0f else 1f)) {
        val cap = maxWidth ?: minOf(dp(theme.mobile_bubble_max), this.maxWidth * RfMetrics.bubbleWidthFraction)
        Column(
            Modifier
                .widthIn(max = cap)
                .clip(shape)
                .background(fill)
                .then(if (failed) Modifier.border(dp(RfMetrics.controlStroke), theme.danger, shape) else Modifier)
                .kitTag(tag)
                .padding(
                    horizontal = dp(if (kind == KitBubbleKind.File) RfMetrics.bubblePadV else RfMetrics.bubblePadH),
                    vertical = dp(RfMetrics.bubblePadV)
                ),
            verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))
        ) {
            if (kind == KitBubbleKind.Incoming && (!sender.isNullOrEmpty() || !agentTag.isNullOrEmpty())) {
                Row(horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)), verticalAlignment = Alignment.CenterVertically) {
                    if (!sender.isNullOrEmpty()) {
                        KitText(sender, style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.text, RfMetrics.lineSecondary))
                    }
                    if (!agentTag.isNullOrEmpty()) {
                        Box(
                            Modifier
                                .heightIn(min = dp(RfMetrics.agentTag))
                                .background(theme.surface_white, shapeOf(theme.radius_chip))
                                .padding(horizontal = dp(RfMetrics.chipPadH)),
                            contentAlignment = Alignment.Center
                        ) {
                            KitText(
                                agentTag,
                                style = kitStyle(
                                    theme.size_mobile_caption,
                                    FontWeight.SemiBold,
                                    theme.secondary,
                                    RfMetrics.lineTitle,
                                    RfFonts.latin
                                )
                            )
                        }
                    }
                }
            }
            if (kind == KitBubbleKind.File) {
                Row(horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(dp(RfMetrics.fileTile)).background(theme.surface_white, shapeOf(theme.radius_md)),
                        contentAlignment = Alignment.Center
                    ) {
                        KitIcon(fileIcon, RfMetrics.fileGlyph, theme.text)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.labelGap))) {
                        KitText(fileName.orEmpty(), style = kitStyle(theme.size_body, FontWeight.Medium, theme.text, RfMetrics.lineBody))
                        if (!fileMeta.isNullOrEmpty() || !time.isNullOrEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!fileMeta.isNullOrEmpty()) {
                                    KitText(
                                        fileMeta,
                                        style = kitStyle(
                                            theme.size_mobile_caption,
                                            FontWeight.Normal,
                                            meta,
                                            RfMetrics.lineTitle,
                                            RfFonts.latin,
                                            1f
                                        )
                                    )
                                }
                                if (!time.isNullOrEmpty()) {
                                    KitText(
                                        time,
                                        style = kitStyle(
                                            theme.size_mobile_caption,
                                            FontWeight.Normal,
                                            meta,
                                            RfMetrics.lineTitle,
                                            RfFonts.latin
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)), verticalAlignment = Alignment.Bottom) {
                    KitText(
                        body,
                        modifier = Modifier.weight(1f, fill = false),
                        style = kitStyle(theme.size_mobile_body, FontWeight.Normal, text, RfMetrics.lineBubble)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.replyRadius)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!edited.isNullOrEmpty()) {
                            KitText(
                                edited,
                                style = kitStyle(theme.size_mobile_caption, FontWeight.Normal, meta, RfMetrics.lineTitle, RfFonts.latin)
                            )
                        }
                        if (!time.isNullOrEmpty()) {
                            KitText(
                                time,
                                style = kitStyle(theme.size_mobile_caption, FontWeight.Normal, meta, RfMetrics.lineTitle, RfFonts.latin)
                            )
                        }
                        if (pending) KitIcon("clock-3", RfMetrics.metaIcon, meta)
                        if (own && !pending && (read || !time.isNullOrEmpty())) {
                            KitIcon("check-check", RfMetrics.metaIcon, if (read) text else meta)
                        }
                    }
                }
            }
        }
    }
}
