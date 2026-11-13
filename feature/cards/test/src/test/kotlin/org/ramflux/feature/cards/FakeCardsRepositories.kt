// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
class FakeCardHost : CardHostAvailabilityRepository {
    var calls = 0
    var checking: suspend () -> ClientResult<Unit> = { ClientResult.Unavailable(ClientFeature.CARDS) }
    override suspend fun availability(): ClientResult<Unit> {
        calls++
        return checking()
    }
}
class FakeCardSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {
        status.value = ClientSnapshot(ClientPhase.OPEN, status.value.generation + 1)
    }
    override suspend fun background() {
        status.value = ClientSnapshot(ClientPhase.CLOSED, status.value.generation + 1)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
