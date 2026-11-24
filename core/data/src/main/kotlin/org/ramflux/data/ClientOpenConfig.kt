// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.CoreOpenConfig
import org.ramflux.sdk.DiagnosticConfig
import org.ramflux.sdk.UsernameDirectoryPin

/** The username directory trust pin the host build provisions; mapped to the SDK pin inside Data. */
data class ClientDirectoryPin(val url: String, val id: String, val publicKey: String, val generation: Long)

/** The SDK diagnostic log the host asks for; mapped to the SDK type inside Data. */
data class ClientDiagnostics(val level: String, val buildClass: String)

/** Host-provisioned open-config fields. Modules above Data name only these types, never the SDK's. */
data class ClientOpenConfig(val usernameDirectory: ClientDirectoryPin? = null, val diagnostics: ClientDiagnostics? = null) {
    internal fun toCore(): CoreOpenConfig = CoreOpenConfig(
        usernameDirectory = usernameDirectory?.let { UsernameDirectoryPin(it.url, it.id, it.publicKey, it.generation) },
        diagnostics = diagnostics?.let { DiagnosticConfig(level = it.level, buildClass = it.buildClass) },
    )
}
