// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import java.net.URI
import java.util.Locale

/** The entered address is retained for review; only the explicitly approved origin is sent. */
data class DiscoveryOrigin(val entered: String, val origin: String)
object DiscoveryOriginParser {
    fun preview(input: String): DiscoveryOrigin? {
        if (input.length !in 1..2048) return null
        val value = input.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(if ("://" in value) value else "https://$value")
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                uri.port !in -1..65535 || uri.port == 0) return null
            val port = if (uri.port == 443) -1 else uri.port
            val origin = URI("https", null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
            DiscoveryOrigin(input, origin)
        } catch (_: java.net.URISyntaxException) { null }
    }
}
