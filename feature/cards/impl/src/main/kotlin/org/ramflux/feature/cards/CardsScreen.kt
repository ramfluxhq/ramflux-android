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
import org.ramflux.feature.cards.impl.R
import org.ramflux.generated.*
@Composable
fun CardsScreen(state: CardsState, onAction: (CardsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(R.string.cards_title),
                    "",
                    theme,
                    leading = { TextButton(onClick = { onAction(CardsAction.Back) }) { Text(stringResource(R.string.cards_back)) } },
                    trailing = {}
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.checking) {
                        item(
                            "checking"
                        ) { RFStateBlock(stringResource(R.string.cards_checking), "", RFStateKind.Loading, theme, {}) }
                    }
                    if (state.closed) item("closed") { Text(stringResource(R.string.cards_closed)) }
                    state.unavailable?.let {
                        item(
                            "unavailable"
                        ) { RFStateBlock(stringResource(R.string.cards_unavailable), it.render(), RFStateKind.Empty, theme, {}) }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { RFStateBlock(stringResource(R.string.cards_failed), it.render(), RFStateKind.Error, theme, {}) }
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
