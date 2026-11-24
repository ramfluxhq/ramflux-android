// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface UsernamesRepository {
    suspend fun mutate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun status(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun contactAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultUsernamesRepository(transport: RepositoryTransport) : SdkRepository(transport), UsernamesRepository {
    override suspend fun mutate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("username.mutate", "username", context, body)

    override suspend fun status(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("username.status", "username", context, SecretBody.publicJson("username.status", body))

    override suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("username.resolve", "username", context, SecretBody.publicJson("username.resolve", body))

    override suspend fun contactAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("username.contact.add", "username", context, body)
}
