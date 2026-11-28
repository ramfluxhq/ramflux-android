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

    @Test fun everyActivityListenerHearsEachCallAndAFailureStopsNone() = runBlocking {
        val heard = mutableListOf<String>()
        val stores = AccountScopedStores()
        stores.onActivity(object : AccountActivityListener {
            override suspend fun appLocked(account: String?) = error("first")
            override suspend fun appUnlocked() = error("first")
            override suspend fun accountLocked(account: String) = error("first")
            override suspend fun switched(active: String) = error("first")
        })
        val recording = object : AccountActivityListener {
            override suspend fun appLocked(account: String?) { heard += "app:$account" }
            override suspend fun appUnlocked() { heard += "open" }
            override suspend fun accountLocked(account: String) { heard += "account:$account" }
            override suspend fun switched(active: String) { heard += "switched:$active" }
        }
        stores.onActivity(recording)
        stores.onActivity(recording)
        stores.appLocked(null)
        stores.appLocked("a")
        stores.appUnlocked()
        stores.accountLocked("a")
        stores.switched("b")
        assertEquals(listOf("app:null", "app:a", "open", "account:a", "switched:b"), heard)
    }
}
