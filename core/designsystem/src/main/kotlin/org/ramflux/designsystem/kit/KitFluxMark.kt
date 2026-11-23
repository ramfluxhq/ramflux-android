// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.ramflux.designsystem.LocalRFTheme

/**
 * The flux line under the Ramflux wordmark on the splash, get-started and welcome boards (MOB-S01, S02, S18): a
 * dot, then a wave that ends in a loop. Decorative only (no semantics); drawn left to right in every layout
 * direction, as the wordmark is. The path is traced from the board exports at their 153 x 25 dp size; the
 * design file's vector would replace it (requested from design).
 */
@Composable
fun KitFluxMark(modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Canvas(modifier.size(dp(FLUX_WIDTH), dp(FLUX_HEIGHT)).clearAndSetSemantics {}) {
        val unit = size.width / FLUX_WIDTH
        scale(unit, unit, Offset.Zero) {
            drawCircle(theme.line_strong, FLUX_DOT, Offset(FLUX_DOT, FLUX_DOT_Y))
            drawPath(FLUX_PATH, theme.text, style = Stroke(width = RfMetrics.controlStroke, cap = StrokeCap.Round))
        }
    }
}

private const val FLUX_WIDTH = 153f
private const val FLUX_HEIGHT = 25f
private const val FLUX_DOT = 5f
private const val FLUX_DOT_Y = 16f

/** In design units (dp at the board size): from the dot over a crest and a trough to the closing loop. */
private val FLUX_PATH = Path().apply {
    moveTo(7.8f, 20.2f)
    cubicTo(27f, 16f, 37f, 1.8f, 52.8f, 1.8f)
    cubicTo(70f, 1.8f, 80f, 23.5f, 97.8f, 23.5f)
    cubicTo(115f, 23.5f, 125f, 4.7f, 138.7f, 4.7f)
    cubicTo(147f, 4.7f, 151.5f, 8.5f, 151.5f, 13.5f)
    cubicTo(151.5f, 18.5f, 148.5f, 21.5f, 145.5f, 21.5f)
    cubicTo(142.5f, 21.5f, 140.5f, 19.5f, 140.5f, 16.5f)
    cubicTo(140.5f, 13.5f, 143f, 11f, 146f, 11f)
    cubicTo(149f, 11f, 151.5f, 12.5f, 151.5f, 13.5f)
}
