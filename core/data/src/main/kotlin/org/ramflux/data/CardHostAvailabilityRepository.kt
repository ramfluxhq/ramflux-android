// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** Availability only: no unverified package, script, action, approval or surface fallback is accepted. */
interface CardHostAvailabilityRepository { suspend fun availability(): ClientResult<Unit> }

/**
 * Agent surfaces render inside their conversations through [CardSurfaceRepository] (`surface.list`,
 * `surface.get`, `surface.action`, all admitted by the pinned method policy). The standalone card host
 * has no content of its own; it reports that cards are available in conversations.
 */
internal class SurfaceCardHostAvailabilityRepository : CardHostAvailabilityRepository {
    override suspend fun availability(): ClientResult<Unit> = ClientResult.Ready(Unit)
}
