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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.ramflux.designsystem.LocalRFTheme

enum class KitWarningTone { Warning, Danger }

@Composable
fun KitSheet(
    title: String,
    body: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(topStart = dp(theme.radius_sheet), topEnd = dp(theme.radius_sheet))
    Column(
        modifier
            .fillMaxWidth()
            .shadow(dp(RfMetrics.shadowBlur), shape, clip = false, ambientColor = theme.shadow, spotColor = theme.shadow)
            .border(dp(RfMetrics.stroke), theme.overlay_border, shape)
            .background(theme.overlay, shape)
            .kitTag(tag)
            .padding(
                start = dp(RfMetrics.sheetPadH),
                top = dp(RfMetrics.sheetPadTop),
                end = dp(RfMetrics.sheetPadH),
                bottom = dp(RfMetrics.sheetPadBottom)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.sheetGap))
    ) {
        Box(
            Modifier.size(
                dp(RfMetrics.grabberWidth),
                dp(RfMetrics.grabberHeight)
            ).clip(shapeOf(RfMetrics.grabberRadius)).background(theme.line_strong)
        )
        Text(title, style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle))
        Text(body, style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, RfMetrics.lineBody))
        if (showClose) KitButton(closeLabel, onClose, kind = KitButtonKind.Secondary)
    }
}

@Composable
fun KitTipBar(
    text: String,
    icon: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .background(theme.surface, shapeOf(theme.radius_card))
            .kitTag(tag)
            .padding(horizontal = dp(RfMetrics.tipPadH), vertical = dp(RfMetrics.tipPadV)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.tipGap))
    ) {
        if (loading) KitSpinner(RfMetrics.tipIcon, theme.secondary) else KitIcon(icon, RfMetrics.tipIcon, theme.secondary)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.text, RfMetrics.lineSecondary)
        )
        if (!action.isNullOrEmpty() && onAction != null) {
            Text(
                action,
                modifier = Modifier.clickableNoRipple(true, onAction),
                style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.text, RfMetrics.lineSecondary)
            )
        }
    }
}

@Composable
fun KitSecurityWarning(
    title: String,
    body: String,
    tone: KitWarningTone,
    modifier: Modifier = Modifier,
    icon: String? = null,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val danger = tone == KitWarningTone.Danger
    Row(
        modifier
            .fillMaxWidth()
            .background(if (danger) theme.danger_bg else theme.warning_bg, shapeOf(theme.radius_card))
            .kitTag(tag)
            .padding(dp(RfMetrics.securityPad)),
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.securityGap))
    ) {
        KitIcon(
            icon ?: if (danger) "shield-alert" else "triangle-alert",
            RfMetrics.securityIcon,
            if (danger) theme.danger else theme.warning
        )
        Column(verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))) {
            Text(title, style = kitStyle(theme.size_body, FontWeight.SemiBold, theme.text, RfMetrics.lineBubble))
            Text(body, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.text, 1.5f))
        }
    }
}

@Composable
fun KitStatusHero(icon: String, modifier: Modifier = Modifier, tint: Color? = null, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(
        modifier.size(dp(RfMetrics.hero)).kitBackdrop().clip(shapeOf(RfMetrics.heroRadius)).background(theme.surface).kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        KitIcon(icon, RfMetrics.heroGlyph, tint ?: theme.secondary)
    }
}

@Composable
fun KitApprovalCard(
    requester: String,
    status: String,
    title: String,
    scopeLabel: String,
    scopeDetail: String,
    decline: String,
    approve: String,
    onDecline: () -> Unit,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    result: String? = null,
    actions: Boolean = true,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    Column(
        modifier
            .width(dp(RfMetrics.approval))
            .border(dp(RfMetrics.stroke), theme.line, shapeOf(theme.radius_card))
            .background(theme.surface_white, shapeOf(theme.radius_card))
            .kitTag(tag)
            .padding(dp(RfMetrics.approvalPad)),
        verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.approvalGap))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))) {
            KitAvatar("", RfMetrics.approvalAvatar, KitAvatarKind.Agent)
            Text(
                requester,
                modifier = Modifier.weight(1f),
                style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary)
            )
            Box(
                Modifier.background(
                    theme.surface,
                    shapeOf(theme.radius_chip)
                ).padding(horizontal = dp(RfMetrics.tagPadH), vertical = dp(RfMetrics.tagPadV))
            ) {
                Text(status, style = kitStyle(theme.size_meta, FontWeight.Normal, theme.text, RfMetrics.lineTitle))
            }
        }
        Text(title, style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle))
        Column(
            Modifier.fillMaxWidth().background(theme.surface, shapeOf(theme.radius_md)).padding(dp(RfMetrics.scopePad)),
            verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.summaryGap))
        ) {
            Text(scopeLabel, style = kitStyle(theme.size_secondary, FontWeight.Medium, theme.text, RfMetrics.lineSecondary))
            Text(scopeDetail, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
        }
        if (!result.isNullOrEmpty()) {
            Text(result, style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary))
        }
        if (actions) {
            Row(horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))) {
                Box(Modifier.weight(1f)) { KitButton(decline, onDecline, kind = KitButtonKind.Secondary, size = KitButtonSize.Compact) }
                Box(Modifier.weight(1f)) { KitButton(approve, onApprove, size = KitButtonSize.Compact) }
            }
        }
    }
}

@Composable
fun KitMonoBlock(value: String, onCopy: () -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .background(theme.surface, shapeOf(theme.radius_md))
            .kitTag(tag)
            .padding(
                start = dp(RfMetrics.monoPadStart),
                top = dp(RfMetrics.monoPadTop),
                end = dp(RfMetrics.monoPadEnd),
                bottom = dp(RfMetrics.monoPadBottom)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.bubbleGap))
    ) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, RfMetrics.lineBody, RfFonts.mono)
        )
        IconHit("copy", RfMetrics.iconInfo, theme.secondary, onCopy)
    }
}

@Composable
fun KitPairingCode(code: String, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    Box(
        modifier.fillMaxWidth().height(dp(RfMetrics.pairing)).background(theme.surface, shapeOf(theme.radius_md)).kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            textAlign = TextAlign.Center,
            style = kitStyle(
                RfMetrics.pairingSize,
                FontWeight.SemiBold,
                theme.text,
                RfMetrics.lineTitle,
                RfFonts.mono,
                RfMetrics.pairingTrack
            )
        )
    }
}

@Composable
fun KitQrPlaceholder(modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    val ink = theme.text
    val tile = theme.surface_white
    val stroke = theme.line_strong
    Box(
        modifier
            .size(dp(RfMetrics.qr))
            .kitBackdrop()
            .border(dp(RfMetrics.stroke), stroke, shapeOf(theme.radius_md))
            .background(tile, shapeOf(theme.radius_md))
            .kitTag(tag),
        contentAlignment = Alignment.Center
    ) {
        KitIcon("qr-code", RfMetrics.qrGlyph, ink)
    }
}
