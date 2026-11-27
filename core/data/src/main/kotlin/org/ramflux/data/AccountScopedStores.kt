// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A host store that keeps data scoped to one account outside the SDK, such as drafts or a media cache. */
fun interface AccountScopedStore {
    suspend fun purgeAccount(account: String)
}

/**
 * The hook point every account-scoped host store registers with. [purgeBeforeClose] runs before an
 * account leaves this device (sign-out or removal), while the SDK account is still open, so a store can
 * erase what it sealed for that account. Lock is not a removal and does not purge. Every store runs even
 * when an earlier one fails; the result is false when any store failed, and the caller then keeps the
 * account open and reports the failure instead of closing over unerased data. The purge runs without cancellation: a
 * caller that goes away mid-purge would otherwise leave some stores erased (cards shut) and the account still open.
 */
class AccountScopedStores {
    private val stores = CopyOnWriteArrayList<AccountScopedStore>()
    private val admissions = CopyOnWriteArrayList<(String) -> Unit>()

    /** [listener] runs when an account is admitted again on purpose (unlock or switch), or when its sign-out did not complete. */
    fun onAdmit(listener: (String) -> Unit) {
        admissions.addIfAbsent(listener)
    }

    fun admit(account: String) {
        for (listener in admissions) listener(account)
    }

    fun register(store: AccountScopedStore) {
        stores.addIfAbsent(store)
    }

    suspend fun purgeBeforeClose(account: String): Boolean {
        if (account.isEmpty()) return false
        return withContext(NonCancellable) {
            var complete = true
            for (store in stores) {
                try {
                    store.purgeAccount(account)
                } catch (_: Exception) {
                    // A store's own cancellation is a failed purge too; the caller is not cancelled here.
                    complete = false
                }
            }
            complete
        }
    }
}
