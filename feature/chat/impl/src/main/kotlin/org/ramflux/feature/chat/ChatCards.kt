// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.feature.cards.renderer.CardActions
import org.ramflux.feature.cards.renderer.CardRenderInput
import org.ramflux.feature.cards.renderer.CardRenderer

/** One agent surface in the timeline: the card renderer draws the runtime's state; every action goes back to the view model. */
@Composable
internal fun CardView(card: CardItem, cap: Dp, modifier: Modifier, onAction: (ChatAction) -> Unit) {
    val actions = remember(card.ref, onAction) { ChatCardActions(card.ref, onAction) }
    val state = card.state
    val input = CardRenderInput(
        ref = card.ref,
        tree = state.tree,
        lifecycle = state.lifecycle,
        fallback = state.fallback,
        renderAllowed = state.renderAllowed,
        hostRequest = state.hostRequest,
        awaitingDecision = state.awaitingDecision,
        draftConflict = state.draftConflict,
        staleKey = card.staleKey,
        staleSeq = card.staleSeq
    )
    CardRenderer(input, actions, cap, modifier)
}

/** The chat's side of one card: each call becomes one [ChatAction] for the card's ref. */
private class ChatCardActions(private val ref: String, private val onAction: (ChatAction) -> Unit) : CardActions {
    override fun event(key: String, event: CardEvent) = onAction(ChatAction.CardInteract(ref, key, event))

    override fun slot(slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome, url: String?) =
        onAction(ChatAction.CardAnswer(ref, slotId, requestId, token, outcome, url))

    override fun show() = onAction(ChatAction.CardShow(ref))

    override fun resync() = onAction(ChatAction.CardResync(ref))
}
