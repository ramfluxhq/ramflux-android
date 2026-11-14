// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
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

interface AccountCeremonyRepository {
    suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending>
    suspend fun showPhrase(): ClientResult<RecoveryPhrase>
    suspend fun challenge(): ClientResult<IntArray>
    suspend fun confirm(words: Array<CharArray>, passphrase: CharArray): ClientResult<String>
    suspend fun cancel(): ClientResult<Unit>
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
class DefaultAccountCeremonyRepository(transport: RepositoryTransport) : AccountCeremonyRepository {
    private val accounts = DefaultAccountsRepository(transport)
    private val gate = Any()
    private var ceremony: String? = null

    override suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending> {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > 64) return failed()
        synchronized(gate) { if (ceremony != null) return busy() }
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

    private fun context() = RepositoryContext(null, UUID.randomUUID().toString())
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
