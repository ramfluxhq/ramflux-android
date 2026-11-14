// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

enum class PermissionDisplayKind { GRANT, APPROVAL, RECEIPT }
data class PermissionDisplay(val id: String, val reference: String, val kind: PermissionDisplayKind, val server: String?, val tool: String?,
    val status: String?, val expires: Long, val scope: String?, val revoked: Boolean?, val fullDelegation: Boolean?,
    val capabilities: List<String>, val registryHash: String?, val manifestHash: String?, val argumentsHash: String?,
    val decision: String?, val grant: String?, val conversation: String?, val task: String?, val step: String?,
    val openingRevision: ULong?, val requester: String?, val assignee: String?, val executor: String?, val delivery: String?, val reason: String?) {
    override fun toString() = "PermissionDisplay(<redacted>)"
}
object PermissionMetadataReader {
    fun read(reply: SecretReply): CoreResult<List<PermissionDisplay>> = try {
        reply.useOnce { raw ->
            val f = DisplayFields(raw)
            fun text(row: ByteJson.Node, key: String) = requireNotNull(f.text(row, key, true))
            fun expiry(row: ByteJson.Node) = requireNotNull(f.optionalSigned(row, "expires_at")).also { require(it in 0L..253402300799L) }
            val grants = f.rows("grants", 512).map { row ->
                val state = requireNotNull(f.field(row, "state")); val ref = text(row, "grant_id")
                val signing = f.field(row, "signing_body")?.takeIf { it.kind != 110 }
                PermissionDisplay("grant:" + ref, ref, PermissionDisplayKind.GRANT, text(state, "server_id"), text(state, "tool_name"),
                    null, expiry(state), f.text(state, "tool_scope"), f.boolean(state, "revoked"), f.boolean(state, "full_delegation"),
                    f.texts(state, "allowed_capabilities", 128, minimum = 0, maximum = 4096), text(state, "registry_hash"), text(state, "tool_manifest_set_hash"),
                    signing?.let { f.text(it, "arguments_hash") }, null, null, null, null, null, null, null, null, null, null, null)
            }
            val approvals = f.rows("approvals", 512).map { row ->
                val ref = text(row, "approval_id"); val delivery = f.field(row, "delivery_state")?.takeIf { it.kind != 110 }
                if (delivery != null) {
                    val binding = requireNotNull(f.field(row, "task_binding"))
                    PermissionDisplay("receipt:" + ref, ref, PermissionDisplayKind.RECEIPT, null, null, text(row, "status"), expiry(row), null, null, null,
                        emptyList(), null, null, null, text(row, "decision_id"), text(row, "grant_id"), text(binding, "conversation_id"), text(binding, "task_id"), text(binding, "step_id"),
                        f.unsigned(binding, "opening_revision"), text(binding, "requester"), text(binding, "assignee"), text(binding, "executor_device_id"), text(row, "delivery_state"), f.text(row, "reason_code"))
                } else {
                    val details = f.field(row, "details")?.takeIf { it.kind != 110 }
                    PermissionDisplay("approval:" + ref, ref, PermissionDisplayKind.APPROVAL, text(row, "server_id"), text(row, "tool_name"), text(row, "status"), expiry(row),
                        f.text(row, "tool_scope"), null, null, listOf(text(row, "capability")), null, null, details?.let { f.text(it, "arguments_hash") },
                        null, null, null, null, null, null, null, null, null, null, null)
                }
            }
            val rows = grants + approvals
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(rows)
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
