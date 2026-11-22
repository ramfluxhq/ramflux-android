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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    tag: String? = null
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
            if (image != null) {
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
