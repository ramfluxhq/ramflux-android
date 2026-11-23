// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import java.net.URI
import java.util.Locale
import org.ramflux.core.data.BuildConfig

/** The entered address is retained for review; only the explicitly approved origin is sent. */
data class DiscoveryOrigin(val entered: String, val origin: String)
object DiscoveryOriginParser {
    /**
     * The discovery origin of [input]: `https` for every host. With [localHttp] (true only in debug builds, see
     * [BuildConfig.LOCAL_NODE_HTTP]) an `http` origin is also accepted when the host is a loopback address, the
     * emulator's alias for the host machine (`10.0.2.2`) or a private-range IPv4 address, so a local test node can
     * be reached. Discovery stays authenticated by the operator key pin either way.
     */
    fun preview(input: String, localHttp: Boolean = BuildConfig.LOCAL_NODE_HTTP): DiscoveryOrigin? {
        if (input.length !in 1..2048) return null
        val value = input.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(if ("://" in value) value else "https://$value")
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (host.isNullOrEmpty() || uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
            when {
                scheme == "https" -> Unit
                scheme == "http" && localHttp && LocalNodeHosts.allows(host) -> Unit
                else -> return null
            }
            val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
            val origin = URI(scheme, null, host, port, null, null, null).toASCIIString()
            DiscoveryOrigin(input, origin)
        } catch (_: java.net.URISyntaxException) { null }
    }
}

/** Hosts a debug build may reach over plain HTTP: loopback, the emulator's host alias and private IPv4 ranges. */
internal object LocalNodeHosts {
    fun allows(host: String): Boolean {
        if (host == "localhost" || host == "[::1]" || host == "::1") return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val bytes = octets.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || (part.length > 1 && part[0] == '0')) return false
            part.toInt().takeIf { it in 0..255 } ?: return false
        }
        val (a, b) = bytes
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
