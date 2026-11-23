// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.ramflux.designsystem.LocalRFTheme

/** Measurements of the loading rows on MOB-B01L (avatar, title bar, preview bar). */
private object SkeletonMetrics {
    const val TITLE_HEIGHT = 14f
    const val PREVIEW_HEIGHT = 12f
    const val TITLE_WIDTH = 120f
    const val PREVIEW_WIDTH = 200f
    const val WIDTH_STEP = 12f
    const val RADIUS = 6f
    const val GAP = 10f
}

/**
 * One placeholder conversation row while the first page loads (MOB-B01L). [index] varies the bar
 * widths as the board does. Decorative: screen readers skip it; the list announces loading itself.
 */
@Composable
fun KitSkeletonRow(index: Int, modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalRFTheme.current
    val step = (index % 5) * SkeletonMetrics.WIDTH_STEP
    val bar = shapeOf(SkeletonMetrics.RADIUS)
    Row(
        modifier
            .kitTag(tag)
            .fillMaxWidth()
            .heightIn(min = dp(RfMetrics.conversation))
            .padding(horizontal = dp(RfMetrics.gutter))
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.avatarGap))
    ) {
        Box(Modifier.size(dp(RfMetrics.avatar52)).background(theme.surface, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(dp(SkeletonMetrics.GAP))) {
            Box(
                Modifier.width(
                    dp(SkeletonMetrics.TITLE_WIDTH + step)
                ).heightIn(min = dp(SkeletonMetrics.TITLE_HEIGHT)).background(theme.surface, bar)
            )
            Box(
                Modifier.width(
                    dp(SkeletonMetrics.PREVIEW_WIDTH - step)
                ).heightIn(min = dp(SkeletonMetrics.PREVIEW_HEIGHT)).background(theme.surface, bar)
            )
        }
    }
}
