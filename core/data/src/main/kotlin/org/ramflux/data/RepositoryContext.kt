// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreRequest
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.SecretRequest
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SecretBytes
import org.ramflux.sdk.UsernamePrepared
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.AndroidCoreHost
import org.ramflux.sdk.HostState
import org.ramflux.sdk.CoreError
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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
    suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply>
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate>
    suspend fun resetEvents(generation: ULong): CoreResult<JsonObject> = CoreResult.Failure(CoreError.Closed())

    /**
     * Runs [body] with the attended account-code callback registered (see `CoreClient.withAttendedCode`). The default has
     * no callback, so the core answers `reauth_required` and nothing changes.
     */
    suspend fun <T> withAttendedCode(answer: suspend () -> CharArray?, body: suspend () -> T): T = body()

    /** Username prepare and authorize (see `CoreClient.prepareUsernameChange`); the default refuses and erases both inputs. */
    suspend fun prepareUsernameChange(request: SecretRequest, change: JsonObject, phrase: SecretBytes): CoreResult<UsernamePrepared> {
        request.body.close()
        phrase.close()
        return CoreResult.Failure(CoreError.Closed(), request.operationId)
    }

    /** True when the open core negotiated the card runtime (`card_runtime`); false without a client. */
    fun cardRuntimeAvailable(): Boolean = false
}

/** Resolve the current host generation for every call and every new collection. */
class SdkRepositoryTransport internal constructor(private val currentClient: () -> org.ramflux.sdk.CoreClient?) : RepositoryTransport {
    constructor(host: AndroidCoreHost) : this({ (host.state.value as? HostState.Running)?.client })
    override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> {
        val client = currentClient()
        if (client == null) {
            org.ramflux.sdk.sdkDebugEvent("sdk.no_client method=${request.method}")
            return CoreResult.Failure(CoreError.Closed(), request.operationId)
        }
        return client.execute(request)
    }
    override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> {
        val client = currentClient()
        if (client == null) {
            request.body.close()
            org.ramflux.sdk.sdkDebugEvent("sdk.no_client method=${request.method}")
            return CoreResult.Failure(CoreError.Closed(), request.operationId)
        }
        return client.executeSecret(request)
    }
    override suspend fun prepareUsernameChange(request: SecretRequest, change: JsonObject, phrase: SecretBytes): CoreResult<UsernamePrepared> {
        val client = currentClient()
        if (client == null) {
            request.body.close()
            phrase.close()
            org.ramflux.sdk.sdkDebugEvent("sdk.no_client method=${request.method}")
            return CoreResult.Failure(CoreError.Closed(), request.operationId)
        }
        return client.prepareUsernameChange(request, change, phrase)
    }
    override suspend fun <T> withAttendedCode(answer: suspend () -> CharArray?, body: suspend () -> T): T {
        val client = currentClient() ?: return body()
        return client.withAttendedCode(answer, body)
    }
    override fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = flow {
        val client = currentClient()
        if (client == null) emit(CoreUpdate.Failure(CoreError.Closed()))
        else emitAll(client.events.subscribe(filter))
    }

    override fun cardRuntimeAvailable(): Boolean = currentClient()?.capabilities?.cardRuntime != null

    /** The shared event hub resets the native queue itself before it reports a resync. */
    override suspend fun resetEvents(generation: ULong): CoreResult<JsonObject> =
        if (currentClient() == null) CoreResult.Failure(CoreError.Closed()) else CoreResult.Success(JsonObject(emptyMap()))
}

/** Carries the SDK's exact descriptor body without a parallel message schema. */
abstract class SdkRepository(private val transport: RepositoryTransport) {
    protected suspend fun execute(
        method: String, sdkApi: String, context: RepositoryContext, body: JsonObject,
    ): CoreResult<JsonObject> = transport.execute(
        CoreRequest(method, sdkApi, context.accountId, context.operationId, body, context.deadlineUnixMs),
    )
    protected suspend fun executeSecret(
        method: String, sdkApi: String, context: RepositoryContext, body: SecretBody,
    ): CoreResult<SecretReply> = transport.executeSecret(
        SecretRequest(method, sdkApi, context.accountId, context.operationId, body, context.deadlineUnixMs),
    )
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = transport.updates(filter)
}
