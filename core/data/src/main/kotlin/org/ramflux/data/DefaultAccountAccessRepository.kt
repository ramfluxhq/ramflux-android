// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.*

internal class DefaultAccountAccessRepository(
    transport: RepositoryTransport,
    private val stores: AccountScopedStores = AccountScopedStores()
) : AccountAccessRepository {
    private val accounts = DefaultAccountsRepository(transport)
    private val revisionCount = AtomicInteger()
    private val publishedRevisions = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    override val revisions: Flow<Int> = publishedRevisions

    /**
     * One gate per account id: sign-in, sign-out, removal and lock of one account run one at a time, so the purge and
     * re-admission of one operation never interleave with another's (a second sign-out cannot undo the first).
     */
    private val gates = HashMap<String, Mutex>()

    private suspend fun <T> serial(id: String, body: suspend () -> T): T = synchronized(gates) { gates.getOrPut(id) { Mutex() } }.withLock { body() }
    /** Display names [list] has read in this process, by account id (see [knownLabel]). Memory only, never logged. */
    private val labels = ConcurrentHashMap<String, String>()

    /** Every account on this device, locked ones included, with its display name (`account.list`). */
    override suspend fun list(): ClientResult<AccountCatalog> = when (val result = accounts.accountList(RepositoryContext(null), JsonObject(emptyMap()))) {
        is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
        is CoreResult.Success -> when (val decoded = AccountListMetadataReader.read(result.value)) {
            is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
            is CoreResult.Success -> {
                val rows = decoded.value.accounts
                remember(rows)
                ClientResult.Ready(AccountCatalog(rows.map {
                    AccountSummary(it.id, it.label, it.state == "unlocked", it.state)
                }, decoded.value.activeAccountId))
            }
        }
    }

    override fun knownLabel(id: String): String? = labels[id]

    /** Removal notices waiting for the app window; a burst keeps the newest few. Memory only, never logged. */
    private val removals = Channel<String?>(REMOVAL_NOTICES, BufferOverflow.DROP_OLDEST)
    override val removedNotices: Flow<String?> = removals.receiveAsFlow()

    override fun announceRemoved(name: String?) {
        removals.trySend(name)
    }

    /** A row with a name renews it; a row without one (an app-locked account) keeps it; an account no longer listed drops it. */
    private fun remember(rows: List<AccountListEntry>) {
        rows.forEach { row -> row.label?.let { labels[row.id] = it } }
        labels.keys.retainAll(rows.mapTo(HashSet()) { it.id })
    }
    override suspend fun select(id: String): ClientResult<Unit> = serial(id) {
        // Authoritative state is checked for every selection, not trusted from a previous screen, and under the account's
        // gate, so a sign-out that completes between the check and the switch cannot be undone by the switch.
        val catalog = when (val current = list()) {
            is ClientResult.Ready -> current.value
            is ClientResult.Failed -> return@serial current
            is ClientResult.Unavailable -> return@serial current
        }
        if (catalog.accounts.none { it.id == id && it.unlocked }) return@serial unavailable()
        switch(id)
    }
    override suspend fun unlock(id: String, code: CharArray): ClientResult<Unit> {
        if (!acceptable(id) || !AccountCode.complete(code)) {
            code.fill('\u0000')
            return if (acceptable(id)) codeRefused() else invalid()
        }
        return serial(id) {
            // The SDK field keeps its historical name; its value is the six-digit account code.
            val unlocked = accounts.accountUnlock(
                RepositoryContext(id, UUID.randomUUID().toString()),
                SecretBody.fields { chars("passphrase", code) }
            )
            when (unlocked) {
                is CoreResult.Failure -> ClientResult.Failed(unlocked.appProblem())
                is CoreResult.Success -> {
                    unlocked.value.close()
                    switch(id)
                }
            }
        }
    }
    override suspend fun signOut(id: String): ClientResult<Unit> {
        if (!acceptable(id)) return invalid()
        return leave(id) { accounts.accountSign_out(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {}) }
    }
    override suspend fun removeLocal(id: String): ClientResult<Unit> {
        if (!acceptable(id)) return invalid()
        return leave(id) {
            // The SDK requires the account id itself as the typed confirmation.
            val body = SecretBody.fields { publicText("confirmation", id) }
            accounts.accountRemove_local(RepositoryContext(id, UUID.randomUUID().toString()), body)
        }.also { if (it is ClientResult.Ready) labels.remove(id) }
    }

    /**
     * Sign-out or removal: purge the account's host stores (which shuts its cards), then [close] it in the core. Once the
     * purge starts the whole body runs without cancellation, so a caller that goes away cannot leave the account open in
     * the core with its cards shut. Any body that still did not complete (an unexpected exception) re-admits the account
     * when the core says it is still open.
     */
    private suspend fun leave(id: String, close: suspend () -> CoreResult<SecretReply>): ClientResult<Unit> = serial(id) {
        withContext(NonCancellable) {
            var settled = false
            try {
                val result = if (!stores.purgeBeforeClose(id)) {
                    readmitIfOpen(id)
                    storageFailed()
                } else {
                    closed(id, close())
                }
                settled = true
                result
            } finally {
                if (!settled) readmitIfOpen(id)
            }
        }
    }

    /**
     * A sign-out or removal that did not complete re-admits the account only when the core confirms it is still listed and
     * unlocked. A failure because the account had already left (a second sign-out, a removal of a signed-out account) or
     * an unreadable list keeps it closed; the next deliberate sign-in admits it again.
     */
    private suspend fun readmitIfOpen(id: String) {
        val listed = try {
            list()
        } catch (_: Exception) {
            return
        }
        val catalog = (listed as? ClientResult.Ready)?.value ?: return
        if (catalog.accounts.any { it.id == id && it.unlocked }) stores.admit(id)
    }
    private suspend fun closed(id: String, result: CoreResult<SecretReply>): ClientResult<Unit> = when (result) {
        is CoreResult.Failure -> {
            // The account may still be open on this device; only then may its cards open again.
            readmitIfOpen(id)
            ClientResult.Failed(result.appProblem())
        }
        is CoreResult.Success -> {
            result.value.close()
            publishedRevisions.emit(revisionCount.incrementAndGet())
            ClientResult.Ready(Unit)
        }
    }
    private suspend fun switch(id: String): ClientResult<Unit> =
        when (val result = accounts.accountSwitch(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {})) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                stores.admit(id)
                publishedRevisions.emit(revisionCount.incrementAndGet())
                ClientResult.Ready(Unit)
            }
        }
    override suspend fun lock(id: String): ClientResult<Unit> {
        if (!acceptable(id)) return invalid()
        return serial(id) {
            when (val locked = accounts.accountLock(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {})) {
                is CoreResult.Failure -> ClientResult.Failed(locked.appProblem())
                is CoreResult.Success -> {
                    locked.value.close()
                    publishedRevisions.emit(revisionCount.incrementAndGet())
                    ClientResult.Ready(Unit)
                }
            }
        }
    }
    override suspend fun networkingLive(id: String): ClientResult<Boolean> {
        if (!acceptable(id)) return invalid()
        return when (val status = accounts.accountStatus(RepositoryContext(id), JsonObject(emptyMap()))) {
            is CoreResult.Failure -> ClientResult.Failed(status.error.appProblem())
            is CoreResult.Success -> when (val networking = AccountListMetadataReader.networking(status.value)) {
                is CoreResult.Failure -> ClientResult.Failed(networking.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(networking.value == "live")
            }
        }
    }
    private fun codeRefused() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "not_six_digits"))
    private fun storageFailed() = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
    private fun acceptable(id: String) = id.isNotEmpty() && id.length <= 128
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = unavailable()
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}

private const val REMOVAL_NOTICES = 4
