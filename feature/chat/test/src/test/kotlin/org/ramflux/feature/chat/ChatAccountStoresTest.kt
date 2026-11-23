// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.data.AccountScopedStores

class ChatAccountStoresTest {
    @Test
    fun drafts_and_media_are_erased_before_an_account_leaves() = runTest {
        val drafts = FakeChatDrafts().apply {
            stored["a/c1"] = "draft"
            stored["b/c2"] = "kept"
        }
        val media = FakeChatMedia()
        val stores = AccountScopedStores()
        stores.register(ChatDataModule.draftStore(drafts))
        stores.register(ChatDataModule.mediaStore(media))

        assertTrue(stores.purgeBeforeClose("a"))

        assertEquals(setOf("b/c2"), drafts.stored.keys)
        assertEquals(listOf("a"), media.purgedAccounts)
    }
}
