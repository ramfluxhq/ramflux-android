// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface ConversationsRepository {
    suspend fun conversationDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun conversationDisappearingExpire(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationDisappearingSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun conversationMute(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageAck(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageFocus(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageFranking_evidence(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun messageList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun messageRead(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun messageReceiptDelivered(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageReceiptRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageReceive(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun messageSubmit(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationSend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationEdit(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationReact(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationReply(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun conversationDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun objectFetch(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectGet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectGetBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectGetFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectGetRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectGetStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun objectImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun objectPut(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectPutBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectPutChunk(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectPutFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectPutStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun objectShare(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectTransferResume(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun objectTransferStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun streamRead(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
}

class DefaultConversationsRepository(transport: RepositoryTransport) : SdkRepository(transport), ConversationsRepository {
    override suspend fun conversationDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("conversation.directory", "conversation", context, SecretBody.publicJson("conversation.directory", body))

    override suspend fun conversationDisappearingExpire(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.disappearing.expire", "conversation", context, body)

    override suspend fun conversationDisappearingSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.disappearing.set", "conversation", context, body)

    override suspend fun conversationList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("conversation.list", "conversation", context, SecretBody.publicJson("conversation.list", body))

    override suspend fun conversationMute(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.mute", "conversation", context, body)

    override suspend fun messageAck(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.ack", "message", context, body)

    override suspend fun messageFocus(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.focus", "message", context, body)

    override suspend fun messageDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.delete", "message", context, body)

    override suspend fun messageFranking_evidence(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("message.franking_evidence", "message", context, SecretBody.publicJson("message.franking_evidence", body))

    override suspend fun messageList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("message.list", "message", context, SecretBody.publicJson("message.list", body))

    override suspend fun messageRead(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("message.read", "message", context, SecretBody.publicJson("message.read", body))

    override suspend fun messageReceiptDelivered(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.receipt.delivered", "message", context, body)

    override suspend fun messageReceiptRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.receipt.read", "message", context, body)

    override suspend fun messageReceive(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.receive", "message", context, body)

    override suspend fun messageSubmit(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("message.submit", "raw_message", context, body)

    override suspend fun conversationSend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.send", "conversation", context, body)

    override suspend fun conversationEdit(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.edit", "conversation", context, body)

    override suspend fun conversationReact(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.react", "conversation", context, body)

    override suspend fun conversationReply(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.reply", "conversation", context, body)

    override suspend fun conversationDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("conversation.delete", "conversation", context, body)

    override suspend fun objectDelete(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.delete", "object", context, body)

    override suspend fun objectDirectory(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("object.directory", "object", context, SecretBody.publicJson("object.directory", body))

    override suspend fun objectFetch(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.fetch", "object", context, body)

    override suspend fun objectGet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.get", "object", context, body)

    override suspend fun objectGetBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.get.begin", "object", context, body)

    override suspend fun objectGetFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.get.finish", "object", context, body)

    override suspend fun objectGetRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.get.read", "object", context, body)

    override suspend fun objectGetStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("object.get.status", "object", context, SecretBody.publicJson("object.get.status", body))

    override suspend fun objectImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.import", "object", context, body)

    override suspend fun objectList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("object.list", "object", context, SecretBody.publicJson("object.list", body))

    override suspend fun objectPut(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.put", "object", context, body)

    override suspend fun objectPutBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.put.begin", "object", context, body)

    override suspend fun objectPutChunk(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.put.chunk", "object", context, body)

    override suspend fun objectPutFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.put.finish", "object", context, body)

    override suspend fun objectPutStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("object.put.status", "object", context, SecretBody.publicJson("object.put.status", body))

    override suspend fun objectShare(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.share", "object", context, body)

    override suspend fun objectTransferResume(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("object.transfer.resume", "object", context, body)

    override suspend fun objectTransferStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("object.transfer.status", "object", context, SecretBody.publicJson("object.transfer.status", body))

    override suspend fun streamRead(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("stream.read", "stream", context, SecretBody.publicJson("stream.read", body))
}
