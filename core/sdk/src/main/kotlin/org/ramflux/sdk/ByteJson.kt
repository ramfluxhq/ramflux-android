// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded byte parser. Nodes hold offsets, never decoded secret Strings or JSON trees. */
internal class ByteJson(private val bytes: ByteArray) {
    data class Node(val start: Int, val end: Int, val kind: Int, val children: List<Node>)
    private var position = 0
    private var values = 0
    fun parse(): Node {
        require(bytes.size <= 1 shl 20)
        val node = value(0)
        space(); require(position == bytes.size)
        return node
    }
    private fun space() { while (position < bytes.size && bytes[position].toInt() in listOf(9, 10, 13, 32)) position++ }
    private fun value(depth: Int): Node {
        require(depth <= 32 && ++values <= 4096)
        space(); require(position < bytes.size)
        val start = position
        val kind = bytes[position++].toInt()
        val children = mutableListOf<Node>()
        when (kind) {
            34 -> {
                var closed = false
                while (position < bytes.size) {
                    when (bytes[position++].toInt()) {
                        34 -> { closed = true; break }
                        92 -> {
                            require(position < bytes.size)
                            if (bytes[position++].toInt() == 117) {
                                repeat(4) { require(position < bytes.size && hex(bytes[position++].toInt()) >= 0) }
                            } else require(bytes[position - 1].toInt() in listOf(34, 92, 47, 98, 102, 110, 114, 116))
                        }
                        in 0..31 -> throw IllegalArgumentException("control byte")
                    }
                }
                require(closed)
            }
            123, 91 -> {
                val end = if (kind == 123) 125 else 93
                space()
                if (position < bytes.size && bytes[position].toInt() == end) position++ else {
                    while (true) {
                        if (kind == 123) {
                            val key = value(depth + 1); require(key.kind == 34); children.add(key)
                            space(); require(position < bytes.size && bytes[position++].toInt() == 58)
                        }
                        children.add(value(depth + 1)); space(); require(position < bytes.size)
                        val next = bytes[position++].toInt()
                        if (next == end) break
                        require(next == 44)
                    }
                }
            }
            else -> {
                while (position < bytes.size && bytes[position].toInt() !in listOf(9, 10, 13, 32, 44, 93, 125)) position++
                require(position > start)
                if (kind == 110) require(literal(start, position, "null"))
                else if (kind == 116) require(literal(start, position, "true"))
                else if (kind == 102) require(literal(start, position, "false"))
                else {
                    require(kind == 45 || kind in 48..57)
                    // Scalars needed by the bridge are integer status codes; other numbers stay opaque.
                    require((start until position).all { bytes[it].toInt() in 48..57 || bytes[it].toInt() in listOf(45, 43, 46, 101, 69) })
                }
            }
        }
        return Node(start, position, kind, children)
    }
    private fun literal(start: Int, end: Int, expected: String) = end - start == expected.length && expected.indices.all { bytes[start + it].toInt() == expected[it].code }
    fun field(node: Node, name: String): Node? {
        require(node.kind == 123)
        var found: Node? = null
        for (index in node.children.indices step 2) {
            val key = chars(node.children[index])
            try {
                if (key.size == name.length && key.indices.all { key[it] == name[it] }) {
                    require(found == null) { "duplicate field" }
                    found = node.children[index + 1]
                }
            } finally { key.fill('\u0000') }
        }
        return found
    }
    fun integer(node: Node): Int {
        var value = 0
        require(node.start < node.end)
        for (index in node.start until node.end) {
            val digit = bytes[index].toInt() - 48
            require(digit in 0..9 && value <= (Int.MAX_VALUE - digit) / 10)
            value = value * 10 + digit
        }
        return value
    }
    fun copy(node: Node): ByteArray = bytes.copyOfRange(node.start, node.end)
    fun chars(node: Node): CharArray {
        require(node.kind == 34)
        val output = CharArray(node.end - node.start)
        var count = 0
        var at = node.start + 1
        try {
            while (at < node.end - 1) {
                if (bytes[at].toInt() == 92) {
                    at++
                    output[count++] = when (bytes[at++].toInt()) {
                        34 -> '"'; 92 -> '\\'; 47 -> '/'; 98 -> '\b'; 102 -> '\u000c'; 110 -> '\n'; 114 -> '\r'; 116 -> '\t'
                        117 -> { var code = 0; repeat(4) { code = code * 16 + hex(bytes[at++].toInt()).also { require(it >= 0) } }; code.toChar() }
                        else -> throw IllegalArgumentException("invalid escape")
                    }
                } else {
                    val begin = at
                    while (at < node.end - 1 && bytes[at].toInt() != 92) at++
                    val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, begin, at - begin))
                    try { while (decoded.hasRemaining()) output[count++] = decoded.get() }
                    finally { if (decoded.hasArray()) decoded.array().fill('\u0000') }
                }
            }
            return output.copyOf(count)
        } finally { output.fill('\u0000') }
    }
    private fun hex(byte: Int): Int = when (byte) { in 48..57 -> byte - 48; in 65..70 -> byte - 55; in 97..102 -> byte - 87; else -> -1 }
}
