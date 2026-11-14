// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class CardHostAvailabilityTest {
    @Test fun deferredNativeCardHostReturnsTypedUnavailableWithoutAnyContentOrActionContract() = runTest(timeout = 5.seconds) {
        assertEquals(ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.FEATURE_NOT_INSTALLED),
            UnavailableCardHostAvailabilityRepository().availability())
    }
}
