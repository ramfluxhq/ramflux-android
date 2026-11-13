// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.StateFlow

/** App-owned values: no native reply, secret holder, or SDK error crosses this contract. */
enum class ClientPhase { CLOSED, OPENING, OPEN, CLOSING, FAILED }
enum class ClientFeature(val id: String) { CHAT("chat"), ONBOARDING("onboarding"), CONVERSATIONS("conversations"), CONTACTS("contacts"), SETTINGS("settings"), GROUPS("groups") }
enum class ClientFailureKind { INCOMPATIBLE, UNAVAILABLE, STORAGE, AUTHORIZATION, SHUTDOWN_PENDING, FAILED }
enum class ClientOutcome { NONE, REFUSED, UNKNOWN }
data class ClientProblem(val kind: ClientFailureKind, val code: Int, val outcome: ClientOutcome)
data class ClientSnapshot(val phase: ClientPhase = ClientPhase.CLOSED, val generation: Long = 0, val problem: ClientProblem? = null)
enum class UnavailableReason { FEATURE_NOT_INSTALLED, UNSUPPORTED_BY_CORE, CUSTODY_PROVIDER_REQUIRED }
sealed interface ClientResult<out T> {
    data class Ready<T>(val value: T) : ClientResult<T>
    data class Failed(val problem: ClientProblem) : ClientResult<Nothing>
    data class Unavailable(val feature: ClientFeature, val reason: UnavailableReason = UnavailableReason.FEATURE_NOT_INSTALLED) : ClientResult<Nothing>
}

/** Foreground/background are serialized. OPEN means the supervisor opened, not account authentication. */
interface ClientSessionRepository {
    val status: StateFlow<ClientSnapshot>
    suspend fun foreground()
    suspend fun background()
    suspend fun availability(feature: ClientFeature): ClientResult<Unit>
}
