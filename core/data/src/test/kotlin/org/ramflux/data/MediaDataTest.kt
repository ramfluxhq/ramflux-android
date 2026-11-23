// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class MediaDataTest {
    private val digest = {
        object : PlaintextDigest {
            var total = 0
            override fun update(bytes: ByteArray, offset: Int, length: Int) {
                total += length
            }
            override fun hex() = "d".repeat(64)
        }
    }

    @Test fun sendEnablesPreparesChunksFinishesAndSendsThePrepares() = runTest(timeout = 5.seconds) {
        val root = Files.createTempDirectory("media").toFile()
        val abi = FakeAbi().apply {
            onSubmitted = { frame ->
                completionBody = when (frame["method"]?.jsonPrimitive?.content) {
                    "attachment.enable" -> json("""{"enabled":true,"source":"node","authority_generation":1}""")
                    "attachment.prepare.begin" -> json(
                        """{"prepare_id":"p1","generation":1,"max_chunk":524288,"state":"accepting","accepted_through":0}""",
                    )
                    "attachment.prepare.chunk", "attachment.prepare.finish" -> json("""{"prepare_id":"p1","state":"accepted"}""")
                    "conversation.send" -> json("""{"message_id":"m","conversation_id":"c","delivery_state":"submitted"}""")
                    else -> json("{}")
                }
            }
        }
        val client = open(abi, "/test/media-send")
        try {
            val repo = DefaultMediaRepository(SdkRepositoryTransport { client }, root, digest, pause = {})
            val staged = java.io.File(repo.outbox("a"), "IMG_1.jpg").apply { writeBytes(ByteArray(600_000) { 7 }) }
            val states = mutableListOf<TransferState>()
            val sent = repo.send("a", MediaDestination("c", null), "look", listOf(StagedMedia(staged, "IMG_1.jpg", "image/jpeg")), "op") {
                states += it
            }
            assertEquals("m", (sent as ClientResult.Ready).value.messageId)
            val methods = abi.submitted.map { it["method"]?.jsonPrimitive?.content }
            assertEquals(
                listOf(
                    "attachment.enable", "attachment.prepare.begin", "attachment.prepare.chunk", "attachment.prepare.chunk",
                    "attachment.prepare.finish", "conversation.send",
                ),
                methods,
            )
            val begin = abi.submitted[1]["body"]!!.jsonObject
            assertEquals("dm", begin["kind"]?.jsonPrimitive?.content)
            assertEquals("c", begin["peer"]?.jsonPrimitive?.content)
            assertEquals("op:0", begin["operation_id"]?.jsonPrimitive?.content)
            val secondChunk = abi.submitted[3]["body"]!!.jsonObject
            assertEquals(524_288L, secondChunk["offset"]?.jsonPrimitive?.long)
            val data = secondChunk["data_base64"]!!.jsonPrimitive.content
            assertFalse(data.contains('=') || data.contains('+') || data.contains('/'))
            val finish = abi.submitted[4]["body"]!!.jsonObject
            assertEquals(600_000L, finish["plaintext_length"]?.jsonPrimitive?.long)
            assertEquals("d".repeat(64), finish["plaintext_digest"]?.jsonPrimitive?.content)
            val send = abi.submitted[5]
            assertEquals("op", send["operation_id"]?.jsonPrimitive?.content)
            assertEquals("p1", send["body"]!!.jsonObject["prepares"]!!.jsonArray[0].jsonObject["prepare_id"]?.jsonPrimitive?.content)
            assertNull(send["body"]!!.jsonObject["media"])
            assertFalse(staged.exists())
            assertTrue(states.first() is TransferState.Preparing)
            assertEquals(TransferState.Uploading, states.last())
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun groupMediaPreparesForTheGroupIdAndSendsToTheConversationId() = runTest(timeout = 5.seconds) {
        val root = Files.createTempDirectory("media").toFile()
        val abi = FakeAbi().apply {
            onSubmitted = { frame ->
                completionBody = when (frame["method"]?.jsonPrimitive?.content) {
                    "attachment.prepare.begin" -> json("""{"prepare_id":"p1","generation":1,"state":"accepting"}""")
                    "conversation.send" -> json("""{"message_id":"m","conversation_id":"conv-x","delivery_state":"submitted"}""")
                    else -> json("""{"state":"accepted"}""")
                }
            }
        }
        val client = open(abi, "/test/media-group")
        try {
            val repo = DefaultMediaRepository(SdkRepositoryTransport { client }, root, digest, pause = {})
            val staged = java.io.File(repo.outbox("a"), "f").apply { writeBytes(ByteArray(10)) }
            // A group made on desktop or the CLI: the conversation id is not the group id.
            repo.send("a", MediaDestination("conv-x", "group-y"), "", listOf(StagedMedia(staged, "f.bin", "application/pdf")), "op")
            val begin = abi.submitted.first { it["method"]?.jsonPrimitive?.content == "attachment.prepare.begin" }["body"]!!.jsonObject
            assertEquals("group", begin["kind"]?.jsonPrimitive?.content)
            assertEquals("group-y", begin["group_id"]?.jsonPrimitive?.content)
            assertNull(begin["peer"])
            val send = abi.submitted.first { it["method"]?.jsonPrimitive?.content == "conversation.send" }["body"]!!.jsonObject
            assertEquals("conv-x", send["conversation_id"]?.jsonPrimitive?.content)
            assertEquals("group-y", send["group_id"]?.jsonPrimitive?.content)
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun downloadPollsReadsOutStoresAndFinishesThenPurges() = runTest(timeout = 5.seconds) {
        val root = Files.createTempDirectory("media").toFile()
        var polls = 0
        val plain = ByteArray(700_000) { (it % 251).toByte() }
        val abi = FakeAbi().apply {
            onSubmitted = { frame ->
                val body = frame["body"]?.jsonObject
                completionBody = when (frame["method"]?.jsonPrimitive?.content) {
                    "attachment.enable" -> json("""{"enabled":true}""")
                    "attachment.import.begin" -> json("""{"import_id":"i1","generation":1,"state":"pending","plaintext_length":700000}""")
                    "attachment.import.status" -> json(if (++polls < 2) """{"state":"pending"}""" else """{"state":"ready"}""")
                    "attachment.import.read" -> {
                        val offset = body!!["offset"]!!.jsonPrimitive.long.toInt()
                        val end = (offset + 524_288).coerceAtMost(plain.size)
                        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.copyOfRange(offset, end))
                        json("""{"offset":$offset,"bytes":"$encoded","eof":${end == plain.size}}""")
                    }
                    else -> json("""{"import_id":"i1","state":"finished"}""")
                }
            }
        }
        val client = open(abi, "/test/media-import")
        try {
            val repo = DefaultMediaRepository(SdkRepositoryTransport { client }, root, digest, pause = {})
            val attachment = MediaAttachment("m", 0, MediaKind.FILE, "application/pdf", "report.pdf", 700_000, conversation = "c")
            assertNull(repo.localFile("a", attachment))
            val state = repo.download("a", attachment)
            val file = (state as TransferState.Ready).file
            assertArrayEquals(plain, file.readBytes())
            assertFalse(file.name.contains("report"))
            assertEquals("attachment.import.finish", abi.submitted.last()["method"]?.jsonPrimitive?.content)
            assertEquals(file, repo.localFile("a", attachment))
            assertEquals(700_000L, repo.usage("a"))
            // Forwarding copies the original into the outbox; the stored file stays where it was.
            val copy = repo.copyToOutbox("a", file, "report.pdf", "application/pdf")!!
            assertArrayEquals(plain, copy.file.readBytes())
            assertEquals(repo.outbox("a"), copy.file.parentFile)
            assertNull(repo.copyToOutbox("a", File(root, "missing"), "x", "application/pdf"))
            copy.file.delete()
            val storage = repo.storage("a")
            assertEquals(listOf(ConversationUsage("c", 700_000)), storage.conversations)
            assertEquals(700_000L, storage.total)
            // A staged file older than the prepare hard deadline is evicted; a fresh one stays.
            val stale = File(repo.outbox("a"), "stale").apply { writeBytes(ByteArray(10)) }
            stale.setLastModified(System.currentTimeMillis() - MediaRetention.OUTBOX_MAX_AGE_MS - 1_000)
            val fresh = File(repo.outbox("a"), "fresh").apply { writeBytes(ByteArray(5)) }
            assertEquals(10L, repo.evict("a", System.currentTimeMillis()))
            assertFalse(stale.exists())
            assertTrue(fresh.delete())
            repo.purgeConversation("a", "c")
            assertNull(repo.localFile("a", attachment))
            assertTrue(repo.storage("a").conversations.isEmpty())
            repo.download("a", attachment)
            repo.purge("a", "m")
            assertNull(repo.localFile("a", attachment))
            repo.purgeAccount("a")
            assertEquals(0L, repo.usage("a"))
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun refusalsMapToTypedStatesAndPolicyHidesStrangers() {
        fun problem(name: String) = ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.REFUSED, statusName = name)
        assertEquals(TransferState.Expired, MediaRefusals.stateFor(problem("expired")))
        assertEquals(TransferState.Revoked, MediaRefusals.stateFor(problem("attachment_deleted")))
        assertEquals(TransferState.NotGrantee, MediaRefusals.stateFor(problem("not_grantee")))
        assertEquals(TransferState.Failed(16, retryable = false), MediaRefusals.stateFor(problem("recipient_limit")))
        val image = MediaAttachment("m", 0, MediaKind.IMAGE, "image/jpeg", "", 1_000)
        val calm = MediaConditions(metered = false, constrained = false, roaming = false)
        assertFalse(MediaPolicy.autoDownload(image, MediaTrust.UNTRUSTED, calm))
        assertFalse(MediaPolicy.showThumbnail(MediaTrust.UNTRUSTED))
        assertTrue(MediaPolicy.autoDownload(image, MediaTrust.CONTACT, calm))
        assertFalse(MediaPolicy.autoDownload(image, MediaTrust.CONTACT, calm.copy(roaming = true)))
        assertEquals(MediaKind.ANIMATED, MediaAttachment.kindOf("image/gif"))
        assertEquals(MediaKind.VIDEO, MediaAttachment.kindOf("video/mp4"))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun open(abi: FakeAbi, root: String) =
        (CoreClient.open(abi, root, "a".repeat(64)) as CoreResult.Success).value
}
