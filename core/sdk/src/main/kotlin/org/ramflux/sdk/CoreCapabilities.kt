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
        fun matchesPolicy(body: JsonObject): Boolean {
            val manifest = body["method_policy"] as? JsonObject ?: return false
            if (manifest.keys != setOf("abi_major", "policy_version", "methods") ||
                manifest["abi_major"] != JsonPrimitive(2) || manifest["policy_version"] != JsonPrimitive(2)) return false
            val rows = manifest["methods"] as? JsonArray ?: return false
            if (rows.size != SdkMethodPolicy.methods.size) return false
            val seen = mutableSetOf<String>()
            for (entry in rows) {
                val row = entry as? JsonObject ?: return false
                if (row.keys != setOf("name", "capability", "bridge_allowed", "secret_input", "secret_output", "show_once", "key_export")) return false
                val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                val policy = SdkMethodPolicy.methods[name] ?: return false
                if (!seen.add(name) || row["capability"] != JsonPrimitive(policy.capability) ||
                    row["bridge_allowed"] != JsonPrimitive(policy.bridgeAllowed) ||
                    row["secret_input"] != JsonPrimitive(policy.secretInput) ||
                    row["secret_output"] != JsonPrimitive(policy.secretOutput) ||
                    row["show_once"] != JsonPrimitive(policy.showOnce) ||
                    row["key_export"] != JsonPrimitive(policy.keyExport)) return false
            }
            return true
        }

        fun parse(body: JsonObject, expectedDescriptorFingerprint: String): CoreCapabilities? {
            fun text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull
            if (!matchesPolicy(body)) return null
            val fingerprint = text("message_descriptor_blake3") ?: return null
            if (expectedDescriptorFingerprint.length != 64 ||
                expectedDescriptorFingerprint.any { it !in '0'..'9' && it !in 'a'..'f' } ||
                fingerprint != expectedDescriptorFingerprint || text("abi_major") != "2" ||
                text("abi_minor")?.toIntOrNull()?.let { it >= 0 } != true ||
                text("bus_protocol") != "ramflux.local_bus.v1" ||
                text("max_frame_bytes")?.toIntOrNull() != 1 shl 20 ||
                text("max_operations")?.toIntOrNull() != 64 ||
                text("max_events")?.toIntOrNull() != 64 ||
                text("max_event_bytes")?.toIntOrNull() != 4 * 1024 * 1024) return null
            val entries = body["methods"] as? JsonArray ?: return null
            if (entries.size !in 1..4096) return null
            val methods = mutableSetOf<String>()
            for (entry in entries) {
                val method = (entry as? JsonPrimitive)?.contentOrNull ?: return null
                if (method.length !in 1..128 || method.contains("itest")) return null
                if (!methods.add(method)) return null
            }
            if (methods != SdkMethodPolicy.methods.filterValues { it.bridgeAllowed }.keys) return null
            return CoreCapabilities(fingerprint, methods.toSet())
        }
    }
}
