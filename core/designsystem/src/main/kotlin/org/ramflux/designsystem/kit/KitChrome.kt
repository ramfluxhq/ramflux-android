// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind

data class KitHeaderAction(val icon: String, val label: String = icon, val onClick: () -> Unit)

data class KitTab(val icon: String, val label: String, val latin: Boolean = false, val badge: String? = null)

@Composable
fun KitStatusBar(time: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        modifier.fillMaxWidth().heightIn(
            min =
            dp(RfMetrics.statusBar)
        ).background(theme.bg).kitTag(tag).padding(horizontal = dp(RfMetrics.statusPad)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KitText(time, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBody, RfFonts.latin))
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

/** A page header. Titles and subtitles wrap instead of clipping; the header grows from its 60 dp frame. */
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
            .heightIn(min = dp(RfMetrics.header))
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
        // The title takes the free width; a text action is capped and wraps, so a long or enlarged label
        // cannot squeeze the title to a few letters.
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) {
                KitText(
                    title,
                    designLine = true,
                    style = if (large) {
                        kitStyle(theme.size_mobile_large_title, FontWeight.Bold, theme.text, RfMetrics.lineTight)
                    } else {
                        kitStyle(theme.size_title, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
                    }
                )
            }
            if (large && !subtitle.isNullOrEmpty()) {
                KitText(
                    subtitle,
                    designLine = true,
                    style = kitStyle(theme.size_meta, FontWeight.Normal, theme.secondary, RfMetrics.lineMeta())
                )
            }
        }
        if (!large && onInfo != null) IconHit("info", RfMetrics.iconInfo, theme.secondary, onInfo, tag = tag?.let { "$it-info" })
        if (!large && actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .widthIn(max = dp(RfMetrics.headerActionMax))
                    .heightIn(min = dp(RfMetrics.hit))
                    .semantics { contentDescription = actionLabel }
                    .clickableNoRipple(true, onAction)
                    .padding(horizontal = dp(RfMetrics.headerGap)),
                Alignment.Center
            ) {
                KitText(actionLabel, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBody))
            }
        }
        actions.forEach { action -> IconHit(action.icon, RfMetrics.iconAction, theme.text, action.onClick, label = action.label) }
    }
}

@Composable
fun KitChatHeader(
    title: String,
    subtitle: String?,
    avatar: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onCall: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    backLabel: String = "chevron-left",
    callLabel: String = "phone",
    moreLabel: String = "ellipsis",
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = dp(RfMetrics.header))
            .background(theme.bg)
            .kitHairline(theme.line)
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.gutter), vertical = dp(RfMetrics.headerPadV)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        if (onBack != null) {
            IconHit("chevron-left", RfMetrics.iconBack, theme.text, onBack, label = backLabel, tag = tag?.let { "$it-back" })
        }
        avatar()
        Column(Modifier.weight(1f)) {
            KitText(
                untrustedDisplay(title, UntrustedKind.Excerpt),
                designLine = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = kitStyle(
                    theme.size_mobile_body,
                    FontWeight.SemiBold,
                    theme.text,
                    RfMetrics.lineTitle
                ).copy(textDirection = TextDirection.Content)
            )
            if (!subtitle.isNullOrEmpty()) {
                KitText(
                    untrustedDisplay(subtitle, UntrustedKind.Excerpt),
                    designLine = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = kitStyle(
                        theme.size_meta,
                        FontWeight.Normal,
                        theme.secondary,
                        RfMetrics.lineMeta()
                    ).copy(textDirection = TextDirection.Content)
                )
            }
        }
        if (onCall != null) IconHit("phone", RfMetrics.iconBack, theme.text, onCall, label = callLabel)
        if (onMore != null) IconHit("ellipsis", RfMetrics.iconBack, theme.text, onMore, label = moreLabel)
    }
}

/**
 * The bottom tab bar. [bottomInset] is the window's bottom system inset (home indicator or navigation
 * bar), supplied by the shell. The design frame draws the 83 dp bar over its 34 dp home-indicator area
 * with a 31 dp bottom band, so the band below the tab row is the inset less that 3 dp overlap; a window
 * without a bottom inset gets no band.
 */
@Composable
fun KitTabBar(
    tabs: List<KitTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: WindowInsets = WindowInsets.navigationBars,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val inset = with(LocalDensity.current) { bottomInset.getBottom(this).toDp() }
    // The 48 dp hit row already reaches 4 dp below the 44 dp tab row into the band.
    val band = (inset - dp(RfMetrics.homeSafeBottom - RfMetrics.tabInset) - dp(RfMetrics.hit - RfMetrics.tab)).coerceAtLeast(dp(0f))
    Column(modifier.fillMaxWidth().background(theme.bg).kitHairline(theme.line, top = true).kitTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(top = dp(RfMetrics.tabPadTop)).heightIn(min = dp(RfMetrics.hit))) {
            tabs.forEachIndexed { index, tab ->
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = dp(RfMetrics.hit))
                        .semantics { contentDescription = tab.label }
                        .clickableNoRipple(true, { onSelect(index) })
                        .kitTag(tag?.let { "$it-$index" })
                ) {
                    KitTabCell(tab, index == selected, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        Spacer(Modifier.height(band))
    }
}

/**
 * The navigation rail used instead of [KitTabBar] at medium and expanded window widths. It offers the
 * same destinations with the same tab cells, stacked from the top, and pads itself by [bottomInset].
 * Labels wrap instead of clipping.
 */
@Composable
fun KitNavigationRail(
    tabs: List<KitTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: WindowInsets = WindowInsets.navigationBars,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Column(
        modifier
            .fillMaxHeight()
            .width(dp(RfMetrics.rail))
            .background(theme.bg)
            .kitHairlineEnd(theme.line)
            .kitTag(tag)
            .windowInsetsPadding(bottomInset)
            .padding(top = dp(RfMetrics.tabPadTop)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.railGap))
    ) {
        tabs.forEachIndexed { index, tab ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = dp(RfMetrics.hit))
                    .semantics { contentDescription = tab.label }
                    .clickableNoRipple(true, { onSelect(index) })
                    .kitTag(tag?.let { "$it-$index" })
                    .padding(horizontal = dp(RfMetrics.railPadH)),
                contentAlignment = Alignment.Center
            ) {
                KitTabCell(tab, index == selected)
            }
        }
    }
}

/** Icon, optional badge and label of one destination, shared by the tab bar and the rail. */
@Composable
private fun KitTabCell(tab: KitTab, on: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val tint = if (on) theme.text else theme.secondary
    Column(
        modifier.heightIn(min = dp(RfMetrics.tab)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.tabGap), Alignment.CenterVertically)
    ) {
        Box(Modifier.size(dp(RfMetrics.tabIcon))) {
            KitIcon(tab.icon, RfMetrics.tabIcon, tint)
            if (tab.badge != null) {
                Box(
                    Modifier
                        .offset(x = dp(RfMetrics.badgeX), y = dp(RfMetrics.badgeY))
                        .heightIn(min = dp(RfMetrics.tabBadge))
                        .widthIn(min = dp(RfMetrics.tabBadgeMin))
                        .clip(shapeOf(theme.radius_md))
                        .background(theme.badge)
                        .padding(horizontal = dp(RfMetrics.tabBadgePad)),
                    contentAlignment = Alignment.Center
                ) {
                    KitText(
                        tab.badge,
                        style = kitStyle(theme.size_mobile_caption, FontWeight.SemiBold, theme.on_badge, RfMetrics.lineTitle)
                    )
                }
            }
        }
        KitText(
            tab.label,
            textAlign = TextAlign.Center,
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
        IconHit(icon, RfMetrics.iconAction, tint, onClick, modifier, label, tag)
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
    modifier: Modifier = Modifier,
    label: String = icon,
    tag: String? = null
) {
    val hit = dp(RfMetrics.hit)
    val slot = dp(RfMetrics.iconButton)
    Layout(modifier = modifier, content = {
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
        layout(slotPx, slotPx) { placeable.placeRelative((slotPx - hitPx) / 2, (slotPx - hitPx) / 2) }
    }
}

/**
 * Reports the drawn slot and places a hit target at least 48 dp on each side over it. [slotWidth] and
 * [slotHeight] are the drawn minimum; content that needs more room, such as text at a large font scale,
 * grows the slot and the hit target with it.
 */
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
        Box(contentAlignment = Alignment.Center) { content() }
        Box(
            Modifier
                .semantics {
                    if (label != null) contentDescription = label
                }
                .clickableNoRipple(enabled, onClick)
                .kitTag(tag)
        )
    }) { measurables, constraints ->
        val hitPx = hit.roundToPx()
        val minW = (width?.roundToPx() ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val minH = (height?.roundToPx() ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val drawn = measurables[0].measure(constraints.copy(minWidth = minW, minHeight = minH))
        val hitW = max(drawn.width, hitPx)
        val hitH = max(drawn.height, hitPx)
        val target = measurables[1].measure(Constraints.fixed(hitW, hitH))
        layout(drawn.width, drawn.height) {
            drawn.placeRelative(0, 0)
            target.placeRelative((drawn.width - hitW) / 2, (drawn.height - hitH) / 2)
        }
    }
}

private fun RfMetrics.lineMeta(): Float = lineTitle
