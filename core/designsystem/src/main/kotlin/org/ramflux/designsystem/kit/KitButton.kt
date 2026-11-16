// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.generated.RFDesign

enum class KitButtonKind { Primary, Secondary, Destructive, Disabled, Text }

enum class KitButtonSize { Regular, Compact }

@Composable
fun KitButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: KitButtonKind = KitButtonKind.Primary,
    size: KitButtonSize = KitButtonSize.Regular,
    enabled: Boolean = true,
    loading: Boolean = false,
    focused: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val designedDisabled = kind == KitButtonKind.Disabled || (kind == KitButtonKind.Primary && !enabled)
    val active = enabled && kind != KitButtonKind.Disabled && !loading
    val height = if (kind == KitButtonKind.Text || size == KitButtonSize.Compact) RfMetrics.buttonCompact else RfMetrics.buttonHeight
    val weight = if (kind == KitButtonKind.Text) FontWeight.Medium else FontWeight.SemiBold
    val fill = when {
        designedDisabled -> theme.surface
        kind == KitButtonKind.Primary -> theme.primary
        kind == KitButtonKind.Destructive -> theme.danger
        else -> Color.Transparent
    }
    val labelColor = when {
        designedDisabled -> theme.control
        kind == KitButtonKind.Primary -> theme.on_primary
        kind == KitButtonKind.Destructive -> theme.on_danger
        else -> theme.text
    }
    val stroke = if (kind == KitButtonKind.Secondary && !designedDisabled) theme.text else null
    val shape = shapeOf(theme.radius_button)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val filled = kind == KitButtonKind.Primary || kind == KitButtonKind.Destructive
    val pressedAlpha = if (pressed && filled && active) RfMetrics.pressedAlpha else 1f
    val disabledAlpha = if (!enabled && !designedDisabled) RfMetrics.disabledAlpha else 1f
    val focusColor = if (fill == theme.focus) RFDesign.dark.focus else theme.focus
    val visual: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dp(height))
                .alpha(pressedAlpha * disabledAlpha)
                .background(fill, shape)
                .then(if (stroke == null) Modifier else Modifier.border(dp(RfMetrics.stroke), stroke, shape))
                .then(if (focused) Modifier.border(dp(RfMetrics.focusRing), focusColor, shape) else Modifier)
                .kitTag(if (height >= RfMetrics.hit) null else tag),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap)),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = dp(RfMetrics.buttonPadH))
            ) {
                if (loading) KitSpinner(RfMetrics.tileGlyph, labelColor, Modifier.kitTag(tag?.let { "$it-spinner" }))
                Text(label, style = kitStyle(theme.size_mobile_body, weight, labelColor, RfMetrics.lineTitle))
            }
        }
    }
    if (height >= RfMetrics.hit) {
        Box(
            modifier
                .fillMaxWidth()
                .height(dp(height))
                .semantics { contentDescription = label }
                .clickable(interaction, null, enabled = active, role = Role.Button, onClick = onClick)
                .kitTag(tag)
        ) { visual() }
    } else {
        val hit = dp(RfMetrics.hit)
        val drawn = dp(height)
        Layout(
            modifier = modifier.fillMaxWidth(),
            content = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(hit)
                        .semantics { contentDescription = label }
                        .clickable(interaction, null, enabled = active, role = Role.Button, onClick = onClick)
                        .kitTag(tag?.let { "$it-hit" }),
                    contentAlignment = Alignment.Center
                ) { visual() }
            }
        ) { measurables, constraints ->
            val hitPx = hit.roundToPx()
            val drawnPx = drawn.roundToPx()
            val placeable = measurables[0].measure(
                constraints.copy(minWidth = constraints.maxWidth, minHeight = hitPx, maxHeight = hitPx)
            )
            layout(placeable.width, drawnPx) { placeable.place(0, (drawnPx - hitPx) / 2) }
        }
    }
}
