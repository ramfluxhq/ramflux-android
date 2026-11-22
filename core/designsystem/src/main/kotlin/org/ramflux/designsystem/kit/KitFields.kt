// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.designsystem.LocalRFTheme

@Composable
fun KitTextField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    focused: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val interaction = remember { MutableInteractionSource() }
    val editing by interaction.collectIsFocusedAsState()
    val activeFocus = focused || editing
    val border = when {
        error != null -> theme.danger
        activeFocus -> theme.focus
        else -> theme.input_border
    }
    val borderWidth = if (activeFocus) RfMetrics.focusRing else RfMetrics.stroke
    Column(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else RfMetrics.fieldDisabledAlpha).kitBackdrop().kitTag(tag),
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.fieldGap))
    ) {
        KitText(label, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            textStyle = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.text, RfMetrics.lineTitle),
            cursorBrush = SolidColor(theme.focus),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(dp(RfMetrics.fieldBox))
                .background(theme.input, shapeOf(theme.radius_input))
                .border(dp(borderWidth), border, shapeOf(theme.radius_input))
                .padding(horizontal = dp(RfMetrics.fieldPad)),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            KitText(
                                placeholder,
                                style = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle)
                            )
                        }
                        inner()
                    }
                    if (error != null) KitIcon("circle-alert", RfMetrics.fieldIcon, theme.danger)
                }
            }
        )
        if (error != null) {
            KitText(error, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.danger, RfMetrics.lineSecondary))
        } else if (helper != null) {
            KitText(helper, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
        }
    }
}

@Composable
fun KitSearchField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focused: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val shape = shapeOf(theme.radius_input)
    Row(
        modifier
            .fillMaxWidth()
            .height(dp(RfMetrics.search))
            .alpha(if (enabled) 1f else RfMetrics.disabledAlpha)
            .background(theme.surface, shape)
            .then(if (focused) Modifier.border(dp(RfMetrics.focusRing), theme.focus, shape) else Modifier)
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.searchPad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))
    ) {
        KitIcon("search", RfMetrics.searchIcon, theme.secondary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            textStyle = kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.secondary, RfMetrics.lineTitle),
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
        if (value.isNotEmpty()) {
            HitOverlap(
                { onValue("") },
                slotWidth = RfMetrics.searchIcon,
                slotHeight = RfMetrics.searchIcon,
                enabled = enabled,
                label = "clear",
                tag = if (tag == null) null else tag + "-clear"
            ) {
                Box(
                    Modifier.size(dp(RfMetrics.searchIcon)).kitTag(if (tag == null) null else tag + "-clear-icon"),
                    contentAlignment = Alignment.Center
                ) {
                    KitIcon("circle-x", RfMetrics.searchIcon, theme.secondary)
                }
            }
        }
    }
}
