// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Reads only the topic (`method`) of one event frame and wipes the frame; null when it has none. */
object EventTopicReader {
    fun topic(reply: SecretReply): String? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            fields.text(fields.root, "method")?.takeIf { it.length <= 128 }
        }
    } catch (_: Exception) { null }
    finally { reply.close() }
}
