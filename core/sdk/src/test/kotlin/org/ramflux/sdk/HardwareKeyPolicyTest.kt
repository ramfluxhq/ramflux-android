// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.vault.HardwareKeyPolicy

class HardwareKeyPolicyTest {
    @Test fun onlyExplicitHardwareLevelsAreAccepted() {
        assertTrue(HardwareKeyPolicy.accepts(1))
        assertTrue(HardwareKeyPolicy.accepts(2))
        listOf(-1, 0, 3, 4, Int.MAX_VALUE).forEach {
            assertFalse("unverified level must fail closed", HardwareKeyPolicy.accepts(it))
        }
    }
}
