// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class TaskStepDisplay(val id: String, val title: String, val state: String, val intervention: String?)
data class TaskDisplay(val id: String, val conversation: String, val version: ULong, val title: String, val state: String,
    val requester: String?, val assignee: String, val steps: List<TaskStepDisplay>, val result: String?, val pending: Boolean, val stalled: Boolean) {
    override fun toString() = "TaskDisplay(<redacted>)"
}
data class TaskPageDisplay(val rows: List<TaskDisplay>, val cursor: String?)
data class TaskOutcomeDisplay(val id: String, val accepted: Boolean, val pending: Boolean)
object TaskMetadataReader {
    fun page(reply: SecretReply) = decode(reply) { f ->
        val rows = f.rows("tasks", 100).map { record(f, it) }
        require(rows.map { it.id }.distinct().size == rows.size)
        TaskPageDisplay(rows, f.text(f.root, "next_cursor")?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 8192) })
    }
    fun record(reply: SecretReply) = decode(reply) { record(it, it.root) }
    fun outcome(reply: SecretReply) = decode(reply) { f ->
        TaskOutcomeDisplay(text(f, f.root, "task_id"), f.boolean(f.root, "accepted"), f.boolean(f.root, "pending"))
    }
    private fun record(f: DisplayFields, row: ByteJson.Node): TaskDisplay {
        val steps = f.rowsAt(row, "steps", 256).map { step ->
            val needs = f.field(step, "needs_me")?.takeIf { it.kind != 110 }
            TaskStepDisplay(text(f, step, "id"), text(f, step, "title"), text(f, step, "state"), needs?.let { f.text(it, "kind") })
        }
        require(steps.map { it.id }.distinct().size == steps.size)
        return TaskDisplay(text(f, row, "task_id"), text(f, row, "conversation_id"), f.unsigned(row, "version"),
            text(f, row, "title"), text(f, row, "state"), f.text(row, "requester"), text(f, row, "assignee"), steps,
            f.text(row, "result_summary"), f.field(row, "pending_resolution")?.kind?.let { it != 110 } ?: false,
            f.field(row, "stalled_by_assignee")?.let { f.boolean(row, "stalled_by_assignee") } ?: false)
    }
    private fun text(f: DisplayFields, row: ByteJson.Node, key: String) = requireNotNull(f.text(row, key, true))
    private fun <T> decode(reply: SecretReply, body: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(body(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
