// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

/** One-time recovery words. Drawing borrows each word; close erases every copy. */
class RecoveryPhrase(private val words: Array<CharArray>) : AutoCloseable {
    val length: Int get() = words.size
    fun draw(index: Int, block: (CharArray) -> Unit) {
        val word = words.getOrNull(index) ?: return
        try { block(word) } finally { }
    }
    override fun close() { words.forEach { it.fill('\u0000') } }
    override fun toString(): String = "RecoveryPhrase(<redacted>)"
}

data class CeremonyPending(val id: String, val expiresInMs: Long)

/** A started phrase recovery: the account anchor (fingerprint) the user confirms before phrase entry. */
data class RecoveryPending(val accountId: String, val anchor: String)

interface AccountCeremonyRepository {
    suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending>
    suspend fun showPhrase(): ClientResult<RecoveryPhrase>
    suspend fun challenge(): ClientResult<IntArray>
    suspend fun confirm(words: Array<CharArray>, passphrase: CharArray): ClientResult<String>
    suspend fun cancel(): ClientResult<Unit>

    /**
     * Phrase recovery of an account whose data is on this installation (`account.recover.begin`).
     * The SDK refuses an account that has no local data; a new installation needs a data restore
     * or device pairing first.
     */
    suspend fun beginRecover(accountId: String): ClientResult<RecoveryPending> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)

    /** `account.recover.confirm`. Both arrays are wiped before return; one attempt per ceremony. */
    suspend fun confirmRecover(phrase: CharArray, code: CharArray, anchor: String): ClientResult<String> {
        phrase.fill('\u0000')
        code.fill('\u0000')
        return ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
    }

    suspend fun cancelRecover(): ClientResult<Unit> = ClientResult.Ready(Unit)
}

class UnavailableAccountCeremonyRepository : AccountCeremonyRepository {
    override suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending> = unavailable()
    override suspend fun showPhrase(): ClientResult<RecoveryPhrase> = unavailable()
    override suspend fun challenge(): ClientResult<IntArray> = unavailable()
    override suspend fun confirm(words: Array<CharArray>, passphrase: CharArray): ClientResult<String> {
        words.forEach { it.fill('\u0000') }
        passphrase.fill('\u0000')
        return unavailable()
    }
    override suspend fun cancel() = ClientResult.Ready(Unit)
    private fun <T> unavailable(): ClientResult<T> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}

/** SDK creation ceremony. The host custody provider, installed at open, stores the vault seed. */
class DefaultAccountCeremonyRepository(
    transport: RepositoryTransport
) : AccountCeremonyRepository {
    private val accounts = DefaultAccountsRepository(transport)
    private val gate = Any()
    private var ceremony: String? = null
    private var recovery: String? = null

    override suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending> {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > 64) return failed()
        synchronized(gate) {
            if (ceremony != null) return busy()
        }
        val reply = accounts.accountCreateBegin(context(), SecretBody.fields {
            publicText("display_name", name)
            publicText("principal_kind", "human")
            if (!nodeId.isNullOrBlank()) publicText("node_id", nodeId)
        })
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> readPending(reply.value)
        }
    }

    override suspend fun showPhrase(): ClientResult<RecoveryPhrase> {
        synchronized(gate) { if (ceremony == null) return failed() }
        val reply = accounts.accountCreateShow_once(context(), JsonObject(emptyMap()))
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> try {
                var phrase: RecoveryPhrase? = null
                reply.value.consumeChars("phrase") { chars -> phrase = splitPhrase(chars) }
                val shown = phrase ?: return failed()
                if (shown.length != 24) {
                    shown.close()
                    failed()
                } else ClientResult.Ready(shown)
            } catch (_: IllegalArgumentException) {
                failed()
            } finally {
                reply.value.close()
            }
        }
    }

    override suspend fun challenge(): ClientResult<IntArray> {
        synchronized(gate) { if (ceremony == null) return failed() }
        val reply = accounts.accountCreateHidden_challenge(context(), JsonObject(emptyMap()))
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> try {
                val positions = reply.value.ints("positions")
                if (positions.size != 3 || positions.any { it !in 1..24 } || positions.toSet().size != 3) failed()
                else ClientResult.Ready(positions)
            } catch (_: IllegalArgumentException) {
                failed()
            } finally {
                reply.value.close()
            }
        }
    }

    override suspend fun confirm(words: Array<CharArray>, passphrase: CharArray): ClientResult<String> {
        try {
            synchronized(gate) { if (ceremony == null || words.size != 3) return failed() }
            // The SDK refuses these too; refusing first keeps the one word-check attempt unspent.
            AccountCode.newCodeRefusal(passphrase)?.let { refusal ->
                val name = if (refusal == AccountCodeRefusal.TRIVIAL_CODE) "trivial_code" else "not_six_digits"
                return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = name))
            }
            val reply = accounts.accountCreateConfirm(context(), SecretBody.fields {
                charArrays("words", words)
                chars("passphrase", passphrase)
            })
            return when (reply) {
                is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
                is CoreResult.Success -> try {
                    val created = reply.value.twoTexts("account_id", "display_name")
                    synchronized(gate) { ceremony = null }
                    ClientResult.Ready(created.first)
                } catch (_: IllegalArgumentException) {
                    failed()
                } finally {
                    reply.value.close()
                }
            }
        } finally {
            words.forEach { it.fill('\u0000') }
            passphrase.fill('\u0000')
        }
    }

    override suspend fun cancel(): ClientResult<Unit> {
        val active = synchronized(gate) { ceremony } ?: return ClientResult.Ready(Unit)
        return when (val reply = accounts.accountCreateCancel(context(), JsonObject(emptyMap()))) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> {
                val body = reply.value["body"] as? JsonObject
                val cancelled = (body?.get("cancelled") as? JsonPrimitive)?.booleanOrNull == true
                if (!cancelled) failed() else {
                    synchronized(gate) { if (ceremony == active) ceremony = null }
                    ClientResult.Ready(Unit)
                }
            }
        }
    }

    override suspend fun beginRecover(accountId: String): ClientResult<RecoveryPending> {
        if (accountId.isBlank() || accountId.length > 128) return failed()
        synchronized(gate) { if (recovery != null) recovery = null }
        val reply = accounts.accountRecoverBegin(context(), SecretBody.fields { publicText("account_id", accountId) })
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> try {
                val (id, anchor) = reply.value.twoTexts("ceremony_id", "account_anchor")
                if (id.isEmpty() || id.length > 128 || anchor.isEmpty() || anchor.length > 256) failed()
                else {
                    synchronized(gate) { recovery = id }
                    ClientResult.Ready(RecoveryPending(accountId, anchor))
                }
            } catch (_: IllegalArgumentException) {
                failed()
            } finally {
                reply.value.close()
            }
        }
    }

    override suspend fun confirmRecover(phrase: CharArray, code: CharArray, anchor: String): ClientResult<String> {
        try {
            synchronized(gate) { if (recovery == null) return failed() }
            if (!AccountCode.complete(code)) {
                return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "not_six_digits"))
            }
            // Every confirmation consumes the ceremony, whatever its outcome.
            synchronized(gate) { recovery = null }
            val reply = accounts.accountRecoverConfirm(context(), SecretBody.fields {
                chars("phrase", phrase)
                chars("passphrase", code)
                publicText("confirmed_anchor", anchor)
            })
            return when (reply) {
                is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
                is CoreResult.Success -> try {
                    val recovered = reply.value.twoTexts("account_id", "display_name")
                    ClientResult.Ready(recovered.first)
                } catch (_: IllegalArgumentException) {
                    failed()
                } finally {
                    reply.value.close()
                }
            }
        } finally {
            phrase.fill('\u0000')
            code.fill('\u0000')
        }
    }

    override suspend fun cancelRecover(): ClientResult<Unit> {
        synchronized(gate) { recovery ?: return ClientResult.Ready(Unit) }
        val reply = accounts.accountRecoverCancel(context(), JsonObject(emptyMap()))
        synchronized(gate) { recovery = null }
        return when (reply) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(Unit)
        }
    }

    private fun readPending(reply: org.ramflux.sdk.SecretReply): ClientResult<CeremonyPending> = try {
        val (id, expires) = reply.textAndLong("ceremony_id", "expires_in_ms")
        if (!id.startsWith("ceremony-") || id.length > 128 || expires <= 0) failed()
        else {
            synchronized(gate) { ceremony = id }
            ClientResult.Ready(CeremonyPending(id, expires))
        }
    } catch (_: IllegalArgumentException) {
        failed()
    }

    // custody_setup rejects a frame operation_id. The in-memory ceremony id is the guard.
    private fun context() = RepositoryContext(null)
    private fun <T> failed(): ClientResult<T> = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun <T> busy(): ClientResult<T> = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 5, ClientOutcome.NONE))

    private fun splitPhrase(chars: CharArray): RecoveryPhrase {
        val parts = mutableListOf<CharArray>()
        var start = 0
        fun push(end: Int) {
            if (end > start) parts.add(chars.copyOfRange(start, end))
        }
        for (index in chars.indices) if (chars[index] == ' ') {
            push(index)
            start = index + 1
        }
        push(chars.size)
        return RecoveryPhrase(parts.toTypedArray())
    }
}
