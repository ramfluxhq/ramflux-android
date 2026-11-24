// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** The claimed name in a `username.status` reply (`name`, or `username`); absent when none is claimed. */
object UsernameStatusReader {
    fun name(reply: SecretReply): String? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            (fields.text(fields.root, "name") ?: fields.text(fields.root, "username"))?.takeIf { it.isNotBlank() && it.length <= 64 }
        }
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }
}
