// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreRequest
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.SubscriptionFilter
import kotlinx.coroutines.flow.Flow

/** Operation IDs originate at the intent boundary and survive uncertain completions. */
data class RepositoryContext(
    val accountId: String?,
    val operationId: String? = null,
    val deadlineUnixMs: Long = 0,
)

interface RepositoryTransport {
    suspend fun execute(request: CoreRequest): CoreResult<JsonObject>
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate>
}

class SdkRepositoryTransport(private val client: org.ramflux.sdk.CoreClient) : RepositoryTransport {
    override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = client.execute(request)
    override fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = client.updates(filter)
}

/** Carries the SDK's exact descriptor body without a parallel message schema. */
abstract class SdkRepository(private val transport: RepositoryTransport) {
    protected suspend fun execute(
        method: String, sdkApi: String, context: RepositoryContext, body: JsonObject,
    ): CoreResult<JsonObject> = transport.execute(
        CoreRequest(method, sdkApi, context.accountId, context.operationId, body, context.deadlineUnixMs),
    )
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = transport.updates(filter)
}
