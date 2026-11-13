// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Only account.list display metadata is allowed into immutable display values. Never a ceremony. */
data class AccountListEntry(val id: String, val label: String?, val state: String)
data class AccountListMetadata(val accounts: List<AccountListEntry>, val activeAccountId: String?)

object AccountListMetadataReader {
    fun read(reply: SecretReply): CoreResult<AccountListMetadata> = try {
        reply.useOnce { raw ->
            val parser = ByteJson(raw)
            val root = parser.parse()
            fun text(row: ByteJson.Node, key: String, required: Boolean = false): String? {
                val node = parser.field(row, key)
                if (node == null || node.kind == 110) {
                    require(!required)
                    return null
                }
                val chars = parser.chars(node)
                return try {
                    require(chars.size <= 4096 && (!required || chars.isNotEmpty()))
                    chars.concatToString()
                } finally { chars.fill('\u0000') }
            }
            val rows = parser.field(root, "account_details") ?: throw IllegalArgumentException("missing account metadata")
            require(rows.kind == 91 && rows.children.size <= 256)
            val entries = rows.children.map { row ->
                AccountListEntry(requireNotNull(text(row, "id", true)), text(row, "label"), requireNotNull(text(row, "state", true)))
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            CoreResult.Success(AccountListMetadata(entries, text(root, "active_account_id")))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
