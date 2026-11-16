// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal fun kitStyle(
    size: Float,
    weight: FontWeight,
    color: Color,
    line: Float = RfMetrics.lineBody,
    family: FontFamily = RfFonts.ui,
    tracking: Float = 0f
): TextStyle = TextStyle(
    color = color,
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * line).sp,
    letterSpacing = tracking.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
)

internal fun dp(value: Float): Dp = value.dp

internal fun agentRadius(size: Float): Float = (size * RfMetrics.agentRadiusRatio).roundToInt().toFloat()

internal fun personInitial(size: Float): Float = when (size) {
    RfMetrics.avatar18 -> RfMetrics.initial8
    RfMetrics.avatar28 -> RfMetrics.initial12
    RfMetrics.avatar36 -> RfMetrics.initial15
    RfMetrics.avatar44 -> RfMetrics.initial17
    RfMetrics.avatar52 -> RfMetrics.initial22
    RfMetrics.avatar72 -> RfMetrics.initial28
    else -> (size * RfMetrics.agentRadiusRatio).roundToInt().coerceAtLeast(1).toFloat()
}

internal fun agentGlyph(size: Float): Float = when (size) {
    RfMetrics.avatar18 -> RfMetrics.glyph10
    RfMetrics.avatar28 -> RfMetrics.glyph16
    RfMetrics.avatar36 -> RfMetrics.glyph21
    RfMetrics.avatar44 -> RfMetrics.glyph24
    RfMetrics.avatar52 -> RfMetrics.glyph28
    RfMetrics.avatar72 -> RfMetrics.glyph40
    RfMetrics.approvalAvatar -> RfMetrics.glyph14
    else -> (size * RfMetrics.lineTight).roundToInt().toFloat()
}

@Composable
internal fun KitIcon(name: String, size: Float, tint: Color, modifier: Modifier = Modifier, description: String? = null) {
    val id = KitIcons.ids[name]
    if (id == null) {
        Box(modifier.size(dp(size)))
        return
    }
    Icon(painterResource(id), description, modifier.size(dp(size)), tint)
}

@Composable
internal fun KitSpinner(size: Float, tint: Color, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "kit-spin").animateFloat(
        initialValue = 0f,
        targetValue = RfMetrics.fullTurn,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing)),
        label = "kit-angle"
    )
    KitIcon("loader-circle", size, tint, modifier.rotate(angle))
}

/** Static launch glyph. An infinite spin never goes idle under a synchronizing activity test. */
@Composable
fun KitLoaderMark(size: Float, tint: Color, modifier: Modifier = Modifier) {
    KitIcon("loader-circle", size, tint, modifier)
}

internal fun Modifier.kitHairline(color: Color, top: Boolean = false): Modifier = drawBehind {
    val stroke = RfMetrics.hairline.dp.toPx()
    val y = if (top) stroke / 2f else size.height - stroke / 2f
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), stroke)
}

@Composable
internal fun HitBox(
    tag: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    label: String = "",
    content: @Composable BoxScope.() -> Unit
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides dp(RfMetrics.hit)) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .semantics {
                    this.role = role
                    if (label.isNotEmpty()) contentDescription = label
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = role,
                    onClick = onClick
                )
                .kitTag(tag?.let { "$it-hit" }),
            contentAlignment = Alignment.Center
        ) {
            Box(if (tag == null) Modifier else Modifier.testTag(tag), content = content)
        }
    }
}

internal fun Modifier.kitTag(tag: String?): Modifier = if (tag == null) this else testTag(tag)

@Composable
internal fun ChipSurface(
    fill: Color,
    radius: Float,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    borderWidth: Float = RfMetrics.stroke,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(dp(radius))
    Box(
        modifier.background(fill, shape).then(
            if (borderColor == null) Modifier else Modifier.border(dp(borderWidth), borderColor, shape)
        ),
        content = content
    )
}

internal fun shapeOf(radius: Float): Shape = androidx.compose.foundation.shape.RoundedCornerShape(dp(radius))
