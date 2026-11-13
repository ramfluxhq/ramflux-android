// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.net.URI

/** Local syntax validation only; identity verification and selection belong to the SDK. */
sealed interface NodeAddress {
    data class Valid(val value: String) : NodeAddress
    data object Invalid : NodeAddress

    companion object {
        fun parse(input: String): NodeAddress {
            if (input.length !in 1..2048 || input.any { it.isWhitespace() || it.isISOControl() }) return Invalid
            return try {
                val uri = URI(if ("://" in input) input else "https://$input")
                if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                    uri.rawFragment != null || uri.rawQuery != null || uri.port !in -1..65535 || uri.port == 0) Invalid
                else Valid(uri.toASCIIString())
            } catch (_: java.net.URISyntaxException) { Invalid }
        }
    }
}
