// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountScopedStoresTest {
    @Test fun everyStoreRunsOnceAndAFailureIsReported() = runBlocking {
        val purged = mutableListOf<String>()
        val stores = AccountScopedStores()
        val drafts = AccountScopedStore { purged += "drafts:$it" }
        stores.register(drafts)
        stores.register(drafts)
        stores.register { error("disk") }
        stores.register { purged += "media:$it" }
        assertFalse(stores.purgeBeforeClose("a"))
        assertEquals(listOf("drafts:a", "media:a"), purged)
        assertFalse(stores.purgeBeforeClose(""))
    }

    @Test fun noStoresIsComplete() = runBlocking {
        assertTrue(AccountScopedStores().purgeBeforeClose("a"))
    }
}
