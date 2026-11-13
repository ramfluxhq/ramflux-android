// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.UnavailableCapability

enum class StrangerReportReason { SPAM, ABUSE, OTHER }
interface StrangerReportingRepository {
    suspend fun report(context: RepositoryContext, contactId: String, reason: StrangerReportReason): CoreResult<Unit>
    suspend fun declineBatch(context: RepositoryContext, requestIds: List<String>): CoreResult<Unit>
}

/** Never synthesize a report receipt or imitate a batch transaction with repeated SDK calls. */
class UnavailableStrangerReportingRepository : StrangerReportingRepository {
    override suspend fun report(context: RepositoryContext, contactId: String, reason: StrangerReportReason): CoreResult<Unit> =
        CoreResult.Failure(CoreError.Unavailable(UnavailableCapability.STRANGER_REPORTING), context.operationId)
    override suspend fun declineBatch(context: RepositoryContext, requestIds: List<String>): CoreResult<Unit> =
        CoreResult.Failure(CoreError.Unavailable(UnavailableCapability.STRANGER_REPORTING), context.operationId)
}
