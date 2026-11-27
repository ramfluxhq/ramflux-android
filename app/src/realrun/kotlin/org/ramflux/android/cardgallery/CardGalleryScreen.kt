// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.ramflux.android.R
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.data.cards.CardStamp
import org.ramflux.data.cards.CardState
import org.ramflux.feature.cards.renderer.CardActions
import org.ramflux.feature.cards.renderer.CardRenderInput
import org.ramflux.feature.cards.renderer.CardRenderer

/** The gallery: every fixture id and scenario, each drawn by the card renderer from the standalone runtime. */
@Composable
internal fun CardGalleryScreen(model: CardGalleryModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("card-gallery"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(stringResource(R.string.card_gallery_title), style = MaterialTheme.typography.titleLarge) }
        if (!model.runtimeReady) {
            item { Text(stringResource(R.string.card_gallery_runtime_missing)) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = model.dark, onCheckedChange = model::applyDark, modifier = Modifier.testTag("card-gallery-dark"))
                Text(stringResource(R.string.card_gallery_dark), modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = model::runConformance,
                    enabled = model.runtimeReady && !model.running,
                    modifier = Modifier.testTag("card-gallery-run")
                ) { Text(stringResource(R.string.card_gallery_run)) }
            }
        }
        item {
            Text(
                model.summary.ifEmpty { stringResource(R.string.card_gallery_not_run) },
                modifier = Modifier.testTag("card-gallery-summary")
            )
        }
        item { Text(stringResource(R.string.card_gallery_fixtures), style = MaterialTheme.typography.titleMedium) }
        items(model.rows, key = { it.id }) { row ->
            GalleryEntry(model, id = row.id, asset = "${CardFixtureCatalog.HOST}/${row.fixture}")
        }
        item { Text(stringResource(R.string.card_gallery_scenarios), style = MaterialTheme.typography.titleMedium) }
        items(model.scenarios, key = { it.name }) { scenario ->
            GalleryEntry(model, id = "scenario/${scenario.name}", asset = scenario.envelope)
        }
    }
}

@Composable
private fun GalleryEntry(model: CardGalleryModel, id: String, asset: String) {
    LaunchedEffect(id) { model.open(id, asset) }
    val actions = remember(id) { GalleryActions(model, id) }
    val state = model.states[id] ?: CardState(id, CardLifecycle.GENERATING, "", true, null, false, null, false, false)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(id, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("card-gallery-id-$id"))
        CardRenderer(state.renderInput(), actions, cap = 640.dp)
    }
}

/** The renderer's input for one card of the gallery; the gallery holds no host request, approval or draft state. */
internal fun CardState.renderInput(): CardRenderInput = CardRenderInput(
    ref = ref,
    tree = tree,
    lifecycle = lifecycle,
    fallback = fallback,
    renderAllowed = renderAllowed,
    hostRequest = hostRequest,
    awaitingDecision = awaitingDecision,
    draftConflict = draftConflict
)

/**
 * The gallery's side of the renderer's actions. Events go to the standalone runtime. A link or approval answer has no
 * standalone path (the SDK owns slots), so it is dropped here and the sheet closes on the next state.
 */
private class GalleryActions(private val model: CardGalleryModel, private val ref: String) : CardActions {
    override fun event(key: String, event: CardEvent, seen: CardStamp) = model.event(ref, key, event)

    override fun slot(slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome, url: String?) = Unit

    override fun show() = model.show(ref)

    override fun resync() = model.show(ref)
}
