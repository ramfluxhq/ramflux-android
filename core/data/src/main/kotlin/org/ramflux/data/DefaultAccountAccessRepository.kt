// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.*

internal class DefaultAccountAccessRepository(
    transport: RepositoryTransport,
    private val known: KnownAccountRegistry = MemoryKnownAccountRegistry()
) : AccountAccessRepository {
    private val accounts = DefaultAccountsRepository(transport)
    private val revisionCount = AtomicInteger()
    private val publishedRevisions = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    override val revisions: Flow<Int> = publishedRevisions
    override suspend fun list(): ClientResult<AccountCatalog> = when (val listed = sdkList()) {
        is ClientResult.Ready -> ClientResult.Ready(listed.value.mergedWith(io { known.all() }))
        else -> listed
    }
    private suspend fun sdkList(): ClientResult<AccountCatalog> = when (val result = accounts.accountList(RepositoryContext(null), JsonObject(emptyMap()))) {
        is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
        is CoreResult.Success -> when (val decoded = AccountListMetadataReader.read(result.value)) {
            is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(AccountCatalog(decoded.value.accounts.map {
                AccountSummary(it.id, it.label, it.state == "unlocked", it.state)
            }, decoded.value.activeAccountId))
        }
    }
    override suspend fun select(id: String): ClientResult<Unit> {
        // Authoritative state is checked for every selection, not trusted from a previous screen.
        val catalog = when (val current = list()) {
            is ClientResult.Ready -> current.value
            is ClientResult.Failed -> return current
            is ClientResult.Unavailable -> return current
        }
        if (catalog.accounts.none { it.id == id && it.unlocked }) return unavailable()
        return switch(id)
    }
    override suspend fun unlock(id: String, code: CharArray): ClientResult<Unit> {
        if (!acceptable(id) || !AccountCode.complete(code)) {
            code.fill('\u0000')
            return if (acceptable(id)) codeRefused() else invalid()
        }
        // The SDK field keeps its historical name; its value is the six-digit account code.
        val unlocked = accounts.accountUnlock(
            RepositoryContext(id, UUID.randomUUID().toString()),
            SecretBody.fields { chars("passphrase", code) }
        )
        return when (unlocked) {
            is CoreResult.Failure -> ClientResult.Failed(unlocked.appProblem())
            is CoreResult.Success -> {
                unlocked.value.close()
                switch(id).also { if (it is ClientResult.Ready) rememberOpened(id) }
            }
        }
    }
    override suspend fun forget(id: String): ClientResult<Unit> {
        if (!acceptable(id)) return invalid()
        val catalog = when (val current = sdkList()) {
            is ClientResult.Ready -> current.value
            is ClientResult.Failed -> return current
            is ClientResult.Unavailable -> return current
        }
        if (catalog.accounts.any { it.id == id && it.unlocked }) return invalid()
        io { known.remove(id) }
        publishedRevisions.emit(revisionCount.incrementAndGet())
        return ClientResult.Ready(Unit)
    }

    /** An account opened by its code is recorded (or renamed from the SDK's label) for the next start. */
    private suspend fun rememberOpened(id: String) {
        val label = ((sdkList() as? ClientResult.Ready)?.value?.accounts?.firstOrNull { it.id == id })?.label
        io {
            val existing = known.all().firstOrNull { it.id == id }
            if (existing == null || !label.isNullOrBlank()) known.record(KnownAccount(id, label.orEmpty()))
        }
    }
    private suspend fun switch(id: String): ClientResult<Unit> =
        when (val result = accounts.accountSwitch(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {})) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                publishedRevisions.emit(revisionCount.incrementAndGet())
                ClientResult.Ready(Unit)
            }
        }
    override suspend fun lock(id: String): ClientResult<Unit> {
        if (!acceptable(id)) return invalid()
        return when (val locked = accounts.accountLock(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {})) {
            is CoreResult.Failure -> ClientResult.Failed(locked.appProblem())
            is CoreResult.Success -> {
                locked.value.close()
                publishedRevisions.emit(revisionCount.incrementAndGet())
                ClientResult.Ready(Unit)
            }
        }
    }
    private fun codeRefused() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "not_six_digits"))
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun acceptable(id: String) = id.isNotEmpty() && id.length <= 128
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = unavailable()
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
