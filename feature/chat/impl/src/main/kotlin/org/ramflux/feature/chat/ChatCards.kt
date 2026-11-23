// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ramflux.data.CardApproval
import org.ramflux.data.CardButton
import org.ramflux.data.CardCheck
import org.ramflux.data.CardChoice
import org.ramflux.data.CardDivider
import org.ramflux.data.CardGroup
import org.ramflux.data.CardNode
import org.ramflux.data.CardText
import org.ramflux.data.CardTextInput
import org.ramflux.data.CardUnsupported
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitApprovalCard
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitCheckbox
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.designsystem.render
import org.ramflux.feature.chat.impl.R

/**
 * One agent surface in the timeline, drawn with Kit parts only. Author text is peer-controlled and goes
 * through the untrusted-text primitive. Components this client does not draw, and links, show a clear
 * placeholder and the author's fallback text; a closed or out-of-date card has no active control.
 */
@Composable
internal fun CardView(card: CardItem, cap: Dp, modifier: Modifier, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(theme.radius_card.dp)
    val enabled = card.actionable && !card.sending
    Box(modifier) {
        Column(
            Modifier
                .widthIn(max = cap)
                .border(theme.gap.dp / 16, theme.line, shape)
                .background(theme.surface_white, shape)
                .padding(theme.gap.dp)
                .testTag("card-" + card.ref),
            verticalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
        ) {
            val root = card.root
            if (root == null) {
                UntrustedText(card.fallback, kind = UntrustedKind.Excerpt)
                KitMeta(stringResource(R.string.card_plain))
            } else {
                Node(root, card, enabled, onAction)
                if (root.unsupported()) {
                    KitMeta(stringResource(R.string.card_partly_unsupported))
                    UntrustedText(card.fallback, kind = UntrustedKind.Excerpt)
                }
            }
            when {
                card.closed -> KitMeta(stringResource(R.string.card_closed))
                card.outOfSync -> KitMeta(stringResource(R.string.card_out_of_sync))
                card.sending -> KitMeta(stringResource(R.string.card_sending))
                card.sent -> KitMeta(stringResource(R.string.card_sent))
            }
            card.problem?.let { KitTipBar(it.render(), "circle-alert") }
        }
    }
}

@Composable
private fun Node(node: CardNode, card: CardItem, enabled: Boolean, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    when (node) {
        is CardText -> if (node.caption) {
            KitMeta(org.ramflux.designsystem.kit.untrustedDisplay(node.text, UntrustedKind.Excerpt))
        } else {
            UntrustedText(node.text, kind = UntrustedKind.Excerpt)
        }
        is CardGroup -> {
            val content: @Composable () -> Unit = { node.children.forEach { Node(it, card, enabled, onAction) } }
            val framed = if (node.framed) {
                Modifier.border(
                    theme.gap.dp / 16,
                    theme.line,
                    RoundedCornerShape(theme.radius_card.dp)
                ).padding(theme.gap.dp / 2)
            } else {
                Modifier
            }
            if (node.horizontal) {
                // Children share the row; a full-width button would otherwise squeeze its neighbours to nothing.
                Row(framed.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)) {
                    node.children.forEach { child -> Box(Modifier.weight(1f)) { Node(child, card, enabled, onAction) } }
                }
            } else {
                Column(framed, verticalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)) { content() }
            }
        }
        is CardButton -> if (node.link) {
            // Links need a host-owned confirmation sheet; until then the button is a placeholder.
            KitButton(
                node.label,
                {},
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                enabled = false,
                tag = "card-link-" + node.id
            )
            KitMeta(stringResource(R.string.card_link_unsupported))
        } else {
            KitButton(
                node.label,
                { onAction(ChatAction.CardPress(card.ref, node.id)) },
                Modifier.fillMaxWidth(),
                kind = if (node.primary) KitButtonKind.Primary else KitButtonKind.Secondary,
                enabled = enabled,
                loading = card.sending,
                tag = "card-button-" + node.id
            )
        }
        is CardTextInput -> {
            val value = (card.values[node.pointer] as? CardValue.Text)?.value ?: node.value
            KitTextField(
                value,
                { onAction(ChatAction.CardInput(card.ref, node.pointer, CardValue.Text(it))) },
                org.ramflux.designsystem.kit.untrustedDisplay(node.label, UntrustedKind.Excerpt),
                placeholder = node.placeholder,
                enabled = enabled,
                tag = "card-input-" + node.id
            )
        }
        is CardCheck -> {
            val checked = (card.values[node.pointer] as? CardValue.Flag)?.value ?: node.value
            KitCheckbox(
                checked,
                { onAction(ChatAction.CardInput(card.ref, node.pointer, CardValue.Flag(it))) },
                enabled = enabled,
                label = org.ramflux.designsystem.kit.untrustedDisplay(node.label, UntrustedKind.Excerpt)
            )
        }
        is CardChoice -> {
            val selected = (card.values[node.pointer] as? CardValue.Choices)?.values ?: node.selected
            if (node.label.isNotBlank()) UntrustedText(node.label, kind = UntrustedKind.Excerpt)
            node.options.forEach { (value, label) ->
                KitRow(
                    label,
                    {
                        val next = when {
                            !node.multiple -> listOf(value)
                            value in selected -> selected - value
                            else -> selected + value
                        }
                        onAction(ChatAction.CardInput(card.ref, node.pointer, CardValue.Choices(next)))
                    },
                    trailing = if (value in selected) KitRowTrailing.Check else KitRowTrailing.None,
                    enabled = enabled,
                    peer = true,
                    testTag = "card-choice-" + node.id + "-" + value
                )
            }
        }
        // An approval request is decided through its task; the card shows it without its own controls.
        is CardApproval -> KitApprovalCard(
            stringResource(R.string.card_approval_requester),
            stringResource(if (card.closed) R.string.card_closed else R.string.card_approval_pending),
            org.ramflux.designsystem.kit.untrustedDisplay(node.explanation, UntrustedKind.Excerpt),
            stringResource(R.string.card_approval_scope),
            stringResource(if (node.task) R.string.card_approval_task else R.string.card_approval_elsewhere),
            stringResource(R.string.card_approval_decline),
            stringResource(R.string.card_approval_approve),
            onDecline = {},
            onApprove = {},
            actions = false,
            tag = "card-approval-" + node.id
        )
        is CardDivider -> Box(Modifier.fillMaxWidth().height(theme.gap.dp / 16).background(theme.line))
        is CardUnsupported -> KitMeta(stringResource(R.string.card_component_unsupported), Modifier.testTag("card-unsupported-" + node.id))
    }
}

private fun CardNode.unsupported(): Boolean = when (this) {
    is CardUnsupported -> true
    is CardGroup -> children.any { it.unsupported() }
    is CardButton -> link
    else -> false
}
