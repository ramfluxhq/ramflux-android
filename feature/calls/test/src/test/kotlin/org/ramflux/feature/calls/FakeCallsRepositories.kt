// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
fun callRecord(sequence: ULong = 7u, phase: CallPhase = CallPhase.INCOMING_RINGING) = CallRecord(
    "call", "conversation", "peer", CallDirection.INCOMING,
    phase, sequence, 3u, 1700000000, null, null, null, null, null
)
class FakeCalls : CallDirectoryRepository {
    var reads = 0
    var reading: suspend () -> ClientResult<List<CallRecord>> = { ClientResult.Ready(listOf(callRecord())) }
    val signals = mutableListOf<CallSignal>()
    var signaling: suspend (
        CallSignal
    ) -> ClientResult<CallRecord> = {
        ClientResult.Ready(it.expected.copy(phase = CallPhase.ENDED, sequence = it.expected.sequence + 1u, endReason = "missed"))
    }
    val invalidations = MutableSharedFlow<ClientResult<Unit>>(extraBufferCapacity = 8)
    override suspend fun list(account: String): ClientResult<List<CallRecord>> {
        reads++
        return reading()
    }
    override suspend fun signal(account: String, attempt: CallSignal): ClientResult<CallRecord> {
        signals.add(attempt)
        return signaling(attempt)
    }
    override fun pending(account: String): CallSignal? = null
    override fun updates(account: String) = invalidations
    override suspend fun audioAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CALLS)
}
class FakeCallAccounts : AccountAccessRepository {
    var result: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    override suspend fun list() = result
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeCallSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
