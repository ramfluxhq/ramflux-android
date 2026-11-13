// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the conversations flows. No retries or optimistic outcomes. */
interface ConversationsRepository {
    suspend fun conversationDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun conversationDisappearingExpire(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun conversationDisappearingSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun conversationList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun conversationMute(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageAck(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageFocus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageFranking_evidence(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageReceiptDelivered(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageReceiptRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageReceive(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun messageSubmit(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectFetch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectGetBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectGetFinish(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectGetRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectGetStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectPut(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectPutBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectPutChunk(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectPutFinish(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectPutStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectShare(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectTransferResume(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun objectTransferStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun streamRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultConversationsRepository(transport: RepositoryTransport) : SdkRepository(transport), ConversationsRepository {
    override suspend fun conversationDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("conversation.directory", "conversation", context, body)

    override suspend fun conversationDisappearingExpire(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("conversation.disappearing.expire", "conversation", context, body)

    override suspend fun conversationDisappearingSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("conversation.disappearing.set", "conversation", context, body)

    override suspend fun conversationList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("conversation.list", "conversation", context, body)

    override suspend fun conversationMute(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("conversation.mute", "conversation", context, body)

    override suspend fun messageAck(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.ack", "message", context, body)

    override suspend fun messageDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.delete", "message", context, body)

    override suspend fun messageFocus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.focus", "message", context, body)

    override suspend fun messageFranking_evidence(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.franking_evidence", "message", context, body)

    override suspend fun messageList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.list", "message", context, body)

    override suspend fun messageRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.read", "message", context, body)

    override suspend fun messageReceiptDelivered(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.receipt.delivered", "message", context, body)

    override suspend fun messageReceiptRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.receipt.read", "message", context, body)

    override suspend fun messageReceive(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.receive", "message", context, body)

    override suspend fun messageSubmit(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("message.submit", "message", context, body)

    override suspend fun objectDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.delete", "object", context, body)

    override suspend fun objectDirectory(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.directory", "object", context, body)

    override suspend fun objectFetch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.fetch", "object", context, body)

    override suspend fun objectGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.get", "object", context, body)

    override suspend fun objectGetBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.get.begin", "object", context, body)

    override suspend fun objectGetFinish(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.get.finish", "object", context, body)

    override suspend fun objectGetRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.get.read", "object", context, body)

    override suspend fun objectGetStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.get.status", "object", context, body)

    override suspend fun objectImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.import", "object", context, body)

    override suspend fun objectList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.list", "object", context, body)

    override suspend fun objectPut(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.put", "object", context, body)

    override suspend fun objectPutBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.put.begin", "object", context, body)

    override suspend fun objectPutChunk(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.put.chunk", "object", context, body)

    override suspend fun objectPutFinish(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.put.finish", "object", context, body)

    override suspend fun objectPutStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.put.status", "object", context, body)

    override suspend fun objectShare(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.share", "object", context, body)

    override suspend fun objectTransferResume(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.transfer.resume", "object", context, body)

    override suspend fun objectTransferStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("object.transfer.status", "object", context, body)

    override suspend fun streamRead(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("stream.read", "stream", context, body)
}
