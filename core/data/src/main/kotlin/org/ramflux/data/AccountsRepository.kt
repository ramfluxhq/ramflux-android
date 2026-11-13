// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface AccountsRepository {
    suspend fun accountBackupCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountBackupCreate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountBackupExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountBackupImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountBackupJobs(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountConfigGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountConfigSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountCreateBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountCreateCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCreateConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountCreateHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountCreateShow_once(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountCustodyBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountCustodyCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountCustodyConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountCustodyHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountCustodyShow_once(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountExport_agent_credentials(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountLock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountPassphraseRotate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountRecoverBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountRecoverCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun accountRecoverConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountRestoreInstall(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountRestoreVerify(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun accountSwitch(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun accountUnlock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun identityDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
}

class DefaultAccountsRepository(transport: RepositoryTransport) : SdkRepository(transport), AccountsRepository {
    override suspend fun accountBackupCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.backup.cancel", "account", context, body)

    override suspend fun accountBackupCreate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.backup.create", "account", context, body)

    override suspend fun accountBackupExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.backup.export", "account", context, body)

    override suspend fun accountBackupImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.backup.import", "account", context, body)

    override suspend fun accountBackupJobs(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.backup.jobs", "account", context, body)

    override suspend fun accountConfigGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.config.get", "account", context, SecretBody.publicJson("account.config.get", body))

    override suspend fun accountConfigSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.config.set", "account", context, body)

    override suspend fun accountCreateBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.create.begin", "account", context, body)

    override suspend fun accountCreateCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.create.cancel", "account", context, body)

    override suspend fun accountCreateConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.create.confirm", "account", context, body)

    override suspend fun accountCreateHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.create.hidden_challenge", "account", context, SecretBody.publicJson("account.create.hidden_challenge", body))

    override suspend fun accountCreateShow_once(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.create.show_once", "account", context, SecretBody.publicJson("account.create.show_once", body))

    override suspend fun accountCustodyBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.custody.begin", "account", context, body)

    override suspend fun accountCustodyCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.custody.cancel", "account", context, body)

    override suspend fun accountCustodyConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.custody.confirm", "account", context, body)

    override suspend fun accountCustodyHidden_challenge(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.custody.hidden_challenge", "account", context, SecretBody.publicJson("account.custody.hidden_challenge", body))

    override suspend fun accountCustodyShow_once(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.custody.show_once", "account", context, SecretBody.publicJson("account.custody.show_once", body))

    override suspend fun accountExport_agent_credentials(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.export_agent_credentials", "account", context, body)

    override suspend fun accountList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.list", "account", context, SecretBody.publicJson("account.list", body))

    override suspend fun accountLock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.lock", "account", context, body)

    override suspend fun accountPassphraseRotate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.passphrase.rotate", "account", context, body)

    override suspend fun accountRecoverBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.recover.begin", "account", context, body)

    override suspend fun accountRecoverCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("account.recover.cancel", "account", context, body)

    override suspend fun accountRecoverConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.recover.confirm", "account", context, body)

    override suspend fun accountRestoreInstall(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.restore.install", "account", context, body)

    override suspend fun accountRestoreVerify(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.restore.verify", "account", context, body)

    override suspend fun accountStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("account.status", "account", context, SecretBody.publicJson("account.status", body))

    override suspend fun accountSwitch(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.switch", "account", context, body)

    override suspend fun accountUnlock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("account.unlock", "account", context, body)

    override suspend fun identityDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("identity.directory", "identity", context, SecretBody.publicJson("identity.directory", body))
}
