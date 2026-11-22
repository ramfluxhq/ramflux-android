// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * The role in which the shell currently shows an entry: [PaneRole.LIST] or [PaneRole.DETAIL] when list
 * and detail are side by side, null when the entry fills one pane. Entry providers read it to tell a
 * screen whether it has a back affordance (a detail beside its list has none); screens never read it.
 */
val LocalPaneRole = compositionLocalOf<PaneRole?> { null }

/** Pane geometry supplied by the shell from design tokens. Only the shell caps widths. */
data class PaneMetrics(val listPaneWidth: Dp, val maxPaneWidth: Dp)

/** The list's largest share of the width the two panes get. */
const val LIST_PANE_MAX_FRACTION = 0.45f

/**
 * Width of the list pane when the two panes share [available] (the width left after the rail and
 * window insets, never the whole window): the token width, but never more than
 * [LIST_PANE_MAX_FRACTION] of [available], so the detail is always the larger pane.
 */
fun listPaneWidth(available: Dp, preferred: Dp): Dp = min(preferred, available * LIST_PANE_MAX_FRACTION)

/** Which entries of a stack are visible, decided without Compose so it can be unit tested. */
data class PaneLayout<T>(val list: T?, val detail: T?, val single: T?, val previous: List<T>) {
    val twoPane: Boolean get() = list != null
}

/**
 * Two-pane rule: at two-pane width a LIST entry on top shows with an empty detail, and a DETAIL entry
 * directly above a LIST entry shows beside it. Every other case is one full pane. Back always pops
 * the top entry, so in two panes it clears the selection instead of leaving the list.
 */
fun <T> paneLayout(entries: List<T>, twoPane: Boolean, role: (T) -> PaneRole?): PaneLayout<T> {
    val top = entries.last()
    val previous = entries.dropLast(1)
    if (twoPane) {
        val below = previous.lastOrNull()
        if (role(top) == PaneRole.LIST) return PaneLayout(top, null, null, previous)
        if (role(top) == PaneRole.DETAIL && below != null && role(below) == PaneRole.LIST) {
            return PaneLayout(below, top, null, previous)
        }
    }
    return PaneLayout(null, null, top, previous)
}

/**
 * The in-house list-detail scene strategy (no alpha adaptive library). It keeps the metadata shape
 * of a list-detail strategy, so a later swap to a stable library strategy is mechanical.
 */
class PaneSceneStrategy<T : Any>(
    private val twoPane: Boolean,
    private val metrics: PaneMetrics,
    private val separator: @Composable () -> Unit,
    private val emptyDetail: @Composable () -> Unit
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> {
        val layout = paneLayout(entries, twoPane) { it.paneRole() }
        val list = layout.list
        return if (list != null) {
            TwoPaneScene(list, layout.detail, layout.previous, metrics, separator, emptyDetail)
        } else {
            SinglePane(requireNotNull(layout.single), layout.previous, metrics)
        }
    }
}

private class SinglePane<T : Any>(
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    private val metrics: PaneMetrics
) : Scene<T> {
    override val key: Any = listOf("single", entry.contentKey)
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().testTag("pane-single"), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = metrics.maxPaneWidth).fillMaxSize()) { entry.Content() }
        }
    }
}

private class TwoPaneScene<T : Any>(
    private val list: NavEntry<T>,
    private val detail: NavEntry<T>?,
    override val previousEntries: List<NavEntry<T>>,
    private val metrics: PaneMetrics,
    private val separator: @Composable () -> Unit,
    private val emptyDetail: @Composable () -> Unit
) : Scene<T> {
    // Keyed by the list so selecting another detail does not animate the list pane.
    override val key: Any = listOf("two-pane", list.contentKey)
    override val entries: List<NavEntry<T>> = listOfNotNull(list, detail)
    override val content: @Composable () -> Unit = {
        // Measured from the scene's own constraints: the rail and insets are already outside them.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            TwoPaneRow(listPaneWidth(maxWidth, metrics.listPaneWidth))
        }
    }

    @Composable
    private fun TwoPaneRow(listWidth: Dp) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight().testTag("pane-list")) {
                CompositionLocalProvider(LocalPaneRole provides PaneRole.LIST) { list.Content() }
            }
            separator()
            Box(
                Modifier.weight(1f).fillMaxHeight().testTag("pane-detail"),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(Modifier.widthIn(max = metrics.maxPaneWidth).fillMaxSize()) {
                    if (detail != null) {
                        CompositionLocalProvider(LocalPaneRole provides PaneRole.DETAIL) { detail.Content() }
                    } else {
                        emptyDetail()
                    }
                }
            }
        }
    }
}
