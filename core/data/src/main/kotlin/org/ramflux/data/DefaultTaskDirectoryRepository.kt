// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

internal class DefaultTaskDirectoryRepository(transport: RepositoryTransport) : TaskDirectoryRepository {
    private val tasks = DefaultTasksRepository(transport)
    private val access = DefaultPermissionsRepository(transport)
    private val events = DefaultConversationsRepository(transport)
    private data class Entry(val attempt: TaskAttempt, var result: ClientResult<TaskOutcome>?, var running: Boolean)
    private val journal = mutableMapOf<String, Entry>()
    override fun pending(account: String): TaskAttempt? = synchronized(journal) { journal[account]?.takeIf { unresolved(it.result) }?.attempt }
    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<TaskPage> {
        if (!valid(account) || !valid(conversation) || cursor != null && cursor.length > 8192) return invalid()
        return decode(tasks.list(RepositoryContext(account), buildJsonObject {
            put("conversation_id", conversation); put("limit", 50); put("cursor", cursor ?: "")
        }), TaskMetadataReader::page) {
            require(it.rows.all { row -> row.conversation == conversation })
            TaskPage(it.rows.map { row -> row.app() }, it.cursor)
        }
    }
    override suspend fun detail(account: String, conversation: String, task: String): ClientResult<TaskRecord> {
        if (!valid(account) || !valid(conversation) || !valid(task)) return invalid()
        return decode(tasks.get(RepositoryContext(account), buildJsonObject { put("conversation_id", conversation); put("task_id", task) }), TaskMetadataReader::record) {
            require(it.id == task && it.conversation == conversation); it.app()
        }
    }
    override suspend fun permissions(account: String): ClientResult<List<TaskPermission>> {
        if (!valid(account)) return invalid()
        return decode(access.grantList(RepositoryContext(account), buildJsonObject {}), PermissionMetadataReader::read) {
            it.map { row -> TaskPermission(row.id, row.reference, when(row.kind) {
                PermissionDisplayKind.GRANT -> TaskPermissionKind.GRANT; PermissionDisplayKind.APPROVAL -> TaskPermissionKind.APPROVAL; PermissionDisplayKind.RECEIPT -> TaskPermissionKind.RECEIPT
            }, row.server, row.tool, row.status, row.expires, row.scope, row.revoked, row.fullDelegation, row.capabilities.toList(), row.registryHash, row.manifestHash, row.argumentsHash,
                row.decision, row.grant, row.conversation, row.task, row.step, row.openingRevision, row.requester, row.assignee, row.executor, row.delivery, row.reason) }
        }
    }
    override suspend fun mutate(account: String, attempt: TaskAttempt): ClientResult<TaskOutcome> {
        if (!valid(account) || !Regex("[a-z0-9-]{1,128}").matches(attempt.operationId)) return invalid()
        val old = synchronized(journal) { journal[account] }
        if (old != null && unresolved(old.result)) {
            if (old.attempt != attempt || old.running) return unknown()
        } else if (old?.attempt?.operationId == attempt.operationId) {
            return if (old.attempt == attempt) old.result ?: unknown() else invalid()
        }
        val entry = old?.takeIf { unresolved(it.result) } ?: Entry(attempt, null, false)
        val reserved = synchronized(journal) {
            if (journal[account] !== old || entry.running || journal.size >= 32 && account !in journal) false
            else { entry.running = true; journal[account] = entry; true }
        }
        if (!reserved) return unknown()
        val reconciling = old != null && unresolved(old.result)
        var submitted = false
        try {
            val expected = attempt.expected
            // Only an exact retained attempt may reach the SDK's original-message receipt path.
            // Rechecking the advanced task first would prevent an applied-but-unacknowledged intent from reconciling.
            if (!reconciling) {
                val fresh = detail(account, expected.conversation, expected.id)
                val preflight = when {
                    fresh !is ClientResult.Ready -> when (val failure = fresh.failure()) {
                        is ClientResult.Failed -> failure.copy(problem = failure.problem.copy(outcome = ClientOutcome.NONE))
                        else -> failure
                    }
                    fresh.value != expected -> invalid()
                    attempt.decision == TaskDecision.CANCEL && !expected.cancellable -> invalid()
                    attempt.decision != TaskDecision.CANCEL && !expected.decidable -> invalid()
                    else -> null
                }
                if (preflight != null) {
                    synchronized(journal) { entry.result = preflight }; return preflight
                }
            }
            val body = SecretBody.fields {
                publicText("conversation_id", expected.conversation); publicText("task_id", expected.id); publicText("message_id", attempt.operationId)
                publicUnsigned(if (attempt.decision == TaskDecision.CANCEL) "base_revision" else "version",
                    if (attempt.decision == TaskDecision.CANCEL) expected.version else expected.version + 1u)
            }
            submitted = true
            val context = RepositoryContext(account, attempt.operationId)
            val reply = when(attempt.decision) { TaskDecision.ACCEPT -> tasks.accept(context, body); TaskDecision.DECLINE -> tasks.decline(context, body); TaskDecision.CANCEL -> tasks.cancel(context, body) }
            val decoded = decode(reply, TaskMetadataReader::outcome) {
                require(it.id == expected.id); TaskOutcome(it.id, it.pending, it.accepted)
            }
            val result = if (decoded is ClientResult.Ready && !decoded.value.accepted && !decoded.value.pending)
                ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED)) else decoded
            val retained = if (reconciling && result !is ClientResult.Ready) unknown() else result
            synchronized(journal) { entry.result = retained }; return retained
        } catch(cancelled: CancellationException) {
            synchronized(journal) { entry.result = if (submitted || reconciling) unknown() else invalid() }; throw cancelled
        } catch(_: Exception) { val result = if (submitted || reconciling) unknown() else invalid(); synchronized(journal) { entry.result = result }; return result }
        finally { synchronized(journal) { entry.running = false } }
    }
    override suspend fun contextualAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.TASKS, UnavailableReason.FEATURE_NOT_INSTALLED)
    override fun updates(account: String): Flow<ClientResult<Unit>> = events.updates(SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { add("*") }) })).map { update -> when(update) {
        is CoreUpdate.Events -> { update.body.close(); ClientResult.Ready(Unit) }
        is CoreUpdate.Failure -> ClientResult.Failed(update.error.appProblem())
        is CoreUpdate.Terminal -> ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
    } }
    private fun TaskDisplay.app() = TaskRecord(id, conversation, version, title, state, requester, assignee, steps.map { TaskStep(it.id, it.title, it.state, it.intervention) }, result, pending, stalled)
    private fun valid(value: String) = value.isNotBlank() && value.length <= 4096
    private fun unresolved(result: ClientResult<TaskOutcome>?) = result == null || (result is ClientResult.Ready && result.value.pending) ||
        (result is ClientResult.Failed && result.problem.outcome == ClientOutcome.UNKNOWN)
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 18, ClientOutcome.NONE))
    private fun unknown() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
    private fun <T> ClientResult<T>.failure(): ClientResult<Nothing> = when(this) { is ClientResult.Failed -> this; is ClientResult.Unavailable -> this; is ClientResult.Ready -> invalid() }
    private fun <T,R> decode(reply: CoreResult<SecretReply>, reader: (SecretReply) -> CoreResult<T>, transform: (T) -> R): ClientResult<R> = when(reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
        is CoreResult.Success -> when(val result = reader(reply.value)) {
            is CoreResult.Failure -> unknown()
            is CoreResult.Success -> try { ClientResult.Ready(transform(result.value)) } catch(_: Exception) { unknown() }
        }
    }
}
