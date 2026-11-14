// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Owns the supplied allocation. A single borrowed access always wipes it, including on failure. */
class SecretBytes private constructor(private var bytes: ByteArray?) : Closeable {
    @Synchronized fun <T> useOnce(block: (ByteArray) -> T): T {
        val owned = bytes ?: throw IllegalStateException("secret already consumed")
        bytes = null
        return try { block(owned) } finally { owned.fill(0) }
    }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null }
    override fun toString(): String = "SecretBytes(<redacted>)"
    companion object {
        /** Ownership transfers: the caller must not retain or mutate the array afterwards. */
        fun take(bytes: ByteArray): SecretBytes = SecretBytes(bytes)
    }
}

/** A JSON body supplied as owned UTF-8 bytes, never an immutable secret String/JsonPrimitive. */
class SecretBody private constructor(internal val storage: SecretBytes) : Closeable {
    override fun close() = storage.close()
    override fun toString() = "SecretBody(<redacted>)"
    companion object {
        fun takeJson(bytes: ByteArray): SecretBody = SecretBody(SecretBytes.take(bytes))
        /** Only for SDK methods whose generated policy says secret_input=false. */
        fun publicJson(method: String, body: kotlinx.serialization.json.JsonObject): SecretBody {
            require(SdkMethodPolicy.methods[method]?.secretInput == false)
            require(BoundedJson.accepts(body))
            return takeJson(body.toString().toByteArray())
        }
        fun fields(block: SecretFields.() -> Unit): SecretBody {
            val fields = SecretFields()
            return try { fields.block(); takeJson(fields.finish()) } finally { fields.close() }
        }
    }
}

/** Bounded JSON writer; secret characters are escaped/encoded directly into a wipeable buffer. */
class SecretFields internal constructor() : Closeable {
    private val output = ByteArray(1 shl 20)
    private var position = 1
    private var fields = 0
    init { output[0] = 123 }
    private fun put(value: Int) { require(position < output.size); output[position++] = value.toByte() }
    fun chars(name: String, value: CharArray) {
        try {
            if (fields++ > 0) put(44)
            writeChars(name.toCharArray()); put(58)
            writeChars(value)
        } finally { value.fill('\u0000') }
    }
    fun publicText(name: String, value: String) = chars(name, value.toCharArray())
    fun publicUnsigned(name: String, value: ULong) {
        if (fields++ > 0) put(44)
        writeChars(name.toCharArray()); put(58)
        value.toString().forEach { put(it.code) }
    }
    private fun writeChars(value: CharArray) {
        put(34)
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        val encoded = ByteBuffer.allocate((value.size.toLong() * 4).coerceAtMost(output.size.toLong()).toInt())
        try {
            val result = encoder.encode(CharBuffer.wrap(value), encoded, true)
            require(!result.isOverflow)
            result.throwExceptionIfError()
            encoded.flip()
            while (encoded.hasRemaining()) {
                val byte = encoded.get().toInt() and 255
                when (byte) {
                    34, 92 -> { put(92); put(byte) }
                    in 0..31 -> {
                        put(92); put(117); put(48); put(48)
                        put("0123456789abcdef"[byte shr 4].code); put("0123456789abcdef"[byte and 15].code)
                    }
                    else -> put(byte)
                }
            }
        } finally { encoded.array().fill(0) }
        put(34)
    }
    internal fun finish(): ByteArray { put(125); return output.copyOf(position) }
    override fun close() = output.fill(0)
}

private fun java.nio.charset.CoderResult.throwExceptionIfError() { if (isError) throwException() }

class SecretReply internal constructor(private val storage: SecretBytes) : Closeable {
    fun <T> useOnce(block: (ByteArray) -> T): T = storage.useOnce(block)
    /** One disclosure. The host draws the characters and must not make an immutable String. */
    fun <T> consumeChars(field: String, block: (CharArray) -> T): T = storage.useOnce { raw ->
        val parser = ByteJson(raw)
        val root = parser.parse()
        val node = parser.field(root, field) ?: throw IllegalArgumentException("missing secret field")
        val chars = parser.chars(node)
        try { block(chars) } finally { chars.fill('\u0000') }
    }
    override fun close() = storage.close()
    override fun toString() = "SecretReply(<redacted>)"
}

class SecretRequest(
    val method: String,
    val sdkApi: String,
    val accountId: String?,
    val operationId: String?,
    val body: SecretBody,
    val deadlineUnixMs: Long = 0,
) {
    override fun toString() = "SecretRequest(method=$method, body=<redacted>)"
}
