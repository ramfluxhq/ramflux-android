// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ReplyCodec {
    fun submitRefusal(reply: NativeReply, operationId: String? = null): CoreResult.Failure = try {
        CoreResult.Failure(error(if (reply.code == 0) 16 else reply.code, Effect.NONE), operationId)
    } finally { reply.body.fill(0) }

    private fun error(code: Int, effect: Effect): CoreError = when (code) {
        1 -> CoreError.InvalidArgument(effect)
        3 -> CoreError.UnsupportedVersion(effect)
        12 -> CoreError.PermissionDenied(effect)
        23 -> CoreError.SchemaResetRequired(effect)
        else -> CoreError.Native(code, effect)
    }

    fun decodeSecret(reply: NativeReply, operationId: String?): CoreResult<SecretReply> = try {
        if (reply.code != 0) CoreResult.Failure(error(reply.code, Effect.UNKNOWN), operationId)
        else {
            val parser = ByteJson(reply.body)
            val envelope = parser.parse()
            val code = parser.integer(parser.field(envelope, "code") ?: throw IllegalArgumentException("missing status"))
            if (code == 0) {
                val response = parser.field(envelope, "response") ?: throw IllegalArgumentException("missing response")
                val body = parser.field(response, "body") ?: throw IllegalArgumentException("missing body")
                CoreResult.Success(SecretReply(SecretBytes.take(parser.copy(body))))
            } else {
                val effects = parser.field(envelope, "effects") ?: parser.field(envelope, "effect")
                val effect = effects?.let { node ->
                    val chars = parser.chars(node)
                    try { when {
                        chars.contentEquals(charArrayOf('n', 'o', 'n', 'e')) -> Effect.NONE
                        chars.contentEquals(charArrayOf('r', 'e', 'f', 'u', 's', 'e', 'd')) -> Effect.REFUSED
                        else -> Effect.UNKNOWN
                    } } finally { chars.fill('\u0000') }
                } ?: Effect.UNKNOWN
                CoreResult.Failure(error(code, effect), operationId)
            }
        }
    } catch (_: Exception) {
        CoreResult.Failure(CoreError.InvalidResponse(), operationId)
    } finally { reply.body.fill(0) }

    fun decodeEvent(reply: NativeReply, accountId: String?, generation: ULong): CoreResult<SecretReply?> = try {
        if (reply.code != 0) CoreResult.Failure(error(reply.code, Effect.UNKNOWN))
        else {
            val parser = ByteJson(reply.body)
            val frame = parser.parse()
            val token = parser.field(frame, "subscription_generation") ?: throw IllegalArgumentException("missing generation")
            val current = parser.unsignedDecimal(token)
            val account = parser.field(frame, "account_id")
            val matches = if (current == 0uL || current != generation) false else if (accountId == null) true else if (account?.kind != 34) false else {
                val chars = parser.chars(account)
                try { chars.size == accountId.length && chars.indices.all { chars[it] == accountId[it] } }
                finally { chars.fill('\u0000') }
            }
            if (matches) CoreResult.Success(SecretReply(SecretBytes.take(reply.body.copyOf())))
            else CoreResult.Success(null)
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.body.fill(0) }

    fun eventGeneration(bytes: ByteArray): ULong? = try {
        val parser = ByteJson(bytes)
        parser.field(parser.parse(), "subscription_generation")?.let { parser.unsignedDecimal(it) }
    } catch (_: IllegalArgumentException) { null }

    private const val MAX_FRAME = 1 shl 20
    fun decodeCompletion(reply: NativeReply, operationId: String?): CoreResult<JsonObject> {
        val decoded = decode(reply, operationId)
        if (decoded !is CoreResult.Success) return decoded
        val envelope = decoded.value
        val code = (envelope["code"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: return CoreResult.Failure(CoreError.InvalidResponse(), operationId)
        val response = envelope["response"] as? JsonObject
        return if (code == 0 && response != null) CoreResult.Success(response)
        else {
            val bytes = envelope.toString().toByteArray()
            decode(NativeReply(if (code == 0) 16 else code, bytes), operationId)
        }
    }

    fun decode(reply: NativeReply, operationId: String? = null): CoreResult<JsonObject> {
        return try {
            if (reply.body.size > MAX_FRAME) return CoreResult.Failure(CoreError.InvalidResponse(), operationId)
            val body = if (reply.body.isEmpty()) JsonObject(emptyMap()) else
                BoundedJson.parseObject(reply.body)
                    ?: return CoreResult.Failure(CoreError.InvalidResponse(), operationId)
            if (reply.code == 0) CoreResult.Success(body) else {
                val effect = when ((body["effects"] ?: body["effect"])?.jsonPrimitive?.contentOrNull) {
                    "none" -> Effect.NONE
                    "refused" -> Effect.REFUSED
                    else -> Effect.UNKNOWN
                }
                val error = when (reply.code) {
                    1 -> CoreError.InvalidArgument(effect)
                    3 -> CoreError.UnsupportedVersion(effect)
                    12 -> CoreError.PermissionDenied(effect)
                    23 -> CoreError.SchemaResetRequired(effect)
                    else -> CoreError.Native(reply.code, effect)
                }
                CoreResult.Failure(error, operationId, body)
            }
        } catch (_: SerializationException) {
            CoreResult.Failure(CoreError.InvalidResponse(), operationId)
        } catch (_: IllegalArgumentException) {
            CoreResult.Failure(CoreError.InvalidResponse(), operationId)
        } finally { reply.body.fill(0) }
    }
}
