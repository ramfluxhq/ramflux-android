// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.UnavailableCapability

/** App-facing requests; these are not SDK wire schemas or fabricated projections. */
class LocalSearchQuery(val accountId: String, val text: String, val conversationId: String? = null) {
    override fun toString() = "LocalSearchQuery(<redacted>)"
}
data class SearchHit(val conversationId: String, val messageId: String, val excerpt: String)
interface LocalSearchRepository {
    suspend fun search(query: LocalSearchQuery): CoreResult<List<SearchHit>>
}

/** Unavailable until the shared SDK implements local full-text search. */
class UnavailableLocalSearchRepository : LocalSearchRepository {
    override suspend fun search(query: LocalSearchQuery): CoreResult<List<SearchHit>> =
        CoreResult.Failure(CoreError.Unavailable(UnavailableCapability.LOCAL_SEARCH))
}
