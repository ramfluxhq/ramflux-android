// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException

/** A host store that keeps data scoped to one account outside the SDK, such as drafts or a media cache. */
fun interface AccountScopedStore {
    suspend fun purgeAccount(account: String)
}

/**
 * The hook point every account-scoped host store registers with. [purgeBeforeClose] runs before an
 * account leaves this device (sign-out or removal), while the SDK account is still open, so a store can
 * erase what it sealed for that account. Lock is not a removal and does not purge. Every store runs even
 * when an earlier one fails; the result is false when any store failed, and the caller then keeps the
 * account open and reports the failure instead of closing over unerased data.
 */
class AccountScopedStores {
    private val stores = CopyOnWriteArrayList<AccountScopedStore>()

    fun register(store: AccountScopedStore) {
        stores.addIfAbsent(store)
    }

    suspend fun purgeBeforeClose(account: String): Boolean {
        if (account.isEmpty()) return false
        var complete = true
        for (store in stores) {
            try {
                store.purgeAccount(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                complete = false
            }
        }
        return complete
    }
}
