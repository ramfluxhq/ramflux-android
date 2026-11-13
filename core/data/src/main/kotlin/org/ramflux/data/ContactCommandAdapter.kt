// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.CancellationException
import org.ramflux.sdk.*

/** Account-scoped, process-lifetime journal retains uncertain identities across feature recreation. */
internal class ContactCommandAdapter(transport: RepositoryTransport) {
    private val source = DefaultContactsRepository(transport)
    private val journal = mutableMapOf<Pair<String, String>, Pair<ContactCommand, ContactAttemptPhase>>()
    fun attempts(account: String): List<ContactAttempt> = synchronized(journal) {
        journal.values.filter { it.first.account == account }.map { (command, phase) ->
            ContactAttempt(command.operationId, command.operation, command.target, phase)
        }
    }
    suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> {
        if (!confirmed) return ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))
        if (command.account.isBlank() || command.account.length > 4096 || command.target.isBlank() || command.target.length > 4096 ||
            !Regex("[a-z0-9-]{1,128}").matches(command.operationId)) return failed(1, ClientOutcome.NONE)
        if (command.operation !in setOf(ContactOperation.ADD, ContactOperation.ACCEPT, ContactOperation.DECLINE, ContactOperation.BLOCK)) {
            return ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
        }
        val key = command.account to command.operationId
        val reserved = synchronized(journal) {
            if (journal.size >= 64) journal.entries.firstOrNull { it.value.second != ContactAttemptPhase.UNKNOWN }?.key?.let { journal.remove(it) }
            val existing = journal[key]
            when {
                existing != null -> false
                journal.values.any { it.first.account == command.account && it.first.target == command.target && it.second == ContactAttemptPhase.UNKNOWN } -> false
                journal.size >= 64 -> false
                else -> { journal[key] = command to ContactAttemptPhase.UNKNOWN; true }
            }
        }
        if (!reserved) return failed(18, ClientOutcome.UNKNOWN)
        val context = RepositoryContext(command.account, command.operationId)
        val card = command.operation == ContactOperation.ADD && (command.target.startsWith("ramflux-card:") || command.target.startsWith("ramflux://contact/"))
        val body = SecretBody.fields { publicText(if (command.operation == ContactOperation.ADD) { if (card) "card" else "principal_id" } else "request_id", command.target) }
        try {
            val result = when (command.operation) {
                ContactOperation.ADD -> source.add(context, body)
                ContactOperation.ACCEPT -> source.requestAccept(context, body)
                ContactOperation.DECLINE -> source.requestDecline(context, body)
                ContactOperation.BLOCK -> source.requestBlock(context, body)
                else -> { body.close(); return failed(1, ClientOutcome.NONE) }
            }
            val decoded = when (result) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> if (command.operation == ContactOperation.ADD) ContactCompletionMetadataReader.added(result.value, if (card) null else command.target)
                else ContactCompletionMetadataReader.decision(result.value, command.target, command.principal, when(command.operation) {
                    ContactOperation.ACCEPT -> "accepted"
                    ContactOperation.DECLINE -> "declined"
                    else -> "blocked"
                })
            }
            val outcome = when (decoded) {
                is CoreResult.Success -> ClientResult.Ready(Unit)
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
            }
            synchronized(journal) { journal[key] = command to when(outcome) {
                is ClientResult.Ready -> ContactAttemptPhase.CONFIRMED
                is ClientResult.Unavailable -> ContactAttemptPhase.FAILED
                is ClientResult.Failed -> when(outcome.problem.outcome) {
                    ClientOutcome.UNKNOWN -> ContactAttemptPhase.UNKNOWN
                    ClientOutcome.REFUSED -> ContactAttemptPhase.REFUSED
                    ClientOutcome.NONE -> ContactAttemptPhase.FAILED
                }
            } }
            return outcome
        } catch (cancelled: CancellationException) {
            synchronized(journal) { journal[key] = command to ContactAttemptPhase.UNKNOWN }
            throw cancelled
        } finally { body.close() }
    }
    private fun failed(code: Int, outcome: ClientOutcome) = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, code, outcome))
}
