// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import org.ramflux.designsystem.LocalRFTheme

/**
 * An image, animated image or video bubble. The box size comes only from [aspect] (message data, clamped
 * to 0.5-2.0) and the width cap, so the row never changes height when the download completes. Without
 * [image] the box shows [icon] and [status] (download, progress, expired, removed, hidden).
 */
@Composable
fun KitMediaBubble(
    image: ImageBitmap?,
    aspect: Float,
    own: Boolean,
    modifier: Modifier = Modifier,
    icon: String? = null,
    status: String? = null,
    time: String? = null,
    badge: String? = null,
    description: String? = null,
    maxWidth: Dp? = null,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
    glyph: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_bubble)
    val ratio = aspect.coerceIn(MIN_RATIO, MAX_RATIO)
    BoxWithConstraints(modifier) {
        val cap = maxWidth ?: kitBubbleCap(this.maxWidth)
        Box(
            Modifier
                .width(cap)
                .aspectRatio(ratio)
                .clip(shape)
                .background(if (own) theme.outgoing else theme.incoming)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .kitTag(tag),
            contentAlignment = Alignment.Center
        ) {
            if (content != null) {
                content()
            } else if (image != null) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val tone = if (own) theme.outgoing_meta else theme.secondary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.labelGap))
                ) {
                    if (icon != null) KitIcon(icon, RfMetrics.fileGlyph, tone)
                    if (!status.isNullOrEmpty()) {
                        KitText(status, style = kitStyle(theme.size_mobile_caption, FontWeight.Medium, tone, RfMetrics.lineTitle))
                    }
                }
            }
            if (glyph != null && (image != null || content != null)) KitMediaGlyph(glyph)
            if (!badge.isNullOrEmpty() || !time.isNullOrEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(dp(RfMetrics.bubblePadV))
                        .background(theme.scrim, shapeOf(theme.radius_chip))
                        .padding(horizontal = dp(RfMetrics.chipPadH)),
                    horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOfNotNull(badge, time).forEach {
                        KitText(
                            it,
                            style = kitStyle(
                                theme.size_mobile_caption,
                                FontWeight.Normal,
                                theme.surface_white,
                                RfMetrics.lineTitle,
                                RfFonts.latin
                            )
                        )
                    }
                }
            }
        }
    }
}

/** A play or pause mark over a picture, on a scrim disc so it reads on any frame; it never mirrors. */
@Composable
fun KitMediaGlyph(glyph: String, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Box(
        modifier.size(dp(RfMetrics.hit)).background(theme.scrim, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        KitIcon(glyph, RfMetrics.fileGlyph, theme.surface_white)
    }
}

/**
 * An album of 2 to 16 items in one bubble (media design 4.6, board B23 to be drawn). Heights depend
 * only on [count] and the width cap: two squares side by side, one tall item beside two stacked, or a
 * two-by-two grid whose last tile shows how many more there are ([more]). The first item sits at the
 * leading edge. [tile] draws item `index` filling the modifier it is given.
 */
@Composable
fun KitAlbumGrid(
    count: Int,
    own: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp? = null,
    more: String? = null,
    time: String? = null,
    tag: String? = null,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit
) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_bubble)
    val gap = dp(RfMetrics.albumGap)
    BoxWithConstraints(modifier) {
        val cap = maxWidth ?: kitBubbleCap(this.maxWidth)
        val half = (cap - gap) / 2
        Box(Modifier.width(cap).clip(shape).background(if (own) theme.outgoing else theme.incoming).kitTag(tag)) {
            when {
                count <= 2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(count.coerceAtLeast(1)) { tile(it, Modifier.size(half)) }
                }
                count == 3 -> {
                    val height = cap * 2 / 3
                    val side = (cap - gap) / 3
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        tile(0, Modifier.width(cap - gap - side).height(height))
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            tile(1, Modifier.width(side).height((height - gap) / 2))
                            tile(2, Modifier.width(side).height((height - gap) / 2))
                        }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (column in 0 until 2) {
                                val index = row * 2 + column
                                Box(Modifier.size(half), contentAlignment = Alignment.Center) {
                                    tile(index, Modifier.size(half))
                                    if (index == ALBUM_TILES - 1 && !more.isNullOrEmpty()) AlbumMore(more)
                                }
                            }
                        }
                    }
                }
            }
            if (!time.isNullOrEmpty()) {
                KitText(
                    time,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(dp(RfMetrics.bubblePadV))
                        .background(theme.scrim, shapeOf(theme.radius_chip))
                        .padding(horizontal = dp(RfMetrics.chipPadH)),
                    style = kitStyle(theme.size_mobile_caption, FontWeight.Normal, theme.surface_white, RfMetrics.lineTitle, RfFonts.latin)
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AlbumMore(more: String) {
    val theme = LocalRFTheme.current
    Box(Modifier.matchParentSize().background(theme.scrim), contentAlignment = Alignment.Center) {
        KitText(more, style = kitStyle(theme.size_title, FontWeight.SemiBold, theme.surface_white, RfMetrics.lineTitle))
    }
}

/** Tiles an album shows before the last one counts the rest. */
const val ALBUM_TILES = 4

/**
 * Full-screen image with pinch zoom (up to five times) and panning while zoomed. The window keeps its
 * secure flag; this composable opens no separate window.
 */
@Composable
fun KitZoomableImage(image: ImageBitmap, description: String?, modifier: Modifier = Modifier, tag: String? = null) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(modifier.fillMaxSize().transformable(state).kitTag(tag), contentAlignment = Alignment.Center) {
        Image(
            image,
            description,
            Modifier.fillMaxWidth().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentScale = ContentScale.Fit
        )
    }
}

private const val MIN_RATIO = .5f
private const val MAX_RATIO = 2f
private const val MAX_ZOOM = 5f

/**
 * A two-button confirmation in a host dialog window. The dialog inherits the activity's secure flag
 * (the default secure policy), so screen capture rules hold inside it.
 */
@Composable
fun KitConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    org.ramflux.designsystem.HostConfirmationDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(theme.overlay, shapeOf(theme.radius_menu))
                .padding(dp(theme.gap))
                .kitTag(tag),
            verticalArrangement = Arrangement.spacedBy(dp(theme.gap))
        ) {
            KitText(title, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBody))
            KitText(body, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dp(theme.gap), Alignment.End)) {
                KitButton(dismiss, onDismiss, kind = KitButtonKind.Text, size = KitButtonSize.Compact)
                KitButton(confirm, onConfirm, size = KitButtonSize.Compact, tag = tag?.let { "$it-confirm" })
            }
        }
    }
}
