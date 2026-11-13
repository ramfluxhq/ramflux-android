// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface SettingsRepository {
    suspend fun statusGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
}

class DefaultSettingsRepository(transport: RepositoryTransport) : SdkRepository(transport), SettingsRepository {
    override suspend fun statusGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("sync.status.get", "sync", context, SecretBody.publicJson("sync.status.get", body))
}
