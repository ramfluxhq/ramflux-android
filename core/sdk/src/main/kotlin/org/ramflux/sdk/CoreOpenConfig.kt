// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The username directory trust pin of the open config (c-abi-v2.md, "Phone provisioning and process diagnostics"):
 * the canonical HTTPS origin, the directory id, its base64url Ed25519 key and the positive trust generation.
 * It is provisioned by the host build, never learned from a reply; native clients read no environment variable.
 */
data class UsernameDirectoryPin(val url: String, val id: String, val publicKey: String, val generation: Long) {
    init { require(url.isNotBlank() && id.isNotBlank() && publicKey.isNotBlank() && generation > 0) }
}

/**
 * The SDK's rotated NDJSON diagnostic log (`<data root>/diag/`). The SDK refuses free text and lowers
 * `debug`/`trace` and `realrun` unless the library carries its real-run feature; the host only asks.
 */
data class DiagnosticConfig(val level: String, val buildClass: String, val process: String = "app")

/** Optional open-config fields; an absent pin leaves usernames refused with `directory_unavailable`. */
data class CoreOpenConfig(val usernameDirectory: UsernameDirectoryPin? = null, val diagnostics: DiagnosticConfig? = null) {
    internal fun writeTo(builder: JsonObjectBuilder) {
        usernameDirectory?.let { pin ->
            builder.put("username_directory", buildJsonObject {
                put("url", pin.url); put("id", pin.id); put("public_key", pin.publicKey); put("generation", pin.generation)
            })
        }
        diagnostics?.let { diag ->
            builder.put("diag", buildJsonObject {
                put("level", diag.level); put("process", diag.process); put("build_class", diag.buildClass)
            })
        }
    }
}
