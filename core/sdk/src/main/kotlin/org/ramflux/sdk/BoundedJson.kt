// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reject excessive nesting and value counts before entering the recursive JSON parser. */
internal object BoundedJson {
    private const val MAX_BYTES = 1 shl 20
    private const val MAX_DEPTH = 32
    private const val MAX_VALUES = 4096

    fun parseObject(bytes: ByteArray): JsonObject? {
        require(bytes.size <= MAX_BYTES)
        var quoted = false
        var escaped = false
        var depth = 0
        var delimiters = 0
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false else when (char) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; delimiters++ }
                    '}', ']' -> depth--
                    ',', ':' -> delimiters++
                }
                require(depth in 0..MAX_DEPTH && delimiters <= MAX_VALUES)
            }
        }
        require(!quoted && depth == 0)
        return Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    }

    fun accepts(element: JsonElement): Boolean {
        val stack = ArrayDeque<Pair<JsonElement, Int>>()
        stack.add(element to 0)
        var values = 0
        var characters = 0L
        while (stack.isNotEmpty()) {
            val (current, depth) = stack.removeLast()
            if (++values > MAX_VALUES || depth > MAX_DEPTH) return false
            when (current) {
                is JsonObject -> {
                    if (current.size > MAX_VALUES - values) return false
                    for ((key, value) in current) {
                        characters += key.length
                        stack.add(value to depth + 1)
                    }
                }
                is JsonArray -> {
                    if (current.size > MAX_VALUES - values) return false
                    current.forEach { stack.add(it to depth + 1) }
                }
                is JsonPrimitive -> characters += current.content.length
            }
            if (characters > MAX_BYTES) return false
        }
        return true
    }
}
