// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Negotiated against the host's build-time descriptor, never the core's self-reported value alone. */
data class CoreCapabilities(val descriptorFingerprint: String, val methods: Set<String>) {
    companion object {
        fun parse(body: JsonObject, expectedDescriptorFingerprint: String): CoreCapabilities? {
            fun text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull
            val fingerprint = text("message_descriptor_blake3") ?: return null
            if (expectedDescriptorFingerprint.length != 64 ||
                expectedDescriptorFingerprint.any { it !in '0'..'9' && it !in 'a'..'f' } ||
                fingerprint != expectedDescriptorFingerprint || text("abi_major") != "2" ||
                text("abi_minor")?.toIntOrNull()?.let { it >= 0 } != true ||
                text("bus_protocol") != "ramflux.local_bus.v1" ||
                text("max_frame_bytes")?.toIntOrNull() != 1 shl 20) return null
            val entries = body["methods"] as? JsonArray ?: return null
            if (entries.size !in 1..4096) return null
            val methods = mutableSetOf<String>()
            for (entry in entries) {
                val method = (entry as? JsonPrimitive)?.contentOrNull ?: return null
                if (method.length !in 1..128 || method.contains("itest")) return null
                methods.add(method)
            }
            return CoreCapabilities(fingerprint, methods.toSet())
        }
    }
}
