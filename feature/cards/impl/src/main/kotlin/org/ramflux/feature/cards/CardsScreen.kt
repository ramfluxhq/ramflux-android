// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.feature.cards.impl.R
@Composable
fun CardsScreen(state: CardsState, onAction: (CardsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                KitPageHeader(
                    stringResource(R.string.cards_title),
                    onBack = { onAction(CardsAction.Back) }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.checking) {
                        item(
                            "checking"
                        ) { KitEmpty("loader", stringResource(R.string.cards_checking), "") }
                    }
                    if (state.closed) item("closed") { Text(stringResource(R.string.cards_closed)) }
                    state.unavailable?.let {
                        item(
                            "unavailable"
                        ) { KitEmpty("inbox", stringResource(R.string.cards_unavailable), it.render()) }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { KitEmpty("circle-alert", stringResource(R.string.cards_failed), it.render()) }
                    }
                }
                TextButton(
                    enabled = state.canCheck && !state.checking,
                    modifier = Modifier.fillMaxWidth().padding(theme.gap.dp),
                    onClick = { onAction(CardsAction.Refresh) }
                ) { Text(stringResource(R.string.cards_retry)) }
            }
        }
    }
}
