// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the settings flows. No retries or optimistic outcomes. */
interface SettingsRepository {
    suspend fun statusGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultSettingsRepository(transport: RepositoryTransport) : SdkRepository(transport), SettingsRepository {
    override suspend fun statusGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("sync.status.get", "sync", context, body)
}
