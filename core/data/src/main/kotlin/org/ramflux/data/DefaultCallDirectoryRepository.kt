// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultCallDirectoryRepository(transport: RepositoryTransport) : CallDirectoryRepository {
    private val source = DefaultCallsRepository(transport)
    private data class Attempt(val signal: CallSignal, var result: ClientResult<CallRecord>?)
    private val journal = mutableMapOf<String, Attempt>()
    override suspend fun list(account: String): ClientResult<List<CallRecord>> {
        if (account.isBlank() || account.length > 4096) return invalid()
        val result = decode(source.list(RepositoryContext(account), buildJsonObject {}), CallMetadataReader::list) { rows -> rows.map { it.app() } }
        val attempt = synchronized(journal) { journal[account] }
        if (attempt != null && pending(account) != null && result is ClientResult.Ready) {
            result.value.find { it.sameCall(attempt.signal.expected) && it.phase == CallPhase.ENDED && it.sequence >= attempt.signal.expected.sequence }?.let { row ->
                synchronized(journal) { if(journal[account] === attempt) attempt.result = ClientResult.Ready(row) }
            }
        }
        return result
    }
    override fun pending(account: String): CallSignal? = synchronized(journal) { journal[account]?.takeIf {
        it.result == null || (it.result as? ClientResult.Failed)?.problem?.outcome == ClientOutcome.UNKNOWN
    }?.signal }
    override suspend fun signal(account: String, attempt: CallSignal): ClientResult<CallRecord> {
        if (account.isBlank() || account.length > 4096 || !Regex("[a-z0-9-]{1,128}").matches(attempt.operationId) ||
            !attempt.expected.canHangup || (attempt.decline && !attempt.expected.canDecline)) return invalid()
        val old = synchronized(journal) { journal[account] }
        if (old?.signal?.operationId == attempt.operationId) {
            if (old.signal != attempt) return invalid()
            return old.result ?: unknown()
        }
        if (pending(account) != null) return unknown()
        val current = list(account)
        if(current !is ClientResult.Ready) return current.failure()
        val row = current.value.find { it.id == attempt.expected.id } ?: return invalid()
        if (!row.sameCall(attempt.expected)) return invalid()
        if (row.phase == CallPhase.ENDED && row.sequence >= attempt.expected.sequence) return ClientResult.Ready(row)
        if (row != attempt.expected) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 18, ClientOutcome.NONE))
        val entry = Attempt(attempt, null)
        val reserved = synchronized(journal) {
            if(pending(account) != null || (journal.size >= 32 && account !in journal)) false else { journal[account] = entry; true }
        }
        if (!reserved) return unknown()
        try {
            val body = SecretBody.fields { publicText("call_id", attempt.expected.id) }
            val context = RepositoryContext(account, attempt.operationId)
            val reply = if(attempt.decline) source.reject(context, body) else source.hangup(context, body)
            val result = decode(reply, CallMetadataReader::record) {
                val actual = it.app()
                require(actual.sameCall(attempt.expected) && actual.phase == CallPhase.ENDED && actual.sequence >= attempt.expected.sequence)
                actual
            }
            synchronized(journal) { entry.result = result }
            return result
        } catch(cancelled: CancellationException) { synchronized(journal) { entry.result = unknown() }; throw cancelled }
        catch(_: Exception) { val result = unknown(); synchronized(journal) { entry.result = result }; return result }
    }
    override suspend fun audioAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CALLS, UnavailableReason.FEATURE_NOT_INSTALLED)
    override fun updates(account: String): Flow<ClientResult<Unit>> = source.updates(SubscriptionFilter(account,
        buildJsonObject { put("topics", buildJsonArray { add("*") }) })).map { update -> when(update) {
            is CoreUpdate.Events -> { update.body.close(); ClientResult.Ready(Unit) }
            is CoreUpdate.Failure -> ClientResult.Failed(update.error.appProblem())
            is CoreUpdate.Terminal -> ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
        } }
    private fun CallDisplay.app() = CallRecord(id, conversation, peer,
        when(direction) { "incoming" -> CallDirection.INCOMING; "outgoing" -> CallDirection.OUTGOING; else -> CallDirection.UNKNOWN },
        when(state) { "incoming_ringing" -> CallPhase.INCOMING_RINGING; "outgoing_ringing" -> CallPhase.OUTGOING_RINGING; "connecting" -> CallPhase.CONNECTING; "active" -> CallPhase.ACTIVE; "ended" -> CallPhase.ENDED; else -> CallPhase.UNKNOWN },
        sequence, generation, created, answered, ended, endReason, failure, peerReason)
    private fun CallRecord.sameCall(other: CallRecord) = id == other.id && conversation == other.conversation && peer == other.peer && direction == other.direction
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun unknown() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
    private fun <T> ClientResult<T>.failure(): ClientResult<Nothing> = when(this) { is ClientResult.Failed -> this; is ClientResult.Unavailable -> this; is ClientResult.Ready -> invalid() }
    private fun <T,R> decode(reply: CoreResult<SecretReply>, reader: (SecretReply) -> CoreResult<T>, transform: (T) -> R): ClientResult<R> = when(reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
        is CoreResult.Success -> when(val result = reader(reply.value)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> try { ClientResult.Ready(transform(result.value)) } catch(_: Exception) { unknown() }
        }
    }
}
