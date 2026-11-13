// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*

/** Documents the installed-feature gate and preserves session phase/generation; never simulates account data. */
class FakeSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot())
    var available: suspend (ClientFeature) -> ClientResult<Unit> = { ClientResult.Unavailable(it) }
    var connects = 0
    var opening: CompletableDeferred<Unit>? = null
    override suspend fun foreground() {
        connects++
        status.value = status.value.copy(phase = ClientPhase.OPENING)
        opening?.await()
        status.value = ClientSnapshot(ClientPhase.OPEN, status.value.generation + 1)
    }
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = if (status.value.phase != ClientPhase.OPEN) {
        ClientResult.Failed(
            ClientProblem(ClientFailureKind.UNAVAILABLE, 9, ClientOutcome.NONE)
        )
    } else {
        available(feature)
    }
}
class FakeAppearance : AppearanceRepository {
    override val appearance = MutableStateFlow(AppearanceSnapshot(loading = false))
    var failure: ClientProblem? = null
    override suspend fun setAppearance(value: Appearance): ClientResult<Unit> {
        failure?.let { return ClientResult.Failed(it) }
        appearance.value = AppearanceSnapshot(value, loading = false)
        return ClientResult.Ready(Unit)
    }
}
