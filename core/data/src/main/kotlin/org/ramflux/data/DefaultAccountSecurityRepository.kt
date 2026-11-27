// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.*

/** [clock] gives Unix seconds; the pairing lease starts now. */
internal class DefaultAccountSecurityRepository(
    private val transport: RepositoryTransport,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AccountSecurityRepository {
    private val devices = DefaultDevicesRepository(transport)
    private val contacts = DefaultContactsRepository(transport)
    private val accounts = DefaultAccountsRepository(transport)
    private val usernames = DefaultUsernamesRepository(transport)

    override suspend fun usernameStatus(account: String): ClientResult<UsernameState> {
        if (account.isBlank() || account.length > 4096) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        val body = kotlinx.serialization.json.buildJsonObject { put("operation_id", kotlinx.serialization.json.JsonNull) }
        return when (val reply = usernames.status(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(UsernameState(UsernameStatusReader.name(reply.value)))
        }
    }

    override suspend fun claimUsername(account: String, name: String, phrase: CharArray): ClientResult<UsernameClaimResult> {
        // The phrase is erased on every path: the bytes are taken here and wiped by the SDK call or on refusal.
        val secret = try { SecretBytes.utf8Of(phrase) } catch (_: IllegalArgumentException) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        if (!valid(account) || name.isBlank() || name.length > MAX_USERNAME_INPUT) {
            secret.close()
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        return when (val result = usernames.claim(account, name, secret)) {
            is CoreResult.Failure -> ClientResult.Failed(result.appProblem())
            is CoreResult.Success -> ClientResult.Ready(result.value)
        }
    }

    override suspend fun beginPairing(account: String, displayName: String, leaseSeconds: Long): ClientResult<PairingInvitation> {
        if (!valid(account) || displayName.isBlank() || displayName.length > 128 || leaseSeconds !in 3600L..MAX_LEASE_SECONDS) return invalid()
        val now = clock()
        val body = SecretBody.fields {
            header("Begin")
            charArrays("capabilities", CAPABILITIES.map { it.toCharArray() }.toTypedArray())
            publicUnsigned("lease_not_before", now.toULong())
            publicUnsigned("lease_expires_at", (now + leaseSeconds).toULong())
            publicText("display_name", displayName)
        }
        return when (val reply = devices.pairBegin(context(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
            is CoreResult.Success -> when (val read = PairingMetadataReader.read(reply.value)) {
                is CoreResult.Failure -> ClientResult.Failed(read.error.appProblem())
                is CoreResult.Success -> when (val outcome = read.value) {
                    is PairingOutcomeDisplay.Invitation -> ClientResult.Ready(
                        PairingInvitation(outcome.value.operationId, outcome.value.generation, outcome.value.expiresAt, outcome.value.token)
                    )
                    is PairingOutcomeDisplay.Refused -> refused(outcome)
                    is PairingOutcomeDisplay.Update -> invalid()
                }
            }
        }
    }

    override suspend fun pairingStatus(account: String, operationId: String, generation: ULong): ClientResult<PairingState> {
        if (!valid(account) || !operation(operationId)) return invalid()
        val body = buildJsonObject {
            put("schema", "ramflux.pairing.StatusRequest.v2"); put("version", 2)
            put("operation_id", operationId); put("generation", generation.toLong())
        }
        return update(devices.pairStatus(RepositoryContext(account), body), operationId)
    }

    override suspend fun confirmPairing(account: String, state: PairingState, match: Boolean): ClientResult<PairingState> {
        val transcript = state.transcriptHash
        if (!valid(account) || state.phase != PairingPhase.SAS_PENDING || transcript == null) return invalid()
        val body = SecretBody.fields {
            header("Confirm"); base(state)
            publicText("transcript_hash", transcript); publicFlag("match", match)
        }
        return update(devices.pairConfirm(context(account), body), state.operationId)
    }

    override suspend fun approvePairing(account: String, state: PairingState, approved: Boolean): ClientResult<PairingState> {
        val transcript = state.transcriptHash
        val proposal = state.proposalHash
        if (!valid(account) || state.phase != PairingPhase.PROPOSAL_PENDING || transcript == null || proposal == null) return invalid()
        val body = SecretBody.fields {
            header("Approve"); base(state)
            publicText("transcript_hash", transcript); publicText("proposal_hash", proposal); publicFlag("approved", approved)
        }
        return update(devices.pairApprove(context(account), body), state.operationId)
    }

    override suspend fun cancelPairing(account: String, operationId: String, generation: ULong): ClientResult<PairingState> {
        if (!valid(account) || !operation(operationId)) return invalid()
        val body = SecretBody.fields {
            header("Cancel"); publicText("operation_id", operationId); publicUnsigned("generation", generation)
        }
        return update(devices.pairCancel(context(account), body), operationId)
    }

    override suspend fun appLockState(account: String): ClientResult<AppLockStatus> {
        if (!valid(account)) return invalid()
        return appLock(accounts.accountApp_lockState(RepositoryContext(account), JsonObject(emptyMap())))
    }

    override suspend fun configureAppLock(account: String, enabled: Boolean, timeoutMs: Long, answer: suspend () -> CharArray?): ClientResult<Unit> {
        if (!valid(account) || timeoutMs !in 0L..AccountSecurityRepository.MAX_APP_LOCK_TIMEOUT_MS) return invalid()
        val body = buildJsonObject { put("enabled", enabled); put("timeout_ms", timeoutMs) }
        // The attended code window is 60 s; the deadline covers it with a margin, and only this call sets it.
        val context = RepositoryContext(account, deadlineUnixMs = System.currentTimeMillis() + ATTENDED_DEADLINE_MS)
        val reply = try {
            transport.withAttendedCode(account, answer) { accounts.accountApp_lockConfigure(context, body) }
        } catch (_: AttendedCodeBusyException) {
            // A second attended change while one runs: refused before the core sees it, with its own typed refusal.
            return codeRefused(AccountCodeRefusal.APP_LOCK_CHANGE_BUSY)
        }
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
            is CoreResult.Success -> ClientResult.Ready(Unit)
        }
    }

    override suspend fun appLockLifecycle(account: String, state: String): ClientResult<AppLockState> {
        if (!valid(account) || state !in APP_LOCK_STATES) return invalid()
        val body = buildJsonObject { put("state", state) }
        return when (val reply = accounts.accountApp_lockLifecycle(RepositoryContext(account), body)) {
            is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
            is CoreResult.Success -> AppLockState.parse((((reply.value["body"] as? JsonObject)?.get("state")) as? JsonPrimitive)?.contentOrNull)
                ?.let { ClientResult.Ready(it) } ?: invalid()
        }
    }

    private fun appLock(reply: CoreResult<JsonObject>): ClientResult<AppLockStatus> = when (reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
        is CoreResult.Success -> (reply.value["body"] as? JsonObject)?.let { AppLockStatus.read(it) }?.let { ClientResult.Ready(it) } ?: invalid()
    }

    override suspend fun requestPolicy(account: String): ClientResult<ContactRequestPolicy> {
        if (!valid(account)) return invalid()
        return policy(contacts.requestsPolicyGet(RepositoryContext(account), buildJsonObject {}))
    }

    override suspend fun setRequestPolicy(account: String, policy: ContactRequestPolicy): ClientResult<ContactRequestPolicy> {
        if (!valid(account)) return invalid()
        val body = SecretBody.fields { publicText("contact_requests", policy.wire) }
        return policy(contacts.requestsPolicySet(context(account), body))
    }

    override suspend fun changeAccountCode(account: String, current: CharArray, next: CharArray): ClientResult<Unit> {
        try {
            if (!valid(account) || !AccountCode.complete(current)) return codeRefused(AccountCodeRefusal.NOT_SIX_DIGITS)
            AccountCode.newCodeRefusal(next)?.let { return codeRefused(it) }
            if (current.contentEquals(next)) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = SAME_CODE))
            // The SDK field names keep their historical "passphrase"; their values are six-digit account codes.
            val body = SecretBody.fields { chars("old_passphrase", current); chars("new_passphrase", next) }
            return when (val reply = accounts.accountPassphraseRotate(context(account), body)) {
                is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
                is CoreResult.Success -> {
                    reply.value.close()
                    ClientResult.Ready(Unit)
                }
            }
        } finally {
            current.fill('\u0000')
            next.fill('\u0000')
        }
    }

    private fun policy(reply: CoreResult<SecretReply>): ClientResult<ContactRequestPolicy> = when (reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
        is CoreResult.Success -> when (val read = SettingsMetadataReader.requestPolicy(reply.value)) {
            is CoreResult.Failure -> ClientResult.Failed(read.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(ContactRequestPolicy.entries.first { it.wire == read.value })
        }
    }

    private fun update(reply: CoreResult<SecretReply>, operationId: String): ClientResult<PairingState> = when (reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
        is CoreResult.Success -> when (val read = PairingMetadataReader.read(reply.value)) {
            is CoreResult.Failure -> ClientResult.Failed(read.error.appProblem())
            is CoreResult.Success -> when (val outcome = read.value) {
                is PairingOutcomeDisplay.Update -> outcome.value.takeIf { it.operationId == operationId }?.let { ClientResult.Ready(it.state()) } ?: invalid()
                is PairingOutcomeDisplay.Refused -> refused(outcome)
                is PairingOutcomeDisplay.Invitation -> {
                    outcome.value.token.close()
                    invalid()
                }
            }
        }
    }

    private fun PairingUpdateDisplay.state() = PairingState(
        operationId, generation, PairingPhase.entries.first { it.wire == phase }, expiresAt, transcriptHash, emojiIndices,
        peerDevice, peerName, capabilities, leaseExpiresAt, proposalHash, result,
    )

    private fun SecretFields.header(name: String) {
        publicText("schema", "ramflux.pairing.$name.v2")
        publicUnsigned("version", 2u)
    }
    private fun SecretFields.base(state: PairingState) {
        publicText("operation_id", state.operationId)
        publicUnsigned("generation", state.generation)
    }
    private fun context(account: String) = RepositoryContext(account, UUID.randomUUID().toString())
    private fun valid(value: String) = value.isNotBlank() && value.length <= 128
    private fun operation(value: String) = value.isNotBlank() && value.length <= 128
    private fun refused(outcome: PairingOutcomeDisplay.Refused) = ClientResult.Failed(
        ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = outcome.code, retryAfterSecs = outcome.retryAfterMillis?.let { (it + 999) / 1000 })
    )
    private fun codeRefused(refusal: AccountCodeRefusal) = ClientResult.Failed(
        ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = refusal.name.lowercase())
    )
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    private companion object {
        /** Sorted, as the SDK normalises them (`rights` in `local_dto.rs`). */
        val CAPABILITIES = listOf("call", "group", "message", "register", "sync")
        const val MAX_LEASE_SECONDS = 2_592_000L
        const val MAX_USERNAME_INPUT = 65
        /** The attended code window (60 s) plus a margin: the default request deadline would cut it short. */
        const val ATTENDED_DEADLINE_MS = 90_000L
        val APP_LOCK_STATES = setOf(APP_LOCK_FOREGROUND, APP_LOCK_BACKGROUND, "device_locked")
    }
}

/** Client-side refusal name: the new account code equals the current one. */
const val SAME_CODE = "same_code"

