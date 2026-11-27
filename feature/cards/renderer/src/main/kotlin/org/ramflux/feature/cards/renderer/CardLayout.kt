// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardNode
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAiMarker
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitSegmented
import org.ramflux.designsystem.kit.KitText
import org.ramflux.designsystem.kit.kitStyle
import org.ramflux.designsystem.kit.shapeOf

/** Row: children in order, wrapping at compact width (spec 4.3). `justify` maps to the main-axis alignment. */
@Composable
internal fun CardRow(node: CardNode, ctx: CardContext) {
    val space = when (node.attrs.str("justify")) {
        "center" -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(8.dp, Alignment.End)
        else -> Arrangement.spacedBy(8.dp)
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = space, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.children.forEach { RenderNode(it, ctx) }
    }
}

/** Column: children stacked with 12 between blocks (spec 2.3, 4.3). `stretch` fills the width. */
@Composable
internal fun CardColumn(node: CardNode, ctx: CardContext) {
    val align = when (node.attrs.str("align")) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = align) {
        node.children.forEach { child -> Box(Modifier.fillMaxWidth()) { RenderNode(child, ctx) } }
    }
}

/** List: vertical rows with rules and "Show all"; horizontal is a carousel (spec 4.4). */
@Composable
internal fun CardList(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    if (node.children.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            KitMeta(stringResource(R.string.card_nothing_to_show), centered = true)
        }
        return
    }
    if (node.attrs.str("direction") == "horizontal") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(node.children, key = { it }) { child ->
                Box(Modifier.width(200.dp)) { RenderNode(child, ctx) }
            }
        }
        return
    }
    var expanded by rememberSaveable(node.key) { mutableStateOf(false) }
    val shown = if (expanded) node.children else node.children.take(COLLAPSED_ROWS)
    Column(Modifier.fillMaxWidth()) {
        shown.forEachIndexed { index, child ->
            if (index > 0) HorizontalDivider(color = theme.line_soft)
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { RenderNode(child, ctx) }
        }
        if (!expanded && node.children.size > COLLAPSED_ROWS) {
            KitButton(
                label = stringResource(R.string.card_show_all, node.children.size),
                onClick = { expanded = true },
                kind = KitButtonKind.Text
            )
        }
    }
}

/** A nested Card is an inset block: `surface`, radius 8, padding 12, no border (spec 4.5). */
@Composable
internal fun CardInset(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.surface, shapeOf(theme.radius_md))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        node.children.forEach { RenderNode(it, ctx) }
    }
}

/** Tabs: host-owned selection (spec 4.6); the selected index is sent as `select_tab` and kept by key. */
@Composable
internal fun CardTabs(node: CardNode, ctx: CardContext) {
    val titles = node.attrs.arr("tabs")?.map { trusted((it as? JsonObject)?.str("title").orEmpty()) }.orEmpty()
    val panels = node.children
    if (panels.isEmpty()) return
    var selected by rememberSaveable(node.key) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, panels.lastIndex)
    if (titles.size in 1..4) {
        KitSegmented(
            labels = titles.take(panels.size),
            selected = index,
            onSelect = { i ->
                selected = i
                if (ctx.navigable) ctx.event(node.key, CardEvent.SelectTab(i))
            },
            enabled = ctx.navigable
        )
    } else {
        PrimaryTabRow(selectedTabIndex = index) {
            titles.take(panels.size).forEachIndexed { i, title ->
                Tab(
                    selected = i == index,
                    onClick = {
                        selected = i
                        if (ctx.navigable) ctx.event(node.key, CardEvent.SelectTab(i))
                    },
                    text = {
                        KitText(
                            title,
                            style = kitStyle(LocalRFTheme.current.size_secondary, FontWeight.Medium, LocalRFTheme.current.text, 1.448f)
                        )
                    }
                )
            }
        }
    }
    RenderNode(panels[index], ctx)
}

/**
 * Modal: child 0 is the trigger (drawn inert, the whole trigger opens the sheet), child 1 the content. One sheet at a time;
 * the link sheet of the card wins (the renderer draws it above). Opening and closing send `modal_open` / `modal_close`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardModal(node: CardNode, ctx: CardContext) {
    val trigger = node.children.getOrNull(0) ?: return
    val content = node.children.getOrNull(1)
    var open by rememberSaveable(node.key) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = stringResource(R.string.card_modal_open)
    val covered = LocalAppLockCovered.current
    // A suspended card or the app lock cover closes the sheet: no card window may stay above the cover.
    val closeNow = covered || !ctx.navigable
    LaunchedEffect(closeNow) {
        if (closeNow && open) {
            open = false
            if (ctx.navigable) ctx.event(node.key, CardEvent.ModalClose)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = ctx.navigable, role = Role.Button, onClickLabel = label) {
                if (!open) {
                    open = true
                    ctx.event(node.key, CardEvent.ModalOpen)
                }
            }
    ) {
        RenderNode(trigger, ctx.inert())
    }
    if (open && !closeNow && content != null && ctx.input.hostRequest?.str("kind") != "open_url") {
        val close = {
            if (open) {
                open = false
                ctx.event(node.key, CardEvent.ModalClose)
            }
        }
        val theme = LocalRFTheme.current
        // Plain card surface, not the host's security-sheet look, and an agent header inside: the author's content cannot
        // pass for an approval sheet or a system prompt.
        ModalBottomSheet(
            onDismissRequest = close,
            sheetState = sheet,
            containerColor = theme.surface_white,
            modifier = Modifier.testTag("card-modal-sheet")
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().testTag("card-modal-header"), verticalAlignment = Alignment.CenterVertically) {
                    KitAiMarker()
                    Spacer(Modifier.width(8.dp))
                    KitMeta(stringResource(R.string.card_modal_from_agent))
                }
                RenderNode(content, ctx)
                KitButton(label = stringResource(R.string.card_modal_close), onClick = close, kind = KitButtonKind.Secondary)
            }
        }
    }
}

/** Divider: a 1 px rule in `line` (spec 4.8). Decorative for accessibility. */
@Composable
internal fun CardDivider(node: CardNode) {
    val theme = LocalRFTheme.current
    val vertical = node.attrs.str("axis") == "vertical"
    if (vertical) {
        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 0.dp), color = theme.line)
    } else {
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = theme.line)
    }
}

private const val COLLAPSED_ROWS = 5
