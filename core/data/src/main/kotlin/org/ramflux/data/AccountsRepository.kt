// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the accounts flows. No retries or optimistic outcomes. */
interface AccountsRepository {
    suspend fun accountBackupExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountBackupImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountConfigGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountConfigSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountExport_agent_credentials(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountLock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountPassphraseRotate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountSwitch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountUnlock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun identityDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateShow_once(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountRecoverBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountRecoverConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountRecoverCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultAccountsRepository(transport: RepositoryTransport) : SdkRepository(transport), AccountsRepository {
    override suspend fun accountBackupExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.backup.export", "account", context, body)

    override suspend fun accountBackupImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.backup.import", "account", context, body)

    override suspend fun accountConfigGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.config.get", "account", context, body)

    override suspend fun accountConfigSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.config.set", "account", context, body)

    override suspend fun accountExport_agent_credentials(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.export_agent_credentials", "account", context, body)

    override suspend fun accountList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.list", "account", context, body)

    override suspend fun accountLock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.lock", "account", context, body)

    override suspend fun accountPassphraseRotate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.passphrase.rotate", "account", context, body)

    override suspend fun accountStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.status", "account", context, body)

    override suspend fun accountSwitch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.switch", "account", context, body)

    override suspend fun accountUnlock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.unlock", "account", context, body)

    override suspend fun identityDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("identity.directory", "identity", context, body)

    override suspend fun accountCreateBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.begin", "account", context, body)

    override suspend fun accountCreateShow_once(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.show_once", "account", context, body)

    override suspend fun accountCreateHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.hidden_challenge", "account", context, body)

    override suspend fun accountCreateConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.confirm", "account", context, body)

    override suspend fun accountCreateCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.cancel", "account", context, body)

    override suspend fun accountRecoverBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.recover.begin", "account", context, body)

    override suspend fun accountRecoverConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.recover.confirm", "account", context, body)

    override suspend fun accountRecoverCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.recover.cancel", "account", context, body)
}
