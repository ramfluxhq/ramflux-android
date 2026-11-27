// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardControlsTest {
    @Test fun sliderBoundsWithoutAWholeNumberAreNotSendable() {
        // min 0.2 and max 0.8 hold no whole number: the slider stays disabled instead of clamping to an empty range.
        assertNull(sliderWholeBounds(0.2, 0.8))
        assertNull(sliderWholeBounds(5.0, 3.0))
        assertNull(sliderWholeBounds(Double.NaN, 3.0))
        assertNull(sliderWholeBounds(0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun sliderBoundsAreTheWholeNumbersInside() {
        assertEquals(1L..9L, sliderWholeBounds(0.5, 9.9))
        assertEquals(0L..100L, sliderWholeBounds(0.0, 100.0))
    }

    @Test fun sliderRangeRefusesBoundsThatOverflowFloat() {
        assertNull(sliderRange(-1e39, 100.0, 5.0))
        assertNull(sliderRange(0.0, 1e39, 5.0))
        assertNull(sliderRange(0.0, 100.0, 1e39))
        assertNull(sliderRange(0.0, 100.0, Double.NaN))
        assertNull(sliderRange(10.0, 1.0, 5.0))
    }

    @Test fun sliderRangeClampsTheValueAndKeepsAZeroWidthRangeDefined() {
        assertEquals(Triple(0f, 100f, 100f), sliderRange(0.0, 100.0, 250.0))
        val flat = sliderRange(3.0, 3.0, 3.0)
        assertTrue(flat != null && flat.first < flat.second)
    }

    @Test fun anEchoOfOwnInputKeepsTheTypedText() {
        val sent = UnechoedValues()
        sent.sent("a")
        sent.sent("ab")
        sent.sent("abc")
        // The older echoes arrive while the user keeps typing: the visible "abc" is not replaced.
        assertFalse(sent.onTreeValue("a"))
        assertFalse(sent.onTreeValue("ab"))
        // The latest echo settles the list; a later value from the runtime is not an echo and replaces the text.
        assertFalse(sent.onTreeValue("abc"))
        assertTrue(sent.onTreeValue("reverted by the agent"))
    }

    @Test fun aValueThatWasNeverTypedReplacesTheText() {
        val sent = UnechoedValues()
        sent.sent("draft")
        assertTrue(sent.onTreeValue("from another device"))
        // The list was cleared by the replacement, so the old draft is no longer an echo.
        assertTrue(sent.onTreeValue("draft"))
    }

    @Test fun unansweredEchoesAreBounded() {
        val sent = UnechoedValues(limit = 2)
        sent.sent("a")
        sent.sent("b")
        sent.sent("c")
        // "a" fell out of the bounded list, so its late echo is taken as a server value and replaces the text.
        assertTrue(sent.onTreeValue("a"))
    }
}
