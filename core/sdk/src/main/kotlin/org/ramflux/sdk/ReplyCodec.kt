// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ReplyCodec {
    var debugTrace: SdkDebugTrace? = null

    fun submitRefusal(reply: NativeReply, operationId: String? = null): CoreResult.Failure = try {
        CoreResult.Failure(error(if (reply.code == 0) 16 else reply.code, Effect.NONE), operationId)
    } finally { reply.body.fill(0) }

    private fun error(
        code: Int,
        effect: Effect,
        statusName: String? = null,
        reasonCode: String? = null,
    ): CoreError = when (code) {
        1 -> CoreError.InvalidArgument(effect, statusName, reasonCode)
        3 -> CoreError.UnsupportedVersion(effect, statusName, reasonCode)
        12 -> CoreError.PermissionDenied(effect, statusName, reasonCode)
        23 -> CoreError.SchemaResetRequired(effect, statusName, reasonCode)
        else -> CoreError.Native(code, effect, statusName, reasonCode)
    }

    private fun namedEffect(chars: CharArray): Effect = when {
        chars.contentEquals(charArrayOf('n', 'o', 'n', 'e')) -> Effect.NONE
        chars.contentEquals(charArrayOf('r', 'e', 'f', 'u', 's', 'e', 'd')) -> Effect.REFUSED
        else -> Effect.UNKNOWN
    }

    private fun namedEffect(text: String?): Effect = when (text) {
        "none" -> Effect.NONE
        "refused" -> Effect.REFUSED
        else -> Effect.UNKNOWN
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
                val effect = if (effects == null) Effect.NONE else {
                    val chars = parser.chars(effects)
                    try { namedEffect(chars) } finally { chars.fill('\u0000') }
                }
                val status = statusName(parser, envelope)
                val reason = reasonCode(parser, envelope)
                if (debugTrace != null) {
                    traceFailure(
                        code,
                        status,
                        reason,
                        diagnostic(parser, envelope, "variant"),
                        diagnostic(parser, envelope, "message"),
                    )
                }
                CoreResult.Failure(error(code, effect, status, reason), operationId, retryDetails(parser, envelope))
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
        val code = (envelope["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
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
                val effects = body["effects"] ?: body["effect"]
                val effect = if (effects == null) Effect.NONE else namedEffect(effects.jsonPrimitive.contentOrNull)
                val status = statusName(body)
                val reason = reasonCode(body)
                if (debugTrace != null) {
                    traceFailure(
                        reply.code,
                        status,
                        reason,
                        diagnostic(body, "variant"),
                        diagnostic(body, "message"),
                    )
                }
                CoreResult.Failure(error(reply.code, effect, status, reason), operationId, body)
            }
        } catch (_: SerializationException) {
            CoreResult.Failure(CoreError.InvalidResponse(), operationId)
        } catch (_: IllegalArgumentException) {
            CoreResult.Failure(CoreError.InvalidResponse(), operationId)
        } finally { reply.body.fill(0) }
    }

    /**
     * The only refusal detail lifted from a secret method's error frame: the public wait the SDK
     * reports in `response.body.retry_after_secs` (for example a rate-limited account code check).
     */
    private fun retryDetails(parser: ByteJson, envelope: ByteJson.Node): JsonObject? = try {
        val response = parser.field(envelope, "response")?.takeIf { it.kind == 123 }
        val body = response?.let { parser.field(it, "body") }?.takeIf { it.kind == 123 }
        val wait = body?.let { parser.field(it, "retry_after_secs") }
        if (wait == null || wait.kind == 34 || wait.end - wait.start > 9) null
        else JsonObject(mapOf(RETRY_AFTER_SECS to JsonPrimitive(parser.integer(wait))))
    } catch (_: IllegalArgumentException) {
        null
    }

    const val RETRY_AFTER_SECS = "retry_after_secs"

    private fun statusName(parser: ByteJson, envelope: ByteJson.Node): String? {
        return namedErrorField(parser, envelope, "code")
    }

    private fun reasonCode(parser: ByteJson, envelope: ByteJson.Node): String? {
        return namedErrorField(parser, envelope, "reason_code")
    }

    private fun namedErrorField(parser: ByteJson, envelope: ByteJson.Node, field: String): String? {
        return try {
            val response = parser.field(envelope, "response") ?: return null
            if (response.kind != 123) return null
            val errorNode = parser.field(response, "error") ?: return null
            if (errorNode.kind != 123) return null
            val code = parser.field(errorNode, field) ?: return null
            if (code.kind != 34) return null
            val chars = parser.chars(code)
            try { identifier(chars) } finally { chars.fill('\u0000') }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun statusName(body: JsonObject): String? = identifierField(body, "code")

    private fun reasonCode(body: JsonObject): String? = identifierField(body, "reason_code")

    private fun identifierField(body: JsonObject, field: String): String? {
        val response = body["response"] as? JsonObject ?: return null
        val errorNode = response["error"] as? JsonObject ?: return null
        val raw = (errorNode[field] as? JsonPrimitive)?.contentOrNull ?: return null
        val chars = raw.toCharArray()
        return try { identifier(chars) } finally { chars.fill('\u0000') }
    }

    private fun traceFailure(code: Int, statusName: String?, reasonCode: String?, variant: String?, text: String?) {
        val trace = debugTrace ?: return
        val shown = text?.replace('\n', ' ')?.take(400) ?: "-"
        val kind = variant?.take(80) ?: "-"
        trace.event("sdk.failed.$code.${statusName ?: "NONE"}.${reasonCode ?: "NONE"} variant=$kind text=$shown")
    }

    private fun diagnostic(parser: ByteJson, envelope: ByteJson.Node, field: String): String? {
        return try {
            val response = parser.field(envelope, "response") ?: return null
            if (response.kind != 123) return null
            val errorNode = parser.field(response, "error") ?: return null
            if (errorNode.kind != 123) return null
            val node = parser.field(errorNode, field) ?: return null
            if (node.kind != 34) return null
            val chars = parser.chars(node)
            try { if (chars.isEmpty()) null else String(chars) } finally { chars.fill('\u0000') }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun diagnostic(body: JsonObject, field: String): String? {
        val response = body["response"] as? JsonObject ?: return null
        val errorNode = response["error"] as? JsonObject ?: return null
        val raw = (errorNode[field] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.ifEmpty { null }
    }

    private fun identifier(chars: CharArray): String? {
        if (chars.size !in 1..64) return null
        val allowed = chars.all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' }
        return if (allowed) String(chars) else null
    }
}
