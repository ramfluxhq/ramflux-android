// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.MessageHistoryMetadataReader
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

/**
 * [root] is the app's no-backup files directory (credential-encrypted storage, the SDK's own root).
 * [digest] creates the BLAKE3 digester the SDK expects in `attachment.prepare.finish`.
 */
class DefaultMediaRepository(
    transport: RepositoryTransport,
    private val root: File,
    private val digest: () -> PlaintextDigest,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : MediaRepository {
    private val attachments = DefaultAttachmentTransport(transport)
    private val conversations = DefaultConversationsRepository(transport)
    private val enabled = HashSet<String>()

    override fun localFile(account: String, attachment: MediaAttachment): File? = stored(account, attachment)?.takeIf { it.isFile }

    override suspend fun locate(account: String, attachments: List<MediaAttachment>): List<File?> =
        withContext(Dispatchers.IO) { attachments.map { localFile(account, it) } }

    override suspend fun download(account: String, attachment: MediaAttachment, progress: (TransferState) -> Unit): TransferState {
        localFile(account, attachment)?.let { return TransferState.Ready(it) }
        val target = stored(account, attachment) ?: return TransferState.Failed(1, retryable = false)
        enable(account)?.let { return MediaRefusals.stateFor(it) }
        progress(TransferState.Downloading(null))
        val begun = call(
            attachments.importBegin(
                RepositoryContext(account),
                SecretBody.fields {
                    publicText("message_id", attachment.messageId)
                    publicUnsigned("index", attachment.index.toULong())
                    publicText("operation_id", operation(attachment))
                },
            ),
        )
        val start = when (begun) {
            is Reply.Problem -> return MediaRefusals.stateFor(begun.problem)
            is Reply.Body -> begun.json
        }
        val importId = start.text("import_id") ?: return TransferState.Failed(16, retryable = true)
        val generation = start.number("generation") ?: 1L
        var finished = false
        try {
            var state = start.text("state")
            var wait = 250L
            while (state != "ready") {
                pause(wait)
                wait = (wait * 2).coerceAtMost(2_000L)
                state = when (val status = call(attachments.importStatus(RepositoryContext(account), buildJsonObject { put("import_id", importId) }))) {
                    is Reply.Problem -> return MediaRefusals.stateFor(status.problem)
                    is Reply.Body -> status.json.text("state")
                }
            }
            val written = readOut(account, importId, generation, target, attachment.conversation) ?: return TransferState.Failed(16, retryable = true)
            if (written is TransferState.Ready) {
                finished = true
                call(attachments.importFinish(RepositoryContext(account), SecretBody.fields { publicText("import_id", importId) }))
            }
            return written
        } finally {
            if (!finished) {
                withContext(NonCancellable) {
                    call(attachments.importCancel(RepositoryContext(account), SecretBody.fields { publicText("import_id", importId) }))
                }
            }
        }
    }

    private suspend fun readOut(account: String, importId: String, generation: Long, target: File, conversation: String): TransferState? =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            mark(target.parentFile, conversation)
            val staging = File(target.parentFile, target.name + ".part")
            try {
                FileOutputStream(staging).use { out ->
                    var offset = 0L
                    while (true) {
                        val body = SecretBody.fields {
                            publicText("import_id", importId)
                            publicUnsigned("generation", generation.toULong())
                            publicUnsigned("offset", offset.toULong())
                            publicUnsigned("max_bytes", CHUNK.toULong())
                        }
                        val reply = when (val read = call(attachments.importRead(RepositoryContext(account), body))) {
                            is Reply.Problem -> return@withContext MediaRefusals.stateFor(read.problem)
                            is Reply.Body -> read.json
                        }
                        val encoded = reply.text("bytes").orEmpty()
                        val bytes = Base64.getUrlDecoder().decode(encoded)
                        try {
                            out.write(bytes)
                            offset += bytes.size
                        } finally {
                            bytes.fill(0)
                        }
                        if (reply.flag("eof") == true) break
                        if (bytes.isEmpty()) return@withContext null
                    }
                    out.fd.sync()
                }
                if (!staging.renameTo(target)) return@withContext null
                TransferState.Ready(target)
            } finally {
                staging.delete()
            }
        }

    override suspend fun send(
        account: String,
        destination: MediaDestination,
        caption: String,
        items: List<StagedMedia>,
        operationId: String,
        progress: (TransferState) -> Unit,
    ): ClientResult<SendReceipt> {
        if (items.isEmpty() || items.size > MediaAttachment.MAX_PER_MESSAGE || !usable(account) || !usable(operationId)) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        enable(account)?.let { return ClientResult.Failed(it) }
        val prepared = ArrayList<String>(items.size)
        items.forEachIndexed { index, item ->
            when (val one = prepare(account, destination, item, "$operationId:$index") { fraction ->
                progress(TransferState.Preparing((index + fraction) / items.size))
            }) {
                is ClientResult.Ready -> prepared += one.value
                is ClientResult.Failed -> return one
                is ClientResult.Unavailable -> return one
            }
        }
        progress(TransferState.Uploading)
        // Upload, key wrapping and grants all happen inside this one call; it reports no progress (R5).
        val body = SecretBody.takeJson(
            buildJsonObject {
                put("conversation_id", destination.conversation)
                put("text", caption)
                destination.group?.let { put("group_id", it) }
                put("prepares", buildJsonArray { prepared.forEach { add(buildJsonObject { put("prepare_id", it) }) } })
            }.toString().toByteArray(),
        )
        return when (val result = conversations.conversationSend(RepositoryContext(account, operationId), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val delivery = MessageHistoryMetadataReader.delivery(result.value, destination.conversation, null)) {
                is CoreResult.Failure -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
                is CoreResult.Success -> when {
                    delivery.value.state == "submitted" && !delivery.value.partial -> {
                        items.forEach { it.file.delete() }
                        ClientResult.Ready(SendReceipt(delivery.value.messageId))
                    }
                    delivery.value.state == "failed" -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
                    else -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
                }
            }
        }
    }

    private suspend fun prepare(
        account: String,
        destination: MediaDestination,
        item: StagedMedia,
        operation: String,
        progress: (Float) -> Unit,
    ): ClientResult<String> {
        val length = item.file.length()
        val begun = call(
            attachments.prepareBegin(
                RepositoryContext(account),
                SecretBody.fields {
                    publicText("operation_id", operation)
                    if (destination.group != null) {
                        publicText("kind", "group")
                        publicText("group_id", destination.group)
                    } else {
                        // As the reference CLI does, a direct conversation addresses its peer by the conversation id.
                        publicText("kind", "dm")
                        publicText("peer", destination.conversation)
                    }
                    chars("file_name", item.fileName.toCharArray())
                    publicText("mime", item.mime)
                },
            ),
        )
        val start = when (begun) {
            is Reply.Problem -> return ClientResult.Failed(begun.problem)
            is Reply.Body -> begun.json
        }
        val prepareId = start.text("prepare_id") ?: return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
        val generation = start.number("generation") ?: 1L
        // A repeated begin with the same operation returns the existing prepare; an accepted one is reused.
        if (start.text("state") != "accepting") return ClientResult.Ready(prepareId)
        val hasher = digest()
        val failed = withContext(Dispatchers.IO) {
            item.file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                var offset = 0L
                while (true) {
                    val read = input.fill(buffer)
                    if (read <= 0) break
                    hasher.update(buffer, 0, read)
                    val encoded = Base64.getUrlEncoder().withoutPadding().encode(buffer.copyOf(read))
                    val chars = CharArray(encoded.size) { encoded[it].toInt().toChar() }
                    encoded.fill(0)
                    val body = SecretBody.fields {
                        publicText("prepare_id", prepareId)
                        publicUnsigned("generation", generation.toULong())
                        publicUnsigned("offset", offset.toULong())
                        chars("data_base64", chars)
                    }
                    when (val chunk = call(attachments.prepareChunk(RepositoryContext(account), body))) {
                        is Reply.Problem -> return@withContext chunk.problem
                        is Reply.Body -> Unit
                    }
                    offset += read
                    if (length > 0) progress(offset.toFloat() / length)
                }
                buffer.fill(0)
                null
            }
        }
        if (failed != null) return ClientResult.Failed(failed)
        val finish = call(
            attachments.prepareFinish(
                RepositoryContext(account),
                SecretBody.fields {
                    publicText("prepare_id", prepareId)
                    publicUnsigned("generation", generation.toULong())
                    publicUnsigned("plaintext_length", length.toULong())
                    publicText("plaintext_digest", hasher.hex())
                },
            ),
        )
        return when (finish) {
            is Reply.Problem -> ClientResult.Failed(finish.problem)
            is Reply.Body -> ClientResult.Ready(prepareId)
        }
    }

    override suspend fun revoke(account: String, message: String): ClientResult<Unit> {
        if (!usable(account) || !usable(message)) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        return when (val result = call(attachments.revoke(RepositoryContext(account), SecretBody.fields { publicText("message_id", message) }))) {
            is Reply.Problem -> ClientResult.Failed(result.problem)
            is Reply.Body -> ClientResult.Ready(Unit)
        }
    }

    override suspend fun purge(account: String, message: String) {
        withContext(Dispatchers.IO) {
            val folder = folder(account) ?: return@withContext
            val prefix = hash(message) + "-"
            // One folder per conversation: listing folders, never reading files.
            folder.listFiles()?.filter { it.isDirectory }?.forEach { conversation ->
                conversation.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            }
        }
    }

    override suspend fun purgeConversation(account: String, conversation: String) {
        withContext(Dispatchers.IO) {
            if (!usable(conversation)) return@withContext
            folder(account)?.let { File(it, hash(conversation)).deleteRecursively() }
        }
    }

    override suspend fun storage(account: String): MediaStorage = withContext(Dispatchers.IO) {
        if (!usable(account)) return@withContext MediaStorage(0, 0, emptyList())
        val conversations = folder(account)?.listFiles()?.filter { it.isDirectory }?.mapNotNull { folder ->
            val id = File(folder, MARKER).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { usable(it) } ?: return@mapNotNull null
            ConversationUsage(id, folder.walkTopDown().filter { it.isFile && it.name != MARKER }.sumOf { it.length() })
        }.orEmpty().filter { it.bytes > 0 }.sortedByDescending { it.bytes }
        val outbox = outbox(account)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        MediaStorage(conversations.sumOf { it.bytes } + outbox, outbox, conversations)
    }

    override suspend fun evict(account: String, nowMs: Long): Long = withContext(Dispatchers.IO) {
        val stale = outbox(account)?.listFiles()?.filter { it.isFile && nowMs - it.lastModified() > MediaRetention.OUTBOX_MAX_AGE_MS }.orEmpty()
        stale.sumOf { file -> file.length().also { if (!file.delete()) return@sumOf 0L } }
    }

    override suspend fun purgeAccount(account: String) {
        withContext(Dispatchers.IO) {
            if (!usable(account)) return@withContext
            File(root, ROOT + "/" + hash(account)).deleteRecursively()
        }
    }

    override suspend fun usage(account: String): Long = withContext(Dispatchers.IO) {
        if (!usable(account)) return@withContext 0L
        File(root, ROOT + "/" + hash(account)).walkTopDown().filter { it.isFile && it.name != MARKER }.sumOf { it.length() }
    }

    override fun outbox(account: String): File? =
        if (!usable(account)) null else File(root, ROOT + "/" + hash(account) + "/outbox").apply { mkdirs() }

    override suspend fun copyToOutbox(account: String, source: File, fileName: String, mime: String): StagedMedia? =
        withContext(Dispatchers.IO) {
            val folder = outbox(account) ?: return@withContext null
            if (!source.isFile || fileName.isEmpty() || mime.isEmpty()) return@withContext null
            val copy = File.createTempFile("forward", ".bin", folder)
            try {
                source.copyTo(copy, overwrite = true)
                StagedMedia(copy, fileName, mime)
            } catch (cancelled: CancellationException) {
                copy.delete()
                throw cancelled
            } catch (_: Exception) {
                copy.delete()
                null
            }
        }

    /** `attachment.enable` once per account and process; a repeat with the same pin is harmless. */
    private suspend fun enable(account: String): ClientProblem? {
        if (synchronized(enabled) { account in enabled }) return null
        val result = call(attachments.enable(RepositoryContext(account), SecretBody.fields { publicFlag("from_node", true) }))
        if (result is Reply.Problem && result.problem.statusName != "duplicate_operation" && result.problem.check != "duplicate_operation") {
            return result.problem
        }
        synchronized(enabled) { enabled += account }
        return null
    }

    private fun folder(account: String): File? = if (!usable(account)) null else File(root, ROOT + "/" + hash(account) + "/media")

    /** `<account>/media/<conversation>/<message>-<index>`, every part hashed; never the sender's file name. */
    private fun stored(account: String, attachment: MediaAttachment): File? {
        val message = attachment.messageId
        if (!usable(message) || attachment.index !in 0 until MediaAttachment.MAX_PER_MESSAGE) return null
        val conversation = attachment.conversation.takeIf { usable(it) } ?: UNSORTED
        return folder(account)?.let { File(File(it, hash(conversation)), hash(message) + "-" + attachment.index) }
    }

    /** Records which conversation a folder belongs to, inside protected storage, for the storage screen. */
    private fun mark(folder: File?, conversation: String) {
        folder ?: return
        val marker = File(folder, MARKER)
        if (!marker.isFile && usable(conversation)) marker.writeText(conversation, Charsets.UTF_8)
    }

    private fun operation(attachment: MediaAttachment) = "import:" + hash(attachment.messageId).take(32) + ":" + attachment.index

    private fun usable(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private sealed interface Reply {
        data class Body(val json: JsonObject) : Reply
        data class Problem(val problem: ClientProblem) : Reply
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun call(result: CoreResult<SecretReply>): Reply = when (result) {
        is CoreResult.Failure -> Reply.Problem(result.error.appProblem())
        is CoreResult.Success -> try {
            result.value.useOnce { raw ->
                val parsed = json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw))
                Reply.Body(parsed as? JsonObject ?: JsonObject(emptyMap()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Reply.Problem(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
        } finally {
            result.value.close()
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? {
        val value = this[name] as? JsonPrimitive ?: return null
        return if (value.isString) value.content.toLongOrNull() else value.longOrNull
    }

    private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val CHUNK = 524_288
        const val ROOT = "media"
        const val MARKER = ".conversation"
        const val UNSORTED = "unsorted"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Reads until [buffer] is full or the stream ends; returns the bytes read (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.fill(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}
