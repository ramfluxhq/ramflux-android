// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ReplyCodec {
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
