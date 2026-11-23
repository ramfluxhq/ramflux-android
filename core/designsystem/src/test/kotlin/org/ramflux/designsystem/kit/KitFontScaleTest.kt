// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** At an enlarged font scale stacked Kit texts never overlap and a header title stays inside its header. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class KitFontScaleTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(text: String): Rect = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun content(scale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                RamfluxTheme(dark = false) {
                    Column(Modifier.width(393.dp)) {
                        KitPageHeader("Short title", onBack = {}, tag = "first")
                        KitPageHeader(LONG_TITLE, onBack = {}, actionLabel = "Refresh", onAction = {}, tag = "second")
                        KitRow(LABEL, {}, icon = "key", value = VALUE)
                        KitSummaryCard(LABEL, VALUE)
                    }
                }
            }
        }
    }

    @Test fun atFontScaleTwoRowLabelAndValueDoNotOverlap() {
        content(2f)
        val labels = compose.onAllNodesWithTextBounds(LABEL)
        val values = compose.onAllNodesWithTextBounds(VALUE)
        labels.zip(values).forEach { (label, value) ->
            assertTrue("value $value starts inside label $label", value.top >= label.bottom)
        }
    }

    @Test fun atFontScaleTwoAWrappedHeaderTitleStaysInsideItsHeader() {
        content(2f)
        val first = compose.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("second").fetchSemanticsNode().boundsInRoot
        val title = bounds(LONG_TITLE)
        assertTrue("title $title overhangs the header above ($first)", title.top >= first.bottom)
        assertTrue("title $title overhangs its header ($second)", title.bottom <= second.bottom)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextBounds(text: String): List<Rect> =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private companion object {
        const val LONG_TITLE = "Backup and restore of every account on this phone"
        const val LABEL = "Unlock with account code"
        const val VALUE = "Enter the six-digit account code you set when you created the account"
    }
}
