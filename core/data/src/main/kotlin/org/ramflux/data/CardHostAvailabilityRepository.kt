// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** Availability only: no unverified package, script, action, approval or surface fallback is accepted. */
interface CardHostAvailabilityRepository { suspend fun availability(): ClientResult<Unit> }
internal class UnavailableCardHostAvailabilityRepository : CardHostAvailabilityRepository {
    override suspend fun availability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.FEATURE_NOT_INSTALLED)
}
