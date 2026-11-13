// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Internal bounded field access. Only typed method-specific readers may opt display fields in. */
internal class DisplayFields(raw: ByteArray) {
    private val parser = ByteJson(raw)
    val root = parser.parse()
    fun field(row: ByteJson.Node, key: String) = parser.field(row, key)
    fun text(row: ByteJson.Node, key: String, required: Boolean = false): String? {
        val value = field(row, key)
        if (value == null || value.kind == 110) { require(!required); return null }
        val chars = parser.chars(value)
        return try { require(chars.size <= 65536 && (!required || chars.isNotEmpty())); chars.concatToString() }
        finally { chars.fill('\u0000') }
    }
    fun unsigned(row: ByteJson.Node, key: String): ULong {
        val value = field(row, key) ?: throw IllegalArgumentException("missing count")
        if (value.kind == 34) return parser.unsignedDecimal(value)
        require(value.kind in 48..57)
        val raw = parser.copy(value)
        return try {
            var number = 0uL
            for (byte in raw) {
                val digit = byte.toInt() - 48
                require(digit in 0..9 && number <= (ULong.MAX_VALUE - digit.toUInt()) / 10u)
                number = number * 10u + digit.toUInt()
            }
            number
        } finally { raw.fill(0) }
    }

    fun optionalSigned(row: ByteJson.Node, key: String): Long? {
        val value = field(row, key) ?: return null
        if (value.kind == 110) return null
        require(value.kind == 45 || value.kind in 48..57)
        val raw = parser.copy(value)
        return try {
            val negative = raw.firstOrNull()?.toInt() == 45
            val start = if (negative) 1 else 0
            require(raw.size > start)
            val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
            var number = 0L
            for (index in start until raw.size) {
                val digit = raw[index].toInt() - 48
                require(digit in 0..9 && number >= limit / 10)
                number *= 10
                require(number >= limit + digit)
                number -= digit
            }
            if (negative) number else -number
        } finally { raw.fill(0) }
    }
    fun texts(row: ByteJson.Node, key: String, bound: Int): List<String> {
        val array = requireNotNull(field(row, key))
        require(array.kind == 91 && array.children.size in 1..bound)
        return array.children.map { node ->
            val chars = parser.chars(node)
            try { require(chars.size in 1..128); chars.concatToString() } finally { chars.fill('\u0000') }
        }
    }
    fun boolean(row: ByteJson.Node, key: String): Boolean {
        val value = field(row, key) ?: throw IllegalArgumentException("missing flag")
        require(value.kind in listOf(116, 102))
        return value.kind == 116
    }
    fun rows(key: String, bound: Int = 100): List<ByteJson.Node> {
        val value = field(root, key) ?: throw IllegalArgumentException("missing rows")
        require(value.kind == 91 && value.children.size <= bound)
        return value.children
    }
}
