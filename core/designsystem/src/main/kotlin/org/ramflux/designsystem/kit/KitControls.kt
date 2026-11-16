// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme

@Composable
fun KitSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "",
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    HitBox(tag, enabled, { onChecked(!checked) }, modifier.alpha(if (enabled) 1f else RfMetrics.disabledAlpha), Role.Switch, label) {
        Box(
            Modifier
                .size(dp(RfMetrics.switchWidth), dp(RfMetrics.switchHeight))
                .clip(CircleShape)
                .background(if (checked) theme.primary else theme.line_strong)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(dp(RfMetrics.switchInset))
                    .offset(x = dp(if (checked) RfMetrics.switchWidth - RfMetrics.switchKnob - RfMetrics.switchInset * 2 else 0f))
                    .size(dp(RfMetrics.switchKnob))
                    .clip(CircleShape)
                    .background(if (checked) theme.on_primary else theme.surface_white)
            )
        }
    }
}

@Composable
fun KitCheckbox(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Float = RfMetrics.checkbox,
    radius: Float? = null,
    strokeColor: Color? = null,
    label: String = "",
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val corner = radius ?: theme.radius_sm
    val shape = shapeOf(corner)
    HitBox(tag, enabled, { onChecked(!checked) }, modifier.alpha(if (enabled) 1f else RfMetrics.disabledAlpha), Role.Checkbox, label) {
        Box(
            Modifier
                .size(dp(size))
                .then(
                    if (checked) {
                        Modifier.background(theme.primary, shape)
                    } else {
                        Modifier.border(dp(RfMetrics.controlStroke), strokeColor ?: theme.control, shape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) KitIcon("check", RfMetrics.check * size / RfMetrics.checkbox, theme.on_primary)
        }
    }
}

@Composable
fun KitSegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.segmented))
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .background(theme.surface, shapeOf(theme.radius_md))
            .kitTag(tag)
            .padding(dp(RfMetrics.segmentedPad)),
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.segmentedPad))
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (on) Modifier.background(theme.surface_white, shapeOf(theme.radius_sm)) else Modifier)
                    .clickableNoRipple(enabled, { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = kitStyle(
                        theme.size_secondary,
                        if (on) FontWeight.SemiBold else FontWeight.Normal,
                        if (on) theme.text else theme.secondary,
                        RfMetrics.lineSecondary
                    )
                )
            }
        }
    }
}

@Composable
internal fun Modifier.clickableNoRipple(enabled: Boolean, onClick: () -> Unit, role: Role = Role.Button): Modifier =
    clickableNoRipple(remember { MutableInteractionSource() }, enabled, onClick, role)

@Composable
internal fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
    role: Role = Role.Button
): Modifier = this.clickable(interaction, null, enabled = enabled, role = role, onClick = onClick)
