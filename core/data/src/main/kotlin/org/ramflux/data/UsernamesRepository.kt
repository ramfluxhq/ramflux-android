// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretBytes
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SecretRequest
import org.ramflux.sdk.UsernamePrepared
import org.ramflux.sdk.UsernameStatusReader

/** The directory's answer once a claim or change has settled, or once the poll window closed while it was still pending. */
sealed interface UsernameClaimResult {
    /** The draft was a no-op; nothing was published. */
    data object Unchanged : UsernameClaimResult
    data class Committed(val name: String?) : UsernameClaimResult
    /** [reason] is the directory's reason code when it gave one. */
    data class Aborted(val reason: String?) : UsernameClaimResult
    /** Still pending after the poll window; read the status again later. */
    data object Pending : UsernameClaimResult
}

interface UsernamesRepository {
    suspend fun mutate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun status(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun contactAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>

    /**
     * Claims [name] (a leading `@` is dropped) with the account's recovery [phrase]: prepare, authorize, submit, then poll
     * while pending. [phrase] is erased by this call; it never enters JSON, logs or storage.
     */
    suspend fun claim(accountId: String, name: String, phrase: SecretBytes): CoreResult<UsernameClaimResult>
}

class DefaultUsernamesRepository(private val transport: RepositoryTransport) : SdkRepository(transport), UsernamesRepository {
    override suspend fun mutate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("username.mutate", "username", context, body)

    override suspend fun status(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("username.status", "username", context, SecretBody.publicJson("username.status", body))

    override suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("username.resolve", "username", context, SecretBody.publicJson("username.resolve", body))

    override suspend fun contactAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("username.contact.add", "username", context, body)

    override suspend fun claim(accountId: String, name: String, phrase: SecretBytes): CoreResult<UsernameClaimResult> {
        val context = RepositoryContext(accountId)
        val change = buildJsonObject {
            put("change", JsonPrimitive("set"))
            put("name", JsonPrimitive(name.removePrefix("@")))
        }
        val prepareBody = SecretBody.takeJson(
            buildJsonObject { put("phase", JsonPrimitive("prepare")); put("change", change) }.toString().toByteArray(),
        )
        val request = SecretRequest("username.mutate", "username", accountId, null, prepareBody, 0)
        val prepared = when (val result = transport.prepareUsernameChange(request, change, phrase)) {
            is CoreResult.Failure -> return result
            is CoreResult.Success -> result.value
        }
        return prepared.use { step ->
            when (step) {
                is UsernamePrepared.NoChange -> CoreResult.Success(UsernameClaimResult.Unchanged)
                is UsernamePrepared.Signed -> settle(context, accountId, step)
            }
        }
    }

    /** Submits the signed draft, then polls `username.status` every two seconds for up to thirty seconds while pending. */
    private suspend fun settle(context: RepositoryContext, accountId: String, signed: UsernamePrepared.Signed): CoreResult<UsernameClaimResult> {
        var current = when (val submitted = mutate(context, signed.submitBody())) {
            is CoreResult.Failure -> return submitted
            is CoreResult.Success -> UsernameStatusReader.status(submitted.value) ?: return unreadable()
        }
        val operationId = signed.operationId
        var waited = 0L
        while (current.state == "pending" && operationId != null && waited < POLL_WINDOW_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
            val polled = status(context, buildJsonObject { put("operation_id", JsonPrimitive(operationId)) })
            current = when (polled) {
                is CoreResult.Failure -> return polled
                is CoreResult.Success -> UsernameStatusReader.status(polled.value) ?: return unreadable()
            }
        }
        return CoreResult.Success(
            when (current.state) {
                "committed" -> UsernameClaimResult.Committed(current.name)
                "pending" -> UsernameClaimResult.Pending
                else -> UsernameClaimResult.Aborted(current.reason)
            },
        )
    }

    private fun unreadable(): CoreResult<UsernameClaimResult> = CoreResult.Failure(CoreError.InvalidResponse())

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_WINDOW_MS = 30_000L
    }
}
