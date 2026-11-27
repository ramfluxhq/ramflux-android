// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardNode
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitText
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.designsystem.kit.kitStyle
import org.ramflux.designsystem.kit.shapeOf

/**
 * Approval (spec 4.16). The card tree carries only the request reference and the explanation. Every fact shown as verified
 * comes from the host slot the SDK stamped (`facts`); without it the card shows no decision buttons. The explanation is the
 * author's words and never appears as a fact. Allow opens the security sheet; Confirm there sends the verified intent.
 */
@Composable
internal fun CardApproval(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val key = node.key
    val slot = ctx.tree.hostSlotFor(key, "approval")
    val facts = slot?.obj("facts")
    val slotId = slot?.str("id")
    val requestId = slot?.str("request_id")
    val token = slot?.obj("token")
    val explanation = node.attrs.str("explanation").orEmpty()
    val canAnswer = ctx.live && facts != null && slotId != null && requestId != null && token != null &&
        !ctx.input.awaitingDecision && node.attrs.bool("disabled") != true
    var confirming by remember(key) { mutableStateOf(false) }
    val covered = LocalAppLockCovered.current
    LaunchedEffect(covered) { if (covered) confirming = false }
    Column(Modifier.fillMaxWidth().testTag("card-approval"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitText(
            facts?.str("action")?.let { trusted(it) } ?: stringResource(R.string.card_approval_title),
            style = kitStyle(theme.size_subheading, FontWeight.SemiBold, theme.text, 1.448f)
        )
        if (explanation.isNotEmpty()) {
            KitMeta(stringResource(R.string.card_approval_agent_says))
            UntrustedText(
                explanation,
                kind = UntrustedKind.Excerpt,
                style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, 1.448f)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(theme.surface, shapeOf(theme.radius_md))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (facts == null) {
                KitMeta(stringResource(R.string.card_approval_facts_missing))
            } else {
                ApprovalFacts(facts)
            }
        }
        if (ctx.input.awaitingDecision) {
            KitMeta(stringResource(R.string.card_status_waiting))
        } else if (canAnswer) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                KitButton(
                    label = stringResource(R.string.card_approval_deny),
                    onClick = {
                        if (slotId != null && requestId != null && token != null) {
                            ctx.actions.slot(
                                slotId,
                                requestId,
                                token,
                                CardSlotOutcome.DENY,
                                null
                            )
                        }
                    },
                    kind = KitButtonKind.Secondary
                )
                Spacer(Modifier.width(8.dp))
                KitButton(
                    label = stringResource(R.string.card_approval_allow),
                    onClick = { confirming = true },
                    kind = KitButtonKind.Primary
                )
            }
        }
    }
    if (confirming && canAnswer && facts != null && slotId != null && requestId != null && token != null) {
        ApprovalConfirm(
            facts = facts,
            onCancel = { confirming = false },
            onConfirm = {
                confirming = false
                ctx.actions.slot(slotId, requestId, token, CardSlotOutcome.CONFIRM, null)
            }
        )
    }
}

/** The verified facts: requester, action, scope rows, risk and the expiry time. All untrusted display text. */
@Composable
private fun ApprovalFacts(facts: JsonObject) {
    val theme = LocalRFTheme.current
    val requester = facts.str("requester").orEmpty()
    if (requester.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KitMeta(stringResource(R.string.card_approval_requester))
            Spacer(Modifier.width(8.dp))
            UntrustedText(
                requester,
                kind = UntrustedKind.Name,
                style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.text, 1.448f)
            )
        }
    }
    facts.arr("scope")?.mapNotNull { it as? JsonObject }?.forEach { scope ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KitMeta(trusted(scope.str("name").orEmpty()))
            Spacer(Modifier.width(8.dp))
            UntrustedText(
                scope.str("value").orEmpty(),
                kind = UntrustedKind.Excerpt,
                style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.text, 1.448f)
            )
        }
    }
    facts.str("risk")?.let { risk ->
        val tone = when (risk.lowercase()) {
            "high" -> theme.danger
            "medium" -> theme.warning
            else -> null
        }
        KitMeta(stringResource(R.string.card_approval_risk, riskLabel(risk)), tone = tone)
    }
    facts.long("expires_at")?.let { millis ->
        val locale = LocalConfiguration.current.locales[0]
        val at = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
        KitMeta(stringResource(R.string.card_approval_expires, at))
    }
}

private fun riskLabel(risk: String): String = risk.lowercase().replaceFirstChar { it.titlecase() }

/** The host security sheet: the verified facts again and a single-tap Confirm (README, no hold gesture). */
@Composable
private fun ApprovalConfirm(facts: JsonObject, onCancel: () -> Unit, onConfirm: () -> Unit) {
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(theme.radius_dialog.dp)
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(theme.overlay, shape)
                .border(1.dp, theme.overlay_border, shape)
                .padding(24.dp)
                .testTag("card-approval-confirm"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KitText(
                stringResource(R.string.card_approval_confirm_title),
                style = kitStyle(theme.size_subheading, FontWeight.SemiBold, theme.text, 1.448f)
            )
            ApprovalFacts(facts)
            KitMeta(stringResource(R.string.card_approval_confirm_note))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                KitButton(label = stringResource(R.string.card_cancel), onClick = onCancel, kind = KitButtonKind.Secondary)
                Spacer(Modifier.width(8.dp))
                KitButton(label = stringResource(R.string.card_approval_confirm), onClick = onConfirm, kind = KitButtonKind.Primary)
            }
        }
    }
}
