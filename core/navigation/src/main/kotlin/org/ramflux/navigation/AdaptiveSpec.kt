// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.compose.runtime.Immutable

/** Window width classes. The decision unit is the window, never the device type. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** How the top-level destinations are offered. */
enum class NavigationForm { BOTTOM_BAR, RAIL }

/**
 * The shell's view of its window, measured from the shell's own constraints (so split screen,
 * free-form windows and fold states are all just sizes). Only the shell reads it; screens get
 * their pane size from their constraints and never query the window.
 */
@Immutable
data class AdaptiveSpec(val widthDp: Int, val heightDp: Int) {
    val widthClass: WidthClass
        get() = when {
            widthDp >= EXPANDED_MIN_WIDTH -> WidthClass.EXPANDED
            widthDp >= MEDIUM_MIN_WIDTH -> WidthClass.MEDIUM
            else -> WidthClass.COMPACT
        }

    /** List and detail side by side: always when expanded, and on medium windows from 700 dp. */
    val twoPane: Boolean get() = widthDp >= TWO_PANE_MIN_WIDTH

    val navigationForm: NavigationForm
        get() = if (widthClass == WidthClass.COMPACT) NavigationForm.BOTTOM_BAR else NavigationForm.RAIL

    /** Cover screens and short split-screen halves. */
    val shortHeight: Boolean get() = heightDp < SHORT_HEIGHT

    companion object {
        const val MEDIUM_MIN_WIDTH = 600
        const val EXPANDED_MIN_WIDTH = 840
        const val TWO_PANE_MIN_WIDTH = 700
        const val SHORT_HEIGHT = 480
    }
}
