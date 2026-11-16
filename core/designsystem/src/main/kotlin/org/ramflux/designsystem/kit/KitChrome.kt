// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.max
import org.ramflux.designsystem.LocalRFTheme

data class KitHeaderAction(val icon: String, val label: String = icon, val onClick: () -> Unit)

data class KitTab(val icon: String, val label: String, val latin: Boolean = false, val badge: String? = null)

@Composable
fun KitStatusBar(time: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        modifier.fillMaxWidth().height(
            dp(RfMetrics.statusBar)
        ).background(theme.bg).kitTag(tag).padding(horizontal = dp(RfMetrics.statusPad)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBody, RfFonts.latin))
        Row(horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.statusGap)), verticalAlignment = Alignment.CenterVertically) {
            KitIcon("signal", RfMetrics.statusIcon, theme.text)
            KitIcon("wifi", RfMetrics.statusIcon, theme.text)
            KitIcon("battery-full", RfMetrics.statusBattery, theme.text)
        }
    }
}

@Composable
fun KitHomeIndicator(modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(modifier.kitTag(tag), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(dp(RfMetrics.homeWidth), dp(RfMetrics.homeHeight))
                .clip(shapeOf(RfMetrics.homeRadius))
                .background(theme.text)
        )
    }
}

@Composable
fun KitPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actions: List<KitHeaderAction> = emptyList(),
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.header))
            .background(theme.bg)
            .kitHairline(theme.line)
            .kitTag(tag)
            .padding(
                start = dp(if (large) RfMetrics.gutter else RfMetrics.gutter),
                end = dp(if (large) RfMetrics.headerGap else RfMetrics.gutter)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        if (!large && onBack != null) {
            IconHit("chevron-left", RfMetrics.iconBack, theme.text, onBack, tag = tag?.let { "$it-back" })
        }
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (large) {
                        kitStyle(theme.size_mobile_large_title, FontWeight.Bold, theme.text, RfMetrics.lineTight)
                    } else {
                        kitStyle(theme.size_title, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
                    }
                )
            }
            if (large && !subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_meta, FontWeight.Normal, theme.secondary, RfMetrics.lineMeta())
                )
            }
        }
        if (!large && onInfo != null) IconHit("info", RfMetrics.iconInfo, theme.secondary, onInfo, tag = tag?.let { "$it-info" })
        if (!large && actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .height(dp(RfMetrics.hit))
                    .semantics { contentDescription = actionLabel }
                    .clickableNoRipple(true, onAction)
                    .padding(horizontal = dp(RfMetrics.headerGap)),
                Alignment.Center
            ) {
                Text(actionLabel, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBody))
            }
        }
        actions.forEach { action -> IconHit(action.icon, RfMetrics.iconAction, theme.text, action.onClick, action.label) }
    }
}

@Composable
fun KitChatHeader(
    title: String,
    subtitle: String?,
    avatar: @Composable () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCall: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.header))
            .background(theme.bg)
            .kitHairline(theme.line)
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.gutter), vertical = dp(RfMetrics.headerPadV)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        IconHit("chevron-left", RfMetrics.iconBack, theme.text, onBack, tag = tag?.let { "$it-back" })
        avatar()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(theme.size_meta, FontWeight.Normal, theme.secondary, RfMetrics.lineMeta())
                )
            }
        }
        if (onCall != null) IconHit("phone", RfMetrics.iconBack, theme.text, onCall)
        if (onMore != null) IconHit("ellipsis", RfMetrics.iconBack, theme.text, onMore)
    }
}

@Composable
fun KitTabBar(tabs: List<KitTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Column(
        modifier.fillMaxWidth().height(dp(RfMetrics.tabBar)).background(theme.bg).kitHairline(theme.line, top = true).kitTag(tag)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = dp(RfMetrics.tabPadTop)).height(dp(RfMetrics.hit))) {
            tabs.forEachIndexed { index, tab ->
                val on = index == selected
                val tint = if (on) theme.text else theme.secondary
                Box(
                    Modifier
                        .weight(1f)
                        .height(dp(RfMetrics.hit))
                        .semantics { contentDescription = tab.label }
                        .clickableNoRipple(true, { onSelect(index) })
                        .kitTag(tag?.let { "$it-$index" })
                ) {
                    Column(
                        Modifier.height(dp(RfMetrics.tab)).align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.tabGap), Alignment.CenterVertically)
                    ) {
                        Box(Modifier.size(dp(RfMetrics.tabIcon))) {
                            KitIcon(tab.icon, RfMetrics.tabIcon, tint)
                            if (tab.badge != null) {
                                Box(
                                    Modifier
                                        .offset(x = dp(RfMetrics.badgeX), y = dp(RfMetrics.badgeY))
                                        .height(dp(RfMetrics.tabBadge))
                                        .widthIn(min = dp(RfMetrics.tabBadgeMin))
                                        .clip(shapeOf(theme.radius_md))
                                        .background(theme.badge)
                                        .padding(horizontal = dp(RfMetrics.tabBadgePad)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tab.badge,
                                        style = kitStyle(
                                            theme.size_mobile_caption,
                                            FontWeight.SemiBold,
                                            theme.on_badge,
                                            RfMetrics.lineTitle
                                        )
                                    )
                                }
                            }
                        }
                        Text(
                            tab.label,
                            style = kitStyle(
                                theme.size_mobile_caption,
                                if (on) FontWeight.SemiBold else FontWeight.Medium,
                                tint,
                                RfMetrics.lineTitle,
                                if (tab.latin) RfFonts.latin else RfFonts.ui
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(dp(RfMetrics.tabInset - (RfMetrics.hit - RfMetrics.tab))))
    }
}

@Composable
fun KitIconButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val tint = if (enabled) theme.text else theme.control
    if (enabled) {
        IconHit(icon, RfMetrics.iconAction, tint, onClick, label, tag)
    } else {
        Box(
            modifier.size(dp(RfMetrics.iconButton)).semantics { contentDescription = label }.kitTag(tag),
            contentAlignment = Alignment.Center
        ) {
            KitIcon(icon, RfMetrics.iconAction, tint)
        }
    }
}

@Composable
internal fun IconHit(
    icon: String,
    size: Float,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    label: String = icon,
    tag: String? = null
) {
    val hit = dp(RfMetrics.hit)
    val slot = dp(RfMetrics.iconButton)
    Layout(content = {
        Box(
            Modifier
                .size(hit)
                .semantics { contentDescription = label }
                .clickableNoRipple(true, onClick)
                .kitTag(tag),
            contentAlignment = Alignment.Center
        ) {
            KitIcon(icon, size, tint)
        }
    }) { measurables, constraints ->
        val hitPx = hit.roundToPx()
        val slotPx = slot.roundToPx()
        val placeable = measurables[0].measure(
            constraints.copy(minWidth = hitPx, maxWidth = hitPx, minHeight = hitPx, maxHeight = hitPx)
        )
        layout(slotPx, slotPx) { placeable.place((slotPx - hitPx) / 2, (slotPx - hitPx) / 2) }
    }
}

/** Reports the drawn slot and places a hit target at least 48 dp on each side. */
@Composable
internal fun HitOverlap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slotWidth: Float? = null,
    slotHeight: Float? = null,
    enabled: Boolean = true,
    label: String? = null,
    tag: String? = null,
    content: @Composable () -> Unit
) {
    val hit = dp(RfMetrics.hit)
    val width = slotWidth?.let { dp(it) }
    val height = slotHeight?.let { dp(it) }
    Layout(modifier = modifier, content = {
        Box(
            Modifier
                .semantics {
                    if (label != null) contentDescription = label
                }
                .clickableNoRipple(enabled, onClick)
                .kitTag(tag),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }) { measurables, constraints ->
        val hitPx = hit.roundToPx()
        val slotW = width?.roundToPx() ?: constraints.maxWidth
        val slotH = height?.roundToPx() ?: constraints.maxHeight
        val hitW = max(slotW, hitPx)
        val hitH = max(slotH, hitPx)
        val placeable = measurables[0].measure(
            constraints.copy(minWidth = hitW, maxWidth = hitW, minHeight = hitH, maxHeight = hitH)
        )
        layout(slotW, slotH) { placeable.place((slotW - hitW) / 2, (slotH - hitH) / 2) }
    }
}

private fun RfMetrics.lineMeta(): Float = lineTitle
