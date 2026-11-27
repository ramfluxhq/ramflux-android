// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** A `username.status` reply (or a submit reply): the state, the bound name when one is held, and the directory's reason. */
data class UsernameStatus(val state: String, val name: String?, val reason: String?)

/** Reads the username fields of a status reply. The claimed name is `binding.name`; a top-level `name` is accepted too. */
object UsernameStatusReader {
    private val STATES = setOf("empty", "pending", "committed", "aborted")

    /** The claimed name; absent when none is claimed. */
    fun name(reply: SecretReply): String? = read(reply)?.name

    /** The status of a claim or a poll; null when the reply names no known state. */
    fun status(reply: SecretReply): UsernameStatus? = read(reply)

    private fun read(reply: SecretReply): UsernameStatus? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val root = fields.root
            val state = optionalText(fields, root, "state")?.takeIf { it in STATES }
            if (state == null) null else {
                val binding = fields.field(root, "binding")?.takeIf { it.kind == 123 }
                val name = (binding?.let { optionalText(fields, it, "name") } ?: optionalText(fields, root, "name"))
                    ?.takeIf { it.isNotBlank() && it.length <= 64 }
                UsernameStatus(state, name, optionalText(fields, root, "reason_code") ?: optionalText(fields, root, "reason"))
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }

    private fun optionalText(fields: DisplayFields, row: ByteJson.Node, key: String): String? = try {
        fields.text(row, key)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** What the directory publishes for an `@name` (`username.resolve`): the display name and bio when the account set them. */
data class UsernameMatch(val name: String, val displayName: String?, val bio: String?)

object UsernameMatchReader {
    /** The match, or null when the reply does not name the username. */
    fun read(reply: SecretReply): UsernameMatch? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val root = fields.root
            val name = optionalText(fields, root, "name")?.takeIf { it.isNotBlank() && it.length <= 64 }
            val profile = fields.field(root, "profile")?.takeIf { it.kind == 123 }
            val body = profile?.let { fields.field(it, "body") }?.takeIf { it.kind == 123 }
            if (name == null) null else UsernameMatch(
                name,
                body?.let { optionalText(fields, it, "display_name") }?.takeIf { it.isNotBlank() },
                body?.let { optionalText(fields, it, "bio") }?.takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }

    private fun optionalText(fields: DisplayFields, row: ByteJson.Node, key: String): String? = try {
        fields.text(row, key)
    } catch (_: IllegalArgumentException) {
        null
    }
}
