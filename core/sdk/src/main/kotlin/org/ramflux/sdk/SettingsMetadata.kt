// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class DeviceMetadata(val id: String, val local: Boolean, val epoch: ULong)
data class SyncMetadata(val secureSessions: String, val sync: String?, val node: String?, val lastRun: Long?)
object SettingsMetadataReader {
    fun devices(reply: SecretReply): CoreResult<List<DeviceMetadata>> = consume(reply) { fields ->
        fields.rows("devices", 256).map { row -> DeviceMetadata(requireNotNull(fields.text(row, "device_id", true)),
            fields.boolean(row, "is_local"), fields.unsigned(row, "device_epoch")) }.also { rows ->
            require(rows.map { it.id }.distinct().size == rows.size && rows.count { it.local } <= 1)
        }
    }
    fun sync(reply: SecretReply): CoreResult<SyncMetadata> = consume(reply) { fields ->
        fun sample(key: String): String? {
            val sample = fields.field(fields.root, key) ?: throw IllegalArgumentException("missing sample")
            if (sample.kind == 110) return null
            val body = fields.field(sample, "body") ?: throw IllegalArgumentException("missing sample body")
            return fields.text(body, "state", true)
        }
        require(fields.field(fields.root, "last_run") != null)
        SyncMetadata(requireNotNull(fields.text(fields.root, "secure_sessions", true)), sample("sync_status"), sample("node_health"),
            fields.optionalSigned(fields.root, "last_run"))
    }
    private fun <T> consume(reply: SecretReply, block: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(block(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
